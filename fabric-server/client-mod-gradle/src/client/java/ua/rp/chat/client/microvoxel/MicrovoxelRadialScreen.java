package ua.rp.chat.client.microvoxel;

import com.cinemamod.mcef.MCEF;
import com.cinemamod.mcef.MCEFBrowser;
import com.cinemamod.mcef.MCEFClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.cef.CefSettings;
import org.cef.browser.CefBrowser;
import org.cef.handler.CefDisplayHandlerAdapter;
import ua.rp.chat.client.EclipseClientMod;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Fullscreen MCEF overlay for the microvoxel radial menu.
 *
 * <p>Opens the embedded {@code web/radial/radial.html} page (extracted from the mod resources,
 * exactly like the auth/body pages), injects the client-computed material palette through
 * {@code EclipseRadial.setMaterials(...)} and reads the player's choice back on the CEF console
 * channel: the page logs {@code @eclipse/radial:{"action":...,"material":...}}. The chosen action
 * is handed to {@link #listener} which drives the existing edit actions.</p>
 */
public final class MicrovoxelRadialScreen extends Screen {
    /** Callback the opener supplies: action id, material id (nullable), fragment flag, shape id. */
    @FunctionalInterface
    public interface Listener {
        void onSelect(String action, String material, boolean fragment, int shapeId);
    }

    private static final String RESOURCE = "/assets/eclipseclient/web/radial/radial.html";
    private static final String BRIDGE_PREFIX = "@eclipse/radial:";

    private final Listener listener;
    private MCEFBrowser browser;
    private CefDisplayHandlerAdapter displayHandler;
    private boolean paletteInjected;
    private int ticks;

    public MicrovoxelRadialScreen(Listener listener) {
        super(Component.literal("Eclipse: микровоксели"));
        this.listener = listener;
    }

    @Override
    protected void init() {
        super.init();
        try {
            if (!MCEF.isInitialized()) MCEF.initialize();
            File page = extractPage();
            String url = "file:///" + page.getAbsolutePath().replace("\\", "/") + "#ingame";
            browser = MCEF.createBrowser(url, true);
            if (browser != null) {
                browser.setFocus(true);
                int scale = guiScale();
                browser.resize(Math.max(1, width * scale), Math.max(1, height * scale));
            }
            installBridge();
        } catch (Throwable error) {
            browser = null;
            EclipseClientMod.LOGGER.warn("Microvoxel radial overlay failed to open", error);
            if (minecraft != null) minecraft.setScreen(null);
        }
    }

    private File extractPage() throws IOException {
        File target = new File(minecraft.gameDirectory, "web/radial/radial.html");
        File parent = target.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        try (InputStream in = MicrovoxelRadialScreen.class.getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IOException("Missing resource " + RESOURCE);
            try (OutputStream out = new FileOutputStream(target)) {
                in.transferTo(out);
            }
        }
        return target;
    }

    private void installBridge() {
        MCEFClient client = MCEF.getClient();
        if (client == null) return;
        displayHandler = new CefDisplayHandlerAdapter() {
            @Override
            public boolean onConsoleMessage(CefBrowser source, CefSettings.LogSeverity level,
                                            String message, String scriptUrl, int line) {
                if (message != null && message.startsWith(BRIDGE_PREFIX)) {
                    handleSelection(message.substring(BRIDGE_PREFIX.length()));
                    return true;
                }
                return false;
            }
        };
        client.addDisplayHandler(displayHandler);
    }

    private void handleSelection(String json) {
        String action = jsonValue(json, "action");
        String material = jsonValue(json, "material");
        boolean fragment = !"false".equalsIgnoreCase(jsonValue(json, "fragment"));
        int shapeId = jsonInt(json, "shapeId", -1);
        Minecraft minecraft = this.minecraft;
        if (minecraft != null) minecraft.execute(() -> {
            if (listener != null) listener.onSelect(action, material, fragment, shapeId);
            if (minecraft.screen == this) minecraft.setScreen(null);
        });
    }

    private static int jsonInt(String json, String key, int fallback) {
        String value = jsonValue(json, key);
        if (value == null || value.isBlank()) return fallback;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException error) {
            return fallback;
        }
    }

    private static String jsonValue(String json, String key) {
        String needle = "\"" + key + "\":";
        int at = json.indexOf(needle);
        if (at < 0) return null;
        int from = at + needle.length();
        if (from >= json.length()) return null;
        char first = json.charAt(from);
        if (first == 'n') return null; // null
        if (first == '"') {
            int end = json.indexOf('"', from + 1);
            return end < 0 ? null : json.substring(from + 1, end);
        }
        int end = from;
        while (end < json.length() && ",}".indexOf(json.charAt(end)) < 0) end++;
        return json.substring(from, end).trim();
    }

    @Override
    public void tick() {
        super.tick();
        ticks++;
        if (browser != null && !paletteInjected && browser.isTextureReady() && ticks >= 2) {
            paletteInjected = true;
            try {
                browser.executeJavaScript(
                        "window.EclipseRadial && window.EclipseRadial.setMaterials("
                                + MicrovoxelMaterialPaletteClient.toJson() + ");", "", 0);
            } catch (Throwable ignored) {
                // palette stays empty; the wheel still opens
            }
        }
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        // Transparent: the wheel floats over the world.
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        if (browser != null && browser.isTextureReady()) {
            Identifier texture = browser.getTextureIdentifier();
            graphics.blit(RenderPipelines.GUI_TEXTURED, texture, 0, 0, 0.0f, 0.0f,
                    width, height, width, height);
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (browser != null) {
            browser.sendMousePress(browserX(event.x()), browserY(event.y()), event.button());
            browser.setFocus(true);
        }
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (browser != null) {
            browser.sendMouseRelease(browserX(event.x()), browserY(event.y()), event.button());
            browser.setFocus(true);
        }
        return true;
    }

    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        if (browser != null) browser.sendMouseMove(browserX(mouseX), browserY(mouseY));
        super.mouseMoved(mouseX, mouseY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (browser != null) {
            browser.sendMouseWheel(browserX(mouseX), browserY(mouseY), verticalAmount, 0);
            browser.setFocus(true);
        }
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == 256) { // Escape
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
        if (browser != null) {
            int scale = guiScale();
            browser.resize(Math.max(1, width * scale), Math.max(1, height * scale));
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        closeBrowser();
        if (minecraft != null) minecraft.setScreen(null);
    }

    @Override
    public void removed() {
        closeBrowser();
        super.removed();
    }

    private void closeBrowser() {
        if (displayHandler != null) {
            MCEFClient client = MCEF.getClient();
            if (client != null) client.removeDisplayHandler(displayHandler);
            displayHandler = null;
        }
        if (browser != null) {
            try {
                browser.close();
            } catch (Throwable ignored) {
                // best effort
            }
            browser = null;
        }
    }

    private int guiScale() {
        return minecraft == null ? 1 : Math.max(1, minecraft.getWindow().getGuiScale());
    }

    private int browserX(double screenX) {
        return (int) (screenX * guiScale());
    }

    private int browserY(double screenY) {
        return (int) (screenY * guiScale());
    }
}
