package ua.rp.chat.carver;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Material character table for work sounds. Most blocks play their vanilla sound
 * type straight; three families get an instrumental overlay on top of the base kit:
 * shearing for fibrous blocks, axe-stripping for wood, inverted loudness balance
 * for soft earth that thuds instead of ringing.
 *
 * <p>Matching runs on registry id paths instead of tags on purpose: tags need loaded
 * datapacks, while id suffixes are stable, data-independent and unit-testable without
 * any Minecraft bootstrap.</p>
 */
public final class CarverSoundTable {
    public enum Kind { PLAIN, SNIP, STRIP, SOFT }

    private static final String[] SNIP_ENDINGS = {"_wool", "_leaves"};
    private static final String[] SNIP_EXACT =
            {"moss_block", "cobweb", "hay_block", "sculk_vein"};
    private static final String[] STRIP_ENDINGS =
            {"_log", "_stem", "_wood", "_hyphae", "_planks", "bamboo_block"};
    private static final String[] SOFT_ENDINGS =
            {"dirt", "mud", "clay", "sand", "gravel", "mycelium", "podzol",
             "farmland", "dirt_path", "soul_soil", "snow_block"};

    private CarverSoundTable() {
    }

    /** Classifies a vanilla block id like {@code minecraft:oak_log}. */
    public static Kind classify(String blockId) {
        if (blockId == null) return Kind.PLAIN;
        int separator = blockId.indexOf(':');
        String path = separator >= 0 ? blockId.substring(separator + 1) : blockId;
        for (String exact : SNIP_EXACT) {
            if (path.equals(exact)) return Kind.SNIP;
        }
        for (String ending : SNIP_ENDINGS) {
            if (path.endsWith(ending)) return Kind.SNIP;
        }
        for (String ending : STRIP_ENDINGS) {
            if (path.endsWith(ending) || path.equals(ending)) return Kind.STRIP;
        }
        for (String ending : SOFT_ENDINGS) {
            if (path.endsWith(ending) || path.equals(ending)) return Kind.SOFT;
        }
        return Kind.PLAIN;
    }

    /** Convenience overload resolving the live state to its registry id. */
    public static Kind classify(BlockState state) {
        if (state == null || state.getBlock() == null) return Kind.PLAIN;
        return classify(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
    }
}
