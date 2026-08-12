package org.francium.cybercoreClient.client;

import net.ccbluex.liquidbounce.mcef.MCEF;
import net.minecraft.client.Minecraft;
import org.cef.CefSettings;
import org.cef.browser.CefBrowser;
import org.cef.handler.CefDisplayHandlerAdapter;

/**
 * Applies the browser scale the player picks on the site's settings page.
 *
 * <p>The slider lives in the front-end, but what it drives is Chromium's device scale factor,
 * which only the mod can set - so the page reports the chosen percentage over the console
 * channel (the same transport as {@link BrowserEscapeBridge}) and the mod clamps, persists and
 * applies it. The page sends only on slider release: every application is a full re-layout.
 */
final class BrowserScaleBridge {

    private static final String MESSAGE_PREFIX = "[cybercore-scale]";

    private static boolean registered = false;

    private BrowserScaleBridge() {
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
                int percent;
                try {
                    percent = Integer.parseInt(message.substring(MESSAGE_PREFIX.length()).trim());
                } catch (NumberFormatException e) {
                    return true;
                }
                Minecraft.getInstance().execute(
                        () -> CybercoreClientClient.applyUserBrowserScale(percent));
                return true;
            }
        });
    }
}
