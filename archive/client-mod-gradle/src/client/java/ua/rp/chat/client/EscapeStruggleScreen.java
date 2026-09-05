package ua.rp.chat.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

public final class EscapeStruggleScreen extends Screen {
    private int ticks;
    private double balance = 0.5; // 0.0 (left) to 1.0 (right)
    private double currentEffort = 0.0; // 0.0 to 1.0 (current pull progress)
    private double driftVelocity = 0.0;
    
    private int aFlash = 0;
    private int dFlash = 0;
    private int successFlash = 0;

    public EscapeStruggleScreen() { 
        super(Component.literal("Ослабление узлов")); 
    }

    @Override 
    public void tick() {
        ticks++;
        if (aFlash > 0) aFlash--;
        if (dFlash > 0) dFlash--;
        if (successFlash > 0) successFlash--;

        // Pointer physics: natural instability and random drift
        // Drift pulls pointer further away from center
        double bias = (balance - 0.5) * 0.014;
        double jitter = (Math.random() - 0.5) * 0.008;
        
        driftVelocity = clamp(driftVelocity + bias + jitter, -0.035, 0.035);
        balance = clamp(balance + driftVelocity, 0.0, 1.0);

        // Green zone check (width 0.24, center 0.5)
        boolean inGreen = Math.abs(balance - 0.5) <= 0.12;
        if (inGreen) {
            // effort increases when balanced
            currentEffort = Math.min(1.0, currentEffort + 0.015); // ~3.3 seconds to complete
        } else {
            // effort decays when out of balance
            currentEffort = Math.max(0.0, currentEffort - 0.010);
            if (balance <= 0.001 || balance >= 0.999) {
                currentEffort = Math.max(0.0, currentEffort - 0.025); // stuck penalty
            }
        }

        // Check pull success
        if (currentEffort >= 0.999) {
            currentEffort = 0.0;
            successFlash = 8;
            balance = 0.5;
            driftVelocity = 0.0;
            
            // Send pull action to Spigot server
            EscapeClientState.action(71); // ACTION_ESCAPE_QTE
            if (minecraft != null && minecraft.player != null) {
                minecraft.player.playSound(net.minecraft.sounds.SoundEvents.WOOL_BREAK, 0.75f, 0.72f);
            }
        }
    }

