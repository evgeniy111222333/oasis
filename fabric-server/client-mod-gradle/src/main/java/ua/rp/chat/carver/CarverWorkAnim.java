package ua.rp.chat.carver;

import java.util.Locale;

/**
 * "Living master" carving performance model.
 *
 * <p>The work animation is not one pose repeated: it is a seeded rhythm (no two artisans
 * strike alike), three progress stages (rough removal, the body of the work, fine
 * detailing), fatigue that slows and slumps the artisan as stamina drops, a strike type
 * taken from the held chisel, and a material class that changes how heavy the blows read.
 * Pure and dependency-free, so the whole choreography is unit-testable away from Minecraft.</p>
 *
 * <p>Mirror contract: client-only helper, no server copy exists by design.</p>
 */
public final class CarverWorkAnim {
    public enum Material { STONE, WOOD, METAL, ICE, GLASS, CLOTH, GENERIC }

    public enum StrikeType { POINT_CHISEL, FLAT_MALLET }

    public enum Stage { ROUGH, MAIN, FINE }

    /** Scalar multipliers applied on top of the base working pose. */
    public record Pose(double amplitude, double lean, double twist, double dip, double nod,
                       double tempo, double shockGain, boolean twoHanded, Material material,
                       StrikeType type, Stage stage, double fatigue) {
    }

    /** Progress offset (in job fraction) where rough removal hands over to the main stage. */
    public static final double ROUGH_END = 0.34;
    /** Progress offset where the main stage hands over to fine detailing. */
    public static final double FINE_START = 0.80;

    private CarverWorkAnim() {
    }

    /** Coarse material class of a block id, driving particles, sound and blow weight. */
    public static Material classify(String blockId) {
        if (blockId == null || blockId.isBlank()) return Material.GENERIC;
        String id = blockId.toLowerCase(Locale.ROOT);
        int bracket = id.indexOf('[');
        if (bracket >= 0) id = id.substring(0, bracket);
        if (containsAny(id, "iron", "gold", "copper", "netherite", "metal", "anvil",
                "cauldron", "chain", "rail", "bell", "hopper", "minecart", "iron_bars")) {
            return Material.METAL;
        }
        if (containsAny(id, "log", "planks", "wood", "stripped", "bamboo", "_stem", "hyphae")) {
            return Material.WOOD;
        }
        if (containsAny(id, "ice", "snow", "frost")) return Material.ICE;
        if (containsAny(id, "glass", "_pane")) return Material.GLASS;
        if (containsAny(id, "wool", "carpet", "bed", "banner")) return Material.CLOTH;
        if (containsAny(id, "stone", "cobble", "deepslate", "granite", "diorite", "andesite",
                "sandstone", "terracotta", "concrete", "brick", "obsidian", "basalt", "tuff",
                "calcite", "dripstone", "ore", "prismarine", "quartz", "clay", "mud", "gravel",
                "dirt", "grass", "sand", "soul", "nether", "blackstone", "end_stone", "amethyst")) {
            return Material.STONE;
        }
        return Material.GENERIC;
    }

    /** Strike type from the off-hand chisel code (0 none, 1 flat, 2 point). */
    public static StrikeType strikeType(int chiselCode) {
        return chiselCode == 2 ? StrikeType.POINT_CHISEL : StrikeType.FLAT_MALLET;
    }

    /** Progress stage of a job, in job fraction. */
    public static Stage stage(double progress) {
        if (!(progress > 0.0)) return Stage.ROUGH;
        if (progress < ROUGH_END) return Stage.ROUGH;
        if (progress < FINE_START) return Stage.MAIN;
        return Stage.FINE;
    }

    /** Full pose multipliers for a stage, strike type, fatigue (0..1) and material. */
    public static Pose pose(Stage stage, StrikeType type, double fatigue, Material material) {
        double f = clamp01(fatigue);
        double materialAmplitude = switch (material) {
            case METAL -> 0.9;   // hard, ringing, a smaller bite per blow
            case ICE, GLASS -> 0.85;
            case WOOD -> 1.06;
            default -> 1.0;
        };
        double materialShock = switch (material) {
            case METAL -> 1.25;
            case ICE, GLASS -> 1.15;
            case WOOD -> 0.85;
            default -> 1.0;
        };
        double typeAmplitude = type == StrikeType.FLAT_MALLET ? 1.0 : 0.7;
        double stageAmplitude = switch (stage) {
            case ROUGH -> 1.12;
            case MAIN -> 1.0;
            case FINE -> 0.62;
        };
        double stageLean = switch (stage) {
            case ROUGH -> 1.15;
            case MAIN -> 1.0;
            case FINE -> 1.5;   // lean in over delicate work
        };
        double stageTempo = switch (stage) {
            case ROUGH -> 1.18;
            case MAIN -> 1.0;
            case FINE -> 0.78;
        };
        double amplitude = typeAmplitude * stageAmplitude * materialAmplitude * (1.0 + 0.15 * f);
        double lean = stageLean * (type == StrikeType.FLAT_MALLET ? 1.0 : 0.82) * (1.0 + 0.2 * f);
        double twist = 0.9 * stageLean;
        double dip = (type == StrikeType.FLAT_MALLET ? 1.0 : 0.7) * (1.0 + 0.25 * f);
        double nod = (type == StrikeType.FLAT_MALLET ? 1.0 : 0.6) * (1.0 + 0.3 * f);
        double tempo = stageTempo * (1.0 - 0.45 * f);
        boolean twoHanded = type == StrikeType.FLAT_MALLET && stage == Stage.ROUGH;
        return new Pose(amplitude, lean, twist, dip, nod, tempo, materialShock, twoHanded,
                material, type, stage, f);
    }

    /** True when the impact throws sparks (metals). */
    public static boolean sparks(Material material) {
        return material == Material.METAL;
    }

    /** True when the impact throws brittle shards instead of dust chips. */
    public static boolean shards(Material material) {
        return material == Material.ICE || material == Material.GLASS;
    }

    /** True when the impact throws soft splinters and a dull thud. */
    public static boolean splinters(Material material) {
        return material == Material.WOOD;
    }

    private static boolean containsAny(String haystack, String... needles) {
        for (String needle : needles) {
            if (haystack.contains(needle)) return true;
        }
        return false;
    }

    private static double clamp01(double value) {
        if (!(value > 0.0)) return 0.0;
        return value >= 1.0 ? 1.0 : value;
    }
}
