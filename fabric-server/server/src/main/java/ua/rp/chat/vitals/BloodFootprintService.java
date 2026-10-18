package ua.rp.chat.vitals;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.state.BlockState;
import ua.rp.chat.RPChat;
import ua.rp.chat.client.blood.BloodFootprintPayload;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Validates, merges, persists and proximity-syncs long-lived blood footprints.
 * Storage is compact and bounded; world/chunk access is restricted to already
 * loaded chunks during weather maintenance.
 */
public final class BloodFootprintService {
    private static final int FILE_VERSION = 2;
    private static final int MAX_RECORDS = 4_096;
    private static final double TRACKING_DISTANCE_SQ = 72.0 * 72.0;
    private static final double REQUEST_DISTANCE_SQ = 2.25 * 2.25;
    private static final long MILLIS_PER_TICK = 50L;
    private static final UUID NIL_UUID = new UUID(0L, 0L);

    private final RPChat plugin;
    private final File storageFile;
    private final LinkedHashMap<Long, Record> records = new LinkedHashMap<>();
    private final Map<CellKey, Long> cells = new HashMap<>();
    private final Map<UUID, RateState> rates = new HashMap<>();
    private final Map<UUID, Set<Long>> sent = new HashMap<>();
    private long nextId = 1L;
    private int ticks;
    private int weatherCursor;
    private boolean dirty;

    public BloodFootprintService(RPChat plugin) {
        this.plugin = plugin;
        this.storageFile = new File(plugin.getDataFolder(), "blood-footprints.dat");
        load();
    }

    public void handleRequest(ServerPlayer player, BloodFootprintPayload request) {
        if (player == null || request == null) return;
        if (request.event() == BloodFootprintPayload.ABSORB) {
            handleAbsorb(player, request);
            return;
        }
        if (request.event() != BloodFootprintPayload.REQUEST) return;
        if (!finite(request.x()) || !finite(request.y()) || !finite(request.z())
                || !Float.isFinite(request.yaw()) || !Float.isFinite(request.wetness())
                || request.wetness() < 0.07f || request.wetness() > 1.01f
                || request.foot() < 0 || request.foot() > 1
                || request.gait() < 0 || request.gait() > 4
                || request.footwear() < 0 || request.footwear() > 3) {
            return;
        }
        double dx = request.x() - player.getX();
        double dy = request.y() - player.getY();
        double dz = request.z() - player.getZ();
        if (dx * dx + dy * dy + dz * dz > REQUEST_DISTANCE_SQ || !player.onGround()) return;

        RateState rate = rates.computeIfAbsent(player.getUUID(), ignored -> new RateState());
        if (request.sequence() <= rate.sequence || ticks - rate.tick < 2) return;
        rate.sequence = request.sequence();
        rate.tick = ticks;

        ServerLevel level = (ServerLevel) player.level();
        BlockPos surfacePos = BlockPos.containing(request.x(), request.y() - 0.04, request.z());
        BlockState state = level.getBlockState(surfacePos);
        if (state.isAir() || !level.getFluidState(surfacePos).isEmpty()) return;
        BlockState exactMaterial = plugin.getMicrovoxelManager() == null ? null
                : plugin.getMicrovoxelManager().materialStateAtSurface(level,
                new net.minecraft.world.phys.Vec3(request.x(), request.y(), request.z()),
                new net.minecraft.world.phys.Vec3(0.0, 1.0, 0.0));
        if (plugin.getMicrovoxelManager() != null
                && plugin.getMicrovoxelManager().protectsMarker(level, surfacePos)
                && exactMaterial == null) {
            return;
        }
        if (exactMaterial != null) state = exactMaterial;

        int material = material(state);
        float wetness = clamp01(request.wetness());
        int lifetime = lifetimeTicks(material, wetness, request.seed());
        String dimension = level.dimension().toString();
        CellKey cell = new CellKey(dimension, mergeCell(request.x(), request.y(), request.z()),
                request.foot());
        Record record = cells.containsKey(cell) ? records.get(cells.get(cell)) : null;
        if (record != null) {
            record.wetness = Math.min(1.0f, Math.max(record.wetness, wetness) + wetness * 0.18f);
            record.yaw = blendAngle(record.yaw, request.yaw(), 0.35f);
            record.createdMillis = System.currentTimeMillis();
            record.lifetimeTicks = Math.max(record.lifetimeTicks, lifetime);
            record.weatherDamageTicks = 0;
            record.seed ^= request.seed();
            record.sequence = request.sequence();
            record.entityId = player.getId();
            record.playerUuid = player.getUUID();
            record.gait = request.gait();
            record.footwear = request.footwear();
            broadcast(level, record, true);
        } else {
            long id = nextId++;
            record = new Record(id, dimension, player.getId(), player.getUUID(), request.sequence(),
                    request.x(), request.y() + 0.004, request.z(), request.yaw(), wetness,
                    request.foot(), request.gait(), material, request.footwear(), request.seed(),
                    System.currentTimeMillis(), lifetime, 0);
            records.put(id, record);
            cells.put(cell, id);
            enforceBudget();
            broadcast(level, record, true);
        }
        dirty = true;
    }

