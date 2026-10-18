package ua.rp.chat.client.tab;

/** Time-based, reversible animation independent of client ticks and frame rate. */
public final class TabAnimation {
    private float progress;
    private long previousNanos;
    private boolean timed;

    public float advance(boolean opening, long nowNanos) {
        double seconds = !timed ? 1.0 / 60.0 : Math.max(0, (nowNanos - previousNanos) * 1.0e-9);
        timed = true;
        previousNanos = nowNanos;
        progress = Math.clamp(progress + (float) (seconds / (opening ? 0.85 : -0.65)), 0, 1);
        if (!opening && progress == 0) timed = false;
        return opacity();
    }

    public float progress() { return progress; }
    public static float ease(float value) { float t = Math.clamp(value, 0, 1); return 1 - (1-t)*(1-t)*(1-t); }
    public static float stagger(float progress, float delay) { return ease((progress-delay)/(1-delay)); }
    public float opacity() { return ease(progress); }
    public boolean visible() { return progress > 0; }
    public void reset() { progress = 0; previousNanos = 0; timed = false; }
}
