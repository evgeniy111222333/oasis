package ua.rp.chat.heavyhammer;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.bukkit.scheduler.BukkitTask;
import ua.rp.chat.RPChat;
import ua.rp.chat.microvoxel.MicrovoxelManager;
import ua.rp.chat.microvoxel.MicrovoxelVolume;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class HeavyHammerManager implements Listener, PluginMessageListener, CommandExecutor {
    public static final String ITEM_ID = "heavy_builder_hammer";
    public static final String CLIENT_MODEL = "eclipseclient:heavy_hammer";

    private final RPChat plugin;
    private final MicrovoxelManager microvoxels;
    private final NamespacedKey itemIdKey;
    private final NamespacedKey recipeKey;
    private final Map<UUID, PendingStrike> strikes = new HashMap<>();
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private final Map<UUID, CarryState> carryStates = new HashMap<>();
    private final Map<UUID, Long> readyAt = new HashMap<>();
    private BukkitTask carryTask;
    private int nextSequence = 1;
    private int carryRevision = 1;
    private int carryPoll;

    public HeavyHammerManager(RPChat plugin, MicrovoxelManager microvoxels) {
        this.plugin = plugin;
        this.microvoxels = microvoxels;
        this.itemIdKey = new NamespacedKey(plugin, "item_id");
        this.recipeKey = new NamespacedKey(plugin, "heavy_hammer");
    }

    public void start() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getMessenger().registerIncomingPluginChannel(plugin, HeavyHammerProtocol.ACTION_CHANNEL, this);
        Bukkit.getMessenger().registerOutgoingPluginChannel(plugin, HeavyHammerProtocol.SYNC_CHANNEL);
        if (plugin.getCommand("heavyhammer") != null) plugin.getCommand("heavyhammer").setExecutor(this);
        registerRecipe();
        carryTask = Bukkit.getScheduler().runTaskTimer(plugin, this::pollCarryStates, 1L, 2L);
    }

    public void shutdown() {
        strikes.clear();
        cooldowns.clear();
        carryStates.clear();
        readyAt.clear();
        if (carryTask != null) carryTask.cancel();
        carryTask = null;
        Bukkit.removeRecipe(recipeKey);
    }

    public ItemStack createItem() {
        ItemStack hammer = new ItemStack(Material.IRON_AXE);
        ItemMeta meta = hammer.getItemMeta();
        meta.customName(Component.text("Тяжёлый рабочий молот", NamedTextColor.WHITE));
        meta.lore(List.of(
                Component.text("Простой кузнечный инструмент для грубой каменной работы.", NamedTextColor.GRAY),
                Component.text("Требует обеих рук и расходует выносливость.", NamedTextColor.DARK_GRAY),
                Component.text("ЛКМ по микровокселям — тяжёлый круговой удар.", NamedTextColor.DARK_GRAY)
        ));
        meta.setItemModel(NamespacedKey.fromString(CLIENT_MODEL));
        // Числовой маркер остаётся на предмете при переносе между совместимыми версиями Paper.
        meta.setCustomModelData(1401);
        meta.getPersistentDataContainer().set(itemIdKey, PersistentDataType.STRING, ITEM_ID);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        if (meta instanceof Damageable damageable) damageable.setMaxDamage(420);
        hammer.setItemMeta(meta);
        return hammer;
    }

    public boolean isHeavyHammer(ItemStack stack) {
        if (stack == null || stack.getType().isAir() || !stack.hasItemMeta()) return false;
        return ITEM_ID.equals(stack.getItemMeta().getPersistentDataContainer()
                .get(itemIdKey, PersistentDataType.STRING));
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!HeavyHammerProtocol.ACTION_CHANNEL.equals(channel) || player == null || message == null) return;
        if (message.length != 22 || !isHeavyHammer(player.getInventory().getItemInMainHand())) return;
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(message))) {
            int x = input.readInt();
            int y = input.readInt();
            int z = input.readInt();
            int cell = input.readUnsignedShort();
            int revision = input.readInt();
            int clientSequence = input.readInt();
            if (input.available() != 0) return;
            beginStrike(player, x, y, z, cell, revision, clientSequence);
        } catch (IOException | RuntimeException error) {
            plugin.getLogger().warning("Отклонён повреждённый пакет тяжёлого молота от "
                    + player.getName() + ": " + error.getMessage());
        }
    }

    private void beginStrike(Player player, int x, int y, int z, int cell, int revision, int clientSequence) {
        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();
        if (strikes.containsKey(id) || cooldowns.getOrDefault(id, 0L) > now) {
            sendCancel(player, clientSequence);
            return;
        }
        if (now < readyAt.getOrDefault(id, Long.MAX_VALUE)) {
            player.sendActionBar(Component.text("Сначала надёжно возьмите тяжёлый молот обеими руками."));
            sendCancel(player, clientSequence);
            return;
        }
        if (!player.getInventory().getItemInOffHand().getType().isAir()) {
            player.sendActionBar(Component.text("Для тяжёлого молота нужно освободить вторую руку."));
            sendCancel(player, clientSequence);
            return;
        }
        MicrovoxelManager.HammerTarget target = microvoxels.prepareHammerTarget(player, x, y, z, cell, revision);
        if (target == null) {
            sendCancel(player, clientSequence);
            return;
        }
        if (!physicallyReachable(player, target)) {
            player.sendActionBar(Component.text("Подойдите ближе: тяжёлый молот не достаёт до точки удара."));
            sendCancel(player, clientSequence);
            return;
        }
        if (!plugin.getStaminaManager().consumeWorkEffort(
                player, HeavyHammerRules.STAMINA_COST, HeavyHammerRules.FATIGUE_GAIN)) {
            player.sendActionBar(Component.text("Не хватает выносливости для тяжёлого замаха."));
            sendCancel(player, clientSequence);
            return;
        }

        int sequence = nextSequence++;
        if (nextSequence <= 0) nextSequence = 1;
        PendingStrike strike = new PendingStrike(sequence, target);
        strikes.put(id, strike);
        cooldowns.put(id, now + HeavyHammerRules.COOLDOWN_TICKS * 50L);
        player.setSprinting(false);
        broadcast(player, HeavyHammerProtocol.start(id, sequence,
                HeavyHammerRules.DURATION_TICKS, HeavyHammerRules.IMPACT_TICK,
                target.key().x(), target.key().y(), target.key().z(), target.anchorCell(),
                HeavyHammerProtocol.Face.valueOf(target.face().name())));

        Bukkit.getScheduler().runTaskLater(plugin, () -> impact(player, strike), HeavyHammerRules.IMPACT_TICK);
        Bukkit.getScheduler().runTaskLater(plugin, () -> finish(player, strike), HeavyHammerRules.DURATION_TICKS);
    }

    private void impact(Player player, PendingStrike expected) {
        if (!expected.equals(strikes.get(player.getUniqueId()))) return;
        boolean validGrip = player.isOnline() && !player.isDead()
                && isHeavyHammer(player.getInventory().getItemInMainHand())
                && player.getInventory().getItemInOffHand().getType().isAir()
                && physicallyReachable(player, expected.target());
        int removed = validGrip ? microvoxels.commitHammerImpact(player, expected.target()) : 0;
        boolean success = removed > 0;
        broadcast(player, HeavyHammerProtocol.impact(player.getUniqueId(), expected.sequence(), success));
        if (!success) return;

        ItemStack hammer = player.getInventory().getItemInMainHand();
        player.getInventory().setItemInMainHand(hammer.damage(1, player));
        TargetPoint point = targetPoint(expected.target());
        var location = player.getWorld().getBlockAt(
                expected.target().key().x(), expected.target().key().y(), expected.target().key().z())
                .getLocation().add(point.x() - expected.target().key().x(),
                        point.y() - expected.target().key().y(), point.z() - expected.target().key().z());
        player.getWorld().spawnParticle(Particle.SMOKE, location, Math.min(20, 5 + removed / 4), 0.22, 0.22, 0.22, 0.015);
        player.getWorld().playSound(location, Sound.BLOCK_ANVIL_LAND, 0.72f, 0.62f);
        player.getWorld().playSound(location, Sound.BLOCK_STONE_BREAK, 0.85f, 0.72f);
    }

    private void finish(Player player, PendingStrike expected) {
        strikes.remove(player.getUniqueId(), expected);
    }

    private boolean physicallyReachable(Player player, MicrovoxelManager.HammerTarget target) {
        if (player == null || target == null || !player.isOnline() || player.isDead()) return false;
        TargetPoint point = targetPoint(target);
        var playerLocation = player.getLocation();
        double dx = point.x() - playerLocation.getX();
        double dy = point.y() - playerLocation.getY();
        double dz = point.z() - playerLocation.getZ();
        double horizontalSquared = dx * dx + dz * dz;
        return horizontalSquared <= HeavyHammerRules.MAX_HORIZONTAL_TARGET_DISTANCE
                * HeavyHammerRules.MAX_HORIZONTAL_TARGET_DISTANCE
                && dy >= HeavyHammerRules.MIN_TARGET_HEIGHT
                && dy <= HeavyHammerRules.MAX_TARGET_HEIGHT
                && horizontalSquared + dy * dy <= HeavyHammerRules.MAX_TARGET_DISTANCE
                * HeavyHammerRules.MAX_TARGET_DISTANCE;
    }

    private static TargetPoint targetPoint(MicrovoxelManager.HammerTarget target) {
        double scale = 1.0 / MicrovoxelVolume.RESOLUTION;
        int cell = target.anchorCell();
        return new TargetPoint(
                target.key().x() + (MicrovoxelVolume.x(cell) + 0.5 + target.face().dx() * 0.5) * scale,
                target.key().y() + (MicrovoxelVolume.y(cell) + 0.5 + target.face().dy() * 0.5) * scale,
                target.key().z() + (MicrovoxelVolume.z(cell) + 0.5 + target.face().dz() * 0.5) * scale);
    }

    private void sendCancel(Player player, int clientSequence) {
        player.sendPluginMessage(plugin, HeavyHammerProtocol.SYNC_CHANNEL,
                HeavyHammerProtocol.cancel(player.getUniqueId(), clientSequence));
    }

    private void broadcast(Player source, byte[] payload) {
        for (Player observer : source.getWorld().getPlayers()) {
            if (observer.getLocation().distanceSquared(source.getLocation()) <= 96.0 * 96.0) {
                observer.sendPluginMessage(plugin, HeavyHammerProtocol.SYNC_CHANNEL, payload);
            }
        }
    }

    private void pollCarryStates() {
        carryPoll++;
        boolean refresh = carryPoll % 20 == 0;
        for (Player player : Bukkit.getOnlinePlayers()) {
            boolean present = false;
            for (ItemStack stack : player.getInventory().getContents()) {
                if (isHeavyHammer(stack)) {
                    present = true;
                    break;
                }
            }
            boolean selected = isHeavyHammer(player.getInventory().getItemInMainHand());
            CarryState previous = carryStates.get(player.getUniqueId());
            CarryState current = new CarryState(present, selected);
            if (!current.equals(previous)) {
                carryStates.put(player.getUniqueId(), current);
                if (selected && (previous == null || !previous.selected())) {
                    readyAt.put(player.getUniqueId(), System.currentTimeMillis() + 1500L);
                } else if (!selected) {
                    readyAt.remove(player.getUniqueId());
                }
                broadcast(player, HeavyHammerProtocol.carry(player.getUniqueId(), nextCarryRevision(),
                        present, selected));
            } else if (refresh) {
                broadcast(player, HeavyHammerProtocol.carry(player.getUniqueId(), nextCarryRevision(),
                        present, selected));
            }
        }
    }

    private int nextCarryRevision() {
        int revision = carryRevision++;
        if (carryRevision <= 0) carryRevision = 1;
        return revision;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player observer = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!observer.isOnline()) return;
            for (Player source : observer.getWorld().getPlayers()) {
                CarryState state = carryStates.get(source.getUniqueId());
                if (state != null && observer.getLocation().distanceSquared(source.getLocation()) <= 96.0 * 96.0) {
                    observer.sendPluginMessage(plugin, HeavyHammerProtocol.SYNC_CHANNEL,
                            HeavyHammerProtocol.carry(source.getUniqueId(), nextCarryRevision(),
                                    state.present(), state.selected()));
                }
            }
        }, 10L);
    }

    private void registerRecipe() {
        Bukkit.removeRecipe(recipeKey);
        ShapedRecipe recipe = new ShapedRecipe(recipeKey, createItem());
        recipe.shape("III", "ISI", " S ");
        recipe.setIngredient('I', Material.IRON_INGOT);
        recipe.setIngredient('S', Material.STICK);
        Bukkit.addRecipe(recipe);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        strikes.remove(event.getPlayer().getUniqueId());
        cooldowns.remove(event.getPlayer().getUniqueId());
        carryStates.remove(event.getPlayer().getUniqueId());
        readyAt.remove(event.getPlayer().getUniqueId());
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("rpchat.heavyhammer.give")) {
            sender.sendMessage(Component.text("Недостаточно прав."));
            return true;
        }
        Player target;
        if (args.length > 0) {
            target = Bukkit.getPlayerExact(args[0]);
        } else {
            target = sender instanceof Player player ? player : null;
        }
        if (target == null) {
            sender.sendMessage(Component.text("Укажите игрока: /heavyhammer <игрок>"));
            return true;
        }
        Map<Integer, ItemStack> overflow = target.getInventory().addItem(createItem());
        overflow.values().forEach(item -> target.getWorld().dropItemNaturally(target.getLocation(), item));
        sender.sendMessage(Component.text("Тяжёлый рабочий молот выдан игроку " + target.getName() + "."));
        return true;
    }

    private record PendingStrike(int sequence, MicrovoxelManager.HammerTarget target) {
    }

    private record TargetPoint(double x, double y, double z) {
    }

    private record CarryState(boolean present, boolean selected) {
    }
}
