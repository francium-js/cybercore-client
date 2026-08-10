package org.francium.cybercoreClient.client;

import net.ccbluex.liquidbounce.mcef.MCEF;
import net.ccbluex.liquidbounce.mcef.cef.MCEFBrowser;
import org.cef.CefSettings;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.handler.CefDisplayHandlerAdapter;
import org.cef.handler.CefLoadHandlerAdapter;
import org.cef.network.CefRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class BrowserTextInputTracker {

    private static final Logger LOGGER = LoggerFactory.getLogger("cybercore-client");

    private static final String MESSAGE_PREFIX = "[cybercore-input]";
    private static final long STALE_NANOS = 4_000_000_000L;

    private static final Map<String, FrameState> FRAME_STATES = new ConcurrentHashMap<>();

    private static boolean registered = false;

    private BrowserTextInputTracker() {
    }

    static void register() {
        if (registered) {
            return;
        }
        registered = true;

        MCEF.INSTANCE.getClient().addLoadHandler(new CefLoadHandlerAdapter() {
            @Override
            public void onLoadStart(CefBrowser browser, CefFrame frame, CefRequest.TransitionType transitionType) {
                if (!isOurBrowser(browser)) {
                    return;
                }
                if (frame.isMain()) {
                    FRAME_STATES.clear();
                } else {
                    FRAME_STATES.remove(frame.getIdentifier());
                }
            }

            @Override
            public void onLoadEnd(CefBrowser browser, CefFrame frame, int httpStatusCode) {
                if (isOurBrowser(browser)) {
                    install(frame);
                }
            }
        });

        MCEF.INSTANCE.getClient().addDisplayHandler(new CefDisplayHandlerAdapter() {
            @Override
            public boolean onConsoleMessage(CefBrowser browser, CefSettings.LogSeverity level,
                                            String message, String source, int line) {
                return handleConsoleMessage(message);
            }
        });
    }

    static void install(MCEFBrowser browser) {
        if (browser == null) {
            return;
        }
        install(browser.getMainFrame());
    }

    static void reset() {
        FRAME_STATES.clear();
    }

    static boolean isTextInputFocused() {
        long now = System.nanoTime();
        for (FrameState state : FRAME_STATES.values()) {
            if (state.focused && now - state.lastSeenNanos < STALE_NANOS) {
                return true;
            }
        }
        return false;
    }

    private static boolean isOurBrowser(CefBrowser browser) {
        return browser != null && browser == CybercoreClientClient.browser;
    }

    private static void install(CefFrame frame) {
        if (frame == null) {
            return;
        }
        try {
            frame.executeJavaScript(watcherScript(frame.getIdentifier()), frame.getURL(), 0);
        } catch (Throwable t) {
            LOGGER.debug("Could not install the text input watcher in frame {}.", frame, t);
        }
    }

    private static boolean handleConsoleMessage(String message) {
        if (message == null || !message.startsWith(MESSAGE_PREFIX)) {
            return false;
        }
        String payload = message.substring(MESSAGE_PREFIX.length());
        int separator = payload.lastIndexOf(':');
        if (separator <= 0) {
            return true;
        }
        String token = payload.substring(0, separator);
        boolean focused = "1".equals(payload.substring(separator + 1));

        FrameState state = FRAME_STATES.computeIfAbsent(token, t -> new FrameState());
        state.focused = focused;
        state.lastSeenNanos = System.nanoTime();
        return true;
    }

    private static String watcherScript(String token) {
        return WATCHER_SCRIPT.formatted(jsString(token), jsString(MESSAGE_PREFIX));
    }

    private static String jsString(String s) {
        return "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    private static final String WATCHER_SCRIPT = """
            (function(){
              var TOKEN = %s;
              var PREFIX = %s;
              if (window.__cybercoreInput && window.__cybercoreInput.token === TOKEN) {
                window.__cybercoreInput.resend();
                return;
              }
              var NON_TEXT_INPUTS = ['button','submit','reset','checkbox','radio','file','image','range','color'];
              function isEditable(element) {
                if (!element) return false;
                if (element.isContentEditable) return true;
                var tag = element.tagName;
                if (tag === 'TEXTAREA' || tag === 'SELECT') return true;
                if (tag !== 'INPUT') return false;
                var type = (element.getAttribute('type') || 'text').toLowerCase();
                return NON_TEXT_INPUTS.indexOf(type) < 0;
              }
              function focusedInsideShadowRoots(element) {
                var root = element && element.shadowRoot;
                while (root && root.activeElement) {
                  if (isEditable(root.activeElement)) return true;
                  root = root.activeElement.shadowRoot;
                }
                return false;
              }
              function current() {
                try {
                  var active = document.activeElement;
                  return isEditable(active) || focusedInsideShadowRoots(active);
                } catch (e) {
                  return false;
                }
              }
              var lastSent = null;
              function send(force) {
                var focused = current();
                if (!force && focused === lastSent) return;
                lastSent = focused;
                console.info(PREFIX + TOKEN + ':' + (focused ? '1' : '0'));
              }
              function onFocusChange() {
                setTimeout(function(){ send(false); }, 0);
              }
              window.__cybercoreInput = { token: TOKEN, resend: function(){ send(true); } };
              document.addEventListener('focusin', onFocusChange, true);
              document.addEventListener('focusout', onFocusChange, true);
              window.addEventListener('pagehide', function(){ send(true); }, true);
              setInterval(function(){ send(true); }, 1000);
              send(true);
            })();
            """;

    private static final class FrameState {
        volatile boolean focused;
        volatile long lastSeenNanos;
    }
}
