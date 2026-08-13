package org.francium.cybercoreClient.client;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public final class CybercoreConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger("cybercore-client");

    private static final String DEFAULT_BASE_URL = "https://mc-cybercore.space";
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("cybercore-client.properties");
    private static final String KEY_BASE_URL = "baseUrl";
    private static final String KEY_SWAP_RED_BLUE = "swapRedBlue";
    private static final String KEY_BROWSER_SCALE = "browserScalePercent";
    private static final String KEY_ACCELERATED_PAINT = "gpuFrames";

    private static final boolean DEFAULT_SWAP_RED_BLUE = true;

    /**
     * GPU-shared frames by default - they carry the monitor's full refresh rate. Machines where
     * the GPU path misbehaves switch to software frames on the site's settings page, or here.
     * The switch covers both browsers at once; they never run on different paths.
     */
    private static final boolean DEFAULT_ACCELERATED_PAINT = true;

    static final int DEFAULT_BROWSER_SCALE_PERCENT = 100;
    static final int MIN_BROWSER_SCALE_PERCENT = 50;
    static final int MAX_BROWSER_SCALE_PERCENT = 200;

    private static volatile String baseUrl = DEFAULT_BASE_URL;

    /**
     * The player's manual browser scale (percent) on top of the OS content scale - the escape
     * hatch for machines where the auto-detected scale is wrong. Set from the site's settings
     * page (BrowserScaleBridge), persisted so it applies from launch.
     */
    private static volatile int browserScalePercent = DEFAULT_BROWSER_SCALE_PERCENT;

    private static volatile boolean acceleratedPaint = DEFAULT_ACCELERATED_PAINT;

    /**
     * Whether GPU-shared browser frames need their red and blue channels swapped.
     *
     * <p>CEF's shared texture is BGRA while MCEF imports it as RGBA8, so in theory the swap is
     * always needed - but in practice which way round it comes out has not been stable across
     * runs here, and it may well differ by driver. Override it by editing the config file's
     * {@code swapRedBlue} property if the default is ever wrong for a given install.
     */
    private static volatile boolean swapRedBlue = DEFAULT_SWAP_RED_BLUE;

    static {
        load();
    }

    private CybercoreConfig() {
    }

    static String getBaseUrl() {
        return baseUrl;
    }

    static void setBaseUrl(String url) {
        String normalized = stripTrailingSlash(url.trim());
        baseUrl = normalized.isEmpty() ? DEFAULT_BASE_URL : normalized;
        save();
    }

    static boolean isSwapRedBlue() {
        return swapRedBlue;
    }

    static int getBrowserScalePercent() {
        return browserScalePercent;
    }

    static boolean isAcceleratedPaintAllowed() {
        return acceleratedPaint;
    }

    static void setAcceleratedPaintAllowed(boolean enabled) {
        acceleratedPaint = enabled;
        save();
    }

    static void setBrowserScalePercent(int percent) {
        browserScalePercent = clampBrowserScale(percent);
        save();
    }

    private static int clampBrowserScale(int percent) {
        return Math.max(MIN_BROWSER_SCALE_PERCENT, Math.min(MAX_BROWSER_SCALE_PERCENT, percent));
    }

    private static void load() {
        if (!Files.isRegularFile(FILE)) {
            return;
        }
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(FILE)) {
            props.load(in);
        } catch (IOException e) {
            LOGGER.warn("Failed to read cybercore-client.properties, using defaults.", e);
            return;
        }

        String storedUrl = props.getProperty(KEY_BASE_URL);
        if (storedUrl != null && !storedUrl.isBlank()) {
            baseUrl = stripTrailingSlash(storedUrl.trim());
        }

        String storedSwap = props.getProperty(KEY_SWAP_RED_BLUE);
        if (storedSwap != null && !storedSwap.isBlank()) {
            swapRedBlue = Boolean.parseBoolean(storedSwap.trim());
        }

        String storedAccelerated = props.getProperty(KEY_ACCELERATED_PAINT);
        if (storedAccelerated != null && !storedAccelerated.isBlank()) {
            acceleratedPaint = Boolean.parseBoolean(storedAccelerated.trim());
        }

        String storedScale = props.getProperty(KEY_BROWSER_SCALE);
        if (storedScale != null && !storedScale.isBlank()) {
            try {
                browserScalePercent = clampBrowserScale(Integer.parseInt(storedScale.trim()));
            } catch (NumberFormatException e) {
                LOGGER.warn("Ignoring malformed {} value: {}", KEY_BROWSER_SCALE, storedScale);
            }
        }
    }

    private static void save() {
        Properties props = new Properties();
        props.setProperty(KEY_BASE_URL, baseUrl);
        props.setProperty(KEY_SWAP_RED_BLUE, String.valueOf(swapRedBlue));
        props.setProperty(KEY_BROWSER_SCALE, String.valueOf(browserScalePercent));
        props.setProperty(KEY_ACCELERATED_PAINT, String.valueOf(acceleratedPaint));
        try {
            Files.createDirectories(FILE.getParent());
            try (OutputStream out = Files.newOutputStream(FILE)) {
                props.store(out, "Cybercore client configuration");
            }
        } catch (IOException e) {
            LOGGER.warn("Failed to save cybercore-client.properties.", e);
        }
    }

    private static String stripTrailingSlash(String url) {
        int end = url.length();
        while (end > 0 && url.charAt(end - 1) == '/') {
            end--;
        }
        return url.substring(0, end);
    }
}
