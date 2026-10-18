package ua.rp.chat;

import ua.rp.chat.carver.CarverBoxSelect;
import ua.rp.chat.carver.CarverCameraMath;
import ua.rp.chat.carver.CarverChalkQuads;
import ua.rp.chat.carver.CarverCursorPick;
import ua.rp.chat.carver.CarverFaceSlicer;
import ua.rp.chat.carver.CarverHologramMotion;
import ua.rp.chat.carver.CarverHologramOffset;
import ua.rp.chat.carver.DraftMask;

/**
 * Guards the hologram-anchoring contract: the copy always lifts straight up, steps
 * sideways only away from solid side neighbours, lands back into its socket, and
 * every consumer (camera target, picking hitbox, chalk frame, box select) resolves
 * against the same anchor.
 *
 * <p>Pure JVM test in the mirror-test style (main entry, no Minecraft bootstrap).</p>
 */
public final class CarverHologramOffsetTest {
    public static void main(String[] args) {
        verifyOffsetDirections();
        verifyOffsetClamp();
        verifyFalloff();
        verifyMotionEasing();
        verifyPickFollowsOffset();
        verifyCameraTargetsLiftedAnchor();
        verifyBoxDragCoversArea();
        verifyFingerprint();
        System.out.println("CarverHologramOffsetTest passed");
    }

    private static void verifyOffsetDirections() {
        double[] clear = CarverHologramOffset.compute(false, false, false, false);
        require(clear[0] == 0.0 && clear[1] == 0.0,
                "Clear surroundings must lift strictly vertical");
        double[] east = CarverHologramOffset.compute(true, false, false, false);
        require(east[0] < 0.0 && east[1] == 0.0,
                "East wall must push the copy west, got " + east[0] + "," + east[1]);
        double[] west = CarverHologramOffset.compute(false, true, false, false);
        require(west[0] > 0.0 && west[1] == 0.0,
                "West wall must push the copy east");
        double[] north = CarverHologramOffset.compute(false, false, true, false);
        require(north[0] == 0.0 && north[1] > 0.0,
                "North wall (-Z) must push the copy south (+Z)");
        double[] south = CarverHologramOffset.compute(false, false, false, true);
        require(south[0] == 0.0 && south[1] < 0.0,
                "South wall must push the copy north");
        double[] corridor = CarverHologramOffset.compute(true, true, false, false);
        require(corridor[0] == 0.0 && corridor[1] == 0.0,
                "Opposing walls must cancel out, no sideways drift");
        double[] shaft = CarverHologramOffset.compute(true, true, true, true);
        require(shaft[0] == 0.0 && shaft[1] == 0.0,
                "A fully enclosed socket must still lift straight up");
    }

    private static void verifyOffsetClamp() {
        double max = CarverHologramOffset.MAX_LATERAL;
        require(max > 0.0 && max <= 0.5,
                "Nudge must stay inside the socket column, got " + max);
        CarverFaceSlicer.Face[] faces = CarverFaceSlicer.Face.values();
        for (int mask = 1; mask < 16; mask++) {
            double[] off = CarverHologramOffset.compute(
                    (mask & 1) != 0, (mask & 2) != 0, (mask & 4) != 0, (mask & 8) != 0);
            double length = Math.sqrt(off[0] * off[0] + off[1] * off[1]);
            require(length <= max + 1.0e-9,
                    "Nudge must never exceed the cap, got " + length);
        }
        double[] corner = CarverHologramOffset.compute(true, false, true, false);
        double cornerLength = Math.sqrt(
                corner[0] * corner[0] + corner[1] * corner[1]);
        require(Math.abs(cornerLength - max) < 1.0e-9,
                "Corner push must clamp back to the cap radius");
        require(corner[0] < 0.0 && corner[1] > 0.0,
                "East+north walls must push west+south");
    }

