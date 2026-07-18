package ua.rp.chat;

public final class HeavyHammerAnimationTest {
    public static void main(String[] args) {
        HeavyHammerAnimation.Sample idle = HeavyHammerAnimation.idle(0.0f);
        require(gripError(idle) < 0.03f, "Обе ладони должны точно совпадать с точками хвата");
        require(idle.mainClampDistance() < 0.03f && idle.gripClampDistance() < 0.03f,
                "Рабочая стойка должна быть достижима обеими руками");
        require(orthonormal(idle), "Оси жёсткого молота должны образовывать ортонормальный базис");
        HeavyHammerProceduralMotion.Frame idleFrame = HeavyHammerProceduralMotion.idle(0.0f);
        require(idleFrame.gripDistance() >= 12.2f && idleFrame.gripDistance() <= 12.6f,
                "Спокойный хват должен оставаться широким, но позволять обеим рукам висеть вниз");
        require(HeavyHammerProceduralMotion.HEAD_DISTANCE - idleFrame.gripDistance() <= 7.6f,
                "Поддерживающая ладонь не должна оставлять неконтролируемый рычаг перед головкой");
        require(idleFrame.mainGrip().y() >= 8.8f && idleFrame.mainGrip().y() <= 10.0f
                        && idleFrame.offhandGrip().y() >= 9.5f && idleFrame.offhandGrip().y() <= 11.0f,
                "В carry idle обе ладони должны находиться ниже пояса");
        require(idleFrame.headCenter().y() >= 10.5f && idleFrame.headCenter().x() >= 13.0f,
                "В carry idle головка должна висеть снаружи у бедра, а не возле плеча");
        require(idleFrame.shaft().y() > 0.06f && idleFrame.shaft().y() < 0.14f,
                "Древко в покое должно слегка опускаться к тяжёлой головке");
        require(Math.abs(idle.bodyX()) < 0.05f && Math.abs(idle.bodyY()) < 0.06f
                        && Math.abs(idle.bodyZ()) < 0.04f,
                "Спокойное ношение не должно выглядеть как уже начатый замах корпуса");
        require(Math.atan2(Math.abs(idleFrame.shaft().z()), Math.abs(idleFrame.shaft().x()))
                        < Math.toRadians(3.0),
                "Древко в стойке должно проходить почти поперёк персонажа, а не уходить по диагонали в глубину");
        require(idle.rightLegZ() > 0.0f && idle.leftLegZ() < 0.0f,
                "Стопы силовой стойки должны раскрываться наружу");
        require(footCenterSeparation(idle) >= 5.4f && footCenterSeparation(idle) <= 6.3f,
                "Центры стоп в спокойной стойке должны быть немного шире таза, но уже плеч: "
                        + footCenterSeparation(idle));
        require(idle.leftWristTwist() < -0.45f,
                "Левая ладонь в стойке должна разворачиваться под древко");
        for (int tick = 0; tick < 240; tick++) {
            HeavyHammerAnimation.Sample carry = HeavyHammerAnimation.idle(tick / 4.0f, 0.22f);
            require(gripError(carry) < 0.03f && carry.mainClampDistance() < 0.03f
                            && carry.gripClampDistance() < 0.03f,
                    "Инерция при ходьбе не должна разрывать двуручный хват");
        }

        HeavyHammerAnimation.Sample previousEquip = HeavyHammerAnimation.equip(0.0f, 0.0f, 0.0f);
        float previousPoseWeight = previousEquip.poseWeight();
        float previousOffhandWeight = previousEquip.offhandWeight();
        int equipSamples = (int) (HeavyHammerAnimation.EQUIP_DURATION_TICKS * 20.0f);
        for (int sample = 1; sample <= equipSamples; sample++) {
            float ticks = sample / 20.0f;
            HeavyHammerAnimation.Sample equip = HeavyHammerAnimation.equip(ticks, ticks, 0.0f);
            require(finite(equip) && orthonormal(equip),
                    "Все каналы взятия молота должны оставаться конечными: sample=" + sample);
            require(equip.mainClampDistance() < 0.03f && equip.gripClampDistance() < 0.03f,
                    "Взятие молота не должно растягивать руки: sample=" + sample
                            + ", main=" + equip.mainClampDistance() + ", offhand=" + equip.gripClampDistance());
            require(equip.poseWeight() + 0.0001f >= previousPoseWeight,
                    "Основная рука должна принимать вес без обратного скачка: sample=" + sample);
            require(equip.offhandWeight() + 0.0001f >= previousOffhandWeight,
                    "Левая рука должна непрерывно подхватывать рукоять: sample=" + sample);
            require(distance(previousEquip, equip) < 0.20f,
                    "Между кадрами взятия молота не должно быть телепорта: sample=" + sample);
            previousEquip = equip;
            previousPoseWeight = equip.poseWeight();
            previousOffhandWeight = equip.offhandWeight();
        }
        HeavyHammerAnimation.Sample equipEnd = HeavyHammerAnimation.equip(
                HeavyHammerAnimation.EQUIP_DURATION_TICKS, HeavyHammerAnimation.EQUIP_DURATION_TICKS, 0.0f);
        require(equipEnd.poseWeight() > 0.999f && equipEnd.offhandWeight() > 0.999f,
                "К концу взятия обе руки должны полностью принять молот");
        require(distance(HeavyHammerAnimation.idle(HeavyHammerAnimation.EQUIP_DURATION_TICKS, 0.0f), equipEnd) < 0.08f,
                "Взятие должно бесшовно завершаться в спокойном carry idle");

        HeavyHammerProceduralMotion.Frame behind = HeavyHammerProceduralMotion.strike(0.28f);
        HeavyHammerProceduralMotion.Frame overhead = HeavyHammerProceduralMotion.strike(0.43f);
        HeavyHammerProceduralMotion.Frame impactFrame = HeavyHammerProceduralMotion.strike(
                HeavyHammerProceduralMotion.IMPACT_PROGRESS);
        require(behind.headCenter().z() > 2.5f, "Круговой замах должен уводить головку за спину");
        require(overhead.headCenter().y() < -12.0f, "Перед ускорением головка должна пройти над плечами");
        require(impactFrame.headCenter().y() > 18.0f && impactFrame.headCenter().z() < -9.0f,
                "В контакте головка должна быть направлена вниз и вперёд");

        HeavyHammerAnimation.Sample impact = HeavyHammerAnimation.strike(HeavyHammerAnimation.IMPACT_TICK);
        require(impact.bodyX() > 0.10f, "Кадр контакта должен переносить вес корпуса вперёд");
        require(footCenterSeparation(impact) > footCenterSeparation(idle) + 1.5f,
                "В контакте ноги должны расширять опору под тяжёлым инструментом");
        require(impact.rightKnee() > 0.25f && impact.leftKnee() > 0.20f,
                "Удар должен амортизироваться обоими коленями");
        require(impact.leftWristTwist() > -0.20f,
                "Перед контактом поддерживающая ладонь должна закрыть силовой хват");
        require(HeavyHammerAnimation.impactReached(HeavyHammerAnimation.IMPACT_TICK - 0.1f,
                HeavyHammerAnimation.IMPACT_TICK), "Контакт должен срабатывать ровно один раз");
        require(!HeavyHammerAnimation.impactReached(HeavyHammerAnimation.IMPACT_TICK,
                HeavyHammerAnimation.IMPACT_TICK + 0.1f), "Контакт нельзя повторять после прохождения кадра");

        HeavyHammerAnimation.Sample previous = HeavyHammerAnimation.strike(0.0f);
        int totalSamples = (int) (HeavyHammerAnimation.DURATION_TICKS * 20.0f);
        float maximumDelta = 0.0f;
        int maximumDeltaSample = 0;
        float maximumAxisDelta = 0.0f;
        int maximumAxisDeltaSample = 0;
        for (int sample = 1; sample <= totalSamples; sample++) {
            HeavyHammerAnimation.Sample current = HeavyHammerAnimation.strike(sample / 20.0f);
            require(finite(current), "Все каналы процедурной анимации должны оставаться конечными");
            require(gripError(current) < 0.05f,
                    "Левая ладонь не должна соскальзывать с древка: sample=" + sample
                            + ", error=" + gripError(current) + ", main=" + current.mainClampDistance()
                            + ", offhand=" + current.gripClampDistance());
            require(current.mainClampDistance() < 0.03f && current.gripClampDistance() < 0.03f,
                    "Процедурная траектория не должна растягивать руки: sample=" + sample
                            + ", main=" + current.mainClampDistance() + ", offhand=" + current.gripClampDistance());
            require(orthonormal(current), "Ориентация молота не должна накапливать сдвиг или масштаб");
            require(current.rightLower() >= 0.40f && current.leftLower() >= 0.40f,
                    "Локоть не должен входить в почти прямую сингулярность: sample=" + sample);
            require(current.rightLower() <= 2.50f && current.leftLower() <= 2.50f,
                    "Локоть не должен складываться за анатомический предел: sample=" + sample);
            require(footCenterSeparation(current) >= 5.35f,
                    "Стопы не должны сходиться или перекрещиваться: sample=" + sample
                            + ", separation=" + footCenterSeparation(current));
            float delta = distance(previous, current);
            if (delta > maximumDelta) {
                maximumDelta = delta;
                maximumDeltaSample = sample;
            }
            float axisDelta = axisDistance(previous, current);
            if (axisDelta > maximumAxisDelta) {
                maximumAxisDelta = axisDelta;
                maximumAxisDeltaSample = sample;
            }
            previous = current;
        }
        require(maximumDelta < 0.58f, "Между соседними кадрами не должно быть рывка: sample="
                + maximumDeltaSample + ", delta=" + maximumDelta);
        require(maximumAxisDelta < 0.10f, "Ось бойка не должна переворачиваться между кадрами: sample="
                + maximumAxisDeltaSample + ", delta=" + maximumAxisDelta);

        float maximumHeadSpeed = 0.0f;
        float maximumHeadAcceleration = 0.0f;
        float maximumHeadAngularSpeed = 0.0f;
        float maximumGripSlideRate = 0.0f;
        float minimumGripDistance = Float.MAX_VALUE;
        for (int sample = 2; sample < totalSamples - 2; sample++) {
            float ticks = sample / 20.0f;
            HeavyHammerProceduralMotion.MotionMetrics metrics = HeavyHammerProceduralMotion.metrics(ticks, null);
            HeavyHammerProceduralMotion.Frame frame = HeavyHammerProceduralMotion.strike(
                    ticks / HeavyHammerAnimation.DURATION_TICKS);
            maximumHeadSpeed = Math.max(maximumHeadSpeed, metrics.headSpeed());
            maximumHeadAcceleration = Math.max(maximumHeadAcceleration, metrics.headAcceleration());
            maximumHeadAngularSpeed = Math.max(maximumHeadAngularSpeed, metrics.headAngularSpeed());
            maximumGripSlideRate = Math.max(maximumGripSlideRate, metrics.gripSlideRate());
            minimumGripDistance = Math.min(minimumGripDistance, frame.gripDistance());
        }
        require(maximumHeadSpeed < 10.0f, "Скорость тяжёлой головки не должна давать телепорт: " + maximumHeadSpeed);
        require(maximumHeadAcceleration < 10.0f,
                "Ускорение головки должно оставаться непрерывным: " + maximumHeadAcceleration);
        require(maximumHeadAngularSpeed < 0.60f,
                "Поворот рабочего бойка не должен ломать силуэт: " + maximumHeadAngularSpeed);
        require(maximumGripSlideRate < 1.35f,
                "Левая ладонь должна скользить по древку контролируемо: " + maximumGripSlideRate);
        require(minimumGripDistance >= 4.40f,
                "Силовой хват не должен визуально накладывать кисти: " + minimumGripDistance);
        float[] internalProgress = {0.10f, 0.20f, 0.29f, 0.38f, 0.43f, 0.49f,
                0.56f, HeavyHammerProceduralMotion.IMPACT_PROGRESS, 0.73f, 0.86f};
        for (float progress : internalProgress) {
            float ticks = progress * HeavyHammerAnimation.DURATION_TICKS;
            require(HeavyHammerProceduralMotion.metrics(ticks, null).headSpeed() > 0.75f,
                    "Внутренняя опорная точка не должна останавливать молот: tick=" + ticks);
        }
        require(HeavyHammerProceduralMotion.metrics(0.91f * HeavyHammerAnimation.DURATION_TICKS, null)
                        .headSpeed() > 0.20f,
                "Финальный перехват может гасить молот, но не останавливать его");
        HeavyHammerProceduralMotion.Target groundTarget = new HeavyHammerProceduralMotion.Target(
                0.0f, 24.0f, -12.0f, HeavyHammerProceduralMotion.Surface.UP,
                0.0f, 1.0f, 0.0f);
        HeavyHammerAnimation.Sample previousTargeted = HeavyHammerAnimation.strike(0.0f, groundTarget);
        float targetedAxisDelta = 0.0f;
        for (int sample = 1; sample <= totalSamples; sample++) {
            HeavyHammerAnimation.Sample targeted = HeavyHammerAnimation.strike(sample / 20.0f, groundTarget);
            require(targeted.mainClampDistance() < 0.03f && targeted.gripClampDistance() < 0.03f,
                    "Наведение на поверхность не должно растягивать руки: sample=" + sample);
            require(targeted.rightLower() >= 0.40f && targeted.leftLower() >= 0.40f,
                    "Целевой удар не должен выпрямлять локоть в сингулярность: sample=" + sample);
            targetedAxisDelta = Math.max(targetedAxisDelta, axisDistance(previousTargeted, targeted));
            previousTargeted = targeted;
        }
        require(targetedAxisDelta < 0.11f,
                "Разворот рабочей грани к цели должен оставаться непрерывным: delta=" + targetedAxisDelta);
        HeavyHammerProceduralMotion.Target wallTarget = new HeavyHammerProceduralMotion.Target(
                0.0f, 10.0f, -12.0f, HeavyHammerProceduralMotion.Surface.SIDE,
                0.0f, 0.0f, -1.0f);
        HeavyHammerAnimation.Sample previousWall = HeavyHammerAnimation.strike(0.0f, wallTarget);
        float wallAxisDelta = 0.0f;
        for (int sample = 1; sample <= totalSamples; sample++) {
            HeavyHammerAnimation.Sample wall = HeavyHammerAnimation.strike(sample / 20.0f, wallTarget);
            wallAxisDelta = Math.max(wallAxisDelta, axisDistance(previousWall, wall));
            previousWall = wall;
        }
        require(wallAxisDelta < 0.11f,
                "Доворот бойка к стене не должен переворачивать ось: delta=" + wallAxisDelta);
        HeavyHammerAnimation.Sample end = HeavyHammerAnimation.strike(HeavyHammerAnimation.DURATION_TICKS);
        require(distance(idle, end) < 0.03f, "Удар должен бесшовно возвращаться в рабочую стойку");
        System.out.println("HeavyHammerAnimationTest passed");
    }

