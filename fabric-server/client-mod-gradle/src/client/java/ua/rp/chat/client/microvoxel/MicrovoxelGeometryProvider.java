package ua.rp.chat.client.microvoxel;

import net.minecraft.core.BlockPos;
import ua.rp.chat.microvoxel.MicrovoxelGreedyMesher;

import java.util.List;

/**
 * Seam between volume state and terrain rendering. The section model consumes only this
 * interface — never the state store directly — so the mesh backend can migrate (CPU greedy
 * meshes today, GPU-resident Baker buffers tomorrow) without touching compilation, networking
 * or prediction code.
 *
 * <p>All methods are safe to call from terrain compilation workers. Returned face lists are
 * immutable snapshots; implementations must never hand out a live mutable mesh.</p>
 */
public interface MicrovoxelGeometryProvider {
    /**
     * Greedy faces for one volume, LOD-selected for the current camera distance.
     * Returns an empty list for unknown positions (the caller then reports a missing volume).
     */
    List<MicrovoxelGreedyMesher.Face> meshFor(BlockPos position);

    /** Material render flags for one volume (opaque fast path vs translucent fallback). */
    int renderFlagsFor(BlockPos position);

    /** Current revision of one volume, or {@code Integer.MIN_VALUE} when unknown. */
    int revisionOf(BlockPos position);

    /** Fluid revision for one volume, or {@code Integer.MIN_VALUE} when dry/unknown. */
    int fluidRevisionOf(BlockPos position);
}
