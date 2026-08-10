package org.francium.cybercoreClient.client;

import net.ccbluex.liquidbounce.mcef.MCEF;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.handler.CefDisplayHandlerAdapter;
import org.cef.handler.CefLoadHandlerAdapter;

/**
 * Follows the address CEF reports for our browser's main frame, so the mod can tell one of our own
 * pages from a foreign one. The Discord login flow parks the browser off-site for as long as the
 * login takes, and nothing there can answer the overlay flag - see {@link BrowserOverlayMode}.
 */
final class BrowserRouteTracker {

    /** Written from CEF's thread, read from the client thread. */
    private static volatile String currentUrl = null;

    private static boolean registered = false;

    private BrowserRouteTracker() {
    }

    /** Must run before the first browser exists, so its opening address is seen too. */
    static void register() {
        if (registered) {
            return;
        }
        registered = true;

        MCEF.INSTANCE.getClient().addDisplayHandler(new CefDisplayHandlerAdapter() {
            @Override
            public void onAddressChange(CefBrowser browser, CefFrame frame, String url) {
                if (isOurMainFrame(browser, frame)) {
                    currentUrl = url;
                }
            }
        });

        // Belt and braces for the loads that commit without an address change of their own.
        MCEF.INSTANCE.getClient().addLoadHandler(new CefLoadHandlerAdapter() {
            @Override
            public void onLoadEnd(CefBrowser browser, CefFrame frame, int httpStatusCode) {
                if (isOurMainFrame(browser, frame)) {
                    currentUrl = frame.getURL();
                }
            }
        });
    }

    static void reset() {
        currentUrl = null;
    }

    /** False for a foreign page, and while no address has been reported yet. */
    static boolean isOnOwnSite() {
        String url = currentUrl;
        String base = CybercoreConfig.getBaseUrl();
        if (url == null || !url.startsWith(base)) {
            return false;
        }
        // Guard against a host that merely starts with ours - "site.example" vs "site.example.evil".
        String rest = url.substring(base.length());
        return rest.isEmpty() || rest.charAt(0) == '/' || rest.charAt(0) == '?'
                || rest.charAt(0) == '#';
    }

    private static boolean isOurMainFrame(CefBrowser browser, CefFrame frame) {
        return browser != null
                && frame != null
                && frame.isMain()
                && browser == CybercoreClientClient.browser;
    }
}
