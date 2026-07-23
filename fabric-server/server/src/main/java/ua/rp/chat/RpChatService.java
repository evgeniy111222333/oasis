package ua.rp.chat;

import ua.rp.chat.mixin.TextDisplayAccessor;

import ua.rp.chat.mixin.DisplayAccessor;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Display.TextDisplay;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import ua.rp.chat.client.rpfeed.RpChatFeedPayload;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class RpChatService {
    private static final TextColor NAME = TextColor.fromRgb(0xE3C099);
    private static final TextColor MUTED = TextColor.fromRgb(0x9A9289);
    private static final int BUBBLE_LIFETIME_TICKS = 86;
    private static final int BUBBLE_FADE_IN_TICKS = 8;
    private static final int BUBBLE_FADE_OUT_TICKS = 18;

    private final RPChat plugin;
    private final Map<UUID, BubbleData> activeBubbles = new ConcurrentHashMap<>();

    private record BubbleData(TextDisplay display, ServerPlayer sender, RpChatChannel channel, int[] age) {
        public int getAge() { return age[0]; }
        public void incrementAge() { age[0] += 2; }
    }

    public RpChatService(RPChat plugin) {
        this.plugin = plugin;
    }

    public void tickBubbles() {
        activeBubbles.forEach((uuid, bubble) -> {
            ServerPlayer sender = plugin.getServer().getPlayerList().getPlayer(uuid);
            if (sender == null || !bubble.display.isAlive() || activeBubbles.get(uuid) != bubble) {
                removeBubble(uuid, bubble.display);
                return;
            }

            bubble.incrementAge();
            if (bubble.getAge() > BUBBLE_LIFETIME_TICKS) {
                removeBubble(uuid, bubble.display);
                return;
            }

            float floatUp = Math.min(0.22f, bubble.getAge() * 0.0035f);
            Vec3 pos = sender.position().add(0.0, 2.72 + floatUp, 0.0);
            bubble.display.setPos(pos.x, pos.y, pos.z);
            
            // Text opacity updates
            int opacityVal = opacityFor(bubble.getAge());
            ((TextDisplayAccessor) (Object) bubble.display).eclipseserver$setTextOpacity((byte) opacityVal);

            updateBubbleVisibility(sender, bubble.channel, bubble.display);
        });
    }

    public String rpName(ServerPlayer player) {
        String name = plugin.getAuthManager() != null ? plugin.getAuthManager().getRpName(player.getUUID()) : null;
        if (name == null || name.isBlank()) {
            name = player.getName().getString().replace("_", " ");
        }
        return name;
    }

    public void sendSpeech(ServerPlayer sender, RpChatChannel channel, String message) {
        deliverSpeech(sender, channel, message, true);
        showSpeechBubble(sender, channel, message);
    }

    public void sendAction(ServerPlayer sender, String action) {
        deliver(sender, RpChatChannel.ACTION, action, formatAction(rpName(sender), action), true);
    }

    public void sendActionHighlighted(ServerPlayer sender, String action, String... highlightedNames) {
        deliver(sender, RpChatChannel.ACTION, action, formatActionHighlighted(rpName(sender), action, highlightedNames), true);
    }

    public void sendDescription(ServerPlayer sender, String description) {
        deliver(sender, RpChatChannel.DESCRIPTION, description, formatDescription(rpName(sender), description), true);
    }

    public void sendTry(ServerPlayer sender, String action, boolean success) {
        Component message = Component.empty()
                .append(Component.literal(rpName(sender)).withStyle(s -> s.withColor(NAME)))
                .append(Component.literal(" пытается " + action + "... ").withStyle(s -> s.withColor(RpChatChannel.ACTION.messageColor())))
                .append(Component.literal(success ? "[удачно]" : "[неудачно]").withStyle(s -> s.withColor(success ? TextColor.fromRgb(0x99C3A2) : TextColor.fromRgb(0xE3A899))));
        deliver(sender, RpChatChannel.TRY, action, message, true, success ? 1 : 2);
    }

    public void sendTodo(ServerPlayer sender, String speech, String action) {
        deliverTodo(sender, speech, action, true);
        showSpeechBubble(sender, RpChatChannel.SPEAK, speech);
    }

    public void sendOoc(ServerPlayer sender, String message) {
        Component formatted = Component.empty()
                .append(Component.literal("[OOC] ").withStyle(s -> s.withColor(RpChatChannel.OOC.accentColor())))
                .append(Component.literal(rpName(sender)).withStyle(s -> s.withColor(MUTED)))
                .append(Component.literal(": ").withStyle(s -> s.withColor(MUTED)))
                .append(Component.literal(message).withStyle(s -> s.withColor(RpChatChannel.OOC.messageColor())));
        deliver(sender, RpChatChannel.OOC, message, formatted, true);
    }

    public void sendSystemLocal(ServerPlayer sender, RpChatChannel channel, Component message) {
        deliver(sender, channel, "", message, false);
        sendFeed(sender, sender, channel, false, "", message.getString());
    }

    private void deliverSpeech(ServerPlayer sender, RpChatChannel channel, String rawMessage, boolean notifyIfAlone) {
        int heard = 0;
        sender.sendSystemMessage(formatSpeech(rpName(sender), channel, rawMessage));
        if (rawMessage != null && !rawMessage.isBlank()) {
            sendFeed(sender, sender, channel, false, rpName(sender), rawMessage);
        }

        for (ServerPlayer receiver : plugin.getServer().getPlayerList().getPlayers()) {
            if (receiver.equals(sender)) {
                continue;
            }
            Delivery delivery = deliveryFor(sender, receiver, channel);
            if (delivery == Delivery.NONE) {
                if (RPChat.hasPermission(receiver, "rpchat.spy", 2)) {
                    receiver.sendSystemMessage(spyMessage(sender, channel, rawMessage));
                }
                continue;
            }
            String receiverName = displayNameFor(receiver, sender);
            receiver.sendSystemMessage(delivery == Delivery.CLEAR
                    ? formatSpeech(receiverName, channel, rawMessage)
                    : distantMessage(receiverName, channel, rawMessage));
            sendFeed(receiver, sender, channel, delivery == Delivery.DISTANT, receiverName,
                    delivery == Delivery.CLEAR ? rawMessage : distantFeedText(channel, rawMessage));
            heard++;
        }

        plugin.getLogger().info("[RP:" + channel.id() + "] " + sender.getName().getString() + " (" + heard + "): " + rawMessage);
        notifyIfAlone(sender, heard, notifyIfAlone);
    }

    private void deliverTodo(ServerPlayer sender, String speech, String action, boolean notifyIfAlone) {
        String raw = speech + " * " + action;
        int heard = 0;
        sender.sendSystemMessage(formatTodo(rpName(sender), speech, action));
        sendFeed(sender, sender, RpChatChannel.TODO, false, rpName(sender), speech + "\u001f" + action);

        for (ServerPlayer receiver : plugin.getServer().getPlayerList().getPlayers()) {
            if (receiver.equals(sender)) {
                continue;
            }
            Delivery delivery = deliveryFor(sender, receiver, RpChatChannel.SPEAK);
            if (delivery == Delivery.NONE) {
                if (RPChat.hasPermission(receiver, "rpchat.spy", 2)) {
                    receiver.sendSystemMessage(spyMessage(sender, RpChatChannel.SPEAK, raw));
                }
                continue;
            }
            String receiverName = displayNameFor(receiver, sender);
            receiver.sendSystemMessage(delivery == Delivery.CLEAR
                    ? formatTodo(receiverName, speech, action)
                    : distantMessage(receiverName, RpChatChannel.SPEAK, speech));
            sendFeed(receiver, sender, delivery == Delivery.CLEAR ? RpChatChannel.TODO : RpChatChannel.SPEAK,
                    delivery == Delivery.DISTANT, receiverName,
                    delivery == Delivery.CLEAR ? speech + "\u001f" + action : distantFeedText(RpChatChannel.SPEAK, speech));
            heard++;
        }

        plugin.getLogger().info("[RP:todo] " + sender.getName().getString() + " (" + heard + "): " + raw);
        notifyIfAlone(sender, heard, notifyIfAlone);
    }

    private void deliver(ServerPlayer sender, RpChatChannel channel, String rawMessage, Component exact, boolean notifyIfAlone) {
        deliver(sender, channel, rawMessage, exact, notifyIfAlone, 0);
    }

    private void deliver(ServerPlayer sender, RpChatChannel channel, String rawMessage, Component exact, boolean notifyIfAlone, int outcome) {
        int heard = 0;
        sender.sendSystemMessage(exact);
        sendFeed(sender, sender, channel, false, rpName(sender), rawMessage, outcome);

        for (ServerPlayer receiver : plugin.getServer().getPlayerList().getPlayers()) {
            if (receiver.equals(sender)) {
                continue;
            }
            Delivery delivery = deliveryFor(sender, receiver, channel);
            if (delivery == Delivery.NONE) {
                if (RPChat.hasPermission(receiver, "rpchat.spy", 2)) {
                    receiver.sendSystemMessage(spyMessage(sender, channel, rawMessage));
                }
                continue;
            }
            receiver.sendSystemMessage(delivery == Delivery.CLEAR ? exact : distantMessage(rpName(sender), channel, rawMessage));
            String name = displayNameFor(receiver, sender);
            sendFeed(receiver, sender, channel, delivery == Delivery.DISTANT, name,
                    delivery == Delivery.CLEAR ? rawMessage : distantFeedText(channel, rawMessage), outcome);
            heard++;
        }

        plugin.getLogger().info("[RP:" + channel.id() + "] " + sender.getName().getString() + " (" + heard + "): " + rawMessage);
        notifyIfAlone(sender, heard, notifyIfAlone);
    }

    private void notifyIfAlone(ServerPlayer sender, int heard, boolean notifyIfAlone) {
    }

    private void sendFeed(ServerPlayer receiver, ServerPlayer speaker, RpChatChannel channel, boolean distant, String name, String text) {
        sendFeed(receiver, speaker, channel, distant, name, text, 0);
    }

    private void sendFeed(ServerPlayer receiver, ServerPlayer speaker, RpChatChannel channel, boolean distant, String name, String text, int outcome) {
        if (receiver == null || receiver.connection == null) return;
        byte[] data = RpChatFeedProtocol.message(speaker == null ? null : speaker.getUUID(), channel, distant, name, text, outcome);
        ServerPlayNetworking.send(receiver, new RpChatFeedPayload(data));
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

    private void showSpeechBubble(ServerPlayer sender, RpChatChannel channel, String rawMessage) {
        if (sender == null || sender.connection == null || rawMessage == null || rawMessage.isBlank()) {
            return;
        }

        BubbleData previous = activeBubbles.remove(sender.getUUID());
        if (previous != null && previous.display.isAlive()) {
            previous.display.discard();
        }

        String text = bubbleText(channel, rawMessage);
        ServerLevel level = ((ServerLevel) sender.level());
        Vec3 start = sender.position().add(0.0, 2.72, 0.0);
        
        TextDisplay display = new TextDisplay(EntityType.TEXT_DISPLAY, level);
        display.setPos(start.x, start.y, start.z);
        TextDisplayAccessor textDisplay = (TextDisplayAccessor) (Object) display;
        textDisplay.eclipseserver$setText(Component.literal(text).withStyle(s -> s.withColor(channel.messageColor())));
        ((DisplayAccessor) (Object) display).eclipseserver$setBillboardConstraints(Display.BillboardConstraints.CENTER);
        textDisplay.eclipseserver$setBackgroundColor(0);
        textDisplay.eclipseserver$setFlags((byte) (textDisplay.eclipseserver$getFlags() | Display.TextDisplay.FLAG_SHADOW));
        textDisplay.eclipseserver$setLineWidth(170);
        textDisplay.eclipseserver$setTextOpacity((byte) 0);
        
        level.addFreshEntity(display);

        BubbleData bubble = new BubbleData(display, sender, channel, new int[]{0});
        activeBubbles.put(sender.getUUID(), bubble);
        updateBubbleVisibility(sender, channel, display);
    }

    private void removeBubble(UUID uuid, TextDisplay display) {
        activeBubbles.remove(uuid);
        if (display != null && display.isAlive()) {
            display.discard();
        }
    }

    private void updateBubbleVisibility(ServerPlayer sender, RpChatChannel channel, TextDisplay display) {
        if (display == null || !display.isAlive()) {
            return;
        }
        for (ServerPlayer receiver : plugin.getServer().getPlayerList().getPlayers()) {
            if (receiver.equals(sender)) {
                continue;
            }
            if (deliveryFor(sender, receiver, channel) != Delivery.CLEAR) {
                receiver.connection.send(new ClientboundRemoveEntitiesPacket(display.getId()));
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

    private Delivery deliveryFor(ServerPlayer sender, ServerPlayer receiver, RpChatChannel channel) {
        Vec3 from = sender.position();
        Vec3 to = receiver.position();
        if (sender.level() == null || receiver.level() == null || !sender.level().equals(receiver.level())) {
            return Delivery.NONE;
        }
        if (receiver.isSpectator() && !RPChat.hasPermission(receiver, "rpchat.spy", 2)) {
            return Delivery.NONE;
        }
        double distance = from.distanceTo(to);
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

    private boolean hasSoftLineOfSight(ServerPlayer sender, ServerPlayer receiver) {
        return sender.hasLineOfSight(receiver) || receiver.hasLineOfSight(sender);
    }

    private String displayNameFor(ServerPlayer viewer, ServerPlayer target) {
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
        return Component.empty()
                .append(Component.literal(name).withStyle(s -> s.withColor(NAME)))
                .append(Component.literal(verb).withStyle(s -> s.withColor(channel.accentColor())))
                .append(Component.literal("«" + message + "»").withStyle(s -> s.withColor(channel.messageColor())));
    }

    private Component formatTodo(String name, String speech, String action) {
        return Component.empty()
                .append(Component.literal("«" + speech + "» ").withStyle(s -> s.withColor(RpChatChannel.SPEAK.messageColor())))
                .append(Component.literal("- ").withStyle(s -> s.withColor(MUTED)))
                .append(Component.literal(name).withStyle(s -> s.withColor(NAME)))
                .append(Component.literal(", " + action).withStyle(s -> s.withColor(RpChatChannel.ACTION.messageColor())));
    }

    private Component formatAction(String name, String action) {
        return Component.empty()
                .append(Component.literal(name).withStyle(s -> s.withColor(NAME)))
                .append(Component.literal(" " + action).withStyle(s -> s.withColor(RpChatChannel.ACTION.messageColor())));
    }

    private Component formatActionHighlighted(String name, String action, String... highlightedNames) {
        MutableComponent result = Component.empty()
                .append(Component.literal(name).withStyle(s -> s.withColor(NAME)))
                .append(Component.literal(" ").withStyle(s -> s.withColor(RpChatChannel.ACTION.messageColor())));
        String remaining = action == null ? "" : action;
        while (!remaining.isEmpty()) {
            Highlight hit = firstHighlight(remaining, highlightedNames);
            if (hit == null) {
                return result.append(Component.literal(remaining).withStyle(s -> s.withColor(RpChatChannel.ACTION.messageColor())));
            }
            if (hit.start > 0) {
                result = result.append(Component.literal(remaining.substring(0, hit.start)).withStyle(s -> s.withColor(RpChatChannel.ACTION.messageColor())));
            }
            result = result.append(Component.literal(hit.name).withStyle(s -> s.withColor(NAME)));
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
        return Component.empty()
                .append(Component.literal(description).withStyle(s -> s.withColor(RpChatChannel.DESCRIPTION.messageColor())))
                .append(Component.literal(" - ").withStyle(s -> s.withColor(MUTED)))
                .append(Component.literal(name).withStyle(s -> s.withColor(MUTED)));
    }

    private Component distantMessage(String name, RpChatChannel channel, String rawMessage) {
        return switch (channel) {
            case WHISPER -> Component.literal(name + " что-то тихо шепчет рядом.").withStyle(s -> s.withColor(MUTED));
            case SHOUT -> Component.empty()
                    .append(Component.literal(name).withStyle(s -> s.withColor(NAME)))
                    .append(Component.literal(" кричит издалека: ").withStyle(s -> s.withColor(channel.accentColor())))
                    .append(Component.literal("«" + distantText(rawMessage) + "»").withStyle(s -> s.withColor(MUTED)));
            case ACTION, TRY -> Component.literal(name + " что-то делает неподалеку.").withStyle(s -> s.withColor(MUTED));
            case DESCRIPTION -> Component.literal("Неподалеку заметно: " + distantText(rawMessage)).withStyle(s -> s.withColor(MUTED));
            case OOC -> Component.literal("[OOC] слышен обрывок разговора вне роли.").withStyle(s -> s.withColor(MUTED));
            default -> Component.empty()
                    .append(Component.literal(name).withStyle(s -> s.withColor(NAME)))
                    .append(Component.literal(" говорит где-то рядом, но слова слышны нечетко.").withStyle(s -> s.withColor(MUTED)));
        };
    }

    private Component spyMessage(ServerPlayer sender, RpChatChannel channel, String rawMessage) {
        return Component.empty()
                .append(Component.literal("[SPY:" + channel.id().toUpperCase(Locale.ROOT) + "] ").withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal(sender.getName().getString()).withStyle(ChatFormatting.GRAY))
                .append(Component.literal(": " + rawMessage).withStyle(ChatFormatting.GRAY));
    }

    private Component consoleMessage(ServerPlayer sender, RpChatChannel channel, String rawMessage, int heard) {
        return Component.empty()
                .append(Component.literal("[RP:" + channel.id() + "] ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(sender.getName().getString()).withStyle(ChatFormatting.WHITE))
                .append(Component.literal(" (" + heard + "): ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(rawMessage).withStyle(ChatFormatting.WHITE));
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
