package ua.rp.chat;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;

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

    private static final TextColor NAME = TextColor.color(0xE3C099);
    private static final TextColor MUTED = TextColor.color(0xB0A8A0);
    private static final TextColor SPEECH = TextColor.color(0xF2EFE7);
    private static final TextColor ACTION = TextColor.color(0xC3C4A5);
    private static final TextColor DESCRIPTION = TextColor.color(0xA5C3C4);
    private static final TextColor OOC = TextColor.color(0xAFA69E);
    private static final TextColor SUCCESS = TextColor.color(0x99C3A2);
    private static final TextColor FAIL = TextColor.color(0xE3A899);

    public static Component formatJoin(String name, int style) {
        return Component.text()
                .append(Component.text(name, NAME))
                .append(Component.text(" входит в мир.", ACTION))
                .build();
    }

    public static Component formatSpeech(String name, String message, int style) {
        return Component.text()
                .append(Component.text(name, NAME))
                .append(Component.text(" говорит: ", MUTED))
                .append(Component.text("«" + message + "»", SPEECH))
                .build();
    }

    public static Component formatMe(String name, String action, int style) {
        return Component.text()
                .append(Component.text(name, NAME))
                .append(Component.text(" " + action, ACTION))
                .build();
    }

    public static Component formatDo(String name, String description, int style) {
        return Component.text()
                .append(Component.text(description, DESCRIPTION))
                .append(Component.text(" — ", MUTED))
                .append(Component.text(name, MUTED))
                .build();
    }

    public static Component formatTry(String name, String action, boolean success, int style) {
        return Component.text()
                .append(Component.text(name, NAME))
                .append(Component.text(" пытается " + action + "... ", ACTION))
                .append(Component.text(success ? "[удачно]" : "[неудачно]", success ? SUCCESS : FAIL))
                .build();
    }

    public static Component formatTodo(String name, String speech, String action, int style) {
        return Component.text()
                .append(Component.text("«" + speech + "» ", SPEECH))
                .append(Component.text("— ", MUTED))
                .append(Component.text(name, NAME))
                .append(Component.text(", " + action, ACTION))
                .build();
    }

    public static Component formatB(String name, String message, int style) {
        return Component.text()
                .append(Component.text("[OOC] ", NamedTextColor.DARK_GRAY))
                .append(Component.text(name, MUTED))
                .append(Component.text(": ", MUTED))
                .append(Component.text(message, OOC))
                .build();
    }
}
