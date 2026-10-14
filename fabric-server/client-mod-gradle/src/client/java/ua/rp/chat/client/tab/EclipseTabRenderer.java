package ua.rp.chat.client.tab;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.fabricmc.fabric.api.resource.v1.reloader.ResourceReloaderKeys;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Native concept-8 astrolabe: cached vector textures, bounded portraits and no browser UI. */
public final class EclipseTabRenderer {
    private static final Identifier OUTER = asset("outer"), MIDDLE = asset("middle"), INNER = asset("inner"),
            CROSSHAIRS = asset("crosshairs"), GLOW = asset("glow"), CORE = asset("core"), ECLIPSE = asset("eclipse"),
            BACKDROP = asset("backdrop"), PILL = asset("pill"), EMBER = asset("ember");
    private static final TabAnimation ANIMATION = new TabAnimation();
    private static final float[] MOTES = new float[36 * 4];
    static {
        long seed = 48271;
        for (int i = 0; i < MOTES.length; i++) {
            seed = (seed * 48271) % 2147483647;
            MOTES[i] = seed / 2147483647f;
        }
    }
    private static KeyMapping previousPageKey, nextPageKey;
    private static boolean requested, wasOpen;
    private static int offset;
    private static double wheelRemainder;
    private static TabLayout layout;
    private static long cachedRevision = -1, fontGeneration, cachedFontGeneration = -1;
    private static int cachedWidth, cachedHeight, cachedOffset = -1;
    private static List<RenderedName> names = List.of();
    private static List<UUID> portraitIds = List.of();
    private static AstrolabeText.Label countText, onlineText, clockText, phaseText, realmText, emptyText;
    private static boolean splitClock;
    private static TabClock clock;
    private static long nextClockRefresh, animationEpoch;

    private EclipseTabRenderer() { }

