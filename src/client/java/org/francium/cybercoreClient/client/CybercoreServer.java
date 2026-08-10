package org.francium.cybercoreClient.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CybercoreServer {

    private static final Logger LOGGER = LoggerFactory.getLogger("cybercore-client");

    private static final int PROTOCOL_VERSION = 1;

    private static volatile boolean connected;

    private CybercoreServer() {
    }

    public static boolean isConnected() {
        return connected;
    }

    static void register() {
        PayloadTypeRegistry.clientboundPlay()
                .register(CybercoreHandshakePayload.TYPE, CybercoreHandshakePayload.CODEC);

        ClientPlayNetworking.registerGlobalReceiver(
                CybercoreHandshakePayload.TYPE,
                (payload, context) -> onGreeting(payload.protocolVersion())
        );

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            connected = false;
            LOGGER.info("Left the server, Cybercore features are off again.");
        });
    }

    private static void onGreeting(int serverProtocol) {
        connected = true;

        if (serverProtocol != PROTOCOL_VERSION) {
            LOGGER.warn(
                    "Cybercore server speaks protocol {} but this mod speaks {} — consider updating.",
                    serverProtocol, PROTOCOL_VERSION
            );

            return;
        }

        LOGGER.info("Cybercore server detected, server-specific features are on.");
    }
}
