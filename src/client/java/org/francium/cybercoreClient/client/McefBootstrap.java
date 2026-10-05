package org.francium.cybercoreClient.client;

import net.ccbluex.liquidbounce.mcef.MCEF;
import net.ccbluex.liquidbounce.mcef.MCEFAccelerationSupport;
import net.ccbluex.liquidbounce.mcef.MCEFDownloadManager;
import net.ccbluex.liquidbounce.mcef.MCEFPlatform;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import org.cef.CefApp;
import org.cef.browser.CefBrowser;
import org.cef.callback.CefCompletionCallback;
import org.cef.handler.CefLifeSpanHandlerAdapter;
import org.cef.network.CefCookieManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Owns MCEF's lifecycle: download the Chromium runtime, initialize CEF, probe for GPU acceleration,
 * shut it down. MCEF is a plain library with no mixins and no lifecycle of its own, so all of this
 * is ours - including pumping its message loop every frame, which {@code GameRendererMixin} does.
 */
public final class McefBootstrap {

    private static final Logger LOGGER = LoggerFactory.getLogger("cybercore-client");

    /** Lives in the game directory, so every Modrinth profile keeps its own browser session. */
    private static final String CACHE_DIR_NAME = "cybercore-browser";

    private static volatile boolean starting = false;

    private static volatile boolean acceleratedPaint = false;

    private McefBootstrap() {
    }

    static boolean isReady() {
        return MCEF.INSTANCE.isInitialized();
    }

    /** True once CEF is up and the platform accepted the zero-copy path. */
    static boolean isAcceleratedPaint() {
        return acceleratedPaint;
    }

    /**
     * Downloads the runtime off-thread, then initializes CEF back on the render thread - which is
     * where CEF's UI thread ends up living, since that is where we pump its message loop.
     *
     * @param onReady invoked on the render thread once the outcome is known
     */
    static void start(Consumer<Boolean> onReady) {
        if (starting || isReady()) {
            return;
        }
        starting = true;

        Thread downloader = new Thread(() -> {
            try {
                MCEFDownloadManager resources = MCEF.INSTANCE.newResourceManager();

                if (!resources.isSystemCompatible()) {
                    LOGGER.error("MCEF does not support this platform, the browser is unavailable.");
                    finish(onReady, false);
                    return;
                }

                if (resources.requiresDownload()) {
                    LOGGER.info("Downloading the Chromium runtime, the browser will come up when it is done.");
                    resources.downloadJcef();
                }

                Minecraft.getInstance().execute(() -> initializeOnRenderThread(onReady));
            } catch (Throwable t) {
                LOGGER.error("Failed to prepare the Chromium runtime, the browser is unavailable.", t);
                finish(onReady, false);
            }
        }, "cybercore-mcef-bootstrap");

        downloader.setDaemon(true);
        downloader.start();
    }

    /**
     * Gives Chromium somewhere to keep its profile. Without this MCEF leaves {@code cache_path}
     * null, which is CEF's in-memory mode: no HTTP cache, no service worker and no localStorage
     * surviving a restart. Must run before {@code initialize()}, which reads the setting once.
     */
    private static void configureCacheDirectory() {
        Path cache = FabricLoader.getInstance().getGameDir().resolve(CACHE_DIR_NAME).toAbsolutePath();
        try {
            Files.createDirectories(cache);
            MCEF.INSTANCE.getSettings().setCacheDirectory(cache.toFile());
            LOGGER.info("Browser profile directory: {}", cache);
        } catch (IOException e) {
            LOGGER.warn("Could not create the browser profile directory {}. The browser will run "
                    + "in-memory and the player will have to sign in again every launch.", cache, e);
        }
    }

    private static void initializeOnRenderThread(Consumer<Boolean> onReady) {
        configureCacheDirectory();

        boolean initialized;
        try {
            initialized = MCEF.INSTANCE.initialize();
        } catch (Throwable t) {
            LOGGER.error("CEF initialization threw, the browser is unavailable.", t);
            initialized = false;
        }

        if (!initialized) {
            finish(onReady, false);
            return;
        }

        registerLifeSpanTracking();
        resolveAccelerationSupport();
        finish(onReady, true);
    }

