package org.francium.cybercoreClient.client;

import net.ccbluex.liquidbounce.mcef.MCEF;
import org.cef.CefSettings;
import org.cef.browser.CefBrowser;
import org.cef.handler.CefDisplayHandlerAdapter;

/**
 * Carries locally-born notifications from the platform page over to the overlay page.
 *
 * <p>Toasts exist only in the overlay browser, but some are born inside the platform - the
 * debug page's demo buttons, a president seeing their own tax change without waiting for the
 * websocket echo. The platform page serializes those ("[cc-toast-fwd]" + JSON) and the mod
 * replays them into the overlay page's global hook. Websocket-born events need none of this:
 * both pages hold their own socket, and the overlay page shows its copy directly.
 */
final class BrowserToastForwardBridge {

    private static final String MESSAGE_PREFIX = "[cc-toast-fwd]";

    private static boolean registered = false;

    private BrowserToastForwardBridge() {
    }

    static void register() {
        if (registered) {
            return;
        }
        registered = true;

        MCEF.INSTANCE.getClient().addDisplayHandler(new CefDisplayHandlerAdapter() {
            @Override
            public boolean onConsoleMessage(CefBrowser browser, CefSettings.LogSeverity level,
                                            String message, String source, int line) {
                if (!CybercoreClientClient.isUiBrowser(browser)
                        || message == null || !message.startsWith(MESSAGE_PREFIX)) {
                    return false;
                }
                String payload = message.substring(MESSAGE_PREFIX.length());
                // The payload must be a JSON object and is injected as a literal; both pages are
                // the same trusted origin, the shape check just keeps garbage out of a script.
                if (!payload.startsWith("{") || CybercoreClientClient.overlayBrowser == null) {
                    return true;
                }
                CybercoreClientClient.overlayBrowser.executeJavaScript(
                        "(function(){if(typeof window.__ccShowForwardedNotification==='function')"
                                + "{window.__ccShowForwardedNotification(" + payload + ");}})();",
                        CybercoreClientClient.overlayBrowser.getURL(),
                        0
                );
                return true;
            }
        });
    }
}
