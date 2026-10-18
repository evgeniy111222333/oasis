package ua.rp.chat.carry;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** Exercises the real carry mutation/ledger, with a native BlockState world-read fixture. */
public final class CarryBlockMutationTest {
    public static void main(String[] args) throws Exception {
        verifyAttachedTorchRejectsBeforeSave();
        verifyDiskFailureIsSilent();
        verifyPlacementNotifiesOnlyAfterCommit();
        verifyProjectionScope();
        System.out.println("CarryBlockMutationTest passed");
    }

    private static void verifyAttachedTorchRejectsBeforeSave() throws Exception {
        Fixture world = new Fixture();
        BlockPos source = BlockPos.ZERO;
        BlockPos torch = source.east();
        BlockState attached = Blocks.WALL_TORCH.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST);
        world.blocks.put(source, Blocks.CRAFTING_TABLE.defaultBlockState());
        world.blocks.put(torch, attached);
        require(attached.canSurvive(world.reader(), torch), "native wall torch starts supported");
        CarryLedger ledger = ledger(world, false);
        CarryLedger.Effect effect = mutation(world, source, Blocks.AIR.defaultBlockState(),
                Blocks.CRAFTING_TABLE.defaultBlockState());
        fails(() -> ledger.introduce(snapshot(), UUID.randomUUID(), location(), effect));
        require(world.state(source).is(Blocks.CRAFTING_TABLE), "failed lift restores source");
        require(world.state(torch).equals(attached), "dependent attachment remains exact");
        require(world.events.equals(List.of("write:818", "write:818")),
                "dependency rejection must precede persistence and notifications: " + world.events);
        require(world.callbacks == 0 && world.notifications == 0, "rollback produces no native callbacks");
        require(ledger.entries().isEmpty(), "failed dependency check creates no owner");
    }

    private static void verifyDiskFailureIsSilent() throws Exception {
        Fixture world = new Fixture();
        world.blocks.put(BlockPos.ZERO, Blocks.BOOKSHELF.defaultBlockState());
        CarryLedger ledger = ledger(world, true);
        fails(() -> ledger.introduce(snapshot(), UUID.randomUUID(), location(),
                mutation(world, BlockPos.ZERO, Blocks.AIR.defaultBlockState(),
                        Blocks.BOOKSHELF.defaultBlockState())));
        require(world.state(BlockPos.ZERO).is(Blocks.BOOKSHELF), "IO failure restores furnishing");
        require(world.events.equals(List.of("write:818", "save", "write:818")),
                "failed durable save rolls back silently: " + world.events);
        require(world.callbacks == 0 && world.notifications == 0, "failed save never notifies");
        require(ledger.entries().isEmpty(), "failed save retains original ledger");
    }

    private static void verifyPlacementNotifiesOnlyAfterCommit() throws Exception {
        Fixture world = new Fixture();
        CarryLedger ledger = ledger(world, false);
        UUID owner = UUID.randomUUID();
        UUID id = ledger.introduce(snapshot(), owner, location(), CarryLedger.NONE);
        world.events.clear();
        world.committed = () -> ledger.get(id) == null;
        ledger.consume(id, owner, mutation(world, BlockPos.ZERO,
                Blocks.CARVED_PUMPKIN.defaultBlockState(), Blocks.AIR.defaultBlockState()));
        require(world.events.equals(List.of("write:818", "save", "notify")),
                "callbacks must follow durable ownership: " + world.events);
        require(world.callbacks == 0, "moving a carved pumpkin never invokes onPlace");
        require(world.notifications == 1 && world.state(BlockPos.ZERO).is(Blocks.CARVED_PUMPKIN),
                "successful furnishing remains the native block");
        require(CarryBlockMutation.SILENT_FLAGS == 818, "exact native silent flags");
        require((CarryBlockMutation.SILENT_FLAGS & Block.UPDATE_SKIP_ON_PLACE) != 0,
                "silent writes must skip golem-producing placement callback");
        require((CarryBlockMutation.SILENT_FLAGS & Block.UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS) != 0,
                "silent writes must skip block entity side effects");
        require((CarryBlockMutation.SILENT_FLAGS & Block.UPDATE_NEIGHBORS) == 0,
                "silent writes must defer neighbors");
        // Demonstrate that the fixture detects both pre-fix native flag choices.
        world.setBlock(BlockPos.ZERO, Blocks.CARVED_PUMPKIN.defaultBlockState(), 3);
        world.setBlock(BlockPos.ZERO, Blocks.CARVED_PUMPKIN.defaultBlockState(), 2);
        require(world.callbacks == 2, "old flags expose the unsafe placement callback");
    }

    private static void verifyProjectionScope() throws Exception {
        require(CarryBlockMutation.projectionFlags() == Block.UPDATE_CLIENTS, "normal projection unchanged");
        try (var outer = CarryBlockMutation.silentProjection()) {
            require(CarryBlockMutation.projectionFlags() == 818, "carry projection is silent");
            try (var nested = CarryBlockMutation.silentProjection()) {
                require(CarryBlockMutation.projectionFlags() == 818, "nested rollback stays silent");
            }
            require(CarryBlockMutation.projectionFlags() == 818, "nested close preserves outer scope");
        }
        try {
            try (var ignored = CarryBlockMutation.silentProjection()) { throw new IOException("failure"); }
        } catch (IOException expected) { }
        require(CarryBlockMutation.projectionFlags() == 2, "scope restores normal flags even on failure");
    }

    private static CarryLedger.Effect mutation(Fixture world, BlockPos pos,
                                               BlockState after, BlockState before) {
        return CarryBlockMutation.effect(world, pos,
                () -> CarryBlockMutation.setSilently(world, pos, after),
                () -> CarryBlockMutation.setSilently(world, pos, before));
    }

    private static CarryLedger ledger(Fixture world, boolean failSave) throws IOException {
        return new CarryLedger(new CarryLedger.Store() {
            public List<CarryLedger.Entry> load() { return List.of(); }
            public void save(Collection<CarryLedger.Entry> entries) throws IOException {
                world.events.add("save");
                if (failSave) throw new IOException("disk full");
            }
        });
    }

    private static CarryLedger.Snapshot snapshot() {
        return new CarryLedger.Snapshot("block", "Furnishing", "{}", "{}", new byte[0], true);
    }

    private static CarryLedger.Location location() {
        return new CarryLedger.Location("minecraft:overworld", 0.5, 0, 0.5, 0);
    }

    private static final class Fixture implements CarryBlockMutation.WorldAccess {
        final Map<BlockPos, BlockState> blocks = new HashMap<>();
        final List<String> events = new ArrayList<>();
        int callbacks;
        int notifications;
        BooleanSupplier committed = () -> true;
        final LevelReader reader = (LevelReader) Proxy.newProxyInstance(LevelReader.class.getClassLoader(),
                new Class<?>[]{LevelReader.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getBlockState")) return state((BlockPos) args[0]);
                    if (method.getName().equals("getFluidState")) return state((BlockPos) args[0]).getFluidState();
                    if (method.getName().equals("getBlockEntity")) return null;
                    if (method.getName().equals("getMinY")) return -64;
                    if (method.getName().equals("getHeight")) return 384;
                    if (method.isDefault()) return InvocationHandler.invokeDefault(proxy, method, args);
                    throw new AssertionError("Unexpected world read: " + method);
                });

        public BlockState state(BlockPos pos) { return blocks.getOrDefault(pos, Blocks.AIR.defaultBlockState()); }
        public LevelReader reader() { return reader; }
        public boolean loaded(BlockPos pos) { return true; }
        public boolean setBlock(BlockPos pos, BlockState state, int flags) {
            events.add("write:" + flags);
            blocks.put(pos.immutable(), state);
            if ((flags & Block.UPDATE_SKIP_ON_PLACE) == 0) callbacks++;
            return true;
        }
        public void notifyCommitted(BlockPos pos, BlockState before, BlockState after) {
            require(committed.getAsBoolean(), "ledger must expose committed owner before notification");
            notifications++;
            events.add("notify");
        }
    }

    private interface Checked { void run() throws Exception; }
    private static void fails(Checked action) throws Exception {
        try { action.run(); } catch (IOException expected) { return; }
        throw new AssertionError("Expected mutation rejection");
    }
    private static void require(boolean ok, String why) { if (!ok) throw new AssertionError(why); }
}
