package ua.rp.chat;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

public class RPCommands implements CommandExecutor {
    private static final TextColor GOLD = TextColor.color(0xE8C58C);
    private static final TextColor MUTED = TextColor.color(0xB0A8A0);

    private final RPChat plugin;

    public RPCommands(RPChat plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String cmd = label.toLowerCase(Locale.ROOT);

        if ("rpreload".equals(cmd)) {
            return handleReload(sender, args);
        }
        if ("rpcrun".equals(cmd)) {
            return handleRpcRun(sender, args);
        }
        if ("rpdemo".equals(cmd)) {
            return handleDemo(sender, args);
        }
        if ("rpcombatdebug".equals(cmd)) {
            return handleCombatDebug(sender);
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Эту команду может выполнить только игрок.", NamedTextColor.RED));
            return true;
        }
        if (plugin.getAuthManager() != null && plugin.getAuthManager().isPendingAuth(player.getUniqueId())) {
            player.sendMessage(Component.text("Сначала авторизуйтесь.", NamedTextColor.RED));
            return true;
        }
        if (args.length == 0) {
            sendUsage(player, cmd);
            return true;
        }

        dispatchPlayerCommand(player, cmd, String.join(" ", args).trim());
        return true;
    }

    private void dispatchPlayerCommand(Player player, String cmd, String message) {
        switch (cmd) {
            case "me" -> plugin.getRpChatService().sendAction(player, message);
            case "do" -> plugin.getRpChatService().sendDescription(player, message);
            case "try" -> plugin.getRpChatService().sendTry(player, message, ThreadLocalRandom.current().nextBoolean());
            case "todo" -> handleTodo(player, message);
            case "b" -> plugin.getRpChatService().sendOoc(player, message);
            case "w", "whisper" -> plugin.getRpChatService().sendSpeech(player, RpChatChannel.WHISPER, message);
            case "s", "shout" -> plugin.getRpChatService().sendSpeech(player, RpChatChannel.SHOUT, message);
            case "say" -> plugin.getRpChatService().sendSpeech(player, RpChatChannel.SPEAK, message);
            default -> sendUsage(player, cmd);
        }
    }

    private void handleTodo(Player player, String rawText) {
        if (!rawText.contains("*")) {
            player.sendMessage(Component.text("Использование: /todo [реплика] * [действие]", NamedTextColor.RED));
            return;
        }
        String[] parts = rawText.split("\\*", 2);
        String speech = parts[0].trim();
        String action = parts[1].trim();
        if (speech.isBlank() || action.isBlank()) {
            player.sendMessage(Component.text("В /todo должны быть и реплика, и действие.", NamedTextColor.RED));
            return;
        }
        plugin.getRpChatService().sendTodo(player, speech, action);
    }

    private boolean handleReload(CommandSender sender, String[] args) {
        if (sender instanceof Player player && !player.hasPermission("rpchat.admin")) {
            player.sendMessage(Component.text("Недостаточно прав.", NamedTextColor.RED));
            return true;
        }
        String targetPlugin = args.length > 0 ? args[0] : "RPChat";
        long start = System.currentTimeMillis();
        boolean success = PluginReloader.reload(targetPlugin, plugin);
        long duration = System.currentTimeMillis() - start;
        sender.sendMessage(Component.text(
                success
                        ? "Плагин " + targetPlugin + " перезагружен за " + duration + " мс."
                        : "Не удалось перезагрузить " + targetPlugin + ". Проверьте консоль.",
                success ? NamedTextColor.GREEN : NamedTextColor.RED));
        return true;
    }

    private boolean handleRpcRun(CommandSender sender, String[] args) {
        if (sender instanceof Player player && !player.hasPermission("rpchat.admin")) {
            player.sendMessage(Component.text("Недостаточно прав.", NamedTextColor.RED));
            return true;
        }
        if (args.length < 3) {
            sender.sendMessage(Component.text("Использование: /rpcrun [player] [me/do/try/todo/b/w/s/say] [текст]", NamedTextColor.RED));
            return true;
        }

        Player target = plugin.getServer().getPlayer(args[0]);
        if (target == null || !target.isOnline()) {
            sender.sendMessage(Component.text("Игрок " + args[0] + " не найден.", NamedTextColor.RED));
            return true;
        }
        String subCommand = args[1].toLowerCase(Locale.ROOT);
        String message = String.join(" ", Arrays.copyOfRange(args, 2, args.length)).trim();
        dispatchPlayerCommand(target, subCommand, message);
        sender.sendMessage(Component.text("Выполнено от имени " + target.getName() + ".", NamedTextColor.GREEN));
        return true;
    }

    private boolean handleDemo(CommandSender sender, String[] args) {
        if (args.length >= 2 && "select".equalsIgnoreCase(args[0])) {
            if (sender instanceof Player player && !player.hasPermission("rpchat.admin")) {
                player.sendMessage(Component.text("Недостаточно прав.", NamedTextColor.RED));
                return true;
            }
            try {
                int style = Integer.parseInt(args[1]);
                plugin.setActiveStyle(style);
                sender.sendMessage(Component.text("Стиль чата установлен: " + ChatFormatter.STYLE_NAMES[Math.max(0, Math.min(9, style - 1))], NamedTextColor.GREEN));
            } catch (Exception e) {
                sender.sendMessage(Component.text("Укажите число от 1 до 10.", NamedTextColor.RED));
            }
            return true;
        }

        sender.sendMessage(Component.text("RP Chat: дистанционная система речи", GOLD));
        sender.sendMessage(Component.text("Обычный чат или /say — 24 блока, дальний шум до 34.", MUTED));
        sender.sendMessage(Component.text("/w или ~текст — шепот 7 блоков, дальний шум до 11.", MUTED));
        sender.sendMessage(Component.text("/s или !текст — крик 48 блоков, дальний шум до 68.", MUTED));
        sender.sendMessage(Component.text("/me, /do, /try, /todo, /b — локальные RP/OOC действия.", MUTED));
        return true;
    }

    private boolean handleCombatDebug(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Эту команду может выполнить только игрок-оператор.", NamedTextColor.RED));
            return true;
        }
        if (!player.isOp()) {
            player.sendMessage(Component.text("Команда доступна только операторам сервера.", NamedTextColor.RED));
            return true;
        }
        boolean enabled = plugin.getCombatManager().toggleDebug(player);
        player.sendMessage(Component.text(
                enabled ? "RP combat debug включен: броски боя будут видны только вам."
                        : "RP combat debug выключен.",
                enabled ? NamedTextColor.GREEN : NamedTextColor.GRAY
        ));
        return true;
    }

    private void sendUsage(Player player, String label) {
        String usage = switch (label) {
            case "me" -> "/me [действие]";
            case "do" -> "/do [описание ситуации]";
            case "try" -> "/try [попытка действия]";
            case "todo" -> "/todo [реплика] * [действие]";
            case "b" -> "/b [локальное OOC]";
            case "w", "whisper" -> "/w [шепот]";
            case "s", "shout" -> "/s [крик]";
            case "say" -> "/say [фраза]";
            default -> "Обычный чат, !крик, ~шепот, /me, /do, /try, /todo, /b";
        };
        player.sendMessage(Component.text("Использование: " + usage, NamedTextColor.RED));
    }
}
