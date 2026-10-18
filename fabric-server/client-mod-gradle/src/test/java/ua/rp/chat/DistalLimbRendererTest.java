package ua.rp.chat;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.core.Direction;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import ua.rp.chat.client.render.DistalLimbRenderer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

/** Standalone integration checks using the actual skin cubes and vertex consumer contract. */
public final class DistalLimbRendererTest {
    private static final int LIGHT = 0x00A000E0;
    private static final int OVERLAY = 0x00030007;
    private static final int COLOR = 0xB0A09080;
    private static final float EPSILON = 0.00001f;

    public static void main(String[] args) {
        verifyWristAndOuterLayer(false);
        verifyWristAndOuterLayer(true);
        verifyAnkleAndToe();
        verifyFullArmorChain();
        verifyGeneralMapper();
        verifyUnregisteredAndHiddenParts();
        System.out.println("DistalLimbRendererTest: independent wrist/ankle/toe skin, reset, UVs, caps and normals passed");
    }

    private static void verifyWristAndOuterLayer(boolean mirroredSlimArm) {
        ModelPart hand = marker(0.0f, 4.0f, 0.0f);
        ModelPart.Cube skin = cube(32, 48, mirroredSlimArm, false);
        ModelPart.Cube sleeve = cube(48, 48, mirroredSlimArm, true);
        ModelPart outer = new ModelPart(List.of(sleeve), Map.of());
        ModelPart forearm = new ModelPart(List.of(skin), Map.of("hand", hand, "sleeve", outer));

        // Mimic end-cap remapping performed before registration; no atlas assumptions are allowed.
        ModelPart.Polygon bottom = Arrays.stream(skin.polygons)
                .filter(polygon -> polygon.normal().y() > 0.5f).findFirst().orElseThrow();
        for (int i = 0; i < bottom.vertices().length; i++) {
            ModelPart.Vertex vertex = bottom.vertices()[i];
            bottom.vertices()[i] = vertex.remap(vertex.u(), vertex.v() - 2.0f / 64.0f);
        }
        List<ModelPart.Vertex[]> originalVertices = snapshot(skin, sleeve);
        DistalLimbRenderer.register(forearm, hand, null);
        DistalLimbRenderer.register(outer, hand, null);

        PoseStack stack = incomingLowerSegmentPose();
        Capture stock = compileStock(skin, stack.last());
        Capture neutral = render(forearm, stack.last());
        assertSameVertices("neutral forearm is the original skin, with children rendered separately", stock, neutral);
        assertSameVertices("neutral sleeve retains its own atlas and removed caps",
                compileStock(sleeve, stack.last()), render(outer, stack.last()));

        hand.setRotation(-0.8f, 0.0f, 0.0f);
        Capture bent = render(forearm, stack.last());
        assertTrue("wrist transition is tessellated", bent.vertices.size() > stock.vertices.size());
        assertUvArea("forearm UV area and cap count", stock, bent);
        assertAttributesAndWinding(bent);

        for (ModelPart.Polygon polygon : skin.polygons) {
            for (ModelPart.Vertex vertex : polygon.vertices()) {
                Vector3f point = new Vector3f(vertex.x(), vertex.y(), vertex.z());
                if (vertex.y() <= 3.5f) {
                    assertContains("wrist leaves the proximal forearm fixed", bent, point,
                            vertex.u(), vertex.v(), stack.last());
                } else if (vertex.y() >= 4.5f) {
                    rotateAround(point, new Vector3f(0.0f, 4.0f, 0.0f), new Quaternionf().rotationX(-0.8f));
                    assertContains("hand end follows the complete wrist angle", bent, point,
                            vertex.u(), vertex.v(), stack.last());
                }
            }
        }
        ModelPart.Vertex seam = pointOnEdge(skin, true, 3.5f);
        assertContains("new seam ring stays fixed", bent,
                new Vector3f(seam.x(), seam.y(), seam.z()), seam.u(), seam.v(), stack.last());
        ModelPart.Vertex middle = pointOnEdge(skin, true, 4.0f);
        Vector3f middlePoint = new Vector3f(middle.x(), middle.y(), middle.z());
        rotateAround(middlePoint, new Vector3f(0.0f, 4.0f, 0.0f), new Quaternionf().rotationX(-0.4f));
        assertContains("middle wrist ring smoothly blends half the angle", bent,
                middlePoint, middle.u(), middle.v(), stack.last());

        Capture bentSleeve = render(outer, stack.last());
        assertAttributesAndWinding(bentSleeve);
        assertUvArea("outer layer keeps its own four uncapped faces", compileStock(sleeve, stack.last()), bentSleeve);
        ModelPart.Vertex outerCorner = Arrays.stream(sleeve.polygons[0].vertices())
                .filter(vertex -> vertex.y() >= 4.5f).findFirst().orElseThrow();
        Vector3f outerPoint = new Vector3f(outerCorner.x(), outerCorner.y(), outerCorner.z());
        rotateAround(outerPoint, new Vector3f(0.0f, 4.0f, 0.0f), new Quaternionf().rotationX(-0.8f));
        assertContains("sleeve uses the same wrist bone with its original clearance", bentSleeve,
                outerPoint, outerCorner.u(), outerCorner.v(), stack.last());

        hand.setRotation(-0.37f, 0.22f, -0.18f);
        Capture threeAxes = render(forearm, stack.last());
        assertSameUvs("UVs are independent of all wrist angles", bent, threeAxes);
        ModelPart.Vertex corner = bottom.vertices()[0];
        Vector3f threeAxisPoint = new Vector3f(corner.x(), corner.y(), corner.z());
        rotateAround(threeAxisPoint, new Vector3f(0.0f, 4.0f, 0.0f),
                new Quaternionf().rotationZYX(hand.zRot, hand.yRot, hand.xRot));
        assertContains("native ZYX wrist rotation follows the existing forearm pose once", threeAxes,
                threeAxisPoint, corner.u(), corner.v(), stack.last());

        hand.setRotation(0.0f, 0.0f, 0.0f);
        assertSameVertices("reset restores the exact neutral forearm", stock, render(forearm, stack.last()));
        assertSameVertices("reset restores the exact neutral sleeve",
                compileStock(sleeve, stack.last()), render(outer, stack.last()));
        assertCubeUnchanged(originalVertices, skin, sleeve);
    }

