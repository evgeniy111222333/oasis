package ua.rp.chat;

/**
 * Физическая траектория тяжёлого двуручного молота. Сначала задаётся непрерывное
 * движение жёсткого инструмента и центра тяжести, затем руки решаются по двум
 * точкам хвата. Ни одна фаза не должна требовать прохождения древка сквозь тело.
 */
public final class HeavyHammerProceduralMotion {
    public static final float ITEM_SCALE = 0.57f;
    public static final float IDLE_GRIP_DISTANCE = 10.5f * ITEM_SCALE;
    public static final float HEAD_DISTANCE = 33.1f * ITEM_SCALE;
    public static final float IMPACT_PROGRESS = HeavyHammerAnimation.IMPACT_TICK
            / HeavyHammerAnimation.DURATION_TICKS;

    private static final Anchor[] STRIKE_PATH = {
            // Устойчивая стойка: древко целиком перед корпусом, боёк вынесен над левым плечом.
            anchor(0.00f, -1.50f, 8.80f, -4.20f, 0.72f, -0.44f, -0.54f,
                    0.686f, 0.343f, 0.636f, IDLE_GRIP_DISTANCE,
                    pose(0.03f, 0.08f, 0.00f, -0.03f, 0.00f,
                            -0.08f, -0.03f, -0.08f, 0.08f, 0.12f, 0.08f, 0.25f)),
            // Снятие веса с головки без разрыва хвата.
            anchor(0.10f, -1.00f, 8.60f, -4.30f, 0.72f, -0.40f, -0.56f,
                    0.686f, 0.343f, 0.637f, 5.60f,
                    pose(-0.08f, -0.12f, -0.03f, -0.02f, 0.08f,
                            -0.10f, -0.05f, -0.10f, 0.10f, 0.15f, 0.10f, 0.45f)),
            // Начало кругового заноса: кисти уходят вперёд и наружу, а не сквозь грудь.
            anchor(0.20f, -0.50f, 6.50f, -5.00f, 0.88f, -0.25f, -0.40f,
                    0.474f, 0.474f, 0.747f, 5.00f,
                    pose(-0.14f, -0.28f, -0.06f, 0.00f, 0.12f,
                            -0.13f, -0.08f, -0.13f, 0.13f, 0.18f, 0.12f, 0.55f)),
            // Древко пересекает плоскость спины только после выхода сбоку от силуэта.
            anchor(0.30f, -0.50f, 5.00f, -7.00f, 0.82f, -0.15f, 0.55f,
                    0.18f, 0.98f, 0.00f, 4.50f,
                    pose(-0.18f, -0.38f, -0.08f, 0.02f, 0.14f,
                            -0.15f, -0.09f, -0.15f, 0.15f, 0.22f, 0.14f, 0.65f)),
            // Подхват массы ногами и тазом.
            anchor(0.38f, -0.30f, 3.00f, -6.50f, 0.55f, -0.82f, -0.15f,
                    0.83f, 0.56f, 0.00f, 5.00f,
                    pose(-0.12f, -0.25f, -0.05f, 0.06f, 0.10f,
                            -0.14f, -0.12f, -0.16f, 0.16f, 0.20f, 0.18f, 0.68f)),
            // Верхняя точка: головка над и перед персонажем, локти остаются согнутыми.
            anchor(0.43f, 0.00f, 2.00f, -5.50f, 0.15f, -0.985f, -0.08f,
                    0.99f, 0.15f, 0.00f, 5.00f,
                    pose(-0.10f, -0.12f, -0.03f, 0.12f, 0.06f,
                            -0.16f, -0.15f, -0.17f, 0.17f, 0.22f, 0.22f, 0.70f)),
            // Разгон по дуге: кисти ведут рукоять, а корпус догоняет инструмент.
            anchor(0.54f, -0.50f, 2.50f, -5.00f, 0.15f, -0.20f, -0.968f,
                    0.99f, 0.10f, 0.133f, 2.80f,
                    pose(0.08f, 0.08f, 0.02f, 0.18f, 0.02f,
                            -0.20f, -0.16f, -0.19f, 0.19f, 0.28f, 0.22f, 0.75f)),
            // Контакт: нижняя грань бойка доходит до плоскости земли перед ногами.
            anchor(IMPACT_PROGRESS, -1.00f, 1.45f, -5.40f, 0.20f, 0.90f, -0.39f,
                    0.98f, -0.20f, 0.00f, 3.30f,
                    pose(0.30f, 0.22f, 0.05f, 0.28f, -0.03f,
                            -0.25f, -0.20f, -0.22f, 0.22f, 0.35f, 0.28f, 0.80f)),
            // Сопровождение удара без мгновенной остановки массы.
            anchor(0.73f, -0.50f, 2.55f, -5.40f, 0.45f, 0.72f, -0.53f,
                    0.85f, -0.53f, 0.00f, 3.20f,
                    pose(0.32f, 0.30f, 0.08f, 0.24f, -0.06f,
                            -0.22f, -0.17f, -0.20f, 0.20f, 0.30f, 0.24f, 0.72f)),
            // Гашение импульса. Боёк остаётся поперечным древку и не превращается в «якорь».
            anchor(0.86f, -1.00f, 8.00f, -4.20f, 0.55f, -0.35f, -0.76f,
                    0.835f, 0.209f, 0.509f, 3.50f,
                    pose(0.14f, 0.16f, 0.03f, 0.08f, -0.03f,
                            -0.13f, -0.08f, -0.12f, 0.12f, 0.18f, 0.14f, 0.48f)),
            anchor(1.00f, -1.50f, 8.80f, -4.20f, 0.72f, -0.44f, -0.54f,
                    0.686f, 0.343f, 0.636f, IDLE_GRIP_DISTANCE,
                    pose(0.03f, 0.08f, 0.00f, -0.03f, 0.00f,
                            -0.08f, -0.03f, -0.08f, 0.08f, 0.12f, 0.08f, 0.25f))
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
        BodyPose breathingPose = base.bodyPose.withBreath(breath * 0.008f);
        return compose(0.0f, main, shaft, base.headRoll, base.gripDistance, breathingPose);
    }

