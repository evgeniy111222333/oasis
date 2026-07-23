package ua.rp.chat.client.blood;

import net.fabricmc.fabric.api.client.particle.v1.FabricSpriteSet;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import ua.rp.chat.blood.BloodFxRules;

final class BloodDecalParticle extends SingleQuadParticle {
    private final Quaternionf surfaceRotation;
    private final float baseRed;
    private final float baseGreen;
    private final float baseBlue;
    private float targetSize;
    private final long surfaceKey;
    private boolean completionReported;

    BloodDecalParticle(ClientLevel level, Vec3 position, Vec3 normal, int material,
                       float size, long seed, long surfaceKey, TextureAtlasSprite sprite) {
        super(level,
                position.x + normal.x * 0.003,
                position.y + normal.y * 0.003,
                position.z + normal.z * 0.003,
                sprite);
        Vec3 safeNormal = normal.lengthSqr() < 0.5 ? new Vec3(0.0, 1.0, 0.0) : normal.normalize();
        this.surfaceRotation = new Quaternionf().rotationTo(
                new Vector3f(0.0f, 0.0f, 1.0f),
                new Vector3f((float) safeNormal.x, (float) safeNormal.y, (float) safeNormal.z)
        ).rotateZ(BloodFxRules.unitFloat(seed ^ 0x94d049bb133111ebL) * ((float) Math.PI * 2.0f));
        this.targetSize = 0.075f + BloodFxRules.clamp01(size) * 0.19f;
        this.surfaceKey = surfaceKey;
        this.quadSize = targetSize * 0.18f;
        this.hasPhysics = false;
        this.lifetime = BloodFxRules.decalLifetimeTicks(material, size, seed);
        float materialShade = switch (material) {
            case 1 -> 0.62f;
            case 2 -> 0.72f;
            case 3 -> 0.78f;
            case 4 -> 1.08f;
            case 5 -> 0.68f;
            default -> 0.88f;
        };
        this.baseRed = 0.48f * materialShade;
        this.baseGreen = 0.016f * materialShade;
        this.baseBlue = 0.011f * materialShade;
        setColor(baseRed, baseGreen, baseBlue);
    }

    static BloodDecalParticle fallback(ClientLevel level, double x, double y, double z,
                                       double nx, double ny, double nz, RandomSource random,
                                       FabricSpriteSet sprites) {
        Vec3 normal = new Vec3(nx, ny, nz);
        if (normal.lengthSqr() < 0.5) normal = new Vec3(0.0, 1.0, 0.0);
        return new BloodDecalParticle(level, new Vec3(x, y, z), normal,
                0, 0.45f, random.nextLong(), Long.MIN_VALUE, sprites.get(random));
    }

    void absorb(float energy, long seed) {
        float growth = 0.025f + BloodFxRules.clamp01(energy) * 0.052f;
        targetSize = Math.min(0.38f, targetSize + growth);
        lifetime = Math.max(lifetime, age + 420 + (int) (BloodFxRules.unitFloat(seed) * 360.0f));
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
        float settle = Math.min(1.0f, age / 7.0f);
        quadSize = targetSize * (0.18f + 0.82f * (1.0f - (1.0f - settle) * (1.0f - settle)));
        float dried = BloodFxRules.driedColorFactor(age, lifetime);
        setColor(baseRed * dried, baseGreen * (0.72f + dried * 0.28f), baseBlue * 0.75f);
        if (age > lifetime - 80) {
            setAlpha(Math.max(0.0f, (lifetime - age) / 80.0f));
        }
    }

    @Override
    public void extract(QuadParticleRenderState state, Camera camera, float partialTick) {
        extractRotatedQuad(state, camera, surfaceRotation, partialTick);
    }

    @Override
    public void remove() {
        super.remove();
        if (!completionReported) {
            completionReported = true;
            BloodFxClientState.onDecalRemoved(surfaceKey, this);
        }
    }

    @Override
    protected Layer getLayer() {
        return Layer.TRANSLUCENT;
    }
}
