package ua.rp.chat.client.microvoxel;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;
import ua.rp.chat.microvoxel.MicrovoxelRaycaster;

public final class MicrovoxelInteractionController {
    private static final int ACTION_CONVERT = 1;
    private static final int ACTION_REMOVE = 2;
    private static final int ACTION_ADD = 3;
    private static KeyMapping modeKey;
    private static KeyMapping convertKey;
    private static boolean editing;
    private static MicrovoxelRaycaster.Hit currentHit;

    private MicrovoxelInteractionController() {
    }

    public static void register() {
        modeKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.eclipseclient.microvoxel_mode", InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_M, KeyMapping.Category.GAMEPLAY));
        convertKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.eclipseclient.microvoxel_convert", InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_C, KeyMapping.Category.GAMEPLAY));
    }

    public static void tick(Minecraft minecraft) {
        if (minecraft.player == null || minecraft.level == null) {
            currentHit = null;
            editing = false;
            return;
        }
        while (modeKey != null && modeKey.consumeClick()) {
            if (!editing && !ClientPlayNetworking.canSend(MicrovoxelActionPayload.TYPE)) {
                minecraft.gui.setOverlayMessage(Component.literal(
                        "Сервер не поддерживает редактирование микровокселей или канал ещё не готов."), false);
                continue;
            }
            editing = !editing;
            minecraft.gui.setOverlayMessage(Component.literal(editing
                    ? "Режим микровокселей включён: C — преобразовать, ЛКМ/ПКМ — убрать/добавить"
                    : "Режим микровокселей выключен"), false);
        }
        currentHit = editing ? raycast(minecraft) : null;
        while (editing && convertKey != null && convertKey.consumeClick()) convertTarget(minecraft);
    }

    public static boolean handleAttack(Minecraft minecraft) {
        if (!editing || currentHit == null || minecraft.player == null) return false;
        send(ACTION_REMOVE, currentHit.entry().x(), currentHit.entry().y(), currentHit.entry().z(),
                currentHit.cell(), currentHit.entry().volume().revision());
        minecraft.player.swing(InteractionHand.MAIN_HAND);
        return true;
    }

    public static boolean handleUse(Minecraft minecraft) {
        if (!editing || currentHit == null || minecraft.player == null) return false;
        int adjacent = currentHit.adjacentCell();
        if (adjacent < 0) {
            minecraft.gui.setOverlayMessage(Component.literal(
                    "Добавление за границей блока запрещено: сначала преобразуйте соседний полный блок."), false);
            return true;
        }
        send(ACTION_ADD, currentHit.entry().x(), currentHit.entry().y(), currentHit.entry().z(),
                adjacent, currentHit.entry().volume().revision());
        minecraft.player.swing(InteractionHand.MAIN_HAND);
        return true;
    }

    public static MicrovoxelRaycaster.Hit currentHit() {
        return currentHit;
    }

    public static boolean editing() {
        return editing;
    }

    private static void convertTarget(Minecraft minecraft) {
        if (!(minecraft.hitResult instanceof BlockHitResult blockHit)) {
            minecraft.gui.setOverlayMessage(Component.literal("Нужно смотреть на полноразмерный блок."), false);
            return;
        }
        BlockPos position = blockHit.getBlockPos();
        if (MicrovoxelClientState.get(position) != null) return;
        send(ACTION_CONVERT, position.getX(), position.getY(), position.getZ(), 0, 0);
    }

    private static MicrovoxelRaycaster.Hit raycast(Minecraft minecraft) {
        Vec3 eye = minecraft.player.getEyePosition(1.0f);
        Vec3 direction = minecraft.player.getViewVector(1.0f).normalize();
        double reach = Math.min(6.25, minecraft.player.blockInteractionRange() + 0.25);
        return MicrovoxelRaycaster.cast(eye.x, eye.y, eye.z, direction.x, direction.y, direction.z, reach,
                MicrovoxelClientState.raycastEntries(eye.x, eye.y, eye.z, reach + 2.0));
    }

    private static void send(int action, int x, int y, int z, int cell, int revision) {
        if (ClientPlayNetworking.canSend(MicrovoxelActionPayload.TYPE)) {
            ClientPlayNetworking.send(new MicrovoxelActionPayload(action, x, y, z, cell, revision));
        }
    }
}
