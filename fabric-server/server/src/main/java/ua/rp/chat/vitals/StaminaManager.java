package ua.rp.chat.vitals;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import java.util.List;
import java.util.ArrayList;
import java.util.logging.Level;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import ua.rp.chat.RPChat;
import ua.rp.chat.blood.BloodVolumeRules;
import ua.rp.chat.client.blood.BloodFxPayload;
import ua.rp.chat.projectile.ArrowImpactPhysics;
import ua.rp.chat.projectile.ArrowImpactRuntime;
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


public class StaminaManager {
    private static final double MAX_STAMINA = 100.0;
    private static final double MAX_BLOOD = 100.0;
    private static final int INFECTION_START_TICKS = 20 * 60 * 5;
    private static final int SAVE_INTERVAL_TICKS = 20 * 30;
    private static final int STORAGE_VERSION = 1;

    private final RPChat plugin;
    private final BloodFxService bloodFx;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final File storageFile;
    private final Map<UUID, Vitals> vitals = new ConcurrentHashMap<>();
    private final Map<UUID, PendingTreatment> treatments = new ConcurrentHashMap<>();
    private final java.util.Set<UUID> internalDamage = ConcurrentHashMap.newKeySet();
    private int saveTicks = 0;
    private boolean dirty = false;

    public StaminaManager(RPChat plugin, BloodFxService bloodFx) {
        this.plugin = plugin;
        this.bloodFx = bloodFx;
        this.storageFile = new File(plugin.getDataFolder(), "vitals.json");
    }

    public void start() {
        loadFromDisk();
    }

    public void shutdown() {
        saveToDisk(true);
    }

    public Vitals getVitals(ServerPlayer player) {
        return vitals.computeIfAbsent(player.getUUID(), id -> new Vitals());
    }

    /** Supplies persistent wound emitters to a newly joined observer. */
    public void syncBloodFxTo(ServerPlayer observer) {
        if (observer == null || plugin.getServer() == null) return;
        for (ServerPlayer victim : plugin.getServer().getPlayerList().getPlayers()) {
            Vitals state = vitals.get(victim.getUUID());
            if (state == null) continue;
            for (BodyZone zone : BodyZone.values()) {
                BodyPartState part = state.zone(zone);
                if (part.bleeding <= 0.1 && !part.openWound && !part.bandaged && !part.embeddedArrow) continue;
                ensureWoundVisual(part, zone);
                float bleedingShare = (float) (part.bleeding / Math.max(1, part.wounds.size()));
                float partFlow = state.blood <= 0.0 ? 0.0f
                        : partFlowMlPerSecond(part, movementFactor(victim));
                for (WoundVisual wound : part.wounds) {
                    bloodFx.syncWoundTo(observer, victim, wound.id, zone.ordinal(), wound.face, wound.profile,
                            (float) wound.side, (float) wound.height, (float) wound.intensity,
                            bleedingShare, woundFlowShare(part, wound, partFlow),
                            remainingBloodMl(state), (float) wound.penetrationDepth,
                            wound.direction, wound.seed, bloodFxFlags(part, wound));
                }
            }
        }
    }

    public boolean isUnconscious(ServerPlayer player) {
        return player != null && getVitals(player).unconsciousTicks > 0;
    }

    public boolean consumeEscapeEffort(ServerPlayer player, double staminaCost, double fatigueGain) {
        return consumeWorkEffort(player, staminaCost, fatigueGain);
    }

    /** Единая серверная проверка затрат для физически тяжёлой работы. */
    public boolean consumeWorkEffort(ServerPlayer player, double staminaCost, double fatigueGain) {
        if (player == null || !player.isAlive()) {
            return false;
        }
        Vitals v = getVitals(player);
        if (v.unconsciousTicks > 0 || v.stamina < staminaCost) {
            return false;
        }
        v.stamina = clamp(v.stamina - staminaCost, 0.0, MAX_STAMINA);
        v.fatigue = clamp(v.fatigue + fatigueGain, 0.0, 100.0);
        v.breathDebt = clamp(v.breathDebt + staminaCost * 0.65, 0.0, 100.0);
        dirty = true;
        return true;
    }

    public double escapeStamina(ServerPlayer player) {
        return player == null ? 0.0 : getVitals(player).stamina;
    }

    public void applyEscapeBurn(ServerPlayer player, double severity) {
        if (player == null || !player.isAlive()) {
            return;
        }
        Vitals v = getVitals(player);
        double amount = clamp(severity, 0.2, 8.0);
        for (BodyZone zone : new BodyZone[]{BodyZone.LEFT_ARM, BodyZone.RIGHT_ARM}) {
            BodyPartState arm = v.zone(zone);
            arm.condition = clamp(arm.condition - amount * 0.85, 0.0, 100.0);
            arm.pain = clamp(arm.pain + amount * 2.4, 0.0, 100.0);
            arm.burn = clamp(arm.burn + amount * 3.2, 0.0, 100.0);
            arm.burnTreated = false;
        }
        v.fatigue = clamp(v.fatigue + amount * 0.9, 0.0, 100.0);
        dirty = true;
        damagePlayerSilently(player, Math.min(2.5, amount * 0.24));
    }

    public int restraintPenalty(ServerPlayer player) {
        if (player == null) {
            return 0;
        }
        Vitals v = getVitals(player);
        int penalty = 0;
        BodyPartState leftArm = v.zone(BodyZone.LEFT_ARM);
        BodyPartState rightArm = v.zone(BodyZone.RIGHT_ARM);
        BodyPartState leftLeg = v.zone(BodyZone.LEFT_LEG);
        BodyPartState rightLeg = v.zone(BodyZone.RIGHT_LEG);
        if (leftArm.isBroken()) penalty += 4;
        if (rightArm.isBroken()) penalty += 4;
        if (leftLeg.isBroken()) penalty += 3;
        if (rightLeg.isBroken()) penalty += 3;
        if (v.stamina < 25.0) penalty += 3;
        if (v.totalPain() > 55.0) penalty += 3;
        if (v.blood < 45.0) penalty += 2;
        if (v.unconsciousTicks > 0) penalty += 20;
        return penalty;
    }

    public String woundInspectionSummary(ServerPlayer player, boolean trained) {
        if (player == null) {
            return "Осмотреть некого.";
        }
        Vitals v = getVitals(player);
        java.util.List<String> lines = new java.util.ArrayList<>();
        for (BodyZone zone : BodyZone.values()) {
            BodyPartState part = v.zone(zone);
            if (part.condition >= 88.0 && part.bleeding <= 0.2 && part.pain <= 8.0 && !part.fracture && !part.openWound && !part.embeddedArrow) {
                continue;
            }
            StringBuilder line = new StringBuilder(zone.label).append(": ");
            if (part.bleeding > 10.0) {
                line.append("заметное кровотечение");
            } else if (part.openWound) {
                line.append("открытая рана");
            } else if (part.fracture || part.isBroken()) {
                line.append("кость выглядит поврежденной");
            } else if (part.condition < 65.0) {
                line.append("травма и болезненность");
            } else {
                line.append("легкое повреждение");
            }
            if (trained) {
                line.append(" (состояние ").append(Math.round(part.condition)).append("%");
                if (part.bleeding > 0.2) {
                    line.append(", кровь ").append(Math.round(part.bleeding));
                }
                if (part.pain > 8.0) {
                    line.append(", боль ").append(Math.round(part.pain));
                }
                line.append(")");
            }
            lines.add(line.toString());
        }
        if (lines.isEmpty()) {
            return "Видимых серьезных повреждений не заметно.";
        }
        if (v.unconsciousTicks > 0) {
            lines.add(0, "Человек без сознания или почти не реагирует.");
        }
        if (v.blood < 55.0) {
            lines.add("Кожа бледная, заметны признаки кровопотери.");
        }
        return String.join(" | ", lines);
    }

