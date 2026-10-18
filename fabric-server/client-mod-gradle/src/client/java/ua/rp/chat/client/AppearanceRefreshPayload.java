package ua.rp.chat.client;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Server broadcast emitted after an appearance edit has reached persistent storage. */
public record AppearanceRefreshPayload(String playerUuid) implements CustomPacketPayload {
    public static final Type<AppearanceRefreshPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("rpchat", "appearance_refresh"));
    public static final StreamCodec<RegistryFriendlyByteBuf, AppearanceRefreshPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8.cast(), AppearanceRefreshPayload::playerUuid, AppearanceRefreshPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
