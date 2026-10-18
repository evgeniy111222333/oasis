package ua.rp.chat;

import ua.rp.chat.microvoxel.MicrovoxelGreedyMesher;
import ua.rp.chat.microvoxel.MicrovoxelRaycaster;
import ua.rp.chat.microvoxel.MicrovoxelVolume;

import java.util.Arrays;
import java.util.List;

public final class MicrovoxelCoreTest {
    private MicrovoxelCoreTest() {
    }

    public static void main(String[] args) {
        MicrovoxelVolume full = MicrovoxelVolume.full("minecraft:red_wool");
        var fullMesh = MicrovoxelGreedyMesher.build(full, full::materialAt);
        require(fullMesh.size() == 6, "A solid 16x16x16 volume must collapse to six quads");
        require(full.collisionCuboids().size() == 1, "A solid volume must collapse to one collision cuboid");

        var eastCulled = MicrovoxelGreedyMesher.build(full, (x, y, z) ->
                x >= 16 ? 1 : full.materialAt(x, y, z));
        require(eastCulled.size() == 5, "A solid adjacent volume must cull the shared face");

        byte[] carvedCells = full.cellsCopy();
        carvedCells[MicrovoxelVolume.index(8, 8, 0)] = 0;
        MicrovoxelVolume carved = new MicrovoxelVolume(2, List.of("", "minecraft:red_wool"), carvedCells);
        var hit = MicrovoxelRaycaster.cast(
                8.5 / 16.0, 8.5 / 16.0, -1.0,
                0.0, 0.0, 1.0, 6.0,
                List.of(new MicrovoxelRaycaster.Entry(0, 0, 0, carved)));
        require(hit != null, "Ray must continue through a removed front cell");
        require(MicrovoxelVolume.z(hit.cell()) == 1, "Ray must hit the first occupied cell behind the opening");
        require(hit.face() == MicrovoxelGreedyMesher.Direction.NORTH, "Entry face must remain stable");

        byte[] checkerCells = new byte[MicrovoxelVolume.CELL_COUNT];
        for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            if (((x + y + z) & 1) == 0) checkerCells[MicrovoxelVolume.index(x, y, z)] = 1;
        }
        MicrovoxelVolume checker = new MicrovoxelVolume(1, List.of("", "minecraft:stone"), checkerCells);
        require(checker.collisionCuboids().size() == 2048, "Disconnected checker voxels must remain separate colliders");
        require(MicrovoxelGreedyMesher.build(checker, checker::materialAt).size() == 12288,
                "Worst-case checker mesh must emit only visible faces and stay deterministically bounded");

        byte[] invalid = new byte[MicrovoxelVolume.CELL_COUNT];
        Arrays.fill(invalid, (byte) 2);
        boolean rejected = false;
        try {
            new MicrovoxelVolume(1, List.of("", "minecraft:stone"), invalid);
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        require(rejected, "Invalid palette indices must be rejected before rendering");
        boolean duplicateRejected = false;
        try {
            new MicrovoxelVolume(1, List.of("", "minecraft:stone", "minecraft:stone"),
                    new byte[MicrovoxelVolume.CELL_COUNT]);
        } catch (IllegalArgumentException expected) {
            duplicateRejected = true;
        }
        require(duplicateRejected, "Duplicate palette materials must be rejected before caching");
        System.out.println("MicrovoxelCoreTest passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
