package ua.rp.chat.client.mixin.microvoxel;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ua.rp.chat.client.camera.SmartCameraManager;
import ua.rp.chat.client.microvoxel.MicrovoxelClientState;
import ua.rp.chat.client.microvoxel.MicrovoxelInteractionController;
import ua.rp.chat.client.pickup.PickupClientState;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    @Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
    private void eclipse$microvoxelAttack(CallbackInfoReturnable<Boolean> cir) {
        Minecraft minecraft = (Minecraft) (Object) this;
        if (MicrovoxelInteractionController.handleAttack(minecraft)) cir.setReturnValue(true);
    }

    @Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
    private void eclipse$microvoxelContinueAttack(boolean leftClickActive, CallbackInfo ci) {
        Minecraft minecraft = (Minecraft) (Object) this;
        if (leftClickActive && MicrovoxelInteractionController.editing()) {
            if (MicrovoxelInteractionController.currentHit() != null 
                    || MicrovoxelInteractionController.currentStandardTarget() != null) {
                MicrovoxelInteractionController.handleContinuousAttack(minecraft);
                ci.cancel();
            }
        }
    }

    @Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
    private void eclipse$microvoxelUse(CallbackInfo ci) {
        Minecraft minecraft = (Minecraft) (Object) this;
        if (PickupClientState.handleUse(minecraft)) {
            ci.cancel();
            return;
        }
        // Optimistic fluid preview rides along without cancelling: the server owns the fill,
        // the client just stops waiting a round trip to show the water.
        MicrovoxelClientState.predictFluidUse(minecraft);
        if (MicrovoxelInteractionController.handleUse(minecraft)) ci.cancel();
    }

    @Inject(method = "pickBlockOrEntity", at = @At("RETURN"))
    private void eclipse$blockPickThroughOccludedCamera(CallbackInfo ci) {
        if (!SmartCameraManager.getInstance().isCameraFailClosed()) {
            return;
        }
        Minecraft minecraft = (Minecraft) (Object) this;
        Vec3 cameraPosition = minecraft.gameRenderer.getMainCamera().position();
        minecraft.crosshairPickEntity = null;
        minecraft.hitResult = BlockHitResult.miss(
                cameraPosition, Direction.UP, BlockPos.containing(cameraPosition));
    }
}
