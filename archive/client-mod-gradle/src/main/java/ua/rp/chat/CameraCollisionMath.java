package ua.rp.chat;

/**
 * Continuous collision math for translating an axis-aligned camera volume.
 * Obstacles are expanded by the camera half extents (Minkowski sum), reducing
 * the sweep to an exact segment-versus-box test.
 */
public final class CameraCollisionMath {
    private static final double AXIS_EPSILON = 1.0e-9;
    private static final double PARAMETER_EPSILON = 1.0e-6;

    private CameraCollisionMath() {
    }

    public static SweepResult sweep(
            Point start,
            Point movement,
            Point halfExtents,
            double clearance,
            Iterable<Box> obstacles) {
        requireFinite(start, "start");
        requireFinite(movement, "movement");
        requireFinite(halfExtents, "halfExtents");
        if (halfExtents.x < 0.0 || halfExtents.y < 0.0 || halfExtents.z < 0.0
                || !Double.isFinite(clearance) || clearance < 0.0) {
            throw new IllegalArgumentException("Camera extents and clearance must be finite and non-negative");
        }

        double allowed = 1.0;
        boolean startBlocked = false;
        int tested = 0;
        for (Box obstacle : obstacles) {
            tested++;
            Box expanded = obstacle.inflate(
                    halfExtents.x + clearance,
                    halfExtents.y + clearance,
                    halfExtents.z + clearance);
            if (expanded.strictlyContains(start, AXIS_EPSILON)) {
                startBlocked = true;
                allowed = 0.0;
                continue;
            }

            double entry = segmentEntry(start, movement, expanded);
            if (Double.isFinite(entry) && entry <= allowed) {
                allowed = Math.max(0.0, entry - PARAMETER_EPSILON);
            }
        }
        return new SweepResult(allowed, startBlocked, tested);
    }

    public static Point bodyCompensation(Point desiredCameraOffset, Point resolvedCameraOffset) {
        requireFinite(desiredCameraOffset, "desiredCameraOffset");
        requireFinite(resolvedCameraOffset, "resolvedCameraOffset");
        return new Point(
                resolvedCameraOffset.x - desiredCameraOffset.x,
                resolvedCameraOffset.y - desiredCameraOffset.y,
                resolvedCameraOffset.z - desiredCameraOffset.z);
    }

    private static double segmentEntry(Point start, Point movement, Box box) {
        double enter = Double.NEGATIVE_INFINITY;
        double exit = Double.POSITIVE_INFINITY;

        double[] starts = {start.x, start.y, start.z};
        double[] deltas = {movement.x, movement.y, movement.z};
        double[] minima = {box.minX, box.minY, box.minZ};
        double[] maxima = {box.maxX, box.maxY, box.maxZ};
        for (int axis = 0; axis < 3; axis++) {
            double delta = deltas[axis];
            if (Math.abs(delta) <= AXIS_EPSILON) {
                if (starts[axis] < minima[axis] || starts[axis] > maxima[axis]) {
                    return Double.POSITIVE_INFINITY;
                }
                continue;
            }
            double first = (minima[axis] - starts[axis]) / delta;
            double second = (maxima[axis] - starts[axis]) / delta;
            double axisEnter = Math.min(first, second);
            double axisExit = Math.max(first, second);
            enter = Math.max(enter, axisEnter);
            exit = Math.min(exit, axisExit);
            if (enter > exit) {
                return Double.POSITIVE_INFINITY;
            }
        }

        // exit <= 0 means the segment starts on a surface and moves away.
        if (exit <= PARAMETER_EPSILON || enter > 1.0 || exit < 0.0) {
            return Double.POSITIVE_INFINITY;
        }
        return Math.max(0.0, enter);
    }

    private static void requireFinite(Point point, String name) {
        if (!Double.isFinite(point.x) || !Double.isFinite(point.y) || !Double.isFinite(point.z)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }

    public record Point(double x, double y, double z) {
        public Point scale(double scale) {
            return new Point(x * scale, y * scale, z * scale);
        }
    }

    public record Box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        public Box {
            if (!(Double.isFinite(minX) && Double.isFinite(minY) && Double.isFinite(minZ)
                    && Double.isFinite(maxX) && Double.isFinite(maxY) && Double.isFinite(maxZ))
                    || minX > maxX || minY > maxY || minZ > maxZ) {
                throw new IllegalArgumentException("Invalid obstacle box");
            }
        }

        public Box inflate(double x, double y, double z) {
            return new Box(minX - x, minY - y, minZ - z, maxX + x, maxY + y, maxZ + z);
        }

        public boolean strictlyContains(Point point, double epsilon) {
            return point.x > minX + epsilon && point.x < maxX - epsilon
                    && point.y > minY + epsilon && point.y < maxY - epsilon
                    && point.z > minZ + epsilon && point.z < maxZ - epsilon;
        }
    }

    public record SweepResult(double fraction, boolean startBlocked, int testedObstacles) {
        public SweepResult {
            if (!Double.isFinite(fraction) || fraction < 0.0 || fraction > 1.0 || testedObstacles < 0) {
                throw new IllegalArgumentException("Invalid sweep result");
            }
        }
    }
}
