package ua.rp.chat.client.carver;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.Set;

/**
 * World-space dust cloud over the workpiece. A loose cluster of camera-facing puffs — a denser
 * centre, a broad mid shell and a few trailing wisps — slowly billows and rotates so it reads as
 * living air rather than a flat card. It rides the work-phase strength shared with
 * {@link CarverDustScreen}, kicks brighter on hammer strikes, and stays local to the bench. The
 * block and the artisan stay visible through the work shot, so the cloud is a light, pretty layer
 * of atmosphere rather than an occlusion device.
 */
public final class CarverDustCore {
    private static final Identifier[] TEXTURES = {
            Identifier.fromNamespaceAndPath("eclipseclient", "textures/particle/dust/puff_5.png"),
            Identifier.fromNamespaceAndPath("eclipseclient", "textures/particle/dust/puff_3.png"),
            Identifier.fromNamespaceAndPath("eclipseclient", "textures/particle/dust/puff_7.png")
    };

    /** dx, dy, dz in cluster radii; size and alpha factors; animation phase. */
    private record Puff(float dx, float dy, float dz, float size, float alpha, float phase) {
    }

    private static final Puff[] PUFFS = {
            new Puff(0.00f, 0.02f, 0.00f, 1.05f, 0.32f, 0.0f),
            new Puff(0.34f, 0.12f, 0.22f, 0.82f, 0.27f, 1.7f),
            new Puff(-0.30f, 0.28f, -0.16f, 0.78f, 0.25f, 3.1f),
            new Puff(0.12f, 0.52f, -0.30f, 0.72f, 0.21f, 4.6f),
            new Puff(-0.22f, -0.30f, 0.34f, 0.68f, 0.19f, 2.2f),
            new Puff(0.46f, -0.12f, -0.36f, 0.86f, 0.16f, 5.3f),
            new Puff(-0.50f, 0.16f, 0.30f, 0.90f, 0.14f, 0.9f),
            new Puff(0.20f, 0.78f, 0.10f, 0.95f, 0.11f, 3.8f),
            new Puff(-0.16f, -0.54f, -0.42f, 0.80f, 0.12f, 2.7f),
            new Puff(0.62f, 0.32f, 0.26f, 0.96f, 0.09f, 1.2f),
            new Puff(-0.66f, -0.16f, -0.20f, 0.96f, 0.09f, 4.1f),
            new Puff(0.06f, 0.54f, 0.60f, 0.92f, 0.08f, 5.9f),
            new Puff(-0.34f, 0.60f, -0.56f, 0.88f, 0.08f, 0.4f)
    };

    private static final int FULL_BRIGHT = 0xF000F0;
    private static final double BASE_HALF = 0.82;
    private static final double CLUSTER_RADIUS = 1.35;
    private static final float MAX_PUFF_ALPHA = 0.62f;

    private static float strength;
    private static float pulse;
    private static double tick;
    private static BlockPos focus;
    private static Vec3 center = Vec3.ZERO;

    private CarverDustCore() {
    }

    /** Advances the cloud ramp, decay and strike kick once per client tick. */
    public static void tick() {
        BlockPos next = activeFocus();
        boolean active = next != null;
        strength += ((active ? 1.0f : 0.0f) - strength) * (active ? 0.08f : 0.06f);
        pulse *= 0.80f;
        if (pulse < 0.01f) {
            pulse = 0.0f;
        }
        tick++;
        if (strength < 0.005f) {
            strength = 0.0f;
            focus = null;
            return;
        }
        if (next == null) {
            return;
        }
        focus = next;
        center = Vec3.atCenterOf(next).add(0.0, 0.25, 0.0);
    }

    /** Hammer strike: a short density and scale kick that makes the cloud feel struck. */
    public static void onStrike() {
        pulse = 1.0f;
    }

    /** World-space END_MAIN hook: emits the billowing cluster while the work phase holds. */
    public static void render(LevelRenderContext context) {
        if (strength <= 0.02f || focus == null) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.level == null || minecraft.gameRenderer == null) {
            return;
        }
        Vec3 camera = minecraft.gameRenderer.getMainCamera().position();
        Vec3 toCamera = camera.subtract(center);
        if (toCamera.lengthSqr() < 1.0e-6) {
            return;
        }
        toCamera = toCamera.normalize();
        Vec3 upReference = Math.abs(toCamera.y) > 0.99 ? new Vec3(0.0, 0.0, 1.0) : new Vec3(0.0, 1.0, 0.0);
        Vec3 right = upReference.cross(toCamera).normalize();
        Vec3 up = toCamera.cross(right).normalize();

        int tint = lighten(CarverWorkFx.stormTintFor(focus), 40);
        PoseStack.Pose pose = context.poseStack().last();
        Set<RenderType> used = new HashSet<>();

