package ua.rp.chat.client.tab;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import org.slf4j.LoggerFactory;

import java.awt.Font;
import java.io.IOException;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Set;

/** Small, bounded cache of antialiased text. Rasterization/upload only occurs for changed visible labels. */
public final class AstrolabeText {
    private static final int MAX_LABELS = 64, MAX_PIXELS = 4_194_304;
    private static final Identifier REGULAR = Identifier.fromNamespaceAndPath("eclipseclient", "font/source_serif_4_regular.ttf");
    private static final Identifier SEMIBOLD = Identifier.fromNamespaceAndPath("eclipseclient", "font/source_serif_4_semibold.ttf");
    private static final LinkedHashMap<Key, Label> CACHE = new LinkedHashMap<>(16, .75f, true);
    private static Font face = new Font(Font.SERIF, Font.PLAIN, 12);
    private static Font boldFace = face.deriveFont(Font.BOLD);
    private static int pixels;
    private static long serial, uploads;

    private AstrolabeText() { }

    public static void reload(ResourceManager resources) {
        for (Label label : CACHE.values()) release(label);
        CACHE.clear(); pixels = 0;
        // Bundle both physical faces, giving every player the same legible typography without synthetic bold.
        face = load(resources, REGULAR, new Font(Font.SERIF, Font.PLAIN, 12));
        boldFace = load(resources, SEMIBOLD, face.deriveFont(Font.BOLD));
    }

    private static Font load(ResourceManager resources, Identifier resource, Font fallback) {
        try (var input = resources.getResourceOrThrow(resource).open()) {
            return Font.createFont(Font.TRUETYPE_FONT, input);
        } catch (IOException | java.awt.FontFormatException failure) {
            LoggerFactory.getLogger(AstrolabeText.class).warn("Unable to load astrolabe face {}; using fallback", resource, failure);
            return fallback;
        }
    }

    public static float measure(String text, float size, float tracking) {
        return measure(text, size, tracking, false);
    }

    public static float measure(String text, float size, float tracking, boolean bold) {
        return TabTextRaster.measure(bold ? boldFace : face, text, size, tracking);
    }

    public static Label fit(String text, int capacity, float size, float tracking) {
        return fit(text, capacity, size, tracking, false);
    }

    public static Label fit(String text, int capacity, float size, float tracking, boolean bold) {
        return prepare(TabText.ellipsize(text, capacity, value -> (int)Math.ceil(measure(value, size, tracking, bold))), size, tracking, bold);
    }

    public static Label prepare(String text, float size, float tracking) {
        return prepare(text, size, tracking, false);
    }

    public static Label prepare(String text, float size, float tracking, boolean bold) {
        Key key = new Key(text, size, tracking, bold);
        Label existing = CACHE.get(key);
        if (existing != null) return existing;
        var raster = TabTextRaster.rasterize(bold ? boldFace : face, text, size, tracking);
        if (raster.image() == null) return new Label(null, 0, 0, 0, 0, 0, 0, 0, 0);
        var image = raster.image();
        NativeImage nativeImage = new NativeImage(image.getWidth(), image.getHeight(), true);
        for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) {
            // White RGB in transparent texels prevents dark fringes when linearly sampling rotated glyph edges.
            nativeImage.setPixel(x, y, (image.getRGB(x, y) & 0xFF000000) | 0x00FFFFFF);
        }
        Identifier texture = Identifier.fromNamespaceAndPath("eclipseclient", "tab_text/" + serial++);
        Minecraft.getInstance().getTextureManager().register(texture, new SmoothTexture(nativeImage));
        Label label = new Label(texture, raster.width(), raster.height(), image.getWidth(), image.getHeight(),
                raster.inkLeft(), raster.inkTop(), raster.inkWidth(), raster.inkHeight());
        CACHE.put(key, label); pixels += label.sourceWidth * label.sourceHeight; uploads++;
        image.flush();
        return label;
    }

    /** Only call after visible labels change; active labels cannot be evicted while referenced by the renderer. */
    public static void trim(Collection<Label> visible) {
        if (CACHE.size() <= MAX_LABELS && pixels <= MAX_PIXELS) return;
        Set<Label> protectedLabels = new HashSet<>(visible);
        var iterator = CACHE.entrySet().iterator();
        while ((CACHE.size() > MAX_LABELS || pixels > MAX_PIXELS) && iterator.hasNext()) {
            Label label = iterator.next().getValue();
            if (protectedLabels.contains(label)) continue;
            release(label); pixels -= label.sourceWidth * label.sourceHeight; iterator.remove();
        }
    }

    private static void release(Label label) {
        if (label.texture != null) Minecraft.getInstance().getTextureManager().release(label.texture);
    }

    public static void draw(GuiGraphicsExtractor graphics, Label label, float x, float y, int color) {
        if (label == null || label.texture == null || (color >>> 24) == 0) return;
        drawTexture(graphics, label, x + .7f, y + 1.2f, Math.round((color >>> 24) * .65f) << 24);
        drawTexture(graphics, label, x, y, color);
    }

    public static void drawCentered(GuiGraphicsExtractor graphics, Label label, float centerX, float centerY, int color) {
        draw(graphics, label, centerX - label.inkLeft - label.inkWidth / 2,
                centerY - label.inkTop - label.inkHeight / 2, color);
    }

    private static void drawTexture(GuiGraphicsExtractor graphics, Label label, float x, float y, int color) {
        graphics.pose().pushMatrix();
        try {
            graphics.pose().translate(x - TabTextRaster.padding(), y - TabTextRaster.padding());
            graphics.pose().scale(1f / TabTextRaster.SAMPLING, 1f / TabTextRaster.SAMPLING);
            graphics.blit(RenderPipelines.GUI_TEXTURED, label.texture, 0, 0, 0, 0,
                    label.sourceWidth, label.sourceHeight, label.sourceWidth, label.sourceHeight,
                    label.sourceWidth, label.sourceHeight, color);
        } finally { graphics.pose().popMatrix(); }
    }

    public static int cachedLabels() { return CACHE.size(); }
    public static int cachedPixels() { return pixels; }
    public static long uploads() { return uploads; }
    public static String family() { return face.getFamily(Locale.ROOT); }
    public record Label(Identifier texture, float width, float height, int sourceWidth, int sourceHeight,
                        float inkLeft, float inkTop, float inkWidth, float inkHeight) { }
    private record Key(String text, float size, float tracking, boolean bold) { }
    private static final class SmoothTexture extends DynamicTexture {
        private SmoothTexture(NativeImage image) {
            super(() -> "Astrolabe antialiased text", image);
            sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
        }
    }
}
