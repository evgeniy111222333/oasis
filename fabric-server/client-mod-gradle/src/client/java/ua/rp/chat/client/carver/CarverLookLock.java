package ua.rp.chat.client.carver;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;

/**
 * Cursor kill-switch for the carving run: from the SPACE press until the very
 * end of the process the artisan's look is owned by the workpiece, never by the
 * mouse. Engagement snaps the look instantly (no ease-in for the eye to chase);
 * every client tick re-forces it, and the mouse-turn mixin cancels deltas at the
 * source, so between-tick mouse movement cannot leak a single turned frame into
 * the IK, the gaze or the hammer. The orbit camera keeps full control throughout:
 * it reads its own orbit angles, never the player look.
 */
public final class CarverLookLock {
    private CarverLookLock() {
    }

    private static boolean engaged;
    private static float yaw;
    private static float pitch;

    public static boolean engaged() {
        return engaged;
    }

    /**
     * Engages the lock aiming the eyes at the contact, snapping instantly.
     * Safe to call every SPACE press; re-engagement just re-aims.
     */
    public static void engage(Player player, double contactX, double contactY, double contactZ) {
        if (player == null) return;
        try {
            net.minecraft.world.phys.Vec3 eye = player.getEyePosition();
            double[] look = ua.rp.chat.carver.CarverLookMath.lookYawPitch(
                    eye.x, eye.y, eye.z, contactX, contactY, contactZ);
            yaw = (float) look[0];
            pitch = (float) look[1];
            engaged = true;
            player.setYRot(yaw);
            player.setXRot(pitch);
        } catch (RuntimeException ignored) {
        }
    }

    /** Re-aims the locked angles without disengaging (plan refined mid-walk). */
    public static void retarget(Player player, double contactX, double contactY, double contactZ) {
        if (!engaged || player == null) return;
        try {
            net.minecraft.world.phys.Vec3 eye = player.getEyePosition();
            double[] look = ua.rp.chat.carver.CarverLookMath.lookYawPitch(
                    eye.x, eye.y, eye.z, contactX, contactY, contactZ);
            yaw = (float) look[0];
            pitch = (float) look[1];
        } catch (RuntimeException ignored) {
        }
    }

    /** Forces the locked look; runs every client tick while engaged. */
    public static void tick(Minecraft minecraft) {
        if (!engaged || minecraft == null || minecraft.player == null) return;
        try {
            minecraft.player.setYRot(yaw);
            minecraft.player.setXRot(pitch);
        } catch (RuntimeException ignored) {
        }
    }

    public static void disengage() {
        engaged = false;
    }
}
