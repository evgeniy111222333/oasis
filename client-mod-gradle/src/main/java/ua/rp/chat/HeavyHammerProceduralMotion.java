package ua.rp.chat;

/**
 * Траектория жёсткого двуручного молота. Здесь описывается движение инструмента,
 * а не углы рук: суставы решаются отдельно по двум точкам хвата.
 */
public final class HeavyHammerProceduralMotion {
    public static final float ITEM_SCALE = 0.57f;
    public static final float GRIP_DISTANCE = 7.2f * ITEM_SCALE;
    public static final float HEAD_DISTANCE = 24.1f * ITEM_SCALE;
    public static final float IMPACT_PROGRESS = HeavyHammerAnimation.IMPACT_TICK
            / HeavyHammerAnimation.DURATION_TICKS;

    private static final Anchor[] STRIKE_PATH = {
            // Рабочая стойка: правая рука внизу, головка лежит выше левого плеча.
            new Anchor(0.00f, new Vec3(-1.50f, 8.50f, -3.00f), new Vec3(0.65f, -0.75f, 0.14f)),
            // Снятие веса и начало кругового движения.
            new Anchor(0.10f, new Vec3(-1.00f, 8.00f, -2.00f), new Vec3(0.70f, -0.60f, 0.38f)),
            // Молот уходит за правое плечо, но обе точки хвата остаются достижимы.
            new Anchor(0.28f, new Vec3(-3.00f, 5.00f, -5.00f), new Vec3(0.60f, -0.30f, 0.74f)),
            // Верхняя точка с коротким накоплением веса.
            new Anchor(0.43f, new Vec3(-1.00f, 3.00f, -4.00f), new Vec3(0.30f, -0.90f, 0.30f)),
            // Проход через переднюю горизонталь не даёт направлению древка
            // интерполироваться сквозь нулевой вектор между верхом и контактом.
            new Anchor(0.54f, new Vec3(-1.00f, 2.50f, -3.00f), new Vec3(0.20f, -0.20f, -0.96f)),
            // Контакт: головка направлена вниз и вперёд, руки не растягиваются.
            new Anchor(IMPACT_PROGRESS, new Vec3(-1.00f, 3.00f, -2.00f), new Vec3(0.35f, 0.82f, -0.45f)),
            // Инерционное сопровождение удара.
            new Anchor(0.73f, new Vec3(0.00f, 5.00f, -1.00f), new Vec3(0.50f, 0.70f, -0.50f)),
            // Гашение импульса перед возвратом.
            new Anchor(0.86f, new Vec3(-0.50f, 8.00f, -2.20f), new Vec3(0.62f, -0.42f, -0.25f)),
            new Anchor(1.00f, new Vec3(-1.50f, 8.50f, -3.00f), new Vec3(0.65f, -0.75f, 0.14f))
    };

    private HeavyHammerProceduralMotion() {
    }

    public static Frame idle(float ageTicks) {
        return idle(ageTicks, 0.0f);
    }

    public static Frame idle(float ageTicks, float locomotion) {
        Frame base = frame(0.0f);
        float breath = (float) Math.sin(ageTicks * 0.055f);
        float moving = clamp(locomotion * 5.0f, 0.0f, 1.0f);
        float carrySway = (float) Math.sin(ageTicks * 0.55f) * moving;
        Vec3 main = base.mainGrip.add(carrySway * 0.12f,
                breath * 0.06f + Math.abs(carrySway) * 0.08f, carrySway * 0.18f);
        Vec3 shaft = base.shaft.add(0.0f, 0.0f,
                breath * 0.012f + carrySway * 0.025f).normalized();
        return compose(0.0f, main, shaft);
    }

    public static Frame strike(float progress) {
        return frame(clamp(progress, 0.0f, 1.0f));
    }

    private static Frame frame(float progress) {
        Anchor from = STRIKE_PATH[0];
        Anchor to = STRIKE_PATH[STRIKE_PATH.length - 1];
        for (int index = 1; index < STRIKE_PATH.length; index++) {
            if (progress <= STRIKE_PATH[index].time) {
                from = STRIKE_PATH[index - 1];
                to = STRIKE_PATH[index];
                break;
            }
        }
        float local = (progress - from.time) / Math.max(0.0001f, to.time - from.time);
        float eased = smootherStep(clamp(local, 0.0f, 1.0f));
        Vec3 main = Vec3.lerp(from.mainGrip, to.mainGrip, eased);
        Vec3 shaft = Vec3.lerp(from.shaft, to.shaft, eased).normalized();
        return compose(progress, main, shaft);
    }

    private static Frame compose(float progress, Vec3 mainGrip, Vec3 shaft) {
        Vec3 normalizedShaft = shaft.normalized();
        Vec3 side = new Vec3(1.0f, 0.0f, 0.0f);
        Vec3 headAxis = side.subtract(normalizedShaft.scale(side.dot(normalizedShaft))).normalized();
        Vec3 depthAxis = headAxis.cross(normalizedShaft).normalized();
        Vec3 headCenter = mainGrip.add(normalizedShaft.scale(HEAD_DISTANCE));

        // Корпус компенсирует положение центра тяжести и направление инерции молота.
        float bodyY = clamp((headCenter.x - 1.5f) * 0.035f - normalizedShaft.z * 0.22f, -0.55f, 0.55f);
        float bodyX = clamp((headCenter.y - 5.0f) * 0.025f
                - Math.abs(normalizedShaft.z) * 0.05f, -0.16f, 0.24f);
        return new Frame(progress, mainGrip, normalizedShaft, headAxis, depthAxis, headCenter, bodyX, bodyY);
    }

    private static float smootherStep(float value) {
        return value * value * value * (value * (value * 6.0f - 15.0f) + 10.0f);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private record Anchor(float time, Vec3 mainGrip, Vec3 shaft) {
    }

    public record Frame(float progress, Vec3 mainGrip, Vec3 shaft,
                        Vec3 headAxis, Vec3 depthAxis, Vec3 headCenter,
                        float bodyX, float bodyY) {
        public Vec3 offhandGrip() {
            return mainGrip.add(shaft.scale(GRIP_DISTANCE));
        }
    }

    public record Vec3(float x, float y, float z) {
        public Vec3 add(Vec3 other) {
            return new Vec3(x + other.x, y + other.y, z + other.z);
        }

        public Vec3 add(float dx, float dy, float dz) {
            return new Vec3(x + dx, y + dy, z + dz);
        }

        public Vec3 subtract(Vec3 other) {
            return new Vec3(x - other.x, y - other.y, z - other.z);
        }

        public Vec3 scale(float amount) {
            return new Vec3(x * amount, y * amount, z * amount);
        }

        public float dot(Vec3 other) {
            return x * other.x + y * other.y + z * other.z;
        }

        public Vec3 cross(Vec3 other) {
            return new Vec3(y * other.z - z * other.y,
                    z * other.x - x * other.z,
                    x * other.y - y * other.x);
        }

        public float length() {
            return (float) Math.sqrt(dot(this));
        }

        public Vec3 normalized() {
            float length = length();
            return length < 0.0001f ? new Vec3(0.0f, -1.0f, 0.0f) : scale(1.0f / length);
        }

        public float distanceTo(Vec3 other) {
            return subtract(other).length();
        }

        public static Vec3 lerp(Vec3 from, Vec3 to, float amount) {
            return new Vec3(from.x + (to.x - from.x) * amount,
                    from.y + (to.y - from.y) * amount,
                    from.z + (to.z - from.z) * amount);
        }
    }
}
