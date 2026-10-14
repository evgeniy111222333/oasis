package ua.rp.chat.client.tab;

import java.util.function.ToIntFunction;

/** Keeps names on one line and truncates without breaking Unicode surrogate pairs. */
public final class TabText {
    private TabText() { }

    public static String clean(String input) {
        if (input == null) return "";
        StringBuilder result = new StringBuilder();
        boolean formatting = false;
        int visible = 0;
        for (int offset = 0; offset < input.length() && visible < 128;) {
            int cp = input.codePointAt(offset);
            offset += Character.charCount(cp);
            if (formatting) { formatting = false; continue; }
            if (cp == 0xA7) { formatting = true; continue; }
            if (Character.isWhitespace(cp)) cp = ' ';
            if (Character.isISOControl(cp) || Character.getType(cp) == Character.FORMAT) continue;
            result.appendCodePoint(cp);
            visible++;
        }
        return result.toString().strip();
    }

    public static String ellipsize(String text, int capacity, ToIntFunction<String> width) {
        if (capacity <= 0) return "";
        if (width.applyAsInt(text) <= capacity) return text;
        String ellipsis = "…";
        if (width.applyAsInt(ellipsis) > capacity) return "";
        int low = 0;
        int high = text.codePointCount(0, text.length());
        while (low < high) {
            int mid = (low + high + 1) >>> 1;
            String candidate = text.substring(0, text.offsetByCodePoints(0, mid)) + ellipsis;
            if (width.applyAsInt(candidate) <= capacity) low = mid;
            else high = mid - 1;
        }
        return text.substring(0, text.offsetByCodePoints(0, low)).stripTrailing() + ellipsis;
    }
}
