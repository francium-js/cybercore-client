package org.francium.cybercoreClient.client;

import net.ccbluex.liquidbounce.mcef.MCEF;
import net.minecraft.client.Minecraft;
import org.cef.CefSettings;
import org.cef.browser.CefBrowser;
import org.cef.handler.CefDisplayHandlerAdapter;

/**
 * Applies the GPU/CPU frame choice the player makes on the site's settings page.
 *
 * <p>The rendering path is fixed at browser creation, so flipping it means persisting the choice
 * (see CybercoreConfig) and recreating the browser - the page reloads once and comes back on the
 * new path. Same console transport as the other bridges.
 */
final class BrowserAccelBridge {

    private static final String MESSAGE_PREFIX = "[cybercore-accel]";

    private static boolean registered = false;

    private BrowserAccelBridge() {
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
                boolean enabled = "true".equals(message.substring(MESSAGE_PREFIX.length()).trim());
                Minecraft.getInstance().execute(
                        () -> CybercoreClientClient.applyGpuFrames(enabled));
                return true;
            }
        });
    }
}
