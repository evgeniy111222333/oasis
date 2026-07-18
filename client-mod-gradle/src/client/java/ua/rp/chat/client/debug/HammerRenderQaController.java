package ua.rp.chat.client.debug;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;
import ua.rp.chat.ArticulatedLimbLayout;
import ua.rp.chat.HeavyHammerAnimation;
import ua.rp.chat.HeavyHammerGait;
import ua.rp.chat.HeavyHammerProceduralMotion;
import ua.rp.chat.HeavyHammerRenderedGait;
import ua.rp.chat.HeavyHammerSpatialRules;
import ua.rp.chat.HeavyHammerGripSolver;
import ua.rp.chat.HeavyHammerRenderedRig;
import ua.rp.chat.client.EclipseClientMod;
import ua.rp.chat.client.heavyhammer.HeavyHammerClientState;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Снимает настоящий игровой рендер молота через основной framebuffer Minecraft.
 * Камера, PlayerRenderer, ItemInHandLayer, миксины тела и ресурсная модель остаются игровыми.
 */
public final class HammerRenderQaController {
    public static final String REQUEST_FILE = "capture.request.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final DateTimeFormatter SESSION_TIME = DateTimeFormatter
            .ofPattern("uuuuMMdd-HHmmss", Locale.ROOT).withZone(ZoneOffset.UTC);
    private static final int SETTLE_TICKS = 3;
    private static final HeavyHammerProceduralMotion.Target QA_TARGET =
            new HeavyHammerProceduralMotion.Target(0.0f, 24.0f, -12.0f,
                    HeavyHammerProceduralMotion.Surface.UP, 0.0f, 1.0f, 0.0f);
    private static final List<Scene> FULL_SCENES = List.of(
            new Scene("idle_front", "idle", -1.0f, 180.0f, 8.0f, 4.6f),
            new Scene("idle_grip_close", "idle", -1.0f, 225.0f, 7.0f, 3.9f),
            new Scene("idle_front_left", "idle", -1.0f, 225.0f, 8.0f, 4.6f),
            new Scene("idle_left", "idle", -1.0f, 270.0f, 8.0f, 4.6f),
            new Scene("idle_back", "idle", -1.0f, 0.0f, 8.0f, 4.6f),
            new Scene("idle_right", "idle", -1.0f, 90.0f, 8.0f, 4.6f),
            new Scene("anticipation_front", "anticipation", 3.4f, 180.0f, 10.0f, 4.8f),
            new Scene("lift_close_left", "lift", 6.2f, 250.0f, 9.0f, 4.1f),
            new Scene("backswing_left", "backswing", 9.5f, 270.0f, 10.0f, 4.9f),
            new Scene("backswing_back_left", "backswing", 9.5f, 45.0f, 12.0f, 4.9f),
            new Scene("back_scoop_left", "backswing", 15.9f, 270.0f, 13.0f, 5.0f),
            new Scene("overhead_front", "overhead", 18.1f, 180.0f, 14.0f, 5.0f),
            new Scene("overhead_left", "overhead", 18.1f, 270.0f, 14.0f, 5.0f),
            new Scene("acceleration_front_left", "acceleration", 20.6f, 230.0f, 11.0f, 4.6f),
            new Scene("preimpact_left", "preimpact", 23.5f, 270.0f, 9.0f, 4.3f),
            new Scene("impact_front", "impact", HeavyHammerAnimation.IMPACT_TICK, 180.0f, 10.0f, 4.9f),
            new Scene("impact_left", "impact", HeavyHammerAnimation.IMPACT_TICK, 270.0f, 10.0f, 4.9f),
            new Scene("impact_close_front_left", "impact", HeavyHammerAnimation.IMPACT_TICK, 225.0f, 8.0f, 4.0f),
            new Scene("postimpact_front_left", "postimpact", 27.7f, 225.0f, 9.0f, 4.4f),
            new Scene("follow_front_left", "follow", 30.7f, 225.0f, 10.0f, 4.9f),
            new Scene("recover_left", "recover", 36.1f, 270.0f, 9.0f, 4.5f),
            new Scene("settle_front", "settle", 40.3f, 180.0f, 8.0f, 4.7f)
    );
    private static final List<Scene> TEMPORAL_SCENES = List.of(
            new Scene("temporal_13_5", "temporal", 13.5f, 225.0f, 11.0f, 4.7f),
            new Scene("temporal_14_5", "temporal", 14.5f, 225.0f, 11.0f, 4.7f),
            new Scene("temporal_15_5", "temporal", 15.5f, 225.0f, 11.0f, 4.7f),
            new Scene("temporal_16_5", "temporal", 16.5f, 225.0f, 11.0f, 4.7f),
            new Scene("temporal_17_5", "temporal", 17.5f, 225.0f, 11.0f, 4.7f),
            new Scene("temporal_18_5", "temporal", 18.5f, 225.0f, 10.0f, 4.6f),
            new Scene("temporal_19_0", "temporal", 19.0f, 225.0f, 10.0f, 4.6f),
            new Scene("temporal_19_5", "temporal", 19.5f, 225.0f, 10.0f, 4.6f),
            new Scene("temporal_20_0", "temporal", 20.0f, 225.0f, 9.0f, 4.5f),
            new Scene("temporal_20_5", "temporal", 20.5f, 225.0f, 9.0f, 4.5f),
            new Scene("temporal_21_5", "temporal", 21.5f, 225.0f, 9.0f, 4.5f),
            new Scene("temporal_22_5", "temporal", 22.5f, 225.0f, 9.0f, 4.6f),
            new Scene("temporal_23_5", "temporal", 23.5f, 225.0f, 10.0f, 4.7f),
            new Scene("temporal_25_5", "temporal", 25.5f, 225.0f, 10.0f, 4.8f),
            new Scene("temporal_27_5", "temporal", 27.5f, 225.0f, 10.0f, 4.8f)
    );
    private static final List<Scene> EQUIP_GAIT_SCENES = List.of(
            new Scene("draw_stowed_back", "draw", 0.0f, 0.0f, 8.0f, 4.5f),
            new Scene("draw_reach_back_right", "draw", 4.5f, 45.0f, 8.0f, 4.5f),
            new Scene("draw_unlatch_right", "draw", 8.0f, 90.0f, 8.0f, 4.5f),
            new Scene("draw_clear_right", "draw", 13.5f, 90.0f, 9.0f, 4.7f),
            new Scene("draw_offhand_catch_front", "draw", 20.5f, 180.0f, 8.0f, 4.6f),
            new Scene("draw_weight_accept_left", "draw", 24.5f, 270.0f, 8.0f, 4.6f),
            new Scene("draw_settle_front", "draw", 28.5f, 180.0f, 8.0f, 4.6f),
            new Scene("walk_right_support_front", "walk", -1.0f, 180.0f, 8.0f, 4.8f,
                    0.0f, 0.28f, 0.10f),
            new Scene("walk_right_swing_front", "walk", -1.0f, 180.0f, 8.0f, 4.8f,
                    (float) (Math.PI * 0.5 / 0.6662), 0.28f, 0.10f),
            new Scene("walk_transfer_left", "walk", -1.0f, 270.0f, 8.0f, 4.8f,
                    (float) (Math.PI / 0.6662), 0.28f, 0.10f),
            new Scene("walk_left_swing_front", "walk", -1.0f, 180.0f, 8.0f, 4.8f,
                    (float) (Math.PI * 1.5 / 0.6662), 0.28f, 0.10f),
            new Scene("run_narrow_front", "run", -1.0f, 180.0f, 9.0f, 4.9f,
                    (float) (Math.PI * 0.5 / 0.6662), 0.45f, 0.28f)
    );

