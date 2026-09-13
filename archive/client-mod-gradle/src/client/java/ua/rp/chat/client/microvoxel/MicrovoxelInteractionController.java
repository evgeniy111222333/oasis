package ua.rp.chat.client.microvoxel;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;
import ua.rp.chat.microvoxel.MicrovoxelGreedyMesher;
import ua.rp.chat.microvoxel.MicrovoxelRaycaster;

import java.util.List;

public final class MicrovoxelInteractionController {
    private static final int ACTION_CONVERT = 1;
    private static final int ACTION_REMOVE = 2;
    private static final int ACTION_ADD = 3;
    private static final int ACTION_CARVE_STANDARD = 4;
    private static KeyMapping modeKey;
    private static KeyMapping convertKey;
    private static boolean editing;
    private static MicrovoxelRaycaster.Hit currentHit;
    private static StandardTarget currentStandardTarget;
    private static int breakCooldown;

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
            currentStandardTarget = null;
            editing = false;
            breakCooldown = 0;
            return;
        }
        if (breakCooldown > 0) {
            breakCooldown--;
        }
        while (modeKey != null && modeKey.consumeClick()) {
            if (!editing && !ClientPlayNetworking.canSend(MicrovoxelActionPayload.TYPE)) {
                trace("MODE_REJECTED channel-unavailable");
                minecraft.gui.setOverlayMessage(Component.literal(
                        "Сервер не поддерживает редактирование микровокселей или канал ещё не готов."), false);
                continue;
            }
            editing = !editing;
            trace("MODE " + (editing ? "enabled" : "disabled"));
            minecraft.gui.setOverlayMessage(Component.literal(editing
                    ? "Режим микровокселей включён: C — преобразовать, ЛКМ/ПКМ — убрать/добавить"
                    : "Режим микровокселей выключен"), false);
        }
        currentHit = editing ? raycast(minecraft) : null;
        currentStandardTarget = editing && currentHit == null ? standardTarget(minecraft) : null;
        while (editing && convertKey != null && convertKey.consumeClick()) convertTarget(minecraft);
    }

    public static void handleContinuousAttack(Minecraft minecraft) {
        if (breakCooldown > 0) return;
        if (handleAttack(minecraft)) {
            breakCooldown = 5;
        }
    }

    public static boolean handleAttack(Minecraft minecraft) {
        if (!editing || minecraft.player == null) return false;
        MicrovoxelRaycaster.Hit hit = currentHit != null ? currentHit : resolveHit(minecraft);
        if (hit == null) {
            StandardTarget standard = currentStandardTarget != null ? currentStandardTarget : standardTarget(minecraft);
            if (standard != null) {
                trace("ACTION_SENT carve-standard pos=" + standard.position.toShortString() + " cell=" + standard.cell);
                send(minecraft, ACTION_CARVE_STANDARD, standard.position.getX(), standard.position.getY(),
                        standard.position.getZ(), standard.cell, 0);
                minecraft.player.swing(InteractionHand.MAIN_HAND);
                return true;
            }
        }
        if (hit == null) {
            // A microvoxel marker must never fall through to vanilla breaking: its visual block is only a
            // server-side anchor and vanilla would briefly erase it before the server restores it.
            if (targetsKnownVolume(minecraft)) {
                trace("REMOVE_BLOCKED marker-targeted-without-cell");
                minecraft.gui.setOverlayMessage(Component.literal(
                        "Не удалось определить ячейку. Наведитесь на поверхность микровокселя."), false);
                return true;
            }
            return false;
        }
        currentHit = hit;
        trace("ACTION_SENT remove pos=" + hit.entry().x() + "," + hit.entry().y() + "," + hit.entry().z()
                + " cell=" + hit.cell() + " revision=" + hit.entry().volume().revision());
        send(minecraft, ACTION_REMOVE, hit.entry().x(), hit.entry().y(), hit.entry().z(),
                hit.cell(), hit.entry().volume().revision());
        minecraft.player.swing(InteractionHand.MAIN_HAND);
        return true;
    }

    public static boolean handleUse(Minecraft minecraft) {
        if (!editing || minecraft.player == null) return false;
        MicrovoxelRaycaster.Hit hit = currentHit != null ? currentHit : resolveHit(minecraft);
        if (hit == null) {
            if (targetsKnownVolume(minecraft)) {
                trace("ADD_BLOCKED marker-targeted-without-cell");
                minecraft.gui.setOverlayMessage(Component.literal(
                        "Не удалось определить ячейку. Наведитесь на поверхность микровокселя."), false);
                return true;
            }
            return false;
        }
        currentHit = hit;
        int adjacent = hit.adjacentCell();
        if (adjacent < 0) {
            minecraft.gui.setOverlayMessage(Component.literal(
                    "Добавление за границей блока запрещено: сначала преобразуйте соседний полный блок."), false);
            return true;
        }
        trace("ACTION_SENT add pos=" + hit.entry().x() + "," + hit.entry().y() + "," + hit.entry().z()
                + " cell=" + adjacent + " revision=" + hit.entry().volume().revision());
        send(minecraft, ACTION_ADD, hit.entry().x(), hit.entry().y(), hit.entry().z(),
                adjacent, hit.entry().volume().revision());
        minecraft.player.swing(InteractionHand.MAIN_HAND);
        return true;
    }

    public static MicrovoxelRaycaster.Hit currentHit() {
        return currentHit;
    }

    public static StandardTarget currentStandardTarget() {
        return currentStandardTarget;
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
        send(minecraft, ACTION_CONVERT, position.getX(), position.getY(), position.getZ(), 0, 0);
    }

    private static MicrovoxelRaycaster.Hit raycast(Minecraft minecraft) {
        Vec3 eye = minecraft.player.getEyePosition(1.0f);
        Vec3 direction = minecraft.player.getViewVector(1.0f).normalize();
        double reach = Math.min(6.25, minecraft.player.blockInteractionRange() + 0.25);
        return MicrovoxelRaycaster.cast(eye.x, eye.y, eye.z, direction.x, direction.y, direction.z, reach,
                MicrovoxelClientState.raycastEntries(eye.x, eye.y, eye.z, reach + 2.0));
    }

    /**
     * The normal path uses the same eye ray as the server.  Some client camera modes can however
     * leave that ray one frame behind the actual crosshair target.  In that case, reconstruct the
     * microvoxel hit from Minecraft's already-resolved block hit before allowing vanilla to act.
     */
    private static MicrovoxelRaycaster.Hit resolveHit(Minecraft minecraft) {
        MicrovoxelRaycaster.Hit direct = raycast(minecraft);
        if (direct != null || !(minecraft.hitResult instanceof BlockHitResult blockHit)) return direct;

        BlockPos position = blockHit.getBlockPos();
        MicrovoxelClientState.CachedVolume cached = MicrovoxelClientState.get(position);
        if (cached == null) return null;
        MicrovoxelRaycaster.Entry entry = new MicrovoxelRaycaster.Entry(
                position.getX(), position.getY(), position.getZ(), cached.volume);
        Vec3 eye = minecraft.player.getEyePosition(1.0f);
        Vec3 target = blockHit.getLocation();
        Vec3 delta = target.subtract(eye);
        double distance = delta.length();
        if (distance > 1.0E-5 && distance <= 6.5) {
            MicrovoxelRaycaster.Hit reconstructed = MicrovoxelRaycaster.cast(
                    eye.x, eye.y, eye.z, delta.x / distance, delta.y / distance, delta.z / distance,
                    // A vanilla marker can be hit on an empty cavity.  Continue the ray through
                    // the entire editable reach, otherwise the fallback stops at that cavity and
                    // makes a partially carved block impossible to select.
                    Math.min(6.25, minecraft.player.blockInteractionRange() + 0.25), List.of(entry));
            if (reconstructed != null) return reconstructed;
        }

        // Last-resort protection for a marker selected by vanilla. Nudge inside the clicked face,
        // then map its exact surface point to a 1/16 cell.
        Direction face = blockHit.getDirection();
        Vec3 point = target.subtract(face.getStepX() * 1.0E-4, face.getStepY() * 1.0E-4, face.getStepZ() * 1.0E-4);
        int cellX = clampCell((int) Math.floor((point.x - position.getX()) * 16.0));
        int cellY = clampCell((int) Math.floor((point.y - position.getY()) * 16.0));
        int cellZ = clampCell((int) Math.floor((point.z - position.getZ()) * 16.0));
        int cell = ua.rp.chat.microvoxel.MicrovoxelVolume.index(cellX, cellY, cellZ);
        if (cached.volume.materialAt(cellX, cellY, cellZ) == 0) return null;
        return new MicrovoxelRaycaster.Hit(entry, cell,
                ua.rp.chat.microvoxel.MicrovoxelGreedyMesher.Direction.valueOf(face.name()), distance);
    }

    private static boolean targetsKnownVolume(Minecraft minecraft) {
        return minecraft.hitResult instanceof BlockHitResult blockHit
                && MicrovoxelClientState.get(blockHit.getBlockPos()) != null;
    }

    /**
     * While in edit mode a normal full block is a valid first cut. The client only proposes the
     * exact cell; the server repeats the standard-block raycast before it converts anything.
     */
    private static StandardTarget standardTarget(Minecraft minecraft) {
        if (!(minecraft.hitResult instanceof BlockHitResult hit) || minecraft.level == null) return null;
        BlockPos position = hit.getBlockPos();
        if (MicrovoxelClientState.get(position) != null) return null;
        var state = minecraft.level.getBlockState(position);
        if (state.isAir() || !state.isSolidRender() || state.hasBlockEntity()) return null;
        Vec3 point = hit.getLocation().subtract(hit.getDirection().getStepX() * 1.0E-4,
                hit.getDirection().getStepY() * 1.0E-4, hit.getDirection().getStepZ() * 1.0E-4);
        int x = clampCell((int) Math.floor((point.x - position.getX()) * 16.0));
        int y = clampCell((int) Math.floor((point.y - position.getY()) * 16.0));
        int z = clampCell((int) Math.floor((point.z - position.getZ()) * 16.0));
        return new StandardTarget(position.immutable(),
                ua.rp.chat.microvoxel.MicrovoxelVolume.index(x, y, z),
                MicrovoxelGreedyMesher.Direction.valueOf(hit.getDirection().name()));
    }

    private static int clampCell(int cell) {
        return Math.max(0, Math.min(15, cell));
    }

    public record StandardTarget(BlockPos position, int cell, MicrovoxelGreedyMesher.Direction face) {
    }

    private static void send(Minecraft minecraft, int action, int x, int y, int z, int cell, int revision) {
        if (ClientPlayNetworking.canSend(MicrovoxelActionPayload.TYPE)) {
            Vec3 look = minecraft.player == null ? Vec3.ZERO : minecraft.player.getViewVector(1.0f).normalize();
            Vec3 eye = minecraft.player == null ? Vec3.ZERO : minecraft.player.getEyePosition(1.0f);
            ClientPlayNetworking.send(new MicrovoxelActionPayload(action, x, y, z, cell, revision,
                    (float) look.x, (float) look.y, (float) look.z,
                    (float) eye.x, (float) eye.y, (float) eye.z));
        } else {
            trace("ACTION_NOT_SENT channel-unavailable action=" + action);
        }
    }

    private static void trace(String message) {
        ua.rp.chat.client.EclipseClientMod.LOGGER.info("[MICROVOXEL] " + message);
    }
}
