package ua.rp.chat.mixin.microvoxel;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ua.rp.chat.RPChat;
import ua.rp.chat.microvoxel.MicrovoxelBlocks;
import ua.rp.chat.mixin.PathNavigationRegionAccessor;

@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class BlockStateMicrovoxelMixin {
    @Inject(method = "canBeReplaced(Lnet/minecraft/world/level/material/Fluid;)Z",
            at = @At("HEAD"), cancellable = true)
    private void eclipse$protectMicrovoxelFromFluid(Fluid fluid,
                                                    CallbackInfoReturnable<Boolean> cir) {
        if (MicrovoxelBlocks.isMarker((BlockState) (Object) this)) cir.setReturnValue(false);
    }

    @Inject(method = "canBeReplaced()Z", at = @At("HEAD"), cancellable = true)
    private void eclipse$protectMicrovoxelFromReplacement(CallbackInfoReturnable<Boolean> cir) {
        if (MicrovoxelBlocks.isMarker((BlockState) (Object) this)) cir.setReturnValue(false);
    }

    /**
     * Redstone tanks: a comparator against a wet marker reads the water fraction 0..15
     * instead of 0. Dry markers and volumes without fluid data read 0, exactly like an
     * empty container. Neighbour notifications ride the sim (quantum changes only).
     */
    @Inject(method = "getAnalogOutputSignal(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;)I",
            at = @At("HEAD"), cancellable = true)
    private void eclipse$microvoxelTankSignal(Level level, BlockPos pos, Direction direction,
                                              CallbackInfoReturnable<Integer> cir) {
        if (!MicrovoxelBlocks.isMarker((BlockState) (Object) this)) return;
        RPChat plugin = RPChat.getInstance();
        if (plugin == null || plugin.getMicrovoxelManager() == null) return;
        ua.rp.chat.microvoxel.MicrovoxelManager manager = plugin.getMicrovoxelManager();
        ua.rp.chat.microvoxel.MicrovoxelKey key;
        try {
            key = new ua.rp.chat.microvoxel.MicrovoxelKey(manager.runtimeWorldId(level),
                    pos.getX(), pos.getY(), pos.getZ());
        } catch (IllegalStateException unavailable) {
            return;
        }
        ua.rp.chat.microvoxel.FluidVolume fluid = manager.fluidStore().get(key);
        ua.rp.chat.microvoxel.MicrovoxelVolume micro = manager.microvolumes().get(key);
        if (fluid == null || micro == null) {
            cir.setReturnValue(0);
            return;
        }
        long airCells = 0;
        for (int cell = 0; cell < ua.rp.chat.microvoxel.MicrovoxelVolume.CELL_COUNT; cell++) {
            if (!micro.occupied(cell)) airCells++;
        }
        cir.setReturnValue(ua.rp.chat.microvoxel.fluid.FluidSim.comparatorSignal(
                fluid.totalUnits(), airCells));
    }

    @Inject(method = "getCollisionShape(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/phys/shapes/CollisionContext;)Lnet/minecraft/world/phys/shapes/VoxelShape;",
            at = @At("HEAD"), cancellable = true)
    private void eclipse$microvoxelCollision(BlockGetter level, BlockPos position, CollisionContext context,
                                             CallbackInfoReturnable<VoxelShape> cir) {
        eclipse$replaceShape(level, position, cir);
    }

    @Inject(method = "getCollisionShape(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/phys/shapes/VoxelShape;",
            at = @At("HEAD"), cancellable = true)
    private void eclipse$microvoxelCollisionNoContext(BlockGetter level, BlockPos position,
                                                      CallbackInfoReturnable<VoxelShape> cir) {
        eclipse$replaceShape(level, position, cir);
    }

    @Inject(method = "getShape(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/phys/shapes/CollisionContext;)Lnet/minecraft/world/phys/shapes/VoxelShape;",
            at = @At("HEAD"), cancellable = true)
    private void eclipse$microvoxelSelection(BlockGetter level, BlockPos position, CollisionContext context,
                                             CallbackInfoReturnable<VoxelShape> cir) {
        eclipse$replaceShape(level, position, cir);
    }

    @Inject(method = "getShape(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/phys/shapes/VoxelShape;",
            at = @At("HEAD"), cancellable = true)
    private void eclipse$microvoxelSelectionNoContext(BlockGetter level, BlockPos position,
                                                      CallbackInfoReturnable<VoxelShape> cir) {
        eclipse$replaceShape(level, position, cir);
    }

    @Inject(method = "getInteractionShape(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/phys/shapes/VoxelShape;",
            at = @At("HEAD"), cancellable = true)
    private void eclipse$microvoxelInteraction(BlockGetter level, BlockPos position,
                                               CallbackInfoReturnable<VoxelShape> cir) {
        eclipse$replaceShape(level, position, cir);
    }

    @Inject(method = "getBlockSupportShape(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/phys/shapes/VoxelShape;",
            at = @At("HEAD"), cancellable = true)
    private void eclipse$microvoxelSupport(BlockGetter level, BlockPos position,
                                           CallbackInfoReturnable<VoxelShape> cir) {
        eclipse$replaceShape(level, position, cir);
    }

    @Inject(method = "getDestroyProgress(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)F",
            at = @At("HEAD"), cancellable = true)
    private void eclipse$microvoxelDestroyProgress(
            net.minecraft.world.entity.player.Player player,
            BlockGetter level,
            BlockPos position,
            CallbackInfoReturnable<Float> cir
    ) {
        BlockState parent = eclipse$parentState(level, position);
        if (parent != null) cir.setReturnValue(parent.getDestroyProgress(player, level, position));
    }

    @Inject(method = "getDestroySpeed(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)F",
            at = @At("HEAD"), cancellable = true)
    private void eclipse$microvoxelDestroySpeed(
            BlockGetter level,
            BlockPos position,
            CallbackInfoReturnable<Float> cir
    ) {
        BlockState parent = eclipse$parentState(level, position);
        if (parent != null) cir.setReturnValue(parent.getDestroySpeed(level, position));
    }

    private void eclipse$replaceShape(BlockGetter level, BlockPos position,
                                      CallbackInfoReturnable<VoxelShape> cir) {
        ServerLevel serverLevel;
        if (level instanceof ServerLevel direct) {
            serverLevel = direct;
        } else if (level instanceof PathNavigationRegion navigation
                && ((PathNavigationRegionAccessor) navigation).eclipse$backingLevel()
                instanceof ServerLevel backing) {
            serverLevel = backing;
        } else {
            return;
        }
        BlockState state = (BlockState) (Object) this;
        if (!MicrovoxelBlocks.isMarker(state)
                && !state.is(Blocks.STRUCTURE_VOID) && !state.is(Blocks.LIGHT)) return;
        RPChat plugin = RPChat.getInstance();
        if (plugin == null || plugin.getMicrovoxelManager() == null) return;
        VoxelShape shape = plugin.getMicrovoxelManager().collisionShape(serverLevel, position);
        if (shape != null) cir.setReturnValue(shape);
    }

    private BlockState eclipse$parentState(BlockGetter level, BlockPos position) {
        BlockState state = (BlockState) (Object) this;
        if (!MicrovoxelBlocks.isMarker(state)
                && !state.is(Blocks.STRUCTURE_VOID) && !state.is(Blocks.LIGHT)) return null;
        ServerLevel serverLevel;
        if (level instanceof ServerLevel direct) {
            serverLevel = direct;
        } else if (level instanceof PathNavigationRegion navigation
                && ((PathNavigationRegionAccessor) navigation).eclipse$backingLevel()
                instanceof ServerLevel backing) {
            serverLevel = backing;
        } else {
            return null;
        }
        RPChat plugin = RPChat.getInstance();
        if (plugin == null || plugin.getMicrovoxelManager() == null) return null;
        return plugin.getMicrovoxelManager().parentBlockState(serverLevel, position);
    }
}
