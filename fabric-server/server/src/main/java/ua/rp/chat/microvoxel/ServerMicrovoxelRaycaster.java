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

    private static Hit castVolume(double ox, double oy, double oz, double dx, double dy, double dz,
                                  double maxDistance, MicrovoxelKey key, MicrovoxelVolume volume) {
        Slab slab = intersectUnitBlock(ox - key.x(), oy - key.y(), oz - key.z(), dx, dy, dz, maxDistance);
        if (slab == null) return null;
        double t = Math.max(0.0, slab.enter) + EPSILON;
        int x = clampCell((int) Math.floor((ox + dx * t - key.x()) * MicrovoxelVolume.RESOLUTION));
        int y = clampCell((int) Math.floor((oy + dy * t - key.y()) * MicrovoxelVolume.RESOLUTION));
        int z = clampCell((int) Math.floor((oz + dz * t - key.z()) * MicrovoxelVolume.RESOLUTION));
        Face enteredFace = slab.face;

        for (int steps = 0; steps < 52 && t <= slab.exit + EPSILON && t <= maxDistance; steps++) {
            if (volume.occupied(x, y, z)) {
                return new Hit(key, MicrovoxelVolume.index(x, y, z), enteredFace, t);
            }
            double tx = nextBoundary(t, ox, dx,
                    key.x() + (dx > 0 ? (x + 1.0) / MicrovoxelVolume.RESOLUTION : x / (double) MicrovoxelVolume.RESOLUTION));
            double ty = nextBoundary(t, oy, dy,
                    key.y() + (dy > 0 ? (y + 1.0) / MicrovoxelVolume.RESOLUTION : y / (double) MicrovoxelVolume.RESOLUTION));
            double tz = nextBoundary(t, oz, dz,
                    key.z() + (dz > 0 ? (z + 1.0) / MicrovoxelVolume.RESOLUTION : z / (double) MicrovoxelVolume.RESOLUTION));
            if (tx <= ty && tx <= tz) {
                t = tx + EPSILON;
                x += dx > 0 ? 1 : -1;
                enteredFace = dx > 0 ? Face.WEST : Face.EAST;
            } else if (ty <= tz) {
                t = ty + EPSILON;
                y += dy > 0 ? 1 : -1;
                enteredFace = dy > 0 ? Face.DOWN : Face.UP;
            } else {
                t = tz + EPSILON;
                z += dz > 0 ? 1 : -1;
                enteredFace = dz > 0 ? Face.NORTH : Face.SOUTH;
            }
            // Leaving the 16^3 lattice means leaving the unit block itself (straight ray
            // through a convex box cannot re-enter). Empty cavities inside were already
            // stepped through above; the block-level walk in castIndexed moves on next.
            if (!MicrovoxelVolume.inside(x, y, z)) return null;
        }
        return null;
    }

    private static double nextBoundary(double current, double origin, double direction, double boundary) {
        if (Math.abs(direction) < EPSILON) return Double.POSITIVE_INFINITY;
        double result = (boundary - origin) / direction;
        return result <= current + EPSILON ? current + EPSILON * 4.0 : result;
    }

    private static Slab intersectUnitBlock(
            double ox, double oy, double oz, double dx, double dy, double dz, double maxDistance) {
        double enter = 0.0;
        double exit = maxDistance;
        Face face = Face.NORTH;
        double[][] axes = {{ox, dx}, {oy, dy}, {oz, dz}};
        Face[][] faces = {{Face.WEST, Face.EAST}, {Face.DOWN, Face.UP}, {Face.NORTH, Face.SOUTH}};
        for (int axis = 0; axis < 3; axis++) {
            double origin = axes[axis][0];
            double direction = axes[axis][1];
            if (Math.abs(direction) < EPSILON) {
                if (origin < 0.0 || origin > 1.0) return null;
                continue;
            }
            double first = -origin / direction;
            double second = (1.0 - origin) / direction;
            Face nearFace = faces[axis][0];
            if (first > second) {
                double swap = first;
                first = second;
                second = swap;
                nearFace = faces[axis][1];
            }
            if (first > enter) {
                enter = first;
                face = nearFace;
            }
            exit = Math.min(exit, second);
            if (exit < enter) return null;
        }
        return new Slab(enter, exit, face);
    }

    private static int clampCell(int cell) {
        return Math.max(0, Math.min(MicrovoxelVolume.RESOLUTION - 1, cell));
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

    private record Slab(double enter, double exit, Face face) {
    }
}