    public static Frame strike(float progress) {
        return frame(clamp(progress, 0.0f, 1.0f));
    }

    public static Frame strike(float progress, Target target) {
        Frame base = strike(progress);
        if (target == null) return base;

        float influence = contactInfluence(progress);
        // Направление персонажа даёт основное наведение. Саму жёсткую траекторию
        // нельзя телескопически переносить к далёкой цели — такие цели отклоняет сервер.
        float horizontal = Math.max(1.0f, (float) Math.sqrt(target.x * target.x + target.z * target.z));
        float desiredHeadYaw = clamp((float) Math.atan2(target.x, -target.z), -0.32f, 0.32f);
        float desiredHeadPitch = clamp((float) Math.atan2(target.y + 4.0f, horizontal) - 0.55f,
                -0.20f, 0.38f);
        BodyPose aimedBody = base.bodyPose.withAim(desiredHeadPitch, desiredHeadYaw,
                desiredHeadYaw * 0.30f, 0.35f + influence * 0.65f);
        Vec3 surfaceNormal = new Vec3(target.normalX, target.normalY, target.normalZ);
        float aimedRoll = base.headRoll;
        if (surfaceNormal.length() > 0.5f) {
            // У бойка две равноправные рабочие стороны. Выбираем знак нормали один
            // раз относительно базового кадра контакта, иначе ±нормаль заставляет
            // ось визуально переворачиваться на 180° между соседними кадрами.
            if (surfaceNormal.dot(frame(IMPACT_PROGRESS).headAxis) < 0.0f) {
                surfaceNormal = surfaceNormal.scale(-1.0f);
            }
            float surfaceWeight = target.surface == Surface.SIDE ? 0.45f : 1.00f;
            float orientationTiming = target.surface == Surface.SIDE
                    ? sideSurfaceInfluence(progress) : surfaceInfluence(progress);
            float surfaceRoll = rollFor(base.shaft, surfaceNormal);
            aimedRoll += shortestAngle(aimedRoll, surfaceRoll) * orientationTiming * surfaceWeight;
        }
        return compose(progress, base.mainGrip, base.shaft, aimedRoll, base.gripDistance, aimedBody);
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
        float gripDistance = lerp(from.gripDistance, to.gripDistance, eased);
        return compose(progress, main, shaft, roll, gripDistance,
                BodyPose.lerp(from.bodyPose, to.bodyPose, eased));
    }

