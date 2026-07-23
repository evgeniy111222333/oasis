package ua.rp.chat.blood;

/** Pure, deterministic policies shared by the renderer and its regression tests. */
public final class BloodFxRules {
    public static final int MAX_ACTIVE_DROPS = 384;
    public static final int MAX_ACTIVE_DECALS = 640;
    public static final int MAX_IMPACT_DROPS_PER_EVENT = 24;
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
                ? Math.round(5.0f + clamped * 15.0f)
                : Math.round(4.0f + clamped * 11.0f);
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
        if (bleeding >= 10.0f) count++;
        if (bleeding >= 22.0f && movement >= 0.35f) count++;
        return scaleForDistance(count, distanceSq);
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
