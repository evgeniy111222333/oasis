package ua.rp.chat.client.carver;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;

/**
 * Screen-space dust for the carving work phase. It replaces the old full-frame curtain: instead of
 * hiding the shot behind an opaque wash, motes and wind streaks drift across the viewport in three
 * parallax layers (far, mid, near) so the camera stays readable while the air visibly fills with
 * debris. Every client runs its own copy, driven by the local work or any observed work nearby and
 * tinted by the material being cut.
 */
public final class CarverDustScreen {
    private static final Identifier[] MOTES = {
            sprite("mote_0"), sprite("mote_1"), sprite("mote_2"), sprite("mote_3")
    };

    private static final int MAX_MOTES = 160;
    private static final int MAX_STREAKS = 56;

    private static final float[] MOTE_X = new float[MAX_MOTES];
    private static final float[] MOTE_Y = new float[MAX_MOTES];
    private static final float[] MOTE_DEPTH = new float[MAX_MOTES];
    private static final float[] MOTE_SIZE = new float[MAX_MOTES];
    private static final float[] MOTE_LIFE = new float[MAX_MOTES];
    private static final float[] MOTE_LIFE_STEP = new float[MAX_MOTES];
    private static final float[] MOTE_SPEED = new float[MAX_MOTES];
    private static final float[] MOTE_BOB = new float[MAX_MOTES];
    private static final int[] MOTE_SPRITE = new int[MAX_MOTES];
    private static final boolean[] MOTE_LIVE = new boolean[MAX_MOTES];

    private static final float[] STREAK_X = new float[MAX_STREAKS];
    private static final float[] STREAK_Y = new float[MAX_STREAKS];
    private static final float[] STREAK_DEPTH = new float[MAX_STREAKS];
    private static final float[] STREAK_LEN = new float[MAX_STREAKS];
    private static final float[] STREAK_LIFE = new float[MAX_STREAKS];
    private static final float[] STREAK_STEP = new float[MAX_STREAKS];
    private static final boolean[] STREAK_LIVE = new boolean[MAX_STREAKS];

    private static final RandomSource RANDOM = RandomSource.create();

    private static float intensity;
    private static float gust;
    private static float spawnAccumulator;
    private static int tint = 0xFFFFFF;
    private static double windAngle;
    private static int tick;

    private CarverDustScreen() {
    }

    /** Advances the field once per client tick. Cheap: only scalar work unless the field is live. */
    public static void clientTick() {
        BlockPos focus = activeFocus();
        boolean active = focus != null;
        float target = active ? 1.0f : 0.0f;
        intensity += (target - intensity) * (active ? 0.05f : 0.045f);
        if (intensity < 0.002f) {
            intensity = 0.0f;
            clear();
            return;
        }
        tick++;
        if (active) {
            tint = CarverWorkFx.stormTintFor(focus);
        }
        windAngle += 0.010 + Math.sin(tick * 0.013) * 0.007;
        gust += (1.0f - gust) * 0.11f;
        spawn();
        advance();
    }

    /** HUD hook: paints the drifting dust over the world and under the readouts. */
    public static void render(GuiGraphicsExtractor graphics, int width, int height) {
        if (intensity <= 0.01f || width <= 0 || height <= 0) {
            return;
        }
        float dirX = (float) Math.cos(windAngle);
        float dirY = (float) Math.sin(windAngle) * 0.4f;
        int minSide = Math.min(width, height);

        int vignette = Math.round(intensity * 14.0f);
        if (vignette > 0) {
            graphics.fillGradient(0, 0, width, height, (vignette << 24) | darken(tint, 40),
                    (vignette * 2 << 24) | 0x000000);
        }

        for (int i = 0; i < MAX_MOTES; i++) {
            if (!MOTE_LIVE[i]) continue;
            float alpha = moteAlpha(i);
            if (alpha <= 0.01f) continue;
            int size = Math.max(6, Math.round(MOTE_SIZE[i] * minSide));
            int px = Math.round(MOTE_X[i] * width) - size / 2;
            int py = Math.round((MOTE_Y[i] + bobOffset(i)) * height) - size / 2;
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, MOTES[MOTE_SPRITE[i]],
                    px, py, size, size, alpha);
        }

