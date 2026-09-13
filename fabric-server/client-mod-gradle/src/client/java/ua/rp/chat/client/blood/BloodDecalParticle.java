package ua.rp.chat.client.blood;

import net.fabricmc.fabric.api.client.particle.v1.FabricSpriteSet;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import ua.rp.chat.blood.BloodFxRules;
import ua.rp.chat.blood.BloodVolumeRules;

final class BloodDecalParticle extends SingleQuadParticle {
    private final Quaternionf surfaceRotation;
    private final float baseRed;
    private final float baseGreen;
    private final float baseBlue;
    private float targetSize;
    private float volumeMl;
    private final long surfaceKey;
    private final Vec3 surfaceNormal;
    private final int material;
    private final long flowSeed;
    private final int flowDepth;
    private final boolean contaminating;
    private final int spriteFamily;
    private int spriteStage;
    private int wetAge;
    private int dryingTicks;
    private int flowEmissions;
    private int nextFlowAge;
    private boolean completionReported;

    BloodDecalParticle(ClientLevel level, Vec3 position, Vec3 normal, int material,
                       float volumeMl, float size, long seed, long surfaceKey, int spriteFamily,
                       TextureAtlasSprite sprite) {
        this(level, position, normal, material, volumeMl, size, seed,
                surfaceKey, spriteFamily, sprite, true);
    }

    BloodDecalParticle(ClientLevel level, Vec3 position, Vec3 normal, int material,
                       float volumeMl, float size, long seed, long surfaceKey, int spriteFamily,
                       TextureAtlasSprite sprite,
                       boolean contaminating) {
        this(level, position, normal, material, volumeMl, size, seed, surfaceKey,
                spriteFamily, sprite, contaminating, Float.NaN, 0);
    }

    BloodDecalParticle(ClientLevel level, Vec3 position, Vec3 normal, int material,
                       float volumeMl, float size, long seed, long surfaceKey, int spriteFamily,
                       TextureAtlasSprite sprite,
                       boolean contaminating, float rotationRadians) {
        this(level, position, normal, material, volumeMl, size, seed, surfaceKey,
                spriteFamily, sprite, contaminating, rotationRadians, 0);
    }

