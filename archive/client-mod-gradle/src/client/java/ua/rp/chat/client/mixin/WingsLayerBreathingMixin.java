package ua.rp.chat.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.WingsLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ua.rp.chat.client.render.BreathingAttachmentOffset;

/** Moves elytra with the same upper-back expansion used by the player skin. */
@Mixin(WingsLayer.class)
public class WingsLayerBreathingMixin {
    @Inject(
            method = "submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/client/renderer/entity/state/HumanoidRenderState;FF)V",
            at = @At("HEAD")
    )
    private void eclipse$pushBreathingBackOffset(
            PoseStack poseStack, SubmitNodeCollector collector, int light,
            HumanoidRenderState state, float yRot, float xRot, CallbackInfo ci) {
        poseStack.pushPose();
        if (state instanceof AvatarRenderState avatar) {
            poseStack.translate(0.0f, 0.0f, BreathingAttachmentOffset.backOffsetPixels(avatar) / 16.0f);
        }
    }

    @Inject(
            method = "submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/client/renderer/entity/state/HumanoidRenderState;FF)V",
            at = @At("RETURN")
    )
    private void eclipse$popBreathingBackOffset(
            PoseStack poseStack, SubmitNodeCollector collector, int light,
            HumanoidRenderState state, float yRot, float xRot, CallbackInfo ci) {
        poseStack.popPose();
    }
}
