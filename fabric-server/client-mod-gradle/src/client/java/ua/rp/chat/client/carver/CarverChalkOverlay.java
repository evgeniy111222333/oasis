package ua.rp.chat.client.carver;

import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import ua.rp.chat.carver.CarverChalkQuads;
import ua.rp.chat.carver.CarverFaceSlicer;
import ua.rp.chat.carver.DraftMask;
import ua.rp.chat.microvoxel.MicrovoxelGreedyMesher;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Chalk survey lines on the live hologram: shell marks merged into a handful of
 * white quads per geometric face, the open editor slice in gold, and a gold frame
 * around the focused workpiece. Peel hides the outer skin in front of the working
 * layer; isolation leaves only the working front glowing.
 *
 * <p>Every cuboid tracks the hologram anchor (lift plus the sideways occlusion
 * nudge), so the chalk never drifts off the visible copy.</p>
 *
 * <p>Merging is the expensive half of this overlay, so merged rectangles are cached
 * and rebuilt only when the draft content or the view selection changes. Per-frame
 * work is then just the gizmo submission, which keeps painting at full frame rate
 * even on dense drafts.</p>
 */
public final class CarverChalkOverlay {
    private static final int MAX_RECTS_PER_FRAME = 2048;
    private static final int MAX_SURFACE_FRAMES = 256;
    private static final double PAD = 0.002;
    private static final double SURFACE_PAD = 0.004;
    private static final GizmoStyle CHALK = GizmoStyle.stroke(0xFFFFFFFF);
    private static final GizmoStyle FRONT = GizmoStyle.stroke(0xFFFFD27A);
    private static final GizmoStyle FRAME = GizmoStyle.stroke(0xFFE3C099);
    private static final GizmoStyle SURFACE = GizmoStyle.stroke(0xFFFFF2B0);

    private CarverChalkOverlay() {
    }

    /** Merged shell rectangles per face from the last draft that needed a rebuild. */
    private static final Map<CarverFaceSlicer.Face, List<CarverChalkQuads.Rect>> SHELL_CACHE =
            new EnumMap<>(CarverFaceSlicer.Face.class);
    private static List<CarverChalkQuads.Rect> frontCache = List.of();
    private static long cacheFingerprint = 0L;
    private static CarverFaceSlicer.Face cacheFace;
    private static int cacheLayer;
    private static boolean cachePeel;
    private static boolean cacheIsolate;
    private static boolean cacheCarved;

    /** Rebuild counter for tests: counts actual merge passes, never frame submits. */
    static long mergePasses;
    /** Frame diagnostics: invocations and submitted rects since session start. */
    static long frames;
    static long submitted;

