package ua.rp.chat;

/** Двухзвенная кинематика рук для жёсткого двуручного хвата молота. */
public final class HeavyHammerGripSolver {
    public static final float UPPER_LENGTH = ArticulatedLimbLayout.ARM_ELBOW_Y;
    public static final float LOWER_LENGTH = ArticulatedLimbLayout.ARM_HAND_Y
            - ArticulatedLimbLayout.ARM_ELBOW_Y;
    private static final float MIN_REACH = Math.abs(LOWER_LENGTH - UPPER_LENGTH) + 0.02f;
    private static final float MAX_REACH = UPPER_LENGTH + LOWER_LENGTH - 0.02f;

    private HeavyHammerGripSolver() {
    }

    public static Solution solve(Point shoulder, Point requestedTarget) {
        float dx = requestedTarget.x - shoulder.x;
        float dy = requestedTarget.y - shoulder.y;
        float dz = requestedTarget.z - shoulder.z;
        float distance = length(dx, dy, dz);
        if (distance < 0.0001f) {
            dy = MIN_REACH;
            distance = MIN_REACH;
        }

        float reachableDistance = clamp(distance, MIN_REACH, MAX_REACH);
        float scale = reachableDistance / distance;
        dx *= scale;
        dy *= scale;
        dz *= scale;
        Point target = new Point(shoulder.x + dx, shoulder.y + dy, shoulder.z + dz);

        // Поворот Z задаёт поперечную плоскость руки, X решает обычную двухзвенную цепь в этой плоскости.
        float upperZ = (float) Math.atan2(-dx, dy);
        float planarY = (float) Math.sqrt(dx * dx + dy * dy);
        float elbowCos = clamp((reachableDistance * reachableDistance - UPPER_LENGTH * UPPER_LENGTH
                - LOWER_LENGTH * LOWER_LENGTH) / (2.0f * UPPER_LENGTH * LOWER_LENGTH), -1.0f, 1.0f);
        float lowerX = (float) Math.acos(elbowCos);
        float targetX = (float) Math.atan2(dz, planarY);
        float triangle = (float) Math.atan2(LOWER_LENGTH * Math.sin(lowerX),
                UPPER_LENGTH + LOWER_LENGTH * Math.cos(lowerX));
        float upperX = targetX - triangle;
        return new Solution(upperX, upperZ, lowerX, target, Math.abs(distance - reachableDistance));
    }

    public static Point hand(Point shoulder, float upperX, float upperZ, float lowerX) {
        float firstY = UPPER_LENGTH * (float) Math.cos(upperX);
        float firstZ = UPPER_LENGTH * (float) Math.sin(upperX);
        float secondAngle = upperX + lowerX;
        float secondY = LOWER_LENGTH * (float) Math.cos(secondAngle);
        float secondZ = LOWER_LENGTH * (float) Math.sin(secondAngle);
        float planarY = firstY + secondY;
        float x = -planarY * (float) Math.sin(upperZ);
        float y = planarY * (float) Math.cos(upperZ);
        return new Point(shoulder.x + x, shoulder.y + y, shoulder.z + firstZ + secondZ);
    }

    private static float length(float x, float y, float z) {
        return (float) Math.sqrt(x * x + y * y + z * z);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    public record Point(float x, float y, float z) {
        public Point add(float offsetX, float offsetY, float offsetZ) {
            return new Point(x + offsetX, y + offsetY, z + offsetZ);
        }

        public float distanceTo(Point other) {
            return length(x - other.x, y - other.y, z - other.z);
        }
    }

    public record Solution(float upperX, float upperZ, float lowerX, Point target, float clampDistance) {
    }
}
