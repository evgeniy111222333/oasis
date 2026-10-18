package ua.rp.chat.client.blood;

import net.fabricmc.fabric.api.client.particle.v1.FabricSpriteSet;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import ua.rp.chat.blood.BloodFxRules;

import java.util.UUID;

/** A thin surface mark that follows the wounded animated body region. */
final class BloodWoundParticle extends SingleQuadParticle {
    private int entityId;
    private UUID entityUuid;
    private int zone;
    private float localSide;
    private float localHeight;
    private float intensity;
    private int flags;
    private long seed;
    private int missingTicks;
    private final Quaternionf surfaceRotation = new Quaternionf();

    BloodWoundParticle(ClientLevel level, int entityId, UUID entityUuid, int zone,
                       float localSide, float localHeight, float intensity,
                       int profile, int flags, long seed, TextureAtlasSprite sprite) {
        super(level, 0.0, -2048.0, 0.0, sprite);
        this.hasPhysics = false;
        this.lifetime = Integer.MAX_VALUE;
        update(entityId, entityUuid, zone, localSide, localHeight, intensity, profile, flags, seed);
    }

    static BloodWoundParticle fallback(ClientLevel level, double x, double y, double z,
                                       RandomSource random, FabricSpriteSet sprites) {
        BloodWoundParticle particle = new BloodWoundParticle(level, Integer.MIN_VALUE,
                new UUID(0L, 0L), 1, 0.0f, 0.5f, 0.5f,
                0, 0, random.nextLong(), sprites.get(random));
        particle.setPos(x, y, z);
        particle.missingTicks = -1000;
        return particle;
    }

    void update(int entityId, UUID entityUuid, int zone, float localSide, float localHeight,
                float intensity, int profile, int flags, long seed) {
        this.entityId = entityId;
        this.entityUuid = entityUuid;
        this.zone = zone;
        this.localSide = localSide;
        this.localHeight = localHeight;
        this.intensity = BloodFxRules.clamp01(intensity);
        this.flags = flags;
        this.seed = seed;
        this.quadSize = 0.065f + this.intensity * 0.075f;
        int spriteIndex = (flags & BloodFxPayload.FLAG_BANDAGED) != 0
                ? 3
                : profile == 1 ? 1 : (BloodFxRules.unitFloat(seed) > 0.48f ? 2 : 0);
        TextureAtlasSprite next = BloodParticleSprites.wound(seed, spriteIndex);
        if (next != null) setSprite(next);
        float shade = (flags & BloodFxPayload.FLAG_BANDAGED) != 0 ? 0.66f : 0.88f;
        setColor(0.49f * shade, 0.018f * shade, 0.012f * shade);
    }

    @Override
    public void tick() {
        xo = x;
        yo = y;
        zo = z;
        Entity entity = level.getEntity(entityId);
        if (entity == null || !entity.getUUID().equals(entityUuid) || !entity.isAlive()) {
            if (++missingTicks > 40) remove();
            return;
        }
        missingTicks = 0;
        BloodFxClientState.Anchor anchor = BloodFxClientState.anchorFor(
                entity, zone, localSide, localHeight, seed, true);
        setPos(anchor.position().x, anchor.position().y, anchor.position().z);
        Vector3f normal = new Vector3f(
                (float) anchor.normal().x,
                (float) anchor.normal().y,
                (float) anchor.normal().z
        );
        surfaceRotation.rotationTo(new Vector3f(0.0f, 0.0f, 1.0f), normal)
                .rotateZ((BloodFxRules.unitFloat(seed ^ 0xd1b54a32d192ed03L) - 0.5f) * 0.72f);
    }

    @Override
    public void extract(QuadParticleRenderState state, Camera camera, float partialTick) {
        extractRotatedQuad(state, camera, surfaceRotation, partialTick);
    }

    @Override
    protected Layer getLayer() {
        return Layer.TRANSLUCENT;
    }
}
