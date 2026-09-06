package ua.rp.chat;

import ua.rp.chat.microvoxel.MicrovoxelAnchorRules;
import ua.rp.chat.microvoxel.MicrovoxelBrush;
import ua.rp.chat.microvoxel.MicrovoxelGreedyMesher;
import ua.rp.chat.microvoxel.MicrovoxelLodTiers;
import ua.rp.chat.microvoxel.MicrovoxelItemScale;
import ua.rp.chat.microvoxel.MicrovoxelPrediction;
import ua.rp.chat.microvoxel.MicrovoxelPortableVolume;
import ua.rp.chat.microvoxel.MicrovoxelRaycaster;
import ua.rp.chat.microvoxel.MicrovoxelRevision;
import ua.rp.chat.microvoxel.MicrovoxelVisualShape;
import ua.rp.chat.microvoxel.MicrovoxelVolume;
import net.minecraft.world.item.ItemDisplayContext;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.Arrays;
import java.util.List;

public final class MicrovoxelCoreTest {
    private MicrovoxelCoreTest() {
    }

    public static void main(String[] args) {
        verifyPredictionReplay();
        verifyCavityRaycast();
        verifyLodMeshing();
        verifyLoadStand();
        verifyLodTiers();
        verifyStrideFour();
        verifyLodPayoff();
        verifySeamCulling();
        verifyLightSealingMirror();
        verifyFluidCodecMirror();
        verifyVisualIdentityAndBounds();
        require(close(MicrovoxelItemScale.resultingHandFraction(
                                ItemDisplayContext.FIRST_PERSON_RIGHT_HAND), 0.60f)
                        && close(MicrovoxelItemScale.resultingHandFraction(
                                ItemDisplayContext.FIRST_PERSON_LEFT_HAND), 0.60f)
                        && close(MicrovoxelItemScale.resultingHandFraction(
                                ItemDisplayContext.THIRD_PERSON_RIGHT_HAND), 0.60f)
                        && close(MicrovoxelItemScale.resultingHandFraction(
                                ItemDisplayContext.THIRD_PERSON_LEFT_HAND), 0.60f),
                "Portable microvoxel workpieces must occupy 60% of a world block in either hand");
        verifyPortableItemPresentation();
        verifyDominantMaterial();
        verifyHologramCulling();
        require(MicrovoxelAnchorRules.renderable(true, false, false, false),
                "The native rpchat marker must remain visible after the server block update");
        require(MicrovoxelAnchorRules.renderable(false, true, false, false)
                        && MicrovoxelAnchorRules.renderable(false, false, true, false),
                "Legacy marker volumes must remain visible while their chunks migrate");
        require(MicrovoxelAnchorRules.renderable(false, false, false, true),
                "A pending authoritative removal must not flash the volume invisible");
        require(!MicrovoxelAnchorRules.renderable(false, false, false, false),
                "Ordinary blocks must never be rendered by the microvoxel pass");

        MicrovoxelVolume full = MicrovoxelVolume.full("minecraft:red_wool");
        int encodedBrush = MicrovoxelBrush.encode(
                MicrovoxelVolume.index(15, 8, 8), MicrovoxelBrush.SPHERE, 2);
        require(MicrovoxelBrush.cell(encodedBrush) == MicrovoxelVolume.index(15, 8, 8)
                        && MicrovoxelBrush.shape(encodedBrush) == MicrovoxelBrush.SPHERE
                        && MicrovoxelBrush.radius(encodedBrush) == 2,
                "Client brush decoding must be bit-identical to the server");
        require(MicrovoxelBrush.targets(-1, 5, 7, MicrovoxelVolume.index(15, 8, 8),
                        MicrovoxelBrush.SPHERE, 2, MicrovoxelBrush.Axis.X).size() == 33,
                "Client preview must use the authoritative sphere lattice");
        MicrovoxelVolume wrapping = new MicrovoxelVolume(Integer.MAX_VALUE,
                full.palette(), full.cellsCopy());
        wrapping.update(0, "");
        require(wrapping.revision() == 1
                        && MicrovoxelRevision.isImmediateNext(1, Integer.MAX_VALUE),
                "Client revision wrap must match the authoritative server");
        var fullMesh = MicrovoxelGreedyMesher.build(full, full::materialAt);
        require(fullMesh.size() == 6, "A solid 16x16x16 volume must collapse to six quads");
        require(full.collisionCuboids().size() == 1, "A solid volume must collapse to one collision cuboid");

        try {
            byte[] portable = portableBytes(carvedFixture());
            MicrovoxelVolume decoded = MicrovoxelPortableVolume.decode(portable);
            require(decoded.palette().equals(List.of("", "minecraft:red_wool"))
                            && Arrays.equals(decoded.cellsCopy(), carvedFixture().cellsCopy()),
                    "Portable item decoding must preserve the exact authored 16^3 geometry");
            byte[] trailing = Arrays.copyOf(portable, portable.length + 1);
            boolean trailingRejected = false;
            try {
                MicrovoxelPortableVolume.decode(trailing);
            } catch (java.io.IOException expected) {
                trailingRejected = true;
            }
            require(trailingRejected,
                    "Portable item decoding must reject trailing data before caching a mesh");
            MicrovoxelVolume halfMeasure = MicrovoxelPortableVolume.packedRemainder(
                    "minecraft:red_wool", 2048);
            require(occupiedCount(halfMeasure) == 2048
                            && MicrovoxelGreedyMesher.build(
                            halfMeasure, halfMeasure::materialAt).size() == 6,
                    "Measured material remainders must render as an exact compact fraction");
        } catch (java.io.IOException error) {
            throw new AssertionError("Portable volume fixture failed", error);
        }

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

        MicrovoxelRaycaster.Hit eastBoundary = new MicrovoxelRaycaster.Hit(
                new MicrovoxelRaycaster.Entry(-2, 5, 7, full),
                MicrovoxelVolume.index(15, 4, 9), MicrovoxelGreedyMesher.Direction.EAST, 1.0);
        MicrovoxelRaycaster.Target eastTarget = eastBoundary.adjacentTarget();
        require(eastTarget.x() == -1 && eastTarget.y() == 5 && eastTarget.z() == 7
                        && MicrovoxelVolume.x(eastTarget.cell()) == 0
                        && MicrovoxelVolume.y(eastTarget.cell()) == 4
                        && MicrovoxelVolume.z(eastTarget.cell()) == 9,
                "Client placement targets must wrap seamlessly across block boundaries");

        byte[] checkerCells = new byte[MicrovoxelVolume.CELL_COUNT];
        for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            if (((x + y + z) & 1) == 0) checkerCells[MicrovoxelVolume.index(x, y, z)] = 1;
        }
        MicrovoxelVolume checker = new MicrovoxelVolume(1, List.of("", "minecraft:stone"), checkerCells);
        require(checker.collisionCuboids().size() == 2048, "Disconnected checker voxels must remain separate colliders");
        require(checker.collisionPlan().backend() == MicrovoxelVolume.CollisionBackend.GRID,
                "Disconnected checker voxels must use the compact grid collision backend");
        require(checker.collisionPlan().xMask(0, 0) == 0x5555
                        && checker.collisionPlan().xMask(0, 1) == 0xAAAA,
                "Grid collision masks must preserve exact checkerboard occupancy");
        require(full.collisionPlan().backend() == MicrovoxelVolume.CollisionBackend.CUBOIDS,
                "Simple full volumes must keep the merged-cuboid fast path");

