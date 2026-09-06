package ua.rp.chat.microvoxel;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import ua.rp.chat.RPChat;
import ua.rp.chat.microvoxel.collision.MicrovoxelCollision;
import ua.rp.chat.microvoxel.fluid.FluidSim;
import ua.rp.chat.microvoxel.fluid.FluidStore;
import ua.rp.chat.microvoxel.fluid.FluidTuning;
import ua.rp.chat.microvoxel.econ.MicrovoxelMaterialEconomy;
import ua.rp.chat.microvoxel.edit.MicrovoxelEditEngine;
import ua.rp.chat.microvoxel.edit.MicrovoxelEligibility;
import ua.rp.chat.microvoxel.edit.MicrovoxelEditHistory;
import ua.rp.chat.microvoxel.environment.MicrovoxelEnvironmentSim;
import ua.rp.chat.microvoxel.mining.MicrovoxelMiningEngine;
import ua.rp.chat.microvoxel.persistence.MicrovoxelPersistence;
import ua.rp.chat.microvoxel.sync.MicrovoxelSyncHub;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Public facade of the microvoxel subsystem. Keeps the exact pre-refactor public contract
 * (protocol entry point, lifecycle, mixin queries, item ports, marker restoration)
 * while delegating behaviour to the modular packages:
 *
 * <ul>
 *     <li>{@link MicrovoxelSyncHub} — snapshots, subscriptions, broadcasts;</li>
 *     <li>{@link MicrovoxelCollision} — sweeps, shapes, native collision;</li>
 *     <li>{@link MicrovoxelMaterialEconomy} — survival unit ledger;</li>
 *     <li>{@link MicrovoxelEditEngine} / {@link MicrovoxelEditHistory} — actions and undo;</li>
 *     <li>{@link MicrovoxelEnvironmentSim} — fire and explosion pressure;</li>
 *     <li>{@link MicrovoxelPersistence} — journals and the save worker.</li>
 * </ul>
 *
 * <p>The storage bootstrap, item-drop serialization and marker projection stay here: they are
 * lifecycle-critical and locked by the source-text verification tasks.</p>
 */
public final class MicrovoxelManager {
    private static final boolean DEBUG = Boolean.getBoolean("rpchat.microvoxel.debug");
    private static final int SYNC_RADIUS_CHUNKS = 8;
    private static final double MAX_REACH = 6.25;
    private static final long ACTION_WINDOW_MS = 1_000L;
    private static final int MAX_ACTIONS_PER_WINDOW = 40;
    private static final int[][] BOUNDARY_DIRECTIONS = {
            {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}
    };

    private final RPChat plugin;
    private final MicrovoxelRuntime runtime;
    private final MicrovoxelPersistence persistence;
    private final MicrovoxelSyncHub sync;
    private final MicrovoxelCollision collision;
    private final MicrovoxelMaterialEconomy economy;
    private final MicrovoxelEditHistory history;
    private final MicrovoxelEditEngine engine;
    private final MicrovoxelEnvironmentSim environment;
    private final MicrovoxelMiningEngine mining;
    private final FluidStore fluidStore = new FluidStore();
    private final FluidTuning fluidTuning;
    private final FluidSim fluidSim;
    private MicrovoxelFlags volumeFlags;
    private final Map<UUID, RateWindow> actionRates = new ConcurrentHashMap<>();
    private Path storagePath;
    private Path fluidFile;

    public MicrovoxelManager(RPChat plugin) {
        this.plugin = plugin;
        this.runtime = new MicrovoxelRuntime(plugin);
        this.persistence = new MicrovoxelPersistence(plugin, runtime);
        this.collision = new MicrovoxelCollision(runtime);
        this.sync = new MicrovoxelSyncHub(runtime);
        runtime.setSync(sync);
        this.volumeFlags = new MicrovoxelFlags(null, plugin.getLogger());
        runtime.setFlags(volumeFlags);
        MicrovoxelContext context = new MicrovoxelContext(runtime, sync, collision, persistence);
        this.economy = new MicrovoxelMaterialEconomy(runtime);
        runtime.setFluidStore(fluidStore);
        this.fluidTuning = new FluidTuning();
        this.fluidTuning.reload(plugin.getConfig());
        this.fluidSim = new FluidSim(runtime, fluidTuning);
        // Fluid rewrites ride the coalesced microvoxel worker: crash windows shrink from
        // minutes to one edit burst at zero extra wakeups. The periodic backstop stays.
        this.persistence.setOverflowSave(() -> saveFluids("coalesced"));
        this.history = new MicrovoxelEditHistory(context);
        this.engine = new MicrovoxelEditEngine(context, economy, history);
        this.environment = new MicrovoxelEnvironmentSim(context);
        this.mining = new MicrovoxelMiningEngine(context, economy,
                (float) plugin.getConfig().getDouble("microvoxels.mining.multiplier", 1.0),
                plugin.getConfig().getBoolean("microvoxels.mining.wrong-tool-blocks", false));
    }

    public void start() {
        try {
            Path worldDataDirectory = plugin.getServer().getWorldPath(
                    net.minecraft.world.level.storage.LevelResource.DATA);
            UUID saveIdentity = loadOrCreateSaveIdentity(worldDataDirectory);
            MicrovoxelStore store = loadWorldOwnedStore(worldDataDirectory);
            runtime.initialize(store, saveIdentity, storagePath);
            int migrated = migrateLegacyWorldIdentities();
            if (migrated > 0) {
                store.save();
                plugin.getLogger().info("Migrated " + migrated
                        + " microvoxel volumes to the save-scoped world identity.");
            }
            plugin.getLogger().info("Loaded " + store.size() + " microvoxel volumes.");
            fluidFile = storagePath.resolve("fluids-v1.dat");
            try {
                fluidStore.load(fluidFile);
                plugin.getLogger().info("Loaded " + fluidStore.size() + " fluid volumes.");
                if (fluidStore.loadedFromBackup()) {
                    plugin.getLogger().warning("Primary fluid storage was invalid; recovered from backup.");
                }
            } catch (IOException | RuntimeException error) {
                plugin.getLogger().severe("Unable to load fluid volumes: " + error.getMessage());
            }
            plugin.getLogger().info("[MICROVOXEL] Journal replay: "
                    + MicrovoxelMetrics.get("store.journal.replayed") + " batches replayed, "
                    + MicrovoxelMetrics.get("store.journal.dropped") + " truncated. "
                    + MicrovoxelMetrics.summarize());
            if (store.loadedFromBackup()) {
                plugin.getLogger().warning("Primary microvoxel storage was invalid; recovered from backup.");
            }
            if (store.recoveredJournalTail()) {
                plugin.getLogger().warning("Recovered microvoxel storage up to the last valid journal batch.");
            }
            runtime.initialize(store, saveIdentity, storagePath);
            MicrovoxelProjection projection = new MicrovoxelProjection(
                    store, projectionWorld(), persistence::schedulePersistence,
                    (key, volume) -> {
                        // Gravity at edit time: water above freshly placed cells settles before
                        // the marker projects, so no edit ever shows a floating frame. Dry and
                        // fluidless volumes skip on a single map lookup.
                        settleFluid(key, volume);
                        return markerState(volume, fluidKind(key));
                    });
            runtime.setProjection(projection);
            projection.reconcileLoadedChunks();
        } catch (IOException | RuntimeException error) {
            runtime.setStorageUnavailable();
            plugin.getLogger().severe("Unable to load microvoxels: " + error.getMessage());
        }
    }

    private MicrovoxelProjection.World projectionWorld() {
        return new MicrovoxelProjection.World() {
            @Override
            public ServerLevel getWorld(UUID worldId) {
                return runtime.getWorld(worldId);
            }

            @Override
            public LevelChunk loadedChunk(UUID worldId, int chunkX, int chunkZ) {
                ServerLevel world = getWorld(worldId);
                return world == null ? null : world.getChunkSource().getChunkNow(chunkX, chunkZ);
            }

            @Override
            public void setBlock(ServerLevel world, LevelChunk chunk, BlockPos pos, BlockState state) {
                world.setBlock(pos, state, 2);
            }

            @Override
            public void scheduleLight(ServerLevel world, BlockPos pos) {
                world.getLightEngine().checkBlock(pos);
            }
        };
    }

