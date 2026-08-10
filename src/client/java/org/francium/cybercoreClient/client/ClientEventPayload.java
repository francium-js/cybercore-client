package org.francium.cybercoreClient.client;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.nio.charset.StandardCharsets;

public record ClientEventPayload(String json) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ClientEventPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    Identifier.fromNamespaceAndPath("cybercore", "event"));

    public static final StreamCodec<FriendlyByteBuf, ClientEventPayload> CODEC =
            CustomPacketPayload.codec(ClientEventPayload::write, ClientEventPayload::read);

    private static ClientEventPayload read(FriendlyByteBuf buf) {
        byte[] bytes = new byte[buf.readableBytes()];
        buf.readBytes(bytes);

        return new ClientEventPayload(new String(bytes, StandardCharsets.UTF_8));
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeBytes(json.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