    /**
     * Decides whether frames can come straight from CEF as a GPU handle instead of a CPU buffer.
     * MCEF's probe is narrow - on Windows only NVIDIA and AMD pass - and anything it rejects falls
     * back to the copy path.
     */
    /** Re-runs the probe after the player flips the GPU/CPU choice (see BrowserAccelBridge). */
    static void reapplyAccelerationSupport() {
        if (isReady()) {
            resolveAccelerationSupport();
        }
    }

    private static void resolveAccelerationSupport() {
        // The config gates the GPU path: MCEF can lose a shared frame silently mid-import, and
        // some machines are better off on software frames (see CybercoreConfig for the story).
        if (!CybercoreConfig.isAcceleratedPaintAllowed()) {
            LOGGER.info("GPU-shared browser frames are disabled by config; using software frames.");
            acceleratedPaint = false;
            return;
        }

        MCEFAccelerationSupport.Support support;
        try {
            support = MCEFAccelerationSupport.getAccelerationSupport();
        } catch (Throwable t) {
            LOGGER.warn("Acceleration probe failed, falling back to software frames.", t);
            acceleratedPaint = false;
            return;
        }

        // macOS hands the frame over as a GL_TEXTURE_RECTANGLE, which vanilla's GUI blit cannot
        // sample; consuming it would need a custom pipeline we do not ship.
        if (support.isSupported() && MCEFPlatform.getPlatform().isMacOS()) {
            LOGGER.info("macOS offers GPU-shared frames as a rectangle texture, which our GUI "
                    + "pipeline cannot sample; staying on software frames.");
            acceleratedPaint = false;
            return;
        }

        acceleratedPaint = support.isSupported();

        if (!acceleratedPaint) {
            LOGGER.info("GPU-shared browser frames are unavailable here; using software frames.");
        } else if (support.isBeta()) {
            LOGGER.info("GPU-shared browser frames enabled (marked beta by MCEF on this platform).");
        } else {
            LOGGER.info("GPU-shared browser frames enabled.");
        }
    }

    private static void finish(Consumer<Boolean> onReady, boolean successful) {
        starting = false;
        Minecraft.getInstance().execute(() -> onReady.accept(successful));
    }

    /** Pumps CEF's message loop. Must run on the render thread, once per frame. */
    public static void pumpMessageLoop() {
        if (!isReady()) {
            return;
        }
        try {
            MCEF.INSTANCE.getApp().getHandle().N_DoMessageLoopWork();
        } catch (Throwable t) {
            LOGGER.error("CEF message loop pump failed.", t);
        }
    }

    /** How often the cookie store is pushed to disk, in client ticks (5 s). */
    private static final int COOKIE_FLUSH_INTERVAL_TICKS = 100;

    /** How long quitting may wait for CEF to close the profile cleanly. */
    private static final long SHUTDOWN_TIMEOUT_MS = 3000;

    private static int ticksSinceCookieFlush = 0;

    /** CEF's native side guards a null callback upstream; an empty one does not depend on that. */
    private static final CefCompletionCallback NO_OP_COMPLETION = () -> {
    };

    /**
     * Pushes pending cookie writes to disk. Chromium batches them for up to 30 seconds, and the
     * backend rotates the refresh token on every refresh - so a game killed from the task manager
     * (or a PC losing power) inside that window comes back with a token the server already
     * retired, and the player is signed out. A flush with nothing pending is a no-op.
     */
    static void tickCookieFlush() {
        if (!isReady() || ++ticksSinceCookieFlush < COOKIE_FLUSH_INTERVAL_TICKS) {
            return;
        }
        ticksSinceCookieFlush = 0;
        try {
            CefCookieManager.getGlobalManager().flushStore(NO_OP_COMPLETION);
        } catch (Throwable t) {
            LOGGER.debug("Cookie flush failed.", t);
        }
    }

