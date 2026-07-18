package ua.rp.chat.auth;

import net.kyori.adventure.text.Component;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** Player-owned entry point for the short-lived appearance editor. */
public final class AppearanceCommands implements CommandExecutor {
    private final AuthManager authManager;

    public AppearanceCommands(AuthManager authManager) {
        this.authManager = authManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Эта команда доступна только в игре."));
            return true;
        }
        if (!authManager.requestAppearanceChange(player)) {
            player.sendMessage(Component.text("Сменить внешность можно после входа в персонажа."));
        }
        return true;
    }
}
