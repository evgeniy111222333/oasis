package ua.rp.chat.carver;

/**
 * Coherent grain geology of a workpiece. Where the old model hashed one direction per cell
 * (a "TV static" look), this builds a continuous field per material family:
 * <ul>
 *   <li>{@code LAYERS} — sedimentary bedding: wavy parallel planes of constant thickness, the
 *       layer index and its colour shifting where the beds fold.</li>
 *   <li>{@code FIBERS} — a trunk: concentric growth rings around a seeded pith, fibres running
 *       up the grain.</li>
 *   <li>{@code CRYSTALS} — a metal ingot: Voronoi crystal domains whose shared boundaries are
 *       the cleavage planes.</li>
 *   <li>{@code WOVEN} — cloth: two-cell warp and weft bands alternating on both axes.</li>
 *   <li>{@code AMORPHOUS} — ice and glass: no preferred axis, no grain drawn.</li>
 * </ul>
 * The whole field is deterministic from the socket seed, so every client and every session sees
 * the same geology. Pure and dependency-free, so the geology is unit-tested away from Minecraft.
 *
 * <p>Mirror contract: duplicated verbatim in the paired server module. Keep both copies
 * byte-identical; {@code verifyCarverParity} fails the build on divergence.</p>
 */
public final class CarverGrainField {
    /** Grain families the carving rules and the inspection overlay both understand. */
    public enum GrainType { LAYERS, RINGS, FIBERS, CRYSTALS, AMORPHOUS, WOVEN }

    /** Quantized grain travel, one of the lattice axes; the sign is a per-domain flip. */
    public record Direction(int x, int y, int z) {
    }

    /** Bedding thickness in cells for sedimentary stone. */
    private static final double LAYER_THICKNESS = 2.6;
    /** Bedding fold amplitude in cells. */
    private static final double LAYER_WARP = 2.4;
    /** Growth-ring width in cells for timber. */
    private static final double RING_WIDTH = 1.6;
    /** Crystal seed count for a forged ingot. */
    private static final int CRYSTAL_SEEDS = 18;

    /** Grain family of a material class. */
    public static GrainType typeFor(CarverWorkAnim.Material material) {
        return switch (material) {
            case STONE -> GrainType.LAYERS;
            case WOOD -> GrainType.FIBERS;
            case METAL -> GrainType.CRYSTALS;
            case CLOTH -> GrainType.WOVEN;
            case ICE, GLASS -> GrainType.AMORPHOUS;
            default -> GrainType.AMORPHOUS;
        };
    }

    /** True when this family draws any grain at all. */
    public static boolean hasGrain(GrainType type) {
        return type != GrainType.AMORPHOUS;
    }

    /**
     * Deterministic geology seed of a socket: raw block coordinates in, a stable 64-bit seed out.
     * Mirrored verbatim so the client render and the server evaluation read the same geology.
     * Pure.
     */
    public static long seedFor(int x, int y, int z) {
        long h = x * 0x9E3779B97F4A7C15L
                + y * 0xC2B2AE3D27D4EB4FL
                + z * 0x165667B19E3779F9L;
        h ^= (h >>> 29);
        h *= 0xBF58476D1CE4E5B9L;
        h ^= (h >>> 32);
        return h;
    }

    /**
     * Projection of the grain travel onto a carving face: the in-plane direction a cut should
     * follow to ride the grain rather than fight it. Degenerate projections fall back to the
     * face's first in-plane axis. Pure.
     */
    public static Direction projected(Direction grain, CarverFaceSlicer.Face face) {
        int gx = grain.x();
        int gy = grain.y();
        int gz = grain.z();
        return switch (face) {
            case UP, DOWN -> new Direction(gx == 0 ? 1 : gx, 0, gz);
            case NORTH, SOUTH -> new Direction(gx == 0 ? 0 : gx, gy == 0 ? 1 : gy, 0);
            case WEST, EAST -> new Direction(0, gy == 0 ? 1 : gy, gz == 0 ? 1 : gz);
        };
    }

    /**
     * A built grain field of one workpiece: domain (layer / ring / crystal / weave cell), the
     * grain travel inside it, the grain strength per voxel and how close each voxel sits to a
     * domain boundary (drives the baked edge shading). Arrays are computed once and read every
     * frame by the overlay. Immutable and safe to share.
     */
    public static final class Field {
        private final GrainType type;
        private final int[] domains = new int[DraftMask.CELL_COUNT];
        private final byte[] axes = new byte[DraftMask.CELL_COUNT];
        private final byte[] signs = new byte[DraftMask.CELL_COUNT];
        private final byte[] strengths = new byte[DraftMask.CELL_COUNT];
        private final byte[] boundary = new byte[DraftMask.CELL_COUNT];

