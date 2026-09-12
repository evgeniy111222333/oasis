package ua.rp.chat.microvoxel;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Server-side microvoxel message builders. Frame layout, message/action codes, capabilities and
 * the primitive codecs live in the shared {@link MicrovoxelWire}; this class only serialises the
 * server-produced payloads so no side keeps a private mirror of the wire contract.
 */
public final class MicrovoxelProtocol {
    public static final int MAGIC = MicrovoxelWire.MAGIC;
    public static final int VERSION = MicrovoxelWire.MAJOR;
    public static final int MINOR = MicrovoxelWire.MINOR;
    public static final String SYNC_CHANNEL = "rpchat:microvoxels";
    public static final String ACTION_CHANNEL = "rpchat:microvoxel_action";
    public static final int CLEAR = MicrovoxelWire.CLEAR;
    public static final int UPSERT = MicrovoxelWire.UPSERT;
    public static final int REMOVE = MicrovoxelWire.REMOVE;
    public static final int MESSAGE = MicrovoxelWire.MESSAGE;
    public static final int BATCH_UPSERT = MicrovoxelWire.BATCH_UPSERT;
    public static final int CLEAR_CHUNK = MicrovoxelWire.CLEAR_CHUNK;
    public static final int DELTA_UPSERT = MicrovoxelWire.DELTA_UPSERT;
    /** One all-or-nothing client-visible edit spanning any number of blocks/chunks. */
    public static final int TRANSACTION = MicrovoxelWire.TRANSACTION;
    public static final int EDIT_RESULT = MicrovoxelWire.EDIT_RESULT;
    public static final int SNAPSHOT_BEGIN = MicrovoxelWire.SNAPSHOT_BEGIN;
    public static final int SNAPSHOT_END = MicrovoxelWire.SNAPSHOT_END;
    public static final int MINE_STAGE = MicrovoxelWire.MINE_STAGE;
    public static final int FLUID_UPSERT = MicrovoxelWire.FLUID_UPSERT;
    public static final int FLUID_REMOVE = MicrovoxelWire.FLUID_REMOVE;
    public static final int HELLO_ACK = MicrovoxelWire.HELLO_ACK;
    public static final int ACTION_CONVERT = MicrovoxelWire.ACTION_CONVERT;
    public static final int ACTION_REMOVE = MicrovoxelWire.ACTION_REMOVE;
    public static final int ACTION_ADD = MicrovoxelWire.ACTION_ADD;
    public static final int ACTION_CARVE_STANDARD = MicrovoxelWire.ACTION_CARVE_STANDARD;
    public static final int ACTION_READY = MicrovoxelWire.ACTION_READY;
    public static final int ACTION_RESYNC_VOLUME = MicrovoxelWire.ACTION_RESYNC_VOLUME;
    public static final int ACTION_RESYNC_CHUNK = MicrovoxelWire.ACTION_RESYNC_CHUNK;
    public static final int ACTION_UNDO = MicrovoxelWire.ACTION_UNDO;
    public static final int ACTION_REDO = MicrovoxelWire.ACTION_REDO;
    public static final int ACTION_BRUSH_REMOVE = MicrovoxelWire.ACTION_BRUSH_REMOVE;
    public static final int ACTION_BRUSH_ADD = MicrovoxelWire.ACTION_BRUSH_ADD;
    public static final int ACTION_COPY = MicrovoxelWire.ACTION_COPY;
    public static final int ACTION_PASTE = MicrovoxelWire.ACTION_PASTE;
    public static final int ACTION_SNAPSHOT_ACK = MicrovoxelWire.ACTION_SNAPSHOT_ACK;
    public static final int ACTION_HELLO = MicrovoxelWire.ACTION_HELLO;
    public static final int ACTION_SET_SHAPE = MicrovoxelWire.ACTION_SET_SHAPE;

    private MicrovoxelProtocol() {
    }

    public static byte[] clear() {
        return message(CLEAR, output -> {
        });
    }

    public static byte[] helloAck(int major, int minor, int capabilities) {
        return message(HELLO_ACK, output -> {
            MicrovoxelWire.writeVarInt(output, major);
            MicrovoxelWire.writeVarInt(output, minor);
            MicrovoxelWire.writeVarInt(output, capabilities);
        });
    }

    public static byte[] snapshotBegin(long snapshotId) {
        return message(SNAPSHOT_BEGIN, output -> output.writeLong(snapshotId));
    }

    public static byte[] snapshotEnd(long snapshotId) {
        return message(SNAPSHOT_END, output -> output.writeLong(snapshotId));
    }

    public static boolean isSynchronizationAction(int action) {
        return action == ACTION_READY
                || action == ACTION_HELLO
                || action == ACTION_RESYNC_VOLUME
                || action == ACTION_RESYNC_CHUNK
                || action == ACTION_SNAPSHOT_ACK;
    }

    public static byte[] remove(MicrovoxelKey key) {
        return message(REMOVE, output -> writePosition(output, key));
    }

    public static byte[] message(String message) {
        return message(MESSAGE, output -> writeUtf8(output, message));
    }

    public static byte[] clearChunk(int chunkX, int chunkZ) {
        return message(CLEAR_CHUNK, output -> {
            output.writeInt(chunkX);
            output.writeInt(chunkZ);
        });
    }

    public static byte[] upsert(MicrovoxelKey key, MicrovoxelVolume volume) {
        return message(UPSERT, output -> {
            writePosition(output, key);
            writeRawVolume(output, volume);
        });
    }

