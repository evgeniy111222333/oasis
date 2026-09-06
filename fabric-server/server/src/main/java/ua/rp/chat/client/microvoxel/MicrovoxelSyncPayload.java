package ua.rp.chat.client.microvoxel;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record MicrovoxelSyncPayload(byte[] data) implements CustomPacketPayload {
    private static final int MAX_BYTES = 1_048_576;
    public static final Type<MicrovoxelSyncPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath("rpchat", "microvoxels"));
    public static final StreamCodec<RegistryFriendlyByteBuf, MicrovoxelSyncPayload> CODEC = StreamCodec.ofMember(
            MicrovoxelSyncPayload::write, MicrovoxelSyncPayload::read);

    private void write(RegistryFriendlyByteBuf buffer) {
        if (data.length > MAX_BYTES) throw new IllegalArgumentException("Microvoxel payload exceeds safety limit");
        buffer.writeBytes(data);
    }

    private static MicrovoxelSyncPayload read(RegistryFriendlyByteBuf buffer) {
        int length = buffer.readableBytes();
        if (length < 1 || length > MAX_BYTES) throw new IllegalArgumentException("Invalid microvoxel payload size");
        byte[] data = new byte[length];
        buffer.readBytes(data);
        return new MicrovoxelSyncPayload(data);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
