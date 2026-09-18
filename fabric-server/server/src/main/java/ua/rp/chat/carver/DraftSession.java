package ua.rp.chat.carver;

/**
 * Pure state machine of one Carver drafting session.
 *
 * <p>Lifecycle: {@code IDLE -> DESIGN -> WORK -> DONE}, with {@code CANCELLED} reachable
 * from {@code DESIGN} and {@code WORK}. The session owns the authoritative removal mask,
 * a bounded pre-commit undo/redo stack over mask snapshots, the mirror axes and both
 * tick budgets; the manager only feeds it validated events. Tick counters are advanced
 * explicitly so the logic stays unit-testable without a Minecraft server.</p>
 */
public final class DraftSession {
    public enum State { IDLE, DESIGN, WORK, DONE, CANCELLED }

    public enum CancelReason {
        NONE,
        TIMEOUT,
        MOVED,
        DAMAGED,
        BLOCK_CHANGED,
        LOGOUT,
        NO_STAMINA,
        EMPTY_DRAFT,
        PLAYER_REQUEST
    }

    private State state = State.IDLE;
    private CancelReason cancelReason = CancelReason.NONE;
    private int blockX;
    private int blockY;
    private int blockZ;
    public static final int MAX_HISTORY = 50;
    /** Mirror bit 0 mirrors X, bit 1 mirrors Z around the volume center. */
    public static final int MIRROR_X = 1;
    public static final int MIRROR_Z = 2;

    private String materialId = "";
    private double materialMultiplier = 1.0;
    /** Cells the approved draft removes. */
    private final DraftMask mask = new DraftMask();
    /** Cells the approved draft places; each carries its own material. */
    private final DraftMask placeMask = new DraftMask();
    private final java.util.LinkedHashMap<Integer, String> placeMaterials = new java.util.LinkedHashMap<>();
    private int mirrorAxes;
    private final java.util.Deque<Snapshot> undoStack = new java.util.ArrayDeque<>();
    private final java.util.Deque<Snapshot> redoStack = new java.util.ArrayDeque<>();

    /** Immutable draft state used by the pre-commit undo/redo stack. */
    private record Snapshot(DraftMask remove, DraftMask place,
                            java.util.Map<Integer, String> materials) {
    }
    private int designTicks;
    private int designTimeoutTicks;
    private int workTotalTicks;
    private int workDoneTicks;
    /**
     * The artisan walks up to the workpiece itself: the design leash is suspended
     * (movement is the point), the design clock keeps running. Cleared by approval,
     * cancellation and fresh design entries.
     */
    private boolean autowalk;

    public State state() {
        return state;
    }

    public CancelReason cancelReason() {
        return cancelReason;
    }

    public int blockX() {
        return blockX;
    }

    public int blockY() {
        return blockY;
    }

    public int blockZ() {
        return blockZ;
    }

    public String materialId() {
        return materialId;
    }

    public double materialMultiplier() {
        return materialMultiplier;
    }

    public void setMaterialMultiplier(double multiplier) {
        if (multiplier > 0.0 && Double.isFinite(multiplier)) {
            materialMultiplier = multiplier;
        }
    }

    public DraftMask mask() {
        return mask;
    }

    public DraftMask placeMask() {
        return placeMask;
    }

    public java.util.Map<Integer, String> placeMaterials() {
        return java.util.Collections.unmodifiableMap(placeMaterials);
    }

    public String placeMaterial(int cell) {
        return placeMaterials.get(cell);
    }

    /** True when the draft would actually change the workpiece (remove or place). */
    public boolean hasContent() {
        return !mask.isEmpty() || !placeMask.isEmpty();
    }

    public int mirrorAxes() {
        return mirrorAxes;
    }

    public void setMirrorAxes(int axes) {
        mirrorAxes = axes & (MIRROR_X | MIRROR_Z);
    }

    /** Mirrors one cell around the volume center (15-x / 15-z, never fixed). */
    public static int mirrorCell(int cell, int axes) {
        int x = DraftMask.x(cell);
        int y = DraftMask.y(cell);
        int z = DraftMask.z(cell);
        if ((axes & MIRROR_X) != 0) x = 15 - x;
        if ((axes & MIRROR_Z) != 0) z = 15 - z;
        return DraftMask.index(x, y, z);
    }

    /**
     * Unions every mirrored twin of the mask into itself: per-axis twins plus the
     * diagonal twin when both axes are set (one cell becomes up to four). Returns
     * newly added cells.
     */
    public static int expandMirrored(DraftMask mask, int axes) {
        axes &= MIRROR_X | MIRROR_Z;
        if (axes == 0) return 0;
        DraftMask twins = new DraftMask();
        for (int cell : mask.cells()) {
            if ((axes & MIRROR_X) != 0) twins.set(mirrorCell(cell, MIRROR_X));
            if ((axes & MIRROR_Z) != 0) twins.set(mirrorCell(cell, MIRROR_Z));
            if (axes == (MIRROR_X | MIRROR_Z)) twins.set(mirrorCell(cell, axes));
        }
        return mask.orIn(twins);
    }

    /**
     * Snapshots the mask before a draft mutation. Any new mutation invalidates the
     * redo branch; the stack holds at most {@link #MAX_HISTORY} snapshots.
     */
    public void pushHistory() {
        undoStack.push(snapshot());
        while (undoStack.size() > MAX_HISTORY) undoStack.removeLast();
        redoStack.clear();
    }

    private Snapshot snapshot() {
        return new Snapshot(mask.copy(), placeMask.copy(),
                new java.util.LinkedHashMap<>(placeMaterials));
    }

