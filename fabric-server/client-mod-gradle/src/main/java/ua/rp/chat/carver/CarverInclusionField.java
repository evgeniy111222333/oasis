package ua.rp.chat.carver;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * Procedural subsurface inclusions of a workpiece. Where the old model scattered independent
 * hint cells (a "grain of sand" look), this grows real linked structures, all deterministic
 * from the socket seed:
 * <ul>
 *   <li>{@code CRACK} — a self-intersecting random walk: a true hairline fracture.</li>
 *   <li>{@code VEIN} — branchy blob growth: a mineral seam with limbs.</li>
 *   <li>{@code CAVITY} — a spherical pocket.</li>
 * </ul>
 * Each structure carries a rarity tier that drives its colour and value. The inspection phase
 * only hints at them, and the sonar scan is what truly reveals their shape. Pure and
 * dependency-free, so the whole geology is unit-tested away from Minecraft.
 *
 * <p>Mirror contract: client-only helper, no server copy exists by design.</p>
 */
public final class CarverInclusionField {
    /** Inclusion families the carving rules and the hints both understand. */
    public enum Kind { VEIN, CRACK, CAVITY }

    /** Rarity of a structure: drives colour, size and value. */
    public enum Tier { COMMON, UNCOMMON, RARE }

    /**
     * One linked inclusion: its kind and tier, the exact voxels it occupies and a bounding
     * sphere used by the overlay to place hints and depth shafts.
     */
    public record Structure(int id, Kind kind, Tier tier, int[] cells,
                            double centerX, double centerY, double centerZ, double radius) {
    }

    private static final int[][] NEIGHBOURS = {
            {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1},
    };

    /** Per-cell chance a cell seeds an inclusion, by material class (used by the panel). */
    public static double baseChance(CarverWorkAnim.Material material) {
        return switch (material) {
            case METAL -> 0.011;
            case ICE, GLASS -> 0.014;
            case STONE -> 0.008;
            case WOOD -> 0.006;
            case CLOTH -> 0.003;
            default -> 0.004;
        };
    }

