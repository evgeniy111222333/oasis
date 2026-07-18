package ua.rp.chat.client.rpfeed;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** Client-only RP conversation feed. Nothing enters it except the server's rpchat:rp_feed payload. */
public final class RpChatFeedClientState {
    private static final long HOLD_NANOS = 7_000_000_000L;
    private static final long FADE_NANOS = 1_300_000_000L;
    private static final int MAX_ENTRIES = 5;
    private static final ArrayDeque<Entry> ENTRIES = new ArrayDeque<>();

    private RpChatFeedClientState() {}

    public static void accept(RpChatFeedPayload payload) {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload.data()))) {
            if (input.readUnsignedByte() != 1) return;
            input.readLong(); input.readLong();
            int ordinal = input.readUnsignedByte();
            if (ordinal >= FeedChannel.values().length) return;
            boolean distant = input.readBoolean();
            String name = read(input);
            String text = read(input);
            if (text.isBlank()) return;
            synchronized (ENTRIES) {
                ENTRIES.addFirst(new Entry(FeedChannel.values()[ordinal], distant, name, text, System.nanoTime()));
                while (ENTRIES.size() > MAX_ENTRIES) ENTRIES.removeLast();
            }
        } catch (IOException | IllegalArgumentException ignored) {
            // Malformed chat must never make the HUD fail.
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
        int cursor = height - 88; // clear of hotbar, health and the new pickup focus prompt
        for (Entry entry : entries) {
            float alpha = alpha(entry);
            if (alpha <= 0.01f) continue;
            drawEntry(graphics, minecraft.font, entry, 16, cursor, Math.min(304, width - 32), alpha);
            cursor -= entryHeight(minecraft.font, entry, Math.min(304, width - 32)) + 5;
            if (cursor < 12) break;
        }
    }

    private static void drawEntry(GuiGraphicsExtractor graphics, Font font, Entry entry, int x, int bottom, int maxWidth, float opacity) {
        int height = entryHeight(font, entry, maxWidth);
        int y = bottom - height;
        int alpha = Math.round(222 * opacity);
        int slide = Math.round((1.0f - opacity) * 8.0f);
        x -= slide;
        int accent = accent(entry.channel, alpha);
        List<FormattedCharSequence> lines = textLines(font, displayText(entry), maxWidth - 42);
        graphics.fill(x - 1, y - 1, x + maxWidth + 1, bottom + 1, color(0x020202, alpha / 3));
        graphics.fill(x, y, x + maxWidth, bottom, color(0x11100F, alpha));
        graphics.fill(x, y, x + 2, bottom, accent);
        graphics.fill(x + 9, y + 8, x + 25, y + 24, color(0x272018, alpha));
        String initial = entry.name.isBlank() ? "•" : entry.name.substring(0, 1).toUpperCase();
        graphics.centeredText(font, initial, x + 17, y + 12, accent);
        String header = "[" + label(entry.channel) + "] " + entry.name;
        graphics.text(font, header, x + 33, y + 7, accent, false);
        int lineY = y + 18;
        for (FormattedCharSequence line : lines) {
            graphics.text(font, line, x + 33, lineY, color(entry.distant ? 0xB6ACA0 : 0xF5F0E6, alpha), false);
            lineY += 10;
        }
    }

    private static int entryHeight(Font font, Entry entry, int maxWidth) { return 25 + textLines(font, displayText(entry), maxWidth - 42).size() * 10; }
    private static List<FormattedCharSequence> textLines(Font font, String text, int width) {
        List<FormattedCharSequence> all = font.split(Component.literal(text), Math.max(80, width));
        return all.size() <= 2 ? all : all.subList(0, 2);
    }
    private static String displayText(Entry entry) {
        return switch (entry.channel) {
            case SPEAK, WHISPER, SHOUT -> entry.distant ? entry.text : "«" + entry.text + "»";
            default -> entry.text;
        };
    }
    private static float alpha(Entry entry) {
        long age = System.nanoTime() - entry.createdNanos;
        if (age < 180_000_000L) return age / 180_000_000f;
        if (age <= HOLD_NANOS) return 1.0f;
        return Math.max(0f, 1f - (age - HOLD_NANOS) / (float) FADE_NANOS);
    }
    private static String label(FeedChannel channel) { return switch (channel) {
        case WHISPER -> "ШЁПОТ"; case SHOUT -> "КРИК"; case ACTION -> "ДЕЙСТВИЕ";
        case DESCRIPTION -> "ОПИСАНИЕ"; case OOC -> "OOC"; default -> "ГОВОРИТ"; }; }
    private static int accent(FeedChannel channel, int alpha) { return color(switch (channel) {
        case WHISPER -> 0x9AA7B2; case SHOUT -> 0xD6A06A; case ACTION -> 0xB9C59B;
        case DESCRIPTION -> 0x91B7C2; case OOC -> 0x8E8A84; default -> 0xE3C099; }, alpha); }
    private static String read(DataInputStream input) throws IOException {
        int length = input.readUnsignedShort(); if (length > 720) throw new IOException("long text");
        byte[] data = input.readNBytes(length); if (data.length != length) throw new IOException("truncated text");
        return new String(data, StandardCharsets.UTF_8);
    }
    private static int color(int rgb, int alpha) { return (Math.max(0, Math.min(255, alpha)) << 24) | rgb; }
    /** Ordinals intentionally mirror the server RpChatChannel protocol. */
    private enum FeedChannel { WHISPER, SPEAK, SHOUT, ACTION, DESCRIPTION, OOC }
    private record Entry(FeedChannel channel, boolean distant, String name, String text, long createdNanos) {}
}
