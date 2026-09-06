package ua.rp.chat.blood;

/**
 * Deterministic conversion between medical bleeding and visible liquid volume.
 * The server is authoritative; the client only spends volume that was granted
 * by the server.
 */
public final class BloodVolumeRules {
    public static final float MILLILITRES_PER_BLOOD_UNIT = 50.0f;
    public static final float NOMINAL_DROP_ML = 1.25f;
    public static final float MIN_VISIBLE_DROP_ML = 0.35f;
    public static final float MAX_DROP_ML = 8.0f;
    public static final float MAX_CLIENT_ACCUMULATOR_ML = 12.0f;
    public static final float MAX_FLOW_ML_PER_SECOND = 20.0f;
    private static final int MAX_IMPACT_DROPS = 12;
    private static final double FULL_DETAIL_DISTANCE_SQ = 16.0 * 16.0;
    private static final double MEDIUM_DETAIL_DISTANCE_SQ = 32.0 * 32.0;
    private static final double MAX_DISTANCE_SQ = 56.0 * 56.0;

    private BloodVolumeRules() {
    }

    public static float impactVolumeMl(int profile, float damage, float intensity, boolean embeddedProjectile) {
        float safeDamage = clamp(damage, 0.0f, 20.0f);
        float severity = clamp01(intensity);
        return switch (profile) {
            case 0 -> clamp(0.8f + safeDamage * 2.15f + severity * 2.4f, 0.0f, 28.0f);
            case 1 -> embeddedProjectile
                    ? clamp(1.5f + safeDamage * 1.15f + severity * 1.8f, 0.0f, 14.0f)
                    : clamp(3.0f + safeDamage * 1.9f + severity * 2.8f, 0.0f, 24.0f);
            case 2 -> severity >= 0.88f ? clamp((severity - 0.82f) * 5.0f, 0.0f, 1.2f) : 0.0f;
            default -> 0.0f;
        };
    }

    public static float flowRateMlPerSecond(float bleedingScore, boolean openWound,
                                            boolean bandaged, boolean tourniquet,
                                            boolean embeddedProjectile, float movement) {
        if (!openWound || bandaged || tourniquet || bleedingScore <= 0.1f) return 0.0f;
        float score = clamp(bleedingScore, 0.0f, 100.0f);
        float rate = score * 0.065f + score * score * 0.00075f;
        if (embeddedProjectile) rate *= 0.28f;
        rate *= 1.0f + clamp01(movement) * 0.35f;
        return clamp(rate, 0.0f, MAX_FLOW_ML_PER_SECOND);
    }

    public static float woundFlowWeight(int profile, float intensity) {
        float severity = clamp01(intensity);
        return switch (profile) {
            case 0 -> 0.8f + severity * 1.2f;
            case 1 -> 0.7f + severity;
            default -> 0.0f;
        };
    }

    public static float bloodUnitsForVolume(float volumeMl) {
        return Math.max(0.0f, volumeMl) / MILLILITRES_PER_BLOOD_UNIT;
    }

    public static int impactDropCount(float volumeMl, double distanceSq) {
        if (!Float.isFinite(volumeMl) || volumeMl < MIN_VISIBLE_DROP_ML
                || distanceSq > MAX_DISTANCE_SQ) {
            return 0;
        }
        int count = Math.max(1, Math.round(volumeMl / NOMINAL_DROP_ML));
        count = Math.min(MAX_IMPACT_DROPS, count);
        return scaleForDistance(count, distanceSq);
    }

    public static float dropVolume(float totalVolumeMl, int count) {
        if (count <= 0) return 0.0f;
        return clamp(totalVolumeMl / count, MIN_VISIBLE_DROP_ML, MAX_DROP_ML);
    }

    public static float accumulatorAfterTick(float accumulatorMl, float flowMlPerSecond) {
        float next = Math.max(0.0f, accumulatorMl) + Math.max(0.0f, flowMlPerSecond) / 20.0f;
        return Math.min(MAX_CLIENT_ACCUMULATOR_ML, next);
    }

    public static int spendableDrops(float accumulatorMl) {
        if (!Float.isFinite(accumulatorMl) || accumulatorMl < NOMINAL_DROP_ML) return 0;
        return Math.min(2, (int) Math.floor(accumulatorMl / NOMINAL_DROP_ML));
    }

    public static float decalRadius(float volumeMl) {
        float volume = clamp(volumeMl, MIN_VISIBLE_DROP_ML, 80.0f);
        return clamp(0.052f + (float) Math.sqrt(volume) * 0.043f, 0.075f, 0.44f);
    }

    public static float wallFlowTransfer(float volumeMl) {
        if (!Float.isFinite(volumeMl) || volumeMl < 0.72f) return 0.0f;
        return clamp(volumeMl * 0.34f, 0.28f, 0.85f);
    }

    public static float clamp01(float value) {
        return clamp(value, 0.0f, 1.0f);
    }

    private static int scaleForDistance(int count, double distanceSq) {
        if (count <= 0 || distanceSq > MAX_DISTANCE_SQ) return 0;
        if (distanceSq <= FULL_DETAIL_DISTANCE_SQ) return count;
        if (distanceSq <= MEDIUM_DETAIL_DISTANCE_SQ) return Math.max(1, (count + 1) / 2);
        return Math.max(1, (count + 3) / 4);
    }

    private static float clamp(float value, float min, float max) {
        if (!Float.isFinite(value)) return min;
        return Math.max(min, Math.min(max, value));
    }
}
