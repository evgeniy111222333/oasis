package ua.rp.chat.carver;

import ua.rp.chat.microvoxel.MicrovoxelGreedyMesher;

import java.util.ArrayList;
import java.util.List;

/**
 * Greedy-merged chalk rectangles: instead of one gizmo cuboid per marked cell (up to
 * 4096 submissions), shell and slice masks collapse into a handful of non-overlapping
 * rectangles covering exactly the same cells. Same picture, two orders of magnitude
 * fewer submissions, no GPU instancing pipeline required.
 *
 * <p>Pure and dependency-free: safe to unit-test.</p>
 *
 * <p>Mirror contract: client-only helper, no server copy exists by design.</p>
 */
public final class CarverChalkQuads {
    /** Inclusive-min, exclusive-max bounds in grid units. */
    public record Rect(int x0, int y0, int x1, int y1) {
    }

    private CarverChalkQuads() {
    }

    /**
     * Greedy-merges a 16x16 row-major occupancy grid into non-overlapping rectangles
     * covering exactly the occupied set: widest run first, then tallest extension.
     */
    public static List<Rect> merge(boolean[] occupied) {
        if (occupied == null || occupied.length != 256) {
            throw new IllegalArgumentException("Chalk grid must hold exactly 256 cells");
        }
        boolean[][] used = new boolean[16][16];
        List<Rect> rects = new ArrayList<>();
        for (int row = 0; row < 16; row++) {
            for (int col = 0; col < 16; col++) {
                if (!occupied[row * 16 + col] || used[row][col]) continue;
                int width = 1;
                while (col + width < 16 && occupied[row * 16 + col + width]
                        && !used[row][col + width]) {
                    width++;
                }
                int height = 1;
                outer: while (row + height < 16) {
                    for (int x = col; x < col + width; x++) {
                        if (!occupied[(row + height) * 16 + x] || used[row + height][x]) {
                            break outer;
                        }
                    }
                    height++;
                }
                for (int y = row; y < row + height; y++) {
                    for (int x = col; x < col + width; x++) used[y][x] = true;
                }
                rects.add(new Rect(col, row, col + width, row + height));
            }
        }
        return List.copyOf(rects);
    }

    /**
     * True when every cell of the inclusive box is marked in the draft: such faces
     * hide live on the hologram and skip chalk, so a stroke reads as done the
     * moment it lands. Pure.
     */
    public static boolean cellsCleared(int x0, int y0, int z0, int x1, int y1, int z1,
                                       DraftMask mask) {
        if (mask == null || mask.isEmpty()) return false;
        for (int y = y0; y <= y1; y++) {
            for (int z = z0; z <= z1; z++) {
                for (int x = x0; x <= x1; x++) {
                    if (!mask.get(DraftMask.index(x, y, z))) return false;
                }
            }
        }
        return true;
    }

    public static boolean cellsClearedFace(MicrovoxelGreedyMesher.Face face, DraftMask mask) {
        if (mask == null || mask.isEmpty()) return false;
        int x0 = face.minX();
        int y0 = face.minY();
        int z0 = face.minZ();
        int x1 = face.maxX() - 1;
        int y1 = face.maxY() - 1;
        int z1 = face.maxZ() - 1;
        switch (face.direction()) {
            case UP -> y0 = y1;
            case DOWN -> y1 = y0;
            case SOUTH -> z0 = z1;
            case NORTH -> z1 = z0;
            case EAST -> x0 = x1;
            case WEST -> x1 = x0;
        }
        return cellsCleared(x0, y0, z0, x1, y1, z1, mask);
    }

    /**
     * Change fingerprint of a draft: one linear pass over set cells mixed into the
     * count. Counts alone miss same-size edits; a single read pass is still far
     * cheaper than the greedy merge over six face grids, so the overlay uses this to
     * skip merge passes on idle frames without ever missing a stroke.
     */
    public static long draftFingerprint(DraftMask mask) {
        long hash = 0x9E3779B97F4A7C15L ^ mask.count();
        for (int cell : mask.cells()) {
            hash = hash * 31 + cell;
        }
        return hash;
    }

    /** Row-major occupancy of the marked shell on one geometric face. */
    public static boolean[] faceMask(DraftMask mask, CarverFaceSlicer.Face face) {
        boolean[] grid = new boolean[256];
        for (int row = 0; row < 16; row++) {
            for (int col = 0; col < 16; col++) {
                grid[row * 16 + col] = mask.get(faceCell(face, col, row));
            }
        }
        return grid;
    }

    /** Row-major occupancy of the marked cells on the open editor slice. */
    public static boolean[] sliceMask(DraftMask mask, CarverFaceSlicer.Face face, int layer) {
        int[] cells = CarverFaceSlicer.sliceCells(face, layer);
        boolean[] grid = new boolean[256];
        for (int index = 0; index < 256; index++) {
            grid[index] = mask.get(cells[index]);
        }
        return grid;
    }