    private double clamp(double val, double min, double max) {
        return Math.max(min, Math.min(max, val));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        EscapeClientState.EscapeProgress p = EscapeClientState.progress();
        if (p == null) return;
        int cx = width / 2, cy = height / 2;

        // Dark background overlay
        g.fill(0, 0, width, height, 0x8A000000);

        // Success flash effect (glowing overlay when a knot breaks)
        if (successFlash > 0) {
            int alpha = Math.min(180, successFlash * 22);
            g.fill(0, 0, width, height, (alpha << 24) | 0xE3C099);
        }

        // Glassmorphism main dashboard frame
        int boxW = 280;
        int boxH = 160;
        int topY = cy - 80;
        g.fill(cx - boxW / 2, topY, cx + boxW / 2, topY + boxH, 0xEA110E0B);
        g.fill(cx - boxW / 2, topY, cx + boxW / 2, topY + 2, 0xFFE3C099); // Gold top border

        // Header Text
        g.centeredText(font, "ОСЛАБЛЕНИЕ УЗЛОВ", cx, topY + 8, 0xFFFFE8C5);
        g.centeredText(font, "Удерживайте баланс в зеленой зоне", cx, topY + 20, 0xFFA5C3C4);

        // Slider track
        int trackW = 200;
        int trackH = 10;
        int trackY = cy - 20;
        g.fill(cx - trackW / 2, trackY, cx + trackW / 2, trackY + trackH, 0xFF1D1712); // track bg

        // Green sweet spot (24% width of track, so 48 pixels wide)
        int greenHalfW = 24;
        g.fill(cx - greenHalfW, trackY, cx + greenHalfW, trackY + trackH, 0x774CAF50); // soft green glow

        // Green sweet spot boundaries (dashed indicators)
        g.fill(cx - greenHalfW, trackY - 2, cx - greenHalfW + 1, trackY + trackH + 2, 0xCC81C784);
        g.fill(cx + greenHalfW, trackY - 2, cx + greenHalfW + 1, trackY + trackH + 2, 0xCC81C784);

        // A Key indicator (left side)
        // Glows if pointer is drifting right, recommending player to tap A
        int aBg = (driftVelocity > 0.005) ? 0xED4A3525 : 0xD017130F;
        int aEdge = (driftVelocity > 0.005) ? 0xFFE3C099 : 0xAA6B5A43;
        int aTextColor = (driftVelocity > 0.005) ? 0xFFFFE8C5 : 0xFF746E68;
        if (aFlash > 0) {
            aBg = 0xFFE3C099;
            aTextColor = 0xFF110E0B;
        }
        g.fill(cx - trackW / 2 - 24, trackY - 4, cx - trackW / 2 - 6, trackY + 14, aBg);
        g.fill(cx - trackW / 2 - 24, trackY - 4, cx - trackW / 2 - 6, trackY - 2, aEdge);
        g.centeredText(font, "A", cx - trackW / 2 - 15, trackY + 2, aTextColor);

        // D Key indicator (right side)
        // Glows if pointer is drifting left, recommending player to tap D
        int dBg = (driftVelocity < -0.005) ? 0xED4A3525 : 0xD017130F;
        int dEdge = (driftVelocity < -0.005) ? 0xFFE3C099 : 0xAA6B5A43;
        int dTextColor = (driftVelocity < -0.005) ? 0xFFFFE8C5 : 0xFF746E68;
        if (dFlash > 0) {
            dBg = 0xFFE3C099;
            dTextColor = 0xFF110E0B;
        }
        g.fill(cx + trackW / 2 + 6, trackY - 4, cx + trackW / 2 + 24, trackY + 14, dBg);
        g.fill(cx + trackW / 2 + 6, trackY - 4, cx + trackW / 2 + 24, trackY - 2, dEdge);
        g.centeredText(font, "D", cx + trackW / 2 + 15, trackY + 2, dTextColor);

        // Draw Pointer (with custom colors based on state)
        int px = cx - trackW / 2 + (int) (balance * trackW);
        boolean inGreen = Math.abs(balance - 0.5) <= 0.12;
        int pointerColor = inGreen ? 0xFFFFE8C5 : 0xFFE57373;
        g.fill(px - 2, trackY - 3, px + 2, trackY + trackH + 3, pointerColor);
        g.fill(px - 3, trackY - 4, px + 3, trackY - 3, pointerColor); // top cap
        g.fill(px - 3, trackY + trackH + 3, px + 3, trackY + trackH + 4, pointerColor); // bottom cap

        // Tension Bar (Current Effort)
        int effortY = cy + 18;
        int effortW = 200;
        g.fill(cx - effortW / 2, effortY, cx + effortW / 2, effortY + 8, 0xFF2A211A);
        int effortFill = (int) (effortW * currentEffort);
        g.fill(cx - effortW / 2, effortY, cx - effortW / 2 + effortFill, effortY + 8, 0xFFE07B42);
        
        String hintText = inGreen ? "УДЕРЖИВАЙТЕ БАЛАНС!" : "ВЕРНИТЕ В ЦЕНТР!";
        int hintColor = inGreen ? 0xFF81C784 : 0xFFE57373;
        g.centeredText(font, hintText + " (" + Math.round(currentEffort * 100) + "%)", cx, effortY - 11, hintColor);

        // Overall Escape Progress Bar
        int overallY = cy + 46;
        int overallW = 220;
        g.fill(cx - overallW / 2, overallY, cx + overallW / 2, overallY + 8, 0xFF2A211A);
        int overallFill = (int) (overallW * p.progress());
        g.fill(cx - overallW / 2, overallY, cx - overallW / 2 + overallFill, overallY + 8, 0xFFD5B16F);
        g.centeredText(font, Math.round(p.progress() * 100) + "% путей разрушено", cx, overallY + 12, 0xFFB7A895);

        // Close Hint
        g.centeredText(font, "Esc — прекратить попытку", cx, overallY + 26, 0xFF81776E);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == 256) { // Esc
            EscapeClientState.action(74); // ACTION_ESCAPE_STOP
            onClose();
            return true;
        }
        
        if (event.key() == 65) { // A
            driftVelocity = clamp(driftVelocity - 0.015, -0.035, 0.035);
            aFlash = 3;
            if (minecraft != null && minecraft.player != null) {
                minecraft.player.playSound(net.minecraft.sounds.SoundEvents.WOOL_HIT, 0.35f, 0.85f);
            }
            return true;
        }
        
        if (event.key() == 68) { // D
            driftVelocity = clamp(driftVelocity + 0.015, -0.035, 0.035);
            dFlash = 3;
            if (minecraft != null && minecraft.player != null) {
                minecraft.player.playSound(net.minecraft.sounds.SoundEvents.WOOL_HIT, 0.35f, 0.85f);
            }
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
