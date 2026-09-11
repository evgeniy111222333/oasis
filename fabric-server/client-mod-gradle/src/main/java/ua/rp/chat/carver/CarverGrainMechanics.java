package ua.rp.chat.carver;

/**
 * Pure grain/cut mechanics: how a stroke that travels in one lattice direction interacts with
 * the grain travel of the cell it cuts. Riding the grain gives a clean, cheap, cool cut; cutting
 * across it fights the material — more hardness, more heat, more wear and a real risk of tearing
 * out the neighbouring cells. The server mirrors these numbers so evaluation and anti-cheat agree
 * with the client feel.
 *
 * <p>Pure and dependency-free: every curve is snapshot-tested without Minecraft.</p>
 *
 * <p>Mirror contract: duplicated verbatim in the paired server module. Keep both copies
 * byte-identical; {@code verifyCarverParity} fails the build on divergence.</p>
 */
public final class CarverGrainMechanics {
    /** How a cut runs relative to the grain. */
    public enum Alignment { ALONG, NEUTRAL, CROSS }

    /** Dot-product threshold above which a cut counts as riding the grain. */
    private static final double ALONG_THRESHOLD = 0.5;
    /** Dot-product threshold below which a cut counts as cutting across the grain. */
    private static final double CROSS_THRESHOLD = -0.5;

    private CarverGrainMechanics() {
    }

    /** Quantizes an arbitrary tool path into the nearest lattice direction (never zero). */
    public static CarverGrainField.Direction quantize(double dx, double dy, double dz) {
        double ax = Math.abs(dx);
        double ay = Math.abs(dy);
        double az = Math.abs(dz);
        if (ax >= ay && ax >= az) return new CarverGrainField.Direction(dx < 0.0 ? -1 : 1, 0, 0);
        if (ay >= az) return new CarverGrainField.Direction(0, dy < 0.0 ? -1 : 1, 0);
        return new CarverGrainField.Direction(0, 0, dz < 0.0 ? -1 : 1);
    }

    /** Cosine between two lattice directions, 0 when either is degenerate. */
    public static double dot(CarverGrainField.Direction a, CarverGrainField.Direction b) {
        double la = Math.sqrt(a.x() * (double) a.x() + a.y() * (double) a.y() + a.z() * (double) a.z());
        double lb = Math.sqrt(b.x() * (double) b.x() + b.y() * (double) b.y() + b.z() * (double) b.z());
        if (la <= 0.0 || lb <= 0.0) return 0.0;
        return (a.x() * b.x() + a.y() * b.y() + a.z() * b.z()) / (la * lb);
    }

    /** Alignment class of a cut relative to the grain travel. */
    public static Alignment classify(CarverGrainField.Direction stroke,
                                     CarverGrainField.Direction grain) {
        double d = dot(stroke, grain);
        if (d >= ALONG_THRESHOLD) return Alignment.ALONG;
        if (d <= CROSS_THRESHOLD) return Alignment.CROSS;
        return Alignment.NEUTRAL;
    }

    /** Smooth alignment score in -1..1: +1 rides the grain, -1 cuts straight across it. */
    public static double alignmentScore(CarverGrainField.Direction stroke,
                                        CarverGrainField.Direction grain) {
        return dot(stroke, grain);
    }

    /** Work-time hardness multiplier: cutting across the grain fights the material. */
    public static double hardnessMultiplier(Alignment alignment, double strength) {
        double s = clamp01(strength);
        return switch (alignment) {
            case ALONG -> 1.0 - 0.35 * s;
            case CROSS -> 1.0 + 0.55 * s;
            case NEUTRAL -> 1.0 - 0.10 * s;
        };
    }

    /** Chisel/edge wear multiplier: cross-grain cuts dull the tool fastest. */
    public static double wearFactor(Alignment alignment, double strength) {
        double s = clamp01(strength);
        return switch (alignment) {
            case ALONG -> 1.0 - 0.35 * s;
            case CROSS -> 1.0 + 0.85 * s;
            case NEUTRAL -> 1.0;
        };
    }

