package ua.rp.chat.blood;

/** Pure policies for contact cadence, finite sole reservoirs and long-lived marks. */
public final class FootprintRules {
    public static final int LEFT = 0;
    public static final int RIGHT = 1;
    public static final int GAIT_WALK = 0;
    public static final int GAIT_RUN = 1;
    public static final int GAIT_CROUCH = 2;
    public static final int GAIT_LANDING = 3;
    public static final int GAIT_SLIDE = 4;
    public static final int VARIANTS_PER_FOOT = 6;
    public static final int STAGES = 4;
    public static final int SPRITE_COUNT = 2 * VARIANTS_PER_FOOT * STAGES;
    public static final int MAX_RENDERED_FOOTPRINTS = 512;
    public static final int DEFAULT_LIFETIME_TICKS = 54_000;

    private FootprintRules() {
    }

    public static float pickup(float current, float available, float contactArea,
                               int material, int footwear) {
        float absorption = switch (material) {
            case 1, 2 -> 0.62f;
            case 4 -> 0.74f;
            case 5 -> 0.82f;
            default -> 1.0f;
        };
        float footwearFactor = switch (footwear) {
            case 1 -> 0.88f; // leather
            case 2 -> 0.72f; // hard metal/composite sole
            case 3 -> 1.08f; // absorbent/soft footwear
            default -> 1.0f;
        };
        float transferred = BloodFxRules.clamp01(available)
                * (0.22f + BloodFxRules.clamp01(contactArea) * 0.56f)
                * absorption * footwearFactor;
        return BloodFxRules.clamp01(Math.max(current, current + transferred * (1.0f - current)));
    }

    public static float deposit(float wetness, int gait, int material) {
        float gaitFactor = switch (gait) {
            case GAIT_RUN -> 0.34f;
            case GAIT_CROUCH -> 0.18f;
            case GAIT_LANDING -> 0.42f;
            case GAIT_SLIDE -> 0.38f;
            default -> 0.26f;
        };
        float surfaceFactor = switch (material) {
            case 1, 2 -> 0.82f;
            case 4, 5 -> 0.92f;
            default -> 1.0f;
        };
        return Math.min(BloodFxRules.clamp01(wetness), gaitFactor * surfaceFactor);
    }

    public static float afterDeposit(float wetness, float deposited, int gait) {
        float loss = deposited * (gait == GAIT_RUN ? 1.08f : 0.96f) + 0.018f;
        return Math.max(0.0f, BloodFxRules.clamp01(wetness) - loss);
    }

    public static float passiveDry(float wetness, boolean raining, boolean submerged) {
        float loss = submerged ? 0.085f : raining ? 0.010f : 0.00022f;
        return Math.max(0.0f, BloodFxRules.clamp01(wetness) - loss);
    }

    public static float stepDistance(int gait, float speed) {
        float normalized = BloodFxRules.clamp01(speed);
        return switch (gait) {
            case GAIT_RUN -> 0.48f + normalized * 0.18f;
            case GAIT_CROUCH -> 0.22f + normalized * 0.12f;
            default -> 0.31f + normalized * 0.16f;
        };
    }

    public static double accumulateTravel(double current, double moved) {
        if (!Double.isFinite(current) || current < 0.0) current = 0.0;
        if (!Double.isFinite(moved) || moved < 0.0 || moved > 1.5) return current;
        return Math.min(2.0, current + moved);
    }

    public static boolean contactDue(double accumulated, int gait, float speed) {
        return Double.isFinite(accumulated) && accumulated >= stepDistance(gait, speed);
    }

    public static double afterContact(double accumulated, int gait, float speed) {
        if (!Double.isFinite(accumulated)) return 0.0;
        return Math.max(0.0, accumulated - stepDistance(gait, speed));
    }

    public static int variant(int foot, int gait, int footwear, long seed) {
        int gaitBase = switch (gait) {
            case GAIT_RUN -> 2;
            case GAIT_CROUCH -> 4;
            case GAIT_LANDING -> 5;
            case GAIT_SLIDE -> 3;
            default -> 0;
        };
        int variant = Math.floorMod(gaitBase + Math.max(0, footwear)
                + (int) BloodFxRules.mix64(seed), VARIANTS_PER_FOOT);
        return Math.max(LEFT, Math.min(RIGHT, foot)) * VARIANTS_PER_FOOT + variant;
    }

    public static int stage(float wetness, int ageTicks, int lifetimeTicks) {
        float age = BloodFxRules.clamp01(ageTicks / (float) Math.max(1, lifetimeTicks));
        float dryness = Math.max(age, 1.0f - BloodFxRules.clamp01(wetness));
        if (dryness < 0.24f) return 0;
        if (dryness < 0.52f) return 1;
        if (dryness < 0.80f) return 2;
        return 3;
    }

    public static int lifetimeTicks(int material, float wetness, long seed) {
        int base = switch (material) {
            case 1 -> 30_000;
            case 2 -> 36_000;
            case 4 -> 22_000;
            case 5 -> 48_000;
            default -> DEFAULT_LIFETIME_TICKS;
        };
        int jitter = Math.floorMod((int) BloodFxRules.mix64(seed), 6_001) - 3_000;
        return Math.max(12_000, base + Math.round(BloodFxRules.clamp01(wetness) * 12_000) + jitter);
    }

    public static long mergeCell(double x, double y, double z) {
        long ix = ((long) Math.floor(x * 8.0)) & 0x1fffffL;
        long iy = ((long) Math.floor(y * 8.0)) & 0x3ffffL;
        long iz = ((long) Math.floor(z * 8.0)) & 0x1fffffL;
        return ix | (iz << 21) | (iy << 42);
    }
}
