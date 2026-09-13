package ua.rp.chat.mixin.combat;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ua.rp.chat.RPChat;

@Mixin(ProjectileWeaponItem.class)
public abstract class ProjectileWeaponItemMixin {
    @Inject(method = "createProjectile", at = @At("RETURN"))
    private void eclipse$rememberProjectileWeapon(Level level, LivingEntity shooter, ItemStack weapon,
                                                  ItemStack ammunition, boolean critical,
                                                  CallbackInfoReturnable<Projectile> callback) {
        RPChat plugin = RPChat.getInstance();
        Projectile projectile = callback.getReturnValue();
        if (level instanceof ServerLevel && plugin != null && plugin.getCombatManager() != null
                && projectile != null) {
            plugin.getCombatManager().rememberProjectileLaunch(projectile.getUUID(), weapon.copy());
        }
    }
}
