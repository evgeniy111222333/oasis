package ua.rp.chat.client.heavyhammer;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import ua.rp.chat.client.microvoxel.MicrovoxelClientState;
import ua.rp.chat.microvoxel.MicrovoxelRaycaster;

public final class HeavyHammerInteractionController {
    private static final double MAX_TARGET_DISTANCE = 2.20;
    private static final double MAX_HORIZONTAL_TARGET_DISTANCE = 1.65;
    private static MicrovoxelRaycaster.Hit currentHit;

    private HeavyHammerInteractionController() {
    }

    public static void tick(Minecraft minecraft) {
        if (minecraft.player == null || minecraft.level == null
                || !HeavyHammerClientState.isHolding(minecraft.player.getMainHandItem())) {
            currentHit = null;
            return;
        }
        currentHit = raycast(minecraft);
    }

    public static boolean handleAttack(Minecraft minecraft) {
        if (minecraft.player == null || !HeavyHammerClientState.isHolding(minecraft.player.getMainHandItem())
                || currentHit == null) return false;
        if (!minecraft.player.getOffhandItem().isEmpty()) {
            minecraft.gui.setOverlayMessage(Component.literal(
                    "Для тяжёлого молота нужно освободить вторую руку."), false);
            return true;
        }
        if (HeavyHammerClientState.striking(minecraft.player)) return true;
        if (!physicallyReachable(minecraft.player.position(), currentHit)) {
            minecraft.gui.setOverlayMessage(Component.literal(
                    "Подойдите ближе: тяжёлый молот не достаёт до точки удара."), false);
            return true;
        }
        if (!ClientPlayNetworking.canSend(HeavyHammerActionPayload.TYPE)) {
            minecraft.gui.setOverlayMessage(Component.literal(
                    "Сервер не поддерживает тяжёлый рабочий молот."), false);
            return true;
        }
        int sequence = HeavyHammerClientState.startPrediction(minecraft.player, currentHit);
        ClientPlayNetworking.send(new HeavyHammerActionPayload(
                currentHit.entry().x(), currentHit.entry().y(), currentHit.entry().z(),
                currentHit.cell(), currentHit.entry().volume().revision(), sequence));
        return true;
    }

    private static MicrovoxelRaycaster.Hit raycast(Minecraft minecraft) {
        Vec3 eye = minecraft.player.getEyePosition(1.0f);
        Vec3 direction = minecraft.player.getViewVector(1.0f).normalize();
        double reach = Math.min(6.25, minecraft.player.blockInteractionRange() + 0.25);
        return MicrovoxelRaycaster.cast(eye.x, eye.y, eye.z, direction.x, direction.y, direction.z, reach,
                MicrovoxelClientState.raycastEntries(eye.x, eye.y, eye.z, reach + 2.0));
    }

    private static boolean physicallyReachable(Vec3 playerPosition, MicrovoxelRaycaster.Hit hit) {
        int cell = hit.cell();
        Vec3 target = new Vec3(
                hit.entry().x() + (ua.rp.chat.microvoxel.MicrovoxelVolume.x(cell)
                        + 0.5 + hit.face().dx * 0.5) / 16.0,
                hit.entry().y() + (ua.rp.chat.microvoxel.MicrovoxelVolume.y(cell)
                        + 0.5 + hit.face().dy * 0.5) / 16.0,
                hit.entry().z() + (ua.rp.chat.microvoxel.MicrovoxelVolume.z(cell)
                        + 0.5 + hit.face().dz * 0.5) / 16.0);
        double dx = target.x - playerPosition.x;
        double dy = target.y - playerPosition.y;
        double dz = target.z - playerPosition.z;
        double horizontalSquared = dx * dx + dz * dz;
        return horizontalSquared <= MAX_HORIZONTAL_TARGET_DISTANCE * MAX_HORIZONTAL_TARGET_DISTANCE
                && dy >= -0.30 && dy <= 2.35
                && horizontalSquared + dy * dy <= MAX_TARGET_DISTANCE * MAX_TARGET_DISTANCE;
    }
}