    BloodDecalParticle(ClientLevel level, Vec3 position, Vec3 normal, int material,
                       float volumeMl, float size, long seed, long surfaceKey, int spriteFamily,
                       TextureAtlasSprite sprite, boolean contaminating,
                       float rotationRadians, int flowDepth) {
        super(level,
                position.x + normal.x * 0.003,
                position.y + normal.y * 0.003,
                position.z + normal.z * 0.003,
                sprite);
        Vec3 safeNormal = normal.lengthSqr() < 0.5 ? new Vec3(0.0, 1.0, 0.0) : normal.normalize();
        this.surfaceNormal = safeNormal;
        this.material = material;
        this.flowSeed = seed;
        this.flowDepth = Math.max(0, flowDepth);
        this.contaminating = contaminating;
        this.spriteFamily = spriteFamily;
        this.surfaceRotation = new Quaternionf().rotationTo(
                new Vector3f(0.0f, 0.0f, 1.0f),
                new Vector3f((float) safeNormal.x, (float) safeNormal.y, (float) safeNormal.z)
        ).rotateZ(Float.isFinite(rotationRadians) ? rotationRadians
                : BloodFxRules.unitFloat(seed ^ 0x94d049bb133111ebL) * ((float) Math.PI * 2.0f));
        this.volumeMl = Math.max(0.05f, volumeMl);
        this.targetSize = BloodVolumeRules.decalRadius(this.volumeMl);
        this.surfaceKey = surfaceKey;
        this.quadSize = targetSize * 0.18f;
        this.hasPhysics = false;
        this.lifetime = BloodFxRules.decalLifetimeTicks(material, size, seed);
        this.dryingTicks = lifetime;
        this.nextFlowAge = 5 + (int) (BloodFxRules.unitFloat(seed ^ 0x6a09e667f3bcc909L) * 8.0f);
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
                0, 0.55f, 0.45f, random.nextLong(), Long.MIN_VALUE, -1, sprites.get(random));
    }

    void absorb(float addedVolumeMl, float energy, long seed) {
        volumeMl = Math.min(80.0f, volumeMl + Math.max(0.0f, addedVolumeMl));
        targetSize = BloodVolumeRules.decalRadius(volumeMl);
        lifetime = Math.max(lifetime, age + 420 + (int) (BloodFxRules.unitFloat(seed) * 360.0f));
        wetAge = 0;
        dryingTicks = Math.max(260, lifetime - age);
        if (spriteFamily >= 0 && spriteStage != 0) {
            TextureAtlasSprite fresh = BloodParticleSprites.decal(seed, spriteFamily, 0);
            if (fresh != null) {
                setSprite(fresh);
                spriteStage = 0;
            }
        }
    }

    void synchronizeAuthoritative(float authoritativeVolumeMl, int authoritativeAge,
                                  int authoritativeLifetime, long seed) {
        volumeMl = Math.max(0.02f, Math.min(80.0f, authoritativeVolumeMl));
        targetSize = BloodVolumeRules.decalRadius(volumeMl);
        lifetime = Math.max(1, authoritativeLifetime);
        age = Math.max(0, Math.min(lifetime - 1, authoritativeAge));
        wetAge = age;
        dryingTicks = lifetime;
        int stage = BloodFxRules.decalStage(wetAge, dryingTicks);
        if (spriteFamily >= 0 && stage != spriteStage) {
            TextureAtlasSprite synchronizedSprite = BloodParticleSprites.decal(seed, spriteFamily, stage);
            if (synchronizedSprite != null) {
                setSprite(synchronizedSprite);
                spriteStage = stage;
            }
        }
    }

    boolean canWetFoot(Vec3 foot) {
        if (!contaminating || surfaceNormal.y < 0.72 || age > lifetime * 0.72f) return false;
        double radius = Math.max(0.08, targetSize * 1.12);
        double dx = foot.x - x;
        double dz = foot.z - z;
        return dx * dx + dz * dz <= radius * radius && Math.abs(foot.y - y) <= 0.11;
    }

    float transferableWetness() {
        return BloodFxRules.clamp01((1.0f - age / (float) Math.max(1, lifetime)) * targetSize * 4.4f);
    }

    long spatialBucket() {
        return BlockPos.containing(x - surfaceNormal.x * 0.02,
                y - surfaceNormal.y * 0.02, z - surfaceNormal.z * 0.02).asLong();
    }

    float takeWetness(Vec3 sample, float pressure) {
        if (!canWetFoot(sample)) return 0.0f;
        float visualWetness = transferableWetness();
        float transferMl = Math.min(volumeMl,
                0.08f + BloodFxRules.clamp01(pressure) * 0.24f);
        volumeMl = Math.max(0.02f, volumeMl - transferMl);
        targetSize = BloodVolumeRules.decalRadius(volumeMl);
        wetAge += Math.round(transferMl * 12.0f);
        return Math.min(visualWetness, transferMl / 0.32f);
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
        if ((age & 7) == 0 && !BloodFxClientState.surfaceStillExists(
                level, new Vec3(x, y, z), surfaceNormal)) {
            BloodFxClientState.detachDecal(level, new Vec3(x, y, z), surfaceNormal,
                    volumeMl, flowSeed ^ age, wetAge < dryingTicks);
            remove();
            return;
        }
        wetAge++;
        // Persisted wall flow is advanced once by the authoritative server. Running it here
        // would multiply one impact by the number of observing clients.
        float settle = Math.min(1.0f, age / 7.0f);
        quadSize = targetSize * (0.18f + 0.82f * (1.0f - (1.0f - settle) * (1.0f - settle)));
        int nextStage = BloodFxRules.decalStage(wetAge, dryingTicks);
        if (spriteFamily >= 0 && nextStage != spriteStage) {
            TextureAtlasSprite next = BloodParticleSprites.decal(0L, spriteFamily, nextStage);
            if (next != null) {
                setSprite(next);
                spriteStage = nextStage;
            }
        }
        float dried = 0.86f + BloodFxRules.driedColorFactor(wetAge, dryingTicks) * 0.14f;
        setColor(baseRed * dried, baseGreen * (0.72f + dried * 0.28f), baseBlue * 0.75f);
        if (age > lifetime - 80) {
            setAlpha(Math.max(0.0f, (lifetime - age) / 80.0f));
        }
    }

    private void tickSurfaceFlow() {
        if (Math.abs(surfaceNormal.y) > 0.25 || age < nextFlowAge
                || age > dryingTicks * 0.55f || flowDepth >= 14) {
            return;
        }
        int maxEmissions = flowDepth == 0
                ? Math.min(4, Math.max(1, (int) Math.ceil(volumeMl / 2.0f)))
                : 1;
        if (flowEmissions >= maxEmissions) return;
        float transferMl = BloodVolumeRules.wallFlowTransfer(volumeMl);
        if (transferMl <= 0.0f || volumeMl - transferMl < 0.12f) return;
        long childSeed = BloodFxRules.mix64(flowSeed + flowEmissions * 0x9e3779b97f4a7c15L + flowDepth * 131L);
        if (!BloodFxClientState.onSurfaceFlowStep(level, new Vec3(x, y, z), surfaceNormal,
                material, transferMl, childSeed, flowDepth + 1)) {
            nextFlowAge = age + 12;
            return;
        }
        volumeMl -= transferMl;
        targetSize = BloodVolumeRules.decalRadius(volumeMl);
        flowEmissions++;
        nextFlowAge = age + 7 + (int) (BloodFxRules.unitFloat(childSeed) * 7.0f);
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
        // Surface stains are alpha-cutout geometry. Opaque ordering prevents
        // intersecting translucent cards from exposing their rectangular bounds.
        return Layer.OPAQUE;
    }
}
