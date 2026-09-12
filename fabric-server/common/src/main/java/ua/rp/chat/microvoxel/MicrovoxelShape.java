package ua.rp.chat.microvoxel;

import java.util.List;

/**
 * Bounded structural shape catalog ("geometry as data", tier S).
 *
 * <p>Every shape is a precomputed occupancy bitmask at {@link #SUB}³ sub-cells per block cell
 * (SUB = 16, i.e. 1/256 block precision). Because a shape is just a tiny voxel volume, the exact
 * same machinery already used everywhere else — greedy meshing, merged-cuboid collision and DDA
 * raycast — can consume a shaped cell with zero per-instance geometry math. That is what keeps the
 * new layer immutable, deterministic and headless-testable, unlike the mutable per-object box
 * graphs of a general voxel editor.</p>
 *
 * <p>The catalog is closed and shipped with the mod. {@link #faceFullMask()} records which of the
 * six cell-boundary faces the shape covers completely, so a neighbour can cull exactly as it would
 * against a full cube. {@link #solidFraction()} is the material cost fraction used by the economy.</p>
 *
 * <p>Pure: no Minecraft classes, fully unit-testable.</p>
 */
public final class MicrovoxelShape {
    /** Sub-cells per block cell along one axis. */
    public static final int SUB = 16;
    /** Sub-cells per block cell. */
    public static final int SUB_COUNT = SUB * SUB * SUB;
    /** Bitmask longs for one shape. */
    public static final int MASK_LONGS = (SUB_COUNT + 63) >>> 6;

    /** Face bits, in the same order as {@link #faceFullMask()}. */
    public static final int FACE_NEG_X = 1;
    public static final int FACE_POS_X = 1 << 1;
    public static final int FACE_NEG_Y = 1 << 2;
    public static final int FACE_POS_Y = 1 << 3;
    public static final int FACE_NEG_Z = 1 << 4;
    public static final int FACE_POS_Z = 1 << 5;

    /** The shape vocabulary. Ordinal is stable; never reorder, only append. */
    public enum Type {
        FULL,
        SLAB_BOTTOM,
        SLAB_TOP,
        PANEL_X,
        PANEL_Z,
        RAMP_S,
        RAMP_N,
        RAMP_E,
        RAMP_W,
        BEVEL_SW,
        BEVEL_SE,
        BEVEL_NW,
        BEVEL_NE,
        QUARTER_ROUND_SW,
        QUARTER_ROUND_SE,
        QUARTER_ROUND_NW,
        QUARTER_ROUND_NE
    }

    private static final MicrovoxelShape[] BY_ID;
    private static final MicrovoxelShape FULL;

    static {
        Type[] types = Type.values();
        BY_ID = new MicrovoxelShape[types.length];
        for (Type type : types) {
            BY_ID[type.ordinal()] = build(type);
        }
        FULL = BY_ID[0];
    }

    private final Type type;
    private final long[] mask;
    private final int solidCells;
    private final int faceFullMask;
    private final int minX;
    private final int minY;
    private final int minZ;
    private final int maxX;
    private final int maxY;
    private final int maxZ;

    private MicrovoxelShape(Type type) {
        this.type = type;
        this.mask = new long[MASK_LONGS];
        int solid = 0;
        int minX = SUB;
        int minY = SUB;
        int minZ = SUB;
        int maxX = -1;
        int maxY = -1;
        int maxZ = -1;
        for (int y = 0; y < SUB; y++) {
            for (int z = 0; z < SUB; z++) {
                for (int x = 0; x < SUB; x++) {
                    if (!occupies(type, x, y, z)) continue;
                    int cell = index(x, y, z);
                    mask[cell >>> 6] |= 1L << (cell & 63);
                    solid++;
                    minX = Math.min(minX, x);
                    minY = Math.min(minY, y);
                    minZ = Math.min(minZ, z);
                    maxX = Math.max(maxX, x);
                    maxY = Math.max(maxY, y);
                    maxZ = Math.max(maxZ, z);
                }
            }
        }
        this.solidCells = solid;
        if (solid == 0) {
            this.minX = this.minY = this.minZ = 0;
            this.maxX = this.maxY = this.maxZ = -1;
        } else {
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxY = maxY;
            this.maxZ = maxZ;
        }
        this.faceFullMask = computeFaceFullMask(this.mask);
    }

