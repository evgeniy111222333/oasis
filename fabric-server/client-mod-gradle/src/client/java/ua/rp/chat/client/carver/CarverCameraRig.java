package ua.rp.chat.client.carver;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import ua.rp.chat.carver.CarverCameraMath;

/**
 * Cinematic camera for carving sessions. Entry flies the camera from the eyes to a
 * close-up orbit with an eased landing that slightly overflies the anchor; afterwards
 * the player orbits with right-drag, zooms with the wheel, and the pose freezes as
 * the work close-up on approval. Rotation always derives from the position by
 * looking at the block center, so the framing can never drift off the workpiece.
 */
public final class CarverCameraRig {
    private enum Mode { IDLE, FLY, ORBIT, WORK }

    private static Mode mode = Mode.IDLE;
    private static BlockPos focus;
    private static Vec3 flyFrom = Vec3.ZERO;
    private static double orbitYaw;
    private static double orbitPitch = CarverCameraMath.ENTRY_PITCH;
    private static double orbitDist = CarverCameraMath.ENTRY_DIST;
    private static double dragVelYaw;
    private static double dragVelPitch;
    private static int flyTick;
    private static Vec3 posePosition = Vec3.ZERO;
    private static float poseYaw;
    private static float posePitch;
    private static Vec3 prevPosePosition = Vec3.ZERO;
    private static float prevPoseYaw;
    private static float prevPosePitch;
    private static long lastTickNanos;
    private static double shakeTrauma;

    private CarverCameraRig() {
    }

    public static boolean active() {
        return mode != Mode.IDLE;
    }

    /** True once the fly-to has landed on the orbit (or the work framing holds). */
    public static boolean arrived() {
        return mode == Mode.ORBIT || mode == Mode.WORK;
    }

    private static int traceTicks;
    private static boolean traceAnnounced;
    private static int traceApplies;

    /** Temporary wiring trace: logs the first frames the mixin actually applies. */
    public static void traceApplied(Pose pose) {
        if (traceApplies < 3) {
            traceApplies++;
            ua.rp.chat.client.EclipseClientMod.LOGGER.info(
                    "[CARVER-CAM] mixin applied " + pose.position());
        }
    }

    public static void beginDesign(BlockPos focusBlock) {
        Minecraft minecraft = Minecraft.getInstance();
        ua.rp.chat.client.EclipseClientMod.LOGGER.info(
                "[CARVER-CAM] beginDesign focus=" + focusBlock.toShortString());
        traceAnnounced = false;
        mode = Mode.FLY;
        focus = focusBlock;
        flyTick = 0;
        dragVelYaw = 0.0;
        dragVelPitch = 0.0;
        if (minecraft.gameRenderer != null && minecraft.gameRenderer.getMainCamera() != null) {
            flyFrom = minecraft.gameRenderer.getMainCamera().position();
        } else if (minecraft.player != null) {
            flyFrom = minecraft.player.getEyePosition();
        } else {
            flyFrom = Vec3.ZERO;
        }
        posePosition = flyFrom;
        prevPosePosition = flyFrom;
        lastTickNanos = System.nanoTime();
        Vec3 center = center();
        double viewerX = minecraft.player == null ? center.x + 1.0 : minecraft.player.getX();
        double viewerZ = minecraft.player == null ? center.z + 1.0 : minecraft.player.getZ();
        orbitYaw = CarverCameraMath.entryYaw(viewerX, viewerZ, center.x, center.z)
                + CarverCameraMath.ENTRY_CORNER_OFFSET;
        orbitPitch = CarverCameraMath.ENTRY_PITCH;
        orbitDist = CarverCameraMath.ENTRY_DIST;
        if (minecraft.player != null) {
            poseYaw = minecraft.player.getYRot();
            posePitch = minecraft.player.getXRot();
            prevPoseYaw = poseYaw;
            prevPosePitch = posePitch;
        }
    }

    private static int workTick;
    private static float workPitchFrom;
    private static double workDistFrom;

    public static void beginWork(BlockPos focusBlock) {
        if (mode == Mode.IDLE) {
            beginDesign(focusBlock);
        }
        focus = focusBlock;
        // Aligned work framing: ease the orbit parameters (not the position) from
        // wherever the design orbit sits to the wider, lower work framing, keeping
        // the current orbit side so the view never whips around or overshoots into
        // the ground. Afterwards the orbit stays live: drag and wheel keep working.
        workPitchFrom = (float) orbitPitch;
        workDistFrom = orbitDist;
        workTick = 0;
        mode = Mode.WORK;
        dragVelYaw = 0.0;
        dragVelPitch = 0.0;
    }

