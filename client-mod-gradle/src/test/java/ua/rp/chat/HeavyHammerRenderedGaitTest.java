package ua.rp.chat;

public final class HeavyHammerRenderedGaitTest {
    public static void main(String[] args) {
        float idle = verify(HeavyHammerGait.sample(0.0f, 0.0f, 0.0f, false), "idle");
        float minimumWalk = Float.MAX_VALUE;
        float maximumWalk = 0.0f;
        for (int index = 0; index <= 720; index++) {
            float position = index * (float) (Math.PI * 2.0 / 720.0) / 0.6662f;
            float separation = verify(HeavyHammerGait.sample(position, 0.28f, 0.10f, false),
                    "walk frame=" + index);
            minimumWalk = Math.min(minimumWalk, separation);
            maximumWalk = Math.max(maximumWalk, separation);
        }
        require(idle >= 5.35f && idle <= 6.20f,
                "Фактические стопы в покое должны оставаться уже плеч: " + idle);
        require(minimumWalk >= 4.05f && maximumWalk <= 5.15f,
                "Фактический шаг не должен пересекать стопы или сохранять широкую стойку: "
                        + minimumWalk + ".." + maximumWalk);
        System.out.println("HeavyHammerRenderedGaitTest passed: idle=" + idle
                + ", walk=" + minimumWalk + ".." + maximumWalk);
    }

    private static float verify(HeavyHammerGait.Sample gait, String frame) {
        HeavyHammerRenderedGait.Result result = HeavyHammerRenderedGait.expected(gait);
        require(result.rightHipRootError() < 0.001f && result.leftHipRootError() < 0.001f,
                "Корни ног не должны отрываться от таза: " + frame);
        require(Float.isFinite(result.footCenterSeparation()),
                "Положение стоп должно оставаться конечным: " + frame);
        return result.footCenterSeparation();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
