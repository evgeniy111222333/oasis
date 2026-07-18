package ua.rp.chat.heavyhammer;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.UUID;

public final class HeavyHammerProtocol {
    public static final String ACTION_CHANNEL = "rpchat:hammer_action";
    public static final String SYNC_CHANNEL = "rpchat:hammer_sync";
    public static final int START = 1;
    public static final int IMPACT = 2;
    public static final int CANCEL = 3;
    public static final int CARRY = 4;

    private HeavyHammerProtocol() {
    }

    public static byte[] start(UUID playerId, int sequence, int durationTicks, int impactTick,
                               int blockX, int blockY, int blockZ, int cell, Face face) {
        return write(output -> {
            output.writeByte(START);
            writeUuid(output, playerId);
            output.writeInt(sequence);
            output.writeShort(durationTicks);
            output.writeShort(impactTick);
            output.writeInt(blockX);
            output.writeInt(blockY);
            output.writeInt(blockZ);
            output.writeShort(cell);
            output.writeByte(face.ordinal());
        });
    }

    public enum Face {
        DOWN, UP, NORTH, SOUTH, WEST, EAST
    }

    public static byte[] impact(UUID playerId, int sequence, boolean success) {
        return write(output -> {
            output.writeByte(IMPACT);
            writeUuid(output, playerId);
            output.writeInt(sequence);
            output.writeBoolean(success);
        });
    }

    public static byte[] cancel(UUID playerId, int sequence) {
        return write(output -> {
            output.writeByte(CANCEL);
            writeUuid(output, playerId);
            output.writeInt(sequence);
        });
    }

    public static byte[] carry(UUID playerId, int revision, boolean present, boolean selected) {
        return write(output -> {
            output.writeByte(CARRY);
            writeUuid(output, playerId);
            output.writeInt(revision);
            output.writeBoolean(present);
            output.writeBoolean(selected);
        });
    }

    private static void writeUuid(DataOutputStream output, UUID id) throws IOException {
        output.writeLong(id.getMostSignificantBits());
        output.writeLong(id.getLeastSignificantBits());
    }

    private static byte[] write(IoWriter writer) {
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
