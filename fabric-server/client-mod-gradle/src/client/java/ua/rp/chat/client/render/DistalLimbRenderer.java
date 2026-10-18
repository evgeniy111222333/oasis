package ua.rp.chat.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.geom.ModelPart;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import ua.rp.chat.client.mixin.ModelPartAccessor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.WeakHashMap;
import java.util.function.UnaryOperator;

/** Skins the end of a lower limb without moving its proximal seam or replacing its skin atlas. */
public final class DistalLimbRenderer {
    private static final float BLEND_START_Y = 3.5f;
    private static final float BLEND_END_Y = 4.5f;
    private static final Map<ModelPart, DistalData> SEGMENTS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private DistalLimbRenderer() {
    }

    /** Register after cap removal and UV remapping; an outer layer uses the same bone markers. */
    public static void register(ModelPart segment, ModelPart distal, ModelPart toe) {
        if (segment == null || distal == null) {
            return;
        }
        cache(segment, null, distal, toe, null);
    }

    /** Skin an original full armor limb using a lower-segment joint and its distal markers. */
    public static void updateChain(ModelPart segment, ModelPart joint, ModelPart distal, ModelPart toe) {
        if (segment == null || joint == null || distal == null) {
            return;
        }
        DistalData current = SEGMENTS.get(segment);
        float toeZ = toe == null ? 0.0f : joint.z + distal.z + toe.z;
        if (current != null && current.mapper == null && current.joint == joint
                && current.distal == distal && current.toe == toe
                && current.cutOffsetY == joint.y && current.toeCutZ == toeZ) {
            return;
        }
        cache(segment, joint, distal, toe, null);
    }

    /** A mapper receives model pixels; geometry is cached with a horizontal ring every two pixels. */
    public static void updateDeformation(ModelPart segment, UnaryOperator<Vector3f> mapper) {
        if (segment == null) {
            return;
        }
        if (mapper == null) {
            clear(segment);
            return;
        }
        DistalData current = SEGMENTS.get(segment);
        if (current != null && current.mapper != null) {
            SEGMENTS.put(segment, new DistalData(null, null, null, mapper,
                    current.cubes, current.quads, 0.0f, 0.0f));
        } else {
            cache(segment, null, null, null, mapper);
        }
    }

    public static void clear(ModelPart segment) {
        SEGMENTS.remove(segment);
    }

    private static void cache(
            ModelPart segment, ModelPart joint, ModelPart distal, ModelPart toe,
            UnaryOperator<Vector3f> mapper) {
        List<ModelPart.Cube> cubes = cubesOf(segment);
        List<Quad> quads = new ArrayList<>();
        float offsetY = joint == null ? 0.0f : joint.y;
        float toeZ = toe == null ? 0.0f : (joint == null ? 0.0f : joint.z) + distal.z + toe.z;
        for (ModelPart.Cube cube : cubes) {
            for (ModelPart.Polygon polygon : cube.polygons) {
                splitPolygon(polygon, joint != null, offsetY, toe != null, toeZ, mapper != null, quads);
            }
        }
        SEGMENTS.put(segment, new DistalData(
                joint, distal, toe, mapper, List.copyOf(cubes), List.copyOf(quads), offsetY, toeZ));
    }

    /** Incoming pose already contains the lower segment transform; children still render normally. */
    public static boolean compileIfRegistered(
            ModelPart segment, PoseStack.Pose pose, VertexConsumer consumer,
            int light, int overlay, int color) {
        DistalData data = SEGMENTS.get(segment);
        if (data == null) {
            return false;
        }
        if (!segment.visible || segment.skipDraw) {
            return true;
        }
        if (data.mapper == null && (data.joint == null || neutral(data.joint))
                && neutral(data.distal) && (data.toe == null || neutral(data.toe))) {
            // Apart from avoiding allocations, this preserves vanilla geometry exactly on reset.
            for (ModelPart.Cube cube : data.cubes) {
                cube.compile(pose, consumer, light, overlay, color);
            }
            return true;
        }

        SkinPose skinPose = data.mapper == null ? new SkinPose(data.joint, data.distal, data.toe) : null;
        Vector3f[] points = {new Vector3f(), new Vector3f(), new Vector3f(), new Vector3f()};
        Vector3f normal = new Vector3f();
        Vector3f edge = new Vector3f();
        for (Quad quad : data.quads) {
            for (int i = 0; i < 4; i++) {
                if (skinPose != null) {
                    skinPose.transform(quad.vertices[i], points[i]);
                } else {
                    SkinVertex vertex = quad.vertices[i];
                    points[i].set(vertex.x, vertex.y, vertex.z);
                    points[i].set(data.mapper.apply(points[i]));
                }
            }
            normal.set(points[1]).sub(points[0]);
            edge.set(points[3]).sub(points[0]);
            normal.cross(edge).mul(quad.normalSign);
            if (normal.lengthSquared() > 1.0e-12f) {
                normal.normalize();
            } else {
                // An extreme bend can collapse a transition cell; never emit an invalid normal.
                normal.set(quad.normal);
                if (skinPose != null) {
                    skinPose.rotateNormal(quad.vertices[0], normal);
                }
                normal.normalize();
            }
            for (int i = 0; i < 4; i++) {
                SkinVertex source = quad.vertices[i];
                Vector3f point = points[i];
                consumer.addVertex(pose, point.x / 16.0f, point.y / 16.0f, point.z / 16.0f)
                        .setColor(color)
                        .setUv(source.u, source.v)
                        .setOverlay(overlay)
                        .setLight(light)
                        .setNormal(pose, normal.x, normal.y, normal.z);
            }
        }
        return true;
    }

