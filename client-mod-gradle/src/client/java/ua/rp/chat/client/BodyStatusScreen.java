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
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.FileNotFoundException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public class BodyStatusScreen extends Screen {
    private final String url;
    private final String traceId = McefDiagnostics.nextTraceId("body");
    private final long createdNanos = System.nanoTime();
    private MCEFBrowser browser;
    private int ticksWithoutTexture;
    private int ticksOpen;
    private boolean browserClosed;
    private String fallbackStatus = "Открываем состояние персонажа...";
    private int panelX;
    private int panelY;
    private int panelWidth;
    private int panelHeight;
    private boolean lastTextureReady;
    private boolean firstFallbackLogged;
    private boolean firstBlitLogged;
    private int lastBrowserWidth = -1;
    private int lastBrowserHeight = -1;

    public BodyStatusScreen(String url) {
        super(Component.literal("Eclipse: состояние персонажа"));
        this.url = url;
        log("constructed: url=" + McefDiagnostics.safeUrl(url));
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
            String targetUrl = getLocalBodyUrl(url);
            log("Opening local body URL: " + targetUrl);
            browser = MCEF.createBrowser(targetUrl, true);
            log("browser created: " + McefDiagnostics.browserState(browser));
            browserClosed = false;
            resizeBrowser();
            if (browser != null) {
                browser.setFocus(true);
            }
        } catch (Throwable t) {
            browser = null;
            fallbackStatus = "Встроенная панель состояния недоступна. Открываем в браузере.";
            EclipseClientMod.LOGGER.warn("MCEF body status failed, using external fallback.", t);
            log("init failed: " + t.getClass().getName() + ": " + String.valueOf(t.getMessage()));
            openExternalFallback();
        }
        log("init complete: " + McefDiagnostics.browserState(browser));
    }

    private String getLocalBodyUrl(String remoteUrl) {
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
                        if (key.equals("username")) {
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
            EclipseClientMod.LOGGER.error("Failed to parse remote body URL", e);
        }

        try {
            File webDir = new File(minecraft.gameDirectory, "web");
            String[] webFiles = {
                "body.html",
                "body.css",
                "body.js",
                "assets/body-ui.css",
                "assets/body-ui.js"
            };
            for (String file : webFiles) {
                File target = new File(webDir, file);
                copyResourceToFile("/assets/eclipseclient/web/" + file, target);
            }
            
            File localIndexFile = new File(webDir, "body.html");
            return "file:///" + localIndexFile.getAbsolutePath().replace("\\", "/") 
                + "?username=" + URLEncoder.encode(username, "UTF-8")
                + "&apiUrl=" + URLEncoder.encode(apiHost, "UTF-8");
        } catch (Exception e) {
            EclipseClientMod.LOGGER.error("Failed to extract local body web resources, fallback to remote", e);
            return remoteUrl;
        }
    }

    private static void copyResourceToFile(String resourcePath, File targetFile) throws IOException {
        File parent = targetFile.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        try (InputStream in = BodyStatusScreen.class.getResourceAsStream(resourcePath)) {
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
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        updatePanelBounds();
        if (browser != null && browser.isTextureReady()) {
            Identifier texture = browser.getTextureIdentifier();
            if (texture != null) {
                if (!firstBlitLogged) {
                    firstBlitLogged = true;
                    log("first browser blit: texture=" + texture + ", panel=" + panelWidth + "x" + panelHeight
                            + "@" + panelX + "," + panelY + ", " + McefDiagnostics.browserState(browser));
                }
                graphics.blit(RenderPipelines.GUI_TEXTURED, texture, panelX, panelY, 0.0f, 0.0f, panelWidth, panelHeight, panelWidth, panelHeight);
                return;
            }
        }
        if (browser == null) {
            if (!firstFallbackLogged) {
                firstFallbackLogged = true;
                log("rendering fallback because browser is null");
            }
            graphics.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight, 0xF012100F);
            graphics.centeredText(font, "ECLIPSE: СТАТУС", width / 2, height / 2 - 12, 0xFFE3C099);
            graphics.centeredText(font, fallbackStatus, width / 2, height / 2 + 10, 0xFFA5C3C4);
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
        if (browser != null && !textureReady) {
            ticksWithoutTexture++;
            if (ticksWithoutTexture == 100) {
                EclipseClientMod.LOGGER.warn("[BODY][" + traceId + "] MCEF browser has not produced a texture after 5 seconds: "
                        + McefDiagnostics.browserState(browser));
            }
        } else {
            ticksWithoutTexture = 0;
        }
        if (ticksWithoutTexture == 1 || ticksWithoutTexture == 5 || ticksWithoutTexture == 20
                || ticksWithoutTexture == 40 || ticksWithoutTexture == 80) {
            log("waiting for texture, tick=" + ticksWithoutTexture + ": " + McefDiagnostics.browserState(browser));
        }
        if (ticksOpen == 1 || ticksOpen == 5 || ticksOpen == 20 || ticksOpen == 40
                || ticksOpen == 80 || ticksOpen == 100 || ticksOpen == 200
                || ticksOpen == 400 || ticksOpen == 800) {
            log("screen heartbeat tick=" + ticksOpen + ": " + McefDiagnostics.browserState(browser));
        }
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
        log("onClose invoked");
        closeBrowser("onClose");
        if (minecraft != null) {
            minecraft.setScreen(null);
        }
    }

    @Override
    public void removed() {
        log("removed from Minecraft; currentScreen="
                + (minecraft == null || minecraft.screen == null ? "none" : minecraft.screen.getClass().getSimpleName()));
        closeBrowser("removed");
        super.removed();
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
        int browserWidth = Math.max(1, panelWidth * scale);
        int browserHeight = Math.max(1, panelHeight * scale);
        browser.resize(browserWidth, browserHeight);
        if (browserWidth != lastBrowserWidth || browserHeight != lastBrowserHeight) {
            lastBrowserWidth = browserWidth;
            lastBrowserHeight = browserHeight;
            log("browser resize requested: screen=" + width + "x" + height + ", panel="
                    + panelWidth + "x" + panelHeight + "@" + panelX + "," + panelY
                    + ", scale=" + scale + ", browser=" + browserWidth + "x" + browserHeight);
        }
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

    private void closeBrowser(String reason) {
        if (browser == null || browserClosed) {
            log("close skipped: reason=" + reason + ", browser=" + (browser == null ? "null" : "already-closed"));
            return;
        }
        browserClosed = true;
        log("closing browser: reason=" + reason + ", " + McefDiagnostics.browserState(browser));
        browser.setFocus(false);
        browser.close();
        browser = null;
        log("browser close returned: reason=" + reason);
    }

    private void openExternalFallback() {
        try {
            Util.getPlatform().openUri(new URI(url));
        } catch (Exception e) {
            fallbackStatus = "Не удалось открыть состояние персонажа.";
            EclipseClientMod.LOGGER.warn("Failed to open body status URL.", e);
        }
    }

    private void log(String message) {
        long elapsedMs = (System.nanoTime() - createdNanos) / 1_000_000L;
        EclipseClientMod.LOGGER.info("[BODY][" + traceId + "][+" + elapsedMs + "ms] " + message);
    }
}
