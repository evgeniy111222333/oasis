package ua.rp.chat.client.microvoxel;

import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import ua.rp.chat.microvoxel.MicrovoxelMaterialPalette;

import java.util.ArrayList;
import java.util.List;

/**
 * Client-side builder for the radial menu's material palette.
 *
 * <p>Scans the local player's inventory (hotbar first, then the main grid, then the off-hand) and
 * reduces every eligible block stack to a {@link MicrovoxelMaterialPalette.Stack}: a fragment stack
 * carries its reclaimed units, a converted block stack its used units, a plain block neither. The
 * pure {@link MicrovoxelMaterialPalette} then aggregates the exact unit totals (fragments first).
 * Everything runs on the client so the server never scans inventories just to draw a menu.</p>
 */
public final class MicrovoxelMaterialPaletteClient {
    private MicrovoxelMaterialPaletteClient() {
    }

    /** Palette entries for the local player, or an empty list when no level/player is loaded. */
    public static List<MicrovoxelMaterialPalette.Entry> entries() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.player == null) return List.of();
        return MicrovoxelMaterialPalette.compose(scan(minecraft.player.getInventory(), minecraft.player));
    }

    private static List<MicrovoxelMaterialPalette.Stack> scan(Inventory inventory,
                                                              net.minecraft.world.entity.player.Player player) {
        List<MicrovoxelMaterialPalette.Stack> stacks = new ArrayList<>();
        int size = inventory.getContainerSize();
        // Hotbar (0..8) first so the player's curated bar leads the palette, then the main grid.
        for (int slot = 0; slot < 9 && slot < size; slot++) {
            addStack(stacks, inventory.getItem(slot));
        }
        for (int slot = 9; slot < size; slot++) {
            addStack(stacks, inventory.getItem(slot));
        }
        addStack(stacks, player.getOffhandItem());
        return stacks;
    }

    private static void addStack(List<MicrovoxelMaterialPalette.Stack> stacks, ItemStack stack) {
        if (stack == null || stack.isEmpty()) return;
        if (!(stack.getItem() instanceof BlockItem blockItem)) return;

        CompoundTag tag = customTag(stack);
        String reclaimedMaterial = tag.getStringOr(MicrovoxelMaterialPalette.TAG_RECLAIMED_MATERIAL, "");
        int reclaimedUnits = tag.getIntOr(MicrovoxelMaterialPalette.TAG_RECLAIMED_UNITS, 0);
        if (!reclaimedMaterial.isBlank() && reclaimedUnits > 0) {
            stacks.add(new MicrovoxelMaterialPalette.Stack(
                    reclaimedMaterial, stack.getCount(), reclaimedUnits, 0));
            return;
        }

        String consumedMaterial = tag.getStringOr(MicrovoxelMaterialPalette.TAG_CONSUMED_MATERIAL, "");
        int usedUnits = tag.getIntOr(MicrovoxelMaterialPalette.TAG_UNITS_USED, 0);
        String material = consumedMaterial.isBlank()
                ? blockStateString(blockItem.getBlock().defaultBlockState())
                : consumedMaterial;
        if (material == null || material.isBlank()) return;
        stacks.add(new MicrovoxelMaterialPalette.Stack(material, stack.getCount(), 0, usedUnits));
    }

    /** Palette as a JSON array for the web UI: material id, display name, exact units, fragment flag. */
    public static String toJson() {
        StringBuilder json = new StringBuilder("[");
        boolean first = true;
        for (MicrovoxelMaterialPalette.Entry entry : entries()) {
            if (!first) json.append(',');
            first = false;
            json.append("{\"id\":\"").append(escape(entry.material())).append('"')
                    .append(",\"name\":\"").append(escape(displayName(entry.material()))).append('"')
                    .append(",\"units\":").append(entry.units())
                    .append(",\"fragment\":").append(entry.fromFragment())
                    .append('}');
        }
        return json.append(']').toString();
    }

    private static String displayName(String material) {
        int bracket = material.indexOf('[');
        String blockId = bracket < 0 ? material : material.substring(0, bracket);
        try {
            net.minecraft.resources.Identifier id = net.minecraft.resources.Identifier.parse(blockId);
            var reference = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(id);
            if (reference.isPresent()) {
                net.minecraft.world.item.Item item = reference.get().value().asItem();
                if (item != net.minecraft.world.item.Items.AIR) {
                    return new ItemStack(item).getHoverName().getString();
                }
            }
        } catch (RuntimeException ignored) {
            // fall through to the raw id
        }
        return blockId;
    }

    private static String escape(String value) {
        StringBuilder out = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        return out.toString();
    }

    private static CompoundTag customTag(ItemStack stack) {
        net.minecraft.world.item.component.CustomData custom = stack.get(DataComponents.CUSTOM_DATA);
        return custom == null ? new CompoundTag() : custom.copyTag();
    }

    /** Canonical material string, matching the server's block-state form (registry key + properties). */
    private static String blockStateString(BlockState state) {
        StringBuilder result = new StringBuilder(
                net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
        var properties = state.getProperties();
        if (!properties.isEmpty()) {
            result.append('[');
            boolean first = true;
            for (net.minecraft.world.level.block.state.properties.Property<?> property : properties) {
                if (!first) result.append(',');
                first = false;
                result.append(property.getName()).append('=').append(propertyValue(state, property));
            }
            result.append(']');
        }
        return result.toString();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static String propertyValue(BlockState state,
                                        net.minecraft.world.level.block.state.properties.Property property) {
        Comparable value = state.getValue(property);
        return property.getName(value);
    }
}
