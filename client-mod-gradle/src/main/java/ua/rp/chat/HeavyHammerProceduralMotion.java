package ua.rp.chat;

/**
 * Физическая траектория тяжёлого двуручного молота. Сначала задаётся непрерывное
 * движение жёсткого инструмента и центра тяжести, затем руки решаются по двум
 * точкам хвата. Ни одна фаза не должна требовать прохождения древка сквозь тело.
 */
public final class HeavyHammerProceduralMotion {
    public static final float ITEM_SCALE = 0.57f;
    public static final float IDLE_GRIP_DISTANCE = 12.4f;
    public static final float HEAD_DISTANCE = 19.835f;
    public static final float IMPACT_PROGRESS = HeavyHammerAnimation.IMPACT_TICK
            / HeavyHammerAnimation.DURATION_TICKS;
    private static final Vec3 EQUIP_MAIN_GRIP = new Vec3(-4.50f, 9.80f, -3.20f);
    private static final Vec3 EQUIP_SHAFT = new Vec3(0.75f, -0.66f, 0.00f).normalized();
    private static final Vec3 EQUIP_HEAD_HINT = new Vec3(0.00f, 0.00f, 1.00f);
    private static final float EQUIP_GRIP_DISTANCE = 6.20f;
    private static final BodyPose EQUIP_BODY_POSE = pose(
            0.01f, -0.015f, -0.01f, -0.015f, 0.02f,
            -0.02f, -0.01f, 0.0f, 0.0f, 0.07f, 0.06f, 0.20f);
    private static final HeavyHammerHolsterLayout.Point HOLSTER_MAIN =
            HeavyHammerHolsterLayout.mainGrip();
    private static final HeavyHammerHolsterLayout.Point HOLSTER_SHAFT =
            HeavyHammerHolsterLayout.shaftAxis();
    private static final HeavyHammerHolsterLayout.Point HOLSTER_HEAD =
            HeavyHammerHolsterLayout.headAxis();

    /*
     * Путь начинается в кожаном подвесе за правым бедром. До открытия
     * защёлки инструмент неподвижен; затем боёк выходит за силуэт справа,
     * и только после этого левая рука принимает середину рукояти.
     */
    private static final Anchor[] DRAW_PATH = {
            anchor(0.00f, HOLSTER_MAIN.x(), HOLSTER_MAIN.y(), HOLSTER_MAIN.z(),
                    HOLSTER_SHAFT.x(), HOLSTER_SHAFT.y(), HOLSTER_SHAFT.z(),
                    HOLSTER_HEAD.x(), HOLSTER_HEAD.y(), HOLSTER_HEAD.z(), 5.6f,
                    pose(0.0f, 0.0f, 0.0f, 0.0f, 0.0f,
                            0.0f, 0.0f, 0.0f, 0.0f, 0.06f, 0.06f, 0.10f)),
            anchor(0.18f, HOLSTER_MAIN.x(), HOLSTER_MAIN.y(), HOLSTER_MAIN.z(),
                    HOLSTER_SHAFT.x(), HOLSTER_SHAFT.y(), HOLSTER_SHAFT.z(),
                    HOLSTER_HEAD.x(), HOLSTER_HEAD.y(), HOLSTER_HEAD.z(), 5.6f,
                    pose(0.015f, -0.08f, -0.025f, -0.01f, 0.06f,
                            -0.03f, -0.01f, 0.006f, -0.006f, 0.09f, 0.07f, 0.18f)),
            // Сначала боёк выходит из капелы строго наружу, не прорезая корпус.
            anchor(0.30f, HOLSTER_MAIN.x(), HOLSTER_MAIN.y(), HOLSTER_MAIN.z() - 10.0f,
                    HOLSTER_SHAFT.x(), HOLSTER_SHAFT.y(), HOLSTER_SHAFT.z(),
                    HOLSTER_HEAD.x(), HOLSTER_HEAD.y(), HOLSTER_HEAD.z(), 5.8f,
                    pose(0.025f, -0.12f, -0.035f, -0.015f, 0.08f,
                            -0.04f, -0.015f, 0.009f, -0.009f, 0.11f, 0.08f, 0.24f)),
            // После освобождения втулки древко переводится вверх по внешней дуге.
            anchor(0.46f, -6.00f, 18.00f, -2.00f, 0.35f, -0.82f, -0.45f,
                    -0.72f, 0.05f, 0.69f, 6.4f,
                    pose(-0.02f, -0.16f, -0.055f, 0.0f, 0.10f,
                            -0.06f, -0.025f, 0.012f, -0.012f, 0.14f, 0.10f, 0.30f)),
            anchor(0.64f, -4.85f, 8.60f, -2.20f, 0.89f, -0.20f, -0.41f,
                    0.10f, 0.91f, 0.40f, 7.7f,
                    pose(-0.025f, -0.10f, -0.035f, -0.01f, 0.06f,
                            -0.055f, -0.03f, 0.009f, -0.009f, 0.12f, 0.10f, 0.34f)),
            anchor(0.82f, -4.10f, 9.25f, -3.05f, 0.985f, 0.10f, 0.14f,
                    0.06f, 0.95f, 0.30f, 11.4f,
                    pose(0.01f, 0.02f, -0.02f, -0.01f, 0.0f,
                            -0.05f, -0.03f, 0.006f, -0.006f, 0.09f, 0.09f, 0.52f)),
            anchor(1.00f, -3.80f, 9.20f, -3.20f, 0.995f, 0.095f, 0.020f,
                    0.05f, 0.96f, 0.25f, IDLE_GRIP_DISTANCE,
                    pose(0.015f, 0.035f, -0.015f, -0.01f, -0.01f,
                            -0.05f, -0.03f, 0.006f, -0.006f, 0.085f, 0.085f, 0.62f))
    };

