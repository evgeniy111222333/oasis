package ua.rp.chat;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.NamedTextColor;

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

    // Style 1 Colors (Aether Minimalist)
    private static final TextColor S1_NAME = TextColor.color(0xE3C099);
    private static final TextColor S1_ACTION = TextColor.color(0xC3C4A5);
    private static final TextColor S1_DO = TextColor.color(0xA5C3C4);
    private static final TextColor S1_MUTED = TextColor.color(0xB0A8A0);
    private static final TextColor S1_OOC_NAME = TextColor.color(0x8A827A);
    private static final TextColor S1_OOC_MSG = TextColor.color(0xAFA69E);
    private static final TextColor S1_OOC_SEP = TextColor.color(0xB0A8A0);

    // Style 2 Colors (Chamber Theatre)
    private static final TextColor S2_NAME = TextColor.color(0xEEDC82);
    private static final TextColor S2_ACTION = TextColor.color(0xD3C3D7);
    private static final TextColor S2_DO = TextColor.color(0xC2D1C2);
    private static final TextColor S2_MUTED = TextColor.color(0xB0B0B0);

    // Style 3 Colors (Nordic Aurora)
    private static final TextColor S3_NAME = TextColor.color(0x81D4FA);
    private static final TextColor S3_ACCENT = TextColor.color(0x457B9D);
    private static final TextColor S3_ACTION = TextColor.color(0x80CBC4);
    private static final TextColor S3_DO = TextColor.color(0xA8DADC);
    private static final TextColor S3_MUTED = TextColor.color(0x8E9AAF);

    // Style 4 Colors (Sakura Blossom)
    private static final TextColor S4_NAME = TextColor.color(0xFFB7B2);
    private static final TextColor S4_ACTION = TextColor.color(0xFFC6FF);
    private static final TextColor S4_DO = TextColor.color(0xB5E2FA);
    private static final TextColor S4_MUTED = TextColor.color(0xCB997E);

    // Style 5 Colors (Cyber Glow)
    private static final TextColor S5_NAME = TextColor.color(0x00E5FF);
    private static final TextColor S5_ACCENT = TextColor.color(0xFF007F);
    private static final TextColor S5_ACTION = TextColor.color(0xFF007F);
    private static final TextColor S5_DO = TextColor.color(0x00FF87);
    private static final TextColor S5_MUTED = TextColor.color(0x868F96);

    // Style 6 Colors (Mono Noir)
    private static final TextColor S6_NAME = TextColor.color(0xE0E0E0);
    private static final TextColor S6_ACTION = TextColor.color(0xFFFFFFFF);
    private static final TextColor S6_DO = TextColor.color(0x8A8A8A);
    private static final TextColor S6_MUTED = TextColor.color(0x5C5C5C);

    // Style 7 Colors (Sunset Boulevard)
    private static final TextColor S7_NAME = TextColor.color(0xFBC5B2);
    private static final TextColor S7_ACTION = TextColor.color(0xE8B4B8);
    private static final TextColor S7_DO = TextColor.color(0xFFE3D1);
    private static final TextColor S7_MUTED = TextColor.color(0xB5A4A3);

    // Style 8 Colors (Earthy Forest)
    private static final TextColor S8_NAME = TextColor.color(0xE2C7A1);
    private static final TextColor S8_ACCENT = TextColor.color(0xADC178);
    private static final TextColor S8_ACTION = TextColor.color(0xA3B18A);
    private static final TextColor S8_DO = TextColor.color(0x556B2F);
    private static final TextColor S8_MUTED = TextColor.color(0x8C92AC);

    // Style 9 Colors (Vogue Elegant)
    private static final TextColor S9_NAME = TextColor.color(0xE8A7A1);
    private static final TextColor S9_ACCENT = TextColor.color(0x7D5A50);
    private static final TextColor S9_ACTION = TextColor.color(0x7D5A50);
    private static final TextColor S9_DO = TextColor.color(0xF4EAE6);
    private static final TextColor S9_MUTED = TextColor.color(0xB2B2B2);

    // Style 10 Colors (Echo Whisper)
    private static final TextColor S10_NAME = TextColor.color(0xFCEADE);
    private static final TextColor S10_ACCENT = TextColor.color(0xB39DDB);
    private static final TextColor S10_ACTION = TextColor.color(0xB39DDB);
    private static final TextColor S10_DO = TextColor.color(0x80DEEA);
    private static final TextColor S10_MUTED = TextColor.color(0xB0BEC5);

    // Success / Failure Colors
    private static final TextColor COLOR_SUCCESS = TextColor.color(0x99C3A2); // Soft Olive green
    private static final TextColor COLOR_FAIL = TextColor.color(0xE3A899);    // Soft Terracotta red

    public static Component formatSpeech(String name, String message, int style) {
        TextComponent.Builder builder = Component.text();
        switch (style) {
            case 2: // Chamber Theatre
                builder.append(Component.text("«" + message + "», — сказав(ла) ", NamedTextColor.WHITE))
                       .append(Component.text(name, S2_NAME))
                       .append(Component.text(".", NamedTextColor.WHITE));
                break;
            case 3: // Nordic Aurora
                builder.append(Component.text("// ", S3_ACCENT))
                       .append(Component.text(name, S3_NAME))
                       .append(Component.text(" ‣ ", S3_DO))
                       .append(Component.text("«" + message + "»", NamedTextColor.WHITE));
                break;
            case 4: // Sakura Blossom
                builder.append(Component.text(name, S4_NAME))
                       .append(Component.text(" 🌸 ", NamedTextColor.WHITE))
                       .append(Component.text("«" + message + "»", NamedTextColor.WHITE));
                break;
            case 5: // Cyber Glow
                builder.append(Component.text("[+] ", S5_ACCENT))
                       .append(Component.text(name, S5_NAME))
                       .append(Component.text(" ➔ ", S5_MUTED))
                       .append(Component.text(message, NamedTextColor.WHITE));
                break;
            case 6: // Mono Noir
                builder.append(Component.text(name, S6_NAME))
                       .append(Component.text(" ➔ ", S6_DO))
                       .append(Component.text("«" + message + "»", NamedTextColor.WHITE));
                break;
            case 7: // Sunset Boulevard
                builder.append(Component.text(name, S7_NAME))
                       .append(Component.text(" ➔ ", S7_ACTION))
                       .append(Component.text("«" + message + "»", NamedTextColor.WHITE));
                break;
            case 8: // Earthy Forest
                builder.append(Component.text(name, S8_NAME))
                       .append(Component.text(" 🍂 ", S8_ACCENT))
                       .append(Component.text("«" + message + "»", NamedTextColor.WHITE));
                break;
            case 9: // Vogue Elegant
                builder.append(Component.text(name, S9_NAME))
                       .append(Component.text(" ❖ ", S9_ACCENT))
                       .append(Component.text("«" + message + "»", NamedTextColor.WHITE));
                break;
            case 10: // Echo Whisper
                builder.append(Component.text(name, S10_NAME))
                       .append(Component.text(" ✦ ", S10_ACCENT))
                       .append(Component.text("«" + message + "»", NamedTextColor.WHITE));
                break;
            case 1: // Aether Minimalist
            default:
                builder.append(Component.text(name, S1_NAME))
                       .append(Component.text(" каже: ", S1_MUTED))
                       .append(Component.text("«" + message + "»", NamedTextColor.WHITE));
                break;
        }
        return builder.build();
    }

    public static Component formatMe(String name, String action, int style) {
        TextComponent.Builder builder = Component.text();
        switch (style) {
            case 2: // Chamber Theatre
                builder.append(Component.text(name, S2_NAME))
                       .append(Component.text(" " + action + ".", S2_ACTION));
                break;
            case 3: // Nordic Aurora
                builder.append(Component.text(name, S3_NAME))
                       .append(Component.text(" ◈ ", S3_ACCENT))
                       .append(Component.text(action, S3_ACTION));
                break;
            case 4: // Sakura Blossom
                builder.append(Component.text(name, S4_NAME))
                       .append(Component.text(" 🌸 ", NamedTextColor.WHITE))
                       .append(Component.text(action, S4_ACTION));
                break;
            case 5: // Cyber Glow
                builder.append(Component.text(name, S5_NAME))
                       .append(Component.text(" ⚡ ", S5_ACCENT))
                       .append(Component.text(action, S5_ACTION));
                break;
            case 6: // Mono Noir
                builder.append(Component.text(name, S6_NAME))
                       .append(Component.text(" ➔ ", S6_DO))
                       .append(Component.text(action, S6_ACTION));
                break;
            case 7: // Sunset Boulevard
                builder.append(Component.text(name, S7_NAME))
                       .append(Component.text(" " + action, S7_ACTION));
                break;
            case 8: // Earthy Forest
                builder.append(Component.text(name, S8_NAME))
                       .append(Component.text(" 🍂 ", S8_ACCENT))
                       .append(Component.text(action, S8_ACTION));
                break;
            case 9: // Vogue Elegant
                builder.append(Component.text(name, S9_NAME))
                       .append(Component.text(" ❖ ", S9_ACCENT))
                       .append(Component.text(action, S9_ACTION));
                break;
            case 10: // Echo Whisper
                builder.append(Component.text(name, S10_NAME))
                       .append(Component.text(" ✦ ", S10_ACCENT))
                       .append(Component.text(action, S10_ACTION));
                break;
            case 1: // Aether Minimalist
            default:
                builder.append(Component.text(name, S1_NAME))
                       .append(Component.text(" " + action, S1_ACTION));
                break;
        }
        return builder.build();
    }

    public static Component formatDo(String name, String description, int style) {
        TextComponent.Builder builder = Component.text();
        switch (style) {
            case 2: // Chamber Theatre
                builder.append(Component.text("[" + description + "] ❧ ", S2_DO))
                       .append(Component.text(name, S2_MUTED));
                break;
            case 3: // Nordic Aurora
                builder.append(Component.text(description, S3_DO))
                       .append(Component.text(" ❖ ", S3_ACCENT))
                       .append(Component.text(name, S3_NAME));
                break;
            case 4: // Sakura Blossom
                builder.append(Component.text(description, S4_DO))
                       .append(Component.text(" — ", S4_MUTED))
                       .append(Component.text(name, S4_NAME));
                break;
            case 5: // Cyber Glow
                builder.append(Component.text(description, S5_DO))
                       .append(Component.text(" ⚡ ", S5_ACCENT))
                       .append(Component.text(name, S5_NAME));
                break;
            case 6: // Mono Noir
                builder.append(Component.text(description, S6_DO))
                       .append(Component.text(" ➔ ", S6_DO))
                       .append(Component.text(name, S6_NAME));
                break;
            case 7: // Sunset Boulevard
                builder.append(Component.text(description, S7_DO))
                       .append(Component.text(" — ", S7_MUTED))
                       .append(Component.text(name, S7_NAME));
                break;
            case 8: // Earthy Forest
                builder.append(Component.text(description, S8_DO))
                       .append(Component.text(" 🍂 ", S8_ACCENT))
                       .append(Component.text(name, S8_NAME));
                break;
            case 9: // Vogue Elegant
                builder.append(Component.text(description, S9_DO))
                       .append(Component.text(" ❖ ", S9_ACCENT))
                       .append(Component.text(name, S9_NAME));
                break;
            case 10: // Echo Whisper
                builder.append(Component.text(description, S10_DO))
                       .append(Component.text(" ✦ ", S10_ACCENT))
                       .append(Component.text(name, S10_NAME));
                break;
            case 1: // Aether Minimalist
            default:
                builder.append(Component.text(description, S1_DO))
                       .append(Component.text(" — ", S1_MUTED))
                       .append(Component.text(name, S1_MUTED));
                break;
        }
        return builder.build();
    }

    public static Component formatTry(String name, String action, boolean success, int style) {
        TextComponent.Builder builder = Component.text();
        Component statusText;
        switch (style) {
            case 2: // Chamber Theatre
                statusText = success 
                    ? Component.text("[Успішно]", COLOR_SUCCESS)
                    : Component.text("[Неуспішно]", COLOR_FAIL);
                builder.append(Component.text(name, S2_NAME))
                       .append(Component.text(" спроба " + action + " ➔ ", S2_ACTION))
                       .append(statusText);
                break;
            case 3: // Nordic Aurora
                statusText = success 
                    ? Component.text("[УСПІШНО]", COLOR_SUCCESS)
                    : Component.text("[НЕУСПІШНО]", COLOR_FAIL);
                builder.append(Component.text(name, S3_NAME))
                       .append(Component.text(" ◈ спроба " + action + " ➔ ", S3_ACCENT))
                       .append(statusText);
                break;
            case 4: // Sakura Blossom
                statusText = success 
                    ? Component.text("[Вдалося]", COLOR_SUCCESS)
                    : Component.text("[Не вдалося]", COLOR_FAIL);
                builder.append(Component.text(name, S4_NAME))
                       .append(Component.text(" намагається " + action + "... 🌸 ", S4_ACTION))
                       .append(statusText);
                break;
            case 5: // Cyber Glow
                statusText = success 
                    ? Component.text("[УСПІХ]", COLOR_SUCCESS)
                    : Component.text("[КРАХ]", COLOR_FAIL);
                builder.append(Component.text(name, S5_NAME))
                       .append(Component.text(" ⚡ спроба " + action + " ➔ ", S5_ACCENT))
                       .append(statusText);
                break;
            case 6: // Mono Noir
                statusText = success 
                    ? Component.text("[ТАК]", COLOR_SUCCESS)
                    : Component.text("[НІ]", COLOR_FAIL);
                builder.append(Component.text(name, S6_NAME))
                       .append(Component.text(" ➔ спроба " + action + " ➔ ", S6_DO))
                       .append(statusText);
                break;
            case 7: // Sunset Boulevard
                statusText = success 
                    ? Component.text("[Успіх]", COLOR_SUCCESS)
                    : Component.text("[Невдача]", COLOR_FAIL);
                builder.append(Component.text(name, S7_NAME))
                       .append(Component.text(" намагається " + action + " ➔ ", S7_ACTION))
                       .append(statusText);
                break;
            case 8: // Earthy Forest
                statusText = success 
                    ? Component.text("[Вдало]", COLOR_SUCCESS)
                    : Component.text("[Невдало]", COLOR_FAIL);
                builder.append(Component.text(name, S8_NAME))
                       .append(Component.text(" 🍂 спроба " + action + " ➔ ", S8_ACCENT))
                       .append(statusText);
                break;
            case 9: // Vogue Elegant
                statusText = success 
                    ? Component.text("[УСПІШНО]", COLOR_SUCCESS)
                    : Component.text("[НЕУСПІШНО]", COLOR_FAIL);
                builder.append(Component.text(name, S9_NAME))
                       .append(Component.text(" ❖ спроба " + action + "... ", S9_ACCENT))
                       .append(statusText);
                break;
            case 10: // Echo Whisper
                statusText = success 
                    ? Component.text("[Успіх]", COLOR_SUCCESS)
                    : Component.text("[Невдача]", COLOR_FAIL);
                builder.append(Component.text(name, S10_NAME))
                       .append(Component.text(" ✦ спроба " + action + "... ➔ ", S10_ACCENT))
                       .append(statusText);
                break;
            case 1: // Aether Minimalist
            default:
                statusText = success 
                    ? Component.text("[Успішно]", COLOR_SUCCESS)
                    : Component.text("[Неуспішно]", COLOR_FAIL);
                builder.append(Component.text(name, S1_NAME))
                       .append(Component.text(" намагається " + action + "... ", S1_ACTION))
                       .append(statusText);
                break;
        }
        return builder.build();
    }

    public static Component formatTodo(String name, String speech, String action, int style) {
        TextComponent.Builder builder = Component.text();
        switch (style) {
            case 2: // Chamber Theatre
                builder.append(Component.text("«" + speech + "», — сказав(ла) ", NamedTextColor.WHITE))
                       .append(Component.text(name + ", ", S2_NAME))
                       .append(Component.text(action + ".", S2_ACTION));
                break;
            case 3: // Nordic Aurora
                builder.append(Component.text("«" + speech + "» ", NamedTextColor.WHITE))
                       .append(Component.text("◈ ", S3_ACCENT))
                       .append(Component.text(name, S3_NAME))
                       .append(Component.text(" ➔ ", S3_ACCENT))
                       .append(Component.text(action, S3_ACTION));
                break;
            case 4: // Sakura Blossom
                builder.append(Component.text("«" + speech + "» — ", NamedTextColor.WHITE))
                       .append(Component.text(name, S4_NAME))
                       .append(Component.text(" 🌸 ", NamedTextColor.WHITE))
                       .append(Component.text(action, S4_ACTION));
                break;
            case 5: // Cyber Glow
                builder.append(Component.text("«" + speech + "» ", NamedTextColor.WHITE))
                       .append(Component.text("⚡ ", S5_ACCENT))
                       .append(Component.text(name, S5_NAME))
                       .append(Component.text(" ➔ ", S5_MUTED))
                       .append(Component.text(action, S5_ACTION));
                break;
            case 6: // Mono Noir
                builder.append(Component.text("«" + speech + "» ➔ ", NamedTextColor.WHITE))
                       .append(Component.text(name, S6_NAME))
                       .append(Component.text(" ➔ ", S6_DO))
                       .append(Component.text(action, S6_ACTION));
                break;
            case 7: // Sunset Boulevard
                builder.append(Component.text("«" + speech + "» — ", NamedTextColor.WHITE))
                       .append(Component.text(name + ", ", S7_NAME))
                       .append(Component.text(action, S7_ACTION));
                break;
            case 8: // Earthy Forest
                builder.append(Component.text("«" + speech + "» 🍂 ", NamedTextColor.WHITE))
                       .append(Component.text(name, S8_NAME))
                       .append(Component.text(", ", S8_MUTED))
                       .append(Component.text(action, S8_ACTION));
                break;
            case 9: // Vogue Elegant
                builder.append(Component.text("«" + speech + "» ❖ ", NamedTextColor.WHITE))
                       .append(Component.text(name, S9_NAME))
                       .append(Component.text(", ", S9_ACCENT))
                       .append(Component.text(action, S9_ACTION));
                break;
            case 10: // Echo Whisper
                builder.append(Component.text("«" + speech + "» ✦ ", NamedTextColor.WHITE))
                       .append(Component.text(name, S10_NAME))
                       .append(Component.text(" ➔ ", S10_ACCENT))
                       .append(Component.text(action, S10_ACTION));
                break;
            case 1: // Aether Minimalist
            default:
                builder.append(Component.text("«" + speech + "» — " + name + ", ", NamedTextColor.WHITE))
                       .append(Component.text(action, S1_ACTION));
                break;
        }
        return builder.build();
    }

    public static Component formatB(String name, String message, int style) {
        TextComponent.Builder builder = Component.text();
        switch (style) {
            case 2: // Chamber Theatre
                builder.append(Component.text("⌗ " + name + " ➔ " + message, S2_MUTED));
                break;
            case 3: // Nordic Aurora
                builder.append(Component.text("// " + name + " ➔ " + message, S3_MUTED));
                break;
            case 4: // Sakura Blossom
                builder.append(Component.text("OOC ☕ " + name + ": " + message, S4_MUTED));
                break;
            case 5: // Cyber Glow
                builder.append(Component.text("[!] " + name + ": " + message, S5_MUTED));
                break;
            case 6: // Mono Noir
                builder.append(Component.text(name + ": " + message, S6_MUTED));
                break;
            case 7: // Sunset Boulevard
                builder.append(Component.text("[ooc] " + name + ": " + message, S7_MUTED));
                break;
            case 8: // Earthy Forest
                builder.append(Component.text("OOC 🍂 " + name + " ➔ " + message, S8_MUTED));
                break;
            case 9: // Vogue Elegant
                builder.append(Component.text("OOC ❖ " + name + " ➔ " + message, S9_MUTED));
                break;
            case 10: // Echo Whisper
                builder.append(Component.text("✦ " + name + " ➔ " + message, S10_MUTED));
                break;
            case 1: // Aether Minimalist
            default:
                builder.append(Component.text(name, S1_OOC_NAME))
                       .append(Component.text(": ", S1_OOC_SEP))
                       .append(Component.text(message, S1_OOC_MSG));
                break;
        }
        return builder.build();
    }
}
