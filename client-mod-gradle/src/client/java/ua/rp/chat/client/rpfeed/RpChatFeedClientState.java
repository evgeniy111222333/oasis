package ua.rp.chat.client.rpfeed;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.PlayerSkin;
import ua.rp.chat.client.appearance.EclipseAppearanceManager;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The only in-game conversation surface for structured RP messages.
 * It intentionally receives neither vanilla system chat nor ordinary player chat.
 */
public final class RpChatFeedClientState {
    private static final long HOLD_NANOS = 7_000_000_000L;
    private static final long FADE_NANOS = 1_300_000_000L;
    private static final int MAX_ENTRIES = 5;
    private static final int MIN_PANEL_WIDTH = 208;
    private static final int MAX_PANEL_WIDTH = 356;
    private static final Identifier DOSSIER_ATLAS = Identifier.fromNamespaceAndPath("eclipseclient", "textures/gui/rp_dossier_atlas.png");
    private static final int DOSSIER_TEXTURE_SIZE = 1254;
    private static final int DOSSIER_X = 110;
    private static final int DOSSIER_LEFT_SOURCE_WIDTH = 354;
    private static final int DOSSIER_MIDDLE_SOURCE_WIDTH = 526;
    private static final int DOSSIER_RIGHT_SOURCE_WIDTH = 154;
    private static final int DOSSIER_SOURCE_HEIGHT = 224;
    private static final int DOSSIER_LEFT_WIDTH = 42;
    private static final int DOSSIER_RIGHT_WIDTH = 18;
    private static final ArrayDeque<Entry> ENTRIES = new ArrayDeque<>();

    private RpChatFeedClientState() {}

