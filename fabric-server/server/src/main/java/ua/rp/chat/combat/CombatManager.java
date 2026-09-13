package ua.rp.chat.combat;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import ua.rp.chat.RPChat;
import ua.rp.chat.RpChatChannel;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import ua.rp.chat.projectile.ArrowImpactPhysics;
import java.util.concurrent.ThreadLocalRandom;

public class CombatManager {
    public static final String INTENT_CHANNEL = "rpchat:combat_intent";

    private static final net.minecraft.ChatFormatting ROLL_COLOR = net.minecraft.ChatFormatting.GOLD;
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

    public void handleIntent(ServerPlayer player, long attackId, UUID targetId, int zoneOrdinal, double hitRatio, double lateral, double distance) {
        if (player == null) {
            return;
        }
        CombatBodyZone zone = zoneByOrdinal(zoneOrdinal);
        if (zone == null) {
            zone = CombatBodyZone.fromHitRatio(hitRatio, lateral);
        }
        CombatIntent intent = new CombatIntent(player.getUUID(), targetId, zone, hitRatio, lateral, distance, attackId, System.currentTimeMillis());
        intents.put(player.getUUID(), intent);
    }

    public void rememberProjectileLaunch(UUID projectileId, ItemStack bow) {
        projectileWeapons.put(projectileId, WeaponProfile.fromItem(bow));
    }

    public boolean onCombatDamage(ServerPlayer victim, DamageSource source, float amount) {
        if (victim == null || amount <= 0.0f) {
            return false;
        }
        ServerPlayer attacker = attackerFor(source.getEntity());
        if (attacker == null || attacker.equals(victim) || !canUseRpCombat(attacker, victim)) {
            return false;
        }

        WeaponProfile weapon = weaponFor(attacker, source.getDirectEntity());
        CombatIntent intent = consumeValidIntent(attacker, victim, weapon);
        CombatBodyZone zone = resolveZone(victim, source.getDirectEntity(), intent);

        long now = System.currentTimeMillis();
        long readyAt = cooldowns.getOrDefault(attacker.getUUID(), 0L);
        if (readyAt > now) {
            attacker.sendSystemMessage(Component.literal("Вы еще не готовы к следующему удару"), true);
            return true;
        }
        cooldowns.put(attacker.getUUID(), now + weapon.cooldownMs());

        CombatResult result = resolve(attacker, victim, weapon, zone, intent);
        ArrowImpactPhysics.Result projectileImpact =
                plugin.getStaminaManager().resolveProjectileImpact(victim, source, zone.ordinal());
        playResult(result, source.getDirectEntity(), projectileImpact);
        return true;
    }

    private CombatResult resolve(ServerPlayer attacker, ServerPlayer victim, WeaponProfile weapon,
                                 CombatBodyZone zone, CombatIntent intent) {
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
        String previous = lastMeTemplates.get(attacker.getUUID());
        String meLine = lineService.meLine(victimName, weapon, zone, outcome, previous);
        lastMeTemplates.put(attacker.getUUID(), meLine);
        String doLine = outcome.landed()
                ? lineService.doLine(weapon, zone, outcome, medicalDamage)
                : "";

        double hitRatio = intent == null ? defaultHitRatio(zone) : clamp(intent.hitRatio(), 0.0, 1.0);
        double lateral = intent == null ? defaultLateral(zone) : clamp(intent.lateral(), -1.0, 1.0);
        return new CombatResult(attacker, victim, weapon, zone, outcome, natural, attackTotal, defense,
                healthDamage, medicalDamage, hitRatio, lateral, meLine, doLine);
    }

