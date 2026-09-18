package ua.rp.chat.client.mcef;

import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL33C;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MCEF's offscreen renderer paints straight into Minecraft's GL context and never restores the
 * state it touches. Its {@code onPaint} binds the browser texture through
 * {@link GlStateManager#_bindTexture(int)} and leaves the unpack stride set through
 * {@link GlStateManager#_pixelStore(int, int)} (3314 = GL_UNPACK_ROW_LENGTH, 3315 = SKIP_ROWS,
 * 3316 = SKIP_PIXELS), which in this Minecraft version is an uncached passthrough to
 * {@code glPixelStorei}. When the overlay closes, {@code MCEFRenderer.cleanup()} deletes the
 * browser {@code GpuTexture} as well.
 *
 * <p>Leftover unpack stride corrupts the next texture Minecraft uploads (the GUI font atlas is
 * uploaded lazily for new glyphs) and a stale/foreign texture binding makes the GUI text shader
 * sample the wrong atlas, so every glyph collapses into a solid rectangle. This guard re-asserts
 * the neutral GUI baseline right after MCEF has painted and before the frame's GUI pass is built,
 * so the embedded browser can never poison Minecraft's texture/upload state.</p>
 *
 * <p>Kept deliberately dependency-free (only {@code GlStateManager}, {@code RenderSystem} and
 * LWJGL) so it can be called from mixins and from every MCEF-backed screen.</p>
 */
public final class McefGlStateGuard {
    private static final Logger LOGGER = LoggerFactory.getLogger("EclipseMcefGlState");

    private static final int GL_UNPACK_ROW_LENGTH = 3314;
    private static final int GL_UNPACK_SKIP_ROWS = 3315;
    private static final int GL_UNPACK_SKIP_PIXELS = 3316;
    private static final int GL_UNPACK_ALIGNMENT = 3317;
    private static final int GL_TEXTURE0 = 33984;
    /** The GUI/entity pipelines only ever sample from the first few texture units. */
    private static final int GUARDED_TEXTURE_UNITS = 4;

    private static volatile boolean browserActive;
    private static volatile boolean leakDetected;
    /** The GL state probe is a blocking GPU sync, so it runs exactly once per session. */
    private static boolean probed;
    private static long restores;
    private static boolean warned;

    private McefGlStateGuard() {
    }

    /** Marks that an MCEF browser exists (or existed) in this session; enables the guard. */
    public static void onBrowserActive() {
        browserActive = true;
    }

    /**
     * Restores the GL slice MCEF clobbers. Safe to call from any render-thread hook; it is a
     * no-op when no browser has been used yet or when called off the render thread.
     */
    public static void restore() {
        if (!browserActive || !RenderSystem.isOnRenderThread()) {
            return;
        }
        try {
            // One blocking glGetInteger pass only; the old build probed for 240 frames, which
            // stalled the render thread with a GPU sync on every one of them.
            if (!probed) {
                probed = true;
                probeForLeakedState();
            }
            for (int unit = 0; unit < GUARDED_TEXTURE_UNITS; unit++) {
                GL33C.glBindSampler(unit, 0);
            }
            for (int unit = 0; unit < GUARDED_TEXTURE_UNITS; unit++) {
                GlStateManager._activeTexture(GL_TEXTURE0 + unit);
                GlStateManager._bindTexture(0);
            }
            GlStateManager._activeTexture(GL_TEXTURE0);
            GlStateManager._pixelStore(GL_UNPACK_ROW_LENGTH, 0);
            GlStateManager._pixelStore(GL_UNPACK_SKIP_ROWS, 0);
            GlStateManager._pixelStore(GL_UNPACK_SKIP_PIXELS, 0);
            GlStateManager._pixelStore(GL_UNPACK_ALIGNMENT, 4);
            restores++;
        } catch (Throwable error) {
            if (!warned) {
                warned = true;
                LOGGER.warn("Unable to restore GL state after an MCEF browser frame", error);
            }
        }
    }

    /** How many frames the guard has cleaned this session; surfaced in the radial perf log. */
    public static long restoreCount() {
        return restores;
    }

    /**
     * One-shot diagnostic: if MCEF left the unpack stride dirty we log it once. This is the exact
     * state that used to bleed into the GUI; it no longer runs every frame.
     */
    private static void probeForLeakedState() {
        int rowLength = GL11.glGetInteger(GL_UNPACK_ROW_LENGTH);
        int skipRows = GL11.glGetInteger(GL_UNPACK_SKIP_ROWS);
        int skipPixels = GL11.glGetInteger(GL_UNPACK_SKIP_PIXELS);
        if (rowLength != 0 || skipRows != 0 || skipPixels != 0) {
            leakDetected = true;
            LOGGER.info("MCEF left GL unpack state dirty (rowLength={}, skipRows={}, skipPixels={}); "
                    + "restoring the GUI baseline", rowLength, skipRows, skipPixels);
        }
    }
}
