package ua.rp.chat;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.geom.ModelPart;
import ua.rp.chat.client.render.BreathingTorsoRenderer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class BreathingTorsoRendererTest {
    public static void main(String[] args) {
        ModelPart marker = new ModelPart(List.of(), Map.of());
        ModelPart jacket = new ModelPart(List.of(), Map.of());
        BreathingTorsoRenderer.register(marker, jacket);
        BreathingTorsoRenderer.update(marker, 0.34, 1.0, 1.0, false);

        CountingConsumer consumer = new CountingConsumer();
        jacket.visible = false;
        assertTrue("renderer recognizes registered torso", BreathingTorsoRenderer.renderIfRegistered(
                marker, new PoseStack(), consumer, 0x00F000F0, 0, -1));
        int layerVertices = ((BreathingTorsoLayout.ringCount() - 1) * 4 + 2) * 4;
        assertEquals("base torso vertices", layerVertices, consumer.vertices.size());
        assertTrue("base attributes are finite and normalized", consumer.finite);

        consumer.reset();
        jacket.visible = true;
        BreathingTorsoRenderer.renderIfRegistered(marker, new PoseStack(), consumer, 0x00F000F0, 0, -1);
        assertEquals("skin plus jacket vertices", layerVertices * 2, consumer.vertices.size());
        assertTrue("jacket attributes are finite and normalized", consumer.finite);
        verifyLayerSeparation(consumer.vertices, layerVertices);

        consumer.reset();
        marker.visible = false;
        assertTrue("hidden registered torso is consumed", BreathingTorsoRenderer.renderIfRegistered(
                marker, new PoseStack(), consumer, 0, 0, -1));
        assertEquals("hidden torso emits no vertices", 0, consumer.vertices.size());
        System.out.println("BreathingTorsoRendererTest: skin, jacket, UV and normal invariants passed");
    }

    private static void verifyLayerSeparation(List<Vertex> vertices, int layerVertices) {
        boolean foundClearance = false;
        for (int i = 0; i < layerVertices; i++) {
            Vertex skin = vertices.get(i);
            Vertex jacket = vertices.get(i + layerVertices);
            float radialGrowth = Math.max(Math.abs(jacket.x) - Math.abs(skin.x),
                    Math.abs(jacket.z) - Math.abs(skin.z));
            if (radialGrowth > 0.015f) { // 0.25 model pixels / 16 blocks = 0.015625
                foundClearance = true;
            }
            assertTrue("outer layer never moves inside skin",
                    Math.abs(jacket.x) + 0.00001f >= Math.abs(skin.x)
                            || Math.abs(jacket.z) + 0.00001f >= Math.abs(skin.z));
        }
        assertTrue("jacket has measurable anti-z-fighting clearance", foundClearance);
    }

    private static void assertEquals(String name, int expected, int actual) {
        if (expected != actual) {
            throw new AssertionError(name + ": expected " + expected + ", got " + actual);
        }
    }

    private static void assertTrue(String name, boolean value) {
        if (!value) {
            throw new AssertionError(name);
        }
    }

    private record Vertex(float x, float y, float z) {
    }

    private static final class CountingConsumer implements VertexConsumer {
        private final List<Vertex> vertices = new ArrayList<>();
        private boolean finite = true;

        private void reset() {
            vertices.clear();
            finite = true;
        }

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            vertices.add(new Vertex(x, y, z));
            finite &= Float.isFinite(x) && Float.isFinite(y) && Float.isFinite(z);
            return this;
        }

        @Override public VertexConsumer setColor(int r, int g, int b, int a) { return this; }
        @Override public VertexConsumer setColor(int color) { return this; }
        @Override public VertexConsumer setUv(float u, float v) {
            finite &= Float.isFinite(u) && Float.isFinite(v) && u >= 0.0f && u <= 1.0f && v >= 0.0f && v <= 1.0f;
            return this;
        }
        @Override public VertexConsumer setUv1(int u, int v) { return this; }
        @Override public VertexConsumer setUv2(int u, int v) { return this; }
        @Override public VertexConsumer setNormal(float x, float y, float z) {
            float length = (float) Math.sqrt(x * x + y * y + z * z);
            finite &= Float.isFinite(length) && Math.abs(length - 1.0f) < 0.001f;
            return this;
        }
        @Override public VertexConsumer setLineWidth(float width) { return this; }
    }
}
