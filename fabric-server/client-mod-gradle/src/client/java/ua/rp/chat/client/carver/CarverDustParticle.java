package ua.rp.chat.client.carver;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;

/**
 * Stylized work-process cloud particle. Swirls around the workpiece during carving,
 * maintaining a localized cloud shroud without drifting up away, and rapidly
 * dissipates when work completes.
 */
public final class CarverDustParticle extends SingleQuadParticle {
    /** Ticks of the opening pop: size eases out, alpha snaps in. */
    static final int POP_TICKS = 8;
    private static final float DRAG = 0.965f;
    private static final float BUOYANCY = 0.0045f;
    private static final float MAX_RISE = 0.09f;

    private final float startSize;
    private final float growSize;
    private final SpriteSet sprites;

    // Swirling / orbit dynamics around the block
    private final boolean swirling;
    private final double centerX;
    private final double centerY;
    private final double centerZ;
    private double orbitRadius;
    private double orbitAngle;
    private final double orbitSpeed;
    private final double targetY;
    private final double yWobble;
    private final double phase;
    private boolean fastDissolve;

    /** Dedicated swirling orbital cloud puff hugging and rotating around the block. */
    public CarverDustParticle(ClientLevel level,
                              double cx, double cy, double cz,
                              double orbitRadius, double orbitAngle, double orbitSpeed,
                              double targetY, float size, int lifetime, int color,
                              TextureAtlasSprite sprite, SpriteSet sprites) {
        super(level,
                cx + Math.cos(orbitAngle) * orbitRadius,
                cy + targetY,
                cz + Math.sin(orbitAngle) * orbitRadius,
                sprite);
        this.sprites = sprites;
        this.swirling = true;
        this.centerX = cx;
        this.centerY = cy;
        this.centerZ = cz;
        this.orbitRadius = orbitRadius;
        this.orbitAngle = orbitAngle;
        this.orbitSpeed = orbitSpeed;
        this.targetY = targetY;
        this.yWobble = 0.04 + (Math.abs(cx * 7.0 + cz * 11.0) % 1.0) * 0.04;
        this.phase = (cx * 13.0 + cz * 17.0 + targetY * 5.0) % (Math.PI * 2.0);
        this.xd = 0.0;
        this.yd = 0.0;
        this.zd = 0.0;
        this.startSize = size * 0.45f;
        this.growSize = size;
        this.quadSize = startSize;
        this.lifetime = Math.max(1, lifetime);
        this.hasPhysics = false;

        float red = ((color >> 16) & 0xFF) / 255.0f;
        float green = ((color >> 8) & 0xFF) / 255.0f;
        float blue = (color & 0xFF) / 255.0f;
        setColor(red, green, blue);
        setAlpha(0.0f);
        if (sprites != null) {
            setSpriteFromAge(sprites);
        }
    }

    /** Free-moving cloud puff with animated sprites. */
    public CarverDustParticle(ClientLevel level, double x, double y, double z,
                              double vx, double vy, double vz,
                              float size, int lifetime, int color,
                              TextureAtlasSprite sprite, SpriteSet sprites) {
        super(level, x, y, z, sprite);
        this.sprites = sprites;
        this.swirling = false;
        this.centerX = x;
        this.centerY = y;
        this.centerZ = z;
        this.orbitRadius = 0.0;
        this.orbitAngle = 0.0;
        this.orbitSpeed = 0.0;
        this.targetY = 0.0;
        this.yWobble = 0.0;
        this.phase = 0.0;
        this.xd = vx;
        this.yd = vy;
        this.zd = vz;
        this.startSize = size * 0.45f;
        this.growSize = size;
        this.quadSize = startSize;
        this.lifetime = Math.max(1, lifetime);
        this.hasPhysics = false;

        float red = ((color >> 16) & 0xFF) / 255.0f;
        float green = ((color >> 8) & 0xFF) / 255.0f;
        float blue = (color & 0xFF) / 255.0f;
        setColor(red, green, blue);
        setAlpha(0.0f);
        if (sprites != null) {
            setSpriteFromAge(sprites);
        }
    }

    /** Legacy constructor for tests and unassisted callers. */
    public CarverDustParticle(ClientLevel level, double x, double y, double z,
                              double vx, double vy, double vz,
                              float size, int lifetime, int color,
                              TextureAtlasSprite sprite) {
        this(level, x, y, z, vx, vy, vz, size, lifetime, color, sprite, null);
    }

    /** Triggers rapid outward dissipation on work completion. */
    public void dissipate() {
        this.fastDissolve = true;
        this.lifetime = Math.min(this.lifetime, this.age + 6);
    }

    /** Size multiplier at a given age: fast pop, then a slow settle outward. Pure. */
    public static float sizeAt(int age, int lifetime) {
        if (age <= 0) return 0.45f;
        if (age >= lifetime) return 1.6f;
        if (age <= POP_TICKS) {
            float t = age / (float) POP_TICKS;
            float eased = 1.0f - (1.0f - t) * (1.0f - t);
            return 0.45f + 0.55f * eased;
        }
        float t = (age - POP_TICKS) / (float) Math.max(1, lifetime - POP_TICKS);
        return 1.0f + 0.6f * t;
    }

    /** Alpha envelope at a given age: snaps in with the pop, dissolves at the end. Pure. */
    public static float alphaAt(int age, int lifetime) {
        if (age <= 0 || age >= lifetime) return 0.0f;
        float fadeIn = Math.min(1.0f, age / 4.0f);
        int tail = Math.max(8, lifetime / 3);
        float fadeOut = Math.min(1.0f, (lifetime - age) / (float) tail);
        return Math.min(fadeIn, fadeOut) * 0.85f;
    }

    @Override
    public void tick() {
        xo = x;
        yo = y;
        zo = z;
        if (age++ >= lifetime) {
            remove();
            return;
        }

        if (swirling && !fastDissolve) {
            // Swirl around the block hugging its sides, never shooting up into the sky
            orbitAngle += orbitSpeed;
            x = centerX + Math.cos(orbitAngle) * orbitRadius;
            z = centerZ + Math.sin(orbitAngle) * orbitRadius;
            y = centerY + targetY + Math.sin(age * 0.12 + phase) * yWobble;

            // Soft expansion in the final ticks before natural dispersal
            int fadeTail = Math.max(6, lifetime / 4);
            if (age > lifetime - fadeTail) {
                orbitRadius += 0.012;
            }
        } else {
            // Dissipating or free-moving: expands outward and softly fades
            if (fastDissolve) {
                orbitRadius += 0.035;
                x = centerX + Math.cos(orbitAngle) * orbitRadius;
                z = centerZ + Math.sin(orbitAngle) * orbitRadius;
            } else {
                xd *= DRAG;
                zd *= DRAG;
                yd = Math.min(MAX_RISE, yd * DRAG + BUOYANCY);
                x += xd;
                y += yd;
                z += zd;
            }
        }

        quadSize = growSize * sizeAt(age, lifetime);
        float alpha = alphaAt(age, lifetime);
        if (fastDissolve) {
            alpha *= Math.max(0.0f, (lifetime - age) / 6.0f);
        }
        setAlpha(alpha);

        if (sprites != null) {
            setSpriteFromAge(sprites);
        }
    }

    @Override
    protected Layer getLayer() {
        return Layer.TRANSLUCENT;
    }
}
