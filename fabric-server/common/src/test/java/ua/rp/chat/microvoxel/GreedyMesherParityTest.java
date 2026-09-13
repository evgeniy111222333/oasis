package ua.rp.chat.microvoxel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.function.IntPredicate;

/**
 * Regression guard for the allocation-free greedy mesher. It keeps an independent, allocation-heavy
 * reference implementation (the historical jagged-array algorithm) and proves the optimized build
 * produces the identical face multiset for the exact, hidden, region and LOD paths over random
 * volumes. A second, algorithm-independent check compares the merged quad area per direction and
 * material with a brute-force per-cell face count, so a merging bug cannot hide behind a shared
 * algorithm.
 */
public final class GreedyMesherParityTest {
    private static final String[] MATERIALS = {
            "minecraft:stone", "minecraft:oak_planks", "minecraft:glass", "minecraft:wool"
    };

    public static void main(String[] args) {
        verifyHandcraftedCounts();
        verifyAreaInvariantMatchesBruteForce();
        verifyFuzzExact();
        verifyFuzzHidden();
        verifyFuzzRegion();
        verifyFuzzLodStrides();
        System.out.println("GreedyMesherParityTest passed");
    }

    private static void verifyHandcraftedCounts() {
        // The neighbour lookup must also serve the volume's own cells (that is what culls faces
        // between adjacent occupied cells); return 0 outside. This mirrors volume::materialAt.
        require(MicrovoxelGreedyMesher.build(
                MicrovoxelVolume.empty(), (x, y, z) -> 0).isEmpty(),
                "empty volume must mesh to zero faces");

        MicrovoxelVolume single = MicrovoxelVolume.empty();
        single.put(index(5, 6, 7), MATERIALS[0]);
        List<MicrovoxelGreedyMesher.Face> singleFaces =
                MicrovoxelGreedyMesher.build(single, single::materialAt);
        require(singleFaces.size() == 6, "one cell must emit six faces, got " + singleFaces.size());
        require(area(singleFaces) == 6, "one cell must have total area six");

        MicrovoxelVolume pair = MicrovoxelVolume.empty();
        pair.put(index(5, 6, 7), MATERIALS[0]);
        pair.put(index(6, 6, 7), MATERIALS[0]);
        List<MicrovoxelGreedyMesher.Face> pairFaces =
                MicrovoxelGreedyMesher.build(pair, pair::materialAt);
        require(area(pairFaces) == 10,
                "two adjacent cells must expose area ten (two shared faces culled), got "
                        + area(pairFaces));

        MicrovoxelVolume full = MicrovoxelVolume.full(MATERIALS[0]);
        List<MicrovoxelGreedyMesher.Face> fullFaces =
                MicrovoxelGreedyMesher.build(full, full::materialAt);
        require(fullFaces.size() == 6, "a full volume must merge to one face per direction");
        require(area(fullFaces) == 6 * 16 * 16, "a full volume must expose six 16x16 faces");
    }

    private static void verifyAreaInvariantMatchesBruteForce() {
        Random random = new Random(20260912L);
        for (int round = 0; round < 120; round++) {
            MicrovoxelVolume volume = randomVolume(random);
            MicrovoxelGreedyMesher.NeighbourLookup neighbours = neighboursFor(volume, round);
            List<MicrovoxelGreedyMesher.Face> mesh =
                    MicrovoxelGreedyMesher.build(volume, neighbours);
            long[][] merged = areaByDirectionMaterial(mesh);
            long[][] brute = bruteForceArea(volume, neighbours, null);
            require(equal(merged, brute),
                    "merged area must equal brute-force area per direction/material (round " + round + ")");
        }
    }

    private static void verifyFuzzExact() {
        Random random = new Random(7L);
        for (int round = 0; round < 250; round++) {
            MicrovoxelVolume volume = randomVolume(random);
            MicrovoxelGreedyMesher.NeighbourLookup neighbours = neighboursFor(volume, round);
            require(canonical(MicrovoxelGreedyMesher.build(volume, neighbours))
                            .equals(canonical(referenceExact(volume, neighbours, null, null))),
                    "optimized exact mesh must match the reference (round " + round + ")");
        }
    }

