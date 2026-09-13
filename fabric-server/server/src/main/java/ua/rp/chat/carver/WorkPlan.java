package ua.rp.chat.carver;

import java.util.List;

/**
 * The cached, exact work plan: every cell the approved draft will carve, in ascending
 * order, plus how far the simulation has executed it. The plan never changes after
 * approval, so interruptions keep a well-defined partial result and the finished block
 * always matches the draft cell-for-cell.
 *
 * <p>Pure and dependency-free: safe to unit-test.</p>
 */
public final class WorkPlan {
    private final List<Integer> cells;
    private final String materialId;
    private final List<Integer> placeCells;
    private final java.util.Map<Integer, String> placeMaterials;
    private int applied;
    private int placeApplied;
    private int removed;
    private int placed;
    private boolean converted;
    private final java.util.Map<String, Integer> removedByMaterial = new java.util.HashMap<>();
    private final java.util.Map<String, Integer> placedByMaterial = new java.util.HashMap<>();

    public WorkPlan(List<Integer> cells, String materialId) {
        this(cells, materialId, List.of(), java.util.Map.of());
    }

    public WorkPlan(List<Integer> cells, String materialId,
                    List<Integer> placeCells, java.util.Map<Integer, String> placeMaterials) {
        this.cells = List.copyOf(cells);
        this.materialId = materialId == null ? "" : materialId;
        this.placeCells = List.copyOf(placeCells);
        this.placeMaterials = java.util.Map.copyOf(placeMaterials);
    }

    public List<Integer> cells() {
        return cells;
    }

    public String materialId() {
        return materialId;
    }

    public List<Integer> placeCells() {
        return placeCells;
    }

    public java.util.Map<Integer, String> placeMaterials() {
        return placeMaterials;
    }

    public String placeMaterial(int cell) {
        return placeMaterials.get(cell);
    }

    public int placeApplied() {
        return placeApplied;
    }

    public void setPlaceApplied(int placeApplied) {
        this.placeApplied = Math.max(0, Math.min(placeApplied, placeCells.size()));
    }

    public int placed() {
        return placed;
    }

    /** Placed cells by material string, so the economy charges the right item. */
    public java.util.Map<String, Integer> placedByMaterial() {
        return java.util.Collections.unmodifiableMap(placedByMaterial);
    }

    public void addPlaced(String material, int count) {
        if (count <= 0) return;
        placed += count;
        String key = material == null || material.isEmpty() ? materialId : material;
        placedByMaterial.merge(key, count, Integer::sum);
    }

    public int applied() {
        return applied;
    }

    public void setApplied(int applied) {
        this.applied = Math.max(0, Math.min(applied, cells.size()));
    }

    public int removed() {
        return removed;
    }

    public void addRemoved(int count) {
        removed += count;
    }

    /** Removed cells by material string, so refunds land in the right item. */
    public java.util.Map<String, Integer> removedByMaterial() {
        return java.util.Collections.unmodifiableMap(removedByMaterial);
    }

    public void addRemoved(String material, int count) {
        if (count <= 0) return;
        removed += count;
        String key = material == null || material.isEmpty() ? materialId : material;
        removedByMaterial.merge(key, count, Integer::sum);
    }

    public boolean converted() {
        return converted;
    }

    public void setConverted(boolean converted) {
        this.converted = converted;
    }

    public int remaining() {
        return (cells.size() - applied) + (placeCells.size() - placeApplied);
    }

    /** Total cells the plan will touch (removals + placements). */
    public int total() {
        return cells.size() + placeCells.size();
    }
}
