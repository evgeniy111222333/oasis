package ua.rp.chat.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.level.BlockOutlineRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ua.rp.chat.client.camera.SmartCameraManager;
import ua.rp.chat.client.microvoxel.MicrovoxelClientState;
import ua.rp.chat.client.microvoxel.MicrovoxelInteractionController;

@Mixin(LevelRenderer.class)
public class LevelRendererMixin {
    @Inject(method = "extractLevel", at = @At("HEAD"))
    private void eclipse$beforeExtractLevel(DeltaTracker deltaTracker, Camera camera, float partialTick, CallbackInfo ci) {
        SmartCameraManager cameraManager = SmartCameraManager.getInstance();
        cameraManager.setRenderingFirstPersonPlayer(cameraManager.isFullBodyFirstPersonEnabled());
    }

    @Inject(method = "renderLevel", at = @At("RETURN"))
    private void eclipse$afterRender(CallbackInfo ci) {
        SmartCameraManager.getInstance().setRenderingFirstPersonPlayer(false);
    }

    @Inject(method = "renderHitOutline", at = @At("HEAD"))
    private void eclipse$stonemasonChalk(PoseStack poseStack, VertexConsumer vertexConsumer,
                                         double camX, double camY, double camZ,
                                         BlockOutlineRenderState outlineState, int light, float partialTick) {
        ua.rp.chat.client.stonemason.StonemasonChalkOverlay.render();
    }

    @Inject(method = "renderHitOutline", at = @At("HEAD"), cancellable = true)
    private void eclipse$cancelMicrovoxelOutline(PoseStack poseStack, VertexConsumer vertexConsumer,
                                                 double camX, double camY, double camZ,
                                                 BlockOutlineRenderState outlineState, int light, float partialTick,
                                                 CallbackInfo ci) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.hitResult instanceof BlockHitResult blockHit) {
            BlockPos blockPos = blockHit.getBlockPos();
            if (MicrovoxelInteractionController.editing() && 
                (MicrovoxelClientState.get(blockPos) != null || 
                 (MicrovoxelInteractionController.currentStandardTarget() != null && 
                  MicrovoxelInteractionController.currentStandardTarget().position().equals(blockPos)))) {
                ci.cancel();
            }
        }
    }
}
