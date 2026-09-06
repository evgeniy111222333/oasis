package ua.rp.chat.microvoxel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

public final class MicrovoxelVolume {
    public static final int RESOLUTION = 16;
    public static final int CELL_COUNT = 4096;
    public static final int MAX_PALETTE = 32;
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
        revision = MicrovoxelRevision.next(revision);
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

    /** Face bits for {@link #sealedOpaqueFaces}: -X, +X, -Y, +Y, -Z, +Z. */
    public static final int FACE_WEST = 1;
    public static final int FACE_EAST = 2;
    public static final int FACE_DOWN = 4;
    public static final int FACE_UP = 8;
    public static final int FACE_NORTH = 16;
    public static final int FACE_SOUTH = 32;
    /** Mask value meaning every boundary face is fully covered by opaque cells. */
    public static final int ALL_FACES_SEALED = 63;
    /**
     * Dense-volume fallback for light sealing (client mirror of the server rule): sealed faces,
     * dense fraction or an axial plate. Same contract, same thresholds, so both light engines
     * agree on every volume.
     */
    public static final double LIGHT_SEAL_MIN_OPAQUE_FRACTION = 0.5;
    /**
     * Minimum contiguous opaque run (in cells) for the axial plate rule. Mirror of the server
     * constant; a 4-cell plate reads as a wall while thinner detail stays transparent.
     */
    public static final int LIGHT_SEAL_MIN_AXIAL_RUN = 4;

    /**
     * Whether the light engine should treat this position as the parent material.
     * Mirror of the server helper; kept verbatim so both engines decide identically.
     */
    public boolean isLightSealed(java.util.function.Predicate<String> opaque) {
        boolean[] opaquePalette = new boolean[palette.size()];
        for (int index = 1; index < palette.size(); index++) {
            opaquePalette[index] = opaque.test(palette.get(index));
        }
        return sealedFaces(opaquePalette) == ALL_FACES_SEALED
                || opaqueFraction(opaquePalette) >= LIGHT_SEAL_MIN_OPAQUE_FRACTION
                || axialRunCovered(opaquePalette, LIGHT_SEAL_MIN_AXIAL_RUN);
    }

    /**
     * Axial plate rule (client mirror): every column along ANY axis carries a contiguous
     * opaque run of at least {@code minRun} cells. Coverage requires ALL columns of the
     * axis, so hollow structures fail every axis while spanning walls seal.
     */
    public boolean axialRunCovered(boolean[] opaquePalette, int minRun) {
        return axisRunCovered(opaquePalette, minRun, 0)
                || axisRunCovered(opaquePalette, minRun, 1)
                || axisRunCovered(opaquePalette, minRun, 2);
    }

    private boolean axisRunCovered(boolean[] opaquePalette, int minRun, int axis) {
        for (int a = 0; a < RESOLUTION; a++) {
            for (int b = 0; b < RESOLUTION; b++) {
                int run = 0;
                for (int c = 0; c < RESOLUTION; c++) {
                    // Axis 0 walks X columns (y=a, z=b); axis 1 walks Y (x=a, z=b);
                    // axis 2 walks Z (x=a, y=b).
                    int x = axis == 0 ? c : a;
                    int y = axis == 1 ? c : axis == 0 ? a : b;
                    int z = axis == 2 ? c : b;
                    if (opaquePalette[Byte.toUnsignedInt(cells[x | (z << 4) | (y << 8)])]) {
                        if (++run >= minRun) break;
                    } else {
                        run = 0;
                    }
                }
                if (run < minRun) return false;
            }
        }
        return true;
    }

    /**
     * Fraction of cells occupied by opaque materials, 0.0 when empty. One linear scan, no
     * allocation; opacity is resolved per palette entry, not per cell.
     */
    public double opaqueFraction(java.util.function.Predicate<String> opaque) {
        boolean[] opaquePalette = new boolean[palette.size()];
        for (int index = 1; index < palette.size(); index++) {
            opaquePalette[index] = opaque.test(palette.get(index));
        }
        return opaqueFraction(opaquePalette);
    }

    private double opaqueFraction(boolean[] opaquePalette) {
        int opaqueCells = 0;
        for (byte cell : cells) {
            if (opaquePalette[Byte.toUnsignedInt(cell)]) opaqueCells++;
        }
        return opaqueCells / (double) CELL_COUNT;
    }

    /**
     * Exposed emissive cells needed for full glow (client mirror of the server rule): a 4x4
     * patch reads as a full source while a lone cell glows dimly.
     */
    public static final int FULL_GLOW_EXPOSED_CELLS = 16;

    /**
     * Counts emissive cells exposed to air (client mirror). A torch bricked inside stone
     * contributes nothing, so local predictions never flash full brightness.
     */
    public int exposedEmissiveCount(java.util.function.ToIntFunction<String> emissionOf) {
        int exposed = 0;
        for (int cell = 0; cell < CELL_COUNT; cell++) {
            int materialIndex = Byte.toUnsignedInt(cells[cell]);
            if (materialIndex == 0) continue;
            if (emissionOf.applyAsInt(palette.get(materialIndex)) <= 0) continue;
            int x = cell & 15;
            int y = (cell >>> 8) & 15;
            int z = (cell >>> 4) & 15;
            boolean openNeighbour = (!occupied(x - 1, y, z) && inside(x - 1, y, z))
                    || (!occupied(x + 1, y, z) && inside(x + 1, y, z))
                    || (!occupied(x, y - 1, z) && inside(x, y - 1, z))
                    || (!occupied(x, y + 1, z) && inside(x, y + 1, z))
                    || (!occupied(x, y, z - 1) && inside(x, y, z - 1))
                    || (!occupied(x, y, z + 1) && inside(x, y, z + 1));
            boolean onBoundary = x == 0 || x == RESOLUTION - 1
                    || y == 0 || y == RESOLUTION - 1
                    || z == 0 || z == RESOLUTION - 1;
            if (openNeighbour || onBoundary) {
                exposed++;
            }
        }
        return exposed;
    }

