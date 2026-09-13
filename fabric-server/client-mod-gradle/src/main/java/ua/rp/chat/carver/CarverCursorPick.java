package ua.rp.chat.carver;

/**
 * Direct 3D picking of microvoxel cells through the mouse cursor: builds the view ray
 * from the orbit camera pose and clips it against the focused unit cube, returning
 * the exact surface cell and geometric face under the pointer. No volume data is
 * needed because design targets are still solid full cubes.
 *
 * <p>Angles follow the Minecraft convention (yaw 0 faces +Z, pitch positive looks
 * down), identical to {@link CarverCameraMath}.</p>
 *
 * <p>Pure and dependency-free: safe to unit-test.</p>
 *
 * <p>Mirror contract: client-only helper, no server copy exists by design.</p>
 */
public final class CarverCursorPick {
    public record Hit(int cell, CarverFaceSlicer.Face face, double distance) {
    }

    private CarverCursorPick() {
    }

    /**
     * Picks a cell of the unit cube at (bx, baseY, bz). The base Y accepts the
     * hologram lift, so painting tracks the floating copy while cell ids stay
     * volume-relative. Returns null when the ray misses or starts inside.
     */
    public static Hit pick(double camX, double camY, double camZ,
                           float yawDeg, float pitchDeg, double fovDeg,
                           int screenW, int screenH, double mouseX, double mouseY,
                           int bx, double baseY, int bz) {
        return pick(camX, camY, camZ, yawDeg, pitchDeg, fovDeg, screenW, screenH,
                mouseX, mouseY, bx, baseY, bz, 0.0, 0.0);
    }

    /**
     * Picks a cell of the unit cube whose min corner sits at
     * (bx + offsetX, baseY, bz + offsetZ). The offsets carry the hologram occlusion
     * nudge, so the hitbox follows the copy sideways exactly like it follows the
     * lift vertically, while cell ids stay volume-relative.
     * Returns null when the ray misses or starts inside.
     */
    public static Hit pick(double camX, double camY, double camZ,
                           float yawDeg, float pitchDeg, double fovDeg,
                           int screenW, int screenH, double mouseX, double mouseY,
                           int bx, double baseY, int bz, double offsetX, double offsetZ) {
        double[] ray = ray(camX, camY, camZ, yawDeg, pitchDeg, fovDeg,
                screenW, screenH, mouseX, mouseY);
        if (ray == null) return null;
        return slab(ray[0], ray[1], ray[2], ray[3], ray[4], ray[5],
                bx + offsetX, baseY, bz + offsetZ);
    }

    /**
     * View ray for the cursor in world space: {ox, oy, oz, dx, dy, dz}, or null
     * when the pointer ray cannot be built. Shared by cube picking and volume
     * raycasting so both resolve the same pixel.
     */
    public static double[] ray(double camX, double camY, double camZ,
                               float yawDeg, float pitchDeg, double fovDeg,
                               int screenW, int screenH, double mouseX, double mouseY) {
        if (screenW <= 0 || screenH <= 0) return null;
        double yaw = Math.toRadians(yawDeg);
        double pitch = Math.toRadians(pitchDeg);
        double cosPitch = Math.cos(pitch);
        double fx = -Math.sin(yaw) * cosPitch;
        double fy = -Math.sin(pitch);
        double fz = Math.cos(yaw) * cosPitch;
        double[] right = normalize(cross(fx, fy, fz, 0.0, 1.0, 0.0));
        if (right == null) {
            right = new double[]{-Math.cos(yaw), 0.0, -Math.sin(yaw)};
        }
        double[] up = cross(right[0], right[1], right[2], fx, fy, fz);
        double tanV = Math.tan(Math.toRadians(fovDeg) / 2.0);
        if (!(tanV > 0.0) || !Double.isFinite(tanV)) return null;
        double aspect = (double) screenW / screenH;
        double ndcX = 2.0 * mouseX / screenW - 1.0;
        double ndcY = 1.0 - 2.0 * mouseY / screenH;
        double dx = fx + right[0] * ndcX * tanV * aspect + up[0] * ndcY * tanV;
        double dy = fy + right[1] * ndcX * tanV * aspect + up[1] * ndcY * tanV;
        double dz = fz + right[2] * ndcX * tanV * aspect + up[2] * ndcY * tanV;
        double[] dir = normalize(new double[]{dx, dy, dz});
        if (dir == null) return null;
        return new double[]{camX, camY, camZ, dir[0], dir[1], dir[2]};
    }

    private static Hit slab(double ox, double oy, double oz,
                            double dx, double dy, double dz,
                            double bx, double baseY, double bz) {
        double by = baseY;
        double tmin = 0.0;
        double tmax = Double.MAX_VALUE;
        int axis = -1;
        double[] o = {ox - bx, oy - by, oz - bz};
        double[] d = {dx, dy, dz};
        for (int i = 0; i < 3; i++) {
            if (Math.abs(d[i]) < 1.0e-12) {
                if (o[i] < 0.0 || o[i] > 1.0) return null;
                continue;
            }
            double t0 = (0.0 - o[i]) / d[i];
            double t1 = (1.0 - o[i]) / d[i];
            double near = Math.min(t0, t1);
            double far = Math.max(t0, t1);
            if (near > tmin) {
                tmin = near;
                axis = i;
            }
            if (far < tmax) tmax = far;
            if (tmin > tmax) return null;
        }
        if (axis < 0 || tmin < 0.0) return null;
        double px = ox + dx * tmin;
        double py = oy + dy * tmin;
        double pz = oz + dz * tmin;
        CarverFaceSlicer.Face face = switch (axis) {
            case 0 -> dx > 0.0 ? CarverFaceSlicer.Face.WEST : CarverFaceSlicer.Face.EAST;
            case 1 -> dy > 0.0 ? CarverFaceSlicer.Face.DOWN : CarverFaceSlicer.Face.UP;
            default -> dz > 0.0 ? CarverFaceSlicer.Face.NORTH : CarverFaceSlicer.Face.SOUTH;
        };
        int cx = clampCell((int) Math.floor((px - bx) * 16.0));
        int cy = clampCell((int) Math.floor((py - by) * 16.0));
        int cz = clampCell((int) Math.floor((pz - bz) * 16.0));
        return new Hit(DraftMask.index(cx, cy, cz), face, tmin);
    }

    private static int clampCell(int value) {
        return Math.max(0, Math.min(15, value));
    }

    private static double[] cross(double ax, double ay, double az,
                                  double bx, double by, double bz) {
        return new double[]{ay * bz - az * by, az * bx - ax * bz, ax * by - ay * bx};
    }

    private static double[] normalize(double[] v) {
        double length = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        if (!(length > 1.0e-12)) return null;
        return new double[]{v[0] / length, v[1] / length, v[2] / length};
    }
}
