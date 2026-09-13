package ua.rp.chat.client.carver;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

/**
 * Rebindable editor keys, visible in the vanilla Controls screen under gameplay.
 * Undo/redo additionally require a held Ctrl so bare Z/Y stay free for mirror duty.
 */
public final class CarverKeybinds {
    public static KeyMapping undo;
    public static KeyMapping redo;
    public static KeyMapping mirrorX;
    public static KeyMapping mirrorZ;

    private CarverKeybinds() {
    }

    public static void register() {
        // Undo/redo carry a real Ctrl modifier (shown as Ctrl+Z / Ctrl+Y in Controls) instead
        // of a bare key plus a hidden runtime check.
        undo = ctrlKey("key.eclipse.carver_undo", GLFW.GLFW_KEY_Z);
        redo = ctrlKey("key.eclipse.carver_redo", GLFW.GLFW_KEY_Y);
        mirrorX = key("key.eclipse.carver_mirror_x", GLFW.GLFW_KEY_X);
        mirrorZ = key("key.eclipse.carver_mirror_z", GLFW.GLFW_KEY_C);
        // Box mode and tool toggle also have on-screen buttons, so no bare shortcuts remain.
    }

    private static KeyMapping key(String translationKey, int defaultCode) {
        return KeyMappingHelper.registerKeyMapping(new KeyMapping(translationKey,
                InputConstants.Type.KEYSYM, defaultCode, KeyMapping.Category.GAMEPLAY));
    }

    private static KeyMapping ctrlKey(String translationKey, int defaultCode) {
        return KeyMappingHelper.registerKeyMapping(new KeyMapping(translationKey,
                InputConstants.Type.KEYSYM, defaultCode, KeyMapping.Category.GAMEPLAY,
                GLFW.GLFW_KEY_LEFT_CONTROL));
    }

}
