package org.francium.cybercoreClient.client;

import net.ccbluex.liquidbounce.mcef.MCEF;
import net.ccbluex.liquidbounce.mcef.cef.MCEFBrowser;
import net.minecraft.client.Minecraft;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.handler.CefLoadHandlerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keeps Chromium's own error page off the screen. The browser is always rendered as a HUD
 * overlay while the player is in the world, so a dead front-end (or a 5xx from nginx) would
 * paint a full-screen "This site can't be reached" over the game. Instead we park the browser
 * on a blank transparent page and retry the real URL in the background until it answers.
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

    /** True while the browser sits on BLANK_URL because the site refused to load. */
    private static volatile boolean parked = false;

    private static int ticksSinceRetry = 0;

    private BrowserLoadGuard() {
    }

    /** Must run before the browser is created, so the very first failed load is caught too. */
    static void register() {
        if (registered) {
            return;
        }
        MCEF.INSTANCE.getClient().addLoadHandler(INSTANCE);
        registered = true;
    }

    static boolean isParked() {
        return parked;
    }

    /** Forgets the parked state - used when the browser itself is recreated. */
    static void reset() {
        parked = false;
        ticksSinceRetry = 0;
    }

    /**
     * Loads {@code url} right away and keeps the guard armed: the load either succeeds (and
     * onLoadEnd clears the parked flag) or fails and lands us back on the blank page.
     */
    static void loadNow(MCEFBrowser browser, String url) {
        ticksSinceRetry = 0;
        browser.loadURL(url);
    }

    /** Retries the page the mod currently wants, at most once every RETRY_INTERVAL_TICKS. */
    static void tick() {
        if (!parked) {
            return;
        }

        MCEFBrowser browser = CybercoreClientClient.browser;
        if (browser == null) {
            return;
        }

        ticksSinceRetry++;
        if (ticksSinceRetry < RETRY_INTERVAL_TICKS) {
            return;
        }
        ticksSinceRetry = 0;

        browser.loadURL(CybercoreClientClient.currentPageUrl());
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

        parked = false;
    }

    private static void park(CefBrowser browser) {
        parked = true;

        // Never navigate from inside a CEF load callback - hop to the client thread first,
        // which also keeps the retry counter on the thread that ticks it.
        Minecraft.getInstance().execute(() -> {
            ticksSinceRetry = 0;
            browser.loadURL(BLANK_URL);
        });
    }

    private static boolean isOwnSite(String url) {
        return url != null && url.startsWith(CybercoreConfig.getBaseUrl());
    }

    private static boolean isOurMainFrame(CefBrowser browser, CefFrame frame) {
        return browser != null
                && frame != null
                && frame.isMain()
                && browser == CybercoreClientClient.browser;
    }

    private static boolean isBlank(String url) {
        return url == null || url.isEmpty() || url.startsWith(BLANK_URL);
    }
}
