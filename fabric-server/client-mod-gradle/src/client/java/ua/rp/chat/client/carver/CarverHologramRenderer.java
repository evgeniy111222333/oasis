package ua.rp.chat.client.carver;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.ARGB;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import ua.rp.chat.microvoxel.MicrovoxelGreedyMesher;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws the lifted hologram copy directly: exact socket-corner geometry, the real
 * baked model quads of the material, biome tint resolved explicitly at the socket.
 *
 * <p>Two paths share one emission core. A fresh socket renders the full material
 * cube; a re-entered carving renders its live microvoxel mesh (the same greedy
 * faces the terrain section bakes), so the copy shows every previous cut instead
 * of pretending the block is whole. The mesh path falls back to the full cube
 * whenever the volume has not synced yet; the next frame picks the carving up
 * automatically because the volume is read live.</p>
 */
public final class CarverHologramRenderer {
    private CarverHologramRenderer() {
    }

    /**
     * Display mesh cache: unculled faces of the source volume minus fully drafted
     * cells, so painted strokes vanish live while the mouse is still down. Rebuilt
     * only when the volume revision or the draft fingerprint moves.
     */
    private record DisplayMesh(BlockPos focus, String sourceKey, long draftFp,
                               List<MicrovoxelGreedyMesher.Face> faces,
                               List<String> palette) {
    }

    private static volatile DisplayMesh displayCache;
    private static volatile String virtualKey = "";
    private static volatile ua.rp.chat.microvoxel.MicrovoxelVolume virtualVolume;

    private static long framesLogged;
    private static long facesLogged;

    /** Resets the one-shot render diagnostics for the next session. */
    static void resetDiag() {
        framesLogged = 0L;
        facesLogged = 0L;
    }

