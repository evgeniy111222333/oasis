package ua.rp.chat;

import ua.rp.chat.carver.CarverBoxSelect;
import ua.rp.chat.carver.CarverCameraMath;
import ua.rp.chat.carver.CarverStrokeLine;
import ua.rp.chat.carver.CarverCursorPick;
import ua.rp.chat.carver.CarverChalkQuads;
import ua.rp.chat.carver.CarverFaceSlicer;
import ua.rp.chat.carver.CarverWorkPhases;
import ua.rp.chat.carver.DraftEstimate;
import ua.rp.chat.carver.DraftMask;

/**
 * Guards the client mirrors of the Carver pure logic: the drafting screen prices
 * and previews from these copies, so any divergence from the server computation would
 * silently desynchronize the estimate line. Also covers the client-only camera math
 * and face slicer driving the orbit close-up and the slice editor.
 */
public final class CarverMirrorTest {
    public static void main(String[] args) {
        DraftMask cavity = new DraftMask();
        for (int y = 10; y <= 15; y++) for (int x = 3; x <= 12; x++) for (int z = 3; z <= 12; z++) cavity.set(DraftMask.index(x, y, z));
        require(cavity.count() == 600,
                "Client cavity must carve 600 cells");
        DraftMask mask = new DraftMask();
        mask.set(DraftMask.index(3, 4, 5));
        require(DraftMask.decode(mask.encode()).equals(mask),
                "Client mask codec must round-trip");
        require(DraftEstimate.workTicks(640, 1.0, 6, 1.0, 0) == 708
                        && Math.abs(DraftEstimate.staminaCost(640, 1.0, 6, 1.0, 0) - 41.5625) < 1.0e-9,
                "Client estimate must price the reference job at 708 ticks / 41.5625% stamina");
        verifyCameraMath();
        verifyFaceSlicer();
        verifyWorkPhases();
        verifyPhasePayoff();
        verifyChalkQuads();
        verifyBoxSelect();
        verifyCursorPick();
        verifyStrokeLine();
        verifyPickLift();
        verifyHologramFall();
        System.out.println("CarverMirrorTest passed");
    }

    private static void verifyCameraMath() {
        require(CarverCameraMath.easeInOutBack(0.0) == 0.0
                        && CarverCameraMath.easeInOutBack(1.0) == 1.0,
                "Landing ease must start at 0 and end exactly at 1");
        boolean overshoots = false;
        for (int step = 1; step < 100; step++) {
            if (CarverCameraMath.easeInOutBack(step / 100.0) > 1.0) {
                overshoots = true;
                break;
            }
        }
        require(overshoots, "Landing ease must slightly overfly the anchor");
        double[] offset = CarverCameraMath.orbitOffset(0.0,
                CarverCameraMath.ENTRY_PITCH, CarverCameraMath.ENTRY_DIST);
        double length = Math.sqrt(offset[0] * offset[0] + offset[1] * offset[1]
                + offset[2] * offset[2]);
        require(Math.abs(length - CarverCameraMath.ENTRY_DIST) < 1.0e-9,
                "Orbit offset must keep the distance");
        double[] look = CarverCameraMath.lookAt(offset[0], offset[1], offset[2], 0.0, 0.0, 0.0);
        require(Math.abs(look[1] - CarverCameraMath.ENTRY_PITCH) < 1.0e-6,
                "Look-back pitch must mirror the orbit pitch, got " + look[1]);
        require(CarverCameraMath.ENTRY_DIST <= 2.0
                        && CarverCameraMath.ENTRY_PITCH >= 38.0f
                        && CarverCameraMath.ENTRY_CORNER_OFFSET == 45.0f,
                "Entry framing must stay close, above and corner-on");
        require(CarverCameraMath.clampPitch(-10.0f) == CarverCameraMath.MIN_PITCH
                        && CarverCameraMath.clampPitch(100.0f) == CarverCameraMath.MAX_PITCH,
                "Pitch must clamp to the orbit cone");
        require(CarverCameraMath.clampDist(0.1) == CarverCameraMath.MIN_DIST
                        && CarverCameraMath.clampDist(99.0) == CarverCameraMath.MAX_DIST,
                "Zoom must clamp to the close-up range");
        require(CarverCameraMath.lerpAngle(350.0f, 10.0f, 0.5f) > 355.0f
                        || CarverCameraMath.lerpAngle(350.0f, 10.0f, 0.5f) < 5.0f,
                "Angle lerp must take the short arc across 0 degrees");
        double[] work = CarverCameraMath.orbitOffset(0.0,
                CarverCameraMath.WORK_PITCH, CarverCameraMath.WORK_DIST);
        double workLength = Math.sqrt(work[0] * work[0] + work[1] * work[1]
                + work[2] * work[2]);
        require(Math.abs(workLength - CarverCameraMath.WORK_DIST) < 1.0e-9,
                "Work framing must hold its distance");
        double[] workLook = CarverCameraMath.lookAt(
                work[0], work[1], work[2], 0.0, 0.0, 0.0);
        require(Math.abs(workLook[1] - CarverCameraMath.WORK_PITCH) < 1.0e-6,
                "Work framing must look back at the work pitch, got " + workLook[1]);
        require(CarverCameraMath.WORK_DIST > CarverCameraMath.ENTRY_DIST,
                "Work framing must sit wider than the design close-up");
        double[] workFrom = CarverCameraMath.workFraming(50.0, 1.5, 0.0);
        double[] workTo = CarverCameraMath.workFraming(50.0, 1.5, 1.0);
        require(workFrom[0] == 50.0 && workFrom[1] == 1.5,
                "Work transition must start at the design orbit");
        require(workTo[0] == CarverCameraMath.WORK_PITCH
                        && workTo[1] == CarverCameraMath.WORK_DIST,
                "Work transition must land on the work framing");
        for (int step = 1; step <= 20; step++) {
            double[] at = CarverCameraMath.workFraming(50.0, 1.5, step / 20.0);
            require(at[0] >= CarverCameraMath.WORK_PITCH - 3.0 && at[0] <= 50.0 + 3.0
                            && at[1] >= 1.5 - 0.3 && at[1] <= CarverCameraMath.WORK_DIST + 0.3,
                    "Work transition must stay near the corridor, step " + step);
            if (step >= 18) {
                require(Math.abs(at[0] - CarverCameraMath.WORK_PITCH) < 2.0
                                && Math.abs(at[1] - CarverCameraMath.WORK_DIST) < 0.2,
                        "Work transition must settle on target, step " + step);
            }
        }
        System.out.println("CarverWorkFramingTest: transition passed");
    }

