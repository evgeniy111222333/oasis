package ua.rp.chat.microvoxel.sync;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import ua.rp.chat.client.microvoxel.MicrovoxelSyncPayload;
import ua.rp.chat.microvoxel.ChunkKey;
import ua.rp.chat.microvoxel.MicrovoxelKey;
import ua.rp.chat.microvoxel.MicrovoxelProtocol;
import ua.rp.chat.microvoxel.MicrovoxelRuntime;
import ua.rp.chat.microvoxel.MicrovoxelVolume;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Authoritative client-state mirror. Owns per-player sync position, the 17x17 chunk subscription
 * set, snapshot framing/ACK/retry, and every broadcast family (upsert, remove, delta, transaction).
 * All send paths are thread-safe map reads; actual sends happen on the server thread.
 */
public final class MicrovoxelSyncHub {
    private static final boolean DEBUG = Boolean.getBoolean("rpchat.microvoxel.debug");
    private static final int SYNC_RADIUS_CHUNKS = 8;
    private static final int SNAPSHOT_RETRY_TICKS = 100;
    // One login snapshot is spread over several ticks so a dense 17x17 build cannot emit
    // thousands of batch packets in a single tick. Pending pages are drained by tick()
    // even while the player stands still, with overlay progress for large deliveries.
    static final int SNAPSHOT_PAGE_VOLUMES = 256;
    private static final int SNAPSHOT_PAGE_INTERVAL_TICKS = 5;

    private final MicrovoxelRuntime runtime;
    private final Map<UUID, PlayerSyncPosition> syncPositions = new ConcurrentHashMap<>();
    private final Map<UUID, Set<ChunkKey>> playerSubscriptions = new ConcurrentHashMap<>();
    private final Map<UUID, SnapshotDelivery> pendingSnapshots = new ConcurrentHashMap<>();
    private final Map<UUID, SnapshotPages> snapshotPages = new ConcurrentHashMap<>();
    /** Tick-coalesced live deltas: one entry per volume, flushed every server tick. */
    private final Map<MicrovoxelKey, PendingDelta> deltaOutbox = new ConcurrentHashMap<>();
    private long nextSnapshotId = 1L;
    private int refreshTicks;

    public MicrovoxelSyncHub(MicrovoxelRuntime runtime) {
        this.runtime = runtime;
    }

    public void onJoin(ServerPlayer player) {
        UUID uuid = player.getUUID();
        syncPositions.remove(uuid);
        playerSubscriptions.remove(uuid);
        pendingSnapshots.remove(uuid);
        snapshotPages.remove(uuid);
    }

    public void onQuit(ServerPlayer player) {
        UUID uuid = player.getUUID();
        syncPositions.remove(uuid);
        playerSubscriptions.remove(uuid);
        pendingSnapshots.remove(uuid);
        snapshotPages.remove(uuid);
    }

    /** ACTION_READY: drop stale state and push a fresh full snapshot. */
    public void onReady(ServerPlayer player) {
        UUID playerId = player.getUUID();
        syncPositions.remove(playerId);
        playerSubscriptions.remove(playerId);
        sendFullSnapshot(player, 0);
    }

    public void sendFullSnapshot(ServerPlayer player, int retryCount) {
        if (player.connection == null) return;
        snapshotPages.remove(player.getUUID());
        long snapshotId = nextSnapshotId++;
        if (nextSnapshotId <= 0L) nextSnapshotId = 1L;
        sendPacket(player, MicrovoxelProtocol.snapshotBegin(snapshotId));
        sendSnapshotData(player, true);
        // With paged delivery the END frame goes out after the last page; without pages the
        // snapshot is already complete here.
        if (!snapshotPages.containsKey(player.getUUID())) {
            sendPacket(player, MicrovoxelProtocol.snapshotEnd(snapshotId));
        }
        pendingSnapshots.put(player.getUUID(),
                new SnapshotDelivery(snapshotId, runtime.serverTick(), retryCount));
    }

