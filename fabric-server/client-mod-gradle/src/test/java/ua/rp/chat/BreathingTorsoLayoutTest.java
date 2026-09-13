package ua.rp.chat;

public final class BreathingTorsoLayoutTest {
    private static final float EPSILON = 0.00001f;

    public static void main(String[] args) {
        verifyVProfileAndTravelDirection();
        verifyGeometryAcrossEveryPhase();
        verifyOuterLayerClearance();
        verifyAmplitudeEnvelope();
        System.out.println("BreathingTorsoLayoutTest: V-profile, propagation and layer invariants passed");
    }

    private static void verifyVProfileAndTravelDirection() {
        assertTrue("waist remains the fixed V apex",
                BreathingTorsoLayout.profile(BreathingTorsoLayout.ringCount() - 1) == 0.0f);
        assertTrue("upper ribs are the widest respiratory region",
                BreathingTorsoLayout.profile(2) > BreathingTorsoLayout.profile(0)
                        && BreathingTorsoLayout.profile(2) > BreathingTorsoLayout.profile(5));

        float lowerInhale = BreathingTorsoLayout.regionalBreath(0.05, 0.16f);
        float upperInhale = BreathingTorsoLayout.regionalBreath(0.05, 1.0f);
        assertTrue("inhale starts at the diaphragm and travels upward", lowerInhale > upperInhale);

        float upperExhale = BreathingTorsoLayout.regionalBreath(0.52, 1.0f);
        float lowerExhale = BreathingTorsoLayout.regionalBreath(0.52, 0.16f);
        assertTrue("exhale relaxes the upper back before the diaphragm", upperExhale < lowerExhale);
    }

    private static void verifyGeometryAcrossEveryPhase() {
        double[] intensities = {0.0, 0.5, 1.0};
        double[] calmValues = {0.0, 0.28, 1.0};
        for (int sample = 0; sample <= 256; sample++) {
            double phase = sample / 256.0;
            for (double intensity : intensities) {
                for (double calm : calmValues) {
                    for (boolean firstPerson : new boolean[]{false, true}) {
                        float previousY = -1.0f;
                        for (int ring = 0; ring < BreathingTorsoLayout.ringCount(); ring++) {
                            BreathingTorsoLayout.Bounds bounds = BreathingTorsoLayout.bounds(
                                    ring, phase, intensity, calm, firstPerson, false);
                            assertFinite("ring bounds", bounds.y(), bounds.minX(), bounds.maxX(), bounds.minZ(), bounds.maxZ());
                            assertTrue("rings remain vertically ordered", bounds.y() > previousY);
                            assertTrue("quad width never inverts", bounds.width() > 0.0f);
                            assertTrue("quad depth never inverts", bounds.depth() > 0.0f);
                            previousY = bounds.y();
                        }
                    }
                }
            }
        }

        int waist = BreathingTorsoLayout.ringCount() - 1;
        for (int sample = 0; sample < 64; sample++) {
            BreathingTorsoLayout.Bounds apex = BreathingTorsoLayout.bounds(
                    waist, sample / 64.0, 1.0, 1.0, false, false);
            assertClose("waist minX", -4.0f, apex.minX());
            assertClose("waist maxX", 4.0f, apex.maxX());
            assertClose("waist minZ", -2.0f, apex.minZ());
            assertClose("waist maxZ", 2.0f, apex.maxZ());
        }
    }

    private static void verifyOuterLayerClearance() {
        for (int sample = 0; sample < 128; sample++) {
            double phase = sample / 128.0;
            for (int ring = 0; ring < BreathingTorsoLayout.ringCount(); ring++) {
                BreathingTorsoLayout.Bounds skin = BreathingTorsoLayout.bounds(
                        ring, phase, 1.0, 1.0, false, false);
                BreathingTorsoLayout.Bounds jacket = BreathingTorsoLayout.bounds(
                        ring, phase, 1.0, 1.0, false, true);
                assertClose("jacket left clearance", BreathingTorsoLayout.OUTER_LAYER_GROW,
                        skin.minX() - jacket.minX());
                assertClose("jacket right clearance", BreathingTorsoLayout.OUTER_LAYER_GROW,
                        jacket.maxX() - skin.maxX());
                assertClose("jacket front clearance", BreathingTorsoLayout.OUTER_LAYER_GROW,
                        skin.minZ() - jacket.minZ());
                assertClose("jacket back clearance", BreathingTorsoLayout.OUTER_LAYER_GROW,
                        jacket.maxZ() - skin.maxZ());
            }
        }
    }

    private static void verifyAmplitudeEnvelope() {
        float maxFront = 0.0f;
        for (int sample = 0; sample < 512; sample++) {
            for (int ring = 0; ring < BreathingTorsoLayout.ringCount(); ring++) {
                BreathingTorsoLayout.Bounds bounds = BreathingTorsoLayout.bounds(
                        ring, sample / 512.0, 1.0, 1.0, false, false);
                maxFront = Math.max(maxFront, -2.0f - bounds.minZ());
                assertTrue("skin never exceeds the safe armor envelope", -2.0f - bounds.minZ() <= 0.481f);
            }
        }
        assertTrue("exhausted inhale is deliberately visible", maxFront >= 0.47f);
        float resting = BreathingTorsoLayout.amplitude(0.0, 1.0, false);
        assertTrue("resting inhale remains visibly sub-pixel", resting >= 0.15f && resting <= 0.18f);
    }

    private static void assertFinite(String name, float... values) {
        for (float value : values) {
            if (!Float.isFinite(value)) {
                throw new AssertionError(name + ": non-finite value " + value);
            }
        }
    }

    private static void assertClose(String name, float expected, float actual) {
        if (Math.abs(expected - actual) > EPSILON) {
            throw new AssertionError(name + ": expected " + expected + ", got " + actual);
        }
    }

    private static void assertTrue(String name, boolean value) {
        if (!value) {
            throw new AssertionError(name);
        }
    }
}
