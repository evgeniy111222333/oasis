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

        // 1b. Clean up PaperMC internals (PluginProvider and storage) to avoid duplicate identifier errors
        cleanPaperInternals(pluginName, mainPlugin);

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

                mainPlugin.getLogger().info("knownCommands class: " + knownCommands.getClass().getName());

                // Collect commands to unregister first to avoid concurrent modification issues
                java.util.List<String> keysToRemove = new java.util.ArrayList<>();
                for (Map.Entry<String, Command> entry : knownCommands.entrySet()) {
                    Command cmd = entry.getValue();
                    if (cmd instanceof org.bukkit.command.PluginCommand pluginCmd) {
                        if (pluginCmd.getPlugin().getName().equalsIgnoreCase(pluginName)) {
                            cmd.unregister(commandMap);
                            keysToRemove.add(entry.getKey());
                        }
                    }
                }

                for (String key : keysToRemove) {
                    try {
                        knownCommands.remove(key);
                        mainPlugin.getLogger().info("Successfully removed command: " + key);
                    } catch (UnsupportedOperationException e) {
                        mainPlugin.getLogger().warning("Could not remove command " + key + " directly from knownCommands: " + e.getMessage());
                        // If it's an unmodifiable map (e.g. PaperKnownCommands), it might have a fallback or we might try to find its backing map.
                        try {
                            // Let's inspect fields of the map to see if we can find a delegate/backing map.
                            boolean removed = false;
                            for (Field f : knownCommands.getClass().getDeclaredFields()) {
                                if (Map.class.isAssignableFrom(f.getType())) {
                                    f.setAccessible(true);
                                    Map<String, Command> innerMap = (Map<String, Command>) f.get(knownCommands);
                                    if (innerMap != null && innerMap.containsKey(key)) {
                                        innerMap.remove(key);
                                        mainPlugin.getLogger().info("Removed command " + key + " from inner map field: " + f.getName());
                                        removed = true;
                                    }
                                }
                            }
                            if (!removed) {
                                // Also try the superclasses fields just in case
                                Class<?> superClass = knownCommands.getClass().getSuperclass();
                                while (superClass != null && superClass != Object.class && !removed) {
                                    for (Field f : superClass.getDeclaredFields()) {
                                        if (Map.class.isAssignableFrom(f.getType())) {
                                            f.setAccessible(true);
                                            Map<String, Command> innerMap = (Map<String, Command>) f.get(knownCommands);
                                            if (innerMap != null && innerMap.containsKey(key)) {
                                                innerMap.remove(key);
                                                mainPlugin.getLogger().info("Removed command " + key + " from superclass inner map field: " + f.getName());
                                                removed = true;
                                            }
                                        }
                                    }
                                    superClass = superClass.getSuperclass();
                                }
                            }
                        } catch (Exception ex) {
                            mainPlugin.getLogger().warning("Failed to remove command via reflection: " + ex.getMessage());
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

    private static void cleanPaperInternals(String pluginName, JavaPlugin mainPlugin) {
        PluginManager pm = Bukkit.getPluginManager();
        mainPlugin.getLogger().info("Starting PaperMC internals cleanup for " + pluginName);
        try {
            cleanObjectFields(pm, pluginName, mainPlugin, new java.util.HashSet<>());
        } catch (Exception e) {
            mainPlugin.getLogger().warning("Error during PaperMC internals cleanup: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private static void cleanObjectFields(Object obj, String pluginName, JavaPlugin mainPlugin, java.util.Set<Object> visited) {
        if (obj == null || visited.contains(obj)) return;
        visited.add(obj);

        Class<?> clazz = obj.getClass();
        String className = clazz.getName();
        // Skip basic/JVM system classes to avoid deep recursion or infinite loops
        if (className.startsWith("java.") || className.startsWith("sun.") || className.startsWith("jdk.") || className.startsWith("com.sun.")) {
            return;
        }

        while (clazz != null && clazz != Object.class) {
            for (Field f : clazz.getDeclaredFields()) {
                try {
                    // Skip static fields to avoid global state modification and potential security manager issues
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                        continue;
                    }
                    f.setAccessible(true);
                    Object val = f.get(obj);
                    if (val == null) continue;

                    // If it's a Map
                    if (val instanceof Map<?, ?> map) {
                        java.util.List<Object> keysToRemove = new java.util.ArrayList<>();
                        for (Map.Entry<?, ?> entry : map.entrySet()) {
                            Object key = entry.getKey();
                            Object value = entry.getValue();
                            if (isPluginRef(key, pluginName) || isPluginRef(value, pluginName)) {
                                keysToRemove.add(key);
                            } else {
                                cleanObjectFields(key, pluginName, mainPlugin, visited);
                                cleanObjectFields(value, pluginName, mainPlugin, visited);
                            }
                        }
                        if (!keysToRemove.isEmpty()) {
                            for (Object key : keysToRemove) {
                                try {
                                    map.remove(key);
                                    mainPlugin.getLogger().info("Removed plugin reference from map '" + f.getName() + "' with key: " + key);
                                } catch (Exception e) {
                                    // Ignored
                                }
                            }
                        }
                    }
                    // If it's a Collection (List, Set, etc.)
                    else if (val instanceof java.util.Collection<?> col) {
                        java.util.List<Object> itemsToRemove = new java.util.ArrayList<>();
                        for (Object item : col) {
                            if (isPluginRef(item, pluginName)) {
                                itemsToRemove.add(item);
                            } else {
                                cleanObjectFields(item, pluginName, mainPlugin, visited);
                            }
                        }
                        if (!itemsToRemove.isEmpty()) {
                            try {
                                col.removeAll(itemsToRemove);
                                mainPlugin.getLogger().info("Removed " + itemsToRemove.size() + " plugin references from collection '" + f.getName() + "'");
                            } catch (Exception e) {
                                // Ignored
                            }
                        }
                    }
                    // Recursively clean custom nested objects
                    else {
                        cleanObjectFields(val, pluginName, mainPlugin, visited);
                    }
                } catch (Exception e) {
                    // Ignored
                }
            }
            clazz = clazz.getSuperclass();
        }
    }

    private static boolean isPluginRef(Object item, String pluginName) {
        if (item == null) return false;
        String str = item.toString().toLowerCase();
        if (str.contains("pluginprovider") && str.contains(pluginName.toLowerCase())) {
            return true;
        }
        if (item instanceof org.bukkit.plugin.Plugin p && p.getName().equalsIgnoreCase(pluginName)) {
            return true;
        }
        String className = item.getClass().getName().toLowerCase();
        if (className.contains("pluginprovider") && className.contains(pluginName.toLowerCase())) {
            return true;
        }
        return false;
    }
}
