package ua.rp.chat;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Locale;

public class RpChatService {
    private static final TextColor NAME = TextColor.color(0xE3C099);
    private static final TextColor MUTED = TextColor.color(0x9A9289);
    private static final TextColor SYSTEM = TextColor.color(0xB0A8A0);
    private static final TextColor WARNING = TextColor.color(0xD8A08F);

    private final RPChat plugin;

    public RpChatService(RPChat plugin) {
        this.plugin = plugin;
    }

    public String rpName(Player player) {
        String name = plugin.getAuthManager() != null ? plugin.getAuthManager().getRpName(player.getUniqueId()) : null;
        if (name == null || name.isBlank()) {
            name = player.getName().replace("_", " ");
        }
        return name;
    }

    public void sendSpeech(Player sender, RpChatChannel channel, String message) {
        deliver(sender, channel, message, formatSpeech(rpName(sender), channel, message), true);
    }

    public void sendAction(Player sender, String action) {
        deliver(sender, RpChatChannel.ACTION, action, formatAction(rpName(sender), action), true);
    }

    public void sendDescription(Player sender, String description) {
        deliver(sender, RpChatChannel.DESCRIPTION, description, formatDescription(rpName(sender), description), true);
    }

    public void sendTry(Player sender, String action, boolean success) {
        Component message = Component.text()
                .append(Component.text(rpName(sender), NAME))
                .append(Component.text(" пытается " + action + "... ", RpChatChannel.ACTION.messageColor()))
                .append(Component.text(success ? "[удачно]" : "[неудачно]", success ? TextColor.color(0x99C3A2) : TextColor.color(0xE3A899)))
                .build();
        deliver(sender, RpChatChannel.ACTION, action, message, true);
    }

    public void sendTodo(Player sender, String speech, String action) {
        Component message = Component.text()
                .append(Component.text("«" + speech + "» ", RpChatChannel.SPEAK.messageColor()))
                .append(Component.text("— ", MUTED))
                .append(Component.text(rpName(sender), NAME))
                .append(Component.text(", " + action, RpChatChannel.ACTION.messageColor()))
                .build();
        deliver(sender, RpChatChannel.SPEAK, speech + " * " + action, message, true);
    }

    public void sendOoc(Player sender, String message) {
        Component formatted = Component.text()
                .append(Component.text("[OOC] ", RpChatChannel.OOC.accentColor()))
                .append(Component.text(rpName(sender), MUTED))
                .append(Component.text(": ", MUTED))
                .append(Component.text(message, RpChatChannel.OOC.messageColor()))
                .build();
        deliver(sender, RpChatChannel.OOC, message, formatted, true);
    }

    public void sendSystemLocal(Player sender, RpChatChannel channel, Component message) {
        deliver(sender, channel, "", message, false);
    }

    private void deliver(Player sender, RpChatChannel channel, String rawMessage, Component exact, boolean notifyIfAlone) {
        int heard = 0;
        sender.sendMessage(exact);

        for (Player receiver : plugin.getServer().getOnlinePlayers()) {
            if (receiver.equals(sender)) {
                continue;
            }
            Delivery delivery = deliveryFor(sender, receiver, channel);
            if (delivery == Delivery.NONE) {
                if (receiver.hasPermission("rpchat.spy")) {
                    receiver.sendMessage(spyMessage(sender, channel, rawMessage));
                }
                continue;
            }
            receiver.sendMessage(delivery == Delivery.CLEAR ? exact : distantMessage(rpName(sender), channel, rawMessage));
            heard++;
        }

        plugin.getServer().getConsoleSender().sendMessage(consoleMessage(sender, channel, rawMessage, heard));
        if (notifyIfAlone && heard == 0) {
            sender.sendMessage(Component.text("Рядом никто этого не услышал.", SYSTEM));
        }
    }

