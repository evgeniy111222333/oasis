package ua.rp.chat;

import ua.rp.chat.carver.CarverChalkQuads;
import ua.rp.chat.microvoxel.MicrovoxelGreedyMesher;

import java.util.ArrayList;
import java.util.List;

public final class CarverSurfaceWireTest {
    public static void main(String[] args) {
        verifyTopFaceBounds();
        verifyBottomFaceBounds();
        verifySideFaceBounds();
        verifyOffsetsApply();
        verifyLargestFirstOrdering();
        verifyLargestFirstCap();
        verifyLargestFirstEmpty();
        System.out.println("CarverSurfaceWireTest passed");
    }

    private static void verifyTopFaceBounds() {
        MicrovoxelGreedyMesher.Face top = new MicrovoxelGreedyMesher.Face(
                MicrovoxelGreedyMesher.Direction.UP, 1, 0, 16, 0, 16, 16, 16);
        double[] bounds = CarverChalkQuads.surfaceFrameBounds(top, 0.75, 0.0, 0.0);
        require(close(bounds[0], 0.0) && close(bounds[1], 1.75) && close(bounds[2], 0.0)
                        && close(bounds[3], 1.0) && close(bounds[4], 1.75) && close(bounds[5], 1.0),
                "UP face must map to the lifted top plane, got " + java.util.Arrays.toString(bounds));
    }

    private static void verifyBottomFaceBounds() {
        MicrovoxelGreedyMesher.Face bottom = new MicrovoxelGreedyMesher.Face(
                MicrovoxelGreedyMesher.Direction.DOWN, 1, 2, 0, 3, 6, 0, 9);
        double[] bounds = CarverChalkQuads.surfaceFrameBounds(bottom, 0.0, 0.0, 0.0);
        require(close(bounds[0], 0.125) && close(bounds[1], 0.0) && close(bounds[2], 0.1875)
                        && close(bounds[3], 0.375) && close(bounds[4], 0.0) && close(bounds[5], 0.5625),
                "DOWN face must keep zero thickness on Y, got " + java.util.Arrays.toString(bounds));
    }

    private static void verifySideFaceBounds() {
        MicrovoxelGreedyMesher.Face north = new MicrovoxelGreedyMesher.Face(
                MicrovoxelGreedyMesher.Direction.NORTH, 1, 0, 0, 5, 16, 16, 5);
        double[] northBounds = CarverChalkQuads.surfaceFrameBounds(north, 0.0, 0.0, 0.0);
        require(close(northBounds[2], 0.3125) && close(northBounds[5], 0.3125)
                        && close(northBounds[3], 1.0) && close(northBounds[4], 1.0),
                "NORTH face must keep zero thickness on Z, got " + java.util.Arrays.toString(northBounds));
        MicrovoxelGreedyMesher.Face east = new MicrovoxelGreedyMesher.Face(
                MicrovoxelGreedyMesher.Direction.EAST, 1, 16, 4, 0, 16, 12, 16);
        double[] eastBounds = CarverChalkQuads.surfaceFrameBounds(east, 0.0, 0.0, 0.0);
        require(close(eastBounds[0], 1.0) && close(eastBounds[3], 1.0)
                        && close(eastBounds[1], 0.25) && close(eastBounds[4], 0.75),
                "EAST face must keep zero thickness on X, got " + java.util.Arrays.toString(eastBounds));
    }

    private static void verifyOffsetsApply() {
        MicrovoxelGreedyMesher.Face west = new MicrovoxelGreedyMesher.Face(
                MicrovoxelGreedyMesher.Direction.WEST, 1, 0, 0, 0, 0, 16, 16);
        double[] bounds = CarverChalkQuads.surfaceFrameBounds(west, 0.5, 0.25, -0.25);
        require(close(bounds[0], 0.25) && close(bounds[1], 0.5) && close(bounds[2], -0.25)
                        && close(bounds[3], 0.25) && close(bounds[4], 1.5) && close(bounds[5], 0.75),
                "Lift and nudge must shift every corner, got " + java.util.Arrays.toString(bounds));
    }

    private static void verifyLargestFirstOrdering() {
        MicrovoxelGreedyMesher.Face sliver = new MicrovoxelGreedyMesher.Face(
                MicrovoxelGreedyMesher.Direction.UP, 1, 0, 16, 0, 1, 16, 1);
        MicrovoxelGreedyMesher.Face slab = new MicrovoxelGreedyMesher.Face(
                MicrovoxelGreedyMesher.Direction.UP, 1, 0, 16, 0, 16, 16, 16);
        List<MicrovoxelGreedyMesher.Face> ordered =
                CarverChalkQuads.largestFirst(List.of(sliver, slab), 10);
        require(ordered.size() == 2 && ordered.get(0).equals(slab),
                "Largest face must sort first");
    }

    private static void verifyLargestFirstCap() {
        List<MicrovoxelGreedyMesher.Face> many = new ArrayList<>();
        for (int index = 0; index < 300; index++) {
            many.add(new MicrovoxelGreedyMesher.Face(
                    MicrovoxelGreedyMesher.Direction.UP, 1,
                    index % 16, 16, 0, (index % 16) + 1, 16, 1));
        }
        List<MicrovoxelGreedyMesher.Face> capped = CarverChalkQuads.largestFirst(many, 256);
        require(capped.size() == 256, "Frame budget must cap at 256, got " + capped.size());
    }

    private static void verifyLargestFirstEmpty() {
        require(CarverChalkQuads.largestFirst(List.of(), 256).isEmpty()
                        && CarverChalkQuads.largestFirst(null, 256).isEmpty()
                        && CarverChalkQuads.largestFirst(
                                List.of(new MicrovoxelGreedyMesher.Face(
                                        MicrovoxelGreedyMesher.Direction.UP, 1,
                                        0, 16, 0, 16, 16, 16)), 0).isEmpty(),
                "Empty input or zero budget must yield no frames");
    }

    private static boolean close(double left, double right) {
        return Math.abs(left - right) < 1.0e-9;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