    private void playResult(CombatResult result, Entity directDamager,
                            ArrowImpactPhysics.Result projectileImpact) {
        if (isDebugViewer(result.attacker())) {
            plugin.getRpChatService().sendSystemLocal(
                    result.attacker(),
                    RpChatChannel.DESCRIPTION,
                    Component.literal("Бросок боя: d20=" + result.naturalRoll()
                            + ", итог " + result.attackTotal()
                            + " против защиты " + result.defenseTotal()
                            + " — " + result.outcome().label() + ".").withStyle(ROLL_COLOR)
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
        Vec3 incoming = directDamager instanceof AbstractArrow arrow
                && arrow.getDeltaMovement().lengthSqr() > 1.0e-6
                ? arrow.getDeltaMovement().normalize()
                : result.victim().getBoundingBox().getCenter()
                .subtract(result.attacker().getBoundingBox().getCenter()).normalize();
        plugin.getStaminaManager().applyCombatInjury(
                result.victim(),
                result.zone(),
                result.medicalDamage(),
                result.healthDamage(),
                result.weapon().damageProfile(),
                incoming,
                result.hitRatio(),
                result.lateral(),
                projectileImpact
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

    private ServerPlayer attackerFor(Entity damager) {
        if (damager instanceof ServerPlayer player) {
            return player;
        }
        if (damager instanceof Projectile projectile) {
            Entity shooter = projectile.getOwner();
            if (shooter instanceof ServerPlayer player) {
                return player;
            }
        }
        return null;
    }

    private WeaponProfile weaponFor(ServerPlayer attacker, Entity damager) {
        if (damager instanceof AbstractArrow arrow) {
            WeaponProfile remembered = projectileWeapons.remove(arrow.getUUID());
            if (remembered != null) {
                return remembered;
            }
            ItemStack hand = attacker.getMainHandItem();
            if (hand.getItem() == Items.CROSSBOW || hand.getItem() == Items.BOW) {
                return WeaponProfile.fromItem(hand);
            }
            return WeaponProfile.fromItem(new ItemStack(Items.BOW));
        }
        return WeaponProfile.fromItem(attacker.getMainHandItem());
    }

    private CombatIntent consumeValidIntent(ServerPlayer attacker, ServerPlayer victim, WeaponProfile weapon) {
        CombatIntent intent = intents.remove(attacker.getUUID());
        if (intent == null) {
            return null;
        }
        long age = System.currentTimeMillis() - intent.receivedAt();
        if (age < 0 || age > INTENT_TTL_MS) {
            return null;
        }
        if (!attacker.getUUID().equals(intent.attackerId()) || !victim.getUUID().equals(intent.targetId())) {
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

    private CombatBodyZone resolveZone(ServerPlayer victim, Entity damager, CombatIntent intent) {
        if (intent != null && intent.zone() != null) {
            return intent.zone();
        }
        if (damager instanceof Projectile projectile) {
            double relY = projectile.position().y - victim.position().y;
            double height = Math.max(1.6, victim.getBbHeight());
            return CombatBodyZone.fromHitRatio(relY / height, 0.0);
        }
        return CombatBodyZone.weightedFallback();
    }

    private boolean canUseRpCombat(ServerPlayer attacker, ServerPlayer victim) {
        if (!plugin.getConfig().getBoolean("combat.enabled", true)) {
            return false;
        }
        if (attacker.gameMode.getGameModeForPlayer() == GameType.SPECTATOR || victim.gameMode.getGameModeForPlayer() == GameType.SPECTATOR) {
            return false;
        }
        if (plugin.getAuthManager() != null
                && (plugin.getAuthManager().isPendingAuth(attacker.getUUID())
                || plugin.getAuthManager().isPendingAuth(victim.getUUID()))) {
            return false;
        }
        return ((ServerLevel) attacker.level()).equals(((ServerLevel) victim.level())) && attacker.hasLineOfSight(victim);
    }

    private int armorDefense(ServerPlayer player) {
        int defense = 0;
        defense += armorPieceDefense(player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD));
        defense += armorPieceDefense(player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST));
        defense += armorPieceDefense(player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.LEGS));
        defense += armorPieceDefense(player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.FEET));
        var attr = player.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.ARMOR);
        if (attr != null) {
            defense = Math.max(defense, (int) Math.round(attr.getValue() / 4.0));
        }
        return Math.min(6, defense);
    }

    private int armorPieceDefense(ItemStack item) {
        if (item == null || item.isEmpty()) {
            return 0;
        }
        String name = item.getItem().toString().toUpperCase(java.util.Locale.ROOT);
        if (name.contains("NETHERITE") || name.contains("DIAMOND")) return 2;
        if (name.contains("IRON") || name.contains("CHAINMAIL")) return 1;
        return name.contains("LEATHER") || name.contains("GOLDEN") ? 1 : 0;
    }

    private boolean isBlocking(ServerPlayer player) {
        ItemStack offhand = player.getOffhandItem();
        return player.isBlocking() || (offhand != null && offhand.getItem() == Items.SHIELD);
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

    private double clamp(double value, double min, double max) {
        if (!Double.isFinite(value)) return (min + max) * 0.5;
        return Math.max(min, Math.min(max, value));
    }

    private double defaultHitRatio(CombatBodyZone zone) {
        return switch (zone) {
            case HEAD -> 0.91;
            case TORSO -> 0.64;
            case LEFT_ARM, RIGHT_ARM -> 0.60;
            case LEFT_LEG, RIGHT_LEG -> 0.23;
        };
    }

    private double defaultLateral(CombatBodyZone zone) {
        return switch (zone) {
            case LEFT_ARM, LEFT_LEG -> 0.62;
            case RIGHT_ARM, RIGHT_LEG -> -0.62;
            default -> 0.0;
        };
    }

    public boolean toggleDebug(ServerPlayer player) {
        if (player == null || !RPChat.hasPermission(player, "rpchat.admin", 4)) {
            return false;
        }
        UUID uuid = player.getUUID();
        boolean enabled = !debugViewers.getOrDefault(uuid, false);
        if (enabled) {
            debugViewers.put(uuid, true);
        } else {
            debugViewers.remove(uuid);
        }
        return enabled;
    }

    private boolean isDebugViewer(ServerPlayer player) {
        return player != null && RPChat.hasPermission(player, "rpchat.admin", 4)
                && debugViewers.getOrDefault(player.getUUID(), false);
    }
}
