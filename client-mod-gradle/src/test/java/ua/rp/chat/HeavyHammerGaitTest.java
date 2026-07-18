package ua.rp.chat;

public final class HeavyHammerGaitTest {
    public static void main(String[] args) {
        System.out.println("=== FIELDS of AvatarRenderState ===");
        try {
            Class<?> clazz = Class.forName("net.minecraft.client.renderer.entity.state.AvatarRenderState");
            while (clazz != null) {
                System.out.println("Class: " + clazz.getName());
                for (java.lang.reflect.Field field : clazz.getDeclaredFields()) {
                    System.out.println("  " + field.getType().getName() + " " + field.getName());
                }
                clazz = clazz.getSuperclass();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        HeavyHammerGait.Sample idle = HeavyHammerGait.sample(0.0f, 0.0f, 0.0f, false);
        float idleSeparation = HeavyHammerGait.footSeparation(idle);
        require(idle.movement() == 0.0f, "В покое цикл шага не должен влиять на ноги");
        require(idleSeparation >= 5.4f && idleSeparation <= 6.2f,
                "Опора с молотом должна быть немного шире таза, но уже плеч: " + idleSeparation);

        HeavyHammerGait.Sample walk = HeavyHammerGait.sample(2.4f, 0.25f, 0.10f, false);
        float walkSeparation = HeavyHammerGait.footSeparation(walk);
        require(walk.movement() > 0.90f, "Обычная ходьба должна полностью включать цикл шага");
        require(walkSeparation >= 4.25f && walkSeparation <= 5.05f,
                "При ходьбе стопы должны возвращаться под таз: " + walkSeparation);
        require(walkSeparation < idleSeparation - 0.65f,
                "Ходьба не должна сохранять широкую силовую стойку");

        HeavyHammerGait.Sample run = HeavyHammerGait.sample(2.4f, 0.45f, 0.28f, false);
        require(run.run() > 0.95f, "Высокая скорость должна включать беговую адаптацию");
        require(HeavyHammerGait.footSeparation(run) <= walkSeparation + 0.12f,
                "Бег не должен разводить стопы шире шага");

        float quarterPhase = (float) (Math.PI * 0.5 / 0.6662);
        HeavyHammerGait.Sample phase = HeavyHammerGait.sample(quarterPhase, 0.30f, 0.10f, false);
        require(phase.rightHipPitch() * phase.leftHipPitch() < 0.0f,
                "Бёдра должны двигаться в противофазе");
        require(Math.abs(phase.rightKnee() - phase.leftKnee()) > 0.20f,
                "Маховая нога должна подбирать колено сильнее опорной");

        for (int index = 0; index <= 720; index++) {
            float walkPosition = index * (float) (Math.PI * 2.0 / 720.0) / 0.6662f;
            HeavyHammerGait.Sample frame = HeavyHammerGait.sample(walkPosition, 0.28f, 0.10f, false);
            float separation = HeavyHammerGait.footSeparation(frame);
            require(separation >= 4.20f && separation <= 5.10f,
                    "Стопы не должны пересекаться или расходиться при смене опоры: frame="
                            + index + ", separation=" + separation);
            require(frame.rightKnee() >= 0.08f && frame.leftKnee() >= 0.08f,
                    "Колено не должно выгибаться назад: frame=" + index);
        }

        System.out.println("HeavyHammerGaitTest passed: idle=" + idleSeparation
                + ", walk=" + walkSeparation + ", run=" + HeavyHammerGait.footSeparation(run));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
