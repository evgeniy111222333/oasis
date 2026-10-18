package ua.rp.chat.client.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.item.ItemStack;
import ua.rp.chat.RespirationModel;
import ua.rp.chat.client.camera.RespirationController;
import ua.rp.chat.client.camera.SmartCameraManager;

/** One respiratory sample shared by skin, armor and independent render layers. */
public final class BreathingPoseState {
    private BreathingPoseState() {
    }

    public static Sample sample(AvatarRenderState state) {
        boolean localPlayer = state instanceof LocalPlayerRenderState local && local.eclipse$isLocalPlayer();
        Minecraft client = Minecraft.getInstance();
        localPlayer |= client != null && client.player != null && state.id == client.player.getId();

        RespirationModel.Snapshot respiration = localPlayer
                ? RespirationController.getInstance().sampleFrame()
                : RespirationController.getInstance().sampleRemote(state.ageInTicks, state.id);
        float moving = clamp(state.walkAnimationSpeed * 3.2f, 0.0f, 1.0f);
        float motionDamp = 1.0f - moving * 0.55f;

        ItemStack main = state.getMainHandItemStack();
        String heldItem = main == null || main.isEmpty() ? "" : main.getItem().toString().toLowerCase();
        if (heldItem.contains("axe") || heldItem.contains("mace") || heldItem.contains("hammer")
                || heldItem.contains("great") || heldItem.contains("halberd")) {
            motionDamp *= 0.86f;
        }
        if (state.isUsingItem && (heldItem.contains("bow") || heldItem.contains("crossbow")
                || heldItem.contains("trident") || heldItem.contains("spear"))) {
            motionDamp *= 0.55f;
        }

        float calmBase = state.isCrouching ? 0.62f : 1.0f;
        float calm = calmBase * clamp(motionDamp, 0.28f, 1.0f);
        boolean firstPerson = localPlayer
                && SmartCameraManager.getInstance().shouldApplyFirstPersonBodyPose();
        return new Sample(respiration, moving, calm, localPlayer, firstPerson);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    public record Sample(
            RespirationModel.Snapshot respiration,
            float moving,
            float calm,
            boolean localPlayer,
            boolean firstPerson) {
    }
}
