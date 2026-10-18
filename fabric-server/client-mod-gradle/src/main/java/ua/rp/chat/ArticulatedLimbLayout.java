package ua.rp.chat;

/**
 * Shared geometry contract for the segmented player limbs.
 *
 * <p>The upper and lower segments meet at one plane. Wearable layers grow only
 * across the limb, never along it, so bending cannot expose overlapping end
 * caps or a duplicated joint block.</p>
 */
public final class ArticulatedLimbLayout {
    // Vanilla limbs meet the torso exactly at the shoulder/hip plane.  The old
    // overlap hid gaps at rest but made skin and outer layers physically occupy
    // the same pixels during turns, which is visible as texture bleeding.
    public static final float ARM_SHOULDER_OVERLAP = 0.0f;
    public static final float ARM_TOP_Y = -2.0f - ARM_SHOULDER_OVERLAP;
    public static final float ARM_ELBOW_Y = 4.0f;
    public static final float ARM_HAND_Y = 10.0f;

    public static final float LEG_HIP_OVERLAP = 0.0f;
    public static final float LEG_TOP_Y = -LEG_HIP_OVERLAP;
    public static final float LEG_KNEE_Y = 6.0f;
    public static final float LEG_FOOT_Y = 12.0f;
    public static final float LEG_WIDTH = 4.0f;
    public static final float LEG_HIP_X = 2.2f;

    public static final float JOINT_HALF_BAND = 0.25f;
    public static final int JOINT_SKINNING_RINGS = 5;
    public static final float ARM_LOWER_LOCAL_TOP_Y = JOINT_HALF_BAND;
    public static final float LOWER_LOCAL_TOP_Y = 0.0f;
    public static final float LEG_UPPER_BOUNDARY_Y = LEG_KNEE_Y - JOINT_HALF_BAND;
    public static final float LEG_LOWER_BOUNDARY_Y = LEG_KNEE_Y + JOINT_HALF_BAND;
    public static final float OUTER_LAYER_GROW_XZ = 0.25f;
    public static final float OUTER_LAYER_GROW_Y = 0.0f;
    /**
     * Shoulder cover: the upper sleeve reaches this much above the shoulder pivot
     * into the torso, so a raised arm (attack, work pose) never opens the butt
     * joint into a gaping hole. Vanilla sleeves overlap the same way; the zero
     * overlap elsewhere stays to avoid elbow/knee intersections.
     */
    public static final float SHOULDER_COVER = 0.5f;
    /** Base arm cover stays buried inside the sleeve cover. */
    public static final float BASE_SHOULDER_COVER = 0.25f;
    /**
     * Hip cover: thigh and pants tops reach above the hip pivot into the torso,
     * so torso bobbing (breathing, work dips) never opens the butt joint.
     */
    public static final float HIP_COVER = 0.25f;
    /**
     * Cuff step at the wrist/ankle: the wearable ends this much short of the hand
     * so its trim rows never wrap under the end plane. The elbow/knee keep the zero
     * gap so bending never opens a seam.
     */
    public static final float WRIST_SHORTEN = 0.25f;
    /** Sub-texel UV inset pulling cap samples off exact texel borders. */
    public static final float CAP_UV_EPSILON = 0.0005f;
    public static final float PANTS_LAYER_GROW_X = 0.14f;
    public static final float PANTS_LAYER_GROW_Z = OUTER_LAYER_GROW_XZ;


    public static final int LOWER_SEGMENT_TEXTURE_ROW_OFFSET = 6;
    public static final int SKIN_TEXTURE_HEIGHT = 64;

    public static final int ORIGINAL_CAP_V_SHIFT_PIXELS = -6;
    // A segmented cuboid starts six rows lower, but Minecraft still expects
    // both end caps in the original cap strip. Sampling the side strip here
    // produced the conspicuous wrong hand/foot pixels visible from below.
    public static final int HAND_TOP_CAP_V_SHIFT_PIXELS = ORIGINAL_CAP_V_SHIFT_PIXELS;
    public static final int HAND_BOTTOM_CAP_V_SHIFT_PIXELS = ORIGINAL_CAP_V_SHIFT_PIXELS;

    private ArticulatedLimbLayout() {
    }

    public static float armUpperHeight() {
        return ARM_ELBOW_Y - JOINT_HALF_BAND - ARM_TOP_Y;
    }

    public static float armLowerHeight() {
        return ARM_HAND_Y - ARM_ELBOW_Y - JOINT_HALF_BAND;
    }

    public static float armUpperBoundaryY() {
        return ARM_ELBOW_Y - JOINT_HALF_BAND;
    }

    public static float armLowerBoundaryY() {
        return ARM_ELBOW_Y + JOINT_HALF_BAND;
    }

    public static float jointSkinWeight(float t) {
        return t * t * (3.0f - 2.0f * t);
    }

    public static float legUpperHeight() {
        return LEG_KNEE_Y - LEG_TOP_Y;
    }

    public static float legLowerHeight() {
        return LEG_FOOT_Y - LEG_KNEE_Y;
    }

    public static float legBaseGap() {
        return LEG_HIP_X * 2.0f - LEG_WIDTH;
    }

    public static float pantsOuterGap() {
        return LEG_HIP_X * 2.0f - (LEG_WIDTH + PANTS_LAYER_GROW_X * 2.0f);
    }

    /**
     * Переводит требуемое расширение стойки в наклон бедра, не отрывая hip-pivot от таза.
     */
    public static float stanceRoll(float requestedRootOffset) {
        float sine = requestedRootOffset / LEG_FOOT_Y;
        return (float) Math.asin(Math.max(-0.95f, Math.min(0.95f, sine)));
    }

    /** Фактическое расстояние между центрами стоп при неподвижных тазобедренных шарнирах. */
    public static float footCenterSeparation(float rightRoll, float leftRoll, float requestedRootOffset) {
        float stanceRoll = stanceRoll(requestedRootOffset);
        return LEG_HIP_X * 2.0f + LEG_FOOT_Y * ((float) Math.sin(rightRoll + stanceRoll)
                - (float) Math.sin(leftRoll - stanceRoll));
    }

    /**
     * До появления отдельной кости кисти twist нельзя переносить на всё предплечье:
     * порядок Euler-вращений сдвигает ладонь с точки, которую решил IK.
     */
    public static float forearmYForTwoHandedGrip(float requestedWristTwist) {
        return 0.0f;
    }

    public static float normalizedVShift(int pixels) {
        return (float) pixels / SKIN_TEXTURE_HEIGHT;
    }

    /**
     * Pulls one UV coordinate a hair inside its quad span so bilinear filtering and
     * mipmaps never sample the neighbouring row: the hand/foot end caps stop
     * drinking the sleeve trim colour. Pure.
     */
    public static float insetUv(float u, float minU, float maxU) {
        if (!(maxU > minU)) return u;
        if (u <= (minU + maxU) * 0.5f) return Math.min(maxU, u + CAP_UV_EPSILON);
        return Math.max(minU, u - CAP_UV_EPSILON);
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
