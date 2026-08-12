package org.francium.cybercoreClient.client;

import net.ccbluex.liquidbounce.mcef.MCEF;
import org.cef.CefSettings;
import org.cef.browser.CefBrowser;
import org.cef.handler.CefDisplayHandlerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Relays the page's console into the game log. Players cannot open devtools in-game, so this is
 * the only window into the front-end a report's latest.log gives us: JS errors, and the page's
 * own trace of the platform-open flag (see setPlatformOpen in the front-end's mcef.ts).
 */
final class BrowserConsoleLog {

    private static final Logger LOGGER = LoggerFactory.getLogger("cybercore-page");

    /** Internal high-frequency channel (text-input focus heartbeats) - noise in a log. */
    private static final String INPUT_CHANNEL_PREFIX = "[cybercore-input]";

    private static final int MAX_MESSAGE_LENGTH = 300;

    private static boolean registered = false;

    private BrowserConsoleLog() {
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
                        || message == null || message.startsWith(INPUT_CHANNEL_PREFIX)) {
                    return false;
                }
                String text = message.length() > MAX_MESSAGE_LENGTH
                        ? message.substring(0, MAX_MESSAGE_LENGTH) + "…"
                        : message;
                if (level == CefSettings.LogSeverity.LOGSEVERITY_ERROR
                        || level == CefSettings.LogSeverity.LOGSEVERITY_FATAL) {
                    LOGGER.warn("{} ({}:{})", text, source, line);
                } else {
                    LOGGER.info("{}", text);
                }
                // Not consumed: the escape/scale bridges still need to see their own messages.
                return false;
            }
        });
    }
}
