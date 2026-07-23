package ua.rp.chat.client.blood;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ParticleStatus;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import ua.rp.chat.blood.BloodFxRules;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Client-side deterministic blood simulation with bounded particles, distance
 * LOD, surface-aware decals and wound marks attached to body regions.
 */
public final class BloodFxClientState {
    private static final Map<WoundKey, WoundEmitter> WOUNDS = new HashMap<>();
    private static final Map<Long, BloodDecalParticle> SURFACE_DECALS = new HashMap<>();
    private static ClientLevel activeLevel;
    private static int clientTicks;
    private static int activeDrops;
    private static int activeDecals;

    private BloodFxClientState() {
    }

    public static void register() {
        BloodParticleSprites.register();
    }

    public static void handle(BloodFxPayload payload) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || payload == null || payload.entityUuid() == null) return;
        if (payload.event() == BloodFxPayload.CLEAR) {
            clear(payload.entityUuid(), payload.zone());
            return;
        }
        if (payload.zone() < 0 || payload.zone() > 5) return;

        WoundKey key = new WoundKey(payload.entityUuid(), payload.zone());
        WoundEmitter emitter = WOUNDS.computeIfAbsent(key, ignored -> new WoundEmitter());
        if (payload.revision() < emitter.revision) return;
        emitter.update(payload, clientTicks);

        if (payload.event() == BloodFxPayload.IMPACT) {
            spawnImpact(client, emitter);
        }
    }

    public static void clientTick(Minecraft client) {
        if (client == null || client.level == null || client.player == null) {
            reset();
            return;
        }
        if (activeLevel != client.level) {
            reset();
            activeLevel = client.level;
        }
        clientTicks++;
        if ((clientTicks & 31) == 0) {
            SURFACE_DECALS.entrySet().removeIf(entry -> !entry.getValue().isAlive());
        }

        Iterator<Map.Entry<WoundKey, WoundEmitter>> iterator = WOUNDS.entrySet().iterator();
        while (iterator.hasNext()) {
            WoundEmitter wound = iterator.next().getValue();
            if (clientTicks - wound.lastSyncTick > 120) {
                wound.removeMark();
                iterator.remove();
                continue;
            }
            Entity entity = client.level.getEntity(wound.entityId);
            if (entity == null || !entity.getUUID().equals(wound.uuid) || !entity.isAlive()) {
                continue;
            }
            double distanceSq = client.player.distanceToSqr(entity);
            updateWoundMark(client, wound, distanceSq);
            if (wound.bleeding <= 0.1f
                    || (wound.flags & BloodFxPayload.FLAG_BANDAGED) != 0
                    || wound.profile > 1
                    || distanceSq > BloodFxRules.MAX_DISTANCE_SQ) {
                continue;
            }

            float movement = Math.min(1.0f, (float) entity.getDeltaMovement().horizontalDistance() * 7.0f);
            if (--wound.emissionCountdown > 0) continue;
            wound.emissionSequence++;
            wound.emissionCountdown = BloodFxRules.emissionIntervalTicks(
                    wound.bleeding, movement, wound.seed + wound.emissionSequence);
            int count = BloodFxRules.continuingDropCount(wound.bleeding, movement, distanceSq);
            count = qualityAdjusted(client, count);
            spawnContinuing(client, wound, entity, movement, count);
        }
    }

    private static void spawnImpact(Minecraft client, WoundEmitter wound) {
        Entity entity = client.level == null ? null : client.level.getEntity(wound.entityId);
        if (entity == null || !entity.getUUID().equals(wound.uuid) || client.player == null) return;
        double distanceSq = client.player.distanceToSqr(entity);
        int count = qualityAdjusted(client,
                BloodFxRules.impactDropCount(wound.intensity, wound.profile, distanceSq));
        if (count <= 0 || !BloodParticleSprites.ready()) return;
        Anchor anchor = anchorFor(entity, wound.zone, wound.side, wound.height, wound.seed, false);
        Vec3 incoming = wound.direction.lengthSqr() < 1.0e-5
                ? anchor.normal()
                : wound.direction.normalize();
        for (int i = 0; i < count && activeDrops < BloodFxRules.MAX_ACTIVE_DROPS; i++) {
            long seed = BloodFxRules.mix64(wound.seed + i * 0x9e3779b97f4a7c15L);
            Vec3 spread = randomVector(seed).scale(0.055 + wound.intensity * 0.075);
            double speed = (wound.profile == 1 ? 0.15 : 0.09)
                    + BloodFxRules.unitFloat(seed ^ 0xa0761d6478bd642fL) * (0.11 + wound.intensity * 0.13);
            Vec3 velocity = incoming.scale(speed).add(spread).add(0.0, 0.025 + wound.intensity * 0.05, 0.0);
            Vec3 origin = anchor.position().add(randomVector(seed ^ 0xe7037ed1a0b428dbL).scale(0.035));
            addDrop(client, origin, velocity, 0.35f + wound.intensity * 0.62f, seed);
        }
    }

    private static void spawnContinuing(Minecraft client, WoundEmitter wound, Entity entity,
                                        float movement, int count) {
        if (count <= 0 || !BloodParticleSprites.ready()) return;
        Anchor anchor = anchorFor(entity, wound.zone, wound.side, wound.height, wound.seed, false);
        Vec3 inherited = entity.getDeltaMovement().scale(0.55);
        for (int i = 0; i < count && activeDrops < BloodFxRules.MAX_ACTIVE_DROPS; i++) {
            long seed = BloodFxRules.mix64(wound.seed + wound.emissionSequence * 131L + i * 17L);
            Vec3 jitter = randomVector(seed).multiply(0.035, 0.015, 0.035);
            Vec3 velocity = inherited.add(jitter)
                    .add(anchor.normal().scale(0.012 + movement * 0.035))
                    .add(0.0, -0.025 - BloodFxRules.unitFloat(seed ^ 0x8ebc6af09c88c6e3L) * 0.035, 0.0);
            addDrop(client, anchor.position(), velocity,
                    Math.min(1.0f, 0.26f + wound.bleeding / 30.0f), seed);
        }
    }

    private static void addDrop(Minecraft client, Vec3 origin, Vec3 velocity, float size, long seed) {
        if (client.level == null || activeDrops >= BloodFxRules.MAX_ACTIVE_DROPS) return;
        var sprite = BloodParticleSprites.drop(seed);
        if (sprite == null) return;
        client.particleEngine.add(new BloodDropParticle(client.level,
                origin.x, origin.y, origin.z, velocity.x, velocity.y, velocity.z, size, seed, sprite));
        activeDrops++;
    }

    private static void updateWoundMark(Minecraft client, WoundEmitter wound, double distanceSq) {
        boolean visible = distanceSq <= BloodFxRules.MEDIUM_DETAIL_DISTANCE_SQ
                && (wound.intensity >= 0.10f || wound.bleeding > 0.1f)
                && BloodParticleSprites.ready();
        if (!visible) {
            wound.removeMark();
            return;
        }
        if (wound.mark == null || !wound.mark.isAlive()) {
            var sprite = BloodParticleSprites.wound(wound.seed, wound.profile == 1 ? 1 : 0);
            if (sprite == null || client.level == null) return;
            wound.mark = new BloodWoundParticle(client.level, wound.entityId, wound.uuid, wound.zone,
                    wound.side, wound.height, wound.intensity, wound.profile, wound.flags, wound.seed, sprite);
            client.particleEngine.add(wound.mark);
        } else {
            wound.mark.update(wound.entityId, wound.uuid, wound.zone, wound.side, wound.height,
                    wound.intensity, wound.profile, wound.flags, wound.seed);
        }
    }

    static void onDropCollision(ClientLevel level, Vec3 position, Vec3 normal, float energy, long seed) {
        Minecraft client = Minecraft.getInstance();
        if (level == null || level != client.level || client.player == null || !BloodParticleSprites.ready()) return;
        if (activeDecals >= BloodFxRules.MAX_ACTIVE_DECALS
                || client.player.distanceToSqr(position) > BloodFxRules.MAX_DISTANCE_SQ) return;

        BlockPos fluidPos = BlockPos.containing(position);
        if (!level.getFluidState(fluidPos).isEmpty()) return;
        Vec3 safeNormal = normal.lengthSqr() < 0.5 ? new Vec3(0.0, 1.0, 0.0) : normal.normalize();
        BlockPos surfacePos = BlockPos.containing(position.subtract(safeNormal.scale(0.04)));
        BlockState state = level.getBlockState(surfacePos);
        if (state.isAir()) return;
        int material = material(state);
        long surfaceKey = surfacePos.asLong() ^ normalKey(safeNormal);
        BloodDecalParticle existing = SURFACE_DECALS.get(surfaceKey);
        if (existing != null && existing.isAlive()) {
            existing.absorb(energy, seed);
            return;
        }

        int preferred = preferredSplat(material, energy, seed);
        var sprite = BloodParticleSprites.decal(seed, preferred);
        if (sprite == null) return;
        BloodDecalParticle decal = new BloodDecalParticle(level, position, safeNormal, material,
                Math.min(1.0f, 0.22f + energy * 0.78f), seed, surfaceKey, sprite);
        client.particleEngine.add(decal);
        SURFACE_DECALS.put(surfaceKey, decal);
        activeDecals++;
    }

    static void onDropEnterFluid(ClientLevel level, Vec3 position, float energy, long seed) {
        Minecraft client = Minecraft.getInstance();
        if (level == null || level != client.level || client.player == null || !BloodParticleSprites.ready()
                || activeDecals >= BloodFxRules.MAX_ACTIVE_DECALS
                || client.player.distanceToSqr(position) > BloodFxRules.MEDIUM_DETAIL_DISTANCE_SQ) {
            return;
        }
        var sprite = BloodParticleSprites.decal(seed, 3);
        if (sprite == null) return;
        client.particleEngine.add(new BloodWaterMistParticle(level, position.x, position.y, position.z,
                energy, seed, sprite));
        activeDecals++;
    }

    static void onDropRemoved() {
        activeDrops = Math.max(0, activeDrops - 1);
    }

    static void onDecalRemoved(long surfaceKey, BloodDecalParticle particle) {
        if (surfaceKey != Long.MIN_VALUE && particle != null) {
            SURFACE_DECALS.remove(surfaceKey, particle);
        }
        activeDecals = Math.max(0, activeDecals - 1);
    }

    static Anchor anchorFor(Entity entity, int zone, float localSide, float localHeight,
                            long seed, boolean surfaceOffset) {
        double yaw = Math.toRadians(entity instanceof LivingEntity living ? living.yBodyRot : entity.getYRot());
        Vec3 forward = new Vec3(-Math.sin(yaw), 0.0, Math.cos(yaw));
        Vec3 right = new Vec3(Math.cos(yaw), 0.0, Math.sin(yaw));
        double height = Math.max(1.0, entity.getBbHeight());
        double side = Math.max(-1.0, Math.min(1.0, localSide));
        double partY = Math.max(0.0, Math.min(1.0, localHeight));
        double speed;
        double walkPosition;
        if (entity instanceof LivingEntity living) {
            speed = Math.min(1.0, living.walkAnimation.speed());
            walkPosition = living.walkAnimation.position();
        } else {
            speed = Math.min(1.0, entity.getDeltaMovement().horizontalDistance() * 8.0);
            walkPosition = entity.tickCount;
        }
        double gait = Math.sin(walkPosition * 0.6662
                + BloodFxRules.unitFloat(seed) * 0.08) * speed;
        Vec3 position = entity.position();
        Vec3 normal;

        switch (zone) {
            case 0 -> {
                position = position.add(0.0, height * (0.84 + partY * 0.13), 0.0)
                        .add(right.scale(side * entity.getBbWidth() * 0.24))
                        .add(forward.scale(entity.getBbWidth() * 0.49));
                normal = forward;
            }
            case 1 -> {
                position = position.add(0.0, height * (0.47 + partY * 0.32), 0.0)
                        .add(right.scale(side * entity.getBbWidth() * 0.34))
                        .add(forward.scale(entity.getBbWidth() * 0.39));
                normal = forward.add(right.scale(side * 0.32)).normalize();
            }
            case 2, 3 -> {
                double sign = zone == 2 ? 1.0 : -1.0;
                position = position.add(0.0, height * (0.43 + partY * 0.35), 0.0)
                        .add(right.scale(sign * entity.getBbWidth() * 0.62 + side * 0.035))
                        .add(forward.scale(entity.getBbWidth() * 0.25 + gait * sign * 0.10));
                normal = forward.scale(0.62).add(right.scale(sign * 0.78)).normalize();
            }
            default -> {
                double sign = zone == 4 ? 1.0 : -1.0;
                position = position.add(0.0, height * (0.07 + partY * 0.36), 0.0)
                        .add(right.scale(sign * entity.getBbWidth() * 0.20 + side * 0.025))
                        .add(forward.scale(entity.getBbWidth() * 0.18 - gait * sign * 0.08));
                normal = forward.scale(0.82).add(right.scale(sign * 0.28)).normalize();
            }
        }
        if (surfaceOffset) position = position.add(normal.scale(0.006));
        return new Anchor(position, normal);
    }

    private static int qualityAdjusted(Minecraft client, int count) {
        if (count <= 0) return 0;
        float factor = switch (client.options.particles().get()) {
            case ALL -> 1.0f;
            case DECREASED -> 0.68f;
            case MINIMAL -> 0.34f;
        };
        int fps = client.getFps();
        if (fps > 0 && fps < 30) factor *= 0.48f;
        else if (fps > 0 && fps < 45) factor *= 0.72f;
        int adjusted = Math.round(count * factor);
        return count > 0 && factor >= 0.30f ? Math.max(1, adjusted) : adjusted;
    }

    private static int material(BlockState state) {
        if (state.is(BlockTags.DIRT) || state.is(BlockTags.MUD) || state.is(BlockTags.GRASS_BLOCKS)) return 1;
        if (state.is(BlockTags.SAND)) return 2;
        if (state.is(BlockTags.PLANKS) || state.is(BlockTags.LOGS)) return 3;
        if (state.is(BlockTags.SNOW)) return 4;
        if (state.is(BlockTags.WOOL) || state.is(BlockTags.WOOL_CARPETS)) return 5;
        return 0;
    }

    private static int preferredSplat(int material, float energy, long seed) {
        if (material == 1 || material == 2) return 3;
        if (energy > 0.78f) return 5 + Math.floorMod((int) seed, 3);
        if (energy > 0.46f) return 1 + Math.floorMod((int) (seed >>> 8), 3);
        return Math.floorMod((int) seed, 2);
    }

    private static long normalKey(Vec3 normal) {
        int axis = Math.abs(normal.y) > 0.5 ? 1 : Math.abs(normal.x) > 0.5 ? 2 : 3;
        int sign = normal.x + normal.y + normal.z >= 0.0 ? 1 : 0;
        return ((long) axis << 61) ^ ((long) sign << 60);
    }

    private static Vec3 randomVector(long seed) {
        double x = BloodFxRules.unitFloat(seed) * 2.0 - 1.0;
        double y = BloodFxRules.unitFloat(seed ^ 0x632be59bd9b4e019L) * 2.0 - 1.0;
        double z = BloodFxRules.unitFloat(seed ^ 0x8cb92baa3f3d8dd7L) * 2.0 - 1.0;
        Vec3 vector = new Vec3(x, y, z);
        return vector.lengthSqr() < 1.0e-5 ? new Vec3(0.0, 1.0, 0.0) : vector.normalize();
    }

    private static void clear(UUID uuid, int zone) {
        Iterator<Map.Entry<WoundKey, WoundEmitter>> iterator = WOUNDS.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<WoundKey, WoundEmitter> entry = iterator.next();
            if (entry.getKey().uuid.equals(uuid) && (zone < 0 || entry.getKey().zone == zone)) {
                entry.getValue().removeMark();
                iterator.remove();
            }
        }
    }

    private static void reset() {
        for (WoundEmitter wound : WOUNDS.values()) wound.removeMark();
        WOUNDS.clear();
        SURFACE_DECALS.clear();
        activeLevel = null;
        clientTicks = 0;
        activeDrops = 0;
        activeDecals = 0;
    }

    public record Anchor(Vec3 position, Vec3 normal) {
    }

    private record WoundKey(UUID uuid, int zone) {
    }

    private static final class WoundEmitter {
        private int entityId;
        private UUID uuid;
        private int zone;
        private int profile;
        private float side;
        private float height;
        private float intensity;
        private float bleeding;
        private Vec3 direction = Vec3.ZERO;
        private long seed;
        private int revision;
        private int flags;
        private int lastSyncTick;
        private int emissionCountdown = 2;
        private long emissionSequence;
        private BloodWoundParticle mark;

        private void update(BloodFxPayload payload, int tick) {
            entityId = payload.entityId();
            uuid = payload.entityUuid();
            zone = payload.zone();
            profile = Math.max(0, Math.min(4, payload.profile()));
            side = Math.max(-1.0f, Math.min(1.0f, finite(payload.localSide())));
            height = BloodFxRules.clamp01(payload.localHeight());
            intensity = BloodFxRules.clamp01(payload.intensity());
            bleeding = Math.max(0.0f, Math.min(100.0f, finite(payload.bleeding())));
            direction = safeVector(payload.directionX(), payload.directionY(), payload.directionZ());
            seed = payload.seed();
            revision = payload.revision();
            flags = payload.flags();
            lastSyncTick = tick;
            if (payload.event() == BloodFxPayload.IMPACT) emissionCountdown = 2;
        }

        private void removeMark() {
            if (mark != null) {
                mark.remove();
                mark = null;
            }
        }

        private static float finite(float value) {
            return Float.isFinite(value) ? value : 0.0f;
        }

        private static Vec3 safeVector(float x, float y, float z) {
            if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)) return Vec3.ZERO;
            Vec3 vector = new Vec3(x, y, z);
            return vector.lengthSqr() > 4.0 ? vector.normalize() : vector;
        }
    }
}
