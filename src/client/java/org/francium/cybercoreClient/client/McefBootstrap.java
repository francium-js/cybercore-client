package org.francium.cybercoreClient.client;

import net.ccbluex.liquidbounce.mcef.MCEF;
import net.ccbluex.liquidbounce.mcef.MCEFAccelerationSupport;
import net.ccbluex.liquidbounce.mcef.MCEFDownloadManager;
import net.ccbluex.liquidbounce.mcef.MCEFPlatform;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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

        resolveAccelerationSupport();
        finish(onReady, true);
    }

    /**
     * Decides whether frames can come straight from CEF as a GPU handle instead of a CPU buffer.
     * MCEF's probe is narrow - on Windows only NVIDIA and AMD pass - and anything it rejects falls
     * back to the copy path.
     */
    private static void resolveAccelerationSupport() {
        // Opt-in via config: MCEF can lose a shared frame silently mid-import, and a lost frame
        // is a stale picture frozen over the world (see CybercoreConfig for the full story).
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

    static void shutdown() {
        if (isReady()) {
            MCEF.INSTANCE.shutdown();
        }
    }
}
