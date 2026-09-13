package ua.rp.chat.mixin.entity;

import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundContainerButtonClickPacket;
import net.minecraft.network.protocol.game.ServerboundContainerSlotStateChangedPacket;
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ua.rp.chat.RPChat;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class PlayerActionMixin {
    @Shadow public ServerPlayer player;

    @Inject(method = "handlePlayerAction", at = @At("HEAD"), cancellable = true)
    private void eclipse$blockForbiddenDrops(ServerboundPlayerActionPacket packet, CallbackInfo callback) {
        ServerboundPlayerActionPacket.Action action = packet.getAction();
        if (action != ServerboundPlayerActionPacket.Action.DROP_ITEM
                && action != ServerboundPlayerActionPacket.Action.DROP_ALL_ITEMS) return;
        RPChat plugin = RPChat.getInstance();
        if (plugin == null) return;
        boolean pending = plugin.getAuthManager() != null
                && plugin.getAuthManager().isPendingAuth(player.getUUID());
        boolean bound = plugin.getAcquaintanceManager() != null
                && plugin.getAcquaintanceManager().onBoundDrop(player);
        if (pending || bound) callback.cancel();
    }

    @Inject(method = "handleContainerClick", at = @At("HEAD"), cancellable = true)
    private void eclipse$blockContainerClickDuringAuthentication(
            ServerboundContainerClickPacket packet,
            CallbackInfo callback) {
        if (eclipse$isPendingAuthentication()) callback.cancel();
    }

    @Inject(method = "handleContainerButtonClick", at = @At("HEAD"), cancellable = true)
    private void eclipse$blockContainerButtonDuringAuthentication(
            ServerboundContainerButtonClickPacket packet,
            CallbackInfo callback) {
        if (eclipse$isPendingAuthentication()) callback.cancel();
    }

    @Inject(method = "handleContainerSlotStateChanged", at = @At("HEAD"), cancellable = true)
    private void eclipse$blockContainerSlotStateDuringAuthentication(
            ServerboundContainerSlotStateChangedPacket packet,
            CallbackInfo callback) {
        if (eclipse$isPendingAuthentication()) callback.cancel();
    }

    @Inject(method = "handleSetCreativeModeSlot", at = @At("HEAD"), cancellable = true)
    private void eclipse$blockCreativeSlotDuringAuthentication(
            ServerboundSetCreativeModeSlotPacket packet,
            CallbackInfo callback) {
        if (eclipse$isPendingAuthentication()) callback.cancel();
    }

    private boolean eclipse$isPendingAuthentication() {
        RPChat plugin = RPChat.getInstance();
        return plugin != null && plugin.getAuthManager() != null
                && plugin.getAuthManager().isPendingAuth(player.getUUID());
    }
}
