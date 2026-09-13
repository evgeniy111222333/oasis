package ua.rp.chat;

/** Pure geometry contract for the continuously skinned V-shaped torso. */
public final class BreathingTorsoLayout {
    public static final float BODY_HALF_WIDTH = 4.0f;
    public static final float BODY_HALF_DEPTH = 2.0f;
    public static final float BODY_HEIGHT = 12.0f;
    public static final float OUTER_LAYER_GROW = 0.25f;

    private static final float[] RING_Y = {0.0f, 2.0f, 4.0f, 6.0f, 8.0f, 10.0f, 12.0f};
    private static final float[] V_PROFILE = {0.55f, 0.88f, 1.0f, 0.78f, 0.52f, 0.25f, 0.0f};

    private BreathingTorsoLayout() {
    }

    public static int ringCount() {
        return RING_Y.length;
    }

    public static float ringY(int ring) {
        return RING_Y[ring];
    }

    public static float profile(int ring) {
        return V_PROFILE[ring];
    }

    public static Bounds bounds(
            int ring, double phase, double intensity, double calm, boolean firstPerson, boolean outerLayer) {
        float y = ringY(ring);
        float height01 = 1.0f - y / BODY_HEIGHT;
        float regionalBreath = regionalBreath(phase, height01);
        float amplitude = amplitude(intensity, calm, firstPerson);
        float weighted = amplitude * profile(ring) * regionalBreath;

        float side = weighted * 0.72f;
        float front = weighted;
        float back = weighted * (0.62f + height01 * 0.18f);
        float grow = outerLayer ? OUTER_LAYER_GROW : 0.0f;
        return new Bounds(
                y,
                -BODY_HALF_WIDTH - side - grow,
                BODY_HALF_WIDTH + side + grow,
                -BODY_HALF_DEPTH - front - grow,
                BODY_HALF_DEPTH + back + grow,
                regionalBreath);
    }

    public static float amplitude(double intensity, double calm, boolean firstPerson) {
        float effort = smootherStep((float) clamp(intensity, 0.0, 1.0));
        float visibility = (float) clamp(calm, 0.0, 1.0);
        float perspective = firstPerson ? 0.82f : 1.0f;
        return lerp(0.16f, 0.48f, effort) * visibility * perspective;
    }

    /**
     * Inhale travels diaphragm-to-scapula; exhale relaxes scapula-to-diaphragm.
     * Every region still derives from the same master phase.
     */
    public static float regionalBreath(double phase, float height01) {
        float p = (float) wrap01(phase);
        float height = (float) clamp(height01, 0.0, 1.0);
        float inhaleStart = height * 0.060f;
        float inhaleEnd = 0.360f + height * 0.020f;
        float exhaleStart = 0.430f + (1.0f - height) * 0.060f;
        float exhaleEnd = 0.880f + (1.0f - height) * 0.040f;

        if (p < inhaleStart) {
            return 0.0f;
        }
        if (p < inhaleEnd) {
            return smootherStep((p - inhaleStart) / (inhaleEnd - inhaleStart));
        }
        if (p < exhaleStart) {
            return 1.0f;
        }
        if (p < exhaleEnd) {
            return 1.0f - smootherStep((p - exhaleStart) / (exhaleEnd - exhaleStart));
        }
        return 0.0f;
    }

    private static float smootherStep(float value) {
        float x = (float) clamp(value, 0.0, 1.0);
        return x * x * x * (x * (x * 6.0f - 15.0f) + 10.0f);
    }

    private static float lerp(float from, float to, float amount) {
        return from + (to - from) * amount;
    }

    private static double wrap01(double value) {
        if (!Double.isFinite(value)) {
            return 0.0;
        }
        return value - Math.floor(value);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    public record Bounds(float y, float minX, float maxX, float minZ, float maxZ, float breath) {
        public float width() {
            return maxX - minX;
        }

        public float depth() {
            return maxZ - minZ;
        }
    }
}