    /** Number of linked structures grown inside one workpiece, by material. */
    public static int structureCount(CarverWorkAnim.Material material) {
        return switch (material) {
            case METAL -> 6;
            case ICE, GLASS -> 7;
            case STONE -> 5;
            case WOOD -> 4;
            case CLOTH -> 2;
            default -> 3;
        };
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

    /**
     * Grows every linked inclusion of one workpiece. Deterministic: the same seed and material
     * always yield the same structures, in the same order, on every client. Pure.
     */
    public static List<Structure> structures(long seed, CarverWorkAnim.Material material) {
        int count = structureCount(material);
        ArrayList<Structure> out = new ArrayList<>(count);
        HashSet<Integer> takenSeeds = new HashSet<>();
        for (int id = 0; id < count; id++) {
            Rng rng = new Rng(mix(seed, id + 1));
            int seedCell = pickSeed(rng, takenSeeds);
            if (seedCell < 0) break;
            takenSeeds.add(seedCell);
            Kind kind = kindFor(material, rng.next());
            Tier tier = tierFor(rng);
            int[] cells = switch (kind) {
                case CRACK -> crack(rng, seedCell, tier);
                case VEIN -> vein(rng, seedCell, tier);
                case CAVITY -> cavity(rng, seedCell, tier);
            };
            if (cells.length == 0) continue;
            out.add(buildStructure(id, kind, tier, cells));
        }
        return List.copyOf(out);
    }

    private static Structure buildStructure(int id, Kind kind, Tier tier, int[] cells) {
        double sx = 0.0;
        double sy = 0.0;
        double sz = 0.0;
        for (int cell : cells) {
            sx += DraftMask.x(cell) + 0.5;
            sy += DraftMask.y(cell) + 0.5;
            sz += DraftMask.z(cell) + 0.5;
        }
        double cx = sx / cells.length;
        double cy = sy / cells.length;
        double cz = sz / cells.length;
        double radiusSq = 0.0;
        for (int cell : cells) {
            double dx = DraftMask.x(cell) + 0.5 - cx;
            double dy = DraftMask.y(cell) + 0.5 - cy;
            double dz = DraftMask.z(cell) + 0.5 - cz;
            radiusSq = Math.max(radiusSq, dx * dx + dy * dy + dz * dz);
        }
        return new Structure(id, kind, tier, cells, cx, cy, cz, Math.sqrt(radiusSq) + 0.5);
    }

    /** A hairline fracture: a self-intersecting random walk of axis steps. */
    private static int[] crack(Rng rng, int seedCell, Tier tier) {
        int length = switch (tier) {
            case COMMON -> 7;
            case UNCOMMON -> 14;
            case RARE -> 24;
        };
        HashSet<Integer> cells = new HashSet<>();
        ArrayList<Integer> order = new ArrayList<>();
        int x = DraftMask.x(seedCell);
        int y = DraftMask.y(seedCell);
        int z = DraftMask.z(seedCell);
        for (int step = 0; step < length; step++) {
            int cell = DraftMask.index(x, y, z);
            if (cells.add(cell)) order.add(cell);
            int[] dir = NEIGHBOURS[rng.nextInt(NEIGHBOURS.length)];
            x = advance(x, dir[0]);
            y = advance(y, dir[1]);
            z = advance(z, dir[2]);
        }
        return toArray(order);
    }

    /** A mineral seam: branchy blob growth with a per-step branching chance. */
    private static int[] vein(Rng rng, int seedCell, Tier tier) {
        int budget = switch (tier) {
            case COMMON -> 9;
            case UNCOMMON -> 18;
            case RARE -> 32;
        };
        double branch = tier == Tier.RARE ? 0.38 : tier == Tier.UNCOMMON ? 0.3 : 0.22;
        HashSet<Integer> cells = new HashSet<>();
        ArrayList<Integer> order = new ArrayList<>();
        ArrayList<Integer> frontier = new ArrayList<>();
        cells.add(seedCell);
        order.add(seedCell);
        frontier.add(seedCell);
        while (order.size() < budget && !frontier.isEmpty()) {
            int index = rng.nextInt(frontier.size());
            int base = frontier.get(index);
            int[] dir = NEIGHBOURS[rng.nextInt(NEIGHBOURS.length)];
            int next = DraftMask.index(
                    advance(DraftMask.x(base), dir[0]),
                    advance(DraftMask.y(base), dir[1]),
                    advance(DraftMask.z(base), dir[2]));
            if (next == base) {
                frontier.remove(index);
                continue;
            }
            if (cells.add(next)) {
                order.add(next);
                if (order.size() >= budget) break;
                frontier.add(next);
                if (!rng.chance(branch)) {
                    frontier.remove(index); // this limb moved on rather than branching
                }
            } else {
                frontier.remove(index); // dead end into material already grown
            }
        }
        return toArray(order);
    }

    /** A pocket: every cell inside a sphere around a jittered centre. */
    private static int[] cavity(Rng rng, int seedCell, Tier tier) {
        double radius = switch (tier) {
            case COMMON -> 1.2;
            case UNCOMMON -> 1.9;
            case RARE -> 2.8;
        };
        double cx = DraftMask.x(seedCell) + 0.5 + (rng.nextUnit() - 0.5);
        double cy = DraftMask.y(seedCell) + 0.5 + (rng.nextUnit() - 0.5);
        double cz = DraftMask.z(seedCell) + 0.5 + (rng.nextUnit() - 0.5);
        int span = (int) Math.ceil(radius) + 1;
        ArrayList<Integer> order = new ArrayList<>();
        for (int dx = -span; dx <= span; dx++) {
            for (int dy = -span; dy <= span; dy++) {
                for (int dz = -span; dz <= span; dz++) {
                    int x = DraftMask.x(seedCell) + dx;
                    int y = DraftMask.y(seedCell) + dy;
                    int z = DraftMask.z(seedCell) + dz;
                    if (x < 0 || x > 15 || y < 0 || y > 15 || z < 0 || z > 15) continue;
                    double ex = x + 0.5 - cx;
                    double ey = y + 0.5 - cy;
                    double ez = z + 0.5 - cz;
                    if (ex * ex + ey * ey + ez * ez <= radius * radius) {
                        order.add(DraftMask.index(x, y, z));
                    }
                }
            }
        }
        return toArray(order);
    }

    private static Tier tierFor(Rng rng) {
        double roll = rng.nextUnit();
        if (roll < 0.70) return Tier.COMMON;
        if (roll < 0.92) return Tier.UNCOMMON;
        return Tier.RARE;
    }

    /** A random cell away from already used seeds; -1 when the volume is saturated. */
    private static int pickSeed(Rng rng, HashSet<Integer> taken) {
        for (int attempt = 0; attempt < 24; attempt++) {
            int x = rng.nextInt(16);
            int y = rng.nextInt(16);
            int z = rng.nextInt(16);
            int cell = DraftMask.index(x, y, z);
            if (taken.add(cell)) {
                taken.remove(cell);
                return cell;
            }
        }
        return -1;
    }

    /** One axis step that reflects off the volume wall so consecutive cells stay adjacent. */
    private static int advance(int value, int delta) {
        int next = value + delta;
        if (next < 0) return value + 1;
        if (next > 15) return value - 1;
        return next;
    }

    private static int[] toArray(List<Integer> order) {
        int[] cells = new int[order.size()];
        for (int i = 0; i < cells.length; i++) cells[i] = order.get(i);
        return cells;
    }

    private static long mix(long seed, int salt) {
        long h = seed + 0x9E3779B97F4A7C15L * (salt + 1L);
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        h ^= (h >>> 31);
        return h;
    }

    /** Small deterministic splitmix RNG, one instance per structure. Pure. */
    private static final class Rng {
        private long state;

        Rng(long seed) {
            state = seed == 0L ? 0x9E3779B97F4A7C15L : seed;
        }

        long next() {
            state += 0x9E3779B97F4A7C15L;
            long z = state;
            z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
            z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
            return z ^ (z >>> 31);
        }

        int nextInt(int bound) {
            return (int) Math.floorMod(next(), (long) bound);
        }

        double nextUnit() {
            return (next() >>> 11) / (double) (1L << 53);
        }

        boolean chance(double probability) {
            return nextUnit() < probability;
        }
    }

    private CarverInclusionField() {
    }
}
