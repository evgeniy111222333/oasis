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
import ua.rp.chat.client.McefDiagnostics;
import ua.rp.chat.client.mcef.McefGlStateGuard;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Fullscreen MCEF overlay for the microvoxel radial menu.
 *
 * <p>Performance model: the Chromium browser is created <b>once</b> and reused across opens
 * (cold start of CEF was the biggest stall), the page is extracted to disk only when its size
 * changes, the material palette is serialised once per open, and the wheel pauses its CSS
 * animations while closed. Every open/close is timed and FPS-sampled to the client log under
 * {@code [RADIAL-PERF]} so regressions are visible.</p>
 *
 * <p>Reads the player's choice back on the CEF console channel: the page logs
 * {@code @eclipse/radial:{"action":...,"material":...}}.</p>
 */
public final class MicrovoxelRadialScreen extends Screen {
    /** Callback the opener supplies: action id, material id (nullable), fragment flag, shape id. */
    @FunctionalInterface
    public interface Listener {
        void onSelect(String action, String material, boolean fragment, int shapeId,
                      int length, int width, int height, int brushShape, int brushRadius);
    }

    private static final String RESOURCE = "/assets/eclipseclient/web/radial/radial.html";
    private static final String BRIDGE_PREFIX = "@eclipse/radial:";
    private static final int MAX_PALETTE_ATTEMPTS = 60;

    /** One long-lived transparent browser shared by every wheel open. */
    private static MCEFBrowser cachedBrowser;
    private static CefDisplayHandlerAdapter cachedHandler;
    private static boolean bridgeInstalled;

    private final Listener listener;
    private final Screen returnScreen;
    private MCEFBrowser browser;
    private CefDisplayHandlerAdapter displayHandler;
    private boolean paletteInjected;
    private volatile boolean paletteReady;
    private int paletteAttempts;
    private String paletteJson;
    private int ticks;

    private long openStartedNanos;
    private boolean textureLogged;
    private int fpsSamples;
    private int fpsMin = Integer.MAX_VALUE;
    private long fpsSum;

    public MicrovoxelRadialScreen(Listener listener) {
        this(listener, null);
    }

    /** {@code returnScreen} is restored on close so the radial can be opened over the Carver UI. */
    public MicrovoxelRadialScreen(Listener listener, Screen returnScreen) {
        super(Component.literal("Eclipse: микровоксели"));
        this.listener = listener;
        this.returnScreen = returnScreen;
    }

    @Override
    protected void init() {
        super.init();
        openStartedNanos = System.nanoTime();
        textureLogged = false;
        fpsSamples = 0;
        fpsMin = Integer.MAX_VALUE;
        fpsSum = 0L;
        paletteInjected = false;
        paletteReady = false;
        paletteAttempts = 0;
        paletteJson = null;
        ticks = 0;
        try {
            if (!MCEF.isInitialized()) MCEF.initialize();
            File page = extractPage();
            // "#open" auto-opens the wheel the moment the overlay loads (no extra key press).
            String url = "file:///" + page.getAbsolutePath().replace("\\", "/") + "#open&ingame";
            long started = System.nanoTime();
            if (cachedBrowser == null) {
                cachedBrowser = MCEF.createBrowser(url, true);
                log("browser created in " + ms(started) + ", " + McefDiagnostics.browserState(cachedBrowser));
            } else {
                try {
                    cachedBrowser.executeJavaScript(
                            "window.EclipseRadial && window.EclipseRadial.open();", "", 0);
                } catch (Throwable ignored) {
                    // the page still shows; it is just already open or reloading
                }
                log("browser reused (cold-start avoided)");
            }
            browser = cachedBrowser;
            if (browser != null) {
                McefGlStateGuard.onBrowserActive();
                browser.setFocus(true);
                int scale = guiScale();
                browser.resize(Math.max(1, width * scale), Math.max(1, height * scale));
            }
            installBridge();
            log("open ready in " + ms(openStartedNanos) + " (" + width + "x" + height + ")");
        } catch (Throwable error) {
            browser = null;
            EclipseClientMod.LOGGER.warn("Microvoxel radial overlay failed to open", error);
            if (minecraft != null) minecraft.setScreen(null);
        }
    }

    /**
     * Extracts the page only when the shipped resource changed size: writing ~50 KB to disk on
     * every open was pure waste.
     */
    private File extractPage() throws IOException {
        File target = new File(minecraft.gameDirectory, "web/radial/radial.html");
        File parent = target.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        try (InputStream in = MicrovoxelRadialScreen.class.getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IOException("Missing resource " + RESOURCE);
            byte[] resource = in.readAllBytes();
            if (target.isFile() && target.length() == resource.length) {
                return target;
            }
            try (OutputStream out = new FileOutputStream(target)) {
                out.write(resource);
            }
        }
        return target;
    }

