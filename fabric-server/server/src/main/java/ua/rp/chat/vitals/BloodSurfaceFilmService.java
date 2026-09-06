package ua.rp.chat.vitals;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import ua.rp.chat.RPChat;
import ua.rp.chat.blood.BloodVolumeRules;
import ua.rp.chat.client.blood.BloodSurfacePayload;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
import java.util.zip.CRC32;

/**
 * Server-authoritative, persisted blood film for floors, walls and ceilings.
 *
 * <p>Impact clients submit deterministic collision reports. The server validates the supporting
 * surface, de-duplicates reports from multiple observers, conserves/merges volume in a quantized
 * surface cell, advances wall flow, applies weather/fluid erosion and proximity-syncs records.</p>
 */
public final class BloodSurfaceFilmService {
    private static final int MAGIC = 0x42534632;
    private static final int VERSION = 2;
    private static final int MAX_RECORDS = 16_384;
    private static final int MAX_MAINTENANCE_PER_SECOND = 64;
    private static final double REPORT_DISTANCE_SQ = 96.0 * 96.0;
    private static final double TRACKING_DISTANCE_SQ = 96.0 * 96.0;
    private static final long MILLIS_PER_TICK = 50L;

    private final RPChat plugin;
    private final Path file;
    private final LinkedHashMap<Long, Film> films = new LinkedHashMap<>();
    private final Map<CellKey, Long> cells = new HashMap<>();
    private final Map<Long, Integer> seenSeeds = new HashMap<>();
    private final Map<UUID, RateState> rates = new HashMap<>();
    private final Map<UUID, Set<Long>> sent = new HashMap<>();
    private long nextId = 1L;
    private int ticks;
    private int cursor;
    private boolean dirty;

    public BloodSurfaceFilmService(RPChat plugin) {
        this.plugin = plugin;
        this.file = plugin.getDataFolder().toPath().resolve("blood-surface-film-v2.dat");
        load();
    }

    public void handle(ServerPlayer player, BloodSurfacePayload payload) {
        if (player == null || payload == null) return;
        if (payload.event() == BloodSurfacePayload.ABSORB) {
            absorb(player, payload);
            return;
        }
        if (payload.event() != BloodSurfacePayload.REQUEST
                || !finite(payload.x()) || !finite(payload.y()) || !finite(payload.z())
                || !Float.isFinite(payload.nx()) || !Float.isFinite(payload.ny())
                || !Float.isFinite(payload.nz()) || !Float.isFinite(payload.volumeMl())
                || !Float.isFinite(payload.energy())
                || payload.volumeMl() < 0.02f || payload.volumeMl() > 12.0f
                || payload.energy() < 0.0f || payload.energy() > 2.0f
                || payload.flowDepth() < 0 || payload.flowDepth() > 14) {
            return;
        }
        double distance = player.distanceToSqr(payload.x(), payload.y(), payload.z());
        if (distance > REPORT_DISTANCE_SQ) return;
        RateState rate = rates.computeIfAbsent(player.getUUID(), ignored -> new RateState());
        if (payload.sequence() <= rate.sequence) return;
        if (rate.tick != ticks) {
            rate.tick = ticks;
            rate.countThisTick = 0;
        }
        if (++rate.countThisTick > 16) return;
        rate.sequence = payload.sequence();

        Integer seenTick = seenSeeds.get(payload.seed());
        if (seenTick != null && ticks - seenTick < 1_200) return;
        seenSeeds.put(payload.seed(), ticks);

        Vec3 normal = axisNormal(payload.nx(), payload.ny(), payload.nz());
        Vec3 position = new Vec3(payload.x(), payload.y(), payload.z());
        ServerLevel level = (ServerLevel) player.level();
        Surface surface = validateSurface(level, position, normal);
        if (surface == null) return;
        int material = material(surface.state);
        int family = Math.max(0, Math.min(23, payload.family()));
        upsert(level, position, normal, payload.volumeMl(), payload.energy(), material, family,
                payload.seed(), payload.flowDepth(), true);
    }

    private void absorb(ServerPlayer player, BloodSurfacePayload payload) {
        Film film = films.get(payload.id());
        if (film == null || payload.volumeMl() <= 0.0f || payload.volumeMl() > 0.5f
                || !film.dimension.equals(player.level().dimension().toString())
                || player.distanceToSqr(film.x, film.y, film.z) > 2.5 * 2.5) return;
        RateState rate = rates.computeIfAbsent(player.getUUID(), ignored -> new RateState());
        if (ticks - rate.absorbTick < 2) return;
        rate.absorbTick = ticks;
        film.volumeMl = Math.max(0.02f, film.volumeMl - payload.volumeMl());
        film.revision++;
        dirty = true;
        broadcast((ServerLevel) player.level(), film, BloodSurfacePayload.UPDATE);
    }

