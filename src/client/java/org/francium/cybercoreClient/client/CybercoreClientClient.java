package org.francium.cybercoreClient.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.ccbluex.liquidbounce.mcef.MCEF;
import net.ccbluex.liquidbounce.mcef.cef.MCEFBrowser;
import net.ccbluex.liquidbounce.mcef.cef.MCEFBrowserSettings;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.MemoryStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CybercoreClientClient implements ClientModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger("cybercore-client");

    private static final String ITEMS_PATH = "/items";
    static final String NOTHING_PATH = "/nothing";

    private static KeyMapping browserKey;

    static CybercoreBrowser browser;

    /** True while the in-game browser screen is open. */
    private static boolean wantsPlatformPage = false;

    /** How long a closed browser keeps the page the player left it on. */
    private static final long RESUME_WINDOW_NANOS = 60_000_000_000L;

    /** When the screen was last closed; 0 before the first close and after a fresh browser. */
    private static long closedAtNanos = 0;

    private static boolean resumesLastPage() {
        return closedAtNanos != 0 && System.nanoTime() - closedAtNanos < RESUME_WINDOW_NANOS;
    }

    @Override
    public void onInitializeClient() {
        // Its own category rather than MISC: in a large modpack the miscellaneous section holds
        // dozens of entries, and a single foreign bind in the middle of it is as good as hidden.
        browserKey = KeyMappingHelper.registerKeyMapping(
                new KeyMapping(
                        "key.cybercore.browser",
                        InputConstants.Type.KEYSYM,
                        GLFW.GLFW_KEY_B,
                        KeyMapping.Category.register(
                                Identifier.fromNamespaceAndPath("cybercore-client", "cybercore"))
                )
        );

        HudElementRegistry.addLast(
                Identifier.fromNamespaceAndPath("cybercore-client", "browser_overlay"),
                new BrowserOverlay()
        );

        CybercoreServer.register();

        ClientEvents.register();

        PlayerPositionBridge.register();

        McefBootstrap.start(successful -> {
            if (successful) {
                onBrowserBackendReady();
            } else {
                LOGGER.error("MCEF failed to initialize, the browser is unavailable.");
            }
        });

        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            if (browser != null) {
                try {
                    browser.close();
                } catch (Throwable t) {
                    LOGGER.debug("Browser was already closed by CEF's shutdown.", t);
                }
                browser = null;
            }
            BrowserTexture.release();
            // Ours to call with MCEF: left running, the jcef helpers outlive the game.
            McefBootstrap.shutdown();
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            refreshDisplayScale(client);
            BrowserLoadGuard.tick();
            BrowserOverlayMode.tick();

            tickOverlayPaintHold();

            // Outside a world nothing reads the key queue, so anything left in it would replay on
            // the way back in and open the browser by itself.
            if (client.level == null) {
                while (browserKey.consumeClick()) {
                    // discard
                }
                deactivateBrowser();
                return;
            }

            // The invariant: overlay mode is on for as long as the screen is not. Screens can
            // disappear by routes that never reach removed() - dying, a kick, a server-opened
            // container - and the platform would otherwise be left drawn over the world with input
            // going to the game. Restating it every tick costs a boolean comparison.
            if (!(client.screen instanceof BrowserScreen)) {
                deactivateBrowser();
            }

            // One toggle per tick, however many presses queued up - acting on each in turn made an
            // even number cancel itself out.
            boolean toggleRequested = false;
            while (browserKey.consumeClick()) {
                toggleRequested = true;
            }
            if (!toggleRequested) {
                return;
            }

            if (!McefBootstrap.isReady()) {
                LOGGER.warn("MCEF is not ready yet, cannot open browser.");
                return;
            }

            ensureBrowser();
            if (client.screen instanceof BrowserScreen) {
                deactivateBrowser();
                client.setScreen(null);
            } else {
                // Reopening soon after a close resumes the page the player left, because closing no
                // longer navigates anywhere - the route is still sitting there untouched. After the
                // window it goes back to the front page, on the assumption that a session that old
                // is a new errand rather than a continued one.
                if (!resumesLastPage()) {
                    navigate(browser, ITEMS_PATH);
                }
                wantsPlatformPage = true;
                BrowserOverlayMode.set(false);
                client.setScreen(new BrowserScreen(browser));
            }
        });
    }

    /**
     * Called by {@link BrowserOverlayMode} when the page confirms it has gone to the in-world layer.
     *
     * <p>What is left on screen there barely moves, so whatever CEF painted last is what stays until
     * something else changes - and a single missing frame would leave the platform hanging with
     * nothing to overwrite it. Hence an explicit repaint rather than an assumed one.
     */
    static void onOverlayConfirmed() {
        // Acknowledgements arrive on CEF's thread; the counters belong to the one that ticks them.
        Minecraft.getInstance().execute(() -> {
            overlayAckNanos = System.nanoTime();
            ticksSinceOverlayAck = 0;
            overlayHoldWarned = false;
            forceRepaint();
        });
    }

    /**
     * The moment the page confirmed the overlay; 0 once the hold has been released. The gate stays
     * shut until a frame verifiably arrived after this - the confirmation is a console message and
     * outruns the picture, and a frame the delivery filter silently drops would otherwise leave
     * the platform in the texture with nothing to overwrite it. While the hold lasts, the repaint
     * request is repeated: asking once and assuming is what used to make that state permanent.
     */
    private static long overlayAckNanos = 0;

    private static int ticksSinceOverlayAck = 0;
    private static boolean overlayHoldWarned = false;

    /** In-flight frames of the old page can outrun the ack; a floor keeps them from counting. */
    private static final int OVERLAY_HOLD_MIN_TICKS = 2;

    private static final int OVERLAY_HOLD_RETRY_TICKS = 10;
    private static final int OVERLAY_HOLD_WARN_TICKS = 60;

    static boolean isOverlayPaintHeld() {
        return overlayAckNanos != 0;
    }

    /**
     * One more forced frame shortly after the hold releases. The release trusts "a frame arrived
     * after the ack", and a frame of the old page still in flight can satisfy that; this turns
     * that worst case into a sub-second flicker instead of a picture that stays.
     */
    private static int postReleaseRepaintTicks = 0;

    private static final int POST_RELEASE_REPAINT_TICKS = 5;

    private static void tickOverlayPaintHold() {
        if (browser == null) {
            return;
        }
        if (postReleaseRepaintTicks > 0 && --postReleaseRepaintTicks == 0) {
            forceRepaint();
        }
        if (overlayAckNanos == 0) {
            return;
        }
        ticksSinceOverlayAck++;

        boolean frameArrived = browser.frameDeliveredNanos() > overlayAckNanos;
        if (frameArrived && ticksSinceOverlayAck >= OVERLAY_HOLD_MIN_TICKS) {
            overlayAckNanos = 0;
            postReleaseRepaintTicks = POST_RELEASE_REPAINT_TICKS;
            return;
        }

        if (!frameArrived && ticksSinceOverlayAck % OVERLAY_HOLD_RETRY_TICKS == 0) {
            forceRepaint();
        }
        if (!frameArrived && ticksSinceOverlayAck == OVERLAY_HOLD_WARN_TICKS && !overlayHoldWarned) {
            overlayHoldWarned = true;
            LOGGER.warn("No browser frame has arrived since the page confirmed the overlay - "
                    + "still asking for repaints; nothing is drawn over the world meanwhile.");
        }
    }

    /**
     * {@code CefBrowser_N.invalidate()} asks CEF for a fresh full frame; it is protected, hence the
     * reflection. Re-sending the size through {@code resize} is not a substitute - CEF answers
     * WasResized by comparing the view rect, so a resize to the size it already has can produce no
     * frame at all, which is precisely the case this has to cover.
     */
    private static final java.lang.reflect.Method INVALIDATE = resolveInvalidate();

    private static java.lang.reflect.Method resolveInvalidate() {
        try {
            java.lang.reflect.Method method =
                    Class.forName("org.cef.browser.CefBrowser_N").getDeclaredMethod("invalidate");
            method.setAccessible(true);
            return method;
        } catch (Throwable t) {
            LOGGER.warn("CefBrowser_N.invalidate() is not reachable; falling back to resize.", t);
            return null;
        }
    }

    private static void forceRepaint() {
        if (browser == null) {
            return;
        }
        if (INVALIDATE != null) {
            try {
                INVALIDATE.invoke(browser);
                return;
            } catch (Throwable t) {
                LOGGER.warn("invalidate() failed, falling back to resize.", t);
            }
        }
        var window = Minecraft.getInstance().getWindow();
        browser.resize(Math.max(1, window.getWidth()), Math.max(1, window.getHeight()));
    }

    /**
     * Idempotent: the screen's own teardown and the paths that close it both land here.
     *
     * <p>Nothing is navigated - the page keeps whatever route it was on, so reopening returns to
     * the same place and nothing that moves the router can put the platform back over the world.
     */
    static void deactivateBrowser() {
        if (!wantsPlatformPage) {
            return;
        }
        wantsPlatformPage = false;
        closedAtNanos = System.nanoTime();
        BrowserOverlayMode.set(true);
    }

    static boolean matchesBrowserKey(KeyEvent event) {
        return browserKey != null && !browserKey.isUnbound() && browserKey.matches(event);
    }

    private static void onBrowserBackendReady() {
        Minecraft.getInstance().execute(() -> {
            BrowserClientFlag.register();
            BrowserRouteTracker.register();
            BrowserOverlayMode.register();
            BrowserTextInputTracker.register();
            BrowserEscapeBridge.register();
            // Registered before the first browser exists, so even a front-end that is already
            // down when the game starts never gets to paint Chromium's error page.
            BrowserLoadGuard.register();
            ensureBrowser();
            LOGGER.info("MCEF initialized (accelerated paint: {}). Press B to open the browser.",
                    McefBootstrap.isAcceleratedPaint());
        });
    }

    private static void ensureBrowser() {
        if (browser == null) {
            wantsPlatformPage = false;
            // A fresh browser opens on the overlay page - there is no page to resume onto.
            closedAtNanos = 0;
            BrowserLoadGuard.reset();
            BrowserRouteTracker.reset();
            BrowserOverlayMode.reset();
            browser = createBrowser(CybercoreConfig.getBaseUrl() + NOTHING_PATH);
            lastAppliedZoom = 0;
        }
    }

    /**
     * Absolute URL to fall back on when the browser has to be loaded outright - the load guard's
     * retry target. Not where the page is: with the screen closed it keeps whatever route the
     * player left it on, and only a reload from scratch has to pick somewhere to start.
     */
    static String currentPageUrl() {
        return pageUrl(wantsPlatformPage ? ITEMS_PATH : NOTHING_PATH);
    }

    /**
     * The pre-flag way of getting the platform off the screen, kept for pages that cannot be told
     * anything else. {@link BrowserOverlayMode} calls this only after giving up on being answered.
     */
    static void navigateToOverlayPage() {
        if (browser != null) {
            navigate(browser, NOTHING_PATH);
        }
    }

    private static String pageUrl(String path) {
        return withVanishBg(CybercoreConfig.getBaseUrl() + path);
    }

    static void reloadWithNewBaseUrl() {
        if (browser == null) {
            return;
        }
        // An open screen holds a reference to the browser we are about to close.
        Minecraft client = Minecraft.getInstance();
        if (client.screen instanceof BrowserScreen) {
            client.setScreen(null);
        }
        try {
            browser.close();
        } catch (Throwable t) {
            LOGGER.debug("Browser was already closed while applying the new base URL.", t);
        }
        browser = null;
        wantsPlatformPage = false;
        BrowserTexture.release();
        BrowserTextInputTracker.reset();
        ensureBrowser();
    }

    /** A shared-texture frame never touches the CPU, so it may track the monitor. */
    private static final int MAX_ACCELERATED_BROWSER_FPS = 240;

    /** Every software frame is copied out of CEF and re-uploaded, so it stays cheap. */
    private static final int MAX_SOFTWARE_BROWSER_FPS = 60;

    /** Applied once at creation: re-arming CEF's BeginFrame source mid-flight can drop a frame. */
    private static int maxFrameRate() {
        int ceiling = McefBootstrap.isAcceleratedPaint()
                ? MAX_ACCELERATED_BROWSER_FPS
                : MAX_SOFTWARE_BROWSER_FPS;
        return Math.min(ceiling, CybercoreConfig.getBrowserMaxFps());
    }

    /**
     * The window's content scale, cached once a tick. CybercoreBrowser reports it to Chromium as
     * the device scale factor and converts sizes and mouse coordinates against it - a real HiDPI
     * setup, replacing the old zoom hack that only enlarged a 1x layout and left retina displays
     * soft.
     */
    private static volatile float displayScale = 1f;

    static float displayScale() {
        return displayScale;
    }

    private static double lastAppliedZoom = 0;

    private static void refreshDisplayScale(Minecraft client) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var sx = stack.mallocFloat(1);
            var sy = stack.mallocFloat(1);
            GLFW.glfwGetWindowContentScale(client.getWindow().handle(), sx, sy);
            float scale = sx.get(0);
            if (scale > 0f) {
                displayScale = scale;
            }
        }

        // The accelerated path runs at scale 1 (see CybercoreBrowser) and falls back on zoom for
        // OS display scaling - the pre-HiDPI behavior, kept because it is the one that works there.
        if (browser != null && McefBootstrap.isAcceleratedPaint()) {
            double zoom = Math.log(displayScale) / Math.log(1.2);
            if (Math.abs(zoom - lastAppliedZoom) > 0.001) {
                browser.setZoomLevel(zoom);
                lastAppliedZoom = zoom;
            }
        }
    }

    private static final String CLEAR_CACHE_AND_RELOAD_JS =
            "(async function(){"
            + "try{if(window.caches){var ks=await caches.keys();"
            + "await Promise.all(ks.map(function(k){return caches.delete(k);}));}}catch(e){}"
            + "try{if(navigator.serviceWorker){var rs=await navigator.serviceWorker.getRegistrations();"
            + "await Promise.all(rs.map(function(r){return r.unregister();}));}}catch(e){}"
            + "location.reload();})();";

    /**
     * Ordinary reload. The service worker updates itself and re-fetches only the chunks whose hash
     * changed, so this is what a page refresh should cost.
     */
    static void reloadBrowser() {
        if (browser == null) {
            return;
        }
        if (BrowserLoadGuard.isParked()) {
            BrowserLoadGuard.loadNow(browser, currentPageUrl());
            return;
        }
        browser.reload();
    }

    /**
     * Throws the whole browser-side cache away and reloads from scratch, service worker included -
     * the escape hatch for when a bad build got itself cached, not something to do routinely.
     */
    static void hardReloadBrowser() {
        if (browser == null) {
            return;
        }
        if (BrowserLoadGuard.isParked()) {
            BrowserLoadGuard.loadNow(browser, currentPageUrl());
            return;
        }
        browser.executeJavaScript(CLEAR_CACHE_AND_RELOAD_JS, browser.getURL(), 0);
    }

    private static void navigate(MCEFBrowser b, String path) {
        String absolute = pageUrl(path);

        // Parked on the blank page (site was down): there is no app to route, so go straight
        // for a real load - which doubles as an instant retry when the player presses B.
        if (BrowserLoadGuard.isParked()) {
            BrowserLoadGuard.loadNow(b, absolute);
            return;
        }

        String target = withVanishBg(path);
        // The bridge is only preferred, never trusted: it is absent until React has mounted, and a
        // router it still points at may have been torn down under it. Anything other than a clean
        // call falls through to the document load, which no front-end state can swallow.
        String js =
                "(function(){var p=" + jsString(target) + ";"
                + "try{if(typeof window.__ccNavigate==='function'){window.__ccNavigate(p);return;}}"
                + "catch(e){}"
                + "location.href=" + jsString(absolute) + ";})();";
        b.executeJavaScript(js, b.getURL(), 0);
    }

    private static String jsString(String s) {
        return "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    private static CybercoreBrowser createBrowser(String url) {
        // Built by hand instead of MCEF.createBrowser, which hardwires the base class: ours is the
        // same browser plus a frame-delivery timestamp, which the overlay's paint gate relies on.
        // shared_texture is only requested when the platform probe accepted it - CEF ignores an
        // unsupported request silently.
        CybercoreBrowser b = new CybercoreBrowser(
                MCEF.INSTANCE.getClient(),
                withVanishBg(url),
                true,
                new MCEFBrowserSettings(maxFrameRate(), McefBootstrap.isAcceleratedPaint())
        );
        b.setCloseAllowed();
        b.createImmediately();
        return b;
    }

    private static String withVanishBg(String url) {
        String separator = url.contains("?") ? "&" : "?";
        return url + separator + "isMCEFcliendMod=true";
    }

}
