package ua.rp.chat.client.modeleditor;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.item.*;
import net.minecraft.world.level.Level;
import ua.rp.chat.modeleditor.*;

/** Uses the real game renderer for both preview and saved presentation. */
public final class ModelPlacementRendering {
    private static final ThreadLocal<Boolean> RESOLVING_REPLACEMENT = ThreadLocal.withInitial(() -> false);
    private ModelPlacementRendering() { }

    public static void finish(ItemModelResolver resolver, ItemStackRenderState state, ItemStack original,
                              ItemDisplayContext context, Level level, ItemOwner owner, int seed) {
        if (RESOLVING_REPLACEMENT.get() || original.isEmpty() || context == ItemDisplayContext.NONE) return;
        ModelPose pose;
        try { pose = ModelPose.valueOf(context.name()); } catch (IllegalArgumentException unused) { return; }
        boolean preview = ModelEditorClient.previewItemPose() != null
                || owner instanceof net.minecraft.world.entity.Entity entity && ModelEditorClient.previewPlayer(entity.getId()) != null;
        String id = BuiltInRegistries.ITEM.getKey(original.getItem()).toString();
        if (!preview && !ModelEditorClient.hasPlacement(id, pose)) return;
        ModelPlacement placement = preview ? ModelEditorClient.placement(id, pose) : ModelEditorClient.authoritativePlacement(id, pose);
        ItemStack model = modelStack(original, placement);
        if (model.getItem() != original.getItem()) {
            RESOLVING_REPLACEMENT.set(true);
            try { resolver.updateForTopItem(state, model, context, level, owner, seed); }
            finally { RESOLVING_REPLACEMENT.remove(); }
        }
        ((ModelPlacementRenderState) state).eclipse$applyPlacement(placement);
    }

    /** Presentation replacement never alters the real inventory stack or gameplay identity. */
    public static ItemStack modelStack(ItemStack original, ModelPlacement placement) {
        if (placement.model() == null || placement.model().isBlank()) return original;
        try {
            Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(placement.model()));
            if (item == null || item == Items.AIR || item == original.getItem()) return original;
            return new ItemStack(item);
        } catch (IllegalArgumentException invalid) { return original; }
    }

    public static org.joml.Matrix4f adjustment(ModelTransform adjustment) {
        return new org.joml.Matrix4f().translate(.5f, .5f, .5f)
                .translate(adjustment.x()/16f, adjustment.y()/16f, adjustment.z()/16f)
                .rotateXYZ((float)Math.toRadians(adjustment.rx()), (float)Math.toRadians(adjustment.ry()), (float)Math.toRadians(adjustment.rz()))
                .scale(adjustment.sx(), adjustment.sy(), adjustment.sz()).translate(-.5f, -.5f, -.5f);
    }

    public static void applyWorn(PoseStack stack, PlayerModel model, AvatarRenderState state, ModelPlacement placement) {
        switch (placement.attachment()) {
            case BACK -> model.body.translateAndRotate(stack);
            case BELT -> { model.body.translateAndRotate(stack); stack.translate(0, 6f / 16f, 0); }
            case RIGHT_HAND -> { model.translateToHand(state, HumanoidArm.RIGHT, stack); stack.translate(0, 12f / 16f, 0); }
            case LEFT_HAND -> { model.translateToHand(state, HumanoidArm.LEFT, stack); stack.translate(0, 12f / 16f, 0); }
        }
        ModelTransform transform = placement.transform();
        stack.translate(transform.x() / 16f, transform.y() / 16f, transform.z() / 16f);
        stack.mulPose(new org.joml.Quaternionf().rotationXYZ(
                (float) Math.toRadians(transform.rx()), (float) Math.toRadians(transform.ry()), (float) Math.toRadians(transform.rz())));
        stack.scale(transform.sx(), transform.sy(), transform.sz());
    }
}
