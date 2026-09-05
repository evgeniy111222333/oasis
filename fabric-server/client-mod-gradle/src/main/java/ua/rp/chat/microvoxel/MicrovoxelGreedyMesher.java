package ua.rp.chat.microvoxel;

import java.util.ArrayList;
import java.util.List;

public final class MicrovoxelGreedyMesher {
    private MicrovoxelGreedyMesher() {
    }

    /**
     * Full-resolution greedy mesh (stride 1). Bit-identical to the historical path.
     */
    public static List<Face> build(MicrovoxelVolume volume, NeighbourLookup neighbours) {
        return build(volume, neighbours, 1);
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
        for (Direction direction : Direction.values()) {
            for (int slice = 0; slice < cells; slice++) {
                int[][] mask = new int[cells][cells];
                for (int v = 0; v < cells; v++) {
                    for (int u = 0; u < cells; u++) {
                        int[] xyz = lodCoordinates(direction, slice, u, v, stride);
                        int material = dominantMaterial(volume, xyz[0], xyz[1], xyz[2], stride);
                        if (material != 0 && lodNeighbourFree(
                                volume, neighbours, direction, xyz[0], xyz[1], xyz[2], stride)) {
                            mask[v][u] = material;
                        }
                    }
                }
                greedyLod(direction, slice, stride, mask, faces);
            }
        }
        return List.copyOf(faces);
    }

    /** Dominant (most frequent, ties broken by lowest id) material of one stride block. */
    private static int dominantMaterial(MicrovoxelVolume volume, int baseX, int baseY, int baseZ, int stride) {
        int[] histogram = new int[MicrovoxelVolume.MAX_PALETTE];
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

    private static int[] lodCoordinates(Direction direction, int slice, int u, int v, int stride) {
        int[] base = coordinates(direction, slice, u, v);
        return new int[]{base[0] * stride, base[1] * stride, base[2] * stride};
    }

    private static void greedyLod(Direction direction, int slice, int stride,
                                  int[][] mask, List<Face> output) {
        int cells = mask.length;
        boolean[][] used = new boolean[cells][cells];
        for (int v = 0; v < cells; v++) {
            for (int u = 0; u < cells; u++) {
                int material = mask[v][u];
                if (material == 0 || used[v][u]) continue;
                int width = 1;
                while (u + width < cells && !used[v][u + width] && mask[v][u + width] == material) width++;
                int height = 1;
                outer: while (v + height < cells) {
                    for (int x = u; x < u + width; x++) {
                        if (used[v + height][x] || mask[v + height][x] != material) break outer;
                    }
                    height++;
                }
                for (int y = v; y < v + height; y++) for (int x = u; x < u + width; x++) used[y][x] = true;
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
        List<Face> faces = new ArrayList<>();
        for (Direction direction : Direction.values()) {
            for (int slice = 0; slice < 16; slice++) {
                int[][] mask = new int[16][16];
                for (int v = 0; v < 16; v++) {
                    for (int u = 0; u < 16; u++) {
                        int[] xyz = coordinates(direction, slice, u, v);
                        int material = volume.materialAt(xyz[0], xyz[1], xyz[2]);
                        if (material != 0 && neighbours.materialAt(
                                xyz[0] + direction.dx, xyz[1] + direction.dy, xyz[2] + direction.dz) == 0) {
                            mask[v][u] = material;
                        }
                    }
                }
                greedy(direction, slice, mask, faces);
            }
        }
        return List.copyOf(faces);
    }

    private static void greedy(Direction direction, int slice, int[][] mask, List<Face> output) {
        boolean[][] used = new boolean[16][16];
        for (int v = 0; v < 16; v++) {
            for (int u = 0; u < 16; u++) {
                int material = mask[v][u];
                if (material == 0 || used[v][u]) continue;
                int width = 1;
                while (u + width < 16 && !used[v][u + width] && mask[v][u + width] == material) width++;
                int height = 1;
                outer: while (v + height < 16) {
                    for (int x = u; x < u + width; x++) {
                        if (used[v + height][x] || mask[v + height][x] != material) break outer;
                    }
                    height++;
                }
                for (int y = v; y < v + height; y++) for (int x = u; x < u + width; x++) used[y][x] = true;
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

    private static int[] coordinates(Direction direction, int slice, int u, int v) {
        return switch (direction) {
            case UP, DOWN -> new int[]{u, slice, v};
            case NORTH, SOUTH -> new int[]{u, v, slice};
            case WEST, EAST -> new int[]{slice, v, u};
        };
    }

    public interface NeighbourLookup {
        int materialAt(int x, int y, int z);
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
