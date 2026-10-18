package ua.rp.chat.mixin.fluid;

import net.minecraft.tags.FluidTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.material.Fluid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ua.rp.chat.microvoxel.fluid.FluidSim;

/**
 * Voxel-exact water refinement for entities. Vanilla works at block granularity, so a dry
 * corner of a waterlogged marker wrongly swims; the shared {@link FluidSim#shouldIgnoreWater}
 * guard proves dryness from exact 1/16 cells first. All three reads (submersion, height,
 * eyes) override together — never partially — and only for the WATER tag.
 */
@Mixin(Entity.class)
public abstract class EntityFluidRefineMixin {
    @Inject(method = "isInWater", at = @At("RETURN"), cancellable = true)
    private void eclipse$refineIsInWater(CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValue() && FluidSim.shouldIgnoreWater((Entity) (Object) this)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "isEyeInFluid(Lnet/minecraft/tags/TagKey;)Z", at = @At("RETURN"), cancellable = true)
    private void eclipse$refineIsEyeInFluid(TagKey<Fluid> tag,
                                            CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValue() && tag.equals(FluidTags.WATER)
                && FluidSim.shouldIgnoreWater((Entity) (Object) this)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "getFluidHeight(Lnet/minecraft/tags/TagKey;)D", at = @At("RETURN"), cancellable = true)
    private void eclipse$refineFluidHeight(TagKey<Fluid> tag,
                                           CallbackInfoReturnable<Double> cir) {
        if (cir.getReturnValue() > 0.0 && tag.equals(FluidTags.WATER)
                && FluidSim.shouldIgnoreWater((Entity) (Object) this)) {
            cir.setReturnValue(0.0);
        }
    }

    /**
     * Silences the one-shot entry splash on dry voxel corners. The coarse
     * {@code wasTouchingWater} flag fires from the block box while every refined read above
     * already reports dry; without this, stepping onto a dry rim plays water. Genuine
     * immersion still splashes through the vanilla path.
     */
    @Inject(method = "doWaterSplashEffect", at = @At("HEAD"), cancellable = true)
    private void eclipse$refineWaterSplash(CallbackInfo ci) {
        if (FluidSim.shouldIgnoreWater((Entity) (Object) this)) {
            ci.cancel();
        }
    }

    /**
     * Voxel-exact lava contact: crucibles burn exactly like vanilla pools (damage, ignition,
     * slow movement, fog) through the standard reads. Only fires when vanilla sees no lava
     * itself; real lava nearby always wins.
     */
    @Inject(method = "isInLava", at = @At("RETURN"), cancellable = true)
    private void eclipse$refineIsInLava(CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValue()
                && FluidSim.voxelLavaHeight((Entity) (Object) this) >= 0.0) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "isEyeInFluid(Lnet/minecraft/tags/TagKey;)Z", at = @At("RETURN"), cancellable = true)
    private void eclipse$refineEyeInLava(TagKey<Fluid> tag,
                                         CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValue() || !tag.equals(FluidTags.LAVA)) return;
        Entity entity = (Entity) (Object) this;
        net.minecraft.world.phys.Vec3 eye = entity.position().add(0.0, entity.getEyeHeight(), 0.0);
        if (FluidSim.voxelLavaHeightAt(entity, eye) >= 0.0) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "getFluidHeight(Lnet/minecraft/tags/TagKey;)D", at = @At("RETURN"), cancellable = true)
    private void eclipse$refineLavaHeight(TagKey<Fluid> tag,
                                          CallbackInfoReturnable<Double> cir) {
        if ((cir.getReturnValue() > 0.0 || !tag.equals(FluidTags.LAVA))) return;
        double height = FluidSim.voxelLavaHeight((Entity) (Object) this);
        if (height >= 0.0) {
            cir.setReturnValue(height);
        }
    }
}
