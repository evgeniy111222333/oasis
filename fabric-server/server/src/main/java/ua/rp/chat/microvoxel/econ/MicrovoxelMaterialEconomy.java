package ua.rp.chat.microvoxel.econ;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import ua.rp.chat.microvoxel.MicrovoxelBlockStates;
import ua.rp.chat.microvoxel.MicrovoxelRuntime;
import ua.rp.chat.microvoxel.MicrovoxelVolume;
import ua.rp.chat.microvoxel.edit.MicrovoxelEligibility;

/**
 * Survival material accounting. Every full block carries 4096 microvoxel units; drawn cells are
 * tracked as {@code microvoxel_units_used} on the consumption stack, and removed cells are repaid
 * either into the partially consumed stack or as tagged reclaimed fragments. Creative is exempt
 * from the ledger. All mutations go through the player inventory API so saving and client sync
 * stay vanilla-consistent.
 */
public final class MicrovoxelMaterialEconomy {
    private static final String MATERIAL_UNITS_USED_TAG = "microvoxel_units_used";
    private static final String CONSUMED_MATERIAL_TAG = "microvoxel_consumed_material";
    private static final String RECLAIMED_UNITS_TAG = "microvoxel_reclaimed_units";
    private static final String RECLAIMED_MATERIAL_TAG = "microvoxel_reclaimed_material";

    private final MicrovoxelRuntime runtime;

    public MicrovoxelMaterialEconomy(MicrovoxelRuntime runtime) {
        this.runtime = runtime;
    }

    public SelectedMaterial selectedMaterial(ServerPlayer player) {
        ItemStack main = player.getMainHandItem();
        if (isValidFullBlockItem(main)) {
            return new SelectedMaterial(materialStateFromStack(main), main, InteractionHand.MAIN_HAND);
        }
        ItemStack off = player.getOffhandItem();
        if (isValidFullBlockItem(off)) {
            return new SelectedMaterial(materialStateFromStack(off), off, InteractionHand.OFF_HAND);
        }
        return null;
    }

    public int availableMaterialUnits(ServerPlayer player, SelectedMaterial selected) {
        if (player.gameMode.getGameModeForPlayer() == GameType.CREATIVE) return Integer.MAX_VALUE;
        if (selected == null || selected.stack().isEmpty()) return 0;
        ItemStack stack = selected.stack();
        net.minecraft.world.item.component.CustomData custom = stack.get(DataComponents.CUSTOM_DATA);
        CompoundTag tag = custom == null ? new CompoundTag() : custom.copyTag();
        int reclaimed = Math.max(0, tag.getIntOr(RECLAIMED_UNITS_TAG, 0));
        if (reclaimed > 0) {
            return Math.multiplyExact(reclaimed, stack.getCount());
        }
        int used = Math.max(0, Math.min(MicrovoxelVolume.CELL_COUNT - 1,
                tag.getIntOr(MATERIAL_UNITS_USED_TAG, 0)));
        return Math.multiplyExact(stack.getCount(), MicrovoxelVolume.CELL_COUNT) - used;
    }

    public void consumeMaterialUnit(ServerPlayer player, SelectedMaterial selected) {
        consumeMaterialUnits(player, selected, 1);
    }

    public void consumeMaterialUnits(ServerPlayer player, SelectedMaterial selected, int units) {
        if (player.gameMode.getGameModeForPlayer() == GameType.CREATIVE) return;
        if (units <= 0) return;
        if (availableMaterialUnits(player, selected) < units) {
            throw new IllegalStateException("Selected microvoxel material no longer has enough units");
        }
        ItemStack stack = selected.stack();
        if (stack.isEmpty()) throw new IllegalStateException("Selected microvoxel material disappeared");
        net.minecraft.world.item.component.CustomData custom = stack.get(DataComponents.CUSTOM_DATA);
        CompoundTag tag = custom == null ? new CompoundTag() : custom.copyTag();
        int reclaimed = tag.getIntOr(RECLAIMED_UNITS_TAG, 0);
        if (reclaimed > 0) {
            int remaining = reclaimed * stack.getCount() - units;
            int fullFragments = remaining / reclaimed;
            int remainder = remaining % reclaimed;
            if (remainder == 0) {
                stack.setCount(fullFragments);
            } else {
                if (fullFragments == 0) {
                    stack.setCount(1);
                    tag.putInt(RECLAIMED_UNITS_TAG, remainder);
                    net.minecraft.world.item.component.CustomData.set(DataComponents.CUSTOM_DATA, stack, tag);
                } else {
                    stack.setCount(fullFragments);
                    ItemStack partial = stack.copy();
                    partial.setCount(1);
                    CompoundTag partialTag = tag.copy();
                    partialTag.putInt(RECLAIMED_UNITS_TAG, remainder);
                    net.minecraft.world.item.component.CustomData.set(
                            DataComponents.CUSTOM_DATA, partial, partialTag);
                    if (!player.getInventory().add(partial)) player.drop(partial, false);
                }
            }
            player.getInventory().setChanged();
            return;
        }
        int used = Math.max(0, Math.min(MicrovoxelVolume.CELL_COUNT - 1,
                tag.getIntOr(MATERIAL_UNITS_USED_TAG, 0))) + units;
        int consumedBlocks = used / MicrovoxelVolume.CELL_COUNT;
        used %= MicrovoxelVolume.CELL_COUNT;
        tag.putString(CONSUMED_MATERIAL_TAG, MicrovoxelBlockStates.getBlockStateString(selected.state()));
        stack.shrink(consumedBlocks);
        if (stack.isEmpty()) {
            player.getInventory().setChanged();
            return;
        }
        if (used == 0) {
            tag.remove(MATERIAL_UNITS_USED_TAG);
            tag.remove(CONSUMED_MATERIAL_TAG);
        } else {
            tag.putInt(MATERIAL_UNITS_USED_TAG, used);
        }
        net.minecraft.world.item.component.CustomData.set(DataComponents.CUSTOM_DATA, stack, tag);
        player.getInventory().setChanged();
    }

