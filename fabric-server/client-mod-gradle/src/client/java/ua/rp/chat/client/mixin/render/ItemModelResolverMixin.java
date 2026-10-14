package ua.rp.chat.client.mixin.render;

import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ua.rp.chat.client.render.MicrovoxelItemRenderState;

@Mixin(ItemModelResolver.class)
public abstract class ItemModelResolverMixin {
    @ModifyVariable(method = "updateForTopItem", at = @At("HEAD"), argsOnly = true)
    private ItemDisplayContext eclipse$editorPreviewContext(ItemDisplayContext original) {
        ItemDisplayContext preview = ua.rp.chat.client.modeleditor.ModelEditorClient.itemPreviewContext();
        return original == ItemDisplayContext.GUI && preview != null ? preview : original;
    }
    @Inject(method = "updateForTopItem", at = @At("TAIL"))
    private void eclipse$markMicrovoxelItem(
            ItemStackRenderState renderState,
            ItemStack stack,
            ItemDisplayContext displayContext,
            Level level,
            ItemOwner owner,
            int seed,
            CallbackInfo ci
    ) {
        ((MicrovoxelItemRenderState) renderState)
                .eclipse$replaceWithMicrovoxelItem(stack, displayContext);
        ua.rp.chat.client.modeleditor.ModelPlacementRendering.finish(
                (ItemModelResolver) (Object) this, renderState, stack, displayContext, level, owner, seed);
    }
}
