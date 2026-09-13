package ua.rp.chat.client.mixin.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ua.rp.chat.client.camera.SmartCameraManager;

@Mixin(ItemInHandRenderer.class)
public class ItemInHandRendererMixin {
    @Inject(method = "renderHandsWithItems", at = @At("HEAD"), cancellable = true)
    private void eclipse$cancelFirstPersonHands(float partialTick, PoseStack poseStack, SubmitNodeCollector collector, LocalPlayer player, int light, CallbackInfo ci) {
        try {
            if (ua.rp.chat.client.carver.CarverClientState.working()) {
                ci.cancel();
                return;
            }
        } catch (RuntimeException ignored) {
        }
        if (SmartCameraManager.getInstance().isFullBodyFirstPersonEnabled()) {
            ci.cancel();
        }
    }
}
