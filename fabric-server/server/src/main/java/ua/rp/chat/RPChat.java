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
import ua.rp.chat.vitals.BloodFootprintService;
import ua.rp.chat.vitals.BloodSurfaceFilmService;
import ua.rp.chat.microvoxel.MicrovoxelManager;
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
    private BloodFootprintService bloodFootprintService;
    private BloodSurfaceFilmService bloodSurfaceFilmService;
    private RpChatService rpChatService;
    private CombatManager combatManager;
    private AcquaintanceManager acquaintanceManager;
    private MicrovoxelManager microvoxelManager;
    private ua.rp.chat.carver.CarverManager carverManager;
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
        ua.rp.chat.microvoxel.MicrovoxelBlocks.register();
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

        registerFluidDispenserBehavior();

        authManager = new AuthManager(this, authDatabase, appearanceManager);
        rpChatService = new RpChatService(this);
        bloodFxService = new BloodFxService(this);
        bloodFootprintService = new BloodFootprintService(this);
        bloodSurfaceFilmService = new BloodSurfaceFilmService(this);
        staminaManager = new StaminaManager(this, bloodFxService);
        staminaManager.start();
        combatManager = new CombatManager(this);
        acquaintanceManager = new AcquaintanceManager(this);
        acquaintanceManager.start();
        microvoxelManager = new MicrovoxelManager(this);
        ua.rp.chat.carver.CarverItems.register();
        carverManager = new ua.rp.chat.carver.CarverManager(
                this, microvoxelManager, staminaManager);
        carverManager.reloadTuning();
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
        serverbound.register(ua.rp.chat.client.microvoxel.MicrovoxelBatchPayload.TYPE, ua.rp.chat.client.microvoxel.MicrovoxelBatchPayload.CODEC);
        serverbound.register(ua.rp.chat.client.pickup.ItemPickupPayload.TYPE, ua.rp.chat.client.pickup.ItemPickupPayload.CODEC);
        serverbound.register(ua.rp.chat.client.blood.BloodFootprintPayload.TYPE, ua.rp.chat.client.blood.BloodFootprintPayload.CODEC);
        serverbound.register(ua.rp.chat.client.blood.BloodSurfacePayload.TYPE, ua.rp.chat.client.blood.BloodSurfacePayload.CODEC);
        serverbound.register(ua.rp.chat.client.carver.CarverActionPayload.TYPE, ua.rp.chat.client.carver.CarverActionPayload.CODEC);

        var clientbound = net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.clientboundPlay();
        clientbound.register(ua.rp.chat.client.AuthPayload.TYPE, ua.rp.chat.client.AuthPayload.CODEC);
        clientbound.register(ua.rp.chat.client.AcquaintancePayload.TYPE, ua.rp.chat.client.AcquaintancePayload.CODEC);
        clientbound.register(ua.rp.chat.client.AppearanceRefreshPayload.TYPE, ua.rp.chat.client.AppearanceRefreshPayload.CODEC);
        clientbound.register(ua.rp.chat.client.rpfeed.RpChatFeedPayload.TYPE, ua.rp.chat.client.rpfeed.RpChatFeedPayload.CODEC);
        clientbound.register(ua.rp.chat.client.microvoxel.MicrovoxelSyncPayload.TYPE, ua.rp.chat.client.microvoxel.MicrovoxelSyncPayload.CODEC);
        clientbound.register(ua.rp.chat.client.blood.BloodFxPayload.TYPE, ua.rp.chat.client.blood.BloodFxPayload.CODEC);
        clientbound.register(ua.rp.chat.client.blood.BloodFootprintPayload.TYPE, ua.rp.chat.client.blood.BloodFootprintPayload.CODEC);
        clientbound.register(ua.rp.chat.client.blood.BloodSurfacePayload.TYPE, ua.rp.chat.client.blood.BloodSurfacePayload.CODEC);
        clientbound.register(ua.rp.chat.client.carver.CarverSyncPayload.TYPE, ua.rp.chat.client.carver.CarverSyncPayload.CODEC);

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
            microvoxelManager.tick();
            carverManager.tick();
            bloodFootprintService.tick();
            bloodSurfaceFilmService.tick();
            if (tickCounter % 1200 == 0) carverManager.reloadTuning();
        });

        // Register Auth Listener callbacks
        AuthListener authListener = new AuthListener(authManager);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, srv) -> {
            ServerPlayer player = handler.player;
            authListener.onJoin(player);
            
            microvoxelManager.onJoin(player);
            staminaManager.syncBloodFxTo(player);
            bloodFootprintService.onJoin(player);
            bloodSurfaceFilmService.onJoin(player);
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, srv) -> {
            ServerPlayer player = handler.player;
            authListener.onQuit(player);
            staminaManager.onQuit(player);
            
            microvoxelManager.onQuit(player);
            carverManager.onQuit(player);
            bloodFootprintService.onQuit(player);
            bloodSurfaceFilmService.onQuit(player);
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
            if (isPendingAuth(player)
                    && !MicrovoxelProtocol.isSynchronizationAction(payload.action())) return;
            microvoxelManager.handleAction(player, payload.protocolVersion(), payload.transactionId(),
                    payload.action(), payload.x(), payload.y(), payload.z(), payload.cell(), payload.revision(),
                    new net.minecraft.world.phys.Vec3(payload.lookX(), payload.lookY(), payload.lookZ()),
                    new net.minecraft.world.phys.Vec3(payload.eyeX(), payload.eyeY(), payload.eyeZ()));
        });
        // Batched edits: each entry flows through the exact single-action path (auth gate,
        // rate limit, revision and raycast validation per entry), so batching only saves
        // packets and never weakens validation.
        ServerPlayNetworking.registerGlobalReceiver(ua.rp.chat.client.microvoxel.MicrovoxelBatchPayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            ua.rp.chat.microvoxel.MicrovoxelMetrics.inc("net.batch");
            ua.rp.chat.microvoxel.MicrovoxelMetrics.add("net.batch.entries", payload.entries().size());
            net.minecraft.world.phys.Vec3 look = new net.minecraft.world.phys.Vec3(
                    payload.lookX(), payload.lookY(), payload.lookZ());
            net.minecraft.world.phys.Vec3 eye = new net.minecraft.world.phys.Vec3(
                    payload.eyeX(), payload.eyeY(), payload.eyeZ());
            for (ua.rp.chat.client.microvoxel.MicrovoxelBatchPayload.Entry entry : payload.entries()) {
                if (isPendingAuth(player)
                        && !MicrovoxelProtocol.isSynchronizationAction(entry.action())) return;
                microvoxelManager.handleAction(player, payload.protocolVersion(), entry.transactionId(),
                        entry.action(), entry.x(), entry.y(), entry.z(), entry.cell(), entry.revision(),
                        look, eye);
            }
        });
        ServerPlayNetworking.registerGlobalReceiver(ua.rp.chat.client.AcquaintanceActionPayload.TYPE, (payload, context) -> {
            if (!isPendingAuth(context.player())) {
                acquaintanceManager.handleAction(context.player(), payload.action(), payload.targetId(), payload.text());
            }
        });

        ServerPlayNetworking.registerGlobalReceiver(ua.rp.chat.client.pickup.ItemPickupPayload.TYPE, (payload, context) ->
                itemPickupManager.handlePickup(context.player(), payload.itemId()));
        ServerPlayNetworking.registerGlobalReceiver(ua.rp.chat.client.blood.BloodFootprintPayload.TYPE,
                (payload, context) -> {
                    if (!isPendingAuth(context.player())) {
                        bloodFootprintService.handleRequest(context.player(), payload);
                    }
                });
        ServerPlayNetworking.registerGlobalReceiver(ua.rp.chat.client.blood.BloodSurfacePayload.TYPE,
                (payload, context) -> {
                    if (!isPendingAuth(context.player())) {
                        bloodSurfaceFilmService.handle(context.player(), payload);
                    }
                });
        ServerPlayNetworking.registerGlobalReceiver(ua.rp.chat.client.carver.CarverActionPayload.TYPE,
                (payload, context) -> {
                    ServerPlayer actionPlayer = context.player();
                    if (isPendingAuth(actionPlayer)) return;
                    if (payload.protocolVersion() != ua.rp.chat.carver.CarverProtocol.VERSION) {
                        actionPlayer.sendSystemMessage(Component.literal(
                                "Версия клиентского модуля резчика не совместима с сервером."), true);
                        return;
                    }
                    context.server().execute(() -> carverManager.handleAction(actionPlayer,
                            payload.action(), payload.x(), payload.y(), payload.z(), payload.data()));
                });

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
            net.minecraft.world.item.ItemStack held = player.getItemInHand(hand);
            if (microvoxelManager.isPortableVolumeItem(held)) {
                return microvoxelManager.onBlockPlace(serverPlayer, held, placementPos)
                        ? InteractionResult.SUCCESS : InteractionResult.FAIL;
            }
            // Architect's scroll: right-click on a block enters Carver design mode.
            if (held.is(ua.rp.chat.carver.CarverItems.SCROLL)) {
                return carverManager.tryEnterDesign(serverPlayer, hit.getBlockPos())
                        ? InteractionResult.SUCCESS : InteractionResult.FAIL;
            }
            // Voxel fluid UX: a water bucket on a carved basin fills it (custom path, because
            // vanilla flow may never replace the marker), while an empty bucket passes through
            // to the vanilla scoop (the marker reads as water through its fluid state).
            if (held.is(net.minecraft.world.item.Items.WATER_BUCKET)
                    && microvoxelManager.protectsMarker(serverLevel, hit.getBlockPos())) {
                return microvoxelManager.fillWithBucket(
                        serverPlayer, serverLevel, hit.getBlockPos(), hand)
                        ? InteractionResult.SUCCESS : InteractionResult.FAIL;
            }
            // Mob buckets stock wet basins with live fish instead of placing blocks.
            if (microvoxelManager.fishBucketType(held) != null
                    && microvoxelManager.protectsMarker(serverLevel, hit.getBlockPos())) {
                return microvoxelManager.stockWithBucket(
                        serverPlayer, serverLevel, hit.getBlockPos(), hand, held)
                        ? InteractionResult.SUCCESS : InteractionResult.FAIL;
            }
            if (held.is(net.minecraft.world.item.Items.LAVA_BUCKET)
                    && microvoxelManager.protectsMarker(serverLevel, hit.getBlockPos())) {
                return microvoxelManager.fillWithLavaBucket(
                        serverPlayer, serverLevel, hit.getBlockPos(), hand)
                        ? InteractionResult.SUCCESS : InteractionResult.FAIL;
            }
            // Vanilla cannot scoop lava markers (no lava fluidstate to grab), so lava
            // scooping is custom while water scooping stays native below.
            if (held.is(net.minecraft.world.item.Items.BUCKET)
                    && microvoxelManager.protectsMarker(serverLevel, hit.getBlockPos())
                    && microvoxelManager.scoopLavaBucket(
                            serverPlayer, serverLevel, hit.getBlockPos(), hand)) {
                return InteractionResult.SUCCESS;
            }
            if (held.is(net.minecraft.world.item.Items.BUCKET)) {
                return InteractionResult.PASS;
            }
            if (microvoxelManager.isPartiallyConsumedMaterial(held)) {
                serverPlayer.sendSystemMessage(Component.literal(
                        "Этот блок частично израсходован на микровоксели и не может быть установлен целиком."), true);
                return InteractionResult.FAIL;
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
                        carverManager.onDamaged(player);
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

            dispatcher.register(Commands.literal("carver")
                .then(Commands.literal("kit")
                    .executes(context -> {
                        ServerPlayer player = context.getSource().getPlayerOrException();
                        if (authManager.isPendingAuth(player.getUUID())) {
                            player.sendSystemMessage(Component.literal("Спочатку авторизуйтесь."));
                            return 0;
                        }
                        carverManager.giveKit(player);
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

            dispatcher.register(Commands.literal("microvoxel")
                .requires(source -> RPChat.hasPermission(source, "rpchat.microvoxels.edit", 2))
                .then(Commands.literal("convert")
                    .executes(context -> {
                        ServerPlayer player = context.getSource().getPlayerOrException();
                        microvoxelManager.handleAction(player, MicrovoxelProtocol.VERSION, 0L,
                                MicrovoxelProtocol.ACTION_CONVERT, 0, 0, 0, 0, 0,
                                Vec3.ZERO, Vec3.ZERO);
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
                .then(Commands.literal("protect")
                    .executes(context -> microvoxelCommandProtect(context, true)))
                .then(Commands.literal("unprotect")
                    .executes(context -> microvoxelCommandProtect(context, false)))
                .then(Commands.literal("backup")
                    .requires(source -> RPChat.hasPermission(source, "rpchat.admin", 4))
                    .executes(context -> {
                        try {
                            String location = microvoxelManager.backupVolumes();
                            context.getSource().sendSuccess(
                                    () -> Component.literal("Мікровокселі збережено в бекап: " + location), true);
                        } catch (Exception error) {
                            context.getSource().sendFailure(
                                    Component.literal("Бекап не вдався: " + error.getMessage()));
                        }
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
        if (microvoxelManager != null) microvoxelManager.shutdown();
        if (bloodFootprintService != null) bloodFootprintService.shutdown();
        if (bloodSurfaceFilmService != null) bloodSurfaceFilmService.shutdown();
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

    /** Shared handler for /microvoxel protect and /microvoxel unprotect. */
    private int microvoxelCommandProtect(
            com.mojang.brigadier.context.CommandContext<net.minecraft.commands.CommandSourceStack> context,
            boolean protect) {
        try {
            ServerPlayer player = context.getSource().getPlayerOrException();
            return microvoxelManager.protectLookedAt(player, protect) ? 1 : 0;
        } catch (Exception noPlayer) {
            context.getSource().sendFailure(Component.literal("Команда доступна лише гравцю."));
            return 0;
        }
    }

    /**
     * Dispensers participate in voxel fluids without breaking vanilla: facing a carved basin
     * they fill it through the guarded path, otherwise the original water-bucket behavior
     * runs untouched. Registered once at startup, after vanilla bootstrap populated the
     * default behavior we delegate to. Lava buckets get the same treatment.
     */
    private void registerFluidDispenserBehavior() {
        net.minecraft.core.dispenser.DispenseItemBehavior vanilla =
                net.minecraft.world.level.block.DispenserBlock.DISPENSER_REGISTRY.get(
                        net.minecraft.world.item.Items.WATER_BUCKET);
        net.minecraft.core.dispenser.DispenseItemBehavior basinFilling =
                new net.minecraft.core.dispenser.DefaultDispenseItemBehavior() {
                    @Override
                    protected net.minecraft.world.item.ItemStack execute(
                            net.minecraft.core.dispenser.BlockSource pointer,
                            net.minecraft.world.item.ItemStack stack) {
                        net.minecraft.core.Direction facing = pointer.state().getValue(
                                net.minecraft.world.level.block.DispenserBlock.FACING);
                        net.minecraft.core.BlockPos target = pointer.pos().relative(facing);
                        net.minecraft.server.level.ServerLevel level = pointer.level();
                        if (microvoxelManager != null
                                && microvoxelManager.fillFromDispenser(level, target)) {
                            stack.shrink(1);
                            return stack.isEmpty()
                                    ? new net.minecraft.world.item.ItemStack(
                                            net.minecraft.world.item.Items.BUCKET)
                                    : consumeWithRemainder(pointer, stack,
                                            new net.minecraft.world.item.ItemStack(
                                                    net.minecraft.world.item.Items.BUCKET));
                        }
                        return vanilla != null
                                ? vanilla.dispense(pointer, stack)
                                : super.execute(pointer, stack);
                    }
                };
        net.minecraft.world.level.block.DispenserBlock.registerBehavior(
                net.minecraft.world.item.Items.WATER_BUCKET, basinFilling);
        net.minecraft.core.dispenser.DispenseItemBehavior vanillaLava =
                net.minecraft.world.level.block.DispenserBlock.DISPENSER_REGISTRY.get(
                        net.minecraft.world.item.Items.LAVA_BUCKET);
        net.minecraft.core.dispenser.DispenseItemBehavior lavaFilling =
                new net.minecraft.core.dispenser.DefaultDispenseItemBehavior() {
                    @Override
                    protected net.minecraft.world.item.ItemStack execute(
                            net.minecraft.core.dispenser.BlockSource pointer,
                            net.minecraft.world.item.ItemStack stack) {
                        net.minecraft.core.Direction facing = pointer.state().getValue(
                                net.minecraft.world.level.block.DispenserBlock.FACING);
                        net.minecraft.core.BlockPos target = pointer.pos().relative(facing);
                        net.minecraft.server.level.ServerLevel level = pointer.level();
                        if (microvoxelManager != null
                                && microvoxelManager.fillFromDispenser(
                                        level, target,
                                        ua.rp.chat.microvoxel.FluidVolume.Kind.LAVA)) {
                            stack.shrink(1);
                            return stack.isEmpty()
                                    ? new net.minecraft.world.item.ItemStack(
                                            net.minecraft.world.item.Items.BUCKET)
                                    : consumeWithRemainder(pointer, stack,
                                            new net.minecraft.world.item.ItemStack(
                                                    net.minecraft.world.item.Items.BUCKET));
                        }
                        return vanillaLava != null
                                ? vanillaLava.dispense(pointer, stack)
                                : super.execute(pointer, stack);
                    }
                };
        net.minecraft.world.level.block.DispenserBlock.registerBehavior(
                net.minecraft.world.item.Items.LAVA_BUCKET, lavaFilling);
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

    public ua.rp.chat.carver.CarverManager getCarverManager() {
        return carverManager;
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