    /**
     * Fractional block-light level (client mirror): scales the strongest exposed emission by
     * coverage, so predicted placements glow exactly as the server will confirm them instead
     * of flashing full brightness for a tick.
     */
    public int emissionLevel(java.util.function.ToIntFunction<String> emissionOf) {
        int strongest = 0;
        for (int index = 1; index < palette.size(); index++) {
            strongest = Math.max(strongest, emissionOf.applyAsInt(palette.get(index)));
        }
        if (strongest <= 0) return 0;
        int exposed = exposedEmissiveCount(emissionOf);
        if (exposed <= 0) return 0;
        double coverage = Math.min(1.0, exposed / (double) FULL_GLOW_EXPOSED_CELLS);
        return Math.max(1, (int) Math.round(strongest * (0.25 + 0.75 * coverage)));
    }

    /**
     * Bitmask of boundary faces whose every cell is occupied by an opaque material. Mirror of
     * the server helper (same contract, same bit order): the opacity predicate receives
     * palette strings so this stays pure and unit-testable on both sides.
     */
    public int sealedOpaqueFaces(java.util.function.Predicate<String> opaque) {
        boolean[] opaquePalette = new boolean[palette.size()];
        for (int index = 1; index < palette.size(); index++) {
            opaquePalette[index] = opaque.test(palette.get(index));
        }
        return sealedFaces(opaquePalette);
    }

    private int sealedFaces(boolean[] opaquePalette) {
        int sealed = 0;
        if (isFaceSealed(0, opaquePalette)) sealed |= FACE_WEST;
        if (isFaceSealed(1, opaquePalette)) sealed |= FACE_EAST;
        if (isFaceSealed(2, opaquePalette)) sealed |= FACE_DOWN;
        if (isFaceSealed(3, opaquePalette)) sealed |= FACE_UP;
        if (isFaceSealed(4, opaquePalette)) sealed |= FACE_NORTH;
        if (isFaceSealed(5, opaquePalette)) sealed |= FACE_SOUTH;
        return sealed;
    }

    private boolean isFaceSealed(int face, boolean[] opaquePalette) {
        for (int a = 0; a < RESOLUTION; a++) {
            for (int b = 0; b < RESOLUTION; b++) {
                int x = face == 0 ? 0 : face == 1 ? RESOLUTION - 1 : a;
                int y = face == 2 ? 0 : face == 3 ? RESOLUTION - 1 : face < 2 ? a : b;
                int z = face == 4 ? 0 : face == 5 ? RESOLUTION - 1 : face < 2 ? b : a;
                int materialIndex = Byte.toUnsignedInt(cells[x | (z << 4) | (y << 8)]);
                if (materialIndex == 0 || !opaquePalette[materialIndex]) return false;
            }
        }
        return true;
    }

    /**
     * Compares only the outer shell (any coordinate 0 or 15) against another volume without
     * copying either cell array. Edit hot paths call this per click to decide whether
     * neighbours need a mesh rebuild; two 4KB clones per click were pure waste with an early
     * exit on the first boundary difference in the common single-cell case.
     */
    public boolean boundaryDiffersFrom(MicrovoxelVolume other) {
        if (other == null) return true;
        byte[] mine = cells;
        byte[] theirs = other.cells;
        int length = Math.min(mine.length, theirs.length);
        for (int i = 0; i < length; i++) {
            if (mine[i] != theirs[i]) {
                int cx = x(i);
                int cy = y(i);
                int cz = z(i);
                if (cx == 0 || cx == RESOLUTION - 1 || cy == 0 || cy == RESOLUTION - 1
                        || cz == 0 || cz == RESOLUTION - 1) {
                    return true;
                }
            }
        }
        return mine.length != theirs.length;
    }

    public int materialIndex(int cell) {
        return Byte.toUnsignedInt(cells[cell]);
    }

    public String material(int cell) {
        return palette.get(materialIndex(cell));
    }

    /**
     * Dominant occupied material: the full material string of the most frequent cell,
     * ties resolve to the first maximum. Client mirror of the server parentage rule, so
     * predicted break feedback reads as the same block the server breaks as. Null when
     * the volume holds no named material.
     *
     * <p>Pure and dependency-free: safe to unit-test.</p>
     */
    public static String dominantMaterial(MicrovoxelVolume volume) {
        if (volume == null) return null;
        java.util.Map<String, Integer> counts = new java.util.HashMap<>();
        String best = null;
        int bestCount = 0;
        for (int cell = 0; cell < CELL_COUNT; cell++) {
            if (!volume.occupied(cell)) continue;
            String material = volume.material(cell);
            if (material == null || material.isEmpty()) continue;
            int count = counts.getOrDefault(material, 0) + 1;
            counts.put(material, count);
            if (count > bestCount) {
                bestCount = count;
                best = material;
            }
        }
        return best;
    }

    public boolean occupied(int cell) {
        return materialIndex(cell) != 0;
    }

    public boolean occupied(int x, int y, int z) {
        return inside(x, y, z) && cells[index(x, y, z)] != 0;
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