    public static void render() {
        if (!CarverClientState.designing()) {
            if (CarverClientState.working() && CarverClientState.focus() != null) {
                CarverContactFx.renderMarker(CarverClientState.focus());
            }
            renderObserved();
            return;
        }
        BlockPos focus = CarverClientState.focus();
        if (focus == null) return;
        frames++;
        if (frames == 1L) {
            CarverPerfLog.chalkAlive();
        }
        double lift = CarverHologram.visualLift();
        double offX = CarverHologram.offsetX();
        double offZ = CarverHologram.offsetZ();
        int wireDrawn = drawSurfaceWire(focus, lift, offX, offZ);
        submitted += wireDrawn;
        if (wireDrawn == 0) {
            double[] occupied = occupiedBounds(focus);
            double frameX0 = occupied == null ? 0.0 : occupied[0] / 16.0;
            double frameY0 = occupied == null ? 0.0 : occupied[1] / 16.0;
            double frameZ0 = occupied == null ? 0.0 : occupied[2] / 16.0;
            double frameX1 = occupied == null ? 1.0 : occupied[3] / 16.0;
            double frameY1 = occupied == null ? 1.0 : occupied[4] / 16.0;
            double frameZ1 = occupied == null ? 1.0 : occupied[5] / 16.0;
            Gizmos.cuboid(new AABB(
                    focus.getX() + offX + frameX0 - PAD, focus.getY() + lift + frameY0 - PAD,
                    focus.getZ() + offZ + frameZ0 - PAD,
                    focus.getX() + offX + frameX1 + PAD, focus.getY() + lift + frameY1 + PAD,
                    focus.getZ() + offZ + frameZ1 + PAD),
                    FRAME);
        }
        if (CarverClientState.hasPendingBox()) {
            int[] pending = CarverClientState.pendingBox();
            Gizmos.cuboid(new AABB(
                    focus.getX() + offX + pending[0] / 16.0 - PAD,
                    focus.getY() + lift + pending[1] / 16.0 - PAD,
                    focus.getZ() + offZ + pending[2] / 16.0 - PAD,
                    focus.getX() + offX + (pending[3] + 1) / 16.0 + PAD,
                    focus.getY() + lift + (pending[4] + 1) / 16.0 + PAD,
                    focus.getZ() + offZ + (pending[5] + 1) / 16.0 + PAD), FRONT);
        }
        submitted += drawLivePreview(focus, lift, offX, offZ);
        DraftMask draft = CarverClientState.draft();
        if (draft.isEmpty()) return;
        CarverFaceSlicer.Face face = CarverClientState.viewFace();
        int layer = CarverClientState.viewLayer();
        boolean peel = CarverClientState.peelOuterLayers();
        boolean isolate = CarverClientState.isolateFace();
        rebuildCacheIfStale(draft, face, layer, peel, isolate, paintableFilter(draft));
        int drawn = 0;
        boolean hiding = !draft.isEmpty();
        if (!isolate) {
            for (CarverFaceSlicer.Face shell : CarverFaceSlicer.Face.values()) {
                if (drawn >= MAX_RECTS_PER_FRAME) break;
                if (peel && shell == face) continue;
                List<CarverChalkQuads.Rect> rects = SHELL_CACHE.get(shell);
                if (rects == null) continue;
                for (CarverChalkQuads.Rect rect : rects) {
                    if (drawn >= MAX_RECTS_PER_FRAME) break;
                    if (hiding && rectHidden(face, shell, rect, layer, draft)) continue;
                    boxOnFace(focus, lift, offX, offZ, shell, rect);
                    drawn++;
                }
            }
        }
        for (CarverChalkQuads.Rect rect : frontCache) {
            if (drawn >= MAX_RECTS_PER_FRAME) break;
            int[] bounds = CarverChalkQuads.rectCells(face, layer, rect);
            if (hiding && ua.rp.chat.carver.CarverChalkQuads.cellsCleared(
                    bounds[0], bounds[1], bounds[2],
                    bounds[3] - 1, bounds[4] - 1, bounds[5] - 1, draft)) continue;
            Gizmos.cuboid(new AABB(
                    focus.getX() + offX + bounds[0] / 16.0 - PAD,
                    focus.getY() + lift + bounds[1] / 16.0 - PAD,
                    focus.getZ() + offZ + bounds[2] / 16.0 - PAD,
                    focus.getX() + offX + bounds[3] / 16.0 + PAD,
                    focus.getY() + lift + bounds[4] / 16.0 + PAD,
                    focus.getZ() + offZ + bounds[5] / 16.0 + PAD), FRONT);
            drawn++;
        }
        submitted += drawn;
    }

    /**
     * Wireframe of the simulated removal: thin outlines only around drafted faces,
     * so intact surface stays clean while the pending cut reads live. Reuses the
     * hologram display cache, hence no extra meshing.
     */
    private static int drawSurfaceWire(BlockPos focus, double lift, double offX, double offZ) {
        if (focus == null || !CarverHologram.active()) return 0;
        BlockState state = CarverHologram.displayState();
        if (state == null) return 0;
        CarverHologramRenderer.DisplayMesh display;
        try {
            display = CarverHologramRenderer.displayMesh(focus, state);
        } catch (RuntimeException unreadable) {
            return 0;
        }
        return submitSurfaceFrames(focus, lift, offX, offZ,
                CarverChalkQuads.largestFirst(display.ghost(), MAX_SURFACE_FRAMES), SURFACE);
    }

