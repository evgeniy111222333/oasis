package ua.rp.chat.client.tab;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.font.TextAttribute;
import java.awt.font.TextLayout;
import java.awt.image.BufferedImage;
import java.util.Map;

/** Resolution-independent serif metrics and supersampled glyph coverage, independent of Minecraft's font atlas. */
public final class TabTextRaster {
    public static final int SAMPLING = 2;
    private static final int RASTER_SAMPLING = 4;
    private static final int PADDING = 2;
    private static final FontRenderContext CONTEXT = new FontRenderContext(null, true, true);

    private TabTextRaster() { }

    private static Font styled(Font face, String text, float size, float tracking) {
        Font selected = face.canDisplayUpTo(text) < 0 ? face : new Font(Font.SERIF, face.getStyle(), 12);
        return selected.deriveFont(size * RASTER_SAMPLING)
                .deriveFont(Map.of(TextAttribute.TRACKING, tracking / size));
    }

    public static float measure(Font face, String text, float size, float tracking) {
        return text.isEmpty() ? 0 : new TextLayout(text, styled(face, text, size, tracking), CONTEXT).getAdvance() / RASTER_SAMPLING;
    }

    public static Raster rasterize(Font face, String text, float size, float tracking) {
        if (text.isEmpty()) return new Raster(null, 0, 0, 0, 0, 0, 0);
        TextLayout layout = new TextLayout(text, styled(face, text, size, tracking), CONTEXT);
        float width = layout.getAdvance() / RASTER_SAMPLING;
        float height = (layout.getAscent() + layout.getDescent() + layout.getLeading()) / RASTER_SAMPLING;
        // Make the high-resolution canvas divisible by two so no edge texel is dropped by prefiltering.
        BufferedImage image = new BufferedImage(((int)Math.ceil(width * SAMPLING) + 2 * PADDING * SAMPLING) * 2,
                ((int)Math.ceil(height * SAMPLING) + 2 * PADDING * SAMPLING) * 2, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            graphics.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.setColor(Color.WHITE);
            layout.draw(graphics, PADDING * RASTER_SAMPLING, PADDING * RASTER_SAMPLING + layout.getAscent());
        } finally { graphics.dispose(); }
        var bounds = layout.getBounds();
        BufferedImage filtered = new BufferedImage(image.getWidth() / 2, image.getHeight() / 2, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < filtered.getHeight(); y++) for (int x = 0; x < filtered.getWidth(); x++) {
            int sx = x * 2, sy = y * 2;
            int alpha = ((image.getRGB(sx, sy) >>> 24) + (image.getRGB(sx + 1, sy) >>> 24)
                    + (image.getRGB(sx, sy + 1) >>> 24) + (image.getRGB(sx + 1, sy + 1) >>> 24) + 2) / 4;
            filtered.setRGB(x, y, alpha << 24 | 0x00FFFFFF);
        }
        image.flush();
        // A 2x texture with an area prefilter lets the native LINEAR sampler integrate the full 4x glyph coverage.
        return new Raster(filtered, width, height, (float)bounds.getX() / RASTER_SAMPLING,
                (layout.getAscent() + (float)bounds.getY()) / RASTER_SAMPLING,
                (float)bounds.getWidth() / RASTER_SAMPLING, (float)bounds.getHeight() / RASTER_SAMPLING);
    }

    public static int padding() { return PADDING; }
    public record Raster(BufferedImage image, float width, float height,
                         float inkLeft, float inkTop, float inkWidth, float inkHeight) { }
}
