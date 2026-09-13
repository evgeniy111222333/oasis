package ua.rp.chat.combat;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.Locale;

public final class WeaponProfile {
    private final String id;
    private final String displayName;
    private final CombatDamageProfile damageProfile;
    private final double baseDamage;
    private final double medicalMultiplier;
    private final int accuracy;
    private final int critThreshold;
    private final long cooldownMs;
    private final double range;
    private final boolean heavy;

    private WeaponProfile(String id, String displayName, CombatDamageProfile damageProfile, double baseDamage,
                          double medicalMultiplier, int accuracy, int critThreshold, long cooldownMs, double range,
                          boolean heavy) {
        this.id = id;
        this.displayName = displayName;
        this.damageProfile = damageProfile;
        this.baseDamage = baseDamage;
        this.medicalMultiplier = medicalMultiplier;
        this.accuracy = accuracy;
        this.critThreshold = critThreshold;
        this.cooldownMs = cooldownMs;
        this.range = range;
        this.heavy = heavy;
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public CombatDamageProfile damageProfile() {
        return damageProfile;
    }

    public double baseDamage() {
        return baseDamage;
    }

    public double medicalMultiplier() {
        return medicalMultiplier;
    }

    public int accuracy() {
        return accuracy;
    }

    public int critThreshold() {
        return critThreshold;
    }

    public long cooldownMs() {
        return cooldownMs;
    }

    public double range() {
        return range;
    }

    public boolean heavy() {
        return heavy;
    }

    public static WeaponProfile fromItem(ItemStack item) {
        Material material = item == null ? Material.AIR : item.getType();
        String name = material.name().toLowerCase(Locale.ROOT);
        if (material == Material.AIR) {
            return new WeaponProfile("unarmed", "кулаком", CombatDamageProfile.BLUNT, 1.2, 0.65, 0, 20, 950, 2.75, false);
        }
        if (name.contains("sword")) {
            return new WeaponProfile("sword", "мечом", CombatDamageProfile.SHARP, tierDamage(name, 3.7, 4.2, 4.8, 5.3, 5.8), 1.0, 2, 20, 1550, 3.15, false);
        }
        if (name.contains("axe")) {
            return new WeaponProfile("axe", "топором", CombatDamageProfile.SHARP, tierDamage(name, 4.4, 5.0, 5.6, 6.2, 6.8), 1.12, 0, 20, 2250, 3.05, true);
        }
        if (name.contains("mace")) {
            return new WeaponProfile("mace", "булавой", CombatDamageProfile.BLUNT, 6.4, 1.18, -1, 19, 2450, 2.95, true);
        }
        if (name.contains("trident")) {
            return new WeaponProfile("trident", "трезубцем", CombatDamageProfile.PROJECTILE, 5.4, 1.0, 1, 20, 1850, 3.45, false);
        }
        if (name.contains("bow")) {
            return new WeaponProfile("bow", "стрелой", CombatDamageProfile.PROJECTILE, name.contains("crossbow") ? 5.7 : 4.8, 1.0, 1, 20, 900, 80.0, false);
        }
        if (name.contains("shovel") || name.contains("pickaxe") || name.contains("hoe")) {
            return new WeaponProfile("tool", "инструментом", CombatDamageProfile.BLUNT, 2.7, 0.82, -1, 20, 1500, 2.85, false);
        }
        if (material.isBlock()) {
            return new WeaponProfile("improvised", "предметом", CombatDamageProfile.BLUNT, 1.6, 0.70, -1, 20, 1250, 2.75, false);
        }
        return new WeaponProfile("item", "предметом", CombatDamageProfile.BLUNT, 1.9, 0.75, 0, 20, 1150, 2.8, false);
    }

    private static double tierDamage(String name, double woodGold, double stone, double iron, double diamond, double netherite) {
        if (name.contains("netherite")) return netherite;
        if (name.contains("diamond")) return diamond;
        if (name.contains("iron")) return iron;
        if (name.contains("stone")) return stone;
        return woodGold;
    }
}
