package ua.rp.chat.mixin.entity;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ua.rp.chat.crawling.CrawlingServerManager;

@Mixin(Player.class)
public abstract class PlayerPoseMixin {
    @Unique
    private static long eclipse$lastDebugLog = 0L;

    @Inject(method = "updatePlayerPose()V", at = @At("HEAD"), cancellable = true)
    private void eclipse$overrideCrawlingPose(CallbackInfo ci) {
        Player player = (Player) (Object) this;
        if (player instanceof ServerPlayer serverPlayer) {
            if (CrawlingServerManager.isCrawling(serverPlayer)) {
                player.setPose(Pose.SWIMMING);
                long now = System.currentTimeMillis();
                if (now - eclipse$lastDebugLog > 3000L) {
                    eclipse$lastDebugLog = now;
                    System.out.println("[CRAWL-DEBUG-SERVER] Pose SWIMMING maintained for player="
                            + serverPlayer.getName().getString() + " pos=" + serverPlayer.blockPosition());
                }
                ci.cancel();
            }
        }
    }
}
