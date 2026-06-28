package ua.rp.chat.vitals;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class StaminaManager implements Listener {
    private static final double MAX_STAMINA = 100.0;
    private final JavaPlugin plugin;
    private final Map<UUID, Vitals> vitals = new ConcurrentHashMap<>();

    public StaminaManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        new BukkitRunnable() {
            @Override
            public void run() {
                tick();
            }
        }.runTaskTimer(plugin, 1L, 1L);
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
        json.addProperty("health", round(health));
        json.addProperty("maxHealth", round(maxHealth));
        json.addProperty("band", staminaBand(v.stamina));
        json.addProperty("bandLabel", staminaLabel(v.stamina));
        json.add("parts", bodyParts(player, v, health / Math.max(1.0, maxHealth)));
        return json;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        Vitals v = vitals.get(player.getUniqueId());
        if (v != null) {
            player.setWalkSpeed(0.2f);
        }
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

    @EventHandler
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        Vitals v = getVitals(player);
        v.lastDamage = Math.max(v.lastDamage, event.getFinalDamage());
        v.lastDamageCause = event.getCause().name().toLowerCase();
        v.breathDebt = clamp(v.breathDebt + event.getFinalDamage() * 1.8, 0.0, 100.0);
        v.fatigue = clamp(v.fatigue + event.getFinalDamage() * 0.8, 0.0, 100.0);
    }

    private void tick() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getGameMode() == GameMode.SPECTATOR || player.isDead()) {
                continue;
            }
            Vitals v = getVitals(player);
            double speed = Math.hypot(player.getVelocity().getX(), player.getVelocity().getZ());
            double armorWeight = Math.min(0.30, countArmorPieces(player) * 0.060);
            boolean sprinting = player.isSprinting() && speed > 0.09;
            boolean moving = speed > 0.045;

            double drain = 0.0;
            if (sprinting) {
                drain += 0.28 + armorWeight * 0.28;
            } else if (moving) {
                drain += 0.035 + armorWeight * 0.035;
            }
            if (player.getFoodLevel() <= 6) {
                drain += 0.025;
            }

            double regen = sprinting ? -drain : (moving ? 0.070 : 0.155);
            if (player.getFoodLevel() <= 6) {
                regen *= 0.45;
            }
            if (player.getHealth() < 8.0) {
                regen *= 0.60;
            }

            v.stamina = clamp(v.stamina + regen - drain, 0.0, MAX_STAMINA);
            v.breathDebt = clamp(v.breathDebt + (sprinting ? 0.38 : moving ? 0.08 : -0.42), 0.0, 100.0);
            v.fatigue = clamp(v.fatigue + (v.stamina < 25.0 ? 0.040 : -0.060), 0.0, 100.0);
            v.lastDamage *= 0.985;

            if (v.stamina <= 5.0 && player.isSprinting()) {
                player.setSprinting(false);
            }
            float walkSpeed = v.stamina < 5.0 ? 0.135f : v.stamina < 25.0 ? 0.165f : 0.2f;
            if (Math.abs(player.getWalkSpeed() - walkSpeed) > 0.002f) {
                player.setWalkSpeed(walkSpeed);
            }
        }
    }

    private JsonArray bodyParts(Player player, Vitals v, double healthRatio) {
        JsonArray parts = new JsonArray();
        addPart(parts, "head", "Голова", clamp(healthRatio * 100.0 - v.lastDamage * 2.0, 0.0, 100.0), v.lastDamageCause);
        addPart(parts, "chest", "Груди", clamp(healthRatio * 100.0 - v.breathDebt * 0.18, 0.0, 100.0), v.breathDebt > 45 ? "heavy_breath" : "");
        addPart(parts, "leftArm", "Ліва рука", clamp(healthRatio * 100.0 - v.fatigue * 0.12, 0.0, 100.0), "");
        addPart(parts, "rightArm", "Права рука", clamp(healthRatio * 100.0 - v.fatigue * 0.12, 0.0, 100.0), "");
        addPart(parts, "leftLeg", "Ліва нога", clamp(healthRatio * 100.0 - (100.0 - v.stamina) * 0.22, 0.0, 100.0), "");
        addPart(parts, "rightLeg", "Права нога", clamp(healthRatio * 100.0 - (100.0 - v.stamina) * 0.22, 0.0, 100.0), "");
        return parts;
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

    private void addPart(JsonArray parts, String id, String label, double condition, String cause) {
        JsonObject part = new JsonObject();
        part.addProperty("id", id);
        part.addProperty("label", label);
        part.addProperty("condition", round(condition));
        part.addProperty("state", partState(condition, cause));
        parts.add(part);
    }

    private String partState(double condition, String cause) {
        if (condition < 35.0) return "Травма";
        if (condition < 65.0) return cause != null && !cause.isBlank() ? "Поранення" : "Перевтома";
        if (condition < 88.0) return "Напруга";
        return "Стабільно";
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
            case "tired" -> "Втома";
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

    public static final class Vitals {
        private double stamina = MAX_STAMINA;
        private double breathDebt = 0.0;
        private double fatigue = 0.0;
        private double lastDamage = 0.0;
        private String lastDamageCause = "";
    }
}
