package ua.rp.chat;

/** Pure shoulder kinematics derived from the same surface used by the breathing torso. */
public final class BreathingShoulderLayout {
    public static final float BASE_SHOULDER_X = 5.0f;
    public static final float BASE_SHOULDER_Y = 2.0f;
    public static final float INNER_ARM_OFFSET = 1.0f;
    private static final float MAX_OUTWARD_ROLL = 0.12f;

    private BreathingShoulderLayout() {
    }

    public static Pose pose(double phase, double intensity, double calm, boolean firstPerson) {
        float effort = (float) clamp(intensity, 0.0, 1.0);
        float amplitude = BreathingTorsoLayout.amplitude(intensity, calm, firstPerson);
        float upperBreath = BreathingTorsoLayout.regionalBreath(phase, 1.0f);
        float lift = amplitude * upperBreath * (0.24f + effort * 0.06f);

        float topSide = sideExpansion(0, phase, intensity, calm, firstPerson);
        float requiredSine = 0.0f;
        for (int ring = 1; ring < BreathingTorsoLayout.ringCount(); ring++) {
            float y = BreathingTorsoLayout.ringY(ring);
            if (y > 6.0f) {
                break;
            }
            float side = sideExpansion(ring, phase, intensity, calm, firstPerson);
            requiredSine = Math.max(requiredSine, (side - topSide) / y);
        }
        requiredSine = (float) clamp(requiredSine, 0.0, Math.sin(MAX_OUTWARD_ROLL));
        float outwardRoll = (float) Math.asin(requiredSine);

        // The arm pivot is 2px below the torso top. Compensating the roll here
        // keeps the inner upper-arm corner exactly on the breathing top seam.
        float rootOut = topSide - INNER_ARM_OFFSET
                + INNER_ARM_OFFSET * (float) Math.cos(outwardRoll)
                + (BASE_SHOULDER_Y - lift) * (float) Math.sin(outwardRoll);
        rootOut = Math.max(0.0f, rootOut);

        float forwardPitch = amplitude * upperBreath * (0.025f + effort * 0.015f);
        return new Pose(rootOut, lift, outwardRoll, forwardPitch);
    }

    public static float innerArmBoundary(float torsoY, Pose pose) {
        float localY = torsoY - (BASE_SHOULDER_Y - pose.liftPixels());
        return BASE_SHOULDER_X + pose.rootOutPixels()
                - INNER_ARM_OFFSET * (float) Math.cos(pose.outwardRollRadians())
                + localY * (float) Math.sin(pose.outwardRollRadians());
    }

    private static float sideExpansion(
            int ring, double phase, double intensity, double calm, boolean firstPerson) {
        BreathingTorsoLayout.Bounds bounds = BreathingTorsoLayout.bounds(
                ring, phase, intensity, calm, firstPerson, false);
        return bounds.maxX() - BreathingTorsoLayout.BODY_HALF_WIDTH;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    public record Pose(
            float rootOutPixels,
            float liftPixels,
            float outwardRollRadians,
            float forwardPitchRadians) {
    }
}
