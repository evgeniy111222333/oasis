package ua.rp.chat;

import net.minecraft.core.BlockPos;
import ua.rp.chat.carver.CarverWorkAim;
import ua.rp.chat.carver.DraftMask;

public final class CarverWorkAimTest {
    public static void main(String[] args) {
        verifyCentroidEmpty();
        verifyCentroidSingle();
        verifyCentroidFullCube();
        verifyCentroidPair();
        verifyContactWorld();
        verifyFaceNormalAxis();
        verifyRightHandCanonicalAttachment();
        verifyGazeSmoothing();
        verifyGazePeekWindows();
        verifyGazePeekCadence();
        verifyGazePeekTarget();
        verifyGazeNod();
        verifyGazeTickTime();
        verifyGazeFallbackPeek();
        verifyGazeSway();
        System.out.println("CarverWorkAimTest passed");
    }

    private static void verifyCentroidEmpty() {
        require(CarverWorkAim.draftCentroid(null) == null
                        && CarverWorkAim.draftCentroid(new DraftMask()) == null,
                "Empty draft must have no centroid");
        require(CarverWorkAim.contactWorld(null, new DraftMask()) == null
                        && CarverWorkAim.contactWorld(new BlockPos(0, 0, 0), null) == null
                        && CarverWorkAim.contactWorld(new BlockPos(0, 0, 0), new DraftMask()) == null,
                "Empty inputs must yield no contact");
    }

    private static void verifyCentroidSingle() {
        DraftMask mask = new DraftMask();
        mask.set(DraftMask.index(3, 4, 5));
        double[] centroid = CarverWorkAim.draftCentroid(mask);
        require(close(centroid[0], 3.5) && close(centroid[1], 4.5) && close(centroid[2], 5.5),
                "Single cell centroid must sit at its center");
    }

    private static void verifyCentroidFullCube() {
        DraftMask mask = new DraftMask();
        for (int cell = 0; cell < DraftMask.RESOLUTION * DraftMask.RESOLUTION
                * DraftMask.RESOLUTION; cell++) {
            mask.set(cell);
        }
        double[] centroid = CarverWorkAim.draftCentroid(mask);
        require(close(centroid[0], 8.0) && close(centroid[1], 8.0) && close(centroid[2], 8.0),
                "Full cube centroid must sit at the block center");
    }

    private static void verifyCentroidPair() {
        DraftMask mask = new DraftMask();
        mask.set(DraftMask.index(0, 0, 0));
        mask.set(DraftMask.index(15, 15, 15));
        double[] centroid = CarverWorkAim.draftCentroid(mask);
        require(close(centroid[0], 8.0) && close(centroid[1], 8.0) && close(centroid[2], 8.0),
                "Opposite corners must average to the center");
    }

    private static void verifyContactWorld() {
        DraftMask mask = new DraftMask();
        mask.set(DraftMask.index(0, 15, 0));
        double[] contact = CarverWorkAim.contactWorld(new BlockPos(10, 80, 20), mask);
        require(close(contact[0], 10.0 + 0.5 / 16.0)
                        && close(contact[1], 80.0 + 15.5 / 16.0)
                        && close(contact[2], 20.0 + 0.5 / 16.0),
                "Contact must offset the focus by the centroid in blocks");
    }