    private static Frame compose(float progress, Vec3 mainGrip, Vec3 shaft,
                                 float headRoll, float gripDistance, BodyPose bodyPose) {
        Vec3 normalizedShaft = shaft.normalized();
        Vec3 referenceHead = referenceHead(normalizedShaft);
        Vec3 referenceDepth = referenceHead.cross(normalizedShaft).normalized();
        Vec3 headAxis = referenceHead.scale((float) Math.cos(headRoll))
                .add(referenceDepth.scale((float) Math.sin(headRoll))).normalized();
        Vec3 depthAxis = headAxis.cross(normalizedShaft).normalized();
        Vec3 headCenter = mainGrip.add(normalizedShaft.scale(HEAD_DISTANCE));
        return new Frame(progress, mainGrip, normalizedShaft, headAxis, depthAxis,
                headCenter, gripDistance, headRoll, bodyPose);
    }

    private static float rollFor(Vec3 shaft, Vec3 headHint) {
        Vec3 normalizedShaft = shaft.normalized();
        Vec3 referenceHead = referenceHead(normalizedShaft);
        Vec3 referenceDepth = referenceHead.cross(normalizedShaft).normalized();
        Vec3 projectedHint = headHint.subtract(normalizedShaft.scale(headHint.dot(normalizedShaft))).normalized();
        return (float) Math.atan2(projectedHint.dot(referenceDepth), projectedHint.dot(referenceHead));
    }

    private static Vec3 referenceHead(Vec3 normalizedShaft) {
        Vec3 worldUp = new Vec3(0.0f, 1.0f, 0.0f);
        return worldUp.subtract(normalizedShaft.scale(worldUp.dot(normalizedShaft))).normalized();
    }

    private static Anchor anchor(float time, float mainX, float mainY, float mainZ,
                                 float shaftX, float shaftY, float shaftZ,
                                 float hintX, float hintY, float hintZ,
                                 float gripDistance, BodyPose pose) {
        return new Anchor(time, new Vec3(mainX, mainY, mainZ),
                new Vec3(shaftX, shaftY, shaftZ).normalized(),
                new Vec3(hintX, hintY, hintZ), gripDistance, pose);
    }

