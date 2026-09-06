package ua.rp.chat.combat;

import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

public enum CombatBodyZone {
    HEAD("head", "голову", "на голове"),
    TORSO("chest", "корпус", "на корпусе"),
    LEFT_ARM("leftArm", "левую руку", "на левой руке"),
    RIGHT_ARM("rightArm", "правую руку", "на правой руке"),
    LEFT_LEG("leftLeg", "левую ногу", "на левой ноге"),
    RIGHT_LEG("rightLeg", "правую ногу", "на правой ноге");

    private final String id;
    private final String accusative;
    private final String locative;

    CombatBodyZone(String id, String accusative, String locative) {
        this.id = id;
        this.accusative = accusative;
        this.locative = locative;
    }

    public String id() {
        return id;
    }

    public String accusative() {
        return accusative;
    }

    public String locative() {
        return locative;
    }

    public boolean limb() {
        return this == LEFT_ARM || this == RIGHT_ARM || this == LEFT_LEG || this == RIGHT_LEG;
    }

    public boolean arm() {
        return this == LEFT_ARM || this == RIGHT_ARM;
    }

    public boolean leg() {
        return this == LEFT_LEG || this == RIGHT_LEG;
    }

    public static CombatBodyZone byId(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        String normalized = id.trim().toLowerCase(Locale.ROOT);
        for (CombatBodyZone zone : values()) {
            if (zone.id.equalsIgnoreCase(normalized) || zone.name().equalsIgnoreCase(normalized)) {
                return zone;
            }
        }
        return null;
    }

    public static CombatBodyZone fromHitRatio(double ratio, double lateral) {
        double y = Math.max(0.0, Math.min(1.0, ratio));
        double side = Math.max(-1.0, Math.min(1.0, lateral));
        if (y >= 0.82) {
            return HEAD;
        }
        if (y >= 0.46) {
            if (Math.abs(side) > 0.34) {
                return side < 0.0 ? RIGHT_ARM : LEFT_ARM;
            }
            return TORSO;
        }
        if (y >= 0.32 && Math.abs(side) > 0.52) {
            return side < 0.0 ? RIGHT_ARM : LEFT_ARM;
        }
        if (Math.abs(side) < 0.12) {
            return ThreadLocalRandom.current().nextBoolean() ? LEFT_LEG : RIGHT_LEG;
        }
        return side < 0.0 ? RIGHT_LEG : LEFT_LEG;
    }

    public static CombatBodyZone weightedFallback() {
        int roll = ThreadLocalRandom.current().nextInt(100);
        if (roll < 8) return HEAD;
        if (roll < 45) return TORSO;
        if (roll < 61) return LEFT_ARM;
        if (roll < 77) return RIGHT_ARM;
        if (roll < 88) return LEFT_LEG;
        return RIGHT_LEG;
    }
}
