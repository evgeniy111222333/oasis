package ua.rp.chat.carver;

/**
 * Procedural grain field of a workpiece: the direction the material wants to be cut, per
 * voxel. Deterministic from the socket position and the material class, so the same block
 * always shows the same grain and a player can learn to read a quarry, a forest or a forge.
 * Pure and dependency-free: the whole geology is unit-tested away from Minecraft.
 *
 * <p>Mirror contract: client-only helper, no server copy exists by design.</p>
 */
public final class CarverGrainField {
    /** Grain families the carving rules and the inspection overlay both understand. */
    public enum GrainType { LAYERS, RINGS, FIBERS, CRYSTALS, AMORPHOUS, WOVEN }

    /** Quantized grain travel, one of the lattice axes; the sign is a per-cell flip. */
    public record Direction(int x, int y, int z) {
    }

    /** Grain family of a material class. */
    public static GrainType typeFor(CarverWorkAnim.Material material) {
        return switch (material) {
            case STONE -> GrainType.LAYERS;      // sedimentary bedding runs horizontally
            case WOOD -> GrainType.FIBERS;       // timber splits along the trunk
            case METAL -> GrainType.CRYSTALS;    // forged bars cleave on crystal planes
            case CLOTH -> GrainType.WOVEN;       // warp and weft
            case ICE, GLASS -> GrainType.AMORPHOUS; // conchoidal, no preferred axis
            default -> GrainType.AMORPHOUS;
        };
    }

    /**
     * Grain travel at one cell. The family picks the plane, the hash picks the axis and the
     * per-cell flip, so a whole face reads as layered stone, standing timber or a crystal web.
     * Pure.
     */
    public static Direction direction(GrainType type, long seed, int cell) {
        long h = mix(seed, cell);
        return switch (type) {
            case LAYERS -> new Direction(1, 0, 0);
            case FIBERS -> new Direction(0, 1, 0);
            case CRYSTALS -> axis(floorMod(h, 3));
            case WOVEN -> (h & 1L) == 0L ? new Direction(1, 0, 0) : new Direction(0, 0, 1);
            case AMORPHOUS -> axis(floorMod(h >>> 8, 3));
            default -> new Direction(0, 1, 0);
        };
    }

    /** Grain strength 0..1 on one cell: how pronounced the grain is. Pure. */
    public static double strength(long seed, int cell) {
        long h = mix(seed ^ 0xA5A5A5A5A5A5A5A5L, cell);
        return ((h >>> 12) & 0xFFFFL) / 65535.0;
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

    private static Direction axis(int a) {
        return switch (a) {
            case 0 -> new Direction(1, 0, 0);
            case 1 -> new Direction(0, 1, 0);
            default -> new Direction(0, 0, 1);
        };
    }

    private static int floorMod(long value, int modulus) {
        return (int) Math.floorMod(value, (long) modulus);
    }

    private static long mix(long seed, int cell) {
        long h = seed + 0x9E3779B97F4A7C15L * (cell + 1L);
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        h ^= (h >>> 31);
        return h;
    }

    private CarverGrainField() {
    }
}
