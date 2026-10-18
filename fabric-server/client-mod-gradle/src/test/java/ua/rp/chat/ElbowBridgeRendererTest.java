package ua.rp.chat;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.geom.ModelPart;
import ua.rp.chat.client.render.ElbowBridgeRenderer;

import java.util.List;
import java.util.Map;

public final class ElbowBridgeRendererTest {
    public static void main(String[] args) {
        ModelPart bridge = new ModelPart(List.of(), Map.of());
        ModelPart forearm = new ModelPart(List.of(), Map.of());
        ModelPart sleeveToggle = new ModelPart(List.of(), Map.of());
        forearm.setPos(0.0f, ArticulatedLimbLayout.ARM_ELBOW_Y, 0.0f);
        forearm.setRotation(-0.72f, 0.08f, -0.04f);
        ElbowBridgeRenderer.register(bridge, forearm, sleeveToggle, -1.0f, 3, 32, 48, 48, 48);

        CountingConsumer consumer = new CountingConsumer();
        sleeveToggle.visible = false;
        assertTrue(ElbowBridgeRenderer.renderIfRegistered(
                bridge, new PoseStack(), consumer, 0x00F000F0, 0, -1));
        int baseVertices = (ArticulatedLimbLayout.JOINT_SKINNING_RINGS - 1) * 4 * 4;
        assertEquals("base bridge vertices", baseVertices, consumer.vertices);
        assertTrue("base attributes are finite", consumer.finite);

        consumer.reset();
        sleeveToggle.visible = true;
        assertTrue(ElbowBridgeRenderer.renderIfRegistered(
                bridge, new PoseStack(), consumer, 0x00F000F0, 0, -1));
        assertEquals("base plus overlay vertices", baseVertices * 2, consumer.vertices);
        assertTrue("overlay attributes are finite", consumer.finite);
        System.out.println("ElbowBridgeRendererTest: base and overlay rendering passed");
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

    private static void assertTrue(boolean value) {
        assertTrue("renderer recognized bridge", value);
    }

    private static final class CountingConsumer implements VertexConsumer {
        int vertices;
        boolean finite = true;

        void reset() {
            vertices = 0;
            finite = true;
        }

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            vertices++;
            finite &= Float.isFinite(x) && Float.isFinite(y) && Float.isFinite(z);
            return this;
        }

        @Override public VertexConsumer setColor(int r, int g, int b, int a) { return this; }
        @Override public VertexConsumer setColor(int color) { return this; }
        @Override public VertexConsumer setUv(float u, float v) {
            finite &= Float.isFinite(u) && Float.isFinite(v);
            return this;
        }
        @Override public VertexConsumer setUv1(int u, int v) { return this; }
        @Override public VertexConsumer setUv2(int u, int v) { return this; }
        @Override public VertexConsumer setNormal(float x, float y, float z) {
            finite &= Float.isFinite(x) && Float.isFinite(y) && Float.isFinite(z);
            return this;
        }
        @Override public VertexConsumer setLineWidth(float width) { return this; }
    }
}
