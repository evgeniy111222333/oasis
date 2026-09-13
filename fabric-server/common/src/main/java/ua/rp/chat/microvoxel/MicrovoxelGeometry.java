package ua.rp.chat.microvoxel;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/**
 * Optional geometry channel of one microvoxel volume ("geometry as data", tier S/D).
 *
 * <p>The material layer stays exactly what it always was: a palette plus a byte per cell. This
 * channel only records the cells whose geometry is <em>not</em> the full cube, mapping each such
 * cell to a global {@link MicrovoxelShape} id through a small per-volume shape palette. A volume
 * whose cells are all full cubes has an empty channel and therefore costs zero bytes, zero wire
 * traffic and zero render work — behaviour stays byte-for-byte identical to before this layer.</p>
 *
 * <p>The palette exists so a volume that uses a handful of shapes stores 4-bit-scale indices
 * (byte-packed, RLE-compressible) instead of a global shape id per cell. Index 0 is reserved for
 * "full cube" so the common case is a single zero byte.</p>
 *
 * <p>Pure: no Minecraft classes, fully unit-testable, immutable-friendly (copy-on-write).</p>
 */
public final class MicrovoxelGeometry {
    public static final int CELL_COUNT = MicrovoxelVolume.CELL_COUNT;
    /** Largest local palette the byte indices can address. */
    public static final int MAX_PALETTE = 128;

    private static final byte[] MAGIC = {'M', 'V', 'G', '1'};
    private static final int VERSION = 1;

    private int[] palette;
    private byte[] cells;
    private int shapedCells;
    /** Lazily built list of shaped cell indices; nulled on every mutation. */
    private transient volatile int[] shapedIndex;

    private MicrovoxelGeometry(int[] palette, byte[] cells, int shapedCells) {
        this.palette = palette;
        this.cells = cells;
        this.shapedCells = shapedCells;
    }

    /** An empty channel (every cell is a full cube). */
    public static MicrovoxelGeometry empty() {
        return new MicrovoxelGeometry(new int[]{0}, new byte[CELL_COUNT], 0);
    }

    public boolean isEmpty() {
        return shapedCells == 0;
    }

    /** Number of cells that carry a non-full shape. */
    public int shapedCells() {
        return shapedCells;
    }

    /** Global shape id at one cell (0 = full cube). */
    public int shapeAt(int cell) {
        requireCell(cell);
        int index = cells[cell] & 0xFF;
        return index < palette.length ? palette[index] : 0;
    }

    /**
     * Sets the shape of one cell. Returns true when the channel actually changed. {@code shapeId}
     * 0 clears the cell back to a full cube.
     */
    public boolean setShape(int cell, int shapeId) {
        requireCell(cell);
        if (shapeId < 0 || shapeId >= MicrovoxelShape.count()) {
            throw new IllegalArgumentException("Unknown microvoxel shape id " + shapeId);
        }
        int previousIndex = cells[cell] & 0xFF;
        int previousShape = previousIndex < palette.length ? palette[previousIndex] : 0;
        if (previousShape == shapeId) return false;

        if (shapeId == 0) {
            cells[cell] = 0;
            shapedCells--;
            shapedIndex = null;
            compactIfPossible();
            return true;
        }
        int index = indexOfShape(shapeId);
        if (index < 0) {
            if (palette.length >= MAX_PALETTE) {
                throw new IllegalStateException("Microvoxel geometry palette is full");
            }
            int[] grown = Arrays.copyOf(palette, palette.length + 1);
            grown[palette.length] = shapeId;
            palette = grown;
            index = palette.length - 1;
        }
        if (previousShape == 0) shapedCells++;
        cells[cell] = (byte) index;
        shapedIndex = null;
        return true;
    }

