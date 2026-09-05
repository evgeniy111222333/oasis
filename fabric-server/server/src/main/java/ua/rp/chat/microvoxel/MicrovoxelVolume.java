package ua.rp.chat.microvoxel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

public final class MicrovoxelVolume {
    public static final int RESOLUTION = 16;
    public static final int CELL_COUNT = RESOLUTION * RESOLUTION * RESOLUTION;
    public static final int MAX_PALETTE = 32;
    public static final int SIMPLE_COLLISION_CUBOID_LIMIT = 64;

    private int revision;
    private final List<String> palette;
    private final byte[] cells;
    private transient volatile List<Cuboid> collisionCuboids;
    private transient volatile CollisionPlan collisionPlan;

    private MicrovoxelVolume(int revision, List<String> palette, byte[] cells) {
        this.revision = Math.max(1, revision);
        this.palette = new ArrayList<>(palette);
        this.cells = cells.clone();
        validate();
    }

    public static MicrovoxelVolume full(String blockData) {
        if (blockData == null || blockData.isBlank()) {
            throw new IllegalArgumentException("Block data cannot be empty");
        }
        byte[] cells = new byte[CELL_COUNT];
        Arrays.fill(cells, (byte) 1);
        return new MicrovoxelVolume(1, List.of("", blockData), cells);
    }

    public static MicrovoxelVolume empty() {
        return new MicrovoxelVolume(1, List.of(""), new byte[CELL_COUNT]);
    }

    public static MicrovoxelVolume restore(int revision, List<String> palette, byte[] cells) {
        return new MicrovoxelVolume(revision, palette, cells);
    }

    public MicrovoxelVolume copy() {
        return new MicrovoxelVolume(revision, palette, cells);
    }

    public int revision() {
        return revision;
    }

    public List<String> palette() {
        return Collections.unmodifiableList(palette);
    }

    public byte[] cellsCopy() {
        return cells.clone();
    }

    public int materialIndex(int cell) {
        requireCell(cell);
        return Byte.toUnsignedInt(cells[cell]);
    }

    public String material(int cell) {
        return palette.get(materialIndex(cell));
    }

    public boolean occupied(int cell) {
        return materialIndex(cell) != 0;
    }

    public boolean occupied(int x, int y, int z) {
        return inside(x, y, z) && cells[index(x, y, z)] != 0;
    }

    public boolean remove(int cell) {
        requireCell(cell);
        if (cells[cell] == 0) {
            return false;
        }
        cells[cell] = 0;
        changed();
        return true;
    }

    public boolean put(int cell, String blockData) {
        requireCell(cell);
        if (blockData == null || blockData.isBlank()) {
            throw new IllegalArgumentException("Block data cannot be empty");
        }
        if (cells[cell] != 0) {
            return false;
        }
        int paletteIndex = palette.indexOf(blockData);
        if (paletteIndex < 0) {
            if (palette.size() >= MAX_PALETTE) {
                throw new IllegalStateException("Microvoxel palette limit reached");
            }
            palette.add(blockData);
            paletteIndex = palette.size() - 1;
        }
        cells[cell] = (byte) paletteIndex;
        changed();
        return true;
    }

    public void update(int cell, String blockData) {
        requireCell(cell);
        if (blockData == null || blockData.isBlank()) {
            cells[cell] = 0;
        } else {
            int paletteIndex = palette.indexOf(blockData);
            if (paletteIndex < 0) {
                if (palette.size() >= MAX_PALETTE) {
                    throw new IllegalStateException("Microvoxel palette limit reached");
                }
                palette.add(blockData);
                paletteIndex = palette.size() - 1;
            }
            cells[cell] = (byte) paletteIndex;
        }
        changed();
    }

    public void setRevision(int revision) {
        this.revision = revision;
    }

    /**
     * Removes palette entries no cell references and remaps indices without changing geometry or
     * revision. Callers must send a full upsert before any later delta because indices may move.
     */
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