    private static void verifyFuzzHidden() {
        Random random = new Random(99L);
        for (int round = 0; round < 200; round++) {
            MicrovoxelVolume volume = randomVolume(random);
            MicrovoxelGreedyMesher.NeighbourLookup neighbours = neighboursFor(volume, round);
            boolean[] hidden = new boolean[MicrovoxelVolume.CELL_COUNT];
            for (int cell = 0; cell < hidden.length; cell++) {
                hidden[cell] = random.nextInt(5) == 0;
            }
            IntPredicate predicate = cell -> hidden[cell];
            require(canonical(MicrovoxelGreedyMesher.build(volume, neighbours, predicate))
                            .equals(canonical(referenceExact(volume, neighbours, predicate, null))),
                    "optimized hidden mesh must match the reference (round " + round + ")");
        }
    }

    private static void verifyFuzzRegion() {
        Random random = new Random(1234L);
        MicrovoxelGreedyMesher.RegionLookup region = (x, y, z) -> x >> 2;
        for (int round = 0; round < 200; round++) {
            MicrovoxelVolume volume = randomVolume(random);
            MicrovoxelGreedyMesher.NeighbourLookup neighbours = neighboursFor(volume, round);
            require(canonical(MicrovoxelGreedyMesher.build(volume, neighbours, null, region))
                            .equals(canonical(referenceExact(volume, neighbours, null, region))),
                    "optimized region mesh must match the reference (round " + round + ")");
        }
    }

    private static void verifyFuzzLodStrides() {
        Random random = new Random(55L);
        for (int round = 0; round < 120; round++) {
            MicrovoxelVolume volume = randomVolume(random);
            MicrovoxelGreedyMesher.NeighbourLookup neighbours = neighboursFor(volume, round);
            for (int stride : new int[]{2, 4}) {
                require(canonical(MicrovoxelGreedyMesher.build(volume, neighbours, stride))
                                .equals(canonical(referenceLod(volume, neighbours, stride))),
                        "optimized LOD stride " + stride + " must match the reference (round "
                                + round + ")");
            }
        }
    }

    // ------------------------------------------------------------------ reference (historical)

    private static List<MicrovoxelGreedyMesher.Face> referenceExact(
            MicrovoxelVolume volume, MicrovoxelGreedyMesher.NeighbourLookup neighbours,
            IntPredicate hidden, MicrovoxelGreedyMesher.RegionLookup region) {
        List<MicrovoxelGreedyMesher.Face> faces = new ArrayList<>();
        for (MicrovoxelGreedyMesher.Direction direction : MicrovoxelGreedyMesher.Direction.values()) {
            for (int slice = 0; slice < 16; slice++) {
                int[][] mask = new int[16][16];
                int[][] regionMask = region == null ? null : new int[16][16];
                for (int v = 0; v < 16; v++) {
                    for (int u = 0; u < 16; u++) {
                        int[] xyz = referenceCoordinates(direction, slice, u, v);
                        int material = referenceMaterial(volume::materialAt, hidden,
                                xyz[0], xyz[1], xyz[2]);
                        if (material != 0 && referenceMaterial(neighbours, hidden,
                                xyz[0] + direction.dx, xyz[1] + direction.dy, xyz[2] + direction.dz) == 0) {
                            mask[v][u] = material;
                            if (regionMask != null) regionMask[v][u] = region.regionAt(xyz[0], xyz[1], xyz[2]);
                        }
                    }
                }
                referenceGreedy(direction, slice, mask, regionMask, faces);
            }
        }
        return List.copyOf(faces);
    }

    private static int referenceMaterial(MicrovoxelGreedyMesher.NeighbourLookup lookup,
                                         IntPredicate hidden, int x, int y, int z) {
        if (hidden != null && x >= 0 && x < 16 && y >= 0 && y < 16 && z >= 0 && z < 16
                && hidden.test(x | (z << 4) | (y << 8))) {
            return 0;
        }
        return lookup.materialAt(x, y, z);
    }

