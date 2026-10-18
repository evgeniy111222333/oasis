package ua.rp.chat.client.camera;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import ua.rp.chat.client.AcquaintanceClientState;
import ua.rp.chat.client.EclipseClientMod;
import ua.rp.chat.client.pickup.PickupClientState;
import ua.rp.chat.client.rpfeed.RpChatFeedClientState;

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

        // Screen-space dust during the carving work phase: drifting motes and wind streaks keep
        // the shot readable instead of hiding the camera. Drawn first so the readouts stay on top.
        ua.rp.chat.client.carver.CarverDustScreen.render(graphics, width, height);

        float danger = manager.getStaminaDanger01();

        if (danger > 0.0f || ua.rp.chat.client.vitals.VitalsClientState.isUnconscious()) {
            MedicalScreenEffects.render(graphics, width, height);
        }

        AcquaintanceClientState.render(graphics, width, height);
        if (!AcquaintanceClientState.isCustomTabVisible()) {
            RpChatFeedClientState.render(graphics, width, height);
            PickupClientState.renderHud(graphics, width, height);
        }
    }
}
