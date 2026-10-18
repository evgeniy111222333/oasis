package ua.rp.chat.client.mixin.carry;

import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ua.rp.chat.client.carry.CarryClient;

/** The native outline pass follows the model silhouette in concept 01's warm ivory. */
@Mixin(EntityRenderer.class)
public abstract class CarryEntityRendererMixin {
    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/entity/state/EntityRenderState;F)V", at = @At("TAIL"))
    private void eclipse$ivoryCarryOutline(Entity entity, EntityRenderState state, float tickDelta, CallbackInfo ci) {
        var focus = CarryClient.focus();
        if (focus != null && focus.entity() == entity.getId()) state.outlineColor = 0xFFFFF0D7;
    }
}
