package ua.rp.chat.client.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ua.rp.chat.BreathingTorsoLayout;
import ua.rp.chat.RespirationModel;
import ua.rp.chat.client.camera.RespirationController;
import ua.rp.chat.client.camera.SmartCameraManager;

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
        Minecraft client = Minecraft.getInstance();
        boolean localPlayer = client != null && client.player != null && avatar.id == client.player.getId();
        RespirationModel.Snapshot respiration = localPlayer
                ? RespirationController.getInstance().sampleFrame()
                : RespirationController.getInstance().sampleRemote(avatar.ageInTicks, avatar.id);
        float moving = clamp(avatar.walkAnimationSpeed * 3.2f, 0.0f, 1.0f);
        float calm = clamp(1.0f - moving * 0.48f, 0.42f, 1.0f);
        boolean firstPerson = localPlayer
                && SmartCameraManager.getInstance().shouldApplyFirstPersonBodyPose();
        float amplitude = BreathingTorsoLayout.amplitude(
                respiration.intensity(), calm, firstPerson);
        float upperBreath = BreathingTorsoLayout.regionalBreath(respiration.phase(), 0.82f);
        float effort = (float) respiration.intensity();

        // Armor remains structurally rigid, but its silhouette follows the ribs
        // enough to remain visible and to contain the more detailed skin mesh.
        model.body.xScale *= 1.0f + upperBreath * amplitude * 0.055f;
        model.body.zScale *= 1.0f + upperBreath * amplitude * 0.100f;
        model.body.xRot += upperBreath * amplitude * (0.010f + effort * 0.008f);
        float shoulderFollow = upperBreath * amplitude * (0.006f + effort * 0.006f);
        model.leftArm.xRot += shoulderFollow;
        model.rightArm.xRot += shoulderFollow;
        model.leftArm.zRot += shoulderFollow * 0.30f;
        model.rightArm.zRot -= shoulderFollow * 0.30f;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
