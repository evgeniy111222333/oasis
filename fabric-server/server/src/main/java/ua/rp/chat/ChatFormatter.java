package ua.rp.chat;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;

public class ChatFormatter {
    public static final String[] STYLE_NAMES = {
            "Aether Minimalist",
            "Chamber Theatre",
            "Nordic Aurora",
            "Sakura Blossom",
            "Cyber Glow",
            "Mono Noir",
            "Sunset Boulevard",
            "Earthy Forest",
            "Vogue Elegant",
            "Echo Whisper"
    };

    private static final TextColor NAME = TextColor.fromRgb(0xE3C099);
    private static final TextColor MUTED = TextColor.fromRgb(0xB0A8A0);
    private static final TextColor SPEECH = TextColor.fromRgb(0xF2EFE7);
    private static final TextColor ACTION = TextColor.fromRgb(0xC3C4A5);
    private static final TextColor DESCRIPTION = TextColor.fromRgb(0xA5C3C4);
    private static final TextColor OOC = TextColor.fromRgb(0xAFA69E);
    private static final TextColor SUCCESS = TextColor.fromRgb(0x99C3A2);
    private static final TextColor FAIL = TextColor.fromRgb(0xE3A899);

    public static Component formatJoin(String name, int style) {
        return Component.empty()
                .append(Component.literal(name).withStyle(s -> s.withColor(NAME)))
                .append(Component.literal(" появляется у дороги.").withStyle(s -> s.withColor(ACTION)));
    }

    public static Component formatQuit(String name, int style) {
        return Component.empty()
                .append(Component.literal(name).withStyle(s -> s.withColor(NAME)))
                .append(Component.literal(" скрывается за дальним трактом.").withStyle(s -> s.withColor(ACTION)));
    }

    public static Component formatSpeech(String name, String message, int style) {
        return Component.empty()
                .append(Component.literal(name).withStyle(s -> s.withColor(NAME)))
                .append(Component.literal(" говорит: ").withStyle(s -> s.withColor(MUTED)))
                .append(Component.literal("«" + message + "»").withStyle(s -> s.withColor(SPEECH)));
    }

    public static Component formatMe(String name, String action, int style) {
        return Component.empty()
                .append(Component.literal(name).withStyle(s -> s.withColor(NAME)))
                .append(Component.literal(" " + action).withStyle(s -> s.withColor(ACTION)));
    }

    public static Component formatDo(String name, String description, int style) {
        return Component.empty()
                .append(Component.literal(description).withStyle(s -> s.withColor(DESCRIPTION)))
                .append(Component.literal(" — ").withStyle(s -> s.withColor(MUTED)))
                .append(Component.literal(name).withStyle(s -> s.withColor(MUTED)));
    }

    public static Component formatTry(String name, String action, boolean success, int style) {
        return Component.empty()
                .append(Component.literal(name).withStyle(s -> s.withColor(NAME)))
                .append(Component.literal(" пытается " + action + "... ").withStyle(s -> s.withColor(ACTION)))
                .append(Component.literal(success ? "[удачно]" : "[неудачно]").withStyle(s -> s.withColor(success ? SUCCESS : FAIL)));
    }

    public static Component formatTodo(String name, String speech, String action, int style) {
        return Component.empty()
                .append(Component.literal("«" + speech + "» ").withStyle(s -> s.withColor(SPEECH)))
                .append(Component.literal("— ").withStyle(s -> s.withColor(MUTED)))
                .append(Component.literal(name).withStyle(s -> s.withColor(NAME)))
                .append(Component.literal(", " + action).withStyle(s -> s.withColor(ACTION)));
    }

    public static Component formatB(String name, String message, int style) {
        return Component.empty()
                .append(Component.literal("[OOC] ").withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal(name).withStyle(s -> s.withColor(MUTED)))
                .append(Component.literal(": ").withStyle(s -> s.withColor(MUTED)))
                .append(Component.literal(message).withStyle(s -> s.withColor(OOC)));
    }
}
