package ua.rp.chat;

import ua.rp.chat.carver.CarverChalkQuads;
import ua.rp.chat.carver.DraftMask;
import ua.rp.chat.microvoxel.MicrovoxelGreedyMesher;
import ua.rp.chat.microvoxel.MicrovoxelVolume;

import java.util.ArrayList;
import java.util.List;

public final class CarverHologramFilterTest {
    public static void main(String[] args) {
        verifySmallDraftKeepsFullCube();
        verifySingleCellDraftKeepsEveryDirection();
        verifyFullDraftGoesGhost();
        verifyPartialFaceCoverage();
        verifySingleCellCycle();
        verifyRemnantCoveredByDraft();
        verifyEmptyDraftNeverClears();
        verifyPreviewCells();
        verifyMaskedMeshRemovesOuterLayer();
        verifyMaskedMeshOpensInteriorCavity();
        verifyMaskedMeshFullyHidden();
        verifyMaskedMeshNullMatchesPlain();
        System.out.println("CarverHologramFilterTest passed");
    }

    /**
     * The new hologram mesh (volume minus draft) must re-cut on the hidden boundary: hiding
     * the top layer drops the old top plane and exposes the freshly revealed one, rather than
     * leaving the untouched side faces of the full cube standing.
     */
    private static void verifyMaskedMeshRemovesOuterLayer() {
        MicrovoxelVolume full = MicrovoxelVolume.full("minecraft:stone");
        java.util.List<MicrovoxelGreedyMesher.Face> open = mesh(full);
        require(open.size() == 6, "A solid volume must mesh to six outer quads, got " + open.size());
        java.util.List<MicrovoxelGreedyMesher.Face> masked = MicrovoxelGreedyMesher.build(
                full, full::materialAt, cell -> DraftMask.y(cell) == 15);
        boolean oldTop = masked.stream().anyMatch(face ->
                face.direction() == MicrovoxelGreedyMesher.Direction.UP && face.maxY() == 16);
        boolean freshTop = masked.stream().anyMatch(face ->
                face.direction() == MicrovoxelGreedyMesher.Direction.UP && face.maxY() == 15);
        require(!oldTop && freshTop,
                "Hiding the top layer must remove the y=16 top plane and reveal the y=15 one");
        boolean tallerSide = masked.stream().anyMatch(face ->
                face.direction() == MicrovoxelGreedyMesher.Direction.EAST && face.maxY() > 15);
        require(!tallerSide, "Side faces must shorten to the remaining height, not stay full");
    }

    /** Hiding one interior cell must open its six cavity walls while the outer six stay. */
    private static void verifyMaskedMeshOpensInteriorCavity() {
        MicrovoxelVolume full = MicrovoxelVolume.full("minecraft:stone");
        int cell = DraftMask.index(8, 8, 8);
        java.util.List<MicrovoxelGreedyMesher.Face> masked = MicrovoxelGreedyMesher.build(
                full, full::materialAt, candidate -> candidate == cell);
        require(masked.size() == 12,
                "One hidden interior cell must add six cavity walls to the six outer quads, got "
                        + masked.size());
        for (MicrovoxelGreedyMesher.Face face : masked) {
            require(face.minX() >= 0 && face.minY() >= 0 && face.minZ() >= 0
                            && face.maxX() <= 16 && face.maxY() <= 16 && face.maxZ() <= 16,
                    "Masked faces must stay inside the unit block");
        }
    }

    /** Hiding every cell must leave no faces at all. */
    private static void verifyMaskedMeshFullyHidden() {
        MicrovoxelVolume full = MicrovoxelVolume.full("minecraft:stone");
        java.util.List<MicrovoxelGreedyMesher.Face> masked = MicrovoxelGreedyMesher.build(
                full, full::materialAt, cell -> true);
        require(masked.isEmpty(), "A fully hidden volume must mesh to nothing");
    }

    /** A null hidden predicate must be bit-identical to the plain exact mesh. */
    private static void verifyMaskedMeshNullMatchesPlain() {
        MicrovoxelVolume carved = MicrovoxelVolume.full("minecraft:stone");
        for (int cell = 0; cell < 200; cell++) carved.update(cell, "");
        java.util.List<MicrovoxelGreedyMesher.Face> plain = mesh(carved);
        java.util.List<MicrovoxelGreedyMesher.Face> nullMask =
                MicrovoxelGreedyMesher.build(carved, carved::materialAt, null);
        require(plain.equals(nullMask),
                "Null hidden predicate must keep the plain exact mesh");
    }

    private static void verifySmallDraftKeepsFullCube() {
        MicrovoxelVolume full = MicrovoxelVolume.full("minecraft:grass_block");
        DraftMask draft = new DraftMask();
        for (int cell = 0; cell < 31; cell++) draft.set(cell);
        int[] split = split(full, draft);
        require(split[0] == 6 && split[1] == 0,
                "31-cell draft must keep all 6 cube faces solid, got solid="
                        + split[0] + " ghost=" + split[1]);
    }

    private static void verifySingleCellDraftKeepsEveryDirection() {
        MicrovoxelVolume full = MicrovoxelVolume.full("minecraft:grass_block");
        DraftMask draft = new DraftMask();
        draft.set(0);
        int[] split = split(full, draft);
        require(split[0] == 6 && split[1] == 0,
                "One marked cell must not clear any face on any axis, got solid="
                        + split[0] + " ghost=" + split[1]);
    }

