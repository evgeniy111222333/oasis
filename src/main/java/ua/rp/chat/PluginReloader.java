package ua.rp.chat;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.SimpleCommandMap;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.SimplePluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.lang.reflect.Field;
import java.net.URLClassLoader;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class PluginReloader {

    @SuppressWarnings("unchecked")
    public static boolean reload(String pluginName, JavaPlugin mainPlugin) {
        PluginManager pm = Bukkit.getPluginManager();
        Plugin plugin = pm.getPlugin(pluginName);
        
        if (plugin == null) {
            mainPlugin.getLogger().warning("Plugin " + pluginName + " not found to reload!");
            return false;
        }

        // 1. Disable the plugin and unregister listeners
        pm.disablePlugin(plugin);
        HandlerList.unregisterAll(plugin);

        try {
            // 2. Remove plugin from SimplePluginManager list and map using Reflection
            if (pm instanceof SimplePluginManager spm) {
                Field pluginsField = SimplePluginManager.class.getDeclaredField("plugins");
                pluginsField.setAccessible(true);
                List<Plugin> plugins = (List<Plugin>) pluginsField.get(spm);
                plugins.remove(plugin);

                Field lookupNamesField = SimplePluginManager.class.getDeclaredField("lookupNames");
                lookupNamesField.setAccessible(true);
                Map<String, Plugin> lookupNames = (Map<String, Plugin>) lookupNamesField.get(spm);
                lookupNames.remove(pluginName.toLowerCase());

                // 3. Unregister commands from Bukkit's command map
                Field commandMapField = SimplePluginManager.class.getDeclaredField("commandMap");
                commandMapField.setAccessible(true);
                SimpleCommandMap commandMap = (SimpleCommandMap) commandMapField.get(spm);

                Field knownCommandsField = SimpleCommandMap.class.getDeclaredField("knownCommands");
                knownCommandsField.setAccessible(true);
                Map<String, Command> knownCommands = (Map<String, Command>) knownCommandsField.get(commandMap);

                Iterator<Map.Entry<String, Command>> cmdIterator = knownCommands.entrySet().iterator();
                while (cmdIterator.hasNext()) {
                    Map.Entry<String, Command> entry = cmdIterator.next();
                    Command cmd = entry.getValue();
                    if (cmd instanceof org.bukkit.command.PluginCommand pluginCmd) {
                        if (pluginCmd.getPlugin().getName().equalsIgnoreCase(pluginName)) {
                            cmd.unregister(commandMap);
                            cmdIterator.remove();
                        }
                    }
                }
            }

            // 4. Close the URLClassLoader to release file locks on the jar file
            ClassLoader cl = plugin.getClass().getClassLoader();
            if (cl instanceof URLClassLoader urlCl) {
                urlCl.close();
            }

            // 5. Suggest Garbage Collection to force release file handles
            System.gc();

            // 6. Reload plugin from file
            File pluginJar = new File("plugins", pluginName + ".jar");
            if (!pluginJar.exists()) {
                pluginJar = new File("plugins", plugin.getName() + ".jar");
            }

            if (!pluginJar.exists()) {
                mainPlugin.getLogger().severe("Jar file not found for plugin: " + pluginName);
                return false;
            }

            Plugin newPlugin = pm.loadPlugin(pluginJar);
            if (newPlugin == null) {
                mainPlugin.getLogger().severe("Failed to load jar file: " + pluginJar.getName());
                return false;
            }

            pm.enablePlugin(newPlugin);
            mainPlugin.getLogger().info("Plugin " + pluginName + " successfully hot-reloaded!");
            return true;
        } catch (Exception e) {
            mainPlugin.getLogger().severe("Error reloading plugin " + pluginName + ": " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }
}
