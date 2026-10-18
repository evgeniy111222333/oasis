package ua.rp.chat.carver;

import java.util.List;

/**
 * Strike Alignment core: derives the ideal carving stance from the strike point.
 *
 * <p>Direction of the solve is contact-first: the draft centroid gives the world
 * contact, the flattest draft axis gives the face normal (pointing at the artisan
 * side), and the stand position is projected forward along that normal at
 * arm+tool reach. Callers (autowalk, pose cache, server broadcast) all share this
 * one solver instead of each re-deriving a slightly different stance.</p>
 *
 * <p>Pure and dependency-free (no Minecraft types): cell layout mirrors
 * {@code DraftMask}/{@code MicrovoxelVolume} ({@code x | (z << 4) | (y << 8)}).
 * Safe to unit-test.</p>
 *
 * <p>Mirror contract: duplicated verbatim in the paired module. Keep both copies
 * byte-identical; {@code verifyCarverParity} fails the build on divergence.</p>
 */
public final class CarverStrikeAlign {
    /** Ideal horizontal reach: arms (0.75) + chisel/hammer working length. */
    public static final double IDEAL_STAND_DIST = 1.15;
    /** Minimum reach so the artisan never stands inside the workpiece. */
    public static final double MIN_STAND_DIST = 0.85;
    /** Maximum reach before the hammer visually detaches from the hands. */
    public static final double MAX_STAND_DIST = 1.60;
    /** How much of the side approach is tolerated before the normal penalty bites. */
    public static final double NORMAL_BLEND = 0.15;

    private CarverStrikeAlign() {
    }

    /**
     * One solved stance. All coordinates are absolute world positions.
     * Axis follows {@code CarverWorkAim.faceNormalAxis}: 0=X, 1=Y, 2=Z, -1=empty.
     */
    public record StrikePlan(double contactX, double contactY, double contactZ,
                             double normalX, double normalY, double normalZ,
                             double standX, double standY, double standZ,
                             float standYaw, int axis) {
    }

    /**
     * Solves the stance for a draft. Never returns null: an empty draft falls back
     * to the socket top-center with an up normal so the walk still approaches.
     */
    public static StrikePlan solve(int focusX, int focusY, int focusZ,
                                   List<Integer> cells,
                                   double playerX, double playerY, double playerZ) {
        double[] contactCells = centroidCells(cells);
        int axis = faceNormalAxis(cells);
        double[] contact = contactCells == null
                ? new double[]{focusX + 0.5, focusY + 0.6, focusZ + 0.5}
                : new double[]{focusX + contactCells[0] / 16.0,
                        focusY + contactCells[1] / 16.0,
                        focusZ + contactCells[2] / 16.0};
        double[] normal = normalFor(axis, contact[0], contact[1], contact[2],
                playerX, playerY, playerZ);
        double dist = IDEAL_STAND_DIST;
        double standX = contact[0] + normal[0] * dist;
        double standZ = contact[2] + normal[2] * dist;
        double standY = playerY;
        float yaw = (float) Math.toDegrees(Math.atan2(-(contact[0] - standX), contact[2] - standZ));
        return new StrikePlan(contact[0], contact[1], contact[2],
                normal[0], normal[1], normal[2], standX, standY, standZ, yaw, axis);
    }

    /** Centroid of draft cells in 0..16 volume space, null when empty. Pure. */
    public static double[] centroidCells(List<Integer> cells) {
        if (cells == null || cells.isEmpty()) return null;
        double x = 0.0;
        double y = 0.0;
        double z = 0.0;
        int n = 0;
        for (int cell : cells) {
            if (cell < 0 || cell >= 4096) continue;
            x += (cell & 15) + 0.5;
            y += ((cell >>> 8) & 15) + 0.5;
            z += ((cell >>> 4) & 15) + 0.5;
            n++;
        }
        if (n == 0) return null;
        return new double[]{x / n, y / n, z / n};
    }

    /**
     * Flattest draft axis: the axis with the smallest variance is the face normal.
     * Matches {@code CarverWorkAim.faceNormalAxis} semantics exactly.
     */
    public static int faceNormalAxis(List<Integer> cells) {
        if (cells == null || cells.isEmpty()) return -1;
        double mx = 0.0;
        double my = 0.0;
        double mz = 0.0;
        int n = 0;
        for (int cell : cells) {
            if (cell < 0 || cell >= 4096) continue;
            mx += cell & 15;
            my += (cell >>> 8) & 15;
            mz += (cell >>> 4) & 15;
            n++;
        }
        if (n == 0) return -1;
        mx /= n;
        my /= n;
        mz /= n;
        double vx = 0.0;
        double vy = 0.0;
        double vz = 0.0;
        for (int cell : cells) {
            if (cell < 0 || cell >= 4096) continue;
            double dx = (cell & 15) - mx;
            double dy = ((cell >>> 8) & 15) - my;
            double dz = ((cell >>> 4) & 15) - mz;
            vx += dx * dx;
            vy += dy * dy;
            vz += dz * dz;
        }
        if (vy <= vx && vy <= vz) return 1;
        if (vx <= vz) return 0;
        return 2;
    }

    /**
     * Unit face normal pointing towards the player side. Y drafts read as up;
     * X/Z drafts snap to the horizontal axis facing the artisan.
     */
    public static double[] normalFor(int axis, double contactX, double contactY, double contactZ,
                                     double playerX, double playerY, double playerZ) {
        double nx = 0.0;
        double ny = 1.0;
        double nz = 0.0;
        if (axis == 0) {
            nx = playerX >= contactX ? 1.0 : -1.0;
            ny = 0.0;
        } else if (axis == 2) {
            nz = playerZ >= contactZ ? 1.0 : -1.0;
            ny = 0.0;
        } else if (axis != 1) {
            double dx = playerX - contactX;
            double dz = playerZ - contactZ;
            if (dx * dx + dz * dz > 1.0e-8) {
                double len = Math.sqrt(dx * dx + dz * dz);
                nx = dx / len * 0.35;
                nz = dz / len * 0.35;
                ny = 0.94;
                double inv = 1.0 / Math.sqrt(nx * nx + ny * ny + nz * nz);
                nx *= inv;
                ny *= inv;
                nz *= inv;
            }
        }
        return new double[]{nx, ny, nz};
    }

    /** Yaw (degrees, Minecraft convention) looking from stand towards contact. Pure. */
    public static float yawFor(double standX, double standZ, double contactX, double contactZ) {
        return (float) Math.toDegrees(Math.atan2(-(contactX - standX), contactZ - standZ));
    }

    /** Smallest signed yaw delta in degrees, wrapped to [-180, 180]. Pure. */
    public static float wrapDegrees(float degrees) {
        float wrapped = degrees % 360.0f;
        if (wrapped >= 180.0f) wrapped -= 360.0f;
        if (wrapped < -180.0f) wrapped += 360.0f;
        return wrapped;
    }

    /** Clamps a stand distance into the reachable hammer envelope. Pure. */
    public static double clampDist(double dist) {
        return Math.max(MIN_STAND_DIST, Math.min(MAX_STAND_DIST, dist));
    }
}
