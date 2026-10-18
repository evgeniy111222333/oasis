package ua.rp.chat.mixin.entity;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import ua.rp.chat.RPChat;

@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {
    @ModifyVariable(method = "heal", at = @At("HEAD"), argsOnly = true)
    private float eclipse$applyVitalRegenerationLimit(float amount) {
        if (!((Object) this instanceof ServerPlayer player)) return amount;
        RPChat plugin = RPChat.getInstance();
        if (plugin == null || plugin.getStaminaManager() == null) return amount;
        return plugin.getStaminaManager().onNaturalRegain(player, amount);
    }
}
