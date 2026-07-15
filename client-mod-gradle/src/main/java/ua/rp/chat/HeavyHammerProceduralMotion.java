package ua.rp.chat;

/**
 * Траектория жёсткого двуручного молота. Здесь описывается движение инструмента,
 * а не углы рук: суставы решаются отдельно по двум точкам хвата.
 */
public final class HeavyHammerProceduralMotion {
    public static final float ITEM_SCALE = 0.57f;
    public static final float IDLE_GRIP_DISTANCE = 11.5f * ITEM_SCALE;
    public static final float HEAD_DISTANCE = 24.1f * ITEM_SCALE;
    public static final float IMPACT_PROGRESS = HeavyHammerAnimation.IMPACT_TICK
            / HeavyHammerAnimation.DURATION_TICKS;

    private static final Anchor[] STRIKE_PATH = {
            // Рабочая стойка: правая рука внизу, головка лежит выше левого плеча.
            new Anchor(0.00f, new Vec3(-1.20f, 8.80f, -2.40f), new Vec3(0.76f, -0.45f, 0.48f),
                    new Vec3(0.0f, 0.0f, -1.0f), IDLE_GRIP_DISTANCE),
            // Снятие веса и начало кругового движения.
            new Anchor(0.10f, new Vec3(-0.80f, 8.00f, -1.50f), new Vec3(0.70f, -0.48f, 0.53f),
                    new Vec3(0.0f, 0.0f, -1.0f), 6.00f),
            // Молот уходит за правое плечо, но обе точки хвата остаются достижимы.
            new Anchor(0.28f, new Vec3(1.50f, 5.00f, -5.00f), new Vec3(0.60f, -0.30f, 0.74f),
                    new Vec3(0.55f, 0.75f, -0.20f), 4.80f),
            new Anchor(0.31f, new Vec3(1.20f, 4.00f, -4.80f), new Vec3(0.68f, -0.42f, 0.60f),
                    new Vec3(0.35f, 0.70f, -0.60f), 4.00f),
            // Перед верхней точкой боёк проходит снаружи левого плеча, а не сквозь голову.
            new Anchor(0.35f, new Vec3(1.20f, 3.00f, -4.50f), new Vec3(0.75f, -0.55f, 0.35f),
                    new Vec3(0.20f, 0.65f, -0.75f), 4.80f),
            new Anchor(0.40f, new Vec3(1.00f, 0.00f, -4.00f), new Vec3(0.65f, -0.72f, 0.25f),
                    new Vec3(0.75f, 0.35f, -0.45f), 5.00f),
            // Верхняя точка с коротким накоплением веса.
            new Anchor(0.43f, new Vec3(-0.20f, 0.20f, -4.00f), new Vec3(0.40f, -0.87f, 0.30f),
                    new Vec3(1.0f, 0.0f, 0.0f), 5.20f),
            // Проход через переднюю горизонталь не даёт направлению древка
            // интерполироваться сквозь нулевой вектор между верхом и контактом.
            new Anchor(0.54f, new Vec3(-1.00f, 2.50f, -3.00f), new Vec3(0.20f, -0.20f, -0.96f),
                    new Vec3(1.0f, 0.0f, 0.0f), 4.60f),
            // Контакт: головка направлена вниз и вперёд, руки не растягиваются.
            new Anchor(IMPACT_PROGRESS, new Vec3(-1.00f, 3.00f, -2.00f), new Vec3(0.35f, 0.82f, -0.45f),
                    new Vec3(1.0f, 0.0f, 0.0f), 4.50f),
            // Инерционное сопровождение удара.
            new Anchor(0.73f, new Vec3(0.00f, 5.00f, -1.00f), new Vec3(0.50f, 0.70f, -0.50f),
                    new Vec3(1.0f, 0.0f, 0.0f), 4.80f),
            // Гашение импульса перед возвратом.
            new Anchor(0.86f, new Vec3(-0.50f, 8.00f, -2.20f), new Vec3(0.971f, 0.235f, -0.050f),
                    new Vec3(0.226f, -0.964f, -0.140f), 5.80f),
            new Anchor(1.00f, new Vec3(-1.20f, 8.80f, -2.40f), new Vec3(0.76f, -0.45f, 0.48f),
                    new Vec3(0.0f, 0.0f, -1.0f), IDLE_GRIP_DISTANCE)
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
        return compose(0.0f, main, shaft, base.headRoll, base.gripDistance);
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
        float fromRoll = rollFor(from.shaft, from.headHint);
        float roll = fromRoll + shortestAngle(fromRoll, rollFor(to.shaft, to.headHint)) * eased;
        float gripDistance = from.gripDistance + (to.gripDistance - from.gripDistance) * eased;
        return compose(progress, main, shaft, roll, gripDistance);
    }

