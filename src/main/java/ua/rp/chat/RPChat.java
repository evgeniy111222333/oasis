package ua.rp.chat;

import org.bukkit.plugin.java.JavaPlugin;
import ua.rp.chat.auth.*;

import java.sql.SQLException;

public class RPChat extends JavaPlugin {
    private int activeStyle = 1;
    private AuthDatabase authDatabase;
    private AuthManager authManager;
    private AuthWebServer authWebServer;
    private AuthGuiManager authGuiManager;
    private AppearanceManager appearanceManager;

    @Override
    public void onEnable() {
        // Save default config if not exists
        saveDefaultConfig();
        
        // Load active style from config
        activeStyle = getConfig().getInt("active-style", 1);
        if (activeStyle < 1 || activeStyle > 10) {
            activeStyle = 1;
        }

        // --- Initialize Auth System ---
        try {
            authDatabase = new AuthDatabase(getDataFolder(), getLogger());
            authDatabase.connect();
            appearanceManager = new AppearanceManager(getDataFolder(), authDatabase, getLogger());
        } catch (SQLException e) {
            getLogger().severe("Failed to initialize AuthDatabase! Disabling plugin.");
            e.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        authManager = new AuthManager(this, authDatabase, appearanceManager);
        
        // Initialize and register GUI Manager
        authGuiManager = new AuthGuiManager(this, authManager);
        getServer().getPluginManager().registerEvents(authGuiManager, this);

        // Register Outgoing Plugin Channel for Fabric Client Mod
        getServer().getMessenger().registerOutgoingPluginChannel(this, "rpchat:auth_init");

        // Register auth events (MUST be before chat events for priority)
        getServer().getPluginManager().registerEvents(new AuthListener(authManager), this);

        // Register auth commands
        AuthCommands authCommands = new AuthCommands(authManager);
        getCommand("login").setExecutor(authCommands);
        getCommand("register").setExecutor(authCommands);
        getCommand("l").setExecutor(authCommands);

        // Extract web resources for customizability
        extractWebAssets();

        // Start Web Server
        int webPort = getConfig().getInt("web.port", 8080);
        authWebServer = new AuthWebServer(this, authManager);
        authWebServer.start(webPort);

        // --- Register Chat Events ---
        getServer().getPluginManager().registerEvents(new ChatListener(this), this);

        // Register RP commands
        RPCommands commands = new RPCommands(this);
        getCommand("me").setExecutor(commands);
        getCommand("do").setExecutor(commands);
        getCommand("try").setExecutor(commands);
        getCommand("todo").setExecutor(commands);
        getCommand("b").setExecutor(commands);
        getCommand("rpreload").setExecutor(commands);
        getCommand("rpcrun").setExecutor(commands);
        getCommand("rpdemo").setExecutor(commands);

        getLogger().info("RPChat has been successfully enabled! Active chat style: " + ChatFormatter.STYLE_NAMES[activeStyle - 1]);
        getLogger().info("Auth system initialized with cinematic camera and web interface.");
    }

    private void extractWebAssets() {
        java.io.File webFolder = new java.io.File(getDataFolder(), "web");
        if (!webFolder.exists()) {
            webFolder.mkdirs();
        }

        String[] files = {"index.html", "style.css", "app.js"};
        for (String filename : files) {
            java.io.File outFile = new java.io.File(webFolder, filename);
            if (!outFile.exists()) {
                saveResource("web/" + filename, false);
            }
        }
    }

    @Override
    public void onDisable() {
        if (authWebServer != null) {
            authWebServer.stop();
        }
        if (authDatabase != null) {
            authDatabase.disconnect();
        }
        getLogger().info("RPChat has been disabled.");
    }

    public int getActiveStyle() {
        return activeStyle;
    }

    public void setActiveStyle(int style) {
        if (style >= 1 && style <= 10) {
            this.activeStyle = style;
            getConfig().set("active-style", style);
            saveConfig();
        }
    }

    public AuthManager getAuthManager() {
        return authManager;
    }

    public AuthGuiManager getAuthGuiManager() {
        return authGuiManager;
    }

    public AppearanceManager getAppearanceManager() {
        return appearanceManager;
    }
}
