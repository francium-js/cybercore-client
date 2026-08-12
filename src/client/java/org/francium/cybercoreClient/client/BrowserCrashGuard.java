package org.francium.cybercoreClient.client;

import net.ccbluex.liquidbounce.mcef.MCEF;
import net.minecraft.client.Minecraft;
import org.cef.browser.CefBrowser;
import org.cef.handler.CefRequestHandler;
import org.cef.handler.CefRequestHandlerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Revives the page when Chromium's renderer process dies.
 *
 * <p>A dead renderer sends no frames, so the paint-freshness gate keeps the world clean - but
 * nothing would ever come back on its own: the browser object stays alive while the process
 * behind it is gone, and every open after that shows a frozen texture until the game restarts.
 * The suspected trigger is a crash mid-navigation (heavy route switch + instant close). Reload
 * spawns a fresh renderer process; the load guard, the zoom reapply and the once-a-second flag
 * reassert then walk the page back into the correct state by themselves.
 *
 * <p>Registered on the raw CefClient: MCEF's wrapper does not expose request handlers, and the
 * raw client holds a single slot - nothing else in the mod or MCEF claims it.
 */
final class BrowserCrashGuard {

    private static final Logger LOGGER = LoggerFactory.getLogger("cybercore-client");

    private static boolean registered = false;

    private BrowserCrashGuard() {
    }

    static void register() {
        if (registered) {
            return;
        }
        registered = true;

        MCEF.INSTANCE.getClient().getHandle().addRequestHandler(new CefRequestHandlerAdapter() {
            @Override
            public void onRenderProcessTerminated(CefBrowser browser,
                                                  CefRequestHandler.TerminationStatus status,
                                                  int errorCode, String reason) {
                if (!CybercoreClientClient.isOurBrowser(browser)) {
                    return;
                }
                LOGGER.warn("Browser renderer process died ({}, code {}, {}) - reloading the page.",
                        status, errorCode, reason);
                Minecraft.getInstance().execute(() -> {
                    if (CybercoreClientClient.isOurBrowser(browser)) {
                        browser.reload();
                    }
                });
            }
        });
    }
}
