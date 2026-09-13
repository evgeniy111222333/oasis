package ua.rp.chat.microvoxel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.IntPredicate;

/**
 * Greedy mesher shared by both sides. The hot loops are allocation-free by design: the per-slice
 * mask/region/used buffers are flat arrays reused across all six directions and sixteen slices of
 * one build, and local coordinates are computed with switch expressions instead of the historical
 * {@code new int[3]} per cell. A full 16^3 build used to churn ~24 000 coordinate arrays plus 288
 * jagged mask arrays; it now allocates a handful of flat buffers, which is what removes the
 * per-edit tick spike on the client. All buffers are per-call (never static), so concurrent mesh
 * workers stay isolated.
 */
public final class MicrovoxelGreedyMesher {
    private static final int SIZE = 16;

    private MicrovoxelGreedyMesher() {
    }

    /**
     * Full-resolution greedy mesh (stride 1). Bit-identical to the historical path.
     */
    public static List<Face> build(MicrovoxelVolume volume, NeighbourLookup neighbours) {
        return build(volume, neighbours, 1);
    }

    /**
     * Exact mesh with a hidden-cell predicate: a hidden cell reads as air for both occupancy
     * and neighbour culling, so it contributes no faces and exposes its neighbours' faces on
     * the cell boundary. The Carver hologram uses this to render the live carving minus its
     * draft without ever mutating or copying the volume, which makes a released stroke vanish
     * (and the fresh cavity walls appear) on the very next frame. Null keeps the plain mesh.
     */
    public static List<Face> build(MicrovoxelVolume volume, NeighbourLookup neighbours,
                                   IntPredicate hidden) {
        return buildExact(volume, neighbours, hidden);
    }

    /**
     * Exact mesh that additionally gates merging by a per-cell region: neighbouring cells only
     * merge when both their material and their region match. The Carver feeds the grain domain
     * here, so banded geology survives greedy merging as one quad per band, which the renderer
     * then tints per domain. A null region keeps the plain material merge (bit-identical).
     */
    public static List<Face> build(MicrovoxelVolume volume, NeighbourLookup neighbours,
                                   IntPredicate hidden, RegionLookup region) {
        return buildExact(volume, neighbours, hidden, region);
    }

    /**
     * Strided greedy mesh for distance LOD. A stride-N mesh samples each NxNxN block as one
     * merged cell (occupied when any sub-cell is occupied, dominant material wins) and emits
     * faces on the N-cell grid, so far volumes compile to a fraction of the quads while keeping
     * the exact silhouette bounds. Stride must divide 16; stride 1 is the exact mesh.
     */
    public static List<Face> build(MicrovoxelVolume volume, NeighbourLookup neighbours, int stride) {
        if (stride <= 1) {
            return buildExact(volume, neighbours);
        }
        if (16 % stride != 0) {
            throw new IllegalArgumentException("LOD stride must divide 16: " + stride);
        }
        int cells = 16 / stride;
        List<Face> faces = new ArrayList<>();
        int[] mask = new int[cells * cells];
        boolean[] used = new boolean[cells * cells];
        int[] histogram = new int[MicrovoxelVolume.MAX_PALETTE];
        for (Direction direction : Direction.values()) {
            for (int slice = 0; slice < cells; slice++) {
                Arrays.fill(mask, 0);
                for (int v = 0; v < cells; v++) {
                    for (int u = 0; u < cells; u++) {
                        int baseX;
                        int baseY;
                        int baseZ;
                        switch (direction) {
                            case UP, DOWN -> {
                                baseX = u * stride;
                                baseY = slice * stride;
                                baseZ = v * stride;
                            }
                            case NORTH, SOUTH -> {
                                baseX = u * stride;
                                baseY = v * stride;
                                baseZ = slice * stride;
                            }
                            default -> {
                                baseX = slice * stride;
                                baseY = v * stride;
                                baseZ = u * stride;
                            }
                        }
                        int material = dominantMaterial(volume, baseX, baseY, baseZ, stride, histogram);
                        if (material != 0 && lodNeighbourFree(
                                volume, neighbours, direction, baseX, baseY, baseZ, stride)) {
                            mask[v * cells + u] = material;
                        }
                    }
                }
                greedyLod(direction, slice, stride, cells, mask, used, faces);
            }
        }
        return List.copyOf(faces);
    }

