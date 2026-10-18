package ua.rp.chat.combat;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.bukkit.projectiles.ProjectileSource;
import ua.rp.chat.RPChat;
import ua.rp.chat.RpChatChannel;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public class CombatManager implements Listener, PluginMessageListener {
    public static final String INTENT_CHANNEL = "rpchat:combat_intent";

    private static final TextColor ROLL_COLOR = TextColor.color(0xC8B998);
    private static final long INTENT_TTL_MS = 650L;
    private final RPChat plugin;
    private final CombatLineService lineService = new CombatLineService();
    private final Map<UUID, CombatIntent> intents = new ConcurrentHashMap<>();
    private final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();
    private final Map<UUID, WeaponProfile> projectileWeapons = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> debugViewers = new ConcurrentHashMap<>();
    private final Map<UUID, String> lastMeTemplates = new ConcurrentHashMap<>();

    public CombatManager(RPChat plugin) {
        this.plugin = plugin;
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!INTENT_CHANNEL.equals(channel) || player == null || message == null) {
            return;
        }
        CombatIntent intent = readIntent(player, message);
        if (intent != null) {
            intents.put(player.getUniqueId(), intent);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void rememberProjectileLaunch(EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof Player) || event.getProjectile() == null) {
            return;
        }
        projectileWeapons.put(event.getProjectile().getUniqueId(), WeaponProfile.fromItem(event.getBow()));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCombatDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }

        Player attacker = attackerFor(event.getDamager());
        if (attacker == null || attacker.equals(victim) || !canUseRpCombat(attacker, victim)) {
            return;
        }

        WeaponProfile weapon = weaponFor(attacker, event.getDamager());
        CombatIntent intent = consumeValidIntent(attacker, victim, weapon);
        CombatBodyZone zone = resolveZone(victim, event.getDamager(), intent);

        event.setCancelled(true);

        long now = System.currentTimeMillis();
        long readyAt = cooldowns.getOrDefault(attacker.getUniqueId(), 0L);
        if (readyAt > now) {
            attacker.sendActionBar("Вы еще не готовы к следующему удару");
            return;
        }
        cooldowns.put(attacker.getUniqueId(), now + weapon.cooldownMs());

        CombatResult result = resolve(attacker, victim, weapon, zone);
        playResult(result);
    }

    private CombatResult resolve(Player attacker, Player victim, WeaponProfile weapon, CombatBodyZone zone) {
        int natural = ThreadLocalRandom.current().nextInt(1, 21);
        int attackModifier = weapon.accuracy() - plugin.getStaminaManager().combatAttackPenalty(attacker);
        int defense = 10 + armorDefense(victim) - plugin.getStaminaManager().combatDefensePenalty(victim);
        if (isBlocking(victim)) {
            defense += 3;
        }
        if (zone == CombatBodyZone.HEAD) {
            defense += 2;
        }
        if (zone.leg()) {
            defense -= 1;
        }

        int attackTotal = natural + attackModifier;
        CombatOutcome outcome = outcomeFor(natural, attackTotal, defense, weapon);
        double healthDamage = healthDamage(weapon, outcome, zone);
        double medicalDamage = healthDamage * weapon.medicalMultiplier() * medicalZoneMultiplier(zone);
        if (outcome.landed()) {
            medicalDamage = Math.max(medicalDamage, minimumMedicalDamage(outcome, weapon));
        }

        String victimName = plugin.getRpChatService().rpName(victim);
        String previous = lastMeTemplates.get(attacker.getUniqueId());
        String meLine = lineService.meLine(victimName, weapon, zone, outcome, previous);
        lastMeTemplates.put(attacker.getUniqueId(), meLine);
        String doLine = outcome.landed()
                ? lineService.doLine(weapon, zone, outcome, medicalDamage)
                : "";

        return new CombatResult(attacker, victim, weapon, zone, outcome, natural, attackTotal, defense,
                healthDamage, medicalDamage, meLine, doLine);
    }

    private void playResult(CombatResult result) {
        if (isDebugViewer(result.attacker())) {
            plugin.getRpChatService().sendSystemLocal(
                    result.attacker(),
                    RpChatChannel.DESCRIPTION,
                    Component.text("Бросок боя: d20=" + result.naturalRoll()
                            + ", итог " + result.attackTotal()
                            + " против защиты " + result.defenseTotal()
                            + " — " + result.outcome().label() + ".", ROLL_COLOR)
            );
        }

        plugin.getRpChatService().sendActionHighlighted(
                result.attacker(),
                result.meLine(),
                plugin.getRpChatService().rpName(result.victim())
        );
        if (!result.landed()) {
            return;
        }

        plugin.getRpChatService().sendDescription(result.victim(), result.doLine());
        plugin.getStaminaManager().applyCombatInjury(
                result.victim(),
                result.zone(),
                result.medicalDamage(),
                result.healthDamage(),
                result.weapon().damageProfile()
        );
    }

    private CombatOutcome outcomeFor(int natural, int total, int defense, WeaponProfile weapon) {
        if (natural == 1) {
            return CombatOutcome.CRITICAL_MISS;
        }
        if (natural >= weapon.critThreshold() && total >= defense) {
            return CombatOutcome.CRITICAL_HIT;
        }
        int margin = total - defense;
        if (margin < -4) {
            return CombatOutcome.MISS;
        }
        if (margin < 0) {
            return CombatOutcome.GRAZE;
        }
        if (margin < 6) {
            return CombatOutcome.HIT;
        }
        if (margin < 11) {
            return CombatOutcome.STRONG_HIT;
        }
        return CombatOutcome.CRITICAL_HIT;
    }

    private double healthDamage(WeaponProfile weapon, CombatOutcome outcome, CombatBodyZone zone) {
        double multiplier = switch (outcome) {
            case CRITICAL_MISS, MISS -> 0.0;
            case GRAZE -> 0.26;
            case HIT -> 0.58;
            case STRONG_HIT -> 0.86;
            case CRITICAL_HIT -> 1.18;
        };
        double zoneMultiplier = switch (zone) {
            case HEAD -> 1.22;
            case TORSO -> 1.0;
            case LEFT_ARM, RIGHT_ARM -> 0.78;
            case LEFT_LEG, RIGHT_LEG -> 0.84;
        };
        return round(Math.max(0.0, weapon.baseDamage() * multiplier * zoneMultiplier));
    }

    private double medicalZoneMultiplier(CombatBodyZone zone) {
        return switch (zone) {
            case HEAD -> 1.16;
            case TORSO -> 1.05;
            case LEFT_ARM, RIGHT_ARM -> 0.92;
            case LEFT_LEG, RIGHT_LEG -> 0.98;
        };
    }

    private double minimumMedicalDamage(CombatOutcome outcome, WeaponProfile weapon) {
        double base = switch (outcome) {
            case GRAZE -> 0.75;
            case HIT -> 1.25;
            case STRONG_HIT -> 2.0;
            case CRITICAL_HIT -> 3.1;
            default -> 0.0;
        };
        return weapon.damageProfile() == CombatDamageProfile.BLUNT ? base * 1.08 : base;
    }

    private Player attackerFor(Entity damager) {
        if (damager instanceof Player player) {
            return player;
        }
        if (damager instanceof Projectile projectile) {
            ProjectileSource shooter = projectile.getShooter();
            if (shooter instanceof Player player) {
                return player;
            }
        }
        return null;
    }

    private WeaponProfile weaponFor(Player attacker, Entity damager) {
        if (damager instanceof AbstractArrow arrow) {
            WeaponProfile remembered = projectileWeapons.remove(arrow.getUniqueId());
            if (remembered != null) {
                return remembered;
            }
            Material hand = attacker.getInventory().getItemInMainHand().getType();
            if (hand == Material.CROSSBOW || hand == Material.BOW) {
                return WeaponProfile.fromItem(attacker.getInventory().getItemInMainHand());
            }
            return WeaponProfile.fromItem(new ItemStack(Material.BOW));
        }
        return WeaponProfile.fromItem(attacker.getInventory().getItemInMainHand());
    }

    private CombatIntent consumeValidIntent(Player attacker, Player victim, WeaponProfile weapon) {
        CombatIntent intent = intents.remove(attacker.getUniqueId());
        if (intent == null) {
            return null;
        }
        long age = System.currentTimeMillis() - intent.receivedAt();
        if (age < 0 || age > INTENT_TTL_MS) {
            return null;
        }
        if (!attacker.getUniqueId().equals(intent.attackerId()) || !victim.getUniqueId().equals(intent.targetId())) {
            return null;
        }
        double allowed = Math.max(weapon.range() + 0.85, 3.35);
        if (intent.distance() > allowed) {
            return null;
        }
        if (!attacker.hasLineOfSight(victim)) {
            return null;
        }
        return intent;
    }

    private CombatBodyZone resolveZone(Player victim, Entity damager, CombatIntent intent) {
        if (intent != null && intent.zone() != null) {
            return intent.zone();
        }
        if (damager instanceof Projectile projectile) {
            double relY = projectile.getLocation().getY() - victim.getLocation().getY();
            double height = Math.max(1.6, victim.getHeight());
            return CombatBodyZone.fromHitRatio(relY / height, 0.0);
        }
        return CombatBodyZone.weightedFallback();
    }

    private boolean canUseRpCombat(Player attacker, Player victim) {
        if (!plugin.getConfig().getBoolean("combat.enabled", true)) {
            return false;
        }
        if (attacker.getGameMode() == GameMode.SPECTATOR || victim.getGameMode() == GameMode.SPECTATOR) {
            return false;
        }
        if (attacker.getGameMode() == GameMode.CREATIVE || victim.getGameMode() == GameMode.CREATIVE) {
            return attacker.hasPermission("rpchat.admin");
        }
        if (plugin.getAuthManager() != null
                && (plugin.getAuthManager().isPendingAuth(attacker.getUniqueId())
                || plugin.getAuthManager().isPendingAuth(victim.getUniqueId()))) {
            return false;
        }
        return attacker.getWorld().equals(victim.getWorld()) && attacker.hasLineOfSight(victim);
    }

    private int armorDefense(Player player) {
        EntityEquipment equipment = player.getEquipment();
        if (equipment == null) {
            return 0;
        }
        int defense = 0;
        defense += armorPieceDefense(equipment.getHelmet());
        defense += armorPieceDefense(equipment.getChestplate());
        defense += armorPieceDefense(equipment.getLeggings());
        defense += armorPieceDefense(equipment.getBoots());
        if (player.getAttribute(Attribute.ARMOR) != null) {
            defense = Math.max(defense, (int) Math.round(player.getAttribute(Attribute.ARMOR).getValue() / 4.0));
        }
        return Math.min(6, defense);
    }

    private int armorPieceDefense(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return 0;
        }
        String name = item.getType().name();
        if (name.contains("NETHERITE") || name.contains("DIAMOND")) return 2;
        if (name.contains("IRON") || name.contains("CHAINMAIL")) return 1;
        return name.contains("LEATHER") || name.contains("GOLDEN") ? 1 : 0;
    }

    private boolean isBlocking(Player player) {
        ItemStack offhand = player.getInventory().getItemInOffHand();
        return player.isBlocking() || (offhand != null && offhand.getType() == Material.SHIELD);
    }

    private CombatIntent readIntent(Player player, byte[] message) {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(message))) {
            int version = input.readInt();
            if (version != CombatIntent.PROTOCOL_VERSION) {
                return null;
            }
            long attackId = input.readLong();
            UUID targetId = new UUID(input.readLong(), input.readLong());
            int zoneOrdinal = input.readInt();
            double hitRatio = input.readDouble();
            double lateral = input.readDouble();
            double distance = input.readDouble();
            CombatBodyZone zone = zoneByOrdinal(zoneOrdinal);
            if (zone == null) {
                zone = CombatBodyZone.fromHitRatio(hitRatio, lateral);
            }
            return new CombatIntent(player.getUniqueId(), targetId, zone, hitRatio, lateral, distance, attackId, System.currentTimeMillis());
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    private CombatBodyZone zoneByOrdinal(int ordinal) {
        CombatBodyZone[] zones = CombatBodyZone.values();
        if (ordinal < 0 || ordinal >= zones.length) {
            return null;
        }
        return zones[ordinal];
    }

    private double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    public boolean toggleDebug(Player player) {
        if (player == null || !player.isOp()) {
            return false;
        }
        UUID uuid = player.getUniqueId();
        boolean enabled = !debugViewers.getOrDefault(uuid, false);
        if (enabled) {
            debugViewers.put(uuid, true);
        } else {
            debugViewers.remove(uuid);
        }
        return enabled;
    }

    private boolean isDebugViewer(Player player) {
        return player != null && player.isOp() && debugViewers.getOrDefault(player.getUniqueId(), false);
    }
}
