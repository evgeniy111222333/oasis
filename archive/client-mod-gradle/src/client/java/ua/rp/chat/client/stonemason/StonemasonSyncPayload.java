package ua.rp.chat.client.stonemason;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Server-to-client drafting events. Server mirror: this file is duplicated verbatim in
 * the client module; the channel id and field order must stay identical on both sides.
 */
public record StonemasonSyncPayload(int protocolVersion, int event,
                                    int x, int y, int z, byte[] data)
        implements CustomPacketPayload {
    public static final Type<StonemasonSyncPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath("rpchat", "stonemason"));
    public static final StreamCodec<RegistryFriendlyByteBuf, StonemasonSyncPayload> CODEC = StreamCodec.ofMember(
            StonemasonSyncPayload::write, StonemasonSyncPayload::read);

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeVarInt(protocolVersion);
        buffer.writeByte(event);
        buffer.writeInt(x);
        buffer.writeInt(y);
        buffer.writeInt(z);
        buffer.writeByteArray(data == null ? new byte[0] : data);
    }

    private static StonemasonSyncPayload read(RegistryFriendlyByteBuf buffer) {
        return new StonemasonSyncPayload(
                buffer.readVarInt(), buffer.readUnsignedByte(),
                buffer.readInt(), buffer.readInt(), buffer.readInt(),
                buffer.readByteArray());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static byte[] openData(String materialId, int timeoutTicks) {
        return writeData(output -> {
            byte[] id = materialId.getBytes(StandardCharsets.UTF_8);
            output.writeInt(id.length);
            output.write(id);
            output.writeInt(timeoutTicks);
        });
    }

    public static byte[] estimateData(int cells, float seconds, float stamina, int ticks) {
        return writeData(output -> {
            output.writeInt(cells);
            output.writeFloat(seconds);
            output.writeFloat(stamina);
            output.writeInt(ticks);
        });
    }

    public static byte[] workStartData(int totalTicks) {
        return writeData(output -> output.writeInt(totalTicks));
    }

    public static byte[] progressData(int doneTicks, int totalTicks) {
        return writeData(output -> {
            output.writeInt(doneTicks);
            output.writeInt(totalTicks);
        });
    }

    public static byte[] doneData(int removedCells) {
        return writeData(output -> output.writeInt(removedCells));
    }

    public static byte[] closeData(int reasonOrdinal) {
        return writeData(output -> output.writeByte(reasonOrdinal));
    }

    private static byte[] writeData(IoWriter writer) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                writer.write(output);
            }
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    @FunctionalInterface
    private interface IoWriter {
        void write(DataOutputStream output) throws IOException;
    }
}
