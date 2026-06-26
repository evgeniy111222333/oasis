package ua.rp.chat;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import java.util.concurrent.ThreadLocalRandom;

public class RPCommands implements CommandExecutor {
    private final RPChat plugin;
    private final double radius = 20.0;

    // Hex Color Palette (Clean & Comfortable)
    private final TextColor goldColor = TextColor.color(0xE8C58C);      // Soft Gold
    private final TextColor purpleColor = TextColor.color(0xC68BFF);    // Soft Purple
    private final TextColor aquaColor = TextColor.color(0x81ECEC);      // Soft Aqua
    private final TextColor grayColor = TextColor.color(0x95A5A6);      // Medium Gray

    public RPCommands(RPChat plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        // Handle rpcrun command (can be executed by console or admin player)
        if (label.equalsIgnoreCase("rpcrun")) {
            if (sender instanceof Player player && !player.hasPermission("rpchat.admin")) {
                player.sendMessage(Component.text("У вас немає прав для використання цієї команди!", NamedTextColor.RED));
                return true;
            }
            return handleRpcRun(sender, args);
        }

        // Handle reloader command
        if (label.equalsIgnoreCase("rpreload")) {
            if (sender instanceof Player player && !player.hasPermission("rpchat.admin")) {
                player.sendMessage(Component.text("У вас немає прав для використання цієї команди!", NamedTextColor.RED));
                return true;
            }
            
            String targetPlugin = args.length > 0 ? args[0] : "RPChat";
            sender.sendMessage(Component.text("Перезавантаження плагіну " + targetPlugin + "...", NamedTextColor.YELLOW));
            
            long start = System.currentTimeMillis();
            boolean success = PluginReloader.reload(targetPlugin, plugin);
            long duration = System.currentTimeMillis() - start;
            
            if (success) {
                sender.sendMessage(Component.text("Плагін " + targetPlugin + " успішно перезавантажено за " + duration + "мс без перезавантаження серверу!", NamedTextColor.GREEN));
            } else {
                sender.sendMessage(Component.text("Помилка при перезавантаженні плагіну " + targetPlugin + ". Перевірте консоль для деталей.", NamedTextColor.RED));
            }
            return true;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Цю команду може виконувати тільки гравець!", NamedTextColor.RED));
            return true;
        }

        if (args.length == 0) {
            sendUsage(player, label);
            return true;
        }

        String message = String.join(" ", args).trim();
        String rpName = player.getName().replace("_", " ");

        switch (label.toLowerCase()) {
            case "me" -> handleMe(player, rpName, message);
            case "do" -> handleDo(player, rpName, message);
            case "try" -> handleTry(player, rpName, message);
            case "todo" -> handleTodo(player, rpName, message);
            case "b" -> handleB(player, rpName, message);
        }

        return true;
    }

    private boolean handleRpcRun(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(Component.text("Використання: /rpcrun [player] [me/do/try/todo/b] [args...]", NamedTextColor.RED));
            return true;
        }

        String targetName = args[0];
        Player target = plugin.getServer().getPlayer(targetName);
        if (target == null || !target.isOnline()) {
            sender.sendMessage(Component.text("Помилка: Гравця " + targetName + " не знайдено або він не в мережі!", NamedTextColor.RED));
            return true;
        }

        String subCommand = args[1].toLowerCase();
        
        // Extract remaining args
        String[] subArgs = new String[args.length - 2];
        System.arraycopy(args, 2, subArgs, 0, subArgs.length);
        String message = String.join(" ", subArgs).trim();

        String rpName = target.getName().replace("_", " ");

        switch (subCommand) {
            case "me" -> handleMe(target, rpName, message);
            case "do" -> handleDo(target, rpName, message);
            case "try" -> handleTry(target, rpName, message);
            case "todo" -> handleTodo(target, rpName, message);
            case "b" -> handleB(target, rpName, message);
            default -> sender.sendMessage(Component.text("Помилка: Невідома підкоманда " + subCommand + "! Доступні: me, do, try, todo, b", NamedTextColor.RED));
        }
        return true;
    }

    private void handleMe(Player player, String rpName, String action) {
        Component message = Component.text(rpName + " " + action, purpleColor);
        broadcastLocal(player, message);
    }

    private void handleDo(Player player, String rpName, String description) {
        Component message = Component.text()
            .append(Component.text(description, aquaColor))
            .append(Component.text(" — ", grayColor))
            .append(Component.text(rpName, grayColor))
            .build();
            
        broadcastLocal(player, message);
    }

    private void handleTry(Player player, String rpName, String action) {
        boolean success = ThreadLocalRandom.current().nextBoolean();
        Component result = success 
            ? Component.text(" [Успішно]", TextColor.color(0x2ECC71))
            : Component.text(" [Неуспішно]", TextColor.color(0xE74C3C));

        Component message = Component.text()
            .append(Component.text(rpName + " намагається " + action + "... ", purpleColor))
            .append(result)
            .build();
            
        broadcastLocal(player, message);
    }

    private void handleTodo(Player player, String rpName, String rawText) {
        if (!rawText.contains("*")) {
            player.sendMessage(Component.text("Помилка! Використовуйте символ '*' для розділення мови та дії. Приклад: /todo Привіт! * потиснув руку", NamedTextColor.RED));
            return;
        }

        String[] parts = rawText.split("\\*", 2);
        String speech = parts[0].trim();
        String action = parts[1].trim();

        Component message = Component.text()
            .append(Component.text("«" + speech + "»", NamedTextColor.WHITE))
            .append(Component.text(" — " + rpName + ", ", goldColor))
            .append(Component.text(action, purpleColor))
            .build();
            
        broadcastLocal(player, message);
    }

    private void handleB(Player player, String rpName, String oocMessage) {
        Component message = Component.text("OOC | " + rpName + ": " + oocMessage, grayColor);
        broadcastLocal(player, message);
    }

    private void broadcastLocal(Player sender, Component component) {
        // Send to console
        plugin.getServer().getConsoleSender().sendMessage(component);
        
        // Send to nearby players
        for (Player onlinePlayer : plugin.getServer().getOnlinePlayers()) {
            if (onlinePlayer.getWorld().equals(sender.getWorld())) {
                if (onlinePlayer.getLocation().distance(sender.getLocation()) <= radius) {
                    onlinePlayer.sendMessage(component);
                }
            }
        }
    }

    private void sendUsage(Player player, String label) {
        String usage = switch (label.toLowerCase()) {
            case "me" -> "/me [дія]";
            case "do" -> "/do [опис оточення]";
            case "try" -> "/try [спроба дії]";
            case "todo" -> "/todo [фраза] * [дія]";
            case "b" -> "/b [OOC повідомлення]";
            default -> "";
        };
        player.sendMessage(Component.text("Використання: " + usage, NamedTextColor.RED));
    }
}
