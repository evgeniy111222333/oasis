package ua.rp.chat;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.PlayerModelPart;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ChargedProjectiles;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import ua.rp.chat.animation.AnimationLibrary;
import ua.rp.chat.animation.AnimationPose;
import ua.rp.chat.animation.LocomotionController;
import ua.rp.chat.carver.CarverItems;
import ua.rp.chat.client.animation.PlayerAnimationRenderState;
import ua.rp.chat.client.animation.PlayerAnimationRig;
import ua.rp.chat.client.animation.PlayerTorsoSkeleton;
import ua.rp.chat.client.camera.SmartCameraManager;
import ua.rp.chat.client.render.LocalPlayerRenderState;
import ua.rp.chat.item.MedievalItems;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/** Native avatar capture, skin deformation, segmented joints, held tools and pooled-model reset. */
public final class PlayerAnimationsClientGameTest implements FabricClientGameTest {
    private static final String RESOURCE = "/assets/eclipseclient/animations/rp_character_v3.animation.json";
    private static final int OBSERVED_A = 2_000_000_038;
    private static final int OBSERVED_B = 2_000_000_039;
    private static final int DETACHED = -2_000_000_038;
    private static final float EPSILON = 2e-5f;

    @Override public void runTest(ClientGameTestContext context) {
        context.waitTicks(3);
        context.waitFor(client -> com.cinemamod.mcef.MCEF.isInitialized(), 3600);
        AnimationLibrary library = loadLibrary();
        require(library.clipCount() == 22, "The runtime resource must contain all 22 supplied clips");
        require(library.boneNames().size() == 22, "The runtime resource must contain all 22 source bones");
        try (var world = context.worldBuilder().create()) {
            world.getClientLevel().waitForChunksRender();
            context.getInput().resizeWindow(1280, 720);
            context.runOnClient(client -> {
                client.options.guiScale().set(2);
                client.resizeGui();
                client.options.setCameraType(CameraType.THIRD_PERSON_BACK);
                equip(client.player);
                RemotePlayer a = subject(client, OBSERVED_A, "AnimObservedA");
                RemotePlayer b = subject(client, OBSERVED_B, "AnimObservedB");
                a.setPos(client.player.getX() - 1.1, client.player.getY(), client.player.getZ() + 3);
                b.setPos(client.player.getX() + 1.1, client.player.getY(), client.player.getZ() + 3);
                motion(a, 0, 0);
                motion(b, 0, 0);
                client.level.addEntity(a);
                client.level.addEntity(b);
                RemotePlayer detached = subject(client, DETACHED, "AnimDetached");
                detached.setPos(client.player.position());
                motion(detached, 0, 0);
                verifyNativeCapture(client, a, b, detached);
                verifyDistantEditorCapture(client, detached);
                verifyEveryBone(client, detached, library);
                verifyEveryClipAndReset(client, detached, library);
                verifyNativeArmor(client, detached, library);
                verifyActionsAndFallbacks(client, detached, library);
                verifyFirstPersonIsolation(client, a, library);
                motion(a, 0, 0);
                motion(b, 0, 0);
                showStandingAndCrouching(client, a, b, library);
            });
            context.waitTicks(4);
            context.takeScreenshot("player-animations-native-stand-crouch-tools-backpack");
            context.runOnClient(client -> showSprintAndStrafe(client,
                    (RemotePlayer) client.level.getEntity(OBSERVED_A),
                    (RemotePlayer) client.level.getEntity(OBSERVED_B), library));
            context.waitTicks(4);
            context.takeScreenshot("player-animations-native-sprint-strafe-two-avatars");
            context.runOnClient(client -> {
                client.setScreen(null);
                client.options.setCameraType(CameraType.FIRST_PERSON);
                client.player.setYRot(0);
                client.player.yRotO = 0;
                client.player.setXRot(24);
                client.player.xRotO = 24;
                client.player.yBodyRot = client.player.yBodyRotO = 0;
                client.player.yHeadRot = client.player.yHeadRotO = 0;
                RemotePlayer a = (RemotePlayer) client.level.getEntity(OBSERVED_A);
                RemotePlayer b = (RemotePlayer) client.level.getEntity(OBSERVED_B);
                a.setPos(client.player.getX() - 1.0, client.player.getY(), client.player.getZ() + 2.6);
                b.setPos(client.player.getX() + 1.0, client.player.getY(), client.player.getZ() + 2.6);
                a.setDeltaMovement(Vec3.ZERO);
                b.setShiftKeyDown(true);
                b.setPose(Pose.CROUCHING);
                b.setDeltaMovement(.12, 0, 0);
            });
            context.waitTicks(5);
            context.takeScreenshot("player-animations-native-first-person-two-equipped-avatars");
            context.runOnClient(client -> {
                client.level.removeEntity(OBSERVED_A, Entity.RemovalReason.DISCARDED);
                client.level.removeEntity(OBSERVED_B, Entity.RemovalReason.DISCARDED);
                client.options.setCameraType(CameraType.FIRST_PERSON);
                SmartCameraManager.getInstance().setRenderingFirstPersonPlayer(false);
                SmartCameraManager.getInstance().setSubmittingFirstPersonPlayer(false);
            });
        }
    }

