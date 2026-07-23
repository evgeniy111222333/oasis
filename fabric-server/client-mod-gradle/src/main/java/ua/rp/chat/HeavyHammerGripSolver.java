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
        // Сегментированная модель сгибает предплечье отрицательным X, поэтому выбираем
        // анатомическую ветвь решения: локоть выходит наружу, а не выворачивается назад.
        float upperX = targetX + triangle;
        return new Solution(upperX, upperZ, lowerX, target, Math.abs(distance - reachableDistance));
    }

    public static Point hand(Point shoulder, float upperX, float upperZ, float lowerX) {
        return hand(shoulder, upperX, 0.0f, upperZ, lowerX);
    }

    public static Point hand(Point shoulder, float upperX, float upperY, float upperZ, float lowerX) {
        Vector upper = rotateArm(new Vector(0.0f, UPPER_LENGTH, 0.0f), upperX, upperY, upperZ);
        Vector lower = rotateArm(rotateX(new Vector(0.0f, LOWER_LENGTH, 0.0f), -lowerX),
                upperX, upperY, upperZ);
        return new Point(shoulder.x + upper.x + lower.x,
                shoulder.y + upper.y + lower.y,
                shoulder.z + upper.z + lower.z);
    }

    private static Vector rotateArm(Vector vector, float x, float y, float z) {
        return rotateZ(rotateY(rotateX(vector, x), y), z);
    }

    private static Vector rotateX(Vector vector, float angle) {
        float cosine = (float) Math.cos(angle);
        float sine = (float) Math.sin(angle);
        return new Vector(vector.x, vector.y * cosine - vector.z * sine,
                vector.y * sine + vector.z * cosine);
    }

    private static Vector rotateY(Vector vector, float angle) {
        float cosine = (float) Math.cos(angle);
        float sine = (float) Math.sin(angle);
        return new Vector(vector.x * cosine + vector.z * sine, vector.y,
                -vector.x * sine + vector.z * cosine);
    }

    private static Vector rotateZ(Vector vector, float angle) {
        float cosine = (float) Math.cos(angle);
        float sine = (float) Math.sin(angle);
        return new Vector(vector.x * cosine - vector.y * sine,
                vector.x * sine + vector.y * cosine, vector.z);
    }

    private record Vector(float x, float y, float z) {
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
