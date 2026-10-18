package ua.rp.chat.microvoxel;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SimpleWaterloggedBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.material.PushReaction;

/**
 * Registry contract shared verbatim by the paired server and client mods.
 *
 * <p>Mirror contract: this file is duplicated exactly in the client module. The marker block id,
 * light and sound-profile properties must stay identical on both sides, otherwise projection and
 * section rendering diverge. {@code verifyMicrovoxelNativeMarker} fails the build on divergence.</p>
 */
public final class MicrovoxelBlocks {
    public static final Identifier MARKER_ID =
            Identifier.fromNamespaceAndPath("rpchat", "microvoxel_marker");
    public static final ResourceKey<Block> MARKER_KEY =
            ResourceKey.create(Registries.BLOCK, MARKER_ID);
    public static final IntegerProperty LIGHT_LEVEL = BlockStateProperties.LEVEL;
    public static final IntegerProperty SOUND_PROFILE =
            IntegerProperty.create("sound_profile", 0, 15);
    public static final Block MARKER = new MarkerBlock(
            BlockBehaviour.Properties.of()
                    .setId(MARKER_KEY)
                    .noCollision()
                    .noOcclusion()
                    .dynamicShape()
                    .strength(-1.0f, 3_600_000.0f)
                    .noLootTable()
                    .pushReaction(PushReaction.BLOCK)
                    .lightLevel(state -> state.getValue(LIGHT_LEVEL)));

    private static boolean registered;

    private MicrovoxelBlocks() {
    }

    public static synchronized void register() {
        if (registered) return;
        if (!BuiltInRegistries.BLOCK.containsKey(MARKER_ID)) {
            Registry.register(BuiltInRegistries.BLOCK, MARKER_KEY, MARKER);
        }
        registered = true;
    }

    public static boolean isMarker(BlockState state) {
        return state != null && state.is(MARKER);
    }

    public static BlockState markerState(int lightLevel) {
        return markerState(lightLevel, 0);
    }

    public static BlockState markerState(int lightLevel, int soundProfile) {
        return markerState(lightLevel, soundProfile, false);
    }

    /**
     * Full marker state including the fluid flag. Waterlogged markers read as vanilla water
     * through the standard fluid chain (section storage, physics, rendering, sounds), while
     * the voxel geometry and per-cell levels stay ours. Vanilla flow can never set this flag
     * (the marker is not replaceable by fluids); only explicit fills and the fluid sim do.
     */
    public static BlockState markerState(int lightLevel, int soundProfile, boolean waterlogged) {
        return MARKER.defaultBlockState().setValue(LIGHT_LEVEL,
                Math.max(0, Math.min(15, lightLevel))).setValue(SOUND_PROFILE,
                Math.max(0, Math.min(15, soundProfile))).setValue(
                BlockStateProperties.WATERLOGGED, waterlogged);
    }

    public static int soundProfile(BlockState parent) {
        return MicrovoxelMaterialProfile.soundProfile(parent);
    }

    private static final class MarkerBlock extends Block implements SimpleWaterloggedBlock {
        private MarkerBlock(BlockBehaviour.Properties properties) {
            super(properties);
            registerDefaultState(stateDefinition.any()
                    .setValue(LIGHT_LEVEL, 0).setValue(SOUND_PROFILE, 0)
                    .setValue(BlockStateProperties.WATERLOGGED, false));
        }

        @Override
        protected SoundType getSoundType(BlockState state) {
            return MicrovoxelMaterialProfile.soundType(state.getValue(SOUND_PROFILE));
        }

        @Override
        protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
            builder.add(LIGHT_LEVEL, SOUND_PROFILE, BlockStateProperties.WATERLOGGED);
        }

        @Override
        protected FluidState getFluidState(BlockState state) {
            return state.getValue(BlockStateProperties.WATERLOGGED)
                    ? Fluids.WATER.getSource(false)
                    : super.getFluidState(state);
        }

        /**
         * Vanilla may never fill the marker on its own (dispensers, flow): every fill goes
         * through the explicit bucket/sim path so voxel levels and the flag never desync.
         * Pickup (scoop, sponge) stays vanilla via the interface defaults.
         */
        @Override
        public boolean canPlaceLiquid(net.minecraft.world.entity.LivingEntity entity,
                                      net.minecraft.world.level.BlockGetter level,
                                      BlockPos pos, BlockState state, Fluid fluid) {
            return false;
        }
    }
}
