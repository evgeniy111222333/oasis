package ua.rp.chat.client.microvoxel;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record MicrovoxelActionPayload(int action, int x, int y, int z, int cell, int revision,
                                      float lookX, float lookY, float lookZ,
                                      float eyeX, float eyeY, float eyeZ)
        implements CustomPacketPayload {
    public static final Type<MicrovoxelActionPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath("rpchat", "microvoxel_action"));
    public static final StreamCodec<RegistryFriendlyByteBuf, MicrovoxelActionPayload> CODEC = StreamCodec.ofMember(
            MicrovoxelActionPayload::write, MicrovoxelActionPayload::read);

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeByte(action);
        buffer.writeInt(x);
        buffer.writeInt(y);
        buffer.writeInt(z);
        buffer.writeShort(cell);
        buffer.writeInt(revision);
        buffer.writeFloat(lookX);
        buffer.writeFloat(lookY);
        buffer.writeFloat(lookZ);
        buffer.writeFloat(eyeX);
        buffer.writeFloat(eyeY);
        buffer.writeFloat(eyeZ);
    }

    private static MicrovoxelActionPayload read(RegistryFriendlyByteBuf buffer) {
        return new MicrovoxelActionPayload(
                buffer.readUnsignedByte(), buffer.readInt(), buffer.readInt(), buffer.readInt(),
                buffer.readUnsignedShort(), buffer.readInt(),
                buffer.readFloat(), buffer.readFloat(), buffer.readFloat(),
                buffer.readFloat(), buffer.readFloat(), buffer.readFloat());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
