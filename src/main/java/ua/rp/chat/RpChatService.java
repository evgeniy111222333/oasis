package ua.rp.chat;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class RpChatService {
    private static final TextColor NAME = TextColor.color(0xE3C099);
    private static final TextColor MUTED = TextColor.color(0x9A9289);
    private static final int BUBBLE_LIFETIME_TICKS = 86;
    private static final int BUBBLE_FADE_IN_TICKS = 8;
    private static final int BUBBLE_FADE_OUT_TICKS = 18;

    private final RPChat plugin;
    private final Map<UUID, TextDisplay> activeBubbles = new ConcurrentHashMap<>();

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
        deliverSpeech(sender, channel, message, true);
        showSpeechBubble(sender, channel, message);
    }

    public void sendAction(Player sender, String action) {
        deliver(sender, RpChatChannel.ACTION, action, formatAction(rpName(sender), action), true);
    }

    public void sendActionHighlighted(Player sender, String action, String... highlightedNames) {
        deliver(sender, RpChatChannel.ACTION, action, formatActionHighlighted(rpName(sender), action, highlightedNames), true);
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
        deliver(sender, RpChatChannel.TRY, action, message, true, success ? 1 : 2);
    }

    public void sendTodo(Player sender, String speech, String action) {
        deliverTodo(sender, speech, action, true);
        showSpeechBubble(sender, RpChatChannel.SPEAK, speech);
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
        sendFeed(sender, sender, channel, false, "", PlainTextComponentSerializer.plainText().serialize(message));
    }

    private void deliverSpeech(Player sender, RpChatChannel channel, String rawMessage, boolean notifyIfAlone) {
        int heard = 0;
        sender.sendMessage(formatSpeech(rpName(sender), channel, rawMessage));
        if (rawMessage != null && !rawMessage.isBlank()) {
            sendFeed(sender, sender, channel, false, rpName(sender), rawMessage);
        }

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
            String receiverName = displayNameFor(receiver, sender);
            receiver.sendMessage(delivery == Delivery.CLEAR
                    ? formatSpeech(receiverName, channel, rawMessage)
                    : distantMessage(receiverName, channel, rawMessage));
            sendFeed(receiver, sender, channel, delivery == Delivery.DISTANT, receiverName,
                    delivery == Delivery.CLEAR ? rawMessage : distantFeedText(channel, rawMessage));
            heard++;
        }

        plugin.getServer().getConsoleSender().sendMessage(consoleMessage(sender, channel, rawMessage, heard));
        notifyIfAlone(sender, heard, notifyIfAlone);
    }

    private void deliverTodo(Player sender, String speech, String action, boolean notifyIfAlone) {
        String raw = speech + " * " + action;
        int heard = 0;
        sender.sendMessage(formatTodo(rpName(sender), speech, action));
        sendFeed(sender, sender, RpChatChannel.TODO, false, rpName(sender), speech + "\u001f" + action);

        for (Player receiver : plugin.getServer().getOnlinePlayers()) {
            if (receiver.equals(sender)) {
                continue;
            }
            Delivery delivery = deliveryFor(sender, receiver, RpChatChannel.SPEAK);
            if (delivery == Delivery.NONE) {
                if (receiver.hasPermission("rpchat.spy")) {
                    receiver.sendMessage(spyMessage(sender, RpChatChannel.SPEAK, raw));
                }
                continue;
            }
            String receiverName = displayNameFor(receiver, sender);
            receiver.sendMessage(delivery == Delivery.CLEAR
                    ? formatTodo(receiverName, speech, action)
                    : distantMessage(receiverName, RpChatChannel.SPEAK, speech));
            sendFeed(receiver, sender, delivery == Delivery.CLEAR ? RpChatChannel.TODO : RpChatChannel.SPEAK,
                    delivery == Delivery.DISTANT, receiverName,
                    delivery == Delivery.CLEAR ? speech + "\u001f" + action : distantFeedText(RpChatChannel.SPEAK, speech));
            heard++;
        }

        plugin.getServer().getConsoleSender().sendMessage(consoleMessage(sender, RpChatChannel.SPEAK, raw, heard));
        notifyIfAlone(sender, heard, notifyIfAlone);
    }

    private void deliver(Player sender, RpChatChannel channel, String rawMessage, Component exact, boolean notifyIfAlone) {
        deliver(sender, channel, rawMessage, exact, notifyIfAlone, 0);
    }

    private void deliver(Player sender, RpChatChannel channel, String rawMessage, Component exact, boolean notifyIfAlone, int outcome) {
        int heard = 0;
        sender.sendMessage(exact);
        sendFeed(sender, sender, channel, false, rpName(sender), rawMessage, outcome);

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
            String name = displayNameFor(receiver, sender);
            sendFeed(receiver, sender, channel, delivery == Delivery.DISTANT, name,
                    delivery == Delivery.CLEAR ? rawMessage : distantFeedText(channel, rawMessage), outcome);
            heard++;
        }

        plugin.getServer().getConsoleSender().sendMessage(consoleMessage(sender, channel, rawMessage, heard));
        notifyIfAlone(sender, heard, notifyIfAlone);
    }

    private void notifyIfAlone(Player sender, int heard, boolean notifyIfAlone) {
        // Silence is an IC outcome; no delivery report is shown to the speaker.
    }

    private void sendFeed(Player receiver, Player speaker, RpChatChannel channel, boolean distant, String name, String text) {
        sendFeed(receiver, speaker, channel, distant, name, text, 0);
    }

    private void sendFeed(Player receiver, Player speaker, RpChatChannel channel, boolean distant, String name, String text, int outcome) {
        if (receiver == null || !receiver.isOnline()) return;
        receiver.sendPluginMessage(plugin, RpChatFeedProtocol.CHANNEL,
                RpChatFeedProtocol.message(speaker == null ? null : speaker.getUniqueId(), channel, distant, name, text, outcome));
    }

    private String distantFeedText(RpChatChannel channel, String rawMessage) {
        return switch (channel) {
            case WHISPER -> "что-то тихо шепчет рядом.";
            case ACTION, TRY -> "что-то делает неподалеку.";
            case DESCRIPTION -> "Неподалеку заметно: " + distantText(rawMessage);
            case SHOUT -> "«" + distantText(rawMessage) + "»";
            default -> "говорит где-то рядом, но слова слышны нечетко.";
        };
    }

    private void showSpeechBubble(Player sender, RpChatChannel channel, String rawMessage) {
        if (sender == null || !sender.isOnline() || rawMessage == null || rawMessage.isBlank()) {
            return;
        }

        TextDisplay previous = activeBubbles.remove(sender.getUniqueId());
        if (previous != null && previous.isValid()) {
            previous.remove();
        }

        String text = bubbleText(channel, rawMessage);
        Location start = sender.getLocation().add(0.0, 2.72, 0.0);
        TextDisplay display = sender.getWorld().spawn(start, TextDisplay.class, td -> {
            td.text(Component.text(text, channel.messageColor()));
            td.setBillboard(Display.Billboard.CENTER);
            td.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            td.setShadowed(true);
            td.setSeeThrough(false);
            td.setGravity(false);
            td.setPersistent(false);
            td.setLineWidth(170);
            td.setTextOpacity((byte) 0);
        });
        activeBubbles.put(sender.getUniqueId(), display);
        updateBubbleVisibility(sender, channel, display);

        new BukkitRunnable() {
            private int age;

            @Override
            public void run() {
                if (!sender.isOnline() || !display.isValid() || activeBubbles.get(sender.getUniqueId()) != display) {
                    removeBubble(sender.getUniqueId(), display);
                    cancel();
                    return;
                }

                float floatUp = Math.min(0.22f, age * 0.0035f);
                display.teleport(sender.getLocation().add(0.0, 2.72 + floatUp, 0.0));
                display.setTextOpacity((byte) opacityFor(age));
                updateBubbleVisibility(sender, channel, display);

                age += 2;
                if (age > BUBBLE_LIFETIME_TICKS) {
                    removeBubble(sender.getUniqueId(), display);
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 0L, 2L);
    }

    private void removeBubble(UUID uuid, TextDisplay display) {
        activeBubbles.remove(uuid, display);
        if (display != null && display.isValid()) {
            display.remove();
        }
    }

    private void updateBubbleVisibility(Player sender, RpChatChannel channel, TextDisplay display) {
        if (display == null || !display.isValid()) {
            return;
        }
        for (Player receiver : plugin.getServer().getOnlinePlayers()) {
            if (receiver.equals(sender)) {
                receiver.showEntity(plugin, display);
                continue;
            }
            if (deliveryFor(sender, receiver, channel) == Delivery.CLEAR) {
                receiver.showEntity(plugin, display);
            } else {
                receiver.hideEntity(plugin, display);
            }
        }
    }

    private int opacityFor(int age) {
        if (age < BUBBLE_FADE_IN_TICKS) {
            return Math.max(30, Math.round(235.0f * age / BUBBLE_FADE_IN_TICKS));
        }
        int fadeStart = BUBBLE_LIFETIME_TICKS - BUBBLE_FADE_OUT_TICKS;
        if (age > fadeStart) {
            float left = Math.max(0.0f, (BUBBLE_LIFETIME_TICKS - age) / (float) BUBBLE_FADE_OUT_TICKS);
            return Math.round(235.0f * left);
        }
        return 235;
    }

    private String bubbleText(RpChatChannel channel, String rawMessage) {
        String text = rawMessage.trim().replaceAll("\\s+", " ");
        if (text.length() > 84) {
            text = text.substring(0, 81).trim() + "...";
        }
        return switch (channel) {
            case WHISPER -> "«" + text + "»";
            case SHOUT -> "«" + text.toUpperCase(Locale.ROOT) + "!»";
            case ACTION -> text;
            case OOC -> "[OOC] " + text;
            default -> "«" + text + "»";
        };
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

    private String displayNameFor(Player viewer, Player target) {
        if (plugin.getAcquaintanceManager() != null) {
            return plugin.getAcquaintanceManager().chatNameFor(viewer, target);
        }
        return rpName(target);
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

    private Component formatTodo(String name, String speech, String action) {
        return Component.text()
                .append(Component.text("«" + speech + "» ", RpChatChannel.SPEAK.messageColor()))
                .append(Component.text("- ", MUTED))
                .append(Component.text(name, NAME))
                .append(Component.text(", " + action, RpChatChannel.ACTION.messageColor()))
                .build();
    }

    private Component formatAction(String name, String action) {
        return Component.text()
                .append(Component.text(name, NAME))
                .append(Component.text(" " + action, RpChatChannel.ACTION.messageColor()))
                .build();
    }

    private Component formatActionHighlighted(String name, String action, String... highlightedNames) {
        Component result = Component.text()
                .append(Component.text(name, NAME))
                .append(Component.text(" ", RpChatChannel.ACTION.messageColor()))
                .build();
        String remaining = action == null ? "" : action;
        while (!remaining.isEmpty()) {
            Highlight hit = firstHighlight(remaining, highlightedNames);
            if (hit == null) {
                return result.append(Component.text(remaining, RpChatChannel.ACTION.messageColor()));
            }
            if (hit.start > 0) {
                result = result.append(Component.text(remaining.substring(0, hit.start), RpChatChannel.ACTION.messageColor()));
            }
            result = result.append(Component.text(hit.name, NAME));
            remaining = remaining.substring(hit.start + hit.name.length());
        }
        return result;
    }

    private Highlight firstHighlight(String text, String... names) {
        if (text == null || text.isBlank() || names == null) {
            return null;
        }
        Highlight best = null;
        for (String name : names) {
            if (name == null || name.isBlank()) {
                continue;
            }
            int index = text.indexOf(name);
            if (index >= 0 && (best == null || index < best.start)) {
                best = new Highlight(index, name);
            }
        }
        return best;
    }

    private Component formatDescription(String name, String description) {
        return Component.text()
                .append(Component.text(description, RpChatChannel.DESCRIPTION.messageColor()))
                .append(Component.text(" - ", MUTED))
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
            case ACTION, TRY -> Component.text(name + " что-то делает неподалеку.", MUTED);
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

    private record Highlight(int start, String name) {
    }
}
