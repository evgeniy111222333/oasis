package ua.rp.chat.client.carver;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import ua.rp.chat.carver.CarverWorkAim;

public final class CarverContactFx {
    private static final GizmoStyle CROSS = GizmoStyle.stroke(0xFFFFD27A);
    private static final GizmoStyle GLOW = GizmoStyle.stroke(0x80FFD27A);

    private CarverContactFx() {
    }

    public static void renderMarker(BlockPos focus) {
        try {
            if (focus == null) return;
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft == null || minecraft.level == null) return;
            boolean mine = CarverClientState.working() && focus.equals(CarverClientState.focus());
            double[] c;
            if (mine) {
                c = CarverWorkAim.contactWorld(focus, CarverClientState.draft());
            } else {
                for (var w : CarverClientState.observedWorks()) {
                    if (w != null && w.focus().equals(focus) && w.contact() != null) {
                        drawCross(w.contact());
                        return;
                    }
                }
                return;
            }
            if (c == null) return;
            drawCross(c);
        } catch (RuntimeException ignored) {
        }
    }

    private static void drawCross(double[] c) {
        double s = 0.045;
        double pad = 0.004;
        Gizmos.cuboid(new AABB(c[0] - s, c[1] - pad, c[2] - 0.008, c[0] + s, c[1] + pad, c[2] + 0.008), CROSS);
        Gizmos.cuboid(new AABB(c[0] - 0.008, c[1] - pad, c[2] - s, c[0] + 0.008, c[1] + pad, c[2] + s), CROSS);
        Gizmos.cuboid(new AABB(c[0] - s * 0.6, c[1] - 0.03, c[2] - s * 0.6, c[0] + s * 0.6, c[1] + 0.012, c[2] + s * 0.6), GLOW);
    }

    public static void chips(Minecraft minecraft, BlockPos focus, Vec3 at) {
        try {
            if (minecraft == null || minecraft.level == null || at == null) return;
            int axis = CarverWorkAim.faceNormalAxis(CarverClientState.draft());
            Vec3 n = switch (axis) {
                case 0 -> new Vec3(1.0, 0.35, 0.0).normalize();
                case 2 -> new Vec3(0.0, 0.35, 1.0).normalize();
                default -> new Vec3(0.0, 1.0, 0.0);
            };
            var state = minecraft.level.getBlockState(focus);
            var opt = new BlockParticleOption(ParticleTypes.BLOCK, state);
            var rand = minecraft.level.getRandom();
            for (int i = 0; i < 3; i++) {
                double spread = 0.6 + rand.nextDouble() * 0.8;
                double vx = (n.x * spread + (rand.nextDouble() - 0.5) * 0.9) * 0.9;
                double vy = (n.y * spread + rand.nextDouble() * 1.1) * 0.9;
                double vz = (n.z * spread + (rand.nextDouble() - 0.5) * 0.9) * 0.9;
                minecraft.level.addParticle(opt, at.x, at.y + 0.02, at.z, vx, vy, vz);
            }
        } catch (RuntimeException ignored) {
        }
    }
}
