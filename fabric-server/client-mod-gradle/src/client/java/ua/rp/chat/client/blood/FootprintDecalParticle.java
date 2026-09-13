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
import ua.rp.chat.blood.BloodFxRules;
import ua.rp.chat.blood.FootprintRules;

/** One long-lived, surface-aligned and weather-aware footprint quad. */
final class FootprintDecalParticle extends SingleQuadParticle {
    private static final double RENDER_DISTANCE_SQ = 72.0 * 72.0;

    private final long decalId;
    private final long mergeCell;
    private final int family;
    private final int material;
    private final Quaternionf rotation;
    private final float baseRed;
    private final float baseGreen;
    private final float baseBlue;
    private float wetness;
    private int stage = -1;
    private int weatherAge;
    private boolean completionReported;

    FootprintDecalParticle(ClientLevel level, long decalId, Vec3 position, float yaw,
                           float wetness, int foot, int gait, int material, long seed,
                           int footwear, int initialAge, int lifetime, TextureAtlasSprite sprite) {
        super(level, position.x, position.y + 0.003, position.z, sprite);
        this.decalId = decalId;
        this.mergeCell = FootprintRules.mergeCell(position.x, position.y, position.z);
        this.family = FootprintRules.variant(foot, gait, footwear, seed);
        this.material = material;
        this.wetness = BloodFxRules.clamp01(wetness);
        this.age = Math.max(0, initialAge);
        this.lifetime = Math.max(this.age + 20, lifetime);
        this.hasPhysics = false;
        this.quadSize = 0.145f + this.wetness * 0.065f
                + (gait == FootprintRules.GAIT_RUN ? 0.025f : 0.0f);
        this.rotation = new Quaternionf()
                .rotationX(-(float) Math.PI * 0.5f)
                .rotateZ(yaw + (float) Math.PI);
        float shade = switch (material) {
            case 1 -> 0.62f;
            case 2 -> 0.72f;
            case 3 -> 0.80f;
            case 4 -> 1.05f;
            case 5 -> 0.68f;
            default -> 0.90f;
        };
        baseRed = 0.49f * shade;
        baseGreen = 0.017f * shade;
        baseBlue = 0.012f * shade;
        updateVisual();
    }

    static FootprintDecalParticle fallback(ClientLevel level, double x, double y, double z,
                                            RandomSource random, FabricSpriteSet sprites) {
        return new FootprintDecalParticle(level, Long.MIN_VALUE, new Vec3(x, y, z), 0.0f,
                0.6f, 0, 0, 0, random.nextLong(), 0, 0, 1_200, sprites.get(random));
    }

    long decalId() {
        return decalId;
    }

    long mergeCell() {
        return mergeCell;
    }

    int ageTicks() {
        return age;
    }

    Vec3 position() {
        return new Vec3(x, y, z);
    }

    void refresh(float nextWetness, int nextAge, int nextLifetime, long seed) {
        wetness = Math.min(1.0f, Math.max(wetness, nextWetness) + nextWetness * 0.12f);
        age = Math.max(0, Math.min(age, nextAge));
        lifetime = Math.max(lifetime, nextLifetime);
        quadSize = Math.min(0.25f, quadSize + 0.008f * BloodFxRules.unitFloat(seed));
        updateVisual();
    }

    void synchronize(float nextWetness, int nextAge, int nextLifetime) {
        wetness = BloodFxRules.clamp01(nextWetness);
        age = Math.max(age, nextAge);
        lifetime = Math.max(age + 20, nextLifetime);
        updateVisual();
    }

    float takeWetness(Vec3 sample, float pressure) {
        if (age > lifetime * 0.58f || wetness < 0.035f) return 0.0f;
        double dx = sample.x - x;
        double dz = sample.z - z;
        double radius = quadSize * 0.72;
        if (dx * dx + dz * dz > radius * radius || Math.abs(sample.y - y) > 0.10) return 0.0f;
        float available = wetness * 0.16f * BloodFxRules.clamp01(pressure);
        wetness = Math.max(0.0f, wetness - available * 0.44f);
        return available;
    }

    @Override
    public void tick() {
        xo = x;
        yo = y;
        zo = z;
        int advance = 1;
        if ((age & 19) == 0) {
            BlockPos pos = BlockPos.containing(x, y, z);
            if (!level.getFluidState(pos).isEmpty()) {
                remove();
                return;
            }
            if (level.isRainingAt(pos.above())) weatherAge += 100;
        }
        advance += weatherAge > 0 ? 2 : 0;
        weatherAge = Math.max(0, weatherAge - 1);
        age += advance;
        if (age >= lifetime) {
            remove();
            return;
        }
        updateVisual();
        if (age > lifetime - 160) {
            setAlpha(Math.max(0.0f, (lifetime - age) / 160.0f));
        }
    }

    private void updateVisual() {
        int nextStage = FootprintRules.stage(wetness, age, lifetime);
        if (nextStage != stage) {
            TextureAtlasSprite sprite = BloodParticleSprites.footprint(family, nextStage);
            if (sprite != null) setSprite(sprite);
            stage = nextStage;
        }
        float dry = BloodFxRules.driedColorFactor(age, lifetime);
        setColor(baseRed * (0.82f + dry * 0.18f),
                baseGreen * (0.58f + dry * 0.42f),
                baseBlue * 0.72f);
    }

    @Override
    public void extract(QuadParticleRenderState state, Camera camera, float partialTick) {
        Vec3 cameraPosition = camera.position();
        double dx = cameraPosition.x - x;
        double dy = cameraPosition.y - y;
        double dz = cameraPosition.z - z;
        if (dx * dx + dy * dy + dz * dz <= RENDER_DISTANCE_SQ) {
            extractRotatedQuad(state, camera, rotation, partialTick);
        }
    }

    @Override
    public void remove() {
        super.remove();
        if (!completionReported) {
            completionReported = true;
            BloodFxClientState.onFootprintRemoved(decalId, this);
        }
    }

    @Override
    protected Layer getLayer() {
        return Layer.OPAQUE;
    }
}
