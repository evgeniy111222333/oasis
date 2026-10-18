package ua.rp.chat.client.carry;

import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.Player;
import ua.rp.chat.client.animation.PlayerArmorAnimationRig;

/** Runs after authored locomotion, breathing and work poses; modifies arms only. */
public final class CarryPoseRenderer {
    private CarryPoseRenderer() {}
    public static void alignBody(Player player, AvatarRenderState state, float partialTick) {
        var sample = CarryClientState.pose(player);
        if (sample == null || !sample.held().large()) return;
        float look = net.minecraft.util.Mth.rotLerp(partialTick, player.yRotO, player.getYRot());
        float delta = net.minecraft.util.Mth.wrapDegrees(look - state.bodyRot) * sample.weight();
        state.bodyRot += delta;
        state.yRot -= delta;
    }
    public static void apply(HumanoidModel<?> model, AvatarRenderState state) {
        var client = Minecraft.getInstance();
        if (client.level == null || !(client.level.getEntity(state.id) instanceof Player player)) return;
        var sample = CarryClientState.pose(player);
        if (sample == null || state.isSpectator) return;
        var held = sample.held();
        if (!held.large() && (state.isUsingItem || state.attackTime > .001f)) return;
        var targets = CarryPoseMath.sample(held.large(), player.getMainArm() == HumanoidArm.LEFT,
                held.showing(), CarryClient.seconds());
        applyArm(model.leftArm, targets.left(), sample.weight());
        applyArm(model.rightArm, targets.right(), sample.weight());
        if (!(model instanceof PlayerModel)) PlayerArmorAnimationRig.applyCarryArms(model, targets, sample.weight());
    }
    public static void applyArm(ModelPart arm, CarryPoseMath.Arm target, float weight) {
        if (!target.active()) return;
        arm.xRot = lerp(arm.xRot, target.pitch(), weight);
        arm.yRot = lerp(arm.yRot, target.yaw(), weight);
        arm.zRot = lerp(arm.zRot, target.roll(), weight);
        if (arm.hasChild("eclipse_forearm")) {
            ModelPart forearm = arm.getChild("eclipse_forearm");
            forearm.xRot = lerp(forearm.xRot, target.elbow(), weight);
            forearm.yRot = lerp(forearm.yRot, 0, weight);
            forearm.zRot = lerp(forearm.zRot, 0, weight);
            if (forearm.hasChild("eclipse_hand")) {
                ModelPart hand = forearm.getChild("eclipse_hand");
                hand.xRot = lerp(hand.xRot, 0, weight);
                hand.yRot = lerp(hand.yRot, target.wrist(), weight);
                hand.zRot = lerp(hand.zRot, 0, weight);
            }
            // The lower sleeve is already a child of the forearm; copying the
            // parent's transform into it would apply the elbow bend twice.
        }
    }
    public static float lerp(float from, float to, float weight) { return from + (to - from) * Math.clamp(weight, 0, 1); }
}