    private Delivery deliveryFor(Player sender, Player receiver, RpChatChannel channel) {
        Location from = sender.getLocation();
        Location to = receiver.getLocation();
        if (from.getWorld() == null || to.getWorld() == null || !from.getWorld().equals(to.getWorld())) {
            return Delivery.NONE;
        }
        if (receiver.getGameMode() == org.bukkit.GameMode.SPECTATOR && !receiver.hasPermission("rpchat.spy")) {
            return Delivery.NONE;
        }
        double distance = from.distance(to);
        double obstructionPenalty = hasSoftLineOfSight(sender, receiver) ? 0.0 : 7.0;
        double effective = distance + obstructionPenalty;
        if (effective <= channel.clearRadius()) {
            return Delivery.CLEAR;
        }
        if (effective <= channel.fadeRadius()) {
            return Delivery.DISTANT;
        }
        return Delivery.NONE;
    }

    private boolean hasSoftLineOfSight(Player sender, Player receiver) {
        return sender.hasLineOfSight(receiver) || receiver.hasLineOfSight(sender);
    }

    private Component formatSpeech(String name, RpChatChannel channel, String message) {
        String verb = switch (channel) {
            case WHISPER -> " шепчет: ";
            case SHOUT -> " кричит: ";
            default -> " говорит: ";
        };
        return Component.text()
                .append(Component.text(name, NAME))
                .append(Component.text(verb, channel.accentColor()))
                .append(Component.text("«" + message + "»", channel.messageColor()))
                .build();
    }

    private Component formatAction(String name, String action) {
        return Component.text()
                .append(Component.text(name, NAME))
                .append(Component.text(" " + action, RpChatChannel.ACTION.messageColor()))
                .build();
    }

    private Component formatDescription(String name, String description) {
        return Component.text()
                .append(Component.text(description, RpChatChannel.DESCRIPTION.messageColor()))
                .append(Component.text(" — ", MUTED))
                .append(Component.text(name, MUTED))
                .build();
    }

    private Component distantMessage(String name, RpChatChannel channel, String rawMessage) {
        return switch (channel) {
            case WHISPER -> Component.text(name + " что-то тихо шепчет рядом.", MUTED);
            case SHOUT -> Component.text()
                    .append(Component.text(name, NAME))
                    .append(Component.text(" кричит издалека: ", channel.accentColor()))
                    .append(Component.text("«" + distantText(rawMessage) + "»", MUTED))
                    .build();
            case ACTION -> Component.text(name + " что-то делает неподалеку.", MUTED);
            case DESCRIPTION -> Component.text("Неподалеку заметно: " + distantText(rawMessage), MUTED);
            case OOC -> Component.text("[OOC] слышен обрывок разговора вне роли.", MUTED);
            default -> Component.text()
                    .append(Component.text(name, NAME))
                    .append(Component.text(" говорит где-то рядом, но слова слышны нечетко.", MUTED))
                    .build();
        };
    }

    private Component spyMessage(Player sender, RpChatChannel channel, String rawMessage) {
        return Component.text()
                .append(Component.text("[SPY:" + channel.id().toUpperCase(Locale.ROOT) + "] ", NamedTextColor.DARK_GRAY))
                .append(Component.text(sender.getName(), NamedTextColor.GRAY))
                .append(Component.text(": " + rawMessage, NamedTextColor.GRAY))
                .build();
    }

    private Component consoleMessage(Player sender, RpChatChannel channel, String rawMessage, int heard) {
        return Component.text()
                .append(Component.text("[RP:" + channel.id() + "] ", NamedTextColor.GRAY))
                .append(Component.text(sender.getName(), NamedTextColor.WHITE))
                .append(Component.text(" (" + heard + "): ", NamedTextColor.GRAY))
                .append(Component.text(rawMessage, NamedTextColor.WHITE))
                .build();
    }

    private String distantText(String raw) {
        if (raw == null || raw.isBlank()) {
            return "...";
        }
        String trimmed = raw.trim();
        if (trimmed.length() <= 12) {
            return trimmed.charAt(0) + "...";
        }
        int keep = Math.max(8, Math.min(24, trimmed.length() / 3));
        return trimmed.substring(0, keep).trim() + "...";
    }

    private enum Delivery {
        NONE,
        DISTANT,
        CLEAR
    }
}
