package ua.rp.chat.microvoxel;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class MicrovoxelProtocol {
    public static final int MAGIC = 0x4D;
    /** Version 6 adds the fluid kind byte to FLUID_UPSERT (lava engine). */
    public static final int VERSION = 6;
    public static final String SYNC_CHANNEL = "rpchat:microvoxels";
    public static final String ACTION_CHANNEL = "rpchat:microvoxel_action";
    public static final int CLEAR = 1;
    public static final int UPSERT = 2;
    public static final int REMOVE = 3;
    public static final int MESSAGE = 4;
    public static final int REGISTER_MATERIAL = 5;
    public static final int BATCH_UPSERT = 6;
    public static final int CLEAR_CHUNK = 7;
    public static final int DELTA_UPSERT = 8;
    /** One all-or-nothing client-visible edit spanning any number of blocks/chunks. */
    public static final int TRANSACTION = 9;
    public static final int EDIT_RESULT = 10;
    /** Opens one ordered, authoritative snapshot delivery. */
    public static final int SNAPSHOT_BEGIN = 11;
    /** Closes a snapshot delivery; the client must acknowledge this id. */
    public static final int SNAPSHOT_END = 12;
    /** Authoritative per-cell mining crack stage (stage -1 clears the crack). */
    public static final int MINE_STAGE = 13;
    /** Authoritative voxel fluid levels for one volume (RLE bytes, 0..16 per cell). */
    public static final int FLUID_UPSERT = 14;
    /** Fluid data dropped (scooped, spilled, evaporated). */
    public static final int FLUID_REMOVE = 15;
    public static final int ACTION_CONVERT = 1;
    public static final int ACTION_REMOVE = 2;
    public static final int ACTION_ADD = 3;
    /** Carves the first 1/16 cell from an eligible, still-vanilla full block. */
    public static final int ACTION_CARVE_STANDARD = 4;
    public static final int ACTION_READY = 5;
    public static final int ACTION_RESYNC_VOLUME = 6;
    public static final int ACTION_RESYNC_CHUNK = 7;
    public static final int ACTION_UNDO = 8;
    public static final int ACTION_REDO = 9;
    public static final int ACTION_BRUSH_REMOVE = 10;
    public static final int ACTION_BRUSH_ADD = 11;
    public static final int ACTION_COPY = 12;
    public static final int ACTION_PASTE = 13;
    public static final int ACTION_SNAPSHOT_ACK = 14;

    private MicrovoxelProtocol() {
    }

    public static byte[] clear() {
        return writeMessage(CLEAR, output -> {
        });
    }

    public static byte[] snapshotBegin(long snapshotId) {
        return writeMessage(SNAPSHOT_BEGIN, output -> output.writeLong(snapshotId));
    }

    public static byte[] snapshotEnd(long snapshotId) {
        return writeMessage(SNAPSHOT_END, output -> output.writeLong(snapshotId));
    }

    public static boolean isSynchronizationAction(int action) {
        return action == ACTION_READY
                || action == ACTION_RESYNC_VOLUME
                || action == ACTION_RESYNC_CHUNK
                || action == ACTION_SNAPSHOT_ACK;
    }

    public static byte[] remove(MicrovoxelKey key) {
        return writeMessage(REMOVE, output -> {
            writePosition(output, key);
        });
    }

    public static byte[] message(String message) {
        return writeMessage(MESSAGE, output -> {
            writeUtf8(output, message);
        });
    }

    public static byte[] registerMaterial(int id, String material) {
        return writeMessage(REGISTER_MATERIAL, output -> {
            writeVarInt(output, id);
            writeUtf8(output, material);
        });
    }

    public static byte[] clearChunk(int chunkX, int chunkZ) {
        return writeMessage(CLEAR_CHUNK, output -> {
            output.writeInt(chunkX);
            output.writeInt(chunkZ);
        });
    }

    public static byte[] upsert(MicrovoxelKey key, MicrovoxelVolume volume) {
        return writeMessage(UPSERT, output -> {
            writePosition(output, key);
            writeVarInt(output, volume.revision());
            writeVarInt(output, volume.palette().size());
            for (String material : volume.palette()) writeUtf8(output, material);
            writeCells(output, volume);
        });
    }

    public static byte[] batchUpsert(int chunkX, int chunkZ,
                                     java.util.List<java.util.Map.Entry<MicrovoxelKey, MicrovoxelVolume>> entries) {
        return writeMessage(BATCH_UPSERT, output -> {
            output.writeInt(chunkX);
            output.writeInt(chunkZ);
            writeVarInt(output, entries.size());
            for (java.util.Map.Entry<MicrovoxelKey, MicrovoxelVolume> entry : entries) {
                MicrovoxelKey key = entry.getKey();
                MicrovoxelVolume volume = entry.getValue();

                int relX = key.x() - (chunkX << 4);
                int relZ = key.z() - (chunkZ << 4);
                output.writeByte(((relX & 15) << 4) | (relZ & 15));
                output.writeShort(key.y());

                writeVarInt(output, volume.revision());
                writeVarInt(output, volume.palette().size());
                for (String material : volume.palette()) writeUtf8(output, material);

                writeCells(output, volume);
            }
        });
    }

    public static byte[] deltaUpsert(int chunkX, int chunkZ, MicrovoxelKey key,
                                     int revision, int cellIndex, String material) {
        return writeMessage(DELTA_UPSERT, output -> {
            output.writeInt(chunkX);
            output.writeInt(chunkZ);
            int relX = key.x() - (chunkX << 4);
            int relZ = key.z() - (chunkZ << 4);
            output.writeByte(((relX & 15) << 4) | (relZ & 15));
            output.writeShort(key.y());
            writeVarInt(output, revision);
            writeVarInt(output, cellIndex);
            writeUtf8(output, material == null ? "" : material);
        });
    }

    public static byte[] editResult(long transactionId, boolean accepted,
                                    MicrovoxelKey key, MicrovoxelVolume volume) {
        return writeMessage(EDIT_RESULT, output -> {
            output.writeLong(transactionId);
            output.writeBoolean(accepted);
            writePosition(output, key);
            output.writeBoolean(volume != null);
            if (volume != null) writeRawVolume(output, volume);
        });
    }

    public static byte[] transaction(long transactionId, java.util.List<StateChange> changes) {
        return writeMessage(TRANSACTION, output -> {
            output.writeLong(transactionId);
            writeVarInt(output, changes.size());
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
        return writeMessage(MINE_STAGE, output -> {
            writePosition(output, key);
            writeVarInt(output, cell);
            output.writeByte(stage);
        });
    }

    public static byte[] fluidUpsert(MicrovoxelKey key, int revision, byte[] levels) {
        return fluidUpsert(key, revision, 0, levels);
    }

    public static byte[] fluidUpsert(MicrovoxelKey key, int revision, int kindCode, byte[] levels) {
        return writeMessage(FLUID_UPSERT, output -> {
            writePosition(output, key);
            writeVarInt(output, revision);
            output.writeByte(kindCode);
            byte[] encoded = encodeLevels(levels);
            writeVarInt(output, encoded.length);
            output.write(encoded);
        });
    }

    public static byte[] fluidRemove(MicrovoxelKey key) {
        return writeMessage(FLUID_REMOVE, output -> {
            writePosition(output, key);
        });
    }

    /**
     * Run-length codec for fluid levels. Levels are smooth (whole basins share one value),
     * so RLE typically compresses 4096 bytes to a handful. Pure and mirrored by the client
     * decoder; round-trip unit-tested.
     */
    public static byte[] encodeLevels(byte[] levels) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                writeVarInt(output, levels.length);
                for (int index = 0; index < levels.length;) {
                    byte level = levels[index];
                    int end = index + 1;
                    while (end < levels.length && levels[end] == level && end - index < 65535) end++;
                    writeVarInt(output, end - index);
                    output.writeByte(level);
                    index = end;
                }
            }
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** Decodes {@link #encodeLevels}; throws on truncation, overflow or trailing bytes. */
    public static byte[] decodeLevels(byte[] encoded) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded))) {
            int total = readVarInt(input);
            if (total < 0 || total > 65536) throw new IOException("Invalid fluid level count");
            byte[] levels = new byte[total];
            int cursor = 0;
            while (cursor < total) {
                int run = readVarInt(input);
                int level = input.readUnsignedByte();
                if (run < 1 || cursor + run > total || level > 16) {
                    throw new IOException("Invalid fluid level run");
                }
                java.util.Arrays.fill(levels, cursor, cursor + run, (byte) level);
                cursor += run;
            }
            if (input.read() != -1) throw new IOException("Trailing fluid level bytes");
            return levels;
        }
    }

    private static void writeRawVolume(DataOutputStream output, MicrovoxelVolume volume) throws IOException {
        writeVarInt(output, volume.revision());
        writeVarInt(output, volume.palette().size());
        for (String material : volume.palette()) writeUtf8(output, material);
        writeCells(output, volume);
    }

    public static void writeVarInt(DataOutputStream out, int value) throws IOException {
        while ((value & 0xFFFFFF80) != 0L) {
            out.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.writeByte(value & 0x7F);
    }

    public static int readVarInt(java.io.DataInputStream in) throws IOException {
        int value = 0;
        int position = 0;
        byte currentByte;
        while (true) {
            currentByte = in.readByte();
            value |= (currentByte & 0x7F) << position;
            if ((currentByte & 0x80) == 0) break;
            position += 7;
            if (position >= 32) throw new IOException("VarInt is too big");
        }
        return value;
    }

    private static void writeCells(DataOutputStream output, MicrovoxelVolume volume) throws IOException {
        byte[] cells = volume.cellsCopy();
        int runs = 0;
        for (int index = 0; index < cells.length;) {
            byte material = cells[index];
            int end = index + 1;
            while (end < cells.length && cells[end] == material && end - index < 65535) end++;
            runs++;
            index = end;
        }
        boolean useRuns = runs * 3 + 2 < cells.length;
        output.writeByte(useRuns ? 1 : 0);
        if (useRuns) {
            writeVarInt(output, runs);
            for (int index = 0; index < cells.length;) {
                byte material = cells[index];
                int end = index + 1;
                while (end < cells.length && cells[end] == material && end - index < 65535) end++;
                writeVarInt(output, end - index);
                output.writeByte(material);
                index = end;
            }
        } else {
            output.write(cells);
        }
    }

    private static void writePosition(DataOutputStream output, MicrovoxelKey key) throws IOException {
        output.writeInt(key.x());
        output.writeInt(key.y());
        output.writeInt(key.z());
    }

    private static void writeUtf8(DataOutputStream output, String value) throws IOException {
        byte[] bytes = String.valueOf(value).getBytes(StandardCharsets.UTF_8);
        writeVarInt(output, bytes.length);
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

    private static byte[] writeMessage(int type, IoWriter writer) {
        return write(output -> {
            output.writeByte(MAGIC);
            writeVarInt(output, VERSION);
            output.writeByte(type);
            writer.write(output);
        });
    }

    @FunctionalInterface
    private interface IoWriter {
        void write(DataOutputStream output) throws IOException;
    }

    public record StateChange(MicrovoxelKey key, MicrovoxelVolume volume) {
    }
}