    private static void verifyAnkleAndToe() {
        ModelPart toe = marker(0.0f, 1.0f, -1.0f);
        ModelPart ankle = new ModelPart(List.of(), Map.of("toe", toe));
        ankle.setPos(0.0f, 4.0f, 0.0f);
        ModelPart.Cube skin = cube(0, 16, false, false);
        ModelPart shin = new ModelPart(List.of(skin), Map.of("ankle", ankle));
        List<ModelPart.Vertex[]> originalVertices = snapshot(skin);
        DistalLimbRenderer.register(shin, ankle, toe);
        PoseStack stack = incomingLowerSegmentPose();
        Capture stock = compileStock(skin, stack.last());
        toe.xRot = -0.5f;
        Capture toeOnly = render(shin, stack.last());
        assertAttributesAndWinding(toeOnly);
        assertUvArea("toe tessellation preserves foot atlas area", stock, toeOnly);
        for (ModelPart.Polygon polygon : skin.polygons) {
            for (ModelPart.Vertex vertex : polygon.vertices()) {
                Vector3f expected = new Vector3f(vertex.x(), vertex.y(), vertex.z());
                if (vertex.y() >= 4.5f && vertex.z() < -1.5f) {
                    rotateAround(expected, new Vector3f(0.0f, 5.0f, -1.0f), new Quaternionf().rotationX(-0.5f));
                    assertContains("front of foot responds to independent toe rotation", toeOnly,
                            expected, vertex.u(), vertex.v(), stack.last());
                } else if (vertex.z() >= -0.5f || vertex.y() <= 3.5f) {
                    assertContains("toe leaves the heel and proximal shin fixed", toeOnly,
                            expected, vertex.u(), vertex.v(), stack.last());
                }
            }
        }
        ModelPart.Vertex toeMiddle = pointOnBottomEdge(skin, -1.0f);
        Vector3f middle = new Vector3f(toeMiddle.x(), toeMiddle.y(), toeMiddle.z());
        rotateAround(middle, new Vector3f(0.0f, 5.0f, -1.0f), new Quaternionf().rotationX(-0.25f));
        assertContains("front transition uses half the toe angle at its hinge plane", toeOnly,
                middle, toeMiddle.u(), toeMiddle.v(), stack.last());

        ankle.setRotation(0.3f, -0.12f, 0.08f);
        Capture combined = render(shin, stack.last());
        assertSameUvs("ankle and toe share stable skin coordinates", toeOnly, combined);
        assertAttributesAndWinding(combined);
        ModelPart.Vertex front = Arrays.stream(skin.polygons)
                .flatMap(polygon -> Arrays.stream(polygon.vertices()))
                .filter(vertex -> vertex.y() >= 4.5f && vertex.z() <= -1.5f).findFirst().orElseThrow();
        Vector3f composite = new Vector3f(front.x(), front.y(), front.z());
        rotateAround(composite, new Vector3f(0.0f, 5.0f, -1.0f), new Quaternionf().rotationX(-0.5f));
        rotateAround(composite, new Vector3f(0.0f, 4.0f, 0.0f),
                new Quaternionf().rotationZYX(ankle.zRot, ankle.yRot, ankle.xRot));
        assertContains("toe inherits the ankle's rotated local basis", combined,
                composite, front.u(), front.v(), stack.last());
        assertTrue("renderer does not alter marker rotations", toe.xRot == -0.5f && ankle.xRot == 0.3f);

        toe.setRotation(0.0f, 0.0f, 0.0f);
        ankle.setRotation(0.0f, 0.0f, 0.0f);
        assertSameVertices("ankle and toe reset restores the original foot", stock, render(shin, stack.last()));
        assertCubeUnchanged(originalVertices, skin);
    }