    private static void verifyFalloff() {
        require(CarverHologramOffset.falloff(0.4, 0.75, 0.75) == 0.4,
                "Full lift must keep the full nudge");
        require(CarverHologramOffset.falloff(0.4, 0.0, 0.75) == 0.0,
                "Touchdown must land back into the socket");
        require(CarverHologramOffset.falloff(0.4, -1.0, 0.75) == 0.0,
                "Overshoot below the socket must not mirror the nudge");
        double half = CarverHologramOffset.falloff(0.4, 0.375, 0.75);
        require(Math.abs(half - 0.2) < 1.0e-9,
                "Half lift must carry half the nudge, got " + half);
        require(CarverHologramOffset.falloff(0.4, 0.75, 0.0) == 0.0,
                "Degenerate rest height must park the nudge");
    }

    private static void verifyMotionEasing() {
        require(CarverHologramMotion.ease(0.0) == 0.0
                        && CarverHologramMotion.ease(1.0) == 1.0,
                "Flights must start and end exactly on schedule");
        require(CarverHologramMotion.RISE_TICKS > CarverHologramMotion.FALL_TICKS
                        && CarverHologramMotion.FALL_TICKS > 0,
                "The fall must be shorter than the rise, the stone drops");
        double previous = 0.0;
        for (int step = 1; step <= 100; step++) {
            double next = CarverHologramMotion.ease(step / 100.0);
            require(next > previous, "Flights must stay strictly monotonic at " + step);
            previous = next;
        }
        double startSpeed = CarverHologramMotion.velocity(0.0);
        double midSpeed = CarverHologramMotion.velocity(0.5);
        double endSpeed = CarverHologramMotion.velocity(1.0);
        require(startSpeed > 1.0 && endSpeed > 1.0 && midSpeed < 1.0,
                "Flights must start sharp, breathe mid-way and pick up again, got "
                        + startSpeed + "/" + midSpeed + "/" + endSpeed);
        require(Math.abs(startSpeed - endSpeed) < 1.0e-9,
                "Rise and fall phrasing must be symmetric");
        double quarter = CarverHologramMotion.ease(0.25);
        require(quarter > 0.25, "Sharp start must run ahead of linear, got " + quarter);
        double threeQuarters = CarverHologramMotion.ease(0.75);
        require(threeQuarters < 0.75, "Soft middle must lag linear, got " + threeQuarters);
        System.out.println("CarverHologramMotionTest: endpoints, monotonicity and phrasing passed");
    }

    private static void verifyPickFollowsOffset() {
        // Zero offsets must delegate exactly to the legacy behaviour.
        CarverCursorPick.Hit legacy = CarverCursorPick.pick(
                0.5, 0.5, 3.0, 180.0f, 0.0f, 70.0, 800, 600, 400.0, 300.0, 0, 0, 0);
        CarverCursorPick.Hit delegated = CarverCursorPick.pick(
                0.5, 0.5, 3.0, 180.0f, 0.0f, 70.0, 800, 600, 400.0, 300.0, 0, 0, 0, 0.0, 0.0);
        require(legacy != null && delegated != null
                        && legacy.cell() == delegated.cell() && legacy.face() == delegated.face(),
                "Zero offsets must not change picking");
        // Translation invariance: shifting the camera and the cube by the same nudge
        // must hit the same face and the same volume-relative cell.
        double offX = -0.4;
        double offZ = 0.0;
        CarverCursorPick.Hit plain = CarverCursorPick.pick(
                0.5, 1.25, 3.0, 180.0f, 0.0f, 70.0, 800, 600, 400.0, 300.0, 0, 0.75, 0);
        CarverCursorPick.Hit nudged = CarverCursorPick.pick(
                0.5 + offX, 1.25, 3.0 + offZ, 180.0f, 0.0f, 70.0, 800, 600,
                400.0, 300.0, 0, 0.75, 0, offX, offZ);
        require(plain != null && nudged != null,
                "Both centred rays must hit their cube");
        require(plain.face() == nudged.face() && plain.cell() == nudged.cell(),
                "Hitbox must follow the nudge, got " + plain + " vs " + nudged);
    }

