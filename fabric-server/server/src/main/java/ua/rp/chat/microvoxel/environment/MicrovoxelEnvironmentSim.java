package ua.rp.chat.microvoxel.environment;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.ServerExplosion;
import ua.rp.chat.microvoxel.ChunkKey;
import ua.rp.chat.microvoxel.MicrovoxelBlockStates;
import ua.rp.chat.microvoxel.MicrovoxelContext;
import ua.rp.chat.microvoxel.MicrovoxelEnvironmentRules;
import ua.rp.chat.microvoxel.MicrovoxelExplosionRules;
import ua.rp.chat.microvoxel.MicrovoxelKey;
import ua.rp.chat.microvoxel.MicrovoxelVolume;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Bounded, deterministic environment simulation: fire propagation from adjacent flame/lava
 * sources (channel-checked against the contacted skin) and explosion pressure over microcells.
 * Iteration order and all randomness are hash-deterministic, so the same world state always
 * produces the same burning front.
 */
public final class MicrovoxelEnvironmentSim {
    private static final int MAX_EXPLOSION_CELL_EVALUATIONS = 262_144;

    private final MicrovoxelContext context;
    private int environmentTicks;
    private int environmentCursor;
    private List<MicrovoxelKey> environmentKeys = List.of();

    public MicrovoxelEnvironmentSim(MicrovoxelContext context) {
        this.context = context;
    }

    /** One-second cadence for fire policy passes. */
    public void tick() {
        if (++environmentTicks >= 20) {
            environmentTicks = 0;
            tickEnvironmentPolicies();
        }
    }

    public void onExplosion(ServerExplosion explosion) {
        if (!context.runtime().storageReady() || explosion == null || explosion.radius() <= 0.0f) return;
        ServerLevel level = explosion.level();
        UUID worldId = context.runtime().worldId(level);
        net.minecraft.world.phys.Vec3 center = explosion.center();
        double effectRadius = Math.max(1.0, explosion.radius() * 2.0);
        int chunkRadius = Math.max(1, (int) Math.ceil(effectRadius / 16.0) + 1);
        List<Map.Entry<MicrovoxelKey, MicrovoxelVolume>> candidates = context.runtime().store().nearby(
                worldId, floor(center.x) >> 4, floor(center.z) >> 4, chunkRadius);
        candidates.sort(Comparator.comparingDouble(entry -> {
            MicrovoxelKey key = entry.getKey();
            double dx = key.x() + 0.5 - center.x;
            double dy = key.y() + 0.5 - center.y;
            double dz = key.z() + 0.5 - center.z;
            return dx * dx + dy * dy + dz * dz;
        }));

        Map<MicrovoxelKey, MicrovoxelVolume> occupancySnapshot = new HashMap<>();
        for (Map.Entry<MicrovoxelKey, MicrovoxelVolume> entry : candidates) {
            occupancySnapshot.put(entry.getKey(), entry.getValue().copy());
        }

        int evaluated = 0;
        int removedTotal = 0;
        for (Map.Entry<MicrovoxelKey, MicrovoxelVolume> entry : candidates) {
            if (evaluated >= MAX_EXPLOSION_CELL_EVALUATIONS) break;
            MicrovoxelKey key = entry.getKey();
            if (isProtected(key)) continue;
            MicrovoxelVolume volume = context.runtime().store().get(key);
            if (volume == null) continue;
            int removed = 0;
            for (int cell = 0; cell < MicrovoxelVolume.CELL_COUNT
                    && evaluated < MAX_EXPLOSION_CELL_EVALUATIONS; cell++) {
                if (!volume.occupied(cell)) continue;
                evaluated++;
                int localX = MicrovoxelVolume.x(cell);
                int localY = MicrovoxelVolume.y(cell);
                int localZ = MicrovoxelVolume.z(cell);
                double worldX = key.x() + (localX + 0.5) / 16.0;
                double worldY = key.y() + (localY + 0.5) / 16.0;
                double worldZ = key.z() + (localZ + 0.5) / 16.0;
                double dx = worldX - center.x;
                double dy = worldY - center.y;
                double dz = worldZ - center.z;
                double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (distance >= effectRadius) continue;

                boolean exposed = isExposedMicrocell(occupancySnapshot, worldId,
                        key.x() * 16 + localX, key.y() * 16 + localY, key.z() * 16 + localZ);
                BlockState material = MicrovoxelBlockStates.parseBlockState(volume.material(cell));
                double resistance = Math.max(0.1, material.getBlock().getExplosionResistance());
                if (MicrovoxelExplosionRules.shouldBreak(
                        explosion.radius(), distance, resistance, exposed,
                        MicrovoxelExplosionRules.variance(key, cell))
                        && volume.remove(cell)) {
                    removed++;
                }
            }
            if (removed == 0) continue;
            removedTotal += removed;
            if (volume.occupiedCount() == 0) {
                // A fully wiped volume bursts with its own materials, not the marker.
                java.util.List<String> blownTop =
                        ua.rp.chat.microvoxel.MicrovoxelParentage.topMaterials(
                                occupancySnapshot.get(key), 3);
                context.runtime().projection().dematerialize(key);
                context.collision().invalidate(key);
                context.sync().broadcastRemove(key);
                net.minecraft.server.level.ServerLevel boomLevel =
                        context.runtime().getWorld(key.worldId());
                if (boomLevel != null) {
                    net.minecraft.core.BlockPos boomPos = new net.minecraft.core.BlockPos(
                            key.x(), key.y(), key.z());
                    for (String top : blownTop) {
                        try {
                            boomLevel.levelEvent(2001, boomPos,
                                    net.minecraft.world.level.block.Block.getId(
                                            MicrovoxelBlockStates.parseBlockState(top)));
                        } catch (RuntimeException unreadable) {
                        }
                    }
                }
            } else {
                context.runtime().projection().materialize(key, volume);
                context.sync().broadcastUpsert(key, volume);
            }
        }
        if (removedTotal > 0) {
            context.runtime().logger().fine("[MICROVOXEL] explosion removed " + removedTotal
                    + " cells after evaluating " + evaluated);
        }
    }

