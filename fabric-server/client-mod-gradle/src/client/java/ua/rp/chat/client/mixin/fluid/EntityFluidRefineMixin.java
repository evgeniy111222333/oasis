package ua.rp.chat.client.mixin.fluid;

import net.minecraft.tags.FluidTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.material.Fluid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ua.rp.chat.client.microvoxel.MicrovoxelClientState;

/**
 * Client mirror of the server voxel-exact water refinement: dry corners of waterlogged
 * markers must not swim the local player either, or prediction and rendering disagree with
 * the server. Same all-together WATER-only contract as the server twin.
 */
@Mixin(Entity.class)
public abstract class EntityFluidRefineMixin {
    @Inject(method = "isInWater", at = @At("RETURN"), cancellable = true)
    private void eclipse$refineIsInWater(CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValue() && MicrovoxelClientState.shouldIgnoreWater((Entity) (Object) this)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "isEyeInFluid(Lnet/minecraft/tags/TagKey;)Z", at = @At("RETURN"), cancellable = true)
    private void eclipse$refineIsEyeInFluid(TagKey<Fluid> tag,
                                            CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValue() && tag.equals(FluidTags.WATER)
                && MicrovoxelClientState.shouldIgnoreWater((Entity) (Object) this)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "getFluidHeight(Lnet/minecraft/tags/TagKey;)D", at = @At("RETURN"), cancellable = true)
    private void eclipse$refineFluidHeight(TagKey<Fluid> tag,
                                           CallbackInfoReturnable<Double> cir) {
        if (cir.getReturnValue() > 0.0 && tag.equals(FluidTags.WATER)
                && MicrovoxelClientState.shouldIgnoreWater((Entity) (Object) this)) {
            cir.setReturnValue(0.0);
        }
    }

    /**
     * Client mirror of the server splash suppression: no phantom splash on dry voxel
     * corners, genuine immersion still splashes through the vanilla path.
     */
    @Inject(method = "doWaterSplashEffect", at = @At("HEAD"), cancellable = true)
    private void eclipse$refineWaterSplash(CallbackInfo ci) {
        if (MicrovoxelClientState.shouldIgnoreWater((Entity) (Object) this)) {
            ci.cancel();
        }
    }

    /**
     * Client mirror of voxel lava contact: crucibles burn the local player exactly like
     * vanilla pools, keeping prediction and rendering consistent with the server.
     */
    @Inject(method = "isInLava", at = @At("RETURN"), cancellable = true)
    private void eclipse$refineIsInLava(CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValue()
                && MicrovoxelClientState.voxelLavaHeight((Entity) (Object) this) >= 0.0) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "isEyeInFluid(Lnet/minecraft/tags/TagKey;)Z", at = @At("RETURN"), cancellable = true)
    private void eclipse$refineEyeInLava(TagKey<Fluid> tag,
                                         CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValue() || !tag.equals(FluidTags.LAVA)) return;
        Entity entity = (Entity) (Object) this;
        net.minecraft.world.phys.Vec3 eye = entity.position().add(0.0, entity.getEyeHeight(), 0.0);
        if (MicrovoxelClientState.voxelLavaHeightAt(entity, eye) >= 0.0) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "getFluidHeight(Lnet/minecraft/tags/TagKey;)D", at = @At("RETURN"), cancellable = true)
    private void eclipse$refineLavaHeight(TagKey<Fluid> tag,
                                          CallbackInfoReturnable<Double> cir) {
        if ((cir.getReturnValue() > 0.0 || !tag.equals(FluidTags.LAVA))) return;
        double height = MicrovoxelClientState.voxelLavaHeight((Entity) (Object) this);
        if (height >= 0.0) {
            cir.setReturnValue(height);
        }
    }
}
