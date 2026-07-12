package ua.rp.chat;

/**
 * Shared geometry contract for the segmented player limbs.
 *
 * <p>The upper and lower segments meet at one plane. Wearable layers grow only
 * across the limb, never along it, so bending cannot expose overlapping end
 * caps or a duplicated joint block.</p>
 */
public final class ArticulatedLimbLayout {
    public static final float ARM_TOP_Y = -2.0f;
    public static final float ARM_ELBOW_Y = 4.0f;
    public static final float ARM_HAND_Y = 10.0f;

    public static final float LEG_TOP_Y = 0.0f;
    public static final float LEG_KNEE_Y = 6.0f;
    public static final float LEG_FOOT_Y = 12.0f;

    public static final float LOWER_LOCAL_TOP_Y = 0.0f;
    public static final float OUTER_LAYER_GROW_XZ = 0.25f;
    public static final float OUTER_LAYER_GROW_Y = 0.0f;

    public static final int LOWER_SEGMENT_TEXTURE_ROW_OFFSET = 6;
    public static final int SKIN_TEXTURE_HEIGHT = 64;

    private ArticulatedLimbLayout() {
    }

    public static float armUpperHeight() {
        return ARM_ELBOW_Y - ARM_TOP_Y;
    }

    public static float armLowerHeight() {
        return ARM_HAND_Y - ARM_ELBOW_Y;
    }

    public static float legUpperHeight() {
        return LEG_KNEE_Y - LEG_TOP_Y;
    }

    public static float legLowerHeight() {
        return LEG_FOOT_Y - LEG_KNEE_Y;
    }

    public static float lowerEndCapsVShift() {
        return -(float) LOWER_SEGMENT_TEXTURE_ROW_OFFSET / SKIN_TEXTURE_HEIGHT;
    }

    public static float intervalOverlap(float firstMin, float firstMax, float secondMin, float secondMax) {
        return Math.max(0.0f, Math.min(firstMax, secondMax) - Math.max(firstMin, secondMin));
    }

    public static int correctedLowerEndCapRow(int lowerSegmentRow) {
        return lowerSegmentRow - LOWER_SEGMENT_TEXTURE_ROW_OFFSET;
    }

    public static float rotatedHandY(float forearmXRotation, float originalHandZ) {
        float relativeY = ARM_HAND_Y - ARM_ELBOW_Y;
        return ARM_ELBOW_Y
                + relativeY * (float) Math.cos(forearmXRotation)
                - originalHandZ * (float) Math.sin(forearmXRotation);
    }

    public static float rotatedHandZ(float forearmXRotation, float originalHandZ) {
        float relativeY = ARM_HAND_Y - ARM_ELBOW_Y;
        return relativeY * (float) Math.sin(forearmXRotation)
                + originalHandZ * (float) Math.cos(forearmXRotation);
    }
}
