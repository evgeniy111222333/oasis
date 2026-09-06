package ua.rp.chat.microvoxel.edit;

import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import ua.rp.chat.microvoxel.MicrovoxelBlocks;

/**
 * Conversion eligibility: which ordinary, full-collision, non-container blocks may be framed as
 * microvoxel geometry or used as drawing material. Container/data-bearing block types are always
 * rejected, and any shape that is not a single full-cube unit is rejected as well.
 */
public final class MicrovoxelEligibility {
    private MicrovoxelEligibility() {
    }

    public static boolean isEligibleFullBlock(BlockState state, net.minecraft.core.BlockPos pos, Level level) {
        if (!isEligibleMaterialState(state, pos, level)) return false;
        if (isBlockEntityState(state)) return false;
        return level == null || level.getBlockEntity(pos) == null;
    }

    public static boolean isBlockEntityState(BlockState state) {
        return state != null && state.getBlock() instanceof net.minecraft.world.level.block.EntityBlock;
    }

    public static boolean isEligibleMaterialState(BlockState state, net.minecraft.core.BlockPos pos, Level level) {
        if (state.isAir() || state.is(Blocks.BARRIER) || state.is(Blocks.STRUCTURE_VOID)
                || state.is(Blocks.LIGHT) || MicrovoxelBlocks.isMarker(state)) {
            return false;
        }
        return isFullCollision(state, pos, level);
    }

    private static boolean isFullCollision(BlockState state, net.minecraft.core.BlockPos pos, Level level) {
        try {
            VoxelShape shape = state.getCollisionShape(level, pos);
            if (shape.isEmpty()) return false;
            var boxes = shape.toAabbs();
            if (boxes.size() != 1) return false;
            AABB box = boxes.get(0);
            return close(box.maxX - box.minX, 1.0) && close(box.maxY - box.minY, 1.0)
                    && close(box.maxZ - box.minZ, 1.0);
        } catch (RuntimeException error) {
            return false;
        }
    }

    private static boolean close(double left, double right) {
        return Math.abs(left - right) < 1.0E-6;
    }
}