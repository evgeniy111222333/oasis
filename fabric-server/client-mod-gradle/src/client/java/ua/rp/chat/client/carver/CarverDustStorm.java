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
    /** Hard cap on live swirl particles, so a long job can never flood the particle engine. */
    private static final int MAX_SWIRLS = 180;

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

        // Drop the previous session's swirls (without the completion pop).
        dissipateActive();
        pruneActive();

        RandomSource random = RandomSource.create(System.nanoTime() ^ pos.asLong());
        double cx = pos.getX() + 0.5;
        double cy = pos.getY() + 0.5;
        double cz = pos.getZ() + 0.5;

        // Dense volumetric cocoon: a tight core, a wide shell that reaches past the artisan, and
        // a few large seal puffs that close the gaps between billboards.
        for (int i = 0; i < 16; i++) {
            spawnSwirl(minecraft, level, random, cx, cy, cz,
                    0.25, 0.75, 0.05, 0.65, 0.50f, 0.70f, color, set);
        }
        for (int i = 0; i < 22; i++) {
            spawnSwirl(minecraft, level, random, cx, cy, cz,
                    0.80, 1.90, -0.25, 1.75, 0.85f, 1.30f, color, set);
        }
        for (int i = 0; i < 8; i++) {
            spawnSwirl(minecraft, level, random, cx, cy, cz,
                    0.90, 1.60, 0.10, 1.10, 1.20f, 1.70f, color, set);
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
        if (activeSwirls.size() >= MAX_SWIRLS) return;

        RandomSource random = RandomSource.create(System.nanoTime() ^ level.getGameTime());
        double cx = pos.getX() + 0.5;
        double cy = pos.getY() + 0.5;
        double cz = pos.getZ() + 0.5;

        // Keep the cocoon dense for the whole job: several fresh billows at varied radii/heights.
        for (int i = 0; i < 6; i++) {
            spawnSwirl(minecraft, level, random, cx, cy, cz,
                    0.35, 1.80, -0.25, 1.70, 0.55f, 1.10f, color, set);
        }
    }

    /** Spawns one orbiting cloud billow and remembers it for rapid dissipation. */
    private static void spawnSwirl(Minecraft minecraft, ClientLevel level, RandomSource random,
                                   double cx, double cy, double cz,
                                   double radiusMin, double radiusMax,
                                   double yMin, double yMax,
                                   float sizeMin, float sizeMax,
                                   int color, FabricSpriteSet set) {
        if (activeSwirls.size() >= MAX_SWIRLS) return;
        TextureAtlasSprite sprite = set.get(0, 8);
        double angle = random.nextDouble() * Math.PI * 2.0;
        double radius = radiusMin + random.nextDouble() * (radiusMax - radiusMin);
        double speed = (random.nextBoolean() ? 1 : -1) * (0.05 + random.nextDouble() * 0.05);
        double targetY = yMin + random.nextDouble() * (yMax - yMin);
        float size = sizeMin + random.nextFloat() * (sizeMax - sizeMin);
        int life = 24 + random.nextInt(12);
        int tone = color == 0xFFFFFF ? randomCloudTone(random) : color;

        CarverDustParticle p = new CarverDustParticle(level, cx, cy, cz,
                radius, angle, speed, targetY, size, life, tone, sprite, set);
        minecraft.particleEngine.add(p);
        activeSwirls.add(new WeakReference<>(p));
    }

    /** Dissipates every live swirl without spawning the completion pop. */
    private static void dissipateActive() {
        for (var ref : activeSwirls) {
            CarverDustParticle p = ref.get();
            if (p != null && p.isAlive()) {
                p.dissipate();
            }
        }
        activeSwirls.clear();
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