    private void tickEnvironmentPolicies() {
        if (!context.runtime().storageReady() || context.runtime().server() == null) return;
        if (environmentKeys.isEmpty() || environmentCursor >= environmentKeys.size()
                || context.runtime().server().getTickCount() % 200 == 0) {
            ArrayList<MicrovoxelKey> refreshed = new ArrayList<>();
            for (ChunkKey chunk : context.runtime().store().indexedChunks()) {
                ServerLevel indexedLevel = context.runtime().getWorld(chunk.worldId());
                if (indexedLevel == null
                        || indexedLevel.getChunkSource().getChunkNow(chunk.x(), chunk.z()) == null) {
                    continue;
                }
                for (Map.Entry<MicrovoxelKey, MicrovoxelVolume> entry
                        : context.runtime().store().inChunk(chunk.worldId(), chunk.x(), chunk.z())) {
                    refreshed.add(entry.getKey());
                }
            }
            environmentKeys = List.copyOf(refreshed);
            environmentCursor = 0;
        }
        int budget = Math.min(24, environmentKeys.size());
        for (int index = 0; index < budget && !environmentKeys.isEmpty(); index++) {
            if (environmentCursor >= environmentKeys.size()) environmentCursor = 0;
            processEnvironmentAt(environmentKeys.get(environmentCursor++));
        }
    }

