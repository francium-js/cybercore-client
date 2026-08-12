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
 * One browser, one app instance, and a single boolean between the two worlds.
 *
 * <p>The page always hosts everything at once: the in-world layer (toasts, glitch effects) lives
 * outside the router, and the platform UI sits inside a wrapper the front-end collapses with
 * {@code content-visibility: hidden} whenever the mod says the screen is closed. Opening and
 * closing the platform is therefore a CSS flip, not a navigation: no intermediate frames exist
 * for a race to show, toast state is shared by construction, and the wrapper lives OUTSIDE the
 * routed tree, so no redirect or history move can ever put the platform back over the game.
 *
 * <p>The browser itself is never hidden, throttled or navigated by the mod. The worst case on
 * close is the platform lingering in the texture for a frame or two - the picture the player was
 * looking at a moment ago - which is why this design needs no acknowledgement protocol and no
 * paint hold.
 */
public class CybercoreClientClient implements ClientModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger("cybercore-client");

    static final String ITEMS_PATH = "/items";

    private static KeyMapping browserKey;

    static CybercoreBrowser browser;

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
            closeBrowserQuietly(browser);
            browser = null;
            BrowserTexture.release();
            // Ours to call with MCEF: left running, the jcef helpers outlive the game.
            McefBootstrap.shutdown();
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            refreshDisplayScale(client);
            syncBrowserFrameRate();
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
            // container - and the page would otherwise keep the platform visible in a texture
            // that is now painted over the world. Restating it every tick costs a boolean check.
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

            ensureBrowser();
            if (client.screen instanceof BrowserScreen) {
                deactivatePlatform();
                client.setScreen(null);
            } else {
                activatePlatform();
                client.setScreen(new BrowserScreen(browser));
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

    static boolean isOurBrowser(org.cef.browser.CefBrowser candidate) {
        return candidate != null && candidate == browser;
    }

    private static void activatePlatform() {
        if (platformShown) {
            return;
        }
        platformShown = true;
        notifyPlatformOpen(true);
    }

    /**
     * Idempotent: the screen's own teardown and every path that closes it land here.
     *
     * <p>Nothing else happens - no navigation, no hiding, no throttling. The page keeps whatever
     * route it was on (reopening returns to the exact same place), collapses the platform wrapper
     * itself, and keeps painting the in-world layer.
     */
    static void deactivatePlatform() {
        if (!platformShown) {
            return;
        }
        platformShown = false;
        notifyPlatformOpen(false);
    }

    /**
     * Tells the page whether the platform screen is open. The front-end collapses or reveals the
     * platform wrapper off this flag, flushes stale data on open, and quiets the platform's own
     * sounds while closed. Fire-and-forget: the flip is idempotent CSS, reasserted once a second,
     * so a page that reloaded relearns it within a second and nothing needs to be acknowledged.
     */
    private static void notifyPlatformOpen(boolean open) {
        if (browser == null) {
            return;
        }
        browser.executeJavaScript(
                "(function(){if(typeof window.__ccSetPlatformOpen==='function')"
                        + "{window.__ccSetPlatformOpen(" + open + ");}})();",
                browser.getURL(),
                0
        );
    }

    // ---- Once-a-second page state reassert --------------------------------------------------
    //
    // Two fire-and-forget signals, repeated so that a page that reloaded (service worker update,
    // F5) relearns them within a second. The platform-open flag is the live one; the overlay flag
    // is legacy for front-end builds from before the platform-open bridge, which the service
    // worker can keep serving for one more load - current builds map it onto the same flag.

    private static final int REASSERT_INTERVAL_TICKS = 20;

    private static int ticksSinceReassert = 0;

    private static void tickPageStateReassert() {
        if (++ticksSinceReassert < REASSERT_INTERVAL_TICKS) {
            return;
        }
        ticksSinceReassert = 0;
        notifyPlatformOpen(platformShown);
        sendLegacyOverlayFlag(!platformShown);
        syncBrowserZoom(true);
    }

    private static void sendLegacyOverlayFlag(boolean overlay) {
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
            BrowserScaleBridge.register();
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
            platformShown = false;
            BrowserLoadGuard.reset();
            // Boot straight on the platform route: the page pre-renders the whole app inside the
            // collapsed wrapper, so the very first open is as instant as every later one. What is
            // painted over the world meanwhile is decided by the platform-open flag, not by the
            // route - a fresh page defaults to "closed" until told otherwise.
            browser = createBrowser(ITEMS_PATH);
            lastAppliedZoom = 0;
        }
    }

    /** The URL a browser retries or falls back to - the load guard's target. */
    static String bootUrl(MCEFBrowser ignored) {
        return pageUrl(ITEMS_PATH);
    }

    private static String pageUrl(String path) {
        return withClientParams(CybercoreConfig.getBaseUrl() + path);
    }

    static void reloadWithNewBaseUrl() {
        // An open screen holds a reference to the browser we are about to close.
        Minecraft client = Minecraft.getInstance();
        if (client.screen instanceof BrowserScreen) {
            client.setScreen(null);
        }
        deactivatePlatform();
        closeBrowserQuietly(browser);
        browser = null;
        BrowserTexture.release();
        BrowserTextInputTracker.reset();
        ensureBrowser();
    }

    /** CEF's own ceiling for the windowless frame rate. */
    private static final int MAX_BROWSER_FPS = 240;

    /** Used until GLFW reports a real refresh rate for the current monitor. */
    private static final int FALLBACK_BROWSER_FPS = 60;

    private static int lastAppliedFrameRate;

    /**
     * Always the monitor's own refresh rate - no software cap and no config knob. Frames above it
     * can never be seen, frames below it are visible judder.
     */
    private static int maxFrameRate() {
        int refreshRate = Minecraft.getInstance().getWindow().getRefreshRate();
        if (refreshRate <= 0) {
            return FALLBACK_BROWSER_FPS;
        }
        return Math.min(refreshRate, MAX_BROWSER_FPS);
    }

    /** Reapplies only when the window lands on a monitor with a different refresh rate. */
    private static void syncBrowserFrameRate() {
        if (browser == null) {
            return;
        }
        int target = maxFrameRate();
        if (target != lastAppliedFrameRate) {
            browser.setWindowlessFrameRate(target);
            lastAppliedFrameRate = target;
        }
    }

    /**
     * The window's content scale, cached once a tick. CybercoreBrowser reports it to Chromium as
     * the device scale factor and converts sizes and mouse coordinates against it - a real HiDPI
     * setup, replacing the old zoom hack that only enlarged a 1x layout and left retina displays
     * soft.
     */
    private static volatile float contentScale = 1f;

    /**
     * The scale CybercoreBrowser reports to Chromium and converts sizes and mouse coordinates
     * against. Deliberately NOT multiplied by the player's manual scale: this jcef build has no
     * notifyScreenInfoChanged binding, so Chromium reads the device scale factor once at browser
     * creation and never again - feeding a different value into the DIP conversion mid-life
     * desyncs mouse coordinates from what Chromium believes and bricks the page. The player's
     * multiplier rides on page zoom instead (see syncBrowserZoom), which does work on a live
     * browser.
     */
    static float displayScale() {
        return contentScale;
    }

    /**
     * Persists the scale the player picked in the site's settings (see BrowserScaleBridge).
     * {@link #syncBrowserZoom} picks it up on the next tick.
     */
    static void applyUserBrowserScale(int percent) {
        int clamped = Math.max(CybercoreConfig.MIN_BROWSER_SCALE_PERCENT,
                Math.min(CybercoreConfig.MAX_BROWSER_SCALE_PERCENT, percent));
        if (clamped == CybercoreConfig.getBrowserScalePercent()) {
            return;
        }
        CybercoreConfig.setBrowserScalePercent(clamped);
    }

    private static double lastAppliedZoom = 0;

    private static void refreshDisplayScale(Minecraft client) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var sx = stack.mallocFloat(1);
            var sy = stack.mallocFloat(1);
            GLFW.glfwGetWindowContentScale(client.getWindow().handle(), sx, sy);
            float scale = sx.get(0);
            if (scale > 0f) {
                contentScale = scale;
            }
        }

        syncBrowserZoom(false);
    }

    /**
     * Page zoom is where the player's manual scale lives - the one scaling knob this jcef build
     * can turn on a live browser, and it is exactly what ctrl+/- does in a desktop browser: the
     * layout rescales, hit-testing follows, and the raster stays at the device scale, so nothing
     * goes soft. The accelerated path additionally folds the OS scale in, since it runs Chromium
     * at device scale 1 (see CybercoreBrowser).
     *
     * @param force reapply even if unchanged - the once-a-second reassert, in case a reload or
     *              navigation dropped the per-host zoom entry.
     */
    private static void syncBrowserZoom(boolean force) {
        if (browser == null) {
            return;
        }
        double userScale = CybercoreConfig.getBrowserScalePercent() / 100.0;
        double targetScale = McefBootstrap.isAcceleratedPaint() ? contentScale * userScale : userScale;
        double zoom = Math.log(targetScale) / Math.log(1.2);
        if (force || Math.abs(zoom - lastAppliedZoom) > 0.001) {
            browser.setZoomLevel(zoom);
            lastAppliedZoom = zoom;
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
        if (BrowserLoadGuard.isParked(browser)) {
            BrowserLoadGuard.loadNow(browser, bootUrl(browser));
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
        if (BrowserLoadGuard.isParked(browser)) {
            BrowserLoadGuard.loadNow(browser, bootUrl(browser));
            return;
        }
        browser.executeJavaScript(CLEAR_CACHE_AND_RELOAD_JS, browser.getURL(), 0);
    }

    private static CybercoreBrowser createBrowser(String path) {
        // Built by hand instead of MCEF.createBrowser, which hardwires the base class: ours is the
        // same browser plus HiDPI and richer wheel input. shared_texture is only requested when
        // the platform probe accepted it - CEF ignores an unsupported request silently.
        int frameRate = maxFrameRate();
        CybercoreBrowser b = new CybercoreBrowser(
                MCEF.INSTANCE.getClient(),
                pageUrl(path),
                true,
                new MCEFBrowserSettings(frameRate, McefBootstrap.isAcceleratedPaint())
        );
        b.setCloseAllowed();
        b.createImmediately();
        lastAppliedFrameRate = frameRate;
        return b;
    }

    private static String withClientParams(String url) {
        String separator = url.contains("?") ? "&" : "?";
        return url + separator + "isMCEFcliendMod=true";
    }

}