    private static BodyPose pose(float torsoPitch, float torsoYaw, float torsoRoll,
                                 float headPitch, float headYaw,
                                 float rightLegPitch, float leftLegPitch,
                                 float rightLegRoll, float leftLegRoll,
                                 float rightKnee, float leftKnee, float stanceWidth) {
        return new BodyPose(torsoPitch, torsoYaw, torsoRoll, headPitch, headYaw,
                rightLegPitch, leftLegPitch, rightLegRoll, leftLegRoll,
                rightKnee, leftKnee, stanceWidth);
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

    private static float contactInfluence(float progress) {
        float rise = smootherStep(clamp((progress - 0.28f) / 0.34f, 0.0f, 1.0f));
        float fall = 1.0f - smootherStep(clamp((progress - 0.73f) / 0.20f, 0.0f, 1.0f));
        return rise * fall;
    }

    private static float surfaceInfluence(float progress) {
        float rise = smootherStep(clamp((progress - 0.43f) / (IMPACT_PROGRESS - 0.43f), 0.0f, 1.0f));
        float fall = 1.0f - smootherStep(clamp((progress - 0.73f) / 0.16f, 0.0f, 1.0f));
        return rise * fall;
    }

    private static float sideSurfaceInfluence(float progress) {
        float rise = smootherStep(clamp((progress - 0.50f) / (IMPACT_PROGRESS - 0.50f), 0.0f, 1.0f));
        float fall = 1.0f - smootherStep(clamp((progress - 0.73f) / 0.16f, 0.0f, 1.0f));
        return rise * fall;
    }

    private static float lerp(float from, float to, float amount) {
        return from + (to - from) * amount;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private record Anchor(float time, Vec3 mainGrip, Vec3 shaft,
                          Vec3 headHint, float gripDistance, BodyPose bodyPose) {
    }

    public record BodyPose(float torsoPitch, float torsoYaw, float torsoRoll,
                           float headPitch, float headYaw,
                           float rightLegPitch, float leftLegPitch,
                           float rightLegRoll, float leftLegRoll,
                           float rightKnee, float leftKnee, float stanceWidth) {
        private BodyPose withBreath(float amount) {
            return new BodyPose(torsoPitch + amount, torsoYaw, torsoRoll,
                    headPitch - amount * 0.65f, headYaw,
                    rightLegPitch, leftLegPitch, rightLegRoll, leftLegRoll,
                    rightKnee, leftKnee, stanceWidth);
        }

        private BodyPose withAim(float pitch, float yaw, float torsoYawOffset, float amount) {
            return new BodyPose(torsoPitch, torsoYaw + torsoYawOffset * amount, torsoRoll,
                    HeavyHammerProceduralMotion.lerp(headPitch, pitch, amount),
                    HeavyHammerProceduralMotion.lerp(headYaw, yaw, amount),
                    rightLegPitch, leftLegPitch, rightLegRoll, leftLegRoll,
                    rightKnee, leftKnee, stanceWidth);
        }

        private static BodyPose lerp(BodyPose from, BodyPose to, float amount) {
            return new BodyPose(
                    HeavyHammerProceduralMotion.lerp(from.torsoPitch, to.torsoPitch, amount),
                    HeavyHammerProceduralMotion.lerp(from.torsoYaw, to.torsoYaw, amount),
                    HeavyHammerProceduralMotion.lerp(from.torsoRoll, to.torsoRoll, amount),
                    HeavyHammerProceduralMotion.lerp(from.headPitch, to.headPitch, amount),
                    HeavyHammerProceduralMotion.lerp(from.headYaw, to.headYaw, amount),
                    HeavyHammerProceduralMotion.lerp(from.rightLegPitch, to.rightLegPitch, amount),
                    HeavyHammerProceduralMotion.lerp(from.leftLegPitch, to.leftLegPitch, amount),
                    HeavyHammerProceduralMotion.lerp(from.rightLegRoll, to.rightLegRoll, amount),
                    HeavyHammerProceduralMotion.lerp(from.leftLegRoll, to.leftLegRoll, amount),
                    HeavyHammerProceduralMotion.lerp(from.rightKnee, to.rightKnee, amount),
                    HeavyHammerProceduralMotion.lerp(from.leftKnee, to.leftKnee, amount),
                    HeavyHammerProceduralMotion.lerp(from.stanceWidth, to.stanceWidth, amount));
        }
    }

    public record Frame(float progress, Vec3 mainGrip, Vec3 shaft,
                        Vec3 headAxis, Vec3 depthAxis, Vec3 headCenter,
                        float gripDistance, float headRoll, BodyPose bodyPose) {
        public Vec3 offhandGrip() {
            return mainGrip.add(shaft.scale(gripDistance));
        }
    }

    public record Target(float x, float y, float z, Surface surface,
                         float normalX, float normalY, float normalZ) {
    }

    public enum Surface {
        UP, DOWN, SIDE
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
            float vectorLength = length();
            return vectorLength < 0.0001f ? new Vec3(0.0f, -1.0f, 0.0f) : scale(1.0f / vectorLength);
        }

        public float distanceTo(Vec3 other) {
            return subtract(other).length();
        }

        public static Vec3 lerp(Vec3 from, Vec3 to, float amount) {
            return new Vec3(HeavyHammerProceduralMotion.lerp(from.x, to.x, amount),
                    HeavyHammerProceduralMotion.lerp(from.y, to.y, amount),
                    HeavyHammerProceduralMotion.lerp(from.z, to.z, amount));
        }
    }
}
