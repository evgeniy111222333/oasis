package ua.rp.chat.microvoxel;

import java.util.ArrayList;
import java.util.List;

public final class MicrovoxelGreedyMesher {
    private MicrovoxelGreedyMesher() {
    }

    public static List<Face> build(MicrovoxelVolume volume, NeighbourLookup neighbours) {
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
