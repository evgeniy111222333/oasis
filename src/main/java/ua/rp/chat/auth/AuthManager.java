package ua.rp.chat.auth;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.title.Title;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Core authentication state manager.
 * Manages login status, timeout, and coordinates the cinematic camera + NPC.
 */
public class AuthManager {

    // Desert Eclipse palette
    private static final TextColor SAND_GOLD = TextColor.color(0xE3C099);
    private static final TextColor PEBBLE_GRAY = TextColor.color(0xB0A8A0);
    private static final TextColor SEAFOAM = TextColor.color(0xA5C3C4);
    private static final TextColor SOFT_GREEN = TextColor.color(0x99C3A2);
    private static final TextColor TERRACOTTA = TextColor.color(0xE3A899);
    private static final TextColor DRY_EARTH = TextColor.color(0x8A827A);
    private static final TextColor WARM_DUST = TextColor.color(0xAFA69E);

    private final ua.rp.chat.RPChat plugin;
    private final AuthDatabase database;
    private final AppearanceManager appearanceManager;
    private final AuthCameraManager cameraManager;

    // Track which players are NOT yet authenticated
    private final Set<UUID> pendingAuth = ConcurrentHashMap.newKeySet();
    // Track timeout tasks
    private final Map<UUID, Integer> timeoutTasks = new ConcurrentHashMap<>();
    // Store original locations before camera setup
    private final Map<UUID, Location> originalLocations = new ConcurrentHashMap<>();
    
    // Web Auth Tokens (Token -> Player UUID)
    private final Map<String, UUID> tokenToUuid = new ConcurrentHashMap<>();
    // Short-lived tokens for an authenticated character changing only their appearance.
    // These are deliberately separate from login tokens: opening the skin studio must
    // never move an already authenticated player back into the auth flow.
    private final Map<String, AppearanceEditSession> appearanceEditSessions = new ConcurrentHashMap<>();
    // Active Nametag Text Displays (Player UUID -> TextDisplay)
    private final Map<UUID, org.bukkit.entity.TextDisplay> activeNametags = new ConcurrentHashMap<>();

    // Auth timeout in seconds
    private static final int AUTH_TIMEOUT_SECONDS = 120;
    private static final long APPEARANCE_EDIT_TTL_MS = Duration.ofMinutes(10).toMillis();

    public AuthManager(ua.rp.chat.RPChat plugin, AuthDatabase database, AppearanceManager appearanceManager) {
        this.plugin = plugin;
        this.database = database;
        this.appearanceManager = appearanceManager;
        this.cameraManager = new AuthCameraManager(plugin);
    }

    /**
     * Called when a player joins. Sets up the cinematic auth screen and token.
     */
    public void handleJoin(Player player) {
        UUID uuid = player.getUniqueId();

        // Explicit web authentication is the default. The optional IP shortcut stays
        // behind configuration for private deployments and is disabled for Eclipse.
        String currentIp = getPlayerIp(player);
        if (plugin.getConfig().getBoolean("auth.auto-login-by-ip", false) && database.isRegistered(uuid)) {
            String lastIp = database.getLastIp(uuid);
                if (lastIp != null && lastIp.equals(currentIp)) {
                // Auto-login: same IP session
                plugin.getLogger().info("Auto-login for " + player.getName() + " (IP session match)");
                database.updateLogin(uuid, currentIp);
                
                String rpName = database.getRpName(uuid);
                applyRpIdentity(player, rpName);
                broadcastRoleplayJoin(player, rpName);
                sendAutoLoginTitle(player);
                return;
            }
        }

        // Mark as pending auth
        pendingAuth.add(uuid);

        // Store original location
        originalLocations.put(uuid, player.getLocation().clone());

        // Publish the web token before any delayed camera/UI work. The client can
        // query /api/client-session immediately after joining, so registering the
        // token inside the delayed task created a reproducible "session not found" race.
        tokenToUuid.values().removeIf(id -> id.equals(uuid));
        String token = UUID.randomUUID().toString().replace("-", "");
        tokenToUuid.put(token, uuid);

        // Setup cinematic camera (delayed 5 ticks so player is fully loaded)
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!player.isOnline()) {
                    tokenToUuid.remove(token, uuid);
                    return;
                }

