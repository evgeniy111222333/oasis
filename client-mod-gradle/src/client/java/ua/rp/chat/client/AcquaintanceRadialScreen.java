package ua.rp.chat.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.UUID;

public class AcquaintanceRadialScreen extends Screen {
    private final UUID targetId;
    private final String targetLabel;
    private int ticksOpen;

    public AcquaintanceRadialScreen(UUID targetId, String targetLabel) {
        super(Component.literal("Oasis acquaintance"));
        this.targetId = targetId;
        this.targetLabel = targetLabel == null ? "Незнакомец" : targetLabel;
    }

    @Override
    public void tick() {
        ticksOpen++;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        int cx = width / 2;
        int cy = height / 2;
        float open = Math.min(1.0f, (ticksOpen + delta) / 9.0f);
        int radius = (int) (66 + 30 * open);
        graphics.fill(0, 0, width, height, 0x62000000);

        drawFrame(graphics, cx, cy, open);
        drawOption(graphics, cx, cy - radius, 112, 32, "Приветствие", "открытые ладони", hovered(mouseX, mouseY, cx, cy - radius, 112, 32));
        drawOption(graphics, cx + radius, cy, 118, 32, "Записать", "личную примету", hovered(mouseX, mouseY, cx + radius, cy, 118, 32));
        drawOption(graphics, cx, cy + radius, 108, 32, "Осмотреть", "без действия", hovered(mouseX, mouseY, cx, cy + radius, 108, 32));
        drawOption(graphics, cx - radius, cy, 104, 32, "Отойти", "закрыть меню", hovered(mouseX, mouseY, cx - radius, cy, 104, 32));

        graphics.centeredText(font, targetLabel, cx, cy - 9, 0xFFE3C099);
        graphics.centeredText(font, "G / Esc - закрыть", cx, cy + 9, 0xFF9A9289);
    }

    private void drawFrame(GuiGraphicsExtractor graphics, int cx, int cy, float open) {
        int r = (int) (76 * open);
        int outer = 0xD018140F;
        int metal = 0xCC6B5A43;
        graphics.fill(cx - r, cy - r, cx + r, cy - r + 4, metal);
        graphics.fill(cx - r, cy + r - 4, cx + r, cy + r, metal);
        graphics.fill(cx - r, cy - r, cx - r + 4, cy + r, metal);
        graphics.fill(cx + r - 4, cy - r, cx + r, cy + r, metal);
        graphics.fill(cx - 52, cy - 24, cx + 52, cy + 24, outer);
        graphics.fill(cx - 52, cy - 24, cx + 52, cy - 22, 0xAAE3C099);
        graphics.fill(cx - 52, cy + 22, cx + 52, cy + 24, 0x88513F2F);
    }

    private void drawOption(GuiGraphicsExtractor graphics, int cx, int cy, int w, int h, String title, String sub, boolean hovered) {
        int x = cx - w / 2;
        int y = cy - h / 2;
        int bg = hovered ? 0xE02B2118 : 0xC016120F;
        int edge = hovered ? 0xFFE3C099 : 0xAA8A765C;
        graphics.fill(x, y, x + w, y + h, bg);
        graphics.fill(x, y, x + w, y + 2, edge);
        graphics.fill(x, y + h - 2, x + w, y + h, 0x88513F2F);
        graphics.centeredText(font, title, cx, y + 6, hovered ? 0xFFFFF4DE : 0xFFE3C099);
        graphics.centeredText(font, sub, cx, y + 19, 0xFF9A9289);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        int cx = width / 2;
        int cy = height / 2;
        int radius = 96;
        if (hovered(event.x(), event.y(), cx, cy - radius, 112, 32)) {
            AcquaintanceClientState.greet(targetId);
            onClose();
            return true;
        }
        if (hovered(event.x(), event.y(), cx + radius, cy, 118, 32)) {
            AcquaintanceClientState.openNote(targetId, targetLabel);
            return true;
        }
        if (hovered(event.x(), event.y(), cx - radius, cy, 104, 32) || hovered(event.x(), event.y(), cx, cy + radius, 108, 32)) {
            onClose();
            return true;
        }
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == 256 || event.key() == 71) {
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

    private boolean hovered(double mx, double my, int cx, int cy, int w, int h) {
        return mx >= cx - w / 2.0 && mx <= cx + w / 2.0 && my >= cy - h / 2.0 && my <= cy + h / 2.0;
    }
}