    private static boolean finite(HeavyHammerAnimation.Sample sample) {
        return Float.isFinite(sample.progress()) && Float.isFinite(sample.bodyX()) && Float.isFinite(sample.bodyY())
                && Float.isFinite(sample.bodyZ()) && Float.isFinite(sample.headX())
                && Float.isFinite(sample.headY()) && Float.isFinite(sample.rightLegX())
                && Float.isFinite(sample.leftLegX()) && Float.isFinite(sample.rightLegZ())
                && Float.isFinite(sample.leftLegZ()) && Float.isFinite(sample.rightKnee())
                && Float.isFinite(sample.leftKnee()) && Float.isFinite(sample.stanceWidth())
                && Float.isFinite(sample.rightX()) && Float.isFinite(sample.rightY())
                && Float.isFinite(sample.rightZ()) && Float.isFinite(sample.rightLower())
                && Float.isFinite(sample.rightWristTwist())
                && Float.isFinite(sample.leftX()) && Float.isFinite(sample.leftY())
                && Float.isFinite(sample.leftZ()) && Float.isFinite(sample.leftLower())
                && Float.isFinite(sample.leftWristTwist())
                && Float.isFinite(sample.gripX()) && Float.isFinite(sample.gripY())
                && Float.isFinite(sample.gripZ()) && Float.isFinite(sample.mainClampDistance())
                && Float.isFinite(sample.gripClampDistance()) && Float.isFinite(sample.headAxisX())
                && Float.isFinite(sample.headAxisY()) && Float.isFinite(sample.headAxisZ())
                && Float.isFinite(sample.shaftX()) && Float.isFinite(sample.shaftY())
                && Float.isFinite(sample.shaftZ()) && Float.isFinite(sample.depthAxisX())
                && Float.isFinite(sample.depthAxisY()) && Float.isFinite(sample.depthAxisZ())
                && Float.isFinite(sample.poseWeight()) && Float.isFinite(sample.offhandWeight())
                && Float.isFinite(sample.gaitWeight());
    }

