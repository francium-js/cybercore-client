package org.francium.cybercoreClient.client;

import net.ccbluex.liquidbounce.mcef.MCEF;
import net.ccbluex.liquidbounce.mcef.cef.MCEFBrowser;
import org.cef.CefSettings;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.handler.CefDisplayHandlerAdapter;
import org.cef.handler.CefLoadHandlerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tells the page whether its screen is open, and knows when it has been heard.
 *
 * <p>Replaces navigating between routes to open and close the browser: a route can be moved by the
 * auth interceptor, a layout guard or the back button, and each of those silently meant "the
 * platform is visible again" to a mod that had no say in it. A flag only the mod writes cannot.
 *
 * <p>The command is idempotent, which is what makes it robust: setting a flag that is already set
 * is a no-op, so it is simply repeated every tick until the page agrees, and reasserted once a
 * second in case the page reloaded. A navigation could never be retried that freely.
 */
final class BrowserOverlayMode {

    private static final Logger LOGGER = LoggerFactory.getLogger("cybercore-client");

    private static final String ACK_PREFIX = "[cybercore-mode]";

    /** Reassert even once agreed, so a page that reloaded is corrected within the second. */
    private static final int REASSERT_INTERVAL_TICKS = 20;

    /**
     * When to give up on an answer and send the page away the old way instead. A build that does
     * not know the flag is normal - the service worker serves the previous one until the next load,
     * and a rollback does it on purpose - and without this the platform would keep running unseen
     * behind the world.
     */
    private static final int FALLBACK_TICKS = 60;

    private static boolean registered = false;

    /** True while the in-game screen is closed. The mod's intent, set on the client thread. */
    private static volatile boolean wantOverlay = true;

    /** What the page last said it was doing, and whether it has said anything at all. */
    private static volatile boolean pageOverlay = false;
    private static volatile boolean acked = false;

    private static int ticksSinceSend = 0;
    private static int ticksDisagreeing = 0;

    /** True once this page has been given up on and sent away instead. */
    private static boolean fallbackEngaged = false;

    private BrowserOverlayMode() {
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
                if (browser != CybercoreClientClient.browser
                        || message == null
                        || !message.startsWith(ACK_PREFIX)) {
                    return false;
                }
                accept("1".equals(message.substring(ACK_PREFIX.length()).trim()));
                return true;
            }
        });

        // A fresh document has a fresh flag, whatever the old one agreed to.
        MCEF.INSTANCE.getClient().addLoadHandler(new CefLoadHandlerAdapter() {
            @Override
            public void onLoadStart(CefBrowser browser, CefFrame frame,
                                    org.cef.network.CefRequest.TransitionType transitionType) {
                if (browser == CybercoreClientClient.browser && frame != null && frame.isMain()) {
                    acked = false;
                    ticksSinceSend = REASSERT_INTERVAL_TICKS;
                }
            }
        });
    }

    /** Forgets everything - used when the browser itself is recreated. */
    static void reset() {
        wantOverlay = true;
        pageOverlay = false;
        acked = false;
        ticksSinceSend = REASSERT_INTERVAL_TICKS;
        ticksDisagreeing = 0;
        fallbackEngaged = false;
    }

    /** The mod's intent: true means the in-game screen is closed. */
    static void set(boolean overlay) {
        wantOverlay = overlay;
        // An ack from before this switch is about the previous state. A quick open/close would
        // otherwise reuse pageOverlay=true from the last parked session and paint the platform
        // frame still sitting in the texture; a fresh ack re-engages the paint hold instead.
        acked = false;
        // Silence is measured from this request, not from the previous one - a counter carried
        // over from an unfinished earlier switch would trip the fallback navigation early.
        ticksDisagreeing = 0;
        fallbackEngaged = false;
        send();
    }

    /** The page's own word that it is drawing the in-world layer. Never inferred, never assumed. */
    static boolean isConfirmedOverlay() {
        return acked && pageOverlay;
    }

    static void tick() {
        if (CybercoreClientClient.browser == null) {
            return;
        }

        boolean agreed = acked && pageOverlay == wantOverlay;

        ticksSinceSend++;
        // Disagreement retried every tick, agreement reasserted once a second.
        if (!agreed || ticksSinceSend >= REASSERT_INTERVAL_TICKS) {
            send();
        }

        if (agreed) {
            ticksDisagreeing = 0;
            fallbackEngaged = false;
            return;
        }

        // A foreign page (the OAuth flow) has no bridge to answer with. Keep asking, but do not
        // call the silence a fault.
        if (!BrowserRouteTracker.isOnOwnSite()) {
            return;
        }

        // Closing direction only: opening already navigates on its own.
        if (wantOverlay && !fallbackEngaged && ++ticksDisagreeing >= FALLBACK_TICKS) {
            fallbackEngaged = true;
            LOGGER.warn("The page does not answer on the overlay flag - most likely an older build "
                    + "from the service worker cache. Sending it to {} the old way.",
                    CybercoreClientClient.NOTHING_PATH);
            CybercoreClientClient.navigateToOverlayPage();
        }
    }

    private static void accept(boolean overlay) {
        boolean changed = !acked || pageOverlay != overlay;
        pageOverlay = overlay;
        acked = true;
        if (changed && overlay) {
            CybercoreClientClient.onOverlayConfirmed();
        }
    }

    private static void send() {
        MCEFBrowser browser = CybercoreClientClient.browser;
        if (browser == null) {
            return;
        }
        ticksSinceSend = 0;
        // No fallback if the bridge is missing - React has not mounted yet, and the next tick
        // asks again.
        browser.executeJavaScript(
                "(function(){if(typeof window.__ccSetOverlayMode==='function')"
                        + "{window.__ccSetOverlayMode(" + wantOverlay + ");}})();",
                browser.getURL(),
                0
        );
    }
}
