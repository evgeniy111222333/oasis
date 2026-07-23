package ua.rp.chat;

public final class HeavyHammerRenderedRigTest {
    public static void main(String[] args) {
        verify(HeavyHammerAnimation.idle(0.0f), "idle");
        int totalSamples = (int) (HeavyHammerAnimation.DURATION_TICKS * 20.0f);
        for (int index = 0; index <= totalSamples; index++) {
            verify(HeavyHammerAnimation.strike(index / 20.0f), "strike sample=" + index);
        }
        System.out.println("HeavyHammerRenderedRigTest passed: actual Minecraft hierarchy follows both grips");
    }

    private static void verify(HeavyHammerAnimation.Sample sample, String frame) {
        HeavyHammerRenderedRig.Result rig = HeavyHammerRenderedRig.expected(sample);
        require(rig.mainGripError() < 0.001f, frame + ": основная ладонь ушла с рассчитанного хвата");
        require(rig.offhandGripError() < 0.001f, frame + ": поддерживающая ладонь ушла с древка");
        require(rig.rightShoulderRootError() < 0.001f && rig.leftShoulderRootError() < 0.001f,
                frame + ": корни рук больше не совпадают с плечами IK");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
