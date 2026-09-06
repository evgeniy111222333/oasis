package ua.rp.chat.client.carver;

import net.fabricmc.fabric.api.client.particle.v1.FabricSpriteSet;
import net.fabricmc.fabric.api.client.particle.v1.ParticleProviderRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import ua.rp.chat.carver.CarverParticleTypes;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Client-only work storm over the workpiece: stylized white cartoon cloud billows
 * swirl in an active vortex around the block while carving runs, hugging the workpiece
 * without flying off into the sky, and rapidly dissipate on completion.
 */
public final class CarverDustStorm {
    private static volatile FabricSpriteSet sprites;

    // Luminous clean cloud whites and subtle natural cloud tones (never biome green!)
    private static final int[] CLOUD_TONES = {
        0xFFFFFF, // Pure brilliant cloud white
        0xFFFFFF,
        0xFFFDF8, // Soft ivory cloud
        0xF4F7FB, // Pale silvery cloud
        0xFFFFFF,
        0xFAFBFD  // Luminous white
    };

    /** Tracks live swirling particles so they can be rapidly dissipated when work ends. */
    private static final List<WeakReference<CarverDustParticle>> activeSwirls = new ArrayList<>();

    private CarverDustStorm() {
    }

    public static void register() {
        ParticleProviderRegistry.getInstance().register(CarverParticleTypes.DUST, set -> {
            sprites = set;
            return (options, level, x, y, z, vx, vy, vz, random) ->
                    new CarverDustParticle(level, x, y, z, vx, vy, vz,
                            0.7f, 40, 0xFFFFFF, set.get(random), set);
        });
    }

    static int randomCloudTone(RandomSource random) {
        return CLOUD_TONES[random.nextInt(CLOUD_TONES.length)];
    }

    static TextureAtlasSprite sprite(RandomSource random) {
        FabricSpriteSet set = sprites;
        if (set == null || set.getSprites().isEmpty()) return null;
        return set.getSprites().get(Math.floorMod(random.nextInt(),
                set.getSprites().size()));
    }

    /**
     * Touchdown / work-start vortex burst: spawns cloud billows on top and in a
     * rotating ring around the block that orbit around the workpiece.
     */
    public static void burst(Minecraft minecraft, BlockPos pos, int color) {
        if (minecraft == null || !(minecraft.level instanceof ClientLevel level)) return;
        if (pos == null) return;
        FabricSpriteSet set = sprites;
        if (set == null || set.getSprites().isEmpty()) return;

        // Clean up any stale particles from previous sessions
        finish(minecraft, pos);

        RandomSource random = RandomSource.create(System.nanoTime() ^ pos.asLong());
        double cx = pos.getX() + 0.5;
        double cy = pos.getY() + 0.5;
        double cz = pos.getZ() + 0.5;

        for (int i = 0; i < 6; i++) {
            TextureAtlasSprite sprite = set.get(0, 8);
            double angle = random.nextDouble() * Math.PI * 2.0;
            double radius = 0.18 + random.nextDouble() * 0.18;
            double speed = (random.nextBoolean() ? 1 : -1) * (0.05 + random.nextDouble() * 0.04);
            double targetY = 0.10 + random.nextDouble() * 0.28;
            float size = 0.30f + random.nextFloat() * 0.16f;
            int life = 22 + random.nextInt(10);
            int tone = color == 0xFFFFFF ? randomCloudTone(random) : color;

            CarverDustParticle p = new CarverDustParticle(level, cx, cy, cz,
                    radius, angle, speed, targetY, size, life, tone, sprite, set);
            minecraft.particleEngine.add(p);
            activeSwirls.add(new WeakReference<>(p));
        }

        for (int i = 0; i < 8; i++) {
            TextureAtlasSprite sprite = set.get(0, 8);
            double angle = random.nextDouble() * Math.PI * 2.0;
            double radius = 0.42 + random.nextDouble() * 0.22;
            double speed = (random.nextBoolean() ? 1 : -1) * (0.06 + random.nextDouble() * 0.05);
            double targetY = -0.05 + random.nextDouble() * 0.35;
            float size = 0.32f + random.nextFloat() * 0.16f;
            int life = 24 + random.nextInt(10);
            int tone = color == 0xFFFFFF ? randomCloudTone(random) : color;

            CarverDustParticle p = new CarverDustParticle(level, cx, cy, cz,
                    radius, angle, speed, targetY, size, life, tone, sprite, set);
            minecraft.particleEngine.add(p);
            activeSwirls.add(new WeakReference<>(p));
        }
    }

    /**
     * Strike accent: a tight trio of puffs exactly where the hammer lands, synced
     * with the contact bottom of the swing curve and the hammer tick.
     */
    public static void accent(Minecraft minecraft, Vec3 at, int color) {
        if (minecraft == null || !(minecraft.level instanceof ClientLevel level)) return;
        if (at == null) return;
        FabricSpriteSet set = sprites;
        if (set == null || set.getSprites().isEmpty()) return;
        RandomSource random = RandomSource.create(
                Double.doubleToLongBits(at.x * 13.0 + at.y * 7.0 + at.z * 5.0));
        int tone = color == 0xFFFFFF ? randomCloudTone(random) : color;
        for (int i = 0; i < 3; i++) {
            TextureAtlasSprite sprite = set.get(0, 8);
            double angle = random.nextDouble() * Math.PI * 2.0;
            double radius = 0.04 + random.nextDouble() * 0.06;
            double speed = (random.nextBoolean() ? 1 : -1) * (0.08 + random.nextDouble() * 0.06);
            float size = 0.26f + random.nextFloat() * 0.12f;
            int life = 12 + random.nextInt(6);
            CarverDustParticle p = new CarverDustParticle(level, at.x, at.y, at.z,
                    radius, angle, speed, 0.06 + random.nextDouble() * 0.10,
                    size, life, tone, sprite, set);
            minecraft.particleEngine.add(p);
        }
    }

