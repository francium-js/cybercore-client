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
    private static final String KEY_BROWSER_MAX_FPS = "browserMaxFps";

    private static final boolean DEFAULT_SWAP_RED_BLUE = true;

    /** Effectively "no extra cap" - the platform ceilings in the client apply first. */
    private static final int DEFAULT_BROWSER_MAX_FPS = 240;

    private static volatile String baseUrl = DEFAULT_BASE_URL;

    /**
     * Whether GPU-shared browser frames need their red and blue channels swapped.
     *
     * <p>CEF's shared texture is BGRA while MCEF imports it as RGBA8, so in theory the swap is
     * always needed - but in practice which way round it comes out has not been stable across
     * runs here, and it may well differ by driver. Override it by editing the config file's
     * {@code swapRedBlue} property if the default is ever wrong for a given install.
     */
    private static volatile boolean swapRedBlue = DEFAULT_SWAP_RED_BLUE;

    /** Upper bound for the browser's own frame rate; lower it to trade top rate for smoothness. */
    private static volatile int browserMaxFps = DEFAULT_BROWSER_MAX_FPS;

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

    static int getBrowserMaxFps() {
        return browserMaxFps;
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

        String storedFps = props.getProperty(KEY_BROWSER_MAX_FPS);
        if (storedFps != null && !storedFps.isBlank()) {
            try {
                browserMaxFps = Math.clamp(Integer.parseInt(storedFps.trim()), 30, 240);
            } catch (NumberFormatException e) {
                LOGGER.warn("browserMaxFps is not a number, keeping {}.", browserMaxFps);
            }
        }
    }

    private static void save() {
        Properties props = new Properties();
        props.setProperty(KEY_BASE_URL, baseUrl);
        props.setProperty(KEY_SWAP_RED_BLUE, String.valueOf(swapRedBlue));
        props.setProperty(KEY_BROWSER_MAX_FPS, String.valueOf(browserMaxFps));
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
