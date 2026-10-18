package ua.rp.chat.client.animation;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import ua.rp.chat.ArticulatedLimbLayout;
import ua.rp.chat.animation.AnimationPose;
import ua.rp.chat.client.render.BreathingTorsoRenderer;

/** Retargets every authored bone to the existing player skin and articulated limb hierarchy. */
public final class PlayerAnimationRig {
    private PlayerAnimationRig() {}

    public static boolean active(AvatarRenderState state) {
        return state instanceof PlayerAnimationRenderState animated
                && animated.eclipse$animationFrame() != null
                && animated.eclipse$animationFrame().active()
                && !state.isPassenger && !state.isFallFlying && !state.isVisuallySwimming
                && !state.isAutoSpinAttack
                && state.deathTime <= 0 && state.bedOrientation == null && !state.isSpectator;
    }

    public static boolean apply(PlayerModel model, AvatarRenderState state) {
        ModelPart marker = child(model.body, "eclipse_breathing_torso");
        if (marker == null) {
            // 26.1.2 also constructs PlayerModel for full-cuboid armor mesh sets.
            // These need the original-UV armor skinner rather than skin-only child bones.
            if (!active(state)) PlayerArmorAnimationRig.reset(model);
            return applyArmor(model, state);
        }
        BreathingTorsoRenderer.setSkeleton(marker, null);
        if (!active(state)) return false;
        AnimationPose pose = ((PlayerAnimationRenderState) state).eclipse$animationFrame().pose();
        PlayerTorsoSkeleton skeleton = applyBase(model, state, pose);
        BreathingTorsoRenderer.setSkeleton(marker, skeleton);
        if (!action(state)) {
            limb(model.leftArm, pose, "left_forearm", "left_hand", true);
            limb(model.rightArm, pose, "right_forearm", "right_hand", true);
        }
        limb(model.leftLeg, pose, "left_calf", "left_ankle", false);
        limb(model.rightLeg, pose, "right_calf", "right_ankle", false);
        return true;
    }

    /** Armor keeps its texture and uses the same joints as the skin. */
    public static boolean applyArmor(HumanoidModel<?> model, AvatarRenderState state) {
        if (!active(state)) return false;
        AnimationPose pose = ((PlayerAnimationRenderState) state).eclipse$animationFrame().pose();
        PlayerTorsoSkeleton skeleton = applyBase(model, state, pose);
        PlayerArmorAnimationRig.update(model, pose, skeleton, action(state));
        return true;
    }

    private static PlayerTorsoSkeleton applyBase(HumanoidModel<?> model, AvatarRenderState state, AnimationPose pose) {
        Quaternionf look = new Quaternionf().rotationZYX(model.head.zRot, model.head.yRot, model.head.xRot);
        Quaternionf vanillaLeft = new Quaternionf().rotationZYX(model.leftArm.zRot, model.leftArm.yRot, model.leftArm.xRot);
        Quaternionf vanillaRight = new Quaternionf().rotationZYX(model.rightArm.zRot, model.rightArm.yRot, model.rightArm.xRot);
        // Skin and all four armor layers share one evaluated skeleton and foot-contact solve.
        PlayerTorsoSkeleton s = ((PlayerAnimationRenderState) state).eclipse$animationSkeleton(state.isCrouching);
        position(model.body, s.neck);
        rotation(model.body, s.chestRotation);
        position(model.head, s.neck);
        rotation(model.head, new Quaternionf(s.chestRotation)
                .mul(PlayerTorsoSkeleton.rotation(pose.rotation("neck")))
                .mul(PlayerTorsoSkeleton.rotation(pose.rotation("head"))).mul(look));
        leg(model.leftLeg, s, pose, "left_leg", ArticulatedLimbLayout.LEG_HIP_X);
        leg(model.rightLeg, s, pose, "right_leg", -ArticulatedLimbLayout.LEG_HIP_X);
        arm(model.leftArm, s, pose, "left", 5, action(state) ? vanillaLeft : null);
        arm(model.rightArm, s, pose, "right", -5, action(state) ? vanillaRight : null);
        return s;
    }

    private static boolean action(AvatarRenderState state) {
        return state.isUsingItem || state.attackTime > .001f
                || state.leftArmPose == HumanoidModel.ArmPose.CROSSBOW_HOLD
                || state.rightArmPose == HumanoidModel.ArmPose.CROSSBOW_HOLD;
    }

    private static void leg(ModelPart leg, PlayerTorsoSkeleton s, AnimationPose pose, String bone, float x) {
        position(leg, PlayerTorsoSkeleton.offset(s.hip, s.pelvisRotation, x, 0, 0));
        rotation(leg, new Quaternionf(s.pelvisRotation).mul(PlayerTorsoSkeleton.rotation(pose.rotation(bone))));
    }

    private static void arm(ModelPart arm, PlayerTorsoSkeleton s, AnimationPose pose,
                            String side, float x, Quaternionf action) {
        Quaternionf shoulder = new Quaternionf(s.chestRotation)
                .mul(PlayerTorsoSkeleton.rotation(pose.rotation(side + "_clavicle")));
        Vector3f shoulderCenter = PlayerTorsoSkeleton.offset(s.neck, s.chestRotation, 0, 2, 0);
        position(arm, PlayerTorsoSkeleton.offset(shoulderCenter, shoulder, x, 0, 0));
        rotation(arm, action == null
                ? shoulder.mul(PlayerTorsoSkeleton.rotation(pose.rotation(side + "_arm")))
                : action);
    }

    private static void limb(ModelPart root, AnimationPose pose, String lowerBone, String distalBone, boolean arm) {
        ModelPart lower = child(root, arm ? "eclipse_forearm" : "eclipse_shin");
        if (lower == null) return;
        rotation(lower, PlayerTorsoSkeleton.rotation(pose.rotation(lowerBone)));
        ModelPart distal = child(lower, arm ? "eclipse_hand" : "eclipse_ankle");
        if (distal == null) return;
        rotation(distal, PlayerTorsoSkeleton.rotation(pose.rotation(distalBone)));
        if (!arm) {
            ModelPart toe = child(distal, "eclipse_toe");
            if (toe != null) rotation(toe, PlayerTorsoSkeleton.rotation(pose.rotation(distalBone.replace("ankle", "toe"))));
        }
    }

    public static ModelPart child(ModelPart parent, String name) {
        return parent != null && parent.hasChild(name) ? parent.getChild(name) : null;
    }

    private static void position(ModelPart part, Vector3f value) { part.setPos(value.x, value.y, value.z); }
    private static void rotation(ModelPart part, Quaternionf rotation) {
        Vector3f r = rotation.getEulerAnglesZYX(new Vector3f());
        part.setRotation(r.x, r.y, r.z);
    }
}
