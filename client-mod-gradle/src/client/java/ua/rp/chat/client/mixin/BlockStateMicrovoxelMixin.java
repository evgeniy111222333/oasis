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
        if (!eclipse$isMicrovoxelMarker()) return;
        VoxelShape shape = MicrovoxelClientState.collisionShape(position);
        if (shape != null) cir.setReturnValue(shape);
    }

    @Inject(method = "getShape(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/phys/shapes/CollisionContext;)Lnet/minecraft/world/phys/shapes/VoxelShape;",
            at = @At("HEAD"), cancellable = true)
    private void eclipse$microvoxelSelection(BlockGetter level, BlockPos position, CollisionContext context,
                                             CallbackInfoReturnable<VoxelShape> cir) {
        if (!eclipse$isMicrovoxelMarker()) return;
        VoxelShape shape = MicrovoxelClientState.collisionShape(position);
        if (shape != null) cir.setReturnValue(shape);
    }

    private boolean eclipse$isMicrovoxelMarker() {
        BlockState state = (BlockState) (Object) this;
        return state.is(Blocks.STRUCTURE_VOID) || state.is(Blocks.LIGHT);
    }
}
