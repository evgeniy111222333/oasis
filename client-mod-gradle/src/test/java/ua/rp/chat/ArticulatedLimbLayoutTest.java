package ua.rp.chat;

public final class ArticulatedLimbLayoutTest {
    private static final float EPSILON = 0.00001f;

    public static void main(String[] args) {
        assertClose("arm upper height starts at the true shoulder plane", 5.75f, ArticulatedLimbLayout.armUpperHeight());
        assertClose("arm lower height", 5.75f, ArticulatedLimbLayout.armLowerHeight());
        assertClose("arm bridge span", 0.5f,
                ArticulatedLimbLayout.armLowerBoundaryY() - ArticulatedLimbLayout.armUpperBoundaryY());
        assertClose("rendered arm span matches the vanilla 12px limb", 12.0f,
                ArticulatedLimbLayout.armUpperHeight() + ArticulatedLimbLayout.armLowerHeight()
                        + ArticulatedLimbLayout.JOINT_HALF_BAND * 2.0f);
        assertClose("arm segment overlap", 0.0f, ArticulatedLimbLayout.intervalOverlap(
                ArticulatedLimbLayout.ARM_TOP_Y,
                ArticulatedLimbLayout.armUpperBoundaryY(),
                ArticulatedLimbLayout.armLowerBoundaryY(),
                ArticulatedLimbLayout.ARM_HAND_Y));

        assertClose("leg upper height starts at the true hip plane", 6.0f, ArticulatedLimbLayout.legUpperHeight());
        assertClose("leg lower height", 6.0f, ArticulatedLimbLayout.legLowerHeight());
        assertClose("leg joint continuity",
                ArticulatedLimbLayout.LEG_KNEE_Y,
                ArticulatedLimbLayout.LEG_KNEE_Y + ArticulatedLimbLayout.LOWER_LOCAL_TOP_Y);
        assertClose("rendered leg span matches the vanilla 12px limb", 12.0f,
                ArticulatedLimbLayout.legUpperHeight() + ArticulatedLimbLayout.legLowerHeight());
        assertClose("leg segment overlap", 0.0f, ArticulatedLimbLayout.intervalOverlap(
                ArticulatedLimbLayout.LEG_TOP_Y,
                ArticulatedLimbLayout.LEG_KNEE_Y,
                ArticulatedLimbLayout.LEG_KNEE_Y + ArticulatedLimbLayout.LOWER_LOCAL_TOP_Y,
                ArticulatedLimbLayout.LEG_FOOT_Y));
        assertClose("separate base legs leave a visible center gap", 0.4f,
                ArticulatedLimbLayout.legBaseGap());
        assertClose("separate pants layers preserve a center gap", 0.12f,
                ArticulatedLimbLayout.pantsOuterGap());
        assertClose("base legs never intersect at rest", 0.0f, ArticulatedLimbLayout.intervalOverlap(
                -ArticulatedLimbLayout.LEG_HIP_X - ArticulatedLimbLayout.LEG_WIDTH / 2.0f,
                -ArticulatedLimbLayout.LEG_HIP_X + ArticulatedLimbLayout.LEG_WIDTH / 2.0f,
                ArticulatedLimbLayout.LEG_HIP_X - ArticulatedLimbLayout.LEG_WIDTH / 2.0f,
                ArticulatedLimbLayout.LEG_HIP_X + ArticulatedLimbLayout.LEG_WIDTH / 2.0f));
        assertClose("pants layers never intersect at rest", 0.0f, ArticulatedLimbLayout.intervalOverlap(
                -ArticulatedLimbLayout.LEG_HIP_X - ArticulatedLimbLayout.LEG_WIDTH / 2.0f
                        - ArticulatedLimbLayout.PANTS_LAYER_GROW_X,
                -ArticulatedLimbLayout.LEG_HIP_X + ArticulatedLimbLayout.LEG_WIDTH / 2.0f
                        + ArticulatedLimbLayout.PANTS_LAYER_GROW_X,
                ArticulatedLimbLayout.LEG_HIP_X - ArticulatedLimbLayout.LEG_WIDTH / 2.0f
                        - ArticulatedLimbLayout.PANTS_LAYER_GROW_X,
                ArticulatedLimbLayout.LEG_HIP_X + ArticulatedLimbLayout.LEG_WIDTH / 2.0f
                        + ArticulatedLimbLayout.PANTS_LAYER_GROW_X));
        assertTrue("pants gap remains positive", ArticulatedLimbLayout.pantsOuterGap() > 0.0f);
        assertTrue("pants gap remains narrower than the base gap",
                ArticulatedLimbLayout.pantsOuterGap() < ArticulatedLimbLayout.legBaseGap());
        assertClose("arm starts at the exact shoulder plane", 0.0f,
                ArticulatedLimbLayout.ARM_SHOULDER_OVERLAP);
        assertClose("thigh starts at the exact hip plane", 0.0f,
                ArticulatedLimbLayout.LEG_HIP_OVERLAP);

        float requestedStanceOffset = 0.70f;
        float stanceRoll = ArticulatedLimbLayout.stanceRoll(requestedStanceOffset);
        assertClose("stance offset is reproduced by thigh roll", requestedStanceOffset,
                ArticulatedLimbLayout.LEG_FOOT_Y * (float) Math.sin(stanceRoll));
        assertClose("wrist request cannot rotate the entire forearm", 0.0f,
                ArticulatedLimbLayout.forearmYForTwoHandedGrip(-0.64f));

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
        assertClose("pants preserve vanilla depth", 4.5f,
                ArticulatedLimbLayout.LEG_WIDTH + ArticulatedLimbLayout.PANTS_LAYER_GROW_Z * 2.0f);

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

        assertTrue("continuous bridge uses multiple weighted rings",
                ArticulatedLimbLayout.JOINT_SKINNING_RINGS >= 5);
        assertClose("upper bridge ring belongs completely to upper arm", 0.0f,
                ArticulatedLimbLayout.jointSkinWeight(0.0f));
        assertClose("middle bridge ring blends both bones equally", 0.5f,
                ArticulatedLimbLayout.jointSkinWeight(0.5f));
        assertClose("lower bridge ring belongs completely to forearm", 1.0f,
                ArticulatedLimbLayout.jointSkinWeight(1.0f));
        assertTrue("each layer has sixteen continuous seam quads",
                (ArticulatedLimbLayout.JOINT_SKINNING_RINGS - 1) * 4 == 16);
        assertClose("knee bridge has a protected half-pixel upper band", 5.75f,
                ArticulatedLimbLayout.LEG_UPPER_BOUNDARY_Y);
        assertClose("knee bridge has a protected half-pixel lower band", 6.25f,
                ArticulatedLimbLayout.LEG_LOWER_BOUNDARY_Y);
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
