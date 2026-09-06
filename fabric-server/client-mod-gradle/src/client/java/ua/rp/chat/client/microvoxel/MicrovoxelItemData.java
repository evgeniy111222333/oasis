package ua.rp.chat.client.microvoxel;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import ua.rp.chat.microvoxel.MicrovoxelPortableVolume;
import ua.rp.chat.microvoxel.MicrovoxelVolume;

import java.io.IOException;

/** Single parser for portable shapes and measured material remainders. */
public final class MicrovoxelItemData {
    public static final String VOLUME_TAG = "microvoxel_volume";
    public static final String PARENT_MATERIAL_TAG = "parent_material";
    public static final String UNITS_USED_TAG = "microvoxel_units_used";
    public static final String CONSUMED_MATERIAL_TAG = "microvoxel_consumed_material";
    public static final String RECLAIMED_UNITS_TAG = "microvoxel_reclaimed_units";
    public static final String RECLAIMED_MATERIAL_TAG = "microvoxel_reclaimed_material";

    private MicrovoxelItemData() {
    }

    public static Parsed parse(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        CustomData custom = stack.get(DataComponents.CUSTOM_DATA);
        if (custom == null) return null;
        CompoundTag tag = custom.copyTag();

        byte[] encoded = tag.getByteArray(VOLUME_TAG).orElse(null);
        if (encoded != null) {
            try {
                MicrovoxelVolume volume = MicrovoxelPortableVolume.decode(encoded);
                String parent = tag.getStringOr(PARENT_MATERIAL_TAG, firstMaterial(volume));
                return new Parsed(volume, parent, Kind.CARVED, encoded);
            } catch (IOException ignored) {
                return null;
            }
        }

        int used = Math.max(0, Math.min(MicrovoxelVolume.CELL_COUNT,
                tag.getIntOr(UNITS_USED_TAG, 0)));
        String consumed = tag.getStringOr(CONSUMED_MATERIAL_TAG, "");
        if (used > 0 && used < MicrovoxelVolume.CELL_COUNT && !consumed.isBlank()) {
            int remaining = MicrovoxelVolume.CELL_COUNT - used;
            return new Parsed(MicrovoxelPortableVolume.packedRemainder(consumed, remaining),
                    consumed, Kind.REMAINDER, null);
        }

        int reclaimed = Math.max(0, Math.min(MicrovoxelVolume.CELL_COUNT,
                tag.getIntOr(RECLAIMED_UNITS_TAG, 0)));
        String reclaimedMaterial = tag.getStringOr(RECLAIMED_MATERIAL_TAG, "");
        if (reclaimed > 0 && reclaimed < MicrovoxelVolume.CELL_COUNT
                && !reclaimedMaterial.isBlank()) {
            return new Parsed(MicrovoxelPortableVolume.packedRemainder(
                    reclaimedMaterial, reclaimed), reclaimedMaterial, Kind.RECLAIMED, null);
        }
        return null;
    }

    private static String firstMaterial(MicrovoxelVolume volume) {
        for (int cell = 0; cell < MicrovoxelVolume.CELL_COUNT; cell++) {
            if (volume.occupied(cell)) return volume.material(cell);
        }
        return "";
    }

    public enum Kind {
        CARVED,
        REMAINDER,
        RECLAIMED
    }

    public record Parsed(MicrovoxelVolume volume, String parentMaterial, Kind kind,
                         byte[] portableBytes) {
        public Parsed {
            portableBytes = portableBytes == null ? null : portableBytes.clone();
        }

        @Override
        public byte[] portableBytes() {
            return portableBytes == null ? null : portableBytes.clone();
        }
    }
}
