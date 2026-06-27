package ua.rp.chat.client;

import com.cinemamod.mcef.MCEF;
import com.cinemamod.mcef.MCEFBrowser;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;

import java.net.URI;

public class AuthScreen extends Screen {

    private final String authUrl;
    private MCEFBrowser browser;
    private boolean openedExternalBrowser;
    private String browserStatus = "Preparing authorization...";

    protected AuthScreen(String authUrl) {
        super(Component.literal("Oasis Auth"));
        this.authUrl = authUrl;
    }

    @Override
    protected void init() {
        super.init();

        openExternalBrowserOnce();

        try {
            if (!MCEF.isInitialized()) {
                MCEF.initialize();
            }
        } catch (Throwable t) {
            browserStatus = "MCEF is installed, but Chromium did not initialize. Browser opened outside the game.";
            OasisAuthMod.LOGGER.warn("MCEF initialization failed, using external browser fallback.", t);
            return;
        }

        try {
            browser = MCEF.createBrowser(authUrl, true);
            if (browser != null) {
                browser.resize(width, height);
                browserStatus = "MCEF browser created. If embedded view is blank, use the opened browser window.";
            } else {
                browserStatus = "MCEF returned no browser. Browser opened outside the game.";
            }
        } catch (Throwable t) {
            browser = null;
            browserStatus = "MCEF browser failed. Browser opened outside the game.";
            OasisAuthMod.LOGGER.warn("MCEF browser creation failed, using external browser fallback.", t);
        }

        OasisAuthMod.LOGGER.info("Opening auth flow for: " + authUrl);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        extractBackground(graphics, mouseX, mouseY, delta);
        graphics.fill(0, 0, width, height, 0xE612100F);
        graphics.centeredText(font, "OASIS ROLEPLAY AUTHORIZATION", width / 2, height / 2 - 44, 0xFFE3C099);
        graphics.centeredText(font, "Complete login or registration in the browser window.", width / 2, height / 2 - 20, 0xFFB0A8A0);
        graphics.centeredText(font, browserStatus, width / 2, height / 2 + 2, 0xFFA5C3C4);
        graphics.centeredText(font, "Do not close the game. This screen will close after successful authorization.", width / 2, height / 2 + 24, 0xFFE3A899);
        super.extractRenderState(graphics, mouseX, mouseY, delta);
    }

    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        if (browser != null) {
            browser.sendMouseMove((int) mouseX, (int) mouseY);
        }
        super.mouseMoved(mouseX, mouseY);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (browser != null) {
            browser.sendMousePress((int) event.x(), (int) event.y(), event.button());
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (browser != null) {
            browser.sendMouseRelease((int) event.x(), (int) event.y(), event.button());
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (browser != null) {
            browser.sendKeyPress(event.key(), event.scancode(), event.modifiers());
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (browser != null && Character.isBmpCodePoint(event.codepoint())) {
            browser.sendKeyTyped((char) event.codepoint(), 0);
        }
        return super.charTyped(event);
    }

    @Override
    public void resize(int width, int height) {
        super.resize(width, height);
        if (browser != null) {
            browser.resize(width, height);
        }
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

    private void openExternalBrowserOnce() {
        if (openedExternalBrowser) {
            return;
        }

        openedExternalBrowser = true;
        try {
            Util.getPlatform().openUri(new URI(authUrl));
        } catch (Exception e) {
            browserStatus = "Could not open external browser automatically. Use the link in chat.";
            OasisAuthMod.LOGGER.warn("Failed to open auth URL in external browser.", e);
        }
    }
}