    /** Dominant (most frequent, ties broken by lowest id) material of one stride block. */
    private static int dominantMaterial(MicrovoxelVolume volume, int baseX, int baseY, int baseZ,
                                        int stride, int[] histogram) {
        Arrays.fill(histogram, 0);
        boolean occupied = false;
        for (int dz = 0; dz < stride; dz++) {
            for (int dy = 0; dy < stride; dy++) {
                for (int dx = 0; dx < stride; dx++) {
                    int material = volume.materialAt(baseX + dx, baseY + dy, baseZ + dz);
                    if (material != 0) {
                        occupied = true;
                        if (material < histogram.length) histogram[material]++;
                    }
                }
            }
        }
        if (!occupied) return 0;
        int best = 0;
        for (int candidate = 1; candidate < histogram.length; candidate++) {
            if (histogram[candidate] > histogram[best]) best = candidate;
        }
        return best;
    }

    /** True when the neighbouring stride block (or vanilla solid) leaves this face visible. */
    private static boolean lodNeighbourFree(MicrovoxelVolume volume, NeighbourLookup neighbours,
                                            Direction direction, int baseX, int baseY, int baseZ, int stride) {
        int nx = baseX + direction.dx * stride;
        int ny = baseY + direction.dy * stride;
        int nz = baseZ + direction.dz * stride;
        for (int dz = 0; dz < stride; dz++) {
            for (int dy = 0; dy < stride; dy++) {
                for (int dx = 0; dx < stride; dx++) {
                    if (neighbours.materialAt(nx + dx, ny + dy, nz + dz) == 0) return true;
                }
            }
        }
        return false;
    }

    private static void greedyLod(Direction direction, int slice, int stride, int cells,
                                  int[] mask, boolean[] used, List<Face> output) {
        Arrays.fill(used, false);
        for (int v = 0; v < cells; v++) {
            for (int u = 0; u < cells; u++) {
                int material = mask[v * cells + u];
                if (material == 0 || used[v * cells + u]) continue;
                int width = 1;
                while (u + width < cells && !used[v * cells + u + width]
                        && mask[v * cells + u + width] == material) {
                    width++;
                }
                int height = 1;
                outer: while (v + height < cells) {
                    int row = (v + height) * cells;
                    for (int x = u; x < u + width; x++) {
                        if (used[row + x] || mask[row + x] != material) break outer;
                    }
                    height++;
                }
                for (int y = 0; y < height; y++) {
                    int row = (v + y) * cells + u;
                    for (int x = 0; x < width; x++) used[row + x] = true;
                }
                // LOD faces sit on stride-grid planes (multiples of stride), spanning whole
                // stride blocks: the outer plane is base+stride on the positive side, base on
                // the negative side, so merged quads keep exact silhouette bounds.
                int base = slice * stride;
                int span = stride;
                int u0 = u * stride;
                int v0 = v * stride;
                int u1 = u0 + width * stride;
                int v1 = v0 + height * stride;
                output.add(switch (direction) {
                    case UP -> new Face(direction, material, u0, base + span, v0, u1, base + span, v1);
                    case DOWN -> new Face(direction, material, u0, base, v0, u1, base, v1);
                    case NORTH -> new Face(direction, material, u0, v0, base, u1, v1, base);
                    case SOUTH -> new Face(direction, material, u0, v0, base + span, u1, v1, base + span);
                    case WEST -> new Face(direction, material, base, v0, u0, base, v1, u1);
                    case EAST -> new Face(direction, material, base + span, v0, u0, base + span, v1, u1);
                });
            }
        }
    }

    private static List<Face> buildExact(MicrovoxelVolume volume, NeighbourLookup neighbours) {
        return buildExact(volume, neighbours, null, null);
    }

    private static List<Face> buildExact(MicrovoxelVolume volume, NeighbourLookup neighbours,
                                         IntPredicate hidden) {
        return buildExact(volume, neighbours, hidden, null);
    }