    public JsonObject toJson(ServerPlayer player) {
        Vitals v = getVitals(player);
        JsonObject json = new JsonObject();
        double health = Math.max(0.0, player.getHealth());
        double maxHealth = player.getAttribute(Attributes.MAX_HEALTH) != null
                ? player.getAttribute(Attributes.MAX_HEALTH).getValue()
                : 20.0;
        json.addProperty("success", true);
        json.addProperty("player", player.getGameProfile().name());
        json.addProperty("uuid", player.getUUID().toString());
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

    public int combatAttackPenalty(ServerPlayer player) {
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

    public int combatDefensePenalty(ServerPlayer player) {
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

    public void applyCombatInjury(ServerPlayer victim, CombatBodyZone combatZone, double medicalDamage,
                                  double healthDamage, CombatDamageProfile combatProfile,
                                  Vec3 incomingDirection, double hitRatio, double lateral,
                                  ArrowImpactPhysics.Result projectileImpact) {
        if (victim == null || !victim.isAlive() || medicalDamage <= 0.0) {
            return;
        }
        cancelTreatment(victim, "Лечение прервано.");
        Vitals v = getVitals(victim);
        BodyZone zone = bodyZoneFor(combatZone);
        DamageProfile profile = damageProfileFor(combatProfile);
        double scaledMedicalDamage = projectileImpact == null
                ? medicalDamage : medicalDamage * projectileImpact.damageScale();
        double scaledHealthDamage = projectileImpact == null
                ? healthDamage : healthDamage * projectileImpact.damageScale();
        applyInjury(victim, v, zone, scaledMedicalDamage, profile);
        BodyPartState part = v.zone(zone);
        if (projectileImpact != null && projectileImpact.embedded()) {
            part.embeddedArrow = true;
        }
        WoundVisual wound = updateWoundVisual(victim, part, zone, profile, scaledMedicalDamage,
                hitRatio, lateral, incomingDirection, projectileImpact);
        if (projectileImpact != null && projectileImpact.exits()) {
            createExitWound(victim, part, zone, wound, incomingDirection);
        }
        v.lastDamage = Math.max(v.lastDamage, scaledMedicalDamage);
        v.lastDamageCause = profile.id;
        v.breathDebt = clamp(v.breathDebt + scaledMedicalDamage * profile.breathDebt, 0.0, 100.0);
        v.fatigue = clamp(v.fatigue + scaledMedicalDamage * profile.fatigue, 0.0, 100.0);
        markDirty();

        emitBloodImpact(victim, zone, part, wound, profile, scaledMedicalDamage, incomingDirection);
        syncBloodWound(victim, zone, part);
        playSound(victim, SoundEvents.PLAYER_HURT, 0.75f, profile == DamageProfile.BLUNT ? 0.72f : 0.92f);
        damagePlayerSilently(victim, scaledHealthDamage);
    }

    public JsonObject startTreatment(ServerPlayer player, String partId, String actionId) {
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
        if (treatments.containsKey(player.getUUID())) {
            response.addProperty("success", false);
            response.addProperty("message", "Лечение уже идет.");
            return response;
        }

        PendingTreatment pending = new PendingTreatment(zone, action, action.durationTicks, player.position());
        treatments.put(player.getUUID(), pending);
        player.setSprinting(false);
        player.sendSystemMessage(Component.literal(action.label + ": 0%"), true);

        response.addProperty("success", true);
        response.addProperty("message", "Лечение начато.");
        response.add("treatment", pending.toJson());
        return response;
    }

    public void onQuit(ServerPlayer player) {
        bloodFx.clear(player, -1);
        Vitals v = vitals.get(player.getUUID());
        if (v != null) {
            restorePlayerMovementAndSymptoms(player);
            markDirty();
            saveToDisk(false);
        }
        treatments.remove(player.getUUID());
    }

    
    public void onDeath(ServerPlayer player) {
        bloodFx.clear(player, -1);
        resetVitalsAfterDeath(player, false);
    }

    public void onRespawn(ServerPlayer player) {
        plugin.getServer().execute(() -> resetVitalsAfterDeath(player, true));
    }

    public float onNaturalRegain(ServerPlayer player, float amount) {
        return (float) Math.min(amount * 0.18, 0.18);
    }

    
    public void onDamage(ServerPlayer player, DamageSource source, float amount) {
        if (amount <= 0.0 || internalDamage.contains(player.getUUID())) {
            return;
        }

        cancelTreatment(player, "Treatment interrupted.");
        Vitals v = getVitals(player);
        DamageProfile profile = profileFor(source, player);
        BodyZone zone = chooseZone(source, player, profile);
        ArrowImpactPhysics.Result projectileImpact =
                resolveProjectileImpact(player, source, zone.ordinal());
        double scaledAmount = projectileImpact == null ? amount : amount * projectileImpact.damageScale();
        applyInjury(player, v, zone, scaledAmount, profile);
        BodyPartState part = v.zone(zone);
        if (projectileImpact != null && projectileImpact.embedded()) part.embeddedArrow = true;
        long placementSeed = ThreadLocalRandom.current().nextLong();
        double fallbackSide = unit(placementSeed) * 1.5 - 0.75;
        double fallbackHeight = unit(placementSeed ^ 0x632be59bd9b4e019L);
        Vec3 incoming = incomingDirection(player, source);
        WoundVisual wound = updateWoundVisual(player, part, zone, profile, scaledAmount,
                fallbackGlobalRatio(zone, fallbackHeight), fallbackSide, incoming, projectileImpact);
        if (projectileImpact != null && projectileImpact.exits()) {
            createExitWound(player, part, zone, wound, incoming);
        }
        emitBloodImpact(player, zone, part, wound, profile, scaledAmount, incoming);
        syncBloodWound(player, zone, part);

        v.lastDamage = Math.max(v.lastDamage, scaledAmount);
        v.lastDamageCause = profile.id;
        v.breathDebt = clamp(v.breathDebt + amount * profile.breathDebt, 0.0, 100.0);
        v.fatigue = clamp(v.fatigue + amount * profile.fatigue, 0.0, 100.0);
        markDirty();
    }

    
    public boolean onToggleSprint(ServerPlayer player, boolean sprinting) {
        if (!sprinting) {
            return true;
        }
        Vitals v = getVitals(player);
        if (v.stamina <= 5.0 || v.zone(BodyZone.LEFT_LEG).isBroken() || v.zone(BodyZone.RIGHT_LEG).isBroken()) {
            return false;
        }
        return true;
    }

    
    public void onMove(ServerPlayer player, Vec3 from, Vec3 to) {
        PendingTreatment pending = treatments.get(player.getUUID());
        if (pending != null && pending.movedTooFar(to)) {
            cancelTreatment(player, "Treatment interrupted by movement.");
            return;
        }
        Vitals v = getVitals(player);
        boolean brokenLeg = v.zone(BodyZone.LEFT_LEG).isBroken() || v.zone(BodyZone.RIGHT_LEG).isBroken();
        if (!brokenLeg) {
            return;
        }
        double dy = to.y - from.y;
        if (dy > 0.28 && player.onGround()) {
            boolean stabilizedLeg = v.zone(BodyZone.LEFT_LEG).fractureStabilized || v.zone(BodyZone.RIGHT_LEG).fractureStabilized;
            v.addPain(stabilizedLeg ? 2.5 : 7.0);
            player.setDeltaMovement(player.getDeltaMovement().x * 0.15, -0.22, player.getDeltaMovement().z * 0.15);
            damagePlayerSilently(player, 0.6);
            if (v.unconsciousTicks <= 0 && ThreadLocalRandom.current().nextDouble() < (stabilizedLeg ? 0.04 : 0.18)) {
                knockdown(player, v, 36, "Боль в ноге сбила вас с ног");
            }
        }
    }

    
    public boolean onTreat(ServerPlayer player, InteractionHand hand) {
        if (player == null || hand == null || !player.isCrouching()) return false;
        ItemStack item = player.getItemInHand(hand);
        if (item.isEmpty() || (item.getItem() != Items.PAPER && item.getItem() != Items.WHITE_WOOL)) return false;

        Vitals v = getVitals(player);
        BodyPartState target = worstBleedingPart(v);
        if (target == null || target.bleeding <= 0.1) {
            player.sendSystemMessage(Component.literal("Активного кровотечения нет"), true);
            return true;
        }

        BodyZone zone = zoneForPart(v, target);
        if (zone != null) {
            JsonObject result = startTreatment(player, zone.id, TreatmentAction.BANDAGE.id);
            if (!result.get("success").getAsBoolean() && result.has("message")) {
                player.sendSystemMessage(Component.literal(result.get("message").getAsString()), true);
            }
            return true;
        }

        target.bleeding = 0.0;
        target.openWound = false;
        target.openWoundTicks = 0;
        target.pain = clamp(target.pain + 2.0, 0.0, 100.0);
        markDirty();
        item.shrink(1);
        player.sendSystemMessage(Component.literal("Кровотечение остановлено"), true);
        playSound(player, SoundEvents.ARMOR_EQUIP_LEATHER.value(), 0.45f, 1.1f);
        return true;
    }

    public void tick() {
        saveTicks++;
        for (ServerPlayer player : plugin.getServer().getPlayerList().getPlayers()) {
            if (player.gameMode.getGameModeForPlayer() == GameType.SPECTATOR || !player.isAlive()) {
                continue;
            }
            Vitals v = getVitals(player);
            v.ticks++;

            Vec3 location = player.position();
            UUID currentWorldId = worldId(player.level());
            boolean hasPreviousMove = currentWorldId != null
                    && v.lastMoveWorld != null
                    && currentWorldId.equals(v.lastMoveWorld);
            if (hasPreviousMove) {
                onMove(player, new Vec3(v.lastMoveX, v.lastMoveY, v.lastMoveZ), location);
                location = player.position();
            }
            double moved = movementDelta(v, location, currentWorldId);
            double verticalMove = hasPreviousMove ? Math.abs(location.y - v.lastMoveY) : 0.0;
            rememberMovement(v, location, currentWorldId);
            double movementIntensity = clamp(moved / 0.145, 0.0, 1.65);
            double armorWeight = Math.min(0.30, countArmorPieces(player) * 0.060);
            boolean moving = moved > 0.018;
            boolean sprinting = player.isSprinting() && moved > 0.070;
            if (player.isSprinting() && !onToggleSprint(player, true)) {
                player.setSprinting(false);
                sprinting = false;
            }
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
            if (verticalMove > 0.12 && !player.onGround()) {
                drain += 0.030 + Math.min(0.075, verticalMove * 0.12);
            }
            if (player.getFoodData().getFoodLevel() <= 6) {
                drain += 0.020;
            }

            double regen = sprinting ? 0.0 : (moving ? 0.006 : 0.145);
            if (player.getFoodData().getFoodLevel() <= 6) {
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

    private UUID worldId(net.minecraft.world.level.Level level) {
        if (level == null) return null;
        return UUID.nameUUIDFromBytes(level.dimension().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private double movementDelta(Vitals v, Vec3 location, UUID currentWorldId) {
        if (location == null || currentWorldId == null || v.lastMoveWorld == null
                || !currentWorldId.equals(v.lastMoveWorld)) {
            return 0.0;
        }
        return Math.hypot(location.x - v.lastMoveX, location.z - v.lastMoveZ);
    }

    private void rememberMovement(Vitals v, Vec3 location, UUID currentWorldId) {
        if (location == null || currentWorldId == null) {
            v.lastMoveWorld = null;
            return;
        }
        v.lastMoveWorld = currentWorldId;
        v.lastMoveX = location.x;
        v.lastMoveY = location.y;
        v.lastMoveZ = location.z;
    }

    private void tickTreatment(ServerPlayer player, Vitals v) {
        PendingTreatment pending = treatments.get(player.getUUID());
        if (pending == null) {
            return;
        }

        player.setSprinting(false);
        player.setDeltaMovement(player.getDeltaMovement().scale(0.82));
        pending.remainingTicks--;

        int percent = (int) Math.round(pending.progress() * 100.0);
        if (pending.remainingTicks % 10 == 0 || pending.remainingTicks <= 0) {
            player.sendSystemMessage(Component.literal(pending.action.label + ": " + percent + "%"), true);
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

        completeTreatment(player, pending.zone, part, pending.action);
        markDirty();
        treatments.remove(player.getUUID());
        playSound(player, SoundEvents.ARMOR_EQUIP_LEATHER.value(), 0.55f, 1.0f);
        player.sendSystemMessage(Component.literal(pending.action.doneLabel), true);
    }

    private void completeTreatment(ServerPlayer player, BodyZone zone, BodyPartState part, TreatmentAction action) {
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
                playSound(player, SoundEvents.PLAYER_HURT, 0.55f, 0.8f);
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
        if (part.bleeding <= 0.1 && !part.openWound && !part.bandaged
                && !part.tourniquet && !part.embeddedArrow) {
            part.wounds.clear();
            bloodFx.clear(player, zone.ordinal());
        } else {
            syncBloodWound(player, zone, part);
        }
    }

    private void cancelTreatment(ServerPlayer player, String message) {
        PendingTreatment removed = treatments.remove(player.getUUID());
        if (removed != null) {
            player.sendSystemMessage(Component.literal(message), true);
            playSound(player, SoundEvents.WOODEN_BUTTON_CLICK_OFF, 0.35f, 0.8f);
        }
    }

    private void resetVitalsAfterDeath(ServerPlayer player, boolean notify) {
        UUID uuid = player.getUUID();
        vitals.remove(uuid);
        treatments.remove(uuid);
        restorePlayerMovementAndSymptoms(player);
        markDirty();
        saveToDisk(false);
    }

    private void restorePlayerMovementAndSymptoms(ServerPlayer player) {
        setWalkSpeed(player, 0.2f);
        player.setSprinting(false);
        removeEffect(player, MobEffects.BLINDNESS);
        removeEffect(player, MobEffects.NAUSEA);
        removeEffect(player, MobEffects.SLOWNESS);
        removeEffect(player, MobEffects.MINING_FATIGUE);
        removeEffect(player, MobEffects.WEAKNESS);
        removeEffect(player, MobEffects.POISON);
    }

    private void tickInjuries(ServerPlayer player, Vitals v) {
        float movement = movementFactor(player);
        double externalFlowMlPerSecond = 0.0;
        if (v.blood > 0.0) {
            for (BodyPartState part : v.parts.values()) {
                externalFlowMlPerSecond += partFlowMlPerSecond(part, movement);
            }
        }
        if (externalFlowMlPerSecond > 0.0) {
            double requestedMl = externalFlowMlPerSecond / 20.0;
            double availableMl = remainingBloodMl(v);
            double lostMl = Math.min(requestedMl, availableMl);
            v.blood = clamp(v.blood - BloodVolumeRules.bloodUnitsForVolume((float) lostMl),
                    0.0, MAX_BLOOD);
            if (v.ticks % 40 == 0) {
                damagePlayerSilently(player, Math.min(0.65, externalFlowMlPerSecond * 0.026));
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
        if (v.ticks % 40 == 0) {
            for (BodyZone zone : BodyZone.values()) {
                BodyPartState part = v.zone(zone);
                if (part.bleeding > 0.1 || part.openWound || part.bandaged || part.embeddedArrow) {
                    syncBloodWound(player, zone, part);
                }
            }
        }
    }

    private void applySymptoms(ServerPlayer player, Vitals v, boolean moving) {
        BodyPartState head = v.zone(BodyZone.HEAD);
        BodyPartState torso = v.zone(BodyZone.TORSO);
        BodyPartState leftArm = v.zone(BodyZone.LEFT_ARM);
        BodyPartState rightArm = v.zone(BodyZone.RIGHT_ARM);
        BodyPartState leftLeg = v.zone(BodyZone.LEFT_LEG);
        BodyPartState rightLeg = v.zone(BodyZone.RIGHT_LEG);

        double pain = v.totalPain();
        if (head.condition < 65.0 || head.pain > 35.0) {
            addEffect(player, new MobEffectInstance(MobEffects.NAUSEA, 70, head.condition < 35.0 ? 1 : 0));
        }
        if ((head.condition < 35.0 || pain > 78.0 || v.blood < 42.0) && v.ticks % 80 < 18) {
            addEffect(player, new MobEffectInstance(MobEffects.BLINDNESS, 28, 0));
        }
        if (head.condition < 28.0 && v.unconsciousTicks <= 0 && ThreadLocalRandom.current().nextDouble() < 0.006) {
            knockdown(player, v, 90, "Контузія");
        }

        if (torso.condition < 65.0 || torso.bleeding > 0.3) {
            v.breathDebt = clamp(v.breathDebt + 0.05, 0.0, 100.0);
            v.stamina = clamp(v.stamina - 0.035, 0.0, MAX_STAMINA);
        }

        if (leftArm.condition < 65.0 || rightArm.condition < 65.0) {
            addEffect(player, new MobEffectInstance(MobEffects.WEAKNESS, 50, armsAmplifier(leftArm, rightArm)));
            addEffect(player, new MobEffectInstance(MobEffects.MINING_FATIGUE, 50, armsAmplifier(leftArm, rightArm)));
        }
        maybeDropItemFromBrokenArm(player, leftArm, InteractionHand.OFF_HAND);
        maybeDropItemFromBrokenArm(player, rightArm, InteractionHand.MAIN_HAND);

        int slowAmplifier = legSlowAmplifier(leftLeg, rightLeg);
        if (slowAmplifier >= 0) {
            addEffect(player, new MobEffectInstance(MobEffects.SLOWNESS, 50, slowAmplifier));
        }
        if (leftLeg.isBroken() || rightLeg.isBroken()) {
            player.setSprinting(false);
        }

        if (v.hasInfection()) {
            int poisonWindow = v.hasAntibiotics() ? 16 : 45;
            if (v.ticks % 100 < poisonWindow) {
                addEffect(player, new MobEffectInstance(MobEffects.POISON, 60, 0));
            }
            if (v.ticks % 120 == 0) {
                damagePlayerSilently(player, v.hasAntibiotics() ? 0.12 : 0.35);
            }
        }

        if (pain > 65.0 && moving && v.ticks % 55 == 0) {
            playSound(player, SoundEvents.GENERIC_HURT, 0.45f, 0.55f);
        }
        if ((pain > 92.0 || v.blood < 25.0) && v.unconsciousTicks <= 0) {
            knockdown(player, v, 120, "Потеря сознания");
        }
        if (v.unconsciousTicks > 0) {
            v.unconsciousTicks--;
            player.setSprinting(false);
            addEffect(player, new MobEffectInstance(MobEffects.BLINDNESS, 45, 1));
            addEffect(player, new MobEffectInstance(MobEffects.SLOWNESS, 45, 8));
            player.setDeltaMovement(player.getDeltaMovement().scale(0.2));
        }

        float walkSpeed = computeWalkSpeed(v);
        if (Math.abs((player.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED) != null ? (float)(player.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED).getBaseValue() * 2.0) : 0.2f) - walkSpeed) > 0.002f) {
            setWalkSpeed(player, walkSpeed);
        }
    }

    private DamageProfile profileFor(DamageSource source, ServerPlayer victim) {
        if (source.is(net.minecraft.world.damagesource.DamageTypes.FALL)) {
            return DamageProfile.FALL;
        }
        if (source.is(net.minecraft.world.damagesource.DamageTypes.IN_FIRE)
                || source.is(net.minecraft.world.damagesource.DamageTypes.ON_FIRE)
                || source.is(net.minecraft.world.damagesource.DamageTypes.LAVA)
                || source.is(net.minecraft.world.damagesource.DamageTypes.HOT_FLOOR)) {
            return DamageProfile.BURN;
        }
        if (source.is(net.minecraft.world.damagesource.DamageTypes.ARROW)
                || source.is(net.minecraft.world.damagesource.DamageTypes.THROWN)
                || source.getDirectEntity() instanceof Projectile) {
            return DamageProfile.PROJECTILE;
        }
        Entity attacker = source.getEntity();
        if (attacker instanceof ServerPlayer attackerPlayer) {
            Item weapon = attackerPlayer.getMainHandItem().getItem();
            String name = weapon.toString().toUpperCase(java.util.Locale.ROOT);
            if (name.contains("SWORD") || name.contains("AXE") || name.contains("TRIDENT")) {
                return DamageProfile.SHARP;
            }
            if (name.contains("MACE")) {
                return DamageProfile.BLUNT;
            }
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

    private WoundVisual updateWoundVisual(ServerPlayer victim, BodyPartState part, BodyZone zone,
                                          DamageProfile profile, double damage,
                                          double globalHitRatio, double lateral, Vec3 direction,
                                          ArrowImpactPhysics.Result projectileImpact) {
        if (part == null) return null;
        double side = clamp(lateral, -1.0, 1.0);
        double height = zoneLocalHeight(zone, globalHitRatio);
        int face = impactFace(victim, direction);
        double intensity = clamp(damage / 11.0 + part.bleeding / 42.0, 0.08, 1.0);
        WoundVisual nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (WoundVisual candidate : part.wounds) {
            if (candidate.face != face) continue;
            double distance = Math.hypot(candidate.side - side, candidate.height - height);
            if (distance < nearestDistance) {
                nearest = candidate;
                nearestDistance = distance;
            }
        }
        if (nearest != null && nearestDistance <= 0.17) {
            nearest.side = nearest.side * 0.72 + side * 0.28;
            nearest.height = nearest.height * 0.72 + height * 0.28;
            nearest.intensity = clamp(nearest.intensity + intensity * 0.34, 0.08, 1.0);
            nearest.flowWeight = Math.max(nearest.flowWeight,
                    BloodVolumeRules.woundFlowWeight(bloodProfile(profile), (float) intensity));
            if (direction != null && direction.lengthSqr() > 1.0e-6) nearest.direction = direction.normalize();
            applyProjectileResult(nearest, projectileImpact);
        } else {
            long seed = ThreadLocalRandom.current().nextLong();
            if (seed == 0L) seed = 1L;
            nearest = new WoundVisual(seed, seed, side, height, intensity, bloodProfile(profile), face,
                    direction == null ? Vec3.ZERO : direction.normalize(),
                    BloodVolumeRules.woundFlowWeight(bloodProfile(profile), (float) intensity));
            applyProjectileResult(nearest, projectileImpact);
            part.wounds.add(nearest);
            if (part.wounds.size() > 8) {
                part.wounds.remove(part.wounds.stream()
                        .min((a, b) -> Double.compare(a.intensity, b.intensity)).orElse(part.wounds.get(0)));
            }
        }
        mirrorPrimaryWound(part, nearest);
        return nearest;
    }

    private void applyProjectileResult(WoundVisual wound, ArrowImpactPhysics.Result impact) {
        if (wound == null || impact == null) return;
        wound.penetrationDepth = clamp(impact.penetrationDepthBlocks(), 0.0, 0.75);
        wound.embeddedProjectile = impact.embedded();
        wound.projectileExit = false;
        wound.projectileShallow = impact.outcome() == ArrowImpactPhysics.Outcome.SHALLOW;
    }

    private WoundVisual createExitWound(ServerPlayer victim, BodyPartState part, BodyZone zone,
                                        WoundVisual entry, Vec3 direction) {
        if (entry == null || part == null) return null;
        long id = entry.id ^ 0x6a09e667f3bcc909L;
        int oppositeFace = switch (entry.face) {
            case 0 -> 1;
            case 1 -> 0;
            case 2 -> 3;
            default -> 2;
        };
        WoundVisual exit = new WoundVisual(id, entry.seed ^ 0xbb67ae8584caa73bL,
                -entry.side, entry.height, clamp(entry.intensity * 1.08, 0.08, 1.0),
                1, oppositeFace, direction == null ? Vec3.ZERO : direction.normalize(),
                entry.flowWeight * 1.12);
        exit.projectileExit = true;
        exit.penetrationDepth = 0.0;
        part.wounds.add(exit);
        if (part.wounds.size() > 8) {
            part.wounds.remove(part.wounds.stream()
                    .min((a, b) -> Double.compare(a.intensity, b.intensity)).orElse(part.wounds.get(0)));
        }
        return exit;
    }

    private void ensureWoundVisual(BodyPartState part, BodyZone zone) {
        if (!part.wounds.isEmpty()) {
            mirrorPrimaryWound(part, part.wounds.get(part.wounds.size() - 1));
            return;
        }
        if (part.woundSeed == 0L) {
            long seed = UUID.nameUUIDFromBytes((zone.id + ':' + part.lastCause + ':' + part.openWoundTicks)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8)).getMostSignificantBits();
            part.woundSeed = seed == 0L ? 1L : seed;
        }
        if (!Double.isFinite(part.woundSide)) part.woundSide = unit(part.woundSeed) * 1.4 - 0.7;
        if (!Double.isFinite(part.woundHeight)) part.woundHeight = unit(part.woundSeed ^ 0x632be59bd9b4e019L);
        if (part.woundIntensity <= 0.0) {
            part.woundIntensity = clamp(Math.max(part.bleeding / 24.0, (100.0 - part.condition) / 100.0),
                    0.08, 1.0);
        }
        if (part.woundProfile < 0 || part.woundProfile > 4) {
            part.woundProfile = bloodProfile(part.lastCause);
        }
        part.wounds.add(new WoundVisual(part.woundSeed, part.woundSeed, part.woundSide,
                part.woundHeight, part.woundIntensity, part.woundProfile, 0, Vec3.ZERO,
                BloodVolumeRules.woundFlowWeight(part.woundProfile, (float) part.woundIntensity)));
    }

    private void emitBloodImpact(ServerPlayer victim, BodyZone zone, BodyPartState part, WoundVisual wound,
                                 DamageProfile profile, double damage, Vec3 direction) {
        if (profile == DamageProfile.FALL || profile == DamageProfile.BURN) return;
        ensureWoundVisual(part, zone);
        WoundVisual active = wound == null ? part.wounds.get(part.wounds.size() - 1) : wound;
        Vitals state = getVitals(victim);
        float impactVolumeMl = BloodVolumeRules.impactVolumeMl(
                bloodProfile(profile), (float) damage, (float) active.intensity, part.embeddedArrow);
        impactVolumeMl = Math.min(impactVolumeMl, remainingBloodMl(state));
        if (impactVolumeMl < BloodVolumeRules.MIN_VISIBLE_DROP_ML) return;
        state.blood = clamp(state.blood - BloodVolumeRules.bloodUnitsForVolume(impactVolumeMl),
                0.0, MAX_BLOOD);
        float bleedingShare = (float) (part.bleeding / Math.max(1, part.wounds.size()));
        float partFlow = state.blood <= 0.0 ? 0.0f
                : partFlowMlPerSecond(part, movementFactor(victim));
        bloodFx.impact(victim, active.id, zone.ordinal(), active.face, bloodProfile(profile),
                (float) active.side, (float) active.height,
                (float) clamp(Math.max(active.intensity, damage / 12.0), 0.0, 1.0),
                bleedingShare, impactVolumeMl, woundFlowShare(part, active, partFlow),
                remainingBloodMl(state), (float) active.penetrationDepth,
                direction, active.seed, bloodFxFlags(part, active));
    }

    private void syncBloodWound(ServerPlayer victim, BodyZone zone, BodyPartState part) {
        if (part == null) return;
        if (part.bleeding <= 0.1 && !part.openWound && !part.bandaged && !part.embeddedArrow) {
            part.wounds.clear();
            bloodFx.clear(victim, zone.ordinal());
            return;
        }
        ensureWoundVisual(part, zone);
        Vitals state = getVitals(victim);
        float bleedingShare = (float) (part.bleeding / Math.max(1, part.wounds.size()));
        float partFlow = state.blood <= 0.0 ? 0.0f
                : partFlowMlPerSecond(part, movementFactor(victim));
        for (WoundVisual wound : part.wounds) {
            bloodFx.syncWound(victim, wound.id, zone.ordinal(), wound.face, wound.profile,
                    (float) wound.side, (float) wound.height, (float) wound.intensity,
                    bleedingShare, woundFlowShare(part, wound, partFlow),
                    remainingBloodMl(state), (float) wound.penetrationDepth,
                    wound.direction, wound.seed, bloodFxFlags(part, wound));
        }
    }

    private float partFlowMlPerSecond(BodyPartState part, float movement) {
        if (part == null) return 0.0f;
        boolean hasExternalWound = part.wounds.stream().anyMatch(wound -> wound.profile == 0 || wound.profile == 1);
        return hasExternalWound
                ? BloodVolumeRules.flowRateMlPerSecond((float) part.bleeding, part.openWound,
                part.bandaged, part.tourniquet, part.embeddedArrow, movement)
                : 0.0f;
    }

    private float woundFlowShare(BodyPartState part, WoundVisual wound, float partFlow) {
        if (part == null || wound == null || partFlow <= 0.0f || wound.profile > 1) return 0.0f;
        double totalWeight = part.wounds.stream()
                .filter(candidate -> candidate.profile <= 1)
                .mapToDouble(candidate -> Math.max(0.0, candidate.flowWeight))
                .sum();
        if (totalWeight <= 1.0e-6) return 0.0f;
        return (float) (partFlow * Math.max(0.0, wound.flowWeight) / totalWeight);
    }

    private float movementFactor(ServerPlayer player) {
        if (player == null) return 0.0f;
        return (float) clamp(player.getDeltaMovement().horizontalDistance() * 7.0, 0.0, 1.0);
    }

    private float remainingBloodMl(Vitals state) {
        if (state == null) return 0.0f;
        return (float) clamp(state.blood * BloodVolumeRules.MILLILITRES_PER_BLOOD_UNIT,
                0.0, MAX_BLOOD * BloodVolumeRules.MILLILITRES_PER_BLOOD_UNIT);
    }

    private void mirrorPrimaryWound(BodyPartState part, WoundVisual wound) {
        part.woundSeed = wound.seed;
        part.woundSide = wound.side;
        part.woundHeight = wound.height;
        part.woundIntensity = wound.intensity;
        part.woundProfile = wound.profile;
    }

    private int impactFace(ServerPlayer victim, Vec3 incoming) {
        if (victim == null || incoming == null || incoming.horizontalDistanceSqr() < 1.0e-6) return 0;
        Vec3 direction = incoming.normalize().scale(-1.0);
        double yaw = Math.toRadians(victim.yBodyRot);
        Vec3 forward = new Vec3(-Math.sin(yaw), 0.0, Math.cos(yaw));
        Vec3 right = new Vec3(Math.cos(yaw), 0.0, Math.sin(yaw));
        double forwardDot = direction.dot(forward);
        double rightDot = direction.dot(right);
        if (Math.abs(forwardDot) >= Math.abs(rightDot)) return forwardDot >= 0.0 ? 0 : 1;
        return rightDot >= 0.0 ? 3 : 2;
    }

    private int bloodFxFlags(BodyPartState part, WoundVisual wound) {
        int flags = 0;
        if (part.bandaged || part.tourniquet) flags |= BloodFxPayload.FLAG_BANDAGED;
        if (wound != null && wound.embeddedProjectile) flags |= BloodFxPayload.FLAG_EMBEDDED_PROJECTILE;
        if (wound != null && wound.projectileExit) flags |= BloodFxPayload.FLAG_PROJECTILE_EXIT;
        if (wound != null && wound.projectileShallow) flags |= BloodFxPayload.FLAG_PROJECTILE_SHALLOW;
        if (part.openWound) flags |= BloodFxPayload.FLAG_OPEN_WOUND;
        return flags;
    }

    private int bloodProfile(DamageProfile profile) {
        return switch (profile) {
            case SHARP -> 0;
            case PROJECTILE -> 1;
            case BLUNT -> 2;
            case BURN -> 3;
            case FALL -> 4;
        };
    }

    private int bloodProfile(String cause) {
        if ("projectile".equalsIgnoreCase(cause)) return 1;
        if ("blunt".equalsIgnoreCase(cause)) return 2;
        if ("burn".equalsIgnoreCase(cause)) return 3;
        if ("fall".equalsIgnoreCase(cause)) return 4;
        return 0;
    }

    private Vec3 incomingDirection(ServerPlayer victim, DamageSource source) {
        Entity attacker = source == null ? null : source.getDirectEntity();
        if (attacker instanceof AbstractArrow arrow && arrow.getDeltaMovement().lengthSqr() > 1.0e-6) {
            return arrow.getDeltaMovement().normalize();
        }
        if (attacker == null && source != null) attacker = source.getEntity();
        if (attacker != null) {
            Vec3 direction = victim.getBoundingBox().getCenter().subtract(attacker.getBoundingBox().getCenter());
            if (direction.lengthSqr() > 1.0e-6) return direction.normalize();
        }
        double yaw = Math.toRadians(victim.getYRot());
        return new Vec3(-Math.sin(yaw), 0.10, Math.cos(yaw)).normalize();
    }

    public ArrowImpactPhysics.Result resolveProjectileImpact(ServerPlayer victim, DamageSource source,
                                                              int zoneOrdinal) {
        if (victim == null || source == null
                || !(source.getDirectEntity() instanceof AbstractArrow arrow)) {
            return null;
        }
        Vec3 velocity = arrow.getDeltaMovement();
        double speed = velocity.length();
        if (speed < 1.0e-5) return null;
        Vec3 normal = contactNormal(victim, arrow.position());
        double incidence = Math.abs(velocity.normalize().dot(normal));
        double armor = projectileArmorResistance(victim, zoneOrdinal);
        long seed = arrow.getUUID().getMostSignificantBits()
                ^ arrow.getUUID().getLeastSignificantBits()
                ^ victim.getUUID().getLeastSignificantBits();
        ArrowImpactPhysics.Result result = ArrowImpactPhysics.resolve(
                new ArrowImpactPhysics.Input(speed, incidence, armor, zoneOrdinal, seed));
        ArrowImpactRuntime.record(arrow.getUUID(), result);
        return result;
    }

    private double projectileArmorResistance(ServerPlayer victim, int zoneOrdinal) {
        EquipmentSlot slot = switch (zoneOrdinal) {
            case 0 -> EquipmentSlot.HEAD;
            case 1, 2, 3 -> EquipmentSlot.CHEST;
            default -> EquipmentSlot.LEGS;
        };
        String itemId = BuiltInRegistries.ITEM.getKey(victim.getItemBySlot(slot).getItem()).toString();
        double coverage = switch (zoneOrdinal) {
            case 2, 3 -> 0.65;
            case 4, 5 -> 0.85;
            default -> 1.0;
        };
        return ArrowImpactPhysics.armorResistance(itemId) * coverage;
    }

    private Vec3 contactNormal(ServerPlayer victim, Vec3 point) {
        var box = victim.getBoundingBox();
        double[] distances = {
                Math.abs(point.x - box.minX), Math.abs(box.maxX - point.x),
                Math.abs(point.y - box.minY), Math.abs(box.maxY - point.y),
                Math.abs(point.z - box.minZ), Math.abs(box.maxZ - point.z)
        };
        Vec3[] normals = {
                new Vec3(-1, 0, 0), new Vec3(1, 0, 0),
                new Vec3(0, -1, 0), new Vec3(0, 1, 0),
                new Vec3(0, 0, -1), new Vec3(0, 0, 1)
        };
        int nearest = 0;
        for (int i = 1; i < distances.length; i++) {
            if (distances[i] < distances[nearest]) nearest = i;
        }
        return normals[nearest];
    }

    private double fallbackGlobalRatio(BodyZone zone, double localHeight) {
        double local = clamp(localHeight, 0.0, 1.0);
        return switch (zone) {
            case HEAD -> 0.82 + local * 0.18;
            case TORSO -> 0.46 + local * 0.36;
            case LEFT_ARM, RIGHT_ARM -> 0.32 + local * 0.50;
            case LEFT_LEG, RIGHT_LEG -> local * 0.46;
        };
    }

    private double zoneLocalHeight(BodyZone zone, double globalHitRatio) {
        double ratio = clamp(globalHitRatio, 0.0, 1.0);
        return switch (zone) {
            case HEAD -> clamp((ratio - 0.82) / 0.18, 0.0, 1.0);
            case TORSO -> clamp((ratio - 0.46) / 0.36, 0.0, 1.0);
            case LEFT_ARM, RIGHT_ARM -> clamp((ratio - 0.32) / 0.50, 0.0, 1.0);
            case LEFT_LEG, RIGHT_LEG -> clamp(ratio / 0.46, 0.0, 1.0);
        };
    }

    private static double unit(long value) {
        value ^= value >>> 30;
        value *= 0xbf58476d1ce4e5b9L;
        value ^= value >>> 27;
        value *= 0x94d049bb133111ebL;
        value ^= value >>> 31;
        return (value >>> 40) / (double) (1L << 24);
    }

    private BodyZone chooseZone(DamageSource source, ServerPlayer player, DamageProfile profile) {
        if (profile == DamageProfile.FALL) {
            return ThreadLocalRandom.current().nextBoolean() ? BodyZone.LEFT_LEG : BodyZone.RIGHT_LEG;
        }
        if (profile == DamageProfile.BURN) {
            return weightedRandomZone(8, 26, 14, 14, 19, 19);
        }
        Entity damager = source.getDirectEntity();
        if (damager instanceof Projectile projectile) {
            double relY = projectile.getY() - player.getY();
            if (relY > 1.45) return BodyZone.HEAD;
            if (relY < 0.80) return ThreadLocalRandom.current().nextBoolean() ? BodyZone.LEFT_LEG : BodyZone.RIGHT_LEG;
            if (relY > 1.05) return BodyZone.TORSO;
            return weightedRandomZone(10, 42, 16, 16, 8, 8);
        }
        return weightedRandomZone(9, 38, 16, 16, 10, 11);
    }

    private void applyInjury(ServerPlayer player, Vitals v, BodyZone zone, double damage, DamageProfile profile) {
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

    private void maybeDropItemFromBrokenArm(ServerPlayer player, BodyPartState arm, InteractionHand slot) {
        double chance = arm.fractureStabilized ? 0.0008 : 0.003;
        if (!arm.isBroken() || ThreadLocalRandom.current().nextDouble() > chance) {
            return;
        }
        ItemStack item = slot == InteractionHand.MAIN_HAND
                ? player.getMainHandItem()
                : player.getOffhandItem();
        if (item == null || item.isEmpty()) {
            return;
        }
        ItemStack dropped = item.copy();
        if (slot == InteractionHand.MAIN_HAND) {
            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        } else {
            player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
        }
        player.drop(dropped, false, true);
        player.sendSystemMessage(Component.literal("Травмированная рука не удержала предмет"), true);
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

    private JsonObject treatmentJson(ServerPlayer player) {
        PendingTreatment pending = treatments.get(player.getUUID());
        return pending == null ? new JsonObject() : pending.toJson();
    }

    private boolean hasTreatmentItem(ServerPlayer player, TreatmentAction action) {
        if (action.materials.length == 0) {
            return true;
        }
        if (action.toolOnly) {
            return findInventorySlot(player, action.materials) >= 0;
        }
        return findInventorySlot(player, action.materials) >= 0;
    }

    private boolean consumeTreatmentItem(ServerPlayer player, TreatmentAction action) {
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
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        if (stack.getCount() <= 1) {
            player.getInventory().setItem(slot, ItemStack.EMPTY);
        } else {
            stack.setCount(stack.getCount() - 1);
        }
        return true;
    }

    private int findInventorySlot(ServerPlayer player, Item[] materials) {
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            for (Item material : materials) {
                if (stack.getItem() == material) {
                    return i;
                }
            }
        }
        return -1;
    }

    private void damagePlayerSilently(ServerPlayer player, double amount) {
        if (amount <= 0.0 || !player.isAlive()) {
            return;
        }
        UUID playerId = player.getUUID();
        internalDamage.add(playerId);
        try {
            player.hurtServer((ServerLevel) player.level(), player.damageSources().generic(), (float) amount);
        } finally {
            internalDamage.remove(playerId);
        }
    }

    private void knockdown(ServerPlayer player, Vitals v, int ticks, String reason) {
        v.unconsciousTicks = Math.max(v.unconsciousTicks, ticks);
        player.setSprinting(false);
        Vec3 vel = player.getDeltaMovement();
        player.setDeltaMovement(new Vec3(vel.x * 0.15, -0.08, vel.z * 0.15));
        sendTitle(player, Component.empty(), Component.literal(reason), 4, 32, 8);
        playSound(player, SoundEvents.PLAYER_HURT, 0.85f, 0.55f);
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

    private int countArmorPieces(ServerPlayer player) {
        int pieces = 0;
        for (EquipmentSlot slot : List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET)) {
            ItemStack stack = player.getItemBySlot(slot);
            if (stack != null && !stack.isEmpty()) {
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
        json.addProperty("woundSide", Double.isFinite(part.woundSide) ? part.woundSide : 0.0);
        json.addProperty("woundHeight", Double.isFinite(part.woundHeight) ? part.woundHeight : 0.5);
        json.addProperty("woundIntensity", part.woundIntensity);
        json.addProperty("woundSeed", part.woundSeed);
        json.addProperty("woundProfile", part.woundProfile);
        JsonArray wounds = new JsonArray();
        for (WoundVisual wound : part.wounds) {
            JsonObject item = new JsonObject();
            item.addProperty("id", wound.id);
            item.addProperty("seed", wound.seed);
            item.addProperty("side", wound.side);
            item.addProperty("height", wound.height);
            item.addProperty("intensity", wound.intensity);
            item.addProperty("profile", wound.profile);
            item.addProperty("face", wound.face);
            item.addProperty("flowWeight", wound.flowWeight);
            item.addProperty("directionX", wound.direction.x);
            item.addProperty("directionY", wound.direction.y);
            item.addProperty("directionZ", wound.direction.z);
            item.addProperty("penetrationDepth", wound.penetrationDepth);
            item.addProperty("embeddedProjectile", wound.embeddedProjectile);
            item.addProperty("projectileExit", wound.projectileExit);
            item.addProperty("projectileShallow", wound.projectileShallow);
            wounds.add(item);
        }
        json.add("wounds", wounds);
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
        double savedWoundSide = readDouble(json, "woundSide", 0.0);
        double savedWoundHeight = readDouble(json, "woundHeight", 0.5);
        part.woundSide = Double.isFinite(savedWoundSide) ? clamp(savedWoundSide, -1.0, 1.0) : 0.0;
        part.woundHeight = Double.isFinite(savedWoundHeight) ? clamp(savedWoundHeight, 0.0, 1.0) : 0.5;
        part.woundIntensity = clamp(readDouble(json, "woundIntensity", 0.0), 0.0, 1.0);
        part.woundSeed = readLong(json, "woundSeed", 0L);
        part.woundProfile = readInt(json, "woundProfile", -1);
        if (json.has("wounds") && json.get("wounds").isJsonArray()) {
            for (JsonElement element : json.getAsJsonArray("wounds")) {
                if (!element.isJsonObject() || part.wounds.size() >= 8) continue;
                JsonObject item = element.getAsJsonObject();
                long id = readLong(item, "id", 0L);
                long seed = readLong(item, "seed", id);
                if (id == 0L) id = seed == 0L ? ThreadLocalRandom.current().nextLong() : seed;
                if (seed == 0L) seed = id;
                WoundVisual loaded = new WoundVisual(id, seed,
                        clamp(readDouble(item, "side", 0.0), -1.0, 1.0),
                        clamp(readDouble(item, "height", 0.5), 0.0, 1.0),
                        clamp(readDouble(item, "intensity", 0.1), 0.08, 1.0),
                        Math.max(0, Math.min(4, readInt(item, "profile", 0))),
                        Math.max(0, Math.min(3, readInt(item, "face", 0))),
                        new Vec3(readDouble(item, "directionX", 0.0),
                                readDouble(item, "directionY", 0.0),
                                readDouble(item, "directionZ", 0.0)),
                        Math.max(0.0, readDouble(item, "flowWeight",
                                BloodVolumeRules.woundFlowWeight(
                                        Math.max(0, Math.min(4, readInt(item, "profile", 0))),
                                        (float) clamp(readDouble(item, "intensity", 0.1), 0.08, 1.0)))));
                loaded.penetrationDepth = clamp(readDouble(item, "penetrationDepth", 0.0), 0.0, 0.75);
                loaded.embeddedProjectile = readBoolean(item, "embeddedProjectile", false);
                loaded.projectileExit = readBoolean(item, "projectileExit", false);
                loaded.projectileShallow = readBoolean(item, "projectileShallow", false);
                part.wounds.add(loaded);
            }
        }
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

    private static long readLong(JsonObject json, String key, long fallback) {
        try {
            return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsLong() : fallback;
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
        private double woundSide = 0.0;
        private double woundHeight = 0.5;
        private double woundIntensity = 0.0;
        private long woundSeed = 0L;
        private int woundProfile = -1;
        private final List<WoundVisual> wounds = new ArrayList<>();

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

    private static final class WoundVisual {
        private final long id;
        private final long seed;
        private double side;
        private double height;
        private double intensity;
        private int profile;
        private final int face;
        private Vec3 direction;
        private double flowWeight;
        private double penetrationDepth;
        private boolean embeddedProjectile;
        private boolean projectileExit;
        private boolean projectileShallow;

        private WoundVisual(long id, long seed, double side, double height,
                            double intensity, int profile, int face, Vec3 direction,
                            double flowWeight) {
            this.id = id;
            this.seed = seed;
            this.side = side;
            this.height = height;
            this.intensity = intensity;
            this.profile = profile;
            this.face = face;
            this.direction = direction == null ? Vec3.ZERO : direction;
            this.flowWeight = Math.max(0.0, flowWeight);
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
                new Item[]{Items.PAPER, Items.WHITE_WOOL}),
        CLEAN_WOUND("clean_wound", "Обработка раны", "Рана обработана", "мед, зелье или стеклянная бутылка",
                "Снижает риск инфекции и немного уменьшает боль.", 90, false,
                new Item[]{Items.HONEY_BOTTLE, Items.POTION, Items.GLASS_BOTTLE}),
        SPLINT("splint", "Наложение шины", "Шина наложена", "палка или бамбук",
                "Стабилизирует перелом и ослабляет штрафы движения.", 150, false,
                new Item[]{Items.STICK, Items.BAMBOO}),
        EXTRACT_ARROW("extract_arrow", "Извлечение стрелы", "Стрела извлечена", "ножницы",
                "Извлекает стрелу, но усиливает боль и открывает рану.", 120, true,
                new Item[]{Items.SHEARS}),
        TOURNIQUET("tourniquet", "Наложение жгута", "Жгут наложен", "нитка или поводок",
                "Экстренно останавливает сильное кровотечение конечности, но опасен при долгом ношении.", 55, false,
                new Item[]{Items.STRING, Items.LEAD}),
        RELEASE_TOURNIQUET("release_tourniquet", "Снятие жгута", "Жгут снят", "не требуется",
                "Снимает давление с конечности. Если рана не перевязана, кровь может пойти снова.", 45, false,
                new Item[]{}),
        PAINKILLER("painkiller", "Обезболивание", "Боль приглушена", "сахар",
                "Временно приглушает боль, но не лечит саму травму.", 60, false,
                new Item[]{Items.SUGAR}),
        ANTIBIOTIC("antibiotic", "Антибиотик", "Курс антибиотика начат", "ферментированный паучий глаз",
                "Запускает курс лечения инфекции и ослабляет отравление.", 70, false,
                new Item[]{Items.FERMENTED_SPIDER_EYE}),
        TREAT_BURN("treat_burn", "Обработка ожога", "Ожог обработан", "снежок",
                "Уменьшает ожог, боль и риск дальнейших осложнений.", 70, false,
                new Item[]{Items.SNOWBALL});

        private final String id;
        private final String label;
        private final String doneLabel;
        private final String itemLabel;
        private final String description;
        private final int durationTicks;
        private final boolean toolOnly;
        private final Item[] materials;

        TreatmentAction(String id, String label, String doneLabel, String itemLabel, String description, int durationTicks,
                        boolean toolOnly, Item[] materials) {
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
        private final Vec3 startedAt;
        private int remainingTicks;

        private PendingTreatment(BodyZone zone, TreatmentAction action, int totalTicks, Vec3 startedAt) {
            this.zone = zone;
            this.action = action;
            this.totalTicks = totalTicks;
            this.remainingTicks = totalTicks;
            this.startedAt = startedAt;
        }

        private boolean movedTooFar(Vec3 current) {
            if (current == null || false || false
                    || !true) {
                return true;
            }
            return current.distanceToSqr(startedAt) > 1.15;
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

    private void playSound(ServerPlayer player, SoundEvent sound, float volume, float pitch) {
        if (player == null || player.connection == null) return;
        player.playSound(sound, volume, pitch);
    }

    private void addEffect(ServerPlayer player, MobEffectInstance effect) {
        if (player != null) player.addEffect(effect);
    }

    private void addEffect(ServerPlayer player, Holder<MobEffect> effect, int duration, int amplifier) {
        if (player == null) return;
        player.addEffect(new MobEffectInstance(effect, duration, amplifier, true, false, false));
    }
    
    private void removeEffect(ServerPlayer player, Holder<MobEffect> effect) {
        if (player == null) return;
        player.removeEffect(effect);
    }

    private void sendTitle(ServerPlayer player, Component title, Component subtitle, int fadeInTicks, int stayTicks, int fadeOutTicks) {
        if (player == null || player.connection == null) return;
        player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket(fadeInTicks, stayTicks, fadeOutTicks));
        if (title != null) {
            player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket(title));
        }
        if (subtitle != null) {
            player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket(subtitle));
        }
    }

    private void setWalkSpeed(ServerPlayer player, float walkSpeed) {
        if (player == null) return;
        double vanillaSpeed = 0.1 * (walkSpeed / 0.2);
        var attr = player.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED);
        if (attr != null) {
            attr.setBaseValue(vanillaSpeed);
        }
    }
}
