package ua.rp.chat.client.rpfeed;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Raw bytes are intentionally kept protocol-compatible with Paper plugin messages. */
public record RpChatFeedPayload(byte[] data) implements CustomPacketPayload {
    public static final Type<RpChatFeedPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("rpchat", "rp_feed"));
    public static final StreamCodec<RegistryFriendlyByteBuf, RpChatFeedPayload> CODEC = StreamCodec.ofMember(
            RpChatFeedPayload::write, RpChatFeedPayload::read);

    private void write(RegistryFriendlyByteBuf buffer) { buffer.writeBytes(data); }
    private static RpChatFeedPayload read(RegistryFriendlyByteBuf buffer) {
        if (buffer.readableBytes() < 22 || buffer.readableBytes() > 2048) throw new IllegalArgumentException("Invalid RP feed packet");
        byte[] data = new byte[buffer.readableBytes()];
        buffer.readBytes(data);
        return new RpChatFeedPayload(data);
    }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
