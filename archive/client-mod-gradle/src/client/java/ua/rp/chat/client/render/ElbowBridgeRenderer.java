package ua.rp.chat.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.geom.ModelPart;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import ua.rp.chat.ArticulatedLimbLayout;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Draws a continuously skinned elbow band between the two rigid arm bones. */
public final class ElbowBridgeRenderer {
    private static final Map<ModelPart, BridgeData> BRIDGES =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final AtomicBoolean FIRST_RENDER_LOGGED = new AtomicBoolean(false);

    private ElbowBridgeRenderer() {
    }

    public static void register(
            ModelPart bridge, ModelPart forearm, ModelPart sleeveToggle,
            float minX, int width, int texX, int texY, int sleeveTexX, int sleeveTexY) {
        if (bridge != null && forearm != null && sleeveToggle != null) {
            BRIDGES.put(bridge, new BridgeData(
                    forearm, sleeveToggle, minX, width, texX, texY, sleeveTexX, sleeveTexY));
        }
    }

    public static boolean renderIfRegistered(
            ModelPart bridge, PoseStack poseStack, VertexConsumer consumer,
            int light, int overlay, int color) {
        BridgeData data = BRIDGES.get(bridge);
        if (data == null) {
            return false;
        }
        if (!bridge.visible) {
            return true;
        }
        renderLayer(data, poseStack.last(), consumer, light, overlay, color, false);
        if (data.sleeveToggle.visible) {
            renderLayer(data, poseStack.last(), consumer, light, overlay, color, true);
        }
        if (FIRST_RENDER_LOGGED.compareAndSet(false, true)) {
            System.out.println("[ECLIPSE-ELBOW] Continuous bridge active: rings="
                    + ArticulatedLimbLayout.JOINT_SKINNING_RINGS
                    + ", baseQuads=" + ((ArticulatedLimbLayout.JOINT_SKINNING_RINGS - 1) * 4)
                    + ", overlayUsesSameWeights=true");
        }
        return true;
    }

    private static void renderLayer(
            BridgeData data, PoseStack.Pose pose, VertexConsumer consumer,
            int light, int overlay, int color, boolean outer) {
        float grow = outer ? ArticulatedLimbLayout.OUTER_LAYER_GROW_XZ : 0.0f;
        float minX = data.minX - grow;
        float maxX = data.minX + data.width + grow;
        float minZ = -2.0f - grow;
        float maxZ = 2.0f + grow;
        int textureX = outer ? data.sleeveTexX : data.texX;
        int textureY = outer ? data.sleeveTexY : data.texY;

        int rings = ArticulatedLimbLayout.JOINT_SKINNING_RINGS;
        Vector3f[][] positions = new Vector3f[rings][4];
        float[] textureV = new float[rings];
        for (int ring = 0; ring < rings; ring++) {
            float t = (float) ring / (rings - 1);
            float weight = ArticulatedLimbLayout.jointSkinWeight(t);
            float y = lerp(ArticulatedLimbLayout.armUpperBoundaryY(),
                    ArticulatedLimbLayout.armLowerBoundaryY(), t);
            positions[ring][0] = transform(data.forearm, minX, y, minZ, weight);
            positions[ring][1] = transform(data.forearm, maxX, y, minZ, weight);
            positions[ring][2] = transform(data.forearm, maxX, y, maxZ, weight);
            positions[ring][3] = transform(data.forearm, minX, y, maxZ, weight);
            float upperV = textureY + 4.0f + ArticulatedLimbLayout.armUpperHeight();
            float lowerV = textureY + ArticulatedLimbLayout.LOWER_SEGMENT_TEXTURE_ROW_OFFSET + 4.0f;
            textureV[ring] = lerp(upperV, lowerV, t) / 64.0f;
        }

        int[][] corners = {{0, 1}, {1, 2}, {2, 3}, {3, 0}};
        float[][] uPixels = {
                {textureX + 4.0f, textureX + 4.0f + data.width},
                {textureX + 4.0f + data.width, textureX + 8.0f + data.width},
                {textureX + 8.0f + data.width, textureX + 8.0f + data.width * 2.0f},
                {textureX, textureX + 4.0f}
        };
        for (int ring = 0; ring < rings - 1; ring++) {
            for (int side = 0; side < 4; side++) {
                int a = corners[side][0];
                int b = corners[side][1];
                Vector3f p0 = positions[ring][a];
                Vector3f p1 = positions[ring + 1][a];
                Vector3f p2 = positions[ring + 1][b];
                Vector3f p3 = positions[ring][b];
                Vector3f normal = new Vector3f(p1).sub(p0)
                        .cross(new Vector3f(p3).sub(p0)).normalize();
                float u0 = uPixels[side][0] / 64.0f;
                float u1 = uPixels[side][1] / 64.0f;
                emit(consumer, pose, p0, u0, textureV[ring], light, overlay, color, normal);
                emit(consumer, pose, p1, u0, textureV[ring + 1], light, overlay, color, normal);
                emit(consumer, pose, p2, u1, textureV[ring + 1], light, overlay, color, normal);
                emit(consumer, pose, p3, u1, textureV[ring], light, overlay, color, normal);
            }
        }
    }

    private static Vector3f transform(ModelPart forearm, float x, float y, float z, float weight) {
        float pivotX = forearm.x;
        float pivotY = forearm.y;
        float pivotZ = forearm.z;
        Vector3f result = new Vector3f(x - pivotX, y - pivotY, z - pivotZ);
        new Quaternionf().rotationZYX(
                forearm.zRot * weight,
                forearm.yRot * weight,
                forearm.xRot * weight).transform(result);
        return result.add(pivotX, pivotY, pivotZ);
    }

    private static void emit(
            VertexConsumer consumer, PoseStack.Pose pose, Vector3f point,
            float u, float v, int light, int overlay, int color, Vector3f normal) {
        consumer.addVertex(pose, point.x / 16.0f, point.y / 16.0f, point.z / 16.0f)
                .setColor(color)
                .setUv(u, v)
                .setOverlay(overlay)
                .setLight(light)
                .setNormal(pose, normal.x, normal.y, normal.z);
    }

    private static float lerp(float from, float to, float amount) {
        return from + (to - from) * amount;
    }

    private record BridgeData(
            ModelPart forearm, ModelPart sleeveToggle,
            float minX, int width,
            int texX, int texY, int sleeveTexX, int sleeveTexY) {
    }
}
