package org.francium.cybercoreClient.client;

import net.ccbluex.liquidbounce.mcef.MCEF;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.handler.CefLoadHandlerAdapter;
import org.cef.network.CefRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tells the page it is running inside the mod, on every document it loads.
 *
 * <p>The front-end reads this once and remembers it, and a lot hangs on the answer: an unauthorized
 * player is only left alone on the overlay route because it counts as public "inside the mod",
 * otherwise the layout bounces them to the landing page, which then covers the game. Deriving it
 * from a query parameter alone was too fragile, so the flag is written here as well.
 */
final class BrowserClientFlag {

    private static final Logger LOGGER = LoggerFactory.getLogger("cybercore-client");

    /** Matches the key and value the front-end's own isMcefClientMod() writes. */
    private static final String SEED_SCRIPT =
            "try{localStorage.setItem('isMCEFcliendMod','true');}catch(e){}";

    private static boolean registered = false;

    private BrowserClientFlag() {
    }

    static void register() {
        if (registered) {
            return;
        }
        registered = true;

        MCEF.INSTANCE.getClient().addLoadHandler(new CefLoadHandlerAdapter() {
            @Override
            public void onLoadStart(CefBrowser browser, CefFrame frame, CefRequest.TransitionType transitionType) {
                // On load start, so the flag is in storage before the app boots and asks.
                if (browser != CybercoreClientClient.browser || frame == null || !frame.isMain()) {
                    return;
                }
                try {
                    frame.executeJavaScript(SEED_SCRIPT, frame.getURL(), 0);
                } catch (Throwable t) {
                    LOGGER.debug("Could not seed the in-game client flag.", t);
                }
            }
        });
    }
}
