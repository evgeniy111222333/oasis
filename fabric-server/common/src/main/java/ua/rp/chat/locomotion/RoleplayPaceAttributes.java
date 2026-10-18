package ua.rp.chat.locomotion;

import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;

/** Applies the same transient pace modifier to local prediction and server authority. */
public final class RoleplayPaceAttributes {
    public static final Identifier MODIFIER_ID =
            Identifier.fromNamespaceAndPath("eclipserp", "roleplay_pace");

    private RoleplayPaceAttributes() { }

    /** Leaves vitals' base speed and all other attribute modifiers intact. */
    public static double apply(Player player) {
        double factor = supportsPacing(player)
                ? RoleplayPacing.factor(player.isSprinting(), player.isCrouching()) : 1.0;
        var movement = player.getAttribute(Attributes.MOVEMENT_SPEED);
        if (movement == null) return factor;

        AttributeModifier current = movement.getModifier(MODIFIER_ID);
        if (factor == 1.0) {
            if (current != null) movement.removeModifier(MODIFIER_ID);
            return factor;
        }

        double amount = factor - 1.0;
        if (current != null && current.amount() == amount
                && current.operation() == AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL) {
            return factor;
        }
        if (current != null) movement.removeModifier(MODIFIER_ID);
        movement.addTransientModifier(new AttributeModifier(MODIFIER_ID, amount,
                AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        return factor;
    }

    public static void clear(Player player) {
        var movement = player.getAttribute(Attributes.MOVEMENT_SPEED);
        if (movement != null) movement.removeModifier(MODIFIER_ID);
    }

    private static boolean supportsPacing(Player player) {
        return player.isAlive() && !player.isSpectator() && !player.getAbilities().flying
                && !player.isFallFlying() && !player.isInWater() && !player.isSwimming()
                && !player.onClimbable() && !player.isPassenger();
    }
}
