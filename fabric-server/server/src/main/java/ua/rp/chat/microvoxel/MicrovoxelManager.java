package ua.rp.chat.microvoxel;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.BitSetDiscreteVoxelShape;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import ua.rp.chat.RPChat;
import ua.rp.chat.heavyhammer.HeavyHammerImpact;
import ua.rp.chat.client.microvoxel.MicrovoxelSyncPayload;
import ua.rp.chat.mixin.CubeVoxelShapeInvoker;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class MicrovoxelManager {
    private static final int SYNC_RADIUS_CHUNKS = 8;
    private static final int MAX_PER_CHUNK = 512;
    private static final double MAX_REACH = 6.25;
    private static final double EPSILON = 1.0E-7;
    private static final long ACTION_WINDOW_MS = 1_000L;
    private static final int MAX_ACTIONS_PER_WINDOW = 40;
    private static final double CLIENT_LOOK_MAX_DIVERGENCE_DEGREES = 4.0;
    private static final double CLIENT_LOOK_MIN_DOT = Math.cos(Math.toRadians(CLIENT_LOOK_MAX_DIVERGENCE_DEGREES));
    private static final double CLIENT_EYE_MAX_DELTA = 0.75;
    private static final int MAX_MARKER_RESTORES_PER_TICK = 64;
    private static final int MAX_MARKER_CHUNKS_PER_TICK = 16;
    private static final int[][] BOUNDARY_DIRECTIONS = {
            {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}
    };

    private final RPChat plugin;
    private final MicrovoxelStore store;
    private final Map<UUID, PlayerSyncPosition> syncPositions = new ConcurrentHashMap<>();
    private final Map<UUID, Set<ChunkKey>> playerSubscriptions = new ConcurrentHashMap<>();
    private final Map<UUID, Map<String, Integer>> playerDictionaries = new ConcurrentHashMap<>();
    private final Map<UUID, RateWindow> actionRates = new ConcurrentHashMap<>();
    private final Map<UUID, Long> miningStartTimes = new ConcurrentHashMap<>();
    private final Map<MicrovoxelKey, CachedCollisionShape> collisionShapes = new ConcurrentHashMap<>();
    private final CoalescingWorkQueue<ChunkKey> markerRestoreQueue = new CoalescingWorkQueue<>();
    private final Map<ChunkKey, MarkerRestoreBatch> markerRestoreBatches = new HashMap<>();
    private final ExecutorService saveExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "rpchat-microvoxel-save");
        thread.setDaemon(true);
        return thread;
    });
    private boolean saveScheduled;
    private boolean saveAgain;
    private boolean shuttingDown;
    private int refreshTicks;
    private boolean storageAvailable = true;

    public MicrovoxelManager(RPChat plugin) {
        this.plugin = plugin;
        this.store = new MicrovoxelStore(plugin.getDataFolder().toPath().resolve("microvoxels-v1.dat"));
    }

    public void start() {
        try {
            store.load();
            plugin.getLogger().info("Loaded " + store.size() + " microvoxel volumes.");
            if (store.loadedFromBackup()) {
                plugin.getLogger().warning("Primary microvoxel storage was invalid; recovered from backup.");
            }
        } catch (IOException | RuntimeException error) {
            storageAvailable = false;
            plugin.getLogger().severe("Unable to load microvoxels: " + error.getMessage());
        }
        restoreMarkersInLoadedChunks();
    }

    public void shutdown() {
        shuttingDown = true;
        saveExecutor.shutdown();
        try {
            if (!saveExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                plugin.getLogger().warning("Timed out waiting for the microvoxel save worker; writing final snapshot now.");
            }
            store.save();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            plugin.getLogger().warning("Interrupted while waiting for the microvoxel save worker.");
            try {
                store.save();
            } catch (IOException saveError) {
                plugin.getLogger().severe("Unable to save microvoxels during shutdown: " + saveError.getMessage());
            }
        } catch (IOException error) {
            plugin.getLogger().severe("Unable to save microvoxels during shutdown: " + error.getMessage());
        }
    }

    public void tick() {
        drainMarkerRestoreQueue();
        if (++refreshTicks >= 10) {
            refreshTicks = 0;
            refreshPlayerSnapshots();
        }
    }

    public void handleAction(ServerPlayer player, int action, int x, int y, int z, int cell, int expectedRevision, Vec3 clientLook, Vec3 clientEye) {
        if (player == null || !storageAvailable || !RPChat.hasPermission(player, "rpchat.microvoxels.edit", 2) || !allowAction(player)) {
            return;
        }
        if (action == MicrovoxelProtocol.ACTION_READY) {
            plugin.getServer().execute(() -> sendSnapshot(player));
            return;
        }
        if (cell >= MicrovoxelVolume.CELL_COUNT) return;
        UUID worldId = worldId(player.level());
        MicrovoxelKey key = new MicrovoxelKey(worldId, x, y, z);
        trace(player, "ACTION_RX action=" + action + " pos=" + x + "," + y + "," + z
                + " cell=" + cell + " revision=" + expectedRevision);
        if (!validClientLook(clientLook) || !validClientEye(clientEye)) return;
        plugin.getServer().execute(() -> applyAction(player,
                new QueuedAction(action, key, cell, expectedRevision, clientLook.normalize(), clientEye)));
    }

    private void applyAction(ServerPlayer player, QueuedAction action) {
        if (player.connection == null || !storageAvailable || !RPChat.hasPermission(player, "rpchat.microvoxels.edit", 2)) return;
        if (!withinReach(player, action.key())) {
            trace(player, "ACTION_REJECT out-of-reach");
            feedback(player, "Микровоксель находится слишком далеко.");
            return;
        }
        switch (action.type()) {
            case MicrovoxelProtocol.ACTION_CONVERT -> convert(player, action.key());
            case MicrovoxelProtocol.ACTION_REMOVE -> removeCell(player, action.key(), action.cell(), action.expectedRevision(),
                    action.clientLook(), action.clientEye());
            case MicrovoxelProtocol.ACTION_ADD -> addCell(player, action.key(), action.cell(), action.expectedRevision(),
                    action.clientLook(), action.clientEye());
            case MicrovoxelProtocol.ACTION_CARVE_STANDARD -> carveStandardBlock(player, action.key(), action.cell(),
                    action.clientLook(), action.clientEye());
            default -> trace(player, "ACTION_REJECT unknown-action=" + action.type());
        }
    }

    private void carveStandardBlock(ServerPlayer player, MicrovoxelKey key, int cell, Vec3 clientLook, Vec3 clientEye) {
        MicrovoxelVolume existing = store.get(key);
        if (existing != null) {
            removeCell(player, key, cell, existing.revision(), clientLook, clientEye);
            return;
        }
        Vec3 clientLocation = boundedClientEye(player, clientEye);
        if (clientLocation == null) return;
        BlockHitResult trace = ((ServerLevel) player.level()).clip(new net.minecraft.world.level.ClipContext(
                clientLocation, clientLocation.add(clientLook.scale(MAX_REACH)),
                net.minecraft.world.level.ClipContext.Block.COLLIDER,
                net.minecraft.world.level.ClipContext.Fluid.NONE, player));
        if (trace.getType() == net.minecraft.world.phys.HitResult.Type.MISS) {
            trace(player, "ACTION_REJECT carve-standard-target-mismatch");
            feedback(player, "Нужно навестись на обычный полный блок ещё раз.");
            return;
        }
        BlockPos pos = trace.getBlockPos();
        if (pos.getX() != key.x() || pos.getY() != key.y() || pos.getZ() != key.z()
                || !isEligibleFullBlock(((ServerLevel) player.level()).getBlockState(pos), pos, ((ServerLevel) player.level()))) {
            trace(player, "ACTION_REJECT carve-standard-target-mismatch");
            feedback(player, "Нужно навестись на обычный полный блок ещё раз.");
            return;
        }
        int authoritativeCell = cellAtStandardHit(key, trace);
        if (authoritativeCell != cell) {
            trace(player, "ACTION_REJECT carve-standard-cell expected=" + cell + " actual=" + authoritativeCell);
            feedback(player, "Цель изменилась. Наведитесь на ячейку ещё раз.");
            return;
        }
        if (store.countInChunk(key.worldId(), key.chunkX(), key.chunkZ()) >= MAX_PER_CHUNK) {
            feedback(player, "В этом чанке достигнут безопасный лимит микровоксельных блоков.");
            return;
        }
        BlockState blockState = ((ServerLevel) player.level()).getBlockState(pos);
        String blockDataStr = getBlockStateString(blockState);
        MicrovoxelVolume volume = MicrovoxelVolume.full(blockDataStr);
        volume.remove(cell);
        store.put(key, volume);
        updateMarker(key, volume);
        broadcastUpsert(key, volume);
        markDirty();
        trace(player, "ACTION_APPLIED carve-standard cell=" + cell + " revision=" + volume.revision());
    }

    private void convert(ServerPlayer player, MicrovoxelKey key) {
        if (store.get(key) != null) {
            sendUpsert(player, key, store.get(key));
            return;
        }
        BlockHitResult trace = rayTraceBlocks(player, MAX_REACH);
        if (trace.getType() == net.minecraft.world.phys.HitResult.Type.MISS) {
            feedback(player, "Блок не выбран.");
            return;
        }
        BlockPos pos = trace.getBlockPos();
        if (pos.getX() != key.x() || pos.getY() != key.y() || pos.getZ() != key.z()) {
            feedback(player, "Нужно смотреть прямо на преобразуемый блок.");
            return;
        }
        BlockState blockState = ((ServerLevel) player.level()).getBlockState(pos);
        if (!isEligibleFullBlock(blockState, pos, ((ServerLevel) player.level()))) {
            feedback(player, "Можно преобразовать только обычный полноразмерный блок без содержимого.");
            return;
        }
        if (store.countInChunk(key.worldId(), key.chunkX(), key.chunkZ()) >= MAX_PER_CHUNK) {
            feedback(player, "В этом чанке достигнут безопасный лимит микровоксельных блоков.");
            return;
        }
        String blockDataStr = getBlockStateString(blockState);
        MicrovoxelVolume volume = MicrovoxelVolume.full(blockDataStr);
        store.put(key, volume);
        updateMarker(key, volume);
        markDirty();
        broadcastUpsert(key, volume);
        feedback(player, "Блок преобразован в сетку 16×16×16. ЛКМ убирает, ПКМ добавляет микровоксель.");
    }

    private void removeCell(ServerPlayer player, MicrovoxelKey key, int cell, int expectedRevision, Vec3 clientLook,
                            Vec3 clientEye) {
        MicrovoxelVolume volume = store.get(key);
        ServerMicrovoxelRaycaster.Hit hit = validatedHit(player, key, cell, clientLook, clientEye, true);
        if (!validRevision(player, key, volume, expectedRevision)) return;
        if (volume == null || !volume.occupied(cell)) {
            trace(player, "ACTION_REJECT remove-cell-not-occupied");
            sendUpsert(player, key, volume);
            feedback(player, "Эта ячейка уже изменена. Сетка синхронизирована.");
            return;
        }
        if (hit == null || !hit.key().equals(key) || hit.cell() != cell) {
            trace(player, "ACTION_REJECT remove-raycast-mismatch");
            sendUpsert(player, key, volume);
            feedback(player, "Цель изменилась. Наведитесь на ячейку ещё раз.");
            return;
        }
        volume.remove(cell);
        if (volume.occupiedCount() == 0) {
            store.remove(key);
            collisionShapes.remove(key);
            setMarkerBlockState(key, Blocks.AIR.defaultBlockState());
            broadcastRemove(key);
        } else {
            updateMarker(key, volume);
            broadcastDelta(key, volume, cell, "");
        }
        markDirty();
        trace(player, "ACTION_APPLIED remove cell=" + cell + " revision=" + volume.revision());
    }

    private void addCell(ServerPlayer player, MicrovoxelKey key, int cell, int expectedRevision, Vec3 clientLook,
                         Vec3 clientEye) {
        MicrovoxelVolume volume = store.get(key);
        ServerMicrovoxelRaycaster.Hit hit = validatedHit(player, key, cell, clientLook, clientEye, false);
        if (hit == null) {
            if (volume == null) sendRemove(player, key); else sendUpsert(player, key, volume);
            feedback(player, "Цель изменилась. Наведитесь на грань ячейки ещё раз.");
            return;
        }

        boolean creatingVolume = volume == null;
        if (creatingVolume) {
            if (expectedRevision != 0) {
                sendRemove(player, key);
                feedback(player, "Целевой микровоксельный блок изменился. Повторите действие.");
                return;
            }
            ServerLevel level = (ServerLevel) player.level();
            BlockPos targetPos = new BlockPos(key.x(), key.y(), key.z());
            if (!level.getBlockState(targetPos).isAir()) {
                feedback(player, "Продолжить форму можно только в свободное пространство.");
                return;
            }
            if (store.countInChunk(key.worldId(), key.chunkX(), key.chunkZ()) >= MAX_PER_CHUNK) {
                feedback(player, "В этом чанке достигнут безопасный лимит микровоксельных блоков.");
                return;
            }
            volume = MicrovoxelVolume.empty();
        } else if (!validRevision(player, key, volume, expectedRevision)) {
            return;
        }

        if (volume.occupied(cell)) {
            trace(player, "ACTION_REJECT add-cell-occupied");
            sendUpsert(player, key, volume);
            feedback(player, "Эта ячейка уже занята. Сетка синхронизирована.");
            return;
        }
        BlockState material = selectedFullBlock(player);
        if (material == null) {
            feedback(player, "Возьмите в основную или вторую руку полноразмерный блок.");
            return;
        }
        String matStr = getBlockStateString(material);
        MicrovoxelVolume updated = volume.copy();
        boolean paletteCompacted = false;
        if (!updated.palette().contains(matStr) && updated.palette().size() >= MicrovoxelVolume.MAX_PALETTE) {
            paletteCompacted = updated.compactPalette();
        }
        try {
            updated.put(cell, matStr);
        } catch (IllegalStateException error) {
            sendUpsert(player, key, volume);
            feedback(player, "В этом микровоксельном блоке достигнут лимит материалов.");
            return;
        }
        store.put(key, updated);
        updateMarker(key, updated);
        markDirty();
        if (creatingVolume || paletteCompacted) {
            broadcastUpsert(key, updated);
        } else {
            broadcastDelta(key, updated, cell, matStr);
        }
        trace(player, "ACTION_APPLIED add cell=" + cell + " revision=" + updated.revision());
    }

    private boolean isEligibleFullBlock(BlockState state, BlockPos pos, Level level) {
        if (!isEligibleMaterialState(state, pos, level)) return false;
        if (isBlockEntityState(state)) return false;
        return level == null || level.getBlockEntity(pos) == null;
    }

    static boolean isBlockEntityState(BlockState state) {
        return state != null && state.getBlock() instanceof net.minecraft.world.level.block.EntityBlock;
    }

    private boolean isEligibleMaterialState(BlockState state, BlockPos pos, Level level) {
        if (state.isAir() || state.is(Blocks.BARRIER) || state.is(Blocks.STRUCTURE_VOID) || state.is(Blocks.LIGHT)) {
            return false;
        }
        return isFullCollision(state, pos, level);
    }

    private boolean isFullCollision(BlockState state, BlockPos pos, Level level) {
        try {
            VoxelShape shape = state.getCollisionShape(level, pos);
            if (shape.isEmpty()) return false;
            var boxes = shape.toAabbs();
            if (boxes.size() != 1) return false;
            AABB box = boxes.get(0);
            return close(box.maxX - box.minX, 1.0) && close(box.maxY - box.minY, 1.0) && close(box.maxZ - box.minZ, 1.0);
        } catch (RuntimeException error) {
            return false;
        }
    }

    private ServerMicrovoxelRaycaster.Hit raycastMicrovoxel(ServerPlayer player) {
        Vec3 eye = player.getEyePosition();
        Vec3 direction = player.getViewVector(1.0f).normalize();
        return raycastMicrovoxel(player, eye, direction);
    }

    private ServerMicrovoxelRaycaster.Hit raycastMicrovoxel(ServerPlayer player, Vec3 eye, Vec3 direction) {
        UUID worldId = worldId(player.level());
        ServerMicrovoxelRaycaster.Hit hit = ServerMicrovoxelRaycaster.cast(
                eye.x, eye.y, eye.z, direction.x, direction.y, direction.z, MAX_REACH,
                store.nearby(worldId, player.blockPosition().getX() >> 4,
                        player.blockPosition().getZ() >> 4, 1));
        if (hit == null) return null;
        BlockHitResult obstruction = ((ServerLevel) player.level()).clip(new net.minecraft.world.level.ClipContext(
                eye, eye.add(direction.scale(Math.max(0.0, hit.distance() - 0.001))),
                net.minecraft.world.level.ClipContext.Block.COLLIDER,
                net.minecraft.world.level.ClipContext.Fluid.NONE, player));
        return obstruction.getType() == net.minecraft.world.phys.HitResult.Type.MISS ? hit : null;
    }

    private ServerMicrovoxelRaycaster.Hit validatedHit(ServerPlayer player, MicrovoxelKey key, int cell, Vec3 clientLook,
                                                       Vec3 clientEye, boolean requireRequestedCell) {
        ServerMicrovoxelRaycaster.Hit serverHit = raycastMicrovoxel(player);
        if (matches(serverHit, key, cell, requireRequestedCell)) return serverHit;

        Vec3 eye = player.getEyePosition();
        Vec3 serverLook = player.getViewVector(1.0f).normalize();
        if (serverLook.dot(clientLook) < CLIENT_LOOK_MIN_DOT) {
            trace(player, "ACTION_REJECT client-look-diverged dot=" + String.format(Locale.ROOT, "%.5f", serverLook.dot(clientLook)));
            return null;
        }
        Vec3 recoveredEye = boundedClientEye(player, clientEye);
        if (recoveredEye == null) return null;
        Vec3 eyeDelta = clientEye.subtract(eye);
        ServerMicrovoxelRaycaster.Hit recovered = raycastMicrovoxel(player, recoveredEye, clientLook);
        if (matches(recovered, key, cell, requireRequestedCell)) {
            trace(player, "ACTION_RECOVERED client-eye-ray delta=" + String.format(Locale.ROOT,
                    "%.3f", eyeDelta.length()));
            return recovered;
        }
        trace(player, "ACTION_RAY_MISMATCH server=" + hitLabel(serverHit) + " client=" + hitLabel(recovered)
                + " expected=" + key.x() + "," + key.y() + "," + key.z() + ":" + cell);
        return null;
    }

    private static boolean matches(ServerMicrovoxelRaycaster.Hit hit, MicrovoxelKey key, int cell,
                                   boolean requireRequestedCell) {
        if (hit == null) return false;
        if (requireRequestedCell) return hit.key().equals(key) && hit.cell() == cell;
        ServerMicrovoxelRaycaster.AdjacentTarget target = hit.adjacentTarget();
        return target.key().equals(key) && target.cell() == cell;
    }

    private static String hitLabel(ServerMicrovoxelRaycaster.Hit hit) {
        return hit == null ? "none" : hit.key().x() + "," + hit.key().y() + "," + hit.key().z() + ":" + hit.cell();
    }

    private static boolean validClientLook(Vec3 look) {
        return Double.isFinite(look.x) && Double.isFinite(look.y) && Double.isFinite(look.z)
                && look.lengthSqr() > 0.98 && look.lengthSqr() < 1.02;
    }

    private static boolean validClientEye(Vec3 eye) {
        return Double.isFinite(eye.x) && Double.isFinite(eye.y) && Double.isFinite(eye.z);
    }

    private Vec3 boundedClientEye(ServerPlayer player, Vec3 clientEye) {
        Vec3 serverEye = player.getEyePosition();
        Vec3 delta = clientEye.subtract(serverEye);
        if (delta.lengthSqr() > CLIENT_EYE_MAX_DELTA * CLIENT_EYE_MAX_DELTA) {
            trace(player, "ACTION_REJECT client-eye-diverged distance=" + String.format(Locale.ROOT,
                    "%.3f", delta.length()));
            return null;
        }
        return clientEye;
    }

    private static int cellAtStandardHit(MicrovoxelKey key, BlockHitResult hit) {
        Direction face = hit.getDirection();
        Vec3 point = hit.getLocation();
        if (face != null) {
            point = point.subtract(new Vec3(face.step().x(), face.step().y(), face.step().z()).scale(1.0E-4));
        }
        int x = clampCell((int) Math.floor((point.x - key.x()) * MicrovoxelVolume.RESOLUTION));
        int y = clampCell((int) Math.floor((point.y - key.y()) * MicrovoxelVolume.RESOLUTION));
        int z = clampCell((int) Math.floor((point.z - key.z()) * MicrovoxelVolume.RESOLUTION));
        return MicrovoxelVolume.index(x, y, z);
    }

    private static int clampCell(int cell) {
        return Math.max(0, Math.min(MicrovoxelVolume.RESOLUTION - 1, cell));
    }

    public HammerTarget prepareHammerTarget(ServerPlayer player, int x, int y, int z, int cell, int expectedRevision) {
        if (player == null || !storageAvailable || cell < 0 || cell >= MicrovoxelVolume.CELL_COUNT) return null;
        UUID worldId = worldId(player.level());
        MicrovoxelKey key = new MicrovoxelKey(worldId, x, y, z);
        MicrovoxelVolume volume = store.get(key);
        ServerMicrovoxelRaycaster.Hit hit = raycastMicrovoxel(player);
        if (volume == null || volume.revision() != expectedRevision || !volume.occupied(cell)
                || hit == null || !hit.key().equals(key) || hit.cell() != cell || !withinReach(player, key)) {
            if (volume != null) sendUpsert(player, key, volume);
            feedback(player, "Цель для удара изменилась или находится вне досягаемости.");
            return null;
        }
        return new HammerTarget(key, cell, expectedRevision, HeavyHammerImpact.Face.valueOf(hit.face().name()));
    }

    public int commitHammerImpact(ServerPlayer player, HammerTarget target) {
        if (player == null || target == null || !withinReach(player, target.key())) return 0;
        MicrovoxelVolume volume = store.get(target.key());
        ServerMicrovoxelRaycaster.Hit currentHit = raycastMicrovoxel(player);
        if (volume == null || volume.revision() != target.expectedRevision() || currentHit == null
                || !currentHit.key().equals(target.key())) {
            if (volume != null) sendUpsert(player, target.key(), volume);
            return 0;
        }

        int removed = 0;
        for (int cell : HeavyHammerImpact.cells(target.anchorCell(), target.face())) {
            if (volume.occupied(cell) && volume.remove(cell)) removed++;
        }
        if (removed == 0) return 0;
        if (volume.occupiedCount() == 0) {
            store.remove(target.key());
            collisionShapes.remove(target.key());
            setMarkerBlockState(target.key(), Blocks.AIR.defaultBlockState());
            broadcastRemove(target.key());
        } else {
            updateMarker(target.key(), volume);
            broadcastUpsert(target.key(), volume);
        }
        markDirty();
        return removed;
    }

    private boolean withinReach(ServerPlayer player, MicrovoxelKey key) {
        UUID worldId = worldId(player.level());
        return worldId.equals(key.worldId())
                && player.getEyePosition().distanceToSqr(
                new Vec3(key.x() + 0.5, key.y() + 0.5, key.z() + 0.5)) <= MAX_REACH * MAX_REACH;
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
        plugin.getServer().execute(() -> sendSnapshot(player));
    }

    public void onQuit(ServerPlayer player) {
        UUID uuid = player.getUUID();
        syncPositions.remove(uuid);
        playerSubscriptions.remove(uuid);
        playerDictionaries.remove(uuid);
        actionRates.remove(uuid);
        miningStartTimes.remove(uuid);
    }

    public boolean protectsMarker(ServerLevel level, BlockPos pos) {
        UUID worldId = worldId(level);
        return store.get(new MicrovoxelKey(worldId, pos.getX(), pos.getY(), pos.getZ())) != null;
    }

    /** Returns the exact native Minecraft shape used by movement, support and projectile queries. */
    public VoxelShape collisionShape(ServerLevel level, BlockPos pos) {
        MicrovoxelKey key = new MicrovoxelKey(worldId(level), pos.getX(), pos.getY(), pos.getZ());
        MicrovoxelVolume volume = store.get(key);
        if (volume == null) {
            collisionShapes.remove(key);
            return null;
        }
        CachedCollisionShape cached = collisionShapes.get(key);
        if (cached != null && cached.revision == volume.revision()) return cached.shape;

        MicrovoxelVolume snapshot = volume.copy();
        VoxelShape compiled = buildNativeShape(snapshot);
        collisionShapes.put(key, new CachedCollisionShape(snapshot.revision(), compiled));
        return compiled;
    }

    static VoxelShape buildNativeShape(MicrovoxelVolume volume) {
        MicrovoxelVolume.CollisionPlan plan = volume.collisionPlan();
        if (plan.backend() == MicrovoxelVolume.CollisionBackend.GRID) {
            BitSetDiscreteVoxelShape discrete = new BitSetDiscreteVoxelShape(
                    MicrovoxelVolume.RESOLUTION, MicrovoxelVolume.RESOLUTION, MicrovoxelVolume.RESOLUTION);
            for (int cell = 0; cell < MicrovoxelVolume.CELL_COUNT; cell++) {
                if (volume.occupied(cell)) {
                    discrete.fill(MicrovoxelVolume.x(cell), MicrovoxelVolume.y(cell), MicrovoxelVolume.z(cell));
                }
            }
            return CubeVoxelShapeInvoker.eclipse$create(discrete);
        }

        List<MicrovoxelVolume.Cuboid> cuboids = plan.cuboids();
        if (cuboids.isEmpty()) return Shapes.empty();
        if (cuboids.size() == 1) {
            MicrovoxelVolume.Cuboid only = cuboids.getFirst();
            if (only.minX() == 0 && only.minY() == 0 && only.minZ() == 0
                    && only.maxX() == MicrovoxelVolume.RESOLUTION
                    && only.maxY() == MicrovoxelVolume.RESOLUTION
                    && only.maxZ() == MicrovoxelVolume.RESOLUTION) {
                return Shapes.block();
            }
        }
        VoxelShape[] parts = new VoxelShape[cuboids.size()];
        for (int index = 0; index < cuboids.size(); index++) {
            MicrovoxelVolume.Cuboid cuboid = cuboids.get(index);
            parts[index] = Shapes.box(
                    cuboid.minX() / 16.0, cuboid.minY() / 16.0, cuboid.minZ() / 16.0,
                    cuboid.maxX() / 16.0, cuboid.maxY() / 16.0, cuboid.maxZ() / 16.0);
        }
        return combineShapes(parts, 0, parts.length).optimize();
    }

    private static VoxelShape combineShapes(VoxelShape[] shapes, int start, int end) {
        if (start >= end) return Shapes.empty();
        if (start == end - 1) return shapes[start];
        int middle = (start + end) >>> 1;
        return Shapes.or(combineShapes(shapes, start, middle), combineShapes(shapes, middle, end));
    }

    public boolean onBlockBreak(ServerPlayer player, BlockPos pos) {
        UUID worldId = worldId(player.level());
        MicrovoxelKey key = new MicrovoxelKey(worldId, pos.getX(), pos.getY(), pos.getZ());
        MicrovoxelVolume volume = store.get(key);
        if (volume == null) return false;

        UUID playerId = player.getUUID();

        if (player.gameMode.getGameModeForPlayer() == GameType.CREATIVE) {
            if (!((ServerLevel) player.level()).getBlockState(pos).is(Blocks.STRUCTURE_VOID)) {
                ((ServerLevel) player.level()).setBlock(pos, Blocks.STRUCTURE_VOID.defaultBlockState(), 2);
            }
            return true;
        }

        if (player.gameMode.getGameModeForPlayer() == GameType.SURVIVAL) {
            Long startTime = miningStartTimes.get(playerId);
            String matStr = volume.palette().size() > 1 ? volume.palette().get(1) : null;
            double requiredTime = 200.0;
            try {
                BlockState nmsState = parseBlockState(matStr);
                float progressPerTick = nmsState.getDestroyProgress(player, ((ServerLevel) player.level()), pos);
                if (progressPerTick > 0.0f) {
                    int requiredTicks = (int) Math.ceil(1.0f / progressPerTick);
                    requiredTime = requiredTicks * 50.0;
                }
            } catch (Exception e) {
                net.minecraft.world.item.Item toolItem = player.getMainHandItem().getItem();
                requiredTime = getRequiredBreakTimeMs(parseBlockState(matStr).getBlock(), toolItem);
            }

            long now = System.currentTimeMillis();
            long elapsed = startTime == null ? -1 : (now - startTime);

            if (startTime == null || elapsed < requiredTime - 150) {
                return true;
            }
        }

        miningStartTimes.remove(playerId);

        store.remove(key);
        collisionShapes.remove(key);
        broadcastRemove(key);
        markDirty();

        net.minecraft.world.level.block.Block dropBlock = Blocks.STONE;
        String matStr = volume.palette().size() > 1 ? volume.palette().get(1) : null;
        if (matStr != null) {
            try { dropBlock = parseBlockState(matStr).getBlock(); }
            catch (Exception ignored) {}
        }

        ItemStack dropItem = new ItemStack(dropBlock.asItem());
        List<Component> lore = new ArrayList<>();
        String name = dropBlock.toString().toUpperCase(Locale.ROOT);
        boolean isWood = name.contains("LOG") || name.contains("PLANKS") || name.contains("WOOD");
        boolean isStone = name.contains("STONE") || name.contains("DEEPSLATE") || name.contains("TUFF") || name.contains("BRICK");

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
        return false;
    }

    public boolean onBlockPlace(ServerPlayer player, ItemStack item, BlockPos pos) {
        if (item == null || item.isEmpty()) return false;
        net.minecraft.world.item.component.CustomData pdc = item.get(DataComponents.CUSTOM_DATA);
        if (pdc == null || !pdc.copyTag().contains("microvoxel_volume")) return false;

        byte[] bytes = pdc.copyTag().getByteArray("microvoxel_volume").orElse(null);
        if (bytes == null) return false;

        UUID worldId = worldId(player.level());
        MicrovoxelKey key = new MicrovoxelKey(worldId, pos.getX(), pos.getY(), pos.getZ());
        if (store.get(key) != null) return false;
        try {
            MicrovoxelVolume volume = deserializeVolume(bytes);
            MicrovoxelVolume copy = MicrovoxelVolume.restore(1, volume.palette(), volume.cellsCopy());
            store.put(key, copy);
            updateMarker(key, copy);
            broadcastUpsert(key, copy);
            if (player.gameMode.getGameModeForPlayer() != GameType.CREATIVE) {
                item.shrink(1);
            }
            markDirty();
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to deserialize volume on place: " + e.getMessage());
        }
        return true;
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
        ByteArrayInputStream bis = new ByteArrayInputStream(bytes);
        try (DataInputStream dis = new DataInputStream(bis)) {
            int revision = dis.readInt();
            int paletteSize = dis.readUnsignedByte();
            List<String> palette = new ArrayList<>(paletteSize);
            for (int i = 0; i < paletteSize; i++) {
                int length = dis.readUnsignedShort();
                byte[] utf8 = dis.readNBytes(length);
                palette.add(new String(utf8, StandardCharsets.UTF_8));
            }
            byte[] cells = dis.readNBytes(MicrovoxelVolume.CELL_COUNT);
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
            UUID worldId = worldId(world);
            for (int[] direction : BOUNDARY_DIRECTIONS) {
                MicrovoxelKey adjacent = new MicrovoxelKey(worldId,
                        x + direction[0], y + direction[1], z + direction[2]);
                MicrovoxelVolume volume = store.get(adjacent);
                if (volume != null) {
                    broadcastUpsert(adjacent, volume);
                    plugin.getLogger().fine("[MICROVOXEL] boundary mesh refresh "
                            + adjacent.x() + "," + adjacent.y() + "," + adjacent.z());
                }
            }
        });
    }

    public void restoreMarkers(ServerLevel world, LevelChunk chunk) {
        // C2ME can invoke CHUNK_LOAD while it is still promoting this chunk.
        // Calling Level#getBlockState or Level#setBlock here can synchronously
        // request the same chunk and deadlock the server thread.
        markerRestoreQueue.schedule(new ChunkKey(worldId(world), chunk.getPos().x(), chunk.getPos().z()));
    }

    private void restoreMarkersInLoadedChunks() {
        for (ChunkKey key : store.indexedChunks()) {
            ServerLevel world = getWorld(key.worldId());
            if (world != null && world.getChunkSource().getChunkNow(key.x(), key.z()) != null) {
                markerRestoreQueue.schedule(key);
            }
        }
    }

    private void drainMarkerRestoreQueue() {
        int markerBudget = MAX_MARKER_RESTORES_PER_TICK;
        int chunkBudget = MAX_MARKER_CHUNKS_PER_TICK;
        while (markerBudget > 0 && chunkBudget-- > 0) {
            ChunkKey chunkKey = markerRestoreQueue.poll();
            if (chunkKey == null) return;

            ServerLevel world = getWorld(chunkKey.worldId());
            LevelChunk loadedChunk = world == null
                    ? null
                    : world.getChunkSource().getChunkNow(chunkKey.x(), chunkKey.z());
            if (loadedChunk == null) {
                markerRestoreBatches.remove(chunkKey);
                markerRestoreQueue.complete(chunkKey);
                continue;
            }

            MarkerRestoreBatch batch = markerRestoreBatches.computeIfAbsent(chunkKey, ignored -> {
                List<MicrovoxelKey> keys = store.inChunk(chunkKey.worldId(), chunkKey.x(), chunkKey.z())
                        .stream()
                        .map(Map.Entry::getKey)
                        .toList();
                return new MarkerRestoreBatch(keys);
            });
            while (markerBudget > 0 && batch.hasNext()) {
                MicrovoxelKey key = batch.next();
                MicrovoxelVolume volume = store.get(key);
                if (volume != null) {
                    updateMarkerInLoadedChunk(world, loadedChunk, key, volume);
                }
                markerBudget--;
            }

            if (batch.hasNext()) {
                markerRestoreQueue.requeue(chunkKey);
            } else {
                markerRestoreBatches.remove(chunkKey);
                markerRestoreQueue.complete(chunkKey);
            }
        }
    }

    public boolean restoreLookedAt(ServerPlayer player) {
        ServerMicrovoxelRaycaster.Hit hit = raycastMicrovoxel(player);
        if (hit == null) {
            player.sendSystemMessage(Component.literal("Подивіться на microvoxel-блок у межах досяжності."));
            return false;
        }
        MicrovoxelVolume volume = store.remove(hit.key());
        if (volume == null) return false;
        collisionShapes.remove(hit.key());
        BlockState restored = Blocks.STONE.defaultBlockState();
        for (int index = 1; index < volume.palette().size(); index++) {
            String material = volume.palette().get(index);
            if (material != null && !material.isBlank()) {
                restored = parseBlockState(material);
                break;
            }
        }
        ServerLevel level = (ServerLevel) player.level();
        BlockPos pos = new BlockPos(hit.key().x(), hit.key().y(), hit.key().z());
        level.setBlock(pos, restored, 3);
        broadcastRemove(hit.key());
        scheduleBoundaryRefresh(level, pos);
        markDirty();
        player.sendSystemMessage(Component.literal("Microvoxel-блок повернуто до звичайного блоку."));
        return true;
    }

    public String status() {
        return "Microvoxel: " + store.size() + " томів; сховище "
                + (storageAvailable ? "доступне" : "недоступне") + ".";
    }

    public Vec3 collide(Entity entity, Vec3 movement) {
        double dx = movement.x;
        double dy = movement.y;
        double dz = movement.z;
        if (Math.abs(dx) + Math.abs(dy) + Math.abs(dz) < EPSILON) return movement;

        AABB actual = entity.getBoundingBox();
        UUID worldId = worldId(entity.level());

        double clippedY = clip(entity.level(), worldId, actual, dy, Axis.Y);
        actual = actual.move(0, clippedY, 0);
        double clippedX = clip(entity.level(), worldId, actual, dx, Axis.X);
        actual = actual.move(clippedX, 0, 0);
        double clippedZ = clip(entity.level(), worldId, actual, dz, Axis.Z);

        return new Vec3(clippedX, clippedY, clippedZ);
    }

    private double clip(Level world, UUID worldId, AABB player, double movement, Axis axis) {
        if (Math.abs(movement) < EPSILON) return 0.0;
        AABB sweep = player;
        if (axis == Axis.X) sweep = sweep.expandTowards(movement, 0, 0);
        if (axis == Axis.Y) sweep = sweep.expandTowards(0, movement, 0);
        if (axis == Axis.Z) sweep = sweep.expandTowards(0, 0, movement);
        int minX = floor(sweep.minX) - 1;
        int maxX = floor(sweep.maxX) + 1;
        int minY = floor(sweep.minY) - 1;
        int maxY = floor(sweep.maxY) + 1;
        int minZ = floor(sweep.minZ) - 1;
        int maxZ = floor(sweep.maxZ) + 1;
        double clipped = movement;
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    MicrovoxelVolume volume = store.get(new MicrovoxelKey(worldId, x, y, z));
                    if (volume == null) continue;
                    MicrovoxelVolume.CollisionPlan plan = volume.collisionPlan();
                    if (plan.backend() == MicrovoxelVolume.CollisionBackend.GRID) {
                        clipped = clipGrid(plan, x, y, z, player, clipped, axis);
                    } else {
                        for (MicrovoxelVolume.Cuboid cuboid : plan.cuboids()) {
                            double scale = 1.0 / MicrovoxelVolume.RESOLUTION;
                            AABB obstacle = new AABB(
                                    x + cuboid.minX() * scale, y + cuboid.minY() * scale, z + cuboid.minZ() * scale,
                                    x + cuboid.maxX() * scale, y + cuboid.maxY() * scale, z + cuboid.maxZ() * scale);
                            clipped = clipAgainst(player, obstacle, clipped, axis);
                        }
                    }
                }
            }
        }
        return clipped;
    }

    /**
     * Exact narrow-phase collision for fragmented volumes. Orthogonal occupancy lines are folded
     * into one 16-bit mask, so only the at most sixteen candidate planes along the movement axis
     * are examined and no temporary AABB is allocated per occupied cell.
     */
    static double clipGrid(MicrovoxelVolume.CollisionPlan plan, int blockX, int blockY, int blockZ,
                           AABB moving, double movement, Axis axis) {
        if (Math.abs(movement) < EPSILON) return 0.0;
        int mask = 0;
        if (axis == Axis.X) {
            int yRange = overlappingCells(moving.minY, moving.maxY, blockY);
            int zRange = overlappingCells(moving.minZ, moving.maxZ, blockZ);
            if (yRange < 0 || zRange < 0) return movement;
            for (int y = rangeMin(yRange); y <= rangeMax(yRange) && mask != 0xFFFF; y++) {
                for (int z = rangeMin(zRange); z <= rangeMax(zRange); z++) mask |= plan.xMask(y, z);
            }
        } else if (axis == Axis.Y) {
            int xRange = overlappingCells(moving.minX, moving.maxX, blockX);
            int zRange = overlappingCells(moving.minZ, moving.maxZ, blockZ);
            if (xRange < 0 || zRange < 0) return movement;
            for (int z = rangeMin(zRange); z <= rangeMax(zRange) && mask != 0xFFFF; z++) {
                for (int x = rangeMin(xRange); x <= rangeMax(xRange); x++) mask |= plan.yMask(z, x);
            }
        } else {
            int xRange = overlappingCells(moving.minX, moving.maxX, blockX);
            int yRange = overlappingCells(moving.minY, moving.maxY, blockY);
            if (xRange < 0 || yRange < 0) return movement;
            for (int y = rangeMin(yRange); y <= rangeMax(yRange) && mask != 0xFFFF; y++) {
                for (int x = rangeMin(xRange); x <= rangeMax(xRange); x++) mask |= plan.zMask(y, x);
            }
        }
        if (mask == 0) return movement;

        double movingMin = min(moving, axis);
        double movingMax = max(moving, axis);
        double blockOrigin = axis == Axis.X ? blockX : axis == Axis.Y ? blockY : blockZ;
        double scale = 1.0 / MicrovoxelVolume.RESOLUTION;
        double clipped = movement;
        int candidates = mask;
        while (candidates != 0) {
            int cell = Integer.numberOfTrailingZeros(candidates);
            candidates &= candidates - 1;
            double obstacleMin = blockOrigin + cell * scale;
            double obstacleMax = obstacleMin + scale;
            if (clipped > 0.0 && movingMax <= obstacleMin + EPSILON) {
                clipped = Math.min(clipped, obstacleMin - movingMax);
            } else if (clipped < 0.0 && movingMin >= obstacleMax - EPSILON) {
                clipped = Math.max(clipped, obstacleMax - movingMin);
            }
        }
        return clipped;
    }

    private static int overlappingCells(double min, double max, int blockOrigin) {
        int first = floor((min - blockOrigin + EPSILON) * MicrovoxelVolume.RESOLUTION);
        int last = floor((max - blockOrigin - EPSILON) * MicrovoxelVolume.RESOLUTION);
        if (last < 0 || first >= MicrovoxelVolume.RESOLUTION) return -1;
        first = Math.max(0, first);
        last = Math.min(MicrovoxelVolume.RESOLUTION - 1, last);
        return first | (last << 8);
    }

    private static int rangeMin(int packed) {
        return packed & 0xFF;
    }

    private static int rangeMax(int packed) {
        return (packed >>> 8) & 0xFF;
    }

    static double clipAgainst(AABB moving, AABB obstacle, double movement, Axis axis) {
        if (!overlapsOtherAxes(moving, obstacle, axis)) return movement;
        double movingMin = min(moving, axis);
        double movingMax = max(moving, axis);
        double obstacleMin = min(obstacle, axis);
        double obstacleMax = max(obstacle, axis);
        if (movement > 0 && movingMax <= obstacleMin + EPSILON) {
            return Math.min(movement, obstacleMin - movingMax);
        }
        if (movement < 0 && movingMin >= obstacleMax - EPSILON) {
            return Math.max(movement, obstacleMax - movingMin);
        }
        return movement;
    }

    private static boolean overlapsOtherAxes(AABB a, AABB b, Axis movementAxis) {
        if (movementAxis != Axis.X && !overlap(a.minX, a.maxX, b.minX, b.maxX)) return false;
        if (movementAxis != Axis.Y && !overlap(a.minY, a.maxY, b.minY, b.maxY)) return false;
        return movementAxis == Axis.Z || overlap(a.minZ, a.maxZ, b.minZ, b.maxZ);
    }

    private static boolean overlap(double minA, double maxA, double minB, double maxB) {
        return maxA > minB + EPSILON && minA < maxB - EPSILON;
    }

    private static double min(AABB box, Axis axis) {
        return axis == Axis.X ? box.minX : axis == Axis.Y ? box.minY : box.minZ;
    }

    private static double max(AABB box, Axis axis) {
        return axis == Axis.X ? box.maxX : axis == Axis.Y ? box.maxY : box.maxZ;
    }

    private void refreshPlayerSnapshots() {
        for (ServerPlayer player : plugin.getServer().getPlayerList().getPlayers()) {
            UUID worldId = worldId(player.level());
            PlayerSyncPosition current = new PlayerSyncPosition(
                    worldId, player.blockPosition().getX() >> 4, player.blockPosition().getZ() >> 4);
            if (!current.equals(syncPositions.get(player.getUUID()))) sendSnapshot(player);
        }
    }

    private void ensureMaterialsRegistered(ServerPlayer player, List<Map.Entry<MicrovoxelKey, MicrovoxelVolume>> entries) {
        Map<String, Integer> dict = playerDictionaries.computeIfAbsent(player.getUUID(), k -> new HashMap<>());
        for (Map.Entry<MicrovoxelKey, MicrovoxelVolume> entry : entries) {
            for (String material : entry.getValue().palette()) {
                if (!dict.containsKey(material)) {
                    int id = dict.size() + 1;
                    dict.put(material, id);
                    sendPacket(player, MicrovoxelProtocol.registerMaterial(id, material));
                }
            }
        }
    }

    private void ensureMaterialsRegistered(ServerPlayer player, MicrovoxelVolume volume) {
        Map<String, Integer> dict = playerDictionaries.computeIfAbsent(player.getUUID(), k -> new HashMap<>());
        for (String material : volume.palette()) {
            if (!dict.containsKey(material)) {
                int id = dict.size() + 1;
                dict.put(material, id);
                sendPacket(player, MicrovoxelProtocol.registerMaterial(id, material));
            }
        }
    }

    private void sendSnapshot(ServerPlayer player) {
        if (player.connection == null) return;
        UUID playerId = player.getUUID();
        UUID worldId = worldId(player.level());
        PlayerSyncPosition current = new PlayerSyncPosition(
                worldId, player.blockPosition().getX() >> 4, player.blockPosition().getZ() >> 4);
        PlayerSyncPosition previousPosition = syncPositions.get(playerId);
        boolean reset = previousPosition == null || !previousPosition.worldId.equals(current.worldId);

        syncPositions.put(playerId, current);
        Set<ChunkKey> subscribed = playerSubscriptions.computeIfAbsent(playerId, k -> ConcurrentHashMap.newKeySet());

        if (reset) {
            sendPacket(player, MicrovoxelProtocol.clear());
            subscribed.clear();
            playerDictionaries.computeIfAbsent(playerId, k -> new HashMap<>()).clear();
        }

        Set<ChunkKey> desired = new HashSet<>();
        for (int dx = -SYNC_RADIUS_CHUNKS; dx <= SYNC_RADIUS_CHUNKS; dx++) {
            for (int dz = -SYNC_RADIUS_CHUNKS; dz <= SYNC_RADIUS_CHUNKS; dz++) {
                desired.add(new ChunkKey(current.worldId, current.chunkX + dx, current.chunkZ + dz));
            }
        }

        Iterator<ChunkKey> iterator = subscribed.iterator();
        while (iterator.hasNext()) {
            ChunkKey chunk = iterator.next();
            if (!desired.contains(chunk)) {
                sendPacket(player, MicrovoxelProtocol.clearChunk(chunk.x(), chunk.z()));
                iterator.remove();
            }
        }

        for (ChunkKey chunk : desired) {
            if (!subscribed.contains(chunk)) {
                List<Map.Entry<MicrovoxelKey, MicrovoxelVolume>> entries =
                        store.inChunk(chunk.worldId(), chunk.x(), chunk.z());
                if (!entries.isEmpty()) {
                    for (int i = 0; i < entries.size(); i += 32) {
                        List<Map.Entry<MicrovoxelKey, MicrovoxelVolume>> subList =
                                entries.subList(i, Math.min(i + 32, entries.size()));
                        ensureMaterialsRegistered(player, subList);
                        sendPacket(player, MicrovoxelProtocol.batchUpsert(chunk.x(), chunk.z(), subList, playerDictionaries.get(playerId)));
                    }
                }
                subscribed.add(chunk);
            }
        }
    }

    private void broadcastUpsert(MicrovoxelKey key, MicrovoxelVolume volume) {
        for (ServerPlayer player : nearbyPlayers(key)) sendUpsert(player, key, volume);
    }

    private void broadcastRemove(MicrovoxelKey key) {
        for (ServerPlayer player : nearbyPlayers(key)) sendRemove(player, key);
    }

    private void broadcastDelta(MicrovoxelKey key, MicrovoxelVolume volume, int cellIndex, String material) {
        for (ServerPlayer player : nearbyPlayers(key)) {
            ensureMaterialsRegistered(player, volume);
            String matToLookup = (material == null) ? "" : material;
            Integer dictId = playerDictionaries.get(player.getUUID()).get(matToLookup);
            if (dictId == null) {
                dictId = 1;
            }
            byte[] packet = MicrovoxelProtocol.deltaUpsert(
                    key.chunkX(), key.chunkZ(), key, volume.revision(), cellIndex, dictId);
            sendPacket(player, packet);
        }
    }

    private List<ServerPlayer> nearbyPlayers(MicrovoxelKey key) {
        ChunkKey chunkKey = ChunkKey.of(key);
        List<ServerPlayer> result = new ArrayList<>();
        for (ServerPlayer player : plugin.getServer().getPlayerList().getPlayers()) {
            Set<ChunkKey> subs = playerSubscriptions.get(player.getUUID());
            if (subs != null && subs.contains(chunkKey)) {
                result.add(player);
            }
        }
        return result;
    }

    private void sendUpsert(ServerPlayer player, MicrovoxelKey key, MicrovoxelVolume volume) {
        ensureMaterialsRegistered(player, volume);
        byte[] packet = MicrovoxelProtocol.batchUpsert(
                key.chunkX(), key.chunkZ(),
                List.of(Map.entry(key, volume)),
                playerDictionaries.get(player.getUUID())
        );
        sendPacket(player, packet);
    }

    private void sendRemove(ServerPlayer player, MicrovoxelKey key) {
        sendPacket(player, MicrovoxelProtocol.remove(key));
    }

    private void feedback(ServerPlayer player, String message) {
        player.sendSystemMessage(Component.literal(message), true);
        sendPacket(player, MicrovoxelProtocol.message(message));
    }

    private void trace(ServerPlayer player, String message) {
        plugin.getLogger().info("[MICROVOXEL] player=" + player.getScoreboardName() + " " + message);
    }

    private record QueuedAction(int type, MicrovoxelKey key, int cell, int expectedRevision, Vec3 clientLook,
                                Vec3 clientEye) {
    }

    private boolean validRevision(ServerPlayer player, MicrovoxelKey key, MicrovoxelVolume volume, int expected) {
        if (volume == null) {
            trace(player, "ACTION_REJECT volume-missing");
            sendRemove(player, key);
            return false;
        }
        if (volume.revision() != expected) {
            trace(player, "ACTION_REJECT stale-revision expected=" + expected + " actual=" + volume.revision());
            sendUpsert(player, key, volume);
            return false;
        }
        return true;
    }

    private void markDirty() {
        if (shuttingDown) return;
        if (saveScheduled) {
            saveAgain = true;
            return;
        }
        saveScheduled = true;
        MicrovoxelStore.Snapshot snapshot = store.snapshot();
        saveExecutor.execute(() -> {
            try {
                store.save(snapshot);
            } catch (IOException error) {
                plugin.getLogger().severe("Unable to persist microvoxels: " + error.getMessage());
            } finally {
                MinecraftServer server = plugin.getServer();
                if (server != null && !shuttingDown) {
                    server.execute(() -> {
                        saveScheduled = false;
                        if (saveAgain) {
                            saveAgain = false;
                            markDirty();
                        }
                    });
                }
            }
        });
    }

    private void setMarkerBlockState(MicrovoxelKey key, BlockState state) {
        ServerLevel world = getWorld(key.worldId());
        if (world == null) return;
        LevelChunk loadedChunk = world.getChunkSource().getChunkNow(key.chunkX(), key.chunkZ());
        if (loadedChunk == null) return;
        BlockPos pos = new BlockPos(key.x(), key.y(), key.z());
        if (!loadedChunk.getBlockState(pos).equals(state)) world.setBlock(pos, state, 2);
    }

    private void updateMarker(MicrovoxelKey key, MicrovoxelVolume volume) {
        ServerLevel world = getWorld(key.worldId());
        if (world == null) return;
        LevelChunk loadedChunk = world.getChunkSource().getChunkNow(key.chunkX(), key.chunkZ());
        if (loadedChunk == null) {
            markerRestoreQueue.schedule(ChunkKey.of(key));
            return;
        }
        updateMarkerInLoadedChunk(world, loadedChunk, key, volume);
    }

    private void updateMarkerInLoadedChunk(
            ServerLevel world,
            LevelChunk loadedChunk,
            MicrovoxelKey key,
            MicrovoxelVolume volume
    ) {
        if (loadedChunk.getPos().x() != key.chunkX() || loadedChunk.getPos().z() != key.chunkZ()) {
            throw new IllegalArgumentException("Marker chunk does not match its loaded chunk");
        }
        BlockPos pos = new BlockPos(key.x(), key.y(), key.z());
        BlockState desired = markerState(volume);
        if (!loadedChunk.getBlockState(pos).equals(desired)) {
            world.setBlock(pos, desired, 2);
        }
    }

    private static BlockState markerState(MicrovoxelVolume volume) {
        int lightLevel = 0;
        boolean[] usedMaterials = new boolean[volume.palette().size()];
        for (byte cell : volume.cellsCopy()) usedMaterials[Byte.toUnsignedInt(cell)] = true;
        for (int index = 1; index < volume.palette().size(); index++) {
            if (!usedMaterials[index]) continue;
            try {
                lightLevel = Math.max(lightLevel, parseBlockState(volume.palette().get(index)).getLightEmission());
            } catch (RuntimeException ignored) {
            }
        }
        if (lightLevel <= 0) {
            return Blocks.STRUCTURE_VOID.defaultBlockState();
        }
        BlockState lightState = Blocks.LIGHT.defaultBlockState();
        if (lightState.hasProperty(BlockStateProperties.LEVEL)) {
            lightState = lightState.setValue(BlockStateProperties.LEVEL, Math.min(15, lightLevel));
        }
        return lightState;
    }

    private void sendPacket(ServerPlayer player, byte[] bytes) {
        if (player.connection != null) {
            ServerPlayNetworking.send(player, new MicrovoxelSyncPayload(bytes));
        }
    }

    private ServerLevel getWorld(UUID worldId) {
        for (ServerLevel level : plugin.getServer().getAllLevels()) {
            UUID id = worldId(level);
            if (id.equals(worldId)) {
                return level;
            }
        }
        return null;
    }

    public static BlockState parseBlockState(String stateStr) {
        if (stateStr == null) return Blocks.STONE.defaultBlockState();
        try {
            if (!stateStr.contains("[")) {
                net.minecraft.resources.Identifier loc = net.minecraft.resources.Identifier.tryParse(stateStr);
                if (loc != null) {
                    var block = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(loc).map(net.minecraft.core.Holder.Reference::value).orElse(null);
                    if (block != null) {
                        return block.defaultBlockState();
                    }
                }
            } else {
                int brace = stateStr.indexOf('[');
                String blockName = stateStr.substring(0, brace);
                net.minecraft.resources.Identifier loc = net.minecraft.resources.Identifier.tryParse(blockName);
                if (loc != null) {
                    var block = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(loc).map(net.minecraft.core.Holder.Reference::value).orElse(null);
                    if (block != null) {
                        BlockState state = block.defaultBlockState();
                        String propsStr = stateStr.substring(brace + 1, stateStr.length() - 1);
                        for (String prop : propsStr.split(",")) {
                            String[] kv = prop.split("=");
                            if (kv.length == 2) {
                                String key = kv[0].trim();
                                String val = kv[1].trim();
                                for (var p : state.getProperties()) {
                                    if (p.getName().equals(key)) {
                                        state = setPropertyHelper(state, p, val);
                                    }
                                }
                            }
                        }
                        return state;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return Blocks.STONE.defaultBlockState();
    }

    @SuppressWarnings("unchecked")
    private static <T extends Comparable<T>> BlockState setPropertyHelper(BlockState state, net.minecraft.world.level.block.state.properties.Property<T> property, String value) {
        var opt = property.getValue(value);
        if (opt.isPresent()) {
            return state.setValue(property, opt.get());
        }
        return state;
    }

    private static String getBlockStateString(BlockState state) {
        StringBuilder sb = new StringBuilder();
        sb.append(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
        var props = state.getProperties();
        if (!props.isEmpty()) {
            sb.append("[");
            Iterator<net.minecraft.world.level.block.state.properties.Property<?>> iter = props.iterator();
            while (iter.hasNext()) {
                var prop = iter.next();
                sb.append(prop.getName()).append("=").append(getPropertyValueName(state, prop));
                if (iter.hasNext()) sb.append(",");
            }
            sb.append("]");
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static <T extends Comparable<T>> String getPropertyValueName(BlockState state, net.minecraft.world.level.block.state.properties.Property<T> prop) {
        return prop.getName(state.getValue(prop));
    }

    private BlockState selectedFullBlock(ServerPlayer player) {
        ItemStack main = player.getMainHandItem();
        if (isValidFullBlockItem(main)) return getBlockFromItem(main);
        ItemStack off = player.getOffhandItem();
        if (isValidFullBlockItem(off)) return getBlockFromItem(off);
        return null;
    }

    private boolean isValidFullBlockItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        net.minecraft.world.item.Item item = stack.getItem();
        if (item instanceof net.minecraft.world.item.BlockItem bi) {
            BlockState state = bi.getBlock().defaultBlockState();
            return isEligibleMaterialState(state, BlockPos.ZERO, playerLevelDummy());
        }
        return false;
    }

    private BlockState getBlockFromItem(ItemStack stack) {
        if (stack.getItem() instanceof net.minecraft.world.item.BlockItem bi) {
            return bi.getBlock().defaultBlockState();
        }
        return Blocks.STONE.defaultBlockState();
    }

    private Level playerLevelDummy() {
        for (ServerLevel level : plugin.getServer().getAllLevels()) return level;
        return null;
    }

    private BlockHitResult rayTraceBlocks(ServerPlayer player, double distance) {
        Vec3 start = player.getEyePosition();
        Vec3 dir = player.getViewVector(1.0f);
        Vec3 end = start.add(dir.x * distance, dir.y * distance, dir.z * distance);
        return ((ServerLevel) player.level()).clip(new net.minecraft.world.level.ClipContext(
                start, end, net.minecraft.world.level.ClipContext.Block.COLLIDER,
                net.minecraft.world.level.ClipContext.Fluid.NONE, player));
    }

    public void startMining(ServerPlayer player, BlockPos pos) {
        UUID worldId = worldId(player.level());
        MicrovoxelKey key = new MicrovoxelKey(worldId, pos.getX(), pos.getY(), pos.getZ());
        if (store.get(key) != null) {
            miningStartTimes.put(player.getUUID(), System.currentTimeMillis());
        }
    }

    private double getRequiredBreakTimeMs(net.minecraft.world.level.block.Block block, net.minecraft.world.item.Item tool) {
        String name = block.toString().toUpperCase(Locale.ROOT);
        boolean isWood = name.contains("LOG") || name.contains("PLANKS") || name.contains("WOOD");
        boolean isStone = name.contains("STONE") || name.contains("DEEPSLATE") || name.contains("TUFF") || name.contains("BRICK");
        String toolName = tool.toString().toUpperCase(Locale.ROOT);

        if (isWood) {
            if (toolName.contains("AXE")) {
                return 200;
            } else {
                return 2000;
            }
        } else if (isStone) {
            if (toolName.contains("PICKAXE")) {
                return 200;
            } else {
                return 4000;
            }
        }
        return 100;
    }

    private static boolean close(double left, double right) {
        return Math.abs(left - right) < 1.0E-6;
    }

    private static int floor(double value) {
        return (int) Math.floor(value);
    }

    private static UUID worldId(Level level) {
        return UUID.nameUUIDFromBytes(level.dimension().toString().getBytes(StandardCharsets.UTF_8));
    }

    enum Axis { X, Y, Z }

    private record PlayerSyncPosition(UUID worldId, int chunkX, int chunkZ) {
    }

    private record RateWindow(long startedAt, int count) {
    }

    private record CachedCollisionShape(int revision, VoxelShape shape) {
    }

    private static final class MarkerRestoreBatch {
        private final List<MicrovoxelKey> keys;
        private int cursor;

        private MarkerRestoreBatch(List<MicrovoxelKey> keys) {
            this.keys = keys;
        }

        private boolean hasNext() {
            return cursor < keys.size();
        }

        private MicrovoxelKey next() {
            return keys.get(cursor++);
        }
    }

    public record HammerTarget(MicrovoxelKey key, int anchorCell, int expectedRevision,
                               HeavyHammerImpact.Face face) {
    }
}