    public void tick() {
        ticks++;
        if (ticks % 20 == 0) maintainSlice();
        if (ticks % 100 == 0) {
            prune();
            syncNearby();
            seenSeeds.entrySet().removeIf(entry -> ticks - entry.getValue() > 1_200);
        }
        if (dirty && ticks % 600 == 0) save();
    }

    public void onJoin(ServerPlayer player) {
        sent.put(player.getUUID(), new HashSet<>());
        syncTo(player);
    }

    public void onQuit(ServerPlayer player) {
        sent.remove(player.getUUID());
        rates.remove(player.getUUID());
    }

    public void shutdown() {
        save();
        sent.clear();
        rates.clear();
    }

    private Film upsert(ServerLevel level, Vec3 position, Vec3 normal, float volumeMl,
                        float energy, int material, int family, long seed, int flowDepth,
                        boolean refresh) {
        String dimension = level.dimension().toString();
        CellKey cell = cell(dimension, position, normal);
        Film film = cells.containsKey(cell) ? films.get(cells.get(cell)) : null;
        if (film != null) {
            film.volumeMl = Math.min(80.0f, film.volumeMl + volumeMl);
            film.energy = Math.max(film.energy * 0.82f, energy);
            film.lifetimeTicks = Math.max(film.lifetimeTicks,
                    lifetime(material, film.volumeMl, film.seed ^ seed));
            film.revision++;
            if (refresh) film.createdMillis = System.currentTimeMillis();
            dirty = true;
            broadcast(level, film, BloodSurfacePayload.UPDATE);
            return film;
        }
        film = new Film(nextId++, dimension, 1, position.x, position.y, position.z,
                (float) normal.x, (float) normal.y, (float) normal.z,
                Math.max(0.02f, volumeMl), Math.max(0.0f, energy), material, family, seed,
                flowDepth, System.currentTimeMillis(), lifetime(material, volumeMl, seed), 0);
        films.put(film.id, film);
        cells.put(cell, film.id);
        enforceBudget();
        dirty = true;
        broadcast(level, film, BloodSurfacePayload.STAMP);
        return film;
    }

    private void maintainSlice() {
        if (films.isEmpty() || plugin.getServer() == null) return;
        List<Film> snapshot = new ArrayList<>(films.values());
        int checks = Math.min(MAX_MAINTENANCE_PER_SECOND, snapshot.size());
        for (int i = 0; i < checks; i++) {
            Film film = snapshot.get(Math.floorMod(cursor++, snapshot.size()));
            ServerLevel level = level(film.dimension);
            if (level == null || level.getChunkSource().getChunkNow(
                    ((int) Math.floor(film.x)) >> 4, ((int) Math.floor(film.z)) >> 4) == null) continue;
            Vec3 position = film.position();
            Vec3 normal = film.normal();
            Surface surface = validateSurface(level, position, normal);
            if (surface == null) {
                remove(film.id, true);
                continue;
            }
            BlockPos sample = BlockPos.containing(position);
            if (!level.getFluidState(sample).isEmpty()) {
                film.weatherDamageTicks += 1_600;
                film.revision++;
                dirty = true;
                broadcast(level, film, BloodSurfacePayload.UPDATE);
            } else if (normal.y > 0.7f && level.isRainingAt(sample.above())) {
                film.weatherDamageTicks += 220;
                film.revision++;
                dirty = true;
                broadcast(level, film, BloodSurfacePayload.UPDATE);
            }
            if (Math.abs(film.ny) < 0.25f && film.flowDepth < 14
                    && film.ageTicks() < film.lifetimeTicks * 0.42
                    && film.volumeMl > 0.24f && (ticks + film.id) % 3 == 0) {
                flow(level, film);
            }
        }
    }