    public static void accept(RpChatFeedPayload payload) {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload.data()))) {
            int version = input.readUnsignedByte();
            if (version != 1 && version != 2) return;
            UUID speaker = new UUID(input.readLong(), input.readLong());
            int ordinal = input.readUnsignedByte();
            if (ordinal >= FeedChannel.values().length) return;
            boolean distant = input.readBoolean();
            int outcome = version >= 2 ? input.readUnsignedByte() : 0;
            if (outcome > 2) outcome = 0;
            String name = read(input);
            String text = read(input);
            if (text.isBlank()) return;

            synchronized (ENTRIES) {
                ENTRIES.addFirst(new Entry(speaker, FeedChannel.values()[ordinal], distant, outcome, name, text, System.nanoTime()));
                while (ENTRIES.size() > MAX_ENTRIES) ENTRIES.removeLast();
            }
        } catch (IOException | IllegalArgumentException ignored) {
            // A malformed network payload is never allowed to break HUD rendering.
        }
    }

    public static void render(GuiGraphicsExtractor graphics, int width, int height) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.font == null || minecraft.screen != null) return;

        List<Entry> entries;
        synchronized (ENTRIES) {
            long now = System.nanoTime();
            ENTRIES.removeIf(entry -> now - entry.createdNanos > HOLD_NANOS + FADE_NANOS);
            entries = new ArrayList<>(ENTRIES);
        }

        int availableWidth = Math.min(MAX_PANEL_WIDTH, width - 28);
        int cursor = height - 88;
        for (Entry entry : entries) {
            float opacity = alpha(entry);
            if (opacity <= 0.01f) continue;
            int entryWidth = entryWidth(minecraft.font, entry, availableWidth);
            int x = (width - entryWidth) / 2; // horizontally centred; the vertical anchor deliberately remains gameplay-safe.
            int entryHeight = entryHeight(minecraft.font, entry, entryWidth);
            drawEntry(graphics, minecraft, minecraft.font, entry, x, cursor - entryHeight, entryWidth, entryHeight, opacity);
            cursor -= entryHeight + 6;
            if (cursor < 12) break;
        }
    }

    private static void drawEntry(GuiGraphicsExtractor graphics, Minecraft minecraft, Font font, Entry entry,
                                  int x, int y, int width, int height, float opacity) {
        Visual visual = visual(entry);
        int slide = Math.round((1.0f - opacity) * 5.0f);
        x -= slide;

        int accent = accent(entry.channel, Math.round(232 * opacity));
        int text = color(entry.distant ? 0xB7B0A7 : messageRgb(entry.channel), Math.round(246 * opacity));

        drawDossierPanel(graphics, visual.kind, x, y, width, height);
        int textX = x + 49;
        if (visual.avatar) {
            drawAvatar(graphics, minecraft, entry, x + 6, y + Math.max(3, (height - 16) / 2), opacity, accent);
        } else {
            drawTypeMark(graphics, visual.kind, x + 15, y + height / 2, accent, opacity);
        }
        int lineY = y + 5;
        if (!visual.title.isEmpty()) {
            graphics.text(font, visual.title, textX, lineY, color(0xF4F1EA, Math.round(250 * opacity)), false);
            lineY += 10;
        }

        List<FormattedCharSequence> lines = textLines(font, visual.bodyText(), width - (textX - x) - 12);
        if (lines.size() == 1 && !visual.bodyPrefix.isEmpty()) {
            graphics.text(font, visual.bodyPrefix, textX, lineY, color(0xF4F1EA, Math.round(250 * opacity)), false);
            graphics.text(font, visual.body, textX + font.width(visual.bodyPrefix), lineY, text, false);
            lineY += 10;
        } else {
            for (FormattedCharSequence wrapped : lines) {
                graphics.text(font, wrapped, textX, lineY, text, false);
                lineY += 10;
            }
        }
        if (!visual.foot.isEmpty()) {
            int footColor = visual.kind == VisualKind.TRY ? tryColor(entry.outcome, opacity) : color(0xB7ADA0, Math.round(190 * opacity));
            graphics.text(font, visual.foot, textX, lineY, footColor, false);
        }
    }

    private static void drawDossierPanel(GuiGraphicsExtractor graphics, VisualKind kind, int x, int y, int width, int height) {
        int middleWidth = Math.max(1, width - DOSSIER_LEFT_WIDTH - DOSSIER_RIGHT_WIDTH);
        int sourceY = dossierSourceY(kind);
        graphics.blit(RenderPipelines.GUI_TEXTURED, DOSSIER_ATLAS, x, y, DOSSIER_X, sourceY,
                DOSSIER_LEFT_WIDTH, height, DOSSIER_LEFT_SOURCE_WIDTH, DOSSIER_SOURCE_HEIGHT,
                DOSSIER_TEXTURE_SIZE, DOSSIER_TEXTURE_SIZE);
        graphics.blit(RenderPipelines.GUI_TEXTURED, DOSSIER_ATLAS, x + DOSSIER_LEFT_WIDTH, y,
                DOSSIER_X + DOSSIER_LEFT_SOURCE_WIDTH, sourceY,
                middleWidth, height, DOSSIER_MIDDLE_SOURCE_WIDTH, DOSSIER_SOURCE_HEIGHT,
                DOSSIER_TEXTURE_SIZE, DOSSIER_TEXTURE_SIZE);
        graphics.blit(RenderPipelines.GUI_TEXTURED, DOSSIER_ATLAS, x + DOSSIER_LEFT_WIDTH + middleWidth, y,
                DOSSIER_X + DOSSIER_LEFT_SOURCE_WIDTH + DOSSIER_MIDDLE_SOURCE_WIDTH, sourceY,
                DOSSIER_RIGHT_WIDTH, height, DOSSIER_RIGHT_SOURCE_WIDTH, DOSSIER_SOURCE_HEIGHT,
                DOSSIER_TEXTURE_SIZE, DOSSIER_TEXTURE_SIZE);
    }

    private static int dossierSourceY(VisualKind kind) {
        return switch (kind) {
            case ACTION -> 80;
            case DESCRIPTION -> 357;
            case TRY -> 650;
            case TODO -> 930;
            case SPEECH, OOC, DISTANT -> 930;
        };
    }

    private static void drawTypeMark(GuiGraphicsExtractor graphics, VisualKind kind, int x, int cy, int accent, float opacity) {
        switch (kind) {
            case SPEECH -> graphics.fill(x, cy - 6, x + 1, cy + 6, accent);
            case ACTION -> {
                graphics.fill(x, cy - 5, x + 5, cy - 4, accent);
                graphics.fill(x + 2, cy - 1, x + 7, cy, accent);
                graphics.fill(x + 4, cy + 3, x + 9, cy + 4, accent);
            }
            case DESCRIPTION -> {
                graphics.fill(x, cy - 5, x + 7, cy - 4, accent);
                graphics.fill(x, cy - 5, x + 1, cy + 5, accent);
                graphics.fill(x, cy + 4, x + 7, cy + 5, accent);
            }
            case TRY -> {
                graphics.fill(x + 3, cy - 5, x + 4, cy - 3, accent);
                graphics.fill(x + 1, cy - 3, x + 6, cy + 3, accent);
                graphics.fill(x + 3, cy + 3, x + 4, cy + 5, accent);
            }
            case TODO -> {
                graphics.fill(x, cy - 5, x + 1, cy + 5, accent);
                graphics.fill(x + 4, cy - 5, x + 5, cy + 5, accent);
            }
            case OOC, DISTANT -> graphics.fill(x, cy - 3, x + 5, cy - 2, accent);
        }
    }

    private static void drawAvatar(GuiGraphicsExtractor graphics, Minecraft minecraft, Entry entry, int x, int y,
                                   float opacity, int accent) {
        graphics.fill(x - 1, y - 1, x + 17, y + 17, color(0x020202, Math.round(145 * opacity)));
        Identifier skin = skinTexture(minecraft, entry.speaker);
        if (skin == null) {
            String initial = entry.name.isBlank() ? "·" : entry.name.substring(0, 1).toUpperCase();
            graphics.fill(x, y, x + 16, y + 16, color(0x282218, Math.round(195 * opacity)));
            graphics.centeredText(minecraft.font, initial, x + 8, y + 4, accent);
            return;
        }

        // Explicit source-region overload: 8x8 face + 8x8 hat layer, never the whole 64x64 skin.
        graphics.blit(RenderPipelines.GUI_TEXTURED, skin, x, y, 8.0f, 8.0f, 16, 16, 8, 8, 64, 64);
        graphics.blit(RenderPipelines.GUI_TEXTURED, skin, x, y, 40.0f, 8.0f, 16, 16, 8, 8, 64, 64);
    }

    private static Identifier skinTexture(Minecraft minecraft, UUID speaker) {
        if (speaker == null || (speaker.getMostSignificantBits() == 0L && speaker.getLeastSignificantBits() == 0L)) return null;
        PlayerSkin skin = null;
        if (minecraft.level != null && minecraft.level.getPlayerByUUID(speaker) instanceof AbstractClientPlayer player) {
            skin = player.getSkin();
        }
        if (skin == null) skin = EclipseAppearanceManager.getSkin(speaker);
        return skin != null && skin.body() != null ? skin.body().texturePath() : null;
    }

    private static int entryWidth(Font font, Entry entry, int availableWidth) {
        Visual visual = visual(entry);
        int leading = 60;
        int content = Math.max(font.width(visual.title), Math.max(font.width(visual.bodyText()), font.width(visual.foot)));
        return Math.max(MIN_PANEL_WIDTH, Math.min(availableWidth, leading + Math.min(content, availableWidth - leading) + 8));
    }

    private static int entryHeight(Font font, Entry entry, int width) {
        Visual visual = visual(entry);
        int leading = 60;
        int textHeight = Math.max(1, textLines(font, visual.bodyText(), width - leading - 8).size()) * 10;
        if (!visual.title.isEmpty()) textHeight += 10;
        if (!visual.foot.isEmpty()) textHeight += 10;
        return Math.max(22, textHeight + 10);
    }

    private static List<FormattedCharSequence> textLines(Font font, String text, int width) {
        List<FormattedCharSequence> all = font.split(Component.literal(text), Math.max(96, width));
        return all.size() <= 2 ? all : all.subList(0, 2);
    }

    private static Visual visual(Entry entry) {
        String name = entry.name.isBlank() ? "Система" : entry.name;
        if (entry.distant) return new Visual(VisualKind.DISTANT, false, "", "", entry.text, "");
        return switch (entry.channel) {
            case SPEAK -> new Visual(VisualKind.SPEECH, true, name, "", entry.text, "");
            case WHISPER -> new Visual(VisualKind.SPEECH, true, name + " · тихо", "", entry.text, "");
            case SHOUT -> new Visual(VisualKind.SPEECH, true, name + " · крик", "", entry.text.toUpperCase(), "");
            case ACTION -> new Visual(VisualKind.ACTION, true, "", name, " " + entry.text, "");
            // A /do is still authored by a character. Leaving the portrait slot empty made the dossier frame
            // read as a missing asset, so descriptions keep their scene composition but always show the author.
            case DESCRIPTION -> new Visual(VisualKind.DESCRIPTION, true, "", "", entry.text, "— " + name);
            case TRY -> new Visual(VisualKind.TRY, true, name + " · попытка", "", entry.text, tryResult(entry.outcome));
            case TODO -> todoVisual(name, entry.text);
            case OOC -> new Visual(VisualKind.OOC, true, name + " · OOC", "", entry.text, "");
        };
    }

    private static Visual todoVisual(String name, String text) {
        int divider = text.indexOf('\u001f');
        if (divider < 0) return new Visual(VisualKind.TODO, true, name, "", text, "");
        return new Visual(VisualKind.TODO, true, name, "", text.substring(0, divider), text.substring(divider + 1));
    }

    private static String tryResult(int outcome) {
        return outcome == 1 ? "УДАЧНО" : outcome == 2 ? "НЕУДАЧНО" : "ПОПЫТКА";
    }

    private static int tryColor(int outcome, float opacity) {
        int rgb = outcome == 1 ? 0xA7D4A6 : outcome == 2 ? 0xD9A39A : 0xE8D28B;
        return color(rgb, Math.round(245 * opacity));
    }

    private static int messageRgb(FeedChannel channel) {
        return switch (channel) {
            case WHISPER -> 0xD5DDE5;
            case SHOUT -> 0xFFE0BD;
            case ACTION -> 0xDFE7CD;
            case DESCRIPTION -> 0xD7EAF0;
            case TRY -> 0xEFE0BB;
            case TODO -> 0xEEE7D9;
            case OOC -> 0xCEC8C0;
            default -> 0xF3F0EA;
        };
    }

    private static float alpha(Entry entry) {
        long age = System.nanoTime() - entry.createdNanos;
        if (age < 180_000_000L) return age / 180_000_000f;
        if (age <= HOLD_NANOS) return 1.0f;
        return Math.max(0f, 1f - (age - HOLD_NANOS) / (float) FADE_NANOS);
    }

    private static int accent(FeedChannel channel, int alpha) {
        return color(switch (channel) {
            case WHISPER -> 0xA5B1BB;
            case SHOUT -> 0xD99A61;
            case ACTION -> 0xAABD88;
            case DESCRIPTION -> 0x8BB7C6;
            case TRY -> 0xCDB477;
            case TODO -> 0xC4B696;
            case OOC -> 0x8E8A84;
            default -> 0xE3C099;
        }, alpha);
    }

    private static String read(DataInputStream input) throws IOException {
        int length = input.readUnsignedShort();
        if (length > 720) throw new IOException("long text");
        byte[] data = input.readNBytes(length);
        if (data.length != length) throw new IOException("truncated text");
        return new String(data, StandardCharsets.UTF_8);
    }

    private static int color(int rgb, int alpha) {
        return (Math.max(0, Math.min(255, alpha)) << 24) | rgb;
    }

    /** Ordinals mirror the server enum. New kinds are appended so old ordinal values remain stable. */
    private enum FeedChannel { WHISPER, SPEAK, SHOUT, ACTION, DESCRIPTION, OOC, TRY, TODO }

    private enum VisualKind { SPEECH, ACTION, DESCRIPTION, TRY, TODO, OOC, DISTANT }

    private record Visual(VisualKind kind, boolean avatar, String title, String bodyPrefix, String body, String foot) {
        private String bodyText() {
            return bodyPrefix + body;
        }
    }

    private record Entry(UUID speaker, FeedChannel channel, boolean distant, int outcome, String name, String text,
                         long createdNanos) {}
}