    /**
     * Trickle during carving: adds fresh cloud billows that continue to swirl
     * around the block while the work process runs.
     */
    public static void trickle(Minecraft minecraft, BlockPos pos, int color) {
        if (minecraft == null || !(minecraft.level instanceof ClientLevel level)) return;
        if (pos == null) return;
        FabricSpriteSet set = sprites;
        if (set == null || set.getSprites().isEmpty()) return;

        pruneActive();

        RandomSource random = RandomSource.create(System.nanoTime() ^ level.getGameTime());
        double cx = pos.getX() + 0.5;
        double cy = pos.getY() + 0.5;
        double cz = pos.getZ() + 0.5;

        TextureAtlasSprite sprite = set.get(0, 8);
        double angle = random.nextDouble() * Math.PI * 2.0;
        double radius = 0.20 + random.nextDouble() * 0.18;
        double speed = (random.nextBoolean() ? 1 : -1) * (0.06 + random.nextDouble() * 0.04);
        double targetY = 0.12 + random.nextDouble() * 0.25;
        float size = 0.30f + random.nextFloat() * 0.14f;
        int life = 22 + random.nextInt(8);
        int tone = color == 0xFFFFFF ? randomCloudTone(random) : color;

        CarverDustParticle p = new CarverDustParticle(level, cx, cy, cz,
                radius, angle, speed, targetY, size, life, tone, sprite, set);
        minecraft.particleEngine.add(p);
        activeSwirls.add(new WeakReference<>(p));
    }

    /**
     * Work completion: all active swirling cloud particles quickly expand and dissipate,
     * plus a fast, crisp outward pop that disappears in ~10 ticks.
     */
    public static void finish(Minecraft minecraft, BlockPos pos) {
        if (minecraft == null || !(minecraft.level instanceof ClientLevel level)) return;
        FabricSpriteSet set = sprites;

        // Rapidly dissipate all circling particles
        for (var ref : activeSwirls) {
            CarverDustParticle p = ref.get();
            if (p != null && p.isAlive()) {
                p.dissipate();
            }
        }
        activeSwirls.clear();

        if (pos == null || set == null || set.getSprites().isEmpty()) return;
        RandomSource random = RandomSource.create(System.nanoTime() ^ pos.asLong());
        double cx = pos.getX() + 0.5;
        double cy = pos.getY() + 0.5;
        double cz = pos.getZ() + 0.5;

        // Quick finishing pop (expands outwards and vanishes in 8-10 ticks)
        for (int i = 0; i < 16; i++) {
            TextureAtlasSprite sprite = set.get(0, 8);
            double angle = random.nextDouble() * Math.PI * 2.0;
            double speed = 0.12 + random.nextDouble() * 0.16;
            double vx = Math.cos(angle) * speed;
            double vz = Math.sin(angle) * speed;
            double vy = 0.03 + random.nextDouble() * 0.06;
            float size = 0.50f + random.nextFloat() * 0.35f;
            int life = 9 + random.nextInt(5);
            int tone = randomCloudTone(random);

            CarverDustParticle p = new CarverDustParticle(level,
                    cx + Math.cos(angle) * 0.3, cy + random.nextDouble() * 0.4,
                    cz + Math.sin(angle) * 0.3, vx, vy, vz, size, life, tone, sprite, set);
            minecraft.particleEngine.add(p);
        }
    }

    private static void pruneActive() {
        Iterator<WeakReference<CarverDustParticle>> it = activeSwirls.iterator();
        while (it.hasNext()) {
            CarverDustParticle p = it.next().get();
            if (p == null || !p.isAlive()) {
                it.remove();
            }
        }
    }

    public static int tintFor(Minecraft minecraft, BlockPos pos,
                              net.minecraft.world.level.block.state.BlockState state) {
        try {
            if (minecraft != null && minecraft.level != null && state != null && pos != null) {
                int map = state.getMapColor(minecraft.level, pos).col;
                int r = (map >> 16) & 0xFF;
                int g = (map >> 8) & 0xFF;
                int b = map & 0xFF;
                r = 205 + (r - 205) / 3;
                g = 205 + (g - 205) / 3;
                b = 205 + (b - 205) / 3;
                return (r << 16) | (g << 8) | b;
            }
        } catch (RuntimeException ignored) {
        }
        return 0xFFFFFF;
    }

    /** Work column anchor. Pure. */
    public static Vec3 columnAt(BlockPos focus, double seed, double progress) {
        double angle = seed * Math.PI * 2.0;
        double radius = 0.4 + seed * 0.9;
        double height = seed * 2.2 * (0.5 + progress);
        return new Vec3(focus.getX() + 0.5 + Math.cos(angle) * radius,
                focus.getY() + 0.5 + height,
                focus.getZ() + 0.5 + Math.sin(angle) * radius);
    }
}