        private Field(GrainType type) {
            this.type = type;
            java.util.Arrays.fill(axes, (byte) -1);
        }

        public GrainType type() {
            return type;
        }

        public boolean hasGrain() {
            return type != GrainType.AMORPHOUS;
        }

        /** Layer / ring / crystal / weave index of a cell (drives the overlay colour). */
        public int domain(int cell) {
            return domains[cell];
        }

        /** Grain strength 0..1 of a cell. */
        public double strength(int cell) {
            return (strengths[cell] & 0xFF) / 255.0;
        }

        /** Distance to the nearest domain boundary, 0 deep inside a grain, 1 on the seam. */
        public double boundaryness(int cell) {
            return (boundary[cell] & 0xFF) / 255.0;
        }

        /** Dominant lattice axis of the grain travel (0=X, 1=Y, 2=Z, -1 grainless). */
        public int axis(int cell) {
            return axes[cell];
        }

        /** Grain travel at a cell, or a vertical fallback for grainless cells. */
        public Direction direction(int cell) {
            int axis = axes[cell];
            if (axis < 0) return new Direction(0, 1, 0);
            int sign = signs[cell] == 0 ? 1 : -1;
            return switch (axis) {
                case 0 -> new Direction(sign, 0, 0);
                case 1 -> new Direction(0, sign, 0);
                default -> new Direction(0, 0, sign);
            };
        }
    }

    /** Builds the coherent grain field for one workpiece seed and material family. Pure. */
    public static Field build(long seed, GrainType type) {
        Field field = new Field(type);
        switch (type) {
            case LAYERS -> buildLayers(field, seed);
            case RINGS, FIBERS -> buildTrunk(field, seed);
            case CRYSTALS -> buildCrystals(field, seed);
            case WOVEN -> buildWoven(field, seed);
            case AMORPHOUS -> {
                // No structure to compute: strength stays 0, the overlay draws nothing.
            }
        }
        computeBoundary(field);
        return field;
    }

    /**
     * Marks every cell that touches a different domain as a seam. A one-cell ridge along the
     * bedding planes / growth rings / crystal faces, reused by the baked edge shading and by the
     * server evaluation as the clean-cut line. Pure.
     */
    private static void computeBoundary(Field field) {
        if (!field.hasGrain()) return;
        for (int cell = 0; cell < DraftMask.CELL_COUNT; cell++) {
            int x = DraftMask.x(cell);
            int y = DraftMask.y(cell);
            int z = DraftMask.z(cell);
            int domain = field.domains[cell];
            int seams = 0;
            int neighbours = 0;
            if (x > 0) { neighbours++; if (field.domains[DraftMask.index(x - 1, y, z)] != domain) seams++; }
            if (x < 15) { neighbours++; if (field.domains[DraftMask.index(x + 1, y, z)] != domain) seams++; }
            if (y > 0) { neighbours++; if (field.domains[DraftMask.index(x, y - 1, z)] != domain) seams++; }
            if (y < 15) { neighbours++; if (field.domains[DraftMask.index(x, y + 1, z)] != domain) seams++; }
            if (z > 0) { neighbours++; if (field.domains[DraftMask.index(x, y, z - 1)] != domain) seams++; }
            if (z < 15) { neighbours++; if (field.domains[DraftMask.index(x, y, z + 1)] != domain) seams++; }
            field.boundary[cell] = toByte(neighbours == 0 ? 0.0 : seams / (double) neighbours);
        }
    }

    /** Wavy parallel bedding planes: layer index from warped depth along Y. */
    private static void buildLayers(Field field, long seed) {
        for (int cell = 0; cell < DraftMask.CELL_COUNT; cell++) {
            int x = DraftMask.x(cell);
            int y = DraftMask.y(cell);
            int z = DraftMask.z(cell);
            double warp = (CarverNoise.fbm3(seed ^ 0x1234ABCDL, x * 0.16, 0.0, z * 0.16, 2) - 0.5)
                    * LAYER_WARP;
            double depth = y + warp;
            int layer = (int) Math.floor(depth / LAYER_THICKNESS);
            field.domains[cell] = layer;
            field.axes[cell] = 0; // bedding runs horizontally
            field.signs[cell] = (byte) ((int) (hashUnit(seed, cell, 7) * 2.0) & 1);
            double band = Math.abs((depth / LAYER_THICKNESS) - layer - 0.5) * 2.0; // 0 centre, 1 edge
            double noise = CarverNoise.value3(seed ^ 0x55AAL, x * 0.35, y * 0.35, z * 0.35);
            double strength = 0.45 + 0.35 * (1.0 - band) + 0.20 * noise;
            field.strengths[cell] = toByte(strength);
        }
    }

