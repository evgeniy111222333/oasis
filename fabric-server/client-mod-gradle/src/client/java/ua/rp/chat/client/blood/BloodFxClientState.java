package ua.rp.chat.client.blood;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ParticleStatus;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.phys.Vec3;
import ua.rp.chat.client.microvoxel.MicrovoxelClientState;
import ua.rp.chat.blood.BloodFxRules;
import ua.rp.chat.blood.BloodVolumeRules;
import ua.rp.chat.blood.FootprintRules;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.List;
import java.util.UUID;

/**
 * Client-side deterministic blood simulation with bounded particles, distance
 * LOD, surface-aware decals and wound marks attached to body regions.
 */
public final class BloodFxClientState {
    /**
     * Temporary kill switch for the custom embedded-arrow renderer and its skin composite.
     * Vanilla arrows, damage and ordinary blood simulation remain active.
     */
    public static final boolean EMBEDDED_PROJECTILE_VISUALS_ENABLED = true;

    private static final Map<WoundKey, WoundEmitter> WOUNDS = new HashMap<>();
    private static final Map<Long, BloodDecalParticle> SURFACE_DECALS = new HashMap<>();
    private static final Map<Long, BloodDecalParticle> PERSISTED_SURFACE_DECALS = new HashMap<>();
    private static final Map<BloodDecalParticle, Long> PERSISTED_SURFACE_IDS = new HashMap<>();
    private static final Map<Long, List<BloodDecalParticle>> DECAL_SPATIAL = new HashMap<>();
    private static final Map<Long, FootprintDecalParticle> FOOTPRINT_DECALS = new HashMap<>();
    private static final Map<Long, Long> FOOTPRINT_CELLS = new HashMap<>();
    private static final Map<UUID, FootState> FOOT_STATES = new HashMap<>();
    private static ClientLevel activeLevel;
    private static int clientTicks;
    private static int activeDrops;
    private static int activeDecals;
    private static int surfaceSequence;

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

        WoundKey key = new WoundKey(payload.entityUuid(), payload.woundId());
        WoundEmitter emitter = WOUNDS.computeIfAbsent(key, ignored -> new WoundEmitter());
        if (payload.revision() < emitter.revision) return;
        emitter.update(payload, clientTicks);
        BloodSkinTextureManager.invalidate(payload.entityUuid());

