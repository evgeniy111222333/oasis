package ua.rp.chat.microvoxel;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.Arrays;

/**
 * Single source of truth for the microvoxel wire protocol: frame layout, message/action codes,
 * capability bitset, and the primitive codecs (VarInt, RLE cells, RLE fluid levels).
 *
 * <p>Client and server compile this exact class; no side keeps a private mirror of the constants
 * or the codecs. The frame is length-prefixed so an unknown message type can be skipped without
 * desynchronising the stream, and every optional message is gated by a capability negotiated at
 * handshake time instead of a single global version gate.</p>
 *
 * <pre>
 * frame := MAGIC(1) | VarInt major | VarInt minor | VarInt frameLen | type(1) | payload
 * </pre>
 *
 * <p>{@code frameLen} counts the bytes of {@code type + payload}.</p>
 */
public final class MicrovoxelWire {
    public static final int MAGIC = 0x4D;
    /** Breaking frame/encoding revisions. Bumped when the layout above changes. */
    public static final int MAJOR = 8;
    /** Additive revisions: new message types/capabilities, no layout change. */
    public static final int MINOR = 0;
    /** Oldest major this build can still read (length-prefixed frames are major 7+). */
    public static final int MIN_SUPPORTED_MAJOR = 8;

    // Server -> client message types.
    public static final int CLEAR = 1;
    public static final int UPSERT = 2;
    public static final int REMOVE = 3;
    public static final int MESSAGE = 4;
    public static final int BATCH_UPSERT = 6;
    public static final int CLEAR_CHUNK = 7;
    public static final int DELTA_UPSERT = 8;
    public static final int TRANSACTION = 9;
    public static final int EDIT_RESULT = 10;
    public static final int SNAPSHOT_BEGIN = 11;
    public static final int SNAPSHOT_END = 12;
    public static final int MINE_STAGE = 13;
    public static final int FLUID_UPSERT = 14;
    public static final int FLUID_REMOVE = 15;
    /** Handshake reply: the capabilities the server will honour for this client. */
    public static final int HELLO_ACK = 16;

    // Client -> server action codes.
    public static final int ACTION_CONVERT = 1;
    public static final int ACTION_REMOVE = 2;
    public static final int ACTION_ADD = 3;
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
    /** Handshake: declares the client's major/minor and capability bitset. */
    public static final int ACTION_HELLO = 15;
    /** Sets (or clears) the geometry shape of one occupied cell. Payload: cell VarInt, shape VarInt. */
    public static final int ACTION_SET_SHAPE = 16;
    /** Generates a structure (ramp/column/roof) as one transaction. Payload: packed params VarInt. */
    public static final int ACTION_GENERATE = 17;

    // Capabilities. The negotiated set is the intersection of both sides' advertisements.
    public static final int CAP_UPSERT = 1;
    public static final int CAP_DELTA = 1 << 1;
    public static final int CAP_BATCH = 1 << 2;
    public static final int CAP_TRANSACTION = 1 << 3;
    public static final int CAP_EDIT_RESULT = 1 << 4;
    public static final int CAP_SNAPSHOT = 1 << 5;
    public static final int CAP_FLUID = 1 << 6;
    public static final int CAP_MINE_STAGE = 1 << 7;
    public static final int CAP_CARVER = 1 << 8;
    /** Per-cell geometry shapes (tier S/D) in the volume body and the SET_SHAPE action. */
    public static final int CAP_GEOMETRY = 1 << 9;

    /** Everything a fully featured client can consume. */
    public static final int CAP_ALL = CAP_UPSERT | CAP_DELTA | CAP_BATCH | CAP_TRANSACTION
            | CAP_EDIT_RESULT | CAP_SNAPSHOT | CAP_FLUID | CAP_MINE_STAGE | CAP_CARVER | CAP_GEOMETRY;

    /** Capabilities the shipped client implements (every advertised feature, crack overlay included). */
    public static final int CLIENT_CAPABILITIES = CAP_ALL;

    private MicrovoxelWire() {
    }

    public static boolean supports(int capabilitySet, int capability) {
        return (capabilitySet & capability) == capability;
    }

    /** Wire compatibility test for a peer's announced major. */
    public static boolean compatibleMajor(int peerMajor) {
        return peerMajor >= MIN_SUPPORTED_MAJOR && peerMajor <= MAJOR;
    }

    // ===== Packed geometry action payloads =====
    // Both sides must agree bit for bit, so the packing lives here (single source of truth) and is
    // unit-tested. The packed int rides the action payload's VarInt cell field; it must NEVER be
    // narrowed to 16 bits, since GENERATE uses bits up to 30.

    /** Action-cell layout: SET_SHAPE = cell[0..11] | shape[12..27]. */
    public static int packSetShape(int cell, int shapeId) {
        if (cell < 0 || cell >= MicrovoxelShape.SUB_COUNT) {
            throw new IllegalArgumentException("Invalid microvoxel cell " + cell);
        }
        if (shapeId < 0 || shapeId >= MicrovoxelShape.count()) {
            throw new IllegalArgumentException("Invalid microvoxel shape " + shapeId);
        }
        return (cell & 0x0FFF) | (shapeId << 12);
    }

    public static int shapeCell(int packed) {
        return packed & 0x0FFF;
    }

    public static int shapeId(int packed) {
        return (packed >>> 12) & 0xFFFF;
    }

