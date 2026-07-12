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
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.FileNotFoundException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;

public class AuthScreen extends Screen {

    private final String authUrl;
    private final String traceId = McefDiagnostics.nextTraceId("auth");
    private final long createdNanos = System.nanoTime();
    private MCEFBrowser browser;
    private String fallbackStatus = "Подготовка авторизации Eclipse...";
    private int ticksOpen;
    private int exitX;
    private int exitY;
    private int exitWidth = 84;
    private int exitHeight = 24;
    private boolean lastTextureReady;
    private boolean firstFallbackLogged;
    private boolean firstBlitLogged;
    private boolean closeLogged;
    private int lastBrowserWidth = -1;
    private int lastBrowserHeight = -1;

    protected AuthScreen(String authUrl) {
        super(Component.literal("Авторизация Eclipse"));
        this.authUrl = authUrl;
        log("constructed: url=" + McefDiagnostics.safeUrl(authUrl));
    }

    boolean usesAuthUrl(String candidate) {
        return authUrl.equals(candidate);
    }

    @Override
    protected void init() {
        super.init();
        log("init start: screen=" + width + "x" + height + ", guiScale=" + getGuiScale()
                + ", " + McefDiagnostics.browserState(browser));

        try {
            if (!MCEF.isInitialized()) {
                log("MCEF.initialize start");
                MCEF.initialize();
                log("MCEF.initialize complete");
            }

            if (browser == null) {
                // The auth page is fully opaque. Avoiding a transparent OSR surface
                // keeps CEF on the stable texture path and prevents missing composited layers.
                String targetUrl = getLocalAuthUrl(authUrl);
                log("Opening local auth URL: " + targetUrl);
                browser = MCEF.createBrowser(targetUrl, false);
                log("browser created: " + McefDiagnostics.browserState(browser));
                McefDiagnostics.registerHandlers(browser, traceId);
            }

            resizeBrowser();
            if (browser != null) {
                browser.setFocus(true);
            }
        } catch (Throwable t) {
            browser = null;
            fallbackStatus = "Встроенный браузер недоступен. Открываем вход во внешнем браузере.";
            EclipseClientMod.LOGGER.warn("MCEF auth browser failed, using external browser fallback.", t);
            log("init failed: " + t.getClass().getName() + ": " + String.valueOf(t.getMessage()));
            openExternalFallback();
        }

        log("init complete: url=" + McefDiagnostics.safeUrl(authUrl) + ", " + McefDiagnostics.browserState(browser));
    }

    private String getLocalAuthUrl(String remoteUrl) {
        String token = "";
        String username = "";
        String apiHost = "https://api.eclipse-roleplay.online";
        try {
            URI uri = new URI(remoteUrl);
            String query = uri.getQuery();
            if (query != null) {
                String[] pairs = query.split("&");
                for (String pair : pairs) {
                    int idx = pair.indexOf("=");
                    if (idx > 0) {
                        String key = pair.substring(0, idx);
                        String val = URLDecoder.decode(pair.substring(idx + 1), "UTF-8");
                        if (key.equals("token")) {
                            token = val;
                        } else if (key.equals("username")) {
                            username = val;
                        }
                    }
                }
            }
            String scheme = uri.getScheme();
            String authority = uri.getAuthority();
            if (scheme != null && authority != null) {
                apiHost = scheme + "://" + authority;
            }
        } catch (Exception e) {
            EclipseClientMod.LOGGER.error("Failed to parse remote auth URL", e);
        }

        try {
            File webDir = new File(minecraft.gameDirectory, "web");
            String[] webFiles = {
                "index.html",
                "style.css",
                "app.js",
                "bg_village.jpg",
                "fonts/500.woff2",
                "fonts/600.woff2",
                "fonts/700.woff2",
                "fonts/800.woff2"
            };
            for (String file : webFiles) {
                File target = new File(webDir, file);
                copyResourceToFile("/assets/eclipseclient/web/" + file, target);
            }
            
            File localIndexFile = new File(webDir, "index.html");
            return "file:///" + localIndexFile.getAbsolutePath().replace("\\", "/") 
                + "?token=" + URLEncoder.encode(token, "UTF-8")
                + "&username=" + URLEncoder.encode(username, "UTF-8")
                + "&apiUrl=" + URLEncoder.encode(apiHost, "UTF-8");
        } catch (Exception e) {
            EclipseClientMod.LOGGER.error("Failed to extract local web resources, fallback to remote", e);
            return remoteUrl;
        }
    }

