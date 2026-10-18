package ua.rp.chat.carry;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/** Native carry writes stay reversible until the ownership file is durable. */
public final class CarryBlockMutation {
    public static final int SILENT_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_SKIP_ALL_SIDEEFFECTS;
    private static final ThreadLocal<Integer> PROJECTION_DEPTH = ThreadLocal.withInitial(() -> 0);

    private CarryBlockMutation() { }

    @FunctionalInterface
    public interface Operation {
        void run() throws IOException;
    }

    /** The native adapter is also the boundary used by the headless block-state fixture. */
    public interface WorldAccess {
        BlockState state(BlockPos pos);
        LevelReader reader();
        boolean loaded(BlockPos pos);
        boolean setBlock(BlockPos pos, BlockState state, int flags);
        void notifyCommitted(BlockPos pos, BlockState before, BlockState after);
    }

    public static WorldAccess nativeWorld(ServerLevel level) {
        return new WorldAccess() {
            @Override
            public BlockState state(BlockPos pos) {
                return level.getBlockState(pos);
            }

            @Override
            public LevelReader reader() {
                return level;
            }

            @Override
            public boolean loaded(BlockPos pos) {
                return level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) != null;
            }

            @Override
            public boolean setBlock(BlockPos pos, BlockState state, int flags) {
                return level.setBlock(pos, state, flags);
            }

            @Override
            public void notifyCommitted(BlockPos pos, BlockState before, BlockState after) {
                // Shape changes also retain skip-onPlace/BE flags. The moved furnishing must
                // never run its placement recipe, including pumpkin/golem pattern spawning.
                after.updateNeighbourShapes(level, pos, SILENT_FLAGS);
                level.updateNeighborsAt(pos, before.getBlock());
                level.updateNeighbourForOutputSignal(pos, after.getBlock());
            }
        };
    }

    public static void setSilently(WorldAccess world, BlockPos pos, BlockState state) throws IOException {
        if (!world.setBlock(pos, state, SILENT_FLAGS) && !world.state(pos).equals(state)) {
            throw new IOException("Native carry block mutation was rejected");
        }
    }

    /**
     * Validates native attachment survival against the temporarily changed world before saving.
     * Reads never call updateShape, which can schedule fluid/native work while merely probing.
     */
    public static CarryLedger.Effect effect(WorldAccess world, BlockPos position,
                                            Operation forward, Operation reverse) {
        BlockPos pos = position.immutable();
        return new CarryLedger.Effect() {
            private BlockState before;
            private boolean started;

            @Override
            public void apply() throws IOException {
                if (!world.loaded(pos)) throw new IOException("Carry target chunk is unloaded");
                before = world.state(pos);
                List<Attachment> attachments = attachments(world, pos);
                started = true;
                forward.run();
                for (Attachment attachment : attachments) {
                    if (attachment.survived() && !attachment.state().canSurvive(world.reader(), attachment.pos())) {
                        throw new IOException("Сначала снимите прикреплённые к объекту блоки.");
                    }
                }
            }

            @Override
            public void rollback() throws IOException {
                if (started) reverse.run();
            }

            @Override
            public void committed() {
                world.notifyCommitted(pos, before, world.state(pos));
            }
        };
    }

    private record Attachment(BlockPos pos, BlockState state, boolean survived) { }

    private static List<Attachment> attachments(WorldAccess world, BlockPos pos) throws IOException {
        List<Attachment> result = new ArrayList<>(6);
        for (Direction direction : Direction.values()) {
            BlockPos neighbor = pos.relative(direction);
            if (!world.loaded(neighbor)) throw new IOException("Adjacent carry target chunk is unloaded");
            BlockState state = world.state(neighbor);
            result.add(new Attachment(neighbor, state, state.canSurvive(world.reader(), neighbor)));
        }
        return result;
    }

    /** Existing projection edits keep vanilla flags; only carry's synchronous write is silent. */
    public static int projectionFlags() {
        return PROJECTION_DEPTH.get() == 0 ? Block.UPDATE_CLIENTS : SILENT_FLAGS;
    }

    public static ProjectionScope silentProjection() {
        return new ProjectionScope();
    }

    public static final class ProjectionScope implements AutoCloseable {
        private final int priorDepth;
        private boolean closed;

        private ProjectionScope() {
            priorDepth = PROJECTION_DEPTH.get();
            PROJECTION_DEPTH.set(priorDepth + 1);
        }

        @Override
        public void close() {
            if (!closed) {
                if (priorDepth == 0) PROJECTION_DEPTH.remove();
                else PROJECTION_DEPTH.set(priorDepth);
                closed = true;
            }
        }
    }
}
