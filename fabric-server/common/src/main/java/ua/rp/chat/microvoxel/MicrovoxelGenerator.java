package ua.rp.chat.microvoxel;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure, deterministic structure generators ("authoring beats manual placement"). A generator turns
 * a small parameter set into a list of shaped-cell placements relative to an anchor volume cell.
 * The server applies the result as one authoritative transaction, exactly like a brush stroke, so
 * generators inherit every Eclipse guarantee (permissions, protection, undo, delta sync) for free.
 *
 * <p>This is where the geometry channel pays off against a manual voxel editor: a player asks for
 * "a 45° roof run" or "a round column" and gets correct shapes without hand-placing each cell.</p>
 *
 * <p>Pure: no Minecraft classes, fully unit-testable.</p>
 */
public final class MicrovoxelGenerator {
    /** Placed structures. Ordinal is the wire code; append only. */
    public enum Type {
        /** A continuous 45° ramp: {@code length} cells along the facing, {@code width} across. */
        RAMP,
        /** A round column: a 2x2 quarter-round footprint stacked {@code height} cells high. */
        COLUMN,
        /** A gable roof: two ramp runs meeting at a ridge along the facing axis. */
        ROOF
    }

    public static final int MAX_LENGTH = 16;
    public static final int MAX_WIDTH = 16;
    public static final int MAX_HEIGHT = 16;

    /** One shaped cell relative to the anchor cell. {@code facing}/position are in cells. */
    public record Placement(int dx, int dy, int dz, int shapeId) {
    }

    private MicrovoxelGenerator() {
    }

    public static int typeCount() {
        return Type.values().length;
    }

    /**
     * Expands a structure into shaped-cell placements relative to the anchor cell.
     *
     * <p>Parameters are used per type: {@code length} and {@code width} drive RAMP and ROOF, while
     * {@code height} drives COLUMN (RAMP/ROOF ignore {@code height}, RAMP/COLUMN ignore
     * {@code width}). Unused parameters are clamped but otherwise have no effect.</p>
     *
     * @param facing 0 = +Z, 1 = -Z, 2 = +X, 3 = -X (a ramp ascends along this axis; a roof ridge
     *               runs along it).
     */
    public static List<Placement> generate(Type type, int length, int width, int height, int facing) {
        int len = clamp(length, 1, MAX_LENGTH);
        int wide = clamp(width, 1, MAX_WIDTH);
        int tall = clamp(height, 1, MAX_HEIGHT);
        int face = Math.floorMod(facing, 4);
        return switch (type) {
            case RAMP -> ramp(len, wide, face);
            case COLUMN -> column(tall);
            case ROOF -> roof(len, wide, face);
        };
    }

    private static List<Placement> ramp(int length, int width, int facing) {
        int shape = rampShape(facing);
        List<Placement> out = new ArrayList<>(length * width);
        for (int along = 0; along < length; along++) {
            for (int across = 0; across < width; across++) {
                int dx = facing == 2 ? along : facing == 3 ? -along : across;
                int dz = facing == 0 ? along : facing == 1 ? -along : across;
                // Each ramp cell already rises one full cell over its own span, so the next cell
                // along the run must sit one cell higher to stitch into a continuous 45° slope.
                out.add(new Placement(dx, along, dz, shape));
            }
        }
        return out;
    }

    /**
     * A round column: a 2x2 footprint of X-Z quarter discs stacked {@code height} cells high. Each
     * quarter disc is centred on the shared 2x2 corner, so the four cells form one vertical circle
     * of diameter 1/8 block.
     */
    private static List<Placement> column(int height) {
        int northEast = MicrovoxelShape.Type.QUARTER_ROUND_XZ_NE.ordinal();
        int northWest = MicrovoxelShape.Type.QUARTER_ROUND_XZ_NW.ordinal();
        int southEast = MicrovoxelShape.Type.QUARTER_ROUND_XZ_SE.ordinal();
        int southWest = MicrovoxelShape.Type.QUARTER_ROUND_XZ_SW.ordinal();
        List<Placement> out = new ArrayList<>(height * 4);
        for (int y = 0; y < height; y++) {
            out.add(new Placement(0, y, 0, northEast));
            out.add(new Placement(1, y, 0, northWest));
            out.add(new Placement(0, y, 1, southEast));
            out.add(new Placement(1, y, 1, southWest));
        }
        return out;
    }

    /**
     * A gable roof: two 45° runs rising to a ridge. The ridge runs along the {@code facing} axis
     * (Z for 0/1, X for 2/3), so the two slopes run on the perpendicular axis; each cell's height
     * follows a symmetric tent so a real ridge (not a flat plateau) forms.
     */
    private static List<Placement> roof(int length, int width, int facing) {
        boolean ridgeAlongX = facing == 2 || facing == 3;
        List<Placement> out = new ArrayList<>(length * width);
        int half = width / 2;
        for (int along = 0; along < length; along++) {
            for (int across = 0; across < width; across++) {
                boolean ascending = across < half || (width % 2 == 1 && across == half);
                int dy = Math.min(across, width - 1 - across);
                int dx;
                int dz;
                int shape;
                if (ridgeAlongX) {
                    dx = facing == 2 ? along : -along;
                    dz = across;
                    shape = (ascending ? MicrovoxelShape.Type.RAMP_S
                            : MicrovoxelShape.Type.RAMP_N).ordinal();
                } else {
                    dx = across;
                    dz = facing == 0 ? along : -along;
                    shape = (ascending ? MicrovoxelShape.Type.RAMP_E
                            : MicrovoxelShape.Type.RAMP_W).ordinal();
                }
                out.add(new Placement(dx, dy, dz, shape));
            }
        }
        return out;
    }

    /** The ramp shape whose surface ascends toward the given facing. */
    public static int rampShape(int facing) {
        return switch (Math.floorMod(facing, 4)) {
            case 0 -> MicrovoxelShape.Type.RAMP_S.ordinal();
            case 1 -> MicrovoxelShape.Type.RAMP_N.ordinal();
            case 2 -> MicrovoxelShape.Type.RAMP_E.ordinal();
            default -> MicrovoxelShape.Type.RAMP_W.ordinal();
        };
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
