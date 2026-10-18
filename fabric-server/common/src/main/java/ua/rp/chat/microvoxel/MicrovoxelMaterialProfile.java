package ua.rp.chat.microvoxel;

import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Compact marker-state profile for material behaviour that has no level/position argument.
 *
 * <p>Mirror contract: duplicated verbatim in the client module. Sound-profile ids feed the
 * marker blockstate on both sides, so any mapping change must be mirrored or footstep and
 * break sounds desync between server projection and client rendering.</p>
 */
public final class MicrovoxelMaterialProfile {
    private MicrovoxelMaterialProfile() {
    }

    public static int soundProfile(BlockState parent) {
        if (parent == null) return 0;
        SoundType sound = parent.getSoundType();
        if (sound == SoundType.WOOL) return 1;
        if (sound == SoundType.WOOD || sound == SoundType.BAMBOO_WOOD
                || sound == SoundType.NETHER_WOOD || sound == SoundType.CHERRY_WOOD) return 2;
        if (sound == SoundType.GRAVEL) return 3;
        if (sound == SoundType.GRASS || sound == SoundType.AZALEA_LEAVES
                || sound == SoundType.CHERRY_LEAVES) return 4;
        if (sound == SoundType.METAL || sound == SoundType.IRON
                || sound == SoundType.COPPER || sound == SoundType.NETHERITE_BLOCK) return 5;
        if (sound == SoundType.GLASS) return 6;
        if (sound == SoundType.SAND || sound == SoundType.SUSPICIOUS_SAND) return 7;
        if (sound == SoundType.SNOW || sound == SoundType.POWDER_SNOW) return 8;
        if (sound == SoundType.SLIME_BLOCK) return 9;
        if (sound == SoundType.HONEY_BLOCK) return 10;
        if (sound == SoundType.WET_GRASS || sound == SoundType.MOSS) return 11;
        if (sound == SoundType.NETHERRACK || sound == SoundType.NETHER_BRICKS) return 12;
        if (sound == SoundType.DEEPSLATE || sound == SoundType.DEEPSLATE_BRICKS
                || sound == SoundType.DEEPSLATE_TILES) return 13;
        if (sound == SoundType.MUD || sound == SoundType.MUD_BRICKS
                || sound == SoundType.PACKED_MUD) return 14;
        if (sound == SoundType.SPONGE || sound == SoundType.WET_SPONGE) return 15;
        return 0;
    }

    public static SoundType soundType(int profile) {
        return switch (profile) {
            case 1 -> SoundType.WOOL;
            case 2 -> SoundType.WOOD;
            case 3 -> SoundType.GRAVEL;
            case 4 -> SoundType.GRASS;
            case 5 -> SoundType.METAL;
            case 6 -> SoundType.GLASS;
            case 7 -> SoundType.SAND;
            case 8 -> SoundType.SNOW;
            case 9 -> SoundType.SLIME_BLOCK;
            case 10 -> SoundType.HONEY_BLOCK;
            case 11 -> SoundType.WET_GRASS;
            case 12 -> SoundType.NETHERRACK;
            case 13 -> SoundType.DEEPSLATE;
            case 14 -> SoundType.MUD;
            case 15 -> SoundType.SPONGE;
            default -> SoundType.STONE;
        };
    }
}
