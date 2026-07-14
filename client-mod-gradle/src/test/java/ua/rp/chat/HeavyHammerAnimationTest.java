package ua.rp.chat;

public final class HeavyHammerAnimationTest {
    public static void main(String[] args) {
        HeavyHammerAnimation.Sample idle = HeavyHammerAnimation.idle(0.0f);
        require(idle.rightLower() > 0.35f, "Правая кисть должна удерживать нижний хват");
        require(idle.leftX() < idle.rightX(), "Левая рука должна поддерживать рукоять выше правой");
        require(gripError(idle) < 0.03f, "Левая ладонь должна точно попадать во второй хват");
        require(idle.gripClampDistance() < 0.03f, "Рабочая стойка должна быть достижима обеими руками");

        HeavyHammerAnimation.Sample behindBack = HeavyHammerAnimation.strike(16.5f);
        require(behindBack.rightX() < -2.1f && behindBack.leftX() < -1.1f,
                "Обе руки должны уводить молот за спину");
        HeavyHammerAnimation.Sample impact = HeavyHammerAnimation.strike(HeavyHammerAnimation.IMPACT_TICK);
        require(impact.rightX() > -1.45f && impact.bodyY() > 0.30f,
                "Кадр контакта должен переносить вес вперёд");
        require(HeavyHammerAnimation.impactReached(20.9f, 21.0f), "Контакт должен срабатывать ровно один раз");
        require(!HeavyHammerAnimation.impactReached(21.0f, 21.1f), "Контакт нельзя повторять после прохождения кадра");

        HeavyHammerAnimation.Sample previous = HeavyHammerAnimation.strike(0.0f);
        for (int sample = 1; sample <= 340; sample++) {
            HeavyHammerAnimation.Sample current = HeavyHammerAnimation.strike(sample / 10.0f);
            require(finite(current), "Все каналы анимации должны оставаться конечными");
            require(gripError(current) < 0.08f, "Левая ладонь не должна соскальзывать с древка");
            require(current.gripClampDistance() < 0.08f, "Ключевой хват не должен выходить за длину руки");
            // Ускорение перед контактом намеренно резкое, но даже там поза меняется непрерывно.
            require(distance(previous, current) < 0.95f, "Между соседними кадрами не должно быть рывка");
            previous = current;
        }
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
                && Float.isFinite(sample.gripZ()) && Float.isFinite(sample.gripClampDistance());
    }

    private static float distance(HeavyHammerAnimation.Sample left, HeavyHammerAnimation.Sample right) {
        return Math.abs(left.bodyX() - right.bodyX()) + Math.abs(left.bodyY() - right.bodyY())
                + Math.abs(left.rightX() - right.rightX()) + Math.abs(left.rightY() - right.rightY())
                + Math.abs(left.rightZ() - right.rightZ()) + Math.abs(left.rightLower() - right.rightLower())
                + Math.abs(left.leftX() - right.leftX()) + Math.abs(left.leftY() - right.leftY())
                + Math.abs(left.leftZ() - right.leftZ()) + Math.abs(left.leftLower() - right.leftLower())
                + Math.abs(left.gripX() - right.gripX()) + Math.abs(left.gripY() - right.gripY())
                + Math.abs(left.gripZ() - right.gripZ());
    }

    private static float gripError(HeavyHammerAnimation.Sample sample) {
        HeavyHammerGripSolver.Point main = HeavyHammerGripSolver.hand(HeavyHammerAnimation.RIGHT_SHOULDER,
                sample.rightX(), sample.rightZ(), sample.rightLower());
        HeavyHammerGripSolver.Point target = main.add(sample.gripX(), sample.gripY(), sample.gripZ());
        HeavyHammerGripSolver.Point offhand = HeavyHammerGripSolver.hand(HeavyHammerAnimation.LEFT_SHOULDER,
                sample.leftX(), sample.leftZ(), sample.leftLower());
        return target.distanceTo(offhand);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
