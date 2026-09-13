package ua.rp.chat.client.microvoxel;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import ua.rp.chat.client.carver.CarverClientState;
import ua.rp.chat.microvoxel.MicrovoxelRaycaster;

import java.util.List;

/**
 * Radial-menu bridge of the Carver.
 *
 * <p>All voxel editing belongs to the Carver draft now (убрать / поставить, applied on SPACE,
 * previewed by the hologram). This class no longer edits voxels: it only opens the MCEF wheel
 * (G), maps its choices to the draft mode/material, and keeps the global Ctrl+Z binding.
 * The leftover attack/selection API is deliberately inert so the world hooks fall through to
 * vanilla behaviour instead of a second, parallel editor.</p>
 */
public final class MicrovoxelInteractionController {
    private static KeyMapping undoKey;
    private static KeyMapping radialKey;
    private static boolean warnedNoSession;

    private MicrovoxelInteractionController() {
    }

    public static void register() {
        // Global undo stays on Ctrl+Z; everything else lives in the radial menu (G).
        undoKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.eclipseclient.microvoxel_undo", InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_Z, KeyMapping.Category.GAMEPLAY, GLFW.GLFW_KEY_LEFT_CONTROL));
        radialKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.eclipseclient.microvoxel_radial", InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_G, KeyMapping.Category.GAMEPLAY));
    }

    public static void tick(Minecraft minecraft) {
        if (minecraft == null || minecraft.player == null || minecraft.level == null) {
            return;
        }
        while (undoKey != null && undoKey.consumeClick()) {
            CarverClientState.sendUndo();
        }
        while (radialKey != null && radialKey.consumeClick()) {
            openRadial();
        }
    }

    /** The old in-world microvoxel editor is gone: the left button is never hijacked. */
    public static boolean handleAttack(Minecraft minecraft) {
        return false;
    }

    /** The right button is reserved for the Carver hologram orbit / vanilla use. */
    public static boolean handleUse(Minecraft minecraft) {
        return false;
    }

    public static void handleContinuousAttack(Minecraft minecraft) {
    }

    public static boolean editing() {
        return false;
    }

    public static MicrovoxelRaycaster.Hit currentHit() {
        return null;
    }

    public static StandardTarget currentStandardTarget() {
        return null;
    }

    public static List<PreviewCell> brushPreview() {
        return List.of();
    }

    public static boolean isRadialKey(net.minecraft.client.input.KeyEvent event) {
        return radialKey != null && radialKey.matches(event);
    }

    /** Opens the wheel over the current screen (the Carver design screen) and restores it on close. */
    public static void openRadial() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) return;
        if (!CarverClientState.inSession()) {
            if (!warnedNoSession) {
                warnedNoSession = true;
                minecraft.gui.setOverlayMessage(Component.literal(
                        "Сначала возьмите свиток архитектора и войдите в режим."), false);
            }
            return;
        }
        Screen previous = minecraft.screen;
        minecraft.setScreen(new MicrovoxelRadialScreen(
                MicrovoxelInteractionController::handleRadialSelection, previous));
    }

    /**
     * Maps a radial choice to the Carver draft: the wheel only switches mode/material and
     * history — it never edits voxels directly.
     */
    static void handleRadialSelection(String action, String material, boolean fragment, int shapeId,
                                      int length, int width, int height,
                                      int chosenBrushShape, int chosenBrushRadius) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || action == null) return;
        switch (action) {
            case "erase" -> {
                CarverClientState.setPlacing(false);
                minecraft.gui.setOverlayMessage(Component.literal("Режим: убрать"), false);
            }
            case "place" -> {
                CarverClientState.setPlacing(true);
                minecraft.gui.setOverlayMessage(Component.literal(
                        CarverClientState.placeMaterial().isEmpty()
                                ? "Режим: поставить. Выберите «Материал»."
                                : "Режим: поставить"), false);
            }
            case "material" -> {
                if (material == null || material.isBlank()) {
                    minecraft.gui.setOverlayMessage(
                            Component.literal("Нет материала в инвентаре."), false);
                    return;
                }
                CarverClientState.setPlaceMaterial(material);
                CarverClientState.setPlacing(true);
                minecraft.gui.setOverlayMessage(Component.literal("Материал выбран."), false);
            }
            case "history.undo", "undo" -> CarverClientState.sendUndo();
            case "history.redo", "redo" -> CarverClientState.sendRedo();
            default -> {
                // shape/gen/clip/brush were microvoxel-editor actions and no longer exist.
            }
        }
    }

    public record StandardTarget(BlockPos position, int cell,
                                 ua.rp.chat.microvoxel.MicrovoxelGreedyMesher.Direction face) {
    }

    public record PreviewCell(BlockPos position, int cell) {
    }
}
