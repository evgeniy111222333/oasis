package ua.rp.chat.client.mixin.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ua.rp.chat.client.render.BreathingTorsoRenderer;
import ua.rp.chat.client.render.ElbowBridgeRenderer;
import ua.rp.chat.client.render.KneeBridgeRenderer;

@Mixin(ModelPart.class)
public class ModelPartRenderMixin {
    @Inject(
            method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;III)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void eclipse$renderDynamicGeometry(
            PoseStack poseStack, VertexConsumer consumer,
            int light, int overlay, int color, CallbackInfo ci) {
        if (BreathingTorsoRenderer.renderIfRegistered(
                (ModelPart) (Object) this, poseStack, consumer, light, overlay, color)
                || ElbowBridgeRenderer.renderIfRegistered(
                (ModelPart) (Object) this, poseStack, consumer, light, overlay, color)
                || KneeBridgeRenderer.renderIfRegistered(
                (ModelPart) (Object) this, poseStack, consumer, light, overlay, color)) {
            ci.cancel();
        }
    }
}