    /**
     * Every browser CEF has not finished closing: ours from the moment they are constructed (so
     * one still being created counts too), anything else from onAfterCreated. onBeforeClose is
     * the last callback a browser gets, and it is where JCEF drops it from the client as well.
     */
    private static final Set<CefBrowser> liveBrowsers = ConcurrentHashMap.newKeySet();

    /** Called for each browser we construct, before it is created. */
    static void trackBrowser(CefBrowser browser) {
        liveBrowsers.add(browser);
    }

    private static void registerLifeSpanTracking() {
        // JCEF keeps a single life span handler per client; neither MCEF nor the rest of the mod
        // installs one, so this slot is ours.
        MCEF.INSTANCE.getClient().getHandle().addLifeSpanHandler(new CefLifeSpanHandlerAdapter() {
            @Override
            public void onAfterCreated(CefBrowser browser) {
                liveBrowsers.add(browser);
            }

            @Override
            public void onBeforeClose(CefBrowser browser) {
                liveBrowsers.remove(browser);
            }
        });
    }

    /**
     * Closes the profile the way Chromium expects, so cookies and storage reach the disk and the
     * profile is not marked as crashed.
     *
     * <p>MCEF's shutdown alone never gets there: it only asks the browsers to close, and JCEF
     * runs CefShutdown from the last close callback - which needs the message loop, and nothing
     * pumps it once the game stops rendering. Pumping after MCEF's shutdown would not do either:
     * CefShutdown would then run from inside a callback, nested in the very loop it tears down.
     *
     * <p>So the browsers are closed and the loop pumped here until all of them are gone, and only
     * then is MCEF shut down. With no browsers left, {@code CefApp.dispose()} calls CefShutdown
     * directly, on this thread, outside the loop. If they do not close in time nothing changes
     * from before: shutting down with a browser still alive is exactly what must not happen.
     */
    static void shutdown() {
        if (!isReady()) {
            return;
        }
        // CEF's UI thread is the render thread (that is where the loop is pumped). Anywhere else,
        // leave it to MCEF as before.
        if (!Minecraft.getInstance().isSameThread()) {
            LOGGER.warn("CEF shutdown requested off the render thread; skipping the clean close.");
            MCEF.INSTANCE.shutdown();
            return;
        }

        try {
            CefCookieManager.getGlobalManager().flushStore(NO_OP_COMPLETION);
        } catch (Throwable t) {
            LOGGER.debug("Cookie flush before shutdown failed.", t);
        }

        for (CefBrowser browser : liveBrowsers) {
            try {
                // A no-op for the ones already closing.
                browser.close(true);
            } catch (Throwable t) {
                LOGGER.debug("Closing a browser for shutdown failed.", t);
            }
        }

        if (!pumpUntilBrowsersClosed()) {
            LOGGER.warn("{} browser(s) did not close within {} ms; quitting without a clean CEF "
                    + "shutdown.", liveBrowsers.size(), SHUTDOWN_TIMEOUT_MS);
            return;
        }

        MCEF.INSTANCE.shutdown();

        if (CefApp.getState() == CefApp.CefAppState.TERMINATED) {
            LOGGER.info("CEF shut down cleanly.");
        } else {
            LOGGER.warn("CEF did not terminate after shutdown (state {}).", CefApp.getState());
        }
    }

    private static boolean pumpUntilBrowsersClosed() {
        CefApp app = MCEF.INSTANCE.getApp().getHandle();
        long deadline = System.currentTimeMillis() + SHUTDOWN_TIMEOUT_MS;
        while (!liveBrowsers.isEmpty()) {
            if (System.currentTimeMillis() > deadline) {
                return false;
            }
            try {
                app.N_DoMessageLoopWork();
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            } catch (Throwable t) {
                LOGGER.warn("CEF message loop pump failed during shutdown.", t);
                return false;
            }
        }
        return true;
    }
}