    private static int submitSurfaceFrames(BlockPos focus, double lift, double offX, double offZ,
                                           java.util.List<MicrovoxelGreedyMesher.Face> faces,
                                           GizmoStyle style) {
        int drawn = 0;
        for (MicrovoxelGreedyMesher.Face face : faces) {
            double[] bounds = CarverChalkQuads.surfaceFrameBounds(face, lift, offX, offZ);
            Gizmos.cuboid(new AABB(
                    focus.getX() + bounds[0] - SURFACE_PAD,
                    focus.getY() + bounds[1] - SURFACE_PAD,
                    focus.getZ() + bounds[2] - SURFACE_PAD,
                    focus.getX() + bounds[3] + SURFACE_PAD,
                    focus.getY() + bounds[4] + SURFACE_PAD,
                    focus.getZ() + bounds[5] + SURFACE_PAD), style);
            drawn++;
        }
        return drawn;
    }

    /**
     * Chalk of nearby artisans for observers: a dim frame plus the merged shell
     * marks of each live draft, drawn on the untouched block. No slice UI here;
     * observers track the shape, not the editor selection.
     */
    private static final int MAX_OBSERVED_DRAFTS = 8;

    private record ObservedShell(long fingerprint,
                                 Map<CarverFaceSlicer.Face, List<CarverChalkQuads.Rect>> shell) {
    }

    private static final Map<BlockPos, ObservedShell> OBSERVED_SHELL = new HashMap<>();

    private static void renderObserved() {
        var entries = CarverClientState.observedDrafts();
        if (entries.isEmpty()) {
            if (!OBSERVED_SHELL.isEmpty()) OBSERVED_SHELL.clear();
            return;
        }
        frames++;
        Set<BlockPos> live = new HashSet<>();
        int shown = 0;
        for (ObservedDraftBoard.Entry entry : entries) {
            if (shown >= MAX_OBSERVED_DRAFTS) break;
            BlockPos focus = entry.focus();
            DraftMask mask = entry.mask();
            if (focus == null || mask == null || mask.isEmpty()) continue;
            live.add(focus.immutable());
            Gizmos.cuboid(new AABB(
                    focus.getX() - PAD, focus.getY() - PAD, focus.getZ() - PAD,
                    focus.getX() + 1.0 + PAD, focus.getY() + 1.0 + PAD,
                    focus.getZ() + 1.0 + PAD), FRAME);
            int drawn = 1;
            for (CarverFaceSlicer.Face shell : CarverFaceSlicer.Face.values()) {
                if (drawn >= MAX_RECTS_PER_FRAME) break;
                List<CarverChalkQuads.Rect> rects = observedShell(entry, shell);
                if (rects == null) continue;
                for (CarverChalkQuads.Rect rect : rects) {
                    if (drawn >= MAX_RECTS_PER_FRAME) break;
                    boxOnFace(focus, 0.0, 0.0, 0.0, shell, rect);
                    drawn++;
                }
            }
            submitted += drawn;
            shown++;
        }
        OBSERVED_SHELL.keySet().retainAll(live);
    }

    private static List<CarverChalkQuads.Rect> observedShell(
            ObservedDraftBoard.Entry entry, CarverFaceSlicer.Face shell) {
        ObservedShell cached = OBSERVED_SHELL.get(entry.focus());
        if (cached != null && cached.fingerprint() == entry.fingerprint()) {
            return cached.shell().get(shell);
        }
        mergePasses++;
        Map<CarverFaceSlicer.Face, List<CarverChalkQuads.Rect>> built =
                new EnumMap<>(CarverFaceSlicer.Face.class);
        boolean carved = hasCarvedVolumeAt(entry.focus());
        for (CarverFaceSlicer.Face face : CarverFaceSlicer.Face.values()) {
            built.put(face, CarverChalkQuads.merge(paintableGrid(
                    CarverChalkQuads.faceMask(entry.mask(), face),
                    (col, row) -> CarverFaceSlicer.cellFor(face, col, row, 0),
                    entry.focus(), carved)));
        }
        OBSERVED_SHELL.put(entry.focus().immutable(),
                new ObservedShell(entry.fingerprint(), built));
        return built.get(shell);
    }

    private static boolean hasCarvedVolumeAt(BlockPos focus) {
        try {
            var cached = ua.rp.chat.client.microvoxel.MicrovoxelClientState.get(focus);
            return cached != null && cached.volume != null;
        } catch (RuntimeException unreadable) {
            return false;
        }
    }

