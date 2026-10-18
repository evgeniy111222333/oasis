package ua.rp.chat;

import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import ua.rp.chat.animation.AnimationPose;
import ua.rp.chat.animation.LocomotionController;
import ua.rp.chat.client.animation.PlayerAnimationRenderState;
import ua.rp.chat.client.animation.PlayerAnimationRuntime;
import ua.rp.chat.client.animation.PlayerTorsoSkeleton;

import java.util.List;
import java.util.ArrayList;
import java.util.UUID;

/** Real renderer extraction and native skin matrices, with a 20 Hz entity and 80 Hz renders. */
public final class GroundedLocomotionClientGameTest implements FabricClientGameTest {
    private static final float[] PARTIALS = {0, .25f, .5f, .75f};
    private static final double RENDER_SECONDS = 1.0 / 80;
    private static final double MAX_PLANTED_SPEED = .03;
    private static final double WALK_SPEED = ua.rp.chat.locomotion.RoleplayPacing.WALK_STRIDE_METERS
            / ua.rp.chat.locomotion.RoleplayPacing.WALK_CYCLE_SECONDS;
    private static final double CROUCH_SPEED = ua.rp.chat.locomotion.RoleplayPacing.CROUCH_STRIDE_METERS
            / ua.rp.chat.locomotion.RoleplayPacing.CROUCH_CYCLE_SECONDS;
    private static final double SPRINT_SPEED = ua.rp.chat.locomotion.RoleplayPacing.SPRINT_STRIDE_METERS
            / ua.rp.chat.locomotion.RoleplayPacing.SPRINT_CYCLE_SECONDS;

    @Override public void runTest(ClientGameTestContext context) {
        context.waitTicks(3);
        context.waitFor(client -> com.cinemamod.mcef.MCEF.isInitialized(), 3600);
        try (var world = context.worldBuilder().create()) {
            world.getClientLevel().waitForChunksRender();
            context.waitFor(client -> client.player != null && client.player.onGround(), 300);
            context.runOnClient(client -> {
                CameraType previous = client.options.getCameraType();
                client.options.setCameraType(CameraType.THIRD_PERSON_BACK);
                try {
                    verifyNativeFootContacts(client);
                    verifyReplicatedRelease(client);
                    verifyLocalRelease(client);
                } finally {
                    PlayerAnimationRuntime.clear();
                    client.options.setCameraType(previous);
                }
            });
        }
    }

