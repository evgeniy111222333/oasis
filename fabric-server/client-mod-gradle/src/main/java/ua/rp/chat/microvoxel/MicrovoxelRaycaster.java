package ua.rp.chat.microvoxel;

import java.util.Collection;

/**
 * Micro-cell DDA raycaster shared by targeting, prediction and validation.
 *
 * <p>Each volume is entered through its unit-block slab, then traversed cell by cell (up to 52
 * micro-steps, covering the full 16x16x16 diagonal). Empty cells never stop the ray: aiming
 * through a carved cavity keeps travelling until the first occupied cell, which is what makes
 * inner walls of U-shaped hollows selectable. The walk ends only when the ray leaves the unit
 * block ({@code !inside}) or exhausts the slab/reach bound. Multi-volume traversal is owned by
 * the caller, which casts every nearby volume and keeps the nearest hit.</p>
 */
public final class MicrovoxelRaycaster {
    private static final double EPSILON = 1.0E-7;

    private MicrovoxelRaycaster() {
    }

    public static Hit cast(double ox, double oy, double oz, double dx, double dy, double dz,
                           double maxDistance, Collection<Entry> entries) {
        Hit nearest = null;
        for (Entry entry : entries) {
            Hit hit = castVolume(ox, oy, oz, dx, dy, dz, maxDistance, entry);
            if (hit != null && (nearest == null || hit.distance < nearest.distance)) nearest = hit;
        }
        return nearest;
    }

    private static Hit castVolume(double ox, double oy, double oz, double dx, double dy, double dz,
                                  double maxDistance, Entry entry) {
        Slab slab = intersectUnitBlock(ox - entry.x, oy - entry.y, oz - entry.z, dx, dy, dz, maxDistance);
        if (slab == null) return null;
        double t = Math.max(0.0, slab.enter) + EPSILON;
        double lx = (ox + dx * t - entry.x) * 16.0;
        double ly = (oy + dy * t - entry.y) * 16.0;
        double lz = (oz + dz * t - entry.z) * 16.0;
        int x = clampCell((int) Math.floor(lx));
        int y = clampCell((int) Math.floor(ly));
        int z = clampCell((int) Math.floor(lz));
        MicrovoxelGreedyMesher.Direction enteredFace = slab.face;

        for (int steps = 0; steps < 52 && t <= slab.exit + EPSILON && t <= maxDistance; steps++) {
            if (entry.volume.materialAt(x, y, z) != 0) {
                return new Hit(entry, MicrovoxelVolume.index(x, y, z), enteredFace, t);
            }
            double tx = nextBoundary(t, ox, dx, entry.x + (dx > 0 ? (x + 1) / 16.0 : x / 16.0));
            double ty = nextBoundary(t, oy, dy, entry.y + (dy > 0 ? (y + 1) / 16.0 : y / 16.0));
            double tz = nextBoundary(t, oz, dz, entry.z + (dz > 0 ? (z + 1) / 16.0 : z / 16.0));
            if (tx <= ty && tx <= tz) {
                t = tx + EPSILON;
                x += dx > 0 ? 1 : -1;
                enteredFace = dx > 0 ? MicrovoxelGreedyMesher.Direction.WEST : MicrovoxelGreedyMesher.Direction.EAST;
            } else if (ty <= tz) {
                t = ty + EPSILON;
                y += dy > 0 ? 1 : -1;
                enteredFace = dy > 0 ? MicrovoxelGreedyMesher.Direction.DOWN : MicrovoxelGreedyMesher.Direction.UP;
            } else {
                t = tz + EPSILON;
                z += dz > 0 ? 1 : -1;
                enteredFace = dz > 0 ? MicrovoxelGreedyMesher.Direction.NORTH : MicrovoxelGreedyMesher.Direction.SOUTH;
            }
            // Leaving the 16^3 lattice means leaving the unit block itself (straight ray
            // through a convex box cannot re-enter), so the traversal of THIS volume is over.
            // Empty cavities inside were already stepped through above; only the caller moves
            // on to the next volume along the ray.
            if (!MicrovoxelVolume.inside(x, y, z)) return null;
        }
        return null;
    }

    private static double nextBoundary(double current, double origin, double direction, double boundary) {
        if (Math.abs(direction) < EPSILON) return Double.POSITIVE_INFINITY;
        double result = (boundary - origin) / direction;
        return result <= current + EPSILON ? current + EPSILON * 4 : result;
    }

    private static Slab intersectUnitBlock(double ox, double oy, double oz, double dx, double dy, double dz, double max) {
        double enter = 0.0;
        double exit = max;
        MicrovoxelGreedyMesher.Direction face = MicrovoxelGreedyMesher.Direction.NORTH;
        double[][] axes = {{ox, dx}, {oy, dy}, {oz, dz}};
        MicrovoxelGreedyMesher.Direction[][] faces = {
                {MicrovoxelGreedyMesher.Direction.WEST, MicrovoxelGreedyMesher.Direction.EAST},
                {MicrovoxelGreedyMesher.Direction.DOWN, MicrovoxelGreedyMesher.Direction.UP},
                {MicrovoxelGreedyMesher.Direction.NORTH, MicrovoxelGreedyMesher.Direction.SOUTH}
        };
        for (int axis = 0; axis < 3; axis++) {
            double origin = axes[axis][0];
            double direction = axes[axis][1];
            if (Math.abs(direction) < EPSILON) {
                if (origin < 0 || origin > 1) return null;
                continue;
            }
            double first = (0 - origin) / direction;
            double second = (1 - origin) / direction;
            MicrovoxelGreedyMesher.Direction nearFace = faces[axis][0];
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
        return Math.max(0, Math.min(15, cell));
    }

    public record Entry(int x, int y, int z, MicrovoxelVolume volume) {
    }

    public record Hit(Entry entry, int cell, MicrovoxelGreedyMesher.Direction face, double distance) {
        public int adjacentCell() {
            int x = MicrovoxelVolume.x(cell) + face.dx;
            int y = MicrovoxelVolume.y(cell) + face.dy;
            int z = MicrovoxelVolume.z(cell) + face.dz;
            return MicrovoxelVolume.inside(x, y, z) ? MicrovoxelVolume.index(x, y, z) : -1;
        }

        public Target adjacentTarget() {
            int localX = MicrovoxelVolume.x(cell) + face.dx;
            int localY = MicrovoxelVolume.y(cell) + face.dy;
            int localZ = MicrovoxelVolume.z(cell) + face.dz;
            int blockX = entry.x + Math.floorDiv(localX, MicrovoxelVolume.RESOLUTION);
            int blockY = entry.y + Math.floorDiv(localY, MicrovoxelVolume.RESOLUTION);
            int blockZ = entry.z + Math.floorDiv(localZ, MicrovoxelVolume.RESOLUTION);
            int wrappedCell = MicrovoxelVolume.index(
                    Math.floorMod(localX, MicrovoxelVolume.RESOLUTION),
                    Math.floorMod(localY, MicrovoxelVolume.RESOLUTION),
                    Math.floorMod(localZ, MicrovoxelVolume.RESOLUTION));
            return new Target(blockX, blockY, blockZ, wrappedCell);
        }
    }

    public record Target(int x, int y, int z, int cell) {
    }

    private record Slab(double enter, double exit, MicrovoxelGreedyMesher.Direction face) {
    }
}