    private void installBridge() {
        if (bridgeInstalled) {
            displayHandler = cachedHandler;
            return;
        }
        MCEFClient client = MCEF.getClient();
        if (client == null) return;
        displayHandler = new CefDisplayHandlerAdapter() {
            @Override
            public boolean onConsoleMessage(CefBrowser source, CefSettings.LogSeverity level,
                                            String message, String scriptUrl, int line) {
                if (message != null && message.startsWith(BRIDGE_PREFIX)) {
                    handleBridge(message.substring(BRIDGE_PREFIX.length()));
                    return true;
                }
                return false;
            }
        };
        client.addDisplayHandler(displayHandler);
        cachedHandler = displayHandler;
        bridgeInstalled = true;
    }

    /** Routes the palette handshake separately from a real selection; both ride the same channel. */
    private void handleBridge(String json) {
        if (json.contains("\"bridge\":\"palette-ready\"")) {
            paletteReady = true;
            log("palette ack at " + ms(openStartedNanos));
            return;
        }
        handleSelection(json);
    }

    private void handleSelection(String json) {
        String action = jsonValue(json, "action");
        String material = jsonValue(json, "material");
        boolean fragment = !"false".equalsIgnoreCase(jsonValue(json, "fragment"));
        int shapeId = jsonInt(json, "shapeId", -1);
        int length = jsonInt(json, "length", 5);
        int width = jsonInt(json, "width", 3);
        int height = jsonInt(json, "height", 1);
        int brushShape = jsonInt(json, "brushShape", -1);
        int brushRadius = jsonInt(json, "brushRadius", 0);
        Minecraft minecraft = this.minecraft;
        boolean keepOpen = action != null && action.startsWith("history.");
        if (minecraft != null) minecraft.execute(() -> {
            if (listener != null) listener.onSelect(action, material, fragment, shapeId,
                    length, width, height, brushShape, brushRadius);
            // History keeps the wheel open so several undo/redo presses are one gesture.
            if (!keepOpen && minecraft.screen == this) minecraft.setScreen(returnScreen);
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
            if (!textureLogged) {
                textureLogged = true;
                log("texture ready at " + ms(openStartedNanos));
            }
            if (paletteReady || paletteAttempts >= MAX_PALETTE_ATTEMPTS) {
                // The page acknowledged the palette (possibly empty) — stop re-injecting.
                paletteInjected = true;
                log("palette " + (paletteReady ? "acked" : "gave up")
                        + " after " + paletteAttempts + " attempts at " + ms(openStartedNanos));
            } else {
                if (paletteJson == null) paletteJson = MicrovoxelMaterialPaletteClient.toJson();
                paletteAttempts++;
                try {
                    browser.executeJavaScript(
                            "window.EclipseRadial && window.EclipseRadial.setMaterials("
                                    + paletteJson + ");", "", 0);
                } catch (Throwable ignored) {
                    // retry on the next tick; the wheel still opens
                }
            }
        }
        int fps = minecraft == null ? 0 : minecraft.getFps();
        if (fps > 0) {
            fpsSamples++;
            fpsSum += fps;
            if (fps < fpsMin) fpsMin = fps;
        }
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        // Transparent: the wheel floats over the world.
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        McefGlStateGuard.restore();
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
        logClose("onClose");
        // Keep the browser alive for the next open (cold-start avoidance); the page pauses its
        // own animations while the wheel is closed.
        if (minecraft != null) minecraft.setScreen(returnScreen);
    }

    @Override
    public void removed() {
        logClose("removed");
        super.removed();
    }

    /** Emits one perf line per close so the real open/interaction cost is visible in the log. */
    private void logClose(String reason) {
        int avg = fpsSamples == 0 ? 0 : (int) (fpsSum / fpsSamples);
        int min = fpsMin == Integer.MAX_VALUE ? 0 : fpsMin;
        log("close/" + reason + " openMs=" + ms(openStartedNanos)
                + " fpsAvg=" + avg + " fpsMin=" + min + " samples=" + fpsSamples
                + " paletteAttempts=" + paletteAttempts);
    }

    private long ms(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }

    private void log(String message) {
        EclipseClientMod.LOGGER.info("[RADIAL-PERF] " + message);
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
