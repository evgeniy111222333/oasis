package ua.rp.chat;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import java.util.concurrent.ThreadLocalRandom;

public class RPCommands implements CommandExecutor {
    private final RPChat plugin;
    private final double radius = 20.0;

    // Hex Color Palette
    private final TextColor goldColor = TextColor.color(0xE8C58C);      // Soft Gold
    private final TextColor purpleColor = TextColor.color(0xC68BFF);    // Purple
    private final TextColor actionColor = TextColor.color(0xD6A2E8);    // Soft Purple
    private final TextColor aquaColor = TextColor.color(0x81ECEC);      // Aqua
    private final TextColor blueColor = TextColor.color(0x70A1FF);      // Soft Blue
    private final TextColor grayColor = TextColor.color(0x95A5A6);      // Medium Gray
    private final TextColor darkGrayColor = TextColor.color(0xA9A9A9);  // Dark Gray

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
        int id = plugin.getIdManager().getId(player);

        switch (label.toLowerCase()) {
            case "me" -> handleMe(player, rpName, id, message);
            case "do" -> handleDo(player, rpName, id, message);
            case "try" -> handleTry(player, rpName, id, message);
            case "todo" -> handleTodo(player, rpName, id, message);
            case "b" -> handleB(player, rpName, id, message);
        }

        return true;
    }

    private void handleMe(Player player, String rpName, int id, String action) {
        Component message = Component.text()
            .append(Component.text(rpName, purpleColor))
            .append(Component.text(" [", darkGrayColor))
            .append(Component.text(id, grayColor))
            .append(Component.text("] ", darkGrayColor))
            .append(Component.text(action, actionColor).decorate(TextDecoration.ITALIC))
            .build();
            
        broadcastLocal(player, message);
    }

    private void handleDo(Player player, String rpName, int id, String description) {
        Component message = Component.text()
            .append(Component.text(description, aquaColor).decorate(TextDecoration.ITALIC))
            .append(Component.text(" (( ", blueColor))
            .append(Component.text(rpName, blueColor))
            .append(Component.text(" [", darkGrayColor))
            .append(Component.text(id, grayColor))
            .append(Component.text("] ))", darkGrayColor))
            .build();
            
        broadcastLocal(player, message);
    }

    private void handleTry(Player player, String rpName, int id, String action) {
        boolean success = ThreadLocalRandom.current().nextBoolean();
        Component result = success 
            ? Component.text(" [УСПІШНО]", TextColor.color(0x2ECC71)).decorate(TextDecoration.BOLD)
            : Component.text(" [НЕУСПІШНО]", TextColor.color(0xE74C3C)).decorate(TextDecoration.BOLD);

        Component message = Component.text()
            .append(Component.text(rpName, purpleColor))
            .append(Component.text(" [", darkGrayColor))
            .append(Component.text(id, grayColor))
            .append(Component.text("] ", darkGrayColor))
            .append(Component.text("намагається " + action + "... ", actionColor).decorate(TextDecoration.ITALIC))
            .append(result)
            .build();
            
        broadcastLocal(player, message);
    }

    private void handleTodo(Player player, String rpName, int id, String rawText) {
        if (!rawText.contains("*")) {
            player.sendMessage(Component.text("Помилка! Використовуйте символ '*' для розділення мови та дії. Приклад: /todo Привіт! * потиснув руку", NamedTextColor.RED));
            return;
        }

        String[] parts = rawText.split("\\*", 2);
        String speech = parts[0].trim();
        String action = parts[1].trim();

        // Convert trailing action to gerund participle form visually or just print
        Component message = Component.text()
            .append(Component.text("«" + speech + "»", NamedTextColor.WHITE))
            .append(Component.text(" — сказав(ла) ", goldColor))
            .append(Component.text(rpName, goldColor))
            .append(Component.text(" [" + id + "], ", darkGrayColor))
            .append(Component.text(action, actionColor).decorate(TextDecoration.ITALIC))
            .build();
            
        broadcastLocal(player, message);
    }

    private void handleB(Player player, String rpName, int id, String oocMessage) {
        Component message = Component.text("(( OOC | " + rpName + " [" + id + "]: " + oocMessage + " ))", grayColor);
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
