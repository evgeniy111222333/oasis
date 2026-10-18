package ua.rp.chat.client.mixin;

import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import ua.rp.chat.locomotion.RoleplayPaceAttributes;

/** Ordinary RP pace is the camera baseline; native sprint, symptoms and item zoom still apply. */
@Mixin(AbstractClientPlayer.class)
public abstract class RoleplayPacingFovMixin {
    @Redirect(method="getFieldOfViewModifier",at=@At(value="INVOKE",
            target="Lnet/minecraft/client/player/AbstractClientPlayer;getAttributeValue(Lnet/minecraft/core/Holder;)D"))
    private double eclipse$cameraSpeedWithoutRoleplayPace(AbstractClientPlayer player,Holder<Attribute> attribute) {
        double speed=player.getAttributeValue(attribute);
        if (!attribute.equals(Attributes.MOVEMENT_SPEED)) return speed;
        var movement=player.getAttribute(attribute);
        var pace=movement==null ? null : movement.getModifier(RoleplayPaceAttributes.MODIFIER_ID);
        if (pace==null || pace.operation()!=AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL) return speed;
        double factor=1+pace.amount();
        return factor>0 ? speed/factor : speed;
    }
}
