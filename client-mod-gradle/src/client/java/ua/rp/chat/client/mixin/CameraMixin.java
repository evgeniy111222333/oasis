package ua.rp.chat.client.mixin;

import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ua.rp.chat.client.camera.SmartCameraManager;

@Mixin(Camera.class)
public abstract class CameraMixin {
    @Shadow private Vec3 position;
    @Shadow private float xRot;
    @Shadow private float yRot;
    @Shadow private boolean detached;
    @Shadow private Entity entity;

    @Shadow public abstract float getCameraEntityPartialTicks(DeltaTracker deltaTracker);

    @Inject(method = "update", at = @At("RETURN"))
    private void oasis$afterUpdate(DeltaTracker deltaTracker, CallbackInfo ci) {
        if (this.entity instanceof Player player && SmartCameraManager.getInstance().isCameraMotionActiveFor(player) && !this.detached) {
            float partialTick = this.getCameraEntityPartialTicks(deltaTracker);
            SmartCameraManager.getInstance().updatePhysics(player, partialTick);
            double stabilizedY = SmartCameraManager.getInstance().getStabilizedY(player, this.position.y, partialTick);
            Vec3 offset = SmartCameraManager.getInstance().getCameraOffset(this.yRot, this.xRot);
            this.position = new Vec3(this.position.x + offset.x, stabilizedY + offset.y, this.position.z + offset.z);
        }
    }

    @Inject(method = "isDetached", at = @At("HEAD"), cancellable = true)
    private void oasis$isDetached(CallbackInfoReturnable<Boolean> cir) {
        if (SmartCameraManager.getInstance().isWorldFirstPersonBodyRender()) {
            cir.setReturnValue(true);
        }
    }
}
