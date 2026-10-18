package ua.rp.chat.client.mixin.microvoxel;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Parent-material break feedback: vanilla spawns hit and predicted-break particles
 * from the blockstate at the position, which is the shared marker for every carved
 * material (stone-grey everywhere). Carved volumes substitute their dominant cached
 * material instead, so a wooden sculpture sheds wooden chips while the server is
 * still authorizing the edit.
 */
@Mixin(ClientLevel.class)
public class ClientLevelFxMixin {
    private static final ThreadLocal<Boolean> ECLIPSE_FX_REDISPATCH =
            ThreadLocal.withInitial(() -> false);

    @Inject(method = "addDestroyBlockEffect", at = @At("HEAD"), cancellable = true)
    private void eclipse$parentBreakFx(BlockPos pos, BlockState state, CallbackInfo ci) {
        if (ECLIPSE_FX_REDISPATCH.get()) return;
        if (!ua.rp.chat.microvoxel.MicrovoxelBlocks.isMarker(state)) return;
        BlockState parent;
        try {
            // Exact struck cell first (mixed sculptures), dominant material as fallback.
            parent = ua.rp.chat.client.microvoxel.MicrovoxelClientState.hitCellState(pos);
            if (parent == null) {
                parent = ua.rp.chat.client.microvoxel.MicrovoxelClientState.parentState(pos);
            }
        } catch (RuntimeException unavailable) {
            return;
        }
        if (parent == null) return;
        ci.cancel();
        ECLIPSE_FX_REDISPATCH.set(true);
        try {
            ((ClientLevel) (Object) this).addDestroyBlockEffect(pos, parent);
        } finally {
            ECLIPSE_FX_REDISPATCH.set(false);
        }
    }

    /**
     * Periodic mining dust (Minecraft.continueAttack -> addBreakingBlockEffect) reads the block
     * state at the hit position directly, which is the shared marker for every carved material
     * and therefore stones every material. Redirect that one read to the struck cell's (or the
     * dominant) parent material so leaf and wood chisel with their own chips, exactly like the
     * break burst. The rest of vanilla's particle placement (shape bounds, direction, count)
     * stays untouched.
     */
    @Redirect(
            method = "addBreakingBlockEffect",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/multiplayer/ClientLevel;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"))
    private BlockState eclipse$breakingParticleParent(ClientLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state == null || !ua.rp.chat.microvoxel.MicrovoxelBlocks.isMarker(state)) return state;
        try {
            BlockState parent = ua.rp.chat.client.microvoxel.MicrovoxelClientState.hitCellState(pos);
            if (parent == null) {
                parent = ua.rp.chat.client.microvoxel.MicrovoxelClientState.parentState(pos);
            }
            return parent != null ? parent : state;
        } catch (RuntimeException unavailable) {
            return state;
        }
    }

    /**
     * Real-block change (server authoritative path): a neighbouring microvoxel volume may have
     * cached a face as hidden or exposed against the previous block, so re-mesh its six axis
     * neighbours. Purely additive to vanilla; never mutates the world.
     */
    @Inject(
            method = "setServerVerifiedBlockState(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)V",
            at = @At("RETURN"))
    private void eclipse$realBlockChangedServer(BlockPos pos, BlockState state, int flags,
                                                CallbackInfo ci) {
        ua.rp.chat.client.microvoxel.MicrovoxelClientState.onRealBlockChanged(pos);
    }

    /** Local predicted block change: same occlusion invalidation as the server path. */
    @Inject(
            method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
            at = @At("RETURN"))
    private void eclipse$realBlockChangedLocal(BlockPos pos, BlockState state, int flags,
                                               int recursionLeft, CallbackInfoReturnable<Boolean> cir) {
        ua.rp.chat.client.microvoxel.MicrovoxelClientState.onRealBlockChanged(pos);
    }
}
