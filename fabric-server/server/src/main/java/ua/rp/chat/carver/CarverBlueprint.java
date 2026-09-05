package ua.rp.chat.carver;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;

import java.util.List;

/**
 * Tradable draft blueprints living in scroll custom data. A blueprint is the frozen
 * removal mask plus authorship: any holder can open it onto any eligible block and
 * carve the same shape. Storage uses the vanilla custom-data component, so no custom
 * registries are involved and scrolls trade through inventories, drops and shops
 * untouched.
 */
public final class CarverBlueprint {
    public static final String TAG_KEY = "carver_blueprint";
    private static final String MASK_KEY = "mask";
    private static final String MATERIAL_KEY = "material";
    private static final String CELLS_KEY = "cells";
    private static final String AUTHOR_KEY = "author";
    private static final int MAX_BLUEPRINT_CELLS = 4096;

    private CarverBlueprint() {
    }

    public record Decoded(DraftMask mask, String materialId, int cells, String author) {
    }

    /** Serializes a draft; the mask is stored verbatim (512 bytes, exact plan). */
    public static CompoundTag encode(DraftMask mask, String materialId, String authorName) {
        CompoundTag root = new CompoundTag();
        CompoundTag blueprint = new CompoundTag();
        blueprint.putByteArray(MASK_KEY, mask.encode());
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
        java.util.Optional<byte[]> bytes;
        try {
            bytes = blueprint.getByteArray(MASK_KEY);
        } catch (RuntimeException invalid) {
            return null;
        }
        if (bytes.isEmpty()) return null;
        DraftMask mask;
        try {
            mask = DraftMask.decode(bytes.get());
        } catch (RuntimeException invalid) {
            return null;
        }
        if (mask.isEmpty() || mask.count() > MAX_BLUEPRINT_CELLS) return null;
        if (blueprint.getInt(CELLS_KEY).map(cells -> cells != mask.count()).orElse(true)) {
            return null;
        }
        String material = blueprint.getString(MATERIAL_KEY).orElse("");
        String author = blueprint.getString(AUTHOR_KEY).orElse("");
        return new Decoded(mask, material, mask.count(), author);
    }

    /** Extracts the blueprint stored in a scroll, or null for blank scrolls. */
    public static Decoded readScroll(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        CustomData custom = stack.get(DataComponents.CUSTOM_DATA);
        if (custom == null) return null;
        return decode(custom.copyTag());
    }

    /** Writes a draft into a scroll with a display name and lore lines. */
    public static void writeScroll(ItemStack stack, DraftMask mask,
                                   String materialId, String authorName) {
        CompoundTag root = encode(mask, materialId, authorName);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
        String shortMaterial = shortMaterial(materialId);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("Чертёж резчика"));
        stack.set(DataComponents.LORE, new ItemLore(List.of(
                Component.literal("Узор: " + mask.count() + " вокселей"),
                Component.literal("Камень: " + shortMaterial),
                Component.literal("Автор: "
                        + (authorName == null || authorName.isEmpty() ? "—" : authorName)))));
    }

    private static String shortMaterial(String materialId) {
        if (materialId == null || materialId.isEmpty()) return "—";
        int separator = materialId.indexOf(':');
        String path = separator >= 0 ? materialId.substring(separator + 1) : materialId;
        return path.replace('_', ' ');
    }
}
