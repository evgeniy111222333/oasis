package ua.rp.chat.client.blood;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerSkin;
import ua.rp.chat.blood.BloodFxRules;
import ua.rp.chat.blood.BloodSkinUv;
import ua.rp.chat.client.appearance.EclipseTextureAsset;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Builds a temporary skin texture whose wound pixels move with every real model bone. */
public final class BloodSkinTextureManager {
    private static final Map<UUID, Entry> CACHE = new HashMap<>();
    private static long generation;

    private BloodSkinTextureManager() {
    }

    public static PlayerSkin apply(UUID uuid, PlayerSkin base) {
        Minecraft client = Minecraft.getInstance();
        if (uuid == null || base == null || client == null || base.body() == null) return base;
        List<BloodFxClientState.SkinWound> wounds = BloodFxClientState.skinWounds(uuid);
        if (wounds.isEmpty()) {
            release(uuid);
            return base;
        }
        Identifier baseId = base.body().texturePath();
        long fingerprint = fingerprint(baseId, wounds);
        Entry current = CACHE.get(uuid);
        if (current != null && current.fingerprint == fingerprint) return current.skin;

        var sourceTexture = client.getTextureManager().getTexture(baseId);
        if (!(sourceTexture instanceof DynamicTexture dynamic) || dynamic.getPixels() == null) return base;
        NativeImage source = dynamic.getPixels();
        if (source.getWidth() < 64 || source.getHeight() < 64
                || source.getWidth() % 64 != 0 || source.getHeight() % 64 != 0) return base;

        NativeImage composite = new NativeImage(source.getWidth(), source.getHeight(), false);
        composite.copyFrom(source);
        int scale = source.getWidth() / 64;
        for (BloodFxClientState.SkinWound wound : wounds) paint(composite, scale, wound);

        Identifier id = Identifier.fromNamespaceAndPath("eclipseclient",
                "blood_skin/" + uuid.toString().replace("-", "_") + "/" + (++generation));
        DynamicTexture texture = new DynamicTexture(() -> "Blood-marked skin " + uuid, composite);
        client.getTextureManager().register(id, texture);
        PlayerSkin skin = PlayerSkin.insecure(new EclipseTextureAsset(id), base.cape(), base.elytra(), base.model());
        release(uuid);
        CACHE.put(uuid, new Entry(fingerprint, id, skin));
        return skin;
    }

    public static void invalidate(UUID uuid) {
        if (uuid != null) release(uuid);
    }

    public static void reset() {
        for (UUID uuid : List.copyOf(CACHE.keySet())) release(uuid);
    }

    private static void paint(NativeImage image, int scale, BloodFxClientState.SkinWound wound) {
        int radius = Math.max(1, Math.round((0.8f + wound.intensity() * 1.7f) * scale));
        for (BloodSkinUv.Point point : BloodSkinUv.points(
                wound.zone(), wound.face(), wound.side(), wound.height())) {
            int cx = point.x() * scale + scale / 2;
            int cy = point.y() * scale + scale / 2;
            int minX = point.face().x() * scale;
            int maxX = (point.face().x() + point.face().width()) * scale - 1;
            int minY = point.face().y() * scale;
            int maxY = (point.face().y() + point.face().height()) * scale - 1;
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dx = -radius; dx <= radius; dx++) {
                    long entropy = BloodFxRules.mix64(wound.seed() + dx * 0x9e3779b9L + dy * 0x632be59bL);
                    float distance = (float) Math.sqrt(dx * dx + dy * dy) / Math.max(1.0f, radius);
                    float ragged = BloodFxRules.unitFloat(entropy) * 0.38f;
                    if (distance > 0.72f + ragged) continue;
                    int x = Math.max(minX, Math.min(maxX, cx + dx));
                    int y = Math.max(minY, Math.min(maxY, cy + dy));
                    float edge = BloodFxRules.clamp01(1.15f - distance);
                    int alpha = Math.round((70 + wound.intensity() * 150) * edge);
                    int color = wound.profile() == 3 ? 0xff4b211a
                            : ((wound.flags() & BloodFxPayload.FLAG_BANDAGED) != 0 ? 0xff795046 : 0xff650a08);
                    image.setPixel(x, y, blend(image.getPixel(x, y), color, alpha));
                }
            }
        }
    }

    private static int blend(int destination, int source, int alpha) {
        int a = Math.max(0, Math.min(255, alpha));
        int inv = 255 - a;
        int r = (((source >>> 16) & 255) * a + ((destination >>> 16) & 255) * inv) / 255;
        int g = (((source >>> 8) & 255) * a + ((destination >>> 8) & 255) * inv) / 255;
        int b = ((source & 255) * a + (destination & 255) * inv) / 255;
        int outA = Math.max(destination >>> 24, a);
        return outA << 24 | r << 16 | g << 8 | b;
    }

    private static long fingerprint(Identifier base, List<BloodFxClientState.SkinWound> wounds) {
        long hash = BloodFxRules.mix64(base.hashCode());
        for (BloodFxClientState.SkinWound wound : wounds) {
            hash = BloodFxRules.mix64(hash ^ wound.woundId() ^ wound.seed()
                    ^ ((long) Float.floatToIntBits(wound.intensity()) << 32) ^ wound.flags());
        }
        return hash;
    }

    private static void release(UUID uuid) {
        Entry old = CACHE.remove(uuid);
        Minecraft client = Minecraft.getInstance();
        if (old != null && client != null) client.getTextureManager().release(old.textureId);
    }

    private record Entry(long fingerprint, Identifier textureId, PlayerSkin skin) {
    }
}
