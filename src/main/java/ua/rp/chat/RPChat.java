package ua.rp.chat;

import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public class RPChat extends JavaPlugin {
    private PlayerIdManager idManager;

    @Override
    public void onEnable() {
        // Initialize ID Manager
        idManager = new PlayerIdManager();

        // Assign IDs to any players already online
        for (Player onlinePlayer : getServer().getOnlinePlayers()) {
            idManager.getOrAssignId(onlinePlayer);
        }

        // Register events
        getServer().getPluginManager().registerEvents(new ChatListener(this), this);

        // Register commands
        RPCommands commands = new RPCommands(this);
        getCommand("me").setExecutor(commands);
        getCommand("do").setExecutor(commands);
        getCommand("try").setExecutor(commands);
        getCommand("todo").setExecutor(commands);
        getCommand("b").setExecutor(commands);
        getCommand("rpreload").setExecutor(commands);
        getCommand("rpcrun").setExecutor(commands);

        getLogger().info("RPChat has been successfully enabled!");
    }

    @Override
    public void onDisable() {
        getLogger().info("RPChat has been disabled.");
    }

    public PlayerIdManager getIdManager() {
        return idManager;
    }
}
