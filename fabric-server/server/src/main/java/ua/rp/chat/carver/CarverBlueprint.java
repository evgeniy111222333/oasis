package ua.rp.chat.carver;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tradable draft blueprints living in scroll custom data. A blueprint freezes the
 * removal mask (and, since v2, the additive placement mask with its per-cell material)
 * plus authorship: any holder can open it onto any eligible block and reproduce the
 * same sculpt. Storage uses the vanilla custom-data component, so no custom registries
 * are involved and scrolls trade through inventories, drops and shops untouched.
 */
public final class CarverBlueprint {
    public static final String TAG_KEY = "carver_blueprint";
    private static final String MASK_KEY = "mask";
    private static final String PLACE_MASK_KEY = "place_mask";
    private static final String PLACE_KEYS_KEY = "place_keys";
    private static final String MATERIAL_KEY = "material";
    private static final String CELLS_KEY = "cells";
    private static final String AUTHOR_KEY = "author";
    private static final int MAX_BLUEPRINT_CELLS = 4096;
    private static final String PLACE_SEPARATOR = "\n";
    private static final String PLACE_PAIR = "=";

    private CarverBlueprint() {
    }

    public record Decoded(DraftMask mask, DraftMask placeMask,
                          Map<Integer, String> placeMaterials,
                          String materialId, int cells, String author) {
    }

    /** Legacy removal-only blueprint (kept for tests and older call sites). */
    public static CompoundTag encode(DraftMask mask, String materialId, String authorName) {
        return encode(mask, new DraftMask(), Map.of(), materialId, authorName);
    }

    /** Serializes a draft; both masks are stored verbatim (512 bytes each, exact plan). */
    public static CompoundTag encode(DraftMask mask, DraftMask placeMask,
                                     Map<Integer, String> placeMaterials,
                                     String materialId, String authorName) {
        CompoundTag root = new CompoundTag();
        CompoundTag blueprint = new CompoundTag();
        blueprint.putByteArray(MASK_KEY, mask.encode());
        if (placeMask != null && !placeMask.isEmpty()) {
            blueprint.putByteArray(PLACE_MASK_KEY, placeMask.encode());
            blueprint.putString(PLACE_KEYS_KEY, encodePlaceKeys(placeMaterials));
        }
        blueprint.putString(MATERIAL_KEY, materialId == null ? "" : materialId);
        blueprint.putInt(CELLS_KEY, mask.count());
        blueprint.putString(AUTHOR_KEY, authorName == null ? "" : authorName);
        root.put(TAG_KEY, blueprint);
        return root;
    }

    /** Reads and validates a blueprint tag; null when absent or malformed. */
    public static Decoded decode(CompoundTag root) {
        if (root == null) return null;
        java.util.Optional<CompoundTag> inner;
        try {
            inner = root.getCompound(TAG_KEY);
        } catch (RuntimeException invalid) {
            return null;
        }
        if (inner.isEmpty()) return null;
        CompoundTag blueprint = inner.get();
        DraftMask mask;
        try {
            java.util.Optional<byte[]> bytes = blueprint.getByteArray(MASK_KEY);
            if (bytes.isEmpty()) return null;
            mask = DraftMask.decode(bytes.get());
        } catch (RuntimeException invalid) {
            return null;
        }
        DraftMask placeMask = new DraftMask();
        Map<Integer, String> placeMaterials = new LinkedHashMap<>();
        try {
            java.util.Optional<byte[]> placeBytes = blueprint.getByteArray(PLACE_MASK_KEY);
            if (placeBytes.isPresent()) {
                placeMask = DraftMask.decode(placeBytes.get());
                placeMaterials = decodePlaceKeys(
                        blueprint.getString(PLACE_KEYS_KEY).orElse(""), placeMask);
            }
        } catch (RuntimeException invalid) {
            return null;
        }
        if (mask.isEmpty() && placeMask.isEmpty()) return null;
        if (mask.count() > MAX_BLUEPRINT_CELLS || placeMask.count() > MAX_BLUEPRINT_CELLS) return null;
        if (blueprint.getInt(CELLS_KEY).map(cells -> cells != mask.count()).orElse(true)) {
            return null;
        }
        String material = blueprint.getString(MATERIAL_KEY).orElse("");
        String author = blueprint.getString(AUTHOR_KEY).orElse("");
        return new Decoded(mask, placeMask, placeMaterials, material, mask.count(), author);
    }

    /** Extracts the blueprint stored in a scroll, or null for blank scrolls. */
    public static Decoded readScroll(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        CustomData custom = stack.get(DataComponents.CUSTOM_DATA);
        if (custom == null) return null;
        return decode(custom.copyTag());
    }

    /** Legacy removal-only scroll writer (kept for older call sites). */
    public static void writeScroll(ItemStack stack, DraftMask mask,
                                   String materialId, String authorName) {
        writeScroll(stack, mask, new DraftMask(), Map.of(), materialId, authorName);
    }

    /** Writes a draft into a scroll with a display name and lore lines. */
    public static void writeScroll(ItemStack stack, DraftMask mask, DraftMask placeMask,
                                   Map<Integer, String> placeMaterials,
                                   String materialId, String authorName) {
        CompoundTag root = encode(mask, placeMask, placeMaterials, materialId, authorName);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
        String shortMaterial = shortMaterial(materialId);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("Чертёж резчика"));
        stack.set(DataComponents.LORE, new ItemLore(List.of(
                Component.literal("Снято: " + mask.count()
                        + (placeMask == null || placeMask.isEmpty() ? "" : ", добавлено: " + placeMask.count())),
                Component.literal("Камень: " + shortMaterial),
                Component.literal("Автор: "
                        + (authorName == null || authorName.isEmpty() ? "—" : authorName)))));
    }

    private static String encodePlaceKeys(Map<Integer, String> materials) {
        if (materials == null || materials.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        for (Map.Entry<Integer, String> entry : new java.util.TreeMap<>(materials).entrySet()) {
            if (entry.getValue() == null || entry.getValue().isEmpty()) continue;
            if (!out.isEmpty()) out.append(PLACE_SEPARATOR);
            out.append(entry.getKey()).append(PLACE_PAIR).append(entry.getValue());
        }
        return out.toString();
    }

    private static Map<Integer, String> decodePlaceKeys(String encoded, DraftMask placeMask) {
        Map<Integer, String> materials = new LinkedHashMap<>();
        if (encoded == null || encoded.isEmpty()) {
            return materials;
        }
        for (String pair : encoded.split(PLACE_SEPARATOR)) {
            if (pair.isEmpty()) continue;
            int split = pair.indexOf(PLACE_PAIR);
            if (split <= 0) continue;
            try {
                int cell = Integer.parseInt(pair.substring(0, split));
                if (cell < 0 || cell >= DraftMask.CELL_COUNT || !placeMask.get(cell)) continue;
                materials.put(cell, pair.substring(split + 1));
            } catch (NumberFormatException ignored) {
                return new LinkedHashMap<>();
            }
        }
        return materials;
    }

    private static String shortMaterial(String materialId) {
        if (materialId == null || materialId.isEmpty()) return "—";
        int separator = materialId.indexOf(':');
        String path = separator >= 0 ? materialId.substring(separator + 1) : materialId;
        return path.replace('_', ' ');
    }
}
