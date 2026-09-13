package ua.rp.chat.carver;

/**
 * Face-relative slicing of the 16x16x16 workpiece. Every face owns 16 layers numbered
 * from the viewer: layer 0 is the outer skin on that side, layer 15 the far wall.
 * The grid editor shows one slice {@code (face, layer)}; strokes translate back to
 * absolute volume cells, so the server never learns about faces at all.
 *
 * <p>Row 0 of a slice is always the top of the block (or north for horizontal
 * top-down faces), keeping the drawing orientation stable while orbiting.</p>
 *
 * <p>Pure and dependency-free: safe to unit-test.</p>
 *
 * <p>Mirror contract: client-only helper, no server copy exists by design.</p>
 */
public final class CarverFaceSlicer {
    public enum Face { UP, DOWN, NORTH, SOUTH, WEST, EAST }

    private CarverFaceSlicer() {
    }

    public static int cellFor(Face face, int col, int row, int layer) {
        if (col < 0 || col > 15 || row < 0 || row > 15 || layer < 0 || layer > 15) {
            throw new IndexOutOfBoundsException("Slice coordinate outside 16x16x16 volume");
        }
        return switch (face) {
            case UP -> DraftMask.index(col, 15 - layer, row);
            case DOWN -> DraftMask.index(col, layer, row);
            case NORTH -> DraftMask.index(col, 15 - row, layer);
            case SOUTH -> DraftMask.index(15 - col, 15 - row, 15 - layer);
            case WEST -> DraftMask.index(layer, 15 - row, col);
            case EAST -> DraftMask.index(15 - layer, 15 - row, 15 - col);
        };
    }

    /**
     * Inverse mapping: face-grid coordinates {col, row, layer} of an absolute cell.
     * Round-trips with {@link #cellFor} for every face.
     */
    public static int[] inverse(Face face, int cell) {
        int x = DraftMask.x(cell);
        int y = DraftMask.y(cell);
        int z = DraftMask.z(cell);
        return switch (face) {
            case UP -> new int[]{x, z, 15 - y};
            case DOWN -> new int[]{x, z, y};
            case NORTH -> new int[]{x, 15 - y, z};
            case SOUTH -> new int[]{15 - x, 15 - y, 15 - z};
            case WEST -> new int[]{z, 15 - y, x};
            case EAST -> new int[]{15 - z, 15 - y, 15 - x};
        };
    }

    /** All 256 absolute cells of one slice in row-major order. */
    public static int[] sliceCells(Face face, int layer) {
        int[] cells = new int[256];
        int cursor = 0;
        for (int row = 0; row < 16; row++) {
            for (int col = 0; col < 16; col++) {
                cells[cursor++] = cellFor(face, col, row, layer);
            }
        }
        return cells;
    }

    /**
     * Picks the face the viewer most likely works on from the normalized view
     * direction: the face pointing back at the viewer (steep looks select top/bottom).
     */
    public static Face defaultFace(double lookX, double lookY, double lookZ) {
        double ax = Math.abs(lookX);
        double ay = Math.abs(lookY);
        double az = Math.abs(lookZ);
        if (ay >= ax && ay >= az) {
            return lookY < 0.0 ? Face.UP : Face.DOWN;
        }
        if (ax >= az) {
            return lookX > 0.0 ? Face.WEST : Face.EAST;
        }
        return lookZ > 0.0 ? Face.NORTH : Face.SOUTH;
    }

    public static String labelKey(Face face) {
        return switch (face) {
            case UP -> "face.eclipse.carver_up";
            case DOWN -> "face.eclipse.carver_down";
            case NORTH -> "face.eclipse.carver_north";
            case SOUTH -> "face.eclipse.carver_south";
            case WEST -> "face.eclipse.carver_west";
            case EAST -> "face.eclipse.carver_east";
        };
    }
}