    /**
     * Shaped cell indices, cached and rebuilt on mutation. The renderer iterates this instead of
     * scanning all 4096 cells per section compile, so a shaped volume costs only its real cells.
     */
    public int[] shapedCellIndex() {
        int[] index = shapedIndex;
        if (index == null) {
            synchronized (this) {
                index = shapedIndex;
                if (index == null) {
                    int[] built = new int[shapedCells];
                    int cursor = 0;
                    for (int cell = 0; cell < CELL_COUNT && cursor < built.length; cell++) {
                        int paletteIndex = cells[cell] & 0xFF;
                        if (paletteIndex < palette.length && palette[paletteIndex] != 0) {
                            built[cursor++] = cell;
                        }
                    }
                    index = cursor == built.length ? built : Arrays.copyOf(built, cursor);
                    shapedIndex = index;
                }
            }
        }
        return index;
    }

    private int indexOfShape(int shapeId) {
        for (int index = 1; index < palette.length; index++) {
            if (palette[index] == shapeId) return index;
        }
        return -1;
    }

    /** Drops unused palette entries; keeps the channel canonical after clears. */
    private void compactIfPossible() {
        if (shapedCells == 0) {
            palette = new int[]{0};
            return;
        }
    }

    public MicrovoxelGeometry copy() {
        return new MicrovoxelGeometry(palette.clone(), cells.clone(), shapedCells);
    }

    /** Distinct shape ids currently referenced (excluding the full cube). */
    public int[] usedShapeIds() {
        int[] used = new int[palette.length];
        int count = 0;
        for (int index = 1; index < palette.length; index++) {
            if (referenced(index)) used[count++] = palette[index];
        }
        return Arrays.copyOf(used, count);
    }

    private boolean referenced(int paletteIndex) {
        for (byte value : cells) {
            if ((value & 0xFF) == paletteIndex) return true;
        }
        return false;
    }

    // ================= Codec (pure, versioned, self-describing) =================

    /** Encodes the channel to a self-contained byte stream (empty channel encodes to zero length). */
    public byte[] encode() {
        if (shapedCells == 0) return new byte[0];
        ByteArrayOutputStream out = new ByteArrayOutputStream(64);
        out.writeBytes(MAGIC);
        out.write(VERSION);
        int[] used = usedShapeIds();
        writeVarInt(out, used.length);
        for (int shapeId : used) writeVarInt(out, shapeId);
        // Remap local indices into the compact used list.
        byte[] encoded = new byte[CELL_COUNT];
        for (int cell = 0; cell < CELL_COUNT; cell++) {
            int index = cells[cell] & 0xFF;
            int shapeId = index < palette.length ? palette[index] : 0;
            encoded[cell] = (byte) (shapeId == 0 ? 0 : indexOf(used, shapeId) + 1);
        }
        writeVarInt(out, encoded.length);
        int runs = countRuns(encoded);
        if ((long) runs * 2 < encoded.length) {
            out.write(1);
            writeVarInt(out, runs);
            int cursor = 0;
            while (cursor < encoded.length) {
                int length = 1;
                while (cursor + length < encoded.length && encoded[cursor + length] == encoded[cursor]) {
                    length++;
                }
                writeVarInt(out, length);
                out.write(encoded[cursor] & 0xFF);
                cursor += length;
            }
        } else {
            out.write(0);
            out.writeBytes(encoded);
        }
        return out.toByteArray();
    }