    /** Native extraction must attach independent immutable frames to local, observed and detached states. */
    private static void verifyNativeCapture(Minecraft client, RemotePlayer a, RemotePlayer b, RemotePlayer detached) {
        boolean grounded = client.player.onGround();
        Vec3 oldVelocity = client.player.getDeltaMovement();
        double oldX = client.player.xOld, oldZ = client.player.zOld;
        try {
            client.player.setOnGround(true);
            motion(client.player, 0, 0);
            AvatarRenderState local = capture(client, client.player);
            require(local instanceof LocalPlayerRenderState flag && flag.eclipse$isLocalPlayer(),
                    "The native extractor must identify the local player");
            require(frame(local) != null && frame(local).active(), "Grounded local capture must have an active frame");
            motion(a, 0, -.18);
            motion(b, 0, 0);
            AvatarRenderState moving = capture(client, a);
            AvatarRenderState idle = capture(client, b);
            LocomotionController.Frame frozen = frame(moving);
            require(frozen != null && frozen.active(), "Observed locomotion must be captured by the native renderer");
            require(frame(idle) != null && frame(idle).active(), "Observed idle must have its own controller frame");
            require(frozen != frame(idle), "Two observed players must never share a mutable animation frame");
            require(!frozen.clip().equals(frame(idle).clip()), "One moving avatar must not drive another avatar's clip");
            AnimationPose.Vector oldLeg = frozen.pose().rotation("left_leg");
            a.tickCount += 8;
            motion(a, .2, 0);
            capture(client, a);
            capture(client, b);
            require(frame(moving) == frozen && oldLeg.equals(frozen.pose().rotation("left_leg")),
                    "Later native extractions must not change a captured pose");
            require(!(moving instanceof LocalPlayerRenderState flag) || !flag.eclipse$isLocalPlayer(),
                    "An observed avatar must retain its remote identity");
            require(frame(capture(client, detached)) != null, "Detached editor avatars must use the native capture path");
            motion(a, 0, .18);
            AvatarRenderState backward = capture(client, a);
            require(frame(backward).clip().startsWith("walk_backward"), "Native capture must classify backward movement");
            require(Math.abs(Mth.wrapDegrees(backward.bodyRot - a.getYRot())) < EPSILON,
                    "Backward motion must preserve the avatar's facing in the captured body rotation");
            b.setShiftKeyDown(true);
            b.setPose(Pose.CROUCHING);
            AvatarRenderState crouched = capture(client, b);
            require(crouched.isCrouching && frame(crouched).clip().equals("sneak_enter"),
                    "Native crouch flags must be extracted before choosing the captured animation");
            b.setShiftKeyDown(false);
            b.setPose(Pose.STANDING);
            verifyEquipment(local, client.player);
            verifyEquipment(moving, a);
            client.player.setOnGround(false);
            require(!frame(capture(client, client.player)).active(), "Airborne local motion must fall back to native animation");
            a.setOnGround(false);
            require(!frame(capture(client, a)).active(), "Airborne observed motion must fall back independently");
            a.setOnGround(true);
        } finally {
            client.player.setOnGround(grounded);
            client.player.setDeltaMovement(oldVelocity);
            client.player.xOld = oldX;
            client.player.zOld = oldZ;
        }
    }

    /** Rendering into a real VertexConsumer also exercises the continuously deformed torso and joint bridges. */
    private static void verifyDistantEditorCapture(Minecraft client, RemotePlayer detached) {
        Vec3 savedPosition = detached.position();
        double savedX = detached.xOld, savedZ = detached.zOld;
        String savedAnimation = ua.rp.chat.client.modeleditor.ModelEditorClient.previewAnimation();
        try {
            ua.rp.chat.client.modeleditor.ModelEditorClient.setPreviewPlayer(detached);
            ua.rp.chat.client.modeleditor.ModelEditorClient.setPreviewAnimation("walk");
            detached.setPos(4096, savedPosition.y, -4096);
            detached.xOld = detached.zOld = 0;
            detached.tickCount += 1;
            AvatarRenderState walking = capture(client, detached);
            require(frame(walking).active() && frame(walking).clip().startsWith("walk"),
                    "A detached editor avatar far from world origin must retain authored walking");
            var skeleton = frameState(walking).eclipse$animationSkeleton(false);
            require(skeleton == frameState(walking).eclipse$animationSkeleton(false),
                    "Skin and armor layers must share the same grounded skeleton within a captured frame");
        } finally {
            ua.rp.chat.client.modeleditor.ModelEditorClient.setPreviewPlayer(null);
            ua.rp.chat.client.modeleditor.ModelEditorClient.setPreviewAnimation(savedAnimation);
            detached.setPos(savedPosition);
            detached.xOld = savedX; detached.zOld = savedZ;
        }
    }

