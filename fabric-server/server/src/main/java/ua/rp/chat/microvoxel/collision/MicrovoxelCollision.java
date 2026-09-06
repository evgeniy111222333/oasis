package ua.rp.chat.microvoxel.collision;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.BitSetDiscreteVoxelShape;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import ua.rp.chat.microvoxel.MicrovoxelKey;
import ua.rp.chat.microvoxel.MicrovoxelManager;
import ua.rp.chat.microvoxel.MicrovoxelManager.Axis;
import ua.rp.chat.microvoxel.MicrovoxelRuntime;
import ua.rp.chat.microvoxel.MicrovoxelVolume;
import ua.rp.chat.mixin.CubeVoxelShapeInvoker;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Native-minecraft collision integration: movement sweep-and-clip against volume collision plans,
 * and the authoritative VoxelShape used by movement, support and projectile queries.
 *
 * <p>The grid backend folds orthogonal occupancy lines into 16-bit masks so only the candidate
 * planes along the movement axis are examined and no temporary AABB is allocated per cell. The
 * shape cache is keyed by volume revision and invalidated by the edit/environment modules.</p>
 */
public final class MicrovoxelCollision {
    private static final double EPSILON = 1.0E-7;
    private static final int MAX_CACHED_SHAPES = 2048;

    private final MicrovoxelRuntime runtime;
    private final Map<MicrovoxelKey, CachedCollisionShape> collisionShapes = new ConcurrentHashMap<>();
    /** Sealed-volume parent states for the light engine, keyed by volume revision. */
    private final Map<MicrovoxelKey, CachedLightParent> lightParents = new ConcurrentHashMap<>();
    /**
     * Dominant-material parents for break feedback and destroy queries, keyed by volume
     * revision. A null parent is a valid negative cache entry for the revision.
     */
    private final Map<MicrovoxelKey, CachedParent> breakParents = new ConcurrentHashMap<>();

    public MicrovoxelCollision(MicrovoxelRuntime runtime) {
        this.runtime = runtime;
    }

