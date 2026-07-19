package ua.rp.chat.client.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ua.rp.chat.client.microvoxel.MicrovoxelClientState;

@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class BlockStateMicrovoxelMixin {
    @Inject(method = "getCollisionShape(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/phys/shapes/CollisionContext;)Lnet/minecraft/world/phys/shapes/VoxelShape;",
            at = @At("HEAD"), cancellable = true)
    private void eclipse$microvoxelCollision(BlockGetter level, BlockPos position, CollisionContext context,
                                             CallbackInfoReturnable<VoxelShape> cir) {
        eclipse$replaceShape("collision-context", position, cir);
    }

    @Inject(method = "getCollisionShape(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/phys/shapes/VoxelShape;",
            at = @At("HEAD"), cancellable = true)
    private void eclipse$microvoxelCollisionNoContext(BlockGetter level, BlockPos position,
                                                      CallbackInfoReturnable<VoxelShape> cir) {
        eclipse$replaceShape("collision", position, cir);
    }

    @Inject(method = "getShape(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/phys/shapes/CollisionContext;)Lnet/minecraft/world/phys/shapes/VoxelShape;",
            at = @At("HEAD"), cancellable = true)
    private void eclipse$microvoxelSelection(BlockGetter level, BlockPos position, CollisionContext context,
                                             CallbackInfoReturnable<VoxelShape> cir) {
        eclipse$replaceShape("selection-context", position, cir);
    }

    @Inject(method = "getShape(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/phys/shapes/VoxelShape;",
            at = @At("HEAD"), cancellable = true)
    private void eclipse$microvoxelSelectionNoContext(BlockGetter level, BlockPos position,
                                                      CallbackInfoReturnable<VoxelShape> cir) {
        eclipse$replaceShape("selection", position, cir);
    }

    @Inject(method = "getInteractionShape(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/phys/shapes/VoxelShape;",
            at = @At("HEAD"), cancellable = true)
    private void eclipse$microvoxelInteraction(BlockGetter level, BlockPos position,
                                               CallbackInfoReturnable<VoxelShape> cir) {
        eclipse$replaceShape("interaction", position, cir);
    }

    @Inject(method = "getBlockSupportShape(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/phys/shapes/VoxelShape;",
            at = @At("HEAD"), cancellable = true)
    private void eclipse$microvoxelSupport(BlockGetter level, BlockPos position,
                                           CallbackInfoReturnable<VoxelShape> cir) {
        eclipse$replaceShape("support", position, cir);
    }

    @Inject(method = "getDestroyProgress(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)F",
            at = @At("HEAD"), cancellable = true)
    private void eclipse$microvoxelDestroyProgress(net.minecraft.world.entity.player.Player player, net.minecraft.world.level.BlockGetter level, BlockPos pos,
                                                 CallbackInfoReturnable<Float> cir) {
        if (!eclipse$isMicrovoxelMarker()) return;
        BlockState baseState = MicrovoxelClientState.getBaseBlockState(pos);
        System.out.println("[MICROVOXEL-DEBUG-MINING] pos=" + pos + " baseState=" + baseState);
        if (baseState != null) {
            cir.setReturnValue(baseState.getDestroyProgress(player, level, pos));
        }
    }

    @Inject(method = "getFaceOcclusionShape(Lnet/minecraft/core/Direction;)Lnet/minecraft/world/phys/shapes/VoxelShape;",
            at = @At("HEAD"), cancellable = true)
    private void eclipse$microvoxelFaceOcclusion(net.minecraft.core.Direction direction,
                                                 CallbackInfoReturnable<VoxelShape> cir) {
        if (eclipse$isMicrovoxelMarker()) {
            cir.setReturnValue(net.minecraft.world.phys.shapes.Shapes.empty());
        }
    }

    @Inject(method = "getDestroySpeed(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)F",
            at = @At("HEAD"), cancellable = true)
    private void eclipse$microvoxelDestroySpeed(BlockGetter level, BlockPos pos, CallbackInfoReturnable<Float> cir) {
        if (!eclipse$isMicrovoxelMarker()) return;
        BlockState baseState = MicrovoxelClientState.getBaseBlockState(pos);
        if (baseState != null) {
            cir.setReturnValue(baseState.getDestroySpeed(level, pos));
        } else {
            cir.setReturnValue(1.5F); // Default fallback hardness
        }
    }

    private void eclipse$replaceShape(String hook, BlockPos position, CallbackInfoReturnable<VoxelShape> cir) {
        // Fast path: known marker blocks always check microvoxel state.
        // Slow path: AIR blocks may be markers that the client predicted as broken;
        // a HashMap lookup is acceptable here to keep collisions correct.
        if (!eclipse$isMicrovoxelMarker()) {
            BlockState state = (BlockState) (Object) this;
            if (!state.isAir()) return;
        }
        VoxelShape shape = MicrovoxelClientState.collisionShape(position);
        if (shape != null) {
            MicrovoxelClientState.probeShape(hook, position, shape);
            cir.setReturnValue(shape);
        }
    }

    private boolean eclipse$isMicrovoxelMarker() {
        BlockState state = (BlockState) (Object) this;
        return state.is(Blocks.STRUCTURE_VOID) || state.is(Blocks.LIGHT);
    }
}
