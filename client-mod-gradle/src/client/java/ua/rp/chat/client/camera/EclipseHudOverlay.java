package ua.rp.chat.client.camera;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import ua.rp.chat.client.AcquaintanceClientState;
import ua.rp.chat.client.EclipseClientMod;

public final class EclipseHudOverlay implements HudElement {
    private static final Identifier ID = Identifier.fromNamespaceAndPath(EclipseClientMod.MOD_ID, "helmet_visor");

    private EclipseHudOverlay() {
    }

    public static void register() {
        HudElementRegistry.addLast(ID, new EclipseHudOverlay());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        SmartCameraManager manager = SmartCameraManager.getInstance();
        int width = graphics.guiWidth();
        int height = graphics.guiHeight();
        if (manager.isCameraFailClosed()) {
            graphics.fill(0, 0, width, height, 0xFF000000);
            return;
        }
        if (manager.shouldRenderHelmetVisor()) {
            int alpha = Math.round(manager.getHelmetVisorAlpha() * 255.0f);
            int edge = alpha << 24;
            int deep = Math.min(220, alpha + 54) << 24;
            int sideWidth = Math.max(18, width / 13);
            int topHeight = Math.max(12, height / 18);
            int bottomHeight = Math.max(16, height / 15);

            graphics.fill(0, 0, sideWidth, height, edge);
            graphics.fill(width - sideWidth, 0, width, height, edge);
            graphics.fill(0, 0, width, topHeight, deep);
            graphics.fill(0, height - bottomHeight, width, height, deep);

            int slitHeight = Math.max(2, height / 160);
            int slitY = topHeight + Math.max(8, height / 34);
            graphics.fill(sideWidth, slitY, width - sideWidth, slitY + slitHeight, (alpha / 2) << 24);
        }

        float stamina = ua.rp.chat.client.vitals.VitalsClientState.getStamina01();
        float danger = manager.getStaminaDanger01();
        int barWidth = Math.max(72, width / 8);
        int barX = width - barWidth - 18;
        int barY = height - 28;
        int fill = Math.round((barWidth - 2) * stamina);
        int color = stamina > 0.5f ? 0xD0A5C3C4 : stamina > 0.25f ? 0xD0E3C099 : 0xD0E3A899;
        graphics.fill(barX, barY, barX + barWidth, barY + 5, 0x78000000);
        graphics.fill(barX + 1, barY + 1, barX + 1 + fill, barY + 4, color);
        graphics.text(net.minecraft.client.Minecraft.getInstance().font, "STAM", barX, barY - 10, 0x99D8D1C8);

        if (danger > 0.0f || ua.rp.chat.client.vitals.VitalsClientState.isUnconscious()) {
            MedicalScreenEffects.render(graphics, width, height);
        }

        AcquaintanceClientState.render(graphics, width, height);
    }
}
