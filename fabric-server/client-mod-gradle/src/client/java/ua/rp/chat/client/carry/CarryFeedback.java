package ua.rp.chat.client.carry;

/** Time-based reversible feedback for the original, unedited concept-01 texture. */
public final class CarryFeedback {
    public static final int TEXTURE_SIZE = 1254;
    public static final int U = 336, V = 1024, SOURCE_WIDTH = 584, SOURCE_HEIGHT = 196;
    private double lastTime = Double.NaN, pressedAt = Double.NEGATIVE_INFINITY;
    private double opacity;

    public void press(double seconds) { if (Double.isFinite(seconds)) pressedAt = seconds; }

    public Sample sample(boolean visible, double seconds) {
        if (!Double.isFinite(seconds)) seconds = Double.isFinite(lastTime) ? lastTime : 0;
        if (Double.isFinite(lastTime) && seconds >= lastTime) {
            double target = visible ? 1 : 0;
            opacity = target + (opacity - target) * Math.exp(-(seconds - lastTime) * (visible ? 16 : 12));
        }
        lastTime = seconds;
        double elapsed = seconds - pressedAt;
        float press = elapsed >= 0 && elapsed < .18 ? (float) Math.sin(elapsed / .18 * Math.PI) : 0;
        float pulse = .94f + .06f * (float) Math.sin(seconds * Math.PI * 1.3);
        return new Sample((float) opacity * pulse, .96f + .04f * (float) opacity - .06f * press,
                (float) (1 - opacity) * 4, press);
    }
    public record Sample(float alpha, float scale, float lift, float press) {}
}
