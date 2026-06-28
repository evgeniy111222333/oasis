package ua.rp.chat.client.mixin;

import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ua.rp.chat.client.camera.SmartCameraManager;

@Mixin(LevelRenderer.class)
public class LevelRendererMixin {
    @Inject(method = "extractLevel", at = @At("HEAD"))
    private void oasis$beforeExtractLevel(DeltaTracker deltaTracker, Camera camera, float partialTick, CallbackInfo ci) {
        if (SmartCameraManager.getInstance().isFirstPersonBodyEnabled()) {
            SmartCameraManager.getInstance().setRenderingFirstPersonPlayer(true);
        }
    }

    @Inject(method = "extractLevel", at = @At("RETURN"))
    private void oasis$afterExtractLevel(DeltaTracker deltaTracker, Camera camera, float partialTick, CallbackInfo ci) {
        SmartCameraManager.getInstance().setRenderingFirstPersonPlayer(false);
    }
}