    private static final Anchor[] STRIKE_PATH = {
            // Спокойное ношение: обе кисти ниже пояса, боёк висит у внешней стороны бедра.
            anchor(0.00f, -3.80f, 9.20f, -3.20f, 0.995f, 0.095f, 0.020f,
                    0.05f, 0.96f, 0.25f, IDLE_GRIP_DISTANCE,
                    pose(0.015f, 0.035f, -0.015f, -0.01f, -0.01f,
                            -0.05f, -0.03f, 0.006f, -0.006f, 0.085f, 0.085f, 0.62f)),
            // Предварительное натяжение начинается только после команды удара: масса ещё остаётся низко.
            anchor(0.10f, -3.20f, 8.80f, -3.80f, 0.96f, -0.10f, -0.26f,
                    0.18f, 0.82f, 0.54f, 11.50f,
                    pose(-0.06f, -0.10f, -0.04f, -0.02f, 0.05f,
                            -0.10f, -0.06f, 0.012f, -0.012f, 0.17f, 0.12f, 0.72f)),
            // Начало кругового заноса: кисти уходят вперёд и наружу, а не сквозь грудь.
            anchor(0.20f, 1.50f, 6.00f, -4.70f, 0.82f, -0.42f, -0.30f,
                    0.474f, 0.474f, 0.747f, 9.50f,
                    pose(-0.16f, -0.32f, -0.09f, 0.00f, 0.14f,
                            -0.16f, -0.10f, 0.018f, -0.018f, 0.24f, 0.17f, 0.82f)),
            // Древко пересекает плоскость спины только после выхода сбоку от силуэта.
            anchor(0.29f, 0.80f, 5.00f, -5.00f, 0.88f, -0.15f, 0.45f,
                    0.18f, 0.98f, 0.00f, 9.00f,
                    pose(-0.20f, -0.42f, -0.10f, 0.03f, 0.16f,
                            -0.18f, -0.11f, 0.020f, -0.020f, 0.28f, 0.20f, 0.88f)),
            // Подхват массы ногами и тазом.
            anchor(0.38f, -0.30f, 3.00f, -6.50f, 0.55f, -0.82f, -0.15f,
                    0.83f, 0.56f, 0.00f, 7.50f,
                    pose(-0.14f, -0.28f, -0.06f, 0.07f, 0.11f,
                            -0.17f, -0.14f, 0.022f, -0.022f, 0.27f, 0.24f, 0.94f)),
            // Верхняя точка: головка над и перед персонажем, локти остаются согнутыми.
            anchor(0.43f, 0.00f, 2.00f, -5.50f, 0.15f, -0.985f, -0.08f,
                    0.99f, 0.15f, 0.00f, 5.80f,
                    pose(-0.08f, -0.12f, -0.03f, 0.13f, 0.06f,
                            -0.18f, -0.17f, 0.024f, -0.024f, 0.28f, 0.27f, 0.98f)),
            // Разгон по дуге: кисти ведут рукоять, а корпус догоняет инструмент.
            anchor(0.49f, 0.00f, 7.00f, -5.30f, 0.36f, -0.78f, -0.42f,
                    0.99f, 0.10f, 0.133f, 6.00f,
                    pose(0.12f, 0.12f, 0.03f, 0.20f, 0.02f,
                            -0.24f, -0.20f, 0.026f, -0.026f, 0.34f, 0.28f, 1.02f)),
            // Предконтактная точка удерживает касательную дуги и не является остановкой.
            anchor(0.56f, 1.00f, 5.00f, -6.00f, 0.67f, 0.30f, -0.45f,
                    0.99f, -0.10f, 0.10f, 4.60f,
                    pose(0.28f, 0.23f, 0.05f, 0.27f, -0.02f,
                            -0.28f, -0.23f, 0.028f, -0.028f, 0.40f, 0.34f, 1.04f)),
            // Подвод к поверхности: конец рукояти уже проходит перед лицом, а боёк ещё не касается земли.
            anchor(0.59f, 0.50f, 2.50f, -6.90f, 0.50f, 0.70f, -0.35f,
                    0.985f, -0.15f, 0.05f, 4.60f,
                    pose(0.31f, 0.245f, 0.055f, 0.286f, -0.03f,
                            -0.29f, -0.24f, 0.030f, -0.030f, 0.42f, 0.35f, 1.06f)),
            // Контакт: нижняя грань бойка доходит до плоскости земли перед ногами.
            anchor(IMPACT_PROGRESS, 0.00f, 1.45f, -6.50f, 0.25f, 0.90f, -0.32f,
                    0.98f, -0.20f, 0.00f, 4.60f,
                    pose(0.34f, 0.26f, 0.06f, 0.30f, -0.04f,
                            -0.30f, -0.25f, 0.032f, -0.032f, 0.44f, 0.36f, 1.08f)),
            // Сопровождение после контакта не даёт сплайну вернуть рукоять через голову.
            anchor(0.66f, -0.90f, 1.80f, -5.85f, 0.32f, 0.90f, -0.29f,
                    0.93f, -0.36f, 0.00f, 4.70f,
                    pose(0.35f, 0.29f, 0.07f, 0.275f, -0.055f,
                            -0.28f, -0.23f, 0.030f, -0.030f, 0.41f, 0.335f, 1.05f)),
            // Сопровождение удара без мгновенной остановки массы.
            anchor(0.73f, 0.00f, 2.55f, -6.10f, 0.62f, 0.74f, -0.25f,
                    0.85f, -0.53f, 0.00f, 5.00f,
                    pose(0.36f, 0.32f, 0.08f, 0.25f, -0.07f,
                            -0.26f, -0.21f, 0.026f, -0.026f, 0.38f, 0.31f, 1.00f)),
            // Гашение импульса. Боёк остаётся поперечным древку и не превращается в «якорь».
            anchor(0.86f, -1.00f, 8.00f, -4.00f, 0.76f, -0.57f, -0.31f,
                    0.835f, 0.209f, 0.509f, 9.90f,
                    pose(0.16f, 0.17f, 0.03f, 0.09f, -0.04f,
                            -0.16f, -0.10f, 0.018f, -0.018f, 0.24f, 0.18f, 0.86f)),
            // Ладони гасят остаточный импульс и опускают головку, не возвращаясь в боевую стойку.
            anchor(0.91f, -2.00f, 8.70f, -3.80f, 0.88f, -0.39f, -0.26f,
                    0.70f, 0.30f, 0.64f, 11.80f,
                    pose(0.08f, 0.12f, -0.03f, 0.00f, -0.03f,
                            -0.11f, -0.07f, 0.012f, -0.012f, 0.18f, 0.13f, 0.74f)),
            anchor(0.96f, -3.20f, 9.10f, -3.55f, 0.98f, -0.05f, -0.12f,
                    0.20f, 0.85f, 0.45f, 12.00f,
                    pose(0.04f, 0.07f, -0.02f, -0.01f, -0.02f,
                            -0.07f, -0.04f, 0.008f, -0.008f, 0.13f, 0.10f, 0.66f)),
            anchor(1.00f, -3.80f, 9.20f, -3.20f, 0.995f, 0.095f, 0.020f,
                    0.05f, 0.96f, 0.25f, IDLE_GRIP_DISTANCE,
                    pose(0.015f, 0.035f, -0.015f, -0.01f, -0.01f,
                            -0.05f, -0.03f, 0.006f, -0.006f, 0.085f, 0.085f, 0.62f))
    };
    private static final Spline MAIN_X = spline(index -> STRIKE_PATH[index].mainGrip.x);
    private static final Spline MAIN_Y = spline(index -> STRIKE_PATH[index].mainGrip.y);
    private static final Spline MAIN_Z = spline(index -> STRIKE_PATH[index].mainGrip.z);
    private static final Spline SHAFT_X = spline(index -> STRIKE_PATH[index].shaft.x);
    private static final Spline SHAFT_Y = spline(index -> STRIKE_PATH[index].shaft.y);
    private static final Spline SHAFT_Z = spline(index -> STRIKE_PATH[index].shaft.z);
    private static final Spline HEAD_ROLL = spline(HeavyHammerProceduralMotion::unwrappedRoll);
    private static final Spline GRIP_DISTANCE = spline(index -> STRIKE_PATH[index].gripDistance);
    private static final Spline TORSO_PITCH = spline(index -> STRIKE_PATH[index].bodyPose.torsoPitch);
    private static final Spline TORSO_YAW = spline(index -> STRIKE_PATH[index].bodyPose.torsoYaw);
    private static final Spline TORSO_ROLL = spline(index -> STRIKE_PATH[index].bodyPose.torsoRoll);
    private static final Spline HEAD_PITCH = spline(index -> STRIKE_PATH[index].bodyPose.headPitch);
    private static final Spline HEAD_YAW = spline(index -> STRIKE_PATH[index].bodyPose.headYaw);
    private static final Spline RIGHT_LEG_PITCH = spline(index -> STRIKE_PATH[index].bodyPose.rightLegPitch);
    private static final Spline LEFT_LEG_PITCH = spline(index -> STRIKE_PATH[index].bodyPose.leftLegPitch);
    private static final Spline RIGHT_LEG_ROLL = spline(index -> STRIKE_PATH[index].bodyPose.rightLegRoll);
    private static final Spline LEFT_LEG_ROLL = spline(index -> STRIKE_PATH[index].bodyPose.leftLegRoll);
    private static final Spline RIGHT_KNEE = spline(index -> STRIKE_PATH[index].bodyPose.rightKnee);
    private static final Spline LEFT_KNEE = spline(index -> STRIKE_PATH[index].bodyPose.leftKnee);
    private static final Spline STANCE_WIDTH = spline(index -> STRIKE_PATH[index].bodyPose.stanceWidth);

