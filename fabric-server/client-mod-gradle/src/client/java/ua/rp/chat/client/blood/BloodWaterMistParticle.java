package ua.rp.chat.client.blood;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import ua.rp.chat.blood.BloodFxRules;

/** Brief, non-emissive dilution cloud when a droplet enters water. */
final class BloodWaterMistParticle extends SingleQuadParticle {
    private final float targetSize;
    private boolean completionReported;

    BloodWaterMistParticle(ClientLevel level, double x, double y, double z,
                           float energy, long seed, TextureAtlasSprite sprite) {
        super(level, x, y, z, sprite);
        hasPhysics = false;
        lifetime = 18 + (int) (BloodFxRules.unitFloat(seed) * 16.0f);
        targetSize = 0.09f + BloodFxRules.clamp01(energy) * 0.13f;
        quadSize = targetSize * 0.35f;
        xd = (BloodFxRules.unitFloat(seed ^ 0x632be59bd9b4e019L) - 0.5f) * 0.006;
        yd = 0.002 + BloodFxRules.unitFloat(seed ^ 0x8cb92baa3f3d8dd7L) * 0.004;
        zd = (BloodFxRules.unitFloat(seed ^ 0x9e3779b97f4a7c15L) - 0.5f) * 0.006;
        setColor(0.40f, 0.012f, 0.009f);
        setAlpha(0.62f);
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
        move(xd, yd, zd);
        xd *= 0.86;
        yd *= 0.90;
        zd *= 0.86;
        float t = age / (float) lifetime;
        quadSize = targetSize * (0.35f + t * 0.65f);
        setAlpha(Math.max(0.0f, (1.0f - t) * 0.62f));
    }

    @Override
    public void remove() {
        super.remove();
        if (!completionReported) {
            completionReported = true;
            BloodFxClientState.onDecalRemoved(Long.MIN_VALUE, null);
        }
    }

    @Override
    protected Layer getLayer() {
        return Layer.TRANSLUCENT;
    }
}
