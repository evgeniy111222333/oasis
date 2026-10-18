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
    @Shadow public abstract float getFov();
    @Shadow public abstract Camera.NearPlane getNearPlane(float fov);
    @Shadow protected abstract void setPosition(Vec3 position);
    @Shadow protected abstract void setRotation(float yRot, float xRot);

    @Inject(method = "update", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;prepareCullFrustum(Lorg/joml/Matrix4fc;Lorg/joml/Matrix4f;Lnet/minecraft/world/phys/Vec3;)V"))
    private void eclipse$afterUpdate(DeltaTracker deltaTracker, CallbackInfo ci) {
        if (this.entity instanceof Player player) {
            ua.rp.chat.client.stonemason.StonemasonCameraRig.Pose pose =
                    ua.rp.chat.client.stonemason.StonemasonCameraRig.pose(player);
            if (pose != null) {
                this.setPosition(pose.position());
                this.setRotation(pose.yaw(), pose.pitch());
                return;
            }
        }
        if (this.entity instanceof Player player && SmartCameraManager.getInstance().isCameraMotionActiveFor(player) && !this.detached) {
            float partialTick = this.getCameraEntityPartialTicks(deltaTracker);
            SmartCameraManager manager = SmartCameraManager.getInstance();
            manager.updatePhysics(player, partialTick);
            double stabilizedY = manager.getStabilizedY(player, this.position.y, partialTick);
            Vec3 origin = new Vec3(this.position.x, stabilizedY, this.position.z);
            Vec3 desiredOffset = manager.getCameraOffset(this.yRot, this.xRot);
            Vec3 safeOffset = manager.resolveCameraOffset(player, origin, desiredOffset, eclipse$nearPlaneHalfExtents());
            this.setPosition(origin.add(safeOffset));
        }
    }

    private Vec3 eclipse$nearPlaneHalfExtents() {
        try {
            Camera.NearPlane plane = this.getNearPlane(this.getFov());
            Vec3[] corners = {
                    plane.getTopLeft(), plane.getTopRight(),
                    plane.getBottomLeft(), plane.getBottomRight()
            };
            double x = 0.0;
            double y = 0.0;
            double z = 0.0;
            for (Vec3 corner : corners) {
                x = Math.max(x, Math.abs(corner.x));
                y = Math.max(y, Math.abs(corner.y));
                z = Math.max(z, Math.abs(corner.z));
            }
            if (Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z)) {
                return new Vec3(Math.max(0.05, x), Math.max(0.05, y), Math.max(0.05, z));
            }
        } catch (RuntimeException ignored) {
            // A conservative fallback keeps the first projection frame safe.
        }
        return ua.rp.chat.client.camera.CameraCollisionResolver.FALLBACK_HALF_EXTENTS;
    }

    @Inject(method = "isDetached", at = @At("HEAD"), cancellable = true)
    private void eclipse$isDetached(CallbackInfoReturnable<Boolean> cir) {
        if (SmartCameraManager.getInstance().isWorldFirstPersonBodyRender()) {
            cir.setReturnValue(true);
        }
    }
}