    private HeavyHammerProceduralMotion() {
    }

    public static Frame idle(float ageTicks) {
        return idle(ageTicks, 0.0f);
    }

    public static Frame idle(float ageTicks, float locomotion) {
        Frame base = frame(0.0f);
        float breath = (float) Math.sin(ageTicks * 0.055f);
        float moving = smootherStep(clamp(locomotion * 7.0f, 0.0f, 1.0f));
        float carrySway = (float) Math.sin(ageTicks * 0.55f) * moving;
        Vec3 main = base.mainGrip.add(carrySway * 0.12f,
                breath * 0.06f + Math.abs(carrySway) * 0.08f, carrySway * 0.18f);
        Vec3 shaft = base.shaft.add(0.0f, 0.0f,
                breath * 0.012f + carrySway * 0.025f).normalized();
        BodyPose breathingPose = base.bodyPose.withBreath(breath * 0.008f).withLocomotion(moving);
        return compose(0.0f, main, shaft, base.headRoll, base.gripDistance, breathingPose);
    }

    /** Возвращает единый мировой кадр молота от закрытого подвеса до рабочего хвата. */
    public static Frame draw(float progress, float ageTicks, float locomotion) {
        float sampled = clamp(progress, 0.0f, 1.0f);
        int segment = DRAW_PATH.length - 2;
        for (int index = 1; index < DRAW_PATH.length; index++) {
            if (sampled <= DRAW_PATH[index].time) {
                segment = index - 1;
                break;
            }
        }
        Anchor from = DRAW_PATH[segment];
        Anchor to = DRAW_PATH[segment + 1];
        float local = smootherStep(clamp((sampled - from.time)
                / Math.max(0.0001f, to.time - from.time), 0.0f, 1.0f));
        Vec3 main = Vec3.lerp(from.mainGrip, to.mainGrip, local);
        Vec3 shaft = Vec3.lerp(from.shaft, to.shaft, local).normalized();
        Vec3 headHint = Vec3.lerp(from.headHint, to.headHint, local).normalized();
        float gripDistance = lerp(from.gripDistance, to.gripDistance, local);
        BodyPose body = BodyPose.lerp(from.bodyPose, to.bodyPose, local);

        float carryBlend = smootherStep(clamp((sampled - 0.82f) / 0.18f, 0.0f, 1.0f));
        if (carryBlend > 0.0f) {
            Frame carry = idle(ageTicks, locomotion);
            main = Vec3.lerp(main, carry.mainGrip, carryBlend);
            shaft = Vec3.lerp(shaft, carry.shaft, carryBlend).normalized();
            headHint = Vec3.lerp(headHint, carry.headAxis, carryBlend).normalized();
            gripDistance = lerp(gripDistance, carry.gripDistance, carryBlend);
            body = BodyPose.lerp(body, carry.bodyPose, carryBlend);
        }
        return compose(sampled, main, shaft, rollFor(shaft, headHint), gripDistance, body);
    }