    private void flow(ServerLevel level, Film parent) {
        float transfer = BloodVolumeRules.wallFlowTransfer(parent.volumeMl);
        if (transfer < 0.08f || parent.volumeMl - transfer < 0.12f) return;
        long seed = mix64(parent.seed + parent.revision * 0x9e3779b97f4a7c15L);
        double lateral = (unit(seed) - 0.5) * 0.018;
        Vec3 normal = parent.normal();
        Vec3 tangent = Math.abs(normal.x) > 0.5
                ? new Vec3(0, 0, 1) : new Vec3(1, 0, 0);
        Vec3 next = parent.position().add(0, -(0.045 + unit(seed ^ 31L) * 0.035), 0)
                .add(tangent.scale(lateral));
        if (validateSurface(level, next, normal) == null) return;
        parent.volumeMl -= transfer;
        parent.revision++;
        upsert(level, next, normal, transfer, 0.18f, parent.material, parent.family,
                seed, parent.flowDepth + 1, false);
        broadcast(level, parent, BloodSurfacePayload.UPDATE);
        dirty = true;
    }

    private Surface validateSurface(ServerLevel level, Vec3 position, Vec3 normal) {
        BlockPos supportPos = BlockPos.containing(position.subtract(normal.scale(0.04)));
        BlockState state = level.getBlockState(supportPos);
        if (state.isAir() || !level.getFluidState(supportPos).isEmpty()) return null;
        BlockState exact = plugin.getMicrovoxelManager() == null ? null
                : plugin.getMicrovoxelManager().materialStateAtSurface(level, position, normal);
        if (plugin.getMicrovoxelManager() != null
                && plugin.getMicrovoxelManager().protectsMarker(level, supportPos)
                && exact == null) return null;
        return new Surface(supportPos, exact == null ? state : exact);
    }

    private void syncNearby() {
        for (ServerPlayer player : plugin.getServer().getPlayerList().getPlayers()) syncTo(player);
    }

    private void syncTo(ServerPlayer player) {
        String dimension = player.level().dimension().toString();
        Set<Long> known = sent.computeIfAbsent(player.getUUID(), ignored -> new HashSet<>());
        known.removeIf(id -> {
            Film film = films.get(id);
            return film == null || !dimension.equals(film.dimension)
                    || player.distanceToSqr(film.x, film.y, film.z) > TRACKING_DISTANCE_SQ * 1.8;
        });
        for (Film film : films.values()) {
            if (!known.contains(film.id) && dimension.equals(film.dimension)
                    && player.distanceToSqr(film.x, film.y, film.z) <= TRACKING_DISTANCE_SQ) {
                send(player, film, BloodSurfacePayload.STAMP);
            }
        }
    }

    private void broadcast(ServerLevel level, Film film, int event) {
        for (ServerPlayer player : plugin.getServer().getPlayerList().getPlayers()) {
            if (player.level() == level
                    && player.distanceToSqr(film.x, film.y, film.z) <= TRACKING_DISTANCE_SQ) {
                send(player, film, event);
            }
        }
    }

    private void send(ServerPlayer player, Film film, int event) {
        if (!ServerPlayNetworking.canSend(player, BloodSurfacePayload.TYPE)) return;
        ServerPlayNetworking.send(player, new BloodSurfacePayload(event, film.id, film.revision,
                film.x, film.y, film.z, film.nx, film.ny, film.nz, film.volumeMl, film.energy,
                film.material, film.family, film.seed, film.flowDepth,
                film.ageTicks(), film.lifetimeTicks));
        sent.computeIfAbsent(player.getUUID(), ignored -> new HashSet<>()).add(film.id);
    }

    private void remove(long id, boolean notify) {
        Film film = films.remove(id);
        if (film == null) return;
        cells.remove(cell(film.dimension, film.position(), film.normal()), id);
        for (Set<Long> ids : sent.values()) ids.remove(id);
        if (notify && plugin.getServer() != null) {
            for (ServerPlayer player : plugin.getServer().getPlayerList().getPlayers()) {
                if (film.dimension.equals(player.level().dimension().toString())
                        && ServerPlayNetworking.canSend(player, BloodSurfacePayload.TYPE)) {
                    ServerPlayNetworking.send(player, new BloodSurfacePayload(
                            BloodSurfacePayload.REMOVE, id, film.revision,
                            0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0L, 0, 0, 0));
                }
            }
        }
        dirty = true;
    }

    private void prune() {
        List<Long> expired = films.values().stream()
                .filter(film -> film.ageTicks() >= film.lifetimeTicks)
                .map(film -> film.id).toList();
        for (long id : expired) remove(id, true);
    }

