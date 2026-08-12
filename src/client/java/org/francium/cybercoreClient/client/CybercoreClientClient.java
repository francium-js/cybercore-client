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

/**
 * Two browsers, one per surface - the architectural fix for every "the platform stayed on the
 * screen" bug this mod used to chase.
 *
 * <p>The overlay browser sits on the transparent in-world page ({@code /nothing}) forever and is
 * the only thing ever painted over the world. The platform browser holds the actual site and is
 * only ever painted inside {@link BrowserScreen}. Neither is navigated to open or close anything:
 * opening the platform shows a browser that is already there, closing it merely stops rendering
 * it. A texture that only ever contains its own surface's frames cannot show the wrong page, so
 * there is no overlay flag, no acknowledgement protocol and no paint hold anywhere.
 */
public class CybercoreClientClient implements ClientModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger("cybercore-client");

    static final String ITEMS_PATH = "/items";
    static final String NOTHING_PATH = "/nothing";

    private static KeyMapping browserKey;

    /** Always in the world, always on the overlay page. Audible only while the screen is closed. */
    static CybercoreBrowser overlayBrowser;

    /** The platform. Lives hidden and throttled between opens, so reopening is instant. */
    static CybercoreBrowser platformBrowser;

    /** True while the in-game browser screen is open. */
    private static boolean platformShown = false;

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
            closeBrowserQuietly(overlayBrowser);
            closeBrowserQuietly(platformBrowser);
            overlayBrowser = null;
            platformBrowser = null;
            BrowserTexture.release();
            // Ours to call with MCEF: left running, the jcef helpers outlive the game.
            McefBootstrap.shutdown();
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            refreshDisplayScale(client);
            syncBrowserFrameRates();
            BrowserLoadGuard.tick();
            tickPageStateReassert();

            // Outside a world nothing reads the key queue, so anything left in it would replay on
            // the way back in and open the browser by itself.
            if (client.level == null) {
                while (browserKey.consumeClick()) {
                    // discard
                }
                deactivatePlatform();
                return;
            }

            // The invariant: the platform is shown for as long as its screen is. Screens can
            // disappear by routes that never reach removed() - dying, a kick, a server-opened
            // container - and the platform browser would otherwise stay unthrottled and audible.
            // Restating it every tick costs a boolean comparison.
            if (!(client.screen instanceof BrowserScreen)) {
                deactivatePlatform();
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

            ensureBrowsers();
            if (client.screen instanceof BrowserScreen) {
                deactivatePlatform();
                client.setScreen(null);
            } else {
                activatePlatform();
                client.setScreen(new BrowserScreen(platformBrowser));
            }
        });
    }

    private static void closeBrowserQuietly(MCEFBrowser browser) {
        if (browser == null) {
            return;
        }
        try {
            browser.close();
        } catch (Throwable t) {
            LOGGER.debug("Browser was already closed by CEF's shutdown.", t);
        }
    }

    static boolean isOurBrowser(org.cef.browser.CefBrowser browser) {
        return browser != null && (browser == overlayBrowser || browser == platformBrowser);
    }

    /**
     * Brings the hidden platform browser back: unhide, full frame rate, and one forced repaint in
     * case Chromium considers its last frame still current after WasHidden.
     */
    private static void activatePlatform() {
        if (platformShown) {
            return;
        }
        platformShown = true;
        // Frame rate first, so the unhide below already schedules frames at full rate instead of
        // finishing one last interval of the hidden cap.
        syncBrowserFrameRates();
        setWindowVisibilityQuietly(platformBrowser, true);
        notifyPlatformOpen(true);
        forceRepaint(platformBrowser);
    }

    /**
     * Idempotent: the screen's own teardown and every path that closes it land here.
     *
     * <p>Nothing is navigated - the page keeps whatever route it was on, however long ago, so
     * reopening always returns to the exact same place. The browser is merely hidden: WasHidden
     * lets Chromium throttle itself and fires the page's own visibility events, and the frame
     * rate drops to 1 as a belt-and-braces cap.
     */
    static void deactivatePlatform() {
        if (!platformShown) {
            return;
        }
        platformShown = false;
        if (platformBrowser == null) {
            return;
        }
        notifyPlatformOpen(false);
        setWindowVisibilityQuietly(platformBrowser, false);
        syncBrowserFrameRates();
    }

    private static void setWindowVisibilityQuietly(MCEFBrowser browser, boolean visible) {
        if (browser == null) {
            return;
        }
        // Maps to CEF's WasHidden for windowless browsers. Wrapped because the jcef fork's
        // behavior here is the least battle-tested part of the plan; if it throws, the frame-rate
        // throttle still carries most of the saving.
        try {
            browser.setWindowVisibility(visible);
        } catch (Throwable t) {
            LOGGER.debug("setWindowVisibility({}) failed; relying on the frame-rate throttle.",
                    visible, t);
        }
    }

    /**
     * Tells both pages whether the platform screen is open. The platform instance reads it as
     * "I am (in)visible" - flushing buffered toasts and refreshing stale data on open - and the
     * overlay instance reads it as "stay quiet, the platform is playing the sounds now".
     * WasHidden should also make Chromium fire the standard Page Visibility events on the
     * platform page, but the front-end must not depend on that working in this OSR build; this
     * bridge is the signal it can trust.
     */
    private static void notifyPlatformOpen(boolean open) {
        String script = "(function(){if(typeof window.__ccSetPlatformOpen==='function')"
                + "{window.__ccSetPlatformOpen(" + open + ");}})();";
        if (overlayBrowser != null) {
            overlayBrowser.executeJavaScript(script, overlayBrowser.getURL(), 0);
        }
        if (platformBrowser != null) {
            platformBrowser.executeJavaScript(script, platformBrowser.getURL(), 0);
        }
    }

    // ---- Once-a-second page state reassert --------------------------------------------------
    //
    // Two fire-and-forget signals, repeated so that a page that reloaded (service worker update,
    // F5) relearns them within a second. No acknowledgements, nothing depends on the answers.
    //
    // The platform-open state is the live one. The overlay flag is legacy: front-end builds from
    // before the two-browser split decide what to render off it, and the service worker can keep
    // serving such a build for one more load; telling each browser its fixed role through the old
    // channel keeps those builds working, while current builds define it as a no-op and go by
    // their ccRole instead.

    private static final int REASSERT_INTERVAL_TICKS = 20;

    private static int ticksSinceReassert = 0;

    private static void tickPageStateReassert() {
        if (++ticksSinceReassert < REASSERT_INTERVAL_TICKS) {
            return;
        }
        ticksSinceReassert = 0;
        notifyPlatformOpen(platformShown);
        sendLegacyOverlayFlag(overlayBrowser, true);
        sendLegacyOverlayFlag(platformBrowser, false);
    }

    private static void sendLegacyOverlayFlag(MCEFBrowser browser, boolean overlay) {
        if (browser == null) {
            return;
        }
        browser.executeJavaScript(
                "(function(){if(typeof window.__ccSetOverlayMode==='function')"
                        + "{window.__ccSetOverlayMode(" + overlay + ");}})();",
                browser.getURL(),
                0
        );
    }

    static boolean matchesBrowserKey(KeyEvent event) {
        return browserKey != null && !browserKey.isUnbound() && browserKey.matches(event);
    }

    private static void onBrowserBackendReady() {
        Minecraft.getInstance().execute(() -> {
            BrowserClientFlag.register();
            BrowserTextInputTracker.register();
            BrowserEscapeBridge.register();
            // Registered before the first browser exists, so even a front-end that is already
            // down when the game starts never gets to paint Chromium's error page.
            BrowserLoadGuard.register();
            ensureBrowsers();
            LOGGER.info("MCEF initialized (accelerated paint: {}). Press B to open the browser.",
                    McefBootstrap.isAcceleratedPaint());
        });
    }

    private static void ensureBrowsers() {
        if (overlayBrowser == null) {
            BrowserLoadGuard.reset();
            overlayBrowser = createBrowser(NOTHING_PATH, "overlay");
            lastAppliedZoom = 0;
        }
        if (platformBrowser == null) {
            platformShown = false;
            platformBrowser = createBrowser(ITEMS_PATH, "platform");
            lastAppliedZoom = 0;
            // Born hidden; it only becomes visible through activatePlatform().
            setWindowVisibilityQuietly(platformBrowser, false);
        }
    }

    /** The URL a browser retries or falls back to - the load guard's target. */
    static String bootUrl(MCEFBrowser browser) {
        return pageUrl(browser == platformBrowser ? ITEMS_PATH : NOTHING_PATH, roleOf(browser));
    }

    private static String roleOf(MCEFBrowser browser) {
        return browser == platformBrowser ? "platform" : "overlay";
    }

    private static String pageUrl(String path, String role) {
        return withClientParams(CybercoreConfig.getBaseUrl() + path, role);
    }

    static void reloadWithNewBaseUrl() {
        // An open screen holds a reference to the browser we are about to close.
        Minecraft client = Minecraft.getInstance();
        if (client.screen instanceof BrowserScreen) {
            client.setScreen(null);
        }
        deactivatePlatform();
        closeBrowserQuietly(overlayBrowser);
        closeBrowserQuietly(platformBrowser);
        overlayBrowser = null;
        platformBrowser = null;
        BrowserTexture.release();
        BrowserTextInputTracker.reset();
        ensureBrowsers();
    }

    /** A shared-texture frame never touches the CPU, so it may track the monitor. */
    private static final int MAX_ACCELERATED_BROWSER_FPS = 240;

    /** Every software frame is copied out of CEF and re-uploaded, so it stays cheap. */
    private static final int MAX_SOFTWARE_BROWSER_FPS = 60;

    /**
     * Cap for the hidden platform browser. Not 1: CEF schedules OSR frames on a timer, so even an
     * explicit invalidate on wake can sit out the rest of the current interval - at 1 fps that
     * showed up as a full second of frozen animations after opening the screen. 10 keeps the wake
     * latency imperceptible, and costs nothing while hidden because frames are damage-driven and
     * WasHidden already throttles the page's own animation clocks.
     */
    private static final int HIDDEN_BROWSER_FPS = 10;

    private static int lastAppliedOverlayFrameRate;
    private static int lastAppliedPlatformFrameRate;

    private static int maxFrameRate() {
        int ceiling = McefBootstrap.isAcceleratedPaint()
                ? MAX_ACCELERATED_BROWSER_FPS
                : MAX_SOFTWARE_BROWSER_FPS;
        // Frames above the monitor's refresh rate can never be seen.
        int refreshRate = Minecraft.getInstance().getWindow().getRefreshRate();
        if (refreshRate > 0) {
            ceiling = Math.min(ceiling, refreshRate);
        }
        return Math.min(ceiling, CybercoreConfig.getBrowserMaxFps());
    }

    /** Reapplies only on change - a monitor switch, or the platform being shown or hidden. */
    private static void syncBrowserFrameRates() {
        int target = maxFrameRate();
        if (overlayBrowser != null && target != lastAppliedOverlayFrameRate) {
            overlayBrowser.setWindowlessFrameRate(target);
            lastAppliedOverlayFrameRate = target;
        }
        int platformTarget = platformShown ? target : HIDDEN_BROWSER_FPS;
        if (platformBrowser != null && platformTarget != lastAppliedPlatformFrameRate) {
            platformBrowser.setWindowlessFrameRate(platformTarget);
            lastAppliedPlatformFrameRate = platformTarget;
        }
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
        if (McefBootstrap.isAcceleratedPaint()) {
            double zoom = Math.log(displayScale) / Math.log(1.2);
            if (Math.abs(zoom - lastAppliedZoom) > 0.001) {
                if (overlayBrowser != null) {
                    overlayBrowser.setZoomLevel(zoom);
                }
                if (platformBrowser != null) {
                    platformBrowser.setZoomLevel(zoom);
                }
                lastAppliedZoom = zoom;
            }
        }
    }

    /**
     * {@code CefBrowser_N.invalidate()} asks CEF for a fresh full frame; it is protected, hence the
     * reflection. Re-sending the size through {@code resize} is not a substitute - CEF answers
     * WasResized by comparing the view rect, so a resize to the size it already has can produce no
     * frame at all.
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

    private static void forceRepaint(MCEFBrowser browser) {
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

    private static final String CLEAR_CACHE_AND_RELOAD_JS =
            "(async function(){"
            + "try{if(window.caches){var ks=await caches.keys();"
            + "await Promise.all(ks.map(function(k){return caches.delete(k);}));}}catch(e){}"
            + "try{if(navigator.serviceWorker){var rs=await navigator.serviceWorker.getRegistrations();"
            + "await Promise.all(rs.map(function(r){return r.unregister();}));}}catch(e){}"
            + "location.reload();})();";

    /**
     * Ordinary reload of the platform page. The service worker updates itself and re-fetches only
     * the chunks whose hash changed, so this is what a page refresh should cost.
     */
    static void reloadBrowser() {
        if (platformBrowser == null) {
            return;
        }
        if (BrowserLoadGuard.isParked(platformBrowser)) {
            BrowserLoadGuard.loadNow(platformBrowser, bootUrl(platformBrowser));
            return;
        }
        platformBrowser.reload();
    }

    /**
     * Throws the whole browser-side cache away and reloads both pages from scratch, service worker
     * included - the escape hatch for when a bad build got itself cached, not something to do
     * routinely. The cache is one profile shared by both browsers, so both must reload to pick the
     * fresh build up.
     */
    static void hardReloadBrowser() {
        hardReload(platformBrowser);
        hardReload(overlayBrowser);
    }

    private static void hardReload(MCEFBrowser browser) {
        if (browser == null) {
            return;
        }
        if (BrowserLoadGuard.isParked(browser)) {
            BrowserLoadGuard.loadNow(browser, bootUrl(browser));
            return;
        }
        browser.executeJavaScript(CLEAR_CACHE_AND_RELOAD_JS, browser.getURL(), 0);
    }

    private static CybercoreBrowser createBrowser(String path, String role) {
        // Built by hand instead of MCEF.createBrowser, which hardwires the base class: ours is the
        // same browser plus HiDPI and richer wheel input. shared_texture is only requested when
        // the platform probe accepted it - CEF ignores an unsupported request silently.
        int frameRate = maxFrameRate();
        CybercoreBrowser b = new CybercoreBrowser(
                MCEF.INSTANCE.getClient(),
                pageUrl(path, role),
                true,
                new MCEFBrowserSettings(frameRate, McefBootstrap.isAcceleratedPaint())
        );
        b.setCloseAllowed();
        b.createImmediately();
        if ("overlay".equals(role)) {
            lastAppliedOverlayFrameRate = frameRate;
        } else {
            lastAppliedPlatformFrameRate = frameRate;
        }
        return b;
    }

    /**
     * Marks the page as ours and, since the split, tells it which of the two browsers it lives in.
     * The role is read once by the front-end and persisted per browsing session, so in-app
     * navigation (which drops query parameters) cannot lose it.
     */
    private static String withClientParams(String url, String role) {
        String separator = url.contains("?") ? "&" : "?";
        return url + separator + "isMCEFcliendMod=true&ccRole=" + role;
    }

}