    private static void verifyUnregisteredAndHiddenParts() {
        ModelPart part = new ModelPart(List.of(cube(32, 48, false, false)), Map.of());
        Capture consumer = new Capture();
        PoseStack.Pose pose = new PoseStack().last();
        assertTrue("unregistered parts keep vanilla compile", !DistalLimbRenderer.compileIfRegistered(
                part, pose, consumer, LIGHT, OVERLAY, COLOR));
        assertTrue("unregistered renderer emits nothing", consumer.vertices.isEmpty());
        DistalLimbRenderer.register(part, marker(0.0f, 4.0f, 0.0f), null);
        part.visible = false;
        assertTrue("hidden registered parts emit nothing", render(part, pose).vertices.isEmpty());
        part.visible = true;
        part.skipDraw = true;
        assertTrue("skipDraw registered parts emit nothing", render(part, pose).vertices.isEmpty());
    }

    private static void verifyFullArmorChain() {
        ModelPart.Cube cube = new ModelPart.Cube(0, 16, -2, 0, -2, 4, 12, 4,
                0.5f, 0, 0.5f, false, 64, 64, EnumSet.allOf(Direction.class));
        ModelPart armor = new ModelPart(List.of(cube), Map.of());
        ModelPart knee = marker(0, 6, 0);
        ModelPart ankle = marker(0, 4, 0);
        ModelPart toe = marker(0, 1, -1);
        knee.xRot = 0.7f;
        ankle.setRotation(-0.25f, 0.12f, -0.06f);
        toe.xRot = -0.2f;
        DistalLimbRenderer.updateChain(armor, knee, ankle, toe);
        PoseStack.Pose pose = incomingLowerSegmentPose().last();
        Capture combined = render(armor, pose);
        Capture stock = compileStock(cube, pose);
        assertUvArea("full armor chain retains original caps and skin atlas", stock, combined);
        assertAttributesAndWinding(combined);
        ModelPart.Vertex front = Arrays.stream(cube.polygons)
                .flatMap(polygon -> Arrays.stream(polygon.vertices()))
                .filter(vertex -> vertex.y() > 10.5f && vertex.z() < -1.5f).findFirst().orElseThrow();
        Vector3f expected = new Vector3f(front.x(), front.y(), front.z());
        rotateAround(expected, new Vector3f(0, 11, -1), new Quaternionf().rotationX(-0.2f));
        rotateAround(expected, new Vector3f(0, 10, 0),
                new Quaternionf().rotationZYX(ankle.zRot, ankle.yRot, ankle.xRot));
        rotateAround(expected, new Vector3f(0, 6, 0), new Quaternionf().rotationX(0.7f));
        assertContains("full armor bends through knee, ankle and toe", combined,
                expected, front.u(), front.v(), pose);
        ModelPart.Vertex upper = Arrays.stream(cube.polygons)
                .flatMap(polygon -> Arrays.stream(polygon.vertices()))
                .filter(vertex -> vertex.y() < 5.5f).findFirst().orElseThrow();
        assertContains("full armor keeps the upper limb fixed", combined,
                new Vector3f(upper.x(), upper.y(), upper.z()), upper.u(), upper.v(), pose);

        knee.xRot = 0.4f;
        DistalLimbRenderer.updateChain(armor, knee, ankle, toe);
        Capture updated = render(armor, pose);
        assertSameUvs("updating armor markers retains cached UV topology", combined, updated);
        knee.setRotation(0, 0, 0);
        ankle.setRotation(0, 0, 0);
        toe.setRotation(0, 0, 0);
        assertSameVertices("neutral full armor chain uses original cube geometry", stock, render(armor, pose));
        DistalLimbRenderer.clear(armor);
        assertTrue("clear restores vanilla armor compile", !DistalLimbRenderer.compileIfRegistered(
                armor, pose, new Capture(), LIGHT, OVERLAY, COLOR));
    }

