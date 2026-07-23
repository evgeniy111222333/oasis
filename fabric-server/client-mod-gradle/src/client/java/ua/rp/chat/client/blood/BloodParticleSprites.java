package ua.rp.chat.client.blood;

import net.fabricmc.fabric.api.client.particle.v1.FabricSpriteSet;
import net.fabricmc.fabric.api.client.particle.v1.ParticleProviderRegistry;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.util.RandomSource;
import ua.rp.chat.blood.BloodParticleTypes;

final class BloodParticleSprites {
    private static volatile FabricSpriteSet drops;
    private static volatile FabricSpriteSet decals;
    private static volatile FabricSpriteSet wounds;

    private BloodParticleSprites() {
    }

    static void register() {
        ParticleProviderRegistry registry = ParticleProviderRegistry.getInstance();
        registry.register(BloodParticleTypes.DROP, sprites -> {
            drops = sprites;
            return (options, level, x, y, z, vx, vy, vz, random) ->
                    BloodDropParticle.fallback(level, x, y, z, vx, vy, vz, random, sprites);
        });
        registry.register(BloodParticleTypes.DECAL, sprites -> {
            decals = sprites;
            return (options, level, x, y, z, nx, ny, nz, random) ->
                    BloodDecalParticle.fallback(level, x, y, z, nx, ny, nz, random, sprites);
        });
        registry.register(BloodParticleTypes.WOUND, sprites -> {
            wounds = sprites;
            return (options, level, x, y, z, vx, vy, vz, random) ->
                    BloodWoundParticle.fallback(level, x, y, z, random, sprites);
        });
    }

    static boolean ready() {
        return drops != null && decals != null && wounds != null;
    }

    static TextureAtlasSprite drop(long seed) {
        return pick(drops, seed, 0);
    }

    static TextureAtlasSprite decal(long seed, int preferred) {
        return pick(decals, seed, preferred);
    }

    static TextureAtlasSprite wound(long seed, int preferred) {
        return pick(wounds, seed, preferred);
    }

    private static TextureAtlasSprite pick(FabricSpriteSet set, long seed, int preferred) {
        if (set == null || set.getSprites().isEmpty()) return null;
        int size = set.getSprites().size();
        int index = preferred >= 0
                ? Math.floorMod(preferred, size)
                : Math.floorMod((int) (seed ^ (seed >>> 32)), size);
        return set.getSprites().get(index);
    }
}