    /** Stance is observed from the native rendered sole, independently of the IK diagnostics. */
    private static void verifyNativeFootContacts(Minecraft client) {
        List<Gait> gaits = List.of(
                new Gait("walk_loop", WALK_SPEED, 1, 0, false, false),
                new Gait("walk_backward_loop", WALK_SPEED, -1, 0, false, false),
                new Gait("walk_strafe_left_loop", WALK_SPEED, 0, 1, false, false),
                new Gait("walk_strafe_right_loop", WALK_SPEED, 0, -1, false, false),
                new Gait("walk_diag_fl_loop", WALK_SPEED, Math.sqrt(.5), Math.sqrt(.5), false, false),
                new Gait("sprint_loop", SPRINT_SPEED, 1, 0, true, false),
                new Gait("sneak_loop", CROUCH_SPEED, 1, 0, false, true),
                new Gait("sneak_backward_loop", CROUCH_SPEED, -1, 0, false, true),
                new Gait("sneak_strafe_left_loop", CROUCH_SPEED, 0, 1, false, true));
        int totalContacts = 0;
        double maximumStancePercentile = 0;
        for (boolean slim : List.of(false, true)) {
            PlayerModel skin = bake(client, slim, false);
            PlayerModel armor = bake(client, slim, true);
            for (Gait gait : gaits) {
                PlayerAnimationRuntime.clear();
                RemotePlayer player = subject(client);
                player.setSprinting(gait.sprint);
                player.setShiftKeyDown(gait.crouch);
                player.setPose(gait.crouch ? Pose.CROUCHING : Pose.STANDING);
                Vec3[] previous = new Vec3[2];
                List<Double> floorContactSpeeds = new ArrayList<>();
                boolean loop = false;
                LocomotionController.Frame previousFrame = null;
                int cadenceSamples = 0;
                Vec3 direction = direction(gait.forward, gait.left, 37);
                for (int tick = 0; tick < 64; tick++) {
                    advance(player, direction.scale(gait.speed / 20));
                    for (float partial : PARTIALS) {
                        AvatarRenderState state = capture(client, player, partial);
                        require(frame(state).active(), "Grounded native capture must remain active: " + gait.clip);
                        loop |= frame(state).clip().equals(gait.clip);
                        if (tick >= 20 && previousFrame != null && previousFrame.clip().equals(gait.clip)
                                && frame(state).clip().equals(gait.clip)) {
                            double delta = frame(state).phase() - previousFrame.phase();
                            if (delta < 0) delta += 1;
                            double actual = delta / RENDER_SECONDS;
                            double expected = 1 / (gait.crouch
                                    ? ua.rp.chat.locomotion.RoleplayPacing.CROUCH_CYCLE_SECONDS
                                    : gait.sprint ? ua.rp.chat.locomotion.RoleplayPacing.SPRINT_CYCLE_SECONDS
                                        : ua.rp.chat.locomotion.RoleplayPacing.WALK_CYCLE_SECONDS);
                            require(Math.abs(actual - expected) < .002,
                                    "Native " + gait.clip + " must keep the RP reference cycle tempo at RP movement speed: "
                                            + actual + " vs " + expected + " cycles/s");
                            cadenceSamples++;
                        }
                        previousFrame = frame(state);
                        skin.setupAnim(state);
                        for (int side = 0; side < 2; side++) {
                            Vec3 sole = sole(skin, state, side);
                            // Ignore startup blending. Flat contacts identify stance. A swing can
                            // graze the floor at its boundary, so assert the interior distribution.
                            if (tick >= 20 && previous[side] != null
                                    && Math.abs(sole.y - player.getY()) < 1e-5
                                    && Math.abs(previous[side].y - player.getY()) < 1e-5) {
                                double speed = sole.distanceTo(previous[side]) / RENDER_SECONDS;
                                floorContactSpeeds.add(speed);
                            }
                            previous[side] = sole;
                        }
                        if (tick % 16 == 0 && partial == .5f) {
                            PlayerTorsoSkeleton cached = animation(state).eclipse$animationSkeleton(state.isCrouching);
                            armor.setupAnim(state);
                            require(cached == animation(state).eclipse$animationSkeleton(state.isCrouching),
                                    "Skin and armor must use the same evaluated grounded skeleton");
                            require(sameTransform(skin.leftLeg, armor.leftLeg)
                                            && sameTransform(skin.rightLeg, armor.rightLeg),
                                    "Native armor hips must follow the grounded skin: " + gait.clip);
                            finiteVertices(skin);
                            finiteVertices(armor);
                        }
                    }
                }
                require(loop, "Native extraction must reach " + gait.clip);
                require(cadenceSamples > 30, "Native authored-tempo check needs settled samples: " + gait.clip);
                require(floorContactSpeeds.size() > 30,
                        "Native gait must expose enough actual planted contacts: " + gait.clip
                                + " (slim=" + slim + ", contacts=" + floorContactSpeeds.size() + ")");
                floorContactSpeeds.sort(Double::compare);
                double percentile = floorContactSpeeds.get((int) ((floorContactSpeeds.size() - 1) * .9));
                require(percentile < MAX_PLANTED_SPEED,
                        "Native planted sole slides during " + gait.clip + " (slim=" + slim
                                + "): 90th percentile=" + percentile + " m/s");
                maximumStancePercentile = Math.max(maximumStancePercentile, percentile);
                totalContacts += floorContactSpeeds.size();
            }
        }
        System.out.println("[GROUNDED-LOCOMOTION-TEST] " + totalContacts
                + " native wide/slim floor contacts; worst 90th percentile world sole speed=" + maximumStancePercentile
                + " m/s; skin/armor skeleton and finite geometry verified");
    }

    private static void verifyReplicatedRelease(Minecraft client) {
        PlayerAnimationRuntime.clear();
        RemotePlayer player = subject(client);
        facing(player, 0);
        warmWalk(client, player);
        for (int drop = 1; drop <= 2; drop++) {
            double speed = 4.3 * Math.pow(.546, drop);
            advance(player, new Vec3(0, 0, speed / 20));
            for (float partial : PARTIALS) {
                LocomotionController.Frame captured = frame(capture(client, player, partial));
                require(captured.clip().equals(drop == 1 ? "walk_loop" : "walk_stop"),
                        "Replicated release must use distinct physics ticks, not repeated render derivatives: "
                                + drop + "/" + partial + " => " + captured.clip());
            }
        }
        require(player.getDeltaMovement().length() * 20 > 1,
                "Observed stop regression must exercise visible residual movement");
        verifyStopProgressAndRestart(client, player);
        System.out.println("[GROUNDED-LOCOMOTION-TEST] Remote friction release enters stop after two ticks while moving; stop progresses and restart interrupts it");
    }