    private static boolean orthonormal(HeavyHammerAnimation.Sample sample) {
        float headLength = length(sample.headAxisX(), sample.headAxisY(), sample.headAxisZ());
        float shaftLength = length(sample.shaftX(), sample.shaftY(), sample.shaftZ());
        float depthLength = length(sample.depthAxisX(), sample.depthAxisY(), sample.depthAxisZ());
        float headShaft = dot(sample.headAxisX(), sample.headAxisY(), sample.headAxisZ(),
                sample.shaftX(), sample.shaftY(), sample.shaftZ());
        float headDepth = dot(sample.headAxisX(), sample.headAxisY(), sample.headAxisZ(),
                sample.depthAxisX(), sample.depthAxisY(), sample.depthAxisZ());
        float shaftDepth = dot(sample.shaftX(), sample.shaftY(), sample.shaftZ(),
                sample.depthAxisX(), sample.depthAxisY(), sample.depthAxisZ());
        return Math.abs(headLength - 1.0f) < 0.002f && Math.abs(shaftLength - 1.0f) < 0.002f
                && Math.abs(depthLength - 1.0f) < 0.002f && Math.abs(headShaft) < 0.002f
                && Math.abs(headDepth) < 0.002f && Math.abs(shaftDepth) < 0.002f;
    }

