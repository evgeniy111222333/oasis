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
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.block.Action;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import ua.rp.chat.RPChat;
import ua.rp.chat.heavyhammer.HeavyHammerImpact;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
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
    private static final double CLIENT_LOOK_MAX_DIVERGENCE_DEGREES = 4.0;
    private static final double CLIENT_LOOK_MIN_DOT = Math.cos(Math.toRadians(CLIENT_LOOK_MAX_DIVERGENCE_DEGREES));
    private static final double CLIENT_EYE_MAX_DELTA = 0.75;
    private static final int[][] BOUNDARY_DIRECTIONS = {
            {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}
    };

    private final RPChat plugin;
    private final MicrovoxelStore store;
    private final Map<UUID, PlayerSyncPosition> syncPositions = new HashMap<>();
    private final Map<UUID, Map<MicrovoxelKey, Integer>> syncedRevisions = new HashMap<>();
    private final Map<UUID, RateWindow> actionRates = new HashMap<>();
    private final Map<UUID, Long> miningStartTimes = new HashMap<>();
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
            if (action == MicrovoxelProtocol.ACTION_READY) {
                Bukkit.getScheduler().runTask(plugin, () -> sendSnapshot(player));
                return;
            }
            int x = input.readInt();
            int y = input.readInt();
            int z = input.readInt();
            int cell = input.readUnsignedShort();
            int expectedRevision = input.readInt();
            Vector clientLook = new Vector(input.readFloat(), input.readFloat(), input.readFloat());
            Vector clientEye = new Vector(input.readFloat(), input.readFloat(), input.readFloat());
            if (input.available() != 0 || cell >= MicrovoxelVolume.CELL_COUNT) return;
            MicrovoxelKey key = new MicrovoxelKey(player.getWorld().getUID(), x, y, z);
            trace(player, "ACTION_RX action=" + action + " pos=" + x + "," + y + "," + z
                    + " cell=" + cell + " revision=" + expectedRevision);
            if (!validClientLook(clientLook) || !validClientEye(clientEye)) return;
            // The view-rotation packet and custom click packet can reach Paper in neighbouring ticks.
            // Defer one tick so the authoritative eye ray has the player's newest orientation.
            Bukkit.getScheduler().runTask(plugin, () -> applyAction(player,
                    new QueuedAction(action, key, cell, expectedRevision, clientLook.normalize(), clientEye)));
        } catch (IOException | RuntimeException error) {
            plugin.getLogger().warning("Rejected malformed microvoxel action from " + player.getName()
                    + ": " + error.getMessage());
        }
    }

    private void applyAction(Player player, QueuedAction action) {
        if (!player.isOnline() || !storageAvailable || !player.hasPermission("rpchat.microvoxels.edit")) return;
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

    /**
     * First-cut path for edit mode. To the player this is a normal block being carved directly;
     * internally the server creates the volume and removes the exact first cell as one atomic
     * operation. No command or visible intermediate conversion is required.
     */
    private void carveStandardBlock(Player player, MicrovoxelKey key, int cell, Vector clientLook, Vector clientEye) {
        MicrovoxelVolume existing = store.get(key);
        if (existing != null) {
            // A concurrent first cut already converted this block; continue through the normal,
            // revision-safe removal path instead of overwriting that player's edit.
            removeCell(player, key, cell, existing.revision(), clientLook, clientEye);
            return;
        }
        Location clientLocation = boundedClientEye(player, clientEye);
        if (clientLocation == null) return;
        RayTraceResult trace = player.getWorld().rayTraceBlocks(
                clientLocation, clientLook, MAX_REACH, FluidCollisionMode.NEVER, true);
        Block target = trace == null ? null : trace.getHitBlock();
        if (target == null || target.getX() != key.x() || target.getY() != key.y() || target.getZ() != key.z()
                || !isEligibleFullBlock(target)) {
            trace(player, "ACTION_REJECT carve-standard-target-mismatch");
            feedback(player, "РќСѓР¶РЅРѕ РЅР°РІРµСЃС‚РёСЃСЊ РЅР° РѕР±С‹С‡РЅС‹Р№ РїРѕР»РЅС‹Р№ Р±Р»РѕРє РµС‰С‘ СЂР°Р·.");
            return;
        }
        int authoritativeCell = cellAtStandardHit(key, trace);
        if (authoritativeCell != cell) {
            trace(player, "ACTION_REJECT carve-standard-cell expected=" + cell + " actual=" + authoritativeCell);
            feedback(player, "Р¦РµР»СЊ РёР·РјРµРЅРёР»Р°СЃСЊ. РќР°РІРµРґРёС‚РµСЃСЊ РЅР° СЏС‡РµР№РєСѓ РµС‰С‘ СЂР°Р·.");
            return;
        }
        if (store.countInChunk(key.worldId(), key.chunkX(), key.chunkZ()) >= MAX_PER_CHUNK) {
            feedback(player, "Р’ СЌС‚РѕРј С‡Р°РЅРєРµ РґРѕСЃС‚РёРіРЅСѓС‚ Р±РµР·РѕРїР°СЃРЅС‹Р№ Р»РёРјРёС‚ РјРёРєСЂРѕРІРѕРєСЃРµР»СЊРЅС‹С… Р±Р»РѕРєРѕРІ.");
            return;
        }
        MicrovoxelVolume volume = MicrovoxelVolume.full(target.getBlockData().getAsString());
        volume.remove(cell);
        store.put(key, volume);
        updateMarker(key, volume);
        broadcastUpsert(key, volume);
        markDirty();
        trace(player, "ACTION_APPLIED carve-standard cell=" + cell + " revision=" + volume.revision());
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

    private void removeCell(Player player, MicrovoxelKey key, int cell, int expectedRevision, Vector clientLook,
                            Vector clientEye) {
        MicrovoxelVolume volume = store.get(key);
        ServerMicrovoxelRaycaster.Hit hit = validatedHit(player, key, cell, clientLook, clientEye, true);
        if (!validRevision(player, key, volume, expectedRevision)) return;
        if (!volume.occupied(cell)) {
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
        trace(player, "ACTION_APPLIED remove cell=" + cell + " revision=" + volume.revision());
    }

    private void addCell(Player player, MicrovoxelKey key, int cell, int expectedRevision, Vector clientLook,
                         Vector clientEye) {
        MicrovoxelVolume volume = store.get(key);
        // `cell` is deliberately empty for an add operation.  The authoritative ray must hit
        // the occupied source cell and expose `cell` as the adjacent placement position; asking
        // it to hit `cell` itself makes every valid right-click fail as "target changed".
        ServerMicrovoxelRaycaster.Hit hit = validatedHit(player, key, cell, clientLook, clientEye, false);
        if (!validRevision(player, key, volume, expectedRevision)) return;
        if (volume.occupied(cell)) {
            trace(player, "ACTION_REJECT add-cell-occupied");
            sendUpsert(player, key, volume);
            feedback(player, "Эта ячейка уже занята. Сетка синхронизирована.");
            return;
        }
        if (!volume.hasOccupiedNeighbour(cell)) {
            trace(player, "ACTION_REJECT add-no-neighbour");
            feedback(player, "Новый микровоксель должен прилегать к существующему.");
            return;
        }
        if (hit == null || !hit.key().equals(key) || hit.adjacentCell() != cell) {
            trace(player, "ACTION_REJECT add-raycast-mismatch");
            sendUpsert(player, key, volume);
            feedback(player, "Цель изменилась. Наведитесь на грань ячейки ещё раз.");
            return;
        }
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
        trace(player, "ACTION_APPLIED add cell=" + cell + " revision=" + volume.revision());
    }

    private boolean validRevision(Player player, MicrovoxelKey key, MicrovoxelVolume volume, int expected) {
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
        return raycastMicrovoxel(player, eye, direction);
    }

    private ServerMicrovoxelRaycaster.Hit raycastMicrovoxel(Player player, Location eye, Vector direction) {
        ServerMicrovoxelRaycaster.Hit hit = ServerMicrovoxelRaycaster.cast(
                eye.getX(), eye.getY(), eye.getZ(), direction.getX(), direction.getY(), direction.getZ(), MAX_REACH,
                store.nearby(player.getWorld().getUID(), player.getLocation().getBlockX() >> 4,
                        player.getLocation().getBlockZ() >> 4, 1));
        if (hit == null) return null;
        RayTraceResult obstruction = player.getWorld().rayTraceBlocks(
                eye, direction, Math.max(0.0, hit.distance() - 0.001), FluidCollisionMode.NEVER, true);
        return obstruction == null ? hit : null;
    }

    private ServerMicrovoxelRaycaster.Hit validatedHit(Player player, MicrovoxelKey key, int cell, Vector clientLook,
                                                       Vector clientEye, boolean requireRequestedCell) {
        ServerMicrovoxelRaycaster.Hit serverHit = raycastMicrovoxel(player);
        if (matches(serverHit, key, cell, requireRequestedCell)) return serverHit;

        Location eye = player.getEyeLocation();
        Vector serverLook = eye.getDirection().normalize();
        if (serverLook.dot(clientLook) < CLIENT_LOOK_MIN_DOT) {
            trace(player, "ACTION_REJECT client-look-diverged dot=" + String.format(java.util.Locale.ROOT, "%.5f", serverLook.dot(clientLook)));
            return null;
        }
        Location recoveredEye = boundedClientEye(player, clientEye);
        if (recoveredEye == null) return null;
        Vector eyeDelta = clientEye.clone().subtract(eye.toVector());
        // The camera and the movement packet are sampled on the client in the same render frame.
        // Paper can still be one movement packet behind, which shifts a hit by several 1/16 cells
        // even when both sides agree on the look direction. The bounded client eye is therefore
        // used only as a recovery origin; reach, world obstruction and exact first-hit checks stay
        // authoritative below.
        ServerMicrovoxelRaycaster.Hit recovered = raycastMicrovoxel(player, recoveredEye, clientLook);
        if (matches(recovered, key, cell, requireRequestedCell)) {
            trace(player, "ACTION_RECOVERED client-eye-ray delta=" + String.format(java.util.Locale.ROOT,
                    "%.3f", eyeDelta.length()));
            return recovered;
        }
        trace(player, "ACTION_RAY_MISMATCH server=" + hitLabel(serverHit) + " client=" + hitLabel(recovered)
                + " expected=" + key.x() + "," + key.y() + "," + key.z() + ":" + cell);
        return null;
    }

    private static boolean matches(ServerMicrovoxelRaycaster.Hit hit, MicrovoxelKey key, int cell,
                                   boolean requireRequestedCell) {
        if (hit == null || !hit.key().equals(key)) return false;
        return requireRequestedCell ? hit.cell() == cell : hit.adjacentCell() == cell;
    }

    private static String hitLabel(ServerMicrovoxelRaycaster.Hit hit) {
        return hit == null ? "none" : hit.key().x() + "," + hit.key().y() + "," + hit.key().z() + ":" + hit.cell();
    }

    private static boolean validClientLook(Vector look) {
        return Double.isFinite(look.getX()) && Double.isFinite(look.getY()) && Double.isFinite(look.getZ())
                && look.lengthSquared() > 0.98 && look.lengthSquared() < 1.02;
    }

    private static boolean validClientEye(Vector eye) {
        return Double.isFinite(eye.getX()) && Double.isFinite(eye.getY()) && Double.isFinite(eye.getZ());
    }

    private Location boundedClientEye(Player player, Vector clientEye) {
        Location serverEye = player.getEyeLocation();
        Vector delta = clientEye.clone().subtract(serverEye.toVector());
        if (delta.lengthSquared() > CLIENT_EYE_MAX_DELTA * CLIENT_EYE_MAX_DELTA) {
            trace(player, "ACTION_REJECT client-eye-diverged distance=" + String.format(java.util.Locale.ROOT,
                    "%.3f", delta.length()));
            return null;
        }
        return new Location(player.getWorld(), clientEye.getX(), clientEye.getY(), clientEye.getZ(),
                serverEye.getYaw(), serverEye.getPitch());
    }

    private static int cellAtStandardHit(MicrovoxelKey key, RayTraceResult hit) {
        org.bukkit.block.BlockFace face = hit.getHitBlockFace();
        Vector point = hit.getHitPosition().clone();
        if (face != null) point.subtract(face.getDirection().multiply(1.0E-4));
        int x = clampCell((int) Math.floor((point.getX() - key.x()) * MicrovoxelVolume.RESOLUTION));
        int y = clampCell((int) Math.floor((point.getY() - key.y()) * MicrovoxelVolume.RESOLUTION));
        int z = clampCell((int) Math.floor((point.getZ() - key.z()) * MicrovoxelVolume.RESOLUTION));
        return MicrovoxelVolume.index(x, y, z);
    }

    private static int clampCell(int cell) {
        return Math.max(0, Math.min(MicrovoxelVolume.RESOLUTION - 1, cell));
    }

    /** Подготавливает неизменяемую цель. Удар всё равно будет повторно проверен в кадре контакта. */
    public HammerTarget prepareHammerTarget(Player player, int x, int y, int z, int cell, int expectedRevision) {
        if (player == null || !storageAvailable || cell < 0 || cell >= MicrovoxelVolume.CELL_COUNT) return null;
        MicrovoxelKey key = new MicrovoxelKey(player.getWorld().getUID(), x, y, z);
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

    /** Совершает серверный удар и возвращает число действительно выбитых микровокселей. */
    public int commitHammerImpact(Player player, HammerTarget target) {
        if (player == null || target == null || !withinReach(player, target.key())) return 0;
        MicrovoxelVolume volume = store.get(target.key());
        ServerMicrovoxelRaycaster.Hit currentHit = raycastMicrovoxel(player);
        if (volume == null || volume.revision() != target.expectedRevision() || currentHit == null
                || !currentHit.key().equals(target.key())) {
            if (volume != null) sendUpsert(player, target.key(), volume);
            return 0;
        }

        MicrovoxelVolume before = volume.copy();
        int removed = 0;
        for (int cell : HeavyHammerImpact.cells(target.anchorCell(), target.face())) {
            if (volume.occupied(cell) && volume.remove(cell)) removed++;
        }
        if (removed == 0) return 0;
        if (volume.collisionCuboids().size() > MAX_COLLISION_CUBOIDS) {
            store.put(target.key(), before);
            sendUpsert(player, target.key(), before);
            feedback(player, "После удара форма стала слишком раздробленной.");
            return 0;
        }
        if (volume.occupiedCount() == 0) {
            store.remove(target.key());
            markerBlock(target.key()).setType(Material.AIR, false);
            broadcastRemove(target.key());
        } else {
            updateMarker(target.key(), volume);
            broadcastUpsert(target.key(), volume);
        }
        markDirty();
        return removed;
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
        UUID uuid = event.getPlayer().getUniqueId();
        syncPositions.remove(uuid);
        syncedRevisions.remove(uuid);
        actionRates.remove(uuid);
        miningStartTimes.remove(uuid);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void protectMarker(BlockPlaceEvent event) {
        MicrovoxelKey key = key(event.getBlockPlaced());
        if (store.get(key) != null) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onBlockDamage(org.bukkit.event.block.BlockDamageEvent event) {
        Block block = event.getBlock();
        MicrovoxelKey key = key(block);
        MicrovoxelVolume volume = store.get(key);
        if (volume == null) return;

        event.setInstaBreak(false);

        // Migrate old BARRIER markers → STRUCTURE_VOID
        if (block.getType() == Material.BARRIER) {
            block.setType(Material.STRUCTURE_VOID, false);
        }

        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();

        if (player.getGameMode() == org.bukkit.GameMode.SURVIVAL) {
            long now = System.currentTimeMillis();
            miningStartTimes.put(playerId, now);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() == Action.LEFT_CLICK_BLOCK) {
            Block block = event.getClickedBlock();
            if (block == null) return;
            MicrovoxelKey key = key(block);
            if (store.get(key) != null) {
                Player player = event.getPlayer();
                if (player.getGameMode() == org.bukkit.GameMode.SURVIVAL) {
                    miningStartTimes.put(player.getUniqueId(), System.currentTimeMillis());
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        MicrovoxelKey key = key(block);
        MicrovoxelVolume volume = store.get(key);
        if (volume == null) return;

        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();

        if (player.getGameMode() == org.bukkit.GameMode.SURVIVAL) {
            Long startTime = miningStartTimes.get(playerId);
            Material baseMaterial = Material.STONE;
            String matStr = volume.palette().size() > 1 ? volume.palette().get(1) : null;
            if (matStr != null) {
                try { baseMaterial = Bukkit.createBlockData(matStr).getMaterial(); }
                catch (Exception ignored) {}
            }
            double requiredTime = getRequiredBreakTimeMs(baseMaterial, player.getInventory().getItemInMainHand());
            long now = System.currentTimeMillis();
            long elapsed = startTime == null ? -1 : (now - startTime);

            if (startTime == null || elapsed < requiredTime - 150) {
                // Not enough time has elapsed; cancel the break.
                event.setCancelled(true);
                return;
            }
        }

        // Break successful: clean up tracking and perform microvoxel drop
        miningStartTimes.remove(playerId);
        event.setDropItems(false);

        store.remove(key);
        broadcastRemove(key);
        markDirty();

        // Determine drop material from palette (index 1 = parent material)
        Material dropMaterial = Material.STONE;
        String matStr = volume.palette().size() > 1 ? volume.palette().get(1) : null;
        if (matStr != null) {
            try { dropMaterial = Bukkit.createBlockData(matStr).getMaterial(); }
            catch (Exception ignored) {}
        }

        ItemStack dropItem = new ItemStack(dropMaterial);
        org.bukkit.inventory.meta.ItemMeta meta = dropItem.getItemMeta();
        if (meta != null) {
            List<Component> lore = new ArrayList<>();
            boolean isWood = dropMaterial.name().contains("LOG") || dropMaterial.name().contains("PLANKS") || dropMaterial.name().contains("WOOD");
            boolean isStone = dropMaterial.name().contains("STONE") || dropMaterial.name().contains("DEEPSLATE") || dropMaterial.name().contains("TUFF") || dropMaterial.name().contains("BRICK");

            if (isWood) {
                lore.add(Component.text("«Тонкая столярная работа»")
                        .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, true)
                        .color(net.kyori.adventure.text.format.NamedTextColor.GRAY));
            } else if (isStone) {
                lore.add(Component.text("«Искусная каменная кладка»")
                        .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, true)
                        .color(net.kyori.adventure.text.format.NamedTextColor.GRAY));
            } else {
                lore.add(Component.text("«Мастерски вырезанное изделие»")
                        .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, true)
                        .color(net.kyori.adventure.text.format.NamedTextColor.GRAY));
            }

            int count = MicrovoxelVolume.CELL_COUNT - volume.occupiedCount();
            int starsCount = 1;
            if (count <= 100) starsCount = 1;
            else if (count <= 500) starsCount = 2;
            else if (count <= 1200) starsCount = 3;
            else if (count <= 2500) starsCount = 4;
            else starsCount = 5;

            lore.add(Component.text("Сложность работы: ")
                    .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false)
                    .color(net.kyori.adventure.text.format.NamedTextColor.GRAY)
                    .append(Component.text("★".repeat(starsCount)).color(net.kyori.adventure.text.format.NamedTextColor.GOLD))
                    .append(Component.text("★".repeat(5 - starsCount)).color(net.kyori.adventure.text.format.NamedTextColor.DARK_GRAY)));

            meta.lore(lore);

            org.bukkit.persistence.PersistentDataContainer pdc = meta.getPersistentDataContainer();
            org.bukkit.NamespacedKey nbtKey = new org.bukkit.NamespacedKey(plugin, "microvoxel_volume");
            org.bukkit.NamespacedKey parentKey = new org.bukkit.NamespacedKey(plugin, "parent_material");
            try {
                pdc.set(nbtKey, org.bukkit.persistence.PersistentDataType.BYTE_ARRAY, serializeVolume(volume));
                if (matStr != null) {
                    pdc.set(parentKey, org.bukkit.persistence.PersistentDataType.STRING, matStr);
                }
            } catch (IOException e) {
                plugin.getLogger().severe("Failed to serialize volume on break: " + e.getMessage());
            }
            dropItem.setItemMeta(meta);
        }

        Location dropLoc = block.getLocation().add(0.5, 0.5, 0.5);
        block.getWorld().dropItemNaturally(dropLoc, dropItem);
    }


    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        ItemStack item = event.getItemInHand();
        if (item == null || !item.hasItemMeta()) return;
        
        org.bukkit.persistence.PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        org.bukkit.NamespacedKey nbtKey = new org.bukkit.NamespacedKey(plugin, "microvoxel_volume");
        byte[] bytes = pdc.get(nbtKey, org.bukkit.persistence.PersistentDataType.BYTE_ARRAY);
        if (bytes == null) return;

        Block placed = event.getBlockPlaced();
        MicrovoxelKey key = key(placed);
        try {
            MicrovoxelVolume volume = deserializeVolume(bytes);
            // Reset revision to 1 on place
            MicrovoxelVolume copy = MicrovoxelVolume.restore(1, volume.palette(), volume.cellsCopy());
            store.put(key, copy);
            updateMarker(key, copy);
            broadcastUpsert(key, copy);
            markDirty();
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to deserialize volume on place: " + e.getMessage());
        }
    }

    private byte[] serializeVolume(MicrovoxelVolume volume) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (DataOutputStream dos = new DataOutputStream(bos)) {
            dos.writeInt(volume.revision());
            dos.writeByte(volume.palette().size());
            for (String material : volume.palette()) {
                byte[] utf8 = material.getBytes(java.nio.charset.StandardCharsets.UTF_8);
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
                palette.add(new String(utf8, java.nio.charset.StandardCharsets.UTF_8));
            }
            byte[] cells = dis.readNBytes(MicrovoxelVolume.CELL_COUNT);
            return MicrovoxelVolume.restore(revision, palette, cells);
        }
    }



    /**
     * An ordinary block shares a plane with an adjacent microvoxel volume.  The client correctly
     * omits that coplanar micro face while the ordinary block exists.  Once the ordinary block is
     * removed or placed again, send the neighbouring volume once more after Bukkit has committed
     * the world mutation, so the client rebuilds exactly the newly exposed/hidden sixteenth-face.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void refreshAdjacentMicrovoxelMeshes(BlockBreakEvent event) {
        scheduleBoundaryRefresh(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void refreshAdjacentMicrovoxelMeshes(BlockPlaceEvent event) {
        scheduleBoundaryRefresh(event.getBlockPlaced());
    }

    private void scheduleBoundaryRefresh(Block changedBlock) {
        World world = changedBlock.getWorld();
        int x = changedBlock.getX();
        int y = changedBlock.getY();
        int z = changedBlock.getZ();
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (int[] direction : BOUNDARY_DIRECTIONS) {
                MicrovoxelKey adjacent = new MicrovoxelKey(world.getUID(),
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

    private void trace(Player player, String message) {
        plugin.getLogger().info("[MICROVOXEL] player=" + player.getName() + " " + message);
    }

    private record QueuedAction(int type, MicrovoxelKey key, int cell, int expectedRevision, Vector clientLook,
                                Vector clientEye) {
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
        String material = (volume == null || volume.palette().size() <= 1) ? null : volume.palette().get(1);
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

    private double getRequiredBreakTimeMs(Material blockMat, org.bukkit.inventory.ItemStack tool) {
        boolean isWood = blockMat.name().contains("LOG") || blockMat.name().contains("PLANKS") || blockMat.name().contains("WOOD");
        boolean isStone = blockMat.name().contains("STONE") || blockMat.name().contains("DEEPSLATE") || blockMat.name().contains("TUFF") || blockMat.name().contains("BRICK");
        
        if (isWood) {
            boolean hasAxe = tool != null && tool.getType().name().contains("AXE");
            if (hasAxe) {
                return 200; // safe threshold for tools
            } else {
                return 2000; // 2s for hand
            }
        } else if (isStone) {
            boolean hasPickaxe = tool != null && tool.getType().name().contains("PICKAXE");
            if (hasPickaxe) {
                return 200; // safe threshold for tools
            } else {
                return 4000; // 4s for hand
            }
        }
        return 100; // default fallback
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

    public record HammerTarget(MicrovoxelKey key, int anchorCell, int expectedRevision,
                               HeavyHammerImpact.Face face) {
    }
}