    /**
     * Переводит молот из диагонального положения у правого бока в низкое
     * двуручное ношение. Левая ладонь сначала подхватывает середину рукояти,
     * затем скользит к рабочей точке; головка опускается по внешней дуге.
     */
    public static Frame equip(float progress, float ageTicks, float locomotion) {
        float sampled = clamp(progress, 0.0f, 1.0f);
        Frame carry = idle(ageTicks, locomotion);
        float toolMove = smootherStep(clamp((sampled - 0.04f) / 0.76f, 0.0f, 1.0f));
        float handSlide = smootherStep(clamp((sampled - 0.24f) / 0.56f, 0.0f, 1.0f));
        float bodyAccept = smootherStep(clamp(sampled / 0.86f, 0.0f, 1.0f));
        Vec3 main = Vec3.lerp(EQUIP_MAIN_GRIP, carry.mainGrip, toolMove);
        Vec3 shaft = Vec3.lerp(EQUIP_SHAFT, carry.shaft, toolMove).normalized();
        float startRoll = rollFor(EQUIP_SHAFT, EQUIP_HEAD_HINT);
        float headRoll = startRoll + shortestAngle(startRoll, carry.headRoll) * toolMove;
        float gripDistance = lerp(EQUIP_GRIP_DISTANCE, carry.gripDistance, handSlide);
        BodyPose body = BodyPose.lerp(EQUIP_BODY_POSE, carry.bodyPose, bodyAccept);

        // После принятия массы руки и колени гасят короткое проседание, а не
        // останавливают тяжёлую головку математически в одном кадре.
        float settlePhase = clamp((sampled - 0.68f) / 0.32f, 0.0f, 1.0f);
        float settle = (float) Math.sin(settlePhase * Math.PI) * 0.26f;
        main = main.add(0.0f, settle, 0.0f);
        return compose(sampled, main, shaft, headRoll, gripDistance, body);
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
        Vec3 contactNormal = surfaceNormal.normalized();
        float contactTiming = 0.0f;
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
            contactTiming = target.surface == Surface.SIDE ? 0.0f : contactPlaneInfluence(progress);
            float surfaceRoll = rollFor(base.shaft, surfaceNormal);
            aimedRoll += shortestAngle(aimedRoll, surfaceRoll) * orientationTiming * surfaceWeight;
        }
        Frame aimed = compose(progress, base.mainGrip, base.shaft, aimedRoll, base.gripDistance, aimedBody);
        if (contactTiming <= 0.0f) return aimed;

