package ua.rp.chat.client.microvoxel;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import ua.rp.chat.client.EclipseClientMod;
import ua.rp.chat.microvoxel.MicrovoxelGreedyMesher;
import ua.rp.chat.microvoxel.MicrovoxelVolume;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * One client-side cached volume: the authoritative/predicted {@link MicrovoxelVolume}, its
 * near/mid/far meshes, its collision shape and its render/light snapshots. Extracted from
 * {@link MicrovoxelClientState} so the volume record is no longer a nested god-class detail and
 * can be referenced directly by the renderer, section model and mixins.
 *
 * <p>Field writes happen on the client thread; the mesh/shape snapshots are immutable and read by
 * terrain workers, so the payloads are safe to hand across threads.</p>
 */
public final class MicrovoxelCachedVolume {
    private static final ExecutorService SHAPE_EXECUTOR =
            Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "Microvoxel-Shape-Generator");
                thread.setDaemon(true);
                return thread;
            });

    public final BlockPos position;
    public volatile MicrovoxelVolume volume;
    private final VoxelShape fallback;
    private volatile VoxelShape shape;
    private volatile int shapeRevision;
    private volatile boolean shapeGenerating = false;
    public volatile List<MicrovoxelGreedyMesher.Face> mesh = List.of();
    /** Revision the near (stride-1) mesh was built for; 0 means never built. */
    volatile int meshRevision;
    /** Stride-2 LOD mesh for mid-range viewing, with its own revision gate. */
    volatile List<MicrovoxelGreedyMesher.Face> midMesh;
    volatile int midMeshRevision = Integer.MIN_VALUE;
    /** Stride-4 silhouette mesh for far viewing, with its own revision gate. */
    volatile List<MicrovoxelGreedyMesher.Face> farMesh;
    volatile int farMeshRevision = Integer.MIN_VALUE;
    /** Display tier owned by the LOD review; rebuilds compile for this stride. */
    volatile ua.rp.chat.microvoxel.MicrovoxelLodTiers.Tier lodTier =
            ua.rp.chat.microvoxel.MicrovoxelLodTiers.Tier.NEAR;
    /** GPU render-pass flags snapshot, refreshed on every displayed volume install. */
    volatile int renderFlags = MicrovoxelSectionModel.GENERAL_MATERIAL_FLAGS;
    /** Light-seal snapshot twin of renderFlags: drives explicit engine rechecks on flips. */
    volatile boolean lightSealed;
    /** Cached light-engine parent state with its revision gate (null = transparent). */
    volatile net.minecraft.world.level.block.state.BlockState lightParent;
    volatile int lightParentRevision = Integer.MIN_VALUE;
    private net.minecraft.world.level.block.state.BlockState baseBlockState;

    MicrovoxelCachedVolume(BlockPos position, MicrovoxelVolume volume, VoxelShape fallback) {
        this.position = position.immutable();
        this.volume = volume;
        this.fallback = fallback;
    }

    synchronized void updateVolume(MicrovoxelVolume replacement) {
        this.volume = replacement;
        this.baseBlockState = null;
        if (!shapeGenerating) triggerAsyncShapeGen();
    }

    public net.minecraft.world.level.block.state.BlockState getBaseBlockState() {
        if (baseBlockState == null) {
            String matStr = null;
            for (int cell = 0; cell < MicrovoxelVolume.CELL_COUNT; cell++) {
                if (volume.occupied(cell)) {
                    matStr = volume.material(cell);
                    break;
                }
            }
            if (matStr != null && !matStr.isEmpty()) {
                baseBlockState = parseBlockState(matStr);
            } else {
                baseBlockState = net.minecraft.world.level.block.Blocks.STONE.defaultBlockState();
            }
        }
        return baseBlockState;
    }

    static net.minecraft.world.level.block.state.BlockState parseBlockState(String value) {
        return MicrovoxelSectionModel.parseBlockState(value);
    }

    public VoxelShape getShape() {
        VoxelShape s = shape;
        if (s == null || shapeRevision != volume.revision()) {
            triggerAsyncShapeGen();
            if (s == null) return fallback != null ? fallback : Shapes.block();
        }
        return s;
    }

    private synchronized void triggerAsyncShapeGen() {
        if (shapeGenerating || shapeRevision == volume.revision()) return;
        shapeGenerating = true;
        MicrovoxelVolume snapshot = volume.copy();
        int requestedRevision = snapshot.revision();
        SHAPE_EXECUTOR.submit(() -> {
            try {
                long start = System.nanoTime();
                VoxelShape newShape = MicrovoxelClientShapes.build(snapshot);
                long durationNs = System.nanoTime() - start;
                Minecraft.getInstance().execute(() -> {
                    if (volume.revision() == requestedRevision) {
                        this.shape = newShape;
                        this.shapeRevision = requestedRevision;
                    }
                    shapeGenerating = false;
                    if (shapeRevision != volume.revision()) triggerAsyncShapeGen();
                });
                EclipseClientMod.LOGGER.info(
                        "[MICROVOXEL-PERF] ASYNC_SHAPE_GEN pos={} | Duration: {}us | Backend: {}",
                        position.toShortString(), String.format("%.2f", durationNs / 1000.0),
                        snapshot.collisionPlan().backend());
            } catch (Throwable t) {
                EclipseClientMod.LOGGER.error("Failed to generate async shape", t);
                Minecraft.getInstance().execute(() -> shapeGenerating = false);
            }
        });
    }
}
