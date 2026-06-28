package ua.rp.chat.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.CustomHeadLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ua.rp.chat.client.camera.SmartCameraManager;
import ua.rp.chat.client.render.LocalPlayerRenderState;

@Mixin(CustomHeadLayer.class)
public class CustomHeadLayerMixin {
    @Inject(method = "submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;FF)V", at = @At("HEAD"), cancellable = true)
    private void oasis$skipLocalHeadLayer(PoseStack poseStack, SubmitNodeCollector collector, int light, LivingEntityRenderState state, float yRot, float xRot, CallbackInfo ci) {
        if (oasis$isLocalFirstPersonState(state) && SmartCameraManager.getInstance().isFirstPersonBodyEnabled()) {
            ci.cancel();
        }
    }

    @Unique
    private boolean oasis$isLocalFirstPersonState(EntityRenderState state) {
        if (state instanceof LocalPlayerRenderState lprs && lprs.oasis$isLocalPlayer()) {
            return true;
        }
        Minecraft client = Minecraft.getInstance();
        return state instanceof AvatarRenderState avatar
                && client != null
                && client.player != null
                && avatar.id == client.player.getId();
    }
}
