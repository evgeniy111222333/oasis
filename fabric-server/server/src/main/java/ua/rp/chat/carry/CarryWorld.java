package ua.rp.chat.carry;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.shapes.CollisionContext;
import ua.rp.chat.RPChat;
import ua.rp.chat.carver.DraftSession;
import ua.rp.chat.microvoxel.MicrovoxelBlockStates;
import ua.rp.chat.microvoxel.MicrovoxelBlocks;
import ua.rp.chat.microvoxel.MicrovoxelCarryBridge;
import ua.rp.chat.microvoxel.MicrovoxelKey;
import ua.rp.chat.microvoxel.MicrovoxelParentage;
import ua.rp.chat.microvoxel.MicrovoxelPortableVolume;
import ua.rp.chat.microvoxel.MicrovoxelVolume;

/** Private block entity data stays exclusively in the persisted authoritative snapshot. */
public final class CarryWorld {
    private static final String DEFAULT_PROFILES = "minecraft:chest,minecraft:barrel,"
            + "minecraft:shulker_box,minecraft:crafting_table,minecraft:bookshelf,"
            + "minecraft:chiseled_bookshelf,minecraft:decorated_pot,minecraft:flower_pot,"
            + "minecraft:lantern,minecraft:soul_lantern,minecraft:carved_pumpkin";

    private final RPChat plugin;
    private final MicrovoxelCarryBridge voxels;
    private final Set<String> profiles = new HashSet<>();

    public CarryWorld(RPChat plugin) throws IOException {
        this.plugin = plugin;
        voxels = new MicrovoxelCarryBridge(plugin.getMicrovoxelManager());
        Path path = plugin.getDataFolder().toPath().resolve("carry-profiles.properties");
        Properties properties = new Properties();
        if (Files.exists(path)) {
            try (var in = Files.newInputStream(path)) { properties.load(in); }
        } else {
            properties.setProperty("blocks", DEFAULT_PROFILES);
            try (var out = Files.newOutputStream(path)) {
                properties.store(out, "Explicit single-block carry allowlist. Never add multi-block machinery.");
            }
        }
        for (String id : properties.getProperty("blocks", DEFAULT_PROFILES).split(",")) {
            profiles.add(id.trim());
        }
        if (profiles.contains("minecraft:shulker_box")) {
            for (var block : BuiltInRegistries.BLOCK) {
                if (block instanceof ShulkerBoxBlock) {
                    profiles.add(BuiltInRegistries.BLOCK.getKey(block).toString());
                }
            }
        }
    }

    public Set<String> liftable() {
        return Set.copyOf(profiles);
    }

    public boolean large(ItemStack stack) {
        return (stack.getItem() instanceof BlockItem block
                && profiles.contains(BuiltInRegistries.BLOCK.getKey(block.getBlock()).toString()))
                || plugin.getMicrovoxelManager().isPortableVolumeItem(stack);
    }

    public JsonElement encode(ItemStack stack, ServerLevel level) {
        return ItemStack.CODEC.encodeStart(level.registryAccess().createSerializationContext(JsonOps.INSTANCE),
                stack).getOrThrow();
    }

    public ItemStack decode(String json, ServerLevel level) {
        return ItemStack.CODEC.parse(level.registryAccess().createSerializationContext(JsonOps.INSTANCE),
                JsonParser.parseString(json)).getOrThrow();
    }

