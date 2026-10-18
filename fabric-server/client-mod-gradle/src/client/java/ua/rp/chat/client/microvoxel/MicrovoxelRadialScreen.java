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
 * changes, and the material palette is serialised once per open. Because the wheel is a resident
 * transparent OSR surface, the page freezes <b>all</b> ambient animation whenever the wheel is
 * closed or the pointer goes idle, so CEF stops handing MCEF a fresh full-screen frame to upload;
 * blend-mode/mask and animated box-shadow effects were removed for the same reason. The carver's
 * chalk/dust world passes are skipped while the wheel is up. Every open/close is timed and
 * FPS/frame-time sampled to the client log under {@code [RADIAL-PERF]} (one heartbeat per second,
 * plus page idle/perf/lifecycle events) so the real cost is visible.</p>
 *
 * <p>Reads the player's choice back on the CEF console channel: the page logs
 * {@code @eclipse/radial:{"action":...,"material":...}} and the perf governor rides the same
 * channel with {@code {"bridge":"idle"|"perf"|"lifecycle",...}}.</p>
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
    /**
     * The wheel instance that currently owns the browser. The console handler is installed once
     * and dispatches here, never to the instance that happened to install it — otherwise a reused
     * browser would keep talking to a dead screen: the palette handshake would spin and a picked
     * action (e.g. «Поставить») would never close the overlay and return to the game.
     */
    private static volatile MicrovoxelRadialScreen current;

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

    /** Frame-budget probe: delta between consecutive on-screen extracts while the wheel is up. */
    private long lastExtractNanos;
    private int frameCount;
    private long frameNanosSum;
    private long frameNanosMax;
    /** Cumulative copies for the close summary (the window above resets every heartbeat). */
    private int totalFrames;
    private long totalFrameNanosSum;
    private long totalFrameNanosMax;
    private boolean firstBlitLogged;
    /** Page-side governor state, pushed over the console bridge (idle/breath/perf). */
    private volatile boolean pageIdle;
    private volatile int pageFps;
    private int idleTransitions;
    private int lifecycleEvents;
    /** Browser render resolution in physical pixels, for the log. */
    private int browserPixelWidth;
    private int browserPixelHeight;

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
        current = this;
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
        lastExtractNanos = 0L;
        frameCount = 0;
        frameNanosSum = 0L;
        frameNanosMax = 0L;
        totalFrames = 0;
        totalFrameNanosSum = 0L;
        totalFrameNanosMax = 0L;
        firstBlitLogged = false;
        pageIdle = false;
        pageFps = 0;
        idleTransitions = 0;
        lifecycleEvents = 0;
        browserPixelWidth = 0;
        browserPixelHeight = 0;
        try {
            log("open start: mcefInitialized=" + MCEF.isInitialized()
                    + " window=" + width + "x" + height + "@scale" + guiScale()
                    + " returnScreen=" + (returnScreen == null ? "none" : returnScreen.getClass().getSimpleName())
                    + " browserActive=" + McefGlStateGuard.restoreCount());
            if (!MCEF.isInitialized()) MCEF.initialize();
            File page = extractPage();
            // "#open" auto-opens the wheel the moment the overlay loads (no extra key press).
            String url = "file:///" + page.getAbsolutePath().replace("\\", "/") + "#open&ingame";
            long started = System.nanoTime();
            if (cachedBrowser == null) {
                cachedBrowser = MCEF.createBrowser(url, true);
                log("browser created in " + ms(started) + ", " + McefDiagnostics.browserState(cachedBrowser));
            } else {
                boolean alive = true;
                try {
                    cachedBrowser.executeJavaScript(
                            "window.EclipseRadial && window.EclipseRadial.open();", "", 0);
                } catch (Throwable reuseFailed) {
                    // A stale handle (CEF reinit / resource reload) must not be reused forever:
                    // drop it and fall through to a fresh browser.
                    alive = false;
                }
                if (alive) {
                    log("browser reused (cold-start avoided), " + McefDiagnostics.browserState(cachedBrowser));
                } else {
                    try {
                        cachedBrowser.close();
                    } catch (Throwable ignored) {
                        // already gone
                    }
                    cachedBrowser = MCEF.createBrowser(url, true);
                    log("browser recreated after stale handle, " + McefDiagnostics.browserState(cachedBrowser));
                }
            }
            browser = cachedBrowser;
            if (browser != null) {
                McefGlStateGuard.onBrowserActive();
                browser.setFocus(true);
                int scale = guiScale();
                browserPixelWidth = Math.max(1, width * scale);
                browserPixelHeight = Math.max(1, height * scale);
                browser.resize(browserPixelWidth, browserPixelHeight);
                log("browser resized to " + browserPixelWidth + "x" + browserPixelHeight
                        + " transparent=" + browser.getRenderer().isTransparent());
            }
            installBridge();
            log("open ready in " + ms(openStartedNanos) + " (" + width + "x" + height + ")");
            log("perf governor armed: ambient animation idles after 1500ms; carver world overlays"
                    + " (chalk/dust) gated while this screen is open");
        } catch (Throwable error) {
            browser = null;
            // Never keep a handle we failed to drive: the next open rebuilds instead of reusing
            // a dead browser and failing again.
            cachedBrowser = null;
            EclipseClientMod.LOGGER.warn("Microvoxel radial overlay failed to open", error);
            // Restore the screen the wheel was opened over (e.g. the Carver UI) rather than
            // dumping the player back into the world.
            if (minecraft != null) minecraft.setScreen(returnScreen);
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
                    // Dispatch to the live wheel, not the instance that installed the handler.
                    MicrovoxelRadialScreen active = current;
                    if (active != null) active.handleBridge(message.substring(BRIDGE_PREFIX.length()));
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
        // Page-side perf governor telemetry must never be mistaken for a menu selection.
        if (json.contains("\"bridge\":\"idle\"")) {
            handlePageIdle(json);
            return;
        }
        if (json.contains("\"bridge\":\"perf\"")) {
            handlePagePerf(json);
            return;
        }
        if (json.contains("\"bridge\":\"lifecycle\"")) {
            handlePageLifecycle(json);
            return;
        }
        handleSelection(json);
    }

    /**
     * The page reports when it stopped/restarted all ambient animation. This is the exact
     * hypothesis under test: if FPS only recovers while {@code idle=true}, the cost is CEF
     * repainting the wheel, not the game.
     */
    private void handlePageIdle(String json) {
        boolean nowIdle = !"false".equalsIgnoreCase(jsonValue(json, "idle"));
        int frames = jsonInt(json, "pageFps", 0);
        if (nowIdle != pageIdle) {
            idleTransitions++;
            pageIdle = nowIdle;
            if (nowIdle) {
                stopFrameProbe();
                pageFps = 0;
            }
            log("page idle=" + nowIdle + " at " + ms(openStartedNanos)
                    + " (pageFpsBeforeIdle=" + frames + ", transitions=" + idleTransitions + ")");
        }
    }

    private void handlePagePerf(String json) {
        pageFps = jsonInt(json, "pageFps", pageFps);
    }

    private void handlePageLifecycle(String json) {
        lifecycleEvents++;
        log("page lifecycle=" + String.valueOf(jsonValue(json, "event"))
                + " at " + ms(openStartedNanos) + " events=" + lifecycleEvents);
    }

    private void stopFrameProbe() {
        lastExtractNanos = 0L;
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
        log("selection action=" + action + " material=" + material + " fragment=" + fragment
                + " keepOpen=" + keepOpen + " at " + ms(openStartedNanos));
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
        if (ticks % 20 == 0) {
            logHeartbeat(fps);
        }
    }

    /**
     * One line per second while the wheel is open: live FPS plus the CPU frame interval and the
     * page-side governor state. Read {@code idle=true} rows against {@code idle=false} rows to see
     * exactly how much the CEF repaint costs on the current machine.
     */
    private void logHeartbeat(int fps) {
        double frameAvgMs = frameCount == 0 ? 0.0 : frameNanosSum / 1_000_000.0 / frameCount;
        double frameMaxMs = frameNanosMax / 1_000_000.0;
        int avgFps = fpsSamples == 0 ? 0 : (int) (fpsSum / fpsSamples);
        log("t=" + (ticks / 20) + "s fps=" + fps + " fpsAvg=" + avgFps
                + " fpsMin=" + (fpsMin == Integer.MAX_VALUE ? 0 : fpsMin)
                + " frameAvgMs=" + String.format(java.util.Locale.ROOT, "%.2f", frameAvgMs)
                + " frameMaxMs=" + String.format(java.util.Locale.ROOT, "%.2f", frameMaxMs)
                + " framesLastSec=" + frameCount
                + " tex=" + (browser == null || !browser.isTextureReady()
                        ? "none" : browser.getRenderer().getTextureWidth() + "x"
                        + browser.getRenderer().getTextureHeight())
                + " pageIdle=" + pageIdle + " pageFps=" + pageFps
                + " paletteInjected=" + paletteInjected + " paletteAttempts=" + paletteAttempts
                + " glRestores=" + McefGlStateGuard.restoreCount());
        // Window resets so each line is this second's budget, not a session average.
        frameCount = 0;
        frameNanosSum = 0L;
        frameNanosMax = 0L;
        lastExtractNanos = 0L;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        // Transparent: the wheel floats over the world.
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        long now = System.nanoTime();
        if (lastExtractNanos != 0L) {
            long deltaNanos = now - lastExtractNanos;
            frameCount++;
            frameNanosSum += deltaNanos;
            if (deltaNanos > frameNanosMax) frameNanosMax = deltaNanos;
            totalFrames++;
            totalFrameNanosSum += deltaNanos;
            if (deltaNanos > totalFrameNanosMax) totalFrameNanosMax = deltaNanos;
        }
        lastExtractNanos = now;
        McefGlStateGuard.restore();
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        if (browser != null && browser.isTextureReady()) {
            Identifier texture = browser.getTextureIdentifier();
            if (!firstBlitLogged) {
                firstBlitLogged = true;
                log("first browser blit at " + ms(openStartedNanos)
                        + ", " + McefDiagnostics.browserState(browser));
            }
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
            browserPixelWidth = Math.max(1, width * scale);
            browserPixelHeight = Math.max(1, height * scale);
            browser.resize(browserPixelWidth, browserPixelHeight);
            log("browser resize re-issued: " + width + "x" + height + " -> "
                    + browserPixelWidth + "x" + browserPixelHeight);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** True while a wheel instance owns the shared browser; used to gate world-space passes. */
    public static boolean isOpen() {
        return current != null;
    }

    @Override
    public void onClose() {
        logClose("onClose");
        // Keep the browser alive for the next open (cold-start avoidance), but explicitly tell
        // the page to close: otherwise it would keep the wheel open-and-animating behind the
        // restored screen, and CEF would keep repainting the whole surface every frame.
        pausePage();
        if (minecraft != null) minecraft.setScreen(returnScreen);
    }

    @Override
    public void removed() {
        pausePage();
        logClose("removed");
        if (current == this) current = null;
        super.removed();
    }

    /** Freezes the page and drops browser focus; the wheel is invisible from now on. */
    private void pausePage() {
        if (browser == null) {
            return;
        }
        try {
            browser.executeJavaScript(
                    "window.EclipseRadial && window.EclipseRadial.close();", "", 0);
            browser.setFocus(false);
        } catch (Throwable ignored) {
            // a closed browser is fine; the next open re-creates it
        }
    }

    /** Emits one perf line per close so the real open/interaction cost is visible in the log. */
    private void logClose(String reason) {
        int avg = fpsSamples == 0 ? 0 : (int) (fpsSum / fpsSamples);
        int min = fpsMin == Integer.MAX_VALUE ? 0 : fpsMin;
        double frameAvgMs = totalFrames == 0 ? 0.0
                : totalFrameNanosSum / 1_000_000.0 / totalFrames;
        double frameMaxMs = totalFrameNanosMax / 1_000_000.0;
        log("close/" + reason + " openMs=" + ms(openStartedNanos)
                + " fpsAvg=" + avg + " fpsMin=" + min + " samples=" + fpsSamples
                + " frameAvgMs=" + String.format(java.util.Locale.ROOT, "%.2f", frameAvgMs)
                + " frameMaxMs=" + String.format(java.util.Locale.ROOT, "%.2f", frameMaxMs)
                + " frames=" + totalFrames
                + " browserPx=" + browserPixelWidth + "x" + browserPixelHeight
                + " idleTransitions=" + idleTransitions + " lifecycleEvents=" + lifecycleEvents
                + " paletteAttempts=" + paletteAttempts + " glRestores=" + McefGlStateGuard.restoreCount()
                + " | overlay released; carver chalk/dust resume");
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
