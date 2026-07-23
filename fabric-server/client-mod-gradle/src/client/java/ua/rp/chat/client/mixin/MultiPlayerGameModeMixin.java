package ua.rp.chat.client.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ua.rp.chat.client.CombatIntentSender;
import ua.rp.chat.client.microvoxel.MicrovoxelClientState;
import ua.rp.chat.client.microvoxel.MicrovoxelInteractionController;

@Mixin(MultiPlayerGameMode.class)
public class MultiPlayerGameModeMixin {
    @Inject(method = "attack", at = @At("HEAD"))
    private void eclipse$sendCombatIntent(Player player, Entity target, CallbackInfo ci) {
        CombatIntentSender.send(player, target);
    }

    @Inject(method = "startDestroyBlock", at = @At("HEAD"), cancellable = true)
    private void eclipse$microvoxelStartDestroyBlock(BlockPos pos, Direction face, CallbackInfoReturnable<Boolean> cir) {
        // Never let vanilla break the invisible marker block; microvoxel
        // editing is handled entirely through the plugin channel.
        if (MicrovoxelClientState.get(pos) != null) {
            cir.setReturnValue(false);
            return;
        }
        if (MicrovoxelInteractionController.editing() && eclipse$isMicrovoxelOrCarvable(pos)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "continueDestroyBlock", at = @At("HEAD"), cancellable = true)
    private void eclipse$microvoxelContinueDestroyBlock(BlockPos pos, Direction face, CallbackInfoReturnable<Boolean> cir) {
        if (MicrovoxelClientState.get(pos) != null) {
            cir.setReturnValue(false);
            return;
        }
        if (MicrovoxelInteractionController.editing() && eclipse$isMicrovoxelOrCarvable(pos)) {
            cir.setReturnValue(false);
        }
    }

    private boolean eclipse$isMicrovoxelOrCarvable(BlockPos pos) {
        if (MicrovoxelClientState.get(pos) != null) return true;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) return false;
        BlockState state = minecraft.level.getBlockState(pos);
        return !state.isAir() && state.isSolidRender() && !state.hasBlockEntity();
    }
}