    private void handleAbsorb(ServerPlayer player, BloodFootprintPayload request) {
        Record record = records.get(request.decalId());
        if (record == null || request.wetness() <= 0.0f || request.wetness() > 0.36f
                || !record.dimension.equals(player.level().dimension().toString())
                || distanceSq(player, record) > REQUEST_DISTANCE_SQ) {
            return;
        }
        RateState rate = rates.computeIfAbsent(player.getUUID(), ignored -> new RateState());
        if (ticks - rate.absorbTick < 2) return;
        rate.absorbTick = ticks;
        record.wetness = Math.max(0.0f, record.wetness - request.wetness() * 0.48f);
        dirty = true;
        broadcastUpdate((ServerLevel) player.level(), record);
    }

    public void tick() {
        ticks++;
        if (ticks % 20 == 0) maintainWeatherSlice();
        if (ticks % 100 == 0) {
            pruneExpired();
            syncNearby();
        }
        if (dirty && ticks % 600 == 0) save();
    }

    public void onJoin(ServerPlayer player) {
        if (player == null) return;
        sent.put(player.getUUID(), new HashSet<>());
        syncTo(player);
    }

    public void onQuit(ServerPlayer player) {
        if (player == null) return;
        sent.remove(player.getUUID());
        rates.remove(player.getUUID());
    }

    public void shutdown() {
        save();
        sent.clear();
        rates.clear();
    }

    private void syncNearby() {
        if (plugin.getServer() == null || records.isEmpty()) return;
        for (ServerPlayer player : plugin.getServer().getPlayerList().getPlayers()) syncTo(player);
    }

    private void syncTo(ServerPlayer player) {
        if (player == null) return;
        String dimension = player.level().dimension().toString();
        Set<Long> known = sent.computeIfAbsent(player.getUUID(), ignored -> new HashSet<>());
        known.removeIf(id -> {
            Record record = records.get(id);
            return record == null || !dimension.equals(record.dimension)
                    || distanceSq(player, record) > TRACKING_DISTANCE_SQ * 1.8;
        });
        for (Record record : records.values()) {
            if (known.contains(record.id) || !dimension.equals(record.dimension)) continue;
            if (distanceSq(player, record) <= TRACKING_DISTANCE_SQ) send(player, record);
        }
        if (known.size() > MAX_RECORDS) known.retainAll(records.keySet());
    }

    private void broadcast(ServerLevel level, Record record, boolean forceRefresh) {
        if (plugin.getServer() == null) return;
        for (ServerPlayer observer : plugin.getServer().getPlayerList().getPlayers()) {
            if (observer.level() != level || distanceSq(observer, record) > TRACKING_DISTANCE_SQ) continue;
            if (forceRefresh) sent.computeIfAbsent(observer.getUUID(), ignored -> new HashSet<>()).remove(record.id);
            send(observer, record);
        }
    }

