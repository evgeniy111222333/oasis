package ua.rp.chat;

public final class HeavyHammerAnimationTest {
    public static void main(String[] args) {
        HeavyHammerAnimation.Sample idle = HeavyHammerAnimation.idle(0.0f);
        require(gripError(idle) < 0.03f, "Обе ладони должны точно совпадать с точками хвата");
        require(idle.mainClampDistance() < 0.03f && idle.gripClampDistance() < 0.03f,
                "Рабочая стойка должна быть достижима обеими руками");
        require(orthonormal(idle), "Оси жёсткого молота должны образовывать ортонормальный базис");
        for (int tick = 0; tick < 240; tick++) {
            HeavyHammerAnimation.Sample carry = HeavyHammerAnimation.idle(tick / 4.0f, 0.22f);
            require(gripError(carry) < 0.03f && carry.mainClampDistance() < 0.03f
                            && carry.gripClampDistance() < 0.03f,
                    "Инерция при ходьбе не должна разрывать двуручный хват");
        }

        HeavyHammerProceduralMotion.Frame behind = HeavyHammerProceduralMotion.strike(0.28f);
        HeavyHammerProceduralMotion.Frame overhead = HeavyHammerProceduralMotion.strike(0.43f);
        HeavyHammerProceduralMotion.Frame impactFrame = HeavyHammerProceduralMotion.strike(
                HeavyHammerProceduralMotion.IMPACT_PROGRESS);
        require(behind.headCenter().z() > 3.0f, "Круговой замах должен уводить головку за спину");
        require(overhead.headCenter().y() < -7.0f, "Перед ускорением головка должна пройти над плечами");
        require(impactFrame.headCenter().y() > 13.0f && impactFrame.headCenter().z() < -7.0f,
                "В контакте головка должна быть направлена вниз и вперёд");

        HeavyHammerAnimation.Sample impact = HeavyHammerAnimation.strike(HeavyHammerAnimation.IMPACT_TICK);
        require(impact.bodyX() > 0.10f, "Кадр контакта должен переносить вес корпуса вперёд");
        require(HeavyHammerAnimation.impactReached(20.9f, 21.0f), "Контакт должен срабатывать ровно один раз");
        require(!HeavyHammerAnimation.impactReached(21.0f, 21.1f), "Контакт нельзя повторять после прохождения кадра");

        HeavyHammerAnimation.Sample previous = HeavyHammerAnimation.strike(0.0f);
        float maximumDelta = 0.0f;
        int maximumDeltaSample = 0;
        float maximumAxisDelta = 0.0f;
        int maximumAxisDeltaSample = 0;
        for (int sample = 1; sample <= 680; sample++) {
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
        HeavyHammerAnimation.Sample end = HeavyHammerAnimation.strike(HeavyHammerAnimation.DURATION_TICKS);
        require(distance(idle, end) < 0.03f, "Удар должен бесшовно возвращаться в рабочую стойку");
        System.out.println("HeavyHammerAnimationTest passed");
    }

    private static boolean finite(HeavyHammerAnimation.Sample sample) {
        return Float.isFinite(sample.progress()) && Float.isFinite(sample.bodyX()) && Float.isFinite(sample.bodyY())
                && Float.isFinite(sample.rightX()) && Float.isFinite(sample.rightY())
                && Float.isFinite(sample.rightZ()) && Float.isFinite(sample.rightLower())
                && Float.isFinite(sample.leftX()) && Float.isFinite(sample.leftY())
                && Float.isFinite(sample.leftZ()) && Float.isFinite(sample.leftLower())
                && Float.isFinite(sample.gripX()) && Float.isFinite(sample.gripY())
                && Float.isFinite(sample.gripZ()) && Float.isFinite(sample.mainClampDistance())
                && Float.isFinite(sample.gripClampDistance()) && Float.isFinite(sample.headAxisX())
                && Float.isFinite(sample.headAxisY()) && Float.isFinite(sample.headAxisZ())
                && Float.isFinite(sample.shaftX()) && Float.isFinite(sample.shaftY())
                && Float.isFinite(sample.shaftZ()) && Float.isFinite(sample.depthAxisX())
                && Float.isFinite(sample.depthAxisY()) && Float.isFinite(sample.depthAxisZ());
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
                + Math.abs(left.rightX() - right.rightX()) + Math.abs(left.rightZ() - right.rightZ())
                + Math.abs(left.rightLower() - right.rightLower())
                + Math.abs(left.leftX() - right.leftX()) + Math.abs(left.leftZ() - right.leftZ())
                + Math.abs(left.leftLower() - right.leftLower())
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

    private static float axisDistance(HeavyHammerAnimation.Sample left, HeavyHammerAnimation.Sample right) {
        return length(left.headAxisX() - right.headAxisX(), left.headAxisY() - right.headAxisY(),
                left.headAxisZ() - right.headAxisZ())
                + length(left.depthAxisX() - right.depthAxisX(), left.depthAxisY() - right.depthAxisY(),
                left.depthAxisZ() - right.depthAxisZ());
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