    private static void verifyFaceNormalAxis() {
        DraftMask topSlab = new DraftMask();
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            topSlab.set(DraftMask.index(x, 15, z));
        }
        require(CarverWorkAim.faceNormalAxis(topSlab) == 1,
                "Top slab must report the Y normal");
        DraftMask sideSlab = new DraftMask();
        for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) {
            sideSlab.set(DraftMask.index(0, y, z));
        }
        require(CarverWorkAim.faceNormalAxis(sideSlab) == 0,
                "Side slab must report the X normal");
        DraftMask backSlab = new DraftMask();
        for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) {
            backSlab.set(DraftMask.index(x, y, 0));
        }
        require(CarverWorkAim.faceNormalAxis(backSlab) == 2,
                "Back slab must report the Z normal");
        require(CarverWorkAim.faceNormalAxis(new DraftMask()) == -1,
                "Empty draft must report no normal");
        DraftMask single = new DraftMask();
        single.set(DraftMask.index(7, 7, 7));
        require(CarverWorkAim.faceNormalAxis(single) == 1,
                "Degenerate draft must fall back to Y");
    }

    private static void verifyRightHandCanonicalAttachment() {
        Mat4 hand = Mat4.identity();
        Mat4 net = hand.mul(Mat4.rx(-90)).mul(Mat4.ry(180))
                .mul(Mat4.tr(1 / 16.0, 2 / 16.0, -10 / 16.0))
                .mul(display(new double[]{-171, 0, 0}, new double[]{8, 2.75, -6.25}, 1.0));
        double[] heel = net.apply(new double[]{0, 12.98 / 16.0, 0});
        double[] tip = net.apply(new double[]{0, 0, 0});
        double len = distance(heel, tip);
        require(len > 0.5 && len < 1.0,
                "Tool length should be physically plausible, got " + len);
    }

    private static Mat4 display(double[] rot, double[] trans, double scale) {
        return Mat4.tr(trans[0] / 16.0, trans[1] / 16.0, trans[2] / 16.0)
                .mul(Mat4.rx(rot[0])).mul(Mat4.ry(rot[1])).mul(Mat4.rz(rot[2]))
                .mul(Mat4.scale(scale));
    }

    private static double distance(double[] left, double[] right) {
        double sum = 0.0;
        for (int index = 0; index < 3; index++) {
            double delta = left[index] - right[index];
            sum += delta * delta;
        }
        return Math.sqrt(sum);
    }

    private static double[] norm(double[] value) {
        double length = Math.sqrt(dot(value, value));
        return new double[]{value[0] / length, value[1] / length, value[2] / length};
    }

    private static double dot(double[] left, double[] right) {
        return left[0] * right[0] + left[1] * right[1] + left[2] * right[2];
    }

    private static boolean close(double left, double right) {
        return Math.abs(left - right) < 1.0e-9;
    }

    private static void verifyGazeSmoothing() {
        require(close(ua.rp.chat.carver.CarverGazeMath.smoothstep(0.0), 0.0)
                        && close(ua.rp.chat.carver.CarverGazeMath.smoothstep(1.0), 1.0)
                        && close(ua.rp.chat.carver.CarverGazeMath.smoothstep(0.5), 0.5),
                "Smoothstep must ease 0->1 through 0.5");
        double alpha = ua.rp.chat.carver.CarverGazeMath.tickAlpha(1.0, 0.12);
        require(alpha > 0.3 && alpha < 0.45,
                "Yaw filter alpha at one tick/0.12s must be ~0.34, got " + alpha);
        require(close(ua.rp.chat.carver.CarverGazeMath.entryBlend(0.0), 0.0)
                        && close(ua.rp.chat.carver.CarverGazeMath.entryBlend(28.0), 1.0),
                "Entry blend must run 0->1 over 28 ticks");
    }

    private static void verifyGazePeekWindows() {
        require(ua.rp.chat.carver.CarverGazeMath.peekWeight(0.0) == 0.0
                        && ua.rp.chat.carver.CarverGazeMath.peekWeight(0.10) == 0.0,
                "Peek must stay shut during early windup and the whole strike");
        require(ua.rp.chat.carver.CarverGazeMath.peekWeight(0.70) == 0.0
                        && ua.rp.chat.carver.CarverGazeMath.peekWeight(0.95) == 0.0,
                "Peek must never fire on strike-down or contact");
        require(ua.rp.chat.carver.CarverGazeMath.peekWeight(0.60) == 0.0
                        && ua.rp.chat.carver.CarverGazeMath.peekWeight(0.65) == 0.0,
                "Peek overlay must be fully back on the chisel before strike-down");
        require(close(ua.rp.chat.carver.CarverGazeMath.peekWeight(0.35), 1.0),
                "Peek must fully hold mid-windup");
        double rising = ua.rp.chat.carver.CarverGazeMath.peekWeight(0.20);
        require(rising > 0.0 && rising < 1.0,
                "Peek attack must ramp smoothly, got " + rising);
    }

    private static void verifyGazePeekCadence() {
        int hits = 0;
        for (int strike = 0; strike < 12; strike++) {
            if (ua.rp.chat.carver.CarverGazeMath.isPeekStrike(strike, 0x1234L)) hits++;
        }
        require(hits == 3,
                "Peek cadence must be ~1/4 of strikes, got " + hits + "/12");
        require(!ua.rp.chat.carver.CarverGazeMath.isPeekStrike(-1, 0L),
                "Negative strike must never peek");
    }

    private static void verifyGazePeekTarget() {
        DraftMask mask = new DraftMask();
        for (int x = 8; x < 16; x++) mask.set(DraftMask.index(x, 8, 8));
        double[] contact = new double[]{8.5, 8.5, 8.5};
        double[] peek = ua.rp.chat.carver.CarverGazeMath.peekWorld(
                10, 80, 20, contact, mask, 1, 0);
        require(peek[0] > 10.0 + 8.5 / 16.0 && peek[0] <= 11.0,
                "Peek must look ahead along the cut line, got " + peek[0]);
        require(Math.abs(peek[1] - (80.0 + 8.5 / 16.0)) < 0.25,
                "Peek must stay on the working face plane");
    }

    private static void verifyGazeNod() {
        require(ua.rp.chat.carver.CarverGazeMath.nodRadians(0.5) == 0.0,
                "Nod must be silent outside contact");
        double nod = ua.rp.chat.carver.CarverGazeMath.nodRadians(0.95);
        require(nod > Math.toRadians(0.5) && nod <= Math.toRadians(1.3),
                "Nod must dip ~1.25deg on impact, got " + Math.toDegrees(nod));
    }

    private static void verifyGazeTickTime() {
        require(ua.rp.chat.carver.CarverGazeMath.tickAlpha(0.0, 0.12) == 0.0,
                "Zero tick delta must take zero step (no multi-pass snap)");
        double tick = ua.rp.chat.carver.CarverGazeMath.tickAlpha(1.0, 0.12);
        require(tick > 0.3 && tick < 0.45,
                "One tick at tau 0.12 must step ~0.34, got " + tick);
        require(ua.rp.chat.carver.CarverGazeMath.tickAlpha(1.0, 0.15)
                        > ua.rp.chat.carver.CarverGazeMath.tickAlpha(1.0, 0.12) - 0.2,
                "Pitch filter must stay in the same band as yaw");
        require(ua.rp.chat.carver.CarverGazeMath.tickAlpha(0.0, 0.12) == 0.0
                        && ua.rp.chat.carver.CarverGazeMath.tickAlpha(-1.0, 0.12) == 0.0,
                "Non-positive tick delta must take zero step");
    }

    private static void verifyGazeFallbackPeek() {
        double[] contact = new double[]{8.5, 8.5, 8.5};
        double[] peek = ua.rp.chat.carver.CarverGazeMath.peekWorld(
                10, 80, 20, contact, null, -1, 2);
        double offX = (peek[0] - 10.0) * 16.0 - 8.5;
        double offY = (peek[1] - 80.0) * 16.0 - 8.5;
        double offZ = (peek[2] - 20.0) * 16.0 - 8.5;
        double dist = Math.sqrt(offX * offX + offY * offY + offZ * offZ);
        require(dist > 4.0 && dist < 5.6,
                "Fallback peek must sit ~4.8 cells out, got " + dist);
    }

    private static void verifyGazeSway() {
        require(ua.rp.chat.carver.CarverGazeMath.swayYawRadians(0.0) == 0.0,
                "Sway must start at zero breath phase");
        require(close(ua.rp.chat.carver.CarverGazeMath.swayYawRadians(1.0), 0.0),
                "Sway must complete one breath cycle per phase");
        double maxYaw = 0.0;
        double maxPitch = 0.0;
        for (int step = 0; step <= 64; step++) {
            double phase = step / 64.0;
            maxYaw = Math.max(maxYaw,
                    Math.abs(ua.rp.chat.carver.CarverGazeMath.swayYawRadians(phase)));
            maxPitch = Math.max(maxPitch,
                    Math.abs(ua.rp.chat.carver.CarverGazeMath.swayPitchRadians(phase)));
        }
        require(maxYaw > Math.toRadians(0.35) && maxYaw <= Math.toRadians(0.45),
                "Yaw sway must breathe at 0.4deg, got " + Math.toDegrees(maxYaw));
        require(maxPitch > Math.toRadians(0.25) && maxPitch <= Math.toRadians(0.35),
                "Pitch sway must breathe at 0.3deg, got " + Math.toDegrees(maxPitch));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class Mat4 {
        private final double[][] cells = new double[4][4];

        private Mat4() {
        }

        static Mat4 identity() {
            Mat4 result = new Mat4();
            for (int index = 0; index < 4; index++) result.cells[index][index] = 1.0;
            return result;
        }

        static Mat4 rx(double degrees) {
            double angle = Math.toRadians(degrees);
            double cosine = Math.cos(angle);
            double sine = Math.sin(angle);
            Mat4 result = identity();
            result.cells[1][1] = cosine;
            result.cells[1][2] = -sine;
            result.cells[2][1] = sine;
            result.cells[2][2] = cosine;
            return result;
        }

        static Mat4 ry(double degrees) {
            double angle = Math.toRadians(degrees);
            double cosine = Math.cos(angle);
            double sine = Math.sin(angle);
            Mat4 result = identity();
            result.cells[0][0] = cosine;
            result.cells[0][2] = sine;
            result.cells[2][0] = -sine;
            result.cells[2][2] = cosine;
            return result;
        }

        static Mat4 rz(double degrees) {
            double angle = Math.toRadians(degrees);
            double cosine = Math.cos(angle);
            double sine = Math.sin(angle);
            Mat4 result = identity();
            result.cells[0][0] = cosine;
            result.cells[0][1] = -sine;
            result.cells[1][0] = sine;
            result.cells[1][1] = cosine;
            return result;
        }

        static Mat4 tr(double x, double y, double z) {
            Mat4 result = identity();
            result.cells[0][3] = x;
            result.cells[1][3] = y;
            result.cells[2][3] = z;
            return result;
        }

        static Mat4 scale(double factor) {
            Mat4 result = identity();
            result.cells[0][0] = factor;
            result.cells[1][1] = factor;
            result.cells[2][2] = factor;
            return result;
        }

        Mat4 mul(Mat4 right) {
            Mat4 result = new Mat4();
            for (int row = 0; row < 4; row++) {
                for (int col = 0; col < 4; col++) {
                    double sum = 0.0;
                    for (int inner = 0; inner < 4; inner++) {
                        sum += cells[row][inner] * right.cells[inner][col];
                    }
                    result.cells[row][col] = sum;
                }
            }
            return result;
        }

        double[] apply(double[] point) {
            double[] result = new double[3];
            for (int row = 0; row < 3; row++) {
                result[row] = cells[row][0] * point[0] + cells[row][1] * point[1]
                        + cells[row][2] * point[2] + cells[row][3];
            }
            return result;
        }
    }
}
