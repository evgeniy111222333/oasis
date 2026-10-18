package ua.rp.chat.item;

import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemAttributeModifiers;

/** Identical item and component definitions on the dedicated server and client. */
public final class MedievalItems {
    public static final Identifier HAMMER_ID = Identifier.fromNamespaceAndPath("eclipseserver", "ancient_peasant_hammer");
    public static final ResourceKey<Item> HAMMER_KEY = ResourceKey.create(Registries.ITEM, HAMMER_ID);
    public static final Item HAMMER = new Item(new Item.Properties().setId(HAMMER_KEY).stacksTo(1)
            .durability(180).repairable(Items.IRON_INGOT).enchantable(10)
            .component(net.minecraft.core.component.DataComponents.WEAPON,
                    new net.minecraft.world.item.component.Weapon(1))
            .attributes(ItemAttributeModifiers.builder()
                    .add(Attributes.ATTACK_DAMAGE, new AttributeModifier(Item.BASE_ATTACK_DAMAGE_ID,
                            3.5, AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.MAINHAND)
                    .add(Attributes.ATTACK_SPEED, new AttributeModifier(Item.BASE_ATTACK_SPEED_ID,
                            -3.0, AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.MAINHAND)
                    .build()));
    private static boolean registered;

    private MedievalItems() { }

    public static synchronized void register() {
        if (registered) return;
        Registry.register(BuiltInRegistries.ITEM, HAMMER_KEY, HAMMER);
        CreativeModeTabEvents.modifyOutputEvent(ResourceKey.create(Registries.CREATIVE_MODE_TAB,
                Identifier.withDefaultNamespace("combat"))).register(output -> output.accept(HAMMER));
        registered = true;
    }
}