        double spin = tick * 0.05;
        double cos = Math.cos(spin);
        double sin = Math.sin(spin);
        for (int index = 0; index < PUFFS.length; index++) {
            Puff puff = PUFFS[index];
            double rx = puff.dx * cos - puff.dz * sin;
            double rz = puff.dx * sin + puff.dz * cos;
            double wobbleX = Math.sin(tick * 0.031 + puff.phase) * 0.06;
            double wobbleY = Math.cos(tick * 0.024 + puff.phase) * 0.06;
            Vec3 point = center
                    .add(right.scale((rx + wobbleX) * CLUSTER_RADIUS))
                    .add(up.scale((puff.dy + wobbleY) * CLUSTER_RADIUS))
                    .add(toCamera.scale(rz * CLUSTER_RADIUS * 0.45));
            float billow = 1.0f + 0.08f * (float) Math.sin(tick * 0.045 + puff.phase);
            float half = (float) (BASE_HALF * puff.size * billow * (1.0 + 0.12 * pulse));
            float alpha = puffAlpha(index, strength, pulse);
            emit(context, pose, camera, toCamera, point, right, up, half, alpha,
                    tint, TEXTURES[index % TEXTURES.length], used);
        }
        for (RenderType type : used) {
            context.bufferSource().endBatch(type);
        }
    }

    /** Per-puff alpha: base ramp, mild billow, strike kick, hard ceiling so it never washes out. */
    public static float puffAlpha(int index, float strength, float pulse) {
        Puff puff = PUFFS[Math.floorMod(index, PUFFS.length)];
        float billow = 1.0f + 0.12f * (float) Math.sin(tick * 0.05 + puff.phase);
        return Math.min(MAX_PUFF_ALPHA,
                puff.alpha * Math.max(0.0f, strength) * billow * (1.0f + 0.22f * Math.max(0.0f, pulse)));
    }

    /** Half-size of one puff in blocks, before the billow pulse. */
    public static double puffHalf(int index) {
        return BASE_HALF * PUFFS[Math.floorMod(index, PUFFS.length)].size;
    }

    public static int puffCount() {
        return PUFFS.length;
    }

    private static BlockPos activeFocus() {
        if (CarverClientState.working() && CarverClientState.focus() != null) {
            return CarverClientState.focus();
        }
        for (CarverClientState.ObservedWork observed : CarverClientState.observedWorks()) {
            if (observed != null && observed.focus() != null) {
                return observed.focus();
            }
        }
        return null;
    }

    private static void emit(LevelRenderContext context, PoseStack.Pose pose, Vec3 camera,
                             Vec3 normal, Vec3 centerPoint, Vec3 right, Vec3 up, float half,
                             float alpha, int tint, Identifier texture, Set<RenderType> used) {
        if (alpha <= 0.01f) {
            return;
        }
        RenderType type = RenderTypes.entityTranslucent(texture);
        used.add(type);
        VertexConsumer consumer = context.bufferSource().getBuffer(type);
        int color = (Math.max(0, Math.min(255, Math.round(alpha * 255.0f))) << 24) | (tint & 0xFFFFFF);

        Vec3 topLeft = centerPoint.add(up.scale(half)).subtract(right.scale(half));
        Vec3 topRight = centerPoint.add(up.scale(half)).add(right.scale(half));
        Vec3 bottomRight = centerPoint.subtract(up.scale(half)).add(right.scale(half));
        Vec3 bottomLeft = centerPoint.subtract(up.scale(half)).subtract(right.scale(half));

        vertex(consumer, pose, camera, normal, topLeft, 0.0f, 0.0f, color);
        vertex(consumer, pose, camera, normal, topRight, 1.0f, 0.0f, color);
        vertex(consumer, pose, camera, normal, bottomRight, 1.0f, 1.0f, color);
        vertex(consumer, pose, camera, normal, bottomLeft, 0.0f, 1.0f, color);
    }

    private static void vertex(VertexConsumer consumer, PoseStack.Pose pose, Vec3 camera,
                               Vec3 normal, Vec3 point, float u, float v, int color) {
        consumer.addVertex(pose, (float) (point.x - camera.x), (float) (point.y - camera.y),
                        (float) (point.z - camera.z))
                .setColor(color)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(FULL_BRIGHT)
                .setNormal(pose, (float) normal.x, (float) normal.y, (float) normal.z);
    }

    private static int lighten(int rgb, int amount) {
        int r = Math.min(255, ((rgb >> 16) & 0xFF) + amount);
        int g = Math.min(255, ((rgb >> 8) & 0xFF) + amount);
        int b = Math.min(255, (rgb & 0xFF) + amount);
        return (r << 16) | (g << 8) | b;
    }

    static void reset() {
        strength = 0.0f;
        pulse = 0.0f;
        focus = null;
    }
}
