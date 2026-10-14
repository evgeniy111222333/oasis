package ua.rp.chat.client.tab;

import java.awt.Font;

/** Checks the actual glyph image, rather than merely accepting a .ttf filename as smooth rendering. */
public final class TabTextRasterTest {
    public static void main(String[] args) {
        Font face = new Font(Font.SERIF, Font.PLAIN, 12);
        String text = "Остап · Ровена · Day 18 - Eventide";
        var raster = TabTextRaster.rasterize(face, text, 15, .5f);
        int covered = 0, smoothed = 0;
        for (int y = 0; y < raster.image().getHeight(); y++) for (int x = 0; x < raster.image().getWidth(); x++) {
            int alpha = raster.image().getRGB(x, y) >>> 24;
            if (alpha > 0) covered++;
            if (alpha > 0 && alpha < 255) smoothed++;
        }
        require(covered > 1000, "Cyrillic and Latin glyphs must actually be present");
        require(smoothed > 100, "Glyph edges must contain antialiased coverage rather than binary Minecraft-style pixels");
        require(Math.abs(raster.width() - TabTextRaster.measure(face, text, 15, .5f)) < .01f,
                "Fit metrics must match the drawn label");
        require(raster.image().getWidth() >= raster.width() * TabTextRaster.SAMPLING,
                "Text must retain supersampling until the native linear-filtered draw");
        require(TabTextRaster.measure(face, "Ровена", 15, 1) > TabTextRaster.measure(face, "Ровена", 15, 0),
                "Reference letter spacing must affect both drawing and truncation");
        String cut = TabText.ellipsize("Остап из далёких северных земель", 85,
                value -> (int)Math.ceil(TabTextRaster.measure(face, value, 15, .5f)));
        require(cut.endsWith("…") && TabTextRaster.measure(face, cut, 15, .5f) <= 85, "Long Cyrillic labels must fit");
        require(TabTextRaster.rasterize(face, "", 15, 0).image() == null, "An empty clipped name needs no GPU upload");
        minificationCoverage(face);
        System.out.println("TabTextRasterTest: smooth Cyrillic/Latin edges, supersampling, spacing and truncation passed");
    }

    private static void minificationCoverage(Font face) {
        for (String text : new String[]{"Day 38 - Dawn", "ВСЕ В МИРЕ", "Иван Петрович"}) {
            for (float size : new float[]{10.5f, 15, 22}) {
                var image = TabTextRaster.rasterize(face, text, size, .5f).image();
                int scale = TabTextRaster.SAMPLING;
                long source = 0;
                double displayed = 0;
                for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++)
                    source += image.getRGB(x, y) >>> 24;
                // Actual LINEAR sampling footprint when the texture is drawn in physical screen pixels.
                for (int y = 0; y < image.getHeight() / scale; y++) for (int x = 0; x < image.getWidth() / scale; x++) {
                    int sx = x * scale + scale / 2 - 1, sy = y * scale + scale / 2 - 1;
                    displayed += ((image.getRGB(sx, sy) >>> 24) + (image.getRGB(sx + 1, sy) >>> 24)
                            + (image.getRGB(sx, sy + 1) >>> 24) + (image.getRGB(sx + 1, sy + 1) >>> 24)) / 4d;
                }
                double reference = source / (double)(scale * scale);
                require(Math.abs(displayed - reference) / reference < .015,
                        "GPU minification must preserve glyph coverage, rather than dropping thin strokes: " + text + " at " + size);
            }
        }
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