    private static void verifyGeneralMapper() {
        ModelPart.Cube cube = new ModelPart.Cube(16, 16, -4, 0, -2, 8, 12, 4,
                0.5f, 0, 0.5f, false, 64, 64, EnumSet.allOf(Direction.class));
        ModelPart armor = new ModelPart(List.of(cube), Map.of());
        PoseStack.Pose pose = incomingLowerSegmentPose().last();
        DistalLimbRenderer.updateDeformation(armor, point -> point.add(0.0f, 0.0f, 0.1f * point.y * point.y));
        Capture deformed = render(armor, pose);
        assertAttributesAndWinding(deformed);
        assertUvArea("general mapper retains armor UV area", compileStock(cube, pose), deformed);
        ModelPart.Vertex ring = pointOnEdge(cube, true, 6.0f);
        assertContains("general mapper deforms intermediate torso rings in model pixels", deformed,
                new Vector3f(ring.x(), ring.y(), ring.z() + 3.6f), ring.u(), ring.v(), pose);
        DistalLimbRenderer.updateDeformation(armor, point -> new Vector3f(point).add(0.0f, 0.0f, -0.05f * point.y * point.y));
        Capture updated = render(armor, pose);
        assertSameUvs("general mapper changes reuse the skin topology", deformed, updated);
        assertContains("mapper may return a fresh vector", updated,
                new Vector3f(ring.x(), ring.y(), ring.z() - 1.8f), ring.u(), ring.v(), pose);
        DistalLimbRenderer.clear(armor);
    }

    private static ModelPart.Cube cube(int texX, int texY, boolean slim, boolean outer) {
        EnumSet<Direction> faces = EnumSet.allOf(Direction.class);
        faces.remove(Direction.DOWN);
        if (outer) {
            faces.remove(Direction.UP);
        }
        return new ModelPart.Cube(texX, texY, -2.0f, 0.0f, -2.0f, slim ? 3.0f : 4.0f, 6.0f, 4.0f,
                outer ? 0.25f : 0.0f, 0.0f, outer ? 0.25f : 0.0f, slim, 64.0f, 64.0f, faces);
    }

    private static ModelPart marker(float x, float y, float z) {
        ModelPart part = new ModelPart(List.of(), Map.of());
        part.setPos(x, y, z);
        return part;
    }

    private static PoseStack incomingLowerSegmentPose() {
        PoseStack stack = new PoseStack();
        stack.translate(0.27f, -0.31f, 0.16f);
        stack.mulPose(new Quaternionf().rotationZYX(0.13f, -0.21f, 0.42f));
        return stack;
    }

    private static Capture render(ModelPart part, PoseStack.Pose pose) {
        Capture consumer = new Capture();
        assertTrue("registered segment is handled", DistalLimbRenderer.compileIfRegistered(
                part, pose, consumer, LIGHT, OVERLAY, COLOR));
        return consumer;
    }

