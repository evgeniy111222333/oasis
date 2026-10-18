package ua.rp.chat.client.microvoxel;

import net.minecraft.core.BlockPos;
import ua.rp.chat.microvoxel.MicrovoxelGeometry;
import ua.rp.chat.microvoxel.MicrovoxelVolume;
import ua.rp.chat.microvoxel.MicrovoxelWire;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Wire-decode helpers for the microvoxel sync channel, extracted from
 * {@link MicrovoxelClientState}. Pure and stateless: every method reads from the supplied
 * stream (or byte array) and validates bounds, so a malformed packet fails closed without
 * touching client state. {@link MicrovoxelClientState} keeps thin delegates so its callers and
 * the shared fluid-codec test are unaffected.
 */
public final class MicrovoxelClientCodec {
    /** Material/name strings on the wire are tiny; this only bounds a hostile length prefix. */
    private static final int MAX_UTF8_BYTES = 4096;
    /** Hard cap on a wire string's length prefix and on a geometry block. */
    private static final int MAX_GEOMETRY_BYTES = 1_048_576;

    private MicrovoxelClientCodec() {
    }

    public static int readVarInt(DataInputStream in) throws IOException {
        return MicrovoxelWire.readVarInt(in);
    }

    public static String readUtf8(DataInputStream input) throws IOException {
        int length = readVarInt(input);
        if (length < 0 || length > MAX_UTF8_BYTES) {
            throw new IOException("Unreasonable microvoxel string length: " + length);
        }
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) throw new EOFException("Truncated UTF-8 value");
        return new String(bytes, StandardCharsets.UTF_8);
    }

    public static BlockPos readPosition(DataInputStream input) throws IOException {
        return new BlockPos(input.readInt(), input.readInt(), input.readInt());
    }

    public static MicrovoxelVolume readRawVolume(DataInputStream input) throws IOException {
        int revision = readVarInt(input);
        int paletteSize = readVarInt(input);
        if (paletteSize < 1 || paletteSize > MicrovoxelVolume.MAX_PALETTE) {
            throw new IOException("Invalid palette size");
        }
        List<String> palette = new ArrayList<>(paletteSize);
        for (int index = 0; index < paletteSize; index++) palette.add(readUtf8(input));
        byte[] cells = new byte[MicrovoxelVolume.CELL_COUNT];
        int encoding = input.readUnsignedByte();
        if (encoding == 0) {
            if (input.readNBytes(cells, 0, cells.length) != cells.length) {
                throw new EOFException("Truncated cells");
            }
        } else if (encoding == 1) {
            int runs = readVarInt(input);
            int cursor = 0;
            for (int run = 0; run < runs; run++) {
                int length = readVarInt(input);
                byte material = input.readByte();
                if (length < 1 || cursor + length > cells.length) {
                    throw new IOException("Invalid RLE run");
                }
                java.util.Arrays.fill(cells, cursor, cursor + length, material);
                cursor += length;
            }
            if (cursor != cells.length) throw new IOException("Incomplete RLE volume");
        } else {
            throw new IOException("Unknown cell encoding");
        }
        return new MicrovoxelVolume(revision, palette, cells, readOptionalGeometry(input));
    }

    /**
     * Reads the optional per-volume geometry section of the wire body: a presence byte and, when
     * present, a length-prefixed {@link MicrovoxelGeometry} block. An all-cube volume costs one
     * false byte, so the common case is unchanged.
     */
    public static MicrovoxelGeometry readOptionalGeometry(DataInputStream input) throws IOException {
        if (!input.readBoolean()) return null;
        int length = readVarInt(input);
        if (length < 0 || length > MAX_GEOMETRY_BYTES) throw new IOException("Invalid geometry length");
        byte[] encoded = input.readNBytes(length);
        if (encoded.length != length) throw new EOFException("Truncated microvoxel geometry");
        try {
            return MicrovoxelGeometry.decode(encoded);
        } catch (RuntimeException invalid) {
            throw new IOException("Invalid microvoxel geometry", invalid);
        }
    }

    /**
     * Mirror of the server RLE fluid codec. Duplicated deliberately: the two modules cannot
     * share code, and the build fails closed if either side drifts (see protocol parity).
     * Public for unit tests.
     */
    public static byte[] decodeLevels(byte[] encoded) throws IOException {
        try (DataInputStream wrapped = new DataInputStream(new ByteArrayInputStream(encoded))) {
            int total = readVarInt(wrapped);
            if (total < 0 || total > 65536) throw new IOException("Invalid fluid level count");
            byte[] levels = new byte[total];
            int cursor = 0;
            while (cursor < total) {
                int run = readVarInt(wrapped);
                int level = wrapped.readUnsignedByte();
                if (run < 1 || cursor + run > total || level > 16) {
                    throw new IOException("Invalid fluid level run");
                }
                java.util.Arrays.fill(levels, cursor, cursor + run, (byte) level);
                cursor += run;
            }
            if (wrapped.read() != -1) throw new IOException("Trailing fluid level bytes");
            return levels;
        }
    }
}