    /** Canonical occupancy predicate for one shape type, in sub-cell coordinates. */
    private static boolean occupies(Type type, int x, int y, int z) {
        int half = SUB / 2;
        int last = SUB - 1;
        return switch (type) {
            case FULL -> true;
            case SLAB_BOTTOM -> y < half;
            case SLAB_TOP -> y >= half;
            case PANEL_X -> x >= 7 && x <= 8;
            case PANEL_Z -> z >= 7 && z <= 8;
            case RAMP_S -> y <= z;
            case RAMP_N -> y <= last - z;
            case RAMP_E -> y <= x;
            case RAMP_W -> y <= last - x;
            case BEVEL_SW -> x + y <= last;
            case BEVEL_SE -> (last - x) + y <= last;
            case BEVEL_NW -> x + (last - y) <= last;
            case BEVEL_NE -> (last - x) + (last - y) <= last;
            case QUARTER_ROUND_SW -> x * x + y * y <= last * last;
            case QUARTER_ROUND_SE -> (last - x) * (last - x) + y * y <= last * last;
            case QUARTER_ROUND_NW -> x * x + (last - y) * (last - y) <= last * last;
            case QUARTER_ROUND_NE -> (last - x) * (last - x) + (last - y) * (last - y) <= last * last;
        };
    }

    private static MicrovoxelShape build(Type type) {
        return new MicrovoxelShape(type);
    }

    /** Number of registered shapes. */
    public static int count() {
        return BY_ID.length;
    }

    /** Shape by stable id, or throws for an out-of-range id (fail closed). */
    public static MicrovoxelShape byId(int id) {
        if (id < 0 || id >= BY_ID.length) {
            throw new IllegalArgumentException("Unknown microvoxel shape id " + id);
        }
        return BY_ID[id];
    }

    /** The default full-cube shape (id 0). */
    public static MicrovoxelShape full() {
        return FULL;
    }

    public static int index(int x, int y, int z) {
        return x | (z << 4) | (y << 8);
    }

    public Type type() {
        return type;
    }

    public int id() {
        return type.ordinal();
    }

    /** True when the given sub-cell (0..SUB-1 per axis) is occupied. Pure, bounds-safe. */
    public boolean occupied(int x, int y, int z) {
        if (x < 0 || x >= SUB || y < 0 || y >= SUB || z < 0 || z >= SUB) return false;
        int cell = index(x, y, z);
        return (mask[cell >>> 6] & (1L << (cell & 63))) != 0;
    }

    public boolean occupied(int subCell) {
        if (subCell < 0 || subCell >= SUB_COUNT) return false;
        return (mask[subCell >>> 6] & (1L << (subCell & 63))) != 0;
    }

    /** Raw bitmask copy (immutable snapshot). */
    public long[] maskCopy() {
        return mask.clone();
    }

    public int solidCells() {
        return solidCells;
    }

    /** 0..1 material fraction; used by the economy for curved/partial shapes. */
    public double solidFraction() {
        return solidCells / (double) SUB_COUNT;
    }

    public boolean isEmpty() {
        return solidCells == 0;
    }

    public int minX() {
        return minX;
    }

    public int minY() {
        return minY;
    }

    public int minZ() {
        return minZ;
    }

    public int maxX() {
        return maxX;
    }

    public int maxY() {
        return maxY;
    }

    public int maxZ() {
        return maxZ;
    }

    /** Bitmask of the six cell faces this shape covers completely (see FACE_* constants). */
    public int faceFullMask() {
        return faceFullMask;
    }

    public boolean coversFaceFully(int faceBit) {
        return (faceFullMask & faceBit) != 0;
    }

    /**
     * True when the shape is exactly the full cube (all sub-cells occupied). The base layer keeps
     * these cells out of the geometry section entirely, so a volume of full cubes costs zero extra
     * bytes and behaves byte-for-byte like before this layer existed.
     */
    public boolean isFullCube() {
        return solidCells == SUB_COUNT;
    }