    private static void verifyLocalRelease(Minecraft client) {
        var player = client.player;
        Input originalInput = player.input.keyPresses;
        Vec3 position = player.position(), velocity = player.getDeltaMovement();
        double xOld = player.xOld, yOld = player.yOld, zOld = player.zOld;
        double xo = player.xo, yo = player.yo, zo = player.zo;
        int ticks = player.tickCount;
        boolean grounded = player.onGround(), sprinting = player.isSprinting();
        float yaw = player.getYRot(), oldYaw = player.yRotO;
        float body = player.yBodyRot, oldBody = player.yBodyRotO;
        float head = player.yHeadRot, oldHead = player.yHeadRotO;
        try {
            PlayerAnimationRuntime.clear();
            facing(player, 0);
            player.setOnGround(true);
            player.setSprinting(false);
            player.input.keyPresses = new Input(true, false, false, false, false, false, false);
            warmWalk(client, player);
            player.input.keyPresses = Input.EMPTY;
            // No physics tick has happened: position and velocity still contain the full walking speed.
            LocomotionController.Frame released = frame(capture(client, player, .875f));
            require(released.clip().equals("walk_stop") && player.getDeltaMovement().length() * 20 > 4,
                    "Local input release must start walk_stop in the same tick, before friction reduces movement");
            verifyStopProgressAndRestart(client, player);
            System.out.println("[GROUNDED-LOCOMOTION-TEST] Local key release starts stop before the first friction tick; zero-speed stop remains live");
        } finally {
            player.input.keyPresses = originalInput;
            player.setPos(position);
            player.setDeltaMovement(velocity);
            player.xOld = xOld; player.yOld = yOld; player.zOld = zOld;
            player.xo = xo; player.yo = yo; player.zo = zo;
            player.tickCount = ticks;
            player.setOnGround(grounded);
            player.setSprinting(sprinting);
            player.setYRot(yaw); player.yRotO = oldYaw;
            player.yBodyRot = body; player.yBodyRotO = oldBody;
            player.yHeadRot = head; player.yHeadRotO = oldHead;
        }
    }

    private static void verifyStopProgressAndRestart(Minecraft client, AbstractClientPlayer player) {
        advance(player, Vec3.ZERO);
        LocomotionController.Frame first = frame(capture(client, player, 0));
        LocomotionController.Frame later = frame(capture(client, player, .75f));
        require(first.clip().equals("walk_stop") && later.clip().equals("walk_stop"),
                "The stop clip must survive zero displacement");
        require(later.phase() > first.phase() + .02 && poseDistance(first.pose(), later.pose()) > 1e-4,
                "A zero-speed stop must advance both real-time clip phase and rendered pose");
        if (player == client.player) {
            client.player.input.keyPresses = new Input(true, false, false, false, false, false, false);
        }
        advance(player, new Vec3(0, 0, 4.3 / 20));
        require(frame(capture(client, player, 0)).clip().equals("walk_start"),
                "Renewed movement must interrupt an unfinished stop immediately");
        if (player == client.player) client.player.input.keyPresses = Input.EMPTY;
        for (int tick = 0; tick < 30; tick++) {
            advance(player, Vec3.ZERO);
            capture(client, player, .75f);
        }
        require(frame(capture(client, player, .75f)).clip().equals("idle"),
                "A stationary stop must finish and settle into idle on its real-time clock");
    }

    private static void warmWalk(Minecraft client, AbstractClientPlayer player) {
        for (int tick = 0; tick < 24; tick++) {
            advance(player, new Vec3(0, 0, 4.3 / 20));
            for (float partial : PARTIALS) capture(client, player, partial);
        }
        require(frame(capture(client, player, .75f)).clip().equals("walk_loop"),
                "Release regression requires a settled walking loop");
    }

    private static RemotePlayer subject(Minecraft client) {
        RemotePlayer player = new RemotePlayer(client.level,
                new GameProfile(new UUID(0xEC, 0x6174), "GroundedAudit")) {
            @Override public PlayerSkin getSkin() { return client.player.getSkin(); }
        };
        player.setId(2_000_000_074);
        player.setPos(client.player.position());
        player.setOldPosAndRot();
        player.setOnGround(true);
        player.setNoGravity(true);
        player.tickCount = 200;
        facing(player, 37);
        return player;
    }

    private static void advance(Player player, Vec3 displacement) {
        player.xOld = player.xo = player.getX();
        player.yOld = player.yo = player.getY();
        player.zOld = player.zo = player.getZ();
        player.setPos(player.position().add(displacement));
        player.setDeltaMovement(displacement);
        player.tickCount++;
    }

    private static void facing(Player player, float yaw) {
        player.setYRot(yaw); player.yRotO = yaw;
        player.yBodyRot = player.yBodyRotO = yaw;
        player.yHeadRot = player.yHeadRotO = yaw;
    }

    private static Vec3 direction(double forward, double left, float yaw) {
        double angle = Math.toRadians(yaw);
        return new Vec3(-Math.sin(angle) * forward + Math.cos(angle) * left, 0,
                Math.cos(angle) * forward + Math.sin(angle) * left);
    }