    /**
     * Shell cell under a face-grid position. Delegates to the slicer at layer 0, so the
     * chalk grid and the editor grid share one orientation by construction (mirrored
     * SOUTH/EAST included).
     */
    private static int faceCell(CarverFaceSlicer.Face face, int col, int row) {
        return CarverFaceSlicer.cellFor(face, col, row, 0);
    }

    /**
     * Volume-cell bounds {x0, y0, z0, x1, y1, z1} (exclusive maxima) of a shell
     * rectangle, resolved through the slicer corners so orientation always matches.
     */
    public static int[] faceRectBounds(CarverFaceSlicer.Face face, Rect rect) {
        int x0 = 16;
        int y0 = 16;
        int z0 = 16;
        int x1 = 0;
        int y1 = 0;
        int z1 = 0;
        int[] cols = {rect.x0(), rect.x1() - 1};
        int[] rows = {rect.y0(), rect.y1() - 1};
        for (int col : cols) {
            for (int row : rows) {
                int cell = CarverFaceSlicer.cellFor(face, col, row, 0);
                int x = DraftMask.x(cell);
                int y = DraftMask.y(cell);
                int z = DraftMask.z(cell);
                if (x < x0) x0 = x;
                if (y < y0) y0 = y;
                if (z < z0) z0 = z;
                if (x + 1 > x1) x1 = x + 1;
                if (y + 1 > y1) y1 = y + 1;
                if (z + 1 > z1) z1 = z + 1;
            }
        }
        return new int[]{x0, y0, z0, x1, y1, z1};
    }

    /**
     * World-space bounds of one greedy mesh face in block units, already shifted by
     * the hologram lift and sideways nudge. Flat axes keep zero thickness here; the
     * caller pads both sides so the outline stays visible.
     */
    public static double[] surfaceFrameBounds(MicrovoxelGreedyMesher.Face face,
                                              double lift, double offX, double offZ) {
        return new double[]{
                face.minX() / 16.0 + offX, face.minY() / 16.0 + lift, face.minZ() / 16.0 + offZ,
                face.maxX() / 16.0 + offX, face.maxY() / 16.0 + lift, face.maxZ() / 16.0 + offZ};
    }

    /**
     * Faces ordered by descending surface area, capped at {@code limit}. Keeps the
     * overlay frame budget on the largest patches when dense carvings spike the count.
     */
    public static List<MicrovoxelGreedyMesher.Face> largestFirst(
            List<MicrovoxelGreedyMesher.Face> faces, int limit) {
        if (faces == null || faces.isEmpty() || limit <= 0) return List.of();
        List<MicrovoxelGreedyMesher.Face> ordered = new ArrayList<>(faces);
        ordered.sort((left, right) -> Double.compare(faceArea(right), faceArea(left)));
        return List.copyOf(ordered.subList(0, Math.min(limit, ordered.size())));
    }

    private static double faceArea(MicrovoxelGreedyMesher.Face face) {
        double dx = face.maxX() - face.minX();
        double dy = face.maxY() - face.minY();
        double dz = face.maxZ() - face.minZ();
        return dx * dy + dy * dz + dz * dx;
    }

    /**
     * Volume cells inside inclusive bounds, capped at {@code cap}. Out-of-range
     * coordinates are skipped instead of failing, so live previews never crash
     * on a half-built drag.
     */
    public static List<Integer> previewCells(int[] bounds, int cap) {
        if (bounds == null || bounds.length != 6 || cap <= 0) return List.of();
        List<Integer> cells = new ArrayList<>();
        outer:
        for (int y = bounds[1]; y <= bounds[4]; y++) {
            for (int z = bounds[2]; z <= bounds[5]; z++) {
                for (int x = bounds[0]; x <= bounds[3]; x++) {
                    if (x < 0 || x >= DraftMask.RESOLUTION
                            || y < 0 || y >= DraftMask.RESOLUTION
                            || z < 0 || z >= DraftMask.RESOLUTION) continue;
                    if (cells.size() >= cap) break outer;
                    cells.add(DraftMask.index(x, y, z));
                }
            }
        }
        return List.copyOf(cells);
    }

    /**
     * Volume-cell bounds of a slice rectangle: {x0, y0, z0, x1, y1, z1} with exclusive
     * maxima, ready to scale by 1/16 into world space.
     */
    public static int[] rectCells(CarverFaceSlicer.Face face, int layer, Rect rect) {
        int x0 = 16;
        int y0 = 16;
        int z0 = 16;
        int x1 = 0;
        int y1 = 0;
        int z1 = 0;
        for (int row = rect.y0(); row < rect.y1(); row++) {
            for (int col = rect.x0(); col < rect.x1(); col++) {
                int cell = CarverFaceSlicer.cellFor(face, col, row, layer);
                int x = DraftMask.x(cell);
                int y = DraftMask.y(cell);
                int z = DraftMask.z(cell);
                if (x < x0) x0 = x;
                if (y < y0) y0 = y;
                if (z < z0) z0 = z;
                if (x + 1 > x1) x1 = x + 1;
                if (y + 1 > y1) y1 = y + 1;
                if (z + 1 > z1) z1 = z + 1;
            }
        }
        return new int[]{x0, y0, z0, x1, y1, z1};
    }
}
