package ua.rp.chat;

import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.phys.Vec3;
import ua.rp.chat.microvoxel.MicrovoxelProtocol;

import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.MessageArgument;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import ua.rp.chat.acquaintance.AcquaintanceManager;
import ua.rp.chat.auth.*;
import ua.rp.chat.combat.CombatManager;
import ua.rp.chat.vitals.StaminaManager;
import ua.rp.chat.vitals.BloodFxService;
import ua.rp.chat.microvoxel.MicrovoxelManager;
import ua.rp.chat.heavyhammer.HeavyHammerManager;
import ua.rp.chat.interaction.ItemPickupManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.sql.SQLException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.io.UncheckedIOException;
import java.util.Locale;
import java.util.UUID;
import java.util.logging.Logger;

public class RPChat implements DedicatedServerModInitializer {
    private static MinecraftServer server;
    private static RPChat instance;

    private int activeStyle = 1;
    private AuthDatabase authDatabase;
    private AuthManager authManager;
    private AuthWebServer authWebServer;
    private AppearanceManager appearanceManager;
    private StaminaManager staminaManager;
    private BloodFxService bloodFxService;
    private RpChatService rpChatService;
    private CombatManager combatManager;
    private AcquaintanceManager acquaintanceManager;
    private MicrovoxelManager microvoxelManager;
    private HeavyHammerManager heavyHammerManager;
    private ItemPickupManager itemPickupManager;
    private SimpleConfig config;
    private Logger logger = Logger.getLogger("RPChat");
    private int tickCounter = 0;

    public static MinecraftServer getMinecraftServer() {
        return server;
    }

    public static RPChat getInstance() {
        return instance;
    }

    public MinecraftServer getServer() {
        return server;
    }

    public Logger getLogger() {
        return logger;
    }

    public File getDataFolder() {
        return new File("config/RPChat");
    }

    public SimpleConfig getConfig() {
        return config;
    }

    @Override
    public void onInitializeServer() {
        instance = this;
        ua.rp.chat.vitals.BloodParticleTypes.register();
        File dataFolder = getDataFolder();
        migrateLegacyDataFolder(dataFolder);
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }

        // Save default config if not exists
        saveDefaultConfig();
        config = new SimpleConfig(new File(dataFolder, "config.yml"));
        
        activeStyle = config.getInt("active-style", 1);
        if (activeStyle < 1 || activeStyle > 10) {
            activeStyle = 1;
        }

        // --- Initialize Auth System ---
        try {
            authDatabase = new AuthDatabase(dataFolder, logger);
            authDatabase.connect();
            R2AppearanceStorage appearanceStorage = R2AppearanceStorage.fromConfig(config, logger);
            appearanceManager = new AppearanceManager(dataFolder, authDatabase, logger, appearanceStorage);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to initialize Fabric authentication database", e);
        }

        authManager = new AuthManager(this, authDatabase, appearanceManager);
        rpChatService = new RpChatService(this);
        bloodFxService = new BloodFxService(this);
        staminaManager = new StaminaManager(this, bloodFxService);
        staminaManager.start();
        combatManager = new CombatManager(this);
        acquaintanceManager = new AcquaintanceManager(this);
        acquaintanceManager.start();
        microvoxelManager = new MicrovoxelManager(this);
        heavyHammerManager = new HeavyHammerManager(this, microvoxelManager);
        heavyHammerManager.start();
        itemPickupManager = new ItemPickupManager(this);
        itemPickupManager.start();
        ua.rp.chat.crawling.CrawlingServerManager.init();

        // Extract web resources for customizability
        extractWebAssets();

        // Start Web Server
        int webPort = config.getInt("web.port", 8080);
        authWebServer = new AuthWebServer(this, authManager, staminaManager);

