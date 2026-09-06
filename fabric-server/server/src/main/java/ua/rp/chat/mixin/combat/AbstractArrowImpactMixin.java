package ua.rp.chat.mixin.combat;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ua.rp.chat.projectile.ArrowImpactPhysics;
import ua.rp.chat.projectile.ArrowImpactRuntime;

@Mixin(AbstractArrow.class)
public abstract class AbstractArrowImpactMixin {
    @Unique private ArrowImpactPhysics.Result eclipse$impact;
    @Unique private Entity eclipse$hitTarget;

    @Inject(method = "onHitEntity", at = @At("HEAD"))
    private void eclipse$captureTarget(EntityHitResult hit, CallbackInfo ci) {
        eclipse$hitTarget = hit.getEntity();
    }

    @Redirect(method = "onHitEntity",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/Entity;hurtOrSimulate(Lnet/minecraft/world/damagesource/DamageSource;F)Z"))
    private boolean eclipse$acceptResolvedRpContact(Entity target, DamageSource source, float amount) {
        // Inlined replacement for the deprecated bridge: verified identical dispatch against
        // its bytecode (ServerLevel -> hurtServer, otherwise -> hurtClient), with zero
        // deprecated references left in the codebase.
        AbstractArrow arrow = (AbstractArrow) (Object) this;
        boolean vanillaAccepted;
        if (arrow.level() instanceof net.minecraft.server.level.ServerLevel serverLevel) {
            vanillaAccepted = target.hurtServer(serverLevel, source, amount);
        } else {
            vanillaAccepted = target.hurtClient(source);
        }
        eclipse$impact = ArrowImpactRuntime.take(arrow.getUUID());
        return vanillaAccepted || eclipse$impact != null && eclipse$impact.acceptedContact();
    }

    @Redirect(method = "onHitEntity",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/LivingEntity;setArrowCount(I)V"))
    private void eclipse$avoidVanillaThroughArrow(LivingEntity target, int count) {
        if (eclipse$impact == null || !eclipse$impact.exits()) {
            target.setArrowCount(count);
        }
    }

    @Redirect(method = "onHitEntity",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/projectile/arrow/AbstractArrow;discard()V"))
    private void eclipse$continueThroughTarget(AbstractArrow arrow) {
        if (eclipse$impact == null || !eclipse$impact.exits()
                || eclipse$impact.residualSpeed() < 0.08 || eclipse$hitTarget == null) {
            arrow.discard();
            return;
        }
        Vec3 velocity = arrow.getDeltaMovement();
        if (velocity.lengthSqr() < 1.0e-8) {
            arrow.discard();
            return;
        }
        Vec3 direction = velocity.normalize();
        double advance = distanceToExit(eclipse$hitTarget.getBoundingBox(), arrow.position(), direction) + 0.055;
        arrow.setPos(arrow.position().add(direction.scale(advance)));
        arrow.setDeltaMovement(direction.scale(eclipse$impact.residualSpeed()));
    }

    @Inject(method = "onHitEntity", at = @At("RETURN"))
    private void eclipse$clearImpact(EntityHitResult hit, CallbackInfo ci) {
        eclipse$impact = null;
        eclipse$hitTarget = null;
    }

    @Unique
    private static double distanceToExit(AABB box, Vec3 origin, Vec3 direction) {
        double best = Double.POSITIVE_INFINITY;
        if (direction.x > 1.0e-8) best = Math.min(best, (box.maxX - origin.x) / direction.x);
        if (direction.x < -1.0e-8) best = Math.min(best, (box.minX - origin.x) / direction.x);
        if (direction.y > 1.0e-8) best = Math.min(best, (box.maxY - origin.y) / direction.y);
        if (direction.y < -1.0e-8) best = Math.min(best, (box.minY - origin.y) / direction.y);
        if (direction.z > 1.0e-8) best = Math.min(best, (box.maxZ - origin.z) / direction.z);
        if (direction.z < -1.0e-8) best = Math.min(best, (box.minZ - origin.z) / direction.z);
        if (!Double.isFinite(best) || best < 0.0) return 0.35;
        return Math.min(best, 1.5);
    }
}
