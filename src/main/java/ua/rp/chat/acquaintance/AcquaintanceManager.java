package ua.rp.chat.acquaintance;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;
import ua.rp.chat.RPChat;
import ua.rp.chat.RpChatChannel;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public class AcquaintanceManager implements PluginMessageListener, Listener {
    public static final String ACTION_CHANNEL = "rpchat:acq_action";
    public static final String STATE_CHANNEL = "rpchat:acq_state";

    private static final int ACTION_GREET = 1;
    private static final int ACTION_ACCEPT_GREETING = 2;
    private static final int ACTION_DECLINE_GREETING = 3;
    private static final int ACTION_INTRODUCE = 4;
    private static final int ACTION_NOTE = 5;
    private static final int ACTION_INSPECT_WOUNDS = 10;
    private static final int ACTION_BIND = 11;
    private static final int ACTION_UNBIND = 12;
    private static final int ACTION_SEARCH_QUICK = 13;
    private static final int ACTION_SEARCH_HANDS = 14;
    private static final int ACTION_SEARCH_BELT = 15;
    private static final int ACTION_SEARCH_BAG = 16;
    private static final int ACTION_SEARCH_CLOAK = 17;
    private static final int ACTION_DISARM = 18;
    private static final int ACTION_CARRY = 19;
    private static final int ACTION_RELEASE = 20;
    private static final int ACTION_KNEEL = 21;
    private static final int ACTION_SEARCH_THOROUGH = 22;
    private static final int ACTION_TAKE_SEARCH_ITEM = 30;
    private static final int ACTION_ACCEPT_CONTROL = 40;
    private static final int ACTION_DECLINE_CONTROL = 41;
    private static final int ACTION_ESCAPE_STRUGGLE = 70;
    private static final int ACTION_ESCAPE_QTE = 71;
    private static final int ACTION_ESCAPE_BLADE = 72;
    private static final int ACTION_ESCAPE_ENVIRONMENT = 73;
    private static final int ACTION_ESCAPE_CANCEL = 74;
    private static final int ACTION_ESCAPE_CALL = 75;
    private static final int ACTION_ESCAPE_HELP = 76;
    private static final int FORCE_ACTION_OFFSET = 40;

    private static final TextColor SAND = TextColor.color(0xE3C099);
    private static final TextColor MUTED = TextColor.color(0x9A9289);
    private static final TextColor AQUA = TextColor.color(0xA5C3C4);
    private static final long REQUEST_TTL_MS = 18_000L;
    private static final long CONTROL_REQUEST_TTL_MS = 9_000L;
    private static final long SEARCH_SESSION_TTL_MS = 45_000L;
    private static final long ACTIVE_ACTION_GRACE_MS = 1_000L;

    private final RPChat plugin;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final File storageFile;
    private final File captivityFile;
    private final File auditFile;
    private final Map<UUID, Map<UUID, Entry>> known = new ConcurrentHashMap<>();
    private final Map<UUID, PendingGreeting> pendingByParticipant = new ConcurrentHashMap<>();
    private final Map<UUID, CaptiveState> captives = new ConcurrentHashMap<>();
    private final Map<UUID, PendingControl> pendingControlByTarget = new ConcurrentHashMap<>();
    private final Map<UUID, SearchSession> searchSessions = new ConcurrentHashMap<>();
    private final Map<UUID, ActivePhysicalAction> activeActions = new ConcurrentHashMap<>();
    private final Map<UUID, Long> forceCooldownByActor = new ConcurrentHashMap<>();
    private final Map<UUID, EscapeSession> escapeSessions = new ConcurrentHashMap<>();
    private final Map<UUID, Long> escapeCooldowns = new ConcurrentHashMap<>();
    private boolean dirty;
    private boolean captivityDirty;

    public AcquaintanceManager(RPChat plugin) {
        this.plugin = plugin;
        this.storageFile = new File(plugin.getDataFolder(), "acquaintances.json");
        this.captivityFile = new File(plugin.getDataFolder(), "captivity.json");
        this.auditFile = new File(plugin.getDataFolder(), "interaction-audit.log");
    }

    public void start() {
        load();
        loadCaptivity();
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, STATE_CHANNEL);
        plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, ACTION_CHANNEL, this);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        new BukkitRunnable() {
            @Override
            public void run() {
                tick();
            }
        }.runTaskTimer(plugin, 10L, 10L);
    }

    public void shutdown() {
        save();
        saveCaptivity();
    }

    public String chatNameFor(Player viewer, Player target) {
        if (viewer == null || target == null || viewer.equals(target)) {
            return plugin.getRpChatService().rpName(target);
        }
        Entry entry = known.getOrDefault(viewer.getUniqueId(), Map.of()).get(target.getUniqueId());
        if (isMasked(target)) {
            return entry != null && entry.name != null && !entry.name.isBlank()
                    ? "Голос " + entry.name
                    : "Голос незнакомца";
        }
        return labelFor(target, entry);
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!ACTION_CHANNEL.equals(channel) || player == null || message == null) {
            return;
        }
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(message))) {
            int action = input.readInt();
            UUID targetId = new UUID(input.readLong(), input.readLong());
            String text = readUtf8(input);
            if (action >= ACTION_ESCAPE_STRUGGLE && action <= ACTION_ESCAPE_CALL) {
                handleSelfEscapeAction(player, action);
                return;
            }
            Player target = Bukkit.getPlayer(targetId);
            if (target == null || !target.isOnline() || target.equals(player) || !near(player, target, 4.2)) {
                sendToast(player, "Собеседник слишком далеко.", "muted");
                return;
            }
            switch (action) {
                case ACTION_GREET -> requestGreeting(player, target);
                case ACTION_ACCEPT_GREETING -> acceptGreeting(player, target);
                case ACTION_DECLINE_GREETING -> declineGreeting(player, target);
                case ACTION_INTRODUCE -> submitIntroduction(player, target, text);
                case ACTION_NOTE -> setNote(player, target, text);
                case ACTION_INSPECT_WOUNDS -> requestOrPerformControl(player, target, ControlAction.INSPECT);
                case ACTION_BIND -> requestOrPerformControl(player, target, ControlAction.BIND);
                case ACTION_UNBIND -> unbind(player, target);
                case ACTION_SEARCH_QUICK -> requestOrPerformControl(player, target, ControlAction.SEARCH_QUICK);
                case ACTION_SEARCH_HANDS -> requestOrPerformControl(player, target, ControlAction.SEARCH_HANDS);
                case ACTION_SEARCH_BELT -> requestOrPerformControl(player, target, ControlAction.SEARCH_BELT);
                case ACTION_SEARCH_BAG -> requestOrPerformControl(player, target, ControlAction.SEARCH_BAG);
                case ACTION_SEARCH_CLOAK -> requestOrPerformControl(player, target, ControlAction.SEARCH_CLOAK);
                case ACTION_SEARCH_THOROUGH -> requestOrPerformControl(player, target, ControlAction.SEARCH_THOROUGH);
                case ACTION_DISARM -> requestOrPerformControl(player, target, ControlAction.DISARM);
                case ACTION_CARRY -> requestOrPerformControl(player, target, ControlAction.CARRY);
                case ACTION_RELEASE -> releaseControl(player, target);
                case ACTION_KNEEL -> requestOrPerformControl(player, target, ControlAction.KNEEL);
                case ACTION_TAKE_SEARCH_ITEM -> takeSearchItem(player, target, text);
                case ACTION_ACCEPT_CONTROL -> acceptControl(player, target);
                case ACTION_DECLINE_CONTROL -> declineControl(player, target);
                case ACTION_ESCAPE_HELP -> startEscapeHelp(player, target);
                default -> {
                    ControlAction forced = action >= 50 && action <= 62 ? controlAction(action - FORCE_ACTION_OFFSET) : null;
                    if (forced != null) {
                        attemptForcedControl(player, target, forced);
                    }
                }
            }
        } catch (IOException | RuntimeException ignored) {
        }
    }

    private void tick() {
        long now = System.currentTimeMillis();
        pendingByParticipant.entrySet().removeIf(entry -> entry.getValue().expiresAt < now);
        pendingControlByTarget.entrySet().removeIf(entry -> entry.getValue().expiresAt < now);
        searchSessions.entrySet().removeIf(entry -> entry.getValue().expiresAt < now);
        forceCooldownByActor.entrySet().removeIf(entry -> entry.getValue() < now);
        tickActiveActions(now);
        tickEscapeSessions(now);
        tickCaptives();
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            sendState(viewer);
        }
        if (dirty) {
            save();
        }
        if (captivityDirty) {
            saveCaptivity();
        }
    }

    private void tickCaptives() {
        for (Map.Entry<UUID, CaptiveState> entry : captives.entrySet()) {
            Player target = Bukkit.getPlayer(entry.getKey());
            if (target == null || !target.isOnline()) {
                continue;
            }
            CaptiveState state = entry.getValue();
            if (state.bound) {
                if (state.restraintMax <= 0.0) {
                    RestraintProfile profile = restraintProfile(state);
                    state.restraintMax = profile.maxDurability;
                    state.restraintHealth = profile.maxDurability;
                    state.lastWeakenAt = System.currentTimeMillis();
                    captivityDirty = true;
                } else if (state.lastWeakenAt > 0L && System.currentTimeMillis() - state.lastWeakenAt >= 300_000L
                        && restraintProfile(state) != RestraintProfile.CHAIN) {
                    state.restraintHealth = Math.max(state.restraintMax * 0.15, state.restraintHealth - state.restraintMax * 0.10);
                    state.lastWeakenAt = System.currentTimeMillis();
                    captivityDirty = true;
                }
                target.setSprinting(false);
                target.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.SLOWNESS, 30, state.tight ? 3 : 1, true, false, false));
                target.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.WEAKNESS, 30, 2, true, false, false));
                target.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.MINING_FATIGUE, 30, 3, true, false, false));
            }
            if (state.kneeling) {
                target.setSprinting(false);
                target.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.SLOWNESS, 30, 4, true, false, false));
            }
            if (state.carriedBy != null) {
                Player carrier = Bukkit.getPlayer(state.carriedBy);
                if (carrier == null || !carrier.isOnline() || !carrier.getWorld().equals(target.getWorld())) {
                    state.carriedBy = null;
                    captivityDirty = true;
                    continue;
                }
                target.setSprinting(false);
                carrier.setSprinting(false);
                carrier.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.SLOWNESS, 30, 1, true, false, false));
                pullEscortedTarget(carrier, target);
            }
        }
    }

    private void pullEscortedTarget(Player carrier, Player target) {
        Vector forward = carrier.getLocation().getDirection().setY(0);
        if (forward.lengthSquared() < 0.001) {
            forward = new Vector(0, 0, 1);
        }
        forward.normalize();
        Vector side = new Vector(-forward.getZ(), 0, forward.getX()).multiply(0.45);
        Vector desired = carrier.getLocation().toVector().subtract(forward.multiply(1.45)).add(side);
        Vector current = target.getLocation().toVector();
        Vector delta = desired.subtract(current);
        double distance = delta.length();
        if (distance > 11.0) {
            target.teleport(carrier.getLocation().add(carrier.getLocation().getDirection().setY(0).normalize().multiply(-1.35)));
            target.setFallDistance(0.0f);
            return;
        }
        if (distance < 0.55) {
            Vector slow = target.getVelocity().multiply(0.45);
            slow.setY(target.getVelocity().getY());
            target.setVelocity(slow);
            return;
        }
        Vector pull = delta.normalize().multiply(Math.min(0.34, 0.10 + distance * 0.055));
        pull.setY(Math.max(target.getVelocity().getY(), Math.min(0.18, delta.getY() * 0.08)));
        target.setVelocity(target.getVelocity().multiply(0.36).add(pull));
        target.setFallDistance(0.0f);
    }

    private void requestGreeting(Player requester, Player target) {
        clearPending(requester.getUniqueId());
        clearPending(target.getUniqueId());

        PendingGreeting pending = new PendingGreeting(requester.getUniqueId(), target.getUniqueId(), System.currentTimeMillis() + REQUEST_TTL_MS);
        pendingByParticipant.put(requester.getUniqueId(), pending);
        pendingByParticipant.put(target.getUniqueId(), pending);

        sendToast(requester, "Вы протянули руку для знакомства.", "sand");
        JsonObject json = base("request");
        json.addProperty("from", requester.getUniqueId().toString());
        json.addProperty("text", "Незнакомец протягивает вам руку для приветствия.");
        json.addProperty("hint", "Y - ответить взаимностью, N - проигнорировать");
        send(target, json);
        playHandshakeCue(requester, target);
    }

    private void acceptGreeting(Player target, Player requester) {
        PendingGreeting pending = pendingByParticipant.get(target.getUniqueId());
        if (pending == null || !pending.requester.equals(requester.getUniqueId()) || !pending.target.equals(target.getUniqueId())) {
            sendToast(target, "Приглашение уже неактуально.", "muted");
            return;
        }
        pending.accepted = true;
        pending.expiresAt = System.currentTimeMillis() + REQUEST_TTL_MS;

        sendIntroducePrompt(requester, target, plugin.getRpChatService().rpName(requester));
        sendIntroducePrompt(target, requester, plugin.getRpChatService().rpName(target));
        sendToast(target, "Вы ответили на приветствие. Представьтесь собеседнику.", "sand");
        sendToast(requester, "Собеседник ответил. Представьтесь друг другу.", "sand");
        playHandshakeCue(requester, target);
    }

    private void declineGreeting(Player target, Player requester) {
        clearPending(target.getUniqueId());
        sendToast(target, "Вы проигнорировали приветствие.", "muted");
        sendToast(requester, "Приветствие оставили без ответа.", "muted");
    }

    private void submitIntroduction(Player presenter, Player receiver, String alias) {
        PendingGreeting pending = pendingByParticipant.get(presenter.getUniqueId());
        if (pending == null || !pending.accepted || !pending.isPair(presenter.getUniqueId(), receiver.getUniqueId())) {
            sendToast(presenter, "Знакомство уже неактуально.", "muted");
            return;
        }

        String clean = sanitize(alias);
        if (clean.length() > 32) {
            clean = clean.substring(0, 32).trim();
        }
        if (presenter.getUniqueId().equals(pending.requester)) {
            pending.requesterAlias = clean;
            pending.requesterSubmitted = true;
        } else {
            pending.targetAlias = clean;
            pending.targetSubmitted = true;
        }

        if (!pending.requesterSubmitted || !pending.targetSubmitted) {
            sendToast(presenter, "Имя принято. Ждем ответ собеседника.", "sand");
            return;
        }

        finishIntroduction(pending);
    }

    private void finishIntroduction(PendingGreeting pending) {
        Player requester = Bukkit.getPlayer(pending.requester);
        Player target = Bukkit.getPlayer(pending.target);
        clearPending(pending.requester);
        if (requester == null || target == null || !requester.isOnline() || !target.isOnline()) {
            return;
        }

        if (!pending.targetAlias.isBlank()) {
            remember(requester, target, pending.targetAlias);
        }
        if (!pending.requesterAlias.isBlank()) {
            remember(target, requester, pending.requesterAlias);
        }

        sendIntroductionResult(requester, pending.targetAlias);
        sendIntroductionResult(target, pending.requesterAlias);
        playHandshakeCue(requester, target);
        sendState(requester);
        sendState(target);
    }

    private void sendIntroducePrompt(Player presenter, Player receiver, String defaultName) {
        JsonObject prompt = base("introduce");
        prompt.addProperty("target", receiver.getUniqueId().toString());
        prompt.addProperty("defaultName", defaultName);
        prompt.addProperty("title", "Как вы хотите представиться?");
        send(presenter, prompt);
    }

    private void sendIntroductionResult(Player learner, String learnedName) {
        if (learnedName == null || learnedName.isBlank()) {
            learner.sendMessage(Component.text("* Собеседник пожал вам руку, но не назвал имени.", MUTED));
        } else {
            learner.sendMessage(Component.text("* Вы запомнили собеседника под именем " + learnedName + ".", SAND));
        }
    }

    private void clearPending(UUID participant) {
        PendingGreeting pending = pendingByParticipant.remove(participant);
        if (pending != null) {
            pendingByParticipant.remove(pending.requester, pending);
            pendingByParticipant.remove(pending.target, pending);
        }
    }

    private void setNote(Player viewer, Player target, String note) {
        String clean = sanitize(note);
        if (clean.length() > 42) {
            clean = clean.substring(0, 42).trim();
        }
        Map<UUID, Entry> map = known.computeIfAbsent(viewer.getUniqueId(), id -> new ConcurrentHashMap<>());
        Entry entry = map.computeIfAbsent(target.getUniqueId(), id -> new Entry("", ""));
        entry.note = clean;
        dirty = true;
        sendToast(viewer, clean.isBlank() ? "Заметка очищена." : "Примета записана: " + clean, "sand");
        sendState(viewer);
    }

    private void remember(Player viewer, Player target, String name) {
        Map<UUID, Entry> map = known.computeIfAbsent(viewer.getUniqueId(), id -> new ConcurrentHashMap<>());
        Entry entry = map.computeIfAbsent(target.getUniqueId(), id -> new Entry("", ""));
        entry.name = sanitize(name);
        dirty = true;
    }

    private void requestOrPerformControl(Player actor, Player target, ControlAction action) {
        if (canActDirectly(actor, target, action)) {
            performControl(actor, target, action);
            return;
        }
        PendingControl pending = new PendingControl(actor.getUniqueId(), target.getUniqueId(), action, System.currentTimeMillis() + CONTROL_REQUEST_TTL_MS);
        pendingControlByTarget.put(target.getUniqueId(), pending);
        JsonObject json = base("control_request");
        json.addProperty("from", actor.getUniqueId().toString());
        json.addProperty("action", action.name());
        json.addProperty("text", controlRequestText(actor, action));
        json.addProperty("hint", "Y - поддаться, N - сопротивляться");
        json.addProperty("acceptAction", ACTION_ACCEPT_CONTROL);
        json.addProperty("declineAction", ACTION_DECLINE_CONTROL);
        send(target, json);
        sendToast(actor, "Вы начали действие: " + action.label + ". Ждем реакции.", "sand");
    }

    private ControlAction controlAction(int action) {
        return switch (action) {
            case ACTION_INSPECT_WOUNDS -> ControlAction.INSPECT;
            case ACTION_BIND -> ControlAction.BIND;
            case ACTION_SEARCH_QUICK -> ControlAction.SEARCH_QUICK;
            case ACTION_SEARCH_HANDS -> ControlAction.SEARCH_HANDS;
            case ACTION_SEARCH_BELT -> ControlAction.SEARCH_BELT;
            case ACTION_SEARCH_BAG -> ControlAction.SEARCH_BAG;
            case ACTION_SEARCH_CLOAK -> ControlAction.SEARCH_CLOAK;
            case ACTION_SEARCH_THOROUGH -> ControlAction.SEARCH_THOROUGH;
            case ACTION_DISARM -> ControlAction.DISARM;
            case ACTION_CARRY -> ControlAction.CARRY;
            case ACTION_KNEEL -> ControlAction.KNEEL;
            default -> null;
        };
    }

    private boolean canActDirectly(Player actor, Player target, ControlAction action) {
        CaptiveState state = captives.get(target.getUniqueId());
        if (state != null && (state.bound || state.carriedBy != null)) {
            return true;
        }
        if (plugin.getStaminaManager().isUnconscious(target)) {
            return true;
        }
        if (action == ControlAction.INSPECT || action == ControlAction.SEARCH_QUICK) {
            return false;
        }
        return false;
    }

    private boolean isControlledTarget(Player target) {
        CaptiveState state = captives.get(target.getUniqueId());
        return state != null && (state.bound || state.carriedBy != null) || plugin.getStaminaManager().isUnconscious(target);
    }

    private void acceptControl(Player target, Player actor) {
        PendingControl pending = pendingControlByTarget.remove(target.getUniqueId());
        if (pending == null || !pending.actor.equals(actor.getUniqueId())) {
            sendToast(target, "Это действие уже неактуально.", "muted");
            return;
        }
        performControl(actor, target, pending.action);
    }

    private void declineControl(Player target, Player actor) {
        PendingControl pending = pendingControlByTarget.remove(target.getUniqueId());
        if (pending == null || !pending.actor.equals(actor.getUniqueId())) {
            return;
        }
        attemptForcedControl(actor, target, pending.action);
    }

    private void attemptForcedControl(Player actor, Player target, ControlAction action) {
        long now = System.currentTimeMillis();
        if (forceCooldownByActor.getOrDefault(actor.getUniqueId(), 0L) > now) {
            sendToast(actor, "Нужно восстановить равновесие перед новой попыткой.", "muted");
            return;
        }
        if (activeActions.containsKey(actor.getUniqueId()) || hasActiveActionWithTarget(target.getUniqueId())) {
            sendToast(actor, "Сейчас уже выполняется другое физическое действие.", "muted");
            return;
        }
        if (action == ControlAction.BIND && restraintMaterial(actor) == null) {
            sendToast(actor, "Для связывания нужна веревка, цепь, поводок или ремень.", "muted");
            return;
        }
        if (action == ControlAction.DISARM && weaponInHands(target) == null) {
            sendToast(actor, "В руках цели нет оружия, которое можно вырвать.", "muted");
            return;
        }
        if (canActDirectly(actor, target, action)) {
            beginTimedControl(actor, target, action, true);
            return;
        }

        int actorRoll = ThreadLocalRandom.current().nextInt(1, 21) + 8
                - plugin.getStaminaManager().combatAttackPenalty(actor);
        int targetRoll = ThreadLocalRandom.current().nextInt(1, 21) + 8
                - plugin.getStaminaManager().restraintPenalty(target);
        CaptiveState targetState = captives.get(target.getUniqueId());
        if (targetState != null && targetState.kneeling) {
            actorRoll += 2;
        }
        if (target.isSneaking()) {
            targetRoll += 1;
        }

        String targetName = plugin.getRpChatService().rpName(target);
        if (actorRoll >= targetRoll + action.forceDifficulty) {
            forceCooldownByActor.put(actor.getUniqueId(), now + 1_200L);
            plugin.getRpChatService().sendActionHighlighted(actor, action.forceSuccessLine(targetName), targetName);
            plugin.getRpChatService().sendDescription(target, action.forceSuccessDoLine());
            sendToast(target, "Захват удался: начинается действие «" + action.label + "».", "muted");
            audit(actor, target, "force_won_" + action.name().toLowerCase(),
                    "actor=" + actorRoll + ",target=" + targetRoll + ",difficulty=" + action.forceDifficulty);
            beginTimedControl(actor, target, action, true);
            return;
        }

        forceCooldownByActor.put(actor.getUniqueId(), now + 2_800L);
        plugin.getRpChatService().sendActionHighlighted(actor, action.forceFailureLine(targetName), targetName);
        plugin.getRpChatService().sendDescription(target, "Захват сорван: цель сохраняет свободу движения.");
        sendToast(actor, "Силовая попытка сорвалась.", "muted");
        sendToast(target, "Вы вырвались и сохранили контроль над собой.", "sand");
        audit(actor, target, "force_lost_" + action.name().toLowerCase(),
                "actor=" + actorRoll + ",target=" + targetRoll + ",difficulty=" + action.forceDifficulty);
    }

    private void performControl(Player actor, Player target, ControlAction action) {
        beginTimedControl(actor, target, action, false);
    }

    private void beginTimedControl(Player actor, Player target, ControlAction action, boolean forced) {
        if (activeActions.containsKey(actor.getUniqueId())) {
            sendToast(actor, "Вы уже заняты другим действием.", "muted");
            return;
        }
        if (hasActiveActionWithTarget(target.getUniqueId())) {
            sendToast(actor, "С этим человеком уже выполняют действие.", "muted");
            return;
        }
        if (!near(actor, target, 4.2)) {
            sendToast(actor, "Нужно стоять ближе и видеть цель.", "muted");
            return;
        }
        if (action == ControlAction.BIND && restraintMaterial(actor) == null) {
            sendToast(actor, "Нужна веревка, поводок, цепь или ремень.", "muted");
            return;
        }
        long durationMs = forced ? Math.round(action.durationMs * 1.20) : action.durationMs;
        ActivePhysicalAction active = new ActivePhysicalAction(
                actor.getUniqueId(),
                target.getUniqueId(),
                action,
                forced,
                System.currentTimeMillis(),
                System.currentTimeMillis() + durationMs,
                actor.getLocation().clone(),
                target.getLocation().clone()
        );
        activeActions.put(actor.getUniqueId(), active);
        plugin.getRpChatService().sendActionHighlighted(actor,
                action.startLine(plugin.getRpChatService().rpName(target)),
                plugin.getRpChatService().rpName(target));
        sendPhysicalAction(actor, target, action, "start", active.startedAt, active.completeAt, forced);
        sendToast(actor, "Действие начато: " + action.label + ". Не двигайтесь и держитесь рядом.", "sand");
        sendToast(target, "С вами выполняют действие: " + action.label + ".", "muted");
        audit(actor, target, "start_" + action.name().toLowerCase(), "durationMs=" + durationMs + ",forced=" + forced);
    }

    private boolean hasActiveActionWithTarget(UUID targetId) {
        for (ActivePhysicalAction action : activeActions.values()) {
            if (action.target.equals(targetId)) {
                return true;
            }
        }
        return false;
    }

    private void tickActiveActions(long now) {
        for (ActivePhysicalAction active : new ArrayList<>(activeActions.values())) {
            Player actor = Bukkit.getPlayer(active.actor);
            Player target = Bukkit.getPlayer(active.target);
            if (actor == null || target == null || !actor.isOnline() || !target.isOnline()) {
                activeActions.remove(active.actor, active);
                continue;
            }
            if (actor.isDead() || target.isDead()) {
                cancelActiveAction(active, "действие сорвано смертью или потерей тела");
                continue;
            }
            if (!nearLoose(actor, target, 5.2)) {
                cancelActiveAction(active, "цель оказалась слишком далеко");
                continue;
            }
            if (active.forced && !isControlledTarget(target)) {
                actor.setSprinting(false);
                target.setSprinting(false);
                actor.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.SLOWNESS, 15, 1, true, false, false));
                target.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.SLOWNESS, 15, 4, true, false, false));
            }
            if (now - active.startedAt > ACTIVE_ACTION_GRACE_MS) {
                if (!sameWorld(actor.getLocation(), active.actorStart) || actor.getLocation().distanceSquared(active.actorStart) > 1.7 * 1.7) {
                    cancelActiveAction(active, "исполнитель сдвинулся с места");
                    continue;
                }
                if (!active.forced && !isControlledTarget(target) && (!sameWorld(target.getLocation(), active.targetStart) || target.getLocation().distanceSquared(active.targetStart) > 2.2 * 2.2)) {
                    cancelActiveAction(active, "цель вырвалась из позиции");
                    continue;
                }
            }
            if (now >= active.completeAt) {
                if (activeActions.remove(active.actor, active)) {
                    sendPhysicalAction(actor, target, active.action, "complete", active.startedAt, active.completeAt, active.forced);
                    completeControl(actor, target, active.action);
                    audit(actor, target, "complete_" + active.action.name().toLowerCase(), "");
                }
                continue;
            }
            if (now - active.lastProgressAt >= 1_000L) {
                active.lastProgressAt = now;
                long left = Math.max(1L, (active.completeAt - now + 999L) / 1000L);
                sendToast(actor, active.action.label + ": осталось " + left + " сек.", "sand");
            }
        }
    }

    private boolean sameWorld(Location a, Location b) {
        return a.getWorld() != null && a.getWorld().equals(b.getWorld());
    }

    private void cancelActiveAction(ActivePhysicalAction active, String reason) {
        if (!activeActions.remove(active.actor, active)) {
            return;
        }
        Player actor = Bukkit.getPlayer(active.actor);
        Player target = Bukkit.getPlayer(active.target);
        if (actor != null) {
            sendToast(actor, "Действие прервано: " + reason + ".", "muted");
        }
        if (target != null) {
            sendToast(target, "Действие над вами прервано.", "muted");
        }
        if (actor != null && target != null) {
            sendPhysicalAction(actor, target, active.action, "cancel", active.startedAt, active.completeAt, active.forced);
            audit(actor, target, "cancel_" + active.action.name().toLowerCase(), reason);
        }
    }

    private void sendPhysicalAction(Player actor, Player target, ControlAction action, String phase, long startedAt, long completeAt, boolean forced) {
        JsonObject json = base("physical_action");
        json.addProperty("phase", phase);
        json.addProperty("action", action.name());
        json.addProperty("label", action.label);
        json.addProperty("actor", actor.getUniqueId().toString());
        json.addProperty("target", target.getUniqueId().toString());
        json.addProperty("forced", forced);
        json.addProperty("durationMs", Math.max(1L, completeAt - startedAt));
        json.addProperty("startedAt", startedAt);
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer.getWorld().equals(actor.getWorld()) && viewer.getLocation().distanceSquared(actor.getLocation()) <= 48 * 48) {
                send(viewer, json);
            }
        }
    }

    private void completeControl(Player actor, Player target, ControlAction action) {
        switch (action) {
            case INSPECT -> inspectWounds(actor, target);
            case BIND -> bind(actor, target);
            case SEARCH_QUICK -> startSearch(actor, target, SearchSection.QUICK);
            case SEARCH_HANDS -> startSearch(actor, target, SearchSection.HANDS);
            case SEARCH_BELT -> startSearch(actor, target, SearchSection.BELT);
            case SEARCH_BAG -> startSearch(actor, target, SearchSection.BAG);
            case SEARCH_CLOAK -> startSearch(actor, target, SearchSection.CLOAK);
            case SEARCH_THOROUGH -> startSearch(actor, target, SearchSection.THOROUGH);
            case DISARM -> disarm(actor, target);
            case CARRY -> carry(actor, target);
            case KNEEL -> kneel(actor, target);
            default -> {
            }
        }
    }

    private void handleSelfEscapeAction(Player player, int action) {
        CaptiveState state = captives.get(player.getUniqueId());
        if (state == null || !state.bound) {
            sendToast(player, "Руки свободны — освобождаться не от чего.", "muted");
            return;
        }
        switch (action) {
            case ACTION_ESCAPE_STRUGGLE -> startStruggle(player, state);
            case ACTION_ESCAPE_QTE -> resolveStruggleHit(player, state);
            case ACTION_ESCAPE_BLADE -> startBladeEscape(player, state);
            case ACTION_ESCAPE_ENVIRONMENT -> startEnvironmentEscape(player, state);
            case ACTION_ESCAPE_CANCEL -> cancelEscape(player.getUniqueId(), "Попытка прекращена.", false);
            case ACTION_ESCAPE_CALL -> callForHelp(player);
            default -> {
            }
        }
    }

    private void startStruggle(Player player, CaptiveState state) {
        RestraintProfile profile = restraintProfile(state);
        if (!profile.struggleAllowed) {
            sendToast(player, "Эти путы невозможно разорвать одной силой.", "muted");
            return;
        }
        long now = System.currentTimeMillis();
        if (escapeCooldowns.getOrDefault(player.getUniqueId(), 0L) > now) {
            sendToast(player, "Нужно перевести дыхание перед новой попыткой.", "muted");
            return;
        }
        if (!plugin.getStaminaManager().consumeEscapeEffort(player, 4.0, 1.5)) {
            sendToast(player, "Не хватает сил, чтобы начать вырываться.", "muted");
            return;
        }
        EscapeSession session = EscapeSession.struggle(player, state, profile, now);
        escapeSessions.put(player.getUniqueId(), session);
        state.escapeMode = "STRUGGLE";
        captivityDirty = true;
        sendEscapeState(player, session, "Быстро нажимайте A и D поочередно.");
        plugin.getRpChatService().sendAction(player, "напрягает связанные запястья, осторожно проверяя узел на прочность.");
    }

    private void resolveStruggleHit(Player player, CaptiveState state) {
        EscapeSession session = escapeSessions.get(player.getUniqueId());
        if (session == null || session.mode != EscapeMode.STRUGGLE) {
            return;
        }
        long now = System.currentTimeMillis();
        
        // A/D mashing successful pull verification:
        // Consume stamina for the pull
        if (plugin.getStaminaManager().consumeEscapeEffort(player, 12.0, 3.5)) {
            // Deduct rope health by a fixed amount per pull (e.g., 12.5% of max durability, so 8 pulls are needed)
            double damage = state.restraintMax * 0.125;
            state.restraintHealth = Math.max(0.0, state.restraintHealth - damage);
            captivityDirty = true;
            player.getWorld().playSound(player.getLocation(), Sound.BLOCK_WOOL_BREAK, 0.55f, 0.72f);
            
            if (state.restraintHealth <= 0.01) {
                completeEscape(player, player, EscapeMode.STRUGGLE);
                return;
            }
            
            // Send back success state to update overall progress
            sendEscapeState(player, session, "Один узел поддался! Продолжайте расшатывать.");
        } else {
            sendToast(player, "Вы слишком истощены, чтобы продолжать бороться.", "muted");
            // Stop escape session if completely out of stamina
            completeEscape(player, player, EscapeMode.STRUGGLE);
        }
    }

    private void startBladeEscape(Player player, CaptiveState state) {
        ItemStack blade = findEscapeBlade(player);
        if (blade == null) {
            sendToast(player, "При вас не осталось доступного острого предмета.", "muted");
            return;
        }
        RestraintProfile profile = restraintProfile(state);
        if (!profile.bladeAllowed) {
            sendToast(player, "Обычное лезвие не справится с этими путами.", "muted");
            return;
        }
        startTimedEscape(player, player, state, EscapeMode.BLADE, profile.bladeDurationMs,
                "Лезвие заведено под путы. Любое резкое движение сорвет работу.");
        plugin.getRpChatService().sendAction(player, "нащупывает спрятанный острый край и начинает понемногу надрезать путы за спиной.");
    }

    private void startEnvironmentEscape(Player player, CaptiveState state) {
        var block = player.getTargetBlockExact(2);
        if (block == null) {
            sendToast(player, "Рядом нет подходящей поверхности.", "muted");
            return;
        }
        String material = block.getType().name();
        RestraintProfile profile = restraintProfile(state);
        if (isOpenFire(material)) {
            if (!profile.fireAllowed) {
                sendToast(player, "Огонь не освободит от этих пут.", "muted");
                return;
            }
            startTimedEscape(player, player, state, EscapeMode.FIRE, 7_000L,
                    "Огонь быстро разрушает путы, но обжигает обе руки.");
            return;
        }
        if (isAbrasiveStone(material)) {
            if (!profile.stoneAllowed) {
                sendToast(player, "Камень почти не оставляет следа на этих путах.", "muted");
                return;
            }
            startTimedEscape(player, player, state, EscapeMode.STONE, profile.stoneDurationMs,
                    "Прижмитесь путами к камню и сохраняйте положение.");
            return;
        }
        sendToast(player, "Эта поверхность не поможет перетереть путы.", "muted");
    }

    private void startEscapeHelp(Player actor, Player target) {
        CaptiveState actorState = captives.get(actor.getUniqueId());
        CaptiveState targetState = captives.get(target.getUniqueId());
        if (actorState == null || !actorState.bound || targetState == null || !targetState.bound) {
            sendToast(actor, "Для такой помощи оба пленника должны быть связаны.", "muted");
            return;
        }
        if (!nearLoose(actor, target, 1.8) || !isBehind(actor, target)) {
            sendToast(actor, "Нужно встать вплотную за товарищем, рядом с его узлами.", "muted");
            return;
        }
        RestraintProfile profile = restraintProfile(targetState);
        if (!profile.helpAllowed) {
            sendToast(actor, "Эти крепления невозможно развязать зубами.", "muted");
            return;
        }
        startTimedEscape(actor, target, targetState, EscapeMode.HELP, 90_000L,
                "Не двигайтесь: узел приходится разбирать почти вслепую.");
        plugin.getRpChatService().sendActionHighlighted(actor,
                "подбирается к узлам на запястьях " + plugin.getRpChatService().rpName(target) + " и осторожно тянет свободный конец зубами.",
                plugin.getRpChatService().rpName(target));
    }

    private void startTimedEscape(Player actor, Player target, CaptiveState targetState, EscapeMode mode, long durationMs, String hint) {
        cancelEscape(actor.getUniqueId(), "", false);
        long now = System.currentTimeMillis();
        EscapeSession session = EscapeSession.timed(actor, target, targetState, mode, durationMs, now);
        escapeSessions.put(actor.getUniqueId(), session);
        CaptiveState actorState = captives.get(actor.getUniqueId());
        if (actorState != null) actorState.escapeMode = mode == EscapeMode.HELP ? "HELP_ACTOR" : mode.name();
        targetState.escapeMode = mode == EscapeMode.HELP ? "HELP_TARGET" : mode.name();
        captivityDirty = true;
        sendEscapeState(actor, session, hint);
        if (!actor.equals(target)) {
            sendEscapeState(target, session, "Товарищ пытается добраться до ваших узлов.");
        }
    }

    private void tickEscapeSessions(long now) {
        for (EscapeSession session : new ArrayList<>(escapeSessions.values())) {
            Player actor = Bukkit.getPlayer(session.actor);
            Player target = Bukkit.getPlayer(session.target);
            CaptiveState state = captives.get(session.target);
            if (actor == null || target == null || state == null || !state.bound) {
                cancelEscape(session.actor, "Попытка больше не актуальна.", false);
                continue;
            }
            if (session.mode == EscapeMode.STRUGGLE) {
                if (now - session.cycleStartedAt > session.cycleDurationMs + 900L) {
                    emitEscapeNoise(actor, false);
                    session.nextCycle(now + 650L);
                }
                sendEscapeState(actor, session, "Ловите момент, когда натяжение ослабевает.");
                continue;
            }
            if (movedTooFar(actor, session.actorStart, 0.18) || movedTooFar(target, session.targetStart, 0.18)
                    || session.mode == EscapeMode.HELP && (!nearLoose(actor, target, 1.9) || !isBehind(actor, target))) {
                cancelEscape(session.actor, "Движение сорвало попытку освобождения.", true);
                continue;
            }
            long elapsed = Math.max(0L, now - session.lastTickAt);
            session.lastTickAt = now;
            double damage = state.restraintMax * elapsed / (double) session.durationMs;
            state.restraintHealth = Math.max(0.0, state.restraintHealth - damage);
            captivityDirty = true;

            if (session.mode == EscapeMode.FIRE && now - session.lastHazardAt >= 1_000L) {
                session.lastHazardAt = now;
                plugin.getStaminaManager().applyEscapeBurn(actor, 2.6);
                actor.setFireTicks(Math.max(actor.getFireTicks(), 24));
                actor.playSound(actor.getLocation(), Sound.ENTITY_PLAYER_HURT, 0.9f, 0.72f);
                emitEscapeNoise(actor, true);
            } else if (session.mode == EscapeMode.STONE && now - session.lastSoundAt >= 2_400L) {
                session.lastSoundAt = now;
                actor.getWorld().playSound(actor.getLocation(), Sound.BLOCK_STONE_HIT, 0.42f, 0.58f);
            } else if (session.mode == EscapeMode.BLADE && now - session.lastSoundAt >= 3_100L) {
                session.lastSoundAt = now;
                actor.playSound(actor.getLocation(), Sound.BLOCK_WOOL_BREAK, 0.22f, 1.28f);
            }
            if (state.restraintHealth <= 0.01 || now >= session.completeAt) {
                completeEscape(actor, target, session.mode);
                continue;
            }
            sendEscapeState(actor, session, escapeModeHint(session.mode));
            if (!actor.equals(target)) {
                sendEscapeState(target, session, "Узел постепенно поддается.");
            }
        }
    }

    private void completeEscape(Player actor, Player target, EscapeMode mode) {
        CaptiveState state = captives.get(target.getUniqueId());
        if (state == null) {
            return;
        }
        state.bound = false;
        state.material = "";
        state.escapeMode = "";
        state.restraintHealth = 0.0;
        captivityDirty = true;
        escapeSessions.entrySet().removeIf(entry -> entry.getValue().actor.equals(actor.getUniqueId()) || entry.getValue().target.equals(target.getUniqueId()));
        if (mode == EscapeMode.BLADE) {
            wearEscapeBlade(actor);
        }
        JsonObject done = base("escape_state");
        done.addProperty("active", false);
        done.addProperty("completed", true);
        done.addProperty("message", "Путы разорваны. Руки снова свободны.");
        send(actor, done);
        if (!actor.equals(target)) send(target, done);
        target.playSound(target.getLocation(), Sound.BLOCK_CHAIN_BREAK, 0.8f, 1.05f);
        plugin.getRpChatService().sendActionHighlighted(actor,
                actor.equals(target) ? "доводит попытку до конца — поврежденные путы соскальзывают с запястий."
                        : "находит слабину в узле и освобождает руки " + plugin.getRpChatService().rpName(target) + ".",
                plugin.getRpChatService().rpName(target));
        plugin.getRpChatService().sendDescription(target, "Путы больше не удерживают запястья.");
        audit(actor, target, "escape_" + mode.name().toLowerCase(), "");
    }

    private void cancelEscape(UUID actorId, String message, boolean cooldown) {
        EscapeSession removed = escapeSessions.remove(actorId);
        if (removed == null) return;
        CaptiveState actorState = captives.get(removed.actor);
        CaptiveState targetState = captives.get(removed.target);
        if (actorState != null) actorState.escapeMode = "";
        if (targetState != null) targetState.escapeMode = "";
        captivityDirty = true;
        Player actor = Bukkit.getPlayer(actorId);
        if (cooldown) escapeCooldowns.put(actorId, System.currentTimeMillis() + 2_000L);
        if (actor != null) {
            JsonObject json = base("escape_state");
            json.addProperty("active", false);
            json.addProperty("completed", false);
            json.addProperty("message", message == null ? "" : message);
            send(actor, json);
        }
    }

    private void callForHelp(Player player) {
        plugin.getRpChatService().sendAction(player, "набирает воздух и громко зовет на помощь.");
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_PLAYER_HURT, 1.0f, 0.84f);
        escapeCooldowns.put(player.getUniqueId(), System.currentTimeMillis() + 3_000L);
    }

    private void sendEscapeState(Player player, EscapeSession session, String message) {
        CaptiveState state = captives.get(session.target);
        if (state == null) return;
        JsonObject json = base("escape_state");
        json.addProperty("active", true);
        json.addProperty("mode", session.mode.name());
        json.addProperty("progress", 1.0 - state.restraintHealth / Math.max(1.0, state.restraintMax));
        json.addProperty("durability", state.restraintHealth);
        json.addProperty("maxDurability", state.restraintMax);
        json.addProperty("message", message == null ? "" : message);
        json.addProperty("startedAt", session.startedAt);
        json.addProperty("completeAt", session.completeAt);
        json.addProperty("cycleStartedAt", session.cycleStartedAt);
        json.addProperty("cycleDurationMs", session.cycleDurationMs);
        json.addProperty("windowCenter", session.windowCenter);
        json.addProperty("windowWidth", session.windowWidth);
        send(player, json);
    }

    private void emitEscapeNoise(Player player, boolean loud) {
        player.getWorld().playSound(player.getLocation(), loud ? Sound.ENTITY_PLAYER_HURT : Sound.BLOCK_WOOL_HIT, loud ? 0.95f : 0.45f, loud ? 0.72f : 0.82f);
        if (loud) plugin.getRpChatService().sendAction(player, "не сдерживает болезненный стон, пока путы впиваются в запястья.");
    }

    private ItemStack findEscapeBlade(Player player) {
        for (ItemStack item : player.getInventory().getContents()) {
            if (item == null || item.getType().isAir()) continue;
            String type = item.getType().name();
            String custom = item.hasItemMeta() && item.getItemMeta().hasDisplayName() && item.getItemMeta().displayName() != null
                    ? net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(item.getItemMeta().displayName()).toLowerCase()
                    : "";
            if (type.contains("SWORD") || type.contains("SHEARS") || type.equals("FLINT") || type.contains("DAGGER")
                    || type.equals("IRON_NUGGET") || custom.contains("скрытое лезвие") || custom.contains("hidden blade")) {
                return item;
            }
        }
        return null;
    }

    private void wearEscapeBlade(Player player) {
        ItemStack blade = findEscapeBlade(player);
        if (blade == null) return;
        if (blade.getItemMeta() instanceof Damageable damageable && blade.getType().getMaxDurability() > 0) {
            int next = damageable.getDamage() + Math.max(1, blade.getType().getMaxDurability() / 18);
            if (next >= blade.getType().getMaxDurability()) {
                blade.setAmount(blade.getAmount() - 1);
            } else {
                damageable.setDamage(next);
                blade.setItemMeta(damageable);
            }
        } else if (blade.getType() == Material.FLINT || blade.getType() == Material.IRON_NUGGET) {
            blade.setAmount(blade.getAmount() - 1);
        }
    }

    private boolean isHiddenBlade(ItemStack item) {
        if (item == null || !item.hasItemMeta() || !item.getItemMeta().hasDisplayName() || item.getItemMeta().displayName() == null) return false;
        String name = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(item.getItemMeta().displayName()).toLowerCase();
        return name.contains("скрытое лезвие") || name.contains("hidden blade") || name.contains("лезвие в сапоге");
    }

    private boolean isBehind(Player actor, Player target) {
        Vector facing = target.getLocation().getDirection().setY(0);
        Vector toActor = actor.getLocation().toVector().subtract(target.getLocation().toVector()).setY(0);
        if (facing.lengthSquared() < 0.001 || toActor.lengthSquared() < 0.001) return false;
        return facing.normalize().dot(toActor.normalize()) < -0.25;
    }

    private boolean movedTooFar(Player player, Location origin, double distance) {
        return player == null || origin == null || !player.getWorld().equals(origin.getWorld())
                || player.getLocation().distanceSquared(origin) > distance * distance;
    }

    private boolean isOpenFire(String material) {
        return material.contains("CAMPFIRE") || material.equals("FIRE") || material.equals("SOUL_FIRE") || material.equals("LAVA");
    }

    private boolean isAbrasiveStone(String material) {
        return material.contains("COBBLESTONE") || material.contains("STONE_BRICK") || material.contains("DEEPSLATE")
                || material.contains("TUFF") || material.contains("BLACKSTONE") || material.contains("GRINDSTONE");
    }

    private String escapeModeHint(EscapeMode mode) {
        return switch (mode) {
            case BLADE -> "Лезвие медленно проходит сквозь волокна.";
            case STONE -> "Шероховатый край стирает путы слой за слоем.";
            case FIRE -> "Путы горят; жар становится почти невыносимым.";
            case HELP -> "Свободный конец узла понемногу выходит из петли.";
            case STRUGGLE -> "Ловите ослабление натяжения.";
        };
    }

    private RestraintProfile restraintProfile(CaptiveState state) {
        String material = state.material == null ? "" : state.material.toLowerCase();
        if (material.contains("цеп")) return RestraintProfile.CHAIN;
        if (material.contains("кож") || material.contains("рем")) return RestraintProfile.LEATHER;
        if (material.contains("нит")) return RestraintProfile.THREAD;
        return state.tight ? RestraintProfile.ROPE_TIGHT : RestraintProfile.ROPE;
    }

    private void bind(Player actor, Player target) {
        RestraintMaterial material = restraintMaterial(actor);
        if (material == null) {
            sendToast(actor, "Нужна веревка, поводок, цепь или ремень.", "muted");
            return;
        }
        consumeRestraint(actor, material);
        CaptiveState state = captives.computeIfAbsent(target.getUniqueId(), id -> new CaptiveState());
        state.bound = true;
        state.tight = material.tight;
        state.boundBy = actor.getUniqueId();
        state.boundAt = System.currentTimeMillis();
        state.material = material.label;
        RestraintProfile profile = restraintProfile(state);
        state.restraintMax = profile.maxDurability;
        state.restraintHealth = profile.maxDurability;
        state.lastWeakenAt = state.boundAt;
        state.searchedMask = 0;
        captivityDirty = true;
        plugin.getRpChatService().sendActionHighlighted(actor,
                "заводит руки " + plugin.getRpChatService().rpName(target) + " за спину и стягивает запястья: " + material.label + ".",
                plugin.getRpChatService().rpName(target));
        plugin.getRpChatService().sendDescription(target,
                "Запястья связаны. Узел выглядит " + (state.tight ? "тугим и надежным." : "достаточно крепким."));
        target.playSound(target.getLocation(), Sound.BLOCK_WOOL_PLACE, 0.75f, 0.82f);
        audit(actor, target, "bind", "material=" + material.label);
        sendState(actor);
        sendState(target);
    }

    private void unbind(Player actor, Player target) {
        CaptiveState state = captives.get(target.getUniqueId());
        if (state == null || !state.bound) {
            sendToast(actor, "Руки не связаны.", "muted");
            return;
        }
        state.bound = false;
        state.material = "";
        state.escapeMode = "";
        state.restraintHealth = 0.0;
        escapeSessions.entrySet().removeIf(entry -> entry.getValue().target.equals(target.getUniqueId()));
        captivityDirty = true;
        plugin.getRpChatService().sendActionHighlighted(actor,
                "ослабляет узел и освобождает руки " + plugin.getRpChatService().rpName(target) + ".",
                plugin.getRpChatService().rpName(target));
        plugin.getRpChatService().sendDescription(target, "Веревка спадает с запястий.");
        audit(actor, target, "unbind", "");
    }

    private void inspectWounds(Player actor, Player target) {
        boolean trained = actor.hasPermission("rpchat.medic") || actor.getInventory().contains(Material.PAPER);
        plugin.getRpChatService().sendActionHighlighted(actor,
                "внимательно осматривает состояние " + plugin.getRpChatService().rpName(target) + ", проверяя дыхание, кровь и положение конечностей.",
                plugin.getRpChatService().rpName(target));
        plugin.getRpChatService().sendSystemLocal(actor, RpChatChannel.DESCRIPTION,
                Component.text(plugin.getStaminaManager().woundInspectionSummary(target, trained), AQUA));
        audit(actor, target, "inspect_wounds", "trained=" + trained);
    }

    private void disarm(Player actor, Player target) {
        ItemStack item = weaponInHands(target);
        EquipmentSlot hand = handWithWeapon(target);
        if (item == null || item.getType().isAir()) {
            sendToast(actor, "В руках не видно оружия.", "muted");
            return;
        }
        ItemStack taken = item.clone();
        if (hand == EquipmentSlot.OFF_HAND) {
            target.getInventory().setItemInOffHand(new ItemStack(Material.AIR));
        } else {
            target.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
        }
        giveOrDrop(actor, taken);
        plugin.getRpChatService().sendActionHighlighted(actor,
                "вырывает " + itemName(taken) + " из рук " + plugin.getRpChatService().rpName(target) + ".",
                plugin.getRpChatService().rpName(target));
        plugin.getRpChatService().sendDescription(target, "Оружие покидает руки.");
        audit(actor, target, "disarm", itemName(taken));
    }

    private void carry(Player actor, Player target) {
        CaptiveState state = captives.computeIfAbsent(target.getUniqueId(), id -> new CaptiveState());
        state.carriedBy = actor.getUniqueId();
        captivityDirty = true;
        plugin.getRpChatService().sendActionHighlighted(actor,
                "подхватывает " + plugin.getRpChatService().rpName(target) + " и начинает тащить за собой.",
                plugin.getRpChatService().rpName(target));
        plugin.getRpChatService().sendDescription(target, "Тело сдвинуто с места и удерживается другим человеком.");
        audit(actor, target, "carry", "");
    }

    private void releaseControl(Player actor, Player target) {
        CaptiveState state = captives.get(target.getUniqueId());
        if (state == null || state.carriedBy == null && !state.kneeling) {
            sendToast(actor, "Некого отпускать или поднимать.", "muted");
            return;
        }
        state.carriedBy = null;
        state.kneeling = false;
        target.setSneaking(false);
        captivityDirty = true;
        plugin.getRpChatService().sendActionHighlighted(actor,
                "отпускает " + plugin.getRpChatService().rpName(target) + " и оставляет его на месте.",
                plugin.getRpChatService().rpName(target));
        audit(actor, target, "release", "");
    }

    private void kneel(Player actor, Player target) {
        CaptiveState state = captives.computeIfAbsent(target.getUniqueId(), id -> new CaptiveState());
        state.kneeling = true;
        captivityDirty = true;
        plugin.getRpChatService().sendActionHighlighted(actor,
                "давит на плечо " + plugin.getRpChatService().rpName(target) + ", заставляя опуститься ниже.",
                plugin.getRpChatService().rpName(target));
        plugin.getRpChatService().sendDescription(target, "Поза становится неустойчивой: подняться без сопротивления трудно.");
        audit(actor, target, "kneel", "");
    }

    private void startSearch(Player actor, Player target, SearchSection section) {
        CaptiveState captive = captives.get(target.getUniqueId());
        if (captive != null && captive.bound) {
            captive.searchedMask |= 1 << section.ordinal();
            captivityDirty = true;
        }
        List<SearchItem> items = collectSearchItems(target, section);
        SearchSession session = new SearchSession(UUID.randomUUID().toString(), actor.getUniqueId(), target.getUniqueId(), section, System.currentTimeMillis() + SEARCH_SESSION_TTL_MS);
        for (SearchItem item : items) {
            session.items.put(item.key, item.ref);
        }
        searchSessions.put(actor.getUniqueId(), session);
        plugin.getRpChatService().sendActionHighlighted(actor,
                section.meLine(plugin.getRpChatService().rpName(target)),
                plugin.getRpChatService().rpName(target));
        plugin.getRpChatService().sendDescription(target, section.doLine(items.isEmpty()));

        JsonObject json = base("search_results");
        json.addProperty("target", target.getUniqueId().toString());
        json.addProperty("session", session.id);
        json.addProperty("title", section.title);
        JsonArray arr = new JsonArray();
        for (SearchItem item : items) {
            JsonObject raw = new JsonObject();
            raw.addProperty("key", item.key);
            raw.addProperty("label", itemName(item.stack));
            raw.addProperty("amount", item.stack.getAmount());
            raw.addProperty("slot", item.ref.label);
            arr.add(raw);
        }
        json.add("items", arr);
        send(actor, json);
        audit(actor, target, "search_" + section.name().toLowerCase(), "items=" + items.size());
    }

    private void takeSearchItem(Player actor, Player target, String key) {
        SearchSession session = searchSessions.get(actor.getUniqueId());
        if (session == null || !session.target.equals(target.getUniqueId()) || !near(actor, target, 4.2)) {
            sendToast(actor, "Обыск уже неактуален.", "muted");
            return;
        }
        InventoryRef ref = session.items.remove(sanitize(key));
        if (ref == null) {
            sendToast(actor, "Этого предмета уже нет на месте.", "muted");
            return;
        }
        ItemStack stack = ref.get(target);
        if (stack == null || stack.getType().isAir()) {
            sendToast(actor, "Предмет исчез.", "muted");
            return;
        }
        ItemStack taken = stack.clone();
        ref.clear(target);
        giveOrDrop(actor, taken);
        plugin.getRpChatService().sendActionHighlighted(actor,
                "забирает " + itemName(taken) + " у " + plugin.getRpChatService().rpName(target) + ".",
                plugin.getRpChatService().rpName(target));
        plugin.getRpChatService().sendDescription(target, itemName(taken) + " изъят и больше не находится при нем.");
        audit(actor, target, "take_search_item", ref.label + ":" + itemName(taken));
    }

    private RestraintMaterial restraintMaterial(Player actor) {
        ItemStack main = actor.getInventory().getItemInMainHand();
        ItemStack off = actor.getInventory().getItemInOffHand();
        RestraintMaterial material = restraintMaterial(main);
        if (material != null) return material;
        material = restraintMaterial(off);
        if (material != null) return material;
        for (ItemStack item : actor.getInventory().getContents()) {
            material = restraintMaterial(item);
            if (material != null) return material;
        }
        return null;
    }

    private RestraintMaterial restraintMaterial(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return null;
        }
        String name = item.getType().name();
        if (name.contains("LEAD")) return new RestraintMaterial("поводок", true);
        if (name.contains("STRING")) return new RestraintMaterial("моток нити", false);
        if (name.contains("CHAIN")) return new RestraintMaterial("цепь", true);
        if (name.contains("LEATHER")) return new RestraintMaterial("кожаный ремень", false);
        return null;
    }

    private void consumeRestraint(Player actor, RestraintMaterial material) {
        for (ItemStack item : actor.getInventory().getContents()) {
            if (restraintMaterial(item) != null) {
                item.setAmount(item.getAmount() - 1);
                return;
            }
        }
    }

    private ItemStack weaponInHands(Player target) {
        ItemStack main = target.getInventory().getItemInMainHand();
        if (isWeapon(main)) return main;
        ItemStack off = target.getInventory().getItemInOffHand();
        return isWeapon(off) ? off : null;
    }

    private EquipmentSlot handWithWeapon(Player target) {
        return isWeapon(target.getInventory().getItemInOffHand()) && !isWeapon(target.getInventory().getItemInMainHand())
                ? EquipmentSlot.OFF_HAND
                : EquipmentSlot.HAND;
    }

    private boolean isWeapon(ItemStack item) {
        if (item == null || item.getType().isAir()) return false;
        String name = item.getType().name();
        return name.contains("SWORD") || name.contains("AXE") || name.contains("BOW") || name.contains("CROSSBOW")
                || name.contains("TRIDENT") || name.contains("MACE") || name.contains("DAGGER");
    }

    private List<SearchItem> collectSearchItems(Player target, SearchSection section) {
        List<SearchItem> result = new ArrayList<>();
        switch (section) {
            case QUICK -> {
                addIfPresent(result, target.getInventory().getItemInMainHand(), InventoryRef.mainHand());
                addIfPresent(result, target.getInventory().getItemInOffHand(), InventoryRef.offHand());
                for (int slot = 0; slot < 9; slot++) {
                    ItemStack item = target.getInventory().getItem(slot);
                    if (isWeapon(item)) {
                        addIfPresent(result, item, InventoryRef.slot(slot, "пояс"));
                    }
                }
            }
            case HANDS -> {
                addIfPresent(result, target.getInventory().getItemInMainHand(), InventoryRef.mainHand());
                addIfPresent(result, target.getInventory().getItemInOffHand(), InventoryRef.offHand());
            }
            case BELT -> {
                for (int slot = 0; slot < 9; slot++) {
                    addIfPresent(result, target.getInventory().getItem(slot), InventoryRef.slot(slot, "пояс"));
                }
            }
            case BAG -> {
                for (int slot = 9; slot < 36; slot++) {
                    addIfPresent(result, target.getInventory().getItem(slot), InventoryRef.slot(slot, "сумка"));
                }
            }
            case CLOAK -> addClothingItems(result, target);
            case THOROUGH -> {
                addIfPresent(result, target.getInventory().getItemInMainHand(), InventoryRef.mainHand());
                addIfPresent(result, target.getInventory().getItemInOffHand(), InventoryRef.offHand());
                for (int slot = 0; slot < 36; slot++) {
                    addIfPresent(result, target.getInventory().getItem(slot), InventoryRef.slot(slot, slot < 9 ? "пояс" : "сумка"));
                }
                addClothingItems(result, target);
            }
        }
        if (section != SearchSection.THOROUGH) {
            result.removeIf(item -> isHiddenBlade(item.stack));
        }
        if (result.size() > section.maxItems) {
            return new ArrayList<>(result.subList(0, section.maxItems));
        }
        return result;
    }

    private void addClothingItems(List<SearchItem> result, Player target) {
        addIfPresent(result, target.getInventory().getHelmet(), InventoryRef.armor(EquipmentSlot.HEAD, "голова"));
        addIfPresent(result, target.getInventory().getChestplate(), InventoryRef.armor(EquipmentSlot.CHEST, "плащ/грудь"));
        addIfPresent(result, target.getInventory().getLeggings(), InventoryRef.armor(EquipmentSlot.LEGS, "одежда"));
        addIfPresent(result, target.getInventory().getBoots(), InventoryRef.armor(EquipmentSlot.FEET, "обувь"));
    }

    private void addIfPresent(List<SearchItem> result, ItemStack item, InventoryRef ref) {
        if (item == null || item.getType().isAir()) {
            return;
        }
        result.add(new SearchItem("i" + result.size() + "_" + ref.key(), item.clone(), ref));
    }

    private String itemName(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return "пусто";
        }
        if (item.hasItemMeta() && item.getItemMeta().hasDisplayName()) {
            return item.getItemMeta().displayName() == null ? item.getType().name().toLowerCase() : net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(item.getItemMeta().displayName());
        }
        String readable = item.getType().name().toLowerCase().replace('_', ' ');
        return item.getAmount() > 1 ? readable + " x" + item.getAmount() : readable;
    }

    private void giveOrDrop(Player player, ItemStack item) {
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(item);
        for (ItemStack drop : leftover.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), drop);
        }
    }

    private void audit(Player actor, Player target, String action, String detail) {
        try {
            if (!plugin.getDataFolder().exists()) {
                plugin.getDataFolder().mkdirs();
            }
            String line = java.time.Instant.now()
                    + " actor=" + actor.getName() + "/" + actor.getUniqueId()
                    + " target=" + target.getName() + "/" + target.getUniqueId()
                    + " action=" + action
                    + " world=" + actor.getWorld().getName()
                    + " xyz=" + actor.getLocation().getBlockX() + "," + actor.getLocation().getBlockY() + "," + actor.getLocation().getBlockZ()
                    + (detail == null || detail.isBlank() ? "" : " detail=\"" + detail.replace("\"", "'") + "\"")
                    + System.lineSeparator();
            try (FileWriter writer = new FileWriter(auditFile, StandardCharsets.UTF_8, true)) {
                writer.write(line);
            }
        } catch (Exception e) {
            plugin.getLogger().fine("Failed to write interaction audit: " + e.getMessage());
        }
    }

    private String controlRequestText(Player actor, ControlAction action) {
        return plugin.getRpChatService().rpName(actor) + " пытается выполнить действие: " + action.label + ".";
    }

    private boolean nearLoose(Player a, Player b, double distance) {
        return a.getWorld().equals(b.getWorld()) && a.getLocation().distanceSquared(b.getLocation()) <= distance * distance;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBoundAttack(EntityDamageByEntityEvent event) {
        if (event.getEntity() instanceof Player damaged) {
            cancelActiveForParticipant(damaged.getUniqueId(), "участник получил удар");
        }
        if (event.getDamager() instanceof Player damager) {
            cancelActiveForParticipant(damager.getUniqueId(), "исполнитель отвлекся на удар");
        }
        if (event.getDamager() instanceof Player player && isBound(player)) {
            event.setCancelled(true);
            sendToast(player, "Связанные руки не позволяют атаковать.", "muted");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEscapeDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && event.getFinalDamage() > 0.0) {
            EscapeSession direct = escapeSessions.get(player.getUniqueId());
            if (direct != null && direct.mode != EscapeMode.FIRE) {
                cancelEscape(player.getUniqueId(), "Полученная травма сорвала попытку освобождения.", true);
            }
            for (EscapeSession session : new ArrayList<>(escapeSessions.values())) {
                if (session.target.equals(player.getUniqueId()) && !session.actor.equals(player.getUniqueId())) {
                    cancelEscape(session.actor, "Полученная травма сорвала попытку освобождения.", true);
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBoundInteract(PlayerInteractEvent event) {
        cancelActiveForParticipant(event.getPlayer().getUniqueId(), "исполнитель отвлекся");
        if (isBound(event.getPlayer())) {
            event.setCancelled(true);
            sendToast(event.getPlayer(), "Связанные руки мешают действовать.", "muted");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBoundDrop(PlayerDropItemEvent event) {
        cancelActiveForParticipant(event.getPlayer().getUniqueId(), "исполнитель отвлекся на вещи");
        if (isBound(event.getPlayer())) {
            event.setCancelled(true);
            sendToast(event.getPlayer(), "Вы не можете свободно распоряжаться вещами со связанными руками.", "muted");
        }
    }

    private void cancelActiveForParticipant(UUID participant, String reason) {
        for (ActivePhysicalAction active : new ArrayList<>(activeActions.values())) {
            if (active.actor.equals(participant) || active.target.equals(participant)) {
                cancelActiveAction(active, reason);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCaptiveMove(PlayerMoveEvent event) {
        CaptiveState state = captives.get(event.getPlayer().getUniqueId());
        if (state == null) {
            return;
        }
        if (state.bound && !event.getPlayer().isSneaking() && event.getPlayer().isSprinting()) {
            event.getPlayer().setSprinting(false);
        }
        if (state.kneeling && event.getTo() != null && event.getTo().getY() > event.getFrom().getY() + 0.035) {
            event.setTo(event.getFrom());
            event.getPlayer().setSprinting(false);
        }
    }

    private boolean isBound(Player player) {
        CaptiveState state = captives.get(player.getUniqueId());
        return state != null && state.bound;
    }

    private void sendState(Player viewer) {
        JsonObject json = base("state");
        JsonArray players = new JsonArray();
        for (Player target : Bukkit.getOnlinePlayers()) {
            if (!viewer.getWorld().equals(target.getWorld()) || viewer.getLocation().distanceSquared(target.getLocation()) > 48 * 48) {
                continue;
            }
            boolean self = target.equals(viewer);
            Entry entry = self ? null : known.getOrDefault(viewer.getUniqueId(), Map.of()).get(target.getUniqueId());
            JsonObject p = new JsonObject();
            p.addProperty("uuid", target.getUniqueId().toString());
            p.addProperty("entityId", target.getEntityId());
            p.addProperty("label", self ? plugin.getRpChatService().rpName(target) : labelFor(target, entry));
            p.addProperty("known", self || entry != null && entry.name != null && !entry.name.isBlank());
            p.addProperty("note", self || entry == null ? "" : entry.note);
            p.addProperty("masked", !self && isMasked(target));
            CaptiveState captive = captives.get(target.getUniqueId());
            p.addProperty("bound", captive != null && captive.bound);
            p.addProperty("kneeling", captive != null && captive.kneeling);
            p.addProperty("carried", captive != null && captive.carriedBy != null);
            p.addProperty("escorting", isEscorting(target.getUniqueId()));
            p.addProperty("escapeMode", captive == null || captive.escapeMode == null ? "" : captive.escapeMode);
            if (self && captive != null && captive.bound) {
                RestraintProfile profile = restraintProfile(captive);
                p.addProperty("restraintMaterial", captive.material == null ? "" : captive.material);
                p.addProperty("restraintDurability", captive.restraintHealth);
                p.addProperty("restraintMax", captive.restraintMax);
                p.addProperty("canStruggle", profile.struggleAllowed);
                p.addProperty("canBlade", profile.bladeAllowed && findEscapeBlade(viewer) != null);
                p.addProperty("canEnvironment", profile.fireAllowed || profile.stoneAllowed);
                p.addProperty("escapeStamina", plugin.getStaminaManager().escapeStamina(viewer));
            }
            players.add(p);
        }
        json.add("players", players);

        JsonArray tab = new JsonArray();
        for (Player target : Bukkit.getOnlinePlayers()) {
            Entry entry = known.getOrDefault(viewer.getUniqueId(), Map.of()).get(target.getUniqueId());
            boolean self = target.equals(viewer);
            JsonObject p = new JsonObject();
            p.addProperty("uuid", target.getUniqueId().toString());
            p.addProperty("label", self ? plugin.getRpChatService().rpName(target) : labelFor(target, entry));
            p.addProperty("self", self);
            p.addProperty("known", self || entry != null && entry.name != null && !entry.name.isBlank());
            p.addProperty("note", entry == null ? "" : entry.note);
            p.addProperty("masked", !self && isMasked(target));
            p.addProperty("ping", Math.max(0, target.getPing()));
            CaptiveState captive = captives.get(target.getUniqueId());
            p.addProperty("bound", captive != null && captive.bound);
            p.addProperty("kneeling", captive != null && captive.kneeling);
            p.addProperty("carried", captive != null && captive.carriedBy != null);
            p.addProperty("escorting", isEscorting(target.getUniqueId()));
            tab.add(p);
        }
        json.add("tab", tab);
        send(viewer, json);
    }

    private boolean isEscorting(UUID playerId) {
        for (CaptiveState state : captives.values()) {
            if (playerId.equals(state.carriedBy)) {
                return true;
            }
        }
        return false;
    }

    private String labelFor(Player target, Entry entry) {
        if (isMasked(target)) {
            return "Человек в закрытом шлеме";
        }
        if (entry != null && entry.name != null && !entry.name.isBlank()) {
            return entry.name;
        }
        String note = entry == null ? "" : entry.note;
        return note == null || note.isBlank() ? "Незнакомец" : "Незнакомец (\"" + note + "\")";
    }

    private boolean isMasked(Player player) {
        if (player.getInventory().getHelmet() == null) {
            return false;
        }
        String type = player.getInventory().getHelmet().getType().name();
        return type.contains("HELMET") || type.contains("SKULL") || type.contains("HEAD");
    }

    private void sendToast(Player player, String text, String tone) {
        JsonObject json = base("toast");
        json.addProperty("text", text);
        json.addProperty("tone", tone);
        send(player, json);
    }

    private void playHandshakeCue(Player a, Player b) {
        a.swingMainHand();
        b.swingMainHand();

        JsonObject jsonA = base("handshake");
        jsonA.addProperty("target", b.getUniqueId().toString());
        JsonObject jsonB = base("handshake");
        jsonB.addProperty("target", a.getUniqueId().toString());
        send(a, jsonA);
        send(b, jsonB);
    }

    private boolean near(Player a, Player b, double distance) {
        return a.getWorld().equals(b.getWorld()) && a.getLocation().distanceSquared(b.getLocation()) <= distance * distance && a.hasLineOfSight(b);
    }

    private JsonObject base(String type) {
        JsonObject json = new JsonObject();
        json.addProperty("type", type);
        return json;
    }

    private void send(Player player, JsonObject json) {
        try {
            byte[] data = json.toString().getBytes(StandardCharsets.UTF_8);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            writeVarInt(out, data.length);
            out.write(data);
            player.sendPluginMessage(plugin, STATE_CHANNEL, out.toByteArray());
        } catch (IOException ignored) {
        }
    }

    private void writeVarInt(ByteArrayOutputStream out, int value) {
        while ((value & ~0x7F) != 0) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value);
    }

    private String readUtf8(DataInputStream input) throws IOException {
        int length = input.readUnsignedShort();
        if (length <= 0) {
            return "";
        }
        byte[] bytes = input.readNBytes(Math.min(length, 32760));
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private String sanitize(String text) {
        return text == null ? "" : text.trim().replaceAll("\\s+", " ");
    }

    private void load() {
        if (!storageFile.exists()) {
            return;
        }
        try (FileReader reader = new FileReader(storageFile, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            for (String viewerKey : root.keySet()) {
                UUID viewer = UUID.fromString(viewerKey);
                Map<UUID, Entry> map = new ConcurrentHashMap<>();
                JsonObject entries = root.getAsJsonObject(viewerKey);
                for (String targetKey : entries.keySet()) {
                    JsonObject raw = entries.getAsJsonObject(targetKey);
                    map.put(UUID.fromString(targetKey), new Entry(
                            raw.has("name") ? raw.get("name").getAsString() : "",
                            raw.has("note") ? raw.get("note").getAsString() : ""
                    ));
                }
                known.put(viewer, map);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to load acquaintances: " + e.getMessage());
        }
    }

    private void save() {
        dirty = false;
        try {
            if (!plugin.getDataFolder().exists()) {
                plugin.getDataFolder().mkdirs();
            }
            JsonObject root = new JsonObject();
            for (Map.Entry<UUID, Map<UUID, Entry>> viewer : known.entrySet()) {
                JsonObject entries = new JsonObject();
                for (Map.Entry<UUID, Entry> target : viewer.getValue().entrySet()) {
                    JsonObject raw = new JsonObject();
                    raw.addProperty("name", target.getValue().name);
                    raw.addProperty("note", target.getValue().note);
                    entries.add(target.getKey().toString(), raw);
                }
                root.add(viewer.getKey().toString(), entries);
            }
            try (FileWriter writer = new FileWriter(storageFile, StandardCharsets.UTF_8)) {
                gson.toJson(root, writer);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to save acquaintances: " + e.getMessage());
        }
    }

    private void loadCaptivity() {
        if (!captivityFile.exists()) {
            return;
        }
        try (FileReader reader = new FileReader(captivityFile, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            for (String targetKey : root.keySet()) {
                JsonObject raw = root.getAsJsonObject(targetKey);
                CaptiveState state = new CaptiveState();
                state.bound = raw.has("bound") && raw.get("bound").getAsBoolean();
                state.tight = raw.has("tight") && raw.get("tight").getAsBoolean();
                state.kneeling = raw.has("kneeling") && raw.get("kneeling").getAsBoolean();
                state.material = raw.has("material") ? raw.get("material").getAsString() : "";
                state.boundAt = raw.has("boundAt") ? raw.get("boundAt").getAsLong() : 0L;
                state.restraintMax = raw.has("restraintMax") ? raw.get("restraintMax").getAsDouble() : 0.0;
                state.restraintHealth = raw.has("restraintHealth") ? raw.get("restraintHealth").getAsDouble() : state.restraintMax;
                state.lastWeakenAt = raw.has("lastWeakenAt") ? raw.get("lastWeakenAt").getAsLong() : state.boundAt;
                state.searchedMask = raw.has("searchedMask") ? raw.get("searchedMask").getAsInt() : 0;
                state.escapeMode = "";
                if (raw.has("boundBy") && !raw.get("boundBy").getAsString().isBlank()) {
                    state.boundBy = UUID.fromString(raw.get("boundBy").getAsString());
                }
                if (state.bound || state.kneeling) {
                    captives.put(UUID.fromString(targetKey), state);
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to load captivity states: " + e.getMessage());
        }
    }

    private void saveCaptivity() {
        captivityDirty = false;
        try {
            if (!plugin.getDataFolder().exists()) {
                plugin.getDataFolder().mkdirs();
            }
            JsonObject root = new JsonObject();
            for (Map.Entry<UUID, CaptiveState> entry : captives.entrySet()) {
                CaptiveState state = entry.getValue();
                if (!state.bound && !state.kneeling) {
                    continue;
                }
                JsonObject raw = new JsonObject();
                raw.addProperty("bound", state.bound);
                raw.addProperty("tight", state.tight);
                raw.addProperty("kneeling", state.kneeling);
                raw.addProperty("material", state.material == null ? "" : state.material);
                raw.addProperty("boundAt", state.boundAt);
                raw.addProperty("boundBy", state.boundBy == null ? "" : state.boundBy.toString());
                raw.addProperty("restraintMax", state.restraintMax);
                raw.addProperty("restraintHealth", state.restraintHealth);
                raw.addProperty("lastWeakenAt", state.lastWeakenAt);
                raw.addProperty("searchedMask", state.searchedMask);
                root.add(entry.getKey().toString(), raw);
            }
            try (FileWriter writer = new FileWriter(captivityFile, StandardCharsets.UTF_8)) {
                gson.toJson(root, writer);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to save captivity states: " + e.getMessage());
        }
    }

    private enum ControlAction {
        INSPECT("осмотр ран", 3_500L, -2),
        BIND("связать руки", 6_000L, 2),
        SEARCH_QUICK("быстрый обыск", 3_000L, 0),
        SEARCH_HANDS("проверить руки", 2_500L, 1),
        SEARCH_BELT("обыскать пояс", 4_500L, 2),
        SEARCH_BAG("обыскать сумку", 7_000L, 3),
        SEARCH_CLOAK("осмотреть одежду", 6_500L, 3),
        SEARCH_THOROUGH("тщательный обыск", 12_000L, 5),
        DISARM("снять оружие", 3_500L, 2),
        CARRY("тащить", 3_500L, 3),
        KNEEL("поставить на колени", 2_500L, 1);

        private final String label;
        private final long durationMs;
        private final int forceDifficulty;

        ControlAction(String label, long durationMs, int forceDifficulty) {
            this.label = label;
            this.durationMs = durationMs;
            this.forceDifficulty = forceDifficulty;
        }

        private String startLine(String targetName) {
            return switch (this) {
                case INSPECT -> "садится ближе к " + targetName + " и начинает осторожный осмотр ран.";
                case BIND -> "заводит руки " + targetName + " назад и начинает связывать запястья.";
                case SEARCH_QUICK -> "быстро проверяет оружие и очевидные вещи у " + targetName + ".";
                case SEARCH_HANDS -> "удерживает руки " + targetName + " и начинает проверять ладони и рукава.";
                case SEARCH_BELT -> "начинает ощупывать пояс " + targetName + ", проверяя крепления и подвесы.";
                case SEARCH_BAG -> "раскрывает сумку " + targetName + " и начинает разбирать вещи внутри.";
                case SEARCH_CLOAK -> "осматривает одежду и обувь " + targetName + " в поисках спрятанного.";
                case SEARCH_THOROUGH -> "начинает тщательный обыск " + targetName + ", проверяя вещи по одной.";
                case DISARM -> "тянется к оружию " + targetName + ", пытаясь выбить или снять его.";
                case CARRY -> "подхватывает " + targetName + " и готовится тащить за собой.";
                case KNEEL -> "давит на плечо " + targetName + ", вынуждая опуститься ниже.";
            };
        }

        private String forceSuccessLine(String targetName) {
            return switch (this) {
                case INSPECT -> "фиксирует " + targetName + " за плечо и получает возможность осмотреть раны.";
                case BIND -> "перехватывает руки " + targetName + " и силой заводит их за спину.";
                case SEARCH_QUICK, SEARCH_HANDS, SEARCH_BELT, SEARCH_BAG, SEARCH_CLOAK, SEARCH_THOROUGH ->
                        "удерживает " + targetName + " и получает контроль, необходимый для обыска.";
                case DISARM -> "сбивает руку " + targetName + " в сторону и перехватывает оружие.";
                case CARRY -> "ломает опору " + targetName + " и перехватывает тело для перемещения.";
                case KNEEL -> "давит на плечо и подсекает опору " + targetName + ", вынуждая опуститься на колени.";
            };
        }

        private String forceFailureLine(String targetName) {
            return switch (this) {
                case DISARM -> "тянется к оружию " + targetName + ", но не успевает зафиксировать вооруженную руку.";
                case CARRY -> "пытается перехватить корпус " + targetName + ", но теряет удобный захват.";
                case KNEEL -> "давит на плечо " + targetName + ", но тот удерживает опору и не опускается.";
                default -> "пытается удержать " + targetName + ", но тот вырывается и срывает действие.";
            };
        }

        private String forceSuccessDoLine() {
            return switch (this) {
                case KNEEL -> "Опора потеряна; под давлением приходится опуститься на колени.";
                case BIND -> "Руки зафиксированы за спиной; сразу вырваться из захвата не получается.";
                case DISARM -> "Вооруженная рука отведена в сторону и на короткое время потеряла свободу движения.";
                case CARRY -> "Корпус удерживается другим человеком; самостоятельно отойти сейчас трудно.";
                default -> "Движения зафиксированы успешным захватом; действие продолжается силой.";
            };
        }
    }

    private enum SearchSection {
        QUICK("Быстрый обыск", 6),
        HANDS("Руки", 4),
        BELT("Пояс", 9),
        BAG("Сумка", 18),
        CLOAK("Одежда и обувь", 8),
        THOROUGH("Тщательный обыск", 36);

        private final String title;
        private final int maxItems;

        SearchSection(String title, int maxItems) {
            this.title = title;
            this.maxItems = maxItems;
        }

        private String meLine(String targetName) {
            return switch (this) {
                case QUICK -> "быстро проверяет оружие и очевидные вещи у " + targetName + ".";
                case HANDS -> "перехватывает руки " + targetName + " и проверяет, что зажато в ладонях и рукавах.";
                case BELT -> "проводит ладонью по поясу " + targetName + ", проверяя ножны, кошель и подвесы.";
                case BAG -> "раскрывает сумку " + targetName + " и перебирает вещи внутри.";
                case CLOAK -> "осматривает плащ, одежду и обувь " + targetName + " в поисках спрятанного.";
                case THOROUGH -> "методично обыскивает " + targetName + ", переходя от рук и пояса к сумке, одежде и обуви.";
            };
        }

        private String doLine(boolean empty) {
            if (empty) {
                return "На выбранном участке ничего очевидного не обнаружено.";
            }
            return switch (this) {
                case QUICK -> "Очевидные предметы становятся заметны при беглом обыске.";
                case HANDS -> "Все, что было в руках, теперь видно обыскивающему.";
                case BELT -> "На поясе можно различить закрепленные предметы.";
                case BAG -> "Содержимое сумки частично раскрыто.";
                case CLOAK -> "Складки одежды и обувь внимательно проверены.";
                case THOROUGH -> "После тщательного обыска скрыть обычные предметы почти невозможно.";
            };
        }
    }

    private record RestraintMaterial(String label, boolean tight) {
    }

    private enum EscapeMode {
        STRUGGLE,
        BLADE,
        STONE,
        FIRE,
        HELP
    }

    private enum RestraintProfile {
        THREAD(46.0, true, true, true, true, true, 38_000L, 105_000L, 0.22),
        ROPE(82.0, true, true, true, true, true, 48_000L, 145_000L, 0.17),
        ROPE_TIGHT(108.0, true, true, true, true, true, 58_000L, 170_000L, 0.13),
        LEATHER(132.0, true, true, false, true, true, 78_000L, 240_000L, 0.10),
        CHAIN(180.0, false, false, false, false, false, 0L, 0L, 0.0);

        private final double maxDurability;
        private final boolean struggleAllowed;
        private final boolean bladeAllowed;
        private final boolean stoneAllowed;
        private final boolean fireAllowed;
        private final boolean helpAllowed;
        private final long bladeDurationMs;
        private final long stoneDurationMs;
        private final double qteWidth;

        RestraintProfile(double maxDurability, boolean struggleAllowed, boolean bladeAllowed, boolean stoneAllowed,
                         boolean fireAllowed, boolean helpAllowed, long bladeDurationMs, long stoneDurationMs, double qteWidth) {
            this.maxDurability = maxDurability;
            this.struggleAllowed = struggleAllowed;
            this.bladeAllowed = bladeAllowed;
            this.stoneAllowed = stoneAllowed;
            this.fireAllowed = fireAllowed;
            this.helpAllowed = helpAllowed;
            this.bladeDurationMs = bladeDurationMs;
            this.stoneDurationMs = stoneDurationMs;
            this.qteWidth = qteWidth;
        }
    }

    private static final class EscapeSession {
        private final UUID actor;
        private final UUID target;
        private final EscapeMode mode;
        private final long startedAt;
        private final long completeAt;
        private final long durationMs;
        private final Location actorStart;
        private final Location targetStart;
        private long lastTickAt;
        private long lastSoundAt;
        private long lastHazardAt;
        private long cycleStartedAt;
        private long cycleDurationMs;
        private double windowCenter;
        private double windowWidth;
        private boolean hitConsumed;

        private EscapeSession(Player actor, Player target, EscapeMode mode, long durationMs, long now) {
            this.actor = actor.getUniqueId();
            this.target = target.getUniqueId();
            this.mode = mode;
            this.startedAt = now;
            this.completeAt = now + durationMs;
            this.durationMs = Math.max(1L, durationMs);
            this.actorStart = actor.getLocation().clone();
            this.targetStart = target.getLocation().clone();
            this.lastTickAt = now;
            this.lastSoundAt = now;
            this.lastHazardAt = now;
        }

        private static EscapeSession struggle(Player actor, CaptiveState state, RestraintProfile profile, long now) {
            EscapeSession session = new EscapeSession(actor, actor, EscapeMode.STRUGGLE, 300_000L, now);
            session.windowWidth = profile.qteWidth;
            session.nextCycle(now + 700L);
            return session;
        }

        private static EscapeSession timed(Player actor, Player target, CaptiveState state, EscapeMode mode, long durationMs, long now) {
            return new EscapeSession(actor, target, mode, durationMs, now);
        }

        private void nextCycle(long startAt) {
            this.cycleStartedAt = startAt;
            this.cycleDurationMs = 1_750L + ThreadLocalRandom.current().nextLong(0L, 450L);
            this.windowCenter = 0.24 + ThreadLocalRandom.current().nextDouble() * 0.52;
            this.hitConsumed = false;
        }
    }

    private record SearchItem(String key, ItemStack stack, InventoryRef ref) {
    }

    private static final class SearchSession {
        private final String id;
        private final UUID actor;
        private final UUID target;
        private final SearchSection section;
        private final long expiresAt;
        private final Map<String, InventoryRef> items = new ConcurrentHashMap<>();

        private SearchSession(String id, UUID actor, UUID target, SearchSection section, long expiresAt) {
            this.id = id;
            this.actor = actor;
            this.target = target;
            this.section = section;
            this.expiresAt = expiresAt;
        }
    }

    private static final class InventoryRef {
        private final int slot;
        private final EquipmentSlot equipment;
        private final String label;

        private InventoryRef(int slot, EquipmentSlot equipment, String label) {
            this.slot = slot;
            this.equipment = equipment;
            this.label = label;
        }

        private static InventoryRef slot(int slot, String label) {
            return new InventoryRef(slot, null, label + " #" + (slot + 1));
        }

        private static InventoryRef mainHand() {
            return new InventoryRef(-1, EquipmentSlot.HAND, "правая рука");
        }

        private static InventoryRef offHand() {
            return new InventoryRef(-1, EquipmentSlot.OFF_HAND, "левая рука");
        }

        private static InventoryRef armor(EquipmentSlot equipment, String label) {
            return new InventoryRef(-1, equipment, label);
        }

        private String key() {
            return equipment == null ? "slot" + slot : equipment.name().toLowerCase();
        }

        private ItemStack get(Player player) {
            if (equipment == null) {
                return player.getInventory().getItem(slot);
            }
            return switch (equipment) {
                case HAND -> player.getInventory().getItemInMainHand();
                case OFF_HAND -> player.getInventory().getItemInOffHand();
                case HEAD -> player.getInventory().getHelmet();
                case CHEST -> player.getInventory().getChestplate();
                case LEGS -> player.getInventory().getLeggings();
                case FEET -> player.getInventory().getBoots();
                default -> null;
            };
        }

        private void clear(Player player) {
            if (equipment == null) {
                player.getInventory().setItem(slot, new ItemStack(Material.AIR));
                return;
            }
            switch (equipment) {
                case HAND -> player.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
                case OFF_HAND -> player.getInventory().setItemInOffHand(new ItemStack(Material.AIR));
                case HEAD -> player.getInventory().setHelmet(new ItemStack(Material.AIR));
                case CHEST -> player.getInventory().setChestplate(new ItemStack(Material.AIR));
                case LEGS -> player.getInventory().setLeggings(new ItemStack(Material.AIR));
                case FEET -> player.getInventory().setBoots(new ItemStack(Material.AIR));
                default -> {
                }
            }
        }
    }

    private static final class ActivePhysicalAction {
        private final UUID actor;
        private final UUID target;
        private final ControlAction action;
        private final boolean forced;
        private final long startedAt;
        private final long completeAt;
        private final Location actorStart;
        private final Location targetStart;
        private long lastProgressAt;

        private ActivePhysicalAction(UUID actor, UUID target, ControlAction action, boolean forced, long startedAt, long completeAt, Location actorStart, Location targetStart) {
            this.actor = actor;
            this.target = target;
            this.action = action;
            this.forced = forced;
            this.startedAt = startedAt;
            this.completeAt = completeAt;
            this.actorStart = actorStart;
            this.targetStart = targetStart;
            this.lastProgressAt = startedAt;
        }
    }

    private static final class CaptiveState {
        private boolean bound;
        private boolean tight;
        private boolean kneeling;
        private UUID boundBy;
        private UUID carriedBy;
        private long boundAt;
        private String material = "";
        private double restraintMax;
        private double restraintHealth;
        private long lastWeakenAt;
        private int searchedMask;
        private String escapeMode = "";
    }

    private static final class PendingControl {
        private final UUID actor;
        private final UUID target;
        private final ControlAction action;
        private final long expiresAt;

        private PendingControl(UUID actor, UUID target, ControlAction action, long expiresAt) {
            this.actor = actor;
            this.target = target;
            this.action = action;
            this.expiresAt = expiresAt;
        }
    }

    private static final class Entry {
        private String name;
        private String note;

        private Entry(String name, String note) {
            this.name = name;
            this.note = note;
        }
    }

    private static final class PendingGreeting {
        private final UUID requester;
        private final UUID target;
        private long expiresAt;
        private boolean accepted;
        private boolean requesterSubmitted;
        private boolean targetSubmitted;
        private String requesterAlias = "";
        private String targetAlias = "";

        private PendingGreeting(UUID requester, UUID target, long expiresAt) {
            this.requester = requester;
            this.target = target;
            this.expiresAt = expiresAt;
        }

        private boolean isPair(UUID first, UUID second) {
            return (requester.equals(first) && target.equals(second)) || (requester.equals(second) && target.equals(first));
        }
    }
}
