package ua.rp.chat.client.mixin;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ItemStack.class)
public abstract class ItemStackMixin {
    @Inject(method = "getHoverName", at = @At("RETURN"), cancellable = true)
    private void eclipse$microvoxelItemName(CallbackInfoReturnable<Component> cir) {
        ItemStack stack = (ItemStack) (Object) this;
        CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
        if (customData != null) {
            CompoundTag nbt = customData.copyTag();
            if (nbt.contains("microvoxel_volume")) {
                String prefix = net.minecraft.locale.Language.getInstance().getOrDefault("item.eclipseclient.microvoxel_carved_prefix");
                if (prefix.equals("item.eclipseclient.microvoxel_carved_prefix")) {
                    prefix = "Резной ";
                }
                String baseKey = stack.getItem().getDescriptionId();
                String translatedName = net.minecraft.locale.Language.getInstance().getOrDefault(baseKey);
                String lowerTranslatedName = translatedName.toLowerCase(java.util.Locale.ROOT);
                Component newName = Component.literal(prefix + lowerTranslatedName)
                        .withStyle(style -> style.withItalic(false).withColor(ChatFormatting.GOLD));
                cir.setReturnValue(newName);
            }
        }
    }
}
