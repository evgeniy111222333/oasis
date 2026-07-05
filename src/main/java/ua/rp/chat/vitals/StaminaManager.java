package ua.rp.chat.vitals;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerToggleSprintEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import ua.rp.chat.combat.CombatBodyZone;
import ua.rp.chat.combat.CombatDamageProfile;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;

public class StaminaManager implements Listener {
    private static final double MAX_STAMINA = 100.0;
    private static final double MAX_BLOOD = 100.0;
    private static final int INFECTION_START_TICKS = 20 * 60 * 5;
    private static final int SAVE_INTERVAL_TICKS = 20 * 30;
    private static final int STORAGE_VERSION = 1;

    private final JavaPlugin plugin;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final File storageFile;
    private final Map<UUID, Vitals> vitals = new ConcurrentHashMap<>();
    private final Map<UUID, PendingTreatment> treatments = new ConcurrentHashMap<>();
    private int saveTicks = 0;
    private boolean dirty = false;

    public StaminaManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.storageFile = new File(plugin.getDataFolder(), "vitals.json");
    }

    public void start() {
        loadFromDisk();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        new BukkitRunnable() {
            @Override
            public void run() {
                tick();
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    public void shutdown() {
        saveToDisk(true);
    }

    public Vitals getVitals(Player player) {
        return vitals.computeIfAbsent(player.getUniqueId(), id -> new Vitals());
    }

    public JsonObject toJson(Player player) {
        Vitals v = getVitals(player);
        JsonObject json = new JsonObject();
        double health = Math.max(0.0, player.getHealth());
        double maxHealth = player.getAttribute(Attribute.MAX_HEALTH) != null
                ? player.getAttribute(Attribute.MAX_HEALTH).getValue()
                : 20.0;
        json.addProperty("success", true);
        json.addProperty("player", player.getName());
        json.addProperty("uuid", player.getUniqueId().toString());
        json.addProperty("stamina", round(v.stamina));
        json.addProperty("breathDebt", round(v.breathDebt));
        json.addProperty("fatigue", round(v.fatigue));
        json.addProperty("blood", round(v.blood));
        json.addProperty("pain", round(v.totalPain()));
        json.addProperty("bleeding", round(v.totalBleeding()));
        json.addProperty("unconscious", v.unconsciousTicks > 0);
        json.addProperty("health", round(health));
        json.addProperty("maxHealth", round(maxHealth));
        json.addProperty("band", staminaBand(v.stamina));
        json.addProperty("bandLabel", staminaLabel(v.stamina));
        json.add("treatment", treatmentJson(player));
        json.add("parts", bodyParts(v));
        return json;
    }

    public int combatAttackPenalty(Player player) {
        Vitals v = getVitals(player);
        BodyPartState mainArm = v.zone(BodyZone.RIGHT_ARM);
        int penalty = 0;
        if (mainArm.isBroken()) {
            penalty += mainArm.fractureStabilized ? 4 : 7;
        } else if (mainArm.condition < 55.0) {
            penalty += 2;
        }
        if (mainArm.pain > 35.0) {
            penalty += 1 + (int) Math.min(3, mainArm.pain / 22.0);
        }
        if (v.stamina < 18.0) {
            penalty += 4;
        } else if (v.stamina < 38.0) {
            penalty += 2;
        }
        if (v.breathDebt > 70.0) {
            penalty += 2;
        }
        if (v.totalPain() > 62.0) {
            penalty += 2;
        }
        if (v.unconsciousTicks > 0) {
            penalty += 20;
        }
        return penalty;
    }

    public int combatDefensePenalty(Player player) {
        Vitals v = getVitals(player);
        int penalty = 0;
        if (v.zone(BodyZone.LEFT_LEG).isBroken() || v.zone(BodyZone.RIGHT_LEG).isBroken()) {
            penalty += 5;
        } else {
            if (v.zone(BodyZone.LEFT_LEG).condition < 55.0) penalty += 2;
            if (v.zone(BodyZone.RIGHT_LEG).condition < 55.0) penalty += 2;
        }
        if (v.stamina < 20.0) {
            penalty += 3;
        }
        if (v.totalPain() > 70.0) {
            penalty += 3;
        }
        if (v.unconsciousTicks > 0) {
            penalty += 12;
        }
        return penalty;
    }

    public void applyCombatInjury(Player victim, CombatBodyZone combatZone, double medicalDamage,
                                  double healthDamage, CombatDamageProfile combatProfile) {
        if (victim == null || victim.isDead() || medicalDamage <= 0.0) {
            return;
        }
        cancelTreatment(victim, "Лечение прервано.");
        Vitals v = getVitals(victim);
        BodyZone zone = bodyZoneFor(combatZone);
        DamageProfile profile = damageProfileFor(combatProfile);
        applyInjury(victim, v, zone, medicalDamage, profile);
        v.lastDamage = Math.max(v.lastDamage, medicalDamage);
        v.lastDamageCause = profile.id;
        v.breathDebt = clamp(v.breathDebt + medicalDamage * profile.breathDebt, 0.0, 100.0);
        v.fatigue = clamp(v.fatigue + medicalDamage * profile.fatigue, 0.0, 100.0);
        markDirty();

        if (profile == DamageProfile.BLUNT) {
            victim.getWorld().spawnParticle(
                    Particle.DAMAGE_INDICATOR,
                    victim.getLocation().add(0.0, zoneParticleY(zone), 0.0),
                    Math.max(2, Math.min(10, (int) Math.ceil(medicalDamage))),
                    0.16, 0.10, 0.16, 0.0
            );
        } else {
            victim.getWorld().spawnParticle(
                    Particle.DUST,
                    victim.getLocation().add(0.0, zoneParticleY(zone), 0.0),
                    Math.max(2, Math.min(10, (int) Math.ceil(medicalDamage))),
                    0.16, 0.10, 0.16, 0.0,
                    new Particle.DustOptions(Color.fromRGB(120, 0, 0), 1.05f)
            );
        }
        victim.playSound(victim.getLocation(), Sound.ENTITY_PLAYER_HURT, 0.75f, profile == DamageProfile.BLUNT ? 0.72f : 0.92f);
        damagePlayerSilently(victim, healthDamage);
    }

    public JsonObject startTreatment(Player player, String partId, String actionId) {
        JsonObject response = new JsonObject();
        if (player == null || partId == null || actionId == null) {
            response.addProperty("success", false);
            response.addProperty("message", "Не вказано ціль лікування.");
            return response;
        }

        BodyZone zone = BodyZone.byId(partId);
        TreatmentAction action = TreatmentAction.byId(actionId);
        if (zone == null || action == null) {
            response.addProperty("success", false);
            response.addProperty("message", "Невідома медична дія.");
            return response;
        }

        Vitals v = getVitals(player);
        BodyPartState part = v.zone(zone);
        String unavailable = action.unavailableReason(zone, part);
        if (unavailable != null) {
            response.addProperty("success", false);
            response.addProperty("message", unavailable);
            return response;
        }
        if (!hasTreatmentItem(player, action)) {
            response.addProperty("success", false);
            response.addProperty("message", "Нужно: " + action.itemLabel);
            return response;
        }
        if (treatments.containsKey(player.getUniqueId())) {
            response.addProperty("success", false);
            response.addProperty("message", "Лечение уже идет.");
            return response;
        }

        PendingTreatment pending = new PendingTreatment(zone, action, action.durationTicks, player.getLocation());
        treatments.put(player.getUniqueId(), pending);
        player.setSprinting(false);
        player.sendActionBar(action.label + ": 0%");

        response.addProperty("success", true);
        response.addProperty("message", "Лечение начато.");
        response.add("treatment", pending.toJson());
        return response;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        Vitals v = vitals.get(player.getUniqueId());
        if (v != null) {
            restorePlayerMovementAndSymptoms(player);
            markDirty();
            saveToDisk(false);
        }
        treatments.remove(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        event.deathMessage(null);
        resetVitalsAfterDeath(event.getEntity(), false);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Bukkit.getScheduler().runTask(plugin, () -> resetVitalsAfterDeath(event.getPlayer(), true));
    }

    @EventHandler
    public void onNaturalRegain(EntityRegainHealthEvent event) {
        if (!(event.getEntity() instanceof Player)) {
            return;
        }
        if (event.getRegainReason() == EntityRegainHealthEvent.RegainReason.SATIATED) {
            event.setAmount(Math.min(event.getAmount() * 0.18, 0.18));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        if (event.getCause() == EntityDamageEvent.DamageCause.CUSTOM || event.getFinalDamage() <= 0.0) {
            return;
        }

        cancelTreatment(player, "Treatment interrupted.");
        Vitals v = getVitals(player);
        DamageProfile profile = profileFor(event, player);
        BodyZone zone = chooseZone(event, player, profile);
        applyInjury(player, v, zone, event.getFinalDamage(), profile);

        v.lastDamage = Math.max(v.lastDamage, event.getFinalDamage());
        v.lastDamageCause = profile.id;
        v.breathDebt = clamp(v.breathDebt + event.getFinalDamage() * profile.breathDebt, 0.0, 100.0);
        v.fatigue = clamp(v.fatigue + event.getFinalDamage() * profile.fatigue, 0.0, 100.0);
        markDirty();
    }

    @EventHandler(ignoreCancelled = true)
    public void onToggleSprint(PlayerToggleSprintEvent event) {
        if (!event.isSprinting()) {
            return;
        }
        Vitals v = getVitals(event.getPlayer());
        if (v.stamina <= 5.0 || v.zone(BodyZone.LEFT_LEG).isBroken() || v.zone(BodyZone.RIGHT_LEG).isBroken()) {
            event.setCancelled(true);
            event.getPlayer().setSprinting(false);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (event.getTo() == null) {
            return;
        }
        Player player = event.getPlayer();
        PendingTreatment pending = treatments.get(player.getUniqueId());
        if (pending != null && pending.movedTooFar(event.getTo())) {
            cancelTreatment(player, "Treatment interrupted by movement.");
            return;
        }
        Vitals v = getVitals(player);
        boolean brokenLeg = v.zone(BodyZone.LEFT_LEG).isBroken() || v.zone(BodyZone.RIGHT_LEG).isBroken();
        if (!brokenLeg) {
            return;
        }
        double dy = event.getTo().getY() - event.getFrom().getY();
        if (dy > 0.28 && player.isOnGround()) {
            boolean stabilizedLeg = v.zone(BodyZone.LEFT_LEG).fractureStabilized || v.zone(BodyZone.RIGHT_LEG).fractureStabilized;
            v.addPain(stabilizedLeg ? 2.5 : 7.0);
            player.setVelocity(player.getVelocity().setY(-0.22));
            player.damage(0.6);
            if (v.unconsciousTicks <= 0 && ThreadLocalRandom.current().nextDouble() < (stabilizedLeg ? 0.04 : 0.18)) {
                knockdown(player, v, 36, "Боль в ноге сбила вас с ног");
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onTreat(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Player player = event.getPlayer();
        if (!player.isSneaking()) {
            return;
        }
        ItemStack item = event.getItem();
        if (item == null || (item.getType() != Material.PAPER && item.getType() != Material.WHITE_WOOL)) {
            return;
        }

        Vitals v = getVitals(player);
        BodyPartState target = worstBleedingPart(v);
        if (target == null || target.bleeding <= 0.1) {
            player.sendActionBar("Активного кровотечения нет");
            return;
        }

        BodyZone zone = zoneForPart(v, target);
        if (zone != null) {
            JsonObject result = startTreatment(player, zone.id, TreatmentAction.BANDAGE.id);
            if (!result.get("success").getAsBoolean() && result.has("message")) {
                player.sendActionBar(result.get("message").getAsString());
            }
            event.setCancelled(true);
            return;
        }

        target.bleeding = 0.0;
        target.openWound = false;
        target.openWoundTicks = 0;
        target.pain = clamp(target.pain + 2.0, 0.0, 100.0);
        markDirty();
        if (item.getAmount() <= 1) {
            player.getInventory().setItem(event.getHand(), new ItemStack(Material.AIR));
        } else {
            item.setAmount(item.getAmount() - 1);
        }
        player.sendActionBar("Кровотечение остановлено");
        player.playSound(player.getLocation(), Sound.ITEM_ARMOR_EQUIP_LEATHER, 0.45f, 1.1f);
        event.setCancelled(true);
    }

    private void tick() {
        saveTicks++;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getGameMode() == GameMode.SPECTATOR || player.isDead()) {
                continue;
            }
            Vitals v = getVitals(player);
            v.ticks++;

            Location location = player.getLocation();
            boolean hasPreviousMove = location.getWorld() != null
                    && v.lastMoveWorld != null
                    && location.getWorld().getUID().equals(v.lastMoveWorld);
            double moved = movementDelta(v, location);
            double verticalMove = hasPreviousMove ? Math.abs(location.getY() - v.lastMoveY) : 0.0;
            rememberMovement(v, location);
            double movementIntensity = clamp(moved / 0.145, 0.0, 1.65);
            double armorWeight = Math.min(0.30, countArmorPieces(player) * 0.060);
            boolean moving = moved > 0.018;
            boolean sprinting = player.isSprinting() && moved > 0.070;
            boolean brokenLeg = v.zone(BodyZone.LEFT_LEG).isBroken() || v.zone(BodyZone.RIGHT_LEG).isBroken();
            if (brokenLeg && player.isSprinting()) {
                player.setSprinting(false);
                sprinting = false;
            }

            double injuryLoad = 1.0;
            injuryLoad += (100.0 - Math.min(v.zone(BodyZone.LEFT_LEG).condition, v.zone(BodyZone.RIGHT_LEG).condition)) / 260.0;
            injuryLoad += v.totalPain() / 420.0;
            injuryLoad += Math.max(0.0, 70.0 - v.blood) / 260.0;
            if (v.zone(BodyZone.TORSO).condition < 65.0) {
                injuryLoad += 0.18;
            }

            double drain = 0.0;
            if (sprinting) {
                drain += (0.096 + movementIntensity * 0.082) * (1.0 + armorWeight + injuryLoad * 0.20);
            } else if (moving) {
                drain += (0.007 + movementIntensity * 0.018) * (1.0 + armorWeight * 0.75 + injuryLoad * 0.10);
            }
            if (verticalMove > 0.12 && !player.isOnGround()) {
                drain += 0.030 + Math.min(0.075, verticalMove * 0.12);
            }
            if (player.getFoodLevel() <= 6) {
                drain += 0.020;
            }

            double regen = sprinting ? 0.0 : (moving ? 0.006 : 0.145);
            if (player.getFoodLevel() <= 6) {
                regen *= 0.45;
            }
            if (player.getHealth() < 8.0 || v.totalBleeding() > 0.5 || v.blood < 65.0) {
                regen *= 0.60;
            }

            v.stamina = clamp(v.stamina + regen - drain, 0.0, MAX_STAMINA);
            double breathChange = sprinting ? 0.135 + movementIntensity * 0.090 : moving ? 0.006 + movementIntensity * 0.016 : -0.42;
            if (v.zone(BodyZone.TORSO).condition < 65.0) {
                breathChange += 0.035;
            }
            v.breathDebt = clamp(v.breathDebt + breathChange, 0.0, 100.0);
            double fatigueChange = sprinting ? 0.034 + drain * 0.08 : moving ? 0.004 : -0.060;
            if (v.stamina < 25.0) {
                fatigueChange += 0.040;
            }
            v.fatigue = clamp(v.fatigue + fatigueChange, 0.0, 100.0);
            v.lastDamage *= 0.985;

            tickTreatment(player, v);
            tickInjuries(player, v);
            applySymptoms(player, v, moving);
            if (v.totalBleeding() > 0.0 || v.blood < MAX_BLOOD || v.totalPain() > 0.0 || v.hasInfection()) {
                markDirty();
            }
        }
        if (saveTicks >= SAVE_INTERVAL_TICKS) {
            saveTicks = 0;
            saveToDisk(false);
        }
    }

    private double movementDelta(Vitals v, Location location) {
        if (location == null || location.getWorld() == null || v.lastMoveWorld == null
                || !location.getWorld().getUID().equals(v.lastMoveWorld)) {
            return 0.0;
        }
        return Math.hypot(location.getX() - v.lastMoveX, location.getZ() - v.lastMoveZ);
    }

    private void rememberMovement(Vitals v, Location location) {
        if (location == null || location.getWorld() == null) {
            v.lastMoveWorld = null;
            return;
        }
        v.lastMoveWorld = location.getWorld().getUID();
        v.lastMoveX = location.getX();
        v.lastMoveY = location.getY();
        v.lastMoveZ = location.getZ();
    }

    private void tickTreatment(Player player, Vitals v) {
        PendingTreatment pending = treatments.get(player.getUniqueId());
        if (pending == null) {
            return;
        }

        player.setSprinting(false);
        player.setVelocity(player.getVelocity().multiply(0.82));
        pending.remainingTicks--;

        int percent = (int) Math.round(pending.progress() * 100.0);
        if (pending.remainingTicks % 10 == 0 || pending.remainingTicks <= 0) {
            player.sendActionBar(pending.action.label + ": " + percent + "%");
        }
        if (pending.remainingTicks > 0) {
            return;
        }

        BodyPartState part = v.zone(pending.zone);
        String unavailable = pending.action.unavailableReason(pending.zone, part);
        if (unavailable != null) {
            cancelTreatment(player, unavailable);
            return;
        }
        if (!consumeTreatmentItem(player, pending.action)) {
            cancelTreatment(player, "Нужно: " + pending.action.itemLabel);
            return;
        }

        completeTreatment(player, part, pending.action);
        markDirty();
        treatments.remove(player.getUniqueId());
        player.playSound(player.getLocation(), Sound.ITEM_ARMOR_EQUIP_LEATHER, 0.55f, 1.0f);
        player.sendActionBar(pending.action.doneLabel);
    }

    private void completeTreatment(Player player, BodyPartState part, TreatmentAction action) {
        switch (action) {
            case BANDAGE -> {
                part.bleeding = 0.0;
                part.bandaged = true;
                part.pain = clamp(part.pain + 2.0, 0.0, 100.0);
            }
            case TOURNIQUET -> {
                part.bleeding = 0.0;
                part.tourniquet = true;
                part.tourniquetTicks = 0;
                part.pain = clamp(part.pain + 4.0, 0.0, 100.0);
            }
            case RELEASE_TOURNIQUET -> {
                part.tourniquet = false;
                part.tourniquetTicks = 0;
                if (part.openWound && !part.bandaged) {
                    part.bleeding = clamp(part.bleeding + 3.5, 0.0, 100.0);
                }
            }
            case CLEAN_WOUND -> {
                part.woundCleaned = true;
                part.openWoundTicks = Math.max(0, part.openWoundTicks - 20 * 60 * 2);
                part.pain = clamp(part.pain - 4.0, 0.0, 100.0);
                if (part.infected && ThreadLocalRandom.current().nextDouble() < 0.18) {
                    part.infected = false;
                }
            }
            case SPLINT -> {
                part.fractureStabilized = true;
                part.pain = clamp(part.pain - 8.0, 0.0, 100.0);
            }
            case EXTRACT_ARROW -> {
                part.embeddedArrow = false;
                part.openWound = true;
                part.bandaged = false;
                part.bleeding = clamp(part.bleeding + 4.0, 0.0, 100.0);
                part.pain = clamp(part.pain + 8.0, 0.0, 100.0);
                player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_HURT, 0.55f, 0.8f);
            }
            case PAINKILLER -> {
                part.medicatedTicks = Math.max(part.medicatedTicks, 20 * 60 * 4);
                part.pain = clamp(part.pain - 18.0, 0.0, 100.0);
            }
            case ANTIBIOTIC -> {
                part.antibioticsTicks = Math.max(part.antibioticsTicks, 20 * 60 * 6);
                part.pain = clamp(part.pain - 5.0, 0.0, 100.0);
            }
            case TREAT_BURN -> {
                part.burnTreated = true;
                part.burn = clamp(part.burn - 22.0, 0.0, 100.0);
                part.pain = clamp(part.pain - 9.0, 0.0, 100.0);
                part.woundCleaned = true;
            }
        }
    }

    private void cancelTreatment(Player player, String message) {
        PendingTreatment removed = treatments.remove(player.getUniqueId());
        if (removed != null) {
            player.sendActionBar(message);
            player.playSound(player.getLocation(), Sound.BLOCK_WOODEN_BUTTON_CLICK_OFF, 0.35f, 0.8f);
        }
    }

    private void resetVitalsAfterDeath(Player player, boolean notify) {
        UUID uuid = player.getUniqueId();
        vitals.remove(uuid);
        treatments.remove(uuid);
        restorePlayerMovementAndSymptoms(player);
        markDirty();
        saveToDisk(false);
    }

    private void restorePlayerMovementAndSymptoms(Player player) {
        player.setWalkSpeed(0.2f);
        player.setSprinting(false);
        player.removePotionEffect(PotionEffectType.BLINDNESS);
        player.removePotionEffect(PotionEffectType.NAUSEA);
        player.removePotionEffect(PotionEffectType.SLOWNESS);
        player.removePotionEffect(PotionEffectType.MINING_FATIGUE);
        player.removePotionEffect(PotionEffectType.WEAKNESS);
        player.removePotionEffect(PotionEffectType.POISON);
    }

    private void tickInjuries(Player player, Vitals v) {
        double bleeding = v.totalBleeding();
        if (bleeding > 0.0) {
            v.blood = clamp(v.blood - bleeding * 0.0025, 0.0, MAX_BLOOD);
            if (v.ticks % 8 == 0) {
                player.getWorld().spawnParticle(
                        Particle.DUST,
                        player.getLocation().add(0.0, 0.08, 0.0),
                        Math.min(8, 1 + (int) Math.ceil(bleeding)),
                        0.18, 0.02, 0.18, 0.0,
                        new Particle.DustOptions(Color.fromRGB(120, 0, 0), 1.1f)
                );
            }
            if (v.ticks % 40 == 0) {
                damagePlayerSilently(player, Math.min(0.65, bleeding * 0.065));
            }
        } else {
            v.blood = clamp(v.blood + 0.004, 0.0, MAX_BLOOD);
        }

        for (BodyPartState part : v.parts.values()) {
            part.ageOpenWound();
            part.tickMedication();
            if (part.tourniquet) {
                part.tourniquetTicks++;
                part.bleeding = 0.0;
                if (part.tourniquetTicks > 20 * 60 * 4 && part.tourniquetTicks % 80 == 0) {
                    part.condition = clamp(part.condition - 0.22, 0.0, 100.0);
                    part.pain = clamp(part.pain + 0.7, 0.0, 100.0);
                }
            }
            if (part.antibioticsTicks > 0 && part.infected) {
                if (part.antibioticsTicks % 40 == 0) {
                    part.pain = clamp(part.pain - 1.4, 0.0, 100.0);
                }
                if (part.antibioticsTicks % 100 == 0 && ThreadLocalRandom.current().nextDouble() < 0.34) {
                    part.infected = false;
                }
            }
            if (part.openWound && !part.infected && part.openWoundTicks > INFECTION_START_TICKS) {
                double chance = part.burn > 0.0 ? 0.010 : 0.0035;
                if (part.woundCleaned) {
                    chance *= 0.22;
                }
                if (part.bandaged) {
                    chance *= 0.55;
                }
                if (ThreadLocalRandom.current().nextDouble() < chance) {
                    part.infected = true;
                    part.pain = clamp(part.pain + 10.0, 0.0, 100.0);
                }
            }
            double painRecovery = part.openWound ? 0.002 : 0.010;
            if (part.bandaged || part.fractureStabilized) {
                painRecovery += 0.006;
            }
            if (part.burnTreated) {
                painRecovery += 0.004;
                part.burn = clamp(part.burn - 0.004, 0.0, 100.0);
            }
            part.pain = clamp(part.pain - painRecovery, 0.0, 100.0);
            if (!part.tourniquet) {
                part.bleeding = clamp(part.bleeding - (part.bandaged ? 0.018 : 0.0018), 0.0, 100.0);
            }
        }
    }

    private void applySymptoms(Player player, Vitals v, boolean moving) {
        BodyPartState head = v.zone(BodyZone.HEAD);
        BodyPartState torso = v.zone(BodyZone.TORSO);
        BodyPartState leftArm = v.zone(BodyZone.LEFT_ARM);
        BodyPartState rightArm = v.zone(BodyZone.RIGHT_ARM);
        BodyPartState leftLeg = v.zone(BodyZone.LEFT_LEG);
        BodyPartState rightLeg = v.zone(BodyZone.RIGHT_LEG);

        double pain = v.totalPain();
        if (head.condition < 65.0 || head.pain > 35.0) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, 70, head.condition < 35.0 ? 1 : 0, true, false, false));
        }
        if ((head.condition < 35.0 || pain > 78.0 || v.blood < 42.0) && v.ticks % 80 < 18) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 28, 0, true, false, false));
        }
        if (head.condition < 28.0 && v.unconsciousTicks <= 0 && ThreadLocalRandom.current().nextDouble() < 0.006) {
            knockdown(player, v, 90, "Контузія");
        }

        if (torso.condition < 65.0 || torso.bleeding > 0.3) {
            v.breathDebt = clamp(v.breathDebt + 0.05, 0.0, 100.0);
            v.stamina = clamp(v.stamina - 0.035, 0.0, MAX_STAMINA);
        }

        if (leftArm.condition < 65.0 || rightArm.condition < 65.0) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 50, armsAmplifier(leftArm, rightArm), true, false, false));
            player.addPotionEffect(new PotionEffect(PotionEffectType.MINING_FATIGUE, 50, armsAmplifier(leftArm, rightArm), true, false, false));
        }
        maybeDropItemFromBrokenArm(player, leftArm, EquipmentSlot.OFF_HAND);
        maybeDropItemFromBrokenArm(player, rightArm, EquipmentSlot.HAND);

        int slowAmplifier = legSlowAmplifier(leftLeg, rightLeg);
        if (slowAmplifier >= 0) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 50, slowAmplifier, true, false, false));
        }
        if (leftLeg.isBroken() || rightLeg.isBroken()) {
            player.setSprinting(false);
        }

        if (v.hasInfection()) {
            int poisonWindow = v.hasAntibiotics() ? 16 : 45;
            if (v.ticks % 100 < poisonWindow) {
                player.addPotionEffect(new PotionEffect(PotionEffectType.POISON, 60, 0, true, false, false));
            }
            if (v.ticks % 120 == 0) {
                damagePlayerSilently(player, v.hasAntibiotics() ? 0.12 : 0.35);
            }
        }

        if (pain > 65.0 && moving && v.ticks % 55 == 0) {
            player.playSound(player.getLocation(), Sound.ENTITY_GENERIC_HURT, 0.45f, 0.55f);
        }
        if ((pain > 92.0 || v.blood < 25.0) && v.unconsciousTicks <= 0) {
            knockdown(player, v, 120, "Потеря сознания");
        }
        if (v.unconsciousTicks > 0) {
            v.unconsciousTicks--;
            player.setSprinting(false);
            player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 45, 1, true, false, false));
            player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 45, 8, true, false, false));
            player.setVelocity(player.getVelocity().multiply(0.2));
        }

        float walkSpeed = computeWalkSpeed(v);
        if (Math.abs(player.getWalkSpeed() - walkSpeed) > 0.002f) {
            player.setWalkSpeed(walkSpeed);
        }
    }

    private DamageProfile profileFor(EntityDamageEvent event, Player victim) {
        EntityDamageEvent.DamageCause cause = event.getCause();
        if (cause == EntityDamageEvent.DamageCause.FALL) {
            return DamageProfile.FALL;
        }
        if (cause == EntityDamageEvent.DamageCause.FIRE || cause == EntityDamageEvent.DamageCause.FIRE_TICK
                || cause == EntityDamageEvent.DamageCause.LAVA || cause == EntityDamageEvent.DamageCause.HOT_FLOOR) {
            return DamageProfile.BURN;
        }
        if (cause == EntityDamageEvent.DamageCause.PROJECTILE) {
            return DamageProfile.PROJECTILE;
        }
        if (event instanceof EntityDamageByEntityEvent byEntity) {
            Entity damager = byEntity.getDamager();
            if (damager instanceof Projectile) {
                return DamageProfile.PROJECTILE;
            }
            if (damager instanceof Player attacker) {
                Material weapon = attacker.getInventory().getItemInMainHand().getType();
                String name = weapon.name();
                if (name.contains("SWORD") || name.contains("AXE") || name.contains("TRIDENT")) {
                    return DamageProfile.SHARP;
                }
                if (name.contains("MACE")) {
                    return DamageProfile.BLUNT;
                }
            }
            return DamageProfile.BLUNT;
        }
        if (cause == EntityDamageEvent.DamageCause.CONTACT
                || cause == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION
                || cause == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION) {
            return DamageProfile.BLUNT;
        }
        return DamageProfile.BLUNT;
    }

    private BodyZone bodyZoneFor(CombatBodyZone zone) {
        if (zone == null) {
            return weightedRandomZone(9, 38, 16, 16, 10, 11);
        }
        return switch (zone) {
            case HEAD -> BodyZone.HEAD;
            case TORSO -> BodyZone.TORSO;
            case LEFT_ARM -> BodyZone.LEFT_ARM;
            case RIGHT_ARM -> BodyZone.RIGHT_ARM;
            case LEFT_LEG -> BodyZone.LEFT_LEG;
            case RIGHT_LEG -> BodyZone.RIGHT_LEG;
        };
    }

    private DamageProfile damageProfileFor(CombatDamageProfile profile) {
        if (profile == null) {
            return DamageProfile.BLUNT;
        }
        return switch (profile) {
            case SHARP -> DamageProfile.SHARP;
            case PROJECTILE -> DamageProfile.PROJECTILE;
            case BLUNT -> DamageProfile.BLUNT;
        };
    }

    private double zoneParticleY(BodyZone zone) {
        return switch (zone) {
            case HEAD -> 1.55;
            case TORSO -> 1.08;
            case LEFT_ARM, RIGHT_ARM -> 1.05;
            case LEFT_LEG, RIGHT_LEG -> 0.45;
        };
    }

    private BodyZone chooseZone(EntityDamageEvent event, Player player, DamageProfile profile) {
        if (profile == DamageProfile.FALL) {
            return ThreadLocalRandom.current().nextBoolean() ? BodyZone.LEFT_LEG : BodyZone.RIGHT_LEG;
        }
        if (profile == DamageProfile.BURN) {
            return weightedRandomZone(8, 26, 14, 14, 19, 19);
        }
        if (event instanceof EntityDamageByEntityEvent byEntity) {
            Entity damager = byEntity.getDamager();
            if (damager instanceof Projectile projectile) {
                double relY = projectile.getLocation().getY() - player.getLocation().getY();
                if (relY > 1.45) return BodyZone.HEAD;
                if (relY < 0.80) return ThreadLocalRandom.current().nextBoolean() ? BodyZone.LEFT_LEG : BodyZone.RIGHT_LEG;
                if (relY > 1.05) return BodyZone.TORSO;
                return weightedRandomZone(10, 42, 16, 16, 8, 8);
            }
        }
        return weightedRandomZone(9, 38, 16, 16, 10, 11);
    }

    private void applyInjury(Player player, Vitals v, BodyZone zone, double damage, DamageProfile profile) {
        BodyPartState part = v.zone(zone);
        double severity = damage * profile.conditionDamage;
        if (profile == DamageProfile.FALL && damage > 6.0) {
            severity *= 1.25;
        }
        part.condition = clamp(part.condition - severity, 0.0, 100.0);
        part.pain = clamp(part.pain + damage * profile.pain, 0.0, 100.0);
        part.bleeding = clamp(part.bleeding + damage * profile.bleeding, 0.0, 100.0);
        part.burn = clamp(part.burn + damage * profile.burn, 0.0, 100.0);
        if (profile == DamageProfile.SHARP || profile == DamageProfile.PROJECTILE || profile == DamageProfile.BURN) {
            part.openWound = true;
            part.bandaged = false;
            part.woundCleaned = false;
            if (profile == DamageProfile.BURN) {
                part.burnTreated = false;
            }
        }
        part.injury = profile.label;
        part.lastCause = profile.id;
        if (profile == DamageProfile.PROJECTILE && ThreadLocalRandom.current().nextDouble() < 0.72) {
            part.embeddedArrow = true;
            part.openWound = true;
            part.bandaged = false;
            part.bleeding = clamp(part.bleeding + 5.0, 0.0, 100.0);
        }
        if ((profile == DamageProfile.FALL && damage > 5.5 && ThreadLocalRandom.current().nextDouble() < fractureChance(damage))
                || (profile == DamageProfile.BLUNT && damage > 7.0 && ThreadLocalRandom.current().nextDouble() < 0.20)) {
            part.fracture = true;
            part.fractureStabilized = false;
            part.injury = "Перелом";
            part.pain = clamp(part.pain + 20.0, 0.0, 100.0);
        }
        if (zone == BodyZone.HEAD && damage > 7.0 && ThreadLocalRandom.current().nextDouble() < 0.24) {
            knockdown(player, v, 70, "Критический удар в голову");
        }
        if (zone == BodyZone.TORSO && part.bleeding > 10.0) {
            v.breathDebt = clamp(v.breathDebt + damage * 2.2, 0.0, 100.0);
        }
    }

    private double fractureChance(double damage) {
        return clamp((damage - 5.0) / 11.0, 0.15, 0.82);
    }

    private JsonArray bodyParts(Vitals v) {
        JsonArray parts = new JsonArray();
        for (BodyZone zone : BodyZone.values()) {
            BodyPartState state = v.zone(zone);
            JsonObject part = new JsonObject();
            part.addProperty("id", zone.id);
            part.addProperty("label", zone.label);
            part.addProperty("condition", round(state.condition));
            part.addProperty("state", partState(state));
            part.addProperty("injury", state.injury);
            part.addProperty("lastCause", state.lastCause);
            part.addProperty("bleeding", round(state.bleeding));
            part.addProperty("pain", round(state.pain));
            part.addProperty("fracture", state.fracture);
            part.addProperty("burn", round(state.burn));
            part.addProperty("infection", state.infected);
            part.addProperty("embeddedArrow", state.embeddedArrow);
            part.addProperty("openWound", state.openWound);
            part.addProperty("bandaged", state.bandaged);
            part.addProperty("woundCleaned", state.woundCleaned);
            part.addProperty("fractureStabilized", state.fractureStabilized);
            part.addProperty("tourniquet", state.tourniquet);
            part.addProperty("medicated", state.medicatedTicks > 0);
            part.addProperty("antibiotics", state.antibioticsTicks > 0);
            part.addProperty("burnTreated", state.burnTreated);
            part.add("actions", treatmentActions(zone, state));
            parts.add(part);
        }
        return parts;
    }

    private JsonArray treatmentActions(BodyZone zone, BodyPartState part) {
        JsonArray actions = new JsonArray();
        for (TreatmentAction action : TreatmentAction.values()) {
            String reason = action.unavailableReason(zone, part);
            JsonObject json = action.toJson();
            json.addProperty("enabled", reason == null);
            if (reason != null) {
                json.addProperty("reason", reason);
            }
            actions.add(json);
        }
        return actions;
    }

    private String partState(BodyPartState state) {
        if (state.infected) return "Инфекция";
        if (state.fracture) return "Перелом";
        if (state.burn > 25.0) return "Ожог";
        if (state.bleeding > 10.0) return "Кровотечение";
        if (state.condition < 35.0) return "Важка травма";
        if (state.condition < 65.0) return "Травма";
        if (state.condition < 88.0) return "Легке пошкодження";
        return "Нормально";
    }

    private void maybeDropItemFromBrokenArm(Player player, BodyPartState arm, EquipmentSlot slot) {
        double chance = arm.fractureStabilized ? 0.0008 : 0.003;
        if (!arm.isBroken() || ThreadLocalRandom.current().nextDouble() > chance) {
            return;
        }
        ItemStack item = slot == EquipmentSlot.HAND
                ? player.getInventory().getItemInMainHand()
                : player.getInventory().getItemInOffHand();
        if (item == null || item.getType().isAir()) {
            return;
        }
        ItemStack dropped = item.clone();
        if (slot == EquipmentSlot.HAND) {
            player.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
        } else {
            player.getInventory().setItemInOffHand(new ItemStack(Material.AIR));
        }
        player.getWorld().dropItemNaturally(player.getLocation(), dropped);
        player.sendActionBar("Травмированная рука не удержала предмет");
    }

    private int armsAmplifier(BodyPartState leftArm, BodyPartState rightArm) {
        double worst = Math.min(leftArm.condition, rightArm.condition);
        if (leftArm.isBroken() || rightArm.isBroken() || worst < 35.0) return 1;
        return 0;
    }

    private int legSlowAmplifier(BodyPartState leftLeg, BodyPartState rightLeg) {
        double worst = Math.min(leftLeg.condition, rightLeg.condition);
        if (leftLeg.isBroken() || rightLeg.isBroken()) {
            return (leftLeg.fractureStabilized || rightLeg.fractureStabilized) ? 1 : 3;
        }
        if (worst < 35.0) return 2;
        if (worst < 65.0) return 1;
        return -1;
    }

    private float computeWalkSpeed(Vitals v) {
        double legPenalty = 0.0;
        BodyPartState left = v.zone(BodyZone.LEFT_LEG);
        BodyPartState right = v.zone(BodyZone.RIGHT_LEG);
        if (left.isBroken() || right.isBroken()) {
            legPenalty = (left.fractureStabilized || right.fractureStabilized) ? 0.045 : 0.090;
        } else if (Math.min(left.condition, right.condition) < 65.0) {
            legPenalty = 0.035;
        }
        float staminaSpeed = v.stamina < 5.0 ? 0.135f : v.stamina < 25.0 ? 0.165f : 0.2f;
        return (float) clamp(staminaSpeed - legPenalty, 0.075, 0.2);
    }

    private BodyPartState worstBleedingPart(Vitals v) {
        BodyPartState target = null;
        for (BodyPartState part : v.parts.values()) {
            if (target == null || part.bleeding > target.bleeding) {
                target = part;
            }
        }
        return target;
    }

    private BodyZone zoneForPart(Vitals v, BodyPartState target) {
        for (Map.Entry<BodyZone, BodyPartState> entry : v.parts.entrySet()) {
            if (entry.getValue() == target) {
                return entry.getKey();
            }
        }
        return null;
    }

    private JsonObject treatmentJson(Player player) {
        PendingTreatment pending = treatments.get(player.getUniqueId());
        return pending == null ? new JsonObject() : pending.toJson();
    }

    private boolean hasTreatmentItem(Player player, TreatmentAction action) {
        if (action.materials.length == 0) {
            return true;
        }
        if (action.toolOnly) {
            return findInventorySlot(player, action.materials) >= 0;
        }
        return findInventorySlot(player, action.materials) >= 0;
    }

    private boolean consumeTreatmentItem(Player player, TreatmentAction action) {
        if (action.materials.length == 0) {
            return true;
        }
        int slot = findInventorySlot(player, action.materials);
        if (slot < 0) {
            return false;
        }
        if (action.toolOnly) {
            return true;
        }
        ItemStack stack = player.getInventory().getItem(slot);
        if (stack == null || stack.getType().isAir()) {
            return false;
        }
        if (stack.getAmount() <= 1) {
            player.getInventory().setItem(slot, new ItemStack(Material.AIR));
        } else {
            stack.setAmount(stack.getAmount() - 1);
        }
        return true;
    }

    private int findInventorySlot(Player player, Material[] materials) {
        for (int i = 0; i < player.getInventory().getSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack == null || stack.getType().isAir()) {
                continue;
            }
            for (Material material : materials) {
                if (stack.getType() == material) {
                    return i;
                }
            }
        }
        return -1;
    }

    private void damagePlayerSilently(Player player, double amount) {
        if (amount <= 0.0 || player.isDead()) {
            return;
        }
        player.damage(amount);
    }

    private void knockdown(Player player, Vitals v, int ticks, String reason) {
        v.unconsciousTicks = Math.max(v.unconsciousTicks, ticks);
        player.setSprinting(false);
        player.setVelocity(player.getVelocity().multiply(0.15).setY(-0.08));
        player.sendTitle("", reason, 4, 32, 8);
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_HURT, 0.85f, 0.55f);
    }

    private BodyZone weightedRandomZone(int head, int torso, int leftArm, int rightArm, int leftLeg, int rightLeg) {
        int total = head + torso + leftArm + rightArm + leftLeg + rightLeg;
        int roll = ThreadLocalRandom.current().nextInt(total);
        if ((roll -= head) < 0) return BodyZone.HEAD;
        if ((roll -= torso) < 0) return BodyZone.TORSO;
        if ((roll -= leftArm) < 0) return BodyZone.LEFT_ARM;
        if ((roll -= rightArm) < 0) return BodyZone.RIGHT_ARM;
        if ((roll -= leftLeg) < 0) return BodyZone.LEFT_LEG;
        return BodyZone.RIGHT_LEG;
    }

    private int countArmorPieces(Player player) {
        int pieces = 0;
        for (ItemStack stack : player.getInventory().getArmorContents()) {
            if (stack != null && !stack.getType().isAir()) {
                pieces++;
            }
        }
        return pieces;
    }

    private String staminaBand(double stamina) {
        if (stamina >= 80.0) return "steady";
        if (stamina >= 50.0) return "warmed";
        if (stamina >= 25.0) return "tired";
        if (stamina >= 5.0) return "strained";
        return "exhausted";
    }

    private String staminaLabel(double stamina) {
        return switch (staminaBand(stamina)) {
            case "warmed" -> "Розігрів";
            case "tired" -> "Усталость";
            case "strained" -> "На межі";
            case "exhausted" -> "Виснаження";
            default -> "Стабільно";
        };
    }

    private double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private void markDirty() {
        dirty = true;
    }

    private void loadFromDisk() {
        if (!storageFile.isFile()) {
            return;
        }
        try (FileReader reader = new FileReader(storageFile, java.nio.charset.StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            JsonObject players = root.has("players") && root.get("players").isJsonObject()
                    ? root.getAsJsonObject("players")
                    : new JsonObject();
            int loaded = 0;
            for (Map.Entry<String, JsonElement> entry : players.entrySet()) {
                try {
                    UUID uuid = UUID.fromString(entry.getKey());
                    if (entry.getValue().isJsonObject()) {
                        vitals.put(uuid, vitalsFromJson(entry.getValue().getAsJsonObject()));
                        loaded++;
                    }
                } catch (IllegalArgumentException ignored) {
                    plugin.getLogger().warning("Skipping invalid vitals UUID: " + entry.getKey());
                }
            }
            plugin.getLogger().info("Loaded vitals for " + loaded + " player(s).");
            dirty = false;
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to load vitals storage: " + storageFile.getAbsolutePath(), e);
        }
    }

    private void saveToDisk(boolean force) {
        if (!force && !dirty) {
            return;
        }
        try {
            if (!plugin.getDataFolder().exists()) {
                plugin.getDataFolder().mkdirs();
            }
            JsonObject root = new JsonObject();
            root.addProperty("version", STORAGE_VERSION);
            root.addProperty("savedAt", System.currentTimeMillis());
            JsonObject players = new JsonObject();
            for (Map.Entry<UUID, Vitals> entry : vitals.entrySet()) {
                players.add(entry.getKey().toString(), vitalsToJson(entry.getValue()));
            }
            root.add("players", players);

            File tempFile = new File(storageFile.getParentFile(), storageFile.getName() + ".tmp");
            try (FileWriter writer = new FileWriter(tempFile, java.nio.charset.StandardCharsets.UTF_8)) {
                gson.toJson(root, writer);
            }
            try {
                Files.move(tempFile.toPath(), storageFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicMoveError) {
                Files.move(tempFile.toPath(), storageFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            dirty = false;
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to save vitals storage: " + storageFile.getAbsolutePath(), e);
        }
    }

    private JsonObject vitalsToJson(Vitals v) {
        JsonObject json = new JsonObject();
        json.addProperty("stamina", v.stamina);
        json.addProperty("breathDebt", v.breathDebt);
        json.addProperty("fatigue", v.fatigue);
        json.addProperty("blood", v.blood);
        json.addProperty("lastDamage", v.lastDamage);
        json.addProperty("lastDamageCause", v.lastDamageCause);
        json.addProperty("ticks", v.ticks);
        json.addProperty("unconsciousTicks", v.unconsciousTicks);
        JsonObject partsJson = new JsonObject();
        for (Map.Entry<BodyZone, BodyPartState> entry : v.parts.entrySet()) {
            partsJson.add(entry.getKey().id, partToJson(entry.getValue()));
        }
        json.add("parts", partsJson);
        return json;
    }

    private Vitals vitalsFromJson(JsonObject json) {
        Vitals v = new Vitals();
        v.stamina = clamp(readDouble(json, "stamina", MAX_STAMINA), 0.0, MAX_STAMINA);
        v.breathDebt = clamp(readDouble(json, "breathDebt", 0.0), 0.0, 100.0);
        v.fatigue = clamp(readDouble(json, "fatigue", 0.0), 0.0, 100.0);
        v.blood = clamp(readDouble(json, "blood", MAX_BLOOD), 0.0, MAX_BLOOD);
        v.lastDamage = Math.max(0.0, readDouble(json, "lastDamage", 0.0));
        v.lastDamageCause = readString(json, "lastDamageCause", "");
        v.ticks = Math.max(0, readInt(json, "ticks", 0));
        v.unconsciousTicks = Math.max(0, readInt(json, "unconsciousTicks", 0));

        JsonObject partsJson = json.has("parts") && json.get("parts").isJsonObject()
                ? json.getAsJsonObject("parts")
                : new JsonObject();
        for (BodyZone zone : BodyZone.values()) {
            JsonElement element = partsJson.get(zone.id);
            if (element != null && element.isJsonObject()) {
                v.parts.put(zone, partFromJson(element.getAsJsonObject()));
            }
        }
        return v;
    }

    private JsonObject partToJson(BodyPartState part) {
        JsonObject json = new JsonObject();
        json.addProperty("condition", part.condition);
        json.addProperty("bleeding", part.bleeding);
        json.addProperty("pain", part.pain);
        json.addProperty("burn", part.burn);
        json.addProperty("fracture", part.fracture);
        json.addProperty("infected", part.infected);
        json.addProperty("openWound", part.openWound);
        json.addProperty("bandaged", part.bandaged);
        json.addProperty("woundCleaned", part.woundCleaned);
        json.addProperty("fractureStabilized", part.fractureStabilized);
        json.addProperty("tourniquet", part.tourniquet);
        json.addProperty("burnTreated", part.burnTreated);
        json.addProperty("embeddedArrow", part.embeddedArrow);
        json.addProperty("openWoundTicks", part.openWoundTicks);
        json.addProperty("tourniquetTicks", part.tourniquetTicks);
        json.addProperty("medicatedTicks", part.medicatedTicks);
        json.addProperty("antibioticsTicks", part.antibioticsTicks);
        json.addProperty("injury", part.injury);
        json.addProperty("lastCause", part.lastCause);
        return json;
    }

    private BodyPartState partFromJson(JsonObject json) {
        BodyPartState part = new BodyPartState();
        part.condition = clamp(readDouble(json, "condition", 100.0), 0.0, 100.0);
        part.bleeding = clamp(readDouble(json, "bleeding", 0.0), 0.0, 100.0);
        part.pain = clamp(readDouble(json, "pain", 0.0), 0.0, 100.0);
        part.burn = clamp(readDouble(json, "burn", 0.0), 0.0, 100.0);
        part.fracture = readBoolean(json, "fracture", false);
        part.infected = readBoolean(json, "infected", readBoolean(json, "infection", false));
        part.openWound = readBoolean(json, "openWound", false);
        part.bandaged = readBoolean(json, "bandaged", false);
        part.woundCleaned = readBoolean(json, "woundCleaned", false);
        part.fractureStabilized = readBoolean(json, "fractureStabilized", false);
        part.tourniquet = readBoolean(json, "tourniquet", false);
        part.burnTreated = readBoolean(json, "burnTreated", false);
        part.embeddedArrow = readBoolean(json, "embeddedArrow", false);
        part.openWoundTicks = Math.max(0, readInt(json, "openWoundTicks", 0));
        part.tourniquetTicks = Math.max(0, readInt(json, "tourniquetTicks", 0));
        part.medicatedTicks = Math.max(0, readInt(json, "medicatedTicks", 0));
        part.antibioticsTicks = Math.max(0, readInt(json, "antibioticsTicks", 0));
        part.injury = readString(json, "injury", "Немає");
        part.lastCause = readString(json, "lastCause", "");
        return part;
    }

    private static double readDouble(JsonObject json, String key, double fallback) {
        try {
            return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsDouble() : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static int readInt(JsonObject json, String key, int fallback) {
        try {
            return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsInt() : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static boolean readBoolean(JsonObject json, String key, boolean fallback) {
        try {
            return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsBoolean() : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static String readString(JsonObject json, String key, String fallback) {
        try {
            return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsString() : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    public static final class Vitals {
        private double stamina = MAX_STAMINA;
        private double breathDebt = 0.0;
        private double fatigue = 0.0;
        private double blood = MAX_BLOOD;
        private double lastDamage = 0.0;
        private String lastDamageCause = "";
        private int ticks = 0;
        private int unconsciousTicks = 0;
        private transient UUID lastMoveWorld;
        private transient double lastMoveX;
        private transient double lastMoveY;
        private transient double lastMoveZ;
        private final EnumMap<BodyZone, BodyPartState> parts = new EnumMap<>(BodyZone.class);

        private Vitals() {
            for (BodyZone zone : BodyZone.values()) {
                parts.put(zone, new BodyPartState());
            }
        }

        private BodyPartState zone(BodyZone zone) {
            return parts.get(zone);
        }

        private double totalBleeding() {
            return parts.values().stream().mapToDouble(part -> part.bleeding).sum();
        }

        private double totalPain() {
            return clamp(parts.values().stream().mapToDouble(BodyPartState::effectivePain).sum() * 0.42, 0.0, 100.0);
        }

        private void addPain(double amount) {
            zone(BodyZone.LEFT_LEG).pain = clamp(zone(BodyZone.LEFT_LEG).pain + amount, 0.0, 100.0);
            zone(BodyZone.RIGHT_LEG).pain = clamp(zone(BodyZone.RIGHT_LEG).pain + amount, 0.0, 100.0);
        }

        private boolean hasInfection() {
            return parts.values().stream().anyMatch(part -> part.infected);
        }

        private boolean hasAntibiotics() {
            return parts.values().stream().anyMatch(part -> part.antibioticsTicks > 0);
        }

        private double clamp(double value, double min, double max) {
            return Math.max(min, Math.min(max, value));
        }
    }

    private static final class BodyPartState {
        private double condition = 100.0;
        private double bleeding = 0.0;
        private double pain = 0.0;
        private double burn = 0.0;
        private boolean fracture = false;
        private boolean infected = false;
        private boolean openWound = false;
        private boolean bandaged = false;
        private boolean woundCleaned = false;
        private boolean fractureStabilized = false;
        private boolean tourniquet = false;
        private boolean burnTreated = false;
        private boolean embeddedArrow = false;
        private int openWoundTicks = 0;
        private int tourniquetTicks = 0;
        private int medicatedTicks = 0;
        private int antibioticsTicks = 0;
        private String injury = "Немає";
        private String lastCause = "";

        private boolean isBroken() {
            return fracture || condition < 22.0;
        }

        private void ageOpenWound() {
            if (openWound) {
                openWoundTicks++;
            }
        }

        private void tickMedication() {
            medicatedTicks = Math.max(0, medicatedTicks - 1);
            antibioticsTicks = Math.max(0, antibioticsTicks - 1);
        }

        private double effectivePain() {
            double value = pain;
            if (medicatedTicks > 0) {
                value *= 0.45;
            }
            if (fractureStabilized) {
                value *= 0.84;
            }
            return value;
        }
    }

    private enum BodyZone {
        HEAD("head", "Голова"),
        TORSO("chest", "Торс"),
        LEFT_ARM("leftArm", "Левая рука"),
        RIGHT_ARM("rightArm", "Правая рука"),
        LEFT_LEG("leftLeg", "Левая нога"),
        RIGHT_LEG("rightLeg", "Правая нога");

        private final String id;
        private final String label;

        BodyZone(String id, String label) {
            this.id = id;
            this.label = label;
        }

        private static BodyZone byId(String id) {
            for (BodyZone zone : values()) {
                if (zone.id.equalsIgnoreCase(id)) {
                    return zone;
                }
            }
            return null;
        }
    }

    private enum TreatmentAction {
        BANDAGE("bandage", "Перевязка", "Рана перевязана", "бумага или белая шерсть",
                "Останавливает кровотечение и закрывает открытую рану.", 80, false,
                new Material[]{Material.PAPER, Material.WHITE_WOOL}),
        CLEAN_WOUND("clean_wound", "Обработка раны", "Рана обработана", "мед, зелье или стеклянная бутылка",
                "Снижает риск инфекции и немного уменьшает боль.", 90, false,
                new Material[]{Material.HONEY_BOTTLE, Material.POTION, Material.GLASS_BOTTLE}),
        SPLINT("splint", "Наложение шины", "Шина наложена", "палка или бамбук",
                "Стабилизирует перелом и ослабляет штрафы движения.", 150, false,
                new Material[]{Material.STICK, Material.BAMBOO}),
        EXTRACT_ARROW("extract_arrow", "Извлечение стрелы", "Стрела извлечена", "ножницы",
                "Извлекает стрелу, но усиливает боль и открывает рану.", 120, true,
                new Material[]{Material.SHEARS}),
        TOURNIQUET("tourniquet", "Наложение жгута", "Жгут наложен", "нитка или поводок",
                "Экстренно останавливает сильное кровотечение конечности, но опасен при долгом ношении.", 55, false,
                new Material[]{Material.STRING, Material.LEAD}),
        RELEASE_TOURNIQUET("release_tourniquet", "Снятие жгута", "Жгут снят", "не требуется",
                "Снимает давление с конечности. Если рана не перевязана, кровь может пойти снова.", 45, false,
                new Material[]{}),
        PAINKILLER("painkiller", "Обезболивание", "Боль приглушена", "сахар",
                "Временно приглушает боль, но не лечит саму травму.", 60, false,
                new Material[]{Material.SUGAR}),
        ANTIBIOTIC("antibiotic", "Антибиотик", "Курс антибиотика начат", "ферментированный паучий глаз",
                "Запускает курс лечения инфекции и ослабляет отравление.", 70, false,
                new Material[]{Material.FERMENTED_SPIDER_EYE}),
        TREAT_BURN("treat_burn", "Обработка ожога", "Ожог обработан", "снежок",
                "Уменьшает ожог, боль и риск дальнейших осложнений.", 70, false,
                new Material[]{Material.SNOWBALL});

        private final String id;
        private final String label;
        private final String doneLabel;
        private final String itemLabel;
        private final String description;
        private final int durationTicks;
        private final boolean toolOnly;
        private final Material[] materials;

        TreatmentAction(String id, String label, String doneLabel, String itemLabel, String description, int durationTicks,
                        boolean toolOnly, Material[] materials) {
            this.id = id;
            this.label = label;
            this.doneLabel = doneLabel;
            this.itemLabel = itemLabel;
            this.description = description;
            this.durationTicks = durationTicks;
            this.toolOnly = toolOnly;
            this.materials = materials;
        }

        private static TreatmentAction byId(String id) {
            for (TreatmentAction action : values()) {
                if (action.id.equalsIgnoreCase(id)) {
                    return action;
                }
            }
            return null;
        }

        private JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("id", id);
            json.addProperty("label", label);
            json.addProperty("item", itemLabel);
            json.addProperty("duration", durationLabel());
            json.addProperty("durationTicks", durationTicks);
            json.addProperty("description", description);
            json.addProperty("toolOnly", toolOnly);
            return json;
        }

        private String durationLabel() {
            double seconds = durationTicks / 20.0;
            if (Math.abs(seconds - Math.rint(seconds)) < 0.01) {
                return (int) Math.rint(seconds) + "с";
            }
            return Math.round(seconds * 10.0) / 10.0 + "с";
        }

        private String unavailableReason(BodyZone zone, BodyPartState part) {
            return switch (this) {
                case BANDAGE -> (part.bleeding > 0.1 || part.openWound) ? null : "Рана не требует перевязки.";
                case CLEAN_WOUND -> (part.openWound || part.burn > 0.0 || part.infected) && !part.woundCleaned
                        ? null : "Рана не требует обработки.";
                case SPLINT -> isLimb(zone) && (part.fracture || part.condition < 22.0) && !part.fractureStabilized
                        ? null : "Нестабильного перелома конечности нет.";
                case EXTRACT_ARROW -> part.embeddedArrow ? null : "Стрелы в теле нет.";
                case TOURNIQUET -> isLimb(zone) && part.bleeding > 6.0 && !part.tourniquet
                        ? null : "Кровотечение конечности не требует жгута.";
                case RELEASE_TOURNIQUET -> part.tourniquet ? null : "Жгута нет.";
                case PAINKILLER -> part.pain > 12.0 && part.medicatedTicks <= 0 ? null : "Обезболивание не требуется.";
                case ANTIBIOTIC -> part.infected && part.antibioticsTicks <= 0 ? null : "Антибиотики не требуются.";
                case TREAT_BURN -> part.burn > 8.0 && !part.burnTreated ? null : "Необработанного ожога нет.";
            };
        }

        private boolean isLimb(BodyZone zone) {
            return zone == BodyZone.LEFT_ARM || zone == BodyZone.RIGHT_ARM
                    || zone == BodyZone.LEFT_LEG || zone == BodyZone.RIGHT_LEG;
        }
    }

    private static final class PendingTreatment {
        private final BodyZone zone;
        private final TreatmentAction action;
        private final int totalTicks;
        private final Location startedAt;
        private int remainingTicks;

        private PendingTreatment(BodyZone zone, TreatmentAction action, int totalTicks, Location startedAt) {
            this.zone = zone;
            this.action = action;
            this.totalTicks = totalTicks;
            this.remainingTicks = totalTicks;
            this.startedAt = startedAt.clone();
        }

        private boolean movedTooFar(Location current) {
            if (current == null || current.getWorld() == null || startedAt.getWorld() == null
                    || !current.getWorld().equals(startedAt.getWorld())) {
                return true;
            }
            return current.distanceSquared(startedAt) > 1.15;
        }

        private double progress() {
            return totalTicks <= 0 ? 1.0 : Math.max(0.0, Math.min(1.0, 1.0 - remainingTicks / (double) totalTicks));
        }

        private JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("active", true);
            json.addProperty("partId", zone.id);
            json.addProperty("partLabel", zone.label);
            json.addProperty("action", action.id);
            json.addProperty("label", action.label);
            json.addProperty("progress", Math.round(progress() * 1000.0) / 10.0);
            json.addProperty("remainingTicks", remainingTicks);
            json.addProperty("totalTicks", totalTicks);
            return json;
        }
    }

    private enum DamageProfile {
        FALL("fall", "Падение", 7.8, 0.0, 7.0, 0.0, 1.3, 0.7),
        SHARP("sharp", "Резаная рана", 6.2, 4.8, 4.2, 0.0, 0.9, 0.7),
        PROJECTILE("projectile", "Прокол", 5.8, 3.8, 4.6, 0.0, 1.0, 0.7),
        BLUNT("blunt", "Ушиб", 5.5, 0.0, 6.0, 0.0, 0.9, 0.8),
        BURN("burn", "Ожог", 4.2, 0.4, 6.6, 7.2, 1.3, 1.1);

        private final String id;
        private final String label;
        private final double conditionDamage;
        private final double bleeding;
        private final double pain;
        private final double burn;
        private final double breathDebt;
        private final double fatigue;

        DamageProfile(String id, String label, double conditionDamage, double bleeding, double pain,
                      double burn, double breathDebt, double fatigue) {
            this.id = id;
            this.label = label;
            this.conditionDamage = conditionDamage;
            this.bleeding = bleeding;
            this.pain = pain;
            this.burn = burn;
            this.breathDebt = breathDebt;
            this.fatigue = fatigue;
        }
    }
}
