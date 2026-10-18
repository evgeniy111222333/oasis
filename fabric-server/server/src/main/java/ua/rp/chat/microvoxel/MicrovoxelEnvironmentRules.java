package ua.rp.chat.microvoxel;

import net.minecraft.core.Direction;

/** Deterministic, side-effect-free rules used by bounded environment simulation. */
public final class MicrovoxelEnvironmentRules {
    private MicrovoxelEnvironmentRules() {
    }

    public static boolean exposed(MicrovoxelVolume volume, int cell) {
        if (volume == null || !volume.occupied(cell)) return false;
        int x = MicrovoxelVolume.x(cell);
        int y = MicrovoxelVolume.y(cell);
        int z = MicrovoxelVolume.z(cell);
        return x == 0 || x == 15 || y == 0 || y == 15 || z == 0 || z == 15
                || !volume.occupied(x - 1, y, z) || !volume.occupied(x + 1, y, z)
                || !volume.occupied(x, y - 1, z) || !volume.occupied(x, y + 1, z)
                || !volume.occupied(x, y, z - 1) || !volume.occupied(x, y, z + 1);
    }

    public static boolean ignites(long worldTime, MicrovoxelKey key, int cell, int sourceStrength) {
        if (sourceStrength <= 0) return false;
        long value = worldTime * 0x9e3779b97f4a7c15L
                ^ key.x() * 0xbf58476d1ce4e5b9L
                ^ key.y() * 0x94d049bb133111ebL
                ^ key.z() * 0x632be59bd9b4e019L
                ^ cell * 0x85157af5L;
        value ^= value >>> 30;
        value *= 0xbf58476d1ce4e5b9L;
        value ^= value >>> 27;
        value *= 0x94d049bb133111ebL;
        value ^= value >>> 31;
        int threshold = Math.min(48, 5 + sourceStrength * 9);
        return Math.floorMod((int) value, 64) < threshold;
    }

    /**
     * Requires a straight empty channel between the cell and the adjacent heat source. This
     * consumes the contacted skin first and prevents fire from jumping to an unrelated far face.
     */
    public static boolean exposedToFace(MicrovoxelVolume volume, int cell, Direction sourceFace) {
        if (volume == null || sourceFace == null || cell < 0
                || cell >= MicrovoxelVolume.CELL_COUNT || !volume.occupied(cell)) return false;
        int x = MicrovoxelVolume.x(cell);
        int y = MicrovoxelVolume.y(cell);
        int z = MicrovoxelVolume.z(cell);
        for (int step = 1; step < MicrovoxelVolume.RESOLUTION; step++) {
            int nx = x + sourceFace.getStepX() * step;
            int ny = y + sourceFace.getStepY() * step;
            int nz = z + sourceFace.getStepZ() * step;
            if (nx < 0 || nx >= MicrovoxelVolume.RESOLUTION
                    || ny < 0 || ny >= MicrovoxelVolume.RESOLUTION
                    || nz < 0 || nz >= MicrovoxelVolume.RESOLUTION) return true;
            if (volume.occupied(nx, ny, nz)) return false;
        }
        return true;
    }
}