    private static void verifyFaceSlicer() {
        require(CarverFaceSlicer.cellFor(CarverFaceSlicer.Face.UP, 5, 7, 0)
                        == DraftMask.index(5, 15, 7),
                "UP layer 0 must be the top skin");
        require(CarverFaceSlicer.cellFor(CarverFaceSlicer.Face.UP, 5, 7, 15)
                        == DraftMask.index(5, 0, 7),
                "UP layer 15 must be the bottom wall");
        require(CarverFaceSlicer.cellFor(CarverFaceSlicer.Face.NORTH, 2, 0, 3)
                        == DraftMask.index(2, 15, 3),
                "NORTH slice row 0 must read the top edge");
        require(CarverFaceSlicer.cellFor(CarverFaceSlicer.Face.SOUTH, 0, 0, 0)
                        == DraftMask.index(15, 15, 15),
                "SOUTH slice must mirror columns");
        int[] slice = CarverFaceSlicer.sliceCells(CarverFaceSlicer.Face.WEST, 4);
        require(slice.length == 256, "Slices must cover 16x16");
        java.util.Set<Integer> unique = new java.util.HashSet<>();
        for (int cell : slice) unique.add(cell);
        require(unique.size() == 256, "Slice cells must be unique");
        for (int cell : slice) {
            require(DraftMask.x(cell) == 4, "WEST layer 4 must sit on x=4");
        }
        require(CarverFaceSlicer.defaultFace(0.0, -1.0, 0.0) == CarverFaceSlicer.Face.UP,
                "Looking down must default to the top face");
        require(CarverFaceSlicer.defaultFace(0.0, 1.0, 0.0) == CarverFaceSlicer.Face.DOWN,
                "Looking up must default to the bottom face");
        require(CarverFaceSlicer.defaultFace(0.0, 0.0, -1.0) == CarverFaceSlicer.Face.SOUTH,
                "Looking north must default to the south face");
        require(CarverFaceSlicer.defaultFace(1.0, 0.0, 0.0) == CarverFaceSlicer.Face.WEST,
                "Looking east must default to the west face");
        boolean rejected = false;
        try {
            CarverFaceSlicer.cellFor(CarverFaceSlicer.Face.UP, 16, 0, 0);
        } catch (IndexOutOfBoundsException expected) {
            rejected = true;
        }
        require(rejected, "Out-of-range slice coordinates must be rejected");
    }

