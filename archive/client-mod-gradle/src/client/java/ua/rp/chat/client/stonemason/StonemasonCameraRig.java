package ua.rp.chat.client.stonemason;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * Cinematic camera for drafting sessions. On design entry the camera glides from the
 * eyes to a 45-degree orbit (1.5 m up, 1.5 m to the side of the block) and stays there;
 * on approval the pose freezes as the work close-up until the session ends.
 */
public final class StonemasonCameraRig {
    private static final double UP_METERS = 1.5;
    private static final double SIDE_METERS = 1.5;
    private static final double BLEND_TICKS = 10.0;

    private static boolean active;
    private static boolean frozen;
    private static BlockPos focus;
    private static Vec3 posePosition = Vec3.ZERO;
    private static float poseYaw;
    private static float posePitch;
    private static double blend;

    private StonemasonCameraRig() {
    }

    public static boolean active() {
        return active;
    }

    public static void beginDesign(BlockPos focusBlock) {
        Minecraft minecraft = Minecraft.getInstance();
        active = true;
        frozen = false;
        focus = focusBlock;
        blend = 0.0;
        if (minecraft.gameRenderer != null && minecraft.gameRenderer.getMainCamera() != null) {
            posePosition = minecraft.gameRenderer.getMainCamera().position();
        } else if (minecraft.player != null) {
            posePosition = minecraft.player.getEyePosition();
        } else {
            posePosition = Vec3.ZERO;
        }
        poseYaw = minecraft.player == null ? 0.0f : minecraft.player.getYRot();
        posePitch = minecraft.player == null ? 0.0f : minecraft.player.getXRot();
    }

    public static void beginWork(BlockPos focusBlock) {
        if (!active) {
            beginDesign(focusBlock);
        }
        focus = focusBlock;
        frozen = true;
        blend = 1.0;
    }

    public static void end() {
        active = false;
        frozen = false;
        focus = null;
        blend = 0.0;
    }

    /** Camera override consumed by the camera mixin; null when the rig is parked. */
    public static Pose pose(Player player) {
        if (!active || focus == null || player == null) return null;
        return new Pose(posePosition, poseYaw, posePitch);
    }

    public static void tick(Minecraft minecraft) {
        if (!active || focus == null || minecraft.player == null) return;
        if (frozen) return;
        Pose target = orbitTarget(minecraft);
        if (blend < 1.0) {
            blend = Math.min(1.0, blend + 1.0 / BLEND_TICKS);
        }
        double smooth = blend * blend * (3.0 - 2.0 * blend);
        posePosition = posePosition.lerp(target.position(), smooth);
        poseYaw = lerpAngle(poseYaw, target.yaw(), (float) smooth);
        posePitch = lerpAngle(posePitch, target.pitch(), (float) smooth);
    }

    private static Pose orbitTarget(Minecraft minecraft) {
        Vec3 center = new Vec3(focus.getX() + 0.5, focus.getY() + 0.5, focus.getZ() + 0.5);
        Vec3 eye = minecraft.player.getEyePosition();
        Vec3 away = new Vec3(eye.x - center.x, 0.0, eye.z - center.z);
        if (away.lengthSqr() < 1.0e-6) {
            away = new Vec3(1.0, 0.0, 1.0);
        }
        away = away.normalize();
        Vec3 position = center.add(away.x * SIDE_METERS, UP_METERS, away.z * SIDE_METERS);
        Vec3 look = center.subtract(position).normalize();
        float yaw = (float) Math.toDegrees(Math.atan2(-look.x, look.z));
        float pitch = (float) Math.toDegrees(Math.asin(Math.max(-1.0, Math.min(1.0, -look.y))));
        return new Pose(position, yaw, pitch);
    }

    private static float lerpAngle(float from, float to, float t) {
        float delta = ((to - from + 540.0f) % 360.0f) - 180.0f;
        return from + delta * t;
    }

    public record Pose(Vec3 position, float yaw, float pitch) {
    }
}
