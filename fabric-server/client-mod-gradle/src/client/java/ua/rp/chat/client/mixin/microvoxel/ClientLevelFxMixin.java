package ua.rp.chat.client.mixin.microvoxel;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

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
}
