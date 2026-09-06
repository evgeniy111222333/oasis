package ua.rp.chat.client.stonemason;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.phys.Vec3;

/**
 * Local dust layer over the authoritative server work effects: a dense choking cloud
 * around the block while the simulated carving runs, plus a completion burst. Server
 * particles and sounds carry the scene for nearby players; this thickens it for the
 * artisan without replacing anything.
 */
public final class StonemasonWorkFx {
    private static int tickCounter;

    private StonemasonWorkFx() {
    }

    public static void tick(Minecraft minecraft) {
        if (minecraft.level == null || minecraft.player == null) return;
        tickCounter++;
        if (!StonemasonClientState.working() || StonemasonClientState.focus() == null) return;
        if (tickCounter % 3 != 0) return;
        BlockPos focus = StonemasonClientState.focus();
        double seed = (tickCounter * 0.37) % 1.0;
        puff(minecraft, focus, seed);
        puff(minecraft, focus, 1.0 - seed);
    }

    public static void burst(BlockPos focus) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || focus == null) return;
        for (int index = 0; index < 40; index++) {
            double angle = index / 40.0 * Math.PI * 2.0;
            minecraft.level.addParticle(ParticleTypes.CLOUD,
                    focus.getX() + 0.5 + Math.cos(angle) * 0.8,
                    focus.getY() + 0.6 + (index % 5) * 0.15,
                    focus.getZ() + 0.5 + Math.sin(angle) * 0.8,
                    Math.cos(angle) * 0.04, 0.05, Math.sin(angle) * 0.04);
        }
    }

    private static void puff(Minecraft minecraft, BlockPos focus, double seed) {
        double angle = seed * Math.PI * 2.0;
        double radius = 0.5 + seed * 0.5;
        Vec3 at = new Vec3(
                focus.getX() + 0.5 + Math.cos(angle) * radius,
                focus.getY() + 0.7 + seed * 0.6,
                focus.getZ() + 0.5 + Math.sin(angle) * radius);
        minecraft.level.addParticle(seed < 0.5 ? ParticleTypes.CLOUD : ParticleTypes.WHITE_SMOKE,
                at.x, at.y, at.z, 0.0, 0.03, 0.0);
    }
}
