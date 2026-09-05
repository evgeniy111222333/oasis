package ua.rp.chat.client.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ua.rp.chat.client.crawling.CrawlingClientState;

@Mixin(Player.class)
public abstract class PlayerPoseMixin {
    @Unique
    private static long eclipse$lastClientDebugLog = 0L;

    @Inject(method = "updatePlayerPose()V", at = @At("HEAD"), cancellable = true)
    private void eclipse$overrideClientCrawlingPose(CallbackInfo ci) {
        Player player = (Player) (Object) this;
        Minecraft client = Minecraft.getInstance();
        if (client != null && player == client.player) {
            if (CrawlingClientState.isCrawling()) {
                player.setPose(Pose.SWIMMING);
                long now = System.currentTimeMillis();
                if (now - eclipse$lastClientDebugLog > 3000L) {
                    eclipse$lastClientDebugLog = now;
                    System.out.println("[CRAWL-DEBUG-CLIENT] Local player pose maintained as SWIMMING, progress="
                            + String.format(java.util.Locale.ROOT, "%.2f", CrawlingClientState.getCrawlProgress()));
                }
                ci.cancel();
            }
        }
    }
}
