package ua.rp.chat.carver;

import java.util.ArrayList;
import java.util.List;

/**
 * 3D line fill between two picked cells: every volume cell the segment passes
 * through, so diagonal drags paint solid areas instead of dotted paths. A 3D
 * digital differential analyzer over the longest axis, sampling each integer step.
 *
 * <p>Pure and dependency-free: safe to unit-test.</p>
 *
 * <p>Mirror contract: client-only helper, no server copy exists by design.</p>
 */
public final class CarverStrokeLine {
    private CarverStrokeLine() {
    }

    /** All cells on the segment between two picks, endpoints included, in order. */
    public static int[] cellsBetween(int fromCell, int toCell) {
        int x0 = DraftMask.x(fromCell);
        int y0 = DraftMask.y(fromCell);
        int z0 = DraftMask.z(fromCell);
        int x1 = DraftMask.x(toCell);
        int y1 = DraftMask.y(toCell);
        int z1 = DraftMask.z(toCell);
        int steps = Math.max(Math.abs(x1 - x0),
                Math.max(Math.abs(y1 - y0), Math.abs(z1 - z0)));
        if (steps == 0) return new int[]{fromCell};
        List<Integer> cells = new ArrayList<>(steps + 1);
        int previous = -1;
        for (int step = 0; step <= steps; step++) {
            double t = step / (double) steps;
            int cell = DraftMask.index(
                    (int) Math.round(x0 + (x1 - x0) * t),
                    (int) Math.round(y0 + (y1 - y0) * t),
                    (int) Math.round(z0 + (z1 - z0) * t));
            if (cell != previous) {
                cells.add(cell);
                previous = cell;
            }
        }
        int[] result = new int[cells.size()];
        for (int index = 0; index < cells.size(); index++) result[index] = cells.get(index);
        return result;
    }
}
