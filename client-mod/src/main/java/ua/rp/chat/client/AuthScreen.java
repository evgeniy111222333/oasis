package ua.rp.chat.client;

import com.cinemamod.mcef.MCEF;
import com.cinemamod.mcef.MCEFBrowser;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import net.minecraft.world.level.GameType;

import java.net.URI;

public class AuthScreen extends Screen {

    private final String authUrl;
    private MCEFBrowser browser;
    private String fallbackStatus = "Initializing Oasis authorization...";
    private int ticksOpen;
    private int exitX;
    private int exitY;
    private int exitWidth = 84;
    private int exitHeight = 24;

    protected AuthScreen(String authUrl) {
        super(Component.literal("Oasis Auth"));
        this.authUrl = authUrl;
    }

    @Override
    protected void init() {
        super.init();

        try {
            if (!MCEF.isInitialized()) {
                MCEF.initialize();
            }

            if (browser == null) {
                browser = MCEF.createBrowser(authUrl, true);
            }

            resizeBrowser();
            if (browser != null) {
                browser.setFocus(true);
            }
        } catch (Throwable t) {
            browser = null;
            fallbackStatus = "Embedded browser is unavailable. Opening authorization in your default browser.";
            OasisAuthMod.LOGGER.warn("MCEF auth browser failed, using external browser fallback.", t);
            openExternalFallback();
        }

        OasisAuthMod.LOGGER.info("Opening embedded auth flow for: " + authUrl);
    }

    @Override
    public void tick() {
        super.tick();
        ticksOpen++;

        if (ticksOpen > 80 && minecraft != null && minecraft.gameMode != null
                && minecraft.gameMode.getPlayerMode() != GameType.SPECTATOR) {
            minecraft.setScreen(null);
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        if (browser != null && browser.isTextureReady()) {
            Identifier texture = browser.getTextureIdentifier();
            if (texture != null) {
                graphics.blit(RenderPipelines.GUI_TEXTURED, texture, 0, 0, 0.0f, 0.0f, width, height, width, height);
                drawExitButton(graphics, mouseX, mouseY);
                return;
            }
        }

        extractBackground(graphics, mouseX, mouseY, delta);
        graphics.fill(0, 0, width, height, 0xF012100F);
        graphics.centeredText(font, "OASIS ROLEPLAY", width / 2, height / 2 - 38, 0xFFE3C099);
        graphics.centeredText(font, fallbackStatus, width / 2, height / 2 - 12, 0xFFB0A8A0);
        graphics.centeredText(font, "Waiting for authorization window...", width / 2, height / 2 + 12, 0xFFA5C3C4);
        drawExitButton(graphics, mouseX, mouseY);
    }

    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        if (browser != null) {
            browser.sendMouseMove(scaleMouseX(mouseX), scaleMouseY(mouseY));
        }
        super.mouseMoved(mouseX, mouseY);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (isExitButtonHovered(event.x(), event.y())) {
            leaveToMainMenu();
            return true;
        }
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
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (browser != null) {
            browser.sendMouseWheel(scaleMouseX(mouseX), scaleMouseY(mouseY), verticalAmount, 0);
            browser.setFocus(true);
        }
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
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
            browser.close();
            browser = null;
        }
        super.onClose();
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void resizeBrowser() {
        if (browser == null || minecraft == null) {
            return;
        }

        updateExitButtonBounds();
        int scale = Math.max(1, minecraft.getWindow().getGuiScale());
        browser.resize(Math.max(1, width * scale), Math.max(1, height * scale));
    }

    private void drawExitButton(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        updateExitButtonBounds();
        boolean hovered = isExitButtonHovered(mouseX, mouseY);
        graphics.fill(exitX, exitY, exitX + exitWidth, exitY + exitHeight, hovered ? 0xE06F4B43 : 0xB01B1714);
        graphics.fill(exitX, exitY, exitX + exitWidth, exitY + 1, hovered ? 0xFFE3C099 : 0x99E3C099);
        graphics.centeredText(font, "\u0412\u044b\u0439\u0442\u0438", exitX + exitWidth / 2, exitY + 8, hovered ? 0xFFFFF6E8 : 0xFFE3C099);
    }

    private void updateExitButtonBounds() {
        exitX = 28;
        exitY = Math.max(52, height - exitHeight - 86);
    }

    private boolean isExitButtonHovered(double mouseX, double mouseY) {
        return mouseX >= exitX && mouseX <= exitX + exitWidth && mouseY >= exitY && mouseY <= exitY + exitHeight;
    }

    private void leaveToMainMenu() {
        if (browser != null) {
            browser.close();
            browser = null;
        }
        if (minecraft == null) {
            return;
        }
        TitleScreen title = new TitleScreen();
        if (minecraft.level != null) {
            minecraft.disconnect(title, false);
        } else {
            minecraft.setScreen(title);
        }
    }

    private int scaleMouseX(double mouseX) {
        return (int) (mouseX * getGuiScale());
    }

    private int scaleMouseY(double mouseY) {
        return (int) (mouseY * getGuiScale());
    }

    private int getGuiScale() {
        if (minecraft == null) {
            return 1;
        }

        return Math.max(1, minecraft.getWindow().getGuiScale());
    }

    private void openExternalFallback() {
        try {
            Util.getPlatform().openUri(new URI(authUrl));
        } catch (Exception e) {
            fallbackStatus = "Could not open browser automatically. Use the authorization link in chat.";
            OasisAuthMod.LOGGER.warn("Failed to open auth URL in external browser.", e);
        }
    }
}
