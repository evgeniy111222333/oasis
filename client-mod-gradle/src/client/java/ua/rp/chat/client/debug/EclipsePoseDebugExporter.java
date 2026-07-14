package ua.rp.chat.client.debug;

import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import ua.rp.chat.client.appearance.EclipseAppearanceManager;
import ua.rp.chat.client.camera.SmartCameraManager;
import ua.rp.chat.client.heavyhammer.HeavyHammerClientState;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class EclipsePoseDebugExporter {
    private static long lastWriteMs = 0L;

    private EclipsePoseDebugExporter() {
    }

    public static void capture(PlayerModel model, AvatarRenderState state, Player player, boolean localPlayerState) {
        Minecraft client = Minecraft.getInstance();
        if (client == null || model == null || state == null || player == null || player != client.player || !localPlayerState) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastWriteMs < 100L) {
            return;
        }
        lastWriteMs = now;

        try {
            Path debugDir = client.gameDirectory.toPath().resolve("eclipse-debug");
            Files.createDirectories(debugDir);
            Files.writeString(debugDir.resolve("live-pose.json"), toJson(client, model, state, player, now), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }

    private static String toJson(Minecraft client, PlayerModel model, AvatarRenderState state, Player player, long now) {
        SmartCameraManager camera = SmartCameraManager.getInstance();
        Vec3 offset = camera.getCameraOffset(player.getYRot(), player.getXRot());
        Vec3 eyeOffset = camera.getEyeOffset(player.getYRot(), player.getXRot());
        double forward = Math.sqrt(offset.x * offset.x + offset.z * offset.z);
        double eyeForward = Math.sqrt(eyeOffset.x * eyeOffset.x + eyeOffset.z * eyeOffset.z);
        EclipseAppearanceManager.DebugSkinInfo skin = EclipseAppearanceManager.getDebugSkinInfo(player.getUUID());
        ItemStack mainHand = player.getMainHandItem();
        Identifier itemModel = mainHand.get(DataComponents.ITEM_MODEL);

        StringBuilder json = new StringBuilder(4096);
        json.append("{\n");
        prop(json, "timestamp", now).append(",\n");
        prop(json, "username", client.getUser().getName()).append(",\n");
        prop(json, "uuid", player.getUUID().toString()).append(",\n");
        prop(json, "firstPersonBody", camera.isFirstPersonBodyEnabled()).append(",\n");
        prop(json, "cameraActive", camera.isCameraMotionActive()).append(",\n");
        prop(json, "pitch", player.getXRot()).append(",\n");
        prop(json, "yaw", player.getYRot()).append(",\n");
        prop(json, "cameraForward", forward).append(",\n");
        prop(json, "cameraOffsetX", offset.x).append(",\n");
        prop(json, "cameraOffsetY", offset.y).append(",\n");
        prop(json, "cameraOffsetZ", offset.z).append(",\n");
        prop(json, "eyeForward", eyeForward).append(",\n");
        prop(json, "eyeOffsetX", eyeOffset.x).append(",\n");
        prop(json, "eyeOffsetY", eyeOffset.y).append(",\n");
        prop(json, "eyeOffsetZ", eyeOffset.z).append(",\n");
        prop(json, "torsoCull", camera.shouldCullTorso()).append(",\n");
        prop(json, "walkAnimationSpeed", state.walkAnimationSpeed).append(",\n");
        prop(json, "walkAnimationPos", state.walkAnimationPos).append(",\n");
        prop(json, "speedValue", state.speedValue).append(",\n");
        prop(json, "crouching", state.isCrouching).append(",\n");
        prop(json, "mainHandItem", mainHand.isEmpty() ? "" : mainHand.getItem().toString()).append(",\n");
        prop(json, "mainHandName", mainHand.isEmpty() ? "" : mainHand.getHoverName().getString()).append(",\n");
        prop(json, "mainHandModel", itemModel == null ? "" : itemModel.toString()).append(",\n");
        prop(json, "heavyHammerPose", HeavyHammerClientState.isHolding(mainHand)).append(",\n");
        prop(json, "skinPath", skin == null ? "" : skin.path()).append(",\n");
        prop(json, "skinHash", skin == null ? "" : skin.hash()).append(",\n");
        prop(json, "skinModel", skin == null ? "" : skin.model()).append(",\n");
        json.append("  \"parts\": {\n");
        part(json, "head", model.head).append(",\n");
        part(json, "hat", model.hat).append(",\n");
        part(json, "body", model.body).append(",\n");
        part(json, "jacket", model.jacket).append(",\n");
        part(json, "rightArm", model.rightArm).append(",\n");
        part(json, "rightSleeve", model.rightSleeve).append(",\n");
        part(json, "leftArm", model.leftArm).append(",\n");
        part(json, "leftSleeve", model.leftSleeve).append(",\n");
        part(json, "rightLeg", model.rightLeg).append(",\n");
        part(json, "rightPants", model.rightPants).append(",\n");
        part(json, "leftLeg", model.leftLeg).append(",\n");
        part(json, "leftPants", model.leftPants).append("\n");
        json.append("  }\n");
        json.append("}\n");
        return json.toString();
    }

    private static StringBuilder part(StringBuilder json, String name, ModelPart part) {
        json.append("    \"").append(name).append("\": ");
        if (part == null) {
            json.append("null");
            return json;
        }
        json.append("{");
        field(json, "x", part.x).append(",");
        field(json, "y", part.y).append(",");
        field(json, "z", part.z).append(",");
        field(json, "xRot", part.xRot).append(",");
        field(json, "yRot", part.yRot).append(",");
        field(json, "zRot", part.zRot).append(",");
        field(json, "visible", part.visible).append(",");
        json.append("\"children\":{");
        child(json, "eclipse_upper_arm", part).append(",");
        child(json, "eclipse_forearm", part).append(",");
        child(json, "eclipse_upper_sleeve", part).append(",");
        child(json, "eclipse_forearm_sleeve", part).append(",");
        child(json, "eclipse_thigh", part).append(",");
        child(json, "eclipse_shin", part).append(",");
        child(json, "eclipse_thigh_pants", part).append(",");
        child(json, "eclipse_shin_pants", part);
        json.append("}}");
        return json;
    }

    private static StringBuilder child(StringBuilder json, String name, ModelPart parent) {
        json.append("\"").append(name).append("\":");
        try {
            ModelPart child = parent.getChild(name);
            json.append("{");
            field(json, "x", child.x).append(",");
            field(json, "y", child.y).append(",");
            field(json, "z", child.z).append(",");
            field(json, "xRot", child.xRot).append(",");
            field(json, "yRot", child.yRot).append(",");
            field(json, "zRot", child.zRot).append(",");
            field(json, "visible", child.visible);
            json.append("}");
        } catch (RuntimeException ignored) {
            json.append("null");
        }
        return json;
    }

    private static StringBuilder prop(StringBuilder json, String name, String value) {
        return json.append("  \"").append(name).append("\": \"").append(escape(value)).append("\"");
    }

    private static StringBuilder prop(StringBuilder json, String name, boolean value) {
        return json.append("  \"").append(name).append("\": ").append(value);
    }

    private static StringBuilder prop(StringBuilder json, String name, long value) {
        return json.append("  \"").append(name).append("\": ").append(value);
    }

    private static StringBuilder prop(StringBuilder json, String name, double value) {
        return json.append("  \"").append(name).append("\": ").append(format(value));
    }

    private static StringBuilder field(StringBuilder json, String name, boolean value) {
        return json.append("\"").append(name).append("\":").append(value);
    }

    private static StringBuilder field(StringBuilder json, String name, float value) {
        return json.append("\"").append(name).append("\":").append(format(value));
    }

    private static String format(double value) {
        if (!Double.isFinite(value)) {
            return "0";
        }
        return String.format(java.util.Locale.ROOT, "%.5f", value);
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
