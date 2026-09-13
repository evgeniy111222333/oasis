package ua.rp.chat.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.geom.ModelPart;
import org.joml.Vector3f;
import ua.rp.chat.BreathingTorsoLayout;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Draws the base torso and jacket as one continuously deformed V-shaped mesh. */
public final class BreathingTorsoRenderer {
    private static final Map<ModelPart, TorsoData> TORSOS =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final AtomicBoolean FIRST_RENDER_LOGGED = new AtomicBoolean(false);

    private BreathingTorsoRenderer() {
    }

    public static void register(ModelPart marker, ModelPart jacketToggle) {
        if (marker != null && jacketToggle != null) {
            TORSOS.put(marker, new TorsoData(jacketToggle));
        }
    }

    public static void update(
            ModelPart marker, double phase, double intensity, double calm, boolean firstPerson) {
        TorsoData data = TORSOS.get(marker);
        if (data == null) {
            return;
        }
        data.phase = finiteOr(phase, 0.0);
        data.intensity = clamp(finiteOr(intensity, 0.0), 0.0, 1.0);
        data.calm = clamp(finiteOr(calm, 1.0), 0.0, 1.0);
        data.firstPerson = firstPerson;
    }

    public static boolean renderIfRegistered(
            ModelPart marker, PoseStack poseStack, VertexConsumer consumer,
            int light, int overlay, int color) {
        TorsoData data = TORSOS.get(marker);
        if (data == null) {
            return false;
        }
        if (!marker.visible) {
            return true;
        }

        renderLayer(data, poseStack.last(), consumer, light, overlay, color, false);
        if (data.jacketToggle.visible) {
            renderLayer(data, poseStack.last(), consumer, light, overlay, color, true);
        }
        if (FIRST_RENDER_LOGGED.compareAndSet(false, true)) {
            int quads = (BreathingTorsoLayout.ringCount() - 1) * 4 + 2;
            System.out.println("[ECLIPSE-TORSO] Continuous V-breathing mesh active: rings="
                    + BreathingTorsoLayout.ringCount() + ", quadsPerLayer=" + quads
                    + ", jacketUsesSharedVertices=true");
        }
        return true;
    }

    private static void renderLayer(
            TorsoData data, PoseStack.Pose pose, VertexConsumer consumer,
            int light, int overlay, int color, boolean outer) {
        int rings = BreathingTorsoLayout.ringCount();
        Vector3f[][] positions = new Vector3f[rings][4];
        float[] textureV = new float[rings];
        int textureX = 16;
        int textureY = outer ? 32 : 16;

        for (int ring = 0; ring < rings; ring++) {
            BreathingTorsoLayout.Bounds bounds = BreathingTorsoLayout.bounds(
                    ring, data.phase, data.intensity, data.calm, data.firstPerson, outer);
            positions[ring][0] = new Vector3f(bounds.minX(), bounds.y(), bounds.minZ());
            positions[ring][1] = new Vector3f(bounds.maxX(), bounds.y(), bounds.minZ());
            positions[ring][2] = new Vector3f(bounds.maxX(), bounds.y(), bounds.maxZ());
            positions[ring][3] = new Vector3f(bounds.minX(), bounds.y(), bounds.maxZ());
            textureV[ring] = (textureY + 4.0f + bounds.y()) / 64.0f;
        }

        int[][] corners = {{0, 1}, {1, 2}, {2, 3}, {3, 0}};
        float[][] uPixels = {
                {textureX + 4.0f, textureX + 12.0f},
                {textureX + 12.0f, textureX + 16.0f},
                {textureX + 16.0f, textureX + 24.0f},
                {textureX, textureX + 4.0f}
        };
        for (int ring = 0; ring < rings - 1; ring++) {
            for (int side = 0; side < 4; side++) {
                int a = corners[side][0];
                int b = corners[side][1];
                emitQuad(
                        consumer, pose,
                        positions[ring][a], positions[ring + 1][a],
                        positions[ring + 1][b], positions[ring][b],
                        uPixels[side][0] / 64.0f, textureV[ring],
                        uPixels[side][0] / 64.0f, textureV[ring + 1],
                        uPixels[side][1] / 64.0f, textureV[ring + 1],
                        uPixels[side][1] / 64.0f, textureV[ring],
                        light, overlay, color);
            }
        }

        Vector3f[] top = positions[0];
        emitQuad(consumer, pose, top[0], top[1], top[2], top[3],
                (textureX + 4.0f) / 64.0f, textureY / 64.0f,
                (textureX + 12.0f) / 64.0f, textureY / 64.0f,
                (textureX + 12.0f) / 64.0f, (textureY + 4.0f) / 64.0f,
                (textureX + 4.0f) / 64.0f, (textureY + 4.0f) / 64.0f,
                light, overlay, color);

        Vector3f[] bottom = positions[rings - 1];
        emitQuad(consumer, pose, bottom[0], bottom[3], bottom[2], bottom[1],
                (textureX + 12.0f) / 64.0f, textureY / 64.0f,
                (textureX + 12.0f) / 64.0f, (textureY + 4.0f) / 64.0f,
                (textureX + 20.0f) / 64.0f, (textureY + 4.0f) / 64.0f,
                (textureX + 20.0f) / 64.0f, textureY / 64.0f,
                light, overlay, color);
    }

    private static void emitQuad(
            VertexConsumer consumer, PoseStack.Pose pose,
            Vector3f p0, Vector3f p1, Vector3f p2, Vector3f p3,
            float u0, float v0, float u1, float v1,
            float u2, float v2, float u3, float v3,
            int light, int overlay, int color) {
        Vector3f normal = new Vector3f(p1).sub(p0)
                .cross(new Vector3f(p3).sub(p0)).normalize();
        emit(consumer, pose, p0, u0, v0, light, overlay, color, normal);
        emit(consumer, pose, p1, u1, v1, light, overlay, color, normal);
        emit(consumer, pose, p2, u2, v2, light, overlay, color, normal);
        emit(consumer, pose, p3, u3, v3, light, overlay, color, normal);
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

    private static double finiteOr(double value, double fallback) {
        return Double.isFinite(value) ? value : fallback;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static final class TorsoData {
        private final ModelPart jacketToggle;
        private double phase;
        private double intensity;
        private double calm = 1.0;
        private boolean firstPerson;

        private TorsoData(ModelPart jacketToggle) {
            this.jacketToggle = jacketToggle;
        }
    }
}
