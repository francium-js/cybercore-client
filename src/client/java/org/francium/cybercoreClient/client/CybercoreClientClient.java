package org.francium.cybercoreClient.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.ccbluex.liquidbounce.mcef.MCEF;
import net.ccbluex.liquidbounce.mcef.cef.MCEFBrowser;
import net.ccbluex.liquidbounce.mcef.cef.MCEFBrowserSettings;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.MemoryStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Two browsers, one page each, and no shared texture between the two worlds.
 *
 * <p>The overlay browser hosts nothing but the floating in-world layer (toasts, glitch effects)
 * and is painted on top of everything the game shows, always - it never navigates, never
 * collapses, never carries the platform. The UI browser hosts the whole platform, permanently
 * expanded, and is painted exclusively while {@link BrowserScreen} is open. "The platform stuck
 * over the world" is thereby impossible by construction: the platform's texture is simply never
 * drawn outside its screen, however the frames flow.
 *
 * <p>Clicks over a toast are routed to the overlay browser (the front-end reports live toast
 * rectangles - see BrowserToastRectsBridge), everything else goes to the UI browser.
 */
public class CybercoreClientClient implements ClientModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger("cybercore-client");

    static final String ITEMS_PATH = "/items";

    /** The overlay page parks its router here; the layer it paints lives outside the router. */
    static final String NOTHING_PATH = "/nothing";

    private static KeyMapping browserKey;

    /** The platform. Painted only while BrowserScreen is open. */
    static CybercoreBrowser uiBrowser;

    /** The notification layer. Painted over everything, always. */
    static CybercoreBrowser overlayBrowser;

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

        // The overlay browser is drawn from the tail of GameRenderer's GUI extraction (see
        // GameRendererMixin), so toasts stay visible over the HUD, chat, menus, the title screen
        // and loading screens alike - and over the platform screen itself.

        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            // The main menu is the other place the platform may open from. Key mappings never
            // fire while a screen is up (the screen owns the keyboard), so the title screen gets
            // its own key hook; in chat, B stays a letter.
            if (screen instanceof TitleScreen) {
                ScreenKeyboardEvents.allowKeyPress(screen).register((s, event) -> {
                    if (!matchesBrowserKey(event)) {
                        return true;
                    }
                    toggleBrowserScreen(client);
                    return false;
                });
            }

            // Toasts hang over every screen, so a click on one must reach the overlay browser
            // instead of the screen under it - chat, inventory, menus. BrowserScreen routes its
            // own input (hover included) and is left alone here.
            if (!(screen instanceof BrowserScreen)) {
                ScreenMouseEvents.allowMouseClick(screen).register((s, event) -> {
                    if (!isToastAtGui(event.x(), event.y()) || overlayBrowser == null) {
                        return true;
                    }
                    overlayBrowser.sendMousePress(toFbX(event.x()), toFbY(event.y()), event.button());
                    return false;
                });
                ScreenMouseEvents.allowMouseRelease(screen).register((s, event) -> {
                    if (!isToastAtGui(event.x(), event.y()) || overlayBrowser == null) {
                        return true;
                    }
                    overlayBrowser.sendMouseRelease(toFbX(event.x()), toFbY(event.y()), event.button());
                    return false;
                });
            }
        });

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
            closeBrowserQuietly(uiBrowser);
            closeBrowserQuietly(overlayBrowser);
            uiBrowser = null;
            overlayBrowser = null;
            BrowserTexture.release();
            // Ours to call with MCEF: left running, the jcef helpers outlive the game.
            McefBootstrap.shutdown();
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            refreshDisplayScale(client);
            syncBrowserFrameRate();
            BrowserLoadGuard.tick();
            tickPageStateReassert();

            // The invariant: the platform is shown for as long as its screen is. Screens can
            // disappear by routes that never reach removed() - dying, a kick, a server-opened
            // container, quitting to the menu. The flag only drives the page's sounds and
            // refetches now - visibility is the mod's own draw decision - but stale "open"
            // would keep the platform's music playing under the game.
            if (!(client.screen instanceof BrowserScreen)) {
                deactivatePlatform();
            }

            // One toggle per tick, however many presses queued up - acting on each in turn made an
            // even number cancel itself out. Clicks only ever register in-world with no screen
            // open (the title screen path goes through its own key hook); anything queued outside
            // a world is drained here so it cannot replay on the way back in.
            boolean toggleRequested = false;
            while (browserKey.consumeClick()) {
                toggleRequested = true;
            }
            if (!toggleRequested || client.level == null) {
                return;
            }

            toggleBrowserScreen(client);
        });
    }

    private static void toggleBrowserScreen(Minecraft client) {
        if (!McefBootstrap.isReady()) {
            LOGGER.warn("MCEF is not ready yet, cannot open browser.");
            return;
        }

        ensureBrowsers();
        if (client.screen instanceof BrowserScreen) {
            deactivatePlatform();
            // Outside a world this reopens the title screen by itself - vanilla setScreen(null)
            // falls back to it whenever there is no level to return to.
            client.setScreen(null);
        } else {
            activatePlatform();
            client.setScreen(new BrowserScreen(uiBrowser));
        }
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
        return isUiBrowser(candidate) || isOverlayBrowser(candidate);
    }

    static boolean isUiBrowser(org.cef.browser.CefBrowser candidate) {
        return candidate != null && candidate == uiBrowser;
    }

    static boolean isOverlayBrowser(org.cef.browser.CefBrowser candidate) {
        return candidate != null && candidate == overlayBrowser;
    }

    private static void activatePlatform() {
        if (platformShown) {
            return;
        }
        platformShown = true;
        LOGGER.info("Platform screen opened.");
        notifyPlatformOpen(true);
    }

    /**
     * Idempotent: the screen's own teardown and every path that closes it land here. Purely a
     * page-side affair now (sounds, refetches) - what is painted over the world is decided by
     * the mod alone, and the UI browser's texture simply is not.
     */
    static void deactivatePlatform() {
        if (!platformShown) {
            return;
        }
        platformShown = false;
        LOGGER.info("Platform screen closed.");
        notifyPlatformOpen(false);
    }

    /**
     * Tells the platform page whether its screen is open - it gates the music loop and refreshes
     * stale data on open. Fire-and-forget, reasserted once a second for pages that reloaded.
     */
    private static void notifyPlatformOpen(boolean open) {
        if (uiBrowser == null) {
            return;
        }
        uiBrowser.executeJavaScript(
                "(function(){if(typeof window.__ccSetPlatformOpen==='function')"
                        + "{window.__ccSetPlatformOpen(" + open + ");}})();",
                uiBrowser.getURL(),
                0
        );
    }

    private static final int REASSERT_INTERVAL_TICKS = 20;

    private static int ticksSinceReassert = 0;

    private static void tickPageStateReassert() {
        if (++ticksSinceReassert < REASSERT_INTERVAL_TICKS) {
            return;
        }
        ticksSinceReassert = 0;
        notifyPlatformOpen(platformShown);
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
            BrowserConsoleLog.register();
            BrowserCrashGuard.register();
            BrowserToastRectsBridge.register();
            BrowserAccelBridge.register();
            registerZoomLoadHandler();
            // Registered before the first browser exists, so even a front-end that is already
            // down when the game starts never gets to paint Chromium's error page.
            BrowserLoadGuard.register();
            ensureBrowsers();
            LOGGER.info("MCEF initialized (accelerated paint: {}). Press B to open the browser.",
                    McefBootstrap.isAcceleratedPaint());
        });
    }

    private static void ensureBrowsers() {
        if (uiBrowser == null) {
            platformShown = false;
            // Boots straight on the platform route, permanently expanded: the very first open is
            // as instant as every later one, and state (route, scroll, forms) survives closes.
            uiBrowser = createBrowser(platformBootUrl());
        }
        if (overlayBrowser == null) {
            BrowserToastRectsBridge.reset();
            overlayBrowser = createBrowser(overlayBootUrl());
        }
    }

    /** The URL a browser retries or falls back to - the load guard's target. */
    static String bootUrl(MCEFBrowser browser) {
        return isOverlayBrowser(browser) ? overlayBootUrl() : platformBootUrl();
    }

    private static String platformBootUrl() {
        return withClientParams(CybercoreConfig.getBaseUrl() + ITEMS_PATH) + "&ccRole=platform";
    }

    private static String overlayBootUrl() {
        return withClientParams(CybercoreConfig.getBaseUrl() + NOTHING_PATH) + "&ccRole=overlay";
    }

    static void reloadWithNewBaseUrl() {
        recreateBrowsers();
    }

    /** Full teardown and boot of fresh browsers - for changes fixed at creation time. */
    private static void recreateBrowsers() {
        // An open screen holds a reference to the browser we are about to close.
        Minecraft client = Minecraft.getInstance();
        if (client.screen instanceof BrowserScreen) {
            client.setScreen(null);
        }
        deactivatePlatform();
        closeBrowserQuietly(uiBrowser);
        closeBrowserQuietly(overlayBrowser);
        uiBrowser = null;
        overlayBrowser = null;
        BrowserTexture.release();
        BrowserTextInputTracker.reset();
        BrowserToastRectsBridge.reset();
        ensureBrowsers();
    }

    /**
     * The GPU/CPU frame choice from the site's settings (see BrowserAccelBridge). The path is
     * fixed at browser creation, so a change means persisting and recreating both browsers.
     */
    static void applyGpuFrames(boolean enabled) {
        if (enabled == CybercoreConfig.isAcceleratedPaintAllowed()) {
            return;
        }
        CybercoreConfig.setAcceleratedPaintAllowed(enabled);
        LOGGER.info("GPU-shared frames switched {} from the site settings - recreating browsers.",
                enabled ? "on" : "off");
        McefBootstrap.reapplyAccelerationSupport();
        recreateBrowsers();
    }

    // ---- Toast hit-testing --------------------------------------------------------------------
    //
    // The overlay page reports the live bounding boxes of its toasts (BrowserToastRectsBridge) in
    // its own client coordinates, which are exactly the coordinates the mod feeds browsers as
    // mouse positions (framebuffer pixels through the same DIP conversion). A click inside any of
    // them belongs to a toast; everything else belongs to whatever is underneath.

    static boolean isToastAtGui(double guiX, double guiY) {
        return isToastAtFb(toFbX(guiX), toFbY(guiY));
    }

    static boolean isToastAtFb(int fbX, int fbY) {
        return BrowserToastRectsBridge.hit(
                CybercoreBrowser.toBrowserCoord(fbX),
                CybercoreBrowser.toBrowserCoord(fbY));
    }

    static int toFbX(double guiX) {
        return (int) (guiX * Minecraft.getInstance().getWindow().getGuiScale());
    }

    static int toFbY(double guiY) {
        return (int) (guiY * Minecraft.getInstance().getWindow().getGuiScale());
    }

    /** CEF's own ceiling for the windowless frame rate. */
    private static final int MAX_BROWSER_FPS = 240;

    /**
     * The CPU path's ceiling. Every software frame is a full readback + copy + texture upload on
     * the render thread (~15 MB at 1440p); chasing a 180 Hz monitor there starves the game and
     * delivers jittery frames - a steady 60 both feels smoother and costs a third of the work.
     * The GPU path hands frames over as handles and can afford the full refresh rate.
     */
    private static final int SOFTWARE_MAX_FPS = 60;

    /** Used until GLFW reports a real refresh rate for the current monitor. */
    private static final int FALLBACK_BROWSER_FPS = 60;

    private static int lastAppliedFrameRate;

    /**
     * The monitor's own refresh rate, capped by what the active rendering path can sustain.
     * Frames above the refresh rate can never be seen, frames below it are visible judder.
     */
    private static int maxFrameRate() {
        int pathCap = McefBootstrap.isAcceleratedPaint() ? MAX_BROWSER_FPS : SOFTWARE_MAX_FPS;
        int refreshRate = Minecraft.getInstance().getWindow().getRefreshRate();
        if (refreshRate <= 0) {
            return Math.min(FALLBACK_BROWSER_FPS, pathCap);
        }
        return Math.min(refreshRate, pathCap);
    }

    /** Reapplies only when the window lands on a monitor with a different refresh rate. */
    private static void syncBrowserFrameRate() {
        if (uiBrowser == null) {
            return;
        }
        int target = maxFrameRate();
        if (target != lastAppliedFrameRate) {
            uiBrowser.setWindowlessFrameRate(target);
            if (overlayBrowser != null) {
                overlayBrowser.setWindowlessFrameRate(target);
            }
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
     * {@link #syncBrowserZoom} picks it up on the next tick, for both browsers - toasts scale
     * together with the platform.
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

        syncBrowserZoom();
    }

    /**
     * Page zoom is where the player's manual scale lives - the one scaling knob this jcef build
     * can turn on a live browser, and it is exactly what ctrl+/- does in a desktop browser: the
     * layout rescales, hit-testing follows, and the raster stays at the device scale, so nothing
     * goes soft. The accelerated path additionally folds the OS scale in, since it runs Chromium
     * at device scale 1 (see CybercoreBrowser).
     */
    private static void syncBrowserZoom() {
        if (uiBrowser == null) {
            return;
        }
        double userScale = CybercoreConfig.getBrowserScalePercent() / 100.0;
        double targetScale = McefBootstrap.isAcceleratedPaint() ? contentScale * userScale : userScale;
        double zoom = Math.log(targetScale) / Math.log(1.2);
        if (Math.abs(zoom - lastAppliedZoom) > 0.001) {
            uiBrowser.setZoomLevel(zoom);
            if (overlayBrowser != null) {
                overlayBrowser.setZoomLevel(zoom);
            }
            lastAppliedZoom = zoom;
        }
    }

    /**
     * A finished load starts from Chromium's default zoom, so whatever was applied before must
     * not be assumed anymore. Marking the default makes the next tick reapply a non-default
     * target and costs nothing when the target is the default itself.
     */
    static void markZoomStale() {
        lastAppliedZoom = 0;
    }

    private static void registerZoomLoadHandler() {
        MCEF.INSTANCE.getClient().addLoadHandler(new org.cef.handler.CefLoadHandlerAdapter() {
            @Override
            public void onLoadEnd(org.cef.browser.CefBrowser cefBrowser,
                                  org.cef.browser.CefFrame frame, int httpStatusCode) {
                if (isOurBrowser(cefBrowser) && frame.isMain()) {
                    markZoomStale();
                }
            }
        });
    }

    private static final String CLEAR_CACHE_AND_RELOAD_JS =
            "(async function(){"
            + "try{if(window.caches){var ks=await caches.keys();"
            + "await Promise.all(ks.map(function(k){return caches.delete(k);}));}}catch(e){}"
            + "try{if(navigator.serviceWorker){var rs=await navigator.serviceWorker.getRegistrations();"
            + "await Promise.all(rs.map(function(r){return r.unregister();}));}}catch(e){}"
            + "location.reload();})();";

    /**
     * Ordinary reload of both pages. The service worker updates itself and re-fetches only what
     * changed, so this is what a page refresh should cost.
     */
    static void reloadBrowser() {
        reloadOne(uiBrowser, false);
        reloadOne(overlayBrowser, false);
    }

    /**
     * Throws the whole browser-side cache away and reloads from scratch, service worker included -
     * the escape hatch for when a bad build got itself cached, not something to do routinely.
     */
    static void hardReloadBrowser() {
        reloadOne(uiBrowser, true);
        reloadOne(overlayBrowser, false);
    }

    private static void reloadOne(CybercoreBrowser browser, boolean hard) {
        if (browser == null) {
            return;
        }
        if (BrowserLoadGuard.isParked(browser)) {
            BrowserLoadGuard.loadNow(browser, bootUrl(browser));
            return;
        }
        if (hard) {
            browser.executeJavaScript(CLEAR_CACHE_AND_RELOAD_JS, browser.getURL(), 0);
        } else {
            browser.reload();
        }
    }

    private static CybercoreBrowser createBrowser(String url) {
        // Built by hand instead of MCEF.createBrowser, which hardwires the base class: ours is the
        // same browser plus HiDPI and richer wheel input. shared_texture is only requested when
        // the platform probe accepted it - CEF ignores an unsupported request silently.
        int frameRate = maxFrameRate();
        CybercoreBrowser b = new CybercoreBrowser(
                MCEF.INSTANCE.getClient(),
                url,
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