    private static void verifyWorkPhases() {
        require(CarverWorkPhases.phasesCrossed(-1.0, 0.0) == 1,
                "Session start must flush the 0% frame");
        require(CarverWorkPhases.phasesCrossed(0.0, 0.24) == 0,
                "Progress inside a phase must not flush");
        require(CarverWorkPhases.phasesCrossed(0.24, 0.26) == 1,
                "Crossing 25% must flush exactly once");
        require(CarverWorkPhases.phasesCrossed(0.0, 1.0) == 4,
                "A full sweep must cross four marks");
        require(CarverWorkPhases.phasesCrossed(0.5, 0.5) == 0
                        && CarverWorkPhases.phasesCrossed(0.6, 0.5) == 0,
                "Repeated or rewound progress must flush nothing");
        require(CarverWorkPhases.phaseFor(0.0) == 0
                        && CarverWorkPhases.phaseFor(0.24) == 0
                        && CarverWorkPhases.phaseFor(0.25) == 1
                        && CarverWorkPhases.phaseFor(0.99) == 3
                        && CarverWorkPhases.phaseFor(1.0) == 4,
                "Phase indices must step on quarter marks");
    }

    /**
     * Payoff benchmark answering "was phase batching worth it": a simulated 300-tick
     * work with the server progress cadence (every 20 ticks) counts section flushes
     * with batching versus naive per-slice rebuilds. Batching must collapse ~300
     * rebuilds into a handful of phase flushes.
     */
    private static void verifyPhasePayoff() {
        int totalTicks = 300;
        int naiveRebuilds = totalTicks;
        int phasedFlushes = 0;
        double last = -1.0;
        phasedFlushes += CarverWorkPhases.phasesCrossed(last, 0.0);
        last = 0.0;
        for (int done = 20; done <= totalTicks; done += 20) {
            double progress = done / (double) totalTicks;
            phasedFlushes += CarverWorkPhases.phasesCrossed(last, progress);
            last = progress;
        }
        phasedFlushes += 1;
        require(phasedFlushes == 6,
                "A 300-tick work must flush exactly 6 times (start + 4 marks + done), got "
                        + phasedFlushes);
        double reduction = (double) naiveRebuilds / phasedFlushes;
        require(reduction >= 40.0,
                "Phase batching must cut section rebuilds at least 40x, got " + reduction + "x");
        System.out.println("CarverPhasePayoffTest: naive=" + naiveRebuilds
                + " rebuilds phased=" + phasedFlushes + " reduction="
                + String.format(java.util.Locale.ROOT, "%.0f", reduction) + "x");
    }