    /**
     * Marks every palette index referenced by at least one cell. Reads the live array without
     * copying: marker-state derivation and palette compaction run in hot edit paths where a
     * 4096-byte copy per call is pure waste. The caller must hold no lock expectations; the
     * result is a point-in-time view of a server-thread-owned volume.
     */
    public void collectUsedMaterials(boolean[] used) {
        for (byte cell : cells) {
            int index = Byte.toUnsignedInt(cell);
            if (index < used.length) used[index] = true;
        }
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
     * Dense-volume fallback for light sealing. The light engine only understands whole-block
     * opacity, so a wall with a carved detail can never occlude per-face; volumes this dense
     * (vanilla slabs and stairs block skylight the same way) report as light-sealed instead
     * of flipping the entire block transparent over one missing voxel.
     */
    public static final double LIGHT_SEAL_MIN_OPAQUE_FRACTION = 0.5;
    /**
     * Minimum contiguous opaque run (in cells) for the axial rule below. A 4-cell plate reads
     * as a wall; thinner detail stays transparent. Deliberately below the 8-cell vanilla slab
     * precedent so common 4-6 voxel decorative walls seal.
     */
    public static final int LIGHT_SEAL_MIN_AXIAL_RUN = 4;

    /**
     * Whether the light engine should treat this position as the parent material: all six
     * faces sealed opaque, or at least half the cells opaque, or a solid plate across any
     * axis (thin walls that span the block). Sparse lattices stay transparent. Pure and
     * unit-tested; the block-granularity approximation is documented, not hidden.
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
     * Axial plate rule: true when every 16x16 column along ANY axis contains a contiguous
     * opaque run of at least {@code minRun} cells. Coverage requires ALL columns of the
     * axis (a single empty column fails it), so hollow structures stay transparent: their
     * empty columns break every axis, while a thin solid wall spanning the block seals its
     * normal axis. One pass per axis, early exit, no allocation.
     */
    public boolean axialRunCovered(boolean[] opaquePalette, int minRun) {
        return axisRunCovered(opaquePalette, minRun, 0)
                || axisRunCovered(opaquePalette, minRun, 1)
                || axisRunCovered(opaquePalette, minRun, 2);
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
     * Exposed emissive cells needed for full glow. A 4x4 emissive patch reads as a full
     * light source; a lone cell glows dimly (see {@link #emissionLevel}).
     */
    public static final int FULL_GLOW_EXPOSED_CELLS = 16;

    /**
     * Bitmask of boundary faces whose every cell is occupied by an opaque material. The
     * opacity predicate receives palette strings (never parsed here), keeping this pure and
     * unit-testable; callers pass real blockstate checks. A fully sealed volume ({@code 63})
     * blocks skylight and block light exactly like its solid vanilla counterpart.
     */
    public int sealedOpaqueFaces(java.util.function.Predicate<String> opaque) {
        boolean[] opaquePalette = new boolean[palette.size()];
        for (int index = 1; index < palette.size(); index++) {
            opaquePalette[index] = opaque.test(palette.get(index));
        }
        return sealedFaces(opaquePalette);
    }

    /**
     * Counts emissive cells that can actually shine: occupied, with a positive emission value,
     * and exposed to air through an empty 6-neighbour or the volume boundary. A torch bricked
     * inside solid stone contributes nothing, fixing the "one torch lights the whole block"
     * artifact at its root.
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
            // Empty in-bounds neighbours first (occupied() is false outside bounds, hence
            // the explicit inside() guard), then the volume boundary facing outside air.
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
     * Fractional block-light level for the whole 1x1x1 position. Scales the strongest exposed
     * emission by coverage: a lone torch cell glows dimly, a 4x4 patch reads as a full source,
     * buried sources stay dark. Returns 0 when nothing exposed shines.
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
     * Counts occupied cells. Used by harvest rules, empty-volume dematerialization
     * and snapshot budgeting; linear scan is fine at 4096 cells.
     */
    public int occupiedCount() {
        int count = 0;
        for (byte cell : cells) {
            if (cell != 0) count++;
        }
        return count;
    }

    public List<Cuboid> collisionCuboids() {
        List<Cuboid> existing = collisionCuboids;
        if (existing != null) return existing;
        CollisionPlan plan = collisionPlan;
        if (plan != null && plan.backend() == CollisionBackend.CUBOIDS) return plan.cuboids();
        List<Cuboid> result = buildCuboids(Integer.MAX_VALUE);
        collisionCuboids = result;
        return result;
    }

    /**
     * Compiles the volume to the cheapest exact collision representation. Simple shapes retain
     * merged AABBs; fragmented shapes use three compact sets of 16-bit occupancy lines.
     */
    public CollisionPlan collisionPlan() {
        CollisionPlan existing = collisionPlan;
        if (existing != null) return existing;

        short[] xLines = new short[RESOLUTION * RESOLUTION];
        short[] yLines = new short[RESOLUTION * RESOLUTION];
        short[] zLines = new short[RESOLUTION * RESOLUTION];
        for (int y = 0; y < RESOLUTION; y++) {
            for (int z = 0; z < RESOLUTION; z++) {
                for (int x = 0; x < RESOLUTION; x++) {
                    if (cells[index(x, y, z)] == 0) continue;
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
        if (backend == CollisionBackend.CUBOIDS) collisionCuboids = retained;
        collisionPlan = compiled;
        return compiled;
    }

    private List<Cuboid> buildCuboids(int stopAfter) {
        boolean[] used = new boolean[CELL_COUNT];
        List<Cuboid> result = new ArrayList<>();
        for (int y = 0; y < RESOLUTION; y++) {
            for (int z = 0; z < RESOLUTION; z++) {
                for (int x = 0; x < RESOLUTION; x++) {
                    int start = index(x, y, z);
                    if (cells[start] == 0 || used[start]) continue;
                    int maxX = x + 1;
                    while (maxX < RESOLUTION && canExtendX(used, maxX, y, z)) maxX++;
                    int maxZ = z + 1;
                    while (maxZ < RESOLUTION && canExtendZ(used, x, maxX, y, maxZ)) maxZ++;
                    int maxY = y + 1;
                    while (maxY < RESOLUTION && canExtendY(used, x, maxX, z, maxZ, maxY)) maxY++;
                    for (int cy = y; cy < maxY; cy++) {
                        for (int cz = z; cz < maxZ; cz++) {
                            for (int cx = x; cx < maxX; cx++) used[index(cx, cy, cz)] = true;
                        }
                    }
                    result.add(new Cuboid(x, y, z, maxX, maxY, maxZ));
                    if (result.size() >= stopAfter) return List.copyOf(result);
                }
            }
        }
        return List.copyOf(result);
    }

    private boolean canExtendX(boolean[] used, int x, int y, int z) {
        int cell = index(x, y, z);
        return cells[cell] != 0 && !used[cell];
    }

    private boolean canExtendZ(boolean[] used, int minX, int maxX, int y, int z) {
        for (int x = minX; x < maxX; x++) {
            int cell = index(x, y, z);
            if (cells[cell] == 0 || used[cell]) return false;
        }
        return true;
    }

    private boolean canExtendY(boolean[] used, int minX, int maxX, int minZ, int maxZ, int y) {
        for (int z = minZ; z < maxZ; z++) {
            for (int x = minX; x < maxX; x++) {
                int cell = index(x, y, z);
                if (cells[cell] == 0 || used[cell]) return false;
            }
        }
        return true;
    }

    private void changed() {
        revision = MicrovoxelRevision.next(revision);
        collisionCuboids = null;
        collisionPlan = null;
    }

    private void validate() {
        if (cells.length != CELL_COUNT || palette.isEmpty() || !palette.get(0).isEmpty()
                || palette.size() > MAX_PALETTE) {
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
            if (Byte.toUnsignedInt(cell) >= palette.size()) {
                throw new IllegalArgumentException("Cell references missing palette entry");
            }
        }
    }

    public static int index(int x, int y, int z) {
        if (!inside(x, y, z)) throw new IndexOutOfBoundsException("Microvoxel coordinate outside 16x16x16 volume");
        return x | (z << 4) | (y << 8);
    }

    public static int x(int cell) {
        requireCell(cell);
        return cell & 15;
    }

    public static int z(int cell) {
        requireCell(cell);
        return (cell >>> 4) & 15;
    }

    public static int y(int cell) {
        requireCell(cell);
        return (cell >>> 8) & 15;
    }

    public static boolean inside(int x, int y, int z) {
        return (x | y | z) >= 0 && x < RESOLUTION && y < RESOLUTION && z < RESOLUTION;
    }

    private static void requireCell(int cell) {
        if (cell < 0 || cell >= CELL_COUNT) throw new IndexOutOfBoundsException("Invalid microvoxel cell " + cell);
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

        public CollisionBackend backend() {
            return backend;
        }

        public List<Cuboid> cuboids() {
            return cuboids;
        }

        public int xMask(int y, int z) {
            return Short.toUnsignedInt(xLines[(y << 4) | z]);
        }

        public int yMask(int z, int x) {
            return Short.toUnsignedInt(yLines[(z << 4) | x]);
        }

        public int zMask(int y, int x) {
            return Short.toUnsignedInt(zLines[(y << 4) | x]);
        }
    }
}
