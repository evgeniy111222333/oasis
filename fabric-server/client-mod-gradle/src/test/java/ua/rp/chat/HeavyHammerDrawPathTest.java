package ua.rp.chat;

public final class HeavyHammerDrawPathTest {
    public static void main(String[] args) {
        HeavyHammerProceduralMotion.Frame stowed = HeavyHammerProceduralMotion.draw(0.0f, 0.0f, 0.0f);
        HeavyHammerProceduralMotion.Frame latched = HeavyHammerProceduralMotion.draw(0.18f, 4.0f, 0.0f);
        require(distance(stowed.mainGrip(), latched.mainGrip()) < 0.001f,
                "До открытия защёлки молот обязан оставаться неподвижным");
        require(distance(stowed.headCenter(), latched.headCenter()) < 0.001f,
                "Боёк не должен плавать в закрытом подвесе");

        HeavyHammerProceduralMotion.Frame previous = stowed;
        float maxStep = 0.0f;
        for (int sample = 1; sample <= 600; sample++) {
            float progress = sample / 600.0f;
            HeavyHammerProceduralMotion.Frame frame = HeavyHammerProceduralMotion.draw(
                    progress, progress * HeavyHammerCarryMachine.DRAW_DURATION_TICKS, 0.0f);
            float step = distance(previous.headCenter(), frame.headCenter());
            maxStep = Math.max(maxStep, step);
            require(step < 0.55f, "Обнаружен скачок бойка на progress=" + progress + ": " + step);
            require(orthonormal(frame), "Базис молота разрушен на progress=" + progress);
            // До 28% боёк ещё охвачен мягкой капелой у бедра. После выхода из неё
            // свободный инструмент уже не имеет права пересекать тело.
            if (progress >= 0.28f) {
                require(!HeavyHammerSpatialRules.intersectsPlayerHead(frame),
                        "Боёк пересёк голову на progress=" + progress);
                require(!HeavyHammerSpatialRules.intersectsPlayerTorso(frame),
                        "Боёк пересёк корпус на progress=" + progress);
            }
            previous = frame;
        }

        HeavyHammerCarryMachine machine = new HeavyHammerCarryMachine();
        machine.reset(true, false);
        for (int tick = 0; tick < 17; tick++) machine.tick(true, true, 1.0f);
        require(machine.sample(1.0f).offhandWeight() < 0.01f,
                "Левая рука не должна хватать рукоять до выхода бойка из-за силуэта");
        for (int tick = 17; tick < 25; tick++) machine.tick(true, true, 1.0f);
        require(machine.sample(1.0f).offhandWeight() > 0.5f,
                "Левая рука должна принять массу перед финальным опусканием");
        System.out.println("HeavyHammerDrawPathTest passed; maxHeadStep=" + maxStep);
    }

    private static boolean orthonormal(HeavyHammerProceduralMotion.Frame frame) {
        return Math.abs(frame.headAxis().length() - 1.0f) < 0.001f
                && Math.abs(frame.shaft().length() - 1.0f) < 0.001f
                && Math.abs(frame.depthAxis().length() - 1.0f) < 0.001f
                && Math.abs(frame.headAxis().dot(frame.shaft())) < 0.001f
                && Math.abs(frame.headAxis().dot(frame.depthAxis())) < 0.001f
                && Math.abs(frame.shaft().dot(frame.depthAxis())) < 0.001f;
    }

    private static float distance(HeavyHammerProceduralMotion.Vec3 left,
                                  HeavyHammerProceduralMotion.Vec3 right) {
        return left.subtract(right).length();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
