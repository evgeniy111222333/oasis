package ua.rp.chat.client;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record AcquaintancePayload(String json) implements CustomPacketPayload {
    public static final Type<AcquaintancePayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("rpchat", "acq_state"));
    public static final StreamCodec<RegistryFriendlyByteBuf, AcquaintancePayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8.cast(),
            AcquaintancePayload::json,
            AcquaintancePayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