    private static Capture compileStock(ModelPart.Cube cube, PoseStack.Pose pose) {
        Capture consumer = new Capture();
        cube.compile(pose, consumer, LIGHT, OVERLAY, COLOR);
        return consumer;
    }

    private static void rotateAround(Vector3f point, Vector3f pivot, Quaternionf rotation) {
        point.sub(pivot);
        rotation.transform(point);
        point.add(pivot);
    }

    private static ModelPart.Vertex pointOnEdge(ModelPart.Cube cube, boolean yAxis, float plane) {
        for (ModelPart.Polygon polygon : cube.polygons) {
            for (int i = 0; i < 4; i++) {
                ModelPart.Vertex a = polygon.vertices()[i];
                ModelPart.Vertex b = polygon.vertices()[(i + 1) % 4];
                float from = yAxis ? a.y() : a.z();
                float to = yAxis ? b.y() : b.z();
                if (plane > Math.min(from, to) && plane < Math.max(from, to)) {
                    float t = (plane - from) / (to - from);
                    return new ModelPart.Vertex(a.x() + (b.x() - a.x()) * t,
                            a.y() + (b.y() - a.y()) * t, a.z() + (b.z() - a.z()) * t,
                            a.u() + (b.u() - a.u()) * t, a.v() + (b.v() - a.v()) * t);
                }
            }
        }
        throw new AssertionError("No cube edge crosses " + plane);
    }

    private static ModelPart.Vertex pointOnBottomEdge(ModelPart.Cube cube, float z) {
        ModelPart.Polygon bottom = Arrays.stream(cube.polygons)
                .filter(polygon -> polygon.normal().y() > 0.5f).findFirst().orElseThrow();
        for (int i = 0; i < 4; i++) {
            ModelPart.Vertex a = bottom.vertices()[i];
            ModelPart.Vertex b = bottom.vertices()[(i + 1) % 4];
            if (z > Math.min(a.z(), b.z()) && z < Math.max(a.z(), b.z())) {
                float t = (z - a.z()) / (b.z() - a.z());
                return new ModelPart.Vertex(a.x() + (b.x() - a.x()) * t,
                        a.y(), z, a.u() + (b.u() - a.u()) * t, a.v() + (b.v() - a.v()) * t);
            }
        }
        throw new AssertionError("No bottom cap edge crosses " + z);
    }

    private static void assertContains(
            String name, Capture capture, Vector3f modelPoint, float u, float v, PoseStack.Pose pose) {
        Vector3f worldPoint = pose.pose().transformPosition(new Vector3f(modelPoint).div(16.0f));
        boolean found = capture.vertices.stream().anyMatch(vertex ->
                Math.abs(vertex.x - worldPoint.x) < EPSILON
                        && Math.abs(vertex.y - worldPoint.y) < EPSILON
                        && Math.abs(vertex.z - worldPoint.z) < EPSILON
                        && Math.abs(vertex.u - u) < EPSILON && Math.abs(vertex.v - v) < EPSILON);
        assertTrue(name + ": missing vertex " + modelPoint + " at UV " + u + "," + v, found);
    }

    private static void assertAttributesAndWinding(Capture capture) {
        assertTrue("consumer receives complete quads", capture.vertices.size() % 4 == 0);
        for (Vertex vertex : capture.vertices) {
            assertTrue("positions and UVs are finite", Float.isFinite(vertex.x) && Float.isFinite(vertex.y)
                    && Float.isFinite(vertex.z) && Float.isFinite(vertex.u) && Float.isFinite(vertex.v));
            float length = new Vector3f(vertex.nx, vertex.ny, vertex.nz).length();
            assertTrue("normals stay finite and unit length", Float.isFinite(length) && Math.abs(length - 1.0f) < 0.001f);
            assertTrue("light, overlay and color survive deformation",
                    vertex.light == LIGHT && vertex.overlay == OVERLAY && vertex.color == COLOR);
        }
        for (int i = 0; i < capture.vertices.size(); i += 4) {
            Vertex a = capture.vertices.get(i);
            Vertex b = capture.vertices.get(i + 1);
            Vertex d = capture.vertices.get(i + 3);
            Vector3f cross = new Vector3f(b.x - a.x, b.y - a.y, b.z - a.z)
                    .cross(new Vector3f(d.x - a.x, d.y - a.y, d.z - a.z));
            assertTrue("quad winding agrees with its outward normal", cross.dot(a.nx, a.ny, a.nz) > 0.0f);
        }
    }

