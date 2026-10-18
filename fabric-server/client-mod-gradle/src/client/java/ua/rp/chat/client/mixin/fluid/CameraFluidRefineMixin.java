package ua.rp.chat.client.mixin.fluid;

import net.minecraft.client.Camera;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ua.rp.chat.client.microvoxel.MicrovoxelClientState;

/**
 * Keeps underwater fog honest over partial voxel water: when the camera eye sits on a dry
 * 1/16 cell of a waterlogged marker, the WATER fog lifts even though the block reads wet.
 * The water surface a few cells away still fogs correctly through the vanilla path.
 * Lava works in reverse: an eye inside lava cells gains the LAVA fog vanilla never
 * assigns (markers carry no lava fluidstate), so crucibles blind correctly.
 */
@Mixin(Camera.class)
public abstract class CameraFluidRefineMixin {
    @Shadow
    private Level level;

    @Shadow
    public abstract Vec3 position();

    @Inject(method = "getFluidInCamera", at = @At("RETURN"), cancellable = true)
    private void eclipse$refineCameraFluid(CallbackInfoReturnable<FogType> cir) {
        if (level == null) return;
        Vec3 eye = position();
        BlockPos pos = BlockPos.containing(eye);
        if (!ua.rp.chat.microvoxel.MicrovoxelBlocks.isMarker(level.getBlockState(pos))) return;
        if (cir.getReturnValue() == FogType.WATER && !MicrovoxelClientState.cellWet(eye)) {
            cir.setReturnValue(FogType.NONE);
        } else if (cir.getReturnValue() != FogType.LAVA
                && MicrovoxelClientState.voxelLavaHeightAt(level, eye) >= 0.0) {
            cir.setReturnValue(FogType.LAVA);
        }
    }
}