    private static KeyMapping captureKey;
    private static Session session;
    private static Scene activeScene;
    private static int activeIndex = -1;
    private static int settleTicks;
    private static volatile boolean captureInFlight;
    private static int requestPollTicks;

    private HammerRenderQaController() {
    }

    public static void register() {
        captureKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.eclipseclient.hammer_render_qa",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_F8,
                KeyMapping.Category.GAMEPLAY));
    }

    public static void clientTick(Minecraft client) {
        if (client == null) return;
        ensureRoot(client);

        if (session == null) {
            boolean keyRequested = false;
            while (captureKey != null && captureKey.consumeClick()) keyRequested = true;
            String requestedMode = keyRequested ? "full" : pollRequest(client);
            if (requestedMode != null) start(client, requestedMode);
            return;
        }

        if (client.player == null || client.level == null) {
            abort(client, "Игровой мир был закрыт во время захвата.");
            return;
        }
        if (!HeavyHammerClientState.isHolding(client.player.getMainHandItem())) {
            abort(client, "Тяжёлый молот больше не находится в основной руке.");
            return;
        }
        if (captureInFlight) return;

        if (activeScene == null) {
            if (session.nextScene >= session.scenes.size()) {
                finish(client);
                return;
            }
            activeIndex = session.nextScene;
            activeScene = session.scenes.get(session.nextScene++);
            settleTicks = SETTLE_TICKS;
            writeSessionManifest(session, "capturing", null);
            return;
        }
        if (settleTicks-- > 0) return;
        capture(client, session, activeScene, activeIndex);
    }

    public static CameraRig cameraRig() {
        Scene scene = activeScene;
        return session == null || scene == null ? null
                : new CameraRig(scene.yawOffset, scene.pitch, scene.distance, 1.25f);
    }

    public static HeavyHammerAnimation.Sample poseOverride(Player player, float ageTicks) {
        Scene scene = activeScene;
        if (session == null || scene == null || player == null) return null;
        return poseFor(scene);
    }

    public static HeavyHammerProceduralMotion.Frame frameOverride(Player player, float ageTicks) {
        Scene scene = activeScene;
        if (session == null || scene == null || player == null) return null;
        return frameFor(scene);
    }

    public static GaitRig gaitRig() {
        Scene scene = activeScene;
        return session == null || scene == null || !Float.isFinite(scene.walkPosition)
                ? null : new GaitRig(scene.walkPosition, scene.walkSpeed, scene.linearSpeed);
    }

    private static void start(Minecraft client, String mode) {
        if (client.player == null || client.level == null || client.screen != null) {
            failRequest(client, "Для захвата нужно находиться в игровом мире без открытого меню.");
            return;
        }
        if (!HeavyHammerClientState.isHolding(client.player.getMainHandItem())) {
            failRequest(client, "Возьмите тяжёлый молот в основную руку и повторите захват.");
            return;
        }

        List<Scene> scenes;
        if ("idle".equalsIgnoreCase(mode)) {
            scenes = FULL_SCENES.stream().filter(scene -> scene.elapsedTicks < 0.0f).toList();
        } else if ("temporal".equalsIgnoreCase(mode)) {
            scenes = new ArrayList<>(TEMPORAL_SCENES);
        } else if ("motion".equalsIgnoreCase(mode)) {
            scenes = new ArrayList<>(EQUIP_GAIT_SCENES);
        } else {
            scenes = new ArrayList<>(FULL_SCENES);
            scenes.addAll(TEMPORAL_SCENES);
            scenes.addAll(EQUIP_GAIT_SCENES);
        }
        String id = SESSION_TIME.format(Instant.now()) + "-" + sanitize(mode);
        Path directory = root(client).resolve(id);
        try {
            Files.createDirectories(directory);
        } catch (IOException error) {
            failRequest(client, "Не удалось создать каталог QA: " + error.getMessage());
            return;
        }

        session = new Session(id, directory, scenes, client.options.getCameraType(), client.options.hideGui);
        activeScene = null;
        activeIndex = -1;
        captureInFlight = false;
        client.options.setCameraType(CameraType.THIRD_PERSON_BACK);
        client.options.hideGui = true;
        writeSessionManifest(session, "capturing", null);
        writeLatestPointer(client, session, false, null);
        client.player.sendSystemMessage(Component.literal(
                "[Hammer QA] Начат настоящий игровой рендер: " + scenes.size() + " ракурсов."));
        EclipseClientMod.LOGGER.info("[HAMMER-QA] Capture started: id={}, mode={}, scenes={}, directory={}",
                id, mode, scenes.size(), directory);
    }

    private static void capture(Minecraft client, Session current, Scene scene, int index) {
        captureInFlight = true;
        String baseName = String.format(Locale.ROOT, "%02d-%s", index, scene.name);
        Path imagePath = current.directory.resolve(baseName + ".png");
        Path reportPath = current.directory.resolve(baseName + ".json");
        writeSceneReport(client, current, scene, index, reportPath);

        Screenshot.takeScreenshot(client.getMainRenderTarget(), image -> {
            String failure = null;
            try (image) {
                image.writeToFile(imagePath);
                Path livePose = client.gameDirectory.toPath().resolve("eclipse-debug").resolve("live-pose.json");
                if (Files.isRegularFile(livePose)) {
                    Files.copy(livePose, current.directory.resolve(baseName + "-live-pose.json"),
                            StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException | RuntimeException error) {
                failure = error.getMessage();
            }
            String captureFailure = failure;
            client.execute(() -> {
                if (captureFailure != null) {
                    abort(client, "Не удалось сохранить " + imagePath.getFileName() + ": " + captureFailure);
                    return;
                }
                current.captured++;
                activeScene = null;
                activeIndex = -1;
                captureInFlight = false;
                EclipseClientMod.LOGGER.info("[HAMMER-QA] Captured {}/{}: {}",
                        current.captured, current.scenes.size(), imagePath);
            });
        });
    }

    private static void finish(Minecraft client) {
        Session completed = session;
        restore(client, completed);
        writeSessionManifest(completed, "complete", null);
        writeLatestPointer(client, completed, true, null);
        session = null;
        activeScene = null;
        captureInFlight = false;
        if (client.player != null) {
            client.player.sendSystemMessage(Component.literal(
                    "[Hammer QA] Готово: " + completed.directory.toAbsolutePath()));
        }
        EclipseClientMod.LOGGER.info("[HAMMER-QA] Capture complete: id={}, files={}",
                completed.id, completed.captured);
    }

    private static void abort(Minecraft client, String reason) {
        Session aborted = session;
        if (aborted != null) {
            restore(client, aborted);
            writeSessionManifest(aborted, "failed", reason);
            writeLatestPointer(client, aborted, false, reason);
        } else {
            writeLastError(client, reason);
        }
        session = null;
        activeScene = null;
        activeIndex = -1;
        captureInFlight = false;
        if (client.player != null) client.player.sendSystemMessage(Component.literal("[Hammer QA] " + reason));
        EclipseClientMod.LOGGER.error("[HAMMER-QA] Capture aborted: {}", reason);
    }

    private static void restore(Minecraft client, Session captured) {
        if (captured == null) return;
        client.options.setCameraType(captured.previousCamera);
        client.options.hideGui = captured.previousHideGui;
    }

    private static String pollRequest(Minecraft client) {
        if (++requestPollTicks < 10) return null;
        requestPollTicks = 0;
        Path request = root(client).resolve(REQUEST_FILE);
        if (!Files.isRegularFile(request)) return null;
        try {
            String content = Files.readString(request, StandardCharsets.UTF_8).trim();
            Files.deleteIfExists(request);
            if (content.isEmpty()) return "full";
            JsonObject json = JsonParser.parseString(content).getAsJsonObject();
            return json.has("mode") ? json.get("mode").getAsString() : "full";
        } catch (IOException | RuntimeException error) {
            try {
                Files.deleteIfExists(request);
            } catch (IOException ignored) {
            }
            failRequest(client, "Повреждён файл запроса QA: " + error.getMessage());
            return null;
        }
    }

    private static HeavyHammerAnimation.Sample poseFor(Scene scene) {
        if ("draw".equals(scene.phase)) {
            return HeavyHammerAnimation.draw(scene.elapsedTicks / 30.0f, scene.elapsedTicks, 0.0f);
        }
        if (scene.elapsedTicks < 0.0f) {
            return HeavyHammerAnimation.idle(0.0f, 0.0f);
        }
        return HeavyHammerAnimation.strike(scene.elapsedTicks, QA_TARGET);
    }

    private static HeavyHammerProceduralMotion.Frame frameFor(Scene scene) {
        if ("draw".equals(scene.phase)) {
            float progress = Math.max(0.0f, Math.min(1.0f, scene.elapsedTicks / 30.0f));
            return HeavyHammerProceduralMotion.draw(progress, scene.elapsedTicks, 0.0f);
        }
        if (scene.elapsedTicks < 0.0f) {
            return HeavyHammerProceduralMotion.idle(0.0f, 0.0f);
        }
        return HeavyHammerProceduralMotion.strike(
                scene.elapsedTicks / HeavyHammerAnimation.DURATION_TICKS, QA_TARGET);
    }

    private static void writeSceneReport(Minecraft client, Session current, Scene scene,
                                         int index, Path output) {
        HeavyHammerProceduralMotion.Frame frame = frameFor(scene);
        HeavyHammerAnimation.Sample pose = poseFor(scene);
        HeavyHammerProceduralMotion.MotionMetrics metrics = scene.elapsedTicks < 0.0f
                || "draw".equals(scene.phase)
                ? new HeavyHammerProceduralMotion.MotionMetrics(0.0f, 0.0f, 0.0f, 0.0f,
                HeavyHammerProceduralMotion.HEAD_DISTANCE - frame.gripDistance())
                : HeavyHammerProceduralMotion.metrics(scene.elapsedTicks, QA_TARGET);

        JsonObject json = new JsonObject();
        json.addProperty("schema", 2);
        json.addProperty("session", current.id);
        json.addProperty("index", index);
        json.addProperty("scene", scene.name);
        json.addProperty("phase", scene.phase);
        json.addProperty("elapsedTicks", scene.elapsedTicks);
        json.addProperty("progress", frame.progress());
        json.addProperty("cameraYawOffset", scene.yawOffset);
        json.addProperty("cameraPitch", scene.pitch);
        json.addProperty("cameraDistance", scene.distance);
        json.addProperty("framebufferWidth", client.getMainRenderTarget().width);
        json.addProperty("framebufferHeight", client.getMainRenderTarget().height);
        json.addProperty("diagnosticBuild", EclipseClientMod.DIAGNOSTIC_BUILD);
        json.add("mainGrip", vector(frame.mainGrip()));
        json.add("offhandGrip", vector(frame.offhandGrip()));
        json.add("headCenter", vector(frame.headCenter()));
        json.add("shaftAxis", vector(frame.shaft()));
        json.add("headAxis", vector(frame.headAxis()));
        json.add("depthAxis", vector(frame.depthAxis()));
        json.addProperty("gripDistance", frame.gripDistance());
        json.addProperty("unsupportedLever", metrics.unsupportedLever());
        json.addProperty("headSpeed", metrics.headSpeed());
        json.addProperty("headAcceleration", metrics.headAcceleration());
        json.addProperty("headAngularSpeed", metrics.headAngularSpeed());
        json.addProperty("gripSlideRate", metrics.gripSlideRate());
        json.addProperty("headRoll", frame.headRoll());
        json.addProperty("headIntersectsPlayerHead", HeavyHammerSpatialRules.intersectsPlayerHead(frame));
        json.addProperty("headIntersectsPlayerTorso", HeavyHammerSpatialRules.intersectsPlayerTorso(frame));
        json.addProperty("handleIntersectsPlayerHead", HeavyHammerSpatialRules.handleIntersectsPlayerHead(frame));
        json.addProperty("handleIntersectsPlayerTorso", HeavyHammerSpatialRules.handleIntersectsPlayerTorso(frame));
        json.addProperty("mainGripIntersectsPlayer", HeavyHammerSpatialRules.mainGripIntersectsPlayer(frame));
        json.addProperty("offhandGripIntersectsPlayer", HeavyHammerSpatialRules.offhandGripIntersectsPlayer(frame));
        json.addProperty("headGroundClearance", HeavyHammerSpatialRules.headGroundClearance(frame));
        json.addProperty("frontProjectedHeadLength", HeavyHammerSpatialRules.projectedLongAxisLength(frame,
                new HeavyHammerProceduralMotion.Vec3(0.0f, 0.0f, 1.0f)));
        json.addProperty("cameraProjectedHeadLength", HeavyHammerSpatialRules.projectedLongAxisLength(frame,
                cameraForward(scene)));
        json.addProperty("mainClampDistance", pose.mainClampDistance());
        json.addProperty("offhandClampDistance", pose.gripClampDistance());
        json.addProperty("poseWeight", pose.poseWeight());
        json.addProperty("offhandWeight", pose.offhandWeight());
        json.addProperty("gaitWeight", pose.gaitWeight());
        json.addProperty("rightElbowAngle", pose.rightLower());
        json.addProperty("leftElbowAngle", pose.leftLower());
        json.addProperty("requestedRightWristTwist", pose.rightWristTwist());
        json.addProperty("requestedLeftWristTwist", pose.leftWristTwist());
        json.addProperty("renderedRightForearmY",
                ArticulatedLimbLayout.forearmYForTwoHandedGrip(pose.rightWristTwist()));
        json.addProperty("renderedLeftForearmY",
                ArticulatedLimbLayout.forearmYForTwoHandedGrip(pose.leftWristTwist()));
        json.addProperty("rightWristTwist", pose.rightWristTwist());
        json.addProperty("leftWristTwist", pose.leftWristTwist());
        json.addProperty("torsoPitch", pose.bodyX());
        json.addProperty("torsoYaw", pose.bodyY());
        json.addProperty("torsoRoll", pose.bodyZ());
        json.addProperty("rightKnee", pose.rightKnee());
        json.addProperty("leftKnee", pose.leftKnee());
        json.addProperty("stanceWidth", pose.stanceWidth());
        json.addProperty("stanceRoll", ArticulatedLimbLayout.stanceRoll(pose.stanceWidth()));
        json.addProperty("hipRootSeparation", ArticulatedLimbLayout.LEG_HIP_X * 2.0f);
        json.addProperty("rightLegRoll", pose.rightLegZ());
        json.addProperty("leftLegRoll", pose.leftLegZ());
        json.addProperty("footCenterSeparation", ArticulatedLimbLayout.footCenterSeparation(
                pose.rightLegZ(), pose.leftLegZ(), pose.stanceWidth()));
        if (Float.isFinite(scene.walkPosition)) {
            HeavyHammerGait.Sample gait = HeavyHammerGait.sample(
                    scene.walkPosition, scene.walkSpeed, scene.linearSpeed, false);
            HeavyHammerRenderedGait.Result expectedGait = HeavyHammerRenderedGait.expected(gait);
            json.addProperty("qaWalkPosition", scene.walkPosition);
            json.addProperty("qaWalkSpeed", scene.walkSpeed);
            json.addProperty("qaLinearSpeed", scene.linearSpeed);
            json.addProperty("gaitMovement", gait.movement());
            json.addProperty("gaitRun", gait.run());
            json.addProperty("gaitFootCenterSeparation", expectedGait.footCenterSeparation());
        }
        json.addProperty("shaftTopAngleDegrees", Math.toDegrees(Math.atan2(
                Math.abs(frame.shaft().z()), Math.abs(frame.shaft().x()))));
        json.addProperty("shaftDropAngleDegrees", Math.toDegrees(Math.atan2(
                frame.shaft().y(), Math.abs(frame.shaft().x()))));
        json.addProperty("visibleHandleTail", HeavyHammerSpatialRules.visibleHandleTail());
        EclipsePoseDebugExporter.HammerRigSnapshot renderedRig = EclipsePoseDebugExporter.latestHammerRig();
        if (renderedRig != null) {
            HeavyHammerRenderedRig.Result rig = renderedRig.result();
            json.addProperty("renderedRigAgeMs", Math.max(0L, System.currentTimeMillis() - renderedRig.timestamp()));
            json.addProperty("renderedRigProgress", renderedRig.progress());
            json.addProperty("renderedMainGripError", rig.mainGripError());
            json.addProperty("renderedOffhandGripError", rig.offhandGripError());
            json.addProperty("renderedRightShoulderRootError", rig.rightShoulderRootError());
            json.addProperty("renderedLeftShoulderRootError", rig.leftShoulderRootError());
            json.add("renderedMainHand", point(rig.renderedMain()));
            json.add("renderedOffhand", point(rig.renderedOffhand()));
            json.add("renderedRightArm", arm(renderedRig.right()));
            json.add("renderedLeftArm", arm(renderedRig.left()));
            json.addProperty("renderedFootCenterSeparation",
                    renderedRig.gait().footCenterSeparation());
            json.addProperty("renderedRightHipRootError", renderedRig.gait().rightHipRootError());
            json.addProperty("renderedLeftHipRootError", renderedRig.gait().leftHipRootError());
            json.add("renderedRightLeg", leg(renderedRig.rightLeg()));
            json.add("renderedLeftLeg", leg(renderedRig.leftLeg()));
        }
        json.add("target", target(QA_TARGET));
        json.add("item", item(client.player == null ? ItemStack.EMPTY : client.player.getMainHandItem()));
        writeJson(output, json);
    }

    private static void writeSessionManifest(Session current, String state, String error) {
        JsonObject json = new JsonObject();
        json.addProperty("schema", 1);
        json.addProperty("id", current.id);
        json.addProperty("state", state);
        json.addProperty("directory", current.directory.toAbsolutePath().toString());
        json.addProperty("planned", current.scenes.size());
        json.addProperty("captured", current.captured);
        json.addProperty("activeIndex", activeIndex);
        if (error != null) json.addProperty("error", error);
        JsonArray scenes = new JsonArray();
        for (Scene scene : current.scenes) {
            JsonObject entry = new JsonObject();
            entry.addProperty("name", scene.name);
            entry.addProperty("phase", scene.phase);
            entry.addProperty("elapsedTicks", scene.elapsedTicks);
            entry.addProperty("yawOffset", scene.yawOffset);
            entry.addProperty("pitch", scene.pitch);
            entry.addProperty("distance", scene.distance);
            if (Float.isFinite(scene.walkPosition)) {
                entry.addProperty("walkPosition", scene.walkPosition);
                entry.addProperty("walkSpeed", scene.walkSpeed);
                entry.addProperty("linearSpeed", scene.linearSpeed);
            }
            scenes.add(entry);
        }
        json.add("scenes", scenes);
        writeJson(current.directory.resolve("session.json"), json);
    }

    private static void writeLatestPointer(Minecraft client, Session current, boolean complete, String error) {
        JsonObject json = new JsonObject();
        json.addProperty("id", current.id);
        json.addProperty("directory", current.directory.toAbsolutePath().toString());
        json.addProperty("complete", complete);
        json.addProperty("captured", current.captured);
        if (error != null) json.addProperty("error", error);
        writeJson(root(client).resolve("latest.json"), json);
    }

    private static void failRequest(Minecraft client, String reason) {
        writeLastError(client, reason);
        if (client.player != null) client.player.sendSystemMessage(Component.literal("[Hammer QA] " + reason));
        EclipseClientMod.LOGGER.error("[HAMMER-QA] Request rejected: {}", reason);
    }

    private static void writeLastError(Minecraft client, String reason) {
        JsonObject json = new JsonObject();
        json.addProperty("timestamp", Instant.now().toString());
        json.addProperty("error", reason);
        writeJson(root(client).resolve("last-error.json"), json);
    }

    private static JsonObject vector(HeavyHammerProceduralMotion.Vec3 vector) {
        JsonObject json = new JsonObject();
        json.addProperty("x", vector.x());
        json.addProperty("y", vector.y());
        json.addProperty("z", vector.z());
        return json;
    }

    private static JsonObject point(HeavyHammerGripSolver.Point point) {
        JsonObject json = new JsonObject();
        json.addProperty("x", point.x());
        json.addProperty("y", point.y());
        json.addProperty("z", point.z());
        return json;
    }

    private static JsonObject arm(HeavyHammerRenderedRig.ArmPose arm) {
        JsonObject json = new JsonObject();
        json.addProperty("rootX", arm.rootX());
        json.addProperty("rootY", arm.rootY());
        json.addProperty("rootZ", arm.rootZ());
        json.addProperty("upperX", arm.upperX());
        json.addProperty("upperY", arm.upperY());
        json.addProperty("upperZ", arm.upperZ());
        json.addProperty("lowerX", arm.lowerX());
        json.addProperty("lowerY", arm.lowerY());
        json.addProperty("lowerZ", arm.lowerZ());
        return json;
    }

    private static JsonObject leg(HeavyHammerRenderedGait.LegPose leg) {
        JsonObject json = new JsonObject();
        json.addProperty("rootX", leg.rootX());
        json.addProperty("rootY", leg.rootY());
        json.addProperty("rootZ", leg.rootZ());
        json.addProperty("upperX", leg.upperX());
        json.addProperty("upperY", leg.upperY());
        json.addProperty("upperZ", leg.upperZ());
        json.addProperty("lowerX", leg.lowerX());
        json.addProperty("lowerY", leg.lowerY());
        json.addProperty("lowerZ", leg.lowerZ());
        return json;
    }

    private static JsonObject target(HeavyHammerProceduralMotion.Target target) {
        JsonObject json = new JsonObject();
        json.addProperty("x", target.x());
        json.addProperty("y", target.y());
        json.addProperty("z", target.z());
        json.addProperty("surface", target.surface().name());
        json.addProperty("normalX", target.normalX());
        json.addProperty("normalY", target.normalY());
        json.addProperty("normalZ", target.normalZ());
        return json;
    }

    private static HeavyHammerProceduralMotion.Vec3 cameraForward(Scene scene) {
        double yaw = Math.toRadians(scene.yawOffset);
        double pitch = Math.toRadians(scene.pitch);
        float horizontal = (float) Math.cos(pitch);
        return new HeavyHammerProceduralMotion.Vec3(
                (float) Math.sin(yaw) * horizontal,
                (float) Math.sin(pitch),
                (float) -Math.cos(yaw) * horizontal).normalized();
    }

    private static JsonObject item(ItemStack stack) {
        JsonObject json = new JsonObject();
        if (stack == null || stack.isEmpty()) return json;
        json.addProperty("name", stack.getHoverName().getString());
        json.addProperty("item", stack.getItem().toString());
        Identifier model = stack.get(DataComponents.ITEM_MODEL);
        json.addProperty("model", model == null ? "" : model.toString());
        return json;
    }

    private static void writeJson(Path path, JsonObject json) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(json) + System.lineSeparator(), StandardCharsets.UTF_8);
        } catch (IOException error) {
            EclipseClientMod.LOGGER.error("[HAMMER-QA] Could not write {}", path, error);
        }
    }

    private static void ensureRoot(Minecraft client) {
        try {
            Files.createDirectories(root(client));
        } catch (IOException ignored) {
        }
    }

    private static Path root(Minecraft client) {
        return client.gameDirectory.toPath().resolve("eclipse-debug").resolve("hammer-render");
    }

    private static String sanitize(String value) {
        String cleaned = value == null ? "full" : value.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_-]", "-");
        return cleaned.isBlank() ? "full" : cleaned;
    }

    public record CameraRig(float yawOffset, float pitch, float distance, float focusHeight) {
    }

    public record GaitRig(float walkPosition, float walkSpeed, float linearSpeed) {
    }

    private record Scene(String name, String phase, float elapsedTicks,
                         float yawOffset, float pitch, float distance,
                         float walkPosition, float walkSpeed, float linearSpeed) {
        private Scene(String name, String phase, float elapsedTicks,
                      float yawOffset, float pitch, float distance) {
            this(name, phase, elapsedTicks, yawOffset, pitch, distance,
                    Float.NaN, 0.0f, 0.0f);
        }
    }

    private static final class Session {
        private final String id;
        private final Path directory;
        private final List<Scene> scenes;
        private final CameraType previousCamera;
        private final boolean previousHideGui;
        private int nextScene;
        private int captured;

        private Session(String id, Path directory, List<Scene> scenes,
                        CameraType previousCamera, boolean previousHideGui) {
            this.id = id;
            this.directory = directory;
            this.scenes = scenes;
            this.previousCamera = previousCamera;
            this.previousHideGui = previousHideGui;
        }
    }
}
