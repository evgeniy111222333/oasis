package ua.rp.chat.common;

import ua.rp.chat.microvoxel.MicrovoxelWire;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.Arrays;

/**
 * Parity guard for the single shared wire contract. Runs once (server test source set) and proves
 * the frame layout, capability helpers and both RLE codecs round-trip, so a drift on either side is
 * caught at build time instead of by a silent client drop in production.
 */
public final class SharedWireCodecTest {
    public static void main(String[] args) throws Exception {
        verifyFrameRoundTrip();
        verifyUnknownTypeSurvives();
        verifyVarInt();
        verifyCellsRoundTrip();
        verifyLevelsRoundTrip();
        verifyCapabilities();
        verifyCrackState();
        System.out.println("SharedWireCodecTest passed");
    }

    private static void verifyFrameRoundTrip() throws Exception {
        byte[] payload = {7, 8, 9, 10};
        byte[] encoded = MicrovoxelWire.frame(MicrovoxelWire.FLUID_UPSERT, payload);
        MicrovoxelWire.Frame frame = MicrovoxelWire.readFrame(encoded);
        require(frame.major() == MicrovoxelWire.MAJOR,
                "Frame must carry the shared major");
        require(frame.minor() == MicrovoxelWire.MINOR,
                "Frame must carry the shared minor");
        require(frame.type() == MicrovoxelWire.FLUID_UPSERT, "Frame type must round-trip");
        require(Arrays.equals(frame.payload(), payload), "Frame payload must round-trip");
    }

    private static void verifyUnknownTypeSurvives() throws Exception {
        // A length-prefixed frame lets a receiver skip a type it does not know without desync.
        byte[] encoded = MicrovoxelWire.frame(200, new byte[]{1, 2, 3});
        MicrovoxelWire.Frame frame = MicrovoxelWire.readFrame(encoded);
        require(frame.type() == 200 && frame.payload().length == 3,
                "Unknown frame types must decode fully");
    }

    private static void verifyVarInt() throws Exception {
        int[] values = {0, 1, 127, 128, 255, 300, 16384, 1 << 20, Integer.MAX_VALUE};
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            for (int value : values) MicrovoxelWire.writeVarInt(output, value);
        }
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            for (int value : values) {
                require(MicrovoxelWire.readVarInt(input) == value, "VarInt must round-trip " + value);
            }
            require(input.read() == -1, "VarInt stream must be fully consumed");
        }
    }

    private static void verifyCellsRoundTrip() throws Exception {
        byte[] cells = new byte[MicrovoxelVolumeCells.COUNT];
        // A few runs, plus a mostly-uniform case that must pick the raw encoding.
        Arrays.fill(cells, 0, 1000, (byte) 1);
        Arrays.fill(cells, 1000, 1001, (byte) 2);
        Arrays.fill(cells, 1001, cells.length, (byte) 3);
        byte[] encoded = MicrovoxelWire.encodeCells(cells);
        byte[] decoded = new byte[cells.length];
        MicrovoxelWire.decodeCells(encoded, decoded);
        require(Arrays.equals(cells, decoded), "Cell RLE must round-trip");

        byte[] randomish = new byte[cells.length];
        for (int i = 0; i < randomish.length; i++) randomish[i] = (byte) (i & 3);
        byte[] rawEncoded = MicrovoxelWire.encodeCells(randomish);
        require(rawEncoded[0] == 0, "High-entropy cells must fall back to the raw encoding");
        byte[] rawDecoded = new byte[randomish.length];
        MicrovoxelWire.decodeCells(rawEncoded, rawDecoded);
        require(Arrays.equals(randomish, rawDecoded), "Raw cell encoding must round-trip");
    }

    private static void verifyLevelsRoundTrip() throws Exception {
        byte[] levels = new byte[MicrovoxelVolumeCells.COUNT];
        Arrays.fill(levels, 0, 200, (byte) 16);
        Arrays.fill(levels, 200, 400, (byte) 7);
        byte[] encoded = MicrovoxelWire.encodeLevels(levels);
        require(Arrays.equals(levels, MicrovoxelWire.decodeLevels(encoded)),
                "Fluid level RLE must round-trip");
        require(MicrovoxelWire.decodeLevels(MicrovoxelWire.encodeLevels(new byte[0])).length == 0,
                "Empty level arrays must round-trip");

        // A malformed run must fail closed rather than allocate wildly.
        boolean rejected = false;
        try {
            MicrovoxelWire.decodeLevels(new byte[]{(byte) 0x80, 0x01, 0x10, (byte) 17});
        } catch (java.io.IOException expected) {
            rejected = true;
        }
        require(rejected, "An out-of-range fluid level must be rejected");
    }

    private static void verifyCapabilities() {
        int caps = MicrovoxelWire.CAP_DELTA | MicrovoxelWire.CAP_FLUID;
        require(MicrovoxelWire.supports(caps, MicrovoxelWire.CAP_DELTA),
                "supports must match an advertised capability");
        require(!MicrovoxelWire.supports(caps, MicrovoxelWire.CAP_MINE_STAGE),
                "supports must reject an unadvertised capability");
        require(MicrovoxelWire.supports(MicrovoxelWire.CLIENT_CAPABILITIES,
                        MicrovoxelWire.CAP_MINE_STAGE),
                "The shipped client must advertise the per-cell crack overlay it now draws");
        require(MicrovoxelWire.supports(MicrovoxelWire.CLIENT_CAPABILITIES,
                        MicrovoxelWire.CAP_GEOMETRY),
                "The shipped client must advertise the geometry channel");
        require(MicrovoxelWire.compatibleMajor(MicrovoxelWire.MAJOR),
                "The current major must be compatible with itself");
        require(!MicrovoxelWire.compatibleMajor(MicrovoxelWire.MAJOR + 1),
                "A future major must be rejected");
    }

    private static void verifyCrackState() {
        java.util.Map<Integer, Integer> cracks = new java.util.HashMap<>();
        require(ua.rp.chat.microvoxel.MicrovoxelCrack.apply(cracks, 10, 3),
                "A first stage must register");
        require(cracks.get(10) == 3, "The stage must be stored");
        require(!ua.rp.chat.microvoxel.MicrovoxelCrack.apply(cracks, 10, 3),
                "Applying the same stage must report no change");
        require(ua.rp.chat.microvoxel.MicrovoxelCrack.apply(cracks, 10, 7),
                "A stage advance must report a change");
        require(ua.rp.chat.microvoxel.MicrovoxelCrack.apply(cracks, 20, 99)
                        && cracks.get(20) == ua.rp.chat.microvoxel.MicrovoxelCrack.MAX_STAGE,
                "An over-range stage must clamp instead of rejecting");
        require(ua.rp.chat.microvoxel.MicrovoxelCrack.apply(cracks, 10, -1) && !cracks.containsKey(10),
                "Stage -1 must clear the cell");
        require(!ua.rp.chat.microvoxel.MicrovoxelCrack.apply(cracks, 10, -1),
                "Clearing an absent cell must report no change");
        require(!ua.rp.chat.microvoxel.MicrovoxelCrack.apply(cracks, -1, 4)
                        && !ua.rp.chat.microvoxel.MicrovoxelCrack.apply(cracks, 4096, 4),
                "Out-of-range cells must be ignored");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError("SharedWireCodecTest: " + message);
    }

    /** Local mirror of the resolution constant so the test does not depend on a chunk type. */
    private static final class MicrovoxelVolumeCells {
        private static final int COUNT = 16 * 16 * 16;
    }
}
