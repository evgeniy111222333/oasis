package ua.rp.chat.client.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.item.ItemStack;
import ua.rp.chat.BreathingTorsoLayout;
import ua.rp.chat.RespirationModel;
import ua.rp.chat.client.camera.RespirationController;
import ua.rp.chat.client.camera.SmartCameraManager;

/** Keeps independent back attachments outside the breathing skin surface. */
public final class BreathingAttachmentOffset {
    private static final int UPPER_BACK_RING = 2;
    private static final float CLEARANCE_MULTIPLIER = 1.10f;

    private BreathingAttachmentOffset() {
    }

    public static float backOffsetPixels(AvatarRenderState state) {
        if (state == null || state.isFallFlying || state.isVisuallySwimming || state.isPassenger) {
            return 0.0f;
        }

        Minecraft client = Minecraft.getInstance();
        boolean localPlayer = client != null && client.player != null && state.id == client.player.getId();
        RespirationModel.Snapshot respiration = localPlayer
                ? RespirationController.getInstance().sampleFrame()
                : RespirationController.getInstance().sampleRemote(state.ageInTicks, state.id);

        float moving = clamp(state.walkAnimationSpeed * 3.2f, 0.0f, 1.0f);
        float calm = (state.isCrouching ? 0.62f : 1.0f)
                * clamp(1.0f - moving * 0.55f, 0.28f, 1.0f);
        ItemStack main = state.getMainHandItemStack();
        String heldItem = main == null || main.isEmpty() ? "" : main.getItem().toString().toLowerCase();
        if (heldItem.contains("axe") || heldItem.contains("mace") || heldItem.contains("hammer")
                || heldItem.contains("great") || heldItem.contains("halberd")) {
            calm *= 0.86f;
        }
        if (state.isUsingItem && (heldItem.contains("bow") || heldItem.contains("crossbow")
                || heldItem.contains("trident") || heldItem.contains("spear"))) {
            calm *= 0.55f;
        }

        boolean firstPerson = localPlayer
                && SmartCameraManager.getInstance().shouldApplyFirstPersonBodyPose();
        BreathingTorsoLayout.Bounds upperBack = BreathingTorsoLayout.bounds(
                UPPER_BACK_RING, respiration.phase(), respiration.intensity(), calm, firstPerson, false);
        return Math.max(0.0f, upperBack.maxZ() - BreathingTorsoLayout.BODY_HALF_DEPTH)
                * CLEARANCE_MULTIPLIER;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
