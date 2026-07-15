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
        require(frontSpan >= 5.0f && frontSpan <= 10.5f,
                "Спереди боёк должен читаться объёмным инструментом, а не длинной трубой: " + frontSpan);

        int headIntersections = 0;
        int torsoIntersections = 0;
        int firstHeadIntersection = -1;
        int lastHeadIntersection = -1;
        int firstTorsoIntersection = -1;
        int lastTorsoIntersection = -1;
        StringBuilder headSamples = new StringBuilder();
        StringBuilder torsoSamples = new StringBuilder();
        for (int sample = 0; sample <= 680; sample++) {
            HeavyHammerProceduralMotion.Frame frame = HeavyHammerProceduralMotion.strike(sample / 680.0f);
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
        }
        require(headIntersections == 0,
                "Траектория бойка пересекает голову в " + headIntersections + " кадрах ["
                        + firstHeadIntersection + ".." + lastHeadIntersection + "]: " + headSamples);
        require(torsoIntersections == 0,
                "Траектория бойка пересекает корпус в " + torsoIntersections + " кадрах ["
                        + firstTorsoIntersection + ".." + lastTorsoIntersection + "]: " + torsoSamples);
        System.out.println("HeavyHammerSpatialRulesTest passed; idle front span=" + frontSpan);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
