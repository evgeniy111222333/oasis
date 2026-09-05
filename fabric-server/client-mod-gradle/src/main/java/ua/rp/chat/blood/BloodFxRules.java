package ua.rp.chat.blood;

/** Pure, deterministic policies shared by the renderer and its regression tests. */
public final class BloodFxRules {
    public static final int DECAL_VARIANTS_PER_ENERGY = 8;
    public static final int DECAL_ENERGY_TIERS = 3;
    public static final int DECAL_FAMILY_COUNT = DECAL_VARIANTS_PER_ENERGY * DECAL_ENERGY_TIERS;
    public static final int DECAL_STAGE_COUNT = 4;
    public static final int DECAL_SPRITE_COUNT = DECAL_FAMILY_COUNT * DECAL_STAGE_COUNT;
    public static final int MAX_ACTIVE_DROPS = 192;
    public static final int MAX_ACTIVE_DECALS = 384;
    public static final int MAX_IMPACT_DROPS_PER_EVENT = 12;
    public static final double FULL_DETAIL_DISTANCE_SQ = 16.0 * 16.0;
    public static final double MEDIUM_DETAIL_DISTANCE_SQ = 32.0 * 32.0;
    public static final double MAX_DISTANCE_SQ = 56.0 * 56.0;

    private BloodFxRules() {
    }

    public static int impactDropCount(float intensity, int profile, double distanceSq) {
        if (!Float.isFinite(intensity) || distanceSq > MAX_DISTANCE_SQ || profile == 2) {
            return profile == 2 && distanceSq <= FULL_DETAIL_DISTANCE_SQ && intensity >= 0.72f ? 1 : 0;
        }
        float clamped = clamp01(intensity);
        int base = profile == 1
                ? Math.round(2.0f + clamped * 7.0f)
                : Math.round(1.0f + clamped * 6.0f);
        return Math.min(MAX_IMPACT_DROPS_PER_EVENT, scaleForDistance(base, distanceSq));
    }

    public static int emissionIntervalTicks(float bleeding, float movement, long entropy) {
        float severity = clamp01(bleeding / 24.0f);
        float motion = clamp01(movement);
        int base = Math.round(36.0f - severity * 27.0f - motion * 5.0f);
        int jitter = (int) Math.floorMod(mix64(entropy), 7L) - 3;
        return Math.max(5, Math.min(42, base + jitter));
    }

    public static int continuingDropCount(float bleeding, float movement, double distanceSq) {
        if (!Float.isFinite(bleeding) || bleeding <= 0.1f || distanceSq > MAX_DISTANCE_SQ) {
            return 0;
        }
        int count = 1;
        if (bleeding >= 18.0f && movement >= 0.25f) count++;
        return scaleForDistance(count, distanceSq);
    }

    /**
     * Exit speed in blocks/tick. The curve is deliberately sub-linear: wound
     * severity changes volume much more than launch velocity.
     */
    public static float impactSpeed(float intensity, int profile, long entropy) {
        float severity = clamp01(intensity);
        float random = unitFloat(entropy);
        float base = profile == 1 ? 0.030f : 0.020f;
        float profileGain = profile == 1 ? 0.055f : 0.042f;
        if (profile == 2) profileGain *= 0.35f;
        return base + severity * profileGain + random * (0.018f + severity * 0.018f);
    }

    /** Wetness retained after a footprint; guarantees a finite trail. */
    public static float footprintWetnessAfterStep(float wetness, float movement) {
        float consumed = 0.23f + clamp01(movement) * 0.09f;
        return Math.max(0.0f, clamp01(wetness) - consumed);
    }

    /** Four cells per axis keep nearby drops mergeable without collapsing an entire block face. */
    public static int surfaceCell(double coordinate) {
        double local = coordinate - Math.floor(coordinate);
        return Math.max(0, Math.min(3, (int) Math.floor(local * 4.0)));
    }

    public static int decalLifetimeTicks(int material, float size, long entropy) {
        int base = switch (material) {
            case 1 -> 520;  // soil absorbs
            case 2 -> 680;  // sand
            case 3 -> 980;  // wood
            case 4 -> 1320; // snow
            case 5 -> 760;  // cloth
            default -> 1600;
        };
        int jitter = (int) Math.floorMod(mix64(entropy), 241L) - 120;
        return Math.max(260, Math.round(base * (0.72f + clamp01(size) * 0.48f)) + jitter);
    }

    public static int scaleForDistance(int count, double distanceSq) {
        if (count <= 0 || !Double.isFinite(distanceSq) || distanceSq > MAX_DISTANCE_SQ) return 0;
        if (distanceSq <= FULL_DETAIL_DISTANCE_SQ) return count;
        if (distanceSq <= MEDIUM_DETAIL_DISTANCE_SQ) return Math.max(1, (count + 1) / 2);
        return count >= 4 ? 1 : 0;
    }

    public static float driedColorFactor(int age, int lifetime) {
        if (lifetime <= 0) return 0.52f;
        float t = clamp01(age / (float) lifetime);
        return 1.0f - t * 0.48f;
    }

    /**
     * Selects one of 24 authored silhouettes.  Energy owns the broad visual
     * family while material, surface position and droplet entropy select one
     * of eight variants inside that family.
     */
    public static int decalFamily(float energy, int material, long entropy) {
        float clamped = clamp01(energy);
        int tier = clamped < 0.34f ? 0 : clamped < 0.72f ? 1 : 2;
        long mixed = mix64(entropy
                ^ ((long) Math.max(0, material) * 0x9e3779b97f4a7c15L)
                ^ Integer.toUnsignedLong(Float.floatToIntBits(clamped)));
        int variant = Math.floorMod((int) mixed, DECAL_VARIANTS_PER_ENERGY);
        return tier * DECAL_VARIANTS_PER_ENERGY + variant;
    }

    /** Maps time since the last fresh hit to fresh, settled, drying and dry art. */
    public static int decalStage(int ageSinceWet, int lifetime) {
        if (ageSinceWet <= 0) return 0;
        float progress = clamp01(ageSinceWet / (float) Math.max(1, lifetime));
        if (progress < 0.08f) return 0;
        if (progress < 0.36f) return 1;
        if (progress < 0.72f) return 2;
        return 3;
    }

    public static long mix64(long value) {
        value ^= value >>> 30;
        value *= 0xbf58476d1ce4e5b9L;
        value ^= value >>> 27;
        value *= 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }

    public static float unitFloat(long value) {
        return (mix64(value) >>> 40) / (float) (1L << 24);
    }

    public static float clamp01(float value) {
        if (!Float.isFinite(value)) return 0.0f;
        return Math.max(0.0f, Math.min(1.0f, value));
    }
}
