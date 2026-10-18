package ua.rp.chat.client.mixin.carry;

import net.minecraft.world.entity.Display;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Reuses the already-synchronized public mesh when it would overfill a carry snapshot. */
@Mixin(Display.ItemDisplay.class)
public interface CarryItemDisplayAccessor {
    @Invoker("getItemStack")
    ItemStack eclipse$carryVisualItem();
}