    public void acknowledgeSnapshot(ServerPlayer player, long snapshotId) {
        SnapshotDelivery pending = pendingSnapshots.get(player.getUUID());
        if (pending != null && pending.id() == snapshotId) {
            pendingSnapshots.remove(player.getUUID(), pending);
            snapshotPages.remove(player.getUUID());
            if (DEBUG) {
                runtime.logger().info("[MICROVOXEL] Snapshot " + snapshotId
                        + " acknowledged by " + player.getScoreboardName()
                        + " after " + pending.retries() + " retries.");
            }
        }
    }

    public void retryUnacknowledgedSnapshots() {
        if (pendingSnapshots.isEmpty()) return;
        for (Map.Entry<UUID, SnapshotDelivery> entry
                : List.copyOf(pendingSnapshots.entrySet())) {
            // A snapshot with pages still draining is making progress, not stalled.
            if (snapshotPages.containsKey(entry.getKey())) continue;
            SnapshotDelivery pending = entry.getValue();
            if (runtime.serverTick() - pending.sentAtTick() < SNAPSHOT_RETRY_TICKS) continue;
            ServerPlayer player = runtime.server().getPlayerList().getPlayer(entry.getKey());
            if (player == null || player.connection == null) {
                pendingSnapshots.remove(entry.getKey(), pending);
                continue;
            }
            sendFullSnapshot(player, pending.retries() + 1);
        }
    }

    /**
     * End-of-tick maintenance: position-diff snapshots, paged snapshot drain and retries.
     * Pages drain independently of movement so a standing player in a dense build still
     * converges on the full snapshot within seconds.
     */
    public void tick() {
        flushDeltaOutbox();
        if (++refreshTicks >= 10) {
            refreshTicks = 0;
            refreshPlayerSnapshots();
        }
        drainSnapshotPages();
    }

    private void refreshPlayerSnapshots() {
        for (ServerPlayer player : runtime.server().getPlayerList().getPlayers()) {
            UUID worldId = runtime.worldId(player.level());
            PlayerSyncPosition current = new PlayerSyncPosition(
                    worldId, player.blockPosition().getX() >> 4, player.blockPosition().getZ() >> 4);
            if (!current.equals(syncPositions.get(player.getUUID()))) sendIncrementalSnapshot(player);
        }
    }

    private void sendIncrementalSnapshot(ServerPlayer player) {
        sendSnapshotData(player, false);
    }

