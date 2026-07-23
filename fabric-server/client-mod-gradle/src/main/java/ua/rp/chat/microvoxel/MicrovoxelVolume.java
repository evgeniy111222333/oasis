package ua.rp.chat.microvoxel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

public final class MicrovoxelVolume {
    public static final int RESOLUTION = 16;
    public static final int CELL_COUNT = 4096;
    public static final int SIMPLE_COLLISION_CUBOID_LIMIT = 64;

    private int revision;
    private final List<String> palette;
    private final byte[] cells;
    private volatile List<Cuboid> cuboids;
    private volatile CollisionPlan collisionPlan;

    public MicrovoxelVolume(int revision, List<String> palette, byte[] cells) {
        if (cells.length != CELL_COUNT || palette.isEmpty() || !palette.get(0).isEmpty() || palette.size() > 32) {
            throw new IllegalArgumentException("Invalid microvoxel volume");
        }
        HashSet<String> unique = new HashSet<>();
        for (int index = 1; index < palette.size(); index++) {
            String material = palette.get(index);
            if (material == null || material.isBlank() || !unique.add(material)) {
                throw new IllegalArgumentException("Invalid or duplicate palette material");
            }
        }
        for (byte cell : cells) {
            if (Byte.toUnsignedInt(cell) >= palette.size()) throw new IllegalArgumentException("Invalid palette index");
        }
        this.revision = Math.max(1, revision);
        this.palette = new java.util.ArrayList<>(palette);
        this.cells = cells.clone();
    }

    public MicrovoxelVolume copy() {
        return new MicrovoxelVolume(revision, palette, cells);
    }

    public void setRevision(int revision) {
        this.revision = revision;
    }

    public boolean compactPalette() {
        boolean[] used = new boolean[palette.size()];
        used[0] = true;
        for (byte cell : cells) used[Byte.toUnsignedInt(cell)] = true;
        int retained = 0;
        for (boolean present : used) if (present) retained++;
        if (retained == palette.size()) return false;

        int[] remap = new int[palette.size()];
        List<String> compacted = new ArrayList<>(retained);
        for (int oldIndex = 0; oldIndex < palette.size(); oldIndex++) {
            if (!used[oldIndex]) continue;
            remap[oldIndex] = compacted.size();
            compacted.add(palette.get(oldIndex));
        }
        for (int cell = 0; cell < cells.length; cell++) {
            cells[cell] = (byte) remap[Byte.toUnsignedInt(cells[cell])];
        }
        palette.clear();
        palette.addAll(compacted);
        return true;
    }

    public void update(int cell, String blockData) {
        if (cell < 0 || cell >= CELL_COUNT) throw new IndexOutOfBoundsException();
        if (blockData == null || blockData.isBlank()) {
            cells[cell] = 0;
        } else {
            int paletteIndex = palette.indexOf(blockData);
            if (paletteIndex < 0) {
                if (palette.size() >= 32) {
                    throw new IllegalStateException("Microvoxel palette limit reached");
                }
                palette.add(blockData);
                paletteIndex = palette.size() - 1;
            }
            cells[cell] = (byte) paletteIndex;
        }
        changed();
    }

    private void changed() {
        revision++;
        cuboids = null;
        collisionPlan = null;
    }

    public static MicrovoxelVolume full(String material) {
        byte[] cells = new byte[CELL_COUNT];
        Arrays.fill(cells, (byte) 1);
        return new MicrovoxelVolume(1, List.of("", material), cells);
    }

    public int revision() {
        return revision;
    }

    public List<String> palette() {
        return palette;
    }

    public byte[] cellsCopy() {
        return cells.clone();
    }

    public int materialIndex(int cell) {
        return Byte.toUnsignedInt(cells[cell]);
    }

    public String material(int cell) {
        return palette.get(materialIndex(cell));
    }

    public boolean occupied(int cell) {
        return materialIndex(cell) != 0;
    }

    public int materialAt(int x, int y, int z) {
        return inside(x, y, z) ? Byte.toUnsignedInt(cells[index(x, y, z)]) : 0;
    }

    public List<Cuboid> collisionCuboids() {
        List<Cuboid> existing = cuboids;
        if (existing != null) return existing;
        CollisionPlan plan = collisionPlan;
        if (plan != null && plan.backend() == CollisionBackend.CUBOIDS) return plan.cuboids();
        List<Cuboid> result = buildCuboids(Integer.MAX_VALUE);
        cuboids = result;
        return result;
    }

