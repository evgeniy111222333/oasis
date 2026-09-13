package ua.rp.chat.client.mixin.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ua.rp.chat.client.camera.SmartCameraManager;

@Mixin(EntityRenderDispatcher.class)
public class EntityRenderDispatcherMixin {
    @Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
    private <T extends Entity> void eclipse$shouldRender(T entity, Frustum frustum, double x, double y, double z, CallbackInfoReturnable<Boolean> cir) {
        if (entity == Minecraft.getInstance().player && SmartCameraManager.getInstance().isFirstPersonBodyEnabled()) {
            cir.setReturnValue(true);
            return;
        }
        try {
            if (entity == Minecraft.getInstance().player
                    && ua.rp.chat.client.carver.CarverClientState.working()) {
                cir.setReturnValue(true);
            }
        } catch (RuntimeException ignored) {
        }
    }
}