                // Set spectator mode (CEF overlay works on spectator as well, or adventure)
                player.setGameMode(GameMode.SPECTATOR);

                // Setup camera
                cameraManager.setupCinematicView(player);

                // Open client-side visual auth overlay.
                openAuthOverlay(player, token);

                // Start timeout
                startTimeout(player);
            }
        }.runTaskLater(plugin, 5L);
    }

    /**
     * Web Registration Handler.
     */
    public boolean webRegister(UUID uuid, String loginName, String rpName, String email, String password) {
        return webRegister(uuid, loginName, rpName, email, password, null, "classic");
    }

    public boolean webRegister(UUID uuid, String loginName, String rpName, String email, String password, String appearanceDataUrl, String appearanceModel) {
        Player player = plugin.getServer().getPlayer(uuid);
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
            plugin.getLogger().warning("Registration rejected for " + player.getName() + ": missing required appearance.");
            return false;
        }

        AppearanceManager.SaveResult validation = appearanceManager.validateAppearance(appearanceDataUrl);
        if (!validation.success()) {
            plugin.getLogger().warning("Appearance validation failed for " + player.getName() + ": " + validation.message());
            return false;
        }

        String hash = PasswordHasher.hash(password);
        if (database.register(uuid, loginName, rpName, email, hash)) {
            AppearanceManager.SaveResult appearanceResult = appearanceManager.saveAppearance(uuid, appearanceModel, appearanceDataUrl);
            if (!appearanceResult.success()) {
                plugin.getLogger().warning("Appearance upload failed for " + player.getName() + ": " + appearanceResult.message());
                database.deleteAccount(uuid);
                return false;
            }
            database.updateLogin(uuid, null);
            
            // Apply name tag and chat styles
            new BukkitRunnable() {
                @Override
                public void run() {
                    applyRpIdentity(player, rpName);
                    completeAuth(player);
                    broadcastRoleplayJoin(player, rpName);
                }
            }.runTask(plugin); // Run on main server thread!
            return true;
        }
        return false;
    }

    /**
     * Web Login Handler.
     */
    public boolean webLogin(UUID uuid, String loginName, String password) {
        return webLogin(uuid, loginName, password, false);
    }

    public boolean webLogin(UUID uuid, String loginName, String password, boolean rememberDevice) {
        Player player = plugin.getServer().getPlayer(uuid);
        if (player == null || !pendingAuth.contains(uuid)) {
            plugin.getLogger().warning("[AUTH-AUDIT] login rejected: player/session is no longer pending; login=" + loginName);
            return false;
        }

        AuthDatabase.PlayerAccount account = database.getAccountByLoginName(loginName);
        if (account == null) {
            plugin.getLogger().info("[AUTH-AUDIT] login rejected: account not found; player=" + player.getName() + ", login=" + loginName);
            return false;
        }

        String storedHash = account.passwordHash();
        if (storedHash == null) {
            plugin.getLogger().warning("[AUTH-AUDIT] login rejected: account has no password hash; player=" + player.getName() + ", login=" + loginName);
            return false;
        }

        if (PasswordHasher.verify(password, storedHash)) {
            if (!account.uuid().equals(uuid) && !database.rebindAccountUuid(account.loginName(), uuid)) {
                plugin.getLogger().warning("Could not rebind account " + account.loginName() + " to current uuid " + uuid);
                return false;
            }
            plugin.getLogger().info("[AUTH-AUDIT] credential verification succeeded; player=" + player.getName() + ", login=" + loginName);
            database.updateLogin(uuid, rememberDevice ? getPlayerIp(player) : null);
            
            String rpName = account.rpName();
            // Apply name tag and chat styles on main thread
            new BukkitRunnable() {
                @Override
                public void run() {
                    applyRpIdentity(player, rpName);
                    completeAuth(player);
                    broadcastRoleplayJoin(player, rpName);
                }
            }.runTask(plugin);
            return true;
        }
        plugin.getLogger().info("[AUTH-AUDIT] credential verification failed; player=" + player.getName() + ", login=" + loginName);
        return false;
    }

    /**
     * Applies custom display and tab names, while keeping world nametags hidden for full RP.
     */
    public void applyRpIdentity(Player player, String rpName) {
        if (player == null || rpName == null) return;
        
        // Remove existing nametags if any
        removeRpIdentity(player);

        // 1. Update Bukkit display & tab names
        player.displayName(Component.text(rpName));
        player.playerListName(Component.text(rpName));

        // 2. Hide standard name tag using scoreboard team
        org.bukkit.scoreboard.Scoreboard scoreboard = org.bukkit.Bukkit.getScoreboardManager().getMainScoreboard();
        org.bukkit.scoreboard.Team team = scoreboard.getTeam(player.getName());
        if (team == null) {
            team = scoreboard.registerNewTeam(player.getName());
        }
        team.setOption(org.bukkit.scoreboard.Team.Option.NAME_TAG_VISIBILITY, org.bukkit.scoreboard.Team.OptionStatus.NEVER);
        team.addEntry(player.getName());
    }

    /**
     * Cleans up the custom TextDisplay nametag and scoreboard team.
     */
    public void removeRpIdentity(Player player) {
        if (player == null) return;
        UUID uuid = player.getUniqueId();

        org.bukkit.entity.TextDisplay td = activeNametags.remove(uuid);
        if (td != null && td.isValid()) {
            td.remove();
        }

        org.bukkit.scoreboard.Scoreboard scoreboard = org.bukkit.Bukkit.getScoreboardManager().getMainScoreboard();
        org.bukkit.scoreboard.Team team = scoreboard.getTeam(player.getName());
        if (team != null) {
            team.unregister();
        }
    }

    /**
     * Completes authentication: removes pending state, restores player.
     */
    private void completeAuth(Player player) {
        UUID uuid = player.getUniqueId();
        pendingAuth.remove(uuid);

        // Cancel timeout
        Integer taskId = timeoutTasks.remove(uuid);
        if (taskId != null) {
            plugin.getServer().getScheduler().cancelTask(taskId);
        }

        // Cleanup NPC and camera
        cameraManager.cleanup(player);

        // Clear token mapping
        tokenToUuid.values().removeIf(id -> id.equals(uuid));

        // Restore player to survival mode
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!player.isOnline()) return;

                player.setGameMode(GameMode.SURVIVAL);

                // Restore original location
                Location original = originalLocations.remove(uuid);
                if (original != null) {
                    player.teleport(original);
                }

                // Welcome title
                sendWelcomeTitle(player);
            }
        }.runTaskLater(plugin, 2L);
    }

    private void broadcastRoleplayJoin(Player player, String rpName) {
        if (player == null) {
            return;
        }
        String name = rpName != null && !rpName.isBlank() ? rpName : player.getName();
        int style = plugin.getActiveStyle();
        plugin.getServer().broadcast(ua.rp.chat.ChatFormatter.formatJoin(name, style));
    }

    public void broadcastRoleplayQuit(Player player) {
        if (player == null || pendingAuth.contains(player.getUniqueId())) {
            return;
        }
        String rpName = database.getRpName(player.getUniqueId());
        String name = rpName != null && !rpName.isBlank() ? rpName : player.getName();
        plugin.getServer().broadcast(ua.rp.chat.ChatFormatter.formatQuit(name, plugin.getActiveStyle()));
    }

    public void openAuthOverlay(Player player, String token) {
        String finalLink = getAuthUrl(token, player.getName());
        sendAuthPayload(player, finalLink);

        new BukkitRunnable() {
            @Override
            public void run() {
                if (player.isOnline() && pendingAuth.contains(player.getUniqueId())) {
                    sendAuthPayload(player, finalLink);
                }
            }
        }.runTaskLater(plugin, 20L);

        new BukkitRunnable() {
            @Override
            public void run() {
                if (player.isOnline() && pendingAuth.contains(player.getUniqueId())) {
                    sendAuthPayload(player, finalLink);
                }
            }
        }.runTaskLater(plugin, 60L);
    }

    private void sendAuthPayload(Player player, String finalLink) {
        try {
            byte[] urlBytes = finalLink.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            java.io.ByteArrayOutputStream byteOut = new java.io.ByteArrayOutputStream();
            int len = urlBytes.length;
            while ((len & ~0x7F) != 0) {
                byteOut.write((len & 0x7F) | 0x80);
                len >>>= 7;
            }
            byteOut.write(len);
            byteOut.write(urlBytes);
            player.sendPluginMessage(plugin, "rpchat:auth_init", byteOut.toByteArray());
            plugin.getLogger().info("Sent Eclipse auth overlay trigger to " + player.getName() + ": " + finalLink);
        } catch (java.io.IOException e) {
            plugin.getLogger().warning("Failed to send client-mod auth packet: " + e.getMessage());
        }
    }

    public String getAuthUrl(String token) {
        return advertisedWebUrl() + "/auth?token=" + token;
    }

    public String getAuthUrl(String token, String username) {
        String encodedName = java.net.URLEncoder.encode(username == null ? "" : username, java.nio.charset.StandardCharsets.UTF_8);
        return advertisedWebUrl() + "/auth?token=" + token + "&username=" + encodedName;
    }

    /** Opens a one-time character appearance studio for an already authenticated player. */
    public boolean requestAppearanceChange(Player player) {
        if (player == null || !player.isOnline() || pendingAuth.contains(player.getUniqueId())) {
            return false;
        }
        UUID uuid = player.getUniqueId();
        if (!database.isRegistered(uuid)) {
            return false;
        }

        long now = System.currentTimeMillis();
        appearanceEditSessions.entrySet().removeIf(entry -> entry.getValue().expiredAtMs() <= now
                || entry.getValue().uuid().equals(uuid));
        String token = UUID.randomUUID().toString().replace("-", "");
        appearanceEditSessions.put(token, new AppearanceEditSession(uuid, now + APPEARANCE_EDIT_TTL_MS));
        String url = advertisedWebUrl() + "/appearance?token=" + token + "&username="
                + java.net.URLEncoder.encode(player.getName(), java.nio.charset.StandardCharsets.UTF_8);
        sendAuthPayload(player, url);
        player.sendMessage(Component.text("Открыта мастерская внешности. Сеанс действует 10 минут.", SEAFOAM));
        return true;
    }

    /** Returns the owner only while the short-lived appearance editor session is valid. */
    public UUID getAppearanceEditOwner(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        AppearanceEditSession session = appearanceEditSessions.get(token);
        if (session == null || session.expiredAtMs() <= System.currentTimeMillis()) {
            appearanceEditSessions.remove(token);
            return null;
        }
        Player player = plugin.getServer().getPlayer(session.uuid());
        if (player == null || !player.isOnline() || pendingAuth.contains(session.uuid()) || !database.isRegistered(session.uuid())) {
            appearanceEditSessions.remove(token);
            return null;
        }
        return session.uuid();
    }

    /** Saves the skin through the existing real persistence path and closes the session on success. */
    public AppearanceManager.SaveResult saveAppearanceEdit(String token, String model, String dataUrl) {
        UUID uuid = getAppearanceEditOwner(token);
        if (uuid == null) {
            return AppearanceManager.SaveResult.error("Сеанс изменения внешности истёк. Выполните /skin ещё раз.");
        }
        AppearanceManager.SaveResult result = appearanceManager.saveAppearance(uuid, model, dataUrl);
        if (result.success()) {
            appearanceEditSessions.remove(token);
            plugin.getServer().getScheduler().runTask(plugin, () -> notifyAppearanceChanged(uuid));
        }
        return result;
    }

    private void notifyAppearanceChanged(UUID uuid) {
        Player owner = plugin.getServer().getPlayer(uuid);
        if (owner != null && owner.isOnline()) {
            sendAppearanceRefreshPayload(owner, uuid);
            owner.sendMessage(Component.text("Внешность персонажа сохранена и применена.", SOFT_GREEN));
        }
        for (Player viewer : plugin.getServer().getOnlinePlayers()) {
            if (!viewer.getUniqueId().equals(uuid)) {
                sendAppearanceRefreshPayload(viewer, uuid);
            }
        }
    }

    private void sendAppearanceRefreshPayload(Player player, UUID changedUuid) {
        try {
            byte[] uuidBytes = changedUuid.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            java.io.ByteArrayOutputStream byteOut = new java.io.ByteArrayOutputStream();
            int len = uuidBytes.length;
            while ((len & ~0x7F) != 0) {
                byteOut.write((len & 0x7F) | 0x80);
                len >>>= 7;
            }
            byteOut.write(len);
            byteOut.write(uuidBytes);
            player.sendPluginMessage(plugin, "rpchat:appearance_refresh", byteOut.toByteArray());
        } catch (java.io.IOException e) {
            plugin.getLogger().warning("Failed to notify client about appearance refresh: " + e.getMessage());
        }
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

    public String getActiveAuthUrl(Player player) {
        if (player == null || !pendingAuth.contains(player.getUniqueId())) {
            return null;
        }
        String token = getActiveToken(player.getUniqueId());
        return token == null ? null : getAuthUrl(token, player.getName());
    }

    /**
     * Sends the visual welcome menu instructions (opens Chest GUI).
     */
    public void sendAuthMenu(Player player, String token) {
        // Clear chat space
        for (int i = 0; i < 20; i++) {
            player.sendMessage(Component.empty());
        }

        String finalLink = advertisedWebUrl() + "/auth?token=" + token;

        // Broadcast to client-side Fabric Mod (automatically renders HTML screen)
        // Encode using VarInt + UTF-8 to match Fabric's PacketCodecs.STRING format
        try {
            byte[] urlBytes = finalLink.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            java.io.ByteArrayOutputStream byteOut = new java.io.ByteArrayOutputStream();
            // Write VarInt length prefix (Minecraft protocol format)
            int len = urlBytes.length;
            while ((len & ~0x7F) != 0) {
                byteOut.write((len & 0x7F) | 0x80);
                len >>>= 7;
            }
            byteOut.write(len);
            // Write UTF-8 bytes
            byteOut.write(urlBytes);
            player.sendPluginMessage(plugin, "rpchat:auth_init", byteOut.toByteArray());
        } catch (java.io.IOException e) {
            plugin.getLogger().warning("Failed to send client-mod auth packet: " + e.getMessage());
        }

        // Visual Chat Card
        player.sendMessage(Component.text("  ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━", DRY_EARTH));
        player.sendMessage(Component.empty());
        player.sendMessage(
            Component.text("               ECLIPSE ROLEPLAY", SAND_GOLD)
                .decoration(TextDecoration.BOLD, true)
        );
        player.sendMessage(Component.empty());
        player.sendMessage(Component.text("     Будь ласка, пройдіть авторизацію у браузері.", PEBBLE_GRAY));
        player.sendMessage(Component.text("     Для цього натисніть на посилання нижче:", PEBBLE_GRAY));
        player.sendMessage(Component.empty());
        
        // Clickable Button
        player.sendMessage(
            Component.text("     [ КЛІКНІТЬ ТУТ ЩОБ ВІДКРИТИ ВІКНО ВХОДУ ]", SEAFOAM)
                .decoration(TextDecoration.BOLD, true)
                .clickEvent(net.kyori.adventure.text.event.ClickEvent.openUrl(finalLink))
                .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(Component.text("Клацніть для переходу на веб-інтерфейс", WARM_DUST)))
        );
        player.sendMessage(Component.empty());
        player.sendMessage(Component.text("  ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━", DRY_EARTH));

        // Show a beautiful full screen Title
        Title.Times times = Title.Times.times(
            Duration.ofMillis(300),
            Duration.ofSeconds(10),
            Duration.ofMillis(500)
        );
        Title title = Title.title(
            Component.text("ПОТРІБНА АВТОРИЗАЦІЯ", SAND_GOLD).decoration(TextDecoration.BOLD, true),
            Component.text("Посилання відправлено в чат (Натисніть T)", PEBBLE_GRAY),
            times
        );
        player.showTitle(title);
    }

    /**
     * Fallback to satisfy interface.
     */
    public void sendAuthMenu(Player player) {
        sendAuthMenu(player, "err");
    }

    /**
     * Sends an auto-login title effect.
     */
    private void sendAutoLoginTitle(Player player) {
        Title.Times times = Title.Times.times(
            Duration.ofMillis(200),
            Duration.ofSeconds(2),
            Duration.ofMillis(500)
        );
        String rpName = database.getRpName(player.getUniqueId());
        Title title = Title.title(
            Component.text("З поверненням", SAND_GOLD),
            Component.text(rpName != null ? rpName : player.getName(), PEBBLE_GRAY),
            times
        );
        player.showTitle(title);
    }

    /**
     * Sends a welcome title after successful auth.
     */
    private void sendWelcomeTitle(Player player) {
        Title.Times times = Title.Times.times(
            Duration.ofMillis(300),
            Duration.ofSeconds(3),
            Duration.ofMillis(800)
        );
        String rpName = database.getRpName(player.getUniqueId());
        Title title = Title.title(
            Component.text("Добро пожаловать", SAND_GOLD),
            Component.text(rpName != null ? rpName : player.getName(), PEBBLE_GRAY),
            times
        );
        player.showTitle(title);
    }

    /**
     * Starts the auth timeout timer.
     */
    private void startTimeout(Player player) {
        UUID uuid = player.getUniqueId();

        int taskId = new BukkitRunnable() {
            @Override
            public void run() {
                if (pendingAuth.contains(uuid) && player.isOnline()) {
                    cameraManager.cleanup(player);
                    player.kick(
                        Component.text()
                            .append(Component.text("Время авторизации истекло", TERRACOTTA))
                            .append(Component.newline())
                            .append(Component.text("Подключитесь повторно и пройдите авторизацию.", PEBBLE_GRAY))
                            .build()
                    );
                    pendingAuth.remove(uuid);
                    originalLocations.remove(uuid);
                    tokenToUuid.values().removeIf(id -> id.equals(uuid));
                }
            }
        }.runTaskLater(plugin, AUTH_TIMEOUT_SECONDS * 20L).getTaskId();

        timeoutTasks.put(uuid, taskId);
    }

    /**
     * Checks if a player is currently pending authentication.
     */
    public boolean isPendingAuth(UUID uuid) {
        return pendingAuth.contains(uuid);
    }

    /**
     * Handles player disconnect during auth.
     */
    public void handleQuit(Player player) {
        UUID uuid = player.getUniqueId();
        // Duplicate-login replacement fires quit and join for the same UUID almost
        // together. Defer cleanup so the old connection cannot erase the new
        // connection's token, timeout, camera location, or pending-auth state.
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            Player current = plugin.getServer().getPlayer(uuid);
            if (current != null && current != player && current.isOnline()) {
                plugin.getLogger().info("Preserved replacement auth session for " + current.getName());
                cameraManager.cleanup(player);
                removeRpIdentity(player);
                return;
            }

            pendingAuth.remove(uuid);
            originalLocations.remove(uuid);
            cameraManager.cleanup(player);
            removeRpIdentity(player);
            tokenToUuid.values().removeIf(id -> id.equals(uuid));

            Integer taskId = timeoutTasks.remove(uuid);
            if (taskId != null) {
                plugin.getServer().getScheduler().cancelTask(taskId);
            }
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

    /**
     * Returns the camera manager for coordinate checks.
     */
    public AuthCameraManager getCameraManager() {
        return cameraManager;
    }

    private String getPlayerIp(Player player) {
        if (player == null) return null;
        InetSocketAddress addr = player.getAddress();
        return addr != null ? addr.getAddress().getHostAddress() : null;
    }
}