    private void processEnvironmentAt(MicrovoxelKey key) {
        ServerLevel level = context.runtime().getWorld(key.worldId());
        if (level == null
                || level.getChunkSource().getChunkNow(key.chunkX(), key.chunkZ()) == null) return;
        BlockPos pos = new BlockPos(key.x(), key.y(), key.z());
        int heat = 0;
        java.util.EnumSet<Direction> heatFaces = java.util.EnumSet.noneOf(Direction.class);
        for (Direction direction : Direction.values()) {
            BlockPos adjacent = pos.relative(direction);
            BlockState adjacentState = level.getBlockState(adjacent);
            if (adjacentState.getBlock() instanceof BaseFireBlock) {
                heat = Math.max(heat, 1);
                heatFaces.add(direction);
            }
            if (level.getFluidState(adjacent).is(FluidTags.LAVA)) {
                heat = Math.max(heat, 3);
                heatFaces.add(direction);
            }
        }
        if (heat == 0) return;
        if (isProtected(key)) return;
        MicrovoxelVolume current = context.runtime().store().get(key);
        if (current == null) return;
        MicrovoxelVolume updated = current.copy();
        int removed = 0;
        int start = Math.floorMod(Objects.hash(key.x(), key.y(), key.z(),
                (int) level.getGameTime()), MicrovoxelVolume.CELL_COUNT);
        int limit = heat == 3 ? 12 : 4;
        for (int offset = 0; offset < MicrovoxelVolume.CELL_COUNT && removed < limit; offset++) {
            int cell = (start + offset) & (MicrovoxelVolume.CELL_COUNT - 1);
            boolean reachesHeat = false;
            for (Direction sourceFace : heatFaces) {
                if (MicrovoxelEnvironmentRules.exposedToFace(updated, cell, sourceFace)) {
                    reachesHeat = true;
                    break;
                }
            }
            if (!updated.occupied(cell) || !reachesHeat
                    || !flammableMaterial(updated.material(cell))
                    || !MicrovoxelEnvironmentRules.ignites(
                    level.getGameTime(), key, cell, heat)) continue;
            updated.remove(cell);
            removed++;
        }
        if (removed == 0) return;
        context.collision().invalidate(key);
        if (updated.occupiedCount() == 0) {
            context.runtime().projection().dematerialize(key);
            context.sync().broadcastRemove(key);
        } else {
            context.runtime().projection().materialize(key, updated);
            context.sync().broadcastUpsert(key, updated);
        }
        String burnedParent =
                ua.rp.chat.microvoxel.MicrovoxelParentage.dominantMaterial(current);
        if (burnedParent == null) burnedParent = "minecraft:oak_planks";
        level.levelEvent(2001, pos,
                net.minecraft.world.level.block.Block.getId(
                        MicrovoxelBlockStates.parseBlockState(burnedParent)));
    }

    /** Null-safe protection probe; flags are absent (unprotected) before facade start. */
    private boolean isProtected(MicrovoxelKey key) {
        ua.rp.chat.microvoxel.MicrovoxelFlags flags = context.runtime().flags();
        return flags != null && flags.isProtected(key);
    }

    private static boolean flammableMaterial(String material) {
        BlockState state = MicrovoxelBlockStates.parseBlockState(material);
        return state.ignitedByLava()
                || state.is(BlockTags.LOGS_THAT_BURN)
                || state.is(BlockTags.PLANKS)
                || state.is(BlockTags.LEAVES)
                || state.is(BlockTags.WOOL);
    }

    private static boolean isExposedMicrocell(
            Map<MicrovoxelKey, MicrovoxelVolume> snapshot,
            UUID worldId,
            int globalX,
            int globalY,
            int globalZ
    ) {
        return !occupiedGlobal(snapshot, worldId, globalX - 1, globalY, globalZ)
                || !occupiedGlobal(snapshot, worldId, globalX + 1, globalY, globalZ)
                || !occupiedGlobal(snapshot, worldId, globalX, globalY - 1, globalZ)
                || !occupiedGlobal(snapshot, worldId, globalX, globalY + 1, globalZ)
                || !occupiedGlobal(snapshot, worldId, globalX, globalY, globalZ - 1)
                || !occupiedGlobal(snapshot, worldId, globalX, globalY, globalZ + 1);
    }

    private static boolean occupiedGlobal(
            Map<MicrovoxelKey, MicrovoxelVolume> snapshot,
            UUID worldId,
            int globalX,
            int globalY,
            int globalZ
    ) {
        int blockX = Math.floorDiv(globalX, 16);
        int blockY = Math.floorDiv(globalY, 16);
        int blockZ = Math.floorDiv(globalZ, 16);
        MicrovoxelVolume volume = snapshot.get(new MicrovoxelKey(worldId, blockX, blockY, blockZ));
        return volume != null && volume.occupied(
                Math.floorMod(globalX, 16),
                Math.floorMod(globalY, 16),
                Math.floorMod(globalZ, 16));
    }

    private static int floor(double value) {
        return (int) Math.floor(value);
    }
}