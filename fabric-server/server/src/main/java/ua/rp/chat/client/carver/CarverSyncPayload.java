package ua.rp.chat.client.carver;

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
public record CarverSyncPayload(int protocolVersion, int event,
                                    int x, int y, int z, byte[] data)
        implements CustomPacketPayload {
    public static final Type<CarverSyncPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath("rpchat", "carver"));
    public static final StreamCodec<RegistryFriendlyByteBuf, CarverSyncPayload> CODEC = StreamCodec.ofMember(
            CarverSyncPayload::write, CarverSyncPayload::read);

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeVarInt(protocolVersion);
        buffer.writeByte(event);
        buffer.writeInt(x);
        buffer.writeInt(y);
        buffer.writeInt(z);
        buffer.writeByteArray(data == null ? new byte[0] : data);
    }

    private static CarverSyncPayload read(RegistryFriendlyByteBuf buffer) {
        return new CarverSyncPayload(
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

    public static byte[] mirrorData(int axes) {
        return writeData(output -> output.writeByte(axes));
    }

    public static byte[] observedStartData(java.util.UUID playerId, int x, int y, int z,
                                           int totalTicks) {
        return writeData(output -> {
            output.writeLong(playerId.getMostSignificantBits());
            output.writeLong(playerId.getLeastSignificantBits());
            output.writeInt(x);
            output.writeInt(y);
            output.writeInt(z);
            output.writeInt(totalTicks);
        });
    }

    public static byte[] observedEndData(java.util.UUID playerId) {
        return writeData(output -> {
            output.writeLong(playerId.getMostSignificantBits());
            output.writeLong(playerId.getLeastSignificantBits());
        });
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
