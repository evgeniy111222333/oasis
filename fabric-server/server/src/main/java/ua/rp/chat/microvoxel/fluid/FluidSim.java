package ua.rp.chat.microvoxel.fluid;

import ua.rp.chat.microvoxel.ChunkKey;
import ua.rp.chat.microvoxel.FluidVolume;
import ua.rp.chat.microvoxel.MicrovoxelBlocks;
import ua.rp.chat.microvoxel.MicrovoxelKey;
import ua.rp.chat.microvoxel.MicrovoxelManager;
import ua.rp.chat.microvoxel.MicrovoxelMetrics;
import ua.rp.chat.microvoxel.MicrovoxelProtocol;
import ua.rp.chat.microvoxel.MicrovoxelRuntime;
import ua.rp.chat.microvoxel.MicrovoxelVolume;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Tick simulation for voxel water. Still water with honest bookkeeping: levels equalize
 * between connected volumes, vanilla sources feed boundary cells, bottom openings pour real
 * vanilla water below, and every lifecycle edge (scoop, sponge, demolish, orphan flags) is
 * reconciled instead of leaking state.
 *
 * <p>Budgeted like the fire sim (see {@link FluidTuning} for every live-tunable bound):
 * volumes per tick, placements per tick, transfer budgets and sync throttles all come from
 * config with a rotating cursor, so large builds converge over seconds instead of freezing
 * one tick. All cross-volume transfers conserve water exactly
 * (see {@link FluidVolume#equalizeInto}).</p>
 */
public final class FluidSim {
    private static final int[][] DIRECTIONS = {
            {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}
    };

    private final MicrovoxelRuntime runtime;
    private final FluidTuning tuning;
    private final List<MicrovoxelKey> cursor = new ArrayList<>();
    /** Last broadcast (revision, tick) per volume: level syncs throttle to 20 ticks. */
    private final Map<MicrovoxelKey, long[]> fluidSync = new java.util.HashMap<>();
    private int cursorIndex;
    private int outflowPlacementsThisTick;

    public FluidSim(MicrovoxelRuntime runtime, FluidTuning tuning) {
        this.runtime = runtime;
        this.tuning = tuning;
    }

    private FluidStore fluids() {
        return runtime.fluids();
    }

    /**
     * Voxel-exact water override for entities. Vanilla decides swimming from block
     * granularity, so standing on a dry corner of a waterlogged marker wrongly swims. This
     * returns true only when every water signal plausibly comes from dry voxel cells: no
     * vanilla water anywhere in the bounding box, at least one marker-fluid volume touched,
     * and feet, mid-body and eyes all on dry cells. Callers apply it to isInWater,
     * getFluidHeight and isEyeInFluid together, never partially.
     */
    public static boolean shouldIgnoreWater(net.minecraft.world.entity.Entity entity) {
        ua.rp.chat.RPChat plugin = ua.rp.chat.RPChat.getInstance();
        if (plugin == null || plugin.getMicrovoxelManager() == null) return false;
        MicrovoxelManager manager = plugin.getMicrovoxelManager();
        if (manager.fluidCount() == 0) return false;
        if (!(entity.level() instanceof ServerLevel level)) return false;
        net.minecraft.world.phys.AABB box = entity.getBoundingBox().inflate(0.001);
        if (box.getXsize() > 4.0 || box.getYsize() > 4.0 || box.getZsize() > 4.0) return false;
        int minX = (int) Math.floor(box.minX);
        int maxX = (int) Math.floor(box.maxX);
        int minY = (int) Math.floor(box.minY);
        int maxY = (int) Math.floor(box.maxY);
        int minZ = (int) Math.floor(box.minZ);
        int maxZ = (int) Math.floor(box.maxZ);
        if ((maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1) > 64) return false;
        boolean touchedMarkerFluid = false;
        for (int bx = minX; bx <= maxX; bx++) {
            for (int by = minY; by <= maxY; by++) {
                for (int bz = minZ; bz <= maxZ; bz++) {
                    BlockPos pos = new BlockPos(bx, by, bz);
                    net.minecraft.world.level.block.state.BlockState state =
                            level.getBlockState(pos);
                    if (state.getFluidState().isEmpty()) continue;
                    if (!MicrovoxelBlocks.isMarker(state)) return false;
                    touchedMarkerFluid = true;
                }
            }
        }
        if (!touchedMarkerFluid) return false;
        net.minecraft.world.phys.Vec3 feet = entity.position();
        net.minecraft.world.phys.Vec3 eye = feet.add(0.0,
                entity.getEyeHeight(), 0.0);
        net.minecraft.world.phys.Vec3 mid = feet.add(0.0,
                entity.getBbHeight() / 2.0, 0.0);
        return !cellWet(manager, level, feet) && !cellWet(manager, level, mid)
                && !cellWet(manager, level, eye);
    }

    /**
     * Voxel-exact lava height for an entity, or -1 when vanilla already sees lava or no lava
     * cells touch it. Vanilla lava damage, ignition, slow movement and fog all key off the
     * refined reads, so crucibles burn exactly like vanilla pools with zero engine hooks
     * beyond them.
     */
    public static double voxelLavaHeight(net.minecraft.world.entity.Entity entity) {
        ua.rp.chat.RPChat plugin = ua.rp.chat.RPChat.getInstance();
        if (plugin == null || plugin.getMicrovoxelManager() == null) return -1.0;
        MicrovoxelManager manager = plugin.getMicrovoxelManager();
        if (manager.fluidCount() == 0) return -1.0;
        if (!(entity.level() instanceof ServerLevel level)) return -1.0;
        net.minecraft.world.phys.AABB box = entity.getBoundingBox().inflate(0.001);
        if (box.getXsize() > 4.0 || box.getYsize() > 4.0 || box.getZsize() > 4.0) return -1.0;
        int minX = (int) Math.floor(box.minX);
        int maxX = (int) Math.floor(box.maxX);
        int minY = (int) Math.floor(box.minY);
        int maxY = (int) Math.floor(box.maxY);
        int minZ = (int) Math.floor(box.minZ);
        int maxZ = (int) Math.floor(box.maxZ);
        if ((maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1) > 64) return -1.0;
        double height = -1.0;
        for (int bx = minX; bx <= maxX; bx++) {
            for (int by = minY; by <= maxY; by++) {
                for (int bz = minZ; bz <= maxZ; bz++) {
                    BlockPos pos = new BlockPos(bx, by, bz);
                    net.minecraft.world.level.material.FluidState fluidState =
                            level.getFluidState(pos);
                    if (!fluidState.isEmpty() && !fluidState.is(Fluids.WATER)) return -1.0;
                    if (!MicrovoxelBlocks.isMarker(level.getBlockState(pos))) continue;
                    MicrovoxelKey key;
                    try {
                        key = new MicrovoxelKey(manager.runtimeWorldId(level),
                                bx, by, bz);
                    } catch (IllegalStateException unavailable) {
                        continue;
                    }
                    FluidVolume fluid = manager.fluidStore().get(key);
                    if (fluid == null || !fluid.isLava()) continue;
                    // Only the cells the bounding box actually touches — clipped ranges,
                    // never the whole 4096. A player box touches a handful of cells.
                    int x0 = Math.max(0, (int) Math.floor((box.minX - bx) * 16.0));
                    int x1 = Math.min(15, (int) Math.floor((box.maxX - bx) * 16.0 - 1.0E-7));
                    int y0 = Math.max(0, (int) Math.floor((box.minY - by) * 16.0));
                    int y1 = Math.min(15, (int) Math.floor((box.maxY - by) * 16.0 - 1.0E-7));
                    int z0 = Math.max(0, (int) Math.floor((box.minZ - bz) * 16.0));
                    int z1 = Math.min(15, (int) Math.floor((box.maxZ - bz) * 16.0 - 1.0E-7));
                    for (int cx = x0; cx <= x1; cx++) {
                        for (int cy = y0; cy <= y1; cy++) {
                            for (int cz = z0; cz <= z1; cz++) {
                                int wet = fluid.level(MicrovoxelVolume.index(cx, cy, cz));
                                if (wet <= 0) continue;
                                double cell = wet / 16.0;
                                if (cell > height) height = cell;
                            }
                        }
                    }
                }
            }
        }
        return height;
    }

    /**
     * Lava height at one exact position, or -1 when dry. Single-cell fast path for eye
     * checks; the full-box version above covers submersion and buoyancy.
     */
    public static double voxelLavaHeightAt(net.minecraft.world.entity.Entity entity,
                                           net.minecraft.world.phys.Vec3 exact) {
        ua.rp.chat.RPChat plugin = ua.rp.chat.RPChat.getInstance();
        if (plugin == null || plugin.getMicrovoxelManager() == null) return -1.0;
        MicrovoxelManager manager = plugin.getMicrovoxelManager();
        if (manager.fluidCount() == 0) return -1.0;
        if (!(entity.level() instanceof ServerLevel level)) return -1.0;
        BlockPos pos = BlockPos.containing(exact);
        if (!MicrovoxelBlocks.isMarker(level.getBlockState(pos))) return -1.0;
        MicrovoxelKey key;
        try {
            key = new MicrovoxelKey(manager.runtimeWorldId(level),
                    pos.getX(), pos.getY(), pos.getZ());
        } catch (IllegalStateException unavailable) {
            return -1.0;
        }
        FluidVolume fluid = manager.fluidStore().get(key);
        if (fluid == null || !fluid.isLava()) return -1.0;
        int cellX = (int) Math.floor((exact.x - pos.getX()) * 16.0);
        int cellY = (int) Math.floor((exact.y - pos.getY()) * 16.0);
        int cellZ = (int) Math.floor((exact.z - pos.getZ()) * 16.0);
        if (cellX < 0 || cellX > 15 || cellY < 0 || cellY > 15 || cellZ < 0 || cellZ > 15) {
            return -1.0;
        }
        int wet = fluid.level(MicrovoxelVolume.index(cellX, cellY, cellZ));
        return wet > 0 ? wet / 16.0 : -1.0;
    }

    /** True when the exact 1/16 cell under a precise position holds water. */
    static boolean cellWet(MicrovoxelManager manager, ServerLevel level,
                           net.minecraft.world.phys.Vec3 exact) {
        BlockPos pos = BlockPos.containing(exact);
        MicrovoxelKey key;
        try {
            key = new MicrovoxelKey(manager.runtimeWorldId(level),
                    pos.getX(), pos.getY(), pos.getZ());
        } catch (IllegalStateException unavailable) {
            return false;
        }
        FluidVolume fluid = manager.fluidStore().get(key);
        if (fluid == null) return false;
        int cellX = (int) Math.floor((exact.x - pos.getX()) * 16.0);
        int cellY = (int) Math.floor((exact.y - pos.getY()) * 16.0);
        int cellZ = (int) Math.floor((exact.z - pos.getZ()) * 16.0);
        if (cellX < 0 || cellX > 15 || cellY < 0 || cellY > 15 || cellZ < 0 || cellZ > 15) {
            return false;
        }
        return fluid.level(MicrovoxelVolume.index(cellX, cellY, cellZ)) > 0;
    }

    /** Null-safe protection probe; flags are absent (unprotected) before facade start. */
    private boolean isProtected(MicrovoxelKey key) {
        ua.rp.chat.microvoxel.MicrovoxelFlags flags = runtime.flags();
        return flags != null && flags.isProtected(key);
    }

    /**
     * Thermodynamic product rule, shared by volume-volume and volume-vanilla crusting:
     * full-strength contact (both sides at 8+) petrifies into obsidian, anything weaker
     * into cobblestone. Pure and unit-tested.
     */
    public static String crustMaterial(int lavaLevel, int waterLevel) {
        return lavaLevel >= 8 && waterLevel >= 8 ? "minecraft:obsidian" : "minecraft:cobblestone";
    }

    /** Basin rule shared by bucket fills and orphan adoption. */
    public static boolean isBasin(MicrovoxelVolume micro) {
        if (micro == null) return false;
        int occupied = micro.occupiedCount();
        return occupied > 0 && occupied < MicrovoxelVolume.CELL_COUNT;
    }

    /** Air mask of the sibling microvoxel volume: the only cells water may occupy. */
    public static boolean[] airMask(MicrovoxelVolume micro) {
        return airScratch(micro);
    }

    public void tick() {
        if (!runtime.storageReady() || fluids() == null) return;
        outflowPlacementsThisTick = 0;
        if (cursorIndex >= cursor.size()) {
            cursor.clear();
            cursor.addAll(fluids().snapshot().keySet());
            cursorIndex = 0;
        }
        int processed = 0;
        while (processed < tuning.maxVolumesPerTick && cursorIndex < cursor.size()) {
            MicrovoxelKey key = cursor.get(cursorIndex++);
            processed++;
            tickVolume(key);
        }
        long tick = runtime.serverTick();
        if (tick % 200 == 0) {
            adoptOrphanWaterlogged();
            catchRain();
        }
    }

    /**
     * Rain catch: open basins under real rain gain one unit on every surface cell per scan.
     * Slow and atmospheric (a storm fills a pool over minutes, like vanilla cauldrons);
     * gated by the vanilla rain predicate, so deserts, roofs and the Nether stay dry for
     * free. Rotating chunk cursor, budgeted candidates.
     */
    private void catchRain() {
        List<ChunkKey> chunks = new ArrayList<>(runtime.store().indexedChunks());
        if (chunks.isEmpty()) return;
        int caught = 0;
        int scanned = 0;
        while (caught < 8 && scanned < chunks.size()) {
            ChunkKey chunk = chunks.get((rainCursor + scanned) % chunks.size());
            scanned++;
            ServerLevel level = runtime.getWorld(chunk.worldId());
            if (level == null || !chunkLoaded(level, chunk.x(), chunk.z())) continue;
            for (Map.Entry<MicrovoxelKey, MicrovoxelVolume> entry
                    : runtime.store().inChunk(chunk.worldId(), chunk.x(), chunk.z())) {
                if (caught >= 8) break;
                MicrovoxelKey key = entry.getKey();
                if (!isBasin(entry.getValue())) continue;
                BlockPos pos = new BlockPos(key.x(), key.y(), key.z());
                if (!level.isRainingAt(pos.above())) continue;
                FluidVolume fluid = fluids().get(key);
                if (fluid == null) {
                    fluid = FluidVolume.empty();
                    fluids().put(key, fluid);
                }
                int topped = FluidVolume.rainTopUp(
                        fluid.levelsDirect(), solidScratch(entry.getValue()), 1);
                if (topped > 0) {
                    fluid.setRevision(fluid.revision() + 1);
                    fluids().markDirty();
                    ensureWaterlogged(level, pos, entry.getValue());
                    caught++;
                    MicrovoxelMetrics.add("fluid.rain", topped);
                }
            }
        }
        rainCursor = (rainCursor + scanned) % chunks.size();
    }

    /** Raises the waterlogged flag when fluid exists but the marker lost it. */
    private void ensureWaterlogged(ServerLevel level, BlockPos pos, MicrovoxelVolume micro) {
        BlockState current = level.getBlockState(pos);
        if (!MicrovoxelBlocks.isMarker(current) || current.getValue(
                net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED)) {
            return;
        }
        level.setBlock(pos, current.setValue(
                net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED, true), 3);
    }

    /**
     * Never touch an unloaded chunk: any blockstate/fluid read or setBlock below would
     * synchronously (re)load it — a lag spike that also fights the C2ME chunk pipeline.
     * Deferred volumes simply wait for their chunk; fluid data persists meanwhile.
     */
    static boolean chunkLoaded(ServerLevel level, int chunkX, int chunkZ) {
        return level.getChunkSource().getChunkNow(chunkX, chunkZ) != null;
    }

    private void tickVolume(MicrovoxelKey key) {
        ServerLevel level = runtime.getWorld(key.worldId());
        if (level == null) return;
        if (!chunkLoaded(level, key.chunkX(), key.chunkZ())) return;
        MicrovoxelVolume micro = runtime.store().get(key);
        FluidVolume fluid = fluids().get(key);
        if (fluid == null) return;
        BlockPos pos = new BlockPos(key.x(), key.y(), key.z());

        // The microvoxel volume is gone (mined out, blasted, restored): spill the real
        // fluid where there is air, then drop the data. Covers every removal path at once.
        if (micro == null) {
            if (level.getBlockState(pos).isAir()) {
                level.setBlock(pos, fluid.kind() == FluidVolume.Kind.LAVA
                        ? Blocks.LAVA.defaultBlockState() : Blocks.WATER.defaultBlockState(), 3);
                MicrovoxelMetrics.inc("fluid.spills");
            }
            dropFluid(key);
            return;
        }

        // Lava never carries the waterlogged flag by design (it would read as water to
        // vanilla physics), so it takes a dedicated path below instead of the scoop rule.
        if (fluid.kind() == FluidVolume.Kind.LAVA) {
            tickLavaVolume(level, key, pos, micro, fluid);
            return;
        }

        BlockState marker = level.getBlockState(pos);
        boolean flagged = MicrovoxelBlocks.isMarker(marker)
                && marker.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED);
        if (fluid.isDry()) {
            // Dry data is garbage in every path: drop it, and converge a stray flag with it.
            dropFluid(key);
            if (flagged) {
                level.setBlock(pos, marker.setValue(
                        net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED, false), 3);
            }
            return;
        }
        if (!flagged) {
            // Wet data but the flag is gone: vanilla took the water (scoop, sponge).
            // The scoop wins; dropping data is the only consistent answer.
            dropFluid(key);
            MicrovoxelMetrics.inc("fluid.scooped");
            return;
        }

        // Local water first (settle, then lateral), then sharing with the world.
        // Order matters: the drain hole sees a level surface, not a stale pile.
        relaxLocalWater(key, micro, fluid);
        freezeSurface(level, key, micro, fluid);
        crustAgainstVanilla(level, key, micro, fluid);
        equalizeWithNeighbors(level, key, fluid);
        feedFromVanilla(level, key, micro, fluid);
        drainDownwardOpenings(level, key, micro, fluid);
        seepThroughSides(level, key, micro, fluid);
        fluids().markDirty();
        syncFluid(key, fluid);
        updateComparatorOutput(level, key, micro, fluid);
    }

    /**
     * Lava visit: same skeleton as water minus everything lava must not do (no seep, no
     * frost, no flag reconciliation), plus thermodynamic crusting where lava meets water.
     * Outflow places lava, inflow feeds from lava, emission stays 15 through the marker.
     */
    private void tickLavaVolume(ServerLevel level, MicrovoxelKey key, BlockPos pos,
                                MicrovoxelVolume micro, FluidVolume fluid) {
        if (fluid.isDry()) {
            dropFluid(key);
            return;
        }
        relaxLocalWater(key, micro, fluid);
        // Volume-volume crusting is owned by the lava side only, so one pair never
        // petrifies twice per tick; vanilla contact crusts from whichever side is visited.
        crustWithNeighbors(level, key, micro, fluid);
        crustAgainstVanilla(level, key, micro, fluid);
        equalizeWithNeighbors(level, key, fluid);
        feedFromVanilla(level, key, micro, fluid);
        drainDownwardOpenings(level, key, micro, fluid);
        fluids().markDirty();
        syncFluid(key, fluid);
        updateComparatorOutput(level, key, micro, fluid);
    }

    /**
     * Redstone tanks: notifies comparators only when the output quantum actually changes
     * (tracked per fluid revision, so steady baths cost one map lookup). Fluid edits
     * therefore drive redstone with zero polling and zero spam.
     */
    private void updateComparatorOutput(ServerLevel level, MicrovoxelKey key,
                                        MicrovoxelVolume micro, FluidVolume fluid) {
        long[] seen = comparatorLevels.get(key);
        if (seen != null && seen[0] == fluid.revision()) return;
        long airCells = 0;
        for (int cell = 0; cell < MicrovoxelVolume.CELL_COUNT; cell++) {
            if (!micro.occupied(cell)) airCells++;
        }
        int quantum = comparatorSignal(fluid.totalUnits(), airCells);
        long previous = seen == null ? -1L : seen[1];
        comparatorLevels.put(key, new long[]{fluid.revision(), quantum});
        if (previous != quantum) {
            level.updateNeighbourForOutputSignal(
                    new BlockPos(key.x(), key.y(), key.z()), MicrovoxelBlocks.MARKER);
            MicrovoxelMetrics.inc("fluid.comparator");
        }
    }

    /**
     * Cold-biome ice crust: the topmost wet cell of every column becomes a real ice voxel
     * (not a visual overlay), so frozen baths keep their shape and melt only when mined.
     * Protected volumes never freeze; thaw is one-way v1. Revision-gated caches downstream
     * (collision, light, mesh) rebuild themselves off the bumped microvoxel revision.
     */
    private void freezeSurface(ServerLevel level, MicrovoxelKey key,
                               MicrovoxelVolume micro, FluidVolume fluid) {
        if (isProtected(key) || fluid.isLava()) return;
        // Sky exposure first: cave pools under rock never frost, however cold the biome.
        if (!level.canSeeSky(new BlockPos(key.x(), key.y() + 1, key.z()))) return;
        // Fast gate: no wet cells near the top means no crust this visit.
        byte[] levels = fluid.levelsDirect();
        boolean nearTop = false;
        for (int x = 0; x < 16 && !nearTop; x++) {
            for (int z = 0; z < 16 && !nearTop; z++) {
                if (levels[MicrovoxelVolume.index(x, 14, z)] != 0
                        || levels[MicrovoxelVolume.index(x, 15, z)] != 0) {
                    nearTop = true;
                }
            }
        }
        if (!nearTop) return;
        BlockPos pos = new BlockPos(key.x(), key.y(), key.z());
        if (!level.getBiome(pos).value().shouldFreeze(level, pos)) return;
        boolean[] solid = solidScratch(micro);
        java.util.List<Integer> frozen = FluidVolume.freezeTopCells(fluid.levelsDirect(), solid);
        if (frozen.isEmpty()) return;
        int placed = 0;
        for (int cell : frozen) {
            if (micro.put(cell, "minecraft:ice")) placed++;
        }
        if (placed == 0) return;
        fluid.setRevision(fluid.revision() + 1);
        fluids().markDirty();
        runtime.projection().materialize(key, micro);
        if (runtime.sync() != null) {
            for (net.minecraft.server.level.ServerPlayer player : runtime.sync().nearbyPlayers(key)) {
                runtime.sync().sendUpsert(player, key, micro);
            }
        }
        MicrovoxelMetrics.add("fluid.frozen", placed);
    }

    /** Last relaxed (micro revision, fluid revision) per volume: the steady-state gate. */
    private final Map<MicrovoxelKey, long[]> flowState = new HashMap<>();
    /** Last signalled (fluid revision, quantum) per volume for comparator updates. */
    private final Map<MicrovoxelKey, long[]> comparatorLevels = new HashMap<>();

    /**
     * Local relaxation: gravity settle, then horizontal flow toward a level surface. Skipped
     * entirely when neither the geometry nor the fluid moved since the last pass (revision
     * pair gate): a steady bath costs one map lookup instead of two full sweeps. The gate
     * lives here rather than in the sweeps so equalize/inflow-driven changes automatically
     * re-arm it on the next visit.
     */
    private void relaxLocalWater(MicrovoxelKey key, MicrovoxelVolume micro, FluidVolume fluid) {
        long[] last = flowState.get(key);
        if (last != null && last[0] == micro.revision() && last[1] == fluid.revision()) return;
        settleAgainstGeometry(micro, fluid);
        long moved = fluid.lateralFlow(solidScratch(micro), tuning.lateralBudget,
                runtime.serverTick() % 2 == 0);
        if (moved > 0) MicrovoxelMetrics.add("fluid.lateral", moved);
        flowState.put(key, new long[]{micro.revision(), fluid.revision()});
    }

    /**
     * Broadcasts changed levels to subscribed players, at most every 20 ticks per volume.
     * New subscribers converge through snapshot pages instead, so steady state costs nothing.
     */
    private void syncFluid(MicrovoxelKey key, FluidVolume fluid) {
        long tick = runtime.serverTick();
        long[] state = fluidSync.get(key);
        if (state != null && (state[0] == fluid.revision() || tick - state[1] < tuning.syncThrottleTicks)) return;
        if (runtime.sync() == null) return;
        for (net.minecraft.server.level.ServerPlayer player : runtime.sync().nearbyPlayers(key)) {
            runtime.sync().sendFluidUpsert(player, key, fluid.revision(),
                    fluid.kind().code(), fluid.levelsCopy());
        }
        fluidSync.put(key, new long[]{fluid.revision(), tick});
        MicrovoxelMetrics.inc("fluid.synced");
    }

    /**
     * Records an externally broadcast revision (bucket fills) so the throttled sync does not
     * echo it back for the next 20 ticks.
     */
    public void markSynced(MicrovoxelKey key, int revision) {
        fluidSync.put(key, new long[]{revision, runtime.serverTick()});
    }

    /**
     * Drops fluid data and tells subscribers immediately (spills, scoops, dry-outs,
     * restores). Public so facade paths (restore-to-block) converge through the same
     * cleanup instead of leaking sync maps or client surfaces.
     */
    public void dropFluid(MicrovoxelKey key) {
        if (fluids().remove(key) == null) return;
        fluidSync.remove(key);
        flowState.remove(key);
        comparatorLevels.remove(key);
        if (runtime.sync() == null) return;
        for (net.minecraft.server.level.ServerPlayer player : runtime.sync().nearbyPlayers(key)) {
            runtime.sync().sendPacket(player, MicrovoxelProtocol.fluidRemove(key));
        }
        MicrovoxelMetrics.inc("fluid.dropped");
    }

    /**
     * Async safety net for the edit-time settle: compacts this volume against its own
     * geometry in case any path mutated cells without projecting (equalize targets are
     * validated here too, so pushed water can never rest inside fresh stone).
     */
    private void settleAgainstGeometry(MicrovoxelVolume micro, FluidVolume fluid) {
        boolean[] solid = solidScratch(micro);
        long[] deleted = {0};
        int changed = fluid.settleWith(solid, deleted);
        if (changed > 0) MicrovoxelMetrics.add("fluid.settledCells", changed);
        if (deleted[0] > 0) MicrovoxelMetrics.add("fluid.purged", deleted[0]);
    }

    /**
     * Side seepage: wet boundary cells facing world air lose one unit per visit toward a
     * global budget, each with a visible drip particle at the crack so players SEE where
     * the water goes. No blocks are ever placed sideways (a placed block would be scoopable
     * — an infinite-water dupe), so seepage reads as dripping loss. Faces against solid
     * neighbors never seep. Particles share the same budget as the drain.
     */
    private void seepThroughSides(ServerLevel level, MicrovoxelKey key,
                                  MicrovoxelVolume micro, FluidVolume fluid) {
        // Lava is viscous: no seepage, only real outflow below.
        if (fluid.isLava()) return;
        byte[] levels = fluid.levelsDirect();
        int seeped = 0;
        for (int dir = 0; dir < DIRECTIONS.length && seeped < tuning.seepBudget; dir++) {
            int[] offset = DIRECTIONS[dir];
            if (offset[1] != 0) continue;
            int nx = key.x() + offset[0];
            int nz = key.z() + offset[2];
            if (!chunkLoaded(level, nx >> 4, nz >> 4)) continue;
            BlockPos neighbor = new BlockPos(nx, key.y() + offset[1], nz);
            if (!level.getBlockState(neighbor).isAir()) continue;
            int axis = dir / 2;
            boolean positive = (dir % 2) == 0;
            for (int cell : boundaryCells(axis, positive)) {
                if (seeped >= tuning.seepBudget) break;
                if (micro.occupied(cell) || Byte.toUnsignedInt(levels[cell]) <= 0) continue;
                levels[cell]--;
                seeped++;
                if (seeped <= tuning.maxParticles) {
                    double wx = key.x() + (MicrovoxelVolume.x(cell) + 0.5) / 16.0 + offset[0] * 0.06;
                    double wy = key.y() + (MicrovoxelVolume.y(cell) + 0.5) / 16.0;
                    double wz = key.z() + (MicrovoxelVolume.z(cell) + 0.5) / 16.0 + offset[2] * 0.06;
                    level.sendParticles(
                            net.minecraft.core.particles.ParticleTypes.DRIPPING_WATER,
                            wx, wy, wz, 1, 0.02, 0.02, 0.02, 0.05);
                }
            }
        }
        if (seeped > 0) {
            fluid.setRevision(fluid.revision() + 1);
            MicrovoxelMetrics.add("fluid.seeped", seeped);
        }
    }

    private void equalizeWithNeighbors(ServerLevel level, MicrovoxelKey key, FluidVolume fluid) {
        UUID worldId = key.worldId();
        for (int dir = 0; dir < DIRECTIONS.length; dir++) {
            int[] offset = DIRECTIONS[dir];
            MicrovoxelKey neighborKey = new MicrovoxelKey(worldId,
                    key.x() + offset[0], key.y() + offset[1], key.z() + offset[2]);
            FluidVolume neighbor = fluids().get(neighborKey);
            // Mixed kinds never equalize: water and lava crust instead (see below).
            if (neighbor == null || neighbor.kind() != fluid.kind()) continue;
            int axis = dir / 2;
            boolean positive = (dir % 2) == 0;
            int[] pairs = FluidVolume.facePairs(axis, positive);
            long moved = fluid.equalizeWith(neighbor, pairs, tuning.equalizeBudget);
            if (moved > 0) {
                MicrovoxelMetrics.add("fluid.equalized", moved);
            }
        }
    }

    /** Crust conversions per visit across all thermodynamic paths. */
    private static final int CRUST_PER_VISIT = 16;

    /**
     * Thermodynamic crusting between touching volumes of opposite kinds: every wet-wet
     * boundary pair cools the lava side into rock (obsidian on full contact, cobblestone
     * otherwise) and boils off both sides. Miniature foundries, forge crucibles and stone
     * generators work exactly like players expect from vanilla instincts. Protected
     * volumes never participate on either side.
     */
    private void crustWithNeighbors(ServerLevel level, MicrovoxelKey key,
                                    MicrovoxelVolume micro, FluidVolume fluid) {
        UUID worldId = key.worldId();
        int crusted = 0;
        for (int dir = 0; dir < DIRECTIONS.length && crusted < CRUST_PER_VISIT; dir++) {
            int[] offset = DIRECTIONS[dir];
            MicrovoxelKey neighborKey = new MicrovoxelKey(worldId,
                    key.x() + offset[0], key.y() + offset[1], key.z() + offset[2]);
            FluidVolume neighbor = fluids().get(neighborKey);
            if (neighbor == null || neighbor.isDry() || neighbor.kind() == fluid.kind()) continue;
            MicrovoxelVolume neighborMicro = runtime.store().get(neighborKey);
            if (neighborMicro == null) continue;
            if (isProtected(key) || isProtected(neighborKey)) continue;
            int axis = dir / 2;
            boolean positive = (dir % 2) == 0;
            int[] pairs = FluidVolume.facePairs(axis, positive);
            byte[] mine = fluid.levelsDirect();
            byte[] theirs = neighbor.levelsDirect();
            boolean lavaMine = fluid.isLava();
            boolean neighborChanged = false;
            for (int index = 0; index + 1 < pairs.length && crusted < CRUST_PER_VISIT; index += 2) {
                int mineCell = pairs[index];
                int theirCell = pairs[index + 1];
                int mineLevel = Byte.toUnsignedInt(mine[mineCell]);
                int theirLevel = Byte.toUnsignedInt(theirs[theirCell]);
                if (mineLevel <= 0 || theirLevel <= 0) continue;
                // The rock forms where the lava was; water side only boils off.
                MicrovoxelVolume lavaMicro = lavaMine ? micro : neighborMicro;
                int lavaCell = lavaMine ? mineCell : theirCell;
                String rock = crustMaterial(
                        lavaMine ? mineLevel : theirLevel, lavaMine ? theirLevel : mineLevel);
                if (!lavaMicro.occupied(lavaCell) && putRock(lavaMine ? key : neighborKey,
                        lavaMicro, lavaCell, rock)) {
                    int cooled = Math.min(4, mineLevel);
                    int boiled = Math.min(4, theirLevel);
                    mine[mineCell] = (byte) (mineLevel - (lavaMine ? cooled : boiled));
                    theirs[theirCell] = (byte) (theirLevel - (lavaMine ? boiled : cooled));
                    crusted++;
                    neighborChanged = true;
                }
            }
            if (neighborChanged) {
                fluid.setRevision(fluid.revision() + 1);
                neighbor.setRevision(neighbor.revision() + 1);
            }
        }
        if (crusted > 0) {
            fluids().markDirty();
            syncFluid(key, fluid);
            MicrovoxelMetrics.add("fluid.crusted", crusted);
        }
    }

    /**
     * Crusting against vanilla fluids across the border: lava baths touching vanilla water
     * (and vice versa) petrify on contact, budgeted per visit. Chunk-gated like inflow —
     * never load for a crust check.
     */
    private void crustAgainstVanilla(ServerLevel level, MicrovoxelKey key,
                                     MicrovoxelVolume micro, FluidVolume fluid) {
        int crusted = 0;
        for (int dir = 0; dir < DIRECTIONS.length && crusted < CRUST_PER_VISIT; dir++) {
            int[] offset = DIRECTIONS[dir];
            int nx = key.x() + offset[0];
            int ny = key.y() + offset[1];
            int nz = key.z() + offset[2];
            if (!chunkLoaded(level, nx >> 4, nz >> 4)) continue;
            net.minecraft.world.level.material.FluidState state =
                    level.getFluidState(new BlockPos(nx, ny, nz));
            if (state.isEmpty()) continue;
            boolean lavaNeighbor = state.is(net.minecraft.world.level.material.Fluids.LAVA);
            if (fluid.isLava() == lavaNeighbor) continue;
            if (isProtected(key)) return;
            int axis = dir / 2;
            boolean positive = (dir % 2) == 0;
            byte[] levels = fluid.levelsDirect();
            for (int cell : boundaryCells(axis, positive)) {
                if (crusted >= CRUST_PER_VISIT) break;
                int wet = Byte.toUnsignedInt(levels[cell]);
                if (wet <= 0 || micro.occupied(cell)) continue;
                // Our side is the lava side exactly when this volume holds lava; the
                // vanilla neighbor plays the opposite role in both arrangements.
                String rock = fluid.isLava()
                        ? crustMaterial(wet, state.isSource() ? 16 : 1)
                        : crustMaterial(state.isSource() ? 16 : 1, wet);
                if (!micro.put(cell, rock)) continue;
                levels[cell] = (byte) Math.max(0, wet - 4);
                crusted++;
            }
        }
        if (crusted > 0) {
            fluid.setRevision(fluid.revision() + 1);
            fluids().markDirty();
            runtime.projection().materialize(key, micro);
            if (runtime.sync() != null) {
                for (net.minecraft.server.level.ServerPlayer player : runtime.sync().nearbyPlayers(key)) {
                    runtime.sync().sendUpsert(player, key, micro);
                }
            }
            MicrovoxelMetrics.add("fluid.crusted", crusted);
        }
    }

    /**
     * Writes one cooled cell into a microvoxel volume and projects it. Returns false when
     * the cell turned solid under us ((edits race the sim).
     */
    private boolean putRock(MicrovoxelKey key, MicrovoxelVolume micro, int cell, String rock) {
        if (!micro.put(cell, rock)) return false;
        runtime.projection().materialize(key, micro);
        if (runtime.sync() != null) {
            for (net.minecraft.server.level.ServerPlayer player : runtime.sync().nearbyPlayers(key)) {
                runtime.sync().sendUpsert(player, key, micro);
            }
        }
        return true;
    }

    /**
     * Vanilla water next to the volume feeds boundary air cells of the SAME kind, so
     * streams fill adjacent basins and side-fed channels prime. Lava pools feed from lava,
     * never from water. Sources fill to the brim; lesser flows only to half (a trickle
     * cannot conjure a full basin — the anti-dupe bound).
     */
    private void feedFromVanilla(ServerLevel level, MicrovoxelKey key,
                                 MicrovoxelVolume micro, FluidVolume fluid) {
        byte[] levels = fluid.levelsDirect();
        boolean[] air = airScratch(micro);
        int topped = 0;
        for (int dir = 0; dir < DIRECTIONS.length && topped < tuning.inflowTopup; dir++) {
            int[] offset = DIRECTIONS[dir];
            // Water never flows uphill into a volume: sources below cannot feed it.
            if (offset[1] < 0) continue;
            int nx = key.x() + offset[0];
            int nz = key.z() + offset[2];
            // Side/top neighbours may sit across the chunk border — never load for them.
            if (!chunkLoaded(level, nx >> 4, nz >> 4)) continue;
            BlockPos neighbor = new BlockPos(nx, key.y() + offset[1], nz);
            FluidState state = level.getFluidState(neighbor);
            if (state.isEmpty()) continue;
            boolean lavaNeighbor = state.is(Fluids.LAVA);
            if (fluid.isLava() != lavaNeighbor) continue;
            int axis = dir / 2;
            boolean positive = (dir % 2) == 0;
            topped += FluidVolume.inflowTopUp(levels, air,
                    boundaryCells(axis, positive), state.isSource(),
                    tuning.inflowTopup - topped);
        }
        if (topped > 0) {
            fluid.setRevision(fluid.revision() + 1);
            MicrovoxelMetrics.add("fluid.inflow", topped);
        }
    }

    /**
     * Bottom openings pour out: fluid cells on the floor layer above world air drain while
     * a real vanilla block of the same kind appears below (waterfalls and lavafalls share
     * the code, never the fluid). Placements are globally budgeted per tick; the drain math
     * itself is a pure kernel.
     */
    private void drainDownwardOpenings(ServerLevel level, MicrovoxelKey key,
                                       MicrovoxelVolume micro, FluidVolume fluid) {
        if (outflowPlacementsThisTick >= tuning.maxOutflowPlacements) return;
        BlockPos below = new BlockPos(key.x(), key.y() - 1, key.z());
        if (!level.getBlockState(below).isAir()) return;
        long drained = FluidVolume.drainBottomLayer(
                fluid.levelsDirect(), solidScratch(micro), tuning.drainPerCell);
        if (drained <= 0) return;
        fluid.setRevision(fluid.revision() + 1);
        level.setBlock(below, fluid.isLava()
                ? Blocks.LAVA.defaultBlockState() : Blocks.WATER.defaultBlockState(), 3);
        outflowPlacementsThisTick++;
        MicrovoxelMetrics.add("fluid.outflow", drained);
        // Bubbles where the stream lands: two particles per placement, inside the same
        // global budget family as seep drips so waterfalls read alive without showers.
        level.sendParticles(net.minecraft.core.particles.ParticleTypes.BUBBLE,
                key.x() + 0.5, (double) key.y() - 0.2, key.z() + 0.5, 2, 0.2, 0.1, 0.2, 0.02);
    }

    /**
     * Adopts orphan-waterlogged markers (vanilla filled them through paths we do not own)
     * by initializing full fluid data, a few per scan. Skips unloaded chunks (never load
     * for adoption) and protected volumes (a bypass fill must not seed data where buckets
     * are denied). Markers whose flag is gone but data lingers are handled in the main
     * pass, not here.
     */
    /** Rotating chunk cursor so orphan scans never restart from zero. */
    private int adoptCursor;
    /** Rotating chunk cursor for the rain catch pass. */
    private int rainCursor;

    /**
     * Comparator quantum 0..15 from wet units over basin capacity. Any water at all reads at
     * least 1 (vanilla container semantics); empty or capacity-less reads 0. Pure and
     * unit-tested; both the block mixin and the neighbour notifier share it.
     */
    public static int comparatorSignal(long wetUnits, long airCells) {
        if (wetUnits <= 0 || airCells <= 0) return 0;
        return (int) Math.max(1L, Math.min(15L,
                Math.round(15.0 * wetUnits / (airCells * (long) FluidVolume.MAX_LEVEL))));
    }
    private void adoptOrphanWaterlogged() {
        List<ChunkKey> chunks = new ArrayList<>(runtime.store().indexedChunks());
        if (chunks.isEmpty()) return;
        int adopted = 0;
        int scanned = 0;
        while (adopted < 4 && scanned < chunks.size()) {
            ChunkKey chunk = chunks.get((adoptCursor + scanned) % chunks.size());
            scanned++;
            ServerLevel level = runtime.getWorld(chunk.worldId());
            if (level == null || !chunkLoaded(level, chunk.x(), chunk.z())) continue;
            for (Map.Entry<MicrovoxelKey, MicrovoxelVolume> entry
                    : runtime.store().inChunk(chunk.worldId(), chunk.x(), chunk.z())) {
                if (adopted >= 4) break;
                MicrovoxelKey key = entry.getKey();
                // New fluids are always water: rain never seeds lava (no obsidian from the
                // sky); lava arrives only through buckets, dispensers and inflow.
                if (fluids().get(key) != null || !isBasin(entry.getValue())) continue;
                if (isProtected(key)) continue;
                BlockPos pos = new BlockPos(key.x(), key.y(), key.z());
                BlockState marker = level.getBlockState(pos);
                if (!MicrovoxelBlocks.isMarker(marker) || !marker.getValue(
                        net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED)) {
                    continue;
                }
                FluidVolume fluid = FluidVolume.empty();
                fluid.fillMasked(airMask(entry.getValue()));
                fluids().put(key, fluid);
                adopted++;
                MicrovoxelMetrics.inc("fluid.adopted");
            }
        }
        adoptCursor = (adoptCursor + scanned) % chunks.size();
    }

    /**
     * Cells of one boundary face, outward normal along the axis sign. Precomputed once (6
     * tables): inflow and seep resolve them on every visit, so per-call allocation would be
     * pure GC churn. Treat the returned arrays as immutable.
     */
    private static final int[][] BOUNDARY_TABLES = buildBoundaryTables();

    public static int[] boundaryCells(int axis, boolean positive) {
        return BOUNDARY_TABLES[axis * 2 + (positive ? 0 : 1)];
    }

    private static int[][] buildBoundaryTables() {
        int[][] tables = new int[6][];
        for (int axis = 0; axis < 3; axis++) {
            for (int sign = 0; sign < 2; sign++) {
                boolean positive = sign == 0;
                int[] cells = new int[16 * 16];
                int cursor = 0;
                int fixed = positive ? 15 : 0;
                for (int a = 0; a < 16; a++) {
                    for (int b = 0; b < 16; b++) {
                        int x = axis == 0 ? fixed : a;
                        int y = axis == 1 ? fixed : axis == 0 ? a : b;
                        int z = axis == 2 ? fixed : b;
                        cells[cursor++] = MicrovoxelVolume.index(x, y, z);
                    }
                }
                tables[axis * 2 + sign] = cells;
            }
        }
        return tables;
    }

    /**
     * Reusable 4096 scratch for solidity/air masks. The whole sim runs on the server thread
     * with no reentrancy, so one thread-local array replaces dozens of per-visit
     * allocations per tick at zero risk.
     */
    private static final ThreadLocal<boolean[]> SCRATCH =
            ThreadLocal.withInitial(() -> new boolean[MicrovoxelVolume.CELL_COUNT]);

    /** Fills the shared scratch with solidity flags; valid until the next call. */
    public static boolean[] solidScratch(MicrovoxelVolume micro) {
        boolean[] solid = SCRATCH.get();
        for (int cell = 0; cell < solid.length; cell++) solid[cell] = micro.occupied(cell);
        return solid;
    }

    /** Fills the shared scratch with air flags; valid until the next call. */
    public static boolean[] airScratch(MicrovoxelVolume micro) {
        boolean[] air = SCRATCH.get();
        for (int cell = 0; cell < air.length; cell++) air[cell] = !micro.occupied(cell);
        return air;
    }

}