    private static void referenceGreedy(MicrovoxelGreedyMesher.Direction direction, int slice,
                                        int[][] mask, int[][] regionMask,
                                        List<MicrovoxelGreedyMesher.Face> output) {
        boolean[][] used = new boolean[16][16];
        for (int v = 0; v < 16; v++) {
            for (int u = 0; u < 16; u++) {
                int material = mask[v][u];
                if (material == 0 || used[v][u]) continue;
                int region = regionMask == null ? 0 : regionMask[v][u];
                int width = 1;
                while (u + width < 16 && !used[v][u + width] && mask[v][u + width] == material
                        && (regionMask == null || regionMask[v][u + width] == region)) {
                    width++;
                }
                int height = 1;
                outer: while (v + height < 16) {
                    for (int x = u; x < u + width; x++) {
                        if (used[v + height][x] || mask[v + height][x] != material
                                || (regionMask != null && regionMask[v + height][x] != region)) {
                            break outer;
                        }
                    }
                    height++;
                }
                for (int y = v; y < v + height; y++) {
                    for (int x = u; x < u + width; x++) used[y][x] = true;
                }
                output.add(referenceFace(direction, slice, u, v, width, height, material));
            }
        }
    }

    private static List<MicrovoxelGreedyMesher.Face> referenceLod(
            MicrovoxelVolume volume, MicrovoxelGreedyMesher.NeighbourLookup neighbours, int stride) {
        int cells = 16 / stride;
        List<MicrovoxelGreedyMesher.Face> faces = new ArrayList<>();
        for (MicrovoxelGreedyMesher.Direction direction : MicrovoxelGreedyMesher.Direction.values()) {
            for (int slice = 0; slice < cells; slice++) {
                int[][] mask = new int[cells][cells];
                for (int v = 0; v < cells; v++) {
                    for (int u = 0; u < cells; u++) {
                        int[] base = referenceCoordinates(direction, slice, u, v);
                        int baseX = base[0] * stride;
                        int baseY = base[1] * stride;
                        int baseZ = base[2] * stride;
                        int material = referenceDominant(volume, baseX, baseY, baseZ, stride);
                        if (material != 0 && referenceNeighbourFree(
                                neighbours, direction, baseX, baseY, baseZ, stride)) {
                            mask[v][u] = material;
                        }
                    }
                }
                referenceGreedyLod(direction, slice, stride, cells, mask, faces);
            }
        }
        return List.copyOf(faces);
    }

    private static int referenceDominant(MicrovoxelVolume volume, int baseX, int baseY, int baseZ, int stride) {
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

    private static boolean referenceNeighbourFree(
            MicrovoxelGreedyMesher.NeighbourLookup neighbours, MicrovoxelGreedyMesher.Direction direction,
            int baseX, int baseY, int baseZ, int stride) {
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

    private static void referenceGreedyLod(MicrovoxelGreedyMesher.Direction direction, int slice,
                                           int stride, int cells, int[][] mask,
                                           List<MicrovoxelGreedyMesher.Face> output) {
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
                int base = slice * stride;
                int span = stride;
                int u0 = u * stride;
                int v0 = v * stride;
                int u1 = u0 + width * stride;
                int v1 = v0 + height * stride;
                output.add(switch (direction) {
                    case UP -> new MicrovoxelGreedyMesher.Face(direction, material, u0, base + span, v0, u1, base + span, v1);
                    case DOWN -> new MicrovoxelGreedyMesher.Face(direction, material, u0, base, v0, u1, base, v1);
                    case NORTH -> new MicrovoxelGreedyMesher.Face(direction, material, u0, v0, base, u1, v1, base);
                    case SOUTH -> new MicrovoxelGreedyMesher.Face(direction, material, u0, v0, base + span, u1, v1, base + span);
                    case WEST -> new MicrovoxelGreedyMesher.Face(direction, material, base, v0, u0, base, v1, u1);
                    case EAST -> new MicrovoxelGreedyMesher.Face(direction, material, base + span, v0, u0, base + span, v1, u1);
                });
            }
        }
    }

    private static int[] referenceCoordinates(MicrovoxelGreedyMesher.Direction direction,
                                              int slice, int u, int v) {
        return switch (direction) {
            case UP, DOWN -> new int[]{u, slice, v};
            case NORTH, SOUTH -> new int[]{u, v, slice};
            case WEST, EAST -> new int[]{slice, v, u};
        };
    }

