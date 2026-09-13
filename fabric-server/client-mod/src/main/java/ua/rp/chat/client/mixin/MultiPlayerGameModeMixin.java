package ua.rp.chat.client.mixin;

import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ua.rp.chat.client.CombatIntentSender;

@Mixin(MultiPlayerGameMode.class)
public class MultiPlayerGameModeMixin {
    @Inject(method = "attack", at = @At("HEAD"))
    private void eclipse$sendCombatIntent(Player player, Entity target, CallbackInfo ci) {
        CombatIntentSender.send(player, target);
    }
}
