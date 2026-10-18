package ua.rp.chat.client.microvoxel;

import net.minecraft.core.BlockPos;
import ua.rp.chat.microvoxel.MicrovoxelCrack;
import ua.rp.chat.microvoxel.MicrovoxelVolume;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-cell mining crack overlay state, extracted from {@link MicrovoxelClientState}.
 *
 * <p>Owns the authoritative crack map and its monotonic revision counter. Writes happen on the
 * client thread from MINE_STAGE frames; terrain compilation workers read the same concurrent maps
 * through {@code cellsAt}/{@code revisionOf}. Mutation methods report whether a real transition
 * happened so the caller (the client facade) can queue the mesh/section rebuilds — this class has
 * no dependency on the scheduling subsystem.</p>
 */
public final class MicrovoxelClientCracks {
    /**
     * Feature kill-switch for the per-cell mining crack overlay. Default on; the launch flag
     * {@code -Drpchat.microvoxel.crackOverlay=false} both stops advertising the capability in
     * HELLO and disables rendering, so a broken overlay can be killed without a rebuild.
     */
    private static final boolean OVERLAY_ENABLED =
            !"false".equalsIgnoreCase(System.getProperty("rpchat.microvoxel.crackOverlay", "true"));

    private static final Map<BlockPos, Map<Integer, Integer>> CRACKS = new ConcurrentHashMap<>();
    private static final Map<BlockPos, Integer> REVISIONS = new ConcurrentHashMap<>();
    private static int revisionCounter;

    private MicrovoxelClientCracks() {
    }

    public static boolean overlayEnabled() {
        return OVERLAY_ENABLED;
    }

    /** Terrain-worker view of the active cracks on a volume, as {@code {cell, stage}} pairs. */
    public static List<int[]> cellsAt(BlockPos position) {
        Map<Integer, Integer> cells = CRACKS.get(position);
        if (cells == null || cells.isEmpty()) return List.of();
        List<int[]> out = new ArrayList<>(cells.size());
        for (Map.Entry<Integer, Integer> entry : cells.entrySet()) {
            out.add(new int[]{entry.getKey(), entry.getValue()});
        }
        return out;
    }

    /** True when a volume shows at least one crack (forces the translucent render pass). */
    public static boolean hasCracks(BlockPos position) {
        return CRACKS.containsKey(position);
    }

    /** Monotonic per-volume crack revision for the section geometry key (0 when none). */
    public static int revisionOf(BlockPos position) {
        Integer revision = REVISIONS.get(position);
        return revision == null ? 0 : revision;
    }

    /**
     * Applies one authoritative MINE_STAGE frame. Only a real transition bumps the crack revision,
     * so a steady stage costs nothing on the render path. Returns true when the caller must
     * rebuild.
     */
    public static boolean apply(BlockPos position, int cell, int stage) {
        if (!OVERLAY_ENABLED) return false;
        Map<Integer, Integer> cells = CRACKS.computeIfAbsent(
                position, ignored -> new ConcurrentHashMap<>());
        boolean changed = MicrovoxelCrack.apply(cells, cell, stage);
        if (cells.isEmpty()) CRACKS.remove(position, cells);
        if (!changed) return false;
        REVISIONS.put(position, ++revisionCounter);
        return true;
    }

    /** Drops cracks whose cells no longer exist in an authoritative volume; true when changed. */
    public static boolean prune(BlockPos position, MicrovoxelVolume authoritative) {
        Map<Integer, Integer> cells = CRACKS.get(position);
        if (cells == null) return false;
        boolean changed = false;
        for (Integer cell : List.copyOf(cells.keySet())) {
            if (cell == null || cell < 0 || cell >= MicrovoxelVolume.CELL_COUNT
                    || !authoritative.occupied(cell)) {
                cells.remove(cell);
                changed = true;
            }
        }
        if (cells.isEmpty()) CRACKS.remove(position, cells);
        if (!changed) return false;
        REVISIONS.put(position, ++revisionCounter);
        return true;
    }

    /** Clears all crack state for a volume; true when anything was removed. */
    public static boolean clear(BlockPos position) {
        // Both maps are always consulted: the old short-circuit left a stale revision behind when
        // only the crack map held an entry.
        boolean removed = CRACKS.remove(position) != null;
        removed |= REVISIONS.remove(position) != null;
        return removed;
    }

    /** Drops every crack and revision (full volume teardown). */
    public static void clearAll() {
        CRACKS.clear();
        REVISIONS.clear();
    }
}