    private static List<Face> buildExact(MicrovoxelVolume volume, NeighbourLookup neighbours,
                                         IntPredicate hidden, RegionLookup region) {
        List<Face> faces = new ArrayList<>();
        // One set of flat buffers for the whole build; cleared per slice. Hoisting the bound
        // method reference stops a captured lambda allocation per cell.
        NeighbourLookup volumeLookup = volume::materialAt;
        int[] mask = new int[SIZE * SIZE];
        int[] regionMask = region == null ? null : new int[SIZE * SIZE];
        boolean[] used = new boolean[SIZE * SIZE];
        for (Direction direction : Direction.values()) {
            for (int slice = 0; slice < SIZE; slice++) {
                Arrays.fill(mask, 0);
                if (regionMask != null) Arrays.fill(regionMask, 0);
                for (int v = 0; v < SIZE; v++) {
                    for (int u = 0; u < SIZE; u++) {
                        int x;
                        int y;
                        int z;
                        switch (direction) {
                            case UP, DOWN -> {
                                x = u;
                                y = slice;
                                z = v;
                            }
                            case NORTH, SOUTH -> {
                                x = u;
                                y = v;
                                z = slice;
                            }
                            default -> {
                                x = slice;
                                y = v;
                                z = u;
                            }
                        }
                        int material = maskedMaterial(volumeLookup, hidden, x, y, z);
                        if (material != 0 && maskedMaterial(neighbours, hidden,
                                x + direction.dx, y + direction.dy, z + direction.dz) == 0) {
                            int cell = v * SIZE + u;
                            mask[cell] = material;
                            if (regionMask != null) {
                                regionMask[cell] = region.regionAt(x, y, z);
                            }
                        }
                    }
                }
                greedy(direction, slice, mask, regionMask, used, faces);
            }
        }
        return List.copyOf(faces);
    }

    /**
     * Material read that treats a hidden cell inside the 16^3 lattice as air. The cell index
     * matches {@code MicrovoxelVolume.index} / {@code DraftMask.index} exactly, so the Carver
     * draft mask can be fed in without a translation layer. Coordinates outside the lattice
     * (real neighbours) are never hidden.
     */
    private static int maskedMaterial(NeighbourLookup lookup, IntPredicate hidden,
                                      int x, int y, int z) {
        if (hidden != null && x >= 0 && x < 16 && y >= 0 && y < 16 && z >= 0 && z < 16
                && hidden.test(x | (z << 4) | (y << 8))) {
            return 0;
        }
        return lookup.materialAt(x, y, z);
    }

    private static void greedy(Direction direction, int slice, int[] mask, int[] regionMask,
                               boolean[] used, List<Face> output) {
        Arrays.fill(used, false);
        for (int v = 0; v < SIZE; v++) {
            for (int u = 0; u < SIZE; u++) {
                int material = mask[v * SIZE + u];
                if (material == 0 || used[v * SIZE + u]) continue;
                int region = regionMask == null ? 0 : regionMask[v * SIZE + u];
                int width = 1;
                while (u + width < SIZE && !used[v * SIZE + u + width]
                        && mask[v * SIZE + u + width] == material
                        && (regionMask == null || regionMask[v * SIZE + u + width] == region)) {
                    width++;
                }
                int height = 1;
                outer: while (v + height < SIZE) {
                    int row = (v + height) * SIZE;
                    for (int x = u; x < u + width; x++) {
                        if (used[row + x] || mask[row + x] != material
                                || (regionMask != null && regionMask[row + x] != region)) {
                            break outer;
                        }
                    }
                    height++;
                }
                for (int y = 0; y < height; y++) {
                    int row = (v + y) * SIZE + u;
                    for (int x = 0; x < width; x++) used[row + x] = true;
                }
                output.add(face(direction, slice, u, v, width, height, material));
            }
        }
    }

    private static Face face(Direction direction, int slice, int u, int v, int width, int height, int material) {
        return switch (direction) {
            case UP -> new Face(direction, material, u, slice + 1, v, u + width, slice + 1, v + height);
            case DOWN -> new Face(direction, material, u, slice, v, u + width, slice, v + height);
            case NORTH -> new Face(direction, material, u, v, slice, u + width, v + height, slice);
            case SOUTH -> new Face(direction, material, u, v, slice + 1, u + width, v + height, slice + 1);
            case WEST -> new Face(direction, material, slice, v, u, slice, v + height, u + width);
            case EAST -> new Face(direction, material, slice + 1, v, u, slice + 1, v + height, u + width);
        };
    }

    public interface NeighbourLookup {
        int materialAt(int x, int y, int z);
    }

    /** Per-cell merge region: two cells merge only when material AND region match. */
    public interface RegionLookup {
        int regionAt(int x, int y, int z);
    }

    public enum Direction {
        DOWN(0, -1, 0), UP(0, 1, 0), NORTH(0, 0, -1), SOUTH(0, 0, 1), WEST(-1, 0, 0), EAST(1, 0, 0);
        public final int dx;
        public final int dy;
        public final int dz;

        Direction(int dx, int dy, int dz) {
            this.dx = dx;
            this.dy = dy;
            this.dz = dz;
        }
    }

    public record Face(Direction direction, int material, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
    }
}
