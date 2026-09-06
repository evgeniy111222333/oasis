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
    public static KeyMapping layerUp;
    public static KeyMapping layerDown;
    public static KeyMapping depthUp;
    public static KeyMapping depthDown;
    public static KeyMapping boxMode;
    public static KeyMapping toolToggle;

    private CarverKeybinds() {
    }

    public static void register() {
        undo = key("key.eclipse.carver_undo", GLFW.GLFW_KEY_Z);
        redo = key("key.eclipse.carver_redo", GLFW.GLFW_KEY_Y);
        mirrorX = key("key.eclipse.carver_mirror_x", GLFW.GLFW_KEY_X);
        mirrorZ = key("key.eclipse.carver_mirror_z", GLFW.GLFW_KEY_C);
        layerUp = key("key.eclipse.carver_layer_up", GLFW.GLFW_KEY_R);
        layerDown = key("key.eclipse.carver_layer_down", GLFW.GLFW_KEY_F);
        depthUp = key("key.eclipse.carver_depth_up", GLFW.GLFW_KEY_T);
        depthDown = key("key.eclipse.carver_depth_down", GLFW.GLFW_KEY_G);
        boxMode = key("key.eclipse.carver_box", GLFW.GLFW_KEY_B);
        toolToggle = key("key.eclipse.carver_tool", GLFW.GLFW_KEY_E);
    }

    private static KeyMapping key(String translationKey, int defaultCode) {
        return KeyMappingHelper.registerKeyMapping(new KeyMapping(translationKey,
                InputConstants.Type.KEYSYM, defaultCode, KeyMapping.Category.GAMEPLAY));
    }

}
