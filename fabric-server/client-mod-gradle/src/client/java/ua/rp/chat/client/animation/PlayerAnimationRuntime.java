package ua.rp.chat.client.animation;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import ua.rp.chat.animation.AnimationLibrary;
import ua.rp.chat.animation.LocomotionController;
import ua.rp.chat.client.AcquaintanceClientState;
import ua.rp.chat.client.carver.CarverClientState;
import ua.rp.chat.client.modeleditor.ModelEditorClient;

import java.io.IOException;
import java.util.Map;
import java.util.HashMap;
import java.util.WeakHashMap;

/** Motion comes from replicated entities, so observers play the same authored gait. */
public final class PlayerAnimationRuntime {
    public static final String RESOURCE = "/assets/eclipseclient/animations/rp_character_v3.animation.json";
    private static final Map<Player, Playback> CONTROLLERS = new WeakHashMap<>();
    private static final AnimationLibrary LIBRARY = load();
    private static Object world;

    private static final class Playback {
        final PlayerAnimationSampler sampler = new PlayerAnimationSampler(LIBRARY);
        final Map<SurfaceCell, Double> surfaces = new HashMap<>();
        int surfaceTick = Integer.MIN_VALUE;
        org.joml.Vector3f cameraNeck;
        int cameraFrameTick = Integer.MIN_VALUE;

        double ground(Player player, double x, double z, double y) {
            if (surfaceTick != player.tickCount) {
                surfaceTick = player.tickCount;
                surfaces.clear();
            }
            // Repeated model/armor captures and swing predictions share short collision queries.
            var cell = new SurfaceCell((long)Math.floor(x*8), (int)Math.floor(y*8), (long)Math.floor(z*8));
            return surfaces.computeIfAbsent(cell, ignored -> {
                var hit = player.level().clip(new ClipContext(new Vec3(x,y+.6,z),new Vec3(x,y-.9,z),
                        ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,player));
                return hit.getType()==HitResult.Type.BLOCK ? hit.getLocation().y : Double.NaN;
            });
        }
    }
    private record SurfaceCell(long x, int y, long z) {}

    private PlayerAnimationRuntime() {}

    public static AnimationLibrary library() { return LIBRARY; }

    /** Camera.update runs before world extraction: prepare this frame before moving the eyes. */
    public static org.joml.Vector3f captureFirstPersonNeck(Player player, float partialTick) {
        Minecraft client = Minecraft.getInstance();
        if (client == null || player == null || player != client.player || client.level != player.level()) return null;
        // This is extraction only, never a render or an eye query. The native avatar extractor
        // supplies all action/support flags, and its existing mixin captures the authored frame.
        client.getEntityRenderDispatcher().extractEntity(player, partialTick);
        return firstPersonNeck(player);
    }

    /** Read the last extracted pose; eye queries must never advance locomotion or foot contacts. */
    private static org.joml.Vector3f firstPersonNeck(Player player) {
        if (player == null || world != player.level() || !player.onGround()
                || !player.isAlive() || player.isSpectator() || player.isSleeping()
                || player.isInWater() || player.isFallFlying() || player.isVisuallySwimming()
                || player.isPassenger() || player.onClimbable()) return null;
        Playback playback = CONTROLLERS.get(player);
        if (playback == null || playback.cameraNeck == null
                || playback.cameraFrameTick < player.tickCount - 1) return null;
        return new org.joml.Vector3f(playback.cameraNeck);
    }

    public static void clear() {
        CONTROLLERS.clear();
        world = null;
    }

    private static AnimationLibrary load() {
        try (var stream = PlayerAnimationRuntime.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) throw new IllegalStateException("Missing player animations: " + RESOURCE);
            return AnimationLibrary.load(stream);
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot load bundled player animations", failure);
        }
    }

    public static void capture(Player player, AvatarRenderState state, float partialTick) {
        if (!(state instanceof PlayerAnimationRenderState animated)) return;
        if (world != player.level()) {
            clear();
            world = player.level();
        }
        boolean preview = ModelEditorClient.isPreviewPlayer(player);
        // The detached editor avatar is positioned once and never receives physics ticks.
        // Its distance from an uninitialised xOld/zOld is not a movement or a teleport.
        double dx = preview ? 0 : player.getX() - player.xOld;
        double dz = preview ? 0 : player.getZ() - player.zOld;
        double speed = Math.hypot(dx, dz) * 20.0;
        float facing = Mth.rotLerp(partialTick, player.yRotO, player.getYRot());
        double yaw = Math.toRadians(facing);
        double forward = speed > .01 ? (-dx * Math.sin(yaw) + dz * Math.cos(yaw)) * 20 / speed : 0;
        double left = speed > .01 ? (dx * Math.cos(yaw) + dz * Math.sin(yaw)) * 20 / speed : 0;
        boolean work = preview ? ModelEditorClient.previewAnimation().equals("carve")
                : player == Minecraft.getInstance().player
                    ? CarverClientState.working() || CarverClientState.finishing()
                    : CarverClientState.observedWork(player.getUUID()) != null;
        boolean supported = (player.onGround() || preview) && speed < 24
                && player.isAlive() && !player.isSleeping() && !player.isSpectator()
                && !player.isInWater() && !state.isFallFlying && !state.isVisuallySwimming
                && !state.isPassenger && !state.isAutoSpinAttack && !player.onClimbable() && !work
                && AcquaintanceClientState.poseFor(player) == AcquaintanceClientState.RoleplayPose.NONE;
        // Detached editor avatars have no physics; their existing preview clock supplies movement.
        if (preview && ModelEditorClient.previewAnimation().equals("walk")) {
            speed = 4.3; forward = 1; left = 0;
        }
        var playback = CONTROLLERS.computeIfAbsent(player, ignored -> new Playback());
        var intent = LocomotionController.MoveIntent.UNKNOWN;
        if (!preview && player == Minecraft.getInstance().player) {
            var keys = Minecraft.getInstance().player.input.keyPresses;
            intent = keys.forward()!=keys.backward() || keys.left()!=keys.right()
                    ? LocomotionController.MoveIntent.MOVING : LocomotionController.MoveIntent.STOPPED;
        }
        var motion = new LocomotionController.Motion(speed,forward,left,player.isSprinting(),
                state.isCrouching,supported,facing,intent);
        double seconds=(player.tickCount+partialTick)/20.0;
        // Preview walking is intentionally in place; world-distance contacts require actual displacement.
        var frame = playback.sampler.sample(seconds,motion,state.x,state.y,state.z,
                Math.max(.01,state.scale),preview,(gx,gz,gy)->playback.ground(player,gx,gz,gy));
        animated.eclipse$setAnimationFrame(frame);
        playback.cameraNeck = frame.active() && !preview
                ? new org.joml.Vector3f(animated.eclipse$animationSkeleton(state.isCrouching).neck) : null;
        playback.cameraFrameTick = player.tickCount;
        if (frame.active()) {
            float headYaw = state.bodyRot + state.yRot;
            state.bodyRot = facing;
            state.yRot = Mth.wrapDegrees(headYaw - facing);
        }
    }
}