    public CollisionPlan collisionPlan() {
        CollisionPlan existing = collisionPlan;
        if (existing != null) return existing;

        short[] xLines = new short[RESOLUTION * RESOLUTION];
        short[] yLines = new short[RESOLUTION * RESOLUTION];
        short[] zLines = new short[RESOLUTION * RESOLUTION];
        for (int y = 0; y < RESOLUTION; y++) {
            for (int z = 0; z < RESOLUTION; z++) {
                for (int x = 0; x < RESOLUTION; x++) {
                    if (materialAt(x, y, z) == 0) continue;
                    xLines[(y << 4) | z] |= (short) (1 << x);
                    yLines[(z << 4) | x] |= (short) (1 << y);
                    zLines[(y << 4) | x] |= (short) (1 << z);
                }
            }
        }

        List<Cuboid> simpleCuboids = buildCuboids(SIMPLE_COLLISION_CUBOID_LIMIT + 1);
        CollisionBackend backend = simpleCuboids.size() <= SIMPLE_COLLISION_CUBOID_LIMIT
                ? CollisionBackend.CUBOIDS : CollisionBackend.GRID;
        List<Cuboid> retained = backend == CollisionBackend.CUBOIDS ? simpleCuboids : List.of();
        CollisionPlan compiled = new CollisionPlan(backend, retained, xLines, yLines, zLines);
        if (backend == CollisionBackend.CUBOIDS) cuboids = retained;
        collisionPlan = compiled;
        return compiled;
    }

    private List<Cuboid> buildCuboids(int stopAfter) {
        boolean[] used = new boolean[CELL_COUNT];
        List<Cuboid> result = new ArrayList<>();
        for (int y = 0; y < RESOLUTION; y++) {
            for (int z = 0; z < RESOLUTION; z++) {
                for (int x = 0; x < RESOLUTION; x++) {
                    if (materialAt(x, y, z) == 0 || used[index(x, y, z)]) continue;
                    int maxX = x + 1;
                    while (maxX < RESOLUTION && available(used, maxX, y, z)) maxX++;
                    int maxZ = z + 1;
                    while (maxZ < RESOLUTION && planeAvailable(used, x, maxX, y, maxZ)) maxZ++;
                    int maxY = y + 1;
                    while (maxY < RESOLUTION && layerAvailable(used, x, maxX, z, maxZ, maxY)) maxY++;
                    for (int cy = y; cy < maxY; cy++) for (int cz = z; cz < maxZ; cz++)
                        for (int cx = x; cx < maxX; cx++) used[index(cx, cy, cz)] = true;
                    result.add(new Cuboid(x, y, z, maxX, maxY, maxZ));
                    if (result.size() >= stopAfter) return List.copyOf(result);
                }
            }
        }
        return Collections.unmodifiableList(result);
    }

    private boolean available(boolean[] used, int x, int y, int z) {
        return materialAt(x, y, z) != 0 && !used[index(x, y, z)];
    }

    private boolean planeAvailable(boolean[] used, int minX, int maxX, int y, int z) {
        for (int x = minX; x < maxX; x++) if (!available(used, x, y, z)) return false;
        return true;
    }

    private boolean layerAvailable(boolean[] used, int minX, int maxX, int minZ, int maxZ, int y) {
        for (int z = minZ; z < maxZ; z++) for (int x = minX; x < maxX; x++)
            if (!available(used, x, y, z)) return false;
        return true;
    }

    public static int index(int x, int y, int z) {
        if (!inside(x, y, z)) throw new IndexOutOfBoundsException();
        return x | (z << 4) | (y << 8);
    }

    public static int x(int cell) { return cell & 15; }
    public static int z(int cell) { return (cell >>> 4) & 15; }
    public static int y(int cell) { return (cell >>> 8) & 15; }

    public static boolean inside(int x, int y, int z) {
        return (x | y | z) >= 0 && x < RESOLUTION && y < RESOLUTION && z < RESOLUTION;
    }

    public record Cuboid(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
    }

    public enum CollisionBackend { CUBOIDS, GRID }

    public static final class CollisionPlan {
        private final CollisionBackend backend;
        private final List<Cuboid> cuboids;
        private final short[] xLines;
        private final short[] yLines;
        private final short[] zLines;

        private CollisionPlan(CollisionBackend backend, List<Cuboid> cuboids,
                              short[] xLines, short[] yLines, short[] zLines) {
            this.backend = backend;
            this.cuboids = List.copyOf(cuboids);
            this.xLines = xLines;
            this.yLines = yLines;
            this.zLines = zLines;
        }

        public CollisionBackend backend() { return backend; }
        public List<Cuboid> cuboids() { return cuboids; }
        public int xMask(int y, int z) { return Short.toUnsignedInt(xLines[(y << 4) | z]); }
        public int yMask(int z, int x) { return Short.toUnsignedInt(yLines[(z << 4) | x]); }
        public int zMask(int y, int x) { return Short.toUnsignedInt(zLines[(y << 4) | x]); }
    }
}
