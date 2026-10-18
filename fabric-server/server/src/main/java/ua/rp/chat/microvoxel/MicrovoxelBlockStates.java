package ua.rp.chat.microvoxel;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.Iterator;

/**
 * Lossless BlockState &lt;-&gt; string codec shared by all microvoxel modules. The wire format is
 * {@code minecraft:oak_planks[axis=x,...]} and must round-trip exactly; anything unparsable
 * degrades to stone rather than throwing.
 */
public final class MicrovoxelBlockStates {
    private MicrovoxelBlockStates() {
    }

    public static BlockState parseBlockState(String stateStr) {
        if (stateStr == null) return Blocks.STONE.defaultBlockState();
        try {
            if (!stateStr.contains("[")) {
                net.minecraft.resources.Identifier loc = net.minecraft.resources.Identifier.tryParse(stateStr);
                if (loc != null) {
                    var block = BuiltInRegistries.BLOCK.get(loc).map(net.minecraft.core.Holder.Reference::value).orElse(null);
                    if (block != null) {
                        return block.defaultBlockState();
                    }
                }
            } else {
                int brace = stateStr.indexOf('[');
                String blockName = stateStr.substring(0, brace);
                net.minecraft.resources.Identifier loc = net.minecraft.resources.Identifier.tryParse(blockName);
                if (loc != null) {
                    var block = BuiltInRegistries.BLOCK.get(loc).map(net.minecraft.core.Holder.Reference::value).orElse(null);
                    if (block != null) {
                        BlockState state = block.defaultBlockState();
                        String propsStr = stateStr.substring(brace + 1, stateStr.length() - 1);
                        for (String prop : propsStr.split(",")) {
                            String[] kv = prop.split("=");
                            if (kv.length == 2) {
                                String key = kv[0].trim();
                                String val = kv[1].trim();
                                for (var p : state.getProperties()) {
                                    if (p.getName().equals(key)) {
                                        state = setPropertyHelper(state, p, val);
                                    }
                                }
                            }
                        }
                        return state;
                    }
                }
            }
        } catch (Exception parseFailure) {
            // Fall through to the safe stone default so one corrupt palette entry
            // can never break mining speed, collision or rendering lookups.
        }
        return Blocks.STONE.defaultBlockState();
    }

    public static String getBlockStateString(BlockState state) {
        StringBuilder sb = new StringBuilder();
        sb.append(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
        var props = state.getProperties();
        if (!props.isEmpty()) {
            sb.append("[");
            Iterator<Property<?>> iter = props.iterator();
            while (iter.hasNext()) {
                var prop = iter.next();
                sb.append(prop.getName()).append("=").append(getPropertyValueName(state, prop));
                if (iter.hasNext()) sb.append(",");
            }
            sb.append("]");
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static <T extends Comparable<T>> BlockState setPropertyHelper(
            BlockState state, Property<T> property, String value) {
        var opt = property.getValue(value);
        return opt.isPresent() ? state.setValue(property, opt.get()) : state;
    }

    @SuppressWarnings("unchecked")
    private static <T extends Comparable<T>> String getPropertyValueName(
            BlockState state, Property<T> prop) {
        return prop.getName(state.getValue(prop));
    }
}