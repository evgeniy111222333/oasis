package ua.rp.chat.microvoxel;

import java.util.Arrays;
import java.util.List;

/** Immutable visual identity and occupied bounds shared by item mesh and GUI-atlas rendering. */
public final class MicrovoxelVisualShape {
    private MicrovoxelVisualShape() {
    }

    public static Snapshot snapshot(MicrovoxelVolume volume) {
        byte[] cells = volume.cellsCopy();
        int minX = MicrovoxelVolume.RESOLUTION;
        int minY = MicrovoxelVolume.RESOLUTION;
        int minZ = MicrovoxelVolume.RESOLUTION;
        int maxX = -1;
        int maxY = -1;
        int maxZ = -1;
        for (int cell = 0; cell < cells.length; cell++) {
            if (cells[cell] == 0) continue;
            int x = MicrovoxelVolume.x(cell);
            int y = MicrovoxelVolume.y(cell);
            int z = MicrovoxelVolume.z(cell);
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
            maxZ = Math.max(maxZ, z);
        }
        Bounds bounds = maxX < 0
                ? new Bounds(0, 0, 0, 0, 0, 0)
                : new Bounds(unit(minX), unit(minY), unit(minZ),
                        unit(maxX + 1), unit(maxY + 1), unit(maxZ + 1));
        return new Snapshot(new Key(volume.palette(), cells), bounds);
    }

    private static float unit(int cellCoordinate) {
        return cellCoordinate / (float) MicrovoxelVolume.RESOLUTION;
    }

    public record Snapshot(Key key, Bounds bounds) {
    }

    public record Bounds(float minX, float minY, float minZ,
                         float maxX, float maxY, float maxZ) {
    }

    /**
     * Content-exact GUI/cache key.
     *
     * <p>A pair of 32-bit hashes is insufficient here: a collision would make the GUI atlas or
     * mesh cache show a different carved object. Equality therefore checks the complete palette
     * and all 4096 material cells while retaining a cached hash for normal map performance.</p>
     */
    public static final class Key {
        private final List<String> palette;
        private final byte[] cells;
        private final int hashCode;

        private Key(List<String> palette, byte[] cells) {
            this.palette = List.copyOf(palette);
            this.cells = cells.clone();
            this.hashCode = 31 * this.palette.hashCode() + Arrays.hashCode(this.cells);
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Key key)) return false;
            return palette.equals(key.palette) && Arrays.equals(cells, key.cells);
        }

        @Override
        public int hashCode() {
            return hashCode;
        }
    }
}
