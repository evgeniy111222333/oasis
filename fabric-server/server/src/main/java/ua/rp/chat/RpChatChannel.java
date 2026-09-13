package ua.rp.chat;

import net.minecraft.network.chat.TextColor;

public enum RpChatChannel {
    WHISPER("whisper", "шепчет", 7.0, 11.0, TextColor.fromRgb(0x9AA7B2), TextColor.fromRgb(0xD8DEE8)),
    SPEAK("speak", "говорит", 24.0, 34.0, TextColor.fromRgb(0xC8B998), TextColor.fromRgb(0xF2EFE7)),
    SHOUT("shout", "кричит", 48.0, 68.0, TextColor.fromRgb(0xD6A06A), TextColor.fromRgb(0xFFF1D6)),
    ACTION("action", "действие", 25.0, 35.0, TextColor.fromRgb(0xB9C59B), TextColor.fromRgb(0xDDE8C6)),
    DESCRIPTION("description", "описание", 30.0, 42.0, TextColor.fromRgb(0x91B7C2), TextColor.fromRgb(0xD8EEF2)),
    OOC("ooc", "OOC", 20.0, 28.0, TextColor.fromRgb(0x8E8A84), TextColor.fromRgb(0xC8C0B8)),
    TRY("try", "пытается", 25.0, 35.0, TextColor.fromRgb(0xC7B37D), TextColor.fromRgb(0xEEE1BC)),
    TODO("todo", "говорит и действует", 24.0, 34.0, TextColor.fromRgb(0xBBAE94), TextColor.fromRgb(0xF2EFE7));

    private final String id;
    private final String verb;
    private final double clearRadius;
    private final double fadeRadius;
    private final TextColor accentColor;
    private final TextColor messageColor;

    RpChatChannel(String id, String verb, double clearRadius, double fadeRadius, TextColor accentColor, TextColor messageColor) {
        this.id = id;
        this.verb = verb;
        this.clearRadius = clearRadius;
        this.fadeRadius = fadeRadius;
        this.accentColor = accentColor;
        this.messageColor = messageColor;
    }

    public String id() {
        return id;
    }

    public String verb() {
        return verb;
    }

    public double clearRadius() {
        return clearRadius;
    }

    public double fadeRadius() {
        return fadeRadius;
    }

    public TextColor accentColor() {
        return accentColor;
    }

    public TextColor messageColor() {
        return messageColor;
    }
}