    public static void end() {
        mode = Mode.IDLE;
        focus = null;
        flyTick = 0;
        shakeTrauma = 0.0;
    }

    /** Impact kick; decays every tick and offsets the pose by the square. */
    public static void addShake(double amount) {
        shakeTrauma = Math.min(1.0, shakeTrauma + amount);
    }

    /** Right-drag orbit input in mouse pixels; positive dx swings the camera right. */
    public static void orbitDrag(double dx, double dy) {
        if (mode != Mode.ORBIT && mode != Mode.WORK) return;
        orbitYaw -= dx * CarverCameraMath.DRAG_SENSITIVITY;
        orbitPitch = CarverCameraMath.clampPitch(
                (float) (orbitPitch + dy * CarverCameraMath.DRAG_SENSITIVITY));
        dragVelYaw = -dx * CarverCameraMath.DRAG_SENSITIVITY;
        dragVelPitch = dy * CarverCameraMath.DRAG_SENSITIVITY;
    }

    /** Scroll input in notches; positive steps zoom out. */
    public static void zoom(double steps) {
        if (mode != Mode.ORBIT && mode != Mode.WORK) return;
        orbitDist = CarverCameraMath.clampDist(orbitDist + steps * CarverCameraMath.ZOOM_STEP);
    }

    /**
     * Camera override consumed by the camera mixin; null when the rig is parked.
     * Interpolated between tick snapshots over our own tick clock, mirroring the
     * hologram lift: the fly-to and the orbit glide per frame, the logic stays
     * tick-stepped underneath.
     */
    public static Pose pose(Player player) {
        if (mode == Mode.IDLE || focus == null || player == null) return null;
        if (shakeTrauma > 0.001) {
            double kick = shakeTrauma * shakeTrauma * 0.12;
            Vec3 shaken = posePosition.add(
                    (Math.random() - 0.5) * 2.0 * kick,
                    (Math.random() - 0.5) * 2.0 * kick,
                    (Math.random() - 0.5) * 2.0 * kick);
            shakeTrauma *= 0.88;
            return new Pose(shaken, poseYaw, posePitch);
        }
        double partial = ua.rp.chat.carver.CarverHologramMotion.renderPartial(lastTickNanos);
        Vec3 position = new Vec3(
                ua.rp.chat.carver.CarverHologramMotion.lerpTick(
                        prevPosePosition.x, posePosition.x, partial),
                ua.rp.chat.carver.CarverHologramMotion.lerpTick(
                        prevPosePosition.y, posePosition.y, partial),
                ua.rp.chat.carver.CarverHologramMotion.lerpTick(
                        prevPosePosition.z, posePosition.z, partial));
        float yaw = CarverCameraMath.lerpAngle(prevPoseYaw, poseYaw, (float) partial);
        float pitch = prevPosePitch + (posePitch - prevPosePitch) * (float) partial;
        return new Pose(position, yaw, pitch);
    }

