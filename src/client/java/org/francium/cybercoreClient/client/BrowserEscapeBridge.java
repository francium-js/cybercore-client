package org.francium.cybercoreClient.client;

import net.ccbluex.liquidbounce.mcef.MCEF;
import net.minecraft.client.Minecraft;
import org.cef.CefSettings;
import org.cef.browser.CefBrowser;
import org.cef.handler.CefDisplayHandlerAdapter;

/**
 * Closes the browser screen when the page reports that Escape had nothing left to close.
 *
 * <p>The modal stack lives in the front-end, so the front-end decides: its escape layers unwind one
 * per press and the lowest of them, registered only in game, sends this message. Mirroring the open
 * modal count on this side could only ever be a stale copy.
 */
final class BrowserEscapeBridge {

    private static final String CLOSE_MESSAGE = "[cybercore-esc]close";

    private static boolean registered = false;

    private BrowserEscapeBridge() {
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
                if (browser != CybercoreClientClient.browser || !CLOSE_MESSAGE.equals(message)) {
                    return false;
                }
                closeBrowserScreen();
                return true;
            }
        });
    }

    private static void closeBrowserScreen() {
        Minecraft client = Minecraft.getInstance();
        client.execute(() -> {
            if (client.screen instanceof BrowserScreen) {
                CybercoreClientClient.deactivateBrowser();
                client.setScreen(null);
            }
        });
    }
}
