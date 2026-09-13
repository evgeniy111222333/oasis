package ua.rp.chat.microvoxel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Client-side material palette ("9 plaques") behind the radial menu's Material sector.
 *
 * <p>A full block is exactly {@link #UNITS_PER_BLOCK} microvoxel units (16³). A voxel reclaimed by
 * breaking is a <em>fragment</em> that remembers its material and how many units it holds. The
 * palette therefore aggregates, per material, the exact number of usable units:</p>
 *
 * <pre>
 *   units(material) = Σ fragmentUnits × stackCount  +  Σ blockCount × 4096 − usedUnits
 * </pre>
 *
 * <p>Fragments come first (they place with no conversion); any block stacks of the same material
 * are folded into that same entry and are conceptually auto-converted on demand, so the player
 * sees one number (e.g. 1 fragment + 31 blocks ⇒ 126 977). Materials with only plain/converted
 * blocks fill the remaining plaques, up to {@link #MAX_ENTRIES}.</p>
 *
 * <p>Pure: no Minecraft classes, fully unit-testable. Computed on the <em>client</em> so the server
 * never scans inventories just to draw a menu.</p>
 */
public final class MicrovoxelMaterialPalette {
    /** Palette capacity (matches the radial sub-ring). */
    public static final int MAX_ENTRIES = 9;
    /** Microvoxel units in one full block (one cell = one unit for display). */
    public static final int UNITS_PER_BLOCK = MicrovoxelVolume.CELL_COUNT;
    /** Sub-cell units in one cell, matching the server ledger granularity. */
    public static final int SUB_UNITS_PER_CELL = MicrovoxelShape.SUB_COUNT;

    /** Converts a server ledger amount (sub-cells) into whole display cells (rounded up). */
    public static int toCells(int subUnits) {
        return (Math.max(0, subUnits) + SUB_UNITS_PER_CELL - 1) / SUB_UNITS_PER_CELL;
    }

    // Item custom-data tags shared with the server-side economy.
    public static final String TAG_RECLAIMED_UNITS = "microvoxel_reclaimed_units";
    public static final String TAG_RECLAIMED_MATERIAL = "microvoxel_reclaimed_material";
    public static final String TAG_UNITS_USED = "microvoxel_units_used";
    public static final String TAG_CONSUMED_MATERIAL = "microvoxel_consumed_material";

    /**
     * One inventory stack reduced to its material contribution.
     *
     * @param material       the block-state string, e.g. {@code minecraft:dirt}
     * @param count          the stack size
     * @param reclaimedUnits when &gt; 0 this is a fragment stack holding that many units per item
     * @param usedUnits      for a converted block stack, how many of its 4096 units were drawn
     */
    public record Stack(String material, int count, int reclaimedUnits, int usedUnits) {
        public Stack {
            if (material == null || material.isBlank()) {
                throw new IllegalArgumentException("material cannot be blank");
            }
            count = Math.max(1, count);
            reclaimedUnits = Math.max(0, reclaimedUnits);
            usedUnits = Math.max(0, usedUnits);
        }

        public boolean fragment() {
            return reclaimedUnits > 0;
        }

        /** Usable microvoxel units this stack contributes (never negative). */
        public long units() {
            if (reclaimedUnits > 0) {
                return (long) reclaimedUnits * count;
            }
            return Math.max(0L, (long) count * UNITS_PER_BLOCK - usedUnits);
        }
    }

    /** One palette plaque: a material and its exact usable microvoxel units. */
    public record Entry(String material, long units, boolean fromFragment) {
    }

    private MicrovoxelMaterialPalette() {
    }

    /**
     * Aggregates a classified inventory snapshot into up to {@link #MAX_ENTRIES} plaques.
     * Fragment-backed materials are listed first (in first-seen order), then block-only materials.
     */
    public static List<Entry> compose(List<Stack> stacks) {
        if (stacks == null || stacks.isEmpty()) return List.of();

        // LinkedHashMap keeps first-seen order so the hotbar leads the palette.
        Map<String, long[]> totals = new LinkedHashMap<>(); // material -> [units, fragmentFlag]
        for (Stack stack : stacks) {
            long[] aggregate = totals.computeIfAbsent(stack.material(), key -> new long[]{0L, 0L});
            aggregate[0] += stack.units();
            if (stack.fragment()) aggregate[1] = 1L;
        }

        List<Entry> fragments = new ArrayList<>();
        List<Entry> blocks = new ArrayList<>();
        for (Map.Entry<String, long[]> aggregate : totals.entrySet()) {
            long units = aggregate.getValue()[0];
            if (units <= 0) continue;
            Entry entry = new Entry(aggregate.getKey(), units, aggregate.getValue()[1] == 1L);
            if (entry.fromFragment()) fragments.add(entry);
            else blocks.add(entry);
        }

        List<Entry> result = new ArrayList<>(MAX_ENTRIES);
        for (Entry entry : fragments) {
            if (result.size() >= MAX_ENTRIES) break;
            result.add(entry);
        }
        for (Entry entry : blocks) {
            if (result.size() >= MAX_ENTRIES) break;
            result.add(entry);
        }
        return List.copyOf(result);
    }
}
