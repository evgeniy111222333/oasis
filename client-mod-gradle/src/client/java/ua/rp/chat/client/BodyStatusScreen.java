package ua.rp.chat.client;

import com.cinemamod.mcef.MCEF;
import com.cinemamod.mcef.MCEFBrowser;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;

import java.net.URI;

public class BodyStatusScreen extends Screen {
    private static MCEFBrowser sharedBrowser;
    private static String sharedUrl;

    private final String url;
    private MCEFBrowser browser;
    private String fallbackStatus = "Открываем состояние персонажа...";
    private int panelX;
    private int panelY;
    private int panelWidth;
    private int panelHeight;

    public BodyStatusScreen(String url) {
        super(Component.literal("Oasis: состояние персонажа"));
        this.url = url;
    }

    @Override
    protected void init() {
        super.init();
        try {
            if (!MCEF.isInitialized()) {
                MCEF.initialize();
            }
            if (sharedBrowser == null) {
                sharedBrowser = MCEF.createBrowser(url, true);
                sharedUrl = url;
            } else if (!url.equals(sharedUrl)) {
                sharedBrowser.loadURL(url);
                sharedUrl = url;
            }
            browser = sharedBrowser;
            resizeBrowser();
            if (browser != null) {
                browser.setFocus(true);
            }
        } catch (Throwable t) {
            browser = null;
            fallbackStatus = "Встроенная панель состояния недоступна. Открываем в браузере.";
            OasisAuthMod.LOGGER.warn("MCEF body status failed, using external fallback.", t);
            openExternalFallback();
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        updatePanelBounds();
        if (browser != null && browser.isTextureReady()) {
            Identifier texture = browser.getTextureIdentifier();
            if (texture != null) {
                graphics.blit(RenderPipelines.GUI_TEXTURED, texture, panelX, panelY, 0.0f, 0.0f, panelWidth, panelHeight, panelWidth, panelHeight);
                return;
            }
        }
        graphics.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight, 0xF012100F);
        graphics.centeredText(font, "OASIS: СТАТУС", width / 2, height / 2 - 12, 0xFFE3C099);
        graphics.centeredText(font, fallbackStatus, width / 2, height / 2 + 10, 0xFFA5C3C4);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        // The medical panel is an in-world overlay. Leaving this empty prevents
        // Screen from drawing the profile-dependent menu panorama behind MCEF.
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (browser != null) {
            browser.sendMousePress(scaleMouseX(event.x()), scaleMouseY(event.y()), event.button());
            browser.setFocus(true);
        }
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (browser != null) {
            browser.sendMouseRelease(scaleMouseX(event.x()), scaleMouseY(event.y()), event.button());
            browser.setFocus(true);
        }
        return true;
    }

    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        if (browser != null) {
            browser.sendMouseMove(scaleMouseX(mouseX), scaleMouseY(mouseY));
        }
        super.mouseMoved(mouseX, mouseY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (browser != null) {
            browser.sendMouseWheel(scaleMouseX(mouseX), scaleMouseY(mouseY), verticalAmount, 0);
            browser.setFocus(true);
        }
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == 256) {
            onClose();
            return true;
        }
        if (browser != null) {
            browser.sendKeyPress(event.key(), event.scancode(), event.modifiers());
            browser.setFocus(true);
        }
        return true;
    }

    @Override
    public boolean keyReleased(KeyEvent event) {
        if (browser != null) {
            browser.sendKeyRelease(event.key(), event.scancode(), event.modifiers());
            browser.setFocus(true);
        }
        return true;
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (browser != null && Character.isBmpCodePoint(event.codepoint())) {
            browser.sendKeyTyped((char) event.codepoint(), 0);
            browser.setFocus(true);
        }
        return true;
    }

    @Override
    public void resize(int width, int height) {
        super.resize(width, height);
        resizeBrowser();
    }

    @Override
    public void onClose() {
        if (browser != null) {
            browser.setFocus(false);
            browser = null;
        }
        if (minecraft != null) {
            minecraft.setScreen(null);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void resizeBrowser() {
        if (browser == null || minecraft == null) {
            return;
        }
        updatePanelBounds();
        int scale = Math.max(1, minecraft.getWindow().getGuiScale());
        browser.resize(Math.max(1, panelWidth * scale), Math.max(1, panelHeight * scale));
    }

    private void updatePanelBounds() {
        panelWidth = Math.min(980, Math.max(680, width - 48));
        panelHeight = Math.min(620, Math.max(460, height - 48));
        panelX = (width - panelWidth) / 2;
        panelY = (height - panelHeight) / 2;
    }

    private int scaleMouseX(double mouseX) {
        return (int) ((mouseX - panelX) * getGuiScale());
    }

    private int scaleMouseY(double mouseY) {
        return (int) ((mouseY - panelY) * getGuiScale());
    }

    private int getGuiScale() {
        return minecraft == null ? 1 : Math.max(1, minecraft.getWindow().getGuiScale());
    }

    private void openExternalFallback() {
        try {
            Util.getPlatform().openUri(new URI(url));
        } catch (Exception e) {
            fallbackStatus = "Не удалось открыть состояние персонажа.";
            OasisAuthMod.LOGGER.warn("Failed to open body status URL.", e);
        }
    }
}
