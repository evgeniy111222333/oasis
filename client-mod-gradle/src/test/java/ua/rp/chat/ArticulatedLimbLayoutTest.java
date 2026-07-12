package ua.rp.chat;

public final class ArticulatedLimbLayoutTest {
    private static final float EPSILON = 0.00001f;

    public static void main(String[] args) {
        assertClose("arm upper height", 6.0f, ArticulatedLimbLayout.armUpperHeight());
        assertClose("arm lower height", 6.0f, ArticulatedLimbLayout.armLowerHeight());
        assertClose("arm joint continuity",
                ArticulatedLimbLayout.ARM_ELBOW_Y,
                ArticulatedLimbLayout.ARM_ELBOW_Y + ArticulatedLimbLayout.LOWER_LOCAL_TOP_Y);
        assertClose("arm total length", 12.0f,
                ArticulatedLimbLayout.armUpperHeight() + ArticulatedLimbLayout.armLowerHeight());
        assertClose("arm segment overlap", 0.0f, ArticulatedLimbLayout.intervalOverlap(
                ArticulatedLimbLayout.ARM_TOP_Y,
                ArticulatedLimbLayout.ARM_ELBOW_Y,
                ArticulatedLimbLayout.ARM_ELBOW_Y + ArticulatedLimbLayout.LOWER_LOCAL_TOP_Y,
                ArticulatedLimbLayout.ARM_HAND_Y));

        assertClose("leg upper height", 6.0f, ArticulatedLimbLayout.legUpperHeight());
        assertClose("leg lower height", 6.0f, ArticulatedLimbLayout.legLowerHeight());
        assertClose("leg joint continuity",
                ArticulatedLimbLayout.LEG_KNEE_Y,
                ArticulatedLimbLayout.LEG_KNEE_Y + ArticulatedLimbLayout.LOWER_LOCAL_TOP_Y);
        assertClose("leg total length", 12.0f,
                ArticulatedLimbLayout.legUpperHeight() + ArticulatedLimbLayout.legLowerHeight());
        assertClose("leg segment overlap", 0.0f, ArticulatedLimbLayout.intervalOverlap(
                ArticulatedLimbLayout.LEG_TOP_Y,
                ArticulatedLimbLayout.LEG_KNEE_Y,
                ArticulatedLimbLayout.LEG_KNEE_Y + ArticulatedLimbLayout.LOWER_LOCAL_TOP_Y,
                ArticulatedLimbLayout.LEG_FOOT_Y));

        assertClose("wearable Y growth", 0.0f, ArticulatedLimbLayout.OUTER_LAYER_GROW_Y);
        assertClose("original end-cap V shift", -6.0f / 64.0f,
                ArticulatedLimbLayout.normalizedVShift(ArticulatedLimbLayout.ORIGINAL_CAP_V_SHIFT_PIXELS));
        assertClose("hand top cap samples upper forearm side", 4.0f / 64.0f,
                ArticulatedLimbLayout.normalizedVShift(ArticulatedLimbLayout.HAND_TOP_CAP_V_SHIFT_PIXELS));
        assertClose("hand bottom cap samples lower hand side", 6.0f / 64.0f,
                ArticulatedLimbLayout.normalizedVShift(ArticulatedLimbLayout.HAND_BOTTOM_CAP_V_SHIFT_PIXELS));
        assertTrue("right arm end cap returns to its original texture row",
                ArticulatedLimbLayout.correctedLowerEndCapRow(16 + 6) == 16);
        assertTrue("left arm end cap returns to its original texture row",
                ArticulatedLimbLayout.correctedLowerEndCapRow(48 + 6) == 48);
        assertTrue("right leg end cap returns to its original texture row",
                ArticulatedLimbLayout.correctedLowerEndCapRow(16 + 6) == 16);
        assertTrue("left leg end cap returns to its original texture row",
                ArticulatedLimbLayout.correctedLowerEndCapRow(48 + 6) == 48);

        assertClose("classic outer arm width", 4.5f,
                4.0f + ArticulatedLimbLayout.OUTER_LAYER_GROW_XZ * 2.0f);
        assertClose("slim outer arm width", 3.5f,
                3.0f + ArticulatedLimbLayout.OUTER_LAYER_GROW_XZ * 2.0f);

        float originalHandZ = -2.0f;
        assertClose("zero bend preserves hand Y", ArticulatedLimbLayout.ARM_HAND_Y,
                ArticulatedLimbLayout.rotatedHandY(0.0f, originalHandZ));
        assertClose("zero bend preserves hand Z", originalHandZ,
                ArticulatedLimbLayout.rotatedHandZ(0.0f, originalHandZ));
        float bend = -0.72f;
        float bentY = ArticulatedLimbLayout.rotatedHandY(bend, originalHandZ);
        float bentZ = ArticulatedLimbLayout.rotatedHandZ(bend, originalHandZ);
        float originalRadiusSquared = square(ArticulatedLimbLayout.ARM_HAND_Y - ArticulatedLimbLayout.ARM_ELBOW_Y)
                + square(originalHandZ);
        float bentRadiusSquared = square(bentY - ArticulatedLimbLayout.ARM_ELBOW_Y) + square(bentZ);
        assertClose("held item rotation preserves elbow-to-hand distance", originalRadiusSquared, bentRadiusSquared);

        assertTrue("elbow cylinder is inset inside the base arm",
                ArticulatedLimbLayout.ELBOW_CORE_RADIUS < 2.0f);
        assertClose("second layer keeps a radial anti-z-fighting gap",
                ArticulatedLimbLayout.OUTER_LAYER_GROW_XZ,
                ArticulatedLimbLayout.ELBOW_SLEEVE_RADIUS - ArticulatedLimbLayout.ELBOW_CORE_RADIUS);
        assertTrue("elbow cylinder has enough facets for a smooth block-scale silhouette",
                ArticulatedLimbLayout.ELBOW_CYLINDER_SEGMENTS >= 8);
        assertClose("elbow core bisects the joint angle", -0.5f,
                ArticulatedLimbLayout.jointCoreRotation(-1.0f));
        System.out.println("ArticulatedLimbLayoutTest: all geometry and UV invariants passed");
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

    private static float square(float value) {
        return value * value;
    }
}