    public static byte[] batchUpsert(int chunkX, int chunkZ,
                                     java.util.List<java.util.Map.Entry<MicrovoxelKey, MicrovoxelVolume>> entries) {
        return message(BATCH_UPSERT, output -> {
            output.writeInt(chunkX);
            output.writeInt(chunkZ);
            MicrovoxelWire.writeVarInt(output, entries.size());
            for (java.util.Map.Entry<MicrovoxelKey, MicrovoxelVolume> entry : entries) {
                MicrovoxelKey key = entry.getKey();
                MicrovoxelVolume volume = entry.getValue();
                int relX = key.x() - (chunkX << 4);
                int relZ = key.z() - (chunkZ << 4);
                output.writeByte(((relX & 15) << 4) | (relZ & 15));
                output.writeShort(key.y());
                writeRawVolume(output, volume);
            }
        });
    }

    public static byte[] deltaUpsert(int chunkX, int chunkZ, MicrovoxelKey key,
                                     int revision, int cellIndex, String material) {
        return message(DELTA_UPSERT, output -> {
            output.writeInt(chunkX);
            output.writeInt(chunkZ);
            int relX = key.x() - (chunkX << 4);
            int relZ = key.z() - (chunkZ << 4);
            output.writeByte(((relX & 15) << 4) | (relZ & 15));
            output.writeShort(key.y());
            MicrovoxelWire.writeVarInt(output, revision);
            MicrovoxelWire.writeVarInt(output, cellIndex);
            writeUtf8(output, material == null ? "" : material);
        });
    }

    public static byte[] editResult(long transactionId, boolean accepted,
                                    MicrovoxelKey key, MicrovoxelVolume volume) {
        return message(EDIT_RESULT, output -> {
            output.writeLong(transactionId);
            output.writeBoolean(accepted);
            writePosition(output, key);
            output.writeBoolean(volume != null);
            if (volume != null) writeRawVolume(output, volume);
        });
    }

    public static byte[] transaction(long transactionId, java.util.List<StateChange> changes) {
        return message(TRANSACTION, output -> {
            output.writeLong(transactionId);
            MicrovoxelWire.writeVarInt(output, changes.size());
            for (StateChange change : changes) {
                writePosition(output, change.key());
                MicrovoxelVolume volume = change.volume();
                output.writeBoolean(volume != null);
                if (volume == null) continue;
                writeRawVolume(output, volume);
            }
        });
    }

    public static byte[] mineStage(MicrovoxelKey key, int cell, int stage) {
        return message(MINE_STAGE, output -> {
            writePosition(output, key);
            MicrovoxelWire.writeVarInt(output, cell);
            output.writeByte(stage);
        });
    }

    public static byte[] fluidUpsert(MicrovoxelKey key, int revision, byte[] levels) {
        return fluidUpsert(key, revision, 0, levels);
    }

    public static byte[] fluidUpsert(MicrovoxelKey key, int revision, int kindCode, byte[] levels) {
        return message(FLUID_UPSERT, output -> {
            writePosition(output, key);
            MicrovoxelWire.writeVarInt(output, revision);
            output.writeByte(kindCode);
            byte[] encoded = MicrovoxelWire.encodeLevels(levels);
            MicrovoxelWire.writeVarInt(output, encoded.length);
            output.write(encoded);
        });
    }

    public static byte[] fluidRemove(MicrovoxelKey key) {
        return message(FLUID_REMOVE, output -> writePosition(output, key));
    }

    // Delegating codec helpers keep existing server call sites untouched.
    public static byte[] encodeLevels(byte[] levels) {
        return MicrovoxelWire.encodeLevels(levels);
    }

    public static byte[] decodeLevels(byte[] encoded) throws IOException {
        return MicrovoxelWire.decodeLevels(encoded);
    }

    public static void writeVarInt(DataOutputStream out, int value) throws IOException {
        MicrovoxelWire.writeVarInt(out, value);
    }

    public static int readVarInt(DataInputStream in) throws IOException {
        return MicrovoxelWire.readVarInt(in);
    }

    private static void writeRawVolume(DataOutputStream output, MicrovoxelVolume volume) throws IOException {
        MicrovoxelWire.writeVarInt(output, volume.revision());
        MicrovoxelWire.writeVarInt(output, volume.palette().size());
        for (String material : volume.palette()) writeUtf8(output, material);
        output.write(MicrovoxelWire.encodeCells(volume.cellsCopy()));
        // Optional geometry section: an all-cube volume writes a single false byte (zero-cost).
        MicrovoxelGeometry geometry = volume.geometryOrNull();
        boolean hasGeometry = geometry != null && !geometry.isEmpty();
        output.writeBoolean(hasGeometry);
        if (hasGeometry) {
            byte[] encoded = geometry.encode();
            MicrovoxelWire.writeVarInt(output, encoded.length);
            output.write(encoded);
        }
    }

    private static void writePosition(DataOutputStream output, MicrovoxelKey key) throws IOException {
        output.writeInt(key.x());
        output.writeInt(key.y());
        output.writeInt(key.z());
    }

    private static void writeUtf8(DataOutputStream output, String value) throws IOException {
        byte[] bytes = String.valueOf(value).getBytes(StandardCharsets.UTF_8);
        MicrovoxelWire.writeVarInt(output, bytes.length);
        output.write(bytes);
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

    private static byte[] message(int type, IoWriter writer) {
        return MicrovoxelWire.frame(type, write(writer));
    }

    @FunctionalInterface
    private interface IoWriter {
        void write(DataOutputStream output) throws IOException;
    }

    public record StateChange(MicrovoxelKey key, MicrovoxelVolume volume) {
    }
}
