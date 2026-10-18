package ua.rp.chat.client.tab;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.fabricmc.fabric.api.resource.v1.reloader.ResourceReloaderKeys;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/** The compact three-column Eclipse TAB. No skin loading, blur pass, or per-frame roster sorting. */
public final class EclipseTabRenderer {
    private static final Identifier SCRIM = asset("scrim");
    private static final Identifier ECLIPSE = asset("eclipse");
    private static final Identifier DIVIDER = asset("divider");
    private static final Style SERIF = Style.EMPTY.withFont(new FontDescription.Resource(
            Identifier.fromNamespaceAndPath("eclipseclient", "tab_serif")));
    private static final Component BRAND = text("E C L I P S E");
    private static final Component EMPTY = text("Мир ждёт странников");
    private static final TabAnimation ANIMATION = new TabAnimation();
    private static KeyMapping previousPageKey;
    private static KeyMapping nextPageKey;
    private static boolean requested;
    private static boolean wasOpen;
    private static int page;
    private static double wheelRemainder;
    private static TabLayout layout;
    private static long cachedRevision = -1;
    private static List<RenderedName> names = List.of();
    private static Component countText;
    private static Component pageText;
    private static Component brandText;
    private static Component emptyText;
    private static int emptyWidth;
    private static int countWidth;
    private static int pageWidth;
    private static int cachedWidth;
    private static int cachedHeight;
    private static int cachedPage = -1;
    private static long fontGeneration;
    private static long cachedFontGeneration = -1;

    private EclipseTabRenderer() { }

    public static void register() {
        previousPageKey = key("key.eclipseclient.tab_previous_page", GLFW.GLFW_KEY_PAGE_UP);
        nextPageKey = key("key.eclipseclient.tab_next_page", GLFW.GLFW_KEY_PAGE_DOWN);
        Identifier reloadId = Identifier.fromNamespaceAndPath("eclipseclient", "tab_font_metrics");
        ResourceLoader loader = ResourceLoader.get(PackType.CLIENT_RESOURCES);
        loader.registerReloadListener(reloadId, (ResourceManagerReloadListener) resources -> {
            // Reload applies on the client thread, after fonts. Never retain stale glyph widths.
            fontGeneration++;
            invalidate();
        });
        loader.addListenerOrdering(ResourceReloaderKeys.Client.FONTS, reloadId);
    }

    private static KeyMapping key(String translation, int code) {
        return KeyMappingHelper.registerKeyMapping(new KeyMapping(translation, InputConstants.Type.KEYSYM,
                code, KeyMapping.Category.MULTIPLAYER));
    }

    private static boolean playable(Minecraft client) {
        return client != null && client.player != null && client.level != null && client.screen == null
                && client.getOverlay() == null && !client.options.hideGui && client.isWindowActive();
    }

    public static boolean wantsOpen(Minecraft client) {
        return playable(client) && (requested || client.options.keyPlayerList.isDown());
    }

    public static boolean isVisible(Minecraft client) {
        return playable(client) && (wantsOpen(client) || ANIMATION.visible());
    }

    public static void setRequested(boolean value) { requested = value; }

    public static void tick(Minecraft client) {
        if (!playable(client)) {
            requested = false;
            wasOpen = false;
            ANIMATION.reset();
            wheelRemainder = 0;
        }
        if (previousPageKey == null) return;
        while (previousPageKey.consumeClick()) if (wantsOpen(client)) changePage(-1);
        while (nextPageKey.consumeClick()) if (wantsOpen(client)) changePage(1);
    }

    /** Consumes scrolling only while TAB is held; never changes the player's hotbar slot. */
    public static boolean scroll(double amount) {
        if (!wantsOpen(Minecraft.getInstance()) || !Double.isFinite(amount)) return false;
        wheelRemainder += Math.clamp(amount, -10, 10);
        int steps = (int) wheelRemainder;
        if (steps != 0) {
            wheelRemainder -= steps;
            changePage(-steps);
        }
        return true;
    }

    private static void changePage(int step) {
        if (layout != null && layout.pages() > 1) page = Math.floorMod(page + step, layout.pages());
    }

    public static void reset() {
        requested = false;
        wasOpen = false;
        page = 0;
        wheelRemainder = 0;
        ANIMATION.reset();
        layout = null;
        names = List.of();
        invalidate();
    }

    private static void invalidate() { cachedRevision = -1; }

    public static void render(GuiGraphicsExtractor graphics, int width, int height, Minecraft client, TabRoster roster) {
        if (!playable(client)) { reset(); return; }
        boolean open = wantsOpen(client);
        if (open && !wasOpen) { page = 0; wheelRemainder = 0; invalidate(); }
        wasOpen = open;
        float opacity = ANIMATION.advance(open, System.nanoTime());
        if (opacity <= 0 || width < 96 || height < 80) return;
        ensureLayout(client.font, width, height, roster);
        // Header and names move together; no stagger that hides the last names on a quick tap.
        int shift = -Math.round((1 - opacity) * 3);
        graphics.pose().pushMatrix();
        try {
            graphics.pose().translate(0, shift);
            sprite(graphics, SCRIM, layout.x() - 26, layout.y() - 12,
                    layout.width() + 52, layout.height() + 32, 256, 128, tint(0xFFFFFFFF, opacity));
            drawHeader(graphics, client.font, opacity);
            for (RenderedName name : names) {
                if (name.self) drawSelfDot(graphics, name.x - 8, name.y + 4, opacity);
                graphics.text(client.font, name.text, name.x, name.y,
                        tint(name.self ? 0xFFF2D298 : 0xFFF0ECE4, opacity), true);
            }
            if (names.isEmpty()) {
                graphics.text(client.font, emptyText, width / 2 - emptyWidth / 2,
                        layout.y() + TabLayout.LIST_TOP, tint(0xFFB5B1AB, opacity), true);
            }
            if (pageText != null) {
                graphics.text(client.font, pageText, width / 2 - pageWidth / 2,
                        layout.y() + layout.height() - 12, tint(0xFFA3A29C, opacity * 0.85f), true);
            }
        } finally {
            graphics.pose().popMatrix();
        }
    }

