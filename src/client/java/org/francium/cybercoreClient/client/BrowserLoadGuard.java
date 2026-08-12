package org.francium.cybercoreClient.client;

import net.ccbluex.liquidbounce.mcef.MCEF;
import net.ccbluex.liquidbounce.mcef.cef.MCEFBrowser;
import net.minecraft.client.Minecraft;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.handler.CefLoadHandlerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps Chromium's own error page off the screen, for both browsers independently. The overlay
 * browser is always painted over the world, so a dead front-end (or a 5xx from nginx) would show
 * "This site can't be reached" over the game; the platform browser would greet the player with the
 * same error page on open. Instead a failed browser is parked on a blank transparent page and its
 * own boot URL is retried in the background until the site answers.
 */
final class BrowserLoadGuard extends CefLoadHandlerAdapter {

    private static final Logger LOGGER = LoggerFactory.getLogger("cybercore-client");

    /** Nothing to paint and nothing to fail - CEF renders it fully transparent for us. */
    private static final String BLANK_URL = "about:blank";

    /** Belt and braces: force transparency in case a CEF build paints about:blank white. */
    private static final String TRANSPARENT_JS =
            "document.documentElement.style.background='transparent';"
            + "if(document.body){document.body.style.background='transparent';}";

    private static final int RETRY_INTERVAL_TICKS = 100; // 5 s

    private static final BrowserLoadGuard INSTANCE = new BrowserLoadGuard();

    private static boolean registered = false;

    /** Per-browser retry state, present only while that browser sits parked on BLANK_URL. */
    private static final Map<CefBrowser, Integer> PARKED_TICKS = new ConcurrentHashMap<>();

    private BrowserLoadGuard() {
    }

    /** Must run before the browsers are created, so the very first failed loads are caught too. */
    static void register() {
        if (registered) {
            return;
        }
        MCEF.INSTANCE.getClient().addLoadHandler(INSTANCE);
        registered = true;
    }

    static boolean isParked(MCEFBrowser browser) {
        return browser != null && PARKED_TICKS.containsKey(browser);
    }

    /** Forgets all parked state - used when the browsers themselves are recreated. */
    static void reset() {
        PARKED_TICKS.clear();
    }

    /**
     * Loads {@code url} right away and keeps the guard armed: the load either succeeds (and
     * onLoadEnd clears the parked state) or fails and lands the browser back on the blank page.
     */
    static void loadNow(MCEFBrowser browser, String url) {
        PARKED_TICKS.computeIfPresent(browser, (b, ticks) -> 0);
        browser.loadURL(url);
    }

    /** Retries each parked browser's boot URL, at most once every RETRY_INTERVAL_TICKS. */
    static void tick() {
        for (Map.Entry<CefBrowser, Integer> entry : PARKED_TICKS.entrySet()) {
            int ticks = entry.getValue() + 1;
            if (ticks < RETRY_INTERVAL_TICKS) {
                entry.setValue(ticks);
                continue;
            }
            entry.setValue(0);
            if (entry.getKey() instanceof MCEFBrowser browser) {
                browser.loadURL(CybercoreClientClient.bootUrl(browser));
            }
        }
    }

    @Override
    public void onLoadError(
            CefBrowser browser,
            CefFrame frame,
            ErrorCode errorCode,
            String errorText,
            String failedUrl
    ) {
        // Sub-frames (and the aborts every SPA navigation produces) never replace the whole
        // document with an error page, so they are none of our business.
        if (!isOurMainFrame(browser, frame)
                || errorCode == ErrorCode.ERR_NONE
                || errorCode == ErrorCode.ERR_ABORTED) {
            return;
        }

        // The blank page is local; if it ever fails there is nothing safer to fall back to.
        if (isBlank(failedUrl)) {
            return;
        }

        LOGGER.warn("Page failed to load ({}: {}), parking the browser on a blank page: {}",
                errorCode, errorText, failedUrl);

        park(browser);
    }

    @Override
    public void onLoadEnd(CefBrowser browser, CefFrame frame, int httpStatusCode) {
        if (!isOurMainFrame(browser, frame)) {
            return;
        }

        if (isBlank(frame.getURL())) {
            browser.executeJavaScript(TRANSPARENT_JS, frame.getURL(), 0);
            return;
        }

        // A reachable server that answers 5xx (or 404) still hands Chromium a full-page body
        // to paint over the game - treat it exactly like a failed load. Only for our own site
        // though: an error status on a foreign page (the Discord OAuth flow) is that page's
        // business, and blanking it would just restart the login.
        if (httpStatusCode >= 400 && isOwnSite(frame.getURL())) {
            LOGGER.warn("Page answered HTTP {}, parking the browser on a blank page: {}",
                    httpStatusCode, frame.getURL());
            park(browser);
            return;
        }

        PARKED_TICKS.remove(browser);
    }

    private static void park(CefBrowser browser) {
        PARKED_TICKS.put(browser, 0);

        // Never navigate from inside a CEF load callback - hop to the client thread first,
        // which also keeps the retry counters on the thread that ticks them.
        Minecraft.getInstance().execute(() -> {
            PARKED_TICKS.computeIfPresent(browser, (b, ticks) -> 0);
            browser.loadURL(BLANK_URL);
        });
    }

    private static boolean isOwnSite(String url) {
        return url != null && url.startsWith(CybercoreConfig.getBaseUrl());
    }

    private static boolean isOurMainFrame(CefBrowser browser, CefFrame frame) {
        return frame != null
                && frame.isMain()
                && CybercoreClientClient.isOurBrowser(browser);
    }

    private static boolean isBlank(String url) {
        return url == null || url.isEmpty() || url.startsWith(BLANK_URL);
    }
}
