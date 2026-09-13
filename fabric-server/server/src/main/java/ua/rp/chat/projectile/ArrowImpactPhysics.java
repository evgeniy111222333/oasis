package ua.rp.chat.projectile;

/**
 * Deterministic, server-authoritative arrow penetration model. Values are game-tuned
 * energy units rather than claimed real-world joules; velocity remains the real
 * projectile velocity measured at the contact tick.
 */
public final class ArrowImpactPhysics {
    public enum Outcome {
        DEFLECTED,
        SHALLOW,
        LODGED,
        THROUGH
    }

    public record Input(double speed, double incidenceCosine, double armorResistance,
                        int zone, long seed) {
    }

    public record Result(Outcome outcome, double penetrationDepthBlocks,
                         double residualSpeed, double damageScale, double impactEnergy,
                         double spentEnergy, boolean boneContact) {
        public boolean embedded() {
            return outcome == Outcome.SHALLOW || outcome == Outcome.LODGED;
        }

        public boolean exits() {
            return outcome == Outcome.THROUGH;
        }

        public boolean acceptedContact() {
            return outcome != Outcome.DEFLECTED;
        }
    }

    private ArrowImpactPhysics() {
    }

    public static Result resolve(Input input) {
        double speed = clamp(input.speed, 0.0, 4.5);
        double incidence = clamp(input.incidenceCosine, 0.0, 1.0);
        double energy = 100.0 * square(speed / 3.0);
        double angularFactor = 0.18 + 0.82 * square(incidence);
        double effective = energy * angularFactor;
        double armor = clamp(input.armorResistance, 0.0, 180.0);
        double entryCost = 8.0 + armor;
        if (effective <= entryCost) {
            double scale = clamp(effective / Math.max(35.0, entryCost) * 0.30, 0.05, 0.30);
            return new Result(Outcome.DEFLECTED, 0.0, speed * 0.22, scale,
                    energy, Math.max(0.0, effective), false);
        }

        ZoneMaterial zone = ZoneMaterial.forOrdinal(input.zone);
        boolean bone = unit(input.seed ^ 0x9e3779b97f4a7c15L) < zone.boneChance;
        double tissueCost = zone.softTissueCost + (bone ? zone.boneCost : 0.0);
        double available = effective - entryCost;
        double exitCost = tissueCost + 10.0;
        double spent;
        Outcome outcome;
        double depth;
        double residualSpeed;

        if (available >= exitCost) {
            outcome = Outcome.THROUGH;
            spent = entryCost + exitCost;
            depth = zone.thickness + 0.025;
            double residualEnergy = Math.max(0.0, effective - spent);
            residualSpeed = speed * Math.sqrt(residualEnergy / Math.max(effective, 1.0));
        } else {
            double fraction = clamp(available / Math.max(tissueCost, 1.0), 0.0, 1.0);
            depth = zone.thickness * fraction;
            outcome = depth < 0.075 ? Outcome.SHALLOW : Outcome.LODGED;
            depth = clamp(depth, outcome == Outcome.SHALLOW ? 0.025 : 0.075, zone.thickness);
            spent = effective;
            residualSpeed = 0.0;
        }

        double damageScale = clamp(spent / 82.0, 0.28, 1.28);
        return new Result(outcome, depth, residualSpeed, damageScale, energy, spent, bone);
    }

    public static double armorResistance(String itemId) {
        if (itemId == null || itemId.isBlank() || itemId.endsWith(":air")) return 0.0;
        String id = itemId.toLowerCase(java.util.Locale.ROOT);
        if (id.contains("netherite")) return 110.0;
        if (id.contains("diamond")) return 90.0;
        if (id.contains("iron")) return 65.0;
        if (id.contains("chainmail")) return 45.0;
        if (id.contains("gold")) return 40.0;
        if (id.contains("leather")) return 28.0;
        return 18.0;
    }

    private enum ZoneMaterial {
        HEAD(0.50, 112.0, 52.0, 0.78),
        TORSO(0.25, 88.0, 48.0, 0.24),
        ARM(0.25, 46.0, 42.0, 0.34),
        LEG(0.25, 54.0, 48.0, 0.42);

        private final double thickness;
        private final double softTissueCost;
        private final double boneCost;
        private final double boneChance;

        ZoneMaterial(double thickness, double softTissueCost, double boneCost, double boneChance) {
            this.thickness = thickness;
            this.softTissueCost = softTissueCost;
            this.boneCost = boneCost;
            this.boneChance = boneChance;
        }

        private static ZoneMaterial forOrdinal(int zone) {
            return switch (zone) {
                case 0 -> HEAD;
                case 1 -> TORSO;
                case 2, 3 -> ARM;
                default -> LEG;
            };
        }
    }

    private static double unit(long value) {
        value ^= value >>> 30;
        value *= 0xbf58476d1ce4e5b9L;
        value ^= value >>> 27;
        value *= 0x94d049bb133111ebL;
        value ^= value >>> 31;
        return (value >>> 11) * 0x1.0p-53;
    }

    private static double square(double value) {
        return value * value;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