    /**
     * Live preview of the unflushed input: pending box cells or the current
     * stroke, so the artisan sees exactly what release will mark. Read-only;
     * the authoritative draft changes only on flush.
     */
    private static final int MAX_PREVIEW_CELLS = 512;

    private static int drawLivePreview(BlockPos focus, double lift, double offX, double offZ) {
        List<Integer> cells;
        if (CarverClientState.hasPendingBox()) {
            cells = CarverChalkQuads.previewCells(CarverClientState.pendingBox(), MAX_PREVIEW_CELLS);
        } else {
            DraftMask preview = CarverDesignScreen.livePreviewMask();
            if (preview.isEmpty()) return 0;
            List<Integer> all = preview.cells();
            cells = all.size() <= MAX_PREVIEW_CELLS ? all : all.subList(0, MAX_PREVIEW_CELLS);
        }
        int drawn = 0;
        for (int cell : cells) {
            int x = DraftMask.x(cell);
            int y = DraftMask.y(cell);
            int z = DraftMask.z(cell);
            Gizmos.cuboid(new AABB(
                    focus.getX() + offX + x / 16.0 - PAD,
                    focus.getY() + lift + y / 16.0 - PAD,
                    focus.getZ() + offZ + z / 16.0 - PAD,
                    focus.getX() + offX + (x + 1) / 16.0 + PAD,
                    focus.getY() + lift + (y + 1) / 16.0 + PAD,
                    focus.getZ() + offZ + (z + 1) / 16.0 + PAD), FRONT);
            drawn++;
        }
        return drawn;
    }

    /** Chalk over a fully hidden face would float on air: skip it with the face. */
    private static boolean rectHidden(CarverFaceSlicer.Face viewFace, CarverFaceSlicer.Face shell,
                                      CarverChalkQuads.Rect rect, int layer, DraftMask draft) {
        int[] bounds = CarverChalkQuads.faceRectBounds(shell, rect);
        return ua.rp.chat.carver.CarverChalkQuads.cellsCleared(
                bounds[0], bounds[1], bounds[2],
                bounds[3] - 1, bounds[4] - 1, bounds[5] - 1, draft);
    }

    /**
     * Re-merges shell and slice rectangles only when the fingerprint of the draft
     * content or the view selection changed. The fingerprint is one cheap read pass
     * over the mask; a merge pass walks every face grid greedily, so skipping it on
     * idle frames is the whole optimization.
     */
    private static void rebuildCacheIfStale(DraftMask draft, CarverFaceSlicer.Face face,
                                            int layer, boolean peel, boolean isolate,
                                            boolean carved) {
        long fingerprint = fingerprint(draft);
        if (fingerprint == cacheFingerprint && face == cacheFace && layer == cacheLayer
                && peel == cachePeel && isolate == cacheIsolate && carved == cacheCarved
                && !SHELL_CACHE.isEmpty()) {
            return;
        }
        mergePasses++;
        long mergeStart = System.nanoTime();
        SHELL_CACHE.clear();
        for (CarverFaceSlicer.Face shell : CarverFaceSlicer.Face.values()) {
            SHELL_CACHE.put(shell, CarverChalkQuads.merge(
                    paintableGrid(CarverChalkQuads.faceMask(draft, shell),
                            (col, row) -> CarverFaceSlicer.cellFor(shell, col, row, 0),
                            carved)));
        }
        int[] sliceCells = CarverFaceSlicer.sliceCells(face, layer);
        frontCache = CarverChalkQuads.merge(
                paintableGrid(CarverChalkQuads.sliceMask(draft, face, layer),
                        (col, row) -> sliceCells[row * 16 + col], carved));
        CarverPerfLog.merge(System.nanoTime() - mergeStart);
        cacheFingerprint = fingerprint;
        cacheFace = face;
        cacheLayer = layer;
        cachePeel = peel;
        cacheIsolate = isolate;
        cacheCarved = carved;
    }

    /** Drops unpaintable cells from a face grid on re-entered carvings. */
    private interface CellAt {
        int cell(int col, int row);
    }

    private static boolean[] paintableGrid(boolean[] grid, CellAt at, boolean carved) {
        return paintableGrid(grid, at, CarverClientState.focus(), carved);
    }

