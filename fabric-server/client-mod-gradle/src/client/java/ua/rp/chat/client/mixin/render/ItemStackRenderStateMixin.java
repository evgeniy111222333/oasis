package ua.rp.chat.client.mixin.render;

import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.resources.model.cuboid.ItemTransform;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import ua.rp.chat.client.microvoxel.MicrovoxelItemData;
import ua.rp.chat.client.mixin.ItemLayerRenderStateAccessor;
import ua.rp.chat.client.microvoxel.MicrovoxelItemModel;
import ua.rp.chat.client.render.MicrovoxelItemRenderState;
import ua.rp.chat.microvoxel.MicrovoxelItemScale;

@Mixin(ItemStackRenderState.class)
public abstract class ItemStackRenderStateMixin implements MicrovoxelItemRenderState {
    @Shadow ItemDisplayContext displayContext;
    @Shadow private int activeLayerCount;
    @Shadow private ItemStackRenderState.LayerRenderState[] layers;
    @Shadow public abstract void clear();
    @Shadow public abstract ItemStackRenderState.LayerRenderState newLayer();
    @Shadow public abstract void appendModelIdentityElement(Object identity);

    @Override
    public void eclipse$replaceWithMicrovoxelItem(ItemStack stack,
                                                  ItemDisplayContext requestedContext) {
        MicrovoxelItemData.Parsed parsed = MicrovoxelItemData.parse(stack);
        if (parsed == null) return;

        ItemTransform transform = ItemTransform.NO_TRANSFORM;
        if (activeLayerCount > 0 && layers[0] != null) {
            ItemTransform inherited =
                    ((ItemLayerRenderStateAccessor) (Object) layers[0]).eclipse$getItemTransform();
            if (inherited != null) transform = inherited;
        }

        // Remove the underlying full BlockItem model, retain its context transform, and replace it
        // with immutable quads compiled from the serialized 16x16x16 shape.
        clear();
        displayContext = requestedContext;
        ItemStackRenderState.LayerRenderState layer = newLayer();
        layer.setItemTransform(transform);
        MicrovoxelItemModel.CompiledItem compiled = MicrovoxelItemModel.apply(layer, parsed);

        float inheritedScale = Math.max(
                Math.abs(transform.scale().x()),
                Math.max(Math.abs(transform.scale().y()), Math.abs(transform.scale().z())));
        MicrovoxelItemScale.Presentation presentation = MicrovoxelItemScale.presentation(
                requestedContext, compiled.bounds(), inheritedScale);
        Matrix4f localTransform = new Matrix4f();
        if (presentation.recenter()) {
            // ItemTransform ends by translating a normal 0..1 cube around its 0.5 pivot.
            // Put the occupied carved bounds on that pivot before applying display scale.
            localTransform
                    .translate(0.5f, 0.5f, 0.5f)
                    .scale(presentation.scale())
                    .translate(-presentation.centerX(),
                            -presentation.centerY(),
                            -presentation.centerZ());
        } else {
            localTransform.scale(presentation.scale());
        }
        layer.setLocalTransform(localTransform);

        // GUI items are rasterized into GuiItemAtlas and keyed by this identity list. Include the
        // exact carved content plus a policy version so stale pre-fix atlas entries cannot survive.
        appendModelIdentityElement(compiled.renderIdentity());
        appendModelIdentityElement(MicrovoxelItemScale.PRESENTATION_IDENTITY);
    }
}