    private static AvatarRenderState capture(Minecraft client, AbstractClientPlayer player, float partial) {
        var renderer = client.getEntityRenderDispatcher().getPlayerRenderer(player);
        AvatarRenderState state = renderer.createRenderState();
        renderer.extractRenderState(player, state, partial);
        return state;
    }

    /** Independently follow the actual native hierarchy, including both distal joints. */
    private static Vec3 sole(PlayerModel model, AvatarRenderState state, int side) {
        ModelPart leg = side == 0 ? model.leftLeg : model.rightLeg;
        ModelPart shin = leg.getChild("eclipse_shin");
        ModelPart ankle = shin.getChild("eclipse_ankle");
        PoseStack transform = new PoseStack();
        model.root().translateAndRotate(transform);
        leg.translateAndRotate(transform);
        shin.translateAndRotate(transform);
        ankle.translateAndRotate(transform);
        Vector3f sole = transform.last().pose().transformPosition(new Vector3f(0, 2f / 16, 0));
        double scale = .9375 * state.scale, yaw = Math.toRadians(state.bodyRot);
        return new Vec3(state.x + scale * (sole.x * Math.cos(yaw) + sole.z * Math.sin(yaw)),
                state.y + scale * (PlayerTorsoSkeleton.floorPixels(state.isCrouching) / 16 - sole.y),
                state.z + scale * (sole.x * Math.sin(yaw) - sole.z * Math.cos(yaw)));
    }

    private static PlayerModel bake(Minecraft client, boolean slim, boolean armor) {
        var layer = armor
                ? (slim ? ModelLayers.PLAYER_SLIM_ARMOR : ModelLayers.PLAYER_ARMOR).get(EquipmentSlot.FEET)
                : slim ? ModelLayers.PLAYER_SLIM : ModelLayers.PLAYER;
        return new PlayerModel(client.getEntityModels().bakeLayer(layer), slim);
    }

    private static boolean sameTransform(ModelPart a, ModelPart b) {
        return Math.abs(a.x - b.x) < 1e-5 && Math.abs(a.y - b.y) < 1e-5 && Math.abs(a.z - b.z) < 1e-5
                && Math.abs(a.xRot - b.xRot) < 1e-5 && Math.abs(a.yRot - b.yRot) < 1e-5
                && Math.abs(a.zRot - b.zRot) < 1e-5;
    }

    private static double poseDistance(AnimationPose a, AnimationPose b) {
        double total = 0;
        for (String bone : PlayerAnimationRuntime.library().boneNames()) {
            AnimationPose.Vector x = a.rotation(bone), y = b.rotation(bone);
            total += Math.abs(x.x() - y.x()) + Math.abs(x.y() - y.y()) + Math.abs(x.z() - y.z());
            x = a.position(bone); y = b.position(bone);
            total += Math.abs(x.x() - y.x()) + Math.abs(x.y() - y.y()) + Math.abs(x.z() - y.z());
        }
        return total;
    }

    private static PlayerAnimationRenderState animation(AvatarRenderState state) {
        require(state instanceof PlayerAnimationRenderState, "Native renderer must attach animation frame storage");
        return (PlayerAnimationRenderState) state;
    }
    private static LocomotionController.Frame frame(AvatarRenderState state) {
        LocomotionController.Frame value = animation(state).eclipse$animationFrame();
        require(value != null, "Native extraction must capture an animation frame");
        return value;
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }

    private static void finiteVertices(PlayerModel model) {
        FiniteVertices vertices = new FiniteVertices();
        model.renderToBuffer(new PoseStack(), vertices, 0xF000F0, 0);
        require(vertices.count > 100, "Grounded native skin and armor must emit their geometry");
    }
    private static final class FiniteVertices implements VertexConsumer {
        private int count;
        @Override public VertexConsumer addVertex(float x, float y, float z) {
            require(Float.isFinite(x) && Float.isFinite(y) && Float.isFinite(z), "Non-finite grounded skin/armor vertex");
            count++; return this;
        }
        @Override public VertexConsumer setNormal(float x, float y, float z) {
            require(Float.isFinite(x) && Float.isFinite(y) && Float.isFinite(z), "Non-finite grounded skin/armor normal");
            return this;
        }
        @Override public VertexConsumer setUv(float u, float v) {
            require(Float.isFinite(u) && Float.isFinite(v), "Non-finite grounded skin/armor UV"); return this;
        }
        @Override public VertexConsumer setColor(int r, int g, int b, int a) { return this; }
        @Override public VertexConsumer setColor(int color) { return this; }
        @Override public VertexConsumer setUv1(int u, int v) { return this; }
        @Override public VertexConsumer setUv2(int u, int v) { return this; }
        @Override public VertexConsumer setLineWidth(float width) { return this; }
    }
    private record Gait(String clip, double speed, double forward, double left, boolean sprint, boolean crouch) {}
}
