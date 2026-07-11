package ua.rp.chat.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class SearchResultsScreen extends Screen {
    private final UUID targetId;
    private final String title;
    private final List<SearchLine> lines = new ArrayList<>();
    private int ticksOpen;

    public SearchResultsScreen(UUID targetId, String title, JsonArray items) {
        super(Component.literal("Oasis search"));
        this.targetId = targetId;
        this.title = title == null ? "Обыск" : title;
        for (int i = 0; i < items.size(); i++) {
            JsonObject item = items.get(i).getAsJsonObject();
            lines.add(new SearchLine(
                    item.get("key").getAsString(),
                    item.get("label").getAsString(),
                    item.has("amount") ? item.get("amount").getAsInt() : 1,
                    item.has("slot") ? item.get("slot").getAsString() : ""
            ));
        }
    }

    @Override
    public void tick() {
        ticksOpen++;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        int panelW = Math.min(430, width - 42);
        int panelH = Math.min(height - 70, Math.max(156, 74 + Math.max(1, lines.size()) * 34));
        int x = width / 2 - panelW / 2;
        int y = height / 2 - panelH / 2;
        float open = Math.min(1.0f, (ticksOpen + delta) / 8.0f);
        int a = Math.min(226, Math.round(226 * open));

        graphics.fill(0, 0, width, height, 0x76000000);
        graphics.fill(x, y, x + panelW, y + panelH, (a << 24) | 0x17130F);
        graphics.fill(x, y, x + panelW, y + 3, 0xFFE3C099);
        graphics.centeredText(font, title, width / 2, y + 13, 0xFFE3C099);
        graphics.centeredText(font, "ЛКМ - забрать, Esc - закрыть", width / 2, y + 30, 0xFF9A9289);

        if (lines.isEmpty()) {
            graphics.centeredText(font, "Ничего не найдено", width / 2, y + 70, 0xFFB0A8A0);
            return;
        }

        int rowY = y + 52;
        for (int i = 0; i < lines.size(); i++) {
            SearchLine line = lines.get(i);
            int rowX = x + 14;
            int rowW = panelW - 28;
            boolean hover = mouseX >= rowX && mouseX <= rowX + rowW && mouseY >= rowY && mouseY <= rowY + 28;
            graphics.fill(rowX, rowY, rowX + rowW, rowY + 28, hover ? 0xE02B2118 : 0xAA211A14);
            graphics.fill(rowX, rowY, rowX + 3, rowY + 28, hover ? 0xFFE3C099 : 0xAA7FD0CC);
            graphics.text(font, fit(line.label, rowW - 116), rowX + 10, rowY + 5, hover ? 0xFFFFF4DE : 0xFFE3C099);
            graphics.text(font, fit(line.slot, 86), rowX + rowW - 94, rowY + 5, 0xFF9A9289);
            rowY += 34;
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        int panelW = Math.min(430, width - 42);
        int panelH = Math.min(height - 70, Math.max(156, 74 + Math.max(1, lines.size()) * 34));
        int x = width / 2 - panelW / 2;
        int y = height / 2 - panelH / 2;
        int rowY = y + 52;
        for (int i = 0; i < lines.size(); i++) {
            int rowX = x + 14;
            int rowW = panelW - 28;
            if (event.x() >= rowX && event.x() <= rowX + rowW && event.y() >= rowY && event.y() <= rowY + 28) {
                SearchLine line = lines.remove(i);
                AcquaintanceClientState.takeSearchItem(targetId, line.key);
                return true;
            }
            rowY += 34;
        }
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == 256) {
            onClose();
            return true;
        }
        return true;
    }

    @Override
    public void onClose() {
        if (minecraft != null) {
            minecraft.setScreen(null);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private String fit(String value, int maxWidth) {
        if (font.width(value) <= maxWidth) {
            return value;
        }
        String trimmed = value;
        while (!trimmed.isEmpty() && font.width(trimmed + "...") > maxWidth) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed.isEmpty() ? "..." : trimmed + "...";
    }

    private record SearchLine(String key, String label, int amount, String slot) {
    }
}