    private static void verifyChalkQuads() {
        boolean[] full = new boolean[256];
        java.util.Arrays.fill(full, true);
        require(CarverChalkQuads.merge(full).size() == 1,
                "A full grid must merge into one rectangle");
        require(CarverChalkQuads.merge(new boolean[256]).isEmpty(),
                "An empty grid must merge into nothing");
        boolean[] checker = new boolean[256];
        for (int row = 0; row < 16; row++) {
            for (int col = 0; col < 16; col++) checker[row * 16 + col] = (row + col) % 2 == 0;
        }
        require(CarverChalkQuads.merge(checker).size() == 128,
                "A checkerboard must not merge at all");
        boolean[] lShape = new boolean[256];
        for (int col = 0; col < 16; col++) lShape[col] = true;
        for (int row = 0; row < 16; row++) lShape[row * 16] = true;
        require(CarverChalkQuads.merge(lShape).size() == 2,
                "An L-frame must merge into two rectangles");
        DraftMask cavityShell = new DraftMask();
        for (int y = 10; y <= 15; y++) for (int x = 3; x <= 12; x++) for (int z = 3; z <= 12; z++) cavityShell.set(DraftMask.index(x, y, z));
        boolean[] shell = CarverChalkQuads.faceMask(cavityShell,
                CarverFaceSlicer.Face.UP);
        java.util.List<CarverChalkQuads.Rect> rects = CarverChalkQuads.merge(shell);
        int covered = 0;
        boolean[][] seen = new boolean[16][16];
        for (CarverChalkQuads.Rect rect : rects) {
            for (int row = rect.y0(); row < rect.y1(); row++) {
                for (int col = rect.x0(); col < rect.x1(); col++) {
                    require(shell[row * 16 + col] && !seen[row][col],
                            "Merged rectangles must cover exactly the marked set");
                    seen[row][col] = true;
                    covered++;
                }
            }
        }
        int marked = 0;
        for (boolean bit : shell) if (bit) marked++;
        require(covered == marked && marked > 0,
                "Rectangle union must equal the marked shell cells");
        require(rects.size() <= 8,
                "A cavity top must collapse into a handful of quads, got " + rects.size());
        int[] topFull = CarverChalkQuads.rectCells(CarverFaceSlicer.Face.UP, 0,
                new CarverChalkQuads.Rect(0, 0, 16, 16));
        require(topFull[0] == 0 && topFull[1] == 15 && topFull[2] == 0
                        && topFull[3] == 16 && topFull[4] == 16 && topFull[5] == 16,
                "UP layer 0 must span the top slab");
        int[] northDeep = CarverChalkQuads.rectCells(CarverFaceSlicer.Face.NORTH, 3,
                new CarverChalkQuads.Rect(2, 4, 6, 8));
        require(northDeep[0] == 2 && northDeep[1] == 8 && northDeep[2] == 3
                        && northDeep[3] == 6 && northDeep[4] == 12 && northDeep[5] == 4,
                "NORTH rects must map columns to x, rows down from the top, fixed z");
        java.util.Random orientation = new java.util.Random(0xFACE5L);
        for (CarverFaceSlicer.Face face : CarverFaceSlicer.Face.values()) {
            for (int sample = 0; sample < 20; sample++) {
                int col = orientation.nextInt(16);
                int row = orientation.nextInt(16);
                DraftMask single = new DraftMask();
                single.set(CarverFaceSlicer.cellFor(face, col, row, 0));
                java.util.List<CarverChalkQuads.Rect> singleRect =
                        CarverChalkQuads.merge(CarverChalkQuads.faceMask(single, face));
                require(singleRect.size() == 1, "One marked cell must merge into one rect");
                int[] bounds = CarverChalkQuads.faceRectBounds(face, singleRect.get(0));
                int cell = CarverFaceSlicer.cellFor(face, col, row, 0);
                require(bounds[0] == DraftMask.x(cell) && bounds[1] == DraftMask.y(cell)
                                && bounds[2] == DraftMask.z(cell)
                                && bounds[3] == DraftMask.x(cell) + 1
                                && bounds[4] == DraftMask.y(cell) + 1
                                && bounds[5] == DraftMask.z(cell) + 1,
                        "Chalk rects must land on the painted cell for " + face);
            }
        }
        System.out.println("CarverChalkQuadsTest: coverage, merge counts and mapping passed");
        DraftMask strokes = new DraftMask();
        require(!CarverChalkQuads.cellsCleared(0, 0, 0, 15, 15, 15, strokes),
                "Empty draft must hide nothing");
        require(!CarverChalkQuads.cellsCleared(0, 0, 0, 15, 15, 15, null),
                "Missing draft must hide nothing");
        strokes.set(DraftMask.index(0, 15, 0));
        require(CarverChalkQuads.cellsCleared(0, 15, 0, 0, 15, 0, strokes),
                "Fully stroked cell must hide");
        require(!CarverChalkQuads.cellsCleared(0, 15, 0, 1, 15, 0, strokes),
                "Partially stroked span must stay visible");
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) strokes.set(DraftMask.index(x, 15, z));
        }
        require(CarverChalkQuads.cellsCleared(0, 15, 0, 15, 15, 15, strokes),
                "Fully stroked top slab must hide");
        System.out.println("CarverLiveHideTest: stroke hiding passed");
    }

    private static void verifyBoxSelect() {
        int[] flat = CarverBoxSelect.cellsFor(CarverFaceSlicer.Face.UP, 2, 3, 5, 7, 0, 1);
        require(flat.length == 20, "A 4x5 drag at depth 1 must enumerate 20 cells");
        for (int cell : flat) {
            require(DraftMask.y(cell) == 15, "UP layer 0 must sit on the top skin");
        }
        int[] reversed = CarverBoxSelect.cellsFor(CarverFaceSlicer.Face.UP, 5, 7, 2, 3, 0, 1);
        require(new java.util.HashSet<>(toList(flat)).equals(new java.util.HashSet<>(toList(reversed))),
                "Drag direction must not change the box");
        int[] deep = CarverBoxSelect.cellsFor(CarverFaceSlicer.Face.UP, 0, 0, 1, 1, 0, 3);
        require(deep.length == 12, "A 2x2 drag three deep must enumerate 12 cells");
        boolean hasTop = false;
        boolean hasBottom = false;
        for (int cell : deep) {
            if (DraftMask.y(cell) == 15) hasTop = true;
            if (DraftMask.y(cell) == 13) hasBottom = true;
        }
        require(hasTop && hasBottom, "Depth must stack layers inward from the face");
        int[] clipped = CarverBoxSelect.cellsFor(CarverFaceSlicer.Face.UP, 0, 0, 15, 15, 14, 5);
        require(clipped.length == 512, "Depth past the far wall must clip, got " + clipped.length);
        int[] single = CarverBoxSelect.cellsFor(CarverFaceSlicer.Face.NORTH, 4, 4, 4, 4, 2, 1);
        require(single.length == 1 && single[0] == DraftMask.index(4, 11, 2),
                "A click without drag must select exactly its cell");
        require(CarverBoxSelect.countFor(2, 3, 5, 7, 0, 1) == 20
                        && CarverBoxSelect.countFor(0, 0, 15, 15, 14, 5) == 512,
                "Count estimates must match enumeration");
        java.util.Random random = new java.util.Random(0xB08E5L);
        CarverFaceSlicer.Face[] faces = CarverFaceSlicer.Face.values();
        for (int trial = 0; trial < 300; trial++) {
            CarverFaceSlicer.Face face = faces[random.nextInt(faces.length)];
            int c0 = random.nextInt(16);
            int r0 = random.nextInt(16);
            int c1 = random.nextInt(16);
            int r1 = random.nextInt(16);
            int layer = random.nextInt(16);
            int depth = 1 + random.nextInt(16);
            int[] cells = CarverBoxSelect.cellsFor(face, c0, r0, c1, r1, layer, depth);
            int[] bounds = CarverBoxSelect.boundsFor(face, c0, r0, c1, r1, layer, depth);
            int x0 = 16;
            int y0 = 16;
            int z0 = 16;
            int x1 = -1;
            int y1 = -1;
            int z1 = -1;
            java.util.Set<Integer> unique = new java.util.HashSet<>();
            for (int cell : cells) {
                unique.add(cell);
                int x = DraftMask.x(cell);
                int y = DraftMask.y(cell);
                int z = DraftMask.z(cell);
                if (x < x0) x0 = x;
                if (y < y0) y0 = y;
                if (z < z0) z0 = z;
                if (x > x1) x1 = x;
                if (y > y1) y1 = y;
                if (z > z1) z1 = z;
            }
            require(unique.size() == cells.length, "Box cells must be unique");
            require(bounds[0] == x0 && bounds[1] == y0 && bounds[2] == z0
                            && bounds[3] == x1 && bounds[4] == y1 && bounds[5] == z1,
                    "O(1) bounds must match enumeration on trial " + trial);
            require(cells.length == CarverBoxSelect.countFor(c0, r0, c1, r1, layer, depth),
                    "Count must match enumeration on trial " + trial);
        }
        System.out.println("CarverBoxSelectTest: bounds, depth, clipping and counts passed");
    }

    private static java.util.List<Integer> toList(int[] cells) {
        java.util.List<Integer> list = new java.util.ArrayList<>(cells.length);
        for (int cell : cells) list.add(cell);
        return list;
    }

    private static void verifyCursorPick() {
        CarverCursorPick.Hit south = CarverCursorPick.pick(
                0.5, 0.5, 3.0, 180.0f, 0.0f, 70.0, 800, 600, 400.0, 300.0, 0, 0, 0);
        require(south != null && south.face() == CarverFaceSlicer.Face.SOUTH
                        && DraftMask.z(south.cell()) == 15,
                "Screen center aimed north must hit the south face");
        CarverCursorPick.Hit top = CarverCursorPick.pick(
                0.5, 3.0, 0.5, 0.0f, 90.0f, 70.0, 800, 600, 400.0, 300.0, 0, 0, 0);
        require(top != null && top.face() == CarverFaceSlicer.Face.UP
                        && DraftMask.y(top.cell()) == 15,
                "Top-down aim must hit the upper face");
        CarverCursorPick.Hit miss = CarverCursorPick.pick(
                0.5, 0.5, 3.0, 0.0f, 0.0f, 70.0, 800, 600, 400.0, 300.0, 0, 0, 0);
        require(miss == null, "Aim away from the block must miss");
        CarverCursorPick.Hit edge = CarverCursorPick.pick(
                0.5, 0.5, 3.0, 180.0f, 0.0f, 70.0, 800, 600, 799.0, 300.0, 0, 0, 0);
        require(edge == null || DraftMask.z(edge.cell()) == 15
                        || DraftMask.x(edge.cell()) == 15 || DraftMask.x(edge.cell()) == 0,
                "Far screen edge must miss or clip a side face");
        for (CarverFaceSlicer.Face face : CarverFaceSlicer.Face.values()) {
            for (int sample = 0; sample < 25; sample++) {
                int col = sample % 16;
                int row = (sample * 7) % 16;
                int layer = sample % 16;
                int cell = CarverFaceSlicer.cellFor(face, col, row, layer);
                int[] back = CarverFaceSlicer.inverse(face, cell);
                require(back[0] == col && back[1] == row && back[2] == layer,
                        "Inverse must round-trip " + face + " at " + col + "," + row + "," + layer);
            }
        }
        System.out.println("CarverCursorPickTest: faces, miss and inverse passed");
    }

    private static void verifyStrokeLine() {
        int straight = DraftMask.index(2, 4, 6);
        int[] single = CarverStrokeLine.cellsBetween(straight, straight);
        require(single.length == 1 && single[0] == straight,
                "Zero-length drag must paint exactly its cell");
        int[] diagonal = CarverStrokeLine.cellsBetween(
                DraftMask.index(0, 0, 0), DraftMask.index(3, 0, 3));
        require(diagonal.length == 4, "Diagonal must fill every step, got " + diagonal.length);
        for (int step = 0; step < 4; step++) {
            require(diagonal[step] == DraftMask.index(step, 0, step),
                    "Diagonal must walk x==z at step " + step);
        }
        int[] longLine = CarverStrokeLine.cellsBetween(
                DraftMask.index(0, 15, 0), DraftMask.index(15, 0, 15));
        require(longLine.length == 16, "Corner-to-corner must span 16 cells");
        require(longLine[0] == DraftMask.index(0, 15, 0)
                        && longLine[15] == DraftMask.index(15, 0, 15),
                "Endpoints must be exact");
        java.util.Set<Integer> unique = new java.util.HashSet<>();
        for (int cell : longLine) unique.add(cell);
        require(unique.size() == longLine.length, "Line cells must not repeat");
        System.out.println("CarverStrokeLineTest: diagonals and endpoints passed");
    }

    private static void verifyHologramFall() {
        // Touchdown lands exactly when the fall clock reaches FALL_TICKS: the same
        // tick the hologram replays dust, shake and sound for.
        double rest = 0.75;
        double beforeLast = rest * (1.0 - ua.rp.chat.carver.CarverHologramMotion.ease(
                (ua.rp.chat.carver.CarverHologramMotion.FALL_TICKS - 1.0)
                        / ua.rp.chat.carver.CarverHologramMotion.FALL_TICKS));
        require(beforeLast > 0.0, "The copy must still be airborne one tick before touchdown");
        double touchdown = rest * (1.0 - ua.rp.chat.carver.CarverHologramMotion.ease(1.0));
        require(touchdown == 0.0, "The copy must touch down exactly on the final fall tick");
        require(ua.rp.chat.carver.CarverHologramMotion.FALL_TICKS
                        < ua.rp.chat.carver.CarverHologramMotion.RISE_TICKS,
                "The fall must stay shorter than the rise");
        System.out.println("CarverHologramFallTest: impact timing passed");
    }

    private static void verifyPickLift() {
        CarverCursorPick.Hit lifted = CarverCursorPick.pick(
                0.5, 1.25, 3.0, 180.0f, 0.0f, 70.0, 800, 600, 400.0, 300.0, 0, 0.75, 0);
        require(lifted != null && lifted.face() == CarverFaceSlicer.Face.SOUTH
                        && DraftMask.z(lifted.cell()) == 15,
                "Lifted cube must pick the same face and cells");
        System.out.println("CarverPickLiftTest: hologram offset passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException("CarverMirrorTest: " + message);
    }
}