    private void broadcastUpdate(ServerLevel level, Record record) {
        if (plugin.getServer() == null) return;
        int age = record.visualAgeTicks();
        for (ServerPlayer observer : plugin.getServer().getPlayerList().getPlayers()) {
            if (observer.level() != level || distanceSq(observer, record) > TRACKING_DISTANCE_SQ
                    || !ServerPlayNetworking.canSend(observer, BloodFootprintPayload.TYPE)) continue;
            ServerPlayNetworking.send(observer, new BloodFootprintPayload(
                    BloodFootprintPayload.UPDATE, record.id, record.entityId, record.playerUuid,
                    record.sequence, record.x, record.y, record.z, record.yaw, record.wetness,
                    record.foot, record.gait, record.material, record.footwear,
                    record.seed, age, record.lifetimeTicks));
            sent.computeIfAbsent(observer.getUUID(), ignored -> new HashSet<>()).add(record.id);
        }
    }

    private void send(ServerPlayer player, Record record) {
        if (!ServerPlayNetworking.canSend(player, BloodFootprintPayload.TYPE)) return;
        int age = record.visualAgeTicks();
        if (age >= record.lifetimeTicks) return;
        ServerPlayNetworking.send(player, new BloodFootprintPayload(
                BloodFootprintPayload.STAMP, record.id, record.entityId, record.playerUuid,
                record.sequence, record.x, record.y, record.z, record.yaw, record.wetness,
                record.foot, record.gait, record.material, record.footwear,
                record.seed, age, record.lifetimeTicks));
        sent.computeIfAbsent(player.getUUID(), ignored -> new HashSet<>()).add(record.id);
    }

    private void removeRecord(long id, boolean notify) {
        Record removed = records.remove(id);
        if (removed == null) return;
        cells.remove(new CellKey(removed.dimension, mergeCell(removed.x, removed.y, removed.z), removed.foot), id);
        for (Set<Long> ids : sent.values()) ids.remove(id);
        if (notify && plugin.getServer() != null) {
            for (ServerPlayer player : plugin.getServer().getPlayerList().getPlayers()) {
                if (!removed.dimension.equals(player.level().dimension().toString())
                        || distanceSq(player, removed) > TRACKING_DISTANCE_SQ
                        || !ServerPlayNetworking.canSend(player, BloodFootprintPayload.TYPE)) continue;
                ServerPlayNetworking.send(player, new BloodFootprintPayload(
                        BloodFootprintPayload.REMOVE, id, 0, NIL_UUID, 0,
                        0.0, 0.0, 0.0, 0.0f, 0.0f, 0, 0, 0, 0, 0L, 0, 0));
            }
        }
        dirty = true;
    }

    private void pruneExpired() {
        List<Long> expired = new ArrayList<>();
        for (Record record : records.values()) {
            if (record.visualAgeTicks() >= record.lifetimeTicks) expired.add(record.id);
        }
        for (long id : expired) removeRecord(id, true);
    }

    private void maintainWeatherSlice() {
        if (records.isEmpty() || plugin.getServer() == null) return;
        List<Record> snapshot = new ArrayList<>(records.values());
        int checks = Math.min(48, snapshot.size());
        for (int i = 0; i < checks; i++) {
            Record record = snapshot.get(Math.floorMod(weatherCursor++, snapshot.size()));
            ServerLevel level = level(record.dimension);
            if (level == null) continue;
            int chunkX = ((int) Math.floor(record.x)) >> 4;
            int chunkZ = ((int) Math.floor(record.z)) >> 4;
            if (level.getChunkSource().getChunkNow(chunkX, chunkZ) == null) continue;
            BlockPos pos = BlockPos.containing(record.x, record.y, record.z);
            if (!level.getFluidState(pos).isEmpty()) {
                record.weatherDamageTicks += 1_200;
                dirty = true;
            } else if (level.isRainingAt(pos.above())) {
                record.weatherDamageTicks += 180;
                dirty = true;
            }
        }
    }

