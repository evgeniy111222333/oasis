package ua.rp.chat.client.carver;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * Single impact bus for hammer strikes: every effect (dust accent, chips, hit
 * sound, camera shake) fires on the rising front of the contact pulse, never on
 * strike boundaries, so the eye, the ear and the camera agree on one frame.
 *
 * <p>The front detector is pure ({@link #shouldFire}) and unit-tested; the firing
 * path stays client-only. Distant artisans (>32 blocks) skip particle spawns and
 * keep only a quiet sound so workshops scale.</p>
 */
public final class CarverImpactFx {
    /** Contact pulse level that counts as touching stone. */
    public static final double CONTACT_FRONT = 0.9;
    /** Particle/sound cutoff for distant artisans (32 blocks squared). */
    public static final double FAR_CUTOFF_SQ = 1024.0;
    /** Camera shake for top-down chisel blows. */
    public static final double SHAKE_TOP = 0.30;
    /** Camera shake for side-face blows. */
    public static final double SHAKE_SIDE = 0.20;

    private CarverImpactFx() {
    }

    private record FrontKey(java.util.UUID playerId, int strikeIndex) {
    }

    private static final java.util.Map<FrontKey, Double> LAST_PULSE =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Pure rising-front detector: fires exactly once when the pulse crosses the
     * contact level from below. Unit-testable without Minecraft.
     */
    public static boolean shouldFire(double prevPulse, double pulse) {
        if (!(pulse > 0.0) && !(prevPulse > 0.0)) return CONTACT_FRONT <= 0.0;
        return prevPulse < CONTACT_FRONT && pulse >= CONTACT_FRONT;
    }

    /** Drops per-strike front state (session end, disconnect). */
    public static void clear() {
        LAST_PULSE.clear();
    }

    /**
     * Fires the impact effects at most once per strike, on the contact front.
     * Safe to call every frame: the internal front state filters repeats.
     *
     * @param at      strike contact the hammer visually lands on.
     * @param butt    hammer-butt position of the current pose, may be null; when
     *                present the butt-to-contact miss is reported to the perf log.
     * @param topness face-normal Y in [0, 1]: scales the camera shake.
     */
    public static boolean tickFire(Player player, BlockPos focus, Vec3 at, double[] butt,
                                   int strikeIndex, double pulse, float topness) {
        if (player == null || focus == null || at == null) return false;
        FrontKey key = new FrontKey(player.getUUID(), strikeIndex);
        double prev = LAST_PULSE.getOrDefault(key, 0.0);
        LAST_PULSE.put(key, pulse);
        if (!shouldFire(prev, pulse)) return false;
        CarverDustCore.onStrike();
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.level == null) return false;
        try {
            double distSq = distSqToLocal(client, at);
            boolean far = distSq >= 0.0 && distSq > FAR_CUTOFF_SQ;
            net.minecraft.world.level.block.state.BlockState state =
                    client.level.getBlockState(focus);
            if (!far) {
                int tint = CarverDustStorm.tintFor(client, focus, state);
                CarverDustStorm.accent(client, at, tint);
                CarverContactFx.chips(client, focus, at);
                materialImpact(client, state, at);
            }
            float top = Math.max(0.0f, Math.min(1.0f, topness));
            float pitch = 1.0f + (player.getRandom().nextFloat() - 0.5f) * 0.2f;
            float volume = far ? 0.2f : 0.5f;
            client.level.playLocalSound(at.x, at.y, at.z,
                    state.getSoundType().getHitSound(),
                    net.minecraft.sounds.SoundSource.BLOCKS, volume, pitch, false);
            if (client.player != null && client.player.getUUID().equals(player.getUUID())) {
                CarverCameraRig.addShake(SHAKE_SIDE + (SHAKE_TOP - SHAKE_SIDE) * top);
            }
            if (butt != null && butt.length >= 3) {
                double dx = butt[0] - at.x;
                double dy = butt[1] - at.y;
                double dz = butt[2] - at.z;
                CarverPerfLog.sasMiss(Math.sqrt(dx * dx + dy * dy + dz * dz) * 100.0);
            }
            CarverPerfLog.noteImpact();
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    /**
     * Material-specific impact debris layered on the shared dust: sparks for metal, brittle
     * shards for ice and glass, dry splinters for wood. Stone keeps reading as plain dust chips.
     */
    private static void materialImpact(Minecraft client, net.minecraft.world.level.block.state.BlockState state,
                                       Vec3 at) {
        try {
            net.minecraft.resources.Identifier key = net.minecraft.core.registries.BuiltInRegistries.BLOCK
                    .getKey(state.getBlock());
            ua.rp.chat.carver.CarverWorkAnim.Material material =
                    ua.rp.chat.carver.CarverWorkAnim.classify(key == null ? "" : key.toString());
            var rand = client.level.getRandom();
            if (ua.rp.chat.carver.CarverWorkAnim.sparks(material)) {
                for (int i = 0; i < 6; i++) {
                    client.level.addParticle(net.minecraft.core.particles.ParticleTypes.CRIT,
                            at.x, at.y, at.z,
                            (rand.nextDouble() - 0.5) * 1.3, rand.nextDouble() * 0.9,
                            (rand.nextDouble() - 0.5) * 1.3);
                }
            } else if (ua.rp.chat.carver.CarverWorkAnim.shards(material)) {
                for (int i = 0; i < 6; i++) {
                    client.level.addParticle(net.minecraft.core.particles.ParticleTypes.SNOWFLAKE,
                            at.x, at.y + 0.02, at.z,
                            (rand.nextDouble() - 0.5) * 0.7, rand.nextDouble() * 0.6,
                            (rand.nextDouble() - 0.5) * 0.7);
                }
            } else if (ua.rp.chat.carver.CarverWorkAnim.splinters(material)) {
                var opt = new net.minecraft.core.particles.BlockParticleOption(
                        net.minecraft.core.particles.ParticleTypes.BLOCK, state);
                for (int i = 0; i < 5; i++) {
                    client.level.addParticle(opt, at.x, at.y + 0.02, at.z,
                            (rand.nextDouble() - 0.5) * 0.9, rand.nextDouble() * 0.8,
                            (rand.nextDouble() - 0.5) * 0.9);
                }
            }
        } catch (RuntimeException ignored) {
        }
    }

    private static double distSqToLocal(Minecraft client, Vec3 at) {
        try {
            if (client.player == null) return -1.0;
            net.minecraft.world.phys.Vec3 mine = client.player.getEyePosition();
            double dx = at.x - mine.x;
            double dy = at.y - mine.y;
            double dz = at.z - mine.z;
            return dx * dx + dy * dy + dz * dz;
        } catch (RuntimeException unavailable) {
            return -1.0;
        }
    }
}
