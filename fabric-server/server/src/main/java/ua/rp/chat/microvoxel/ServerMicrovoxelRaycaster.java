package ua.rp.chat.microvoxel;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Authoritative micro-cell DDA raycaster. Mirrors the client traversal cell for cell (slab
 * entry, up to 52 micro-steps, empty cavities stepped through, walk ends at unit-block exit),
 * so every client-predicted cavity shot revalidates to the same cell on the server.
 * Multi-block traversal is owned by {@link #castIndexed}, which walks whole blocks and keeps
 * the nearest in-range volume hit.
 */
public final class ServerMicrovoxelRaycaster {
    private static final double EPSILON = 1.0E-7;

    private ServerMicrovoxelRaycaster() {
    }

    static Hit cast(double ox, double oy, double oz, double dx, double dy, double dz,
                    double maxDistance, Collection<Map.Entry<MicrovoxelKey, MicrovoxelVolume>> entries) {
        Hit nearest = null;
        for (Map.Entry<MicrovoxelKey, MicrovoxelVolume> entry : entries) {
            Hit hit = castVolume(ox, oy, oz, dx, dy, dz, maxDistance, entry.getKey(), entry.getValue());
            if (hit != null && (nearest == null || hit.distance < nearest.distance)) nearest = hit;
        }
        return nearest;
    }

    public static Hit castIndexed(UUID worldId, double ox, double oy, double oz,
                           double dx, double dy, double dz, double maxDistance,
                           VolumeLookup lookup) {
        int blockX = (int) Math.floor(ox);
        int blockY = (int) Math.floor(oy);
        int blockZ = (int) Math.floor(oz);
        int stepX = sign(dx);
        int stepY = sign(dy);
        int stepZ = sign(dz);
        double tMaxX = firstBoundary(ox, dx, blockX, stepX);
        double tMaxY = firstBoundary(oy, dy, blockY, stepY);
        double tMaxZ = firstBoundary(oz, dz, blockZ, stepZ);
        double tDeltaX = delta(dx);
        double tDeltaY = delta(dy);
        double tDeltaZ = delta(dz);

        for (int steps = 0; steps < 64; steps++) {
            MicrovoxelVolume volume = lookup.get(blockX, blockY, blockZ);
            if (volume != null) {
                MicrovoxelKey key = new MicrovoxelKey(worldId, blockX, blockY, blockZ);
                Hit hit = castVolume(ox, oy, oz, dx, dy, dz, maxDistance, key, volume);
                double nextBoundary = Math.min(tMaxX, Math.min(tMaxY, tMaxZ));
                if (hit != null && hit.distance <= nextBoundary + EPSILON) return hit;
            }
            double next = Math.min(tMaxX, Math.min(tMaxY, tMaxZ));
            if (next > maxDistance) break;
            if (tMaxX <= next + EPSILON) {
                blockX += stepX;
                tMaxX += tDeltaX;
            }
            if (tMaxY <= next + EPSILON) {
                blockY += stepY;
                tMaxY += tDeltaY;
            }
            if (tMaxZ <= next + EPSILON) {
                blockZ += stepZ;
                tMaxZ += tDeltaZ;
            }
        }
        return null;
    }

    private static int sign(double value) {
        return value > EPSILON ? 1 : value < -EPSILON ? -1 : 0;
    }

    private static double delta(double direction) {
        return Math.abs(direction) < EPSILON ? Double.POSITIVE_INFINITY : Math.abs(1.0 / direction);
    }

    private static double firstBoundary(double origin, double direction, int block, int step) {
        if (step == 0) return Double.POSITIVE_INFINITY;
        double boundary = step > 0 ? block + 1.0 : block;
        return Math.max(0.0, (boundary - origin) / direction);
    }

    /**
     * Per-volume DDA delegated to the shared {@link MicrovoxelRaycaster}: client and server run
     * the exact same traversal, so a predicted cavity shot revalidates to the same cell. Only the
     * entry/hit types differ and are adapted here.
     */
    private static Hit castVolume(double ox, double oy, double oz, double dx, double dy, double dz,
                                  double maxDistance, MicrovoxelKey key, MicrovoxelVolume volume) {
        MicrovoxelRaycaster.Entry entry =
                new MicrovoxelRaycaster.Entry(key.x(), key.y(), key.z(), volume);
        MicrovoxelRaycaster.Hit shared = MicrovoxelRaycaster.cast(
                ox, oy, oz, dx, dy, dz, maxDistance, java.util.List.of(entry));
        if (shared == null) return null;
        Face face;
        try {
            face = Face.valueOf(shared.face().name());
        } catch (IllegalArgumentException unknown) {
            return null;
        }
        return new Hit(key, shared.cell(), face, shared.distance());
    }

    public enum Face {
        DOWN(0, -1, 0), UP(0, 1, 0), NORTH(0, 0, -1), SOUTH(0, 0, 1), WEST(-1, 0, 0), EAST(1, 0, 0);

        private final int dx;
        private final int dy;
        private final int dz;

        Face(int dx, int dy, int dz) {
            this.dx = dx;
            this.dy = dy;
            this.dz = dz;
        }
    }

    public record Hit(MicrovoxelKey key, int cell, Face face, double distance) {
        public int adjacentCell() {
            int x = MicrovoxelVolume.x(cell) + face.dx;
            int y = MicrovoxelVolume.y(cell) + face.dy;
            int z = MicrovoxelVolume.z(cell) + face.dz;
            return MicrovoxelVolume.inside(x, y, z) ? MicrovoxelVolume.index(x, y, z) : -1;
        }

        public AdjacentTarget adjacentTarget() {
            int localX = MicrovoxelVolume.x(cell) + face.dx;
            int localY = MicrovoxelVolume.y(cell) + face.dy;
            int localZ = MicrovoxelVolume.z(cell) + face.dz;
            MicrovoxelKey targetKey = new MicrovoxelKey(key.worldId(),
                    key.x() + Math.floorDiv(localX, MicrovoxelVolume.RESOLUTION),
                    key.y() + Math.floorDiv(localY, MicrovoxelVolume.RESOLUTION),
                    key.z() + Math.floorDiv(localZ, MicrovoxelVolume.RESOLUTION));
            int targetCell = MicrovoxelVolume.index(
                    Math.floorMod(localX, MicrovoxelVolume.RESOLUTION),
                    Math.floorMod(localY, MicrovoxelVolume.RESOLUTION),
                    Math.floorMod(localZ, MicrovoxelVolume.RESOLUTION));
            return new AdjacentTarget(targetKey, targetCell);
        }
    }

    public record AdjacentTarget(MicrovoxelKey key, int cell) {
    }

    @FunctionalInterface
    public interface VolumeLookup {
        MicrovoxelVolume get(int x, int y, int z);
    }
}