    /** World-space END_MAIN hook: emits the lifted copy, nothing when parked. */
    public static void render(net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext context) {
        if (!CarverHologram.active()) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.level == null) return;
        BlockPos focus = CarverHologram.focus();
        BlockState state = CarverHologram.displayState();
        if (focus == null || state == null) return;
        if (framesLogged < 3L) {
            framesLogged++;
            try {
                var pose = context.poseStack().last().pose();
                Vec3 camera = minecraft.gameRenderer.getMainCamera().position();
                ua.rp.chat.client.EclipseClientMod.LOGGER.info(
                        "[CARVER-HOLO] frame focus=" + focus.toShortString()
                                + " key=" + CarverHologram.materialKey()
                                + " lift=" + CarverHologram.visualLift()
                                + " cam=" + camera
                                + " poseT=" + pose.m30() + "," + pose.m31() + "," + pose.m32());
            } catch (RuntimeException ignored) {
            }
        }
        DisplayMesh display;
        try {
            display = displayMesh(focus, state);
        } catch (RuntimeException unreadable) {
            if (facesLogged < 1L) {
                facesLogged++;
                ua.rp.chat.client.EclipseClientMod.LOGGER.info(
                        "[CARVER-HOLO] displayMesh threw " + unreadable);
            }
            return;
        }
        if (display.faces().isEmpty()) {
            if (facesLogged < 3L) {
                facesLogged++;
                ua.rp.chat.client.EclipseClientMod.LOGGER.info(
                        "[CARVER-HOLO] empty faces for " + focus.toShortString());
            }
            return;
        }
        if (facesLogged < 1L) {
            facesLogged++;
            var first = display.faces().get(0);
            ua.rp.chat.client.EclipseClientMod.LOGGER.info(
                    "[CARVER-HOLO] faces=" + display.faces().size()
                            + " first=" + first.direction() + " mat=" + first.material()
                            + " box=" + first.minX() + "," + first.minY() + "," + first.minZ()
                            + "-" + first.maxX() + "," + first.maxY() + "," + first.maxZ());
        }
        Vec3 camera = minecraft.gameRenderer.getMainCamera().position();
        double baseX = CarverHologram.originX() - camera.x;
        double baseY = CarverHologram.originY() - camera.y;
        double baseZ = CarverHologram.originZ() - camera.z;
        int light = LevelRenderer.getLightCoords(minecraft.level, focus);
        PoseStack.Pose pose = context.poseStack().last();
        VertexConsumer consumer = context.bufferSource().getBuffer(Sheets.cutoutBlockSheet());
        List<String> palette = display.palette();
        for (MicrovoxelGreedyMesher.Face face : display.faces()) {
            int materialIndex = face.material();
            if (materialIndex <= 0 || materialIndex >= palette.size()) continue;
            String materialName = palette.get(materialIndex);
            var materialFaces = materialFaces(minecraft, materialName);
            if (materialFaces.faces().isEmpty()) continue;
            Direction direction;
            try {
                direction = Direction.valueOf(face.direction().name());
            } catch (RuntimeException unknown) {
                continue;
            }
            List<BakedQuad> quads = materialFaces.faces().get(direction);
            if (quads == null || quads.isEmpty()) continue;
            BlockState materialState = materialFaces.state();
            for (BakedQuad quad : quads) {
                var patch = ua.rp.chat.client.microvoxel.MicrovoxelSectionModel.UvPatch.from(quad);
                int color = tinted(minecraft, materialState, quad, focus);
                color = shade(color, direction);
                float[][] corners = faceCorners(face);
                for (float[] corner : corners) {
                    var sample = patch.sample(face.direction(), corner[0], corner[1], corner[2]);
                    consumer.addVertex(pose,
                                    (float) (baseX + corner[0]),
                                    (float) (baseY + corner[1]),
                                    (float) (baseZ + corner[2]))
                            .setColor(color)
                            .setUv(sample.u(), sample.v())
                            .setOverlay(OverlayTexture.NO_OVERLAY)
                            .setLight(light)
                            .setNormal(pose, direction.getStepX(), direction.getStepY(),
                                    direction.getStepZ());
                }
            }
        }
        context.bufferSource().endBatch(Sheets.cutoutBlockSheet());
    }

    /**
     * Source volume for the copy: the live carving when one synced, otherwise a
     * virtual full cube of the display material. Meshed unculled, minus faces the
     * current stroke fully covers.
     */
    static DisplayMesh displayMesh(BlockPos focus, BlockState state) {
        ua.rp.chat.microvoxel.MicrovoxelVolume volume;
        String sourceKey;
        try {
            var cached = ua.rp.chat.client.microvoxel.MicrovoxelClientState.get(focus);
            if (cached != null && cached.volume != null
                    && ua.rp.chat.microvoxel.MicrovoxelVolume.dominantMaterial(
                            cached.volume) != null) {
                volume = cached.volume;
                sourceKey = "v" + volume.revision();
            } else {
                volume = virtualFull();
                sourceKey = "m" + CarverHologram.materialKey();
            }
        } catch (RuntimeException unreadable) {
            volume = virtualFull();
            sourceKey = "m" + CarverHologram.materialKey();
        }
        ua.rp.chat.carver.DraftMask draft = CarverClientState.draft();
        long draftFp = draft.isEmpty() ? 0L
                : ua.rp.chat.carver.CarverChalkQuads.draftFingerprint(draft);
        DisplayMesh current = displayCache;
        if (current != null && current.focus().equals(focus)
                && current.sourceKey().equals(sourceKey) && current.draftFp() == draftFp) {
            return current;
        }
        List<MicrovoxelGreedyMesher.Face> mesh =
                ua.rp.chat.microvoxel.MicrovoxelGreedyMesher.build(volume, (x, y, z) -> 0);
        List<MicrovoxelGreedyMesher.Face> visible = mesh;
        if (draftFp != 0L) {
            visible = new ArrayList<>(mesh.size());
            for (MicrovoxelGreedyMesher.Face face : mesh) {
                if (ua.rp.chat.carver.CarverChalkQuads.cellsCleared(
                        face.minX(), face.minY(), face.minZ(),
                        face.maxX() - 1, face.maxY() - 1, face.maxZ() - 1, draft)) {
                    continue;
                }
                visible.add(face);
            }
            visible = List.copyOf(visible);
        }
        DisplayMesh built = new DisplayMesh(focus.immutable(), sourceKey, draftFp,
                visible, volume.palette());
        displayCache = built;
        return built;
    }

    private static ua.rp.chat.microvoxel.MicrovoxelVolume virtualFull() {
        String key = CarverHologram.materialKey();
        if (!key.equals(virtualKey) || virtualVolume == null) {
            String material = key.isEmpty() ? "minecraft:stone" : key;
            int properties = material.indexOf('[');
            if (properties >= 0) material = material.substring(0, properties);
            virtualVolume = ua.rp.chat.microvoxel.MicrovoxelVolume.full(material);
            virtualKey = key;
        }
        return virtualVolume;
    }

    /** Block-space corners of one greedy mesh face, wound like the terrain emitter. */
    public static float[][] faceCorners(MicrovoxelGreedyMesher.Face face) {
        float x0 = face.minX() / 16.0f;
        float y0 = face.minY() / 16.0f;
        float z0 = face.minZ() / 16.0f;
        float x1 = face.maxX() / 16.0f;
        float y1 = face.maxY() / 16.0f;
        float z1 = face.maxZ() / 16.0f;
        return switch (face.direction()) {
            case NORTH -> new float[][]{{x1, y0, z0}, {x0, y0, z0}, {x0, y1, z0}, {x1, y1, z0}};
            case SOUTH -> new float[][]{{x0, y0, z1}, {x1, y0, z1}, {x1, y1, z1}, {x0, y1, z1}};
            case WEST -> new float[][]{{x0, y0, z0}, {x0, y0, z1}, {x0, y1, z1}, {x0, y1, z0}};
            case EAST -> new float[][]{{x1, y0, z1}, {x1, y0, z0}, {x1, y1, z0}, {x1, y1, z1}};
            case UP -> new float[][]{{x0, y1, z1}, {x1, y1, z1}, {x1, y1, z0}, {x0, y1, z0}};
            case DOWN -> new float[][]{{x0, y0, z0}, {x1, y0, z0}, {x1, y0, z1}, {x0, y0, z1}};
        };
    }

    private static ua.rp.chat.client.microvoxel.MicrovoxelSectionModel.MaterialFaces materialFaces(
            Minecraft minecraft, String materialName) {
        return ua.rp.chat.client.microvoxel.MicrovoxelSectionModel.materialFaces(materialName);
    }

    private static int tinted(Minecraft minecraft, BlockState state, BakedQuad quad, BlockPos tintPos) {
        int color = 0xFFFFFFFF;
        BakedQuad.MaterialInfo info = quad.materialInfo();
        if (info.isTinted()) {
            BlockTintSource tint = minecraft.getBlockColors()
                    .getTintSource(state, info.tintIndex());
            if (tint != null) {
                color = 0xFF000000 | (tint.colorInWorld(state, minecraft.level, tintPos) & 0xFFFFFF);
            }
        }
        return color;
    }

    /** Classic directional shade so the copy reads as a lit cube, not flat paper. */
    public static int shade(int color, Direction direction) {
        float factor = switch (direction) {
            case DOWN -> 0.5f;
            case UP -> 1.0f;
            case NORTH, SOUTH -> 0.8f;
            case WEST, EAST -> 0.6f;
        };
        return ARGB.multiply(color, shadeFactor(factor));
    }

    private static int shadeFactor(float factor) {
        int component = Math.max(0, Math.min(255, Math.round(factor * 255.0f)));
        return 0xFF000000 | (component << 16) | (component << 8) | component;
    }
}
