package ua.rp.chat.client.animation;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUseAnimation;

public record ItemMotionProfile(String id, String group, float weight, float guard, float twoHanded, float medical) {
    public static ItemMotionProfile classify(ItemStack main, ItemStack offhand, boolean usingItem) {
        ItemMotionProfile mainProfile = classifyOne(main, usingItem);
        ItemMotionProfile offProfile = classifyOne(offhand, usingItem);
        if (offProfile.weight * 0.72f > mainProfile.weight) {
            return new ItemMotionProfile(offProfile.id, offProfile.group, offProfile.weight * 0.72f,
                    offProfile.guard, offProfile.twoHanded, offProfile.medical);
        }
        return mainProfile;
    }

    private static ItemMotionProfile classifyOne(ItemStack stack, boolean usingItem) {
        if (stack == null || stack.isEmpty()) {
            return new ItemMotionProfile("empty", "empty", 0.0f, 0.0f, 0.0f, 0.0f);
        }

        String id = stack.getItem().toString().toLowerCase(java.util.Locale.ROOT);
        ItemUseAnimation animation = stack.getUseAnimation();

        if (id.contains("bandage") || id.contains("splint") || id.contains("tourniquet")
                || id.contains("medicine") || id.contains("salve") || id.contains("suture")) {
            return new ItemMotionProfile(id, "medical", 0.24f, 0.25f, 0.55f, usingItem ? 1.0f : 0.65f);
        }
        if (id.contains("shield") || animation == ItemUseAnimation.BLOCK) {
            return new ItemMotionProfile(id, "shield", 0.75f, usingItem ? 1.0f : 0.74f, 0.35f, 0.0f);
        }
        if (animation == ItemUseAnimation.BOW || animation == ItemUseAnimation.CROSSBOW
                || animation == ItemUseAnimation.TRIDENT || animation == ItemUseAnimation.SPEAR
                || id.contains("bow") || id.contains("crossbow") || id.contains("trident") || id.contains("spear")) {
            return new ItemMotionProfile(id, "ranged", 0.66f, usingItem ? 0.88f : 0.35f, usingItem ? 1.0f : 0.45f, 0.0f);
        }
        if (id.contains("mace") || id.contains("hammer") || id.contains("halberd") || id.contains("great")) {
            return new ItemMotionProfile(id, "heavy_melee", 0.95f, 0.78f, 0.62f, 0.0f);
        }
        if (id.contains("axe")) {
            return new ItemMotionProfile(id, "heavy_melee", 0.86f, 0.66f, 0.46f, 0.0f);
        }
        if (id.contains("sword") || id.contains("knife") || id.contains("dagger")) {
            return new ItemMotionProfile(id, "melee", 0.62f, 0.72f, 0.20f, 0.0f);
        }
        if (animation == ItemUseAnimation.EAT || animation == ItemUseAnimation.DRINK || id.contains("potion")) {
            return new ItemMotionProfile(id, "consumable", 0.22f, usingItem ? 0.42f : 0.08f, 0.15f, 0.0f);
        }
        if (id.contains("pickaxe") || id.contains("shovel") || id.contains("hoe")) {
            return new ItemMotionProfile(id, "tool", 0.58f, 0.42f, 0.20f, 0.0f);
        }

        return new ItemMotionProfile(id, "light", 0.18f, 0.10f, 0.0f, 0.0f);
    }
}
