package ua.rp.chat.mixin.microvoxel;

import net.minecraft.world.level.ServerExplosion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ua.rp.chat.RPChat;

/** Applies material-aware blast pressure to the native 1/16 microvoxel geometry. */
@Mixin(ServerExplosion.class)
public abstract class ServerExplosionMicrovoxelMixin {
    @Inject(method = "explode", at = @At("RETURN"))
    private void eclipse$explodeMicrovoxels(CallbackInfoReturnable<Integer> callback) {
        RPChat plugin = RPChat.getInstance();
        if (plugin == null || plugin.getMicrovoxelManager() == null) return;
        plugin.getMicrovoxelManager().onExplosion((ServerExplosion) (Object) this);
    }
}