    private static float distance(HeavyHammerAnimation.Sample left, HeavyHammerAnimation.Sample right) {
        return Math.abs(left.bodyX() - right.bodyX()) + Math.abs(left.bodyY() - right.bodyY())
                + Math.abs(left.bodyZ() - right.bodyZ())
                + Math.abs(left.headX() - right.headX()) + Math.abs(left.headY() - right.headY())
                + Math.abs(left.rightLegX() - right.rightLegX())
                + Math.abs(left.leftLegX() - right.leftLegX())
                + Math.abs(left.rightLegZ() - right.rightLegZ())
                + Math.abs(left.leftLegZ() - right.leftLegZ())
                + Math.abs(left.rightKnee() - right.rightKnee())
                + Math.abs(left.leftKnee() - right.leftKnee())
                + Math.abs(left.stanceWidth() - right.stanceWidth())
                + Math.abs(left.rightX() - right.rightX()) + angleDistance(left.rightZ(), right.rightZ())
                + Math.abs(left.rightLower() - right.rightLower())
                + Math.abs(left.rightWristTwist() - right.rightWristTwist())
                + Math.abs(left.leftX() - right.leftX()) + angleDistance(left.leftZ(), right.leftZ())
                + Math.abs(left.leftLower() - right.leftLower())
                + Math.abs(left.leftWristTwist() - right.leftWristTwist())
                + Math.abs(left.gripX() - right.gripX()) + Math.abs(left.gripY() - right.gripY())
                + Math.abs(left.gripZ() - right.gripZ());
    }