    public net.minecraft.world.phys.Vec3 collide(Entity entity, net.minecraft.world.phys.Vec3 movement) {
        double dx = movement.x;
        double dy = movement.y;
        double dz = movement.z;
        if (Math.abs(dx) + Math.abs(dy) + Math.abs(dz) < EPSILON) return movement;

        AABB actual = entity.getBoundingBox();
        UUID worldId = runtime.worldId(entity.level());

        double clippedY = clip(entity.level(), worldId, actual, dy, Axis.Y);
        actual = actual.move(0, clippedY, 0);
        double clippedX = clip(entity.level(), worldId, actual, dx, Axis.X);
        actual = actual.move(clippedX, 0, 0);
        double clippedZ = clip(entity.level(), worldId, actual, dz, Axis.Z);

        return new net.minecraft.world.phys.Vec3(clippedX, clippedY, clippedZ);
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
                    MicrovoxelVolume volume = runtime.store().get(new MicrovoxelKey(worldId, x, y, z));
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

    /** Exact narrow-phase collision for fragmented volumes. */
    public static double clipGrid(MicrovoxelVolume.CollisionPlan plan, int blockX, int blockY, int blockZ,
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

    public static double clipAgainst(AABB moving, AABB obstacle, double movement, Axis axis) {
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

    private static int floor(double value) {
        return (int) Math.floor(value);
    }

    /** Returns the exact native Minecraft shape used by movement, support and projectile queries. */
    public VoxelShape collisionShape(ServerLevel level, BlockPos pos) {
        MicrovoxelKey key = new MicrovoxelKey(runtime.worldId(level), pos.getX(), pos.getY(), pos.getZ());
        MicrovoxelVolume volume = runtime.store().get(key);
        if (volume == null) {
            collisionShapes.remove(key);
            return null;
        }
        CachedCollisionShape cached = collisionShapes.get(key);
        if (cached != null && cached.revision == volume.revision()) return cached.shape;

        MicrovoxelVolume snapshot = volume.copy();
        VoxelShape compiled = buildNativeShape(snapshot);
        if (collisionShapes.size() >= MAX_CACHED_SHAPES) trimCache();
        collisionShapes.put(key, new CachedCollisionShape(snapshot.revision(), compiled));
        return compiled;
    }

    /**
     * Parent material used by vanilla mining speed, tool and harvest calculations.
     * Dominant (not first) occupied cell, cached per volume revision: destroy
     * queries run every dig tick, so the 4096-cell scan happens at most once per edit.
     */
    public BlockState parentBlockState(ServerLevel level, BlockPos pos) {
        MicrovoxelKey key;
        try {
            key = new MicrovoxelKey(runtime.worldId(level), pos.getX(), pos.getY(), pos.getZ());
        } catch (IllegalStateException unavailable) {
            return null;
        }
        MicrovoxelVolume volume = runtime.store().get(key);
        if (volume == null) return null;
        CachedParent cached = breakParents.get(key);
        if (cached != null && cached.revision == volume.revision()) return cached.parent;
        BlockState parent = ua.rp.chat.microvoxel.MicrovoxelParentage.parentState(volume);
        if (breakParents.size() >= MAX_CACHED_SHAPES) trimParentCache();
        breakParents.put(key, new CachedParent(volume.revision(), parent));
        return parent;
    }

    /**
     * Light-engine view of one light-sealed volume: the first opaque parent material, or
     * {@code null} when the volume is sparse/translucent (the engine then keeps the marker
     * state and light passes as before). Results are cached per revision; misses are the
     * only counted event because this runs inside light-propagation BFS.
     */
    public BlockState lightState(ServerLevel level, BlockPos pos) {
        MicrovoxelKey key = new MicrovoxelKey(runtime.worldId(level), pos.getX(), pos.getY(), pos.getZ());
        MicrovoxelVolume volume = runtime.store().get(key);
        if (volume == null) {
            collisionShapes.remove(key);
            lightParents.remove(key);
            return null;
        }
        CachedLightParent cached = lightParents.get(key);
        if (cached != null && cached.revision == volume.revision()) return cached.parent;
        BlockState parent = resolveSealedParent(volume);
        boolean sealed = parent != null;
        if (lightParents.size() >= MAX_CACHED_SHAPES) trimLightCache();
        lightParents.put(key, new CachedLightParent(volume.revision(), parent, sealed));
        if (parent != null) {
            ua.rp.chat.microvoxel.MicrovoxelMetrics.inc("light.sealed");
        }
        return parent;
    }

    /**
     * Returns the first opaque parent material of a light-sealed volume (sealed faces or a
     * dense opaque fraction). Glass and other translucent builds never qualify even when
     * geometrically closed, so windows keep passing light exactly like vanilla.
     */
    private static BlockState resolveSealedParent(MicrovoxelVolume volume) {
        if (!volume.isLightSealed(MicrovoxelCollision::isOpaqueMaterial)) return null;
        for (int cell = 0; cell < MicrovoxelVolume.CELL_COUNT; cell++) {
            if (!volume.occupied(cell)) continue;
            String material = volume.material(cell);
            if (isOpaqueMaterial(material)) {
                return ua.rp.chat.microvoxel.MicrovoxelBlockStates.parseBlockState(material);
            }
        }
        return null;
    }

    private static boolean isOpaqueMaterial(String material) {
        try {
            return ua.rp.chat.microvoxel.MicrovoxelBlockStates.parseBlockState(material).isSolidRender();
        } catch (RuntimeException unparsable) {
            return false;
        }
    }

    /**
     * Drops stale caches so the next query rebuilds from the current volume. Also detects
     * light-seal transitions at edit time: the light engine only relights on stored-state
     * changes, so a seal flip with an identical marker blockstate (or a volume/block-packet
     * race) would otherwise leave stale light forever. Flips queue an explicit light check.
     */
    public void invalidate(MicrovoxelKey key) {
        CachedLightParent previous = lightParents.remove(key);
        collisionShapes.remove(key);
        breakParents.remove(key);
        boolean wasSealed = previous != null && previous.sealed;
        MicrovoxelVolume fresh = null;
        try {
            if (runtime.storageReady()) fresh = runtime.store().get(key);
        } catch (IllegalStateException unavailable) {
            return;
        }
        boolean nowSealed = fresh != null && fresh.isLightSealed(MicrovoxelCollision::isOpaqueMaterial);
        if (wasSealed != nowSealed) {
            pendingLightChecks.add(key);
        }
    }

    /** Explicit light checks queued by seal transitions; drained a few per server tick. */
    private final java.util.Set<MicrovoxelKey> pendingLightChecks =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Runs queued light checks against loaded chunks. Trickled (not all at once) so a mass
     * edit or a login snapshot cannot stall the tick on relight BFS.
     */
    public void drainLightChecks() {
        if (pendingLightChecks.isEmpty()) return;
        int budget = 16;
        var iterator = pendingLightChecks.iterator();
        while (budget-- > 0 && iterator.hasNext()) {
            MicrovoxelKey key = iterator.next();
            iterator.remove();
            net.minecraft.server.level.ServerLevel level = runtime.getWorld(key.worldId());
            if (level == null) continue;
            if (level.getChunkSource().getChunkNow(key.chunkX(), key.chunkZ()) == null) continue;
            level.getLightEngine().checkBlock(new net.minecraft.core.BlockPos(key.x(), key.y(), key.z()));
            ua.rp.chat.microvoxel.MicrovoxelMetrics.inc("light.checks");
        }
    }

    /**
     * Bounds the shape cache. Movement queries touch this map every tick, so without eviction
     * a tour of many volumes would grow the heap monotonically even after regions unload.
     */
    public void trimCache() {
        if (collisionShapes.size() <= MAX_CACHED_SHAPES) return;
        int overflow = collisionShapes.size() - MAX_CACHED_SHAPES;
        var iterator = collisionShapes.keySet().iterator();
        while (overflow-- > 0 && iterator.hasNext()) {
            iterator.next();
            iterator.remove();
        }
        trimLightCache();
    }

    private void trimLightCache() {
        if (lightParents.size() < MAX_CACHED_SHAPES) return;
        int overflow = lightParents.size() - MAX_CACHED_SHAPES;
        var iterator = lightParents.keySet().iterator();
        while (overflow-- > 0 && iterator.hasNext()) {
            iterator.next();
            iterator.remove();
        }
    }

    private void trimParentCache() {
        if (breakParents.size() < MAX_CACHED_SHAPES) return;
        int overflow = breakParents.size() - MAX_CACHED_SHAPES;
        var iterator = breakParents.keySet().iterator();
        while (overflow-- > 0 && iterator.hasNext()) {
            iterator.next();
            iterator.remove();
        }
    }

    public static VoxelShape buildNativeShape(MicrovoxelVolume volume) {
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

    private record CachedCollisionShape(int revision, VoxelShape shape) {
    }

    /** A null parent means "computed transparent for this revision" (negative cache). */
    private record CachedLightParent(int revision, BlockState parent, boolean sealed) {
    }

    /** A null parent means "computed parentless for this revision" (negative cache). */
    private record CachedParent(int revision, BlockState parent) {
    }
}