package ua.rp.chat.carry;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

/** Native placement facts available to both the server and its read-only preview. */
public final class CarryPlacement {
    private CarryPlacement() {}
    public static Rotation rotation(int quarterTurns) {
        return switch (Math.floorMod(quarterTurns, 4)) {
            case 1 -> Rotation.CLOCKWISE_90;
            case 2 -> Rotation.CLOCKWISE_180;
            case 3 -> Rotation.COUNTERCLOCKWISE_90;
            default -> Rotation.NONE;
        };
    }
    public static BlockState rotated(BlockState state, int quarterTurns) {
        return state.rotate(rotation(quarterTurns));
    }
    public static boolean snapshotDestination(LevelReader world, BlockPos destination,
                                               BlockState snapshot, int quarterTurns) {
        return world.getBlockState(destination).isAir() && world.getFluidState(destination).isEmpty()
                && (snapshot == null || rotated(snapshot, quarterTurns).canSurvive(world, destination));
    }
}