    private UUID loadOrCreateSaveIdentity(Path dataDirectory) throws IOException {
        Path identityFile = dataDirectory.resolve("rpchat-world-id.txt");
        if (Files.isRegularFile(identityFile)) {
            String value = Files.readString(identityFile, StandardCharsets.UTF_8).trim();
            try {
                return UUID.fromString(value);
            } catch (IllegalArgumentException invalid) {
                throw new IOException("Invalid RPChat world identity: " + value, invalid);
            }
        }
        Files.createDirectories(dataDirectory);
        UUID created = UUID.randomUUID();
        Path temporary = identityFile.resolveSibling(identityFile.getFileName() + ".tmp");
        Files.writeString(temporary, created + System.lineSeparator(), StandardCharsets.UTF_8);
        try {
            Files.move(temporary, identityFile, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, identityFile, StandardCopyOption.REPLACE_EXISTING);
        }
        return created;
    }

    private MicrovoxelStore loadWorldOwnedStore(Path worldDataDirectory) throws IOException {
        Path storageDirectory = worldDataDirectory.resolve("rpchat").resolve("microvoxels");
        Path worldStoreFile = storageDirectory.resolve("microvoxels-v2.dat");
        storagePath = storageDirectory.toAbsolutePath().normalize();
        // Protection flags live in a sidecar next to the region store so flag schema changes
        // never touch the region format or require migrations.
        volumeFlags = new MicrovoxelFlags(
                storageDirectory.resolve("microvoxel-flags.json"), plugin.getLogger());
        volumeFlags.load();
        runtime.setFlags(volumeFlags);
        plugin.getLogger().info("Loaded " + volumeFlags.size() + " protected microvoxel volumes.");
        Path migrationMarker = storageDirectory.resolve("LEGACY_MIGRATION_COMPLETE");
        MicrovoxelStore worldStore = new MicrovoxelStore(worldStoreFile);
        worldStore.load();

        if (!Files.isRegularFile(migrationMarker)) {
            Path legacyFile = plugin.getDataFolder().toPath().resolve("microvoxels-v1.dat");
            int imported = 0;
            if (worldStore.size() == 0 && hasStorageArtifacts(legacyFile)) {
                MicrovoxelStore legacyStore = new MicrovoxelStore(legacyFile);
                legacyStore.load();
                for (Map.Entry<MicrovoxelKey, MicrovoxelVolume> entry
                        : legacyStore.snapshot().entries()) {
                    worldStore.put(entry.getKey(), entry.getValue().copy());
                    imported++;
                }
                if (imported > 0) worldStore.save();
            }
            writeMigrationMarker(migrationMarker, legacyFile, imported);
            if (imported > 0) {
                plugin.getLogger().info("Migrated " + imported
                        + " microvoxel volumes into the world-owned region storage. "
                        + "The legacy source was preserved.");
            }
        }
        plugin.getLogger().info("Microvoxel storage: " + storagePath);
        return worldStore;
    }

    private static boolean hasStorageArtifacts(Path baseFile) throws IOException {
        Path regionDirectory = baseFile.resolveSibling(baseFile.getFileName() + ".regions-v2");
        if (Files.isRegularFile(baseFile)
                || Files.isRegularFile(baseFile.resolveSibling(baseFile.getFileName() + ".bak"))
                || Files.isRegularFile(baseFile.resolveSibling(baseFile.getFileName() + ".journal"))) {
            return true;
        }
        if (!Files.isDirectory(regionDirectory)) return false;
        try (var entries = Files.list(regionDirectory)) {
            return entries.anyMatch(path -> Files.isRegularFile(path)
                    && (path.getFileName().toString().endsWith(".mvr")
                    || path.getFileName().toString().endsWith(".mvr.bak")));
        }
    }