    private static List<ModelPart.Cube> cubesOf(ModelPart segment) {
        if ((Object) segment instanceof ModelPartAccessor accessor) {
            return accessor.getCubes();
        }
        // The public visitor also supports standalone verification without a Mixin bootstrap.
        List<ModelPart.Cube> cubes = new ArrayList<>();
        segment.visit(new PoseStack(), (pose, path, index, cube) -> {
            if (path.isEmpty()) {
                cubes.add(cube);
            }
        });
        return cubes;
    }

    private static void splitPolygon(
            ModelPart.Polygon polygon, boolean hasJoint, float offsetY,
            boolean hasToe, float toeZ, boolean general, List<Quad> output) {
        ModelPart.Vertex[] vertices = polygon.vertices();
        SkinVertex a = SkinVertex.of(vertices[0]);
        SkinVertex b = SkinVertex.of(vertices[1]);
        SkinVertex c = SkinVertex.of(vertices[2]);
        SkinVertex d = SkinVertex.of(vertices[3]);
        TreeSet<Float> along = cuts(a, b, hasJoint, offsetY, hasToe, toeZ, general);
        TreeSet<Float> across = cuts(a, d, hasJoint, offsetY, hasToe, toeZ, general);
        Float[] s = along.toArray(Float[]::new);
        Float[] t = across.toArray(Float[]::new);
        Vector3fc normal = polygon.normal();
        Vector3f cross = new Vector3f(b.x - a.x, b.y - a.y, b.z - a.z)
                .cross(new Vector3f(d.x - a.x, d.y - a.y, d.z - a.z));
        float sign = cross.dot(normal) < 0.0f ? -1.0f : 1.0f;
        for (int i = 0; i + 1 < s.length; i++) {
            for (int j = 0; j + 1 < t.length; j++) {
                output.add(new Quad(new SkinVertex[]{
                        interpolate(a, b, c, d, s[i], t[j]),
                        interpolate(a, b, c, d, s[i + 1], t[j]),
                        interpolate(a, b, c, d, s[i + 1], t[j + 1]),
                        interpolate(a, b, c, d, s[i], t[j + 1])},
                        new Vector3f(normal), sign));
            }
        }
    }

    private static TreeSet<Float> cuts(
            SkinVertex a, SkinVertex b, boolean hasJoint, float offsetY,
            boolean hasToe, float toeZ, boolean general) {
        TreeSet<Float> result = new TreeSet<>();
        result.add(0.0f);
        result.add(1.0f);
        if (general) {
            float min = Math.min(a.y, b.y);
            float max = Math.max(a.y, b.y);
            for (float y = (float) Math.ceil(min / 2.0f) * 2.0f; y < max; y += 2.0f) {
                addCut(result, a.y, b.y, y);
            }
            return result;
        }
        if (hasJoint) {
            addCut(result, a.y, b.y, offsetY - 0.5f);
            addCut(result, a.y, b.y, offsetY);
            addCut(result, a.y, b.y, offsetY + 0.5f);
        }
        addCut(result, a.y, b.y, offsetY + BLEND_START_Y);
        addCut(result, a.y, b.y, offsetY + 4.0f);
        addCut(result, a.y, b.y, offsetY + BLEND_END_Y);
        if (hasToe) {
            addCut(result, a.z, b.z, toeZ - 0.5f);
            addCut(result, a.z, b.z, toeZ);
            addCut(result, a.z, b.z, toeZ + 0.5f);
        }
        return result;
    }

