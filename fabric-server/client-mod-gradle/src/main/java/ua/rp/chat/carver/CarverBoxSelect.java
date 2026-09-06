package ua.rp.chat.carver;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Rectangular cavity enumeration for the box select tool: a grid drag
 * (corner to corner on the active face slice) extruded by depth layers into the
 * block. Corners normalize in any drag direction, depth clips at the far wall
 * instead of failing, and every cell is emitted exactly once.
 *
 * <p>Pure and dependency-free: safe to unit-test.</p>
 *
 * <p>Mirror contract: client-only helper, no server copy exists by design. The
 * server validates box masks exactly like strokes (session, target, cell cap),
 * so rectangularity never needs to cross the wire.</p>
 */
public final class CarverBoxSelect {
    private CarverBoxSelect() {
    }

    /**
     * Absolute volume cells of a box drag. Face and layer follow the open editor
     * slice; depth counts layers inward from it (1 = the slice itself).
     */
    public static int[] cellsFor(CarverFaceSlicer.Face face,
                                 int col0, int row0, int col1, int row1,
                                 int layer, int depth) {
        int fromCol = Math.max(0, Math.min(col0, col1));
        int toCol = Math.min(15, Math.max(col0, col1));
        int fromRow = Math.max(0, Math.min(row0, row1));
        int toRow = Math.min(15, Math.max(row0, row1));
        int fromLayer = Math.max(0, layer);
        int toLayer = Math.min(15, layer + Math.max(1, depth) - 1);
        Set<Integer> cells = new LinkedHashSet<>();
        for (int sliceLayer = fromLayer; sliceLayer <= toLayer; sliceLayer++) {
            for (int row = fromRow; row <= toRow; row++) {
                for (int col = fromCol; col <= toCol; col++) {
                    cells.add(CarverFaceSlicer.cellFor(face, col, row, sliceLayer));
                }
            }
        }
        int[] result = new int[cells.size()];
        int cursor = 0;
        for (int cell : cells) result[cursor++] = cell;
        return result;
    }

    /**
     * Volume-cell bounds of a box drag {x0, y0, z0, x1, y1, z1} with inclusive maxima,
     * resolved through the eight slicer corners without enumerating cells: O(1) per
     * mouse move and orientation-identical to {@link #cellsFor} by construction.
     */
    public static int[] boundsFor(CarverFaceSlicer.Face face,
                                  int col0, int row0, int col1, int row1,
                                  int layer, int depth) {
        int fromCol = Math.max(0, Math.min(col0, col1));
        int toCol = Math.min(15, Math.max(col0, col1));
        int fromRow = Math.max(0, Math.min(row0, row1));
        int toRow = Math.min(15, Math.max(row0, row1));
        int fromLayer = Math.max(0, layer);
        int toLayer = Math.min(15, layer + Math.max(1, depth) - 1);
        int x0 = 16;
        int y0 = 16;
        int z0 = 16;
        int x1 = 0;
        int y1 = 0;
        int z1 = 0;
        int[] cols = {fromCol, toCol};
        int[] rows = {fromRow, toRow};
        int[] layers = {fromLayer, toLayer};
        for (int col : cols) {
            for (int row : rows) {
                for (int sliceLayer : layers) {
                    int cell = CarverFaceSlicer.cellFor(face, col, row, sliceLayer);
                    int x = DraftMask.x(cell);
                    int y = DraftMask.y(cell);
                    int z = DraftMask.z(cell);
                    if (x < x0) x0 = x;
                    if (y < y0) y0 = y;
                    if (z < z0) z0 = z;
                    if (x > x1) x1 = x;
                    if (y > y1) y1 = y;
                    if (z > z1) z1 = z;
                }
            }
        }
        return new int[]{x0, y0, z0, x1, y1, z1};
    }

    /** Cell count of a box drag without materializing it. */
    public static int countFor(int col0, int row0, int col1, int row1, int layer, int depth) {
        int width = Math.min(15, Math.max(col0, col1)) - Math.max(0, Math.min(col0, col1)) + 1;
        int height = Math.min(15, Math.max(row0, row1)) - Math.max(0, Math.min(row0, row1)) + 1;
        int layers = Math.min(15, layer + Math.max(1, depth) - 1) - Math.max(0, layer) + 1;
        if (width <= 0 || height <= 0 || layers <= 0) return 0;
        return width * height * layers;
    }
}