    /** Rendering into a real VertexConsumer also exercises the continuously deformed torso and joint bridges. */
    private static void verifyEveryBone(Minecraft client, RemotePlayer detached, AnimationLibrary library) {
        AvatarRenderState state = prepared(client, detached, false);
        setFrame(state, probe(null), "probe");
        PlayerModel neutralModel = baked(client, false);
        neutralModel.setupAnim(state);
        VertexCloud neutral = vertices(neutralModel);
        require(neutral.size() > 100, "The native rig must emit skin geometry");
        for (String bone : library.boneNames()) {
            setFrame(state, probe(bone), "probe");
            PlayerModel model = baked(client, false);
            model.setupAnim(state);
            VertexCloud posed = vertices(model);
            require(!posed.closeTo(neutral), "Source bone must affect native rendered skin: " + bone);
            require(posed.sameUvSurface(neutral),
                    "Joint subdivision must preserve native skin UV surface area and atlas bounds: " + bone);
            if (bone.equals("center_of_mass")) {
                PlayerTorsoSkeleton grounded = new PlayerTorsoSkeleton(probe(bone));
                grounded.groundFootContacts(probe(bone), false);
                require(Math.abs(model.body.x - .7f) < EPSILON
                                && Math.abs(model.body.y + grounded.groundLiftPixels() - .9f) < EPSILON
                                && Math.abs(model.body.z - 1.1f) < EPSILON,
                        "Authored center-of-mass pixels must use native (x, -y, z) translation");
            }
        }
        System.out.println("[PLAYER-ANIMATIONS-TEST] All 22 source bones affect native emitted skin vertices");
    }

    private static void verifyEveryClipAndReset(Minecraft client, RemotePlayer detached, AnimationLibrary library) {
        AvatarRenderState state = prepared(client, detached, false);
        for (boolean slim : List.of(false, true)) {
            PlayerModel reused = baked(client, slim);
            for (String clip : library.clipNames()) {
                for (double fraction : List.of(0.0, .37, .93)) {
                    AnimationPose pose = library.sample(clip, library.length(clip) * fraction);
                    for (String bone : library.boneNames()) {
                        finite(pose.rotation(bone), clip + " rotation " + bone);
                        finite(pose.position(bone), clip + " position " + bone);
                    }
                    setFrame(state, pose, clip);
                    reused.setupAnim(state);
                    PlayerModel fresh = baked(client, slim);
                    fresh.setupAnim(state);
                    require(vertices(reused).closeTo(vertices(fresh)),
                            "A shared native model must reset every channel before " + clip + " at " + fraction + " (slim=" + slim + ")");
                    verifyDistalJoints(reused, pose);
                    verifyHeldMatrices(reused, state, slim);
                    VertexCloud feet = new VertexCloud();
                    reused.leftLeg.render(new PoseStack(), feet, 0xF000F0, 0);
                    reused.rightLeg.render(new PoseStack(), feet, 0xF000F0, 0);
                    require(feet.maxY() * 16 <= PlayerTorsoSkeleton.floorPixels(state.isCrouching) + .15f,
                            "Authored soles must remain above the native floor: " + clip);
                }
            }
            setFrame(state, probe(null), "probe");
            reused.setupAnim(state);
            PlayerModel neutral = baked(client, slim);
            neutral.setupAnim(state);
            require(vertices(reused).closeTo(vertices(neutral)), "Sparse/neutral poses must clear all preceding clip channels");
        }
        System.out.println("[PLAYER-ANIMATIONS-TEST] All 22 clips sampled on wide/slim native models; pooled resets and both held matrices verified");
    }

