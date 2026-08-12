package org.francium.cybercoreClient.client;

import net.ccbluex.liquidbounce.mcef.MCEF;
import org.cef.CefSettings;
import org.cef.browser.CefBrowser;
import org.cef.handler.CefDisplayHandlerAdapter;

import java.util.ArrayList;
import java.util.List;

/**
 * Live toast bounding boxes from the overlay page - the click router's map.
 *
 * <p>The overlay page reports "[cc-toast-rects]x,y,w,h;x,y,w,h" (client coordinates, integers,
 * empty payload when no toasts) on every change. A click landing inside any of them belongs to
 * the toast layer; everything else falls through to whatever the player sees underneath. Client
 * coordinates are exactly what the mod feeds browsers as mouse positions, so no conversion
 * happens here.
 */
final class BrowserToastRectsBridge {

    private static final String MESSAGE_PREFIX = "[cc-toast-rects]";

    /** A little slack around each toast: sloppy clicks near the border still count as "on it". */
    private static final int HIT_PADDING = 2;

    private static volatile int[][] rects = new int[0][];

    private static boolean registered = false;

    private BrowserToastRectsBridge() {
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
                if (!CybercoreClientClient.isOverlayBrowser(browser)
                        || message == null || !message.startsWith(MESSAGE_PREFIX)) {
                    return false;
                }
                rects = parse(message.substring(MESSAGE_PREFIX.length()));
                return true;
            }
        });
    }

    static void reset() {
        rects = new int[0][];
    }

    static boolean hit(int x, int y) {
        for (int[] rect : rects) {
            if (x >= rect[0] - HIT_PADDING && x <= rect[0] + rect[2] + HIT_PADDING
                    && y >= rect[1] - HIT_PADDING && y <= rect[1] + rect[3] + HIT_PADDING) {
                return true;
            }
        }
        return false;
    }

    private static int[][] parse(String payload) {
        if (payload.isBlank()) {
            return new int[0][];
        }
        List<int[]> parsed = new ArrayList<>();
        for (String part : payload.split(";")) {
            String[] numbers = part.split(",");
            if (numbers.length != 4) {
                continue;
            }
            try {
                parsed.add(new int[]{
                        Integer.parseInt(numbers[0].trim()),
                        Integer.parseInt(numbers[1].trim()),
                        Integer.parseInt(numbers[2].trim()),
                        Integer.parseInt(numbers[3].trim())});
            } catch (NumberFormatException ignored) {
                // A malformed rect is dropped; the rest of the report stays usable.
            }
        }
        return parsed.toArray(new int[0][]);
    }
}