    private static float gripError(HeavyHammerAnimation.Sample sample) {
        HeavyHammerGripSolver.Point main = HeavyHammerGripSolver.hand(HeavyHammerAnimation.RIGHT_SHOULDER,
                sample.rightX(), sample.rightY(), sample.rightZ(), sample.rightLower());
        HeavyHammerGripSolver.Point target = main.add(sample.gripX(), sample.gripY(), sample.gripZ());
        HeavyHammerGripSolver.Point offhand = HeavyHammerGripSolver.hand(HeavyHammerAnimation.LEFT_SHOULDER,
                sample.leftX(), sample.leftY(), sample.leftZ(), sample.leftLower());
        return target.distanceTo(offhand);
    }

    private static float footCenterSeparation(HeavyHammerAnimation.Sample sample) {
        return ArticulatedLimbLayout.footCenterSeparation(
                sample.rightLegZ(), sample.leftLegZ(), sample.stanceWidth());
    }

    private static float axisDistance(HeavyHammerAnimation.Sample left, HeavyHammerAnimation.Sample right) {
        return length(left.headAxisX() - right.headAxisX(), left.headAxisY() - right.headAxisY(),
                left.headAxisZ() - right.headAxisZ())
                + length(left.depthAxisX() - right.depthAxisX(), left.depthAxisY() - right.depthAxisY(),
                left.depthAxisZ() - right.depthAxisZ());
    }

    private static float angleDistance(float left, float right) {
        float difference = Math.abs(left - right) % ((float) Math.PI * 2.0f);
        return Math.min(difference, (float) Math.PI * 2.0f - difference);
    }

    private static float length(float x, float y, float z) {
        return (float) Math.sqrt(x * x + y * y + z * z);
    }

    private static float dot(float ax, float ay, float az, float bx, float by, float bz) {
        return ax * bx + ay * by + az * bz;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