    private void restore(Snapshot snapshot) {
        mask.clearAll();
        mask.orIn(snapshot.remove());
        placeMask.clearAll();
        placeMask.orIn(snapshot.place());
        placeMaterials.clear();
        placeMaterials.putAll(snapshot.materials());
    }

    /**
     * Draft removal marking: {@code add=true} marks cells (and clears any placement there),
     * {@code add=false} unmarks them. Mirror expansion is applied to the affected cells.
     */
    public void markRemove(DraftMask cells, int mirrorAxes, boolean add) {
        DraftMask work = cells.copy();
        expandMirrored(work, mirrorAxes);
        if (add) {
            mask.orIn(work);
            for (int cell : work.cells()) {
                placeMask.clear(cell);
                placeMaterials.remove(cell);
            }
        } else {
            mask.andNot(work);
        }
    }

    /**
     * Draft placement marking: {@code add=true} marks cells to be filled with the chosen
     * material (and clears any removal there), {@code add=false} unmarks them.
     */
    public void markPlace(DraftMask cells, int mirrorAxes, String material, boolean add) {
        DraftMask work = cells.copy();
        expandMirrored(work, mirrorAxes);
        if (add) {
            placeMask.orIn(work);
            String resolved = ua.rp.chat.microvoxel.MicrovoxelMaterialPalette
                    .canonicalMaterial(material);
            for (int cell : work.cells()) {
                mask.clear(cell);
                placeMaterials.put(cell, resolved);
            }
        } else {
            placeMask.andNot(work);
            for (int cell : work.cells()) {
                placeMaterials.remove(cell);
            }
        }
    }

    public void clearDraft() {
        mask.clearAll();
        placeMask.clearAll();
        placeMaterials.clear();
    }

    /** Loads a saved placement mask (blueprint) verbatim, bypassing mirror expansion. */
    public void loadPlace(DraftMask place, java.util.Map<Integer, String> materials) {
        placeMask.clearAll();
        if (place != null) placeMask.orIn(place);
        placeMaterials.clear();
        if (materials != null) {
            for (java.util.Map.Entry<Integer, String> entry : materials.entrySet()) {
                placeMaterials.put(entry.getKey(),
                        ua.rp.chat.microvoxel.MicrovoxelMaterialPalette
                                .canonicalMaterial(entry.getValue()));
            }
        }
    }

    /** Restores the previous snapshot; returns false when there is nothing to undo. */
    public boolean undo() {
        if (undoStack.isEmpty()) return false;
        redoStack.push(snapshot());
        restore(undoStack.pop());
        return true;
    }

    /** Reapplies an undone snapshot; returns false when there is nothing to redo. */
    public boolean redo() {
        if (redoStack.isEmpty()) return false;
        undoStack.push(snapshot());
        restore(redoStack.pop());
        return true;
    }

    public int undoDepth() {
        return undoStack.size();
    }

    public int redoDepth() {
        return redoStack.size();
    }

    public int designTicks() {
        return designTicks;
    }

    public int workTotalTicks() {
        return workTotalTicks;
    }

    public int workDoneTicks() {
        return workDoneTicks;
    }

    public boolean autowalk() {
        return autowalk;
    }

    public void setAutowalk(boolean autowalk) {
        if (state == State.DESIGN) {
            this.autowalk = autowalk;
        }
    }

    public double workProgress() {
        return DraftEstimate.progress(workDoneTicks, workTotalTicks);
    }

    public boolean beginDesign(int x, int y, int z, String material, int timeoutTicks) {
        if (state == State.DESIGN || state == State.WORK) return false;
        state = State.DESIGN;
        cancelReason = CancelReason.NONE;
        blockX = x;
        blockY = y;
        blockZ = z;
        materialId = material == null ? "" : material;
        materialMultiplier = 1.0;
        mask.clearAll();
        placeMask.clearAll();
        placeMaterials.clear();
        mirrorAxes = 0;
        undoStack.clear();
        redoStack.clear();
        designTicks = 0;
        designTimeoutTicks = Math.max(1, timeoutTicks);
        workTotalTicks = 0;
        workDoneTicks = 0;
        autowalk = false;
        return true;
    }

    public boolean targets(int x, int y, int z) {
        return (state == State.DESIGN || state == State.WORK)
                && blockX == x && blockY == y && blockZ == z;
    }

    /** Advances the design clock; returns true when it just timed out. */
    public boolean tickDesign() {
        if (state != State.DESIGN) return false;
        designTicks++;
        if (designTicks >= designTimeoutTicks) {
            cancel(CancelReason.TIMEOUT);
            return true;
        }
        return false;
    }

    public boolean approve(int workTicks) {
        if (state != State.DESIGN || !hasContent() || workTicks <= 0) return false;
        state = State.WORK;
        workTotalTicks = workTicks;
        workDoneTicks = 0;
        autowalk = false;
        return true;
    }

    /** Advances the work clock; returns true when the work just finished. */
    public boolean tickWork() {
        if (state != State.WORK) return false;
        workDoneTicks++;
        if (workDoneTicks >= workTotalTicks) {
            state = State.DONE;
            return true;
        }
        return false;
    }

    public boolean cancel(CancelReason reason) {
        if (state != State.DESIGN && state != State.WORK) return false;
        state = State.CANCELLED;
        cancelReason = reason == null ? CancelReason.NONE : reason;
        return true;
    }

    public void reset() {
        state = State.IDLE;
        cancelReason = CancelReason.NONE;
        mask.clearAll();
        placeMask.clearAll();
        placeMaterials.clear();
        mirrorAxes = 0;
        undoStack.clear();
        redoStack.clear();
        designTicks = 0;
        workTotalTicks = 0;
        workDoneTicks = 0;
    }
}