    private static void addCut(TreeSet<Float> cuts, float from, float to, float plane) {
        if (from == to) {
            return;
        }
        float amount = (plane - from) / (to - from);
        if (amount > 0.0f && amount < 1.0f) {
            cuts.add(amount);
        }
    }

    private static SkinVertex interpolate(
            SkinVertex a, SkinVertex b, SkinVertex c, SkinVertex d, float s, float t) {
        return SkinVertex.lerp(SkinVertex.lerp(a, b, s), SkinVertex.lerp(d, c, s), t);
    }

    private static boolean neutral(ModelPart part) {
        return part.xRot == 0.0f && part.yRot == 0.0f && part.zRot == 0.0f;
    }

    private static float weight(float value) {
        float t = Math.max(0.0f, Math.min(1.0f, value));
        return t * t * (3.0f - 2.0f * t);
    }

    private record SkinVertex(float x, float y, float z, float u, float v) {
        private static SkinVertex of(ModelPart.Vertex vertex) {
            return new SkinVertex(vertex.x(), vertex.y(), vertex.z(), vertex.u(), vertex.v());
        }

        private static SkinVertex lerp(SkinVertex a, SkinVertex b, float t) {
            return new SkinVertex(
                    a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, a.z + (b.z - a.z) * t,
                    a.u + (b.u - a.u) * t, a.v + (b.v - a.v) * t);
        }
    }

    private record Quad(SkinVertex[] vertices, Vector3f normal, float normalSign) {
    }

    private record DistalData(
            ModelPart joint, ModelPart distal, ModelPart toe, UnaryOperator<Vector3f> mapper,
            List<ModelPart.Cube> cubes, List<Quad> quads, float cutOffsetY, float toeCutZ) {
    }

    private static final class SkinPose {
        private final Vector3f jointPivot;
        private final Vector3f distalPivot;
        private final Vector3f toePivot;
        private final Quaternionf jointRotation;
        private final Quaternionf distalRotation;
        private final Quaternionf toeRotation;
        private final Quaternionf weightedRotation = new Quaternionf();

        private SkinPose(ModelPart joint, ModelPart distal, ModelPart toe) {
            jointPivot = joint == null ? null : new Vector3f(joint.x, joint.y, joint.z);
            jointRotation = joint == null ? null
                    : new Quaternionf().rotationZYX(joint.zRot, joint.yRot, joint.xRot);
            distalPivot = new Vector3f(distal.x, distal.y, distal.z);
            if (jointPivot != null) {
                distalPivot.add(jointPivot);
            }
            distalRotation = new Quaternionf().rotationZYX(distal.zRot, distal.yRot, distal.xRot);
            toePivot = toe == null ? null : new Vector3f(distalPivot).add(toe.x, toe.y, toe.z);
            toeRotation = toe == null ? null
                    : new Quaternionf().rotationZYX(toe.zRot, toe.yRot, toe.xRot);
        }

        private void transform(SkinVertex vertex, Vector3f point) {
            point.set(vertex.x, vertex.y, vertex.z);
            float distalWeight = distalWeight(vertex.y);
            // A toe is a child of the ankle: rotate in its local basis before the parent rotation.
            if (toeRotation != null) {
                float toeWeight = distalWeight * weight(toePivot.z + 0.5f - vertex.z);
                rotateAround(point, toePivot, toeRotation, toeWeight);
            }
            rotateAround(point, distalPivot, distalRotation, distalWeight);
            if (jointRotation != null) {
                rotateAround(point, jointPivot, jointRotation, weight(vertex.y - jointPivot.y + 0.5f));
            }
        }

        private void rotateAround(Vector3f point, Vector3f pivot, Quaternionf rotation, float amount) {
            if (amount == 0.0f) {
                return;
            }
            point.sub(pivot);
            weightedRotation.identity().slerp(rotation, amount).transform(point);
            point.add(pivot);
        }

        private void rotateNormal(SkinVertex vertex, Vector3f normal) {
            float distalWeight = distalWeight(vertex.y);
            if (toeRotation != null) {
                float toeWeight = distalWeight * weight(toePivot.z + 0.5f - vertex.z);
                weightedRotation.identity().slerp(toeRotation, toeWeight).transform(normal);
            }
            weightedRotation.identity().slerp(distalRotation, distalWeight).transform(normal);
            if (jointRotation != null) {
                weightedRotation.identity().slerp(jointRotation,
                        weight(vertex.y - jointPivot.y + 0.5f)).transform(normal);
            }
        }

        private float distalWeight(float y) {
            float offset = jointPivot == null ? 0.0f : jointPivot.y;
            return weight((y - offset - BLEND_START_Y) / (BLEND_END_Y - BLEND_START_Y));
        }
    }
}
