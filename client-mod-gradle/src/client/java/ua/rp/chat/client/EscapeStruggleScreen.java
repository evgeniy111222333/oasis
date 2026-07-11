package ua.rp.chat.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

public final class EscapeStruggleScreen extends Screen {
    private int ticks;
    private double currentEffort = 0.0;
    private char nextKey = 'A';
    private long lastTapTime = 0;
    private int tapFlashTicks = 0;

    public EscapeStruggleScreen() { 
        super(Component.literal("Ослабление узлов")); 
    }

    @Override 
    public void tick() {
        ticks++;
        if (tapFlashTicks > 0) tapFlashTicks--;

        // Drain effort over time if not tapping
        long now = System.currentTimeMillis();
        if (now - lastTapTime > 250) {
            double drainRate = 0.15 / 20.0; // 15% effort lost per second (20 ticks)
            currentEffort = Math.max(0.0, currentEffort - drainRate);
        }
    }

    private void handleTap(char key) {
        if (key == nextKey) {
            currentEffort = Math.min(1.0, currentEffort + 0.08);
            nextKey = (nextKey == 'A') ? 'D' : 'A';
            lastTapTime = System.currentTimeMillis();
            tapFlashTicks = 3;

            // Play a wool hit sound
            if (minecraft != null && minecraft.player != null) {
                minecraft.player.playSound(net.minecraft.sounds.SoundEvents.WOOL_HIT, 0.4f, 0.9f);
            }

            if (currentEffort >= 0.999) {
                currentEffort = 0.0;
                // Send success pull to server!
                EscapeClientState.action(71); // ACTION_ESCAPE_QTE
                if (minecraft != null && minecraft.player != null) {
                    minecraft.player.playSound(net.minecraft.sounds.SoundEvents.WOOL_BREAK, 0.65f, 0.75f);
                }
            }
        } else {
            // Penalize wrong tap
            currentEffort = Math.max(0.0, currentEffort - 0.05);
            if (minecraft != null && minecraft.player != null) {
                minecraft.player.playSound(net.minecraft.sounds.SoundEvents.WOOL_HIT, 0.35f, 0.55f);
            }
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        EscapeClientState.EscapeProgress p = EscapeClientState.progress();
        if (p == null) return;
        int cx = width / 2, cy = height / 2;

        // Dark background overlay
        g.fill(0, 0, width, height, 0x8A000000);

        // Header Panel
        int boxW = 260;
        int panelY = cy - 85;
        g.fill(cx - boxW / 2, panelY, cx + boxW / 2, panelY + 38, 0xE018140F);
        g.fill(cx - boxW / 2, panelY, cx + boxW / 2, panelY + 2, 0xFFE3C099);
        g.centeredText(font, "ОСЛАБЛЕНИЕ УЗЛОВ", cx, panelY + 8, 0xFFFFE8C5);
        g.centeredText(font, "Быстро нажимайте A и D поочередно", cx, panelY + 22, 0xFFA5C3C4);

        // Keys Visuals
        int keyY = cy - 25;
        // Left Key (A)
        int aBg = (nextKey == 'A') ? 0xED423325 : 0xD017130F;
        int aEdge = (nextKey == 'A') ? 0xFFE3C099 : 0xAA6B5A43;
        int aTextColor = (nextKey == 'A') ? 0xFFFFE8C5 : 0xFF746E68;
        g.fill(cx - 48, keyY, cx - 16, keyY + 32, aBg);
        g.fill(cx - 48, keyY, cx - 16, keyY + 2, aEdge);
        g.centeredText(font, "A", cx - 32, keyY + 11, aTextColor);

        // Right Key (D)
        int dBg = (nextKey == 'D') ? 0xED423325 : 0xD017130F;
        int dEdge = (nextKey == 'D') ? 0xFFE3C099 : 0xAA6B5A43;
        int dTextColor = (nextKey == 'D') ? 0xFFFFE8C5 : 0xFF746E68;
        g.fill(cx + 16, keyY, cx + 48, keyY + 32, dBg);
        g.fill(cx + 16, keyY, cx + 48, keyY + 2, dEdge);
        g.centeredText(font, "D", cx + 32, keyY + 11, dTextColor);

        // Current Effort Bar
        int effortY = cy + 22;
        int effortW = 160;
        g.fill(cx - effortW / 2, effortY, cx + effortW / 2, effortY + 8, 0xFF2A211A);
        int effortFill = (int) (effortW * currentEffort);
        g.fill(cx - effortW / 2, effortY, cx - effortW / 2 + effortFill, effortY + 8, 0xFFE07B42);
        g.centeredText(font, "НАТЯЖЕНИЕ: " + Math.round(currentEffort * 100) + "%", cx, effortY - 11, 0xFFB7A895);

        // Overall Escape Progress Bar
        int overallY = cy + 54;
        int overallW = 200;
        g.fill(cx - overallW / 2, overallY, cx + overallW / 2, overallY + 8, 0xFF2A211A);
        int overallFill = (int) (overallW * p.progress());
        g.fill(cx - overallW / 2, overallY, cx - overallW / 2 + overallFill, overallY + 8, 0xFFD5B16F);
        g.centeredText(font, Math.round(p.progress() * 100) + "% узлов развязано", cx, overallY + 13, 0xFFB7A895);

        // Close Hint
        g.centeredText(font, "Esc — прекратить попытку", cx, overallY + 30, 0xFF81776E);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == 256) { // Esc
            EscapeClientState.action(74); // ACTION_ESCAPE_STOP
            onClose();
            return true;
        }
        char key = 0;
        if (event.key() == 65) key = 'A';
        else if (event.key() == 68) key = 'D';

        if (key != 0) {
            handleTap(key);
            return true;
        }
        return true;
    }

    @Override 
    public void onClose() { 
        if (minecraft != null) minecraft.setScreen(null); 
    }

    @Override 
    public boolean isPauseScreen() { 
        return false; 
    }
}
