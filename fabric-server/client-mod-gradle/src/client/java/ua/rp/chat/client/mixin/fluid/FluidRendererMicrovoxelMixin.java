package ua.rp.chat.client.mixin.fluid;

import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ua.rp.chat.client.microvoxel.MicrovoxelClientState;
import ua.rp.chat.microvoxel.MicrovoxelBlocks;

/**
 * Suppresses the vanilla full-cube water visual on markers that carry precise voxel fluid
 * data: the section model draws the exact per-column surface instead, so a half-filled bowl
 * no longer renders a floating full cube clipping through its walls. Markers WITHOUT fluid
 * data (packet race) keep vanilla rendering as the fallback, never invisible water.
 */
@Mixin(FluidRenderer.class)
public abstract class FluidRendererMicrovoxelMixin {
    @Inject(method = "tesselate(Lnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/client/renderer/block/FluidRenderer$Output;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/material/FluidState;)V",
            at = @At("HEAD"), cancellable = true)
    private void eclipse$suppressMarkerWater(BlockAndTintGetter level, BlockPos pos,
                                             FluidRenderer.Output output, BlockState blockState,
                                             FluidState fluidState, CallbackInfo ci) {
        if (MicrovoxelBlocks.isMarker(blockState) && MicrovoxelClientState.fluidAt(pos) != null) {
            ci.cancel();
        }
    }
}
