package ua.rp.chat.auth;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Handles /login, /register, and /l commands.
 */
public class AuthCommands implements CommandExecutor {

    private static final TextColor SAND_GOLD = TextColor.color(0xE3C099);
    private static final TextColor PEBBLE_GRAY = TextColor.color(0xB0A8A0);
    private static final TextColor SOFT_GREEN = TextColor.color(0x99C3A2);
    private static final TextColor TERRACOTTA = TextColor.color(0xE3A899);
    private static final TextColor WARM_DUST = TextColor.color(0xAFA69E);
    private static final TextColor SEAFOAM = TextColor.color(0xA5C3C4);

    private final AuthManager authManager;

    public AuthCommands(AuthManager authManager) {
        this.authManager = authManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Цю команду може виконувати тільки гравець.", TERRACOTTA));
            return true;
        }

        String command = label.toLowerCase();

        return switch (command) {
            case "login", "l" -> handleLogin(player, args);
            case "register" -> handleRegister(player, args);
            default -> false;
        };
    }

    private boolean handleLogin(Player player, String[] args) {
        if (!authManager.isPendingAuth(player.getUniqueId())) {
            player.sendMessage(Component.text("Ви вже авторизовані.", PEBBLE_GRAY));
            return true;
        }

        // Find the player's active web token
        String token = null;
        for (java.util.Map.Entry<String, java.util.UUID> entry : authManager.getTokenToUuid().entrySet()) {
            if (entry.getValue().equals(player.getUniqueId())) {
                token = entry.getKey();
                break;
            }
        }

        if (token == null) {
            token = java.util.UUID.randomUUID().toString().substring(0, 8);
            authManager.getTokenToUuid().put(token, player.getUniqueId());
        }

        player.sendMessage(Component.text("Будь ласка, використовуйте візуальний інтерфейс для входу.", TERRACOTTA));
        authManager.sendAuthMenu(player, token);
        return true;
    }

    private boolean handleRegister(Player player, String[] args) {
        if (!authManager.isPendingAuth(player.getUniqueId())) {
            player.sendMessage(Component.text("Ви вже авторизовані.", PEBBLE_GRAY));
            return true;
        }

        // Find or generate active web token
        String token = null;
        for (java.util.Map.Entry<String, java.util.UUID> entry : authManager.getTokenToUuid().entrySet()) {
            if (entry.getValue().equals(player.getUniqueId())) {
                token = entry.getKey();
                break;
            }
        }

        if (token == null) {
            token = java.util.UUID.randomUUID().toString().substring(0, 8);
            authManager.getTokenToUuid().put(token, player.getUniqueId());
        }

        player.sendMessage(Component.text("Будь ласка, використовуйте візуальний інтерфейс для реєстрації.", TERRACOTTA));
        authManager.sendAuthMenu(player, token);
        return true;
    }
}