    private void sendSnapshotData(ServerPlayer player, boolean forceReset) {
        if (player.connection == null) return;
        UUID playerId = player.getUUID();
        UUID worldId = runtime.worldId(player.level());
        PlayerSyncPosition current = new PlayerSyncPosition(
                worldId, player.blockPosition().getX() >> 4, player.blockPosition().getZ() >> 4);
        PlayerSyncPosition previousPosition = syncPositions.get(playerId);
        boolean reset = forceReset || previousPosition == null
                || !previousPosition.worldId.equals(current.worldId);

        syncPositions.put(playerId, current);
        Set<ChunkKey> subscribed = playerSubscriptions.computeIfAbsent(playerId, k -> ConcurrentHashMap.newKeySet());

        if (reset) {
            sendPacket(player, MicrovoxelProtocol.clear());
            subscribed.clear();
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

        // New chunks are streamed nearest-first so the buildings around the player appear
        // before distant territory. Only the first page goes out immediately; the remainder
        // is queued and drained a page per few ticks (see tick()).
        List<ChunkKey> pending = new ArrayList<>();
        for (ChunkKey chunk : desired) {
            if (!subscribed.contains(chunk)) pending.add(chunk);
        }
        sortNearestFirst(pending, current.chunkX, current.chunkZ);
        sendChunkPages(player, subscribed, pending);
    }

    /**
     * Sends the first page of new chunks synchronously and queues the rest. Every fully sent
     * chunk joins the subscription set at send time, so reconnects and movement diffs never
     * observe a half-subscribed chunk.
     */
    private void sendChunkPages(ServerPlayer player, Set<ChunkKey> subscribed, List<ChunkKey> pending) {
        if (pending.isEmpty()) return;
        // Volume counts are read once per chunk here; pages below reuse the same numbers.
        // Empty chunks subscribe immediately without consuming page budget or packets.
        Map<ChunkKey, Integer> counts = new java.util.HashMap<>();
        pending.removeIf(chunk -> {
            int count = chunkVolumeCount(chunk);
            if (count <= 0) {
                subscribed.add(chunk);
                return true;
            }
            counts.put(chunk, count);
            return false;
        });
        if (pending.isEmpty()) return;
        List<List<ChunkKey>> pages = paginate(pending, SNAPSHOT_PAGE_VOLUMES,
                chunk -> counts.getOrDefault(chunk, 0));
        sendPage(player, subscribed, pages.get(0));
        int firstPageVolumes = sumVolumes(pages.get(0), counts);
        if (pages.size() > 1) {
            SnapshotPages queued = snapshotPages.get(player.getUUID());
            if (queued == null) {
                queued = new SnapshotPages(nextSnapshotId - 1, runtime.serverTick());
                snapshotPages.put(player.getUUID(), queued);
            }
            queued.totalVolumes += sumVolumes(pending, counts);
            for (int page = 1; page < pages.size(); page++) {
                queued.remaining.addAll(pages.get(page));
            }
            queued.sentVolumes += firstPageVolumes;
            announceProgress(player, queued);
        } else {
            SnapshotPages queued = snapshotPages.get(player.getUUID());
            if (queued != null) queued.sentVolumes += firstPageVolumes;
        }
    }

    /** Drains one queued page per player every few ticks, then closes paged snapshots. */
    private void drainSnapshotPages() {
        if (snapshotPages.isEmpty()) return;
        // Yield to live traffic: bulk backfill waits while real gameplay packets flow.
        if (runtime.serverTick() - lastLiveSendTick < 2) return;
        for (Map.Entry<UUID, SnapshotPages> entry : List.copyOf(snapshotPages.entrySet())) {
            SnapshotPages pages = entry.getValue();
            if (runtime.serverTick() - pages.lastSendTick < SNAPSHOT_PAGE_INTERVAL_TICKS) continue;
            ServerPlayer player = runtime.server().getPlayerList().getPlayer(entry.getKey());
            Set<ChunkKey> subscribed = playerSubscriptions.get(entry.getKey());
            if (player == null || player.connection == null || subscribed == null) {
                snapshotPages.remove(entry.getKey());
                continue;
            }
            List<ChunkKey> page = new ArrayList<>(SNAPSHOT_PAGE_VOLUMES);
            int volumes = 0;
            while (!pages.remaining.isEmpty()) {
                ChunkKey chunk = pages.remaining.peek();
                int count = chunkVolumeCount(chunk);
                if (count <= 0) {
                    // Emptied since queuing: subscribe silently, no packets needed.
                    pages.remaining.poll();
                    subscribed.add(chunk);
                    continue;
                }
                if (!page.isEmpty() && volumes + count > SNAPSHOT_PAGE_VOLUMES) break;
                pages.remaining.poll();
                page.add(chunk);
                volumes += count;
            }
            pages.lastSendTick = runtime.serverTick();
            if (page.isEmpty()) {
                finishPagedSnapshot(player, entry.getKey(), pages);
                continue;
            }
            sendPage(player, subscribed, page);
            pages.sentVolumes += volumes;
            if (pages.remaining.isEmpty()) {
                finishPagedSnapshot(player, entry.getKey(), pages);
            } else {
                announceProgress(player, pages);
            }
        }
    }

    private void sendPage(ServerPlayer player, Set<ChunkKey> subscribed, List<ChunkKey> page) {
        for (ChunkKey chunk : page) {
            List<Map.Entry<MicrovoxelKey, MicrovoxelVolume>> entries =
                    runtime.store().inChunk(chunk.worldId(), chunk.x(), chunk.z());
            for (int i = 0; i < entries.size(); i += 32) {
                sendPacket(player, MicrovoxelProtocol.batchUpsert(
                        chunk.x(), chunk.z(), entries.subList(i, Math.min(i + 32, entries.size()))));
            }
            // Fluid levels ride the same page so snapshots converge surfaces too.
            if (runtime.fluids() != null) {
                for (Map.Entry<MicrovoxelKey, MicrovoxelVolume> entry : entries) {
                    ua.rp.chat.microvoxel.FluidVolume fluid =
                            runtime.fluids().get(entry.getKey());
                    if (fluid != null && !fluid.isDry()) {
                        sendFluidUpsert(player, entry.getKey(),
                                fluid.revision(), fluid.kind().code(), fluid.levelsCopy());
                    }
                }
            }
            subscribed.add(chunk);
        }
    }

    private void finishPagedSnapshot(ServerPlayer player, UUID playerId, SnapshotPages pages) {
        snapshotPages.remove(playerId);
        SnapshotDelivery delivery = pendingSnapshots.get(playerId);
        // Only full-snapshot deliveries are framed; incremental movement pages just end.
        if (delivery != null && delivery.id() == pages.snapshotId) {
            sendPacket(player, MicrovoxelProtocol.snapshotEnd(pages.snapshotId));
        }
        sendPacket(player, MicrovoxelProtocol.message(
                "Синк мікровокселів завершено: " + pages.sentVolumes + " томів."));
    }

    /** Quiet overlay progress for large deliveries (chat-framed messages would spam). */
    private void announceProgress(ServerPlayer player, SnapshotPages pages) {
        if (pages.totalVolumes <= SNAPSHOT_PAGE_VOLUMES) return;
        int percent = (int) ((long) pages.sentVolumes * 100L / Math.max(1, pages.totalVolumes));
        int milestone = percent / 25 * 25;
        if (milestone > pages.lastAnnouncedMilestone) {
            pages.lastAnnouncedMilestone = milestone;
            sendPacket(player, MicrovoxelProtocol.message(
                    "Синк мікровокселів: " + pages.sentVolumes + "/" + pages.totalVolumes));
        }
    }

    private int chunkVolumeCount(ChunkKey chunk) {
        return runtime.store().inChunk(chunk.worldId(), chunk.x(), chunk.z()).size();
    }

    private static int sumVolumes(List<ChunkKey> chunks, Map<ChunkKey, Integer> counts) {
        int total = 0;
        for (ChunkKey chunk : chunks) total += counts.getOrDefault(chunk, 0);
        return total;
    }

    /**
     * Orders new chunks nearest-first so the buildings around the player stream before distant
     * territory. Pure and deterministic: unit-tested without a server.
     */
    public static void sortNearestFirst(List<ChunkKey> chunks, int centerX, int centerZ) {
        chunks.sort(java.util.Comparator.comparingInt(chunk ->
                (chunk.x() - centerX) * (chunk.x() - centerX)
                        + (chunk.z() - centerZ) * (chunk.z() - centerZ)));
    }

    /**
     * Splits an ordered chunk list into pages capped by cumulative volume count. Pure and
     * deterministic: unit-tested without a server.
     */
    public static <T> List<List<T>> paginate(List<T> ordered, int pageVolumeBudget,
                                             java.util.function.ToIntFunction<T> volumeCount) {
        List<List<T>> pages = new ArrayList<>();
        List<T> current = new ArrayList<>();
        int used = 0;
        for (T item : ordered) {
            int cost = Math.max(1, volumeCount.applyAsInt(item));
            if (!current.isEmpty() && used + cost > pageVolumeBudget) {
                pages.add(List.copyOf(current));
                current = new ArrayList<>();
                used = 0;
            }
            current.add(item);
            used += cost;
        }
        if (!current.isEmpty()) pages.add(List.copyOf(current));
        return pages;
    }

    public void subscribe(UUID playerId, ChunkKey chunk) {
        playerSubscriptions.computeIfAbsent(playerId, ignored -> ConcurrentHashMap.newKeySet())
                .add(chunk);
    }

    public Set<ChunkKey> subscriptions(UUID playerId) {
        return playerSubscriptions.get(playerId);
    }

    public void broadcastUpsert(MicrovoxelKey key, MicrovoxelVolume volume) {
        markLiveTraffic();
        // A full upsert supersedes any queued delta for the same volume.
        if (deltaOutbox.remove(key) != null) {
            ua.rp.chat.microvoxel.MicrovoxelMetrics.inc("net.delta.superseded");
        }
        for (ServerPlayer player : nearbyPlayers(key)) sendUpsert(player, key, volume);
    }

    public void broadcastRemove(MicrovoxelKey key) {
        markLiveTraffic();
        if (deltaOutbox.remove(key) != null) {
            ua.rp.chat.microvoxel.MicrovoxelMetrics.inc("net.delta.superseded");
        }
        for (ServerPlayer player : nearbyPlayers(key)) sendRemove(player, key);
    }

    public void broadcastRemoveExcept(MicrovoxelKey key, ServerPlayer excluded) {
        markLiveTraffic();
        if (deltaOutbox.remove(key) != null) {
            ua.rp.chat.microvoxel.MicrovoxelMetrics.inc("net.delta.superseded");
        }
        for (ServerPlayer player : nearbyPlayers(key)) {
            if (player != excluded) sendRemove(player, key);
        }
    }

    public void broadcastDelta(MicrovoxelKey key, MicrovoxelVolume volume, int cellIndex, String material) {
        broadcastDeltaExcept(key, volume, cellIndex, material, null);
    }

    public void broadcastDeltaExcept(MicrovoxelKey key, MicrovoxelVolume volume,
                                      int cellIndex, String material, ServerPlayer excluded) {
        // Coalesced, not sent: rapid clicks on one volume collapse to a single delta per tick
        // at flush time. A same-tick upsert/remove supersedes the queued delta entirely.
        markLiveTraffic();
        deltaOutbox.put(key, new PendingDelta(cellIndex, material, volume.revision(),
                excluded == null ? null : excluded.getUUID()));
    }

    /**
     * Flushes one coalesced delta per volume. Runs every server tick before snapshot work so
     * live edits keep single-tick latency while bursts collapse to one packet each.
     */
    public void flushDeltaOutbox() {
        if (deltaOutbox.isEmpty()) return;
        Map<MicrovoxelKey, PendingDelta> drained = new java.util.HashMap<>(deltaOutbox);
        deltaOutbox.clear();
        for (Map.Entry<MicrovoxelKey, PendingDelta> entry : drained.entrySet()) {
            MicrovoxelKey key = entry.getKey();
            PendingDelta delta = entry.getValue();
            ua.rp.chat.microvoxel.MicrovoxelMetrics.inc("net.delta");
            for (ServerPlayer player : nearbyPlayers(key)) {
                if (delta.excluded != null && delta.excluded.equals(player.getUUID())) continue;
                sendPacket(player, MicrovoxelProtocol.deltaUpsert(
                        key.chunkX(), key.chunkZ(), key, delta.revision, delta.cell, delta.material));
            }
        }
        ua.rp.chat.microvoxel.MicrovoxelMetrics.add("net.delta.coalesced", drained.size());
    }

    private record PendingDelta(int cell, String material, int revision, UUID excluded) {
    }

    public void broadcastTransaction(long transactionId, List<MicrovoxelProtocol.StateChange> changes) {
        ua.rp.chat.microvoxel.MicrovoxelMetrics.inc("net.transaction");
        for (ServerPlayer player : runtime.server().getPlayerList().getPlayers()) {
            Set<ChunkKey> subscriptions = playerSubscriptions.get(player.getUUID());
            if (subscriptions == null || subscriptions.isEmpty()) continue;
            List<MicrovoxelProtocol.StateChange> visible = new ArrayList<>();
            for (MicrovoxelProtocol.StateChange change : changes) {
                if (subscriptions.contains(ChunkKey.of(change.key()))) visible.add(change);
            }
            if (!visible.isEmpty()) {
                sendPacket(player, MicrovoxelProtocol.transaction(transactionId, visible));
            }
        }
    }

    public List<ServerPlayer> nearbyPlayers(MicrovoxelKey key) {
        ChunkKey chunkKey = ChunkKey.of(key);
        List<ServerPlayer> result = new ArrayList<>();
        for (ServerPlayer player : runtime.server().getPlayerList().getPlayers()) {
            Set<ChunkKey> subs = playerSubscriptions.get(player.getUUID());
            if (subs != null && subs.contains(chunkKey)) {
                result.add(player);
            }
        }
        return result;
    }

    public void sendUpsert(ServerPlayer player, MicrovoxelKey key, MicrovoxelVolume volume) {
        markLiveTraffic();
        ua.rp.chat.microvoxel.MicrovoxelMetrics.inc("net.upsert");
        byte[] packet = MicrovoxelProtocol.batchUpsert(
                key.chunkX(), key.chunkZ(),
                List.of(Map.entry(key, volume))
        );
        sendPacket(player, packet);
    }

    public void sendEditResult(ServerPlayer player, long transactionId, boolean accepted,
                               MicrovoxelKey key, MicrovoxelVolume volume) {
        markLiveTraffic();
        sendPacket(player, MicrovoxelProtocol.editResult(
                transactionId, accepted, key, volume == null ? null : volume.copy()));
    }

    public void sendRemove(ServerPlayer player, MicrovoxelKey key) {
        markLiveTraffic();
        ua.rp.chat.microvoxel.MicrovoxelMetrics.inc("net.remove");
        sendPacket(player, MicrovoxelProtocol.remove(key));
        // Idempotent: clients without fluid data drop nothing.
        sendPacket(player, MicrovoxelProtocol.fluidRemove(key));
    }

    /** Authoritative fluid levels for one volume (revision-guarded on the client). */
    public void sendFluidUpsert(ServerPlayer player, MicrovoxelKey key,
                                int revision, byte[] levels) {
        sendFluidUpsert(player, key, revision, 0, levels);
    }

    /** Kind-aware variant: water renders water, lava renders lava. */
    public void sendFluidUpsert(ServerPlayer player, MicrovoxelKey key,
                                int revision, int kindCode, byte[] levels) {
        markLiveTraffic();
        ua.rp.chat.microvoxel.MicrovoxelMetrics.inc("net.fluid");
        sendPacket(player, MicrovoxelProtocol.fluidUpsert(key, revision, kindCode, levels));
    }

    public void feedback(ServerPlayer player, String message) {
        player.sendSystemMessage(Component.literal(message), true);
        sendPacket(player, MicrovoxelProtocol.message(message));
    }

    public void trace(ServerPlayer player, String message) {
        if (DEBUG) {
            runtime.logger().info("[MICROVOXEL] player="
                    + player.getScoreboardName() + " " + message);
        }
    }

    public void sendPacket(ServerPlayer player, byte[] bytes) {
        if (player.connection != null) {
            ua.rp.chat.microvoxel.MicrovoxelMetrics.inc("net.packets");
            ua.rp.chat.microvoxel.MicrovoxelMetrics.add("net.bytes", bytes.length);
            ServerPlayNetworking.send(player, new MicrovoxelSyncPayload(bytes));
        }
    }

    /**
     * Marks interactive (non-paged) traffic. Page drain yields while live edits, mining cracks
     * or resyncs are flowing so bulk backfill can never delay gameplay packets within a tick.
     */
    public void markLiveTraffic() {
        lastLiveSendTick = runtime.serverTick();
    }

    private long lastLiveSendTick = Long.MIN_VALUE / 2;

    private record PlayerSyncPosition(UUID worldId, int chunkX, int chunkZ) {
    }

    private record SnapshotDelivery(long id, long sentAtTick, int retries) {
    }

    /**
     * Remaining pages of one snapshot delivery. Drained by tick() while the player stands
     * still; the END frame is emitted only after the last page so the client never
     * acknowledges a half-delivered snapshot.
     */
    private static final class SnapshotPages {
        private final long snapshotId;
        private final java.util.ArrayDeque<ChunkKey> remaining = new java.util.ArrayDeque<>();
        private int totalVolumes;
        private int sentVolumes;
        private int lastAnnouncedMilestone;
        private long lastSendTick;

        private SnapshotPages(long snapshotId, long nowTick) {
            this.snapshotId = snapshotId;
            this.lastSendTick = nowTick;
        }
    }
}