    /** Trunk: growth rings around a seeded pith, fibres vertical. */
    private static void buildTrunk(Field field, long seed) {
        double pithX = 5.0 + hashUnit(seed, 1, 11) * 6.0;
        double pithZ = 5.0 + hashUnit(seed, 2, 13) * 6.0;
        for (int cell = 0; cell < DraftMask.CELL_COUNT; cell++) {
            int x = DraftMask.x(cell);
            int y = DraftMask.y(cell);
            int z = DraftMask.z(cell);
            double wx = (CarverNoise.value3(seed ^ 0xBEEFL, x * 0.2, y * 0.2, z * 0.2) - 0.5) * 0.8;
            double radius = Math.sqrt((x - pithX) * (x - pithX) + (z - pithZ) * (z - pithZ)) + wx;
            int ring = (int) Math.floor(radius / RING_WIDTH);
            field.domains[cell] = ring;
            field.axes[cell] = 1; // fibres climb the trunk
            field.signs[cell] = 0;
            double frac = radius / RING_WIDTH - ring;
            double band = Math.abs(frac - 0.5) * 2.0;
            field.strengths[cell] = toByte(0.55 + 0.45 * (1.0 - band));
        }
    }

    /** Voronoi crystal domains: nearest seeded point wins, boundaries are the cleavage planes. */
    private static void buildCrystals(Field field, long seed) {
        double[] sx = new double[CRYSTAL_SEEDS];
        double[] sy = new double[CRYSTAL_SEEDS];
        double[] sz = new double[CRYSTAL_SEEDS];
        for (int i = 0; i < CRYSTAL_SEEDS; i++) {
            sx[i] = hashUnit(seed, i, 101) * 16.0;
            sy[i] = hashUnit(seed, i, 211) * 16.0;
            sz[i] = hashUnit(seed, i, 307) * 16.0;
        }
        for (int cell = 0; cell < DraftMask.CELL_COUNT; cell++) {
            double x = DraftMask.x(cell) + 0.5;
            double y = DraftMask.y(cell) + 0.5;
            double z = DraftMask.z(cell) + 0.5;
            int nearest = 0;
            double d1 = Double.MAX_VALUE;
            double d2 = Double.MAX_VALUE;
            for (int i = 0; i < CRYSTAL_SEEDS; i++) {
                double dx = x - sx[i];
                double dy = y - sy[i];
                double dz = z - sz[i];
                double d = dx * dx + dy * dy + dz * dz;
                if (d < d1) {
                    d2 = d1;
                    d1 = d;
                    nearest = i;
                } else if (d < d2) {
                    d2 = d;
                }
            }
            field.domains[cell] = nearest;
            field.axes[cell] = (byte) ((int) (hashUnit(seed, nearest, 401) * 3.0) % 3);
            field.signs[cell] = (byte) ((int) (hashUnit(seed, nearest, 409) * 2.0) & 1);
            // Closeness to a cleavage boundary: 0 deep inside a domain, 1 on the plane.
            double gap = Math.sqrt(d2) - Math.sqrt(d1);
            double edge = 1.0 - Math.min(1.0, gap / 2.5);
            field.strengths[cell] = toByte(0.5 + 0.5 * edge);
        }
    }

    /** Cloth: two-cell warp and weft bands alternating on both axes. */
    private static void buildWoven(Field field, long seed) {
        for (int cell = 0; cell < DraftMask.CELL_COUNT; cell++) {
            int x = DraftMask.x(cell);
            int z = DraftMask.z(cell);
            int warp = (x >> 1) & 1;
            int weft = (z >> 1) & 1;
            field.domains[cell] = warp | (weft << 1);
            field.axes[cell] = (byte) (warp == 0 ? 0 : 2);
            field.signs[cell] = 0;
            field.strengths[cell] = toByte(0.7 + 0.3 * CarverNoise.value3(
                    seed ^ 0xC107AL, x * 0.5, 0.0, z * 0.5));
        }
    }

    /** Deterministic unit value for one (seed, cell, salt) triple. Pure. */
    private static double hashUnit(long seed, int cell, int salt) {
        long h = seed + 0x9E3779B97F4A7C15L * (cell + 1L) + 0xBF58476D1CE4E5B9L * (salt + 1L);
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        h ^= (h >>> 31);
        return (h >>> 11) / (double) (1L << 53);
    }

    private static byte toByte(double value) {
        int clamped = (int) Math.round(Math.max(0.0, Math.min(1.0, value)) * 255.0);
        return (byte) clamped;
    }

    private CarverGrainField() {
    }
}