        if (payload.event() == BloodFxPayload.IMPACT) {
            spawnImpact(client, emitter);
        }
    }

    public static void handleFootprint(BloodFootprintPayload payload) {
        Minecraft client = Minecraft.getInstance();
        if (payload == null || client.level == null || client.player == null) return;
        if (payload.event() == BloodFootprintPayload.REMOVE) {
            FootprintDecalParticle removed = FOOTPRINT_DECALS.remove(payload.decalId());
            if (removed != null) removed.remove();
            return;
        }
        if ((payload.event() != BloodFootprintPayload.STAMP
                && payload.event() != BloodFootprintPayload.UPDATE)
                || payload.decalId() == 0L
                || !finite(payload.x()) || !finite(payload.y()) || !finite(payload.z())
                || !Float.isFinite(payload.yaw()) || !Float.isFinite(payload.wetness())
                || payload.foot() < FootprintRules.LEFT || payload.foot() > FootprintRules.RIGHT
                || payload.gait() < FootprintRules.GAIT_WALK
                || payload.gait() > FootprintRules.GAIT_SLIDE
                || payload.footwear() < 0 || payload.footwear() > 3
                || client.player.distanceToSqr(payload.x(), payload.y(), payload.z())
                > BloodFxRules.MAX_DISTANCE_SQ * 1.7) {
            return;
        }
        FootprintDecalParticle existing = FOOTPRINT_DECALS.get(payload.decalId());
        if (existing != null && existing.isAlive()) {
            if (payload.event() == BloodFootprintPayload.UPDATE) {
                existing.synchronize(payload.wetness(), payload.ageTicks(), payload.lifetimeTicks());
            } else {
                existing.refresh(payload.wetness(), payload.ageTicks(), payload.lifetimeTicks(), payload.seed());
            }
            return;
        }
        spawnFootprintDecal(client, payload);
    }

    public static void handleSurface(BloodSurfacePayload payload) {
        Minecraft client = Minecraft.getInstance();
        if (payload == null || client.level == null || client.player == null) return;
        if (payload.event() == BloodSurfacePayload.REMOVE) {
            BloodDecalParticle removed = PERSISTED_SURFACE_DECALS.remove(payload.id());
            if (removed != null) {
                PERSISTED_SURFACE_IDS.remove(removed);
                removed.remove();
            }
            return;
        }
        if ((payload.event() != BloodSurfacePayload.STAMP
                && payload.event() != BloodSurfacePayload.UPDATE)
                || payload.id() <= 0L
                || !finite(payload.x()) || !finite(payload.y()) || !finite(payload.z())
                || !Float.isFinite(payload.volumeMl()) || payload.volumeMl() <= 0.0f
                || payload.family() < 0 || payload.family() > 23
                || client.player.distanceToSqr(payload.x(), payload.y(), payload.z())
                > BloodFxRules.MAX_DISTANCE_SQ * 2.0) return;
        Vec3 normal = new Vec3(payload.nx(), payload.ny(), payload.nz());
        if (normal.lengthSqr() < 0.5) return;
        normal = normal.normalize();
        BloodDecalParticle existing = PERSISTED_SURFACE_DECALS.get(payload.id());
        if (existing != null && existing.isAlive()) {
            existing.synchronizeAuthoritative(payload.volumeMl(), payload.ageTicks(),
                    payload.lifetimeTicks(), payload.seed());
            return;
        }
        long surfaceKey = surfaceKey(BlockPos.containing(
                payload.x() - normal.x * 0.04,
                payload.y() - normal.y * 0.04,
                payload.z() - normal.z * 0.04),
                new Vec3(payload.x(), payload.y(), payload.z()), normal);
        BloodDecalParticle predicted = SURFACE_DECALS.get(surfaceKey);
        if (predicted != null && predicted.isAlive()) {
            predicted.synchronizeAuthoritative(payload.volumeMl(), payload.ageTicks(),
                    payload.lifetimeTicks(), payload.seed());
            PERSISTED_SURFACE_DECALS.put(payload.id(), predicted);
            PERSISTED_SURFACE_IDS.put(predicted, payload.id());
            return;
        }
        if (!BloodParticleSprites.ready() || activeDecals >= BloodFxRules.MAX_ACTIVE_DECALS) return;
        var sprite = BloodParticleSprites.decal(payload.seed(), payload.family(),
                BloodFxRules.decalStage(payload.ageTicks(), payload.lifetimeTicks()));
        if (sprite == null) return;
        BloodDecalParticle decal = new BloodDecalParticle(client.level,
                new Vec3(payload.x(), payload.y(), payload.z()), normal, payload.material(),
                payload.volumeMl(), Math.min(1.0f, 0.22f + payload.energy() * 0.78f),
                payload.seed(), surfaceKey, payload.family(), sprite,
                true, Float.NaN, payload.flowDepth());
        decal.synchronizeAuthoritative(payload.volumeMl(), payload.ageTicks(),
                payload.lifetimeTicks(), payload.seed());
        client.particleEngine.add(decal);
        SURFACE_DECALS.put(surfaceKey, decal);
        PERSISTED_SURFACE_DECALS.put(payload.id(), decal);
        PERSISTED_SURFACE_IDS.put(decal, payload.id());
        DECAL_SPATIAL.computeIfAbsent(decal.spatialBucket(), ignored -> new ArrayList<>()).add(decal);
        activeDecals++;
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
            PERSISTED_SURFACE_DECALS.entrySet().removeIf(entry -> !entry.getValue().isAlive());
        }

        Iterator<Map.Entry<WoundKey, WoundEmitter>> iterator = WOUNDS.entrySet().iterator();
        while (iterator.hasNext()) {
            WoundEmitter wound = iterator.next().getValue();
            if (clientTicks - wound.lastSyncTick > 120) {
                wound.removeMark();
                BloodSkinTextureManager.invalidate(wound.uuid);
                iterator.remove();
                continue;
            }
            Entity entity = client.level.getEntity(wound.entityId);
            if (entity == null || !entity.getUUID().equals(wound.uuid) || !entity.isAlive()) {
                continue;
            }
            double distanceSq = client.player.distanceToSqr(entity);
            if (wound.flowMlPerSecond <= 0.0f
                    || wound.remainingBloodMl < BloodVolumeRules.MIN_VISIBLE_DROP_ML
                    || (wound.flags & BloodFxPayload.FLAG_BANDAGED) != 0
                    || wound.profile > 1
                    || distanceSq > BloodFxRules.MAX_DISTANCE_SQ) {
                continue;
            }

            float grantedThisTick = Math.min(wound.flowMlPerSecond / 20.0f, wound.remainingBloodMl);
            wound.remainingBloodMl = Math.max(0.0f, wound.remainingBloodMl - grantedThisTick);
            wound.volumeAccumulatorMl = Math.min(BloodVolumeRules.MAX_CLIENT_ACCUMULATOR_ML,
                    wound.volumeAccumulatorMl + grantedThisTick);
            int physicalDrops = BloodVolumeRules.spendableDrops(wound.volumeAccumulatorMl);
            if (physicalDrops <= 0) continue;
            float spendMl = physicalDrops * BloodVolumeRules.NOMINAL_DROP_ML;
            int count = qualityAdjusted(client, physicalDrops);
            if (count <= 0) continue;
            wound.emissionSequence++;
            wound.volumeAccumulatorMl = Math.max(0.0f, wound.volumeAccumulatorMl - spendMl);
            float movement = Math.min(1.0f, (float) entity.getDeltaMovement().horizontalDistance() * 7.0f);
            spawnContinuing(client, wound, entity, movement, count, spendMl);
        }
        tickFootprints(client);
    }

    private static void spawnImpact(Minecraft client, WoundEmitter wound) {
        Entity entity = client.level == null ? null : client.level.getEntity(wound.entityId);
        if (entity == null || !entity.getUUID().equals(wound.uuid) || client.player == null) return;
        double distanceSq = client.player.distanceToSqr(entity);
        int count = qualityAdjusted(client,
                BloodVolumeRules.impactDropCount(wound.impactVolumeMl, distanceSq));
        if (count <= 0 || !BloodParticleSprites.ready()) return;
        float dropVolumeMl = BloodVolumeRules.dropVolume(wound.impactVolumeMl, count);
        Anchor anchor = anchorFor(entity, wound.zone, wound.face, wound.side, wound.height, wound.seed, false);
        Vec3 incoming = wound.direction.lengthSqr() < 1.0e-5
                ? anchor.normal().scale(-1.0)
                : wound.direction.normalize();
        Vec3 outward = incoming.scale(-1.0);
        for (int i = 0; i < count && activeDrops < BloodFxRules.MAX_ACTIVE_DROPS; i++) {
            long seed = BloodFxRules.mix64(wound.seed + i * 0x9e3779b97f4a7c15L);
            Vec3 spread = randomVector(seed).scale(0.006 + wound.intensity * 0.014);
            double speed = BloodFxRules.impactSpeed(wound.intensity, wound.profile,
                    seed ^ 0xa0761d6478bd642fL);
            Vec3 velocity = outward.scale(speed).add(spread)
                    .add(entity.getDeltaMovement().scale(0.07))
                    .add(0.0, 0.003 + wound.intensity * 0.008, 0.0);
            Vec3 origin = anchor.position().add(randomVector(seed ^ 0xe7037ed1a0b428dbL).scale(0.016));
            addDrop(client, origin, velocity, dropVolumeMl,
                    0.35f + wound.intensity * 0.62f, seed);
        }
    }

    private static void spawnContinuing(Minecraft client, WoundEmitter wound, Entity entity,
                                        float movement, int count, float totalVolumeMl) {
        if (count <= 0 || !BloodParticleSprites.ready()) return;
        Anchor anchor = anchorFor(entity, wound.zone, wound.face, wound.side, wound.height, wound.seed, false);
        Vec3 inherited = entity.getDeltaMovement().scale(0.08);
        float dropVolumeMl = BloodVolumeRules.dropVolume(totalVolumeMl, count);
        for (int i = 0; i < count && activeDrops < BloodFxRules.MAX_ACTIVE_DROPS; i++) {
            long seed = BloodFxRules.mix64(wound.seed + wound.emissionSequence * 131L + i * 17L);
            Vec3 jitter = randomVector(seed).multiply(0.012, 0.006, 0.012);
            Vec3 velocity = inherited.add(jitter)
                    .add(anchor.normal().scale(0.002 + movement * 0.004))
                    .add(0.0, -0.025 - BloodFxRules.unitFloat(seed ^ 0x8ebc6af09c88c6e3L) * 0.016, 0.0);
            addDrop(client, anchor.position(), velocity, dropVolumeMl,
                    Math.min(1.0f, 0.24f + dropVolumeMl / 3.2f), seed);
        }
    }

    private static boolean addDrop(Minecraft client, Vec3 origin, Vec3 velocity,
                                   float volumeMl, float size, long seed) {
        if (client.level == null || activeDrops >= BloodFxRules.MAX_ACTIVE_DROPS) return false;
        var sprite = BloodParticleSprites.drop(seed);
        if (sprite == null) return false;
        client.particleEngine.add(new BloodDropParticle(client.level,
                origin.x, origin.y, origin.z, velocity.x, velocity.y, velocity.z,
                size, volumeMl, seed, sprite));
        activeDrops++;
        return true;
    }

    static void onDropCollision(ClientLevel level, Vec3 position, Vec3 normal,
                                float energy, float volumeMl, long seed) {
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
        BlockState exactMaterial = MicrovoxelClientState.materialStateAtSurface(
                surfacePos, position, safeNormal);
        int material = material(exactMaterial == null ? state : exactMaterial);
        long surfaceKey = surfacePos.asLong() ^ normalKey(safeNormal) ^ surfaceCellKey(position, safeNormal);
        int family = BloodFxRules.decalFamily(energy, material, seed ^ surfaceKey);
        if (ClientPlayNetworking.canSend(BloodSurfacePayload.TYPE)) {
            ClientPlayNetworking.send(new BloodSurfacePayload(BloodSurfacePayload.REQUEST, 0L,
                    ++surfaceSequence, position.x, position.y, position.z,
                    (float) safeNormal.x, (float) safeNormal.y, (float) safeNormal.z,
                    volumeMl, energy, material, family, seed, 0, 0, 0));
        }
        BloodDecalParticle existing = SURFACE_DECALS.get(surfaceKey);
        if (existing != null && existing.isAlive()) {
            existing.absorb(volumeMl, energy, seed);
            return;
        }

        var sprite = BloodParticleSprites.decal(seed, family, 0);
        if (sprite == null) return;
        BloodDecalParticle decal = new BloodDecalParticle(level, position, safeNormal, material,
                volumeMl, Math.min(1.0f, 0.22f + energy * 0.78f),
                seed, surfaceKey, family, sprite);
        client.particleEngine.add(decal);
        SURFACE_DECALS.put(surfaceKey, decal);
        DECAL_SPATIAL.computeIfAbsent(decal.spatialBucket(), ignored -> new ArrayList<>()).add(decal);
        activeDecals++;
    }

    static boolean onSurfaceFlowStep(ClientLevel level, Vec3 position, Vec3 normal,
                                     int material, float volumeMl, long seed, int flowDepth) {
        Minecraft client = Minecraft.getInstance();
        if (level == null || level != client.level || client.player == null
                || volumeMl < 0.1f || !BloodParticleSprites.ready()) {
            return false;
        }
        double descent = 0.045 + BloodFxRules.unitFloat(seed) * 0.035;
        Vec3 lateralAxis = Math.abs(normal.x) > 0.5
                ? new Vec3(0.0, 0.0, 1.0) : new Vec3(1.0, 0.0, 0.0);
        double lateral = (BloodFxRules.unitFloat(seed ^ 0x243f6a8885a308d3L) - 0.5) * 0.018;
        Vec3 next = position.add(0.0, -descent, 0.0).add(lateralAxis.scale(lateral));
        BlockPos supportPos = BlockPos.containing(next.subtract(normal.scale(0.04)));
        BlockState support = level.getBlockState(supportPos);
        VoxelShape supportShape = support.getCollisionShape(level, supportPos);
        if (support.isAir() || supportShape.isEmpty()) {
            Vec3 velocity = normal.scale(0.004).add(0.0, -0.038, 0.0);
            return addDrop(client, next.add(normal.scale(0.008)), velocity, volumeMl,
                    Math.min(1.0f, 0.25f + volumeMl / 3.0f), seed);
        }
        if (activeDecals >= BloodFxRules.MAX_ACTIVE_DECALS
                || client.player.distanceToSqr(next) > BloodFxRules.MAX_DISTANCE_SQ) {
            return false;
        }
        long surfaceKey = supportPos.asLong() ^ normalKey(normal) ^ surfaceCellKey(next, normal);
        BloodDecalParticle existing = SURFACE_DECALS.get(surfaceKey);
        if (existing != null && existing.isAlive()) {
            existing.absorb(volumeMl, 0.22f, seed);
            return true;
        }
        int family = BloodFxRules.decalFamily(0.22f, material, seed ^ surfaceKey);
        var sprite = BloodParticleSprites.decal(seed, family, 0);
        if (sprite == null) return false;
        BloodDecalParticle child = new BloodDecalParticle(level, next, normal, material,
                volumeMl, Math.min(1.0f, 0.22f + volumeMl / 3.5f), seed,
                surfaceKey, family, sprite, true, Float.NaN, flowDepth);
        client.particleEngine.add(child);
        SURFACE_DECALS.put(surfaceKey, child);
        DECAL_SPATIAL.computeIfAbsent(child.spatialBucket(), ignored -> new ArrayList<>()).add(child);
        activeDecals++;
        return true;
    }

    static void onDropEnterFluid(ClientLevel level, Vec3 position, float energy, long seed) {
        Minecraft client = Minecraft.getInstance();
        if (level == null || level != client.level || client.player == null || !BloodParticleSprites.ready()
                || activeDecals >= BloodFxRules.MAX_ACTIVE_DECALS
                || client.player.distanceToSqr(position) > BloodFxRules.MEDIUM_DETAIL_DISTANCE_SQ) {
            return;
        }
        int family = BloodFxRules.decalFamily(Math.min(0.33f, energy), 0, seed);
        var sprite = BloodParticleSprites.decal(seed, family, 0);
        if (sprite == null) return;
        client.particleEngine.add(new BloodWaterMistParticle(level, position.x, position.y, position.z,
                energy, seed, sprite));
        activeDecals++;
    }

    static void onDropRemoved() {
        activeDrops = Math.max(0, activeDrops - 1);
    }

    static boolean surfaceStillExists(ClientLevel level, Vec3 position, Vec3 normal) {
        Vec3 safeNormal = normal.lengthSqr() < 0.5 ? new Vec3(0.0, 1.0, 0.0) : normal.normalize();
        BlockHitResult hit = level.clip(new ClipContext(
                position.add(safeNormal.scale(0.018)),
                position.subtract(safeNormal.scale(0.045)),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty()));
        return hit.getType() == HitResult.Type.BLOCK
                && hit.getLocation().distanceToSqr(position) <= 0.004;
    }

    static void detachDecal(ClientLevel level, Vec3 position, Vec3 normal,
                            float volumeMl, long seed, boolean stillWet) {
        if (!stillWet || volumeMl < 0.08f) return;
        Minecraft client = Minecraft.getInstance();
        Vec3 velocity = normal.scale(0.006).add(0.0, -0.035, 0.0);
        addDrop(client, position.add(normal.scale(0.012)), velocity, volumeMl,
                Math.min(1.0f, 0.20f + volumeMl / 3.0f), seed);
    }

    private static void tickFootprints(Minecraft client) {
        if (client.player == null || client.level == null) return;
        Player player = client.player;
        FootState state = FOOT_STATES.computeIfAbsent(player.getUUID(),
                ignored -> new FootState(player.position()));
        Vec3 position = player.position();
        double moved = Math.hypot(position.x - state.lastPosition.x, position.z - state.lastPosition.z);
        state.lastPosition = position;

        boolean submerged = !client.level.getFluidState(player.blockPosition()).isEmpty();
        boolean raining = client.level.isRainingAt(player.blockPosition().above());
        state.left.wetness = FootprintRules.passiveDry(state.left.wetness, raining, submerged);
        state.right.wetness = FootprintRules.passiveDry(state.right.wetness, raining, submerged);

        boolean grounded = player.onGround() && !player.isSwimming() && !player.isFallFlying();
        if (!grounded) {
            state.wasGrounded = false;
            state.travel = Math.min(0.12, FootprintRules.accumulateTravel(state.travel, moved));
            return;
        }

        int gait = player.isSprinting() ? FootprintRules.GAIT_RUN
                : player.isCrouching() ? FootprintRules.GAIT_CROUCH : FootprintRules.GAIT_WALK;
        float speed = (float) Math.min(1.0, moved / 0.28);
        float yawDelta = Math.abs(wrapDegrees(player.yBodyRot - state.lastYaw));
        if (moved > 0.025 && yawDelta > 18.0f) gait = FootprintRules.GAIT_SLIDE;
        state.lastYaw = player.yBodyRot;

        if (!state.wasGrounded) {
            state.wasGrounded = true;
            int landingFoot = state.nextFoot;
            processFootContact(client, player, state, landingFoot, FootprintRules.GAIT_LANDING, 1.0f, true);
            state.nextFoot = 1 - landingFoot;
            state.travel = 0.0;
        }

        // Standing contact saturates both soles gradually instead of requiring movement.
        if (moved < 0.008 && (clientTicks + player.getId()) % 8 == 0) {
            processFootContact(client, player, state, FootprintRules.LEFT, gait, 0.34f, false);
            processFootContact(client, player, state, FootprintRules.RIGHT, gait, 0.34f, false);
        }

        state.travel = FootprintRules.accumulateTravel(state.travel, moved);
        float phase = player.walkAnimation.position() * 0.6662f;
        int phaseIndex = (int) Math.floor((phase + Math.PI * 0.5) / Math.PI);
        boolean animatedStrike = moved >= 0.008 && phaseIndex != state.phaseIndex;
        if (animatedStrike) state.phaseIndex = phaseIndex;
        boolean distanceStrike = FootprintRules.contactDue(state.travel, gait, speed);
        if ((animatedStrike || distanceStrike) && clientTicks - state.lastContactTick >= 3) {
            int foot = animatedStrike ? Math.floorMod(phaseIndex, 2) : state.nextFoot;
            if (foot != state.nextFoot && clientTicks - state.lastContactTick < 6) foot = state.nextFoot;
            processFootContact(client, player, state, foot, gait,
                    0.62f + speed * 0.38f, true);
            state.nextFoot = 1 - foot;
            state.lastContactTick = clientTicks;
            state.travel = FootprintRules.afterContact(state.travel, gait, speed);
        }
    }

    private static void processFootContact(Minecraft client, Player player, FootState state,
                                           int foot, int gait, float pressure, boolean mayStamp) {
        FootContact contact = findFootContact(client.level, player, foot);
        if (contact == null) return;
        SoleReservoir reservoir = foot == FootprintRules.LEFT ? state.left : state.right;
        int footwear = footwear(player);
        float pickedUp = sampleAndTakeBlood(contact, pressure);
        if (pickedUp > 0.002f) {
            reservoir.wetness = FootprintRules.pickup(reservoir.wetness, pickedUp,
                    contact.coverage, contact.material, footwear);
            reservoir.seed = BloodFxRules.mix64(reservoir.seed ^ contact.blockPos.asLong()
                    ^ clientTicks ^ (long) foot << 48);
            reservoir.lastWetTick = clientTicks;
            return;
        }
        if (!mayStamp || reservoir.wetness < 0.07f) return;

        float deposited = FootprintRules.deposit(reservoir.wetness, gait, contact.material);
        if (deposited < 0.045f) return;
        state.sequence++;
        long seed = BloodFxRules.mix64(reservoir.seed ^ player.getUUID().getLeastSignificantBits()
                ^ (long) state.sequence * 0x9e3779b97f4a7c15L);
        BloodFootprintPayload request = new BloodFootprintPayload(
                BloodFootprintPayload.REQUEST, 0L, player.getId(), player.getUUID(), state.sequence,
                contact.position.x, contact.position.y, contact.position.z,
                (float) Math.toRadians(player.yBodyRot), deposited, foot, gait,
                contact.material, footwear, seed, 0,
                FootprintRules.lifetimeTicks(contact.material, deposited, seed));
        if (ClientPlayNetworking.canSend(BloodFootprintPayload.TYPE)) {
            ClientPlayNetworking.send(request);
        } else {
            handleFootprint(new BloodFootprintPayload(
                    BloodFootprintPayload.STAMP, -Math.max(1, state.sequence), player.getId(),
                    player.getUUID(), state.sequence, request.x(), request.y(), request.z(),
                    request.yaw(), request.wetness(), request.foot(), request.gait(),
                    request.material(), request.footwear(), request.seed(), 0, request.lifetimeTicks()));
        }
        reservoir.wetness = FootprintRules.afterDeposit(reservoir.wetness, deposited, gait);
    }

    private static FootContact findFootContact(ClientLevel level, Player player, int foot) {
        double yaw = Math.toRadians(player.yBodyRot);
        Vec3 forward = new Vec3(-Math.sin(yaw), 0.0, Math.cos(yaw));
        Vec3 right = new Vec3(Math.cos(yaw), 0.0, Math.sin(yaw));
        double lateral = foot == FootprintRules.LEFT ? 0.105 : -0.105;
        Vec3 center = player.position().add(right.scale(lateral)).add(forward.scale(0.015));
        SurfaceHit best = null;
        Vec3[] samples = {
                center,
                center.add(forward.scale(0.105)),
                center.add(forward.scale(-0.085)),
                center.add(right.scale(0.055)),
                center.add(right.scale(-0.055))
        };
        int contacts = 0;
        for (Vec3 sample : samples) {
            SurfaceHit hit = surfaceBelow(level, sample, player.getY());
            if (hit == null) continue;
            contacts++;
            if (best == null || hit.y > best.y) best = hit;
        }
        if (best == null) return null;
        BlockState exactMaterial = MicrovoxelClientState.materialStateAtSurface(
                best.blockPos, best.location, new Vec3(0.0, 1.0, 0.0));
        return new FootContact(new Vec3(center.x, best.y + 0.004, center.z),
                best.blockPos, best.state, material(exactMaterial == null ? best.state : exactMaterial),
                contacts / (float) samples.length,
                forward, right);
    }

    private static SurfaceHit surfaceBelow(ClientLevel level, Vec3 sample, double feetY) {
        Vec3 start = new Vec3(sample.x, feetY + 0.18, sample.z);
        Vec3 end = new Vec3(sample.x, feetY - 0.72, sample.z);
        BlockHitResult hit = level.clip(new ClipContext(start, end,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty()));
        if (hit.getType() != HitResult.Type.BLOCK) return null;
        BlockPos pos = hit.getBlockPos();
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) return null;
        return new SurfaceHit(pos, state, hit.getLocation().y, hit.getLocation());
    }

    private static float sampleAndTakeBlood(FootContact contact, float pressure) {
        Vec3[] samples = {
                contact.position,
                contact.position.add(contact.forward.scale(0.095)),
                contact.position.add(contact.forward.scale(-0.078)),
                contact.position.add(contact.right.scale(0.052)),
                contact.position.add(contact.right.scale(-0.052))
        };
        float total = 0.0f;
        Map<Long, Float> footprintTransfers = new HashMap<>();
        Map<Long, Float> surfaceTransfers = new HashMap<>();
        for (Vec3 sample : samples) {
            BlockPos center = BlockPos.containing(sample);
            for (int y = -1; y <= 1; y++) {
                for (int x = -1; x <= 1; x++) {
                    for (int z = -1; z <= 1; z++) {
                        List<BloodDecalParticle> bucket = DECAL_SPATIAL.get(
                                center.offset(x, y, z).asLong());
                        if (bucket == null) continue;
                        for (BloodDecalParticle decal : List.copyOf(bucket)) {
                            if (decal.isAlive()) {
                                float transfer = decal.takeWetness(sample, pressure);
                                total += transfer;
                                Long persistedId = PERSISTED_SURFACE_IDS.get(decal);
                                if (persistedId != null && transfer > 0.0f) {
                                    surfaceTransfers.merge(persistedId, transfer, Float::sum);
                                }
                            }
                        }
                    }
                }
            }
            for (FootprintDecalParticle footprint : nearbyFootprints(sample)) {
                if (footprint.isAlive()) {
                    float transfer = footprint.takeWetness(sample, pressure);
                    total += transfer;
                    if (transfer > 0.0f && footprint.decalId() > 0L) {
                        footprintTransfers.merge(footprint.decalId(), transfer, Float::sum);
                    }
                }
            }
        }
        Minecraft client = Minecraft.getInstance();
        if (client.player != null && ClientPlayNetworking.canSend(BloodFootprintPayload.TYPE)) {
            for (Map.Entry<Long, Float> entry : footprintTransfers.entrySet()) {
                FootprintDecalParticle footprint = FOOTPRINT_DECALS.get(entry.getKey());
                if (footprint == null) continue;
                Vec3 position = footprint.position();
                ClientPlayNetworking.send(new BloodFootprintPayload(
                        BloodFootprintPayload.ABSORB, entry.getKey(), client.player.getId(),
                        client.player.getUUID(), clientTicks, position.x, position.y, position.z,
                        0.0f, Math.min(0.35f, entry.getValue()), 0, 0, 0, 0,
                        0L, 0, 0));
            }
        }
        if (client.player != null && ClientPlayNetworking.canSend(BloodSurfacePayload.TYPE)) {
            for (Map.Entry<Long, Float> entry : surfaceTransfers.entrySet()) {
                ClientPlayNetworking.send(new BloodSurfacePayload(BloodSurfacePayload.ABSORB,
                        entry.getKey(), ++surfaceSequence, 0, 0, 0, 0, 1, 0,
                        Math.min(0.5f, entry.getValue() * 0.32f), 0,
                        0, 0, 0L, 0, 0, 0));
            }
        }
        return Math.min(1.0f, total / samples.length * 1.42f);
    }

    private static List<FootprintDecalParticle> nearbyFootprints(Vec3 position) {
        List<FootprintDecalParticle> result = new ArrayList<>(4);
        for (int y = -1; y <= 1 && result.size() < 4; y++) {
            for (int x = -1; x <= 1 && result.size() < 4; x++) {
                for (int z = -1; z <= 1 && result.size() < 4; z++) {
                    long cell = FootprintRules.mergeCell(position.x + x * 0.125,
                            position.y + y * 0.125, position.z + z * 0.125);
                    Long id = FOOTPRINT_CELLS.get(cell);
                    if (id == null) continue;
                    FootprintDecalParticle particle = FOOTPRINT_DECALS.get(id);
                    if (particle != null && !result.contains(particle)) result.add(particle);
                }
            }
        }
        return result;
    }

    private static void spawnFootprintDecal(Minecraft client, BloodFootprintPayload payload) {
        if (!BloodParticleSprites.ready()) return;
        enforceFootprintBudget(client);
        int family = FootprintRules.variant(payload.foot(), payload.gait(),
                payload.footwear(), payload.seed());
        int stage = FootprintRules.stage(payload.wetness(), payload.ageTicks(), payload.lifetimeTicks());
        var sprite = BloodParticleSprites.footprint(family, stage);
        if (sprite == null) return;
        FootprintDecalParticle particle = new FootprintDecalParticle(client.level, payload.decalId(),
                new Vec3(payload.x(), payload.y(), payload.z()), payload.yaw(), payload.wetness(),
                payload.foot(), payload.gait(), payload.material(), payload.seed(),
                payload.footwear(),
                payload.ageTicks(), payload.lifetimeTicks(), sprite);
        client.particleEngine.add(particle);
        FOOTPRINT_DECALS.put(payload.decalId(), particle);
        FOOTPRINT_CELLS.put(particle.mergeCell(), payload.decalId());
    }

    private static void enforceFootprintBudget(Minecraft client) {
        if (FOOTPRINT_DECALS.size() < FootprintRules.MAX_RENDERED_FOOTPRINTS) return;
        FootprintDecalParticle candidate = null;
        double candidateScore = Double.NEGATIVE_INFINITY;
        for (FootprintDecalParticle particle : FOOTPRINT_DECALS.values()) {
            Vec3 position = particle.position();
            double distance = client.player == null ? 0.0
                    : client.player.distanceToSqr(position.x, position.y, position.z);
            double score = distance + particle.ageTicks() * 0.035;
            if (score > candidateScore) {
                candidateScore = score;
                candidate = particle;
            }
        }
        if (candidate != null) candidate.remove();
    }

    static void onFootprintRemoved(long decalId, FootprintDecalParticle particle) {
        if (particle == null) return;
        FOOTPRINT_DECALS.remove(decalId, particle);
        FOOTPRINT_CELLS.remove(particle.mergeCell(), decalId);
    }

    static void onDecalRemoved(long surfaceKey, BloodDecalParticle particle) {
        if (surfaceKey != Long.MIN_VALUE && particle != null) {
            SURFACE_DECALS.remove(surfaceKey, particle);
            List<BloodDecalParticle> bucket = DECAL_SPATIAL.get(particle.spatialBucket());
            if (bucket != null) {
                bucket.remove(particle);
                if (bucket.isEmpty()) DECAL_SPATIAL.remove(particle.spatialBucket());
            }
        }
        Long persistedId = PERSISTED_SURFACE_IDS.remove(particle);
        if (persistedId != null) PERSISTED_SURFACE_DECALS.remove(persistedId, particle);
        activeDecals = Math.max(0, activeDecals - 1);
    }

    private static long surfaceKey(BlockPos supportPos, Vec3 position, Vec3 normal) {
        return supportPos.asLong() ^ normalKey(normal) ^ surfaceCellKey(position, normal);
    }

    static Anchor anchorFor(Entity entity, int zone, float localSide, float localHeight,
                            long seed, boolean surfaceOffset) {
        return anchorFor(entity, zone, 0, localSide, localHeight, seed, surfaceOffset);
    }

    static Anchor anchorFor(Entity entity, int zone, int face, float localSide, float localHeight,
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
        Vec3 normal = switch (face) {
            case 1 -> forward.scale(-1.0);
            case 2 -> right.scale(-1.0);
            case 3 -> right;
            default -> forward;
        };
        double surfaceDepth = (face == 2 || face == 3)
                ? entity.getBbWidth() * (zone == 1 ? 0.48 : 0.42)
                : entity.getBbWidth() * (zone == 0 ? 0.48 : zone == 1 ? 0.24 : 0.20);

        switch (zone) {
            case 0 -> {
                position = position.add(0.0, height * (0.84 + partY * 0.13), 0.0)
                        .add(right.scale(side * entity.getBbWidth() * 0.24))
                        .add(normal.scale(surfaceDepth));
            }
            case 1 -> {
                position = position.add(0.0, height * (0.47 + partY * 0.32), 0.0)
                        .add(right.scale(side * entity.getBbWidth() * 0.34))
                        .add(normal.scale(surfaceDepth));
            }
            case 2, 3 -> {
                double sign = zone == 2 ? 1.0 : -1.0;
                position = position.add(0.0, height * (0.43 + partY * 0.35), 0.0)
                        .add(right.scale(sign * entity.getBbWidth() * 0.62 + side * 0.035))
                        .add(forward.scale(gait * sign * 0.10))
                        .add(normal.scale(surfaceDepth));
            }
            default -> {
                double sign = zone == 4 ? 1.0 : -1.0;
                position = position.add(0.0, height * (0.07 + partY * 0.36), 0.0)
                        .add(right.scale(sign * entity.getBbWidth() * 0.20 + side * 0.025))
                        .add(forward.scale(-gait * sign * 0.08))
                        .add(normal.scale(surfaceDepth));
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

    private static int footwear(Player player) {
        var stack = player.getItemBySlot(EquipmentSlot.FEET);
        if (stack.isEmpty()) return 0;
        if (stack.is(Items.LEATHER_BOOTS)) return 1;
        if (stack.is(Items.IRON_BOOTS) || stack.is(Items.CHAINMAIL_BOOTS)
                || stack.is(Items.GOLDEN_BOOTS) || stack.is(Items.DIAMOND_BOOTS)
                || stack.is(Items.NETHERITE_BOOTS)) return 2;
        return 3;
    }

    private static float wrapDegrees(float degrees) {
        float wrapped = degrees % 360.0f;
        if (wrapped >= 180.0f) wrapped -= 360.0f;
        if (wrapped < -180.0f) wrapped += 360.0f;
        return wrapped;
    }

    private static long normalKey(Vec3 normal) {
        int axis = Math.abs(normal.y) > 0.5 ? 1 : Math.abs(normal.x) > 0.5 ? 2 : 3;
        int sign = normal.x + normal.y + normal.z >= 0.0 ? 1 : 0;
        return ((long) axis << 61) ^ ((long) sign << 60);
    }

    private static long surfaceCellKey(Vec3 position, Vec3 normal) {
        int a;
        int b;
        if (Math.abs(normal.y) > 0.5) {
            a = BloodFxRules.surfaceCell(position.x);
            b = BloodFxRules.surfaceCell(position.z);
        } else if (Math.abs(normal.x) > 0.5) {
            a = BloodFxRules.surfaceCell(position.y);
            b = BloodFxRules.surfaceCell(position.z);
        } else {
            a = BloodFxRules.surfaceCell(position.x);
            b = BloodFxRules.surfaceCell(position.y);
        }
        return ((long) a << 56) ^ ((long) b << 58);
    }

    private static Vec3 randomVector(long seed) {
        double x = BloodFxRules.unitFloat(seed) * 2.0 - 1.0;
        double y = BloodFxRules.unitFloat(seed ^ 0x632be59bd9b4e019L) * 2.0 - 1.0;
        double z = BloodFxRules.unitFloat(seed ^ 0x8cb92baa3f3d8dd7L) * 2.0 - 1.0;
        Vec3 vector = new Vec3(x, y, z);
        return vector.lengthSqr() < 1.0e-5 ? new Vec3(0.0, 1.0, 0.0) : vector.normalize();
    }

    private static boolean finite(double value) {
        return Double.isFinite(value);
    }

    private static void clear(UUID uuid, int zone) {
        Iterator<Map.Entry<WoundKey, WoundEmitter>> iterator = WOUNDS.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<WoundKey, WoundEmitter> entry = iterator.next();
            if (entry.getKey().uuid.equals(uuid) && (zone < 0 || entry.getValue().zone == zone)) {
                entry.getValue().removeMark();
                iterator.remove();
            }
        }
        BloodSkinTextureManager.invalidate(uuid);
    }

    private static void reset() {
        for (WoundEmitter wound : WOUNDS.values()) wound.removeMark();
        WOUNDS.clear();
        SURFACE_DECALS.clear();
        PERSISTED_SURFACE_DECALS.clear();
        PERSISTED_SURFACE_IDS.clear();
        DECAL_SPATIAL.clear();
        FOOTPRINT_DECALS.clear();
        FOOTPRINT_CELLS.clear();
        FOOT_STATES.clear();
        BloodSkinTextureManager.reset();
        activeLevel = null;
        clientTicks = 0;
        activeDrops = 0;
        activeDecals = 0;
    }

    public record Anchor(Vec3 position, Vec3 normal) {
    }

    private record SurfaceHit(BlockPos blockPos, BlockState state, double y, Vec3 location) {
    }

    private record FootContact(Vec3 position, BlockPos blockPos, BlockState state, int material,
                               float coverage, Vec3 forward, Vec3 right) {
    }

    public static List<SkinWound> skinWounds(UUID uuid) {
        return WOUNDS.values().stream()
                .filter(wound -> uuid.equals(wound.uuid))
                .filter(wound -> EMBEDDED_PROJECTILE_VISUALS_ENABLED
                        || (wound.flags & BloodFxPayload.FLAG_EMBEDDED_PROJECTILE) == 0)
                .sorted((a, b) -> Long.compare(a.woundId, b.woundId))
                .map(wound -> new SkinWound(wound.woundId, wound.zone, wound.face, wound.profile,
                        wound.side, wound.height, wound.intensity, wound.seed, wound.flags,
                        wound.direction, wound.penetrationDepth,
                        Math.max(0, clientTicks - wound.lastSyncTick)))
                .toList();
    }

    public static boolean hasEmbeddedArrow(UUID uuid) {
        if (!EMBEDDED_PROJECTILE_VISUALS_ENABLED) return false;
        return WOUNDS.values().stream().anyMatch(wound -> uuid.equals(wound.uuid)
                && wound.profile == 1
                && (wound.flags & BloodFxPayload.FLAG_EMBEDDED_PROJECTILE) != 0);
    }

    public record SkinWound(long woundId, int zone, int face, int profile, float side,
                            float height, float intensity, long seed, int flags, Vec3 direction,
                            float penetrationDepth, int ageTicks) {
    }

    private record WoundKey(UUID uuid, long woundId) {
    }

    private static final class WoundEmitter {
        private int entityId;
        private UUID uuid;
        private int zone;
        private long woundId;
        private int face;
        private int profile;
        private float side;
        private float height;
        private float intensity;
        private float bleeding;
        private float impactVolumeMl;
        private float flowMlPerSecond;
        private float remainingBloodMl;
        private float volumeAccumulatorMl;
        private Vec3 direction = Vec3.ZERO;
        private float penetrationDepth;
        private long seed;
        private int revision;
        private int flags;
        private int lastSyncTick;
        private long emissionSequence;

        private void update(BloodFxPayload payload, int tick) {
            entityId = payload.entityId();
            uuid = payload.entityUuid();
            woundId = payload.woundId();
            zone = payload.zone();
            face = Math.max(0, Math.min(3, payload.face()));
            profile = Math.max(0, Math.min(4, payload.profile()));
            side = Math.max(-1.0f, Math.min(1.0f, finite(payload.localSide())));
            height = BloodFxRules.clamp01(payload.localHeight());
            intensity = BloodFxRules.clamp01(payload.intensity());
            bleeding = Math.max(0.0f, Math.min(100.0f, finite(payload.bleeding())));
            impactVolumeMl = Math.max(0.0f, Math.min(40.0f, finite(payload.impactVolumeMl())));
            flowMlPerSecond = Math.max(0.0f, Math.min(
                    BloodVolumeRules.MAX_FLOW_ML_PER_SECOND, finite(payload.flowMlPerSecond())));
            remainingBloodMl = Math.max(0.0f, Math.min(5_000.0f, finite(payload.remainingBloodMl())));
            Vec3 updatedDirection = safeVector(
                    payload.directionX(), payload.directionY(), payload.directionZ());
            if (updatedDirection.lengthSqr() > 1.0e-5 || direction.lengthSqr() < 1.0e-5) {
                direction = updatedDirection;
            }
            penetrationDepth = Math.max(0.0f, Math.min(0.75f, finite(payload.penetrationDepth())));
            seed = payload.seed();
            revision = payload.revision();
            flags = payload.flags();
            lastSyncTick = tick;
        }

        private void removeMark() {
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

    private static final class FootState {
        private Vec3 lastPosition;
        private final SoleReservoir left = new SoleReservoir();
        private final SoleReservoir right = new SoleReservoir();
        private double travel;
        private int phaseIndex;
        private int nextFoot;
        private int lastContactTick = Integer.MIN_VALUE / 2;
        private int sequence;
        private boolean wasGrounded;
        private float lastYaw;

        private FootState(Vec3 position) {
            lastPosition = position;
        }
    }

    private static final class SoleReservoir {
        private float wetness;
        private long seed;
        private int lastWetTick;
    }
}