    public void refundMaterialUnit(ServerPlayer player, String material) {
        refundMaterialUnits(player, material, 1);
    }

    public void refundMaterialUnits(ServerPlayer player, String material, int count) {
        refundMaterialUnits(player, material, count, false);
    }

    /**
     * Material refund with an explicit creative rule. Carved matter (carver work,
     * whole-volume pickup) must not evaporate: fragments land in the inventory even
     * in creative, where they are worthless but visible. Mining refunds keep the
     * survival-only default.
     */
    public void refundMaterialUnits(ServerPlayer player, String material, int count,
                                    boolean evenCreative) {
        if (count <= 0) return;
        if (!evenCreative && player.gameMode.getGameModeForPlayer() == GameType.CREATIVE) return;
        BlockState state = MicrovoxelBlockStates.parseBlockState(material);
        Item item = state.getBlock().asItem();
        if (item == Items.AIR) return;
        var inventory = player.getInventory();
        int remaining = count;

        for (int slot = 0; slot < inventory.getContainerSize() && remaining > 0; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty() || stack.getItem() != item) continue;
            net.minecraft.world.item.component.CustomData custom = stack.get(DataComponents.CUSTOM_DATA);
            if (custom == null) continue;
            CompoundTag tag = custom.copyTag();
            int used = tag.getIntOr(MATERIAL_UNITS_USED_TAG, 0);
            if (used <= 0 || !material.equals(tag.getStringOr(CONSUMED_MATERIAL_TAG, ""))) continue;
            int returned = Math.min(used, remaining);
            used -= returned;
            remaining -= returned;
            if (used == 0) {
                tag.remove(MATERIAL_UNITS_USED_TAG);
                tag.remove(CONSUMED_MATERIAL_TAG);
            } else {
                tag.putInt(MATERIAL_UNITS_USED_TAG, used);
            }
            net.minecraft.world.item.component.CustomData.set(DataComponents.CUSTOM_DATA, stack, tag);
        }

        while (remaining > 0) {
            int units = Math.min(MicrovoxelVolume.CELL_COUNT, remaining);
            remaining -= units;
            ItemStack fragment = new ItemStack(item);
            if (units < MicrovoxelVolume.CELL_COUNT) {
                CompoundTag tag = new CompoundTag();
                tag.putInt(RECLAIMED_UNITS_TAG, units);
                tag.putString(RECLAIMED_MATERIAL_TAG, material);
                net.minecraft.world.item.component.CustomData.set(
                        DataComponents.CUSTOM_DATA, fragment, tag);
            }
            if (!inventory.add(fragment)) player.drop(fragment, false);
        }
        inventory.setChanged();
    }

    public boolean isPartiallyConsumedMaterial(ItemStack item) {
        if (item == null || item.isEmpty()) return false;
        net.minecraft.world.item.component.CustomData custom = item.get(DataComponents.CUSTOM_DATA);
        if (custom == null) return false;
        CompoundTag tag = custom.copyTag();
        return tag.getIntOr(MATERIAL_UNITS_USED_TAG, 0) > 0
                || tag.getIntOr(RECLAIMED_UNITS_TAG, 0) > 0;
    }

    private boolean isValidFullBlockItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        Item item = stack.getItem();
        if (item instanceof BlockItem bi) {
            BlockState state = bi.getBlock().defaultBlockState();
            return MicrovoxelEligibility.isEligibleMaterialState(state, BlockPos.ZERO, playerLevelDummy());
        }
        return false;
    }

    private BlockState getBlockFromItem(ItemStack stack) {
        if (stack.getItem() instanceof BlockItem bi) {
            return bi.getBlock().defaultBlockState();
        }
        return Blocks.STONE.defaultBlockState();
    }

    private BlockState materialStateFromStack(ItemStack stack) {
        net.minecraft.world.item.component.CustomData custom = stack.get(DataComponents.CUSTOM_DATA);
        if (custom != null) {
            CompoundTag tag = custom.copyTag();
            String reclaimed = tag.getStringOr(RECLAIMED_MATERIAL_TAG, "");
            if (!reclaimed.isBlank()) return MicrovoxelBlockStates.parseBlockState(reclaimed);
            String consumed = tag.getStringOr(CONSUMED_MATERIAL_TAG, "");
            if (!consumed.isBlank()) return MicrovoxelBlockStates.parseBlockState(consumed);
        }
        return getBlockFromItem(stack);
    }

    private Level playerLevelDummy() {
        for (ServerLevel level : runtime.server().getAllLevels()) return level;
        return null;
    }

    public record SelectedMaterial(BlockState state, ItemStack stack, InteractionHand hand) {
    }
}