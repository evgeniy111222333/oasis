package ua.rp.chat;

import org.bukkit.plugin.java.JavaPlugin;

public class RPChat extends JavaPlugin {
    @Override
    public void onEnable() {
        // Register events
        getServer().getPluginManager().registerEvents(new ChatListener(this), this);

        // Register commands
        RPCommands commands = new RPCommands(this);
        getCommand("me").setExecutor(commands);
        getCommand("do").setExecutor(commands);
        getCommand("try").setExecutor(commands);
        getCommand("todo").setExecutor(commands);
        getCommand("b").setExecutor(commands);

        getLogger().info("RPChat has been successfully enabled!");
    }

    @Override
    public void onDisable() {
        getLogger().info("RPChat has been disabled.");
    }
}