    private static void copyResourceToFile(String resourcePath, File targetFile) throws IOException {
        File parent = targetFile.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        try (InputStream in = AuthScreen.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new FileNotFoundException("Resource not found in JAR: " + resourcePath);
            }
            try (OutputStream out = new FileOutputStream(targetFile)) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = in.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                }
            }
        }
    }

    @Override
    public void tick() {
        super.tick();
        ticksOpen++;

        boolean textureReady = browser != null && browser.isTextureReady();
        if (textureReady != lastTextureReady) {
            lastTextureReady = textureReady;
            log("textureReady changed to " + textureReady + ": " + McefDiagnostics.browserState(browser));
        }
        if (ticksOpen == 1 || ticksOpen == 5 || ticksOpen == 20 || ticksOpen == 40
                || ticksOpen == 80 || ticksOpen == 100 || ticksOpen == 200
                || ticksOpen == 400 || ticksOpen == 800 || ticksOpen == 1200) {
            String gameMode = minecraft == null || minecraft.gameMode == null
                    ? "unknown" : String.valueOf(minecraft.gameMode.getPlayerMode());
            log("tick milestone=" + ticksOpen + ", gameMode=" + gameMode + ", "
                    + McefDiagnostics.browserState(browser));
        }

        if (ticksOpen > 80 && minecraft != null && minecraft.gameMode != null
                && minecraft.gameMode.getPlayerMode() != GameType.SPECTATOR) {
            log("auto-closing after spectator guard failed: gameMode=" + minecraft.gameMode.getPlayerMode());
            minecraft.setScreen(null);
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        if (browser != null && browser.isTextureReady()) {
            int texWidth = browser.getRenderer().getTextureWidth();
            int texHeight = browser.getRenderer().getTextureHeight();
            if (texWidth > 1 && texHeight > 1) {
                Identifier texture = browser.getTextureIdentifier();
                if (texture != null) {
                    if (!firstBlitLogged) {
                        firstBlitLogged = true;
                        log("first browser blit: texture=" + texture + ", target=" + width + "x" + height
                                + ", " + McefDiagnostics.browserState(browser));
                    }
                    graphics.blit(RenderPipelines.GUI_TEXTURED, texture, 0, 0, 0.0f, 0.0f, width, height, width, height);
                    drawExitButton(graphics, mouseX, mouseY);
                    return;
                }
            }
        }

        graphics.fill(0, 0, width, height, 0xF012100F);
        graphics.centeredText(font, "ECLIPSE ROLEPLAY", width / 2, height / 2 - 38, 0xFFE3C099);
        graphics.centeredText(font, fallbackStatus, width / 2, height / 2 - 12, 0xFFB0A8A0);
        graphics.centeredText(font, "Ожидание окна авторизации...", width / 2, height / 2 + 12, 0xFFA5C3C4);
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
        log("mouseClicked: x=" + event.x() + ", y=" + event.y() + ", button=" + event.button() + ", doubleClick=" + doubleClick 
                + ", scaledX=" + scaleMouseX(event.x()) + ", scaledY=" + scaleMouseY(event.y()) 
                + ", guiScale=" + getGuiScale() 
                + ", width=" + width + ", height=" + height 
                + ", isExitHovered=" + isExitButtonHovered(event.x(), event.y()));

        if (isExitButtonHovered(event.x(), event.y())) {
            leaveToMainMenu();
            return true;
        }
        if (browser != null) {
            log("sending mousePress to CEF: scaledX=" + scaleMouseX(event.x()) + ", scaledY=" + scaleMouseY(event.y()) + ", button=" + event.button());
            browser.sendMousePress(scaleMouseX(event.x()), scaleMouseY(event.y()), event.button());
            browser.setFocus(true);
        }
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        log("mouseReleased: x=" + event.x() + ", y=" + event.y() + ", button=" + event.button()
                + ", scaledX=" + scaleMouseX(event.x()) + ", scaledY=" + scaleMouseY(event.y()));
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
        log("onClose invoked");
        closeBrowser("onClose");
        super.onClose();
    }

    @Override
    public void removed() {
        log("removed from Minecraft; currentScreen="
                + (minecraft == null || minecraft.screen == null ? "none" : minecraft.screen.getClass().getSimpleName()));
        closeBrowser("removed");
        super.removed();
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
        int browserWidth = Math.max(1, width * scale);
        int browserHeight = Math.max(1, height * scale);
        browser.resize(browserWidth, browserHeight);
        if (browserWidth != lastBrowserWidth || browserHeight != lastBrowserHeight) {
            lastBrowserWidth = browserWidth;
            lastBrowserHeight = browserHeight;
            log("browser resize requested: screen=" + width + "x" + height + ", scale=" + scale
                    + ", browser=" + browserWidth + "x" + browserHeight);
        }
    }

    private void closeBrowser(String reason) {
        if (browser != null) {
            if (!closeLogged) {
                closeLogged = true;
                log("closing browser: reason=" + reason + ", " + McefDiagnostics.browserState(browser));
            }
            browser.close();
            browser = null;
            log("browser close returned: reason=" + reason);
        }

        if (!firstFallbackLogged) {
            firstFallbackLogged = true;
            log("rendering fallback because browser texture is unavailable: " + McefDiagnostics.browserState(browser));
        }
    }

    private void drawExitButton(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        updateExitButtonBounds();
        boolean hovered = isExitButtonHovered(mouseX, mouseY);
        graphics.fill(exitX, exitY, exitX + exitWidth, exitY + exitHeight, hovered ? 0xE06F4B43 : 0xB01B1714);
        graphics.fill(exitX, exitY, exitX + exitWidth, exitY + 1, hovered ? 0xFFE3C099 : 0x99E3C099);
        graphics.centeredText(font, "\u0412\u044b\u0439\u0442\u0438", exitX + exitWidth / 2, exitY + 8, hovered ? 0xFFFFF6E8 : 0xFFE3C099);
    }

    private void updateExitButtonBounds() {
        int padding = 30;
        exitX = width - exitWidth - padding;
        exitY = height - exitHeight - padding;
    }

    private boolean isExitButtonHovered(double mouseX, double mouseY) {
        return mouseX >= exitX && mouseX <= exitX + exitWidth && mouseY >= exitY && mouseY <= exitY + exitHeight;
    }

    private void leaveToMainMenu() {
        if (browser != null) {
            log("exit button selected; disconnecting to title screen");
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
            fallbackStatus = "Не удалось открыть браузер автоматически. Используйте ссылку авторизации в чате.";
            EclipseClientMod.LOGGER.warn("Failed to open auth URL in external browser.", e);
        }
    }

    private void log(String message) {
        long elapsedMs = (System.nanoTime() - createdNanos) / 1_000_000L;
        EclipseClientMod.LOGGER.info("[AUTH][" + traceId + "][+" + elapsedMs + "ms] " + message);
    }
}