    private static boolean[] paintableGrid(boolean[] grid, CellAt at,
                                           BlockPos focus, boolean carved) {
        if (!carved) return grid;
        for (int row = 0; row < 16; row++) {
            for (int col = 0; col < 16; col++) {
                int index = row * 16 + col;
                if (grid[index] && !CarverClientState.isPaintable(focus, at.cell(col, row))) {
                    grid[index] = false;
                }
            }
        }
        return grid;
    }

    /** True while the focus carries a carved volume (paintability applies). */
    private static boolean paintableFilter(DraftMask draft) {
        try {
            BlockPos focus = CarverClientState.focus();
            if (focus == null) return false;
            var cached = ua.rp.chat.client.microvoxel.MicrovoxelClientState.get(focus);
            return cached != null && cached.volume != null;
        } catch (RuntimeException unreadable) {
            return false;
        }
    }

    /**
     * Change fingerprint of the draft, delegated to the pure chalk helper so the
     * overlay frame loop and the unit tests share one definition by construction.
     */
    static long fingerprint(DraftMask draft) {
        return CarverChalkQuads.draftFingerprint(draft);
    }

    private static BlockPos boundsFocus;
    private static int boundsRevision = -1;
    private static double[] boundsCache;

    /**
     * Occupied bounds of the carved volume in 1/16 units {x0,y0,z0,x1,y1,z1} with
     * exclusive maxima, so the frame hugs what is really there. Null for fresh
     * sockets (full cube). Cached per volume revision: one 4096 scan per edit.
     */
    static double[] occupiedBounds(BlockPos focus) {
        try {
            var cached = ua.rp.chat.client.microvoxel.MicrovoxelClientState.get(focus);
            if (cached == null || cached.volume == null) {
                boundsFocus = null;
                return null;
            }
            int revision = cached.volume.revision();
            if (boundsCache != null && focus.equals(boundsFocus) && revision == boundsRevision) {
                return boundsCache;
            }
            int x0 = 16;
            int y0 = 16;
            int z0 = 16;
            int x1 = -1;
            int y1 = -1;
            int z1 = -1;
            for (int cell = 0; cell < ua.rp.chat.microvoxel.MicrovoxelVolume.CELL_COUNT; cell++) {
                if (!cached.volume.occupied(cell)) continue;
                int x = ua.rp.chat.microvoxel.MicrovoxelVolume.x(cell);
                int y = ua.rp.chat.microvoxel.MicrovoxelVolume.y(cell);
                int z = ua.rp.chat.microvoxel.MicrovoxelVolume.z(cell);
                if (x < x0) x0 = x;
                if (y < y0) y0 = y;
                if (z < z0) z0 = z;
                if (x > x1) x1 = x;
                if (y > y1) y1 = y;
                if (z > z1) z1 = z;
            }
            if (x1 < 0) {
                boundsFocus = null;
                return null;
            }
            boundsCache = new double[]{x0, y0, z0, x1 + 1, y1 + 1, z1 + 1};
            boundsFocus = focus.immutable();
            boundsRevision = revision;
            return boundsCache;
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    /** Drops the merge cache; called implicitly by content change, exposed for tests. */
    static void invalidateCache() {
        SHELL_CACHE.clear();
        frontCache = List.of();
        cacheFingerprint = 0L;
        cacheFace = null;
        frames = 0L;
        submitted = 0L;
    }

    private static void boxOnFace(BlockPos focus, double lift, double offX, double offZ,
                                  CarverFaceSlicer.Face face,
                                  CarverChalkQuads.Rect rect) {
        int[] bounds = CarverChalkQuads.faceRectBounds(face, rect);
        Gizmos.cuboid(new AABB(
                focus.getX() + offX + bounds[0] / 16.0 - PAD,
                focus.getY() + lift + bounds[1] / 16.0 - PAD,
                focus.getZ() + offZ + bounds[2] / 16.0 - PAD,
                focus.getX() + offX + bounds[3] / 16.0 + PAD,
                focus.getY() + lift + bounds[4] / 16.0 + PAD,
                focus.getZ() + offZ + bounds[5] / 16.0 + PAD), CHALK);
    }
}