        // Register every payload used by the paired Fabric client before play connections are created.
        var serverbound = net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.serverboundPlay();
        serverbound.register(ua.rp.chat.client.CombatIntentPayload.TYPE, ua.rp.chat.client.CombatIntentPayload.CODEC);
        serverbound.register(ua.rp.chat.client.AcquaintanceActionPayload.TYPE, ua.rp.chat.client.AcquaintanceActionPayload.CODEC);
        serverbound.register(ua.rp.chat.client.microvoxel.MicrovoxelActionPayload.TYPE, ua.rp.chat.client.microvoxel.MicrovoxelActionPayload.CODEC);
        serverbound.register(ua.rp.chat.client.heavyhammer.HeavyHammerActionPayload.TYPE, ua.rp.chat.client.heavyhammer.HeavyHammerActionPayload.CODEC);
        serverbound.register(ua.rp.chat.client.pickup.ItemPickupPayload.TYPE, ua.rp.chat.client.pickup.ItemPickupPayload.CODEC);

        var clientbound = net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.clientboundPlay();
        clientbound.register(ua.rp.chat.client.AuthPayload.TYPE, ua.rp.chat.client.AuthPayload.CODEC);
        clientbound.register(ua.rp.chat.client.AcquaintancePayload.TYPE, ua.rp.chat.client.AcquaintancePayload.CODEC);
        clientbound.register(ua.rp.chat.client.AppearanceRefreshPayload.TYPE, ua.rp.chat.client.AppearanceRefreshPayload.CODEC);
        clientbound.register(ua.rp.chat.client.rpfeed.RpChatFeedPayload.TYPE, ua.rp.chat.client.rpfeed.RpChatFeedPayload.CODEC);
        clientbound.register(ua.rp.chat.client.microvoxel.MicrovoxelSyncPayload.TYPE, ua.rp.chat.client.microvoxel.MicrovoxelSyncPayload.CODEC);
        clientbound.register(ua.rp.chat.client.heavyhammer.HeavyHammerSyncPayload.TYPE, ua.rp.chat.client.heavyhammer.HeavyHammerSyncPayload.CODEC);
        clientbound.register(ua.rp.chat.client.blood.BloodFxPayload.TYPE, ua.rp.chat.client.blood.BloodFxPayload.CODEC);

        // Managers that touch worlds or the network start only after MinecraftServer exists.
        ServerLifecycleEvents.SERVER_STARTING.register(srv -> server = srv);
        ServerLifecycleEvents.SERVER_STARTED.register(srv -> {
            microvoxelManager.start();
            authWebServer.start(webPort);
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(srv -> onDisable());

        ServerTickEvents.END_SERVER_TICK.register(srv -> {
            tickCounter++;
            authManager.tick();
            staminaManager.tick();
            acquaintanceManager.tick();
            rpChatService.tickBubbles();
            heavyHammerManager.tick();
            microvoxelManager.tick();
        });

        // Register Auth Listener callbacks
        AuthListener authListener = new AuthListener(authManager);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, srv) -> {
            ServerPlayer player = handler.player;
            authListener.onJoin(player);
            
            microvoxelManager.onJoin(player);
            heavyHammerManager.onJoin(player);
            staminaManager.syncBloodFxTo(player);
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, srv) -> {
            ServerPlayer player = handler.player;
            authListener.onQuit(player);
            staminaManager.onQuit(player);
            
            microvoxelManager.onQuit(player);
            heavyHammerManager.onQuit(player);
        });