    public static void tick(Minecraft minecraft) {
        if (mode == Mode.IDLE || focus == null) return;
        if (!traceAnnounced) {
            traceAnnounced = true;
            ua.rp.chat.client.EclipseClientMod.LOGGER.info("[CARVER-CAM] tick alive, mode=" + mode);
        }
        if (minecraft.player == null || minecraft.level == null) return;
        prevPosePosition = posePosition;
        prevPoseYaw = poseYaw;
        prevPosePitch = posePitch;
        lastTickNanos = System.nanoTime();
        traceTicks++;
        if (traceTicks % 40 == 1) {
            ua.rp.chat.client.EclipseClientMod.LOGGER.info("[CARVER-CAM] mode=" + mode
                    + " pose=" + posePosition + " yaw=" + poseYaw + " pitch=" + posePitch);
        }
        if (mode == Mode.FLY) {
            flyTick++;
            double t = Math.min(1.0, flyTick / (double) CarverCameraMath.FLY_TICKS);
            double eased = CarverCameraMath.easeInOutBack(t);
            Vec3 anchor = orbitAnchor();
            posePosition = flyFrom.lerp(anchor, eased);
            if (t >= 1.0) {
                mode = Mode.ORBIT;
                posePosition = anchor;
            }
        } else if (mode == Mode.WORK) {
            workTick++;
            double t = Math.min(1.0, workTick / (double) CarverCameraMath.WORK_FLY_TICKS);
            double[] framing = CarverCameraMath.workFraming(workPitchFrom, workDistFrom, t);
            orbitPitch = framing[0];
            orbitDist = framing[1];
            if (Math.abs(dragVelYaw) > 0.01 || Math.abs(dragVelPitch) > 0.01) {
                orbitYaw += dragVelYaw;
                orbitPitch = CarverCameraMath.clampPitch((float) (orbitPitch + dragVelPitch));
                dragVelYaw *= 0.82;
                dragVelPitch *= 0.82;
            }
            posePosition = workAnchor();
        } else {
            if (Math.abs(dragVelYaw) > 0.01 || Math.abs(dragVelPitch) > 0.01) {
                orbitYaw += dragVelYaw;
                orbitPitch = CarverCameraMath.clampPitch((float) (orbitPitch + dragVelPitch));
                dragVelYaw *= 0.82;
                dragVelPitch *= 0.82;
            }
            posePosition = orbitAnchor();
        }
        posePosition = resolveCollision(minecraft, posePosition);
        Vec3 target = mode == Mode.WORK ? socketCenter() : center();
        double[] look = CarverCameraMath.lookAt(posePosition.x, posePosition.y, posePosition.z,
                target.x, target.y, target.z);
        poseYaw = (float) look[0];
        posePitch = (float) look[1];
    }

    /**
     * Framing target of the close-up: the lifted hologram anchor (socket center plus
     * visual lift, hover bob and the sideways occlusion nudge), never the bare socket.
     * Painting, chalk and picking resolve against the same anchor by construction.
     */
    private static Vec3 center() {
        return CarverHologram.anchor();
    }

    private static Vec3 orbitAnchor() {
        double[] offset = CarverCameraMath.orbitOffset(orbitYaw, orbitPitch, orbitDist);
        Vec3 center = center();
        return new Vec3(center.x + offset[0], center.y + offset[1], center.z + offset[2]);
    }

    /** Bare socket center: the work framing target once the hologram has landed. */
    private static Vec3 socketCenter() {
        return new Vec3(focus.getX() + 0.5, focus.getY() + 0.5, focus.getZ() + 0.5);
    }

    /** Work framing anchor: socket center plus the wider, lower work orbit offset. */
    private static Vec3 workAnchor() {
        double[] offset = CarverCameraMath.orbitOffset(
                orbitYaw, CarverCameraMath.WORK_PITCH, CarverCameraMath.WORK_DIST);
        Vec3 socket = socketCenter();
        return new Vec3(socket.x + offset[0], socket.y + offset[1], socket.z + offset[2]);
    }

    /**
     * Keeps the camera out of walls: raycasts from the socket center to the desired
     * pose and pulls the camera in front of the first obstruction. Deliberately the
     * socket, never the hologram anchor: the anchor collapses to the world origin
     * once the copy lands, and a ray from the origin yanks the camera underground.
     * Hits against the focused block itself are stepped over, otherwise the ray
     * starting inside the workpiece would pin the camera to its surface forever.
     */
    static Vec3 resolveCollision(Minecraft minecraft, Vec3 desired) {
        if (minecraft.level == null || minecraft.player == null || focus == null) {
            return desired;
        }
        Vec3 center = socketCenter();
        if (desired.distanceToSqr(center) < 0.64) return desired;
        Vec3 direction = desired.subtract(center).normalize();
        Vec3 from = center;
        for (int step = 0; step < 4; step++) {
            BlockHitResult hit = minecraft.level.clip(new ClipContext(from, desired,
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, minecraft.player));
            if (hit.getType() == HitResult.Type.MISS) return desired;
            if (!hit.getBlockPos().equals(focus)) {
                Vec3 pulled = hit.getLocation().subtract(direction.scale(0.25));
                if (pulled.distanceToSqr(center) < 0.64) {
                    return center.add(new Vec3(0.0, 0.8, 0.0));
                }
                return pulled;
            }
            from = hit.getLocation().add(direction.scale(0.02));
            if (from.distanceToSqr(center) >= desired.distanceToSqr(center)) return desired;
        }
        return desired;
    }

    public record Pose(Vec3 position, float yaw, float pitch) {
    }
}
