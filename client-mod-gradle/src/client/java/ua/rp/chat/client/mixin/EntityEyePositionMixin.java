package ua.rp.chat.client.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ua.rp.chat.client.camera.SmartCameraManager;

@Mixin(Entity.class)
public class EntityEyePositionMixin {
    @Inject(method = "getEyePosition(F)Lnet/minecraft/world/phys/Vec3;", at = @At("RETURN"), cancellable = true)
    private void oasis$frontEyePosition(float partialTick, CallbackInfoReturnable<Vec3> cir) {
        Entity entity = (Entity) (Object) this;
        Minecraft client = Minecraft.getInstance();
        SmartCameraManager camera = SmartCameraManager.getInstance();
        if (client == null
                || entity != client.player
                || !camera.isFirstPersonBodyEnabled()) {
            return;
        }

        Vec3 offset = camera.getEyeOffset(entity.getYRot(), entity.getXRot());
        if (offset.lengthSqr() > 0.0) {
            cir.setReturnValue(cir.getReturnValue().add(offset));
        }
    }
}
