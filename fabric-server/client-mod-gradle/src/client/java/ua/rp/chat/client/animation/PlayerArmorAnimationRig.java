package ua.rp.chat.client.animation;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import ua.rp.chat.animation.AnimationPose;
import ua.rp.chat.client.render.DistalLimbRenderer;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/** Keeps the existing armor atlas while skinning full cuboids along the player's joints. */
public final class PlayerArmorAnimationRig {
    private static final Map<HumanoidModel<?>, Joints> JOINTS = new WeakHashMap<>();
    private PlayerArmorAnimationRig() {}

    public static void reset(HumanoidModel<?> model) {
        DistalLimbRenderer.clear(model.body);
        DistalLimbRenderer.clear(model.leftArm);
        DistalLimbRenderer.clear(model.rightArm);
        DistalLimbRenderer.clear(model.leftLeg);
        DistalLimbRenderer.clear(model.rightLeg);
    }

    public static void update(HumanoidModel<?> model, AnimationPose pose,
                              PlayerTorsoSkeleton skeleton, boolean action) {
        Joints joints = JOINTS.computeIfAbsent(model, ignored -> new Joints());
        DistalLimbRenderer.updateDeformation(model.body, point -> skeleton.deform(point.x, point.y, point.z));
        update(model.leftArm, joints.leftArm, pose, "left_forearm", "left_hand", null, action);
        update(model.rightArm, joints.rightArm, pose, "right_forearm", "right_hand", null, action);
        update(model.leftLeg, joints.leftLeg, pose, "left_calf", "left_ankle", "left_toe", false);
        update(model.rightLeg, joints.rightLeg, pose, "right_calf", "right_ankle", "right_toe", false);
    }

    public static void applyCarryArms(HumanoidModel<?> model,
            ua.rp.chat.client.carry.CarryPoseMath.Pose pose, float weight) {
        Joints joints = JOINTS.computeIfAbsent(model, ignored -> new Joints());
        carryArm(model.leftArm, joints.leftArm, pose.left(), weight);
        carryArm(model.rightArm, joints.rightArm, pose.right(), weight);
    }

    private static void carryArm(ModelPart arm, Chain chain,
            ua.rp.chat.client.carry.CarryPoseMath.Arm target, float weight) {
        if (!target.active()) return;
        chain.joint.xRot = ua.rp.chat.client.carry.CarryPoseRenderer.lerp(chain.joint.xRot, target.elbow(), weight);
        chain.joint.yRot *= 1 - weight;
        chain.joint.zRot *= 1 - weight;
        chain.distal.xRot *= 1 - weight;
        chain.distal.yRot = ua.rp.chat.client.carry.CarryPoseRenderer.lerp(chain.distal.yRot, target.wrist(), weight);
        chain.distal.zRot *= 1 - weight;
        DistalLimbRenderer.updateChain(arm, chain.joint, chain.distal, null);
    }

    private static void update(ModelPart segment, Chain chain, AnimationPose pose,
                               String joint, String distal, String toe, boolean action) {
        rotate(chain.joint, action ? new AnimationPose.Vector(0,0,0) : pose.rotation(joint));
        rotate(chain.distal, action ? new AnimationPose.Vector(0,0,0) : pose.rotation(distal));
        if (chain.toe != null) rotate(chain.toe, pose.rotation(toe));
        DistalLimbRenderer.updateChain(segment, chain.joint, chain.distal, chain.toe);
    }

    private static void rotate(ModelPart bone, AnimationPose.Vector v) {
        float r = (float) (Math.PI / 180);
        bone.setRotation(v.x() * r, v.y() * r, v.z() * r);
    }

    private static ModelPart marker(float x, float y, float z) {
        ModelPart part = new ModelPart(List.of(), Map.of());
        part.setPos(x, y, z);
        return part;
    }

    private static final class Chain {
        final ModelPart joint, distal, toe;
        Chain(boolean arm) {
            joint = marker(0, arm ? 4 : 6, 0);
            distal = marker(0, 4, 0);
            toe = arm ? null : marker(0, 1, -1);
        }
    }
    private static final class Joints {
        final Chain leftArm = new Chain(true), rightArm = new Chain(true);
        final Chain leftLeg = new Chain(false), rightLeg = new Chain(false);
    }
}
