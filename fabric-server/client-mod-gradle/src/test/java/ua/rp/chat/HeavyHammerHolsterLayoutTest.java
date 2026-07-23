package ua.rp.chat;

public final class HeavyHammerHolsterLayoutTest {
    public static void main(String[] args) {
        HeavyHammerHolsterLayout.Point seat = HeavyHammerHolsterLayout.socketSeat();
        HeavyHammerHolsterLayout.Point shaft = HeavyHammerHolsterLayout.shaftAxis();
        HeavyHammerHolsterLayout.Point head = HeavyHammerHolsterLayout.headAxis();
        HeavyHammerHolsterLayout.Point grip = HeavyHammerHolsterLayout.mainGrip();
        HeavyHammerHolsterLayout.Point openAttachment = HeavyHammerHolsterLayout.bodyAttachment(0.0f);
        HeavyHammerHolsterLayout.Point closedAttachment = HeavyHammerHolsterLayout.bodyAttachment(1.0f);

        require(close(shaft.length(), 1.0f, 0.0001f), "Ось древка должна быть единичной");
        require(close(head.length(), 1.0f, 0.0001f), "Ось бойка должна быть единичной");
        require(Math.abs(shaft.dot(head)) < 0.0001f, "Оси древка и бойка должны быть ортогональны");
        require(close(distance(grip, seat), HeavyHammerHolsterLayout.MAIN_GRIP_TO_SOCKET, 0.001f),
                "Втулка молота обязана точно попадать на дно капелы");
        require(grip.x() < -4.4f && grip.x() > -4.7f,
                "Петля должна находиться у правого бедра, а не на центре спины");
        require(seat.y() < -8.5f && seat.y() > -9.5f,
                "Боёк должен находиться у плеча, не проваливаясь под землю");

        require(close(openAttachment.x(), HeavyHammerHolsterLayout.ROOT_X, 0.0001f)
                        && close(openAttachment.y(), HeavyHammerHolsterLayout.ROOT_Y, 0.0001f),
                "Holster attachment must keep a stable local anchor on the body bone");
        require(closedAttachment.z() > 0.0f
                        && close(openAttachment.z() - closedAttachment.z(),
                        HeavyHammerHolsterLayout.LATCH_CLOSED_Z_RETRACTION, 0.0001f),
                "Latch motion must not pull the holster into the torso surface");

        HeavyHammerProceduralMotion.Frame stowed = HeavyHammerProceduralMotion.draw(0.0f, 0.0f, 0.0f);
        require(close(stowed.mainGrip().x(), grip.x(), 0.02f)
                        && close(stowed.mainGrip().y(), grip.y(), 0.02f)
                        && close(stowed.mainGrip().z(), grip.z(), 0.02f),
                "Первый кадр извлечения должен совпадать с физической посадкой в портупее");
        require(close(stowed.shaft().x(), shaft.x(), 0.002f)
                        && close(stowed.shaft().y(), shaft.y(), 0.002f),
                "Древко первого кадра обязано проходить через обе петли");
        System.out.println("Heavy hammer holster layout: seat, shaft and draw origin are aligned");
    }

    private static float distance(HeavyHammerHolsterLayout.Point a, HeavyHammerHolsterLayout.Point b) {
        float x = a.x() - b.x();
        float y = a.y() - b.y();
        float z = a.z() - b.z();
        return (float) Math.sqrt(x * x + y * y + z * z);
    }

    private static boolean close(float value, float expected, float tolerance) {
        return Math.abs(value - expected) <= tolerance;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