    /** AvatarRenderer bakes its armor mesh sets as PlayerModel, including empty wearable children. */
    private static void verifyNativeArmor(Minecraft client, RemotePlayer detached, AnimationLibrary library) {
        AvatarRenderState state = prepared(client, detached, false);
        AnimationPose neutralPose = probe(null);
        AnimationPose dramatic = probeAll(library.boneNames());
        for (boolean slim : List.of(false, true)) {
            for (String bone : library.boneNames()) {
                EquipmentSlot slot = bone.equals("head") || bone.equals("neck") ? EquipmentSlot.HEAD
                        : bone.contains("leg") || bone.contains("calf") || bone.contains("ankle") || bone.contains("toe")
                        ? EquipmentSlot.FEET : EquipmentSlot.CHEST;
                PlayerModel model = bakedArmor(client, slim, slot);
                setFrame(state, neutralPose, "probe");
                model.setupAnim(state);
                VertexCloud neutral = vertices(model);
                require(neutral.size() > 0, "Native " + slot + " armor must emit its original cuboids");
                setFrame(state, probe(bone), "probe");
                model.setupAnim(state);
                VertexCloud posed = vertices(model);
                require(!posed.closeTo(neutral), "Authored bone must affect real PlayerModel armor vertices: " + bone);
                require(posed.sameUvSurface(neutral), "Armor joint subdivision must preserve the original atlas surface: " + bone);
            }
            for (EquipmentSlot slot : List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET)) {
                PlayerModel reused = bakedArmor(client, slim, slot);
                for (String clip : library.clipNames()) {
                    for (double fraction : List.of(0.0, .37, .93)) {
                        setFrame(state, library.sample(clip, library.length(clip) * fraction), clip);
                        reused.setupAnim(state);
                        PlayerModel fresh = bakedArmor(client, slim, slot);
                        fresh.setupAnim(state);
                        VertexCloud actual = vertices(reused), expected = vertices(fresh);
                        require(actual.size() > 0 && actual.closeTo(expected),
                                "Native armor must emit finite vertices and reset pooled deformation: " + slot + "/" + clip
                                        + " at " + fraction + " (slim=" + slim + ")");
                    }
                }
                setFrame(state, neutralPose, "probe");
                reused.setupAnim(state);
                PlayerModel neutral = bakedArmor(client, slim, slot);
                neutral.setupAnim(state);
                require(vertices(reused).closeTo(vertices(neutral)), "Neutral authored armor must clear all previous joints: " + slot);
                for (Consumer<AvatarRenderState> configure : unsupportedStates()) {
                    AvatarRenderState fallback = prepared(client, detached, false);
                    setFrame(fallback, dramatic, "probe");
                    reused.setupAnim(fallback);
                    configure.accept(fallback);
                    reused.setupAnim(fallback);
                    require(!PlayerAnimationRig.apply(reused, fallback), "Unsupported native armor must reject authored playback");
                    PlayerModel fresh = bakedArmor(client, slim, slot);
                    fresh.setupAnim(fallback);
                    require(vertices(reused).closeTo(vertices(fresh)),
                            "Native armor fallback must clear cached cuboid skinning: " + slot + " (slim=" + slim + ")");
                    frameState(fallback).eclipse$setAnimationFrame(new LocomotionController.Frame(neutralPose, "", false));
                    PlayerModel inactive = bakedArmor(client, slim, slot);
                    inactive.setupAnim(fallback);
                    require(vertices(fresh).closeTo(vertices(inactive)), "Unsupported armor must match inactive native cuboids: " + slot);
                }
            }
        }
        System.out.println("[PLAYER-ANIMATIONS-TEST] All 22 clips and bones verified on native wide/slim PlayerModel armor; cuboid UVs and fallback resets verified");
    }

    private static void verifyDistalJoints(PlayerModel model, AnimationPose pose) {
        for (HumanoidArm side : HumanoidArm.values()) {
            String prefix = side == HumanoidArm.RIGHT ? "right" : "left";
            ModelPart arm = side == HumanoidArm.RIGHT ? model.rightArm : model.leftArm;
            ModelPart forearm = child(arm, "eclipse_forearm");
            rotationMatches(forearm, pose.rotation(prefix + "_forearm"), prefix + " forearm");
            rotationMatches(child(forearm, "eclipse_hand"), pose.rotation(prefix + "_hand"), prefix + " hand");
            ModelPart leg = side == HumanoidArm.RIGHT ? model.rightLeg : model.leftLeg;
            ModelPart shin = child(leg, "eclipse_shin");
            rotationMatches(shin, pose.rotation(prefix + "_calf"), prefix + " calf");
            ModelPart ankle = child(shin, "eclipse_ankle");
            rotationMatches(ankle, pose.rotation(prefix + "_ankle"), prefix + " ankle");
            rotationMatches(child(ankle, "eclipse_toe"), pose.rotation(prefix + "_toe"), prefix + " toe");
        }
    }

    private static void verifyHeldMatrices(PlayerModel model, AvatarRenderState state, boolean slim) {
        for (HumanoidArm side : HumanoidArm.values()) {
            ModelPart arm = side == HumanoidArm.RIGHT ? model.rightArm : model.leftArm;
            ModelPart forearm = child(arm, "eclipse_forearm");
            ModelPart hand = child(forearm, "eclipse_hand");
            PoseStack expected = new PoseStack();
            model.root().translateAndRotate(expected);
            float originalX = arm.x;
            try {
                if (slim) arm.x += side == HumanoidArm.RIGHT ? .5f : -.5f;
                arm.translateAndRotate(expected);
            } finally { arm.x = originalX; }
            forearm.translateAndRotate(expected);
            expected.translate(-forearm.x / 16, -forearm.y / 16, -forearm.z / 16);
            expected.translate((forearm.x + hand.x) / 16, (forearm.y + hand.y) / 16, (forearm.z + hand.z) / 16);
            expected.mulPose(new Quaternionf().rotationZYX(hand.zRot, hand.yRot, hand.xRot));
            expected.translate(-(forearm.x + hand.x) / 16, -(forearm.y + hand.y) / 16, -(forearm.z + hand.z) / 16);
            PoseStack actual = new PoseStack();
            model.translateToHand(state, side, actual);
            require(new Matrix4f(actual.last().pose()).equals(expected.last().pose(), EPSILON),
                    "Held " + side + " item must follow the native forearm and wrist (slim=" + slim + ")");
            require(arm.x == originalX, "Slim hand correction must not leak into later avatar renders");
        }
    }

    private static void verifyActionsAndFallbacks(Minecraft client, RemotePlayer detached, AnimationLibrary library) {
        AvatarRenderState state = prepared(client, detached, false);
        AnimationPose dramatic = probeAll(library.boneNames());
        for (boolean using : List.of(false, true)) {
            state.isUsingItem = using;
            state.attackTime = using ? 0 : .6f;
            state.useItemHand = InteractionHand.MAIN_HAND;
            state.rightArmPose = using ? HumanoidModel.ArmPose.BLOCK : HumanoidModel.ArmPose.ITEM;
            setFrame(state, probe(null), "probe");
            PlayerModel baseline = baked(client, false);
            baseline.setupAnim(state);
            setFrame(state, dramatic, "probe");
            PlayerModel animated = baked(client, false);
            animated.setupAnim(state);
            for (HumanoidArm side : HumanoidArm.values()) {
                ModelPart arm = side == HumanoidArm.RIGHT ? animated.rightArm : animated.leftArm;
                ModelPart nativeArm = side == HumanoidArm.RIGHT ? baseline.rightArm : baseline.leftArm;
                require(orientation(arm).equals(orientation(nativeArm), EPSILON),
                        "Authored shoulders must preserve the native " + (using ? "use" : "attack") + " arm rotation");
                rotationMatches(child(arm, "eclipse_forearm"), new AnimationPose.Vector(0, 0, 0), "action forearm");
                rotationMatches(child(child(arm, "eclipse_forearm"), "eclipse_hand"), new AnimationPose.Vector(0, 0, 0), "action hand");
            }
            verifyHeldMatrices(animated, state, false);
        }
        verifyChargedCrossbowHold(client, detached, dramatic);
        state.isUsingItem = false;
        state.attackTime = 0;
        state.rightArmPose = HumanoidModel.ArmPose.ITEM;
        PlayerModel reused = baked(client, false);
        for (Consumer<AvatarRenderState> configure : unsupportedStates()) {
            AvatarRenderState fallback = prepared(client, detached, false);
            setFrame(fallback, dramatic, "probe");
            reused.setupAnim(fallback);
            configure.accept(fallback);
            reused.setupAnim(fallback);
            require(!PlayerAnimationRig.apply(reused, fallback), "Unsupported native states must reject authored locomotion");
            PlayerModel fresh = baked(client, false);
            fresh.setupAnim(fallback);
            require(vertices(reused).closeTo(vertices(fresh)), "Returning to native fallback must clear the previous articulated and torso pose");
            frameState(fallback).eclipse$setAnimationFrame(new LocomotionController.Frame(probe(null), "", false));
            PlayerModel inactive = baked(client, false);
            inactive.setupAnim(fallback);
            require(vertices(fresh).closeTo(vertices(inactive)), "Unsupported states must render identically with an inactive authored frame");
        }
    }

    private static List<Consumer<AvatarRenderState>> unsupportedStates() {
        return List.of(
                value -> value.isPassenger = true,
                value -> value.isFallFlying = true,
                value -> { value.isVisuallySwimming = true; value.swimAmount = 1; },
                value -> { value.bedOrientation = Direction.NORTH; value.pose = Pose.SLEEPING; },
                value -> value.deathTime = 8,
                value -> value.isSpectator = true,
                value -> value.isAutoSpinAttack = true);
    }

    private static void verifyChargedCrossbowHold(Minecraft client, RemotePlayer detached, AnimationPose dramatic) {
        ItemStack original = detached.getMainHandItem();
        try {
            ItemStack charged = new ItemStack(Items.CROSSBOW);
            charged.set(DataComponents.CHARGED_PROJECTILES, ChargedProjectiles.ofNonEmpty(List.of(new ItemStack(Items.ARROW))));
            detached.setItemSlot(EquipmentSlot.MAINHAND, charged);
            AvatarRenderState state = prepared(client, detached, false);
            require(!state.isUsingItem && state.attackTime == 0 && state.rightArmPose == HumanoidModel.ArmPose.CROSSBOW_HOLD,
                    "Native extraction must retain a loaded crossbow's idle hold without attack or use flags");
            setFrame(state, probe(null), "probe");
            PlayerModel baseline = baked(client, false);
            baseline.setupAnim(state);
            setFrame(state, dramatic, "probe");
            PlayerModel animated = baked(client, false);
            animated.setupAnim(state);
            for (HumanoidArm side : HumanoidArm.values()) {
                ModelPart arm = side == HumanoidArm.RIGHT ? animated.rightArm : animated.leftArm;
                ModelPart nativeArm = side == HumanoidArm.RIGHT ? baseline.rightArm : baseline.leftArm;
                require(orientation(arm).equals(orientation(nativeArm), EPSILON),
                        "Authored shoulders must preserve the native charged crossbow hold");
                rotationMatches(child(arm, "eclipse_forearm"), new AnimationPose.Vector(0, 0, 0), "crossbow hold forearm");
                rotationMatches(child(child(arm, "eclipse_forearm"), "eclipse_hand"), new AnimationPose.Vector(0, 0, 0), "crossbow hold hand");
            }
            verifyHeldMatrices(animated, state, false);
        } finally { detached.setItemSlot(EquipmentSlot.MAINHAND, original); }
    }

    private static void verifyFirstPersonIsolation(Minecraft client, RemotePlayer observed, AnimationLibrary library) {
        CameraType previous = client.options.getCameraType();
        SmartCameraManager manager = SmartCameraManager.getInstance();
        boolean enabled = manager.isEnabled();
        try {
            manager.setEnabled(true);
            client.options.setCameraType(CameraType.FIRST_PERSON);
            manager.setSubmittingFirstPersonPlayer(true);
            AvatarRenderState local = prepared(client, client.player, false);
            setFrame(local, probeAll(library.boneNames()), "probe");
            PlayerModel shared = baked(client, false);
            shared.setupAnim(local);
            require(!shared.head.visible && shared.head.skipDraw && !shared.hat.visible && shared.hat.skipDraw,
                    "The local first-person body must hide the animated head and hat");
            require(shared.body.visible, "First-person animation must keep the native body visible");
            verifyHeldMatrices(shared, local, false);
            AvatarRenderState remote = prepared(client, observed, false);
            setFrame(remote, probe(null), "probe");
            shared.setupAnim(remote);
            require(shared.head.visible && !shared.head.skipDraw && shared.hat.visible && !shared.hat.skipDraw,
                    "A shared renderer must restore head and hat for the next observed avatar");
            PlayerModel fresh = baked(client, false);
            fresh.setupAnim(remote);
            require(vertices(shared).closeTo(vertices(fresh)), "Local first-person transforms must not leak into an observed body");
        } finally {
            manager.setSubmittingFirstPersonPlayer(false);
            manager.setRenderingFirstPersonPlayer(false);
            manager.setEnabled(enabled);
            client.options.setCameraType(previous);
        }
    }

    private static void showStandingAndCrouching(Minecraft client, RemotePlayer a, RemotePlayer b, AnimationLibrary library) {
        String standing = "idle", crouching = "sneak_enter";
        client.setScreen(new PoseAuditScreen(List.of(
                view(client, a, library, standing, false, 22, "A / standing / hammer + chisel"),
                view(client, b, library, crouching, true, 22, "B / crouching / hammer + chisel"),
                view(client, a, library, standing, false, 158, "A / standing / backpack"),
                view(client, b, library, crouching, true, 158, "B / crouching / backpack"))));
    }

    private static void showSprintAndStrafe(Minecraft client, RemotePlayer a, RemotePlayer b, AnimationLibrary library) {
        String sprint = "sprint_loop", strafe = "walk_strafe_left_loop";
        client.setScreen(new PoseAuditScreen(List.of(
                view(client, a, library, sprint, false, 22, "A / sprint / hammer + chisel"),
                view(client, b, library, strafe, false, 22, "B / left strafe / hammer + chisel"),
                view(client, a, library, sprint, false, 158, "A / sprint / backpack"),
                view(client, b, library, strafe, false, 158, "B / left strafe / backpack"))));
    }

    private static AuditView view(Minecraft client, AbstractClientPlayer player, AnimationLibrary library,
                                  String clip, boolean crouch, float yaw, String label) {
        AvatarRenderState state = prepared(client, player, crouch);
        setFrame(state, library.sample(clip, library.length(clip) * (crouch ? 1 : .37)), clip);
        return new AuditView(state, yaw, label);
    }

    private static RemotePlayer subject(Minecraft client, int id, String name) {
        RemotePlayer player = new RemotePlayer(client.level, new GameProfile(new UUID(0xECL, id), name)) {
            @Override public PlayerSkin getSkin() { return client.player.getSkin(); }
            @Override public boolean isModelPartShown(PlayerModelPart part) { return true; }
            @Override public void tick() {
                xOld = getX(); zOld = getZ();
                setPos(position().add(getDeltaMovement()));
                tickCount++;
            }
        };
        player.setId(id);
        player.setNoGravity(true);
        player.setSilent(true);
        player.setOnGround(true);
        player.setMainArm(HumanoidArm.RIGHT);
        player.yBodyRot = player.yBodyRotO = 180;
        player.yHeadRot = player.yHeadRotO = 180;
        player.setYRot(180);
        player.yRotO = 180;
        player.tickCount = 120;
        equip(player);
        return player;
    }

    private static void equip(net.minecraft.world.entity.player.Player player) {
        player.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(MedievalItems.HAMMER));
        player.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(CarverItems.CHISEL_POINT));
        player.setItemSlot(EquipmentSlot.CHEST, new ItemStack(CarverItems.BAG));
    }

    private static void motion(net.minecraft.world.entity.player.Player player, double x, double z) {
        player.setDeltaMovement(x, 0, z);
        player.xOld = player.getX() - x;
        player.zOld = player.getZ() - z;
    }

    private static void verifyEquipment(AvatarRenderState state, net.minecraft.world.entity.player.Player player) {
        require(state.rightHandItemStack.is(MedievalItems.HAMMER) && state.leftHandItemStack.is(CarverItems.CHISEL_POINT),
                "Native state must retain the hammer and chisel in their physical hands");
        require(state.id == player.getId() && player.getItemBySlot(EquipmentSlot.CHEST).is(CarverItems.BAG),
                "The entity resolved by the custom backpack layer must retain its chest stack");
    }

    private static AvatarRenderState capture(Minecraft client, AbstractClientPlayer player) {
        var renderer = client.getEntityRenderDispatcher().getPlayerRenderer(player);
        AvatarRenderState state = renderer.createRenderState();
        renderer.extractRenderState(player, state, 1);
        return state;
    }

    private static AvatarRenderState prepared(Minecraft client, AbstractClientPlayer player, boolean crouch) {
        AvatarRenderState state = capture(client, player);
        state.ageInTicks = 120;
        state.isPassenger = state.isFallFlying = state.isVisuallySwimming = state.isSpectator = false;
        state.isUsingItem = false;
        state.attackTime = state.swimAmount = state.deathTime = 0;
        state.bedOrientation = null;
        state.pose = crouch ? Pose.CROUCHING : Pose.STANDING;
        state.isCrouching = crouch;
        state.walkAnimationPos = state.walkAnimationSpeed = 0;
        state.bodyRot = 180;
        state.yRot = state.xRot = 0;
        state.showHat = state.showJacket = state.showLeftPants = state.showRightPants = state.showLeftSleeve = state.showRightSleeve = true;
        return state;
    }

    private static PlayerModel baked(Minecraft client, boolean slim) {
        return new PlayerModel(client.getEntityModels().bakeLayer(slim ? ModelLayers.PLAYER_SLIM : ModelLayers.PLAYER), slim);
    }

    private static PlayerModel bakedArmor(Minecraft client, boolean slim, EquipmentSlot slot) {
        var layers = slim ? ModelLayers.PLAYER_SLIM_ARMOR : ModelLayers.PLAYER_ARMOR;
        PlayerModel model = new PlayerModel(client.getEntityModels().bakeLayer(layers.get(slot)), slim);
        require(!model.body.hasChild("eclipse_breathing_torso"), "Native armor must exercise the full-cuboid PlayerModel route");
        return model;
    }

    private static PlayerAnimationRenderState frameState(AvatarRenderState state) {
        require(state instanceof PlayerAnimationRenderState, "Native AvatarRenderState must implement animation capture storage");
        return (PlayerAnimationRenderState) state;
    }
    private static LocomotionController.Frame frame(AvatarRenderState state) { return frameState(state).eclipse$animationFrame(); }
    private static void setFrame(AvatarRenderState state, AnimationPose pose, String clip) {
        frameState(state).eclipse$setAnimationFrame(new LocomotionController.Frame(pose, clip, true));
    }
    private static ModelPart child(ModelPart parent, String name) {
        require(parent.hasChild(name), "Native articulated player bake is missing " + name);
        return parent.getChild(name);
    }
    private static Quaternionf orientation(ModelPart part) { return new Quaternionf().rotationZYX(part.zRot, part.yRot, part.xRot); }
    private static void rotationMatches(ModelPart part, AnimationPose.Vector rotation, String label) {
        float radians = (float) (Math.PI / 180);
        Quaternionf expected = new Quaternionf().rotationZYX(rotation.z() * radians, rotation.y() * radians, rotation.x() * radians);
        require(Math.abs(orientation(part).dot(expected)) > 1 - EPSILON, "Native joint must preserve authored rotation: " + label);
    }
    private static void finite(AnimationPose.Vector value, String label) {
        require(Float.isFinite(value.x()) && Float.isFinite(value.y()) && Float.isFinite(value.z()), "Non-finite sampled " + label);
    }
    private static AnimationLibrary loadLibrary() {
        try (InputStream stream = PlayerAnimationsClientGameTest.class.getResourceAsStream(RESOURCE)) {
            require(stream != null, "The player animation resource must be bundled in the client");
            return AnimationLibrary.load(stream);
        } catch (IOException failure) { throw new AssertionError(failure); }
    }
    private static AnimationPose probe(String bone) { return probeAll(bone == null ? List.of() : List.of(bone)); }
    private static AnimationPose probeAll(Iterable<String> bones) {
        JsonObject channels = new JsonObject();
        for (String bone : bones) {
            JsonObject track = new JsonObject();
            JsonArray rotation = new JsonArray();
            rotation.add(17); rotation.add(23); rotation.add(31);
            JsonArray position = new JsonArray();
            position.add(.7); position.add(-.9); position.add(1.1);
            track.add("rotation", rotation); track.add("position", position);
            channels.add(bone, track);
        }
        JsonObject clip = new JsonObject();
        clip.addProperty("animation_length", 1);
        clip.add("bones", channels);
        JsonObject clips = new JsonObject(); clips.add("animation.probe", clip);
        JsonObject root = new JsonObject(); root.addProperty("format_version", "1.8.0"); root.add("animations", clips);
        try (InputStream stream = new ByteArrayInputStream(root.toString().getBytes(StandardCharsets.UTF_8))) {
            return AnimationLibrary.load(stream).sample("probe", 0);
        } catch (IOException failure) { throw new AssertionError(failure); }
    }
    private static VertexCloud vertices(PlayerModel model) {
        VertexCloud cloud = new VertexCloud();
        model.renderToBuffer(new PoseStack(), cloud, 0xF000F0, 0);
        return cloud;
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }

    private record AuditView(AvatarRenderState state, float yaw, String label) {}

    /** Captured states are immutable: drawing a later tile cannot advance an earlier avatar's controller. */
    private static final class PoseAuditScreen extends Screen {
        private final List<AuditView> views;
        private PoseAuditScreen(List<AuditView> views) { super(Component.literal("Native player animation audit")); this.views = views; }
        @Override public boolean isPauseScreen() { return false; }
        @Override public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
            graphics.fill(0, 0, width, height, 0xff101820);
        }
        @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
            extractBackground(graphics, mouseX, mouseY, delta);
            graphics.text(font, "Native player rig / independent captured avatar frames", 10, 10, 0xffefd4a4);
            int gap = 6;
            int panelWidth = (width - gap * 5) / 4;
            for (int i = 0; i < views.size(); i++) {
                AuditView view = views.get(i);
                int left = gap + i * (panelWidth + gap);
                int top = 35;
                int right = left + panelWidth;
                int bottom = height - 14;
                graphics.fill(left, top, right, bottom, 0xff202d3b);
                graphics.textWithWordWrap(font, Component.literal(view.label()), left + 5, top + 5, panelWidth - 10, 0xffe7edf5, false);
                graphics.textWithWordWrap(font, Component.literal(frame(view.state()).clip()), left + 5, top + 29, panelWidth - 10, 0xff93b2ca, false);
                Quaternionf tilt = new Quaternionf().rotateX((float) Math.toRadians(-6));
                Quaternionf rotation = new Quaternionf().rotateZ((float) Math.PI).mul(tilt).rotateY((float) Math.toRadians(view.yaw()));
                float scale = Math.min((bottom - top - 62) / 2.2f, panelWidth / 1.55f);
                graphics.entity(view.state(), scale, new Vector3f(0, view.state().boundingBoxHeight / 2, 0),
                        rotation, tilt, left + 1, top + 56, right - 1, bottom - 1);
            }
        }
    }

    private static final class VertexCloud implements VertexConsumer {
        private final List<Float> coordinates = new ArrayList<>();
        private final List<Float> textureCoordinates = new ArrayList<>();
        int size() { return coordinates.size() / 3; }
        float maxY() {
            float result = -Float.MAX_VALUE;
            for (int i = 1; i < coordinates.size(); i += 3) result = Math.max(result, coordinates.get(i));
            return result;
        }
        boolean closeTo(VertexCloud other) {
            if (coordinates.size() != other.coordinates.size()) return false;
            for (int i = 0; i < coordinates.size(); i++) if (Math.abs(coordinates.get(i) - other.coordinates.get(i)) > EPSILON) return false;
            return true;
        }
        boolean sameUvSurface(VertexCloud other) {
            if (Math.abs(uvArea() - other.uvArea()) > EPSILON) return false;
            float[] bounds = uvBounds(), otherBounds = other.uvBounds();
            for (int i = 0; i < bounds.length; i++) if (Math.abs(bounds[i] - otherBounds[i]) > EPSILON) return false;
            return true;
        }
        private double uvArea() {
            require(textureCoordinates.size() == size() * 2 && size() % 4 == 0,
                    "Native UV surface collection must contain four complete vertices per quad");
            double total = 0;
            for (int quad = 0; quad < textureCoordinates.size(); quad += 8) {
                double twiceArea = 0;
                for (int corner = 0; corner < 4; corner++) {
                    int current = quad + corner * 2, next = quad + ((corner + 1) % 4) * 2;
                    twiceArea += (double) textureCoordinates.get(current) * textureCoordinates.get(next + 1)
                            - (double) textureCoordinates.get(next) * textureCoordinates.get(current + 1);
                }
                total += Math.abs(twiceArea) / 2;
            }
            return total;
        }
        private float[] uvBounds() {
            require(!textureCoordinates.isEmpty(), "Native UV atlas checks require emitted geometry");
            float[] bounds = {Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY};
            for (int i = 0; i < textureCoordinates.size(); i += 2) {
                float u = textureCoordinates.get(i), v = textureCoordinates.get(i + 1);
                bounds[0] = Math.min(bounds[0], u); bounds[1] = Math.min(bounds[1], v);
                bounds[2] = Math.max(bounds[2], u); bounds[3] = Math.max(bounds[3], v);
            }
            return bounds;
        }
        @Override public VertexConsumer addVertex(float x, float y, float z) {
            require(Float.isFinite(x) && Float.isFinite(y) && Float.isFinite(z), "The native rig emitted non-finite skin vertices");
            coordinates.add(x); coordinates.add(y); coordinates.add(z); return this;
        }
        @Override public VertexConsumer setColor(int red, int green, int blue, int alpha) { return this; }
        @Override public VertexConsumer setColor(int color) { return this; }
        @Override public VertexConsumer setUv(float u, float v) {
            require(Float.isFinite(u) && Float.isFinite(v), "The animated native skin must preserve finite UVs");
            textureCoordinates.add(u); textureCoordinates.add(v); return this;
        }
        @Override public VertexConsumer setUv1(int u, int v) { return this; }
        @Override public VertexConsumer setUv2(int u, int v) { return this; }
        @Override public VertexConsumer setNormal(float x, float y, float z) {
            require(Float.isFinite(x) && Float.isFinite(y) && Float.isFinite(z), "The native rig emitted non-finite skin normals"); return this;
        }
        @Override public VertexConsumer setLineWidth(float width) { return this; }
    }
}
