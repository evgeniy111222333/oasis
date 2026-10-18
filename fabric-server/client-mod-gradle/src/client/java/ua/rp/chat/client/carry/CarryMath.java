package ua.rp.chat.client.carry;

/** Shared by target selection, the placement preview and headless regression tests. */
public final class CarryMath {
    private CarryMath() {}
    public static int snapYaw(float yaw) {
        if (!Float.isFinite(yaw)) return 0;
        return Math.floorMod(Math.round(yaw / 90) * 90, 360);
    }
    public static org.joml.Quaternionf previewRotation(float yaw) {
        // Fixed native displays rotate by -entityYRot in world coordinates.
        return new org.joml.Quaternionf().rotationY((float) Math.toRadians(-snapYaw(yaw)));
    }
    public static double rayBox(double x, double y, double z, double dx, double dy, double dz,
                                double minX, double minY, double minZ,
                                double maxX, double maxY, double maxZ, double limit) {
        double near = 0, far = limit;
        double[] origin = {x, y, z}, ray = {dx, dy, dz};
        double[] minimum = {minX, minY, minZ}, maximum = {maxX, maxY, maxZ};
        for (int axis = 0; axis < 3; axis++) {
            if (Math.abs(ray[axis]) < 1e-10) {
                if (origin[axis] < minimum[axis] || origin[axis] > maximum[axis]) return Double.POSITIVE_INFINITY;
            } else {
                double a = (minimum[axis] - origin[axis]) / ray[axis];
                double b = (maximum[axis] - origin[axis]) / ray[axis];
                near = Math.max(near, Math.min(a, b));
                far = Math.min(far, Math.max(a, b));
                if (near > far) return Double.POSITIVE_INFINITY;
            }
        }
        return near >= 0 && near <= limit ? near : Double.POSITIVE_INFINITY;
    }
}