    private void enforceBudget() {
        while (films.size() > MAX_RECORDS) {
            Film oldest = films.values().stream().min(
                    Comparator.comparingLong(film -> film.createdMillis)).orElse(null);
            if (oldest == null) return;
            remove(oldest.id, true);
        }
    }

    private void load() {
        if (!Files.isRegularFile(file) && !Files.isRegularFile(backup())) return;
        try {
            byte[] body;
            try {
                body = readEnvelope(file);
            } catch (IOException primary) {
                if (!Files.isRegularFile(backup())) throw primary;
                body = readEnvelope(backup());
                plugin.getLogger().warning("Recovered blood surface film from backup.");
            }
            try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(body))) {
                nextId = Math.max(1, input.readLong());
                int count = input.readInt();
                if (count < 0 || count > MAX_RECORDS) throw new IOException("Invalid surface-film count");
                for (int i = 0; i < count; i++) {
                    Film film = Film.read(input);
                    if (film.ageTicks() >= film.lifetimeTicks) continue;
                    films.put(film.id, film);
                    cells.put(cell(film.dimension, film.position(), film.normal()), film.id);
                    nextId = Math.max(nextId, film.id + 1);
                }
                if (input.read() != -1) throw new IOException("Trailing surface-film bytes");
            }
            plugin.getLogger().info("Loaded " + films.size() + " persistent blood surface films.");
        } catch (IOException error) {
            plugin.getLogger().warning("Unable to load blood surface film: " + error.getMessage());
        }
    }

    private void save() {
        if (!dirty && Files.isRegularFile(file)) return;
        try {
            Files.createDirectories(file.getParent());
            ByteArrayOutputStream bodyBytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bodyBytes)) {
                output.writeLong(nextId);
                output.writeInt(films.size());
                for (Film film : films.values()) film.write(output);
            }
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            writeEnvelope(temporary, bodyBytes.toByteArray());
            if (Files.isRegularFile(file)) {
                Files.copy(file, backup(), StandardCopyOption.REPLACE_EXISTING);
            }
            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
            dirty = false;
        } catch (IOException error) {
            plugin.getLogger().warning("Unable to save blood surface film: " + error.getMessage());
        }
    }

    private static void writeEnvelope(Path target, byte[] body) throws IOException {
        CRC32 crc = new CRC32();
        crc.update(body);
        try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(
                Files.newOutputStream(target)))) {
            output.writeInt(MAGIC);
            output.writeInt(VERSION);
            output.writeInt(body.length);
            output.write(body);
            output.writeInt((int) crc.getValue());
        }
    }

    private static byte[] readEnvelope(Path source) throws IOException {
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(
                Files.newInputStream(source)))) {
            if (input.readInt() != MAGIC || input.readInt() != VERSION) {
                throw new IOException("Unsupported blood surface-film format");
            }
            int length = input.readInt();
            if (length < 12 || length > 64 * 1024 * 1024) throw new IOException("Invalid film length");
            byte[] body = input.readNBytes(length);
            if (body.length != length) throw new EOFException("Truncated film store");
            int expected = input.readInt();
            CRC32 crc = new CRC32();
            crc.update(body);
            if ((int) crc.getValue() != expected || input.read() != -1) {
                throw new IOException("Surface-film CRC mismatch");
            }
            return body;
        }
    }

    private Path backup() {
        return file.resolveSibling(file.getFileName() + ".bak");
    }

    private ServerLevel level(String dimension) {
        for (ServerLevel level : plugin.getServer().getAllLevels()) {
            if (dimension.equals(level.dimension().toString())) return level;
        }
        return null;
    }

    private static CellKey cell(String dimension, Vec3 position, Vec3 normal) {
        long x = ((long) Math.floor(position.x * 16.0)) & 0x1fffffL;
        long y = ((long) Math.floor(position.y * 16.0)) & 0x3ffffL;
        long z = ((long) Math.floor(position.z * 16.0)) & 0x1fffffL;
        int face = normal.x > .5 ? 0 : normal.x < -.5 ? 1 : normal.y > .5 ? 2
                : normal.y < -.5 ? 3 : normal.z > .5 ? 4 : 5;
        return new CellKey(dimension, x | (z << 21) | (y << 42), face);
    }

    private static Vec3 axisNormal(float x, float y, float z) {
        float ax = Math.abs(x), ay = Math.abs(y), az = Math.abs(z);
        if (ay >= ax && ay >= az) return new Vec3(0, y < 0 ? -1 : 1, 0);
        if (ax >= az) return new Vec3(x < 0 ? -1 : 1, 0, 0);
        return new Vec3(0, 0, z < 0 ? -1 : 1);
    }

    private static int material(BlockState state) {
        if (state.is(BlockTags.DIRT) || state.is(BlockTags.MUD) || state.is(BlockTags.GRASS_BLOCKS)) return 1;
        if (state.is(BlockTags.SAND)) return 2;
        if (state.is(BlockTags.PLANKS) || state.is(BlockTags.LOGS)) return 3;
        if (state.is(BlockTags.SNOW)) return 4;
        if (state.is(BlockTags.WOOL) || state.is(BlockTags.WOOL_CARPETS)) return 5;
        return 0;
    }

    private static int lifetime(int material, float volume, long seed) {
        int base = switch (material) {
            case 1 -> 30_000; case 2 -> 38_000; case 4 -> 22_000;
            case 5 -> 50_000; default -> 56_000;
        };
        return Math.max(12_000, base + Math.round(Math.min(80, volume) * 180)
                + Math.floorMod((int) mix64(seed), 5_001) - 2_500);
    }

    private static long mix64(long value) {
        value ^= value >>> 30; value *= 0xbf58476d1ce4e5b9L;
        value ^= value >>> 27; value *= 0x94d049bb133111ebL;
        return value ^ value >>> 31;
    }

    private static double unit(long seed) {
        return ((mix64(seed) >>> 40) & 0xffffffL) / 16777216.0;
    }

    private static boolean finite(double value) { return Double.isFinite(value); }

    private record CellKey(String dimension, long cell, int face) {}
    private record Surface(BlockPos pos, BlockState state) {}

    private static final class RateState {
        private int sequence;
        private int tick = Integer.MIN_VALUE / 2;
        private int countThisTick;
        private int absorbTick = Integer.MIN_VALUE / 2;
    }

    private static final class Film {
        private final long id;
        private final String dimension;
        private int revision;
        private final double x, y, z;
        private final float nx, ny, nz;
        private float volumeMl;
        private float energy;
        private final int material, family;
        private final long seed;
        private final int flowDepth;
        private long createdMillis;
        private int lifetimeTicks;
        private int weatherDamageTicks;

        private Film(long id, String dimension, int revision, double x, double y, double z,
                     float nx, float ny, float nz, float volumeMl, float energy,
                     int material, int family, long seed, int flowDepth, long createdMillis,
                     int lifetimeTicks, int weatherDamageTicks) {
            this.id = id; this.dimension = dimension; this.revision = revision;
            this.x = x; this.y = y; this.z = z;
            this.nx = nx; this.ny = ny; this.nz = nz;
            this.volumeMl = volumeMl; this.energy = energy;
            this.material = material; this.family = family; this.seed = seed;
            this.flowDepth = flowDepth; this.createdMillis = createdMillis;
            this.lifetimeTicks = lifetimeTicks; this.weatherDamageTicks = weatherDamageTicks;
        }

        private Vec3 position() { return new Vec3(x, y, z); }
        private Vec3 normal() { return new Vec3(nx, ny, nz); }
        private int ageTicks() {
            long elapsed = Math.max(0, System.currentTimeMillis() - createdMillis) / MILLIS_PER_TICK;
            return (int) Math.min(Integer.MAX_VALUE, elapsed + weatherDamageTicks);
        }

        private void write(DataOutputStream out) throws IOException {
            out.writeLong(id); out.writeUTF(dimension); out.writeInt(revision);
            out.writeDouble(x); out.writeDouble(y); out.writeDouble(z);
            out.writeFloat(nx); out.writeFloat(ny); out.writeFloat(nz);
            out.writeFloat(volumeMl); out.writeFloat(energy);
            out.writeByte(material); out.writeByte(family); out.writeLong(seed);
            out.writeByte(flowDepth); out.writeLong(createdMillis);
            out.writeInt(lifetimeTicks); out.writeInt(weatherDamageTicks);
        }

        private static Film read(DataInputStream in) throws IOException {
            return new Film(in.readLong(), in.readUTF(), in.readInt(),
                    in.readDouble(), in.readDouble(), in.readDouble(),
                    in.readFloat(), in.readFloat(), in.readFloat(),
                    in.readFloat(), in.readFloat(), in.readUnsignedByte(), in.readUnsignedByte(),
                    in.readLong(), in.readUnsignedByte(), in.readLong(), in.readInt(), in.readInt());
        }
    }
}
