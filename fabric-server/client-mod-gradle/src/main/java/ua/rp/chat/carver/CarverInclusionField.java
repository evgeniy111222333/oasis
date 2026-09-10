package ua.rp.chat.carver;

/**
 * Procedural subsurface inclusions of a workpiece: the rare veins, cracks and cavities the
 * inspection phase only hints at, and that carving must respect. Deterministic from the
 * socket position, so the same block hides the same crystal forever and a player can learn
 * a quarry's secrets. Pure and dependency-free.
 *
 * <p>Mirror contract: client-only helper, no server copy exists by design.</p>
 */
public final class CarverInclusionField {
    /** Inclusion families the carving rules and the hints both understand. */
    public enum Kind { VEIN, CRACK, CAVITY }

    /** One hinted inclusion cell: which voxel, and what hides there. */
    public record Hint(int cell, Kind kind) {
    }

    /** Per-cell chance a cell seeds an inclusion, by material class. */
    public static double baseChance(CarverWorkAnim.Material material) {
        return switch (material) {
            case METAL -> 0.011;    // forge flaws and crystal seams
            case ICE, GLASS -> 0.014; // hairline fractures everywhere
            case STONE -> 0.008;    // ore veins
            case WOOD -> 0.006;     // knots and worm pockets
            case CLOTH -> 0.003;
            default -> 0.004;
        };
    }

    /**
     * Sparse hint cells for one volume, capped. A cell becomes a hint when its hash falls
     * under the material chance; the kind is drawn from the same hash. Pure and bounded, so
     * the overlay can never be flooded by an unlucky seed.
     */
    public static java.util.List<Hint> hints(long seed, CarverWorkAnim.Material material, int cap) {
        int limit = Math.max(0, Math.min(cap, DraftMask.CELL_COUNT));
        if (limit == 0) return java.util.List.of();
        double chance = baseChance(material);
        java.util.ArrayList<Hint> out = new java.util.ArrayList<>();
        for (int cell = 0; cell < DraftMask.CELL_COUNT && out.size() < limit; cell++) {
            long h = mix(seed, cell);
            double unit = ((h >>> 11) & ((1L << 53) - 1)) / (double) (1L << 53);
            if (unit < chance) {
                out.add(new Hint(cell, kindFor(material, h)));
            }
        }
        return java.util.List.copyOf(out);
    }

    /** Kind mix by material: metals crack, stone hides veins, wood and cloth pouch out. Pure. */
    public static Kind kindFor(CarverWorkAnim.Material material, long hash) {
        int roll = (int) Math.floorMod(hash >>> 24, 100L);
        return switch (material) {
            case METAL -> roll < 60 ? Kind.CRACK : roll < 90 ? Kind.VEIN : Kind.CAVITY;
            case ICE, GLASS -> roll < 70 ? Kind.CRACK : Kind.CAVITY;
            case WOOD -> roll < 55 ? Kind.CAVITY : Kind.CRACK;
            case CLOTH -> roll < 80 ? Kind.CAVITY : Kind.CRACK;
            default -> roll < 55 ? Kind.VEIN : roll < 85 ? Kind.CRACK : Kind.CAVITY;
        };
    }

    private static long mix(long seed, int cell) {
        long h = seed + 0x9E3779B97F4A7C15L * (cell + 1L);
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        h ^= (h >>> 31);
        return h;
    }

    private CarverInclusionField() {
    }
}
