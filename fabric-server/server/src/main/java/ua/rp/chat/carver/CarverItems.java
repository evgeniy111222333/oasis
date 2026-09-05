package ua.rp.chat.carver;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Tool contract of the Carver system: the mason's bag rides in the chest armour
 * slot (the waist belt has no vanilla slot, so the harness occupies the torso layer
 * and reads as strapped tools), the architect's scroll is held in the main hand.
 *
 * <p>Both items are plain registry entries; all behaviour lives in
 * {@link CarverManager} so balancing never requires item subclasses.</p>
 */
public final class CarverItems {
    public static final Identifier BAG_ID =
            Identifier.fromNamespaceAndPath("eclipseserver", "carver_bag");
    public static final Identifier SCROLL_ID =
            Identifier.fromNamespaceAndPath("eclipseserver", "architect_scroll");
    public static final Identifier CHISEL_POINT_ID =
            Identifier.fromNamespaceAndPath("eclipseserver", "carver_chisel_point");
    public static final Identifier CHISEL_FLAT_ID =
            Identifier.fromNamespaceAndPath("eclipseserver", "carver_chisel_flat");
    public static final Identifier BAG_NO_POINT_ID =
            Identifier.fromNamespaceAndPath("eclipseserver", "carver_bag_no_point");
    public static final Identifier BAG_EMPTY_ID =
            Identifier.fromNamespaceAndPath("eclipseserver", "carver_bag_empty");

    public static final ResourceKey<Item> BAG_KEY =
            ResourceKey.create(Registries.ITEM, BAG_ID);
    public static final ResourceKey<Item> SCROLL_KEY =
            ResourceKey.create(Registries.ITEM, SCROLL_ID);
    public static final ResourceKey<Item> CHISEL_POINT_KEY =
            ResourceKey.create(Registries.ITEM, CHISEL_POINT_ID);
    public static final ResourceKey<Item> CHISEL_FLAT_KEY =
            ResourceKey.create(Registries.ITEM, CHISEL_FLAT_ID);
    public static final ResourceKey<Item> BAG_NO_POINT_KEY =
            ResourceKey.create(Registries.ITEM, BAG_NO_POINT_ID);
    public static final ResourceKey<Item> BAG_EMPTY_KEY =
            ResourceKey.create(Registries.ITEM, BAG_EMPTY_ID);

    public static final Item BAG = new Item(new Item.Properties().setId(BAG_KEY).stacksTo(1)
            .component(net.minecraft.core.component.DataComponents.EQUIPPABLE,
                    net.minecraft.world.item.equipment.Equippable
                            .builder(net.minecraft.world.entity.EquipmentSlot.CHEST)
                            .setEquipSound(net.minecraft.sounds.SoundEvents.ARMOR_EQUIP_LEATHER)
                            .setDispensable(false)
                            .build()));
    public static final Item SCROLL = new Item(new Item.Properties().setId(SCROLL_KEY).stacksTo(1));
    public static final Item CHISEL_POINT = new Item(new Item.Properties().setId(CHISEL_POINT_KEY).stacksTo(1));
    public static final Item CHISEL_FLAT = new Item(new Item.Properties().setId(CHISEL_FLAT_KEY).stacksTo(1));
    public static final Item BAG_NO_POINT = new Item(new Item.Properties().setId(BAG_NO_POINT_KEY).stacksTo(1));
    public static final Item BAG_EMPTY = new Item(new Item.Properties().setId(BAG_EMPTY_KEY).stacksTo(1));

    private static boolean registered;

    private CarverItems() {
    }

    public static synchronized void register() {
        if (registered) return;
        if (!BuiltInRegistries.ITEM.containsKey(BAG_ID)) {
            Registry.register(BuiltInRegistries.ITEM, BAG_KEY, BAG);
        }
        if (!BuiltInRegistries.ITEM.containsKey(SCROLL_ID)) {
            Registry.register(BuiltInRegistries.ITEM, SCROLL_KEY, SCROLL);
        }
        if (!BuiltInRegistries.ITEM.containsKey(CHISEL_POINT_ID)) {
            Registry.register(BuiltInRegistries.ITEM, CHISEL_POINT_KEY, CHISEL_POINT);
        }
        if (!BuiltInRegistries.ITEM.containsKey(CHISEL_FLAT_ID)) {
            Registry.register(BuiltInRegistries.ITEM, CHISEL_FLAT_KEY, CHISEL_FLAT);
        }
        if (!BuiltInRegistries.ITEM.containsKey(BAG_NO_POINT_ID)) {
            Registry.register(BuiltInRegistries.ITEM, BAG_NO_POINT_KEY, BAG_NO_POINT);
        }
        if (!BuiltInRegistries.ITEM.containsKey(BAG_EMPTY_ID)) {
            Registry.register(BuiltInRegistries.ITEM, BAG_EMPTY_KEY, BAG_EMPTY);
        }
        registered = true;
    }

    /** True when the mason's harness is strapped on (chest armour slot). */
    public static boolean hasBag(Player player) {
        return player != null && player.getItemBySlot(EquipmentSlot.CHEST).is(BAG);
    }

    /** True when the architect's scroll is held in the main hand. */
    public static boolean holdsScroll(Player player) {
        return player != null
                && player.getMainHandItem() != null
                && player.getMainHandItem().is(SCROLL);
    }

    public static ItemStack bagStack() {
        return new ItemStack(BAG);
    }

    public static ItemStack scrollStack() {
        return new ItemStack(SCROLL);
    }
}
