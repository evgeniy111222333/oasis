package ua.rp.chat.client;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record AuthPayload(String authUrl) implements CustomPacketPayload {
    public static final Type<AuthPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("rpchat", "auth_init"));
    public static final StreamCodec<RegistryFriendlyByteBuf, AuthPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8.cast(),
            AuthPayload::authUrl,
            AuthPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
