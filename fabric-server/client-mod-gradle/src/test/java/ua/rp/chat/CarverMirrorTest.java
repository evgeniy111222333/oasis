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
        verifyWorkStroke();
        verifyWorkAnim();
        verifyHumanRhythm();
        verifyInspection();
        verifyGrainMechanics();
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

    /**
     * Strike beat used by the work animation: full raise before a sharp contact drop, a
     * one-sided impact shock and a drive that ramps into the blow then settles to zero.
     */
    private static void verifyWorkStroke() {
        require(ua.rp.chat.carver.CarverWorkStroke.lift(0.0) == 0.0,
                "The striker must start down at phase 0");
        require(ua.rp.chat.carver.CarverWorkStroke.lift(0.65) > 0.99,
                "The striker must be fully raised before the strike");
        require(ua.rp.chat.carver.CarverWorkStroke.lift(0.9) < 0.05,
                "The striker must be on the stone at contact");
        require(ua.rp.chat.carver.CarverWorkStroke.contact(0.95) > 0.9
                        && ua.rp.chat.carver.CarverWorkStroke.contact(0.5) == 0.0,
                "Contact must pulse only inside the impact window");
        require(ua.rp.chat.carver.CarverWorkStroke.drive(0.5) == 0.0
                        && ua.rp.chat.carver.CarverWorkStroke.drive(0.9) > 0.99
                        && ua.rp.chat.carver.CarverWorkStroke.drive(1.0) == 0.0,
                "Drive must ramp into the blow and settle back to zero");
        require(ua.rp.chat.carver.CarverWorkStroke.shock(0.5) == 0.0
                        && ua.rp.chat.carver.CarverWorkStroke.shock(0.9) == 1.0
                        && ua.rp.chat.carver.CarverWorkStroke.shock(1.0) == 0.0,
                "Shock must be a non-negative bump that decays to zero");
        for (double t = 0.0; t < 1.0; t += 0.01) {
            double shock = ua.rp.chat.carver.CarverWorkStroke.shock(t);
            double drive = ua.rp.chat.carver.CarverWorkStroke.drive(t);
            require(shock >= 0.0 && shock <= 1.0, "Shock must stay in [0,1] at " + t);
            require(drive >= 0.0 && drive <= 1.0, "Drive must stay in [0,1] at " + t);
        }
        System.out.println("CarverWorkStrokeTest: anticipation, contact, drive and shock passed");
    }

    /** Material classifier, stages, strike type and the fatigue/stage pose multipliers. */
    private static void verifyWorkAnim() {
        require(ua.rp.chat.carver.CarverWorkAnim.classify("minecraft:stone")
                        == ua.rp.chat.carver.CarverWorkAnim.Material.STONE,
                "Stone must classify as stone");
        require(ua.rp.chat.carver.CarverWorkAnim.classify("minecraft:oak_planks")
                        == ua.rp.chat.carver.CarverWorkAnim.Material.WOOD,
                "Planks must classify as wood");
        require(ua.rp.chat.carver.CarverWorkAnim.classify("minecraft:iron_block")
                        == ua.rp.chat.carver.CarverWorkAnim.Material.METAL,
                "Iron must classify as metal");
        require(ua.rp.chat.carver.CarverWorkAnim.classify("minecraft:blue_ice")
                        == ua.rp.chat.carver.CarverWorkAnim.Material.ICE,
                "Blue ice must classify as ice");
        require(ua.rp.chat.carver.CarverWorkAnim.classify("minecraft:glass")
                        == ua.rp.chat.carver.CarverWorkAnim.Material.GLASS,
                "Glass must classify as glass");
        require(ua.rp.chat.carver.CarverWorkAnim.classify("minecraft:white_wool")
                        == ua.rp.chat.carver.CarverWorkAnim.Material.CLOTH,
                "Wool must classify as cloth");
        require(ua.rp.chat.carver.CarverWorkAnim.classify("")
                        == ua.rp.chat.carver.CarverWorkAnim.Material.GENERIC,
                "Blank id must classify as generic");
        require(ua.rp.chat.carver.CarverWorkAnim.stage(0.10)
                        == ua.rp.chat.carver.CarverWorkAnim.Stage.ROUGH
                        && ua.rp.chat.carver.CarverWorkAnim.stage(0.50)
                        == ua.rp.chat.carver.CarverWorkAnim.Stage.MAIN
                        && ua.rp.chat.carver.CarverWorkAnim.stage(0.95)
                        == ua.rp.chat.carver.CarverWorkAnim.Stage.FINE,
                "Progress stages must split at 34% and 80%");
        require(ua.rp.chat.carver.CarverWorkAnim.strikeType(2)
                        == ua.rp.chat.carver.CarverWorkAnim.StrikeType.POINT_CHISEL
                        && ua.rp.chat.carver.CarverWorkAnim.strikeType(1)
                        == ua.rp.chat.carver.CarverWorkAnim.StrikeType.FLAT_MALLET,
                "Off-hand chisel code must select the strike type");
        var rough = ua.rp.chat.carver.CarverWorkAnim.pose(
                ua.rp.chat.carver.CarverWorkAnim.Stage.ROUGH,
                ua.rp.chat.carver.CarverWorkAnim.StrikeType.FLAT_MALLET, 0.0,
                ua.rp.chat.carver.CarverWorkAnim.Material.STONE);
        var fine = ua.rp.chat.carver.CarverWorkAnim.pose(
                ua.rp.chat.carver.CarverWorkAnim.Stage.FINE,
                ua.rp.chat.carver.CarverWorkAnim.StrikeType.POINT_CHISEL, 0.0,
                ua.rp.chat.carver.CarverWorkAnim.Material.STONE);
        require(rough.twoHanded(), "Rough mallet work must read as two-handed");
        require(fine.amplitude() < rough.amplitude(),
                "Fine detailing must strike smaller than rough removal");
        var fresh = ua.rp.chat.carver.CarverWorkAnim.pose(
                ua.rp.chat.carver.CarverWorkAnim.Stage.MAIN,
                ua.rp.chat.carver.CarverWorkAnim.StrikeType.FLAT_MALLET, 0.0,
                ua.rp.chat.carver.CarverWorkAnim.Material.STONE);
        var tired = ua.rp.chat.carver.CarverWorkAnim.pose(
                ua.rp.chat.carver.CarverWorkAnim.Stage.MAIN,
                ua.rp.chat.carver.CarverWorkAnim.StrikeType.FLAT_MALLET, 1.0,
                ua.rp.chat.carver.CarverWorkAnim.Material.STONE);
        require(tired.tempo() < fresh.tempo() && tired.lean() > fresh.lean(),
                "Fatigue must slow the tempo and slump the lean");
        require(ua.rp.chat.carver.CarverWorkAnim.sparks(
                        ua.rp.chat.carver.CarverWorkAnim.Material.METAL)
                        && ua.rp.chat.carver.CarverWorkAnim.shards(
                        ua.rp.chat.carver.CarverWorkAnim.Material.ICE)
                        && ua.rp.chat.carver.CarverWorkAnim.splinters(
                        ua.rp.chat.carver.CarverWorkAnim.Material.WOOD),
                "Material impact classes must select sparks, shards and splinters");
        System.out.println("CarverWorkAnimTest: material, stages, strike type and pose passed");
    }

    /** Humanized rhythm: deterministic per seed, bounded, monotonic and interval-exact. */
    private static void verifyHumanRhythm() {
        long seed = 0xEC12A5EL;
        var start = ua.rp.chat.carver.CarverWorkStroke.placement(0.0, 200, seed);
        require(start.index() == 0 && start.cycle() == 0.0,
                "The first tick must open strike 0 at phase 0");
        for (double tick = 0.0; tick <= 200.0; tick += 5.0) {
            var p = ua.rp.chat.carver.CarverWorkStroke.placement(tick, 200, seed);
            require(p.cycle() >= 0.0 && p.cycle() < 1.0,
                    "Humanized cycle must stay in [0,1) at " + tick);
        }
        int previous = -1;
        for (double tick = 0.0; tick <= 200.0; tick += 1.0) {
            int index = ua.rp.chat.carver.CarverWorkStroke.placement(tick, 200, seed).index();
            require(index >= previous, "Strike index must never go backwards");
            previous = index;
        }
        int strikes = ua.rp.chat.carver.CarverWorkStroke.strikesFor(200);
        require(ua.rp.chat.carver.CarverWorkStroke.placement(200.0, 200, seed).index() == strikes - 1,
                "The last tick must land on the final strike");
        boolean differs = false;
        for (double tick = 0.0; tick <= 200.0; tick += 3.0) {
            if (ua.rp.chat.carver.CarverWorkStroke.placement(tick, 200, seed).cycle()
                    != ua.rp.chat.carver.CarverWorkStroke.placement(tick, 200, seed ^ 1L).cycle()) {
                differs = true;
                break;
            }
        }
        require(differs, "Different artisans must not share one metronome");
        System.out.println("CarverHumanRhythmTest: determinism, bounds and rhythm passed");
    }

    /** Grain 2.0: seam map, palette contrast, cut mechanics and scoring curves. */
    private static void verifyGrainMechanics() {
        require(ua.rp.chat.carver.CarverGrainField.seedFor(1, 2, 3)
                        == ua.rp.chat.carver.CarverGrainField.seedFor(1, 2, 3),
                "Grain seed must be deterministic");
        var grain = ua.rp.chat.carver.CarverGrainField.build(2024L,
                ua.rp.chat.carver.CarverGrainField.GrainType.LAYERS);
        boolean seam = false;
        boolean interior = false;
        for (int y = 0; y < 16; y++) {
            int cell = DraftMask.index(8, y, 8);
            double b = grain.boundaryness(cell);
            require(b >= 0.0 && b <= 1.0, "Boundaryness must be within [0,1]");
            if (b >= 0.5) seam = true;
            if (b <= 0.1) interior = true;
        }
        require(seam, "Layered grain must expose at least one seam cell");
        require(interior, "Layered grain must expose interior cells away from a seam");
        require(ua.rp.chat.carver.CarverGrainPalette.tint(
                        ua.rp.chat.carver.CarverGrainField.GrainType.AMORPHOUS, 3, 1.0, 0.0)
                        == 0xFFFFFFFF,
                "Grainless palette must be identity");
        int a = ua.rp.chat.carver.CarverGrainPalette.tint(
                ua.rp.chat.carver.CarverGrainField.GrainType.LAYERS, 0, 0.8, 0.0);
        int b = ua.rp.chat.carver.CarverGrainPalette.tint(
                ua.rp.chat.carver.CarverGrainField.GrainType.LAYERS, 1, 0.8, 0.0);
        require((a >>> 24) == 0xFF, "Palette alpha must stay opaque");
        require(a != b, "Adjacent grain domains must shade differently");
        int seamed = ua.rp.chat.carver.CarverGrainPalette.tint(
                ua.rp.chat.carver.CarverGrainField.GrainType.LAYERS, 0, 0.8, 1.0);
        require(luma(seamed) < luma(a), "A seam must darken its cell");
        int tinted = ua.rp.chat.carver.CarverGrainTint.apply(0xFF7A9A6A,
                ua.rp.chat.carver.CarverGrainField.GrainType.LAYERS, 0, 0.8, 0.5);
        require((tinted >>> 24) == 0xFF, "Grain tint must preserve alpha");
        require(tinted == ua.rp.chat.carver.CarverGrainTint.apply(0xFF7A9A6A,
                        ua.rp.chat.carver.CarverGrainField.GrainType.LAYERS, 0, 0.8, 0.5),
                "Grain tint must be deterministic");
        var along = new ua.rp.chat.carver.CarverGrainField.Direction(1, 0, 0);
        var cross = new ua.rp.chat.carver.CarverGrainField.Direction(-1, 0, 0);
        require(ua.rp.chat.carver.CarverGrainMechanics.classify(along, along)
                        == ua.rp.chat.carver.CarverGrainMechanics.Alignment.ALONG,
                "A parallel cut must ride the grain");
        require(ua.rp.chat.carver.CarverGrainMechanics.classify(cross, along)
                        == ua.rp.chat.carver.CarverGrainMechanics.Alignment.CROSS,
                "An opposed cut must fight the grain");
        require(ua.rp.chat.carver.CarverGrainMechanics.hardnessMultiplier(
                        ua.rp.chat.carver.CarverGrainMechanics.Alignment.ALONG, 1.0)
                        < ua.rp.chat.carver.CarverGrainMechanics.hardnessMultiplier(
                        ua.rp.chat.carver.CarverGrainMechanics.Alignment.CROSS, 1.0),
                "Cutting across the grain must be harder");
        require(ua.rp.chat.carver.CarverGrainMechanics.wearFactor(
                        ua.rp.chat.carver.CarverGrainMechanics.Alignment.CROSS, 1.0)
                        > ua.rp.chat.carver.CarverGrainMechanics.wearFactor(
                        ua.rp.chat.carver.CarverGrainMechanics.Alignment.ALONG, 1.0),
                "Cross-grain cuts must wear the tool faster");
        require(ua.rp.chat.carver.CarverGrainMechanics.heatFactor(
                        ua.rp.chat.carver.CarverGrainMechanics.Alignment.CROSS, 1.0)
                        > ua.rp.chat.carver.CarverGrainMechanics.heatFactor(
                        ua.rp.chat.carver.CarverGrainMechanics.Alignment.ALONG, 1.0),
                "Cross-grain cuts must run hotter");
        require(ua.rp.chat.carver.CarverGrainMechanics.tearOutChance(
                        ua.rp.chat.carver.CarverGrainMechanics.Alignment.CROSS, 1.0, 1.0)
                        > ua.rp.chat.carver.CarverGrainMechanics.tearOutChance(
                        ua.rp.chat.carver.CarverGrainMechanics.Alignment.ALONG, 1.0, 1.0),
                "Cross-grain cuts must tear out more often");
        double good = ua.rp.chat.carver.CarverGrainMechanics.respectScore(10, 0, 0);
        double bad = ua.rp.chat.carver.CarverGrainMechanics.respectScore(0, 0, 10);
        require(good > bad, "Riding the grain must outscore fighting it");
        require(ua.rp.chat.carver.CarverGrainMechanics.grade(0.95).equals("майстерна")
                        && ua.rp.chat.carver.CarverGrainMechanics.grade(0.6).equals("чиста")
                        && ua.rp.chat.carver.CarverGrainMechanics.grade(0.3).equals("груба"),
                "Grade bands must map rough/clean/masterful");
        require(ua.rp.chat.carver.CarverGrainMechanics.masteryPoints(1.0, 100)
                        > ua.rp.chat.carver.CarverGrainMechanics.masteryPoints(0.0, 100),
                "Higher grain respect must pay more mastery");
        DraftMask band = new DraftMask();
        int targetDomain = grain.domain(DraftMask.index(8, 8, 8));
        for (int cell = 0; cell < DraftMask.CELL_COUNT; cell++) {
            if (grain.domain(cell) == targetDomain) band.set(cell);
        }
        DraftMask half = new DraftMask();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = 0; y < 8; y++) half.set(DraftMask.index(x, y, z));
            }
        }
        require(ua.rp.chat.carver.CarverGrainMechanics.draftRespect(band, grain)
                        > ua.rp.chat.carver.CarverGrainMechanics.draftRespect(half, grain),
                "A seam-following draft must out-score a cross-grain draft");
        System.out.println("CarverGrainMechanicsTest: seam, palette and cut curves passed");
    }

    private static double luma(int argb) {
        return 0.299 * ((argb >> 16) & 0xFF) + 0.587 * ((argb >> 8) & 0xFF) + 0.114 * (argb & 0xFF);
    }

    /** Phase 0 pure models: grain families, inclusion hints and the material readout. */
    private static void verifyInspection() {
        require(ua.rp.chat.carver.CarverGrainField.typeFor(
                        ua.rp.chat.carver.CarverWorkAnim.Material.STONE)
                        == ua.rp.chat.carver.CarverGrainField.GrainType.LAYERS,
                "Stone grain must be layered bedding");
        require(ua.rp.chat.carver.CarverGrainField.typeFor(
                        ua.rp.chat.carver.CarverWorkAnim.Material.WOOD)
                        == ua.rp.chat.carver.CarverGrainField.GrainType.FIBERS,
                "Wood grain must be fibers");
        require(ua.rp.chat.carver.CarverGrainField.typeFor(
                        ua.rp.chat.carver.CarverWorkAnim.Material.METAL)
                        == ua.rp.chat.carver.CarverGrainField.GrainType.CRYSTALS,
                "Metal grain must be crystals");
        require(ua.rp.chat.carver.CarverGrainField.typeFor(
                        ua.rp.chat.carver.CarverWorkAnim.Material.CLOTH)
                        == ua.rp.chat.carver.CarverGrainField.GrainType.WOVEN,
                "Cloth grain must be woven");
        require(ua.rp.chat.carver.CarverGrainField.typeFor(
                        ua.rp.chat.carver.CarverWorkAnim.Material.ICE)
                        == ua.rp.chat.carver.CarverGrainField.GrainType.AMORPHOUS,
                "Ice grain must be amorphous");
        // Coherent noise: deterministic, in range and continuous across cell boundaries.
        double n1 = ua.rp.chat.carver.CarverNoise.value3(5L, 1.25, 2.5, 3.75);
        double n2 = ua.rp.chat.carver.CarverNoise.value3(5L, 1.25, 2.5, 3.75);
        require(n1 == n2 && n1 >= 0.0 && n1 < 1.0,
                "Noise must be deterministic and within [0,1)");
        double near = ua.rp.chat.carver.CarverNoise.value3(5L, 1.2501, 2.5, 3.75);
        require(Math.abs(near - n1) < 0.05, "Noise must be continuous across the cell boundary");
        double fbm = ua.rp.chat.carver.CarverNoise.fbm3(9L, 3.3, 4.4, 5.5, 3);
        require(fbm >= 0.0 && fbm <= 1.0, "fBm must stay within [0,1]");
        // Coherent grain field: deterministic, axis-quantized, banded, bounded by family.
        int cell = DraftMask.index(3, 7, 9);
        var layers = ua.rp.chat.carver.CarverGrainField.build(42L,
                ua.rp.chat.carver.CarverGrainField.GrainType.LAYERS);
        var layersAgain = ua.rp.chat.carver.CarverGrainField.build(42L,
                ua.rp.chat.carver.CarverGrainField.GrainType.LAYERS);
        require(layers.domain(cell) == layersAgain.domain(cell)
                        && layers.strength(cell) == layersAgain.strength(cell)
                        && layers.direction(cell).equals(layersAgain.direction(cell)),
                "Grain field must be deterministic from the seed");
        for (int sample = 0; sample < DraftMask.CELL_COUNT; sample += 137) {
            var dir = layers.direction(sample);
            require(Math.abs(dir.x()) + Math.abs(dir.y()) + Math.abs(dir.z()) == 1,
                    "Field direction must be a unit lattice axis");
            double layerStrength = layers.strength(sample);
            require(layerStrength >= 0.0 && layerStrength <= 1.0,
                    "Field strength must be within [0,1]");
        }
        boolean banded = false;
        for (int y = 0; y < 15; y++) {
            if (layers.domain(DraftMask.index(8, y, 8))
                    != layers.domain(DraftMask.index(8, y + 1, 8))) {
                banded = true;
                break;
            }
        }
        require(banded, "Sedimentary layers must band along the vertical");
        var crystals = ua.rp.chat.carver.CarverGrainField.build(7L,
                ua.rp.chat.carver.CarverGrainField.GrainType.CRYSTALS);
        for (int sample = 0; sample < DraftMask.CELL_COUNT; sample += 211) {
            int domain = crystals.domain(sample);
            require(domain >= 0 && domain < 18,
                    "Crystal domains must stay within the seed count");
        }
        var woven = ua.rp.chat.carver.CarverGrainField.build(3L,
                ua.rp.chat.carver.CarverGrainField.GrainType.WOVEN);
        java.util.HashSet<Integer> weave = new java.util.HashSet<>();
        for (int wx = 0; wx < 8; wx++) {
            for (int wz = 0; wz < 8; wz++) {
                weave.add(woven.domain(DraftMask.index(wx, 4, wz)));
            }
        }
        require(weave.size() == 4, "Woven cloth must show four weave cells");
        var amorphous = ua.rp.chat.carver.CarverGrainField.build(1L,
                ua.rp.chat.carver.CarverGrainField.GrainType.AMORPHOUS);
        require(!amorphous.hasGrain() && amorphous.strength(cell) == 0.0,
                "Amorphous materials must have no grain");
        var projected = ua.rp.chat.carver.CarverGrainField.projected(
                new ua.rp.chat.carver.CarverGrainField.Direction(1, 1, 1),
                CarverFaceSlicer.Face.UP);
        require(projected.y() == 0, "Grain projected on the top face must stay in-plane");

        // Mesh-baked grain tint: grainless identity, band variation, alpha kept, deterministic.
        int baseColor = 0xFF7A9A6A;
        require(ua.rp.chat.carver.CarverGrainTint.apply(baseColor,
                        ua.rp.chat.carver.CarverGrainField.GrainType.AMORPHOUS, 3, 1.0) == baseColor,
                "Grainless materials must not be tinted");
        int tintA = ua.rp.chat.carver.CarverGrainTint.apply(baseColor,
                ua.rp.chat.carver.CarverGrainField.GrainType.LAYERS, 0, 0.8);
        int tintB = ua.rp.chat.carver.CarverGrainTint.apply(baseColor,
                ua.rp.chat.carver.CarverGrainField.GrainType.LAYERS, 1, 0.8);
        require((tintA >>> 24) == 0xFF, "Grain tint must preserve the alpha channel");
        require(tintA != tintB, "Adjacent grain domains must tint differently");
        require(tintA == ua.rp.chat.carver.CarverGrainTint.apply(baseColor,
                        ua.rp.chat.carver.CarverGrainField.GrainType.LAYERS, 0, 0.8),
                "Grain tint must be deterministic");
        require(ua.rp.chat.carver.CarverGrainTint.faceCell(
                        ua.rp.chat.microvoxel.MicrovoxelGreedyMesher.Direction.UP, 2, 4, 4)
                        == DraftMask.index(2, 3, 4),
                "An UP face must resolve to the cell under its plane");
        require(ua.rp.chat.carver.CarverGrainTint.faceCell(
                        ua.rp.chat.microvoxel.MicrovoxelGreedyMesher.Direction.EAST, 5, 3, 4)
                        == DraftMask.index(4, 3, 4),
                "An EAST face must resolve to the cell behind its plane");
        System.out.println("CarverGrainTintTest: bake tint and face mapping passed");
        var structures = ua.rp.chat.carver.CarverInclusionField.structures(99L,
                ua.rp.chat.carver.CarverWorkAnim.Material.STONE);
        var structuresAgain = ua.rp.chat.carver.CarverInclusionField.structures(99L,
                ua.rp.chat.carver.CarverWorkAnim.Material.STONE);
        require(structures.size() == structuresAgain.size(),
                "Inclusion structures must be deterministic");
        require(structures.size() <= ua.rp.chat.carver.CarverInclusionField
                        .structureCount(ua.rp.chat.carver.CarverWorkAnim.Material.STONE),
                "Structure count must respect the material budget");
        for (int i = 0; i < structures.size(); i++) {
            var a = structures.get(i);
            var b = structuresAgain.get(i);
            require(a.kind() == b.kind() && a.tier() == b.tier()
                            && java.util.Arrays.equals(a.cells(), b.cells()),
                    "Structure #" + i + " must be identical across builds");
            require(a.cells().length > 0, "Structures must occupy at least one voxel");
            require(a.radius() > 0.0, "Structures must have a bounding radius");
            java.util.HashSet<Integer> unique = new java.util.HashSet<>();
            for (int structureCell : a.cells()) {
                require(structureCell >= 0 && structureCell < DraftMask.CELL_COUNT,
                        "Structure cells must stay inside the volume");
                require(unique.add(structureCell), "Structure cells must be unique");
            }
            if (a.kind() != ua.rp.chat.carver.CarverInclusionField.Kind.CAVITY) {
                require(connected(a.cells()),
                        "Crack and vein structures must form one connected voxel set");
            }
        }
        require(ua.rp.chat.carver.CarverInclusionField.baseChance(
                        ua.rp.chat.carver.CarverWorkAnim.Material.METAL)
                        > ua.rp.chat.carver.CarverInclusionField.baseChance(
                        ua.rp.chat.carver.CarverWorkAnim.Material.CLOTH),
                "Metals must hide more inclusions than cloth");
        var readout = ua.rp.chat.carver.CarverMaterialView.of("minecraft:deepslate", 3.0f);
        require(readout.name().equals("Deepslate"), "Readout must strip the namespace");
        require(readout.workability() > 0.0 && readout.workability() <= 1.0,
                "Workability must be a 0..1 fraction");
        require(!ua.rp.chat.carver.CarverMaterialView.materialLabel(readout.material()).isBlank()
                        && !ua.rp.chat.carver.CarverMaterialView.grainLabel(readout.grain()).isBlank(),
                "Readout labels must be present");
        System.out.println("CarverInspectionTest: grain, inclusions and readout passed");
    }

    /** 6-neighbour connectivity of a voxel set: proves a structure is one linked inclusion. */
    private static boolean connected(int[] cells) {
        java.util.HashSet<Integer> set = new java.util.HashSet<>();
        for (int cell : cells) set.add(cell);
        java.util.ArrayDeque<Integer> queue = new java.util.ArrayDeque<>();
        java.util.HashSet<Integer> seen = new java.util.HashSet<>();
        queue.add(cells[0]);
        seen.add(cells[0]);
        int[][] dirs = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        while (!queue.isEmpty()) {
            int cell = queue.removeFirst();
            int x = DraftMask.x(cell);
            int y = DraftMask.y(cell);
            int z = DraftMask.z(cell);
            for (int[] dir : dirs) {
                int nx = x + dir[0];
                int ny = y + dir[1];
                int nz = z + dir[2];
                if (nx < 0 || nx > 15 || ny < 0 || ny > 15 || nz < 0 || nz > 15) continue;
                int next = DraftMask.index(nx, ny, nz);
                if (set.contains(next) && seen.add(next)) queue.add(next);
            }
        }
        return seen.size() == set.size();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException("CarverMirrorTest: " + message);
    }
}
