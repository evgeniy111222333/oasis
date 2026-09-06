package ua.rp.chat.mixin.microvoxel;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.lighting.LightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ua.rp.chat.RPChat;
import ua.rp.chat.microvoxel.MicrovoxelBlocks;

/**
 * Makes sealed microvoxel builds block light like vanilla. The light engine only understands
 * per-state opacity, so a fully sealed opaque volume reports its parent material state here
 * during propagation; everything else keeps the marker state and behaves exactly as before.
 *
 * <p>Injection runs at RETURN behind a single marker check, so non-marker positions pay one
 * getter plus one branch. Volume lookups and sealed-mask checks are revision-cached inside
 * the collision module; only marker positions ever reach them.</p>
 */
@Mixin(LightEngine.class)
public abstract class LightEngineMicrovoxelMixin {
    @Shadow
    protected LightChunkGetter chunkSource;

    @Inject(method = "getState", at = @At("RETURN"), cancellable = true)
    private void eclipse$microvoxelLightState(BlockPos pos, CallbackInfoReturnable<BlockState> cir) {
        BlockState stored = cir.getReturnValue();
        if (!MicrovoxelBlocks.isMarker(stored)) return;
        if (!(chunkSource.getLevel() instanceof ServerLevel level)) return;
        RPChat plugin = RPChat.getInstance();
        if (plugin == null || plugin.getMicrovoxelManager() == null) return;
        BlockState parent = plugin.getMicrovoxelManager().lightState(level, pos);
        if (parent != null) cir.setReturnValue(parent);
    }
}
