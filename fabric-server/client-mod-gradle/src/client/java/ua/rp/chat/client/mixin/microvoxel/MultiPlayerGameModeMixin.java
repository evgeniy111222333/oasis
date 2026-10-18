package ua.rp.chat.client.mixin.microvoxel;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ua.rp.chat.client.CombatIntentSender;
import ua.rp.chat.client.microvoxel.MicrovoxelItemData;
import ua.rp.chat.client.microvoxel.MicrovoxelClientState;
import ua.rp.chat.client.microvoxel.MicrovoxelInteractionController;

@Mixin(MultiPlayerGameMode.class)
public class MultiPlayerGameModeMixin {
    @Inject(method = "performUseItemOn", at = @At("HEAD"), cancellable = true)
    private void eclipse$predictPortableMicrovoxelPlacement(
            LocalPlayer player,
            InteractionHand hand,
            BlockHitResult hit,
            CallbackInfoReturnable<InteractionResult> cir
    ) {
        MicrovoxelItemData.Parsed parsed =
                MicrovoxelItemData.parse(player.getItemInHand(hand));
        if (parsed == null || parsed.kind() != MicrovoxelItemData.Kind.CARVED) return;

        BlockPos placement = hit.getBlockPos().relative(hit.getDirection());
        cir.setReturnValue(MicrovoxelClientState.predictPortablePlacement(placement, parsed)
                ? InteractionResult.SUCCESS : InteractionResult.FAIL);
    }

    @Inject(method = "attack", at = @At("HEAD"))
    private void eclipse$sendCombatIntent(Player player, Entity target, CallbackInfo ci) {
        CombatIntentSender.send(player, target);
    }

    /**
     * In edit mode, left-click edits cells on real microvoxel volumes only. Vanilla digging
     * stays available everywhere else; carving a plain block into voxels is an explicit
     * convert action, never an implicit destroy-block hijack.
     */
    @Inject(method = "startDestroyBlock", at = @At("HEAD"), cancellable = true)
    private void eclipse$carverGuardDestroyBlock(BlockPos pos, Direction face, CallbackInfoReturnable<Boolean> cir) {
        // Never break the carver's canvas mid-session; the commit path owns it.
        if (ua.rp.chat.client.carver.CarverClientState.inSession()
                && pos.equals(ua.rp.chat.client.carver.CarverClientState.focus())) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "startDestroyBlock", at = @At("HEAD"), cancellable = true)
    private void eclipse$microvoxelStartDestroyBlock(BlockPos pos, Direction face, CallbackInfoReturnable<Boolean> cir) {
        // In M mode clicks edit individual cells. Vanilla digging stays available everywhere
        // except on actual microvoxel volumes; carving vanilla blocks is an explicit C action.
        if (MicrovoxelInteractionController.editing() && MicrovoxelClientState.get(pos) != null) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "continueDestroyBlock", at = @At("HEAD"), cancellable = true)
    private void eclipse$microvoxelContinueDestroyBlock(BlockPos pos, Direction face, CallbackInfoReturnable<Boolean> cir) {
        if (MicrovoxelInteractionController.editing() && MicrovoxelClientState.get(pos) != null) {
            cir.setReturnValue(false);
        }
    }
}