        // Block chat from unauthenticated players
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, params) -> {
            if (authManager.isPendingAuth(sender.getUUID())) {
                sender.sendSystemMessage(Component.literal("Спочатку авторизуйтесь."));
                return false;
            }
            // Route to RpChatChatListener
            ChatListener chatListener = new ChatListener(this);
            return chatListener.onChat(sender, message.signedBody().content());
        });

        // Register custom packet receivers
        ServerPlayNetworking.registerGlobalReceiver(ua.rp.chat.client.CombatIntentPayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            if (isPendingAuth(player)) return;
            combatManager.handleIntent(player, payload.attackId(), payload.targetId(), payload.zoneOrdinal(), payload.hitRatio(), payload.lateral(), payload.distance());
        });

        ServerPlayNetworking.registerGlobalReceiver(ua.rp.chat.client.microvoxel.MicrovoxelActionPayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            if (isPendingAuth(player)) return;
            microvoxelManager.handleAction(player, payload.action(), payload.x(), payload.y(), payload.z(), payload.cell(), payload.revision(),
                    new net.minecraft.world.phys.Vec3(payload.lookX(), payload.lookY(), payload.lookZ()),
                    new net.minecraft.world.phys.Vec3(payload.eyeX(), payload.eyeY(), payload.eyeZ()));
        });
        ServerPlayNetworking.registerGlobalReceiver(ua.rp.chat.client.AcquaintanceActionPayload.TYPE, (payload, context) -> {
            if (!isPendingAuth(context.player())) {
                acquaintanceManager.handleAction(context.player(), payload.action(), payload.targetId(), payload.text());
            }
        });

        ServerPlayNetworking.registerGlobalReceiver(ua.rp.chat.client.heavyhammer.HeavyHammerActionPayload.TYPE, (payload, context) -> {
            if (!isPendingAuth(context.player())) {
                heavyHammerManager.handleAction(context.player(), payload.x(), payload.y(), payload.z(),
                        payload.cell(), payload.revision(), payload.clientSequence());
            }
        });

        ServerPlayNetworking.registerGlobalReceiver(ua.rp.chat.client.pickup.ItemPickupPayload.TYPE, (payload, context) ->
                itemPickupManager.handlePickup(context.player(), payload.itemId()));

        // Restore the gameplay hooks that previously came from Bukkit listeners.
        net.fabricmc.fabric.api.event.player.UseItemCallback.EVENT.register((player, level, hand) -> {
            if (!(player instanceof ServerPlayer serverPlayer)) return InteractionResult.PASS;
            if (isPendingAuth(serverPlayer)) return InteractionResult.FAIL;
            if (acquaintanceManager.onBoundInteract(serverPlayer)) return InteractionResult.FAIL;
            return staminaManager.onTreat(serverPlayer, hand) ? InteractionResult.SUCCESS : InteractionResult.PASS;
        });

        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (!(player instanceof ServerPlayer serverPlayer) || !(level instanceof net.minecraft.server.level.ServerLevel serverLevel)) {
                return InteractionResult.PASS;
            }
            if (isPendingAuth(serverPlayer)) return InteractionResult.FAIL;
            if (acquaintanceManager.onBoundInteract(serverPlayer)) return InteractionResult.FAIL;
            net.minecraft.core.BlockPos placementPos = hit.getBlockPos().relative(hit.getDirection());
            if (microvoxelManager.onBlockPlace(serverPlayer, player.getItemInHand(hand), placementPos)) {
                return InteractionResult.SUCCESS;
            }
            microvoxelManager.refreshAdjacentMicrovoxelMeshes(serverLevel, placementPos);
            return microvoxelManager.protectsMarker(serverLevel, hit.getBlockPos())
                    || microvoxelManager.protectsMarker(serverLevel, placementPos)
                    ? InteractionResult.FAIL : InteractionResult.PASS;
        });

        AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) -> {
            if (!(player instanceof ServerPlayer serverPlayer)) return InteractionResult.PASS;
            if (isPendingAuth(serverPlayer)) return InteractionResult.FAIL;
            if (acquaintanceManager.checkBoundAttack(serverPlayer)) return InteractionResult.FAIL;
            microvoxelManager.startMining(serverPlayer, pos);
            return InteractionResult.PASS;
        });

        net.fabricmc.fabric.api.event.player.AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
            if (player instanceof ServerPlayer serverPlayer) {
                if (isPendingAuth(serverPlayer) || acquaintanceManager.checkBoundAttack(serverPlayer)) {
                    return InteractionResult.FAIL;
                }
            }
            return InteractionResult.PASS;
        });

        net.fabricmc.fabric.api.event.player.UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
            if (!(player instanceof ServerPlayer serverPlayer)) return InteractionResult.PASS;
            if (isPendingAuth(serverPlayer) || acquaintanceManager.onBoundInteract(serverPlayer)) return InteractionResult.FAIL;
            if (entity instanceof net.minecraft.world.entity.item.ItemEntity item) {
                return itemPickupManager.onUseEntity(serverPlayer, item, hand);
            }
            return InteractionResult.PASS;
        });

        net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, blockEntity) -> {
            if (!(player instanceof ServerPlayer serverPlayer)) return true;
            if (isPendingAuth(serverPlayer) || acquaintanceManager.checkBoundAttack(serverPlayer)) return false;
            return !microvoxelManager.onBlockBreak(serverPlayer, pos);
        });

        net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, blockEntity) -> {
            if (level instanceof net.minecraft.server.level.ServerLevel serverLevel) {
                microvoxelManager.refreshAdjacentMicrovoxelMeshes(serverLevel, pos);
            }
        });

        net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents.CHUNK_LOAD.register(
                (level, chunk, newChunk) -> microvoxelManager.restoreMarkers(level, chunk));

        net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            if (entity instanceof ServerPlayer pendingVictim && isPendingAuth(pendingVictim)) return false;
            if (source.getEntity() instanceof ServerPlayer pendingAttacker && isPendingAuth(pendingAttacker)) return false;
            if (!(entity instanceof ServerPlayer victim)) return true;
            if (source.getEntity() instanceof ServerPlayer attacker) {
                acquaintanceManager.onBoundAttack(attacker, victim);
            }
            acquaintanceManager.onEscapeDamage(victim, amount);
            return !combatManager.onCombatDamage(victim, source, amount);
        });

        net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.AFTER_DAMAGE.register(
                (entity, source, baseDamage, damageTaken, blocked) -> {
                    if (entity instanceof ServerPlayer player && damageTaken > 0.0f) {
                        staminaManager.onDamage(player, source, damageTaken);
                    }
                });

        net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (entity instanceof ServerPlayer player) staminaManager.onDeath(player);
        });

        net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents.AFTER_RESPAWN.register(
                (oldPlayer, newPlayer, alive) -> staminaManager.onRespawn(newPlayer));

        // Command Registrations
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(Commands.literal("login")
                .executes(context -> {
                    ServerPlayer player = context.getSource().getPlayerOrException();
                    if (!authManager.isPendingAuth(player.getUUID())) {
                        player.sendSystemMessage(Component.literal("Вы уже авторизованы."));
                        return 1;
                    }
                    authManager.openAuthOverlay(player, activeOrNewToken(player));
                    player.sendSystemMessage(Component.literal("Открываем визуальное окно входа."));
                    return 1;
                })
            );

            dispatcher.register(Commands.literal("register")
                .executes(context -> {
                    ServerPlayer player = context.getSource().getPlayerOrException();
                    if (!authManager.isPendingAuth(player.getUUID())) {
                        player.sendSystemMessage(Component.literal("Вы уже авторизованы."));
                        return 1;
                    }
                    authManager.openAuthOverlay(player, activeOrNewToken(player));
                    player.sendSystemMessage(Component.literal("Открываем визуальное окно регистрации."));
                    return 1;
                })
            );

            dispatcher.register(Commands.literal("l")
                .executes(context -> {
                    ServerPlayer player = context.getSource().getPlayerOrException();
                    if (!authManager.isPendingAuth(player.getUUID())) {
                        player.sendSystemMessage(Component.literal("Вы уже авторизованы."));
                        return 1;
                    }
                    authManager.openAuthOverlay(player, activeOrNewToken(player));
                    player.sendSystemMessage(Component.literal("Открываем визуальное окно входа."));
                    return 1;
                })
            );

            dispatcher.register(Commands.literal("skin")
                .executes(context -> {
                    ServerPlayer player = context.getSource().getPlayerOrException();
                    if (!authManager.requestAppearanceChange(player)) {
                        player.sendSystemMessage(Component.literal("Сменить внешность можно после входа в персонажа."));
                    }
                    return 1;
                })
            );

            dispatcher.register(Commands.literal("me")
                .then(Commands.argument("action", MessageArgument.message())
                    .executes(context -> {
                        ServerPlayer player = context.getSource().getPlayerOrException();
                        String action = MessageArgument.getMessage(context, "action").getString();
                        rpChatService.sendAction(player, action);
                        return 1;
                    }))
            );

            dispatcher.register(Commands.literal("do")
                .then(Commands.argument("description", MessageArgument.message())
                    .executes(context -> {
                        ServerPlayer player = context.getSource().getPlayerOrException();
                        String desc = MessageArgument.getMessage(context, "description").getString();
                        rpChatService.sendDescription(player, desc);
                        return 1;
                    }))
            );

            dispatcher.register(Commands.literal("try")
                .then(Commands.argument("attempt", MessageArgument.message())
                    .executes(context -> {
                        ServerPlayer player = context.getSource().getPlayerOrException();
                        String attempt = MessageArgument.getMessage(context, "attempt").getString();
                        rpChatService.sendTry(player, attempt, java.util.concurrent.ThreadLocalRandom.current().nextBoolean());
                        return 1;
                    }))
            );

            dispatcher.register(Commands.literal("todo")
                .then(Commands.argument("message", MessageArgument.message())
                    .executes(context -> {
                        ServerPlayer player = context.getSource().getPlayerOrException();
                        String msg = MessageArgument.getMessage(context, "message").getString();
                        if (!msg.contains("*")) {
                            player.sendSystemMessage(Component.literal("Использование: /todo [реплика] * [действие]"));
                            return 1;
                        }
                        String[] parts = msg.split("\\*", 2);
                        String speech = parts[0].trim();
                        String act = parts[1].trim();
                        if (speech.isEmpty() || act.isEmpty()) {
                            player.sendSystemMessage(Component.literal("В /todo должны быть и реплика, и действие."));
                            return 1;
                        }
                        rpChatService.sendTodo(player, speech, act);
                        return 1;
                    }))
            );

            dispatcher.register(Commands.literal("b")
                .then(Commands.argument("message", MessageArgument.message())
                    .executes(context -> {
                        ServerPlayer player = context.getSource().getPlayerOrException();
                        String msg = MessageArgument.getMessage(context, "message").getString();
                        rpChatService.sendOoc(player, msg);
                        return 1;
                    }))
            );

            dispatcher.register(Commands.literal("w")
                .then(Commands.argument("message", MessageArgument.message())
                    .executes(context -> {
                        ServerPlayer player = context.getSource().getPlayerOrException();
                        String msg = MessageArgument.getMessage(context, "message").getString();
                        rpChatService.sendSpeech(player, RpChatChannel.WHISPER, msg);
                        return 1;
                    }))
            );

            dispatcher.register(Commands.literal("whisper")
                .then(Commands.argument("message", MessageArgument.message())
                    .executes(context -> {
                        ServerPlayer player = context.getSource().getPlayerOrException();
                        String msg = MessageArgument.getMessage(context, "message").getString();
                        rpChatService.sendSpeech(player, RpChatChannel.WHISPER, msg);
                        return 1;
                    }))
            );

            dispatcher.register(Commands.literal("s")
                .then(Commands.argument("message", MessageArgument.message())
                    .executes(context -> {
                        ServerPlayer player = context.getSource().getPlayerOrException();
                        String msg = MessageArgument.getMessage(context, "message").getString();
                        rpChatService.sendSpeech(player, RpChatChannel.SHOUT, msg);
                        return 1;
                    }))
            );

            dispatcher.register(Commands.literal("shout")
                .then(Commands.argument("message", MessageArgument.message())
                    .executes(context -> {
                        ServerPlayer player = context.getSource().getPlayerOrException();
                        String msg = MessageArgument.getMessage(context, "message").getString();
                        rpChatService.sendSpeech(player, RpChatChannel.SHOUT, msg);
                        return 1;
                    }))
            );

            dispatcher.register(Commands.literal("say")
                .then(Commands.argument("message", MessageArgument.message())
                    .executes(context -> {
                        ServerPlayer player = context.getSource().getPlayerOrException();
                        String msg = MessageArgument.getMessage(context, "message").getString();
                        rpChatService.sendSpeech(player, RpChatChannel.SPEAK, msg);
                        return 1;
                    }))
            );

            dispatcher.register(Commands.literal("rpcombatdebug")
                .requires(source -> RPChat.hasPermission(source, "rpchat.admin", 4))
                .executes(context -> {
                    ServerPlayer player = context.getSource().getPlayerOrException();
                    boolean enabled = combatManager.toggleDebug(player);
                    player.sendSystemMessage(Component.literal(enabled ? "RP combat debug включен." : "RP combat debug выключен."));
                    return 1;
                })
            );

            dispatcher.register(Commands.literal("rpcrun")
                .requires(source -> RPChat.hasPermission(source, "rpchat.admin", 4))
                .then(Commands.argument("player", EntityArgument.player())
                    .then(Commands.argument("mode", StringArgumentType.word())
                      .suggests((context, builder) -> net.minecraft.commands.SharedSuggestionProvider.suggest(
                              new String[]{"me", "do", "try", "todo", "b", "w", "s", "say"}, builder))
                      .then(Commands.argument("text", MessageArgument.message()).executes(context -> {
                        ServerPlayer target = EntityArgument.getPlayer(context, "player");
                        String mode = StringArgumentType.getString(context, "mode");
                        String text = MessageArgument.getMessage(context, "text").getString();
                        return dispatchRpCommand(target, mode, text) ? 1 : 0;
                    })))
                )
            );

            dispatcher.register(Commands.literal("rpdemo")
                .executes(context -> {
                    sendDemo(context.getSource());
                    return 1;
                })
                .then(Commands.literal("select")
                    .requires(source -> RPChat.hasPermission(source, "rpchat.admin", 4))
                    .then(Commands.argument("style", IntegerArgumentType.integer(1, 10))
                        .executes(context -> {
                            int style = IntegerArgumentType.getInteger(context, "style");
                            setActiveStyle(style);
                            context.getSource().sendSuccess(() -> Component.literal(
                                    "Стиль чату встановлено: " + ChatFormatter.STYLE_NAMES[style - 1]), true);
                            return 1;
                        })))
            );

            dispatcher.register(Commands.literal("rpreload")
                .requires(source -> RPChat.hasPermission(source, "rpchat.admin", 4))
                .executes(context -> {
                    String report = reloadConfiguration();
                    context.getSource().sendSuccess(() -> Component.literal(report), true);
                    return 1;
                })
            );

            dispatcher.register(Commands.literal("heavyhammer")
                .requires(source -> RPChat.hasPermission(source, "rpchat.heavyhammer.give", 4))
                .then(Commands.argument("player", EntityArgument.player())
                    .executes(context -> {
                        ServerPlayer target = EntityArgument.getPlayer(context, "player");
                        heavyHammerManager.giveHammer(target, context.getSource().getPlayerOrException());
                        return 1;
                    }))
            );

            dispatcher.register(Commands.literal("microvoxel")
                .requires(source -> RPChat.hasPermission(source, "rpchat.microvoxels.edit", 2))
                .then(Commands.literal("convert")
                    .executes(context -> {
                        ServerPlayer player = context.getSource().getPlayerOrException();
                        microvoxelManager.handleAction(player, MicrovoxelProtocol.ACTION_CONVERT, 0, 0, 0, 0, 0, Vec3.ZERO, Vec3.ZERO);
                        return 1;
                    })
                )
                .then(Commands.literal("restore")
                    .executes(context -> microvoxelManager.restoreLookedAt(
                            context.getSource().getPlayerOrException()) ? 1 : 0))
                .then(Commands.literal("status")
                    .executes(context -> {
                        context.getSource().sendSuccess(() -> Component.literal(microvoxelManager.status()), false);
                        return 1;
                    }))
            );
        });

        logger.info("RPChat has been successfully enabled! Active chat style: " + ChatFormatter.STYLE_NAMES[activeStyle - 1]);
    }

    private void saveDefaultConfig() {
        File configFile = new File(getDataFolder(), "config.yml");
        if (configFile.exists()) return;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("config.yml")) {
            if (in == null) return;
            try (FileOutputStream out = new FileOutputStream(configFile)) {
                in.transferTo(out);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void extractWebAssets() {
        File webFolder = new File(getDataFolder(), "web");
        if (!webFolder.exists()) {
            webFolder.mkdirs();
        }
        String[] files = {
                "index.html", "style.css", "app.js", "body.html", "body.css", "body.js",
                "assets/body-ui.css", "assets/body-ui.js", "appearance.html", "appearance.css",
                "appearance.js", "bg_village.jpg"
        };
        for (String filename : files) {
            File output = new File(webFolder, filename);
            boolean managed = filename.startsWith("appearance.");
            if (!managed && output.exists()) continue;
            File parent = output.getParentFile();
            if (parent != null) parent.mkdirs();
            try (InputStream input = getClass().getClassLoader().getResourceAsStream("web/" + filename)) {
                if (input == null) throw new IllegalStateException("Missing bundled web asset: " + filename);
                Files.copy(input, output.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception e) {
                throw new IllegalStateException("Unable to extract web asset " + filename, e);
            }
        }
    }

    private void migrateLegacyDataFolder(File target) {
        File legacy = new File("plugins/RPChat");
        if (!legacy.isDirectory()) return;
        File marker = new File(target, ".legacy-migration-complete");
        if (marker.isFile()) return;
        try (var paths = Files.walk(legacy.toPath())) {
            paths.filter(source -> {
                var relative = legacy.toPath().relativize(source);
                if (relative.getNameCount() == 0) return true;
                String topLevel = relative.getName(0).toString();
                return !topLevel.startsWith("client.pre-sync-")
                        && !topLevel.startsWith("client.failed-");
            }).forEach(source -> {
                try {
                    var relative = legacy.toPath().relativize(source);
                    var destination = target.toPath().resolve(relative);
                    if (Files.isDirectory(source)) {
                        Files.createDirectories(destination);
                    } else if (!Files.exists(destination)) {
                        Files.createDirectories(destination.getParent());
                        Files.copy(source, destination, StandardCopyOption.COPY_ATTRIBUTES);
                    }
                } catch (java.io.IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
            Files.createDirectories(target.toPath());
            Files.writeString(marker.toPath(), "Migrated from plugins/RPChat; legacy source preserved.\n",
                    java.nio.charset.StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE_NEW);
            logger.info("Legacy RPChat data is available under Fabric config/RPChat (source preserved).");
        } catch (java.io.IOException | UncheckedIOException e) {
            throw new IllegalStateException("Unable to migrate plugins/RPChat to config/RPChat", e);
        }
    }

    public void onDisable() {
        if (staminaManager != null) {
            staminaManager.shutdown();
        }
        if (acquaintanceManager != null) {
            acquaintanceManager.shutdown();
        }
        if (authWebServer != null) {
            authWebServer.stop();
        }
        if (heavyHammerManager != null) heavyHammerManager.shutdown();
        if (microvoxelManager != null) microvoxelManager.shutdown();
        if (authDatabase != null) {
            authDatabase.disconnect();
        }
        logger.info("RPChat has been disabled.");
    }

    private String activeOrNewToken(ServerPlayer player) {
        String token = authManager.getActiveToken(player.getUUID());
        if (token != null) {
            return token;
        }
        token = java.util.UUID.randomUUID().toString().substring(0, 8);
        authManager.getTokenToUuid().put(token, player.getUUID());
        return token;
    }

    public int getActiveStyle() {
        return activeStyle;
    }

    public void setActiveStyle(int style) {
        if (style >= 1 && style <= 10) {
            this.activeStyle = style;
            if (config != null) config.setInt("active-style", style);
        }
    }

    private String reloadConfiguration() {
        config = new SimpleConfig(new File(getDataFolder(), "config.yml"));
        int style = config.getInt("active-style", 1);
        activeStyle = Math.max(1, Math.min(10, style));
        boolean combatEnabled = config.getBoolean("combat.enabled", true);
        return "Конфігурацію EclipseServer застосовано: стиль " + activeStyle
                + ", RP-бій " + (combatEnabled ? "увімкнено" : "вимкнено")
                + ". web.port і код мода змінюються після рестарту Fabric.";
    }

    private void sendDemo(net.minecraft.commands.CommandSourceStack source) {
        source.sendSystemMessage(Component.literal("RP Chat: дистанційна система мовлення"));
        source.sendSystemMessage(Component.literal("Звичайна мова /say — 24 блоки; /w — 7; /s — 48."));
        source.sendSystemMessage(Component.literal("/me, /do, /try, /todo, /b — локальні RP/OOC дії."));
    }

    private boolean dispatchRpCommand(ServerPlayer player, String mode, String text) {
        switch (mode.toLowerCase(Locale.ROOT)) {
            case "me" -> rpChatService.sendAction(player, text);
            case "do" -> rpChatService.sendDescription(player, text);
            case "try" -> rpChatService.sendTry(player, text,
                    java.util.concurrent.ThreadLocalRandom.current().nextBoolean());
            case "todo" -> {
                String[] parts = text.split("\\*", 2);
                if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
                    player.sendSystemMessage(Component.literal("Використання: todo [репліка] * [дія]"));
                    return false;
                }
                rpChatService.sendTodo(player, parts[0].trim(), parts[1].trim());
            }
            case "b" -> rpChatService.sendOoc(player, text);
            case "w", "whisper" -> rpChatService.sendSpeech(player, RpChatChannel.WHISPER, text);
            case "s", "shout" -> rpChatService.sendSpeech(player, RpChatChannel.SHOUT, text);
            case "say" -> rpChatService.sendSpeech(player, RpChatChannel.SPEAK, text);
            default -> {
                player.sendSystemMessage(Component.literal("Невідомий RP-режим: " + mode));
                return false;
            }
        }
        return true;
    }

    public AuthManager getAuthManager() {
        return authManager;
    }

    public AppearanceManager getAppearanceManager() {
        return appearanceManager;
    }

    public StaminaManager getStaminaManager() {
        return staminaManager;
    }

    public RpChatService getRpChatService() {
        return rpChatService;
    }

    public CombatManager getCombatManager() {
        return combatManager;
    }

    public AcquaintanceManager getAcquaintanceManager() {
        return acquaintanceManager;
    }

    public MicrovoxelManager getMicrovoxelManager() {
        return microvoxelManager;
    }

    private boolean isPendingAuth(ServerPlayer player) {
        return authManager != null && authManager.isPendingAuth(player.getUUID());
    }

    public static boolean hasPermission(ServerPlayer player, int level) {
        return player != null && player.permissions().hasPermission(permissionForLevel(level));
    }

    public static boolean hasPermission(net.minecraft.commands.CommandSourceStack source, int level) {
        return source != null && source.permissions().hasPermission(permissionForLevel(level));
    }

    public static boolean hasPermission(ServerPlayer player, String node, int fallbackLevel) {
        if (player == null) return false;
        return ((net.fabricmc.fabric.api.permission.v1.PermissionContextOwner) (Object) player)
                .checkPermission(permissionId(node), net.minecraft.server.permissions.PermissionLevel.byId(fallbackLevel));
    }

    public static boolean hasPermission(net.minecraft.commands.CommandSourceStack source, String node, int fallbackLevel) {
        if (source == null) return false;
        return ((net.fabricmc.fabric.api.permission.v1.PermissionContextOwner) (Object) source)
                .checkPermission(permissionId(node), net.minecraft.server.permissions.PermissionLevel.byId(fallbackLevel));
    }

    private static net.minecraft.resources.Identifier permissionId(String node) {
        int separator = node.indexOf('.');
        String namespace = separator > 0 ? node.substring(0, separator) : "rpchat";
        String path = separator > 0 ? node.substring(separator + 1) : node;
        return net.minecraft.resources.Identifier.fromNamespaceAndPath(namespace, path);
    }

    private static net.minecraft.server.permissions.Permission permissionForLevel(int level) {
        if (level >= 4) return Permissions.COMMANDS_OWNER;
        if (level == 3) return Permissions.COMMANDS_ADMIN;
        if (level == 2) return Permissions.COMMANDS_GAMEMASTER;
        return Permissions.COMMANDS_MODERATOR;
    }

    public static boolean teleport(ServerPlayer player, net.minecraft.server.level.ServerLevel level,
                                   double x, double y, double z, float yaw, float pitch) {
        return player != null && level != null
                && player.teleportTo(level, x, y, z, java.util.Set.of(), yaw, pitch, true);
    }
}
