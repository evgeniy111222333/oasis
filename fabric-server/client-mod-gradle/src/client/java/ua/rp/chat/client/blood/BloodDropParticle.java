package ua.rp.chat.client.blood;

import net.fabricmc.fabric.api.client.particle.v1.FabricSpriteSet;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.shapes.CollisionContext;
import ua.rp.chat.blood.BloodFxRules;

final class BloodDropParticle extends SingleQuadParticle {
    private final long seed;
    private final float impactEnergy;
    private final float volumeMl;
    private boolean completionReported;

    BloodDropParticle(ClientLevel level, double x, double y, double z,
                      double vx, double vy, double vz, float size,
                      float volumeMl, long seed, TextureAtlasSprite sprite) {
        super(level, x, y, z, vx, vy, vz, sprite);
        this.seed = seed;
        this.volumeMl = Math.max(0.05f, volumeMl);
        this.impactEnergy = Math.min(1.0f, (float) Math.sqrt(vx * vx + vy * vy + vz * vz) * 4.2f + size);
        this.xd = vx;
        this.yd = vy;
        this.zd = vz;
        this.quadSize = 0.035f + BloodFxRules.clamp01(size) * 0.075f;
        this.gravity = 0.78f;
        this.friction = 0.965f;
        this.hasPhysics = true;
        this.lifetime = 26 + (int) (BloodFxRules.unitFloat(seed) * 24.0f);
        this.roll = BloodFxRules.unitFloat(seed ^ 0x51ed270bL) * ((float) Math.PI * 2.0f);
        this.oRoll = this.roll;
        float shade = 0.82f + BloodFxRules.unitFloat(seed ^ 0x9e3779b97f4a7c15L) * 0.18f;
        setColor(0.48f * shade, 0.018f * shade, 0.012f * shade);
        setSize(0.035f, 0.035f);
    }

    static BloodDropParticle fallback(ClientLevel level, double x, double y, double z,
                                      double vx, double vy, double vz, RandomSource random,
                                      FabricSpriteSet sprites) {
        return new BloodDropParticle(level, x, y, z, vx, vy, vz, 0.45f,
                0.55f, random.nextLong(), sprites.get(random));
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

        yd -= 0.04 * gravity;
        double requestedX = xd;
        double requestedY = yd;
        double requestedZ = zd;
        double beforeX = x;
        double beforeY = y;
        double beforeZ = z;
        Vec3 start = new Vec3(beforeX, beforeY, beforeZ);
        Vec3 requestedEnd = start.add(requestedX, requestedY, requestedZ);
        BlockHitResult rayHit = level.clip(new ClipContext(start, requestedEnd,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty()));
        if (rayHit.getType() == HitResult.Type.BLOCK) {
            Vec3 normal = Vec3.atLowerCornerOf(rayHit.getDirection().getUnitVec3i());
            BloodFxClientState.onDropCollision(level,
                    rayHit.getLocation().add(normal.scale(0.002)), normal, impactEnergy, volumeMl, seed);
            remove();
            return;
        }
        move(requestedX, requestedY, requestedZ);
        double movedX = x - beforeX;
        double movedY = y - beforeY;
        double movedZ = z - beforeZ;

        if (!level.getFluidState(BlockPos.containing(x, y, z)).isEmpty()) {
            BloodFxClientState.onDropEnterFluid(level, new Vec3(x, y, z), impactEnergy, seed);
            remove();
            return;
        }

        boolean blockedX = Math.abs(movedX - requestedX) > 1.0e-5;
        boolean blockedY = Math.abs(movedY - requestedY) > 1.0e-5;
        boolean blockedZ = Math.abs(movedZ - requestedZ) > 1.0e-5;
        if (onGround || blockedX || blockedY || blockedZ) {
            Vec3 normal;
            if (onGround || blockedY) {
                normal = new Vec3(0.0, requestedY <= 0.0 ? 1.0 : -1.0, 0.0);
            } else if (blockedX) {
                normal = new Vec3(requestedX <= 0.0 ? 1.0 : -1.0, 0.0, 0.0);
            } else {
                normal = new Vec3(0.0, 0.0, requestedZ <= 0.0 ? 1.0 : -1.0);
            }
            BloodFxClientState.onDropCollision(level, new Vec3(x, y, z), normal,
                    impactEnergy, volumeMl, seed);
            remove();
            return;
        }

        xd *= friction;
        yd *= 0.985;
        zd *= friction;
        oRoll = roll;
        roll += (float) Math.copySign(Math.min(0.34, Math.hypot(xd, zd) * 1.8), xd + zd);
        if (age > lifetime - 6) {
            setAlpha(Math.max(0.0f, (lifetime - age) / 6.0f));
        }
    }

    @Override
    public void remove() {
        super.remove();
        if (!completionReported) {
            completionReported = true;
            BloodFxClientState.onDropRemoved();
        }
    }

    @Override
    protected Layer getLayer() {
        return Layer.TRANSLUCENT;
    }
}