    private static void verifyCameraTargetsLiftedAnchor() {
        // The orbit offset keeps its length at any height; the look-back from the
        // orbit anchor at the lifted anchor must mirror the orbit pitch, so the
        // lifted copy (not the socket) sits in the frame centre.
        double lift = 0.75;
        double[] offset = CarverCameraMath.orbitOffset(0.0, 35.0, 2.2);
        double ax = 0.5;
        double ay = 0.5 + lift;
        double az = 0.5;
        double[] look = CarverCameraMath.lookAt(
                ax + offset[0], ay + offset[1], az + offset[2], ax, ay, az);
        require(Math.abs(look[1] - 35.0) < 1.0e-6,
                "Look-back at the lifted anchor must mirror the orbit pitch, got " + look[1]);
        double[] socketLook = CarverCameraMath.lookAt(
                ax + offset[0], ay + offset[1], az + offset[2], 0.5, 0.5, 0.5);
        require(Math.abs(socketLook[1] - 35.0) > 1.0,
                "Aiming at the bare socket from the lifted orbit must visibly differ");
    }

    private static void verifyBoxDragCoversArea() {
        java.util.Random random = new java.util.Random(0xB005L);
        CarverFaceSlicer.Face[] faces = CarverFaceSlicer.Face.values();
        for (int trial = 0; trial < 120; trial++) {
            CarverFaceSlicer.Face face = faces[random.nextInt(faces.length)];
            int layer = random.nextInt(16);
            int depth = 1 + random.nextInt(4);
            int c0 = random.nextInt(16);
            int r0 = random.nextInt(16);
            int c1 = random.nextInt(16);
            int r1 = random.nextInt(16);
            // Drag anchor as the screen stores it: {cell, faceOrdinal}.
            int startCell = CarverFaceSlicer.cellFor(face, c0, r0, layer);
            int[] anchor = new int[]{startCell, face.ordinal()};
            // The fixed drag path resolves the face from slot 1; the old bug read
            // slot 0 (the cell) as an ordinal and painted the wrong plane.
            CarverFaceSlicer.Face dragFace = faces[anchor[1]];
            require(dragFace == face, "Drag must paint on the grabbed face");
            int endCell = CarverFaceSlicer.cellFor(face, c1, r1, layer);
            int[] to = CarverFaceSlicer.inverse(dragFace, endCell);
            int[] from = CarverFaceSlicer.inverse(dragFace, startCell);
            int[] cells = CarverBoxSelect.cellsFor(dragFace,
                    from[0], from[1], to[0], to[1], layer, depth);
            java.util.Set<Integer> set = new java.util.HashSet<>();
            for (int cell : cells) set.add(cell);
            require(set.contains(startCell) && set.contains(endCell),
                    "Box must cover both drag endpoints on " + face);
            int width = Math.abs(c1 - c0) + 1;
            int height = Math.abs(r1 - r0) + 1;
            int layers = Math.min(16 - layer, depth);
            require(cells.length == width * height * layers,
                    "Box must fill the area, not a pencil line, on " + face
                            + " got " + cells.length + " expected " + (width * height * layers));
        }
    }

    private static void verifyFingerprint() {
        DraftMask empty = new DraftMask();
        require(CarverChalkQuads.draftFingerprint(empty)
                        == CarverChalkQuads.draftFingerprint(new DraftMask()),
                "Empty drafts must fingerprint identically");
        DraftMask one = new DraftMask();
        one.set(DraftMask.index(3, 4, 5));
        DraftMask other = new DraftMask();
        other.set(DraftMask.index(4, 4, 5));
        require(CarverChalkQuads.draftFingerprint(one)
                        != CarverChalkQuads.draftFingerprint(other),
                "Same-size drafts on different cells must fingerprint differently");
        DraftMask grown = new DraftMask();
        grown.set(DraftMask.index(3, 4, 5));
        long before = CarverChalkQuads.draftFingerprint(grown);
        grown.set(DraftMask.index(6, 7, 8));
        require(CarverChalkQuads.draftFingerprint(grown) != before,
                "Added strokes must change the fingerprint");
        grown.clear(DraftMask.index(6, 7, 8));
        require(CarverChalkQuads.draftFingerprint(grown) == before,
                "Undoing a stroke must restore the fingerprint");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException("CarverHologramOffsetTest: " + message);
    }
}