    private static void writeMigrationMarker(
            Path marker, Path legacyFile, int imported) throws IOException {
        Files.createDirectories(marker.getParent());
        Path temporary = marker.resolveSibling(marker.getFileName() + ".tmp");
        String body = "world-owned-microvoxel-storage-v2\n"
                + "legacy=" + legacyFile.toAbsolutePath().normalize() + "\n"
                + "imported=" + imported + "\n";
        Files.writeString(temporary, body, StandardCharsets.UTF_8);
        try {
            Files.move(temporary, marker, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, marker, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private int migrateLegacyWorldIdentities() {
        int migrated = 0;
        for (ServerLevel level : plugin.getServer().getAllLevels()) {
            UUID legacy = MicrovoxelRuntime.legacyWorldId(level);
            UUID current = runtime.worldId(level);
            migrated += runtime.store().remapWorld(legacy, current);
        }
        return migrated;
    }

    public void shutdown() {
        persistence.shutdown();
        saveFluids("shutdown");
    }

    public void tick() {
        long tick = runtime.advanceTick();
        if (!runtime.storageReady()) return;
        if (runtime.projection() != null) runtime.projection().tick();
        environment.tick();
        sync.tick();
        sync.retryUnacknowledgedSnapshots();
        mining.tick();
        fluidSim.tick();
        // Throttled fluid persistence: fluid data is small, so a periodic atomic rewrite
        // beats journaling. Shutdown always saves regardless of the throttle.
        if (tick % 6000 == 0 && fluidStore.isDirty()) {
            saveFluids("periodic");
        }
        runtime.store().trimCache();
        collision.trimCache();
        collision.drainLightChecks();
        // One metrics line per minute at 20 TPS: cheap, greppable, and the only history we
        // keep for edits/rejects/sync/persistence without a metrics backend. Fluid tuning
        // reloads on the same cadence, so /rpreload retunes a running server.
        if (tick % 1200 == 0) {
            fluidTuning.reload(plugin.getConfig());
            plugin.getLogger().info(MicrovoxelMetrics.summarize());
        }
    }

    public void handleAction(ServerPlayer player, int protocolVersion, long transactionId,
                             int action, int x, int y, int z, int cell, int expectedRevision,
                             Vec3 clientLook, Vec3 clientEye) {
        if (player == null || !runtime.storageReady()) {
            return;
        }
        if (protocolVersion != MicrovoxelProtocol.VERSION) {
            plugin.getLogger().warning("[MICROVOXEL] Rejected protocol version " + protocolVersion
                    + " from " + player.getScoreboardName() + "; expected " + MicrovoxelProtocol.VERSION);
            player.sendSystemMessage(Component.literal(
                    "Версия клиентского модуля не совместима с серверной системой микровокселей."), true);
            return;
        }
        if (action == MicrovoxelProtocol.ACTION_SNAPSHOT_ACK) {
            plugin.getServer().execute(() -> sync.acknowledgeSnapshot(player, transactionId));
            return;
        }
        if (action == MicrovoxelProtocol.ACTION_READY) {
            if (!allowAction(player)) return;
            plugin.getServer().execute(() -> sync.onReady(player));
            return;
        }
        if (action == MicrovoxelProtocol.ACTION_RESYNC_VOLUME) {
            if (!allowAction(player)) return;
            plugin.getServer().execute(() -> resyncVolume(player, x, y, z));
            return;
        }
        if (action == MicrovoxelProtocol.ACTION_RESYNC_CHUNK) {
            if (!allowAction(player)) return;
            plugin.getServer().execute(() -> resyncChunk(player, x, z));
            return;
        }
        if (!RPChat.hasPermission(player, "rpchat.microvoxels.edit", 2) || !allowAction(player)) {
            return;
        }
        if (action == MicrovoxelProtocol.ACTION_UNDO || action == MicrovoxelProtocol.ACTION_REDO) {
            plugin.getServer().execute(() -> history.applyHistory(player,
                    action == MicrovoxelProtocol.ACTION_UNDO));
            return;
        }
        boolean brushAction = action == MicrovoxelProtocol.ACTION_BRUSH_REMOVE
                || action == MicrovoxelProtocol.ACTION_BRUSH_ADD;
        boolean packedCellAction = brushAction || action == MicrovoxelProtocol.ACTION_PASTE;
        if (cell < 0 || (!packedCellAction && cell >= MicrovoxelVolume.CELL_COUNT)) return;
        UUID worldId = runtime.worldId(player.level());
        MicrovoxelKey key = new MicrovoxelKey(worldId, x, y, z);
        // Single choke point for protection: every coordinate-carrying edit (convert, add,
        // remove, carve, brush, copy, paste) is rejected here. Undo/redo is gated per change
        // inside the history, mining/break/place/explosion at their own entry points.
        if (isProtected(key)) {
            MicrovoxelMetrics.inc("edits.rejected.protected");
            sync.feedback(player, "Цей мікровоксельний об'єм захищено від змін.");
            MicrovoxelVolume current = runtime.store().get(key);
            if (current == null) sync.sendRemove(player, key);
            else sync.sendUpsert(player, key, current);
            return;
        }
        sync.trace(player, "ACTION_RX action=" + action + " pos=" + x + "," + y + "," + z
                + " cell=" + cell + " revision=" + expectedRevision);
        if (!MicrovoxelEditEngine.validClientLook(clientLook)
                || !MicrovoxelEditEngine.validClientEye(clientEye)) return;
        plugin.getServer().execute(() -> engine.applyAction(player,
                new MicrovoxelEditEngine.QueuedAction(transactionId, action, key, cell,
                        expectedRevision, clientLook.normalize(), clientEye)));
    }

    private void resyncVolume(ServerPlayer player, int x, int y, int z) {
        MicrovoxelMetrics.inc("sync.resync.volume");
        MicrovoxelKey key = new MicrovoxelKey(runtime.worldId(player.level()), x, y, z);
        if (!withinReachOfSubscribedArea(player, key.chunkX(), key.chunkZ())) return;
        MicrovoxelVolume volume = runtime.store().get(key);
        if (volume == null) reconcileOrphanMarker(player, key);
        sync.sendPacket(player, volume == null
                ? MicrovoxelProtocol.remove(key)
                : MicrovoxelProtocol.upsert(key, volume));
    }

    private void reconcileOrphanMarker(ServerPlayer player, MicrovoxelKey key) {
        ServerLevel world = (ServerLevel) player.level();
        LevelChunk loaded = world.getChunkSource().getChunkNow(key.chunkX(), key.chunkZ());
        if (loaded == null) return;
        BlockPos position = new BlockPos(key.x(), key.y(), key.z());
        if (!MicrovoxelBlocks.isMarker(loaded.getBlockState(position))) return;
        runtime.projection().clearOrphanMarker(key);
        plugin.getLogger().warning("[MICROVOXEL] Removed orphan marker without authoritative "
                + "volume at " + key.x() + "," + key.y() + "," + key.z()
                + " after a targeted client reconciliation request.");
    }

    private void resyncChunk(ServerPlayer player, int chunkX, int chunkZ) {
        MicrovoxelMetrics.inc("sync.resync.chunk");
        if (!withinReachOfSubscribedArea(player, chunkX, chunkZ)) return;
        UUID worldId = runtime.worldId(player.level());
        sync.sendPacket(player, MicrovoxelProtocol.clearChunk(chunkX, chunkZ));
        for (Map.Entry<MicrovoxelKey, MicrovoxelVolume> entry
                : runtime.store().inChunk(worldId, chunkX, chunkZ)) {
            sync.sendPacket(player, MicrovoxelProtocol.upsert(entry.getKey(), entry.getValue()));
        }
        sync.subscribe(player.getUUID(), new ChunkKey(worldId, chunkX, chunkZ));
    }

    private static boolean withinReachOfSubscribedArea(ServerPlayer player, int chunkX, int chunkZ) {
        int playerChunkX = player.blockPosition().getX() >> 4;
        int playerChunkZ = player.blockPosition().getZ() >> 4;
        return Math.abs(chunkX - playerChunkX) <= SYNC_RADIUS_CHUNKS
                && Math.abs(chunkZ - playerChunkZ) <= SYNC_RADIUS_CHUNKS;
    }

    private boolean allowAction(ServerPlayer player) {
        long now = System.currentTimeMillis();
        RateWindow previous = actionRates.get(player.getUUID());
        if (previous == null || now - previous.startedAt >= ACTION_WINDOW_MS) {
            actionRates.put(player.getUUID(), new RateWindow(now, 1));
            return true;
        }
        if (previous.count >= MAX_ACTIONS_PER_WINDOW) return false;
        actionRates.put(player.getUUID(), new RateWindow(previous.startedAt, previous.count + 1));
        return true;
    }

    public void onJoin(ServerPlayer player) {
        sync.onJoin(player);
    }

    public void onAuthenticationComplete(ServerPlayer player) {
        if (player == null || player.connection == null || !runtime.storageReady()) return;
        plugin.getServer().execute(() -> sync.sendFullSnapshot(player, 0));
    }

    public void onQuit(ServerPlayer player) {
        UUID uuid = player.getUUID();
        sync.onQuit(player);
        engine.onQuit(uuid);
        mining.onQuit(uuid);
        history.onQuit(uuid);
        actionRates.remove(uuid);
    }

    public boolean protectsMarker(ServerLevel level, BlockPos pos) {
        if (!runtime.storageReady()) return false;
        try {
            UUID worldId = runtime.worldId(level);
            return runtime.store().get(new MicrovoxelKey(worldId, pos.getX(), pos.getY(), pos.getZ())) != null;
        } catch (IllegalStateException unavailable) {
            return false;
        }
    }

    public BlockState materialStateAtSurface(
            ServerLevel level,
            Vec3 worldPosition,
            Vec3 outwardNormal
    ) {
        if (!runtime.storageReady()) return null;
        Vec3 safeNormal = outwardNormal.lengthSqr() < 0.5
                ? new Vec3(0.0, 1.0, 0.0) : outwardNormal.normalize();
        Vec3 inside = worldPosition.subtract(safeNormal.scale(0.002));
        BlockPos pos = BlockPos.containing(inside);
        MicrovoxelVolume volume;
        try {
            volume = runtime.store().get(new MicrovoxelKey(
                    runtime.worldId(level), pos.getX(), pos.getY(), pos.getZ()));
        } catch (IllegalStateException unavailable) {
            return null;
        }
        if (volume == null) return null;
        int x = Math.max(0, Math.min(15,
                (int) Math.floor((inside.x - pos.getX()) * 16.0)));
        int y = Math.max(0, Math.min(15,
                (int) Math.floor((inside.y - pos.getY()) * 16.0)));
        int z = Math.max(0, Math.min(15,
                (int) Math.floor((inside.z - pos.getZ()) * 16.0)));
        int materialIndex = volume.materialIndex(MicrovoxelVolume.index(x, y, z));
        if (materialIndex <= 0 || materialIndex >= volume.palette().size()) return null;
        return MicrovoxelBlockStates.parseBlockState(volume.palette().get(materialIndex));
    }

    public void onExplosion(net.minecraft.world.level.ServerExplosion explosion) {
        if (!runtime.storageReady()) return;
        environment.onExplosion(explosion);
    }

    public VoxelShape collisionShape(ServerLevel level, BlockPos pos) {
        if (!runtime.storageReady()) return null;
        try {
            return collision.collisionShape(level, pos);
        } catch (IllegalStateException unavailable) {
            return null;
        }
    }

    public BlockState parentBlockState(ServerLevel level, BlockPos pos) {
        if (!runtime.storageReady()) return null;
        try {
            return collision.parentBlockState(level, pos);
        } catch (IllegalStateException unavailable) {
            return null;
        }
    }

    public Vec3 collide(Entity entity, Vec3 movement) {
        if (!runtime.storageReady()) return movement;
        try {
            return collision.collide(entity, movement);
        } catch (IllegalStateException unavailable) {
            return movement;
        }
    }

    /**
     * Survival sneak-break converts a volume into a portable item. Gated by the edit permission
     * so the portable loop (break -&gt; carry -&gt; place) cannot bypass build rights, and guarded
     * by storage readiness so a corrupt store fails closed instead of throwing per attack.
     */
    public boolean onBlockBreak(ServerPlayer player, BlockPos pos) {
        if (!runtime.storageReady()) return false;
        if (!RPChat.hasPermission(player, "rpchat.microvoxels.edit", 2)) return false;
        UUID worldId;
        try {
            worldId = runtime.worldId(player.level());
        } catch (IllegalStateException unavailable) {
            return false;
        }
        MicrovoxelKey key = new MicrovoxelKey(worldId, pos.getX(), pos.getY(), pos.getZ());
        MicrovoxelVolume volume = runtime.store().get(key);
        if (volume == null) return false;
        if (isProtected(key)) return true;

        if (player.gameMode.getGameModeForPlayer() == GameType.CREATIVE) {
            BlockState current = ((ServerLevel) player.level()).getBlockState(pos);
            if (!MicrovoxelBlocks.isMarker(current)) {
                ((ServerLevel) player.level()).setBlock(pos, markerState(volume), 2);
            }
            return true;
        }

        if (player.gameMode.getGameModeForPlayer() != GameType.SURVIVAL) {
            return true;
        }

        // Tool check, feedback and drops all resolve through the same parentage:
        // palette slot 1 is just the first material ever placed, while a volume
        // repainted 90% into oak must ask for an axe, not a pickaxe.
        String matStr = ua.rp.chat.microvoxel.MicrovoxelParentage.dominantMaterial(volume);
        BlockState parentState = matStr == null ? null
                : ua.rp.chat.microvoxel.MicrovoxelParentage.parentState(volume);
        if (!player.isShiftKeyDown()) {
            // Normal attacks mine single cells (server-authoritative); the marker never breaks.
            return true;
        }
        if (parentState == null) {
            // Nothing left to pick up: drop the empty shell silently instead of
            // letting vanilla shatter the marker with stone feedback.
            runtime.projection().dematerialize(key);
            collision.invalidate(key);
            sync.broadcastRemove(key);
            return true;
        }
        if (!player.hasCorrectToolForDrops(parentState)) {
            // Sneak attack without the right tool: vanilla rejects the break on its own.
            return false;
        }

        // Whole-volume pickup: vanilla never touches the marker (its break particles
        // and sounds would read as stone for every material). Mixed sculptures burst
        // once per leading material, capped so one pickup never spams the network.
        runtime.projection().dematerialize(key);
        collision.invalidate(key);
        sync.broadcastRemove(key);
        net.minecraft.server.level.ServerLevel breakLevel = (ServerLevel) player.level();
        for (String top : ua.rp.chat.microvoxel.MicrovoxelParentage.topMaterials(volume, 3)) {
            try {
                breakLevel.levelEvent(2001, pos,
                        net.minecraft.world.level.block.Block.getId(
                                ua.rp.chat.microvoxel.MicrovoxelBlockStates.parseBlockState(top)));
            } catch (RuntimeException unreadable) {
            }
        }
        breakLevel.playSound(null, pos,
                parentState.getSoundType().getBreakSound(),
                net.minecraft.sounds.SoundSource.BLOCKS, 1.0f, 0.8f);

        net.minecraft.world.level.block.Block dropBlock = Blocks.STONE;
        if (matStr != null) {
            try {
                dropBlock = MicrovoxelBlockStates.parseBlockState(matStr).getBlock();
            } catch (Exception parseFailure) {
                // Keep the stone fallback so a corrupt palette entry still drops a valid item.
            }
        }

        ItemStack dropItem = new ItemStack(dropBlock.asItem());
        List<Component> lore = new ArrayList<>();
        String name = dropBlock.toString().toUpperCase(java.util.Locale.ROOT);
        boolean isWood = name.contains("LOG") || name.contains("PLANKS") || name.contains("WOOD");
        boolean isStone = name.contains("STONE") || name.contains("DEEPSLATE")
                || name.contains("TUFF") || name.contains("BRICK");

        if (isWood) {
            lore.add(Component.literal("«Тонкая столярная работа»")
                    .withStyle(net.minecraft.ChatFormatting.ITALIC, net.minecraft.ChatFormatting.GRAY));
        } else if (isStone) {
            lore.add(Component.literal("«Искусная каменная кладка»")
                    .withStyle(net.minecraft.ChatFormatting.ITALIC, net.minecraft.ChatFormatting.GRAY));
        } else {
            lore.add(Component.literal("«Мастерски вырезанное изделие»")
                    .withStyle(net.minecraft.ChatFormatting.ITALIC, net.minecraft.ChatFormatting.GRAY));
        }

        int count = MicrovoxelVolume.CELL_COUNT - volume.occupiedCount();
        int starsCount = 1;
        if (count <= 100) starsCount = 1;
        else if (count <= 500) starsCount = 2;
        else if (count <= 1200) starsCount = 3;
        else if (count <= 2500) starsCount = 4;
        else starsCount = 5;

        lore.add(Component.literal("Сложность работы: ")
                .withStyle(net.minecraft.ChatFormatting.GRAY)
                .append(Component.literal("★".repeat(starsCount)).withStyle(net.minecraft.ChatFormatting.GOLD))
                .append(Component.literal("★".repeat(5 - starsCount)).withStyle(net.minecraft.ChatFormatting.DARK_GRAY)));

        dropItem.set(DataComponents.LORE, new net.minecraft.world.item.component.ItemLore(lore));

        CompoundTag tag = new CompoundTag();
        try {
            tag.putByteArray("microvoxel_volume", serializeVolume(volume));
            if (matStr != null) {
                tag.putString("parent_material", matStr);
            }
            net.minecraft.world.item.component.CustomData.set(DataComponents.CUSTOM_DATA, dropItem, tag);
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to serialize volume on break: " + e.getMessage());
        }

        Vec3 dropLoc = new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        net.minecraft.world.entity.item.ItemEntity entity = new net.minecraft.world.entity.item.ItemEntity(
                ((ServerLevel) player.level()), dropLoc.x, dropLoc.y, dropLoc.z, dropItem);
        ((ServerLevel) player.level()).addFreshEntity(entity);
        return true;
    }

    public boolean onBlockPlace(ServerPlayer player, ItemStack item, BlockPos pos) {
        if (item == null || item.isEmpty()) return false;
        if (!runtime.storageReady()) return false;
        if (!RPChat.hasPermission(player, "rpchat.microvoxels.edit", 2)) return false;
        try {
            if (isProtected(new MicrovoxelKey(runtime.worldId(player.level()),
                    pos.getX(), pos.getY(), pos.getZ()))) return false;
        } catch (IllegalStateException unavailable) {
            return false;
        }
        net.minecraft.world.item.component.CustomData pdc = item.get(DataComponents.CUSTOM_DATA);
        if (pdc == null || !pdc.copyTag().contains("microvoxel_volume")) return false;

        byte[] bytes = pdc.copyTag().getByteArray("microvoxel_volume").orElse(null);
        if (bytes == null) return false;

        UUID worldId = runtime.worldId(player.level());
        MicrovoxelKey key = new MicrovoxelKey(worldId, pos.getX(), pos.getY(), pos.getZ());
        ServerLevel level = (ServerLevel) player.level();
        if (runtime.store().get(key) != null || !level.getBlockState(pos).isAir()
                || runtime.store().countInChunk(worldId, key.chunkX(), key.chunkZ())
                >= MicrovoxelRuntime.MAX_PER_CHUNK) {
            return false;
        }
        try {
            MicrovoxelVolume volume = deserializeVolume(bytes);
            validatePortableVolume(volume, level, pos);
            MicrovoxelVolume copy = MicrovoxelVolume.restore(1, volume.palette(), volume.cellsCopy());
            runtime.projection().materialize(key, copy);
            sync.broadcastUpsert(key, copy);
            if (player.gameMode.getGameModeForPlayer() != GameType.CREATIVE) {
                item.shrink(1);
            }
        } catch (IOException | IllegalArgumentException e) {
            plugin.getLogger().warning("Rejected invalid microvoxel item from "
                    + player.getScoreboardName() + ": " + e.getMessage());
            sync.feedback(player, "Данные микровоксельного предмета повреждены или небезопасны.");
        }
        return true;
    }

    /** Direct fluid store access for the sim and physics refinement. */
    public FluidStore fluidStore() {
        return fluidStore;
    }

    /** Direct microvoxel store access for redstone and physics refinements. */
    public MicrovoxelStore microvolumes() {
        return runtime.store();
    }

    /** Shared runtime for sibling systems (Carver drafting commits through it). */
    public MicrovoxelRuntime runtime() {
        return runtime;
    }

    /** Authoritative broadcast hub for sibling systems. */
    public MicrovoxelSyncHub syncHub() {
        return sync;
    }

    /** Collision cache for sibling systems committing geometry. */
    public MicrovoxelCollision collision() {
        return collision;
    }

    /** Material economy for sibling systems issuing refunds. */
    public MicrovoxelMaterialEconomy economy() {
        return economy;
    }

    /** Edit history for sibling systems recording undoable transactions. */
    public MicrovoxelEditHistory history() {
        return history;
    }

    /** Wet-volume count; the entity hot path gates on this before any lookup. */
    public int fluidCount() {
        return fluidStore.size();
    }

    /** World identity for fluid keys; throws when storage is unavailable. */
    public UUID runtimeWorldId(net.minecraft.world.level.Level level) {
        return runtime.worldId(level);
    }

    /** True when live (non-dry) fluid data exists for this position. */
    public boolean hasFluid(MicrovoxelKey key) {
        return fluidKind(key) != null;
    }

    /**
     * Live fluid kind for one position, or null when dry/absent. Single map lookup; every
     * projection, light and bucket path shares it so water and lava can never disagree.
     */
    public FluidVolume.Kind fluidKind(MicrovoxelKey key) {
        FluidVolume fluid = fluidStore.get(key);
        return fluid != null && !fluid.isDry() ? fluid.kind() : null;
    }

    /**
     * Settles one fluid volume against freshly edited geometry. Runs inside projection, hence
     * on every mutation path at once (edits, mining, undo, environment): purged levels are
     * displaced upward, floating water falls in the same tick it was orphaned.
     */
    void settleFluid(MicrovoxelKey key, MicrovoxelVolume volume) {
        FluidVolume fluid = fluidStore.get(key);
        if (fluid == null || fluid.isDry() || volume == null) return;
        boolean[] solid = FluidSim.solidScratch(volume);
        long[] deleted = {0};
        int changed = fluid.settleWith(solid, deleted);
        if (changed > 0) {
            fluidStore.markDirty();
            MicrovoxelMetrics.add("fluid.settledCells", changed);
        }
        if (deleted[0] > 0) MicrovoxelMetrics.add("fluid.purged", deleted[0]);
    }

    private void saveFluids(String reason) {
        if (fluidFile == null || !fluidStore.isDirty()) return;
        try {
            long started = System.nanoTime();
            fluidStore.save(fluidFile);
            long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
            MicrovoxelMetrics.inc("fluid.saves");
            if (elapsedMs > 100L) {
                plugin.getLogger().warning("[MICROVOXEL] Fluid save (" + reason + ") took "
                        + elapsedMs + "ms for " + fluidStore.size() + " volumes.");
            }
        } catch (IOException | RuntimeException error) {
            plugin.getLogger().severe("Unable to save fluid volumes: " + error.getMessage());
        }
    }

    /**
     * Fills a carved basin from a water bucket. One bucket always fills the whole basin to
     * the brim (generous and fun); the marker goes waterlogged in the same action so vanilla
     * physics, rendering and sounds engage instantly with zero desync window.
     */
    public boolean fillWithBucket(ServerPlayer player, ServerLevel level, BlockPos pos,
                                  net.minecraft.world.InteractionHand hand) {
        if (!runtime.storageReady()) return false;
        if (!RPChat.hasPermission(player, "rpchat.microvoxels.edit", 2)) return false;
        UUID worldId;
        try {
            worldId = runtime.worldId(level);
        } catch (IllegalStateException unavailable) {
            return false;
        }
        MicrovoxelKey key = new MicrovoxelKey(worldId, pos.getX(), pos.getY(), pos.getZ());
        MicrovoxelVolume volume = runtime.store().get(key);
        if (!FluidSim.isBasin(volume)) {
            player.sendSystemMessage(Component.literal("Тут нема чаші для води: потрібна вирізана порожнина."), true);
            return false;
        }
        if (isProtected(key)) {
            player.sendSystemMessage(Component.literal("Цей мікровоксельний об'єм захищено від змін."), true);
            return false;
        }
        FluidVolume.Kind existing = fluidKind(key);
        if (existing == FluidVolume.Kind.WATER) {
            player.sendSystemMessage(Component.literal("Чаша вже повна."), true);
            return false;
        }
        if (existing == FluidVolume.Kind.LAVA) {
            // Water over lava crusts instead of mixing (vanilla parity, miniature foundry).
            quenchSurface(player, level, pos, key, volume);
            consumeWaterBucket(player, hand);
            return true;
        }
        // Same gate vanilla buckets use: water evaporates in ultra-warm dimensions.
        if (Boolean.TRUE.equals(level.environmentAttributes().getValue(
                net.minecraft.world.attribute.EnvironmentAttributes.WATER_EVAPORATES, pos))) {
            level.playSound(null, pos,
                    net.minecraft.sounds.SoundEvents.FIRE_EXTINGUISH,
                    net.minecraft.sounds.SoundSource.BLOCKS, 0.5f, 2.6f);
            MicrovoxelMetrics.inc("fluid.netherDenied");
            return true;
        }
        FluidVolume fluid = FluidVolume.empty();
        fluid.fillMasked(FluidSim.airMask(volume));
        fluidStore.put(key, fluid);
        // Full recompute, not a flag flip: underwater wicks burn out, so the light level
        // must be re-derived or a dowsed torch would keep glowing at 14.
        level.setBlock(pos, markerState(volume, true), 3);
        // A short bubble burst marks the fill moment; the steady state needs no particles.
        level.sendParticles(net.minecraft.core.particles.ParticleTypes.BUBBLE_COLUMN_UP,
                pos.getX() + 0.5, pos.getY() + 0.6, pos.getZ() + 0.5, 4, 0.25, 0.1, 0.25, 0.05);
        // Instant surface for the filler: broadcast levels now instead of waiting for the
        // throttled sync, and record it so the next tick does not echo.
        for (ServerPlayer observer : sync.nearbyPlayers(key)) {
            sync.sendFluidUpsert(observer, key, fluid.revision(),
                    fluid.kind().code(), fluid.levelsCopy());
        }
        fluidSim.markSynced(key, fluid.revision());
        consumeWaterBucket(player, hand);
        level.playSound(null, pos, net.minecraft.sounds.SoundEvents.BUCKET_EMPTY,
                net.minecraft.sounds.SoundSource.BLOCKS, 1.0f, 1.0f);
        MicrovoxelMetrics.inc("fluid.fills");
        MicrovoxelMetrics.add("fluid.cells", fluid.totalUnits() / FluidVolume.MAX_LEVEL);
        return true;
    }

    /**
     * Stocks a wet basin with a mob bucket (tropical fish, axolotl and friends): the entity
     * spawns above the basin with the bucket's own data applied, exactly like vanilla water.
     * Dry basins and protected volumes refuse; the bucket is consumed like a water fill.
     */
    public boolean stockWithBucket(ServerPlayer player, ServerLevel level, BlockPos pos,
                                   net.minecraft.world.InteractionHand hand, ItemStack held) {
        net.minecraft.world.entity.EntityType<?> type = fishBucketType(held);
        if (type == null || !runtime.storageReady()) return false;
        if (!RPChat.hasPermission(player, "rpchat.microvoxels.edit", 2)) return false;
        UUID worldId;
        try {
            worldId = runtime.worldId(level);
        } catch (IllegalStateException unavailable) {
            return false;
        }
        MicrovoxelKey key = new MicrovoxelKey(worldId, pos.getX(), pos.getY(), pos.getZ());
        if (runtime.store().get(key) == null || !hasFluid(key) || isProtected(key)) return false;
        // Fish stock wet water only: lava crucibles are not aquariums.
        if (fluidKind(key) != FluidVolume.Kind.WATER) {
            player.sendSystemMessage(Component.literal("Риба в лаву? Серйозно?"), true);
            return false;
        }
        BlockPos swim = pos.above();
        if (!level.getBlockState(swim).isAir() && !level.getFluidState(swim).is(
                net.minecraft.world.level.material.Fluids.WATER)) {
            player.sendSystemMessage(Component.literal("Над чашею нема місця для мешканця."), true);
            return false;
        }
        net.minecraft.world.entity.Entity spawned = type.spawn(
                level, held, player, swim,
                net.minecraft.world.entity.EntitySpawnReason.BUCKET, false, false);
        if (spawned == null) return false;
        consumeWaterBucket(player, hand);
        level.playSound(null, pos, net.minecraft.sounds.SoundEvents.BUCKET_EMPTY_AXOLOTL,
                net.minecraft.sounds.SoundSource.BLOCKS, 1.0f, 1.0f);
        MicrovoxelMetrics.inc("fluid.stocked");
        return true;
    }

    /** Maps mob buckets to their entity types; null means "not a stockable bucket". */
    public static net.minecraft.world.entity.EntityType<?> fishBucketType(ItemStack stack) {
        if (stack.is(net.minecraft.world.item.Items.TROPICAL_FISH_BUCKET)) {
            return net.minecraft.world.entity.EntityType.TROPICAL_FISH;
        }
        if (stack.is(net.minecraft.world.item.Items.PUFFERFISH_BUCKET)) {
            return net.minecraft.world.entity.EntityType.PUFFERFISH;
        }
        if (stack.is(net.minecraft.world.item.Items.SALMON_BUCKET)) {
            return net.minecraft.world.entity.EntityType.SALMON;
        }
        if (stack.is(net.minecraft.world.item.Items.COD_BUCKET)) {
            return net.minecraft.world.entity.EntityType.COD;
        }
        if (stack.is(net.minecraft.world.item.Items.AXOLOTL_BUCKET)) {
            return net.minecraft.world.entity.EntityType.AXOLOTL;
        }
        if (stack.is(net.minecraft.world.item.Items.TADPOLE_BUCKET)) {
            return net.minecraft.world.entity.EntityType.TADPOLE;
        }
        return null;
    }

    /**
     * Dispenser fills: same guarded basin path as the hand bucket, minus the player (no
     * permission actor — redstone fountains are a feature, protection still enforced).
     * Returns true when the basin took water and the stack was consumed.
     */
    public boolean fillFromDispenser(ServerLevel level, BlockPos pos) {
        return fillFromDispenser(level, pos, FluidVolume.Kind.WATER);
    }

    /** Kind-aware dispenser fill shared by water and lava buckets. */
    public boolean fillFromDispenser(ServerLevel level, BlockPos pos, FluidVolume.Kind kind) {
        if (!runtime.storageReady()) return false;
        UUID worldId;
        try {
            worldId = runtime.worldId(level);
        } catch (IllegalStateException unavailable) {
            return false;
        }
        MicrovoxelKey key = new MicrovoxelKey(worldId, pos.getX(), pos.getY(), pos.getZ());
        MicrovoxelVolume volume = runtime.store().get(key);
        if (!FluidSim.isBasin(volume) || isProtected(key) || hasFluid(key)) return false;
        if (kind == FluidVolume.Kind.WATER && Boolean.TRUE.equals(level.environmentAttributes().getValue(
                net.minecraft.world.attribute.EnvironmentAttributes.WATER_EVAPORATES, pos))) {
            return false;
        }
        FluidVolume fluid = FluidVolume.empty(kind);
        fluid.fillMasked(FluidSim.airMask(volume));
        fluidStore.put(key, fluid);
        level.setBlock(pos, markerState(volume, kind), 3);
        for (ServerPlayer observer : sync.nearbyPlayers(key)) {
            sync.sendFluidUpsert(observer, key, fluid.revision(),
                    fluid.kind().code(), fluid.levelsCopy());
        }
        fluidSim.markSynced(key, fluid.revision());
        MicrovoxelMetrics.inc("fluid.dispensed");
        return true;
    }

    /**
     * Fills a carved basin with lava. No waterlogged flag (it would read as water to vanilla
     * physics), no Nether gate (lava belongs there); the marker burns at full brightness
     * through {@link #markerLightLevel}. Pouring lava over water quenches instead of mixing.
     */
    public boolean fillWithLavaBucket(ServerPlayer player, ServerLevel level, BlockPos pos,
                                      net.minecraft.world.InteractionHand hand) {
        if (!runtime.storageReady()) return false;
        if (!RPChat.hasPermission(player, "rpchat.microvoxels.edit", 2)) return false;
        UUID worldId;
        try {
            worldId = runtime.worldId(level);
        } catch (IllegalStateException unavailable) {
            return false;
        }
        MicrovoxelKey key = new MicrovoxelKey(worldId, pos.getX(), pos.getY(), pos.getZ());
        MicrovoxelVolume volume = runtime.store().get(key);
        if (!FluidSim.isBasin(volume)) {
            player.sendSystemMessage(Component.literal("Тут нема чаші для лави: потрібна вирізана порожнина."), true);
            return false;
        }
        if (isProtected(key)) {
            player.sendSystemMessage(Component.literal("Цей мікровоксельний об'єм захищено від змін."), true);
            return false;
        }
        FluidVolume.Kind existing = fluidKind(key);
        if (existing == FluidVolume.Kind.LAVA) {
            player.sendSystemMessage(Component.literal("Чаша вже повна лави."), true);
            return false;
        }
        if (existing == FluidVolume.Kind.WATER) {
            quenchSurface(player, level, pos, key, volume);
            consumeLavaBucket(player, hand);
            return true;
        }
        FluidVolume fluid = FluidVolume.empty(FluidVolume.Kind.LAVA);
        fluid.fillMasked(FluidSim.airMask(volume));
        fluidStore.put(key, fluid);
        level.setBlock(pos, markerState(volume, FluidVolume.Kind.LAVA), 3);
        consumeLavaBucket(player, hand);
        level.playSound(null, pos, net.minecraft.sounds.SoundEvents.BUCKET_EMPTY_LAVA,
                net.minecraft.sounds.SoundSource.BLOCKS, 1.0f, 1.0f);
        for (ServerPlayer observer : sync.nearbyPlayers(key)) {
            sync.sendFluidUpsert(observer, key, fluid.revision(),
                    fluid.kind().code(), fluid.levelsCopy());
        }
        fluidSim.markSynced(key, fluid.revision());
        MicrovoxelMetrics.inc("fluid.lavaFills");
        return true;
    }

    /**
     * Quenching (both directions, vanilla parity): water poured over lava — or lava over
     * water — crusts the topmost wet cells into cobblestone instead of mixing fluids.
     * Miniature foundries and stone generators work exactly like players expect.
     */
    private void quenchSurface(ServerPlayer actor, ServerLevel level, BlockPos pos,
                               MicrovoxelKey key, MicrovoxelVolume volume) {
        FluidVolume fluid = fluidStore.get(key);
        if (fluid == null) return;
        boolean[] solid = FluidSim.solidScratch(volume);
        java.util.List<Integer> crusted = FluidVolume.freezeTopCells(fluid.levelsDirect(), solid);
        int placed = 0;
        for (int cell : crusted) {
            if (volume.put(cell, "minecraft:cobblestone")) placed++;
        }
        if (placed == 0) return;
        fluid.setRevision(fluid.revision() + 1);
        fluidStore.markDirty();
        runtime.projection().materialize(key, volume);
        for (ServerPlayer observer : sync.nearbyPlayers(key)) {
            sync.sendUpsert(observer, key, volume);
            sync.sendFluidUpsert(observer, key, fluid.revision(),
                    fluid.kind().code(), fluid.levelsCopy());
        }
        fluidSim.markSynced(key, fluid.revision());
        level.playSound(null, pos, net.minecraft.sounds.SoundEvents.FIRE_EXTINGUISH,
                net.minecraft.sounds.SoundSource.BLOCKS, 1.0f, 1.8f);
        MicrovoxelMetrics.add("fluid.quenched", placed);
    }

    /**
     * Scoops lava volumes (vanilla cannot: the marker carries no lava fluidstate, so the
     * native pickup finds nothing). Mirrors the water scoop 1:1 — data drops, bucket fills.
     */
    public boolean scoopLavaBucket(ServerPlayer player, ServerLevel level, BlockPos pos,
                                   net.minecraft.world.InteractionHand hand) {
        if (!runtime.storageReady()) return false;
        if (!RPChat.hasPermission(player, "rpchat.microvoxels.edit", 2)) return false;
        UUID worldId;
        try {
            worldId = runtime.worldId(level);
        } catch (IllegalStateException unavailable) {
            return false;
        }
        MicrovoxelKey key = new MicrovoxelKey(worldId, pos.getX(), pos.getY(), pos.getZ());
        FluidVolume fluid = fluidStore.get(key);
        if (fluid == null || fluid.isDry() || !fluid.isLava() || isProtected(key)) return false;
        // Routed through the sim so sync maps stay consistent (no leaked cursors).
        fluidSim.dropFluid(key);
        ItemStack held = player.getItemInHand(hand);
        if (!player.isCreative()) {
            held.shrink(1);
            ItemStack lava = new ItemStack(net.minecraft.world.item.Items.LAVA_BUCKET);
            if (held.isEmpty()) {
                player.setItemInHand(hand, lava);
            } else if (!player.getInventory().add(lava)) {
                player.drop(lava, false);
            }
        }
        level.playSound(null, pos, net.minecraft.sounds.SoundEvents.BUCKET_FILL_LAVA,
                net.minecraft.sounds.SoundSource.BLOCKS, 1.0f, 1.0f);
        MicrovoxelMetrics.inc("fluid.lavaScoops");
        return true;
    }

    private static void consumeWaterBucket(ServerPlayer player, net.minecraft.world.InteractionHand hand) {
        if (player.isCreative()) return;
        ItemStack held = player.getItemInHand(hand);
        held.shrink(1);
        ItemStack empty = new ItemStack(net.minecraft.world.item.Items.BUCKET);
        if (held.isEmpty()) {
            player.setItemInHand(hand, empty);
        } else if (!player.getInventory().add(empty)) {
            player.drop(empty, false);
        }
    }

    private static void consumeLavaBucket(ServerPlayer player, net.minecraft.world.InteractionHand hand) {
        if (player.isCreative()) return;
        ItemStack held = player.getItemInHand(hand);
        held.shrink(1);
        ItemStack empty = new ItemStack(net.minecraft.world.item.Items.BUCKET);
        if (held.isEmpty()) {
            player.setItemInHand(hand, empty);
        } else if (!player.getInventory().add(empty)) {
            player.drop(empty, false);
        }
    }

    public boolean isPortableVolumeItem(ItemStack item) {
        if (item == null || item.isEmpty()) return false;
        net.minecraft.world.item.component.CustomData custom =
                item.get(DataComponents.CUSTOM_DATA);
        return custom != null && custom.copyTag().contains("microvoxel_volume");
    }

    public boolean isPartiallyConsumedMaterial(ItemStack item) {
        return economy.isPartiallyConsumedMaterial(item);
    }

    private void validatePortableVolume(MicrovoxelVolume volume, ServerLevel level, BlockPos pos)
            throws IOException {
        if (volume.occupiedCount() == 0) throw new IOException("Empty portable volume");
        for (int index = 1; index < volume.palette().size(); index++) {
            String encoded = volume.palette().get(index);
            BlockState state = MicrovoxelBlockStates.parseBlockState(encoded);
            if (!MicrovoxelBlockStates.getBlockStateString(state).equals(encoded)
                    || !MicrovoxelEligibility.isEligibleMaterialState(state, pos, level)) {
                throw new IOException("Invalid portable material " + encoded);
            }
        }
    }

    private byte[] serializeVolume(MicrovoxelVolume volume) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (DataOutputStream dos = new DataOutputStream(bos)) {
            dos.writeInt(volume.revision());
            dos.writeByte(volume.palette().size());
            for (String material : volume.palette()) {
                byte[] utf8 = material.getBytes(StandardCharsets.UTF_8);
                dos.writeShort(utf8.length);
                dos.write(utf8);
            }
            dos.write(volume.cellsCopy());
        }
        return bos.toByteArray();
    }

    private MicrovoxelVolume deserializeVolume(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length < MicrovoxelVolume.CELL_COUNT + 5
                || bytes.length > 1_048_576) {
            throw new IOException("Invalid portable volume size");
        }
        ByteArrayInputStream bis = new ByteArrayInputStream(bytes);
        try (DataInputStream dis = new DataInputStream(bis)) {
            int revision = dis.readInt();
            int paletteSize = dis.readUnsignedByte();
            if (paletteSize < 1 || paletteSize > MicrovoxelVolume.MAX_PALETTE) {
                throw new IOException("Invalid portable palette size");
            }
            List<String> palette = new ArrayList<>(paletteSize);
            for (int i = 0; i < paletteSize; i++) {
                int length = dis.readUnsignedShort();
                byte[] utf8 = dis.readNBytes(length);
                if (utf8.length != length) throw new java.io.EOFException("Truncated portable palette");
                palette.add(new String(utf8, StandardCharsets.UTF_8));
            }
            byte[] cells = dis.readNBytes(MicrovoxelVolume.CELL_COUNT);
            if (cells.length != MicrovoxelVolume.CELL_COUNT) {
                throw new java.io.EOFException("Truncated portable volume");
            }
            if (dis.read() != -1) throw new IOException("Trailing portable volume bytes");
            return MicrovoxelVolume.restore(revision, palette, cells);
        }
    }

    public void refreshAdjacentMicrovoxelMeshes(ServerLevel world, BlockPos pos) {
        scheduleBoundaryRefresh(world, pos);
    }

    private void scheduleBoundaryRefresh(ServerLevel world, BlockPos pos) {
        int x = pos.getX();
        int y = pos.getY();
        int z = pos.getZ();
        plugin.getServer().execute(() -> {
            UUID worldId = runtime.worldId(world);
            for (int[] direction : BOUNDARY_DIRECTIONS) {
                MicrovoxelKey adjacent = new MicrovoxelKey(worldId,
                        x + direction[0], y + direction[1], z + direction[2]);
                MicrovoxelVolume volume = runtime.store().get(adjacent);
                if (volume != null) {
                    sync.broadcastUpsert(adjacent, volume);
                    plugin.getLogger().fine("[MICROVOXEL] boundary mesh refresh "
                            + adjacent.x() + "," + adjacent.y() + "," + adjacent.z());
                }
            }
        });
    }

    public void restoreMarkers(ServerLevel world, LevelChunk chunk) {
        // C2ME can invoke CHUNK_LOAD while it is still promoting this chunk. This callback only
        // schedules whole-chunk reconciliation and must never touch the world synchronously.
        if (runtime.projection() != null) {
            runtime.projection().scheduleReconcile(new ChunkKey(
                    runtime.worldId(world), chunk.getPos().x(), chunk.getPos().z()));
        }
    }

    /**
     * Copies the whole storage tree (regions, flags, journal) into a timestamped backup folder
     * and prunes older backups beyond the last three. Runs on the calling thread: invoke from
     * an operator command, never from the tick loop.
     */
    public String backupVolumes() throws java.io.IOException {
        if (storagePath == null) throw new java.io.IOException("Microvoxel storage is not initialized");
        Path backupRoot = storagePath.resolveSibling("microvoxel-backups");
        Path created = MicrovoxelStore.backupDirectory(storagePath, backupRoot, 3);
        MicrovoxelMetrics.inc("store.backups");
        return created.toString();
    }

    /**
     * Toggles protection on the looked-at volume for the operator protect/unprotect commands.
     * Reports the outcome directly to the player; returns false when nothing was targeted.
     */
    public boolean protectLookedAt(ServerPlayer player, boolean protect) {
        ServerMicrovoxelRaycaster.Hit hit = engine.raycastMicrovoxel(player);
        if (hit == null) {
            player.sendSystemMessage(Component.literal("Подивіться на microvoxel-блок у межах досяжності."));
            return false;
        }
        setProtected(player, hit.key(), protect);
        player.sendSystemMessage(Component.literal(protect
                ? "Мікровоксельний об'єм захищено від змін."
                : "Захист мікровоксельного об'єму знято."));
        return true;
    }

    public boolean restoreLookedAt(ServerPlayer player) {
        ServerMicrovoxelRaycaster.Hit hit = engine.raycastMicrovoxel(player);
        if (hit == null) {
            player.sendSystemMessage(Component.literal("Подивіться на microvoxel-блок у межах досяжності."));
            return false;
        }
        MicrovoxelVolume volume = runtime.store().get(hit.key());
        if (volume == null) return false;
        collision.invalidate(hit.key());
        BlockState restored = Blocks.STONE.defaultBlockState();
        for (int index = 1; index < volume.palette().size(); index++) {
            String material = volume.palette().get(index);
            if (material != null && !material.isBlank()) {
                restored = MicrovoxelBlockStates.parseBlockState(material);
                break;
            }
        }
        ServerLevel level = (ServerLevel) player.level();
        boolean hadFluid = hasFluid(hit.key());
        runtime.projection().replaceWithBlock(hit.key(), restored);
        sync.broadcastRemove(hit.key());
        if (hadFluid) {
            // Consistent with demolish-spills: the fluid has to go somewhere, so it pours
            // below when there is air, exactly like a broken waterlogged block — lava
            // pours lava, not water. Routing through the sim keeps sync maps and the
            // client surface consistent instead of leaking either.
            FluidVolume.Kind kind = fluidKind(hit.key());
            fluidSim.dropFluid(hit.key());
            BlockPos below = new BlockPos(hit.key().x(), hit.key().y() - 1, hit.key().z());
            if (level.getBlockState(below).isAir()) {
                level.setBlock(below, kind == FluidVolume.Kind.LAVA
                        ? net.minecraft.world.level.block.Blocks.LAVA.defaultBlockState()
                        : net.minecraft.world.level.block.Blocks.WATER.defaultBlockState(), 3);
            }
            MicrovoxelMetrics.inc("fluid.spills");
        }
        scheduleBoundaryRefresh(level, new BlockPos(hit.key().x(), hit.key().y(), hit.key().z()));
        player.sendSystemMessage(Component.literal("Microvoxel-блок повернуто до звичайного блоку."));
        return true;
    }

    /**
     * Operator status: volume count, storage health, journal backlog and the live metrics
     * summary. Backlog growth here (dirty volumes, journal MB, resync storms) is the first
     * signal that persistence or sync needs attention.
     */
    public String status() {
        int volumes = runtime.store() == null ? 0 : runtime.store().size();
        long dirty = runtime.store() == null ? 0 : runtime.store().dirtyCount();
        long journalBytes = runtime.store() == null ? 0 : runtime.store().journalSizeBytes();
        int protectedVolumes = volumeFlags == null ? 0 : volumeFlags.size();
        return "Microvoxel: " + volumes + " томів (dirty " + dirty
                + ", журнал " + (journalBytes / 1024) + " КБ, захищено " + protectedVolumes
                + ", води " + fluidStore.size() + " томів/" + fluidStore.totalUnits() + " од.); сховище "
                + (runtime.storageAvailable() ? "доступне" : "недоступне") + ". "
                + MicrovoxelMetrics.summarize();
    }

    /** Null-safe protection probe used by every mutation entry point. */
    public boolean isProtected(MicrovoxelKey key) {
        MicrovoxelFlags flags = runtime.flags();
        return key != null && flags != null && flags.isProtected(key);
    }

    public MicrovoxelFlags flags() {
        return volumeFlags;
    }

    /**
     * Toggles protection for the volume under the player's crosshair. Returns the new state;
     * protection is enforced for edits, mining, explosions, fire and portable break/place.
     */
    public boolean setProtected(ServerPlayer player, MicrovoxelKey key, boolean protect) {
        volumeFlags.set(key, protect ? MicrovoxelFlags.PROTECTED : 0);
        MicrovoxelVolume volume = runtime.store().get(key);
        if (volume != null) sync.broadcastUpsert(key, volume);
        return protect;
    }

    public void startMining(ServerPlayer player, BlockPos pos) {
        if (player == null) return;
        try {
            MicrovoxelKey key = new MicrovoxelKey(runtime.worldId(player.level()),
                    pos.getX(), pos.getY(), pos.getZ());
            if (isProtected(key)) return;
        } catch (IllegalStateException unavailable) {
            return;
        }
        mining.startMining(player, pos);
    }

    /**
     * Derives the projected marker blockstate. Light is fractional (see
     * {@link MicrovoxelVolume#emissionLevel}): only exposed emissive cells contribute, scaled
     * by coverage, so a lone torch glows dimly instead of lighting the whole block at 14.
     */
    static BlockState markerState(MicrovoxelVolume volume) {
        return markerState(volume, false);
    }

    /**
     * Full marker state including the fluid flag. Projection routes every materialize through
     * here with live fluid presence, so editing a wet volume never flickers its water off.
     */
    static BlockState markerState(MicrovoxelVolume volume, boolean waterlogged) {
        return markerState(volume, waterlogged ? FluidVolume.Kind.WATER : null);
    }

    /**
     * Kind-aware marker state. Lava never sets the waterlogged flag (it would read as water
     * to vanilla physics) but always burns at full brightness.
     */
    static BlockState markerState(MicrovoxelVolume volume, FluidVolume.Kind kind) {
        int lightLevel = markerLightLevel(volume, kind);
        int soundProfile = 0;
        boolean soundSelected = false;
        boolean[] usedMaterials = new boolean[volume.palette().size()];
        volume.collectUsedMaterials(usedMaterials);
        for (int index = 1; index < volume.palette().size(); index++) {
            if (!usedMaterials[index]) continue;
            try {
                BlockState material = MicrovoxelBlockStates.parseBlockState(volume.palette().get(index));
                if (!soundSelected) {
                    soundProfile = MicrovoxelBlocks.soundProfile(material);
                    soundSelected = true;
                }
            } catch (RuntimeException ignored) {
            }
        }
        return MicrovoxelBlocks.markerState(
                lightLevel, soundProfile, kind == FluidVolume.Kind.WATER);
    }

    /**
     * Marker light level by fluid kind. Lava always burns at full brightness (vanilla lava
     * reads 15 at any amount); water uses the fractional exposed-emission formula with
     * dowsed wicks burned out; dry volumes use the same formula unwaterlogged.
     */
    static int markerLightLevel(MicrovoxelVolume volume, FluidVolume.Kind kind) {
        if (kind == FluidVolume.Kind.LAVA) return 15;
        boolean waterlogged = kind == FluidVolume.Kind.WATER;
        return volume.emissionLevel(material -> {
            if (waterlogged && isDowsedMaterial(material)) return 0;
            try {
                return MicrovoxelBlockStates.parseBlockState(material).getLightEmission();
            } catch (RuntimeException unparsable) {
                return 0;
            }
        });
    }

    /**
     * Light-engine view of one position: the parent material state when the volume is sealed,
     * {@code null} otherwise (the engine keeps the marker state). Lava is the single
     * exception: a sealed forge keeps the glowing marker instead of going dark, because its
     * whole purpose is shining through. Water builds keep the released behavior (sealed
     * means dark) — changing that would relight existing builds.
     */
    public BlockState lightState(ServerLevel level, BlockPos pos) {
        if (!runtime.storageReady()) return null;
        try {
            UUID worldId = runtime.worldId(level);
            MicrovoxelKey key = new MicrovoxelKey(worldId, pos.getX(), pos.getY(), pos.getZ());
            if (runtime.store().get(key) == null) return null;
            if (fluidKind(key) == FluidVolume.Kind.LAVA) return null;
            return collision.lightState(level, pos);
        } catch (IllegalStateException unavailable) {
            return null;
        }
    }

    /**
     * Open-flame materials drown underwater (torches, candles). Everything else keeps its
     * vanilla emission, so sea lanterns and glowstone stay lit while wicks go dark.
     */
    static boolean isDowsedMaterial(String material) {
        String id = material.toLowerCase(java.util.Locale.ROOT);
        int properties = id.indexOf('[');
        String name = properties < 0 ? id : id.substring(0, properties);
        return name.contains("torch") || name.contains("candle");
    }

    public static BlockState parseBlockState(String stateStr) {
        return MicrovoxelBlockStates.parseBlockState(stateStr);
    }

    static boolean isBlockEntityState(BlockState state) {
        return MicrovoxelEligibility.isBlockEntityState(state);
    }

    static double clipAgainst(AABB moving, AABB obstacle, double movement, Axis axis) {
        return MicrovoxelCollision.clipAgainst(moving, obstacle, movement, axis);
    }

    static double clipGrid(MicrovoxelVolume.CollisionPlan plan, int blockX, int blockY, int blockZ,
                           AABB moving, double movement, Axis axis) {
        return MicrovoxelCollision.clipGrid(plan, blockX, blockY, blockZ, moving, movement, axis);
    }

    public enum Axis { X, Y, Z }

    private record RateWindow(long startedAt, int count) {
    }
}