    private ServerLevel level(String dimension) {
        for (ServerLevel level : plugin.getServer().getAllLevels()) {
            if (dimension.equals(level.dimension().toString())) return level;
        }
        return null;
    }

    private void enforceBudget() {
        while (records.size() > MAX_RECORDS) {
            Record oldest = records.values().stream()
                    .min(Comparator.comparingLong(record -> record.createdMillis))
                    .orElse(null);
            if (oldest == null) break;
            removeRecord(oldest.id, true);
        }
    }

    private void load() {
        if (!storageFile.isFile()) return;
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(
                new java.io.FileInputStream(storageFile)))) {
            if (input.readInt() != FILE_VERSION) return;
            nextId = Math.max(1L, input.readLong());
            int count = Math.max(0, Math.min(MAX_RECORDS, input.readInt()));
            for (int i = 0; i < count; i++) {
                Record record = Record.read(input);
                if (record.visualAgeTicks() >= record.lifetimeTicks) continue;
                records.put(record.id, record);
                cells.put(new CellKey(record.dimension, mergeCell(record.x, record.y, record.z), record.foot),
                        record.id);
                nextId = Math.max(nextId, record.id + 1L);
            }
            plugin.getLogger().info("Loaded " + records.size() + " persistent blood footprints.");
        } catch (EOFException exception) {
            plugin.getLogger().warning("Truncated blood-footprint store ignored: " + exception.getMessage());
        } catch (IOException exception) {
            plugin.getLogger().warning("Unable to load blood footprints: " + exception.getMessage());
        }
    }

    private void save() {
        if (!dirty && storageFile.isFile()) return;
        storageFile.getParentFile().mkdirs();
        File temporary = new File(storageFile.getParentFile(), storageFile.getName() + ".tmp");
        try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(
                new java.io.FileOutputStream(temporary)))) {
            output.writeInt(FILE_VERSION);
            output.writeLong(nextId);
            output.writeInt(records.size());
            for (Record record : records.values()) record.write(output);
        } catch (IOException exception) {
            plugin.getLogger().warning("Unable to save blood footprints: " + exception.getMessage());
            return;
        }
        try {
            java.nio.file.Files.move(temporary.toPath(), storageFile.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            dirty = false;
        } catch (IOException exception) {
            plugin.getLogger().warning("Unable to install blood-footprint store: " + exception.getMessage());
        }
    }

    private static int material(BlockState state) {
        if (state.is(BlockTags.DIRT) || state.is(BlockTags.MUD) || state.is(BlockTags.GRASS_BLOCKS)) return 1;
        if (state.is(BlockTags.SAND)) return 2;
        if (state.is(BlockTags.PLANKS) || state.is(BlockTags.LOGS)) return 3;
        if (state.is(BlockTags.SNOW)) return 4;
        if (state.is(BlockTags.WOOL) || state.is(BlockTags.WOOL_CARPETS)) return 5;
        return 0;
    }

    private static int lifetimeTicks(int material, float wetness, long seed) {
        int base = switch (material) {
            case 1 -> 30_000;
            case 2 -> 36_000;
            case 4 -> 22_000;
            case 5 -> 48_000;
            default -> 54_000;
        };
        int jitter = Math.floorMod((int) mix64(seed), 6_001) - 3_000;
        return Math.max(12_000, base + Math.round(wetness * 12_000) + jitter);
    }

    private static long mergeCell(double x, double y, double z) {
        long ix = ((long) Math.floor(x * 8.0)) & 0x1fffffL;
        long iy = ((long) Math.floor(y * 8.0)) & 0x3ffffL;
        long iz = ((long) Math.floor(z * 8.0)) & 0x1fffffL;
        return ix | (iz << 21) | (iy << 42);
    }

    private static long mix64(long value) {
        value ^= value >>> 30;
        value *= 0xbf58476d1ce4e5b9L;
        value ^= value >>> 27;
        value *= 0x94d049bb133111ebL;
        return value ^ value >>> 31;
    }

    private static float blendAngle(float from, float to, float amount) {
        float difference = (float) Math.atan2(Math.sin(to - from), Math.cos(to - from));
        return from + difference * amount;
    }

    private static double distanceSq(ServerPlayer player, Record record) {
        double dx = player.getX() - record.x;
        double dy = player.getY() - record.y;
        double dz = player.getZ() - record.z;
        return dx * dx + dy * dy + dz * dz;
    }

    private static boolean finite(double value) {
        return Double.isFinite(value);
    }

    private static float clamp01(float value) {
        return Float.isFinite(value) ? Math.max(0.0f, Math.min(1.0f, value)) : 0.0f;
    }

    private record CellKey(String dimension, long cell, int foot) {
    }

    private static final class RateState {
        private int sequence;
        private int tick = Integer.MIN_VALUE / 2;
        private int absorbTick = Integer.MIN_VALUE / 2;
    }

    private static final class Record {
        private final long id;
        private final String dimension;
        private int entityId;
        private UUID playerUuid;
        private int sequence;
        private final double x;
        private final double y;
        private final double z;
        private float yaw;
        private float wetness;
        private final int foot;
        private int gait;
        private final int material;
        private int footwear;
        private long seed;
        private long createdMillis;
        private int lifetimeTicks;
        private int weatherDamageTicks;

        private Record(long id, String dimension, int entityId, UUID playerUuid, int sequence,
                       double x, double y, double z, float yaw, float wetness, int foot, int gait,
                       int material, int footwear, long seed, long createdMillis, int lifetimeTicks,
                       int weatherDamageTicks) {
            this.id = id;
            this.dimension = dimension;
            this.entityId = entityId;
            this.playerUuid = playerUuid;
            this.sequence = sequence;
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            this.wetness = wetness;
            this.foot = foot;
            this.gait = gait;
            this.material = material;
            this.footwear = footwear;
            this.seed = seed;
            this.createdMillis = createdMillis;
            this.lifetimeTicks = lifetimeTicks;
            this.weatherDamageTicks = weatherDamageTicks;
        }

        private int visualAgeTicks() {
            long elapsed = Math.max(0L, System.currentTimeMillis() - createdMillis) / MILLIS_PER_TICK;
            return (int) Math.min(Integer.MAX_VALUE, elapsed + Math.max(0, weatherDamageTicks));
        }

        private void write(DataOutputStream output) throws IOException {
            output.writeLong(id);
            output.writeUTF(dimension);
            output.writeInt(entityId);
            output.writeLong(playerUuid.getMostSignificantBits());
            output.writeLong(playerUuid.getLeastSignificantBits());
            output.writeInt(sequence);
            output.writeDouble(x);
            output.writeDouble(y);
            output.writeDouble(z);
            output.writeFloat(yaw);
            output.writeFloat(wetness);
            output.writeByte(foot);
            output.writeByte(gait);
            output.writeByte(material);
            output.writeByte(footwear);
            output.writeLong(seed);
            output.writeLong(createdMillis);
            output.writeInt(lifetimeTicks);
            output.writeInt(weatherDamageTicks);
        }

        private static Record read(DataInputStream input) throws IOException {
            long id = input.readLong();
            String dimension = input.readUTF();
            int entityId = input.readInt();
            UUID playerUuid = new UUID(input.readLong(), input.readLong());
            int sequence = input.readInt();
            double x = input.readDouble();
            double y = input.readDouble();
            double z = input.readDouble();
            float yaw = input.readFloat();
            float wetness = input.readFloat();
            int foot = input.readUnsignedByte();
            int gait = input.readUnsignedByte();
            int material = input.readUnsignedByte();
            int footwear = input.readUnsignedByte();
            long seed = input.readLong();
            long createdMillis = input.readLong();
            int lifetimeTicks = input.readInt();
            int weatherDamageTicks = input.readInt();
            return new Record(id, dimension, entityId, playerUuid, sequence, x, y, z, yaw,
                    wetness, foot, gait, material, footwear, seed, createdMillis, lifetimeTicks,
                    weatherDamageTicks);
        }
    }
}
