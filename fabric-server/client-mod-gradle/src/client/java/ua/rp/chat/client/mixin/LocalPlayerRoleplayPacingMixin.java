package ua.rp.chat.client.mixin;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.ai.attributes.Attributes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ua.rp.chat.locomotion.RoleplayPaceAttributes;

/** Predict the server's pace after this tick's input, before native movement consumes it. */
@Mixin(LocalPlayer.class)
public abstract class LocalPlayerRoleplayPacingMixin {
    @Inject(method="aiStep",at=@At(value="INVOKE",
            target="Lnet/minecraft/client/player/AbstractClientPlayer;aiStep()V"))
    private void eclipse$roleplayPaceBeforeMovement(CallbackInfo ci) {
        LocalPlayer player=(LocalPlayer)(Object)this;
        RoleplayPaceAttributes.apply(player);
        // Player.aiStep refreshes this cache after superclass physics. Refresh it before physics
        // as well so a walk/sprint switch does not use the preceding tick's speed.
        player.setSpeed((float)player.getAttributeValue(Attributes.MOVEMENT_SPEED));
    }
}