    /** Decodes a channel; an empty payload yields an empty channel. */
    public static MicrovoxelGeometry decode(byte[] data) {
        if (data == null || data.length == 0) return empty();
        int[] cursor = {0};
        for (byte expected : MAGIC) {
            if (cursor[0] >= data.length || data[cursor[0]++] != expected) {
                throw new IllegalArgumentException("Bad microvoxel geometry magic");
            }
        }
        if (cursor[0] >= data.length || data[cursor[0]++] != VERSION) {
            throw new IllegalArgumentException("Unsupported microvoxel geometry version");
        }
        int paletteSize = readVarInt(data, cursor);
        if (paletteSize < 0 || paletteSize > MAX_PALETTE) {
            throw new IllegalArgumentException("Invalid microvoxel geometry palette size");
        }
        int[] used = new int[paletteSize];
        for (int index = 0; index < paletteSize; index++) {
            used[index] = readVarInt(data, cursor);
            if (used[index] <= 0 || used[index] >= MicrovoxelShape.count()) {
                throw new IllegalArgumentException("Invalid microvoxel geometry shape id");
            }
        }
        int length = readVarInt(data, cursor);
        if (length != CELL_COUNT) throw new IllegalArgumentException("Invalid microvoxel geometry cell count");
        byte[] encoded = new byte[CELL_COUNT];
        int encoding = data[cursor[0]++] & 0xFF;
        if (encoding == 0) {
            if (cursor[0] + CELL_COUNT > data.length) throw new IllegalArgumentException("Truncated geometry cells");
            System.arraycopy(data, cursor[0], encoded, 0, CELL_COUNT);
            cursor[0] += CELL_COUNT;
        } else if (encoding == 1) {
            int runs = readVarInt(data, cursor);
            int position = 0;
            for (int run = 0; run < runs; run++) {
                int runLength = readVarInt(data, cursor);
                int value = data[cursor[0]++] & 0xFF;
                if (runLength < 1 || position + runLength > CELL_COUNT) {
                    throw new IllegalArgumentException("Invalid geometry RLE run");
                }
                Arrays.fill(encoded, position, position + runLength, (byte) value);
                position += runLength;
            }
            if (position != CELL_COUNT) throw new IllegalArgumentException("Incomplete geometry RLE");
        } else {
            throw new IllegalArgumentException("Unknown geometry cell encoding");
        }
        if (cursor[0] != data.length) throw new IllegalArgumentException("Trailing microvoxel geometry bytes");

        int[] palette = new int[]{0};
        byte[] cells = new byte[CELL_COUNT];
        int shaped = 0;
        for (int cell = 0; cell < CELL_COUNT; cell++) {
            int value = encoded[cell] & 0xFF;
            if (value == 0) continue;
            if (value > used.length) throw new IllegalArgumentException("Geometry index out of range");
            int shapeId = used[value - 1];
            int localIndex = localIndexFor(palette, shapeId);
            if (localIndex < 0) {
                if (palette.length >= MAX_PALETTE) {
                    throw new IllegalArgumentException("Microvoxel geometry palette overflow");
                }
                int[] grown = Arrays.copyOf(palette, palette.length + 1);
                grown[palette.length] = shapeId;
                palette = grown;
                localIndex = palette.length - 1;
            }
            cells[cell] = (byte) localIndex;
            shaped++;
        }
        return new MicrovoxelGeometry(palette, cells, shaped);
    }

    private static int localIndexFor(int[] palette, int shapeId) {
        for (int index = 1; index < palette.length; index++) {
            if (palette[index] == shapeId) return index;
        }
        return -1;
    }

    private static int countRuns(byte[] values) {
        int runs = 0;
        int cursor = 0;
        while (cursor < values.length) {
            int length = 1;
            while (cursor + length < values.length && values[cursor + length] == values[cursor]) length++;
            runs++;
            cursor += length;
        }
        return runs;
    }

    private static int indexOf(int[] array, int value) {
        for (int index = 0; index < array.length; index++) {
            if (array[index] == value) return index;
        }
        return -1;
    }

    private static void writeVarInt(ByteArrayOutputStream out, int value) {
        while ((value & ~0x7F) != 0) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value);
    }

    private static int readVarInt(byte[] data, int[] cursor) {
        int value = 0;
        int shift = 0;
        while (true) {
            if (cursor[0] >= data.length || shift > 35) {
                throw new IllegalArgumentException("Truncated geometry varint");
            }
            int current = data[cursor[0]++] & 0xFF;
            value |= (current & 0x7F) << shift;
            if ((current & 0x80) == 0) return value;
            shift += 7;
        }
    }

    private static void requireCell(int cell) {
        if (cell < 0 || cell >= CELL_COUNT) throw new IndexOutOfBoundsException("Invalid microvoxel cell");
    }
}
