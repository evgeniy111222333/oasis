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

/** A weighted, continuous skin/pants band across the knee joint. */
public final class KneeBridgeRenderer {
    private static final Map<ModelPart, Data> BRIDGES = Collections.synchronizedMap(new WeakHashMap<>());

    private KneeBridgeRenderer() {}

    public static void register(ModelPart bridge, ModelPart shin, ModelPart pants,
                                int textureX, int textureY, int pantsTextureX, int pantsTextureY) {
        if (bridge != null && shin != null && pants != null) {
            BRIDGES.put(bridge, new Data(shin, pants, textureX, textureY, pantsTextureX, pantsTextureY));
        }
    }

    public static boolean renderIfRegistered(ModelPart bridge, PoseStack stack, VertexConsumer consumer,
                                             int light, int overlay, int color) {
        Data data = BRIDGES.get(bridge);
        if (data == null) return false;
        if (bridge.visible) {
            render(data, stack.last(), consumer, light, overlay, color, false);
            if (data.pants.visible) render(data, stack.last(), consumer, light, overlay, color, true);
        }
        return true;
    }

    private static void render(Data data, PoseStack.Pose pose, VertexConsumer consumer,
                               int light, int overlay, int color, boolean outer) {
        float growX = outer ? ArticulatedLimbLayout.PANTS_LAYER_GROW_X : 0.0f;
        float growZ = outer ? ArticulatedLimbLayout.PANTS_LAYER_GROW_Z : 0.0f;
        float minX = -2.0f - growX, maxX = 2.0f + growX;
        float minZ = -2.0f - growZ, maxZ = 2.0f + growZ;
        int tx = outer ? data.pantsTextureX : data.textureX;
        int ty = outer ? data.pantsTextureY : data.textureY;
        int rings = ArticulatedLimbLayout.JOINT_SKINNING_RINGS;
        Vector3f[][] points = new Vector3f[rings][4];
        float[] v = new float[rings];
        for (int ring = 0; ring < rings; ring++) {
            float t = (float) ring / (rings - 1);
            float weight = ArticulatedLimbLayout.jointSkinWeight(t);
            float y = lerp(ArticulatedLimbLayout.LEG_UPPER_BOUNDARY_Y,
                    ArticulatedLimbLayout.LEG_LOWER_BOUNDARY_Y, t);
            points[ring][0] = transform(data.shin, minX, y, minZ, weight);
            points[ring][1] = transform(data.shin, maxX, y, minZ, weight);
            points[ring][2] = transform(data.shin, maxX, y, maxZ, weight);
            points[ring][3] = transform(data.shin, minX, y, maxZ, weight);
            v[ring] = lerp(ty + 4.0f + ArticulatedLimbLayout.legUpperHeight(),
                    ty + ArticulatedLimbLayout.LOWER_SEGMENT_TEXTURE_ROW_OFFSET + 4.0f, t) / 64.0f;
        }
        float[][] us = {{tx + 4, tx + 8}, {tx + 8, tx + 12}, {tx + 12, tx + 16}, {tx, tx + 4}};
        int[][] corners = {{0,1},{1,2},{2,3},{3,0}};
        for (int ring = 0; ring < rings - 1; ring++) for (int side = 0; side < 4; side++) {
            Vector3f a = points[ring][corners[side][0]], b = points[ring + 1][corners[side][0]];
            Vector3f c = points[ring + 1][corners[side][1]], d = points[ring][corners[side][1]];
            Vector3f normal = new Vector3f(b).sub(a).cross(new Vector3f(d).sub(a)).normalize();
            emit(consumer, pose, a, us[side][0] / 64f, v[ring], light, overlay, color, normal);
            emit(consumer, pose, b, us[side][0] / 64f, v[ring + 1], light, overlay, color, normal);
            emit(consumer, pose, c, us[side][1] / 64f, v[ring + 1], light, overlay, color, normal);
            emit(consumer, pose, d, us[side][1] / 64f, v[ring], light, overlay, color, normal);
        }
    }

    private static Vector3f transform(ModelPart shin, float x, float y, float z, float weight) {
        Vector3f result = new Vector3f(x - shin.x, y - shin.y, z - shin.z);
        new Quaternionf().rotationZYX(shin.zRot * weight, shin.yRot * weight, shin.xRot * weight).transform(result);
        return result.add(shin.x, shin.y, shin.z);
    }
    private static void emit(VertexConsumer consumer, PoseStack.Pose pose, Vector3f point, float u, float v,
                             int light, int overlay, int color, Vector3f normal) {
        consumer.addVertex(pose, point.x / 16f, point.y / 16f, point.z / 16f).setColor(color).setUv(u, v)
                .setOverlay(overlay).setLight(light).setNormal(pose, normal.x, normal.y, normal.z);
    }
    private static float lerp(float from, float to, float amount) { return from + (to - from) * amount; }
    private record Data(ModelPart shin, ModelPart pants, int textureX, int textureY, int pantsTextureX, int pantsTextureY) {}
}