        // Положение цели задаёт плоскость контакта. Корректируем только короткий остаток вдоль
        // нормали, чтобы удлинённая модель не проходила сквозь поверхность и не телескопировалась к далёкой цели.
        Vec3 normal = contactNormal;
        HeavyHammerSpatialRules.OrientedBox headBox = HeavyHammerSpatialRules.headBox(aimed);
        float support = Math.abs(headBox.axisX().dot(normal)) * headBox.extentX()
                + Math.abs(headBox.axisY().dot(normal)) * headBox.extentY()
                + Math.abs(headBox.axisZ().dot(normal)) * headBox.extentZ();
        Vec3 targetPoint = new Vec3(target.x, target.y, target.z);
        float currentPlane = headBox.center().dot(normal) + support;
        float planeError = targetPoint.dot(normal) - currentPlane;
        // Не подтягиваем боёк к поверхности, когда он уже отрывается после удара:
        // коррекция работает только против проникновения и потому не создаёт «липкой» паузы.
        float correction = clamp(planeError, -3.25f, 0.0f) * contactTiming;
        Vec3 correctedMain = base.mainGrip.add(normal.scale(correction));
        return compose(progress, correctedMain, base.shaft, aimedRoll, base.gripDistance, aimedBody);
    }

    public static MotionMetrics metrics(float elapsedTicks, Target target) {
        float centerTick = clamp(elapsedTicks, 0.0f, HeavyHammerAnimation.DURATION_TICKS);
        float previousTick = Math.max(0.0f, centerTick - 0.04f);
        float nextTick = Math.min(HeavyHammerAnimation.DURATION_TICKS, centerTick + 0.04f);
        Frame previous = strike(previousTick / HeavyHammerAnimation.DURATION_TICKS, target);
        Frame center = strike(centerTick / HeavyHammerAnimation.DURATION_TICKS, target);
        Frame next = strike(nextTick / HeavyHammerAnimation.DURATION_TICKS, target);
        float beforeDuration = Math.max(0.0001f, centerTick - previousTick);
        float afterDuration = Math.max(0.0001f, nextTick - centerTick);
        float fullDuration = Math.max(0.0001f, nextTick - previousTick);
        Vec3 velocity = next.headCenter.subtract(previous.headCenter).scale(1.0f / fullDuration);
        Vec3 beforeVelocity = center.headCenter.subtract(previous.headCenter).scale(1.0f / beforeDuration);
        Vec3 afterVelocity = next.headCenter.subtract(center.headCenter).scale(1.0f / afterDuration);
        float acceleration = afterVelocity.subtract(beforeVelocity).scale(2.0f / fullDuration).length();
        float headAngularSpeed = Math.max(angleBetween(previous.headAxis, next.headAxis),
                angleBetween(previous.shaft, next.shaft)) / fullDuration;
        float gripSlideRate = Math.abs(next.gripDistance - previous.gripDistance) / fullDuration;
        return new MotionMetrics(velocity.length(), acceleration, headAngularSpeed, gripSlideRate,
                HEAD_DISTANCE - center.gripDistance);
    }

    private static float angleBetween(Vec3 first, Vec3 second) {
        return (float) Math.acos(clamp(first.normalized().dot(second.normalized()), -1.0f, 1.0f));
    }

    private static Frame frame(float progress) {
        float sampledProgress = clamp(progress, 0.0f, 1.0f);
        // Зажатый кубический сплайн сохраняет общие производные между всеми фазами и нулевую
        // скорость только в начале и конце полного движения, а не в каждом опорном кадре.
        Vec3 main = new Vec3(
                MAIN_X.sample(sampledProgress), MAIN_Y.sample(sampledProgress), MAIN_Z.sample(sampledProgress));
        Vec3 shaft = new Vec3(
                SHAFT_X.sample(sampledProgress), SHAFT_Y.sample(sampledProgress),
                SHAFT_Z.sample(sampledProgress)).normalized();
        BodyPose body = new BodyPose(
                TORSO_PITCH.sample(sampledProgress), TORSO_YAW.sample(sampledProgress),
                TORSO_ROLL.sample(sampledProgress), HEAD_PITCH.sample(sampledProgress),
                HEAD_YAW.sample(sampledProgress), RIGHT_LEG_PITCH.sample(sampledProgress),
                LEFT_LEG_PITCH.sample(sampledProgress), RIGHT_LEG_ROLL.sample(sampledProgress),
                LEFT_LEG_ROLL.sample(sampledProgress), RIGHT_KNEE.sample(sampledProgress),
                LEFT_KNEE.sample(sampledProgress), STANCE_WIDTH.sample(sampledProgress));
        return compose(sampledProgress, main, shaft, HEAD_ROLL.sample(sampledProgress),
                GRIP_DISTANCE.sample(sampledProgress), body);
    }

    private static Spline spline(AnchorChannel channel) {
        float[] values = new float[STRIKE_PATH.length];
        for (int index = 0; index < values.length; index++) values[index] = channel.value(index);
        return new Spline(values);
    }

    private static float unwrappedRoll(int index) {
        float result = rollFor(STRIKE_PATH[0].shaft, STRIKE_PATH[0].headHint);
        for (int current = 1; current <= index; current++) {
            float next = rollFor(STRIKE_PATH[current].shaft, STRIKE_PATH[current].headHint);
            result += shortestAngle(result, next);
        }
        return result;
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
        float fall = 1.0f - smootherStep(clamp((progress - 0.68f) / 0.08f, 0.0f, 1.0f));
        return rise * fall;
    }

    private static float sideSurfaceInfluence(float progress) {
        float rise = smootherStep(clamp((progress - 0.50f) / (IMPACT_PROGRESS - 0.50f), 0.0f, 1.0f));
        float fall = 1.0f - smootherStep(clamp((progress - 0.68f) / 0.08f, 0.0f, 1.0f));
        return rise * fall;
    }

    private static float contactPlaneInfluence(float progress) {
        // Плоскость влияет только на сам контакт: раннее «прилипание» бойка к земле
        // поднимало конец рукояти через голову ещё до фактического удара.
        float start = 0.54f;
        float rise = smootherStep(clamp((progress - start) / (IMPACT_PROGRESS - start), 0.0f, 1.0f));
        float fall = 1.0f - smootherStep(clamp((progress - 0.73f) / 0.08f, 0.0f, 1.0f));
        return rise * fall;
    }

    private static float lerp(float from, float to, float amount) {
        return from + (to - from) * amount;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    @FunctionalInterface
    private interface AnchorChannel {
        float value(int index);
    }

    private static final class Spline {
        private final float[] values;
        private final float[] secondDerivatives;

        private Spline(float[] values) {
            this.values = values;
            this.secondDerivatives = solveSecondDerivatives(values);
        }

        private float sample(float progress) {
            int segment = STRIKE_PATH.length - 2;
            for (int index = 1; index < STRIKE_PATH.length; index++) {
                if (progress <= STRIKE_PATH[index].time) {
                    segment = index - 1;
                    break;
                }
            }
            float fromTime = STRIKE_PATH[segment].time;
            float toTime = STRIKE_PATH[segment + 1].time;
            float duration = Math.max(0.0001f, toTime - fromTime);
            float before = (toTime - progress) / duration;
            float after = (progress - fromTime) / duration;
            float curvature = duration * duration / 6.0f;
            return before * values[segment] + after * values[segment + 1]
                    + ((before * before * before - before) * secondDerivatives[segment]
                    + (after * after * after - after) * secondDerivatives[segment + 1]) * curvature;
        }

        private static float[] solveSecondDerivatives(float[] values) {
            int count = values.length;
            float[] lower = new float[count];
            float[] diagonal = new float[count];
            float[] upper = new float[count];
            float[] result = new float[count];
            float firstDuration = STRIKE_PATH[1].time - STRIKE_PATH[0].time;
            diagonal[0] = 2.0f * firstDuration;
            upper[0] = firstDuration;
            result[0] = 6.0f * (values[1] - values[0]) / firstDuration;

            for (int index = 1; index < count - 1; index++) {
                float beforeDuration = STRIKE_PATH[index].time - STRIKE_PATH[index - 1].time;
                float afterDuration = STRIKE_PATH[index + 1].time - STRIKE_PATH[index].time;
                lower[index] = beforeDuration;
                diagonal[index] = 2.0f * (beforeDuration + afterDuration);
                upper[index] = afterDuration;
                result[index] = 6.0f * ((values[index + 1] - values[index]) / afterDuration
                        - (values[index] - values[index - 1]) / beforeDuration);
            }

            float lastDuration = STRIKE_PATH[count - 1].time - STRIKE_PATH[count - 2].time;
            lower[count - 1] = lastDuration;
            diagonal[count - 1] = 2.0f * lastDuration;
            result[count - 1] = -6.0f * (values[count - 1] - values[count - 2]) / lastDuration;

            for (int index = 1; index < count; index++) {
                float factor = lower[index] / diagonal[index - 1];
                diagonal[index] -= factor * upper[index - 1];
                result[index] -= factor * result[index - 1];
            }
            result[count - 1] /= diagonal[count - 1];
            for (int index = count - 2; index >= 0; index--) {
                result[index] = (result[index] - upper[index] * result[index + 1]) / diagonal[index];
            }
            return result;
        }
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

        private BodyPose withLocomotion(float amount) {
            return new BodyPose(torsoPitch + amount * 0.012f, torsoYaw, torsoRoll,
                    headPitch - amount * 0.006f, headYaw,
                    HeavyHammerProceduralMotion.lerp(rightLegPitch, 0.0f, amount),
                    HeavyHammerProceduralMotion.lerp(leftLegPitch, 0.0f, amount),
                    HeavyHammerProceduralMotion.lerp(rightLegRoll, 0.0f, amount),
                    HeavyHammerProceduralMotion.lerp(leftLegRoll, 0.0f, amount),
                    HeavyHammerProceduralMotion.lerp(rightKnee, 0.08f, amount),
                    HeavyHammerProceduralMotion.lerp(leftKnee, 0.08f, amount),
                    HeavyHammerProceduralMotion.lerp(stanceWidth, 0.12f, amount));
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

    public record MotionMetrics(float headSpeed, float headAcceleration, float headAngularSpeed,
                                float gripSlideRate, float unsupportedLever) {
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
