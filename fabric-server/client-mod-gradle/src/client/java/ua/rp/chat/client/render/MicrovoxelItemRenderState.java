package ua.rp.chat.client.render;

import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/**
 * Runtime marker carried by Minecraft's reusable item render state.
 *
 * This interface intentionally lives outside the package owned by the Mixin
 * configuration: Fabric forbids application classes from directly loading a
 * helper type declared inside a reserved mixin package.
 */
public interface MicrovoxelItemRenderState {
    void eclipse$replaceWithMicrovoxelItem(ItemStack stack, ItemDisplayContext displayContext);
}
