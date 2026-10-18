package ua.rp.chat.client.carry;

import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ShapeRenderer;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.block.BlockModelRenderState;
import net.minecraft.client.renderer.block.BlockModelResolver;
import net.minecraft.client.renderer.entity.DisplayRenderer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.RenderShape;
import java.util.*;
import ua.rp.chat.carry.CarryPlacement;

/** Read-only model ghost and contour. Every placement is rechecked by the server. */
public final class CarryWorldFeedback {
    private static Preview preview;
    private static final ItemStackRenderState MODEL = new ItemStackRenderState();
    private static final BlockModelRenderState BLOCK_MODEL = new BlockModelRenderState();
    private static BlockModelResolver blockResolver;
    private static List<BakedQuad> blockQuads = List.of();
    private CarryWorldFeedback() {}
    public static void clear() { preview = null; MODEL.clear(); BLOCK_MODEL.clear(); blockQuads = List.of(); }
    public static Preview preview() { return preview; }

    public static void submitPreview(LevelRenderContext context) {
        Minecraft client = Minecraft.getInstance();
        if (!CarryClient.placing() || client.level == null || client.player == null) { preview = null; return; }
        var held = CarryClientState.local();
        var hit = CarryTargeting.blockHit(client);
        if (hit.getType() != HitResult.Type.BLOCK) { preview = null; return; }
        boolean block = !held.kind().equals("item");
        int turns = CarryClient.yaw() / 90;
        BlockPos destination = block ? hit.getBlockPos().above()
                : BlockPos.containing(hit.getLocation().add(0, .006, 0));
        double x = block ? destination.getX() + .5 : hit.getLocation().x;
        double y = block ? destination.getY() : hit.getLocation().y + .006;
        double z = block ? destination.getZ() + .5 : hit.getLocation().z;
        double half = held.large() ? .5 : .18, height = held.large() ? 1 : .36;
        AABB bounds = new AABB(x - half, y, z - half, x + half, y + height, z + half);
        boolean valid = hit.getDirection() == Direction.UP && client.level.getWorldBorder().isWithinBounds(destination)
                && client.level.getChunkSource().hasChunk(destination.getX() >> 4, destination.getZ() >> 4)
                && !bounds.intersects(client.player.getBoundingBox());
        if (block) valid &= CarryPlacement.snapshotDestination(client.level, destination, held.block(), turns)
                && client.level.noCollision(client.player, bounds.deflate(.005));
        else valid &= client.level.getFluidState(destination).isEmpty()
                && client.level.noCollision(client.player, bounds.deflate(.005));
        valid &= client.level.getEntities(client.player, bounds, entity -> entity.isAlive()
                && !(entity instanceof net.minecraft.world.entity.Display)).isEmpty();
        for (var prop : CarryClientState.props()) {
            if (prop.world().equals(client.level.dimension().identifier().toString()) && prop.bounds().intersects(bounds)) valid = false;
        }
        preview = new Preview(x, y, z, bounds, valid, block);
        blockQuads = List.of(); BLOCK_MODEL.clear();
        if (blockResolver == null) blockResolver = new BlockModelResolver(client.getModelManager());
        if (held.block() != null) blockResolver.update(BLOCK_MODEL,
                CarryPlacement.rotated(held.block(), turns), DisplayRenderer.BLOCK_DISPLAY_CONTEXT);
        if (held.block() != null && held.block().getRenderShape() == RenderShape.MODEL) {
            var parts = new ArrayList<net.minecraft.client.renderer.block.dispatch.BlockStateModelPart>();
            client.getModelManager().getBlockStateModelSet().get(CarryPlacement.rotated(held.block(), turns))
                    .collectParts(net.minecraft.util.RandomSource.create(42), parts);
            var quads = new ArrayList<BakedQuad>();
            for (var part : parts) {
                quads.addAll(part.getQuads(null));
                for (Direction side : Direction.values()) quads.addAll(part.getQuads(side));
            }
            blockQuads = List.copyOf(quads);
        }
        if (held.block() != null && blockQuads.isEmpty()) {
            PoseStack pose = context.poseStack(); pose.pushPose();
            Vec3 camera = client.gameRenderer.getMainCamera().position();
            pose.translate(x - .5 - camera.x, y - camera.y, z - .5 - camera.z);
            BLOCK_MODEL.submit(pose, context.submitNodeCollector(), LevelRenderer.getLightCoords(client.level, destination),
                    OverlayTexture.NO_OVERLAY, valid ? 0xFFB5DEB0 : 0xFFE7A39C);
            pose.popPose(); MODEL.clear(); return;
        }
        var visualItem = held.item();
        if (visualItem.isEmpty() && !held.large()) visualItem = client.player.getMainHandItem();
        if (visualItem.isEmpty() && client.level.getEntity(held.entity()) instanceof net.minecraft.world.entity.Display.ItemDisplay display)
            visualItem = ((ua.rp.chat.client.mixin.carry.CarryItemDisplayAccessor) display).eclipse$carryVisualItem();
        if (visualItem.isEmpty()) { MODEL.clear(); return; }
        client.getItemModelResolver().updateForTopItem(MODEL, visualItem, net.minecraft.world.item.ItemDisplayContext.NONE,
                client.level, client.player, 0);
        // Special item renderers (e.g. chests) don't expose baked quads. Submit their genuine
        // native model as a mock-up; the outline and HUD still mark it as a local preview.
        if (blockQuads.isEmpty() && !((CarryGhostState) MODEL).eclipse$hasCarryGhostQuads()) {
            PoseStack pose = context.poseStack(); pose.pushPose(); transform(pose, client, preview);
            MODEL.submit(pose, context.submitNodeCollector(), LevelRenderer.getLightCoords(client.level, destination),
                    OverlayTexture.NO_OVERLAY, valid ? 0xFFB5DEB0 : 0xFFE7A39C);
            pose.popPose();
        }
    }
    public static void renderLines(LevelRenderContext context) {
        Minecraft client = Minecraft.getInstance();
        if (!CarryClient.supported() || client.level == null || client.player == null
                || client.options.hideGui || client.screen != null) return;
        Vec3 camera = client.gameRenderer.getMainCamera().position();
        PoseStack pose = context.poseStack();
        var target = CarryClient.focus();
        var lines = RenderTypes.lines();
        if (target != null && target.block() != null) {
            BlockPos pos = target.block();
            var shape = client.level.getBlockState(pos).getShape(client.level, pos);
            if (!shape.isEmpty()) ShapeRenderer.renderShape(pose, context.bufferSource().getBuffer(lines), shape,
                    pos.getX() - camera.x, pos.getY() - camera.y, pos.getZ() - camera.z, 0xE6FFF0D7, 1.6f);
        }
        if (CarryClient.placing() && preview != null) {
            int color = preview.valid ? 0xD0B5DEB0 : 0xD0E7A39C;
            ShapeRenderer.renderShape(pose, context.bufferSource().getBuffer(lines), Shapes.create(preview.bounds),
                    -camera.x, -camera.y, -camera.z, color, 1.6f);
            if (!blockQuads.isEmpty() || ((CarryGhostState) MODEL).eclipse$hasCarryGhostQuads()) {
                Set<net.minecraft.client.renderer.rendertype.RenderType> used = new LinkedHashSet<>();
                pose.pushPose();
                int light = LevelRenderer.getLightCoords(client.level, BlockPos.containing(preview.x, preview.y, preview.z));
                int tint = preview.valid ? 0x80CFE9C4 : 0x80EAA49A;
                if (!blockQuads.isEmpty()) {
                    pose.translate(preview.x - .5 - camera.x, preview.y - camera.y, preview.z - .5 - camera.z);
                    for (var quad : blockQuads) CarryGhostVertices.emit(pose, quad, context.bufferSource(), used, tint, light);
                } else {
                    transform(pose, client, preview);
                    ((CarryGhostState) MODEL).eclipse$emitCarryGhost(pose, context.bufferSource(), used, tint, light);
                }
                pose.popPose();
                for (var type : used) context.bufferSource().endBatch(type);
            }
        }
        context.bufferSource().endBatch(lines);
    }
    private static void transform(PoseStack pose, Minecraft client, Preview p) {
        Vec3 camera = client.gameRenderer.getMainCamera().position();
        var held = CarryClientState.local();
        pose.translate(p.x - camera.x, p.y - camera.y + (held.large() ? .5 : .18), p.z - camera.z);
        pose.mulPose(CarryMath.previewRotation(CarryClient.yaw()));
        if (!held.large()) pose.scale(.36f, .36f, .36f);
    }
    public record Preview(double x, double y, double z, AABB bounds, boolean valid, boolean block) {}
}
