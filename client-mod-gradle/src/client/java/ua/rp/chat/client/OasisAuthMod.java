package ua.rp.chat.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.Minecraft;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class OasisAuthMod implements ClientModInitializer {

    public static final String MOD_ID = "oasisauth";
    public static final Logger LOGGER = LogManager.getLogger("OasisAuth");

    @Override
    public void onInitializeClient() {
        LOGGER.info("Oasis Auth Mod initialized! Waiting for server packages...");

        // Register custom payload codec
        PayloadTypeRegistry.clientboundPlay().register(AuthPayload.TYPE, AuthPayload.CODEC);

        // Register packet receiver
        ClientPlayNetworking.registerGlobalReceiver(AuthPayload.TYPE, (payload, context) -> {
            String authUrl = payload.authUrl();
            LOGGER.info("Received auth trigger from server. URL: " + authUrl);
            
            context.client().execute(() -> {
                Minecraft.getInstance().setScreen(new AuthScreen(authUrl));
            });
        });
    }
}
