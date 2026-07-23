package ua.rp.chat.client.heavyhammer;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record HeavyHammerSyncPayload(byte[] data) implements CustomPacketPayload {
    private static final int MAX_BYTES = 64;
    public static final Type<HeavyHammerSyncPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath("rpchat", "hammer_sync"));
    public static final StreamCodec<RegistryFriendlyByteBuf, HeavyHammerSyncPayload> CODEC = StreamCodec.ofMember(
            HeavyHammerSyncPayload::write, HeavyHammerSyncPayload::read);

    private void write(RegistryFriendlyByteBuf buffer) {
        if (data.length < 1 || data.length > MAX_BYTES) throw new IllegalArgumentException("Недопустимый пакет молота");
        buffer.writeBytes(data);
    }

    private static HeavyHammerSyncPayload read(RegistryFriendlyByteBuf buffer) {
        int length = buffer.readableBytes();
        if (length < 1 || length > MAX_BYTES) throw new IllegalArgumentException("Недопустимый размер пакета молота");
        byte[] data = new byte[length];
        buffer.readBytes(data);
        return new HeavyHammerSyncPayload(data);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