        int streakColor = lighten(tint, 40);
        for (int i = 0; i < MAX_STREAKS; i++) {
            if (!STREAK_LIVE[i]) continue;
            float fade = lifeFade(STREAK_LIFE[i]);
            float alpha = fade * intensity * (0.05f + STREAK_DEPTH[i] * 0.16f);
            if (alpha <= 0.01f) continue;
            int length = Math.max(8, Math.round(STREAK_LEN[i] * width));
            int thickness = 1 + Math.round(STREAK_DEPTH[i] * 2.0f);
            int x0 = Math.round(STREAK_X[i] * width);
            int y0 = Math.round(STREAK_Y[i] * height);
            int x1 = x0 + Math.round(dirX * length);
            int y1 = y0 + thickness + Math.round(dirY * length * 0.3f);
            graphics.fill(Math.min(x0, x1), Math.min(y0, y1), Math.max(x0, x1), Math.max(y0, y1),
                    (Math.round(alpha * 255.0f) << 24) | streakColor);
        }
    }

    static void reset() {
        intensity = 0.0f;
        gust = 0.0f;
        spawnAccumulator = 0.0f;
        clear();
    }

    private static void spawn() {
        spawnAccumulator += intensity * (1.0f + gust * 2.2f)
                * (0.65f + 0.35f * Math.abs(Math.sin(tick * 0.017)));
        while (spawnAccumulator >= 1.0f) {
            spawnAccumulator -= 1.0f;
            if (!spawnMote()) {
                spawnAccumulator = 0.0f;
                break;
            }
        }
        if (RANDOM.nextFloat() < intensity * 0.30f) {
            spawnStreak();
        }
    }

    private static boolean spawnMote() {
        int slot = freeMote();
        if (slot < 0) return false;
        float depth = depthSample();
        float dirX = (float) Math.cos(windAngle);
        float dirY = (float) Math.sin(windAngle) * 0.4f;

        if (Math.abs(dirX) >= Math.abs(dirY)) {
            MOTE_X[slot] = dirX > 0.0f ? -0.12f : 1.12f;
            MOTE_Y[slot] = RANDOM.nextFloat() * 1.25f - 0.12f;
        } else {
            MOTE_Y[slot] = dirY > 0.0f ? -0.12f : 1.12f;
            MOTE_X[slot] = RANDOM.nextFloat() * 1.25f - 0.12f;
        }
        MOTE_DEPTH[slot] = depth;
        MOTE_SIZE[slot] = sizeForDepth(depth) * (0.8f + RANDOM.nextFloat() * 0.5f);
        MOTE_SPEED[slot] = (0.0035f + depth * 0.015f) * (0.7f + RANDOM.nextFloat() * 0.8f);
        MOTE_BOB[slot] = RANDOM.nextFloat() * 6.2831855f;
        MOTE_SPRITE[slot] = RANDOM.nextInt(MOTES.length);
        int lifetime = Math.round(90.0f + (1.0f - depth) * 180.0f + RANDOM.nextFloat() * 60.0f);
        MOTE_LIFE[slot] = 0.0f;
        MOTE_LIFE_STEP[slot] = 1.0f / lifetime;
        MOTE_LIVE[slot] = true;
        return true;
    }

    private static void spawnStreak() {
        int slot = freeStreak();
        if (slot < 0) return;
        float depth = 0.3f + RANDOM.nextFloat() * 0.7f;
        float dirX = (float) Math.cos(windAngle);
        STREAK_X[slot] = dirX > 0.0f ? -0.3f : 1.3f;
        STREAK_Y[slot] = RANDOM.nextFloat() * 1.05f - 0.02f;
        STREAK_DEPTH[slot] = depth;
        STREAK_LEN[slot] = 0.05f + RANDOM.nextFloat() * 0.16f;
        int lifetime = Math.round(50.0f + (1.0f - depth) * 90.0f);
        STREAK_LIFE[slot] = 0.0f;
        STREAK_STEP[slot] = 1.0f / lifetime;
        STREAK_LIVE[slot] = true;
    }

    private static void advance() {
        float dirX = (float) Math.cos(windAngle);
        float dirY = (float) Math.sin(windAngle) * 0.4f;
        for (int i = 0; i < MAX_MOTES; i++) {
            if (!MOTE_LIVE[i]) continue;
            MOTE_LIFE[i] += MOTE_LIFE_STEP[i];
            MOTE_X[i] += dirX * MOTE_SPEED[i];
            MOTE_Y[i] += dirY * MOTE_SPEED[i] + Math.sin(tick * 0.05 + MOTE_BOB[i]) * 0.0004f;
            if (MOTE_LIFE[i] >= 1.0f || MOTE_X[i] < -0.25f || MOTE_X[i] > 1.25f
                    || MOTE_Y[i] < -0.25f || MOTE_Y[i] > 1.25f) {
                MOTE_LIVE[i] = false;
            }
        }
        for (int i = 0; i < MAX_STREAKS; i++) {
            if (!STREAK_LIVE[i]) continue;
            STREAK_LIFE[i] += STREAK_STEP[i];
            STREAK_X[i] += dirX * (0.02f + STREAK_DEPTH[i] * 0.05f);
            STREAK_Y[i] += dirY * (0.02f + STREAK_DEPTH[i] * 0.05f);
            if (STREAK_LIFE[i] >= 1.0f || STREAK_X[i] < -0.35f || STREAK_X[i] > 1.35f
                    || STREAK_Y[i] < -0.2f || STREAK_Y[i] > 1.2f) {
                STREAK_LIVE[i] = false;
            }
        }
    }

    private static BlockPos activeFocus() {
        if (CarverClientState.working() && CarverClientState.focus() != null) {
            return CarverClientState.focus();
        }
        for (CarverClientState.ObservedWork observed : CarverClientState.observedWorks()) {
            if (observed != null && observed.focus() != null) {
                return observed.focus();
            }
        }
        return null;
    }

    private static float moteAlpha(int index) {
        return Math.min(0.62f, alphaForDepth(MOTE_DEPTH[index])
                * lifeFade(MOTE_LIFE[index]) * intensity);
    }

    private static float bobOffset(int index) {
        return (float) Math.sin(tick * 0.05 + MOTE_BOB[index]) * 0.006f;
    }

    public static float lifeFade(float life) {
        return (float) Math.sin(Math.PI * Math.min(1.0f, Math.max(0.0f, life)));
    }

    public static float sizeForDepth(float depth) {
        return 0.006f + depth * 0.055f + (1.0f - depth) * 0.010f;
    }

    public static float alphaForDepth(float depth) {
        return 0.10f + depth * 0.38f;
    }

    private static float depthSample() {
        return depthBand(RANDOM.nextFloat());
    }

    /** Maps a uniform roll to a depth band, favouring the distant layer for a full parallax field. */
    public static float depthBand(float roll) {
        float clamped = Math.min(1.0f, Math.max(0.0f, roll));
        if (clamped < 0.45f) return (clamped / 0.45f) * 0.34f;
        if (clamped < 0.80f) return 0.34f + ((clamped - 0.45f) / 0.35f) * 0.33f;
        return 0.67f + ((clamped - 0.80f) / 0.20f) * 0.33f;
    }

    private static int freeMote() {
        for (int i = 0; i < MAX_MOTES; i++) {
            if (!MOTE_LIVE[i]) return i;
        }
        return -1;
    }

    private static int freeStreak() {
        for (int i = 0; i < MAX_STREAKS; i++) {
            if (!STREAK_LIVE[i]) return i;
        }
        return -1;
    }

    private static void clear() {
        for (int i = 0; i < MAX_MOTES; i++) MOTE_LIVE[i] = false;
        for (int i = 0; i < MAX_STREAKS; i++) STREAK_LIVE[i] = false;
    }

    public static int darken(int rgb, int amount) {
        int r = Math.max(0, ((rgb >> 16) & 0xFF) - amount);
        int g = Math.max(0, ((rgb >> 8) & 0xFF) - amount);
        int b = Math.max(0, (rgb & 0xFF) - amount);
        return (r << 16) | (g << 8) | b;
    }

    public static int lighten(int rgb, int amount) {
        int r = Math.min(255, ((rgb >> 16) & 0xFF) + amount);
        int g = Math.min(255, ((rgb >> 8) & 0xFF) + amount);
        int b = Math.min(255, (rgb & 0xFF) + amount);
        return (r << 16) | (g << 8) | b;
    }

    private static Identifier sprite(String name) {
        return Identifier.fromNamespaceAndPath("eclipseclient", "effects/dust/" + name);
    }
}
