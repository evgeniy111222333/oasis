package ua.rp.chat.microvoxel;

/** Deterministic material-pressure model shared by runtime explosions and core tests. */
public final class MicrovoxelExplosionRules {
    private MicrovoxelExplosionRules() {
    }

    public static boolean shouldBreak(
            float radius,
            double distance,
            double resistance,
            boolean exposed,
            double variance
    ) {
        double effectRadius = Math.max(1.0, radius * 2.0);
        if (radius <= 0.0f || distance < 0.0 || distance >= effectRadius) return false;
        double attenuation = 1.0 - distance / effectRadius;
        double pressure = radius * 4.0 * attenuation * attenuation
                * Math.max(0.88, Math.min(1.12, variance));
        double threshold = Math.max(0.1, resistance) * (exposed ? 0.60 : 2.50);
        return pressure > threshold;
    }

    public static double variance(MicrovoxelKey key, int cell) {
        long mixed = key.x() * 0x9E3779B97F4A7C15L
                ^ key.y() * 0xC2B2AE3D27D4EB4FL
                ^ key.z() * 0x165667B19E3779F9L
                ^ cell * 0xD6E8FEB86659FD93L;
        mixed ^= mixed >>> 30;
        mixed *= 0xBF58476D1CE4E5B9L;
        mixed ^= mixed >>> 27;
        mixed *= 0x94D049BB133111EBL;
        mixed ^= mixed >>> 31;
        return 0.88 + 0.24 * ((mixed >>> 11) * 0x1.0p-53);
    }
}
