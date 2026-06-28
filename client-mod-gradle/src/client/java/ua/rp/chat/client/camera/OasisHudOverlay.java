package ua.rp.chat.client.camera;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import ua.rp.chat.client.OasisAuthMod;

public final class OasisHudOverlay implements HudElement {
    private static final Identifier ID = Identifier.fromNamespaceAndPath(OasisAuthMod.MOD_ID, "helmet_visor");

    private OasisHudOverlay() {
    }

    public static void register() {
        HudElementRegistry.addLast(ID, new OasisHudOverlay());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        SmartCameraManager manager = SmartCameraManager.getInstance();
        if (!manager.shouldRenderHelmetVisor()) {
            return;
        }

        int width = graphics.guiWidth();
        int height = graphics.guiHeight();
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
}