    /** Friction-heat multiplier: cross-grain rubs and heats. */
    public static double heatFactor(Alignment alignment, double strength) {
        double s = clamp01(strength);
        return switch (alignment) {
            case ALONG -> 1.0 - 0.40 * s;
            case CROSS -> 1.0 + 1.10 * s;
            case NEUTRAL -> 1.0;
        };
    }

    /** Per-cut tear-out probability: cross-grain on brittle stock rips cells loose. */
    public static double tearOutChance(Alignment alignment, double strength, double brittleness) {
        double s = clamp01(strength);
        double b = clamp01(brittleness);
        return switch (alignment) {
            case ALONG -> 0.01 * b;
            case CROSS -> 0.12 + 0.55 * s * b;
            case NEUTRAL -> 0.03 * (0.5 + b);
        };
    }

    /**
     * Aggregate grain respect from per-cut weights (0..1): riding the grain scores high, cutting
     * across it scores low, neutral sits in between. Returns 0.5 when nothing was cut. Pure.
     */
    public static double respectScore(double alongWeight, double neutralWeight, double crossWeight) {
        double total = alongWeight + neutralWeight + crossWeight;
        if (total <= 0.0) return 0.5;
        double raw = clamp01((alongWeight + 0.5 * neutralWeight) / total);
        // Smoothstep keeps 0 -> 0, a half-neutral cut -> 0.5 and a perfect seam ride -> 1.
        return raw * raw * (3.0 - 2.0 * raw);
    }

    /** Grade band for a 0..1 respect score: 0 rough, 1 masterful. */
    public static String grade(double score) {
        double s = clamp01(score);
        if (s >= 0.80) return "майстерна";
        if (s >= 0.55) return "чиста";
        return "груба";
    }

    private static final int[] NEIGHBOUR_X = {-1, 1, 0, 0, 0, 0};
    private static final int[] NEIGHBOUR_Y = {0, 0, -1, 1, 0, 0};
    private static final int[] NEIGHBOUR_Z = {0, 0, 0, 0, -1, 1};

    /**
     * Draft-time grain respect against a solid workpiece: every exposed cut face of the removed
     * mask is compared to the grain domains, so the artisan sees (and the server prices) the
     * drawing the moment it is fixed. Pure, and shared verbatim with the server evaluation.
     */
    public static double draftRespect(DraftMask draft, CarverGrainField.Field grain) {
        if (draft == null || grain == null || !grain.hasGrain() || draft.isEmpty()) return 0.5;
        double along = 0.0;
        double cross = 0.0;
        for (int cell : draft.cells()) {
            int x = DraftMask.x(cell);
            int y = DraftMask.y(cell);
            int z = DraftMask.z(cell);
            int domain = grain.domain(cell);
            double strength = grain.strength(cell);
            for (int dir = 0; dir < 6; dir++) {
                int nx = x + NEIGHBOUR_X[dir];
                int ny = y + NEIGHBOUR_Y[dir];
                int nz = z + NEIGHBOUR_Z[dir];
                if (nx < 0 || nx > 15 || ny < 0 || ny > 15 || nz < 0 || nz > 15) continue;
                int neighbour = nx | (nz << 4) | (ny << 8);
                if (draft.get(neighbour)) continue; // both carved away: no exposed face
                double weight = 0.25 + strength;
                if (grain.domain(neighbour) == domain) {
                    cross += weight;
                } else {
                    along += weight;
                }
            }
        }
        return respectScore(along, 0.0, cross);
    }

    /** Mastery points a finished piece is worth for a 0..1 respect score. */
    public static int masteryPoints(double score, int cellsRemoved) {
        double s = clamp01(score);
        int base = Math.max(1, cellsRemoved);
        return (int) Math.round(base * (0.35 + 1.30 * s));
    }

    private static double clamp01(double value) {
        if (!(value > 0.0)) return 0.0;
        return value >= 1.0 ? 1.0 : value;
    }
}
