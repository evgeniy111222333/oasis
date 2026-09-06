package ua.rp.chat.carver;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Authoritative 16x16x16 removal mask for one Carver drafting session.
 *
 * <p>A set bit means "carve this cell away on approval". Cell numbering mirrors
 * {@code MicrovoxelVolume} exactly ({@code x | (z << 4) | (y << 8)}), so masks apply
 * to volumes without any translation layer.</p>
 *
 * <p>Pure and dependency-free: safe to unit-test.</p>
 *
 * <p>Mirror contract: duplicated verbatim in the paired module. Keep both copies
 * byte-identical; {@code verifyCarverParity} fails the build on divergence.</p>
 */
public final class DraftMask {
    public static final int RESOLUTION = 16;
    public static final int CELL_COUNT = RESOLUTION * RESOLUTION * RESOLUTION;
    public static final int PACKED_BYTES = CELL_COUNT / 8;

    private final boolean[] bits = new boolean[CELL_COUNT];
    private int count;

    public static int index(int x, int y, int z) {
        if (x < 0 || x >= RESOLUTION || y < 0 || y >= RESOLUTION || z < 0 || z >= RESOLUTION) {
            throw new IndexOutOfBoundsException("Draft cell outside 16x16x16 volume: "
                    + x + "," + y + "," + z);
        }
        return x | (z << 4) | (y << 8);
    }

    public static int x(int cell) {
        requireCell(cell);
        return cell & 15;
    }

    public static int y(int cell) {
        requireCell(cell);
        return (cell >>> 8) & 15;
    }

    public static int z(int cell) {
        requireCell(cell);
        return (cell >>> 4) & 15;
    }

    private static void requireCell(int cell) {
        if (cell < 0 || cell >= CELL_COUNT) {
            throw new IndexOutOfBoundsException("Invalid draft cell " + cell);
        }
    }

    public boolean get(int cell) {
        requireCell(cell);
        return bits[cell];
    }

    /** Returns true when the bit changed. */
    public boolean set(int cell) {
        requireCell(cell);
        if (bits[cell]) return false;
        bits[cell] = true;
        count++;
        return true;
    }

    /** Returns true when the bit changed. */
    public boolean clear(int cell) {
        requireCell(cell);
        if (!bits[cell]) return false;
        bits[cell] = false;
        count--;
        return true;
    }

    public int count() {
        return count;
    }

    public boolean isEmpty() {
        return count == 0;
    }

    public void clearAll() {
        Arrays.fill(bits, false);
        count = 0;
    }

    /** Union of {@code other} into this mask; returns newly added cells. */
    public int orIn(DraftMask other) {
        int added = 0;
        for (int cell = 0; cell < CELL_COUNT; cell++) {
            if (other.bits[cell] && !bits[cell]) {
                bits[cell] = true;
                added++;
            }
        }
        count += added;
        return added;
    }

    /** Removes every bit set in {@code other}; returns removed cells. */
    public int andNot(DraftMask other) {
        int removed = 0;
        for (int cell = 0; cell < CELL_COUNT; cell++) {
            if (other.bits[cell] && bits[cell]) {
                bits[cell] = false;
                removed++;
            }
        }
        count -= removed;
        return removed;
    }

    public List<Integer> cells() {
        ArrayList<Integer> result = new ArrayList<>(count);
        for (int cell = 0; cell < CELL_COUNT; cell++) {
            if (bits[cell]) result.add(cell);
        }
        return List.copyOf(result);
    }

    public DraftMask copy() {
        DraftMask copy = new DraftMask();
        System.arraycopy(bits, 0, copy.bits, 0, CELL_COUNT);
        copy.count = count;
        return copy;
    }

    /** Fixed 512-byte bit packing, bit {@code cell} lives in byte {@code cell/8} bit {@code cell%8}. */
    public byte[] encode() {
        byte[] packed = new byte[PACKED_BYTES];
        for (int cell = 0; cell < CELL_COUNT; cell++) {
            if (bits[cell]) packed[cell >>> 3] |= (byte) (1 << (cell & 7));
        }
        return packed;
    }

    /** Decodes {@link #encode()}; throws on any length mismatch. */
    public static DraftMask decode(byte[] packed) {
        if (packed == null || packed.length != PACKED_BYTES) {
            throw new IllegalArgumentException("Draft mask must be exactly "
                    + PACKED_BYTES + " bytes, got "
                    + (packed == null ? "null" : packed.length));
        }
        DraftMask mask = new DraftMask();
        for (int cell = 0; cell < CELL_COUNT; cell++) {
            if ((packed[cell >>> 3] & (1 << (cell & 7))) != 0) {
                mask.bits[cell] = true;
                mask.count++;
            }
        }
        return mask;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof DraftMask mask)) return false;
        return count == mask.count && Arrays.equals(bits, mask.bits);
    }

    @Override
    public int hashCode() {
        return 31 * count + Arrays.hashCode(bits);
    }
}
