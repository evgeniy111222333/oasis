package ua.rp.chat.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

import java.util.UUID;

public class AcquaintanceInputScreen extends Screen {
    private final UUID targetId;
    private final int action;
    private final String prompt;
    private String value;
    private int ticksOpen;

    public AcquaintanceInputScreen(UUID targetId, int action, String prompt, String defaultValue) {
        super(Component.literal(prompt));
        this.targetId = targetId;
        this.action = action;
        this.prompt = prompt;
        this.value = defaultValue == null ? "" : defaultValue;
    }

    @Override
    public void tick() {
        ticksOpen++;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        int boxW = Math.min(420, width - 44);
        int boxH = 118;
        int x = width / 2 - boxW / 2;
        int y = height / 2 - boxH / 2;
        graphics.fill(0, 0, width, height, 0x64000000);
        graphics.fill(x, y, x + boxW, y + boxH, 0xEC15110E);
        graphics.fill(x, y, x + boxW, y + 3, 0xFFE3C099);
        graphics.fill(x + 10, y + 48, x + boxW - 10, y + 76, 0xFF211A15);
        graphics.centeredText(font, prompt, width / 2, y + 18, 0xFFE3C099);
        String cursor = ticksOpen % 20 < 10 ? "_" : "";
        graphics.text(font, value + cursor, x + 18, y + 58, 0xFFFFF4DE);
        graphics.centeredText(font, "Enter - подтвердить   Esc - отменить", width / 2, y + 92, 0xFF9A9289);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (Character.isBmpCodePoint(event.codepoint()) && value.length() < 48) {
            char c = (char) event.codepoint();
            if (!Character.isISOControl(c)) {
                value += c;
            }
        }
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == 256) {
            onClose();
            return true;
        }
        if (event.key() == 257 || event.key() == 335) {
            AcquaintanceClientState.send(action, targetId, value);
            onClose();
            return true;
        }
        if (event.key() == 259 && !value.isEmpty()) {
            value = value.substring(0, value.length() - 1);
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
}
