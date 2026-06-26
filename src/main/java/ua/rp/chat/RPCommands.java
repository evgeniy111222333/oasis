package ua.rp.chat;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import java.util.concurrent.ThreadLocalRandom;

public class RPCommands implements CommandExecutor {
    private final RPChat plugin;
    private final double radius = 20.0;

    public RPCommands(RPChat plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
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

    private void handleMe(Player player, String rpName, String action) {
        Component message = Component.text("* " + rpName + " " + action, NamedTextColor.LIGHT_PURPLE);
        broadcastLocal(player, message);
    }

    private void handleDo(Player player, String rpName, String description) {
        Component message = Component.text("* " + description + " (( " + rpName + " ))", NamedTextColor.AQUA);
        broadcastLocal(player, message);
    }

    private void handleTry(Player player, String rpName, String action) {
        boolean success = ThreadLocalRandom.current().nextBoolean();
        Component result = success 
            ? Component.text(" [Успішно]", NamedTextColor.GREEN).decorate(TextDecoration.BOLD)
            : Component.text(" [Неуспішно]", NamedTextColor.RED).decorate(TextDecoration.BOLD);

        Component message = Component.text("* " + rpName + " намагається " + action, NamedTextColor.LIGHT_PURPLE)
            .append(result);
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
            .append(Component.text("\"" + speech + "\", - сказав ", NamedTextColor.WHITE))
            .append(Component.text(rpName, NamedTextColor.YELLOW))
            .append(Component.text(", " + action, NamedTextColor.LIGHT_PURPLE))
            .build();
            
        broadcastLocal(player, message);
    }

    private void handleB(Player player, String rpName, String oocMessage) {
        Component message = Component.text("(( [OOC] " + rpName + ": " + oocMessage + " ))", NamedTextColor.GRAY);
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
