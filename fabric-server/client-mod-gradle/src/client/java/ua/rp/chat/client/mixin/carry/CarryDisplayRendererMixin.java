package ua.rp.chat.client.mixin.carry;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.DisplayRenderer;
import net.minecraft.client.renderer.entity.state.DisplayEntityRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ua.rp.chat.carry.CarryTransforms;
import ua.rp.chat.client.carry.CarryClientState;

/** A carried projection follows the same interpolated player frame as the skin and hands. */
@Mixin(DisplayRenderer.class)
public abstract class CarryDisplayRendererMixin {
    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/Display;Lnet/minecraft/client/renderer/entity/state/DisplayEntityRenderState;F)V", at = @At("TAIL"))
    private void eclipse$carryAttachment(Display display, DisplayEntityRenderState state, float partialTick, CallbackInfo ci) {
        var client = Minecraft.getInstance();
        var owner = CarryClientState.displayOwner(display.getId());
        if (owner == null || client.level == null || !(client.level.getPlayerByUUID(owner) instanceof Player player)) return;
        var position = player.getPosition(partialTick);
        float yaw = Mth.rotLerp(partialTick, player.yRotO, player.getYRot());
        var anchor = CarryTransforms.anchor(position.x, position.y, position.z, yaw, player.isCrouching());
        state.x = anchor.x(); state.y = anchor.y() + CarryTransforms.modelCenter(display instanceof Display.BlockDisplay, true);
        state.z = anchor.z(); state.entityYRot = yaw; state.entityXRot = 0;
    }
}