    private static void ensureLayout(Font font, int width, int height, TabRoster roster) {
        if (cachedRevision == roster.revision() && cachedWidth == width && cachedHeight == height
                && cachedPage == page && cachedFontGeneration == fontGeneration) return;
        layout = TabLayout.calculate(width, height, roster.entries().size(), page);
        page = layout.page();
        ArrayList<RenderedName> next = new ArrayList<>(layout.visible());
        for (int i = 0; i < layout.visible(); i++) {
            TabRoster.Entry entry = roster.entries().get(layout.first() + i);
            String label = TabText.ellipsize(entry.label(), layout.nameCapacity(), value -> font.width(text(value)));
            next.add(new RenderedName(text(label), layout.textX(i), layout.textY(i), entry.self()));
        }
        names = List.copyOf(next);
        countText = fit(font, roster.entries().size() + " в мире", layout.width() - 24);
        countWidth = font.width(countText);
        int brandCapacity = layout.width() - countWidth - 40;
        brandText = brandCapacity >= font.width(BRAND) ? BRAND
                : brandCapacity >= font.width(text("ECLIPSE")) ? text("ECLIPSE") : null;
        emptyText = fit(font, EMPTY.getString(), layout.width() - 16);
        emptyWidth = font.width(emptyText);
        String previousKey = pageKeyName(previousPageKey, GLFW.GLFW_KEY_PAGE_UP, "PgUp");
        String nextKey = pageKeyName(nextPageKey, GLFW.GLFW_KEY_PAGE_DOWN, "PgDn");
        pageText = layout.pages() > 1 ? fit(font,
                (page + 1) + " / " + layout.pages() + "   ·   ↕ / " + previousKey + " / " + nextKey,
                layout.width() - 16) : null;
        pageWidth = pageText == null ? 0 : font.width(pageText);
        cachedRevision = roster.revision();
        cachedWidth = width;
        cachedHeight = height;
        cachedPage = page;
        cachedFontGeneration = fontGeneration;
    }

    private static void drawHeader(GuiGraphicsExtractor graphics, Font font, float opacity) {
        int x = layout.x();
        int y = layout.y();
        sprite(graphics, ECLIPSE, x, y - 3, 18, 18, 64, 64, tint(0xFFFFFFFF, opacity));
        if (brandText != null) graphics.text(font, brandText, x + 26, y + 1, tint(0xFFE2C9A3, opacity), true);
        graphics.text(font, countText, x + layout.width() - countWidth, y + 1,
                tint(0xFFB6B2AD, opacity), true);
        int lineY = y + 20;
        int center = x + layout.width() / 2;
        int lineColor = tint(0x809DA09C, opacity);
        graphics.fill(x, lineY, center - 4, lineY + 1, lineColor);
        graphics.fill(center + 4, lineY, x + layout.width(), lineY + 1, lineColor);
        sprite(graphics, DIVIDER, center - 4, lineY - 4, 8, 8, 32, 32, tint(0xFFFFFFFF, opacity));
        graphics.fill(x, lineY - 1, x + 1, lineY + 2, lineColor);
        graphics.fill(x + layout.width() - 1, lineY - 1, x + layout.width(), lineY + 2, lineColor);
    }

    private static void drawSelfDot(GuiGraphicsExtractor graphics, int x, int y, float opacity) {
        graphics.fill(x - 1, y - 1, x + 4, y + 4, tint(0x16EEC574, opacity));
        graphics.fill(x, y, x + 3, y + 3, tint(0xB7EDC87F, opacity));
        graphics.fill(x + 1, y + 1, x + 2, y + 2, tint(0xFFFFE5A4, opacity));
    }

    private static int tint(int argb, float opacity) {
        return Math.round((argb >>> 24) * Math.clamp(opacity, 0, 1)) << 24 | (argb & 0xFFFFFF);
    }

    private static Identifier asset(String name) {
        return Identifier.fromNamespaceAndPath("eclipseclient", "textures/gui/tab/" + name + ".png");
    }

    private static Component text(String value) { return Component.literal(value).setStyle(SERIF); }

    private static Component fit(Font font, String value, int capacity) {
        return text(TabText.ellipsize(value, capacity, candidate -> font.width(text(candidate))));
    }

    private static String pageKeyName(KeyMapping mapping, int defaultKey, String shortName) {
        if (mapping == null) return shortName;
        InputConstants.Key key = KeyMappingHelper.getBoundKeyOf(mapping);
        return key.getType() == InputConstants.Type.KEYSYM && key.getValue() == defaultKey
                ? shortName : mapping.getTranslatedKeyMessage().getString();
    }

    private static void sprite(GuiGraphicsExtractor graphics, Identifier texture, int x, int y,
                               int width, int height, int sourceWidth, int sourceHeight, int tint) {
        graphics.blit(RenderPipelines.GUI_TEXTURED, texture, x, y, 0.0f, 0.0f,
                width, height, sourceWidth, sourceHeight, sourceWidth, sourceHeight, tint);
    }

    private record RenderedName(Component text, int x, int y, boolean self) { }
}