    public BlockState state(String json) {
        return BlockState.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json)).getOrThrow();
    }

    public boolean permitted(ServerPlayer player, BlockPos pos) {
        ServerLevel level = (ServerLevel) player.level();
        return level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) != null
                && level.getWorldBorder().isWithinBounds(pos) && level.mayInteract(player, pos)
                && player.mayBuild() && !plugin.getServer().isUnderSpawnProtection(level, pos, player)
                && !plugin.getMicrovoxelManager().isProtected(new MicrovoxelKey(
                plugin.getMicrovoxelManager().runtime().worldId(level), pos.getX(), pos.getY(), pos.getZ()));
    }

    public CarryLedger.Snapshot itemSnapshot(ItemStack item, ServerLevel level) {
        ItemStack one = item.copyWithCount(1);
        String json = encode(one, level).toString();
        CarryWire.utf8(json, 131072);
        return new CarryLedger.Snapshot("item", one.getHoverName().getString(), json, "", new byte[0], large(one));
    }

    public CarryLedger.Snapshot snapshot(ServerPlayer player, BlockPos pos) throws IOException {
        ServerLevel level = (ServerLevel) player.level();
        if (!permitted(player, pos)) throw new IOException("Target protected or unavailable");
        rejectActiveCarver(level, pos);
        BlockState state = level.getBlockState(pos);
        if (!state.getFluidState().isEmpty()) throw new IOException("Drain fluids first");
        if (MicrovoxelBlocks.isMarker(state)) return voxelSnapshot(player, pos, level);

        String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        if (!profiles.contains(id)) throw new IOException("This block is not a supported single-block furnishing");
        if (state.hasProperty(BlockStateProperties.CHEST_TYPE)
                && state.getValue(BlockStateProperties.CHEST_TYPE) != ChestType.SINGLE) {
            throw new IOException("Separate the double chest first");
        }
        if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                || state.hasProperty(BlockStateProperties.BED_PART)
                || state.getBlock() instanceof PistonBaseBlock || state.getBlock() instanceof DoorBlock) {
            throw new IOException("Multi-block structures cannot be lifted");
        }
        byte[] bytes = new byte[0];
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity != null) {
            for (ServerPlayer viewer : plugin.getServer().getPlayerList().getPlayers()) {
                if (viewer.containerMenu != viewer.inventoryMenu
                        && viewer.containerMenu.slots.stream().anyMatch(slot -> slot.container == blockEntity)) {
                    throw new IOException("Close the container first");
                }
            }
            bytes = CarryBlockSnapshot.encode(blockEntity, level.registryAccess());
        }
        ItemStack visual = new ItemStack(state.getBlock().asItem());
        if (visual.isEmpty()) throw new IOException("No safe furnishing visual");
        String blockState = BlockState.CODEC.encodeStart(JsonOps.INSTANCE, state).getOrThrow().toString();
        return new CarryLedger.Snapshot("block", visual.getHoverName().getString(),
                encode(visual, level).toString(), blockState, bytes, true);
    }

    private void rejectActiveCarver(ServerLevel level, BlockPos pos) throws IOException {
        for (ServerPlayer artisan : plugin.getServer().getPlayerList().getPlayers()) {
            var session = plugin.getCarverManager().sessionOf(artisan.getUUID());
            if (artisan.level() == level && session != null
                    && (session.state() == DraftSession.State.DESIGN || session.state() == DraftSession.State.WORK)
                    && session.blockX() == pos.getX() && session.blockY() == pos.getY()
                    && session.blockZ() == pos.getZ()) {
                throw new IOException("Сначала завершите работу резчика над объектом.");
            }
        }
    }

    private CarryLedger.Snapshot voxelSnapshot(ServerPlayer player, BlockPos pos, ServerLevel level)
            throws IOException {
        byte[] bytes = voxels.snapshot(player, pos);
        MicrovoxelVolume volume = MicrovoxelPortableVolume.decode(bytes);
        String material = MicrovoxelParentage.dominantMaterial(volume);
        BlockState parent = MicrovoxelBlockStates.parseBlockState(material);
        ItemStack visual = new ItemStack(parent.getBlock().asItem());
        if (visual.isEmpty()) visual = new ItemStack(Items.STONE);
        CompoundTag data = new CompoundTag();
        data.putByteArray("microvoxel_volume", bytes);
        CustomData.set(DataComponents.CUSTOM_DATA, visual, data);
        return new CarryLedger.Snapshot("voxel", "Скульптура", encode(visual, level).toString(), "", bytes, true);
    }

    /** Raw forward/rollback write. Ownership callers must use removalEffect/placementEffect. */
    private void remove(ServerLevel level, BlockPos pos, CarryLedger.Snapshot snapshot) throws IOException {
        if (snapshot.kind().equals("voxel")) {
            voxels.remove(level, pos);
            return;
        }
        level.removeBlockEntity(pos);
        CarryBlockMutation.setSilently(CarryBlockMutation.nativeWorld(level), pos, Blocks.AIR.defaultBlockState());
    }

    public boolean canRestore(ServerPlayer player, BlockPos pos, CarryLedger.Snapshot snapshot, int turns) {
        ServerLevel level = (ServerLevel) player.level();
        if (!permitted(player, pos)) return false;
        BlockState savedState = snapshot.kind().equals("voxel") ? null : state(snapshot.blockState());
        if (!CarryPlacement.snapshotDestination(level, pos, savedState, turns)) return false;
        if (snapshot.kind().equals("voxel")) return voxels.canPlace(player, pos);
        BlockState desired = CarryPlacement.rotated(savedState, turns);
        return level.isUnobstructed(desired, pos, CollisionContext.empty());
    }

    /** Silent restoration preserves inventories but never invokes a fresh placement recipe. */
    private void restore(ServerLevel level, BlockPos pos, CarryLedger.Snapshot snapshot, int turns)
            throws IOException {
        if (snapshot.kind().equals("voxel")) {
            voxels.restore(level, pos, snapshot.data(), turns);
            return;
        }
        BlockState desired = CarryPlacement.rotated(state(snapshot.blockState()), turns);
        BlockEntity entity = snapshot.data().length == 0 ? null
                : CarryBlockSnapshot.decode(snapshot.data(), pos, desired, level.registryAccess());
        CarryBlockMutation.setSilently(CarryBlockMutation.nativeWorld(level), pos, desired);
        if (entity != null) {
            level.setBlockEntity(entity);
            entity.setChanged();
        }
    }

    public CarryLedger.Effect removalEffect(ServerLevel level, BlockPos pos, CarryLedger.Snapshot snapshot) {
        return CarryBlockMutation.effect(CarryBlockMutation.nativeWorld(level), pos,
                () -> remove(level, pos, snapshot), () -> restore(level, pos, snapshot, 0));
    }

    public CarryLedger.Effect placementEffect(ServerLevel level, BlockPos pos,
                                              CarryLedger.Snapshot snapshot, int turns) {
        return CarryBlockMutation.effect(CarryBlockMutation.nativeWorld(level), pos,
                () -> restore(level, pos, snapshot, turns), () -> remove(level, pos, snapshot));
    }

    public static Rotation rotation(int turns) {
        return CarryPlacement.rotation(turns);
    }
}
