package ua.rp.chat;

public final class BreathingShoulderLayoutTest {
    private static final float EPSILON = 0.0002f;

    public static void main(String[] args) {
        validatesNeutralAndPeakPose();
        validatesArmpitContactAcrossCycle();
        validatesSymmetryAndNonAccumulation();
        validatesContinuousMotion();
        System.out.println("BreathingShoulderLayoutTest: seam, clearance, symmetry and stability invariants passed");
    }

    private static void validatesNeutralAndPeakPose() {
        BreathingShoulderLayout.Pose neutral = BreathingShoulderLayout.pose(0.4, 1.0, 0.0, false);
        assertClose("neutral root", 0.0f, neutral.rootOutPixels());
        assertClose("neutral lift", 0.0f, neutral.liftPixels());
        assertClose("neutral roll", 0.0f, neutral.outwardRollRadians());
        assertClose("neutral pitch", 0.0f, neutral.forwardPitchRadians());

        BreathingShoulderLayout.Pose peak = BreathingShoulderLayout.pose(0.4, 1.0, 1.0, false);
        assertRange("peak root", peak.rootOutPixels(), 0.28f, 0.32f);
        assertRange("peak lift", peak.liftPixels(), 0.13f, 0.15f);
        assertRange("peak roll", peak.outwardRollRadians(), 0.050f, 0.065f);
        assertRange("peak pitch", peak.forwardPitchRadians(), 0.018f, 0.021f);
    }

    private static void validatesArmpitContactAcrossCycle() {
        double[] intensities = {0.0, 0.25, 0.65, 1.0};
        double[] calmValues = {0.0, 0.28, 0.62, 1.0};
        for (int frame = 0; frame <= 512; frame++) {
            double phase = frame / 512.0;
            for (double intensity : intensities) {
                for (double calm : calmValues) {
                    for (boolean firstPerson : new boolean[]{false, true}) {
                        BreathingShoulderLayout.Pose pose = BreathingShoulderLayout.pose(
                                phase, intensity, calm, firstPerson);
                        assertFiniteAndBounded(pose);
                        for (int ring = 0; ring < BreathingTorsoLayout.ringCount(); ring++) {
                            float y = BreathingTorsoLayout.ringY(ring);
                            if (y > 6.0f) {
                                break;
                            }
                            float armInner = BreathingShoulderLayout.innerArmBoundary(y, pose);
                            float torsoSide = BreathingTorsoLayout.bounds(
                                    ring, phase, intensity, calm, firstPerson, false).maxX();
                            require(armInner + EPSILON >= torsoSide,
                                    "upper arm entered torso at phase=" + phase + ", ring=" + ring);
                        }

                        float topArm = BreathingShoulderLayout.innerArmBoundary(0.0f, pose);
                        float topTorso = BreathingTorsoLayout.bounds(
                                0, phase, intensity, calm, firstPerson, false).maxX();
                        assertClose("top armpit seam", topTorso, topArm);
                    }
                }
            }
        }
    }

    private static void validatesSymmetryAndNonAccumulation() {
        BreathingShoulderLayout.Pose pose = BreathingShoulderLayout.pose(0.31, 0.92, 1.0, false);
        float expectedLeft = BreathingShoulderLayout.BASE_SHOULDER_X + pose.rootOutPixels();
        float expectedRight = -BreathingShoulderLayout.BASE_SHOULDER_X - pose.rootOutPixels();
        assertClose("symmetric roots", expectedLeft, -expectedRight);
        float leftRoll = -pose.outwardRollRadians();
        float rightRoll = pose.outwardRollRadians();
        assertClose("symmetric rolls", leftRoll, -rightRoll);

        float left = 999.0f;
        float right = -999.0f;
        for (int frame = 0; frame < 10_000; frame++) {
            // Mirrors PlayerModelMixin: restore the absolute vanilla root, then apply one sample.
            left = BreathingShoulderLayout.BASE_SHOULDER_X + pose.rootOutPixels();
            right = -BreathingShoulderLayout.BASE_SHOULDER_X - pose.rootOutPixels();
        }
        assertClose("left does not accumulate", expectedLeft, left);
        assertClose("right does not accumulate", expectedRight, right);
    }

    private static void validatesContinuousMotion() {
        BreathingShoulderLayout.Pose previous = BreathingShoulderLayout.pose(0.0, 1.0, 1.0, false);
        for (int frame = 1; frame <= 4096; frame++) {
            BreathingShoulderLayout.Pose current = BreathingShoulderLayout.pose(
                    frame / 4096.0, 1.0, 1.0, false);
            require(Math.abs(current.rootOutPixels() - previous.rootOutPixels()) < 0.004f,
                    "root discontinuity at frame " + frame);
            require(Math.abs(current.outwardRollRadians() - previous.outwardRollRadians()) < 0.001f,
                    "roll discontinuity at frame " + frame);
            previous = current;
        }
    }

    private static void assertFiniteAndBounded(BreathingShoulderLayout.Pose pose) {
        require(Float.isFinite(pose.rootOutPixels()) && Float.isFinite(pose.liftPixels())
                        && Float.isFinite(pose.outwardRollRadians()) && Float.isFinite(pose.forwardPitchRadians()),
                "pose must remain finite");
        assertRange("root bound", pose.rootOutPixels(), 0.0f, 0.35f);
        assertRange("lift bound", pose.liftPixels(), 0.0f, 0.15f);
        assertRange("roll bound", pose.outwardRollRadians(), 0.0f, 0.12f);
        assertRange("pitch bound", pose.forwardPitchRadians(), 0.0f, 0.021f);
    }

    private static void assertClose(String label, float expected, float actual) {
        require(Math.abs(expected - actual) <= EPSILON,
                label + ": expected " + expected + ", got " + actual);
    }

    private static void assertRange(String label, float value, float min, float max) {
        require(value >= min - EPSILON && value <= max + EPSILON,
                label + ": " + value + " outside [" + min + ", " + max + "]");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
