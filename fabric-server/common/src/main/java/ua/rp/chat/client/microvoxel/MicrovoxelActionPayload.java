package ua.rp.chat.client.microvoxel;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record MicrovoxelActionPayload(int protocolVersion, long transactionId,
                                      int action, int x, int y, int z, int cell, String material,
                                      int revision,
                                      float lookX, float lookY, float lookZ,
                                      float eyeX, float eyeY, float eyeZ)
        implements CustomPacketPayload {
    public static final Type<MicrovoxelActionPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath("rpchat", "microvoxel_action"));
    public static final StreamCodec<RegistryFriendlyByteBuf, MicrovoxelActionPayload> CODEC = StreamCodec.ofMember(
            MicrovoxelActionPayload::write, MicrovoxelActionPayload::read);

    /** Backwards-compatible constructor for actions that carry no material (everything but place). */
    public MicrovoxelActionPayload(int protocolVersion, long transactionId,
                                   int action, int x, int y, int z, int cell, int revision,
                                   float lookX, float lookY, float lookZ,
                                   float eyeX, float eyeY, float eyeZ) {
        this(protocolVersion, transactionId, action, x, y, z, cell, "", revision,
                lookX, lookY, lookZ, eyeX, eyeY, eyeZ);
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeVarInt(protocolVersion);
        buffer.writeVarLong(transactionId);
        buffer.writeByte(action);
        buffer.writeInt(x);
        buffer.writeInt(y);
        buffer.writeInt(z);
        // VarInt, not short: SET_SHAPE packs the shape id above bit 12 and GENERATE packs its
        // dimensions up to bit 30, so a 16-bit cell field would silently drop both.
        buffer.writeVarInt(cell);
        buffer.writeUtf(material == null ? "" : material);
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
                buffer.readVarInt(), buffer.readVarLong(),
                buffer.readUnsignedByte(), buffer.readInt(), buffer.readInt(), buffer.readInt(),
                buffer.readVarInt(), buffer.readUtf(), buffer.readInt(),
                buffer.readFloat(), buffer.readFloat(), buffer.readFloat(),
                buffer.readFloat(), buffer.readFloat(), buffer.readFloat());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
