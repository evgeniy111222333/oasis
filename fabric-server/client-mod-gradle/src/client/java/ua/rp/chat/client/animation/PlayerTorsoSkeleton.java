package ua.rp.chat.client.animation;

import org.joml.Quaternionf;
import org.joml.Vector3f;
import ua.rp.chat.animation.AnimationPose;

/** Rest-space skinning for the pelvis/spine/chest chain of the native 12px torso. */
public final class PlayerTorsoSkeleton {
    private static final String[] SIDES = {"left", "right"};
    private static final float[] SOLE_Y = {3.5f, 4, 4.5f, 6};
    private static final float[] SOLE_Z = {-2, -1.5f, -1, -.5f, 2};
    private static final float[] SOLE_X = {-2, 2};
    public final Vector3f hip, spine, chest, neck;
    public final Quaternionf pelvisRotation, spineRotation, chestRotation;
    private final Quaternionf inverseChest;
    private float groundLiftPixels;

    public PlayerTorsoSkeleton(AnimationPose pose) {
        var p = pose.position("center_of_mass");
        hip = new Vector3f(p.x(), 12 - p.y(), p.z());
        pelvisRotation = rotation(pose.rotation("pelvis"));
        spine = offset(hip, pelvisRotation, 0, -4, 0);
        spineRotation = new Quaternionf(pelvisRotation).mul(rotation(pose.rotation("spine_lower")));
        chest = offset(spine, spineRotation, 0, -4, 0);
        chestRotation = new Quaternionf(spineRotation).mul(rotation(pose.rotation("chest")));
        neck = offset(chest, chestRotation, 0, -4, 0);
        inverseChest = new Quaternionf(chestRotation).conjugate();
    }

    public static Quaternionf rotation(AnimationPose.Vector v) {
        float degrees = (float) (Math.PI / 180);
        return new Quaternionf().rotationZYX(v.z() * degrees, v.y() * degrees, v.x() * degrees);
    }

    public static Vector3f offset(Vector3f pivot, Quaternionf rotation, float x, float y, float z) {
        return rotation.transform(new Vector3f(x, y, z)).add(pivot);
    }

    public float groundLiftPixels() { return groundLiftPixels; }

    public static float floorPixels(boolean crouching) {
        // LivingEntityRenderer translates by -1.501, then AvatarRenderer scales the
        // model by .9375 and adds a separate -2/16 world offset while crouching.
        return 1.501f * 16 - (crouching ? 2 / .9375f : 0);
    }

    /** Retain authored flight/bob, but never allow the grounded sole to enter a block. */
    public void groundFootContacts(AnimationPose pose, boolean crouching) {
        float lift = Math.max(0, lowestFootPixels(pose) - floorPixels(crouching));
        hip.y -= lift;
        spine.y -= lift;
        chest.y -= lift;
        neck.y -= lift;
        groundLiftPixels += lift;
    }

    /** Maximum native Y of the actual tessellated sole, including ankle and toe skinning. */
    public float lowestFootPixels(AnimationPose pose) {
        float lowest = -Float.MAX_VALUE;
        Vector3f point = new Vector3f();
        Quaternionf weighted = new Quaternionf();
        for (String side : SIDES) {
            float x = side.equals("left") ? 2.2f : -2.2f;
            Vector3f origin = offset(hip, pelvisRotation, x, 0, 0);
            Quaternionf upper = new Quaternionf(pelvisRotation).mul(rotation(pose.rotation(side + "_leg")));
            Quaternionf calf = rotation(pose.rotation(side + "_calf"));
            Quaternionf ankle = rotation(pose.rotation(side + "_ankle"));
            Quaternionf toe = rotation(pose.rotation(side + "_toe"));
            for (float y : SOLE_Y) {
                float ankleWeight = smooth(y - 3.5f);
                for (float z : SOLE_Z) {
                    float toeWeight = ankleWeight * smooth(-.5f - z);
                    for (float localX : SOLE_X) {
                        point.set(localX, y - 5, z + 1);
                        weighted.identity().slerp(toe, toeWeight).transform(point);
                        point.add(0, 1, -1); // toe pivot (0,5,-1), expressed around ankle (0,4,0)
                        weighted.identity().slerp(ankle, ankleWeight).transform(point);
                        point.add(0, 4, 0);
                        calf.transform(point).add(0, 6, 0);
                        upper.transform(point).add(origin);
                        lowest = Math.max(lowest, point.y);
                    }
                }
            }
        }
        return lowest;
    }

    /** Returns a deformed point in body-local pixels; breathing can expand the rest point first. */
    public Vector3f deform(float x, float y, float z) {
        Vector3f result;
        if (y <= 4) {
            result = offset(chest, chestRotation, x, y - 4, z);
        } else if (y <= 8) {
            float t = smooth((y - 4) / 4);
            result = offset(chest, chestRotation, x, y - 4, z)
                    .lerp(offset(spine, spineRotation, x, y - 8, z), t);
        } else {
            float t = smooth((y - 8) / 4);
            result = offset(spine, spineRotation, x, y - 8, z)
                    .lerp(offset(hip, pelvisRotation, x, y - 12, z), t);
        }
        return inverseChest.transform(result.sub(neck));
    }

    private static float smooth(float value) {
        float t = Math.max(0, Math.min(1, value));
        return t * t * (3 - 2 * t);
    }
}