    private static Frame compose(float progress, Vec3 mainGrip, Vec3 shaft,
                                 float headRoll, float gripDistance) {
        Vec3 normalizedShaft = shaft.normalized();
        // Направление древка не задаёт поворот бойка вокруг него. Стабильный
        // базис и отдельный непрерывный roll закрывают эту степень свободы:
        // даже при смене фазы опорный вектор не может совпасть с древком.
        Vec3 referenceHead = referenceHead(normalizedShaft);
        Vec3 referenceDepth = referenceHead.cross(normalizedShaft).normalized();
        Vec3 headAxis = referenceHead.scale((float) Math.cos(headRoll))
                .add(referenceDepth.scale((float) Math.sin(headRoll))).normalized();
        Vec3 depthAxis = headAxis.cross(normalizedShaft).normalized();
        Vec3 headCenter = mainGrip.add(normalizedShaft.scale(HEAD_DISTANCE));

        // Корпус компенсирует положение центра тяжести и направление инерции молота.
        float bodyY = clamp((headCenter.x - 1.5f) * 0.035f - normalizedShaft.z * 0.22f, -0.55f, 0.55f);
        float bodyX = clamp((headCenter.y - 5.0f) * 0.025f
                - Math.abs(normalizedShaft.z) * 0.05f, -0.16f, 0.24f);
        return new Frame(progress, mainGrip, normalizedShaft, headAxis, depthAxis,
                headCenter, gripDistance, headRoll, bodyX, bodyY);
    }

    private static float rollFor(Vec3 shaft, Vec3 headHint) {
        Vec3 normalizedShaft = shaft.normalized();
        Vec3 referenceHead = referenceHead(normalizedShaft);
        Vec3 referenceDepth = referenceHead.cross(normalizedShaft).normalized();
        Vec3 projectedHint = headHint.subtract(normalizedShaft.scale(headHint.dot(normalizedShaft))).normalized();
        return (float) Math.atan2(projectedHint.dot(referenceDepth), projectedHint.dot(referenceHead));
    }

    private static Vec3 referenceHead(Vec3 normalizedShaft) {
        // Во всей траектории древко остаётся достаточно далеко от мировой Y,
        // поэтому верх даёт непрерывный базис без переключений между осями.
        Vec3 worldUp = new Vec3(0.0f, 1.0f, 0.0f);
        return worldUp.subtract(normalizedShaft.scale(worldUp.dot(normalizedShaft))).normalized();
    }

    private static float shortestAngle(float from, float to) {
        float delta = to - from;
        while (delta > Math.PI) delta -= (float) (Math.PI * 2.0);
        while (delta < -Math.PI) delta += (float) (Math.PI * 2.0);
        return delta;
    }

    private static float smootherStep(float value) {
        return value * value * value * (value * (value * 6.0f - 15.0f) + 10.0f);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private record Anchor(float time, Vec3 mainGrip, Vec3 shaft,
                          Vec3 headHint, float gripDistance) {
    }

    public record Frame(float progress, Vec3 mainGrip, Vec3 shaft,
                        Vec3 headAxis, Vec3 depthAxis, Vec3 headCenter,
                        float gripDistance, float headRoll, float bodyX, float bodyY) {
        public Vec3 offhandGrip() {
            return mainGrip.add(shaft.scale(gripDistance));
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
