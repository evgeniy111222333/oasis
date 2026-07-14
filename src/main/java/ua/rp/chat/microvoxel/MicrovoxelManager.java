package ua.rp.chat.microvoxel;

import io.papermc.paper.event.entity.EntityMoveEvent;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.TileState;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import ua.rp.chat.RPChat;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class MicrovoxelManager implements Listener, PluginMessageListener, CommandExecutor, TabCompleter {
    private static final int SYNC_RADIUS_CHUNKS = 8;
    private static final int MAX_PER_CHUNK = 512;
    private static final double MAX_REACH = 6.25;
    private static final double EPSILON = 1.0E-7;
    private static final long ACTION_WINDOW_MS = 1_000L;
    private static final int MAX_ACTIONS_PER_WINDOW = 40;
    private static final int MAX_COLLISION_CUBOIDS = 1024;

    private final RPChat plugin;
    private final MicrovoxelStore store;
    private final Map<UUID, PlayerSyncPosition> syncPositions = new HashMap<>();
    private final Map<UUID, Map<MicrovoxelKey, Integer>> syncedRevisions = new HashMap<>();
    private final Map<UUID, RateWindow> actionRates = new HashMap<>();
    private boolean saveScheduled;
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
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getMessenger().registerOutgoingPluginChannel(plugin, MicrovoxelProtocol.SYNC_CHANNEL);
        Bukkit.getMessenger().registerIncomingPluginChannel(plugin, MicrovoxelProtocol.ACTION_CHANNEL, this);
        if (plugin.getCommand("microvoxel") != null) {
            plugin.getCommand("microvoxel").setExecutor(this);
            plugin.getCommand("microvoxel").setTabCompleter(this);
        }
        restoreMarkersInLoadedChunks();
        Bukkit.getScheduler().runTaskTimer(plugin, this::refreshPlayerSnapshots, 20L, 20L);
    }

    public void shutdown() {
        try {
            store.save();
        } catch (IOException error) {
            plugin.getLogger().severe("Unable to save microvoxels during shutdown: " + error.getMessage());
        }
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!MicrovoxelProtocol.ACTION_CHANNEL.equals(channel) || player == null || message == null
                || !storageAvailable || !player.hasPermission("rpchat.microvoxels.edit") || !allowAction(player)) {
            return;
        }
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(message))) {
            int action = input.readUnsignedByte();
            int x = input.readInt();
            int y = input.readInt();
            int z = input.readInt();
            int cell = input.readUnsignedShort();
            int expectedRevision = input.readInt();
            if (input.available() != 0 || cell >= MicrovoxelVolume.CELL_COUNT) return;
            MicrovoxelKey key = new MicrovoxelKey(player.getWorld().getUID(), x, y, z);
            if (!withinReach(player, key)) {
                feedback(player, "Микровоксель находится слишком далеко.");
                return;
            }
            switch (action) {
                case MicrovoxelProtocol.ACTION_CONVERT -> convert(player, key);
                case MicrovoxelProtocol.ACTION_REMOVE -> removeCell(player, key, cell, expectedRevision);
                case MicrovoxelProtocol.ACTION_ADD -> addCell(player, key, cell, expectedRevision);
                default -> {
                }
            }
        } catch (IOException | RuntimeException error) {
            plugin.getLogger().warning("Rejected malformed microvoxel action from " + player.getName()
                    + ": " + error.getMessage());
        }
    }

    private void convert(Player player, MicrovoxelKey key) {
        if (store.get(key) != null) {
            sendUpsert(player, key, store.get(key));
            return;
        }
        Block target = player.getTargetBlockExact((int) Math.ceil(MAX_REACH), FluidCollisionMode.NEVER);
        if (target == null || target.getX() != key.x() || target.getY() != key.y() || target.getZ() != key.z()) {
            feedback(player, "Нужно смотреть прямо на преобразуемый блок.");
            return;
        }
        if (!isEligibleFullBlock(target)) {
            feedback(player, "Можно преобразовать только обычный полноразмерный блок без содержимого.");
            return;
        }
        if (store.countInChunk(key.worldId(), key.chunkX(), key.chunkZ()) >= MAX_PER_CHUNK) {
            feedback(player, "В этом чанке достигнут безопасный лимит микровоксельных блоков.");
            return;
        }
        String blockData = target.getBlockData().getAsString();
        MicrovoxelVolume volume = MicrovoxelVolume.full(blockData);
        store.put(key, volume);
        updateMarker(key, volume);
        markDirty();
        broadcastUpsert(key, volume);
        feedback(player, "Блок преобразован в сетку 16×16×16. ЛКМ убирает, ПКМ добавляет микровоксель.");
    }

    private void removeCell(Player player, MicrovoxelKey key, int cell, int expectedRevision) {
        MicrovoxelVolume volume = store.get(key);
        ServerMicrovoxelRaycaster.Hit hit = raycastMicrovoxel(player);
        if (!validRevision(player, key, volume, expectedRevision) || !volume.occupied(cell)
                || hit == null || !hit.key().equals(key) || hit.cell() != cell) return;
        MicrovoxelVolume before = volume.copy();
        volume.remove(cell);
        if (volume.collisionCuboids().size() > MAX_COLLISION_CUBOIDS) {
            store.put(key, before);
            sendUpsert(player, key, before);
            feedback(player, "Форма слишком фрагментирована. Объедините соседние микровоксели.");
            return;
        }
        if (volume.occupiedCount() == 0) {
            store.remove(key);
            markerBlock(key).setType(Material.AIR, false);
            broadcastRemove(key);
        } else {
            updateMarker(key, volume);
            broadcastUpsert(key, volume);
        }
        markDirty();
    }

    private void addCell(Player player, MicrovoxelKey key, int cell, int expectedRevision) {
        MicrovoxelVolume volume = store.get(key);
        ServerMicrovoxelRaycaster.Hit hit = raycastMicrovoxel(player);
        if (!validRevision(player, key, volume, expectedRevision) || volume.occupied(cell)
                || !volume.hasOccupiedNeighbour(cell) || hit == null || !hit.key().equals(key)
                || hit.adjacentCell() != cell) return;
        BlockData material = selectedFullBlock(player, markerBlock(key).getLocation());
        if (material == null) {
            feedback(player, "Возьмите в основную или вторую руку полноразмерный блок.");
            return;
        }
        MicrovoxelVolume before = volume.copy();
        try {
            volume.put(cell, material.getAsString());
        } catch (IllegalStateException paletteFull) {
            feedback(player, "В одном микровоксельном блоке допускается не более "
                    + MicrovoxelVolume.MAX_PALETTE + " материалов.");
            return;
        }
        BoundingBox addedCell = cellBoundingBox(key, cell);
        if (!player.getWorld().getNearbyEntities(addedCell).isEmpty()) {
            store.put(key, before);
            feedback(player, "Нельзя поместить микровоксель внутрь игрока или другой сущности.");
            return;
        }
        if (volume.collisionCuboids().size() > MAX_COLLISION_CUBOIDS) {
            store.put(key, before);
            sendUpsert(player, key, before);
            feedback(player, "Форма слишком фрагментирована. Объедините соседние микровоксели.");
            return;
        }
        updateMarker(key, volume);
        broadcastUpsert(key, volume);
        markDirty();
    }

    private boolean validRevision(Player player, MicrovoxelKey key, MicrovoxelVolume volume, int expected) {
        if (volume == null) {
            sendRemove(player, key);
            return false;
        }
        if (volume.revision() != expected) {
            sendUpsert(player, key, volume);
            return false;
        }
        return true;
    }

    private boolean isEligibleFullBlock(Block block) {
        Material material = block.getType();
        if (!material.isBlock() || material.isAir() || material == Material.STRUCTURE_VOID
                || material == Material.LIGHT || material == Material.BARRIER || block.getState() instanceof TileState
                || block.getState() instanceof InventoryHolder) return false;
        return isFullCollision(block.getBlockData(), block.getLocation());
    }

    private BlockData selectedFullBlock(Player player, Location location) {
        ItemStack[] candidates = {
                player.getInventory().getItemInMainHand(),
                player.getInventory().getItemInOffHand()
        };
        for (ItemStack item : candidates) {
            Material material = item == null ? Material.AIR : item.getType();
            if (!material.isBlock() || material.isAir() || material == Material.STRUCTURE_VOID
                    || material == Material.LIGHT || material == Material.BARRIER) continue;
            BlockData data;
            try {
                data = material.createBlockData();
            } catch (RuntimeException ignored) {
                continue;
            }
            if (data.createBlockState() instanceof TileState || data.createBlockState() instanceof InventoryHolder) continue;
            if (isFullCollision(data, location)) return data;
        }
        return null;
    }

    private boolean isFullCollision(BlockData data, Location location) {
        try {
            var boxes = data.getCollisionShape(location).getBoundingBoxes();
            if (boxes.size() != 1) return false;
            BoundingBox box = boxes.iterator().next();
            return close(box.getWidthX(), 1.0) && close(box.getHeight(), 1.0) && close(box.getWidthZ(), 1.0);
        } catch (RuntimeException error) {
            return false;
        }
    }

    private ServerMicrovoxelRaycaster.Hit raycastMicrovoxel(Player player) {
        Location eye = player.getEyeLocation();
        Vector direction = eye.getDirection().normalize();
        ServerMicrovoxelRaycaster.Hit hit = ServerMicrovoxelRaycaster.cast(
                eye.getX(), eye.getY(), eye.getZ(), direction.getX(), direction.getY(), direction.getZ(), MAX_REACH,
                store.nearby(player.getWorld().getUID(), player.getLocation().getBlockX() >> 4,
                        player.getLocation().getBlockZ() >> 4, 1));
        if (hit == null) return null;
        RayTraceResult obstruction = player.getWorld().rayTraceBlocks(
                eye, direction, Math.max(0.0, hit.distance() - 0.001), FluidCollisionMode.NEVER, true);
        return obstruction == null ? hit : null;
    }

    private boolean withinReach(Player player, MicrovoxelKey key) {
        return player.getWorld().getUID().equals(key.worldId())
                && player.getEyeLocation().toVector().distanceSquared(
                new Vector(key.x() + 0.5, key.y() + 0.5, key.z() + 0.5)) <= MAX_REACH * MAX_REACH;
    }

    private boolean allowAction(Player player) {
        long now = System.currentTimeMillis();
        RateWindow previous = actionRates.get(player.getUniqueId());
        if (previous == null || now - previous.startedAt >= ACTION_WINDOW_MS) {
            actionRates.put(player.getUniqueId(), new RateWindow(now, 1));
            return true;
        }
        if (previous.count >= MAX_ACTIONS_PER_WINDOW) return false;
        actionRates.put(player.getUniqueId(), new RateWindow(previous.startedAt, previous.count + 1));
        return true;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> sendSnapshot(event.getPlayer()), 40L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        syncPositions.remove(event.getPlayer().getUniqueId());
        syncedRevisions.remove(event.getPlayer().getUniqueId());
        actionRates.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void protectMarker(BlockPlaceEvent event) {
        MicrovoxelKey key = key(event.getBlockPlaced());
        if (store.get(key) != null) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void protectMarker(BlockBreakEvent event) {
        MicrovoxelKey key = key(event.getBlock());
        if (store.get(key) != null) event.setCancelled(true);
    }

    @EventHandler
    public void restoreMarkers(ChunkLoadEvent event) {
        UUID worldId = event.getWorld().getUID();
        for (Map.Entry<MicrovoxelKey, MicrovoxelVolume> entry
                : store.inChunk(worldId, event.getChunk().getX(), event.getChunk().getZ())) {
            updateMarker(entry.getKey(), entry.getValue());
        }
    }

    private void restoreMarkersInLoadedChunks() {
        for (World world : Bukkit.getWorlds()) {
            for (org.bukkit.Chunk chunk : world.getLoadedChunks()) {
                for (Map.Entry<MicrovoxelKey, MicrovoxelVolume> entry
                        : store.inChunk(world.getUID(), chunk.getX(), chunk.getZ())) {
                    updateMarker(entry.getKey(), entry.getValue());
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void collidePlayer(PlayerMoveEvent event) {
        if (event.getTo() == null || event.getFrom().getWorld() != event.getTo().getWorld()) return;
        Location resolved = resolveMovement(event.getPlayer(), event.getFrom(), event.getTo());
        if (resolved != null) event.setTo(resolved);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void collideLivingEntity(EntityMoveEvent event) {
        if (event.getEntity() instanceof Player || event.getFrom().getWorld() != event.getTo().getWorld()) return;
        Location resolved = resolveMovement(event.getEntity(), event.getFrom(), event.getTo());
        if (resolved != null) event.setTo(resolved);
    }

    private Location resolveMovement(LivingEntity entity, Location from, Location to) {
        double dx = to.getX() - from.getX();
        double dy = to.getY() - from.getY();
        double dz = to.getZ() - from.getZ();
        if (Math.abs(dx) + Math.abs(dy) + Math.abs(dz) < EPSILON) return null;

        BoundingBox actual = entity.getBoundingBox();
        double halfWidthX = actual.getWidthX() * 0.5;
        double halfWidthZ = actual.getWidthZ() * 0.5;
        BoundingBox box = new BoundingBox(
                from.getX() - halfWidthX, from.getY(), from.getZ() - halfWidthZ,
                from.getX() + halfWidthX, from.getY() + actual.getHeight(), from.getZ() + halfWidthZ);
        double clippedY = clip(entity.getWorld(), box, dy, Axis.Y);
        box.shift(0, clippedY, 0);
        double clippedX = clip(entity.getWorld(), box, dx, Axis.X);
        box.shift(clippedX, 0, 0);
        double clippedZ = clip(entity.getWorld(), box, dz, Axis.Z);
        if (close(dx, clippedX) && close(dy, clippedY) && close(dz, clippedZ)) return null;

        Location resolved = from.clone().add(clippedX, clippedY, clippedZ);
        resolved.setYaw(to.getYaw());
        resolved.setPitch(to.getPitch());
        if (dy < 0 && clippedY > dy) entity.setFallDistance(0);
        return resolved;
    }

    private double clip(World world, BoundingBox player, double movement, Axis axis) {
        if (Math.abs(movement) < EPSILON) return 0.0;
        BoundingBox sweep = player.clone();
        if (axis == Axis.X) sweep.expandDirectional(movement, 0, 0);
        if (axis == Axis.Y) sweep.expandDirectional(0, movement, 0);
        if (axis == Axis.Z) sweep.expandDirectional(0, 0, movement);
        int minX = floor(sweep.getMinX()) - 1;
        int maxX = floor(sweep.getMaxX()) + 1;
        int minY = floor(sweep.getMinY()) - 1;
        int maxY = floor(sweep.getMaxY()) + 1;
        int minZ = floor(sweep.getMinZ()) - 1;
        int maxZ = floor(sweep.getMaxZ()) + 1;
        double clipped = movement;
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    MicrovoxelVolume volume = store.get(new MicrovoxelKey(world.getUID(), x, y, z));
                    if (volume == null) continue;
                    for (MicrovoxelVolume.Cuboid cuboid : volume.collisionCuboids()) {
                        double scale = 1.0 / MicrovoxelVolume.RESOLUTION;
                        BoundingBox obstacle = new BoundingBox(
                                x + cuboid.minX() * scale, y + cuboid.minY() * scale, z + cuboid.minZ() * scale,
                                x + cuboid.maxX() * scale, y + cuboid.maxY() * scale, z + cuboid.maxZ() * scale);
                        clipped = clipAgainst(player, obstacle, clipped, axis);
                    }
                }
            }
        }
        return clipped;
    }

    static double clipAgainst(BoundingBox moving, BoundingBox obstacle, double movement, Axis axis) {
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

    private static BoundingBox cellBoundingBox(MicrovoxelKey key, int cell) {
        double scale = 1.0 / MicrovoxelVolume.RESOLUTION;
        return new BoundingBox(
                key.x() + MicrovoxelVolume.x(cell) * scale,
                key.y() + MicrovoxelVolume.y(cell) * scale,
                key.z() + MicrovoxelVolume.z(cell) * scale,
                key.x() + (MicrovoxelVolume.x(cell) + 1) * scale,
                key.y() + (MicrovoxelVolume.y(cell) + 1) * scale,
                key.z() + (MicrovoxelVolume.z(cell) + 1) * scale);
    }

    private static boolean overlapsOtherAxes(BoundingBox a, BoundingBox b, Axis movementAxis) {
        if (movementAxis != Axis.X && !overlap(a.getMinX(), a.getMaxX(), b.getMinX(), b.getMaxX())) return false;
        if (movementAxis != Axis.Y && !overlap(a.getMinY(), a.getMaxY(), b.getMinY(), b.getMaxY())) return false;
        return movementAxis == Axis.Z || overlap(a.getMinZ(), a.getMaxZ(), b.getMinZ(), b.getMaxZ());
    }

    private static boolean overlap(double minA, double maxA, double minB, double maxB) {
        return maxA > minB + EPSILON && minA < maxB - EPSILON;
    }

    private static double min(BoundingBox box, Axis axis) {
        return axis == Axis.X ? box.getMinX() : axis == Axis.Y ? box.getMinY() : box.getMinZ();
    }

    private static double max(BoundingBox box, Axis axis) {
        return axis == Axis.X ? box.getMaxX() : axis == Axis.Y ? box.getMaxY() : box.getMaxZ();
    }

    private void refreshPlayerSnapshots() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            PlayerSyncPosition current = new PlayerSyncPosition(
                    player.getWorld().getUID(), player.getLocation().getBlockX() >> 4, player.getLocation().getBlockZ() >> 4);
            if (!current.equals(syncPositions.get(player.getUniqueId()))) sendSnapshot(player);
        }
    }

    private void sendSnapshot(Player player) {
        if (!player.isOnline()) return;
        PlayerSyncPosition current = new PlayerSyncPosition(
                player.getWorld().getUID(), player.getLocation().getBlockX() >> 4, player.getLocation().getBlockZ() >> 4);
        PlayerSyncPosition previousPosition = syncPositions.get(player.getUniqueId());
        Map<MicrovoxelKey, Integer> previous = new HashMap<>(
                syncedRevisions.getOrDefault(player.getUniqueId(), Map.of()));
        boolean reset = previousPosition == null || !previousPosition.worldId.equals(current.worldId);
        syncPositions.put(player.getUniqueId(), current);
        if (reset) {
            player.sendPluginMessage(plugin, MicrovoxelProtocol.SYNC_CHANNEL, MicrovoxelProtocol.clear());
            previous.clear();
        }

        Map<MicrovoxelKey, Integer> desired = new HashMap<>();
        List<Map.Entry<MicrovoxelKey, MicrovoxelVolume>> nearby =
                store.nearby(current.worldId, current.chunkX, current.chunkZ, SYNC_RADIUS_CHUNKS);
        for (Map.Entry<MicrovoxelKey, MicrovoxelVolume> entry : nearby) {
            desired.put(entry.getKey(), entry.getValue().revision());
        }
        for (MicrovoxelKey oldKey : previous.keySet()) {
            if (!desired.containsKey(oldKey)) {
                player.sendPluginMessage(plugin, MicrovoxelProtocol.SYNC_CHANNEL, MicrovoxelProtocol.remove(oldKey));
            }
        }
        for (Map.Entry<MicrovoxelKey, MicrovoxelVolume> entry : nearby) {
            if (!java.util.Objects.equals(previous.get(entry.getKey()), entry.getValue().revision())) {
                player.sendPluginMessage(plugin, MicrovoxelProtocol.SYNC_CHANNEL,
                        MicrovoxelProtocol.upsert(entry.getKey(), entry.getValue()));
            }
        }
        syncedRevisions.put(player.getUniqueId(), desired);
    }

    private void broadcastUpsert(MicrovoxelKey key, MicrovoxelVolume volume) {
        for (Player player : nearbyPlayers(key)) sendUpsert(player, key, volume);
    }

    private void broadcastRemove(MicrovoxelKey key) {
        for (Player player : nearbyPlayers(key)) sendRemove(player, key);
    }

    private List<Player> nearbyPlayers(MicrovoxelKey key) {
        World world = Bukkit.getWorld(key.worldId());
        if (world == null) return List.of();
        List<Player> result = new ArrayList<>();
        double max = SYNC_RADIUS_CHUNKS * 16.0 + 16.0;
        for (Player player : world.getPlayers()) {
            double dx = player.getLocation().getX() - (key.x() + 0.5);
            double dz = player.getLocation().getZ() - (key.z() + 0.5);
            if (dx * dx + dz * dz <= max * max) result.add(player);
        }
        return result;
    }

    private void sendUpsert(Player player, MicrovoxelKey key, MicrovoxelVolume volume) {
        player.sendPluginMessage(plugin, MicrovoxelProtocol.SYNC_CHANNEL, MicrovoxelProtocol.upsert(key, volume));
        syncedRevisions.computeIfAbsent(player.getUniqueId(), ignored -> new HashMap<>()).put(key, volume.revision());
    }

    private void sendRemove(Player player, MicrovoxelKey key) {
        player.sendPluginMessage(plugin, MicrovoxelProtocol.SYNC_CHANNEL, MicrovoxelProtocol.remove(key));
        Map<MicrovoxelKey, Integer> revisions = syncedRevisions.get(player.getUniqueId());
        if (revisions != null) revisions.remove(key);
    }

    private void feedback(Player player, String message) {
        player.sendActionBar(Component.text(message));
        player.sendPluginMessage(plugin, MicrovoxelProtocol.SYNC_CHANNEL, MicrovoxelProtocol.message(message));
    }

    private void markDirty() {
        if (saveScheduled) return;
        saveScheduled = true;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            saveScheduled = false;
            MicrovoxelStore.Snapshot snapshot = store.snapshot();
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    store.save(snapshot);
                } catch (IOException error) {
                    plugin.getLogger().severe("Unable to persist microvoxels: " + error.getMessage());
                }
            });
        }, 40L);
    }

    private Block markerBlock(MicrovoxelKey key) {
        World world = Bukkit.getWorld(key.worldId());
        if (world == null) throw new IllegalStateException("Microvoxel world is not loaded");
        return world.getBlockAt(key.x(), key.y(), key.z());
    }

    private void updateMarker(MicrovoxelKey key, MicrovoxelVolume volume) {
        Block marker = markerBlock(key);
        int lightLevel = 0;
        boolean[] usedMaterials = new boolean[volume.palette().size()];
        for (byte cell : volume.cellsCopy()) usedMaterials[Byte.toUnsignedInt(cell)] = true;
        for (int index = 1; index < volume.palette().size(); index++) {
            if (!usedMaterials[index]) continue;
            try {
                lightLevel = Math.max(lightLevel, Bukkit.createBlockData(volume.palette().get(index)).getLightEmission());
            } catch (RuntimeException ignored) {
                // Storage validation remains fail-closed; an unknown block state simply emits no light.
            }
        }
        if (lightLevel <= 0) {
            if (marker.getType() != Material.STRUCTURE_VOID) marker.setType(Material.STRUCTURE_VOID, false);
            return;
        }
        org.bukkit.block.data.type.Light light = (org.bukkit.block.data.type.Light) Material.LIGHT.createBlockData();
        light.setLevel(Math.min(light.getMaximumLevel(), lightLevel));
        marker.setBlockData(light, false);
    }

    private static MicrovoxelKey key(Block block) {
        return new MicrovoxelKey(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Команда доступна только игроку.");
            return true;
        }
        if (!player.hasPermission("rpchat.microvoxels.edit")) {
            player.sendMessage("Недостаточно прав для редактирования микровокселей.");
            return true;
        }
        if (!storageAvailable) {
            player.sendMessage("Хранилище микровокселей повреждено и не восстановилось из резервной копии. Редактирование заблокировано.");
            return true;
        }
        String action = args.length == 0 ? "help" : args[0].toLowerCase(java.util.Locale.ROOT);
        if ("convert".equals(action)) {
            Block target = player.getTargetBlockExact((int) Math.ceil(MAX_REACH), FluidCollisionMode.NEVER);
            if (target == null) {
                feedback(player, "Блок не выбран.");
            } else {
                convert(player, key(target));
            }
            return true;
        }
        if ("restore".equals(action)) {
            restoreLookedAt(player);
            return true;
        }
        if ("status".equals(action)) {
            player.sendMessage("Микровокселей в мире: " + store.size()
                    + ". Разрешение: 16×16×16, лимит: " + MAX_PER_CHUNK + " блоков на чанк.");
            return true;
        }
        player.sendMessage("/microvoxel convert — преобразовать выбранный полный блок");
        player.sendMessage("/microvoxel restore — вернуть выбранный микровоксельный блок в обычный");
        player.sendMessage("Клиент: M — режим, C — преобразование, ЛКМ/ПКМ — убрать/добавить.");
        return true;
    }

    private void restoreLookedAt(Player player) {
        Location eye = player.getEyeLocation();
        Vector direction = eye.getDirection();
        MicrovoxelKey bestKey = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (Map.Entry<MicrovoxelKey, MicrovoxelVolume> entry
                : store.nearby(player.getWorld().getUID(), player.getLocation().getBlockX() >> 4,
                player.getLocation().getBlockZ() >> 4, 1)) {
            MicrovoxelKey candidate = entry.getKey();
            BoundingBox box = new BoundingBox(candidate.x(), candidate.y(), candidate.z(),
                    candidate.x() + 1, candidate.y() + 1, candidate.z() + 1);
            RayTraceResult hit = box.rayTrace(eye.toVector(), direction, MAX_REACH);
            if (hit != null) {
                double distance = hit.getHitPosition().distanceSquared(eye.toVector());
                if (distance < bestDistance) {
                    bestDistance = distance;
                    bestKey = candidate;
                }
            }
        }
        if (bestKey == null) {
            feedback(player, "Микровоксельный блок не выбран.");
            return;
        }
        MicrovoxelVolume volume = store.remove(bestKey);
        String material = volume == null ? null : firstMaterial(volume);
        if (material == null) return;
        try {
            markerBlock(bestKey).setBlockData(Bukkit.createBlockData(material), false);
            broadcastRemove(bestKey);
            markDirty();
            feedback(player, "Микровоксельный блок восстановлен как обычный.");
        } catch (RuntimeException error) {
            store.put(bestKey, volume);
            feedback(player, "Не удалось восстановить исходный материал.");
        }
    }

    private static String firstMaterial(MicrovoxelVolume volume) {
        for (int cell = 0; cell < MicrovoxelVolume.CELL_COUNT; cell++) {
            if (volume.occupied(cell)) return volume.material(cell);
        }
        return null;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return List.of("convert", "restore", "status").stream()
                .filter(value -> value.startsWith(args[0].toLowerCase(java.util.Locale.ROOT))).toList();
        return List.of();
    }

    private static boolean close(double left, double right) {
        return Math.abs(left - right) < 1.0E-6;
    }

    private static int floor(double value) {
        return (int) Math.floor(value);
    }

    enum Axis { X, Y, Z }

    private record PlayerSyncPosition(UUID worldId, int chunkX, int chunkZ) {
    }

    private record RateWindow(long startedAt, int count) {
    }
}
