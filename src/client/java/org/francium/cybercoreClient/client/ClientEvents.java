package org.francium.cybercoreClient.client;

import net.ccbluex.liquidbounce.mcef.cef.MCEFBrowser;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ClientEvents {

    private static final Logger LOGGER = LoggerFactory.getLogger("cybercore-client");

    private static final String DOM_EVENT = "cybercore:event";

    private static final char LINE_SEPARATOR = 0x2028;
    private static final char PARAGRAPH_SEPARATOR = 0x2029;

    private ClientEvents() {
    }

    static void register() {
        PayloadTypeRegistry.clientboundPlay()
                .register(ClientEventPayload.TYPE, ClientEventPayload.CODEC);

        ClientPlayNetworking.registerGlobalReceiver(
                ClientEventPayload.TYPE,
                (payload, context) -> {
                    if (!CybercoreServer.isConnected()) {
                        LOGGER.warn("Ignored a Cybercore event from a server that never greeted us.");
                        return;
                    }

                    dispatch(payload.json());
                }
        );
    }

    /** Both browsers: each page decides for itself what an event means on its surface. */
    private static void dispatch(String json) {
        MCEFBrowser overlay = CybercoreClientClient.overlayBrowser;
        MCEFBrowser platform = CybercoreClientClient.platformBrowser;
        if (overlay == null && platform == null) {
            LOGGER.debug("Dropped a Cybercore event, no browser is up: {}", json);
            return;
        }

        String script = dispatchScript(json);
        if (overlay != null) {
            overlay.executeJavaScript(script, overlay.getURL(), 0);
        }
        if (platform != null) {
            platform.executeJavaScript(script, platform.getURL(), 0);
        }
    }

    private static String dispatchScript(String json) {
        return "(function(){try{"
                + "var d=JSON.parse(" + jsString(json) + ");"
                + "window.dispatchEvent(new CustomEvent('" + DOM_EVENT + "',{detail:d}));"
                + "}catch(e){}})();";
    }

    private static String jsString(String value) {
        StringBuilder out = new StringBuilder(value.length() + 16).append('\'');

        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);

            if (c == '\\') {
                out.append("\\\\");
            } else if (c == '\'') {
                out.append("\\'");
            } else if (c == '\n') {
                out.append("\\n");
            } else if (c == '\r') {
                out.append("\\r");
            } else if (c == LINE_SEPARATOR) {
                out.append("\\u2028");
            } else if (c == PARAGRAPH_SEPARATOR) {
                out.append("\\u2029");
            } else {
                out.append(c);
            }
        }

        return out.append('\'').toString();
    }
}
