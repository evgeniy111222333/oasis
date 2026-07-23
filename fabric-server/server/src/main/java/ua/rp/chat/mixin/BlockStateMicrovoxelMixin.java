package ua.rp.chat.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ua.rp.chat.RPChat;

@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class BlockStateMicrovoxelMixin {
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

    private void eclipse$replaceShape(BlockGetter level, BlockPos position,
                                      CallbackInfoReturnable<VoxelShape> cir) {
        if (!(level instanceof ServerLevel serverLevel)) return;
        BlockState state = (BlockState) (Object) this;
        if (!state.is(Blocks.STRUCTURE_VOID) && !state.is(Blocks.LIGHT)) return;
        RPChat plugin = RPChat.getInstance();
        if (plugin == null || plugin.getMicrovoxelManager() == null) return;
        VoxelShape shape = plugin.getMicrovoxelManager().collisionShape(serverLevel, position);
        if (shape != null) cir.setReturnValue(shape);
    }
}