    private static void assertSameVertices(String name, Capture expected, Capture actual) {
        assertTrue(name + " vertex count", expected.vertices.size() == actual.vertices.size());
        for (int i = 0; i < expected.vertices.size(); i++) {
            Vertex a = expected.vertices.get(i);
            Vertex b = actual.vertices.get(i);
            assertTrue(name + " vertex " + i, a.sameAs(b));
        }
    }

    private static void assertSameUvs(String name, Capture a, Capture b) {
        assertTrue(name + " vertex count", a.vertices.size() == b.vertices.size());
        for (int i = 0; i < a.vertices.size(); i++) {
            assertTrue(name, Math.abs(a.vertices.get(i).u - b.vertices.get(i).u) < EPSILON
                    && Math.abs(a.vertices.get(i).v - b.vertices.get(i).v) < EPSILON);
        }
    }

    private static void assertUvArea(String name, Capture a, Capture b) {
        assertTrue(name, Math.abs(uvArea(a) - uvArea(b)) < EPSILON);
    }

    private static double uvArea(Capture capture) {
        double total = 0.0;
        for (int i = 0; i < capture.vertices.size(); i += 4) {
            double area = 0.0;
            for (int j = 0; j < 4; j++) {
                Vertex a = capture.vertices.get(i + j);
                Vertex b = capture.vertices.get(i + (j + 1) % 4);
                area += (double) a.u * b.v - (double) b.u * a.v;
            }
            total += Math.abs(area) * 0.5;
        }
        return total;
    }

    private static List<ModelPart.Vertex[]> snapshot(ModelPart.Cube... cubes) {
        List<ModelPart.Vertex[]> result = new ArrayList<>();
        for (ModelPart.Cube cube : cubes) {
            for (ModelPart.Polygon polygon : cube.polygons) {
                result.add(polygon.vertices().clone());
            }
        }
        return result;
    }

    private static void assertCubeUnchanged(List<ModelPart.Vertex[]> expected, ModelPart.Cube... cubes) {
        List<ModelPart.Vertex[]> actual = snapshot(cubes);
        assertTrue("registration does not add or remove original polygons", expected.size() == actual.size());
        for (int i = 0; i < expected.size(); i++) {
            assertTrue("original skin vertices and UVs are untouched", Arrays.equals(expected.get(i), actual.get(i)));
        }
    }

    private static void assertTrue(String name, boolean condition) {
        if (!condition) {
            throw new AssertionError(name);
        }
    }

    private static final class Vertex {
        float x, y, z, u, v, nx, ny, nz;
        int color, light, overlay;

        boolean sameAs(Vertex other) {
            return x == other.x && y == other.y && z == other.z && u == other.u && v == other.v
                    && nx == other.nx && ny == other.ny && nz == other.nz
                    && color == other.color && light == other.light && overlay == other.overlay;
        }
    }

    private static final class Capture implements VertexConsumer {
        private final List<Vertex> vertices = new ArrayList<>();
        private Vertex current;

        @Override public VertexConsumer addVertex(float x, float y, float z) {
            current = new Vertex();
            current.x = x;
            current.y = y;
            current.z = z;
            vertices.add(current);
            return this;
        }
        @Override public VertexConsumer setColor(int r, int g, int b, int a) {
            current.color = (a << 24) | (r << 16) | (g << 8) | b;
            return this;
        }
        @Override public VertexConsumer setColor(int color) { current.color = color; return this; }
        @Override public VertexConsumer setUv(float u, float v) { current.u = u; current.v = v; return this; }
        @Override public VertexConsumer setUv1(int u, int v) { current.overlay = u | (v << 16); return this; }
        @Override public VertexConsumer setUv2(int u, int v) { current.light = u | (v << 16); return this; }
        @Override public VertexConsumer setNormal(float x, float y, float z) {
            current.nx = x;
            current.ny = y;
            current.nz = z;
            return this;
        }
        @Override public VertexConsumer setLineWidth(float width) { return this; }
    }
}
