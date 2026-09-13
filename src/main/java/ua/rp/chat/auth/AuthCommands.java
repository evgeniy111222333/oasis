package ua.rp.chat.auth;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class AuthCommands implements CommandExecutor {
    private static final TextColor TERRACOTTA = TextColor.color(0xE3A899);
    private static final TextColor PEBBLE_GRAY = TextColor.color(0xB0A8A0);

    private final AuthManager authManager;

    public AuthCommands(AuthManager authManager) {
        this.authManager = authManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Эту команду может выполнить только игрок.", TERRACOTTA));
            return true;
        }

        return switch (label.toLowerCase()) {
            case "login", "l" -> handleLogin(player);
            case "register" -> handleRegister(player);
            default -> false;
        };
    }

    private boolean handleLogin(Player player) {
        if (!authManager.isPendingAuth(player.getUniqueId())) {
            player.sendMessage(Component.text("Вы уже авторизованы.", PEBBLE_GRAY));
            return true;
        }
        authManager.openAuthOverlay(player, activeOrNewToken(player));
        player.sendMessage(Component.text("Открываем визуальное окно входа.", TERRACOTTA));
        return true;
    }

    private boolean handleRegister(Player player) {
        if (!authManager.isPendingAuth(player.getUniqueId())) {
            player.sendMessage(Component.text("Вы уже авторизованы.", PEBBLE_GRAY));
            return true;
        }
        authManager.openAuthOverlay(player, activeOrNewToken(player));
        player.sendMessage(Component.text("Открываем визуальное окно регистрации.", TERRACOTTA));
        return true;
    }

    private String activeOrNewToken(Player player) {
        String token = authManager.getActiveToken(player.getUniqueId());
        if (token != null) {
            return token;
        }
        token = java.util.UUID.randomUUID().toString().substring(0, 8);
        authManager.getTokenToUuid().put(token, player.getUniqueId());
        return token;
    }
}
