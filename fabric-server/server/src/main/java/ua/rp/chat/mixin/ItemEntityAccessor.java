package ua.rp.chat.mixin;

import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.UUID;

@Mixin(ItemEntity.class)
public interface ItemEntityAccessor {
    /** Vanilla's optional pickup recipient; this is not the entity that threw the item. */
    @Accessor("target")
    UUID eclipse$getPickupTarget();
}
