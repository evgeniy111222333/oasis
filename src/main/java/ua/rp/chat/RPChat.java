package ua.rp.chat;

import org.bukkit.plugin.java.JavaPlugin;
import ua.rp.chat.acquaintance.AcquaintanceManager;
import ua.rp.chat.auth.*;
import ua.rp.chat.combat.CombatManager;
import ua.rp.chat.vitals.StaminaManager;
import ua.rp.chat.microvoxel.MicrovoxelManager;
import ua.rp.chat.interaction.ItemPickupManager;

import java.sql.SQLException;

public class RPChat extends JavaPlugin {
    private int activeStyle = 1;
    private AuthDatabase authDatabase;
    private AuthManager authManager;
    private AuthWebServer authWebServer;
    private AuthGuiManager authGuiManager;
    private AppearanceManager appearanceManager;
    private StaminaManager staminaManager;
    private RpChatService rpChatService;
    private CombatManager combatManager;
    private AcquaintanceManager acquaintanceManager;
    private MicrovoxelManager microvoxelManager;

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
            R2AppearanceStorage appearanceStorage = R2AppearanceStorage.fromConfig(getConfig(), getLogger());
            appearanceManager = new AppearanceManager(getDataFolder(), authDatabase, getLogger(), appearanceStorage);
        } catch (SQLException e) {
            getLogger().severe("Failed to initialize AuthDatabase! Disabling plugin.");
            e.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        authManager = new AuthManager(this, authDatabase, appearanceManager);
        rpChatService = new RpChatService(this);
        staminaManager = new StaminaManager(this);
        staminaManager.start();
        combatManager = new CombatManager(this);
        getServer().getPluginManager().registerEvents(combatManager, this);
        acquaintanceManager = new AcquaintanceManager(this);
        acquaintanceManager.start();
        microvoxelManager = new MicrovoxelManager(this);
        microvoxelManager.start();
        new ItemPickupManager(this).start();
        
        // Initialize and register GUI Manager
        authGuiManager = new AuthGuiManager(this, authManager);
        getServer().getPluginManager().registerEvents(authGuiManager, this);

        // Register Outgoing Plugin Channel for Fabric Client Mod
        getServer().getMessenger().registerOutgoingPluginChannel(this, "rpchat:auth_init");
        getServer().getMessenger().registerOutgoingPluginChannel(this, "rpchat:appearance_refresh");
        getServer().getMessenger().registerOutgoingPluginChannel(this, RpChatFeedProtocol.CHANNEL);
        getServer().getMessenger().registerIncomingPluginChannel(this, CombatManager.INTENT_CHANNEL, combatManager);

        // Register auth events (MUST be before chat events for priority)
        getServer().getPluginManager().registerEvents(new AuthListener(authManager), this);

        // Register auth commands
        AuthCommands authCommands = new AuthCommands(authManager);
        getCommand("login").setExecutor(authCommands);
        getCommand("register").setExecutor(authCommands);
        getCommand("l").setExecutor(authCommands);
        getCommand("skin").setExecutor(new AppearanceCommands(authManager));

        // Extract web resources for customizability
        extractWebAssets();

        // Start Web Server
        int webPort = getConfig().getInt("web.port", 8080);
        authWebServer = new AuthWebServer(this, authManager, staminaManager);
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
        getCommand("w").setExecutor(commands);
        getCommand("whisper").setExecutor(commands);
        getCommand("s").setExecutor(commands);
        getCommand("shout").setExecutor(commands);
        getCommand("say").setExecutor(commands);
        getCommand("rpreload").setExecutor(commands);
        getCommand("rpcrun").setExecutor(commands);
        getCommand("rpdemo").setExecutor(commands);
        getCommand("rpcombatdebug").setExecutor(commands);

        getLogger().info("RPChat has been successfully enabled! Active chat style: " + ChatFormatter.STYLE_NAMES[activeStyle - 1]);
        getLogger().info("Auth system initialized with cinematic camera and web interface.");
    }

    private void extractWebAssets() {
        java.io.File webFolder = new java.io.File(getDataFolder(), "web");
        if (!webFolder.exists()) {
            webFolder.mkdirs();
        }

        String[] files = {
                "index.html",
                "style.css",
                "app.js",
                "body.html",
                "body.css",
                "body.js",
                "assets/body-ui.css",
                "assets/body-ui.js"
                ,"appearance.html",
                "appearance.css",
                "appearance.js"
        };
        for (String filename : files) {
            java.io.File outFile = new java.io.File(webFolder, filename);
            boolean managedAppearanceAsset = filename.startsWith("appearance.");
            // Appearance workshop assets are part of the client/server protocol,
            // not administrator-customizable content.  Keep their server fallback
            // in sync on every upgrade; otherwise an old data-folder copy shadows
            // the new JAR indefinitely.
            if (managedAppearanceAsset || !outFile.exists()) {
                saveResource("web/" + filename, managedAppearanceAsset);
            }
        }
    }

    @Override
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
}
