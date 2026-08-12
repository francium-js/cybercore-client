package org.francium.cybercoreClient.client;

import net.ccbluex.liquidbounce.mcef.MCEF;
import org.cef.CefSettings;
import org.cef.browser.CefBrowser;
import org.cef.handler.CefDisplayHandlerAdapter;

/**
 * Feeds the page's collapse confirmations into the paint-freshness gate.
 *
 * <p>The front-end traces every applied overlay state ("[cc-page] overlay applied=...") and
 * repeats it as a heartbeat while collapsed. The gate uses it as the bar frames must be painted
 * after (see CybercoreClientClient.noteOverlayApplied): a frame older than the confirmation may
 * honestly still show the platform, a frame younger cannot. Not consumed - the console relay
 * still logs it for reports.
 */
final class BrowserCollapseAckBridge {

    private static final String MESSAGE_PREFIX = "[cc-page] overlay applied=";

    private static boolean registered = false;

    private BrowserCollapseAckBridge() {
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
                if (!CybercoreClientClient.isOurBrowser(browser)
                        || message == null || !message.startsWith(MESSAGE_PREFIX)) {
                    return false;
                }
                boolean overlay = "true".equals(message.substring(MESSAGE_PREFIX.length()).trim());
                CybercoreClientClient.noteOverlayApplied(overlay);
                return false;
            }
        });
    }
}
