package ua.rp.chat;

public final class HeavyHammerSpatialRulesTest {
    public static void main(String[] args) {
        HeavyHammerProceduralMotion.Frame idle = HeavyHammerProceduralMotion.idle(0.0f);
        require(!HeavyHammerSpatialRules.intersectsPlayerHead(idle),
                "В рабочей стойке боёк не должен пересекать голову");
        require(!HeavyHammerSpatialRules.intersectsPlayerTorso(idle),
                "В рабочей стойке боёк не должен пересекать корпус");

        float frontSpan = HeavyHammerSpatialRules.projectedLongAxisLength(idle,
                new HeavyHammerProceduralMotion.Vec3(0.0f, 0.0f, 1.0f));
        require(frontSpan >= 8.0f && frontSpan <= 13.5f,
                "Спереди боёк должен читаться объёмным инструментом, а не длинной трубой: " + frontSpan);

        int headIntersections = 0;
        int torsoIntersections = 0;
        int handleHeadIntersections = 0;
        int handleTorsoIntersections = 0;
        int mainGripIntersections = 0;
        int offhandGripIntersections = 0;
        int firstHeadIntersection = -1;
        int lastHeadIntersection = -1;
        int firstTorsoIntersection = -1;
        int lastTorsoIntersection = -1;
        StringBuilder headSamples = new StringBuilder();
        StringBuilder torsoSamples = new StringBuilder();
        HeavyHammerProceduralMotion.Target groundTarget = new HeavyHammerProceduralMotion.Target(
                0.0f, 24.0f, -12.0f, HeavyHammerProceduralMotion.Surface.UP,
                0.0f, 1.0f, 0.0f);
        for (int sample = 0; sample <= 680; sample++) {
            HeavyHammerProceduralMotion.Frame frame = HeavyHammerProceduralMotion.strike(
                    sample / 680.0f, groundTarget);
            if (HeavyHammerSpatialRules.intersectsPlayerHead(frame)) {
                if (firstHeadIntersection < 0) firstHeadIntersection = sample;
                lastHeadIntersection = sample;
                headIntersections++;
                if (headSamples.length() < 120) headSamples.append(sample).append(' ');
            }
            if (HeavyHammerSpatialRules.intersectsPlayerTorso(frame)) {
                if (firstTorsoIntersection < 0) firstTorsoIntersection = sample;
                lastTorsoIntersection = sample;
                torsoIntersections++;
                if (torsoSamples.length() < 120) torsoSamples.append(sample).append(' ');
            }
            if (HeavyHammerSpatialRules.handleIntersectsPlayerHead(frame)) handleHeadIntersections++;
            if (HeavyHammerSpatialRules.handleIntersectsPlayerTorso(frame)) handleTorsoIntersections++;
            if (HeavyHammerSpatialRules.mainGripIntersectsPlayer(frame)) mainGripIntersections++;
            if (HeavyHammerSpatialRules.offhandGripIntersectsPlayer(frame)) offhandGripIntersections++;
            require(HeavyHammerSpatialRules.headGroundClearance(frame) >= -0.15f,
                    "Боёк не должен проходить сквозь землю: sample=" + sample
                            + ", clearance=" + HeavyHammerSpatialRules.headGroundClearance(frame));
        }
        require(headIntersections == 0,
                "Траектория бойка пересекает голову в " + headIntersections + " кадрах ["
                        + firstHeadIntersection + ".." + lastHeadIntersection + "]: " + headSamples);
        require(torsoIntersections == 0,
                "Траектория бойка пересекает корпус в " + torsoIntersections + " кадрах ["
                        + firstTorsoIntersection + ".." + lastTorsoIntersection + "]: " + torsoSamples);
        require(handleHeadIntersections == 0 && handleTorsoIntersections == 0,
                "Древко пересекает персонажа: head=" + handleHeadIntersections
                        + ", torso=" + handleTorsoIntersections);
        require(mainGripIntersections == 0 && offhandGripIntersections == 0,
                "Кисти уходят внутрь персонажа: main=" + mainGripIntersections
                        + ", offhand=" + offhandGripIntersections);
        HeavyHammerProceduralMotion.Frame impact = HeavyHammerProceduralMotion.strike(
                HeavyHammerProceduralMotion.IMPACT_PROGRESS, groundTarget);
        float impactClearance = HeavyHammerSpatialRules.headGroundClearance(impact);
        require(Math.abs(impactClearance) <= 0.25f,
                "В кадре контакта боёк обязан касаться земли: clearance=" + impactClearance);
        require(Math.abs(impact.headAxis().dot(new HeavyHammerProceduralMotion.Vec3(0.0f, 1.0f, 0.0f))) > 0.40f,
                "Рабочая грань бойка должна разворачиваться к поверхности настолько, насколько позволяет древко");
        HeavyHammerProceduralMotion.Target wallTarget = new HeavyHammerProceduralMotion.Target(
                0.0f, 10.0f, -12.0f, HeavyHammerProceduralMotion.Surface.SIDE,
                0.0f, 0.0f, -1.0f);
        for (int sample = 0; sample <= 680; sample++) {
            HeavyHammerProceduralMotion.Frame wallFrame = HeavyHammerProceduralMotion.strike(
                    sample / 680.0f, wallTarget);
            require(!HeavyHammerSpatialRules.intersectsPlayerHead(wallFrame)
                            && !HeavyHammerSpatialRules.intersectsPlayerTorso(wallFrame)
                            && !HeavyHammerSpatialRules.handleIntersectsPlayerHead(wallFrame)
                            && !HeavyHammerSpatialRules.handleIntersectsPlayerTorso(wallFrame),
                    "Наведение на стену не должно возвращать инструмент внутрь тела: sample=" + sample);
        }
        HeavyHammerProceduralMotion.Frame wallImpact = HeavyHammerProceduralMotion.strike(
                HeavyHammerProceduralMotion.IMPACT_PROGRESS, wallTarget);
        require(Math.abs(wallImpact.headAxis().z()) > 0.55f,
                "Боёк должен заметно доворачиваться к вертикальной поверхности");
        System.out.println("HeavyHammerSpatialRulesTest passed; idle front span=" + frontSpan);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