        MicrovoxelVolume palettePressure = MicrovoxelVolume.full("minecraft:stone");
        int paletteCell = MicrovoxelVolume.index(0, 0, 0);
        for (int material = 0; material < 30; material++) {
            palettePressure.update(paletteCell, "");
            palettePressure.update(paletteCell, "test:retired_material_" + material);
        }
        palettePressure.update(paletteCell, "");
        int revisionBeforeCompaction = palettePressure.revision();
        require(palettePressure.palette().size() == 32 && palettePressure.compactPalette(),
                "Client palette must compact unused historical materials at protocol pressure");
        require(palettePressure.palette().equals(List.of("", "minecraft:stone")),
                "Client compaction must retain stable material ordering");
        require(palettePressure.revision() == revisionBeforeCompaction,
                "Client palette compaction must not alter authoritative geometry revision");
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

        int firstRemoval = MicrovoxelVolume.index(1, 2, 3);
        int secondRemoval = MicrovoxelVolume.index(4, 5, 6);
        MicrovoxelVolume bothPredicted = MicrovoxelPrediction.replayRemovals(
                full, List.of(firstRemoval, secondRemoval));
        require(bothPredicted != null
                        && !bothPredicted.occupied(firstRemoval)
                        && !bothPredicted.occupied(secondRemoval),
                "Ordered pending edits must be predicted immediately");
        MicrovoxelVolume firstAcknowledged = full.copy();
        firstAcknowledged.update(firstRemoval, "");
        MicrovoxelVolume laterReplayed = MicrovoxelPrediction.replayRemovals(
                firstAcknowledged, List.of(secondRemoval));
        require(laterReplayed != null
                        && !laterReplayed.occupied(firstRemoval)
                        && !laterReplayed.occupied(secondRemoval),
                "Later edits must be replayed over an authoritative acknowledgement");
        MicrovoxelVolume rejectedRollback = MicrovoxelPrediction.replayRemovals(
                full, List.of(secondRemoval));
        require(rejectedRollback.occupied(firstRemoval)
                        && !rejectedRollback.occupied(secondRemoval),
                "A rejected edit must roll back without discarding later pending work");
        System.out.println("MicrovoxelCoreTest passed");
    }

    private static void verifyVisualIdentityAndBounds() {
        MicrovoxelVolume first = MicrovoxelVolume.full("minecraft:white_wool");
        for (int cell = 0; cell < MicrovoxelVolume.CELL_COUNT; cell++) {
            first.update(cell, "");
        }
        first.update(MicrovoxelVolume.index(4, 3, 2), "minecraft:white_wool");
        first.update(MicrovoxelVolume.index(7, 9, 12), "minecraft:white_wool");

        MicrovoxelVolume copyWithAnotherRevision = first.copy();
        copyWithAnotherRevision.setRevision(MicrovoxelRevision.next(first.revision()));
        MicrovoxelVisualShape.Snapshot firstVisual = MicrovoxelVisualShape.snapshot(first);
        MicrovoxelVisualShape.Snapshot copyVisual =
                MicrovoxelVisualShape.snapshot(copyWithAnotherRevision);
        require(firstVisual.key().equals(copyVisual.key())
                        && firstVisual.key().hashCode() == copyVisual.key().hashCode(),
                "Equal carved contents must share one stable GUI-atlas identity");

        MicrovoxelVolume different = first.copy();
        different.update(MicrovoxelVolume.index(7, 9, 12), "");
        different.update(MicrovoxelVolume.index(8, 9, 12), "minecraft:white_wool");
        require(!firstVisual.key().equals(MicrovoxelVisualShape.snapshot(different).key()),
                "Different carved contents must never share a GUI-atlas identity");

        MicrovoxelVisualShape.Bounds bounds = firstVisual.bounds();
        require(close(bounds.minX(), 4.0f / 16.0f)
                        && close(bounds.minY(), 3.0f / 16.0f)
                        && close(bounds.minZ(), 2.0f / 16.0f)
                        && close(bounds.maxX(), 8.0f / 16.0f)
                        && close(bounds.maxY(), 10.0f / 16.0f)
                        && close(bounds.maxZ(), 13.0f / 16.0f),
                "Portable item extents must match its actually occupied microvoxels");
    }

    private static void verifyPortableItemPresentation() {
        MicrovoxelVisualShape.Bounds full =
                new MicrovoxelVisualShape.Bounds(0, 0, 0, 1, 1, 1);
        MicrovoxelVisualShape.Bounds offsetQuarter =
                new MicrovoxelVisualShape.Bounds(0.75f, 0.25f, 0.5f,
                        1.0f, 0.5f, 0.75f);
        MicrovoxelVisualShape.Bounds singleCell =
                new MicrovoxelVisualShape.Bounds(0, 0, 0,
                        1.0f / 16.0f, 1.0f / 16.0f, 1.0f / 16.0f);

        var centred = MicrovoxelItemScale.presentation(
                ItemDisplayContext.FIRST_PERSON_RIGHT_HAND, offsetQuarter);
        require(centred.recenter()
                        && close(centred.centerX(), 0.875f)
                        && close(centred.centerY(), 0.375f)
                        && close(centred.centerZ(), 0.625f),
                "Carved item presentation must use the centre of its occupied bounds");
        float quarterHand = MicrovoxelItemScale.resultingMajorFraction(
                ItemDisplayContext.FIRST_PERSON_RIGHT_HAND, offsetQuarter);
        float cellHand = MicrovoxelItemScale.resultingMajorFraction(
                ItemDisplayContext.FIRST_PERSON_RIGHT_HAND, singleCell);
        require(quarterHand > cellHand && cellHand >= 0.339f && quarterHand < 0.60f,
                "Small hand fragments must stay readable without becoming full-block sized");
        float quarterGui = MicrovoxelItemScale.resultingMajorFraction(
                ItemDisplayContext.GUI, offsetQuarter);
        require(quarterGui > quarterHand
                        && close(MicrovoxelItemScale.resultingMajorFraction(
                        ItemDisplayContext.GUI, full), 0.68f),
                "Inventory presentation must give carved details more readable slot coverage");
        require(!MicrovoxelItemScale.presentation(
                        ItemDisplayContext.GROUND, offsetQuarter).recenter(),
                "World presentation must preserve the workpiece's physical local placement");
    }

    /**
     * Parent-material mirror of the server parentage rule: predicted break feedback
     * substitutes the dominant cached material, so the client sheds wooden chips from
     * a wooden sculpture while the server still authorizes the edit.
     */
    private static void verifyDominantMaterial() {
        require(MicrovoxelVolume.dominantMaterial(null) == null,
                "Null volume must have no dominant material");
        MicrovoxelVolume wood = MicrovoxelVolume.full("minecraft:oak_planks");
        require("minecraft:oak_planks".equals(MicrovoxelVolume.dominantMaterial(wood)),
                "Uniform volume must resolve to its own material");
        MicrovoxelVolume mixed = MicrovoxelVolume.full("minecraft:stone");
        for (int cell = 0; cell < 3000; cell++) mixed.update(cell, "minecraft:oak_planks");
        require("minecraft:oak_planks".equals(MicrovoxelVolume.dominantMaterial(mixed)),
                "Dominant material must be the most frequent cell");
        MicrovoxelVolume emptied = MicrovoxelVolume.full("minecraft:stone");
        for (int cell = 0; cell < MicrovoxelVolume.CELL_COUNT; cell++) emptied.update(cell, "");
        require(MicrovoxelVolume.dominantMaterial(emptied) == null,
                "Fully cleared volume must have no dominant material");
        System.out.println("MicrovoxelDominantMaterialTest: parent mirror passed");
    }

    /**
     * Hologram culling: the terrain mesh drops faces against solid neighbours, the
     * hologram copy must keep them (empty neighbourhood), or wedged copies render
     * with transparent sides.
     */
    private static void verifyHologramCulling() {
        MicrovoxelVolume single = MicrovoxelVolume.full("minecraft:stone");
        for (int cell = 0; cell < MicrovoxelVolume.CELL_COUNT; cell++) {
            if (cell != MicrovoxelVolume.index(3, 4, 5)) single.update(cell, "");
        }
        java.util.List<ua.rp.chat.microvoxel.MicrovoxelGreedyMesher.Face> unculled =
                ua.rp.chat.microvoxel.MicrovoxelGreedyMesher.build(single, (x, y, z) -> 0);
        require(unculled.size() == 6, "Lone cell must expose six faces, got " + unculled.size());
        java.util.List<ua.rp.chat.microvoxel.MicrovoxelGreedyMesher.Face> culled =
                ua.rp.chat.microvoxel.MicrovoxelGreedyMesher.build(single, (x, y, z) -> 1);
        require(culled.isEmpty(), "Fully surrounded cell must expose nothing");
        java.util.List<ua.rp.chat.microvoxel.MicrovoxelGreedyMesher.Face> eastWall =
                ua.rp.chat.microvoxel.MicrovoxelGreedyMesher.build(single,
                        (x, y, z) -> x == 4 && y == 4 && z == 5 ? 1 : 0);
        require(eastWall.size() == 5, "East neighbour must hide exactly one face, got "
                + eastWall.size());
        System.out.println("MicrovoxelHologramCullTest: unculled mesh passed");
    }

    /**
     * Instant placement prediction: mixed add/remove operations replay in transaction order over
     * the authoritative base, stale ops are skipped, and an emptied volume predicts as null
     * (marker hidden) until the server confirms.
     */
    private static void verifyPredictionReplay() {
        MicrovoxelVolume base = MicrovoxelVolume.full("minecraft:stone");
        int victim = MicrovoxelVolume.index(1, 1, 1);
        require(base.occupied(victim), "Prediction fixture must start occupied");

        // Remove then re-add the same cell: order matters, the add must win.
        MicrovoxelVolume readded = MicrovoxelPrediction.replayEdits(base, List.of(
                new MicrovoxelPrediction.PredictedOp(victim, ""),
                new MicrovoxelPrediction.PredictedOp(victim, "minecraft:dirt")));
        require(readded != null && readded.occupied(victim)
                        && readded.material(victim).equals("minecraft:dirt"),
                "Ordered add-after-remove must restore the cell with the new material");

        // Stale ops never corrupt the preview: removing an empty cell and placing into an
        // occupied one are both skipped.
        int empty = MicrovoxelVolume.index(0, 0, 0);
        base.update(empty, "");
        MicrovoxelVolume skipped = MicrovoxelPrediction.replayEdits(base, List.of(
                new MicrovoxelPrediction.PredictedOp(empty, ""),
                new MicrovoxelPrediction.PredictedOp(victim, "minecraft:dirt")));
        require(skipped != null && !skipped.occupied(empty) && skipped.occupied(victim)
                        && skipped.material(victim).equals("minecraft:stone"),
                "Stale remove/place predictions must leave the authoritative cells untouched");

        // Removing every cell predicts an empty volume as null so the marker hides at once.
        MicrovoxelVolume single = new MicrovoxelVolume(3, List.of("", "minecraft:stone"),
                singleCell(MicrovoxelVolume.index(5, 5, 5)));
        MicrovoxelVolume emptied = MicrovoxelPrediction.replayEdits(single, List.of(
                new MicrovoxelPrediction.PredictedOp(MicrovoxelVolume.index(5, 5, 5), "")));
        require(emptied == null, "A fully removed volume must predict as null (marker hidden)");

        // Placement into an unknown volume predicts onto an empty shell; a null base with no
        // ops stays null.
        MicrovoxelVolume shellPlaced = MicrovoxelPrediction.replayEdits(null, List.of(
                new MicrovoxelPrediction.PredictedOp(MicrovoxelVolume.index(2, 2, 2), "minecraft:oak_planks")));
        require(shellPlaced != null && shellPlaced.occupied(MicrovoxelVolume.index(2, 2, 2)),
                "Placement without a base volume must predict onto an empty shell");
        require(MicrovoxelPrediction.replayEdits(null, List.of()) == null,
                "A null base with no operations must stay null");

        // Legacy single-purpose entry point keeps its contract.
        MicrovoxelVolume legacy = MicrovoxelPrediction.replayRemovals(base, List.of(victim));
        require(legacy != null && !legacy.occupied(victim) && legacy.occupied(MicrovoxelVolume.index(2, 2, 2)),
                "Legacy removal replay must clear exactly the listed cells");
        System.out.println("MicrovoxelPredictionTest: ordered add/remove/brush replay passed");
    }

    /**
     * Cavity raycast: a ray through a carved pocket must travel past empty cells and strike the
     * inner wall with the micro face normal (not the vanilla block face), and a ray through one
     * volume's cavity must still hit a second volume standing behind it.
     */
    private static void verifyCavityRaycast() {
        // Blind pocket along +X: cells x=0..10 open at y=8,z=8, back wall at x=11.
        MicrovoxelVolume pocket = MicrovoxelVolume.full("minecraft:stone");
        for (int x = 0; x <= 10; x++) pocket.update(MicrovoxelVolume.index(x, 8, 8), "");
        MicrovoxelRaycaster.Hit inner = MicrovoxelRaycaster.cast(
                -1.0, 8.5 / 16.0, 8.5 / 16.0, 1.0, 0.0, 0.0, 10.0,
                List.of(new MicrovoxelRaycaster.Entry(0, 0, 0, pocket)));
        require(inner != null && inner.cell() == MicrovoxelVolume.index(11, 8, 8)
                        && inner.face() == MicrovoxelGreedyMesher.Direction.WEST,
                "A ray through a pocket must strike the inner back wall with the micro WEST normal");

        // Through-tunnel: the ray crosses the whole volume without touching occupied cells.
        MicrovoxelVolume tunnel = MicrovoxelVolume.full("minecraft:stone");
        for (int x = 0; x < 16; x++) tunnel.update(MicrovoxelVolume.index(x, 8, 8), "");
        MicrovoxelVolume behind = MicrovoxelVolume.full("minecraft:dirt");
        MicrovoxelRaycaster.Hit far = MicrovoxelRaycaster.cast(
                -1.0, 8.5 / 16.0, 8.5 / 16.0, 1.0, 0.0, 0.0, 10.0,
                List.of(new MicrovoxelRaycaster.Entry(0, 0, 0, tunnel),
                        new MicrovoxelRaycaster.Entry(2, 0, 0, behind)));
        require(far != null && far.entry().x() == 2
                        && far.cell() == MicrovoxelVolume.index(0, 8, 8)
                        && far.face() == MicrovoxelGreedyMesher.Direction.WEST,
                "A ray through one volume's cavity must hit the volume standing behind it");

        // Vertical pocket from above: the floor reports UP (ray came from the top).
        MicrovoxelVolume shaft = MicrovoxelVolume.full("minecraft:stone");
        for (int y = 5; y < 16; y++) shaft.update(MicrovoxelVolume.index(8, y, 8), "");
        MicrovoxelRaycaster.Hit floor = MicrovoxelRaycaster.cast(
                8.5 / 16.0, 3.0, 8.5 / 16.0, 0.0, -1.0, 0.0, 10.0,
                List.of(new MicrovoxelRaycaster.Entry(0, 0, 0, shaft)));
        require(floor != null && floor.cell() == MicrovoxelVolume.index(8, 4, 8)
                        && floor.face() == MicrovoxelGreedyMesher.Direction.UP,
                "A downward ray into a shaft must strike its floor with the micro UP normal");
        System.out.println("MicrovoxelCavityRaycastTest: pocket/tunnel/shaft targeting passed");
    }

    /**
     * Distance LOD meshing: stride 1 stays bit-identical to the exact mesh, stride 2 merges
     * 2x2x2 blocks with dominant materials on the stride grid, silhouettes keep exact bounds,
     * and illegal strides fail fast instead of emitting skewed geometry.
     */
    private static void verifyLodMeshing() {
        MicrovoxelVolume full = MicrovoxelVolume.full("minecraft:stone");
        List<MicrovoxelGreedyMesher.Face> exact = MicrovoxelGreedyMesher.build(full, full::materialAt);
        List<MicrovoxelGreedyMesher.Face> strideOne =
                MicrovoxelGreedyMesher.build(full, full::materialAt, 1);
        require(exact.equals(strideOne), "Stride-1 LOD must be bit-identical to the exact mesh");
        List<MicrovoxelGreedyMesher.Face> lod = MicrovoxelGreedyMesher.build(full, full::materialAt, 2);
        require(lod.size() == 6, "A solid volume must collapse to six quads at any stride");
        for (MicrovoxelGreedyMesher.Face face : lod) {
            require(face.minX() >= 0 && face.minY() >= 0 && face.minZ() >= 0
                            && face.maxX() <= 16 && face.maxY() <= 16 && face.maxZ() <= 16
                            && face.minX() % 2 == 0 && face.minY() % 2 == 0 && face.minZ() % 2 == 0
                            && face.maxX() % 2 == 0 && face.maxY() % 2 == 0 && face.maxZ() % 2 == 0,
                    "LOD faces must sit exactly on the stride grid inside the unit block");
        }

        // Carved notch: stride-2 keeps the notch silhouette (bounds shrink where carved).
        MicrovoxelVolume notched = MicrovoxelVolume.full("minecraft:stone");
        for (int x = 0; x < 8; x++) {
            for (int y = 8; y < 16; y++) {
                for (int z = 0; z < 16; z++) notched.update(MicrovoxelVolume.index(x, y, z), "");
            }
        }
        List<MicrovoxelGreedyMesher.Face> notchedLod =
                MicrovoxelGreedyMesher.build(notched, notched::materialAt, 2);
        require(!notchedLod.isEmpty() && notchedLod.size() <= exact.size() + 6,
                "LOD of a carved volume must stay bounded and non-empty");
        boolean rejected = false;
        try {
            MicrovoxelGreedyMesher.build(full, full::materialAt, 3);
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        require(rejected, "A stride that does not divide 16 must fail fast");
        System.out.println("MicrovoxelLodMeshingTest: stride parity, grid planes and silhouette passed");
    }

    /**
     * Load stand: 200 deterministic volumes (full, carved, fragmented) meshed at both strides
     * plus a simulated camera walk exercising the LOD hysteresis. Locks the reduction,
     * no-thrash and review-cost contract under pressure.
     */
    private static void verifyLoadStand() {
        java.util.Random random = new java.util.Random(0xEC1A5E);
        List<MicrovoxelVolume> town = new java.util.ArrayList<>(200);
        for (int index = 0; index < 200; index++) {
            MicrovoxelVolume volume = MicrovoxelVolume.full("minecraft:stone");
            int kind = index % 3;
            if (kind == 1) {
                // Carved: knock out ~30% of cells in blobs.
                for (int blob = 0; blob < 6; blob++) {
                    int cx = random.nextInt(16);
                    int cy = random.nextInt(16);
                    int cz = random.nextInt(16);
                    for (int dx = -1; dx <= 1; dx++) {
                        for (int dy = -1; dy <= 1; dy++) {
                            for (int dz = -1; dz <= 1; dz++) {
                                int x = cx + dx;
                                int y = cy + dy;
                                int z = cz + dz;
                                if (MicrovoxelVolume.inside(x, y, z)) {
                                    volume.update(MicrovoxelVolume.index(x, y, z), "");
                                }
                            }
                        }
                    }
                }
            } else if (kind == 2) {
                // Fragmented: checkerboard forces the worst-case cuboid/grid path.
                for (int cell = 0; cell < MicrovoxelVolume.CELL_COUNT; cell++) {
                    if ((MicrovoxelVolume.x(cell) + MicrovoxelVolume.y(cell)
                            + MicrovoxelVolume.z(cell)) % 2 == 0) {
                        volume.update(cell, "");
                    }
                }
            }
            town.add(volume);
        }

        long exactStart = System.nanoTime();
        int exactFaces = 0;
        for (MicrovoxelVolume volume : town) {
            exactFaces += MicrovoxelGreedyMesher.build(volume, volume::materialAt).size();
        }
        long exactMs = (System.nanoTime() - exactStart) / 1_000_000L;
        long lodStart = System.nanoTime();
        int lodFaces = 0;
        for (MicrovoxelVolume volume : town) {
            List<MicrovoxelGreedyMesher.Face> faces =
                    MicrovoxelGreedyMesher.build(volume, volume::materialAt, 2);
            lodFaces += faces.size();
            for (MicrovoxelGreedyMesher.Face face : faces) {
                require(face.minX() >= 0 && face.minY() >= 0 && face.minZ() >= 0
                                && face.maxX() <= 16 && face.maxY() <= 16 && face.maxZ() <= 16,
                        "Every LOD face of the load stand must stay inside the unit block");
            }
        }
        long lodMs = (System.nanoTime() - lodStart) / 1_000_000L;
        require(exactFaces > 0 && lodFaces > 0, "Load stand must produce real geometry");
        require(lodFaces <= exactFaces,
                "LOD must never emit more faces than the exact mesh: " + lodFaces + "/" + exactFaces);
        require(exactMs < 30_000L && lodMs < 30_000L,
                "Meshing 200 volumes must stay seconds-scale, took " + exactMs + "/" + lodMs + "ms");

        System.out.println("MicrovoxelLoadStandTest: 200 volumes exact=" + exactFaces
                + " faces/" + exactMs + "ms lod=" + lodFaces + " faces/" + lodMs + "ms");
    }

    /**
     * Three-tier hysteresis walk on the pure tier machine: out 10-90 flips NEAR-MID
     * once past 24 m and MID-FAR once past 72 m; back 90-10 flips once below 61.2 m
     * and once below 20.4 m; jitter inside each dead band never flips again.
     */
    private static void verifyLodTiers() {
        MicrovoxelLodTiers.Tier tier = MicrovoxelLodTiers.Tier.NEAR;
        int flips = 0;
        for (int dist = 10; dist <= 90; dist++) {
            MicrovoxelLodTiers.Tier want =
                    MicrovoxelLodTiers.wantTier((double) dist * dist, tier);
            if (want != tier) {
                tier = want;
                flips++;
            }
        }
        require(tier == MicrovoxelLodTiers.Tier.FAR && flips == 2,
                "Walking out must flip NEAR->MID->FAR exactly twice, flipped " + flips);
        for (int dist = 90; dist >= 10; dist--) {
            MicrovoxelLodTiers.Tier want =
                    MicrovoxelLodTiers.wantTier((double) dist * dist, tier);
            if (want != tier) {
                tier = want;
                flips++;
            }
        }
        require(tier == MicrovoxelLodTiers.Tier.NEAR && flips == 4,
                "Walking back must flip FAR->MID->NEAR exactly twice more, flipped " + flips);
        // Settle MID at 30 m, then jitter 21<->23 m inside the near dead band
        // (above the 20.4 m exit, below the 24 m entry): MID must hold.
        tier = MicrovoxelLodTiers.wantTier(30.0 * 30.0, MicrovoxelLodTiers.Tier.NEAR);
        require(tier == MicrovoxelLodTiers.Tier.MID, "30 m must settle MID");
        for (int step = 0; step < 50; step++) {
            double distSq = (step % 2 == 0 ? 21.0 * 21.0 : 23.0 * 23.0);
            MicrovoxelLodTiers.Tier want = MicrovoxelLodTiers.wantTier(distSq, tier);
            if (want != tier) {
                tier = want;
                flips++;
            }
        }
        require(tier == MicrovoxelLodTiers.Tier.MID && flips == 4,
                "Near dead-band jitter must hold MID, flipped " + flips);
        // Settle FAR at 80 m, then jitter 62<->70 m inside the far dead band
        // (above the 61.2 m exit, below the 72 m entry): FAR must hold.
        tier = MicrovoxelLodTiers.wantTier(80.0 * 80.0, tier);
        require(tier == MicrovoxelLodTiers.Tier.FAR, "80 m must settle FAR");
        for (int step = 0; step < 50; step++) {
            double distSq = (step % 2 == 0 ? 62.0 * 62.0 : 70.0 * 70.0);
            MicrovoxelLodTiers.Tier want = MicrovoxelLodTiers.wantTier(distSq, tier);
            if (want != tier) {
                tier = want;
                flips++;
            }
        }
        require(tier == MicrovoxelLodTiers.Tier.FAR && flips == 4,
                "Far dead-band jitter must hold FAR, flipped " + flips);
        require(MicrovoxelLodTiers.strideFor(MicrovoxelLodTiers.Tier.NEAR) == 1
                        && MicrovoxelLodTiers.strideFor(MicrovoxelLodTiers.Tier.MID) == 2
                        && MicrovoxelLodTiers.strideFor(MicrovoxelLodTiers.Tier.FAR) == 4,
                "Tiers must map to strides 1/2/4");
        System.out.println("MicrovoxelLodTiersTest: 24/72 m bands, hysteresis and strides passed");
    }

    /**
     * Stride-4 silhouette tier: solids collapse to six quads, one-cell-thin walls survive
     * dilation, checkerboards densify to solids, and every face stays on the 4-grid.
     */
    private static void verifyStrideFour() {
        MicrovoxelVolume full = MicrovoxelVolume.full("minecraft:stone");
        List<MicrovoxelGreedyMesher.Face> solid =
                MicrovoxelGreedyMesher.build(full, full::materialAt, 4);
        require(solid.size() == 6, "A solid volume must collapse to six quads at stride 4");
        MicrovoxelVolume wall = MicrovoxelVolume.full("minecraft:stone");
        for (int cell = 0; cell < MicrovoxelVolume.CELL_COUNT; cell++) {
            if (MicrovoxelVolume.x(cell) != 0) wall.update(cell, "");
        }
        List<MicrovoxelGreedyMesher.Face> wallFaces =
                MicrovoxelGreedyMesher.build(wall, wall::materialAt, 4);
        require(!wallFaces.isEmpty(),
                "Dilation must preserve a one-cell-thin wall at stride 4");
        MicrovoxelVolume checker = MicrovoxelVolume.full("minecraft:stone");
        for (int cell = 0; cell < MicrovoxelVolume.CELL_COUNT; cell++) {
            if ((MicrovoxelVolume.x(cell) + MicrovoxelVolume.y(cell)
                    + MicrovoxelVolume.z(cell)) % 2 == 0) {
                checker.update(cell, "");
            }
        }
        List<MicrovoxelGreedyMesher.Face> checkerFaces =
                MicrovoxelGreedyMesher.build(checker, checker::materialAt, 4);
        int checkerExact =
                MicrovoxelGreedyMesher.build(checker, checker::materialAt, 1).size();
        require(!checkerFaces.isEmpty() && checkerFaces.size() <= checkerExact,
                "Stride 4 must keep every sightline through the holes while merging planes: "
                        + checkerFaces.size() + "/" + checkerExact);
        for (List<MicrovoxelGreedyMesher.Face> faces : Arrays.asList(solid, wallFaces, checkerFaces)) {
            for (MicrovoxelGreedyMesher.Face face : faces) {
                require(face.minX() >= 0 && face.minY() >= 0 && face.minZ() >= 0
                                && face.maxX() <= 16 && face.maxY() <= 16 && face.maxZ() <= 16
                                && face.minX() % 4 == 0 && face.minY() % 4 == 0 && face.minZ() % 4 == 0
                                && face.maxX() % 4 == 0 && face.maxY() % 4 == 0 && face.maxZ() % 4 == 0,
                        "Stride-4 faces must sit exactly on the 4-grid inside the unit block");
            }
        }
        System.out.println("MicrovoxelStrideFourTest: collapse, dilation and 4-grid passed");
    }

    /**
     * Seam culling proof: two adjacent solid volumes must emit exactly the faces of one
     * 32x16x16 monolith — zero quads on the shared wall — at stride 1 and stride 2.
     * Adjacency is simulated through the neighbour lookup exactly like section rebuilds
     * feed it (solid stone past x=16, air elsewhere).
     */
    private static void verifySeamCulling() {
        MicrovoxelVolume west = MicrovoxelVolume.full("minecraft:stone");
        MicrovoxelGreedyMesher.NeighbourLookup eastSolid = (x, y, z) -> {
            if (x >= 0 && x < 16 && y >= 0 && y < 16 && z >= 0 && z < 16) {
                return west.materialAt(x, y, z);
            }
            if (x >= 16 && x < 32 && y >= 0 && y < 16 && z >= 0 && z < 16) return 1;
            return 0;
        };
        List<MicrovoxelGreedyMesher.Face> faces =
                MicrovoxelGreedyMesher.build(west, eastSolid, 1);
        require(faces.size() == 5, "Seam-adjacent volume must emit five outer quads, got "
                + faces.size());
        for (MicrovoxelGreedyMesher.Face face : faces) {
            require(face.direction() != MicrovoxelGreedyMesher.Direction.EAST
                            || face.minX() != 16,
                    "No quad may sit on the shared +X seam");
        }
        List<MicrovoxelGreedyMesher.Face> lod =
                MicrovoxelGreedyMesher.build(west, eastSolid, 2);
        require(lod.size() == 5, "Seam culling must hold at stride 2, got " + lod.size());
        MicrovoxelVolume air = MicrovoxelVolume.full("minecraft:stone");
        for (int cell = 0; cell < MicrovoxelVolume.CELL_COUNT; cell++) air.update(cell, "");
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                air.update(MicrovoxelVolume.index(0, y, z), "minecraft:stone");
            }
        }
        List<MicrovoxelGreedyMesher.Face> loneWall =
                MicrovoxelGreedyMesher.build(air, air::materialAt, 1);
        require(loneWall.stream().anyMatch(face ->
                        face.direction() == MicrovoxelGreedyMesher.Direction.EAST),
                "A lone wall facing air must keep its +X faces");
        System.out.println("MicrovoxelSeamCullingTest: zero seam quads at strides 1/2 passed");
    }

    /**
     * Payoff benchmark answering "was the third tier worth it": the same 200-volume town
     * meshed all-near versus a city-like tier mix (20% near, 30% mid, 50% far). The far
     * tier must cut total faces several-fold, or the feature does not pay for itself.
     */
    private static void verifyLodPayoff() {
        java.util.Random random = new java.util.Random(0xC17E5L);
        List<MicrovoxelVolume> town = new java.util.ArrayList<>(200);
        for (int index = 0; index < 200; index++) {
            MicrovoxelVolume volume = MicrovoxelVolume.full("minecraft:stone");
            int kind = index % 3;
            if (kind == 1) {
                for (int blob = 0; blob < 6; blob++) {
                    int cx = random.nextInt(16);
                    int cy = random.nextInt(16);
                    int cz = random.nextInt(16);
                    for (int dx = -1; dx <= 1; dx++) {
                        for (int dy = -1; dy <= 1; dy++) {
                            for (int dz = -1; dz <= 1; dz++) {
                                int x = cx + dx;
                                int y = cy + dy;
                                int z = cz + dz;
                                if (MicrovoxelVolume.inside(x, y, z)) {
                                    volume.update(MicrovoxelVolume.index(x, y, z), "");
                                }
                            }
                        }
                    }
                }
            } else if (kind == 2) {
                for (int cell = 0; cell < MicrovoxelVolume.CELL_COUNT; cell++) {
                    if ((MicrovoxelVolume.x(cell) + MicrovoxelVolume.y(cell)
                            + MicrovoxelVolume.z(cell)) % 2 == 0) {
                        volume.update(cell, "");
                    }
                }
            }
            town.add(volume);
        }
        long nearStart = System.nanoTime();
        int nearFaces = 0;
        for (MicrovoxelVolume volume : town) {
            nearFaces += MicrovoxelGreedyMesher.build(volume, volume::materialAt, 1).size();
        }
        long nearMs = (System.nanoTime() - nearStart) / 1_000_000L;
        long tieredStart = System.nanoTime();
        int tieredFaces = 0;
        for (int index = 0; index < town.size(); index++) {
            MicrovoxelVolume volume = town.get(index);
            int stride = index % 10 < 2 ? 1 : index % 10 < 5 ? 2 : 4;
            tieredFaces += MicrovoxelGreedyMesher.build(volume, volume::materialAt, stride).size();
        }
        long tieredMs = (System.nanoTime() - tieredStart) / 1_000_000L;
        require(nearFaces > 0 && tieredFaces > 0, "Payoff town must produce real geometry");
        double reduction = (double) nearFaces / tieredFaces;
        require(reduction >= 3.0,
                "Tiered city rendering must cut faces at least 3x, got " + reduction
                        + "x (" + nearFaces + " -> " + tieredFaces + ")");
        require(nearMs < 30_000L && tieredMs < 30_000L,
                "Payoff meshing must stay seconds-scale, took " + nearMs + "/" + tieredMs + "ms");
        System.out.println("MicrovoxelLodPayoffTest: all-near=" + nearFaces + " faces/" + nearMs
                + "ms tiered=" + tieredFaces + " faces/" + tieredMs + "ms reduction="
                + String.format(java.util.Locale.ROOT, "%.1f", reduction) + "x");
    }

    /**
     * Client mirror of the server sealed-faces contract: identical bit order and thresholds,
     * so both light engines agree on which volumes block skylight.
     */
    private static void verifyLightSealingMirror() {
        java.util.function.Predicate<String> stoneOpaque = material -> !material.contains("glass");
        MicrovoxelVolume solid = MicrovoxelVolume.full("minecraft:stone");
        require(solid.sealedOpaqueFaces(stoneOpaque) == MicrovoxelVolume.ALL_FACES_SEALED,
                "Client mirror must seal a solid opaque cube on all six faces");
        MicrovoxelVolume glass = MicrovoxelVolume.full("minecraft:glass");
        require(glass.sealedOpaqueFaces(stoneOpaque) == 0,
                "Client mirror must seal no face of a glass cube");
        MicrovoxelVolume reliefWall = MicrovoxelVolume.full("minecraft:stone");
        for (int cell = 0; cell < MicrovoxelVolume.CELL_COUNT; cell++) {
            int x = MicrovoxelVolume.x(cell);
            int y = MicrovoxelVolume.y(cell);
            int z = MicrovoxelVolume.z(cell);
            if ((x + y + z) % 20 == 0) reliefWall.update(cell, "");
        }
        require(reliefWall.isLightSealed(stoneOpaque),
                "Client mirror must agree: a 95% dense wall stays light-sealed");
        MicrovoxelVolume thinWall = MicrovoxelVolume.full("minecraft:stone");
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    if (x < 5 || x > 10) thinWall.update(MicrovoxelVolume.index(x, y, z), "");
                }
            }
        }
        require(thinWall.isLightSealed(stoneOpaque),
                "Client mirror must agree: a 6-voxel wall seals through its axial plate");
        java.util.function.ToIntFunction<String> torchEmission =
                material -> material.contains("torch") ? 14 : 0;
        MicrovoxelVolume loneTorch = MicrovoxelVolume.full("minecraft:stone");
        loneTorch.update(MicrovoxelVolume.index(8, 15, 8), "minecraft:torch");
        require(loneTorch.emissionLevel(torchEmission) == 4,
                "Client prediction must glow a lone torch dimly, like the server");
        MicrovoxelVolume buried = MicrovoxelVolume.full("minecraft:stone");
        buried.update(MicrovoxelVolume.index(8, 8, 8), "minecraft:torch");
        require(buried.emissionLevel(torchEmission) == 0,
                "Client prediction must keep a bricked-in torch dark, like the server");
        System.out.println("MicrovoxelLightSealingMirrorTest: sealed-faces parity passed");
    }

    /**
     * Fluid wire mirror: the client decoder must agree with the server encoder bit for bit
     * (uniform basins collapse, ragged levels round-trip, truncation fails closed) or synced
     * surfaces and physics refinement diverge.
     */
    private static void verifyFluidCodecMirror() {
        byte[] smooth = new byte[MicrovoxelVolume.CELL_COUNT];
        java.util.Arrays.fill(smooth, (byte) 16);
        // Hand-rolled RLE frame matching the server encoder: count + (run, value) pairs.
        java.io.ByteArrayOutputStream frame = new java.io.ByteArrayOutputStream();
        writeVarInt(frame, smooth.length);
        writeVarInt(frame, smooth.length);
        frame.write(16);
        byte[] decoded = decodeLevelsQuiet(frame.toByteArray());
        require(decoded != null && java.util.Arrays.equals(decoded, smooth),
                "Client fluid decoder must read the server wire frame exactly");
        require(decodeLevelsQuiet(new byte[]{0x7F}) == null,
                "Client fluid decoder must fail closed on truncation");
        System.out.println("MicrovoxelFluidCodecMirrorTest: wire parity passed");
    }

    private static void writeVarInt(java.io.ByteArrayOutputStream out, int value) {
        while ((value & 0xFFFFFF80) != 0) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value & 0x7F);
    }

    private static byte[] decodeLevelsQuiet(byte[] encoded) {
        try {
            return ua.rp.chat.client.microvoxel.MicrovoxelClientState.decodeLevels(encoded);
        } catch (Exception rejected) {
            return null;
        }
    }

    private static byte[] singleCell(int cell) {
        byte[] cells = new byte[MicrovoxelVolume.CELL_COUNT];
        cells[cell] = 1;
        return cells;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static boolean close(float left, float right) {
        return Math.abs(left - right) < 0.0001f;
    }

    private static MicrovoxelVolume carvedFixture() {
        byte[] cells = new byte[MicrovoxelVolume.CELL_COUNT];
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 4; x++) {
                cells[MicrovoxelVolume.index(x, y, 8)] = 1;
            }
        }
        return new MicrovoxelVolume(
                7, List.of("", "minecraft:red_wool"), cells);
    }

    private static byte[] portableBytes(MicrovoxelVolume volume)
            throws java.io.IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(volume.revision());
            output.writeByte(volume.palette().size());
            for (String material : volume.palette()) {
                byte[] encoded = material.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                output.writeShort(encoded.length);
                output.write(encoded);
            }
            output.write(volume.cellsCopy());
        }
        return bytes.toByteArray();
    }

    private static int occupiedCount(MicrovoxelVolume volume) {
        int occupied = 0;
        for (byte cell : volume.cellsCopy()) if (cell != 0) occupied++;
        return occupied;
    }
}
