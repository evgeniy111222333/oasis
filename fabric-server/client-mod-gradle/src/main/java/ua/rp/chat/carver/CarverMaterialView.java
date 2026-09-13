package ua.rp.chat.carver;

/**
 * Human-facing inspection readout for a carvable block: the display name, the material class
 * and the derived physical properties the inspection panel shows. A pure table over material
 * class and vanilla hardness, so the panel never has to touch the world or the registry.
 *
 * <p>Mirror contract: client-only helper, no server copy exists by design.</p>
 */
public final class CarverMaterialView {
    public record Readout(String name, CarverWorkAnim.Material material, float hardness,
                          double workability, double brittleness, double conductivity) {
    }

    public static Readout of(String blockId, float hardness) {
        CarverWorkAnim.Material material = CarverWorkAnim.classify(blockId);
        float hard = Math.max(0.0f, hardness);
        // Ease of cutting: soft stock carves readily, obsidian resists.
        double workability = clamp01(1.0 / (1.0 + hard * 0.18));
        double brittleness = switch (material) {
            case GLASS, ICE -> 0.90;
            case METAL -> 0.55;
            case STONE -> 0.40;
            case WOOD -> 0.30;
            default -> 0.35;
        };
        double conductivity = switch (material) {
            case METAL -> 0.92;
            case ICE, GLASS -> 0.60;
            case STONE -> 0.45;
            case WOOD -> 0.12;
            default -> 0.25;
        };
        return new Readout(shortName(blockId), material, hard, workability, brittleness, conductivity);
    }

    /** Short class label used by the panel. */
    public static String materialLabel(CarverWorkAnim.Material material) {
        return switch (material) {
            case STONE -> "Камень";
            case WOOD -> "Дерево";
            case METAL -> "Металл";
            case ICE -> "Лёд";
            case GLASS -> "Стекло";
            case CLOTH -> "Ткань";
            default -> "Прочее";
        };
    }

    private static String shortName(String blockId) {
        if (blockId == null || blockId.isBlank()) return "Неизвестно";
        String id = blockId;
        int colon = id.indexOf(':');
        if (colon >= 0) id = id.substring(colon + 1);
        int bracket = id.indexOf('[');
        if (bracket >= 0) id = id.substring(0, bracket);
        id = id.replace('_', ' ').trim();
        if (id.isEmpty()) return "Неизвестно";
        return Character.toUpperCase(id.charAt(0)) + id.substring(1);
    }

    private static double clamp01(double value) {
        if (!(value > 0.0)) return 0.0;
        return value >= 1.0 ? 1.0 : value;
    }

    private CarverMaterialView() {
    }
}