    private static void verifyFullDraftGoesGhost() {
        MicrovoxelVolume full = MicrovoxelVolume.full("minecraft:grass_block");
        DraftMask draft = new DraftMask();
        for (int cell = 0; cell < MicrovoxelVolume.CELL_COUNT; cell++) draft.set(cell);
        int[] split = split(full, draft);
        require(split[0] == 0 && split[1] == 6,
                "Fully drafted cube must ghost all 6 faces, got solid="
                        + split[0] + " ghost=" + split[1]);
    }

    private static void verifyPartialFaceCoverage() {
        MicrovoxelVolume full = MicrovoxelVolume.full("minecraft:grass_block");
        DraftMask topPlane = new DraftMask();
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            topPlane.set(DraftMask.index(x, 15, z));
        }
        int[] split = split(full, topPlane);
        require(split[0] == 5 && split[1] == 1,
                "Drafted top plane must ghost exactly the UP face, got solid="
                        + split[0] + " ghost=" + split[1]);
    }

    private static void verifySingleCellCycle() {
        MicrovoxelVolume single = MicrovoxelVolume.full("minecraft:stone");
        int keep = MicrovoxelVolume.index(3, 4, 5);
        for (int cell = 0; cell < MicrovoxelVolume.CELL_COUNT; cell++) {
            if (cell != keep) single.update(cell, "");
        }
        DraftMask covering = new DraftMask();
        covering.set(keep);
        int[] covered = split(single, covering);
        require(covered[0] == 0 && covered[1] == 6,
                "Covered lone cell must ghost all 6 faces, got solid="
                        + covered[0] + " ghost=" + covered[1]);
        int[] open = split(single, new DraftMask());
        require(open[0] == 6 && open[1] == 0,
                "Empty draft must keep all 6 faces of a lone cell, got solid="
                        + open[0] + " ghost=" + open[1]);
    }

    private static void verifyRemnantCoveredByDraft() {
        MicrovoxelVolume remnant = MicrovoxelVolume.full("minecraft:grass_block");
        for (int cell = 5; cell < MicrovoxelVolume.CELL_COUNT; cell++) remnant.update(cell, "");
        DraftMask draft = new DraftMask();
        for (int cell = 0; cell < 31; cell++) draft.set(cell);
        int[] split = split(remnant, draft);
        require(split[0] == 0 && !mesh(remnant).isEmpty(),
                "Covered 5-cell remnant must ghost every face instead of vanishing silently");
    }

    private static void verifyEmptyDraftNeverClears() {
        MicrovoxelVolume full = MicrovoxelVolume.full("minecraft:grass_block");
        DraftMask empty = new DraftMask();
        for (MicrovoxelGreedyMesher.Face face : mesh(full)) {
            require(!CarverChalkQuads.cellsClearedFace(face, empty),
                    "Empty draft must never clear face " + face.direction());
            require(!CarverChalkQuads.cellsCleared(
                    face.minX(), face.minY(), face.minZ(),
                    face.maxX() - 1, face.maxY() - 1, face.maxZ() - 1, empty),
                    "Empty draft must never clear legacy bounds " + face.direction());
        }
    }

    private static void verifyPreviewCells() {
        java.util.List<Integer> full = CarverChalkQuads.previewCells(
                new int[]{0, 0, 0, 15, 15, 15}, 512);
        require(full.size() == 512, "Preview must cap a full block at 512, got " + full.size());
        java.util.List<Integer> single = CarverChalkQuads.previewCells(
                new int[]{3, 4, 5, 3, 4, 5}, 512);
        require(single.size() == 1 && single.get(0) == DraftMask.index(3, 4, 5),
                "Single-cell bounds must yield exactly that cell");
        java.util.List<Integer> row = CarverChalkQuads.previewCells(
                new int[]{0, 15, 0, 15, 15, 0}, 512);
        require(row.size() == 16, "Top row must yield 16 cells, got " + row.size());
        require(CarverChalkQuads.previewCells(new int[]{0, 0, 0, 30, 30, 30}, 5000).size()
                        == MicrovoxelVolume.CELL_COUNT,
                "Out-of-range bounds must clamp to the volume");
        require(CarverChalkQuads.previewCells(null, 512).isEmpty()
                        && CarverChalkQuads.previewCells(new int[]{0, 0, 0, 1, 1, 1}, 0).isEmpty()
                        && CarverChalkQuads.previewCells(new int[]{5, 5, 5, 2, 2, 2}, 512).isEmpty(),
                "Null bounds, zero cap and inverted bounds must yield nothing");
    }

    private static List<MicrovoxelGreedyMesher.Face> mesh(MicrovoxelVolume volume) {
        return MicrovoxelGreedyMesher.build(volume, volume::materialAt);
    }

    private static int[] split(MicrovoxelVolume volume, DraftMask draft) {
        List<MicrovoxelGreedyMesher.Face> faces = mesh(volume);
        long fingerprint = draft.isEmpty() ? 0L : CarverChalkQuads.draftFingerprint(draft);
        if (fingerprint == 0L) return new int[]{faces.size(), 0};
        int solid = 0;
        int ghost = 0;
        for (MicrovoxelGreedyMesher.Face face : faces) {
            if (CarverChalkQuads.cellsClearedFace(face, draft)) ghost++;
            else solid++;
        }
        return new int[]{solid, ghost};
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
