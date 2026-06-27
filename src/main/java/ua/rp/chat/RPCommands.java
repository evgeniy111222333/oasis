package ua.rp.chat;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.concurrent.ThreadLocalRandom;

public class RPCommands implements CommandExecutor {
    private final RPChat plugin;
    private final double radius = 20.0;

    // Soft Gold for UI Headers/Footers
    private final TextColor goldColor = TextColor.color(0xE8C58C);

    public RPCommands(RPChat plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        // Handle rpdemo command
        if (label.equalsIgnoreCase("rpdemo")) {
            return handleRpDemo(sender, args);
        }

        // Handle rpcrun command
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

    private boolean handleRpDemo(CommandSender sender, String[] args) {
        // Handle style selection
        if (args.length >= 2 && args[0].equalsIgnoreCase("select")) {
            if (!sender.hasPermission("rpchat.admin")) {
                sender.sendMessage(Component.text("У вас немає прав на зміну глобального стилю чату!", NamedTextColor.RED));
                return true;
            }

            int selStyle;
            try {
                selStyle = Integer.parseInt(args[1]);
            } catch (NumberFormatException e) {
                sender.sendMessage(Component.text("Невірний номер стилю!", NamedTextColor.RED));
                return true;
            }

            if (selStyle < 1 || selStyle > 10) {
                sender.sendMessage(Component.text("Вкажіть число від 1 до 10!", NamedTextColor.RED));
                return true;
            }

            plugin.setActiveStyle(selStyle);
            plugin.getServer().broadcast(
                Component.text()
                    .append(Component.text("★ ", goldColor))
                    .append(Component.text(sender.getName(), NamedTextColor.WHITE))
                    .append(Component.text(" змінив(ла) глобальний стиль чату на: ", goldColor))
                    .append(Component.text(ChatFormatter.STYLE_NAMES[selStyle - 1], NamedTextColor.GREEN))
                    .append(Component.text(" ★", goldColor))
                    .build()
            );
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage(Component.text("====== ПРЕМІУМ СТИЛІ ЧАТУ (КЛІКАБЕЛЬНІ) ======", goldColor));
            for (int i = 1; i <= 10; i++) {
                String name = ChatFormatter.STYLE_NAMES[i - 1];
                String desc = getStyleDescription(i);
                sendClickableDemo(sender, i, name, desc);
            }
            sender.sendMessage(Component.text("===========================================", goldColor));
            return true;
        }

        int styleNum;
        try {
            styleNum = Integer.parseInt(args[0]);
        } catch (NumberFormatException e) {
            sender.sendMessage(Component.text("Вкажіть число від 1 до 10!", NamedTextColor.RED));
            return true;
        }

        if (styleNum < 1 || styleNum > 10) {
            sender.sendMessage(Component.text("Вкажіть число від 1 до 10!", NamedTextColor.RED));
            return true;
        }

        sendStyleDemo(sender, styleNum);
        return true;
    }

    private String getStyleDescription(int id) {
        return switch (id) {
            case 1 -> "Мінімалістичний пастельний дизайн без зайвих символів";
            case 2 -> "Художній літературний формат сценарію або роману";
            case 3 -> "Контрастний холодний дизайн (геометричні розділювачі)";
            case 4 -> "Ніжний та затишний стиль з квітучою сакурою";
            case 5 -> "Неоновий кіберпанк із блискавками та яскравими тонами";
            case 6 -> "Класичний чорно-білий темний стиль (мінімум яскравості)";
            case 7 -> "Теплий персиково-оксамитовий вечірній стиль";
            case 8 -> "Природні деревні та лісові тони з осіннім листям";
            case 9 -> "Модний висококонтрастний стиль (винний та пудровий)";
            case 10 -> "Космічний стиль із мерехтливими зірками";
            default -> "";
        };
    }

    private void sendClickableDemo(CommandSender sender, int id, String name, String desc) {
        sender.sendMessage(Component.text()
            .append(Component.text(id + ". ", NamedTextColor.GRAY))
            .append(Component.text(name, goldColor))
            .append(Component.text(" — " + desc, NamedTextColor.WHITE))
            .clickEvent(ClickEvent.runCommand("/rpdemo " + id))
            .hoverEvent(HoverEvent.showText(Component.text("Натисніть для перегляду стилю " + name, NamedTextColor.GREEN)))
        );
    }

    private void sendStyleDemo(CommandSender sender, int style) {
        String styleName = ChatFormatter.STYLE_NAMES[style - 1];
        sender.sendMessage(Component.text("─── ДЕМОНСТРАЦІЯ: " + styleName.toUpperCase() + " ───", goldColor));
        
        String name = "John Doe";
        String speech = "Привіт усім, як ваші справи?";
        String meAction = "дістав телефон з кишені штанів";
        String doDescription = "Телефон знаходиться у правій руці";
        String tryAction = "завести двигун автомобіля";
        String todoSpeech = "Зачекайте одну хвилину";
        String todoAction = "шукаючи ключі в кишені";
        String oocMsg = "це позаігрове OOC повідомлення";

        // Show examples of all 5 chat elements
        sender.sendMessage(Component.text().append(Component.text("[Чат] ", NamedTextColor.GRAY)).append(ChatFormatter.formatSpeech(name, speech, style)));
        sender.sendMessage(Component.text().append(Component.text("[/me] ", NamedTextColor.GRAY)).append(ChatFormatter.formatMe(name, meAction, style)));
        sender.sendMessage(Component.text().append(Component.text("[/do] ", NamedTextColor.GRAY)).append(ChatFormatter.formatDo(name, doDescription, style)));
        sender.sendMessage(Component.text().append(Component.text("[/try] ", NamedTextColor.GRAY)).append(ChatFormatter.formatTry(name, tryAction, true, style)));
        sender.sendMessage(Component.text().append(Component.text("[/todo] ", NamedTextColor.GRAY)).append(ChatFormatter.formatTodo(name, todoSpeech, todoAction, style)));
        sender.sendMessage(Component.text().append(Component.text("[/b] ", NamedTextColor.GRAY)).append(ChatFormatter.formatB(name, oocMsg, style)));
        
        sender.sendMessage(Component.text("───────────────────────────────", goldColor));
        
        // Interactive "Apply" button
        if (sender.hasPermission("rpchat.admin")) {
            sender.sendMessage(Component.text()
                .append(Component.text("➔ [ КЛІКНІТЬ ТУТ, ЩОБ ЗАСТОСУВАТИ ЦЕЙ СТИЛЬ ]", NamedTextColor.GREEN))
                .clickEvent(ClickEvent.runCommand("/rpdemo select " + style))
                .hoverEvent(HoverEvent.showText(Component.text("Зробити цей стиль глобальним для всього серверу", NamedTextColor.YELLOW)))
            );
            sender.sendMessage(Component.text("───────────────────────────────", goldColor));
        }
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
        Component message = ChatFormatter.formatMe(rpName, action, plugin.getActiveStyle());
        broadcastLocal(player, message);
    }

    private void handleDo(Player player, String rpName, String description) {
        Component message = ChatFormatter.formatDo(rpName, description, plugin.getActiveStyle());
        broadcastLocal(player, message);
    }

    private void handleTry(Player player, String rpName, String action) {
        boolean success = ThreadLocalRandom.current().nextBoolean();
        Component message = ChatFormatter.formatTry(rpName, action, success, plugin.getActiveStyle());
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

        Component message = ChatFormatter.formatTodo(rpName, speech, action, plugin.getActiveStyle());
        broadcastLocal(player, message);
    }

    private void handleB(Player player, String rpName, String oocMessage) {
        Component message = ChatFormatter.formatB(rpName, oocMessage, plugin.getActiveStyle());
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
