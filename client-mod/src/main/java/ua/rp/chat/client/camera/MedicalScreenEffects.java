package ua.rp.chat.client.camera;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import ua.rp.chat.client.OasisAuthMod;
import ua.rp.chat.client.vitals.VitalsClientState;

final class MedicalScreenEffects {
    private static final Identifier HYPOXIA_VIGNETTE = effect("hypoxia_vignette");
    private static final Identifier HYPOXIA_PULSE = effect("hypoxia_pulse");
    private static final Identifier BLOOD_VIGNETTE = effect("blood_vignette");
    private static final Identifier BLOOD_SPOTS = effect("blood_spots");
    private static final Identifier BLACKOUT_VIGNETTE = effect("blackout_vignette");

    private MedicalScreenEffects() {
    }

    static void render(GuiGraphicsExtractor graphics, int width, int height) {
        float hypoxia = getSuffocationPulseIntensity();
        float wound = getCrimsonVignetteIntensity();

        if (wound > 0.0f) {
            renderCrimsonVignette(graphics, width, height, wound);
        }
        if (hypoxia > 0.0f) {
            renderSuffocationPulse(graphics, width, height, hypoxia);
        }
        if (VitalsClientState.isUnconscious()) {
            graphics.fill(0, 0, width, height, argb(232, 0x000000));
        }
    }

    private static void renderSuffocationPulse(GuiGraphicsExtractor graphics, int width, int height, float intensity) {
        long now = System.currentTimeMillis();
        float pulse = wave(now, 620.0f);
        float deepPulse = wave(now + 170L, 1350.0f);
        float breathBeat = 0.62f + pulse * 0.38f;
        float coldAlpha = clamp01((0.18f + intensity * 0.54f) * (0.78f + deepPulse * 0.22f));
        float tunnelAlpha = clamp01((0.24f + intensity * 0.72f) * breathBeat);
        float pulseAlpha = clamp01(intensity * intensity * (0.18f + pulse * 0.50f));

        sprite(graphics, HYPOXIA_VIGNETTE, width, height, coldAlpha);
        sprite(graphics, HYPOXIA_PULSE, width, height, pulseAlpha);

        if (intensity > 0.72f) {
            float blackout = (intensity - 0.72f) / 0.28f;
            sprite(graphics, BLACKOUT_VIGNETTE, width, height, clamp01(blackout * (0.42f + pulse * 0.34f)));
            graphics.fill(0, 0, width, height, argb(Math.round(blackout * (24.0f + pulse * 38.0f)), 0x000000));
        }
        graphics.fillGradient(0, 0, width, height,
                argb(Math.round(tunnelAlpha * 28.0f), 0x061014),
                argb(Math.round(tunnelAlpha * 46.0f), 0x000000));
    }

    private static void renderCrimsonVignette(GuiGraphicsExtractor graphics, int width, int height, float intensity) {
        long now = System.currentTimeMillis();
        float heartbeat = wave(now, 860.0f);
        float vignetteAlpha = clamp01((0.22f + intensity * 0.62f) * (0.82f + heartbeat * 0.18f));
        sprite(graphics, BLOOD_VIGNETTE, width, height, vignetteAlpha);

        if (intensity > 0.38f) {
            float spotsAlpha = clamp01((intensity - 0.38f) / 0.62f * (0.26f + heartbeat * 0.18f));
            sprite(graphics, BLOOD_SPOTS, width, height, spotsAlpha);
        }

        int flashAlpha = Math.round(intensity * intensity * heartbeat * 30.0f);
        if (flashAlpha > 1) {
            graphics.fill(0, 0, width, height, argb(flashAlpha, 0x7A0707));
        }
    }

    private static float getSuffocationPulseIntensity() {
        float staminaDanger = 1.0f - VitalsClientState.getStamina01();
        float breathDebt = clamp01(VitalsClientState.getBreathDebt() / 100.0f);
        float fatigue = clamp01(VitalsClientState.getFatigue() / 100.0f);

        float hypoxia = Math.max(breathDebt, staminaDanger * 0.55f + fatigue * 0.25f);
        if (VitalsClientState.isUnconscious()) {
            return 1.0f;
        }
        if (hypoxia <= 0.42f) {
            return 0.0f;
        }
        return smoothStep(0.42f, 0.95f, hypoxia);
    }

    private static float getCrimsonVignetteIntensity() {
        float bloodLoss = 1.0f - VitalsClientState.getBlood01();
        float pain = clamp01(VitalsClientState.getPain() / 100.0f);
        float bleeding = clamp01(VitalsClientState.getBleeding() / 100.0f);
        float injury = Math.max(bloodLoss * 0.90f, Math.max(pain * 0.62f, bleeding * 0.72f));

        if (VitalsClientState.isUnconscious()) {
            injury = Math.max(injury, 0.90f);
        }
        if (injury <= 0.12f) {
            return 0.0f;
        }
        return smoothStep(0.12f, 0.72f, injury);
    }

    private static float wave(long now, float periodMs) {
        return (float) ((Math.sin((now / periodMs) * Math.PI * 2.0) + 1.0) * 0.5);
    }

    private static void sprite(GuiGraphicsExtractor graphics, Identifier id, int width, int height, float alpha) {
        float a = clamp01(alpha);
        if (a <= 0.01f) {
            return;
        }
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, id, 0, 0, width, height, a);
    }

    private static Identifier effect(String name) {
        return Identifier.fromNamespaceAndPath(OasisAuthMod.MOD_ID, "effects/" + name);
    }

    private static float smoothStep(float edge0, float edge1, float value) {
        float x = clamp01((value - edge0) / (edge1 - edge0));
        return x * x * (3.0f - 2.0f * x);
    }

    private static float clamp01(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }

    private static int argb(int alpha, int rgb) {
        int a = Math.max(0, Math.min(255, alpha));
        return (a << 24) | (rgb & 0x00FFFFFF);
    }
}