    public static void register() {
        previousPageKey = key("key.eclipseclient.tab_previous_page", GLFW.GLFW_KEY_PAGE_UP);
        nextPageKey = key("key.eclipseclient.tab_next_page", GLFW.GLFW_KEY_PAGE_DOWN);
        Identifier reloadId = Identifier.fromNamespaceAndPath("eclipseclient", "tab_font_metrics");
        ResourceLoader loader = ResourceLoader.get(PackType.CLIENT_RESOURCES);
        loader.registerReloadListener(reloadId, (ResourceManagerReloadListener) resources -> {
            AstrolabeText.reload(resources);
            fontGeneration++;
            invalidate();
            TabPortraits.invalidate();
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
    public static boolean isVisible(Minecraft client) { return playable(client) && (wantsOpen(client) || ANIMATION.visible()); }
    public static void setRequested(boolean value) { requested = value; }

    public static void tick(Minecraft client) {
        if (!playable(client)) { reset(); return; }
        if (previousPageKey == null) return;
        while (previousPageKey.consumeClick()) if (wantsOpen(client)) changeOffset(-stepSize());
        while (nextPageKey.consumeClick()) if (wantsOpen(client)) changeOffset(stepSize());
    }
    private static int stepSize() { return layout == null ? 10 : layout.capacity(); }
    public static boolean scroll(double amount) {
        if (!wantsOpen(Minecraft.getInstance()) || !Double.isFinite(amount)) return false;
        wheelRemainder += Math.clamp(amount, -10, 10);
        int steps = (int)wheelRemainder;
        if (steps != 0) { wheelRemainder -= steps; changeOffset(-steps); }
        return true;
    }
    private static void changeOffset(int step) {
        if (layout != null && layout.total() > layout.capacity()) offset = Math.floorMod(offset + step, layout.total());
    }
    public static void reset() {
        requested = false; wasOpen = false; offset = 0; wheelRemainder = 0;
        ANIMATION.reset(); layout = null; names = List.of(); portraitIds = List.of();
        clock = null; nextClockRefresh = 0; animationEpoch = 0;
        TabPortraits.reset(); invalidate();
    }
    private static void invalidate() { cachedRevision = -1; }

    public static void render(GuiGraphicsExtractor graphics, int width, int height, Minecraft client, TabRoster roster) {
        float guiScale = client.getWindow().getGuiScale();
        graphics.pose().pushMatrix();
        try {
            // Render in framebuffer pixels, so the font and hairlines never shrink into a low-resolution GUI grid.
            graphics.pose().scale(1/guiScale, 1/guiScale);
            renderPixels(graphics, client.getWindow().getWidth(), client.getWindow().getHeight(), client, roster);
        } finally { graphics.pose().popMatrix(); }
    }

    private static void renderPixels(GuiGraphicsExtractor graphics, int width, int height, Minecraft client, TabRoster roster) {
        if (!playable(client)) { reset(); return; }
        long now = System.nanoTime();
        boolean open = wantsOpen(client);
        if (open && !wasOpen) { offset = 0; wheelRemainder = 0; invalidate(); if (!ANIMATION.visible()) animationEpoch = now; }
        wasOpen = open;
        float opacity = ANIMATION.advance(open, now);
        if (opacity <= 0 || width < 96 || height < 80) return;
        if (offset >= roster.entries().size()) offset = Math.max(0, roster.entries().size() - 1);
        ensureLayout(width, height, roster);
        refreshClock(client, now);
        TabPortraits.update(client, portraitIds, now);
        float progress = ANIMATION.progress();
        float seconds = (float)((now - animationEpoch) * 1e-9);
        // Minecraft's own single GPU blur, respecting the user's menu blur setting; no captured world image.
        graphics.blurBeforeThisStratum();
        graphics.fill(0, 0, width, height, tint(0x4808070A, opacity));
        sprite(graphics, BACKDROP, 0, 0, width, height, 512, 256, tint(0xFFFFFFFF, opacity));
        drawMotes(graphics, width, height, seconds, opacity);
        graphics.pose().pushMatrix();
        try {
            graphics.pose().translate(layout.centerX(), layout.centerY());
            layer(graphics, GLOW, 1, 0, opacity);
            float outer = TabAnimation.stagger(progress, 0);
            float middle = TabAnimation.stagger(progress, .07f);
            float inner = TabAnimation.stagger(progress, .14f);
            float axes = TabAnimation.stagger(progress, .04f);
            layer(graphics, CROSSHAIRS, .82f + .18f * axes, 0, axes);
            layer(graphics, OUTER, .72f + .28f * outer, -28 * (1-outer) + seconds * 360 / 110, outer);
            layer(graphics, MIDDLE, .64f + .36f * middle, 35 * (1-middle) - seconds * 360 / 85, middle);
            layer(graphics, INNER, .52f + .48f * inner, -45 * (1-inner) + seconds * 360 / 64, inner);
            drawCore(graphics, TabAnimation.stagger(progress, .10f));
            drawHeader(graphics, TabAnimation.stagger(progress, .28f));
        } finally { graphics.pose().popMatrix(); }
        for (RenderedName name : names) drawName(graphics, name, progress);
    }

    private static void ensureLayout(int width, int height, TabRoster roster) {
        if (cachedRevision == roster.revision() && cachedWidth == width && cachedHeight == height
                && cachedOffset == offset && cachedFontGeneration == fontGeneration) return;
        layout = TabLayout.calculate(width, height, roster.entries().size(), offset);
        offset = layout.first();
        ArrayList<RenderedName> next = new ArrayList<>(layout.visible());
        ArrayList<UUID> ids = new ArrayList<>(layout.visible());
        for (int i = 0; i < layout.visible(); i++) {
            TabRoster.Entry entry = roster.entries().get(layout.index(i));
            AstrolabeText.Label label = AstrolabeText.fit(entry.label(), layout.nameCapacity(), 22 * layout.nameScale(), .25f * layout.nameScale(), true);
            next.add(new RenderedName(entry.id(), label, entry.self(),
                    layout.anchorX(i), layout.anchorY(i), layout.tilt(i), layout.delay(i), layout.left(i)));
            ids.add(entry.id());
        }
        names = List.copyOf(next); portraitIds = List.copyOf(ids);
        float scale = layout.nameScale();
        countText = AstrolabeText.fit("ВСЕ В МИРЕ (" + roster.entries().size() + ")", Math.max(0, width - 36),
                16 * scale, 1 * scale, true);
        onlineText = AstrolabeText.prepare(roster.entries().size() + " Online", 16 * scale, .4f * scale);
        realmText = AstrolabeText.prepare("ECLIPSE ROLEPLAY", 12 * scale, .8f * scale);
        emptyText = AstrolabeText.prepare("Мир ждёт странников", 16 * scale, .25f * scale);
        clockText = null;
        cachedRevision = roster.revision(); cachedWidth = width; cachedHeight = height;
        cachedOffset = offset; cachedFontGeneration = fontGeneration;
    }

    private static void refreshClock(Minecraft client, long now) {
        if (clock == null || now >= nextClockRefresh) {
            TabClock next = TabClock.of(client.level.getOverworldClockTime());
            if (!next.equals(clock)) { clock = next; clockText = null; }
            nextClockRefresh = now + 500_000_000L;
        }
        if (clockText != null) return;
        float size = 20 * layout.nameScale(), tracking = .4f * layout.nameScale();
        splitClock = AstrolabeText.measure(clock.label(), size, tracking) > layout.radius() * .88f;
        int capacity = (int)Math.max(20, layout.radius() * .88f);
        clockText = AstrolabeText.fit(splitClock ? "Day " + clock.day() : clock.label(), capacity, size, tracking);
        phaseText = AstrolabeText.fit(clock.phase(), capacity, size, tracking);
        ArrayList<AstrolabeText.Label> visible = new ArrayList<>(names.size() + 6);
        for (RenderedName name : names) visible.add(name.label);
        visible.addAll(List.of(countText, onlineText, realmText, emptyText, clockText, phaseText));
        AstrolabeText.trim(visible);
    }

    private static void layer(GuiGraphicsExtractor graphics, Identifier image, float scale, float angle, float opacity) {
        if (opacity <= 0) return;
        graphics.pose().pushMatrix();
        try {
            graphics.pose().rotate((float)Math.toRadians(angle)); graphics.pose().scale(scale, scale);
            int size = Math.round(layout.artSize());
            sprite(graphics, image, -size/2, -size/2, size, size, 1024, 1024, tint(0xFFFFFFFF, opacity));
        } finally { graphics.pose().popMatrix(); }
    }

    private static void drawCore(GuiGraphicsExtractor graphics, float opacity) {
        if (opacity <= 0) return;
        graphics.pose().pushMatrix();
        try {
            float reveal = .65f + .35f * opacity; graphics.pose().scale(reveal, reveal);
            int disc = Math.max(36, Math.round(layout.radius() * 196 / 265));
            sprite(graphics, CORE, -disc/2, -disc/2, disc, disc, 256, 256, tint(0xFFFFFFFF, opacity));
            int icon = Math.max(12, Math.round(layout.radius() * 68 / 265));
            float scale = layout.nameScale();
            float textHeight = clockText.height() + 3 * scale + onlineText.height() + 6 * scale + realmText.height();
            if (splitClock) textHeight += phaseText.height() + 3 * scale;
            float totalHeight = icon + 8 * scale + textHeight;
            int iconY = Math.round(-totalHeight / 2);
            sprite(graphics, ECLIPSE, -icon/2, iconY, icon, icon, 128, 128, tint(0xFFFFFFFF, opacity));
            float firstY = iconY + icon + 8 * scale;
            centered(graphics, clockText, 0, firstY, tint(0xFFF5EBD6, opacity));
            float nextY = firstY + clockText.height() + 3 * scale;
            if (splitClock) {
                centered(graphics, phaseText, 0, nextY, tint(0xFFF5EBD6, opacity));
                nextY += phaseText.height() + 3 * scale;
            }
            centered(graphics, onlineText, 0, nextY, tint(0xFFF3D88C, opacity * .95f));
            if (layout.radius() >= 65) centered(graphics, realmText, 0, nextY + onlineText.height() + 6 * scale,
                    tint(0xFFF5EBD6, opacity * .78f));
            if (names.isEmpty()) centered(graphics, emptyText, 0, layout.radius() * .72f,
                    tint(0xFFF5EBD6, opacity * .75f));
        } finally { graphics.pose().popMatrix(); }
    }

    private static void drawHeader(GuiGraphicsExtractor graphics, float opacity) {
        if (opacity <= 0) return;
        int w = Math.round(countText.inkWidth() + 22 * layout.nameScale()), h = Math.round(countText.inkHeight() + 12 * layout.nameScale());
        int y = Math.round(-layout.radius() * 308 / 265);
        sprite(graphics, PILL, -w/2, y-h/2, w, h, 256, 48, tint(0xFFFFFFFF, opacity));
        // Capitals have unused ascent/descent space. Center the visible outlines, not the nominal font line box.
        AstrolabeText.drawCentered(graphics, countText, 0, y,
                tint(0xFFF3D88C, opacity));
    }

    private static void drawName(GuiGraphicsExtractor graphics, RenderedName name, float progress) {
        float opacity = TabAnimation.stagger(progress, name.delay);
        if (opacity < .005f) return;
        float reveal = .82f + .18f * opacity;
        float x = layout.centerX() + (name.x - layout.centerX()) * reveal;
        float y = layout.centerY() + (name.y - layout.centerY()) * reveal;
        graphics.pose().pushMatrix();
        try {
            graphics.pose().translate(x, y); graphics.pose().rotate((float)Math.toRadians(name.tilt));
            int spoke = Math.max(3, Math.round(layout.spokeLength()));
            int head = layout.headSize(), gap = Math.max(1, Math.round(layout.nodeGap()));
            int hx = name.left ? -spoke-gap-head : spoke+gap;
            int hy = -head/2;
            int line = tint(0x99D8B467, opacity);
            graphics.fill(name.left ? -spoke : 1, 0, name.left ? -1 : spoke, 1, line);
            sprite(graphics, EMBER, -3, -3, 6, 6, 16, 16, tint(0xFFFFFFFF, opacity));
            graphics.fill(0, 0, 1, 1, tint(0xFFF3D88C, opacity));
            graphics.fill(hx-1, hy-1, hx+head+1, hy+head+1, tint(0xD9181410, opacity));
            TabPortraits.render(graphics, name.id, hx, hy, head, tint(0xFFFFFFFF, opacity));
            graphics.outline(hx-1, hy-1, head+2, head+2, tint(name.self ? 0xFFEED495 : 0xA8D8B467, opacity));
            float tx = name.left ? hx-gap-name.label.width() : hx+head+gap;
            AstrolabeText.draw(graphics, name.label, tx, -name.label.height()/2,
                    tint(name.self ? 0xFFFFF1CF : 0xFFF5EBD6, opacity));
        } finally { graphics.pose().popMatrix(); }
    }

    private static void drawMotes(GuiGraphicsExtractor graphics, int width, int height, float seconds, float opacity) {
        for (int i = 0; i < 36; i++) {
            int o = i*4;
            float fy = MOTES[o+1] - seconds * (.004f + MOTES[o+2]*.006f);
            fy -= (float)Math.floor(fy);
            int x = Math.round((MOTES[o] + (float)Math.sin(seconds * .1f + i) * .006f) * width);
            int y = Math.round(fy * height);
            graphics.fill(x, y, x+1, y+1, tint(0xA0F3D88C, opacity * (.15f+MOTES[o+3]*.45f)));
        }
    }
    private static void centered(GuiGraphicsExtractor graphics, AstrolabeText.Label label, float x, float y, int color) {
        AstrolabeText.draw(graphics, label, x-label.width()/2, y, color);
    }
    private static int tint(int color, float opacity) {
        return Math.round((color >>> 24) * Math.clamp(opacity, 0, 1)) << 24 | (color & 0xFFFFFF);
    }
    private static Identifier asset(String name) {
        return Identifier.fromNamespaceAndPath("eclipseclient", "textures/gui/tab/astrolabe/" + name + ".png");
    }
    private static void sprite(GuiGraphicsExtractor graphics, Identifier texture, int x, int y,
                               int width, int height, int sourceWidth, int sourceHeight, int tint) {
        graphics.blit(RenderPipelines.GUI_TEXTURED, texture, x, y, 0, 0, width, height,
                sourceWidth, sourceHeight, sourceWidth, sourceHeight, tint);
    }
    private record RenderedName(UUID id, AstrolabeText.Label label, boolean self,
                                float x, float y, float tilt, float delay, boolean left) { }
}
