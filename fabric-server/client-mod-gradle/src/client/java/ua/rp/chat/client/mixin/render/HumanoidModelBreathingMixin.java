package ua.rp.chat.client.mixin.render;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ua.rp.chat.BreathingTorsoLayout;
import ua.rp.chat.BreathingShoulderLayout;
import ua.rp.chat.client.render.BreathingPoseState;

/** Gives rigid chest armor a restrained version of the shared respiratory pose. */
@Mixin(HumanoidModel.class)
public class HumanoidModelBreathingMixin {
    @Inject(
            method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/HumanoidRenderState;)V",
            at = @At("RETURN")
    )
    private void eclipse$applyArmorBreathing(HumanoidRenderState state, CallbackInfo ci) {
        if ((Object) this instanceof PlayerModel || !(state instanceof AvatarRenderState avatar)
                || avatar.isFallFlying || avatar.isVisuallySwimming || avatar.isPassenger) {
            return;
        }

        HumanoidModel<?> model = (HumanoidModel<?>) (Object) this;
        BreathingPoseState.Sample sample = BreathingPoseState.sample(avatar);
        var respiration = sample.respiration();
        float amplitude = BreathingTorsoLayout.amplitude(
                respiration.intensity(), sample.calm(), sample.firstPerson());
        float upperBreath = BreathingTorsoLayout.regionalBreath(respiration.phase(), 0.82f);
        float effort = (float) respiration.intensity();
        BreathingShoulderLayout.Pose shoulder = BreathingShoulderLayout.pose(
                respiration.phase(), respiration.intensity(), sample.calm(), sample.firstPerson());

        // Armor remains structurally rigid, but its silhouette follows the ribs
        // enough to remain visible and to contain the more detailed skin mesh.
        model.body.xScale *= 1.0f + upperBreath * amplitude * 0.055f;
        model.body.zScale *= 1.0f + upperBreath * amplitude * 0.100f;
        model.body.xRot += upperBreath * amplitude * (0.010f + effort * 0.008f);
        model.leftArm.x += shoulder.rootOutPixels();
        model.rightArm.x -= shoulder.rootOutPixels();
        model.leftArm.y -= shoulder.liftPixels();
        model.rightArm.y -= shoulder.liftPixels();
        model.leftArm.xRot += shoulder.forwardPitchRadians();
        model.rightArm.xRot += shoulder.forwardPitchRadians();
        model.leftArm.zRot -= shoulder.outwardRollRadians();
        model.rightArm.zRot += shoulder.outwardRollRadians();
    }
}
