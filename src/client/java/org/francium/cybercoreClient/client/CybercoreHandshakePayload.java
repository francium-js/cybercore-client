package org.francium.cybercoreClient.client;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record CybercoreHandshakePayload(int protocolVersion) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<CybercoreHandshakePayload> TYPE =
            new CustomPacketPayload.Type<>(
                    Identifier.fromNamespaceAndPath("cybercore", "handshake"));

    public static final StreamCodec<FriendlyByteBuf, CybercoreHandshakePayload> CODEC =
            CustomPacketPayload.codec(CybercoreHandshakePayload::write, CybercoreHandshakePayload::read);

    private static CybercoreHandshakePayload read(FriendlyByteBuf buf) {
        return new CybercoreHandshakePayload(buf.readInt());
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeInt(protocolVersion);
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
