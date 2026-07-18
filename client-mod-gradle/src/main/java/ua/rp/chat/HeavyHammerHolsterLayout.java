package ua.rp.chat;

/**
 * Геометрический контракт между процедурной портупеей и молотом.
 * Все значения заданы в пикселях модели игрока и повторяют якоря генератора
 * generate_minecraft_hammer_holster.py.
 */
public final class HeavyHammerHolsterLayout {
    public static final float MODEL_CENTER = 8.0f;
    public static final float FIXED_SCALE = 0.80f;
    public static final float ROOT_X = 0.0f;
    public static final float ROOT_Y = 6.0f;
    /** Minimum local-space clearance between the leather and the torso surface. */
    public static final float ROOT_Z = 0.18f;
    public static final float LATCH_CLOSED_Z_RETRACTION = 0.004f;
    public static final float FROG_AXIS_X = 13.70f;
    public static final float FROG_AXIS_Z = 8.0f;
    public static final float HAMMER_SEAT_Y = 6.45f;
    public static final float HAMMER_TILT_DEGREES = -14.0f;
    public static final float MAIN_GRIP_TO_SOCKET = 16.70f;

    private HeavyHammerHolsterLayout() {
    }

    /**
     * Local attachment point after the body bone has been applied. Keeping this
     * calculation here makes the rendered holster and hammer-seat contract use
     * one explicit, bounded surface clearance.
     */
    public static Point bodyAttachment(float latchClosed) {
        float latch = Math.max(0.0f, Math.min(1.0f, latchClosed));
        return new Point(ROOT_X, ROOT_Y, ROOT_Z - latch * LATCH_CLOSED_Z_RETRACTION);
    }

    /** Положение нижней грани втулки после fixed-поворота модели на 180 градусов. */
    public static Point socketSeat() {
        Point grip = mainGrip();
        Point shaft = shaftAxis();
        return grip.add(shaft.scale(MAIN_GRIP_TO_SOCKET));
    }

    /** Ось древка от нижнего хвата к бойку в координатах модели игрока. */
    public static Point shaftAxis() {
        double angle = Math.toRadians(-HAMMER_TILT_DEGREES);
        return new Point(-(float) Math.sin(angle), -(float) Math.cos(angle), 0.0f).normalized();
    }

    /** Длинная ось бойка после общей посадки в портупею. */
    public static Point headAxis() {
        double angle = Math.toRadians(-HAMMER_TILT_DEGREES);
        return new Point(-(float) Math.cos(angle), (float) Math.sin(angle), 0.0f).normalized();
    }

    public static Point mainGrip() {
        float x = ROOT_X - (FROG_AXIS_X - MODEL_CENTER) * FIXED_SCALE;
        float y = ROOT_Y + (MODEL_CENTER - HAMMER_SEAT_Y) * FIXED_SCALE;
        return new Point(x, y, ROOT_Z);
    }

    public record Point(float x, float y, float z) {
        public Point scale(float value) {
            return new Point(x * value, y * value, z * value);
        }

        public Point subtract(Point value) {
            return new Point(x - value.x, y - value.y, z - value.z);
        }

        public Point add(Point value) {
            return new Point(x + value.x, y + value.y, z + value.z);
        }

        public float dot(Point value) {
            return x * value.x + y * value.y + z * value.z;
        }

        public float length() {
            return (float) Math.sqrt(dot(this));
        }

        public Point normalized() {
            float length = length();
            if (length < 1.0e-6f) return new Point(0.0f, 0.0f, 0.0f);
            return scale(1.0f / length);
        }
    }
}
