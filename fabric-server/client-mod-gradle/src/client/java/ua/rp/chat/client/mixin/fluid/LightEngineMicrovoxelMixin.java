package ua.rp.chat.client.mixin.fluid;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.lighting.LightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ua.rp.chat.client.microvoxel.MicrovoxelClientState;
import ua.rp.chat.microvoxel.MicrovoxelBlocks;

/**
 * Client mirror of the server light-state interception: sealed opaque microvoxel builds report
 * their parent material to the local light engine during propagation, so client-side lighting
 * agrees with the server instead of flooding interiors with skylight.
 *
 * <p>Same contract as the server twin: RETURN injection behind one marker check, revision-
 * cached resolution, transparent fallback for everything unsealed.</p>
 */
@Mixin(LightEngine.class)
public abstract class LightEngineMicrovoxelMixin {
    @Shadow
    protected LightChunkGetter chunkSource;

    @Inject(method = "getState", at = @At("RETURN"), cancellable = true)
    private void eclipse$microvoxelLightState(BlockPos pos, CallbackInfoReturnable<BlockState> cir) {
        if (!MicrovoxelBlocks.isMarker(cir.getReturnValue())) return;
        BlockState parent = MicrovoxelClientState.resolveLightState(pos);
        if (parent != null) cir.setReturnValue(parent);
    }
}
