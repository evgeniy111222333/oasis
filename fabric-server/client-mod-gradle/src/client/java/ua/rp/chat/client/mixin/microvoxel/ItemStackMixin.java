package ua.rp.chat.client.mixin.microvoxel;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ua.rp.chat.client.microvoxel.MicrovoxelItemData;

@Mixin(ItemStack.class)
public abstract class ItemStackMixin {
    @Inject(method = "getHoverName", at = @At("RETURN"), cancellable = true)
    private void eclipse$microvoxelItemName(CallbackInfoReturnable<Component> cir) {
        ItemStack stack = (ItemStack) (Object) this;
        // A deliberately assigned RP name always wins over the generated material description.
        if (stack.has(DataComponents.CUSTOM_NAME)) return;

        MicrovoxelItemData.Parsed parsed = MicrovoxelItemData.parse(stack);
        if (parsed == null) return;
        Component materialName = cir.getReturnValue();
        String translation = switch (parsed.kind()) {
            case CARVED -> "item.eclipseclient.microvoxel_workpiece";
            case REMAINDER -> "item.eclipseclient.microvoxel_incomplete_measure";
            case RECLAIMED -> "item.eclipseclient.microvoxel_small_measure";
        };
        ChatFormatting colour = switch (parsed.kind()) {
            case CARVED -> ChatFormatting.GOLD;
            case REMAINDER -> ChatFormatting.AQUA;
            case RECLAIMED -> ChatFormatting.GREEN;
        };
        cir.setReturnValue(Component.translatable(translation, materialName)
                .withStyle(style -> style.withItalic(false).withColor(colour)));
    }
}