    /**
     * Greedy mesh of this shape in sub-cell coordinates (0..{@link #SUB}), material index 1.
     *
     * <p>Because the shape is itself a 16³ occupancy mask, the shared {@link MicrovoxelGreedyMesher}
     * produces its geometry with no shape-specific code at all: it simply meshes the mask as a
     * one-material mini-volume with air outside. The result is immutable and cached per shape, so a
     * formed cell costs only a list walk on the render path.</p>
     */
    public List<MicrovoxelGreedyMesher.Face> greedyFaces() {
        List<MicrovoxelGreedyMesher.Face> cached = greedyFaces;
        if (cached == null) {
            synchronized (this) {
                cached = greedyFaces;
                if (cached == null) {
                    cached = buildGreedyFaces();
                    greedyFaces = cached;
                }
            }
        }
        return cached;
    }

    private volatile List<MicrovoxelGreedyMesher.Face> greedyFaces;
    private volatile MicrovoxelVolume volume;

    private List<MicrovoxelGreedyMesher.Face> buildGreedyFaces() {
        MicrovoxelVolume source = shapeVolume();
        return MicrovoxelGreedyMesher.build(source, source::materialAt);
    }

    /**
     * The shape as a one-material 16³ volume, cached. Every derived artifact (greedy mesh,
     * collision cuboids, raycast) reuses this and therefore the exact same voxel machinery as a
     * normal volume — no shape-specific geometry, collision or ray math anywhere.
     */
    public MicrovoxelVolume shapeVolume() {
        MicrovoxelVolume cached = volume;
        if (cached == null) {
            synchronized (this) {
                cached = volume;
                if (cached == null) {
                    byte[] cells = new byte[SUB_COUNT];
                    for (int cell = 0; cell < SUB_COUNT; cell++) {
                        if ((mask[cell >>> 6] & (1L << (cell & 63))) != 0) cells[cell] = 1;
                    }
                    cached = MicrovoxelVolume.restore(1, List.of("", "minecraft:stone"), cells);
                    volume = cached;
                }
            }
        }
        return cached;
    }

    /**
     * Merged collision cuboids in sub-cell coordinates (0..{@link #SUB}), cached. A shape is a
     * normal 16³ volume, so its collision reuses the shared merged-cuboid builder verbatim.
     */
    public List<MicrovoxelVolume.Cuboid> collisionCuboids() {
        return shapeVolume().collisionCuboids();
    }

    /**
     * Exact ray-vs-shape test inside one cell. Coordinates and direction are in cell-local units
     * (0..1), exactly like a unit block, and the result reuses the shared micro-cell DDA, so a
     * sloped or curved shape collides with rays at full 1/256 precision.
     */
    public MicrovoxelRaycaster.Hit raycast(double ox, double oy, double oz,
                                           double dx, double dy, double dz, double maxDistance) {
        return MicrovoxelRaycaster.cast(ox, oy, oz, dx, dy, dz, maxDistance,
                List.of(new MicrovoxelRaycaster.Entry(0, 0, 0, shapeVolume())));
    }

    private static int computeFaceFullMask(long[] mask) {
        int result = 0;
        if (faceFullyOccupied(mask, 0, true)) result |= FACE_NEG_X;
        if (faceFullyOccupied(mask, 0, false)) result |= FACE_POS_X;
        if (faceFullyOccupied(mask, 1, true)) result |= FACE_NEG_Y;
        if (faceFullyOccupied(mask, 1, false)) result |= FACE_POS_Y;
        if (faceFullyOccupied(mask, 2, true)) result |= FACE_NEG_Z;
        if (faceFullyOccupied(mask, 2, false)) result |= FACE_POS_Z;
        return result;
    }

    /** axis 0=x,1=y,2=z; atMin selects the boundary layer at coordinate 0 vs SUB-1. */
    private static boolean faceFullyOccupied(long[] mask, int axis, boolean atMin) {
        int layer = atMin ? 0 : SUB - 1;
        for (int a = 0; a < SUB; a++) {
            for (int b = 0; b < SUB; b++) {
                int x;
                int y;
                int z;
                if (axis == 0) {
                    x = layer;
                    y = a;
                    z = b;
                } else if (axis == 1) {
                    x = a;
                    y = layer;
                    z = b;
                } else {
                    x = a;
                    y = b;
                    z = layer;
                }
                int cell = index(x, y, z);
                if ((mask[cell >>> 6] & (1L << (cell & 63))) == 0) return false;
            }
        }
        return true;
    }
}
