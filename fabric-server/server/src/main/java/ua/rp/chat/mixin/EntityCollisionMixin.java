package ua.rp.chat.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ua.rp.chat.RPChat;

@Mixin(Entity.class)
public abstract class EntityCollisionMixin {
    @Inject(method = "collide", at = @At("RETURN"), cancellable = true)
    private void eclipse$collideWithMicrovoxels(Vec3 requested, CallbackInfoReturnable<Vec3> callback) {
        Entity entity = (Entity) (Object) this;
        RPChat plugin = RPChat.getInstance();
        if (!(entity.level() instanceof ServerLevel) || plugin == null || plugin.getMicrovoxelManager() == null) return;
        callback.setReturnValue(plugin.getMicrovoxelManager().collide(entity, callback.getReturnValue()));
    }
}