    /** Action-cell layout: GENERATE = cell[0..11] | type[12..13] | facing[14..15] | dims[16..30]. */
    public static int packGenerate(int cell, int type, int facing, int length, int width, int height) {
        if (cell < 0 || cell >= MicrovoxelShape.SUB_COUNT) {
            throw new IllegalArgumentException("Invalid microvoxel cell " + cell);
        }
        if (type < 0 || type >= MicrovoxelGenerator.typeCount()) {
            throw new IllegalArgumentException("Invalid microvoxel generator " + type);
        }
        return (cell & 0x0FFF)
                | ((type & 0x3) << 12)
                | ((facing & 0x3) << 14)
                | ((length & 0x1F) << 16)
                | ((width & 0x1F) << 21)
                | ((height & 0x1F) << 26);
    }

    public static int generateCell(int packed) {
        return packed & 0x0FFF;
    }

    public static int generateType(int packed) {
        return (packed >>> 12) & 0x3;
    }

    public static int generateFacing(int packed) {
        return (packed >>> 14) & 0x3;
    }

    public static int generateLength(int packed) {
        return (packed >>> 16) & 0x1F;
    }

    public static int generateWidth(int packed) {
        return (packed >>> 21) & 0x1F;
    }

    public static int generateHeight(int packed) {
        return (packed >>> 26) & 0x1F;
    }

    public record Frame(int major, int minor, int type, byte[] payload) {
    }

    /** Encodes a full frame. {@code payload} excludes the type byte. */
    public static byte[] frame(int type, byte[] payload) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeByte(MAGIC);
                writeVarInt(output, MAJOR);
                writeVarInt(output, MINOR);
                writeVarInt(output, 1 + payload.length);
                output.writeByte(type);
                output.write(payload);
            }
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /**
     * Decodes one frame and validates its bounds. Throws on a bad magic, an unsupported major or a
     * truncated frame; returns the payload so an unknown {@code type} can simply be ignored.
     */
    public static Frame readFrame(byte[] data) throws IOException {
        if (data == null || data.length < 5) throw new IOException("Truncated microvoxel frame");
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(data))) {
            if (input.readUnsignedByte() != MAGIC) throw new IOException("Unsupported microvoxel packet");
            int major = readVarInt(input);
            int minor = readVarInt(input);
            if (!compatibleMajor(major)) {
                throw new IOException("Unsupported microvoxel protocol major " + major);
            }
            int frameLength = readVarInt(input);
            if (frameLength < 1 || frameLength > data.length) {
                throw new IOException("Invalid microvoxel frame length");
            }
            int type = input.readUnsignedByte();
            byte[] payload = input.readNBytes(frameLength - 1);
            if (payload.length != frameLength - 1) throw new EOFException("Truncated microvoxel payload");
            if (input.read() != -1) throw new IOException("Trailing microvoxel frame bytes");
            return new Frame(major, minor, type, payload);
        }
    }

    public static void writeVarInt(DataOutputStream out, int value) throws IOException {
        while ((value & 0xFFFFFF80) != 0L) {
            out.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.writeByte(value & 0x7F);
    }

    public static int readVarInt(DataInputStream in) throws IOException {
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

    /** Material-index RLE for a 16^3 cell array: flag 0 = raw, 1 = runs. */
    public static byte[] encodeCells(byte[] cells) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                int runs = countRuns(cells);
                boolean useRuns = runs * 3 + 2 < cells.length;
                output.writeByte(useRuns ? 1 : 0);
                if (useRuns) {
                    writeVarInt(output, runs);
                    for (int index = 0; index < cells.length;) {
                        int end = runEnd(cells, index);
                        writeVarInt(output, end - index);
                        output.writeByte(cells[index]);
                        index = end;
                    }
                } else {
                    output.write(cells);
                }
            }
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** Decodes {@link #encodeCells} into {@code target} (must be CELL_COUNT long). */
    public static void decodeCells(byte[] encoded, byte[] target) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded))) {
            int encoding = input.readUnsignedByte();
            if (encoding == 0) {
                if (input.readNBytes(target, 0, target.length) != target.length) {
                    throw new EOFException("Truncated raw cells");
                }
            } else if (encoding == 1) {
                int runs = readVarInt(input);
                int cursor = 0;
                for (int run = 0; run < runs; run++) {
                    int length = readVarInt(input);
                    byte material = input.readByte();
                    if (length < 1 || cursor + length > target.length) {
                        throw new IOException("Invalid cell RLE run");
                    }
                    Arrays.fill(target, cursor, cursor + length, material);
                    cursor += length;
                }
                if (cursor != target.length) throw new IOException("Incomplete RLE volume");
            } else {
                throw new IOException("Unknown cell encoding");
            }
            if (input.read() != -1) throw new IOException("Trailing cell bytes");
        }
    }

    private static int countRuns(byte[] cells) {
        int runs = 0;
        for (int index = 0; index < cells.length;) {
            runs++;
            index = runEnd(cells, index);
        }
        return runs;
    }

    private static int runEnd(byte[] cells, int start) {
        byte material = cells[start];
        int end = start + 1;
        while (end < cells.length && cells[end] == material && end - start < 65535) end++;
        return end;
    }

    /** RLE for fluid levels (0..16 per cell). */
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
                Arrays.fill(levels, cursor, cursor + run, (byte) level);
                cursor += run;
            }
            if (input.read() != -1) throw new IOException("Trailing fluid level bytes");
            return levels;
        }
    }
}
