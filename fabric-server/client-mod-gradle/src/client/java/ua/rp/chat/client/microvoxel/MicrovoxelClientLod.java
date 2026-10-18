package ua.rp.chat.client.microvoxel;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import ua.rp.chat.microvoxel.MicrovoxelLodTiers;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Distance-LOD review and far-tier rebuild throttling, extracted from
 * {@link MicrovoxelClientState}.
 *
 * <p>Reconciles mesh resolution with camera distance across three tiers every 40 client ticks and
 * batches far-tier content edits to one rebuild per second. It only flips tier flags and enqueues
 * work through the facade ({@link MicrovoxelClientState#enqueueMesh}, {@link
 * MicrovoxelClientState#rebuild}); the actual meshing stays in the coordinator.</p>
 */
public final class MicrovoxelClientLod {
    /** Boundary with a 15% hysteresis dead band. Reviewed every 40 client ticks. */
    private static final int REVIEW_INTERVAL_TICKS = 40;
    /** Far-tier background builds per review: teleports converge over seconds, not one spike. */
    private static final int FAR_BUILDS_PER_REVIEW = 8;
    /** Crosshair hero radius squared: aimed-at volumes within 8 m always render full. */
    private static final double HERO_DISTANCE_SQUARED = 8.0 * 8.0;
    /** Far content edits batch to this cadence instead of rebuilding instantly. */
    private static final int FAR_THROTTLE_TICKS = 20;
    private static final Map<BlockPos, Long> FAR_THROTTLED_REBUILDS = new LinkedHashMap<>();

    private MicrovoxelClientLod() {
    }

    /** Per-tick entry point: periodic tier review plus the far-throttle drain. */
    public static void tick() {
        if (MicrovoxelClientState.tick() % REVIEW_INTERVAL_TICKS == 0) review();
        drain();
    }

    /**
     * Reconciles mesh resolution with camera distance across three tiers. Only flips flags and
     * requeues stale sides; MeshJob coalescing dedups spam. Far-tier builds are budgeted per
     * review so teleports converge over seconds instead of one frame spike.
     */
    public static void review() {
        BlockPos hero = heroPosition();
        int near = 0;
        int mid = 0;
        int far = 0;
        int farBuilds = 0;
        for (MicrovoxelCachedVolume cached : MicrovoxelClientVolumeStore.VOLUMES.values()) {
            double distanceSquared = cameraDistanceSquared(cached.position);
            MicrovoxelLodTiers.Tier want = MicrovoxelLodTiers.wantTier(distanceSquared, cached.lodTier);
            if (cached.volume.hasGeometry() || (hero != null && hero.equals(cached.position))) {
                want = MicrovoxelLodTiers.Tier.NEAR;
            }
            if (want != cached.lodTier) {
                cached.lodTier = want;
                MicrovoxelClientMetrics.inc("lod.flips");
            }
            int revision = cached.volume.revision();
            boolean stale = switch (want) {
                case NEAR -> cached.meshRevision != revision;
                case MID -> cached.midMesh == null || cached.midMeshRevision != revision;
                case FAR -> cached.farMesh == null || cached.farMeshRevision != revision;
            };
            if (stale) {
                if (want == MicrovoxelLodTiers.Tier.FAR && farBuilds >= FAR_BUILDS_PER_REVIEW) {
                    continue;
                }
                if (want == MicrovoxelLodTiers.Tier.FAR) farBuilds++;
                MicrovoxelClientState.enqueueMesh(cached.position);
            }
            switch (want) {
                case NEAR -> near++;
                case MID -> mid++;
                case FAR -> far++;
            }
        }
        MicrovoxelClientMetrics.add("lod.volumes.near",
                near - MicrovoxelClientMetrics.get("lod.volumes.near"));
        MicrovoxelClientMetrics.add("lod.volumes.mid",
                mid - MicrovoxelClientMetrics.get("lod.volumes.mid"));
        MicrovoxelClientMetrics.add("lod.volumes.far",
                far - MicrovoxelClientMetrics.get("lod.volumes.far"));
        MicrovoxelClientMetrics.add("lod.farbuilds", farBuilds);
    }

    /**
     * Crosshair hero: the aimed-at volume inside 8 m always renders full detail, which covers
     * the carving focus block during design without a carver dependency.
     */
    private static BlockPos heroPosition() {
        try {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft == null || minecraft.player == null) return null;
            if (!(minecraft.hitResult instanceof BlockHitResult hit)) return null;
            BlockPos pos = hit.getBlockPos();
            if (cameraDistanceSquared(pos) > HERO_DISTANCE_SQUARED) return null;
            MicrovoxelCachedVolume cached = MicrovoxelClientVolumeStore.VOLUMES.get(pos.immutable());
            return cached == null ? null : pos.immutable();
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    private static double cameraDistanceSquared(BlockPos position) {
        try {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft == null || minecraft.player == null) return 0.0;
            Vec3 camera = minecraft.gameRenderer.getMainCamera().position();
            double dx = position.getX() + 0.5 - camera.x;
            double dy = position.getY() + 0.5 - camera.y;
            double dz = position.getZ() + 0.5 - camera.z;
            return dx * dx + dy * dy + dz * dz;
        } catch (RuntimeException unavailable) {
            return 0.0;
        }
    }

    /**
     * Far content edits batch to one rebuild per second instead of rebuilding instantly: at 72 m+
     * nobody reads a single carved cell the tick it lands. Near and mid volumes rebuild
     * immediately; the drain moves throttled ones when due.
     */
    public static void requestRebuild(BlockPos position) {
        MicrovoxelCachedVolume cached = MicrovoxelClientVolumeStore.VOLUMES.get(position.immutable());
        if (cached != null && cached.lodTier == MicrovoxelLodTiers.Tier.FAR) {
            FAR_THROTTLED_REBUILDS.put(position.immutable(),
                    (long) MicrovoxelClientState.tick() + FAR_THROTTLE_TICKS);
            MicrovoxelClientMetrics.inc("lod.throttled");
            return;
        }
        MicrovoxelClientState.rebuild(position);
    }

    private static void drain() {
        if (FAR_THROTTLED_REBUILDS.isEmpty()) return;
        Iterator<Map.Entry<BlockPos, Long>> iterator = FAR_THROTTLED_REBUILDS.entrySet().iterator();
        long now = MicrovoxelClientState.tick();
        while (iterator.hasNext()) {
            Map.Entry<BlockPos, Long> entry = iterator.next();
            if (entry.getValue() <= now) {
                iterator.remove();
                MicrovoxelClientState.enqueueMesh(entry.getKey());
            }
        }
    }

    public static void clear() {
        FAR_THROTTLED_REBUILDS.clear();
    }
}