    private static MicrovoxelGreedyMesher.Face referenceFace(
            MicrovoxelGreedyMesher.Direction direction, int slice, int u, int v,
            int width, int height, int material) {
        return switch (direction) {
            case UP -> new MicrovoxelGreedyMesher.Face(direction, material, u, slice + 1, v, u + width, slice + 1, v + height);
            case DOWN -> new MicrovoxelGreedyMesher.Face(direction, material, u, slice, v, u + width, slice, v + height);
            case NORTH -> new MicrovoxelGreedyMesher.Face(direction, material, u, v, slice, u + width, v + height, slice);
            case SOUTH -> new MicrovoxelGreedyMesher.Face(direction, material, u, v, slice + 1, u + width, v + height, slice + 1);
            case WEST -> new MicrovoxelGreedyMesher.Face(direction, material, slice, v, u, slice, v + height, u + width);
            case EAST -> new MicrovoxelGreedyMesher.Face(direction, material, slice + 1, v, u, slice + 1, v + height, u + width);
        };
    }

    // ------------------------------------------------------------------ helpers

    private static MicrovoxelVolume randomVolume(Random random) {
        MicrovoxelVolume volume = MicrovoxelVolume.empty();
        int materials = 1 + random.nextInt(MATERIALS.length);
        for (int cell = 0; cell < MicrovoxelVolume.CELL_COUNT; cell++) {
            if (random.nextInt(10) < 3) {
                volume.put(cell, MATERIALS[random.nextInt(materials)]);
            }
        }
        return volume;
    }

    /**
     * Realistic neighbour lookup: the volume's own cells plus an optionally solid outside shell,
     * exactly the shape the client feeds the world mesher.
     */
    private static MicrovoxelGreedyMesher.NeighbourLookup neighboursFor(MicrovoxelVolume volume, int round) {
        if (round % 3 == 0) {
            return (x, y, z) -> x >= 0 && x < 16 && y >= 0 && y < 16 && z >= 0 && z < 16
                    ? volume.materialAt(x, y, z) : 1;
        }
        return volume::materialAt;
    }

    private static int index(int x, int y, int z) {
        return x | (z << 4) | (y << 8);
    }

    private static long area(List<MicrovoxelGreedyMesher.Face> faces) {
        long total = 0;
        for (MicrovoxelGreedyMesher.Face face : faces) {
            total += (long) Math.max(1, face.maxX() - face.minX())
                    * Math.max(1, face.maxY() - face.minY())
                    * Math.max(1, face.maxZ() - face.minZ());
        }
        return total;
    }

    private static long[][] areaByDirectionMaterial(List<MicrovoxelGreedyMesher.Face> faces) {
        long[][] result = new long[MicrovoxelGreedyMesher.Direction.values().length][MicrovoxelVolume.MAX_PALETTE];
        for (MicrovoxelGreedyMesher.Face face : faces) {
            long quadArea = (long) Math.max(1, face.maxX() - face.minX())
                    * Math.max(1, face.maxY() - face.minY())
                    * Math.max(1, face.maxZ() - face.minZ());
            result[face.direction().ordinal()][face.material()] += quadArea;
        }
        return result;
    }

    private static long[][] bruteForceArea(MicrovoxelVolume volume,
                                           MicrovoxelGreedyMesher.NeighbourLookup neighbours,
                                           IntPredicate hidden) {
        long[][] result = new long[MicrovoxelGreedyMesher.Direction.values().length][MicrovoxelVolume.MAX_PALETTE];
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int material = referenceMaterial(volume::materialAt, hidden, x, y, z);
                    if (material == 0) continue;
                    for (MicrovoxelGreedyMesher.Direction direction : MicrovoxelGreedyMesher.Direction.values()) {
                        if (referenceMaterial(neighbours, hidden,
                                x + direction.dx, y + direction.dy, z + direction.dz) == 0) {
                            result[direction.ordinal()][material]++;
                        }
                    }
                }
            }
        }
        return result;
    }

    private static boolean equal(long[][] a, long[][] b) {
        for (int i = 0; i < a.length; i++) {
            if (!java.util.Arrays.equals(a[i], b[i])) return false;
        }
        return true;
    }

    private static List<String> canonical(List<MicrovoxelGreedyMesher.Face> faces) {
        List<String> keys = new ArrayList<>(faces.size());
        for (MicrovoxelGreedyMesher.Face face : faces) {
            keys.add(face.direction() + ":" + face.material() + ":" + face.minX() + "," + face.minY()
                    + "," + face.minZ() + "-" + face.maxX() + "," + face.maxY() + "," + face.maxZ());
        }
        keys.sort(Comparator.naturalOrder());
        return keys;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError("GreedyMesherParityTest: " + message);
    }
}
