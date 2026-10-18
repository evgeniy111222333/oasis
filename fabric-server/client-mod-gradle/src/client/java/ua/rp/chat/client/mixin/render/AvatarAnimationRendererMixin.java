package ua.rp.chat.client.mixin.render;

import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ua.rp.chat.client.animation.PlayerAnimationRuntime;

/** Capture after humanoid and avatar extraction have populated this frame's movement flags. */
@Mixin(AvatarRenderer.class)
public class AvatarAnimationRendererMixin {
    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V", at = @At("RETURN"))
    private void eclipse$captureAuthoredAnimation(Avatar entity, AvatarRenderState state, float partialTick, CallbackInfo ci) {
        if (entity instanceof Player player) {
            ua.rp.chat.client.carry.CarryPoseRenderer.alignBody(player, state, partialTick);
            PlayerAnimationRuntime.capture(player, state, partialTick);
        }
    }
}
