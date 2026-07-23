package ua.rp.chat.auth;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.TextColor;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import ua.rp.chat.client.AuthPayload;
import ua.rp.chat.client.AppearanceRefreshPayload;
import ua.rp.chat.RPChat;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class AuthManager {

    private static final TextColor SAND_GOLD = TextColor.fromRgb(0xE3C099);
    private static final TextColor PEBBLE_GRAY = TextColor.fromRgb(0xB0A8A0);
    private static final TextColor SEAFOAM = TextColor.fromRgb(0xA5C3C4);
    private static final TextColor SOFT_GREEN = TextColor.fromRgb(0x99C3A2);
    private static final TextColor TERRACOTTA = TextColor.fromRgb(0xE3A899);
    private static final TextColor DRY_EARTH = TextColor.fromRgb(0x8A827A);
    private static final TextColor WARM_DUST = TextColor.fromRgb(0xAFA69E);

    private final RPChat plugin;
    private final AuthDatabase database;
    private final AppearanceManager appearanceManager;
    private final AuthCameraManager cameraManager;

    private final Set<UUID> pendingAuth = ConcurrentHashMap.newKeySet();
    private final Map<UUID, AuthLocation> originalLocations = new ConcurrentHashMap<>();
    private final Map<String, UUID> tokenToUuid = new ConcurrentHashMap<>();
    private final Map<String, AppearanceEditSession> appearanceEditSessions = new ConcurrentHashMap<>();

    private static final int AUTH_TIMEOUT_SECONDS = 120;
    private static final long APPEARANCE_EDIT_TTL_MS = Duration.ofMinutes(10).toMillis();

    private final List<DelayedTask> delayedTasks = new ArrayList<>();

    private record DelayedTask(int[] ticksLeft, Runnable runnable) {
        public int getTicksLeft() { return ticksLeft[0]; }
        public void decrement() { ticksLeft[0]--; }
    }

    public AuthManager(RPChat plugin, AuthDatabase database, AppearanceManager appearanceManager) {
        this.plugin = plugin;
        this.database = database;
        this.appearanceManager = appearanceManager;
        this.cameraManager = new AuthCameraManager(plugin);
    }

    public void tick() {
        cameraManager.tickCameraLocks();

        List<Runnable> ready = new ArrayList<>();
        synchronized (delayedTasks) {
            Iterator<DelayedTask> it = delayedTasks.iterator();
            while (it.hasNext()) {
                DelayedTask task = it.next();
                task.decrement();
                if (task.getTicksLeft() <= 0) {
                    it.remove();
                    ready.add(task.runnable());
                }
            }
        }
        // Auth callbacks may schedule another callback. Run them after removing
        // completed entries and outside the list lock to avoid iterator invalidation.
        for (Runnable runnable : ready) {
            try {
                runnable.run();
            } catch (Exception e) {
                plugin.getLogger().log(java.util.logging.Level.SEVERE, "Error executing scheduled auth task", e);
            }
        }
    }

    public void schedule(int delayTicks, Runnable runnable) {
        synchronized (delayedTasks) {
            delayedTasks.add(new DelayedTask(new int[]{delayTicks}, runnable));
        }
    }

    public void handleJoin(ServerPlayer player) {
        UUID uuid = player.getUUID();
        String currentIp = getPlayerIp(player);

        if (plugin.getConfig().getBoolean("auth.auto-login-by-ip", false) && database.isRegistered(uuid)) {
            String lastIp = database.getLastIp(uuid);
            if (lastIp != null && lastIp.equals(currentIp)) {
                plugin.getLogger().info("Auto-login for " + player.getGameProfile().name() + " (IP session match)");
                database.updateLogin(uuid, currentIp);
                
                String rpName = database.getRpName(uuid);
                applyRpIdentity(player, rpName);
                broadcastRoleplayJoin(player, rpName);
                sendAutoLoginTitle(player);
                return;
            }
        }

        pendingAuth.add(uuid);
        originalLocations.put(uuid, new AuthLocation(((ServerLevel) player.level()), player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot()));
        player.closeContainer();
        player.setGameMode(GameType.SPECTATOR);

        tokenToUuid.values().removeIf(id -> id.equals(uuid));
        String token = UUID.randomUUID().toString().replace("-", "");
        tokenToUuid.put(token, uuid);

        schedule(5, () -> {
            cameraManager.setupCinematicView(player);
            openAuthOverlay(player, token);
        });

        startTimeout(player);
    }

    public boolean attemptRegistration(ServerPlayer player, String loginName, String password, String email) {
        UUID uuid = player.getUUID();
        if (!pendingAuth.contains(uuid)) {
            return false;
        }

        if (database.isRegistered(uuid) || database.isLoginNameTaken(loginName)) {
            return false;
        }

        String hash = PasswordHasher.hash(password);
        if (database.register(uuid, loginName, loginName, email, hash)) {
            plugin.getLogger().info("[AUTH-AUDIT] registration succeeded; player=" + player.getGameProfile().name() + ", login=" + loginName);
            return true;
        }
        return false;
    }

    public boolean attemptLogin(ServerPlayer player, String loginName, String password, boolean rememberDevice) {
        UUID uuid = player.getUUID();
        if (!pendingAuth.contains(uuid)) {
            return false;
        }

        AuthDatabase.PlayerAccount account = database.getAccountByLoginName(loginName);
        if (account == null) {
            plugin.getLogger().info("[AUTH-AUDIT] login rejected: account not found; player=" + player.getGameProfile().name() + ", login=" + loginName);
            return false;
        }

        String storedHash = account.passwordHash();
        if (storedHash == null) {
            plugin.getLogger().warning("[AUTH-AUDIT] login rejected: account has no password hash; player=" + player.getGameProfile().name() + ", login=" + loginName);
            return false;
        }

        if (PasswordHasher.verify(password, storedHash)) {
            if (!account.uuid().equals(uuid) && !database.rebindAccountUuid(account.loginName(), uuid)) {
                plugin.getLogger().warning("Could not rebind account " + account.loginName() + " to current uuid " + uuid);
                return false;
            }
            plugin.getLogger().info("[AUTH-AUDIT] credential verification succeeded; player=" + player.getGameProfile().name() + ", login=" + loginName);
            database.updateLogin(uuid, rememberDevice ? getPlayerIp(player) : null);
            
            String rpName = account.rpName();
            plugin.getServer().execute(() -> {
                applyRpIdentity(player, rpName);
                completeAuth(player);
                broadcastRoleplayJoin(player, rpName);
            });
            return true;
        }
        plugin.getLogger().info("[AUTH-AUDIT] credential verification failed; player=" + player.getGameProfile().name() + ", login=" + loginName);
        return false;
    }

    public void applyRpIdentity(ServerPlayer player, String rpName) {
        if (player == null || rpName == null) return;
        
        removeRpIdentity(player);

        

        net.minecraft.world.scores.Scoreboard scoreboard = plugin.getServer().getScoreboard();
        String teamName = player.getGameProfile().name();
        net.minecraft.world.scores.PlayerTeam team = scoreboard.getPlayerTeam(teamName);
        if (team == null) {
            team = scoreboard.addPlayerTeam(teamName);
        }
        team.setNameTagVisibility(net.minecraft.world.scores.Team.Visibility.NEVER);
        scoreboard.addPlayerToTeam(player.getGameProfile().name(), team);
    }

    public void removeRpIdentity(ServerPlayer player) {
        if (player == null) return;
        UUID uuid = player.getUUID();

        

        net.minecraft.world.scores.Scoreboard scoreboard = plugin.getServer().getScoreboard();
        String teamName = player.getGameProfile().name();
        net.minecraft.world.scores.PlayerTeam team = scoreboard.getPlayerTeam(teamName);
        if (team != null) {
            scoreboard.removePlayerTeam(team);
        }
    }

    private void completeAuth(ServerPlayer player) {
        UUID uuid = player.getUUID();
        pendingAuth.remove(uuid);

        cameraManager.cleanup(player);

        tokenToUuid.values().removeIf(id -> id.equals(uuid));

        schedule(2, () -> {
            if (player.connection == null) return;

            player.setGameMode(GameType.SURVIVAL);

            AuthLocation original = originalLocations.remove(uuid);
            if (original != null) {
                RPChat.teleport(player, original.level(), original.x(), original.y(), original.z(), original.yaw(), original.pitch());
            }

            sendWelcomeTitle(player);
        });
    }

    private void broadcastRoleplayJoin(ServerPlayer player, String rpName) {
        if (player == null) {
            return;
        }
        String name = rpName != null && !rpName.isBlank() ? rpName : player.getGameProfile().name();
        int style = plugin.getActiveStyle();
        plugin.getServer().getPlayerList().broadcastSystemMessage(ua.rp.chat.ChatFormatter.formatJoin(name, style), false);
    }

    public void broadcastRoleplayQuit(ServerPlayer player) {
        if (player == null || pendingAuth.contains(player.getUUID())) {
            return;
        }
        String rpName = database.getRpName(player.getUUID());
        String name = rpName != null && !rpName.isBlank() ? rpName : player.getGameProfile().name();
        plugin.getServer().getPlayerList().broadcastSystemMessage(ua.rp.chat.ChatFormatter.formatQuit(name, plugin.getActiveStyle()), false);
    }

    public void openAuthOverlay(ServerPlayer player, String token) {
        String finalLink = getAuthUrl(token, player.getGameProfile().name());
        sendAuthPayload(player, finalLink);

        schedule(20, () -> {
            if (player.connection != null && pendingAuth.contains(player.getUUID())) {
                sendAuthPayload(player, finalLink);
            }
        });

        schedule(60, () -> {
            if (player.connection != null && pendingAuth.contains(player.getUUID())) {
                sendAuthPayload(player, finalLink);
            }
        });
    }

    private void sendAuthPayload(ServerPlayer player, String finalLink) {
        if (player == null || player.connection == null) return;
        ServerPlayNetworking.send(player, new AuthPayload(finalLink));
        plugin.getLogger().info("Sent Eclipse auth overlay trigger to " + player.getGameProfile().name() + ": " + finalLink);
    }

    public String getAuthUrl(String token) {
        return advertisedWebUrl() + "/auth?token=" + token;
    }

    public String getAuthUrl(String token, String username) {
        String encodedName = java.net.URLEncoder.encode(username == null ? "" : username, java.nio.charset.StandardCharsets.UTF_8);
        return advertisedWebUrl() + "/auth?token=" + token + "&username=" + encodedName;
    }

    public boolean requestAppearanceChange(ServerPlayer player) {
        if (player == null || player.connection == null || pendingAuth.contains(player.getUUID())) {
            return false;
        }
        UUID uuid = player.getUUID();
        if (!database.isRegistered(uuid)) {
            return false;
        }

        long now = System.currentTimeMillis();
        appearanceEditSessions.entrySet().removeIf(entry -> entry.getValue().expiredAtMs() <= now
                || entry.getValue().uuid().equals(uuid));
        String token = UUID.randomUUID().toString().replace("-", "");
        appearanceEditSessions.put(token, new AppearanceEditSession(uuid, now + APPEARANCE_EDIT_TTL_MS));
        String url = advertisedWebUrl() + "/appearance?token=" + token + "&username="
                + java.net.URLEncoder.encode(player.getGameProfile().name(), java.nio.charset.StandardCharsets.UTF_8);
        sendAuthPayload(player, url);
        player.sendSystemMessage(Component.literal("Майстерню зовнішності відкрито.")
                .withStyle(s -> s.withColor(PEBBLE_GRAY))
                .append(Component.literal(" [Відкрити у браузері]")
                        .withStyle(s -> s.withColor(SEAFOAM)
                                .withUnderlined(true)
                                .withClickEvent(new ClickEvent.OpenUrl(java.net.URI.create(url)))
                                .withHoverEvent(new HoverEvent.ShowText(Component.literal("Відкрити майстерню зовнішності"))))));
        return true;
    }

    public boolean requestAppearanceChangeByToken(String sessionToken) {
        AppearanceEditSession session = appearanceEditSessions.remove(sessionToken);
        if (session == null || session.expiredAtMs() <= System.currentTimeMillis()) {
            return false;
        }
        ServerPlayer player = plugin.getServer().getPlayerList().getPlayer(session.uuid());
        if (player == null || player.connection == null) {
            return false;
        }

        String token = UUID.randomUUID().toString().replace("-", "");
        tokenToUuid.put(token, player.getUUID());

        plugin.getServer().execute(() -> {
            pendingAuth.add(player.getUUID());
            originalLocations.put(player.getUUID(), new AuthLocation(((ServerLevel) player.level()), player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot()));
            player.setGameMode(GameType.SPECTATOR);
            cameraManager.setupCinematicView(player);
            openAuthOverlay(player, token);
        });
        return true;
    }

    public boolean completeAppearanceChange(String token, String model, String dataUrl) {
        UUID uuid = tokenToUuid.get(token);
        if (uuid == null) {
            return false;
        }
        ServerPlayer player = plugin.getServer().getPlayerList().getPlayer(uuid);
        if (player == null || player.connection == null || !pendingAuth.contains(uuid)) {
            return false;
        }

        AppearanceManager.SaveResult result = appearanceManager.saveAppearance(uuid, model, dataUrl);
        if (result.success()) {
            plugin.getServer().execute(() -> {
                completeAuth(player);
                notifyAppearanceChanged(uuid);
            });
            return true;
        }
        return false;
    }

    private void notifyAppearanceChanged(UUID uuid) {
        ServerPlayer owner = plugin.getServer().getPlayerList().getPlayer(uuid);
        if (owner != null && owner.connection != null) {
            sendAppearanceRefreshPayload(owner, uuid);
            owner.sendSystemMessage(Component.literal("Внешность персонажа сохранена и применена.").withStyle(s -> s.withColor(SOFT_GREEN)));
        }
        for (ServerPlayer viewer : plugin.getServer().getPlayerList().getPlayers()) {
            if (!viewer.getUUID().equals(uuid)) {
                sendAppearanceRefreshPayload(viewer, uuid);
            }
        }
    }

    private void sendAppearanceRefreshPayload(ServerPlayer player, UUID changedUuid) {
        if (player == null || player.connection == null) return;
        ServerPlayNetworking.send(player, new AppearanceRefreshPayload(changedUuid.toString()));
    }

    private String advertisedWebUrl() {
        String webUrl = plugin.getConfig().getString("web.url", "http://192.168.0.241:25580");
        if (webUrl == null || webUrl.isBlank()) {
            webUrl = "http://192.168.0.241:25580";
        }
        return webUrl.replaceAll("/+$", "");
    }

    public String getActiveToken(UUID uuid) {
        for (Map.Entry<String, UUID> entry : tokenToUuid.entrySet()) {
            if (entry.getValue().equals(uuid)) {
                return entry.getKey();
            }
        }
        return null;
    }

    public String getActiveAuthUrl(ServerPlayer player) {
        if (player == null) {
            return null;
        }
        String token = getActiveToken(player.getUUID());
        return token == null ? null : getAuthUrl(token, player.getGameProfile().name());
    }

    private void sendAuthMenu(ServerPlayer player, String finalLink) {
        player.sendSystemMessage(Component.empty());
        player.sendSystemMessage(Component.literal("  ━━━━━━━━━━━ [ ECLIPSE ROLEPLAY ] ━━━━━━━━━━━").withStyle(s -> s.withColor(DRY_EARTH)));
        player.sendSystemMessage(Component.empty());
        player.sendSystemMessage(Component.literal("     Для входа на сервер необходимо авторизоваться.").withStyle(s -> s.withColor(PEBBLE_GRAY)));
        player.sendSystemMessage(Component.literal("     Пожалуйста, нажмите на кнопку ниже:").withStyle(s -> s.withColor(PEBBLE_GRAY)));
        player.sendSystemMessage(Component.empty());
        player.sendSystemMessage(
            Component.literal("     [ КЛІКНІТЬ ТУТ ЩОБ ВІДКРИТИ ВІКНО ВХОДУ ]")
                .withStyle(s -> s.withColor(SEAFOAM)
                    .withBold(true)
                    .withClickEvent(new ClickEvent.OpenUrl(java.net.URI.create(finalLink)))
                    .withHoverEvent(new HoverEvent.ShowText(Component.literal("Клацніть для переходу на веб-інтерфейс").withStyle(h -> h.withColor(WARM_DUST))))
                )
        );
        player.sendSystemMessage(Component.empty());
        player.sendSystemMessage(Component.literal("  ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━").withStyle(s -> s.withColor(DRY_EARTH)));

        sendTitle(player,
            Component.literal("ПОТРІБНА АВТОРИЗАЦІЯ").withStyle(s -> s.withColor(SAND_GOLD).withBold(true)),
            Component.literal("Посилання відправлено в чат (Натисніть T)").withStyle(s -> s.withColor(PEBBLE_GRAY)),
            6, 200, 10
        );
    }

    public void sendAuthMenu(ServerPlayer player) {
        String token = getActiveToken(player.getUUID());
        if (token != null) {
            sendAuthMenu(player, getAuthUrl(token, player.getGameProfile().name()));
        }
    }

    private void sendAutoLoginTitle(ServerPlayer player) {
        String rpName = database.getRpName(player.getUUID());
        sendTitle(player,
            Component.literal("З поверненням").withStyle(s -> s.withColor(SAND_GOLD)),
            Component.literal(rpName != null ? rpName : player.getGameProfile().name()).withStyle(s -> s.withColor(PEBBLE_GRAY)),
            4, 40, 10
        );
    }

    private void sendWelcomeTitle(ServerPlayer player) {
        String rpName = database.getRpName(player.getUUID());
        sendTitle(player,
            Component.literal("Добро пожаловать").withStyle(s -> s.withColor(SAND_GOLD)),
            Component.literal(rpName != null ? rpName : player.getGameProfile().name()).withStyle(s -> s.withColor(PEBBLE_GRAY)),
            6, 60, 16
        );
    }

    private void sendTitle(ServerPlayer player, Component title, Component subtitle, int fadeInTicks, int stayTicks, int fadeOutTicks) {
        if (player == null || player.connection == null) return;
        player.connection.send(new ClientboundSetTitlesAnimationPacket(fadeInTicks, stayTicks, fadeOutTicks));
        if (title != null) {
            player.connection.send(new ClientboundSetTitleTextPacket(title));
        }
        if (subtitle != null) {
            player.connection.send(new ClientboundSetSubtitleTextPacket(subtitle));
        }
    }

    private void startTimeout(ServerPlayer player) {
        UUID uuid = player.getUUID();
        schedule(AUTH_TIMEOUT_SECONDS * 20, () -> {
            if (pendingAuth.contains(uuid) && player.connection != null) {
                cameraManager.cleanup(player);
                player.connection.disconnect(
                    Component.empty()
                        .append(Component.literal("Время авторизации истекло").withStyle(s -> s.withColor(TERRACOTTA)))
                        .append(Component.literal("\n"))
                        .append(Component.literal("Подключитесь повторно и пройдите авторизацию.").withStyle(s -> s.withColor(PEBBLE_GRAY)))
                );
                pendingAuth.remove(uuid);
                originalLocations.remove(uuid);
                tokenToUuid.values().removeIf(id -> id.equals(uuid));
            }
        });
    }

    public boolean isPendingAuth(UUID uuid) {
        return pendingAuth.contains(uuid);
    }

    public void handleQuit(ServerPlayer player) {
        UUID uuid = player.getUUID();
        plugin.getServer().execute(() -> {
            ServerPlayer current = plugin.getServer().getPlayerList().getPlayer(uuid);
            if (current != null && current != player && current.connection != null) {
                plugin.getLogger().info("Preserved replacement auth session for " + current.getGameProfile().name());
                cameraManager.cleanup(player);
                removeRpIdentity(player);
                return;
            }

            pendingAuth.remove(uuid);
            originalLocations.remove(uuid);
            cameraManager.cleanup(player);
            removeRpIdentity(player);
            tokenToUuid.values().removeIf(id -> id.equals(uuid));
        });
    }

    public String getRpName(UUID uuid) {
        return database.getRpName(uuid);
    }

    public String getLoginName(UUID uuid) {
        return database.getLoginName(uuid);
    }

    public Map<String, UUID> getTokenToUuid() {
        return tokenToUuid;
    }

    private record AppearanceEditSession(UUID uuid, long expiredAtMs) {}

    public AuthDatabase getDatabase() {
        return database;
    }

    public AppearanceManager getAppearanceManager() {
        return appearanceManager;
    }

    public AuthCameraManager getCameraManager() {
        return cameraManager;
    }

    private String getPlayerIp(ServerPlayer player) {
        if (player == null || player.connection == null) return null;
        java.net.SocketAddress addr = player.connection.getRemoteAddress();
        if (addr instanceof InetSocketAddress isa) {
            return isa.getAddress().getHostAddress();
        }
        return null;
    }

    public boolean webLogin(UUID uuid, String loginName, String password, boolean rememberDevice) {
        ServerPlayer player = plugin.getServer().getPlayerList().getPlayer(uuid);
        if (player == null || !pendingAuth.contains(uuid)) {
            plugin.getLogger().warning("[AUTH-AUDIT] login rejected: player/session is no longer pending; login=" + loginName);
            return false;
        }

        AuthDatabase.PlayerAccount account = database.getAccountByLoginName(loginName);
        if (account == null) {
            plugin.getLogger().info("[AUTH-AUDIT] login rejected: account not found; player=" + player.getScoreboardName() + ", login=" + loginName);
            return false;
        }

        String storedHash = account.passwordHash();
        if (storedHash == null) {
            plugin.getLogger().warning("[AUTH-AUDIT] login rejected: account has no password hash; player=" + player.getScoreboardName() + ", login=" + loginName);
            return false;
        }

        if (PasswordHasher.verify(password, storedHash)) {
            if (!account.uuid().equals(uuid) && !database.rebindAccountUuid(account.loginName(), uuid)) {
                plugin.getLogger().warning("Could not rebind account " + account.loginName() + " to current uuid " + uuid);
                return false;
            }
            plugin.getLogger().info("[AUTH-AUDIT] credential verification succeeded; player=" + player.getScoreboardName() + ", login=" + loginName);
            database.updateLogin(uuid, rememberDevice ? getPlayerIp(player) : null);
            
            String rpName = account.rpName();
            plugin.getServer().execute(() -> {
                applyRpIdentity(player, rpName);
                completeAuth(player);
                broadcastRoleplayJoin(player, rpName);
            });
            return true;
        }
        plugin.getLogger().info("[AUTH-AUDIT] credential verification failed; player=" + player.getScoreboardName() + ", login=" + loginName);
        return false;
    }

    public boolean webRegister(UUID uuid, String loginName, String rpName, String email, String password, String appearanceDataUrl, String appearanceModel) {
        ServerPlayer player = plugin.getServer().getPlayerList().getPlayer(uuid);
        if (player == null || !pendingAuth.contains(uuid)) {
            return false;
        }

        if (database.isRegistered(uuid)) {
            return false;
        }

        if (database.isLoginNameTaken(loginName)) {
            return false;
        }

        if (database.isRpNameTaken(rpName)) {
            plugin.getLogger().warning("RP name already taken: " + rpName);
            return false;
        }

        if (appearanceDataUrl == null || appearanceDataUrl.isBlank()) {
            plugin.getLogger().warning("Registration rejected for " + player.getScoreboardName() + ": missing required appearance.");
            return false;
        }

        AppearanceManager.SaveResult validation = appearanceManager.validateAppearance(appearanceDataUrl);
        if (!validation.success()) {
            plugin.getLogger().warning("Appearance validation failed for " + player.getScoreboardName() + ": " + validation.message());
            return false;
        }

        String hash = PasswordHasher.hash(password);
        if (database.register(uuid, loginName, rpName, email, hash)) {
            AppearanceManager.SaveResult appearanceResult = appearanceManager.saveAppearance(uuid, appearanceModel, appearanceDataUrl);
            if (!appearanceResult.success()) {
                plugin.getLogger().warning("Appearance upload failed for " + player.getScoreboardName() + ": " + appearanceResult.message());
                database.deleteAccount(uuid);
                return false;
            }
            database.updateLogin(uuid, null);
            
            plugin.getServer().execute(() -> {
                applyRpIdentity(player, rpName);
                completeAuth(player);
                broadcastRoleplayJoin(player, rpName);
            });
            return true;
        }
        return false;
    }

    public UUID getAppearanceEditOwner(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        AppearanceEditSession session = appearanceEditSessions.get(token);
        if (session == null || session.expiredAtMs() <= System.currentTimeMillis()) {
            appearanceEditSessions.remove(token);
            return null;
        }
        ServerPlayer player = plugin.getServer().getPlayerList().getPlayer(session.uuid());
        if (player == null || player.connection == null || pendingAuth.contains(session.uuid()) || !database.isRegistered(session.uuid())) {
            appearanceEditSessions.remove(token);
            return null;
        }
        return session.uuid();
    }

    public AppearanceManager.SaveResult saveAppearanceEdit(String token, String model, String dataUrl) {
        UUID uuid = getAppearanceEditOwner(token);
        if (uuid == null) {
            return AppearanceManager.SaveResult.error("Сеанс изменения внешности истёк. Выполните /skin ещё раз.");
        }
        AppearanceManager.SaveResult result = appearanceManager.saveAppearance(uuid, model, dataUrl);
        if (result.success()) {
            appearanceEditSessions.remove(token);
            plugin.getServer().execute(() -> notifyAppearanceChanged(uuid));
        }
        return result;
    }
}
