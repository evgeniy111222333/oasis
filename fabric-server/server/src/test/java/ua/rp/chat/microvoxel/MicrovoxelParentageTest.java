package ua.rp.chat.microvoxel;

/**
 * Guards the "parent block" resolver: every break-feedback path (particles, sounds,
 * mining speed, tool checks) must agree on which vanilla material a carved volume
 * reads as. Empty volumes resolve to nothing, never to a stone fallback.
 */
public final class MicrovoxelParentageTest {
    public static void main(String[] args) {
        // Vanilla registries stay frozen without an explicit bootstrap in plain JVM tests.
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        require(MicrovoxelParentage.dominantMaterial(null) == null,
                "Null volume must have no dominant material");
        require(MicrovoxelParentage.dominantMaterial(MicrovoxelVolume.empty()) == null,
                "Empty volume must have no dominant material");
        require(MicrovoxelParentage.parentState(null) == null
                        && MicrovoxelParentage.parentState(MicrovoxelVolume.empty()) == null
                        && MicrovoxelParentage.parentBlockId(MicrovoxelVolume.empty()) == null,
                "Empty volumes must resolve to no parent at all");

        MicrovoxelVolume wood = MicrovoxelVolume.full("minecraft:oak_planks");
        require("minecraft:oak_planks".equals(MicrovoxelParentage.dominantMaterial(wood)),
                "Uniform volume must resolve to its own material");
        require(MicrovoxelParentage.parentState(wood) != null
                        && MicrovoxelParentage.parentState(wood).is(
                                net.minecraft.world.level.block.Blocks.OAK_PLANKS),
                "Parent state must parse to the dominant block");
        require("minecraft:oak_planks".equals(MicrovoxelParentage.parentBlockId(wood)),
                "Parent id must be the dominant registry id");

        MicrovoxelVolume mixed = MicrovoxelVolume.full("minecraft:stone");
        for (int cell = 0; cell < 3000; cell++) {
            mixed.remove(cell);
            mixed.put(cell, "minecraft:oak_planks");
        }
        require("minecraft:oak_planks".equals(MicrovoxelParentage.dominantMaterial(mixed)),
                "Dominant material must be the most frequent cell");
        require("minecraft:oak_planks".equals(MicrovoxelParentage.parentBlockId(mixed)),
                "Parent id must follow the dominant material");

        MicrovoxelVolume carved = MicrovoxelVolume.full("minecraft:stone");
        for (int cell = 0; cell < MicrovoxelVolume.CELL_COUNT; cell++) carved.remove(cell);
        require(MicrovoxelParentage.dominantMaterial(carved) == null,
                "Fully cleared volume must have no dominant material");

        MicrovoxelVolume mixedTop = MicrovoxelVolume.full("minecraft:stone");
        for (int cell = 0; cell < 2000; cell++) {
            mixedTop.remove(cell);
            mixedTop.put(cell, "minecraft:oak_planks");
        }
        for (int cell = 2000; cell < 2500; cell++) {
            mixedTop.remove(cell);
            mixedTop.put(cell, "minecraft:dirt");
        }
        java.util.List<String> top = MicrovoxelParentage.topMaterials(mixedTop, 3);
        require(top.size() == 3
                        && top.get(0).equals("minecraft:oak_planks")
                        && top.get(1).equals("minecraft:stone")
                        && top.get(2).equals("minecraft:dirt"),
                "Top materials must rank by frequency, got " + top);
        require(MicrovoxelParentage.topMaterials(mixedTop, 1).size() == 1
                        && MicrovoxelParentage.topMaterials(mixedTop, 0).isEmpty()
                        && MicrovoxelParentage.topMaterials(null, 3).isEmpty(),
                "Top materials must honour the cap and empty inputs");
        System.out.println("MicrovoxelParentageTest passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException("MicrovoxelParentageTest: " + message);
    }
}
