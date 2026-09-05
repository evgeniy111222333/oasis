package ua.rp.chat.microvoxel.mining;

import ua.rp.chat.microvoxel.MicrovoxelKey;
import ua.rp.chat.microvoxel.MicrovoxelProtocol;
import ua.rp.chat.microvoxel.MicrovoxelRevision;
import ua.rp.chat.microvoxel.MicrovoxelVolume;
import ua.rp.chat.microvoxel.ServerMicrovoxelRaycaster;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Pure-core mining invariants: the vanilla destroy formula mirror, crack stage mapping, the
 * bounded session continuation, session immutability, crosshair cell targeting (including the
 * eye-inside-volume case) and the MINE_STAGE wire envelope.
 */
public final class MicrovoxelMiningTest {
    private static final float EPS = 1.0E-5f;

    public static void main(String[] args) throws Exception {
        testProgressPerTick();
        testRequiredTicks();
        testCrackStages();
        testAdvance();
        testSessionImmutability();
        testRaycasterTargeting();
        testProtocolMineStage();
        System.out.println("MicrovoxelMiningTest: all assertions passed.");
    }

    private static void testProgressPerTick() {
        require(close(MicrovoxelMiningMath.progressPerTick(1.0f, 1.5f, false),
                        (1.0f / 1.5f) / 100.0f),
                "Stone dug by hand must use the wrong-tool divisor (1/100)");
        require(close(MicrovoxelMiningMath.progressPerTick(6.0f, 1.5f, true),
                        (6.0f / 1.5f) / 30.0f),
                "Stone dug with an iron pickaxe must use the correct-tool divisor (1/30)");
        require(close(MicrovoxelMiningMath.progressPerTick(8.0f, 50.0f, true),
                        (8.0f / 50.0f) / 30.0f),
                "Obsidian with a diamond pickaxe must follow the vanilla ratio");
        require(MicrovoxelMiningMath.progressPerTick(1.0f, -1.0f, true) == 0.0f,
                "Unbreakable hardness must produce zero progress");
        require(MicrovoxelMiningMath.progressPerTick(0.0f, 1.5f, true) == 0.0f,
                "Zero tool speed must produce zero progress");
        require(MicrovoxelMiningMath.progressPerTick(100.0f, 0.001f, true) == 1.0f,
                "Progress per tick must clamp at the vanilla upper bound");
    }

    private static void testRequiredTicks() {
        require(close(MicrovoxelMiningMath.requiredTicks(1.0f, 1.5f, false, 1.0f), 150.0f),
                "Hand-mining stone must take 150 ticks");
        require(close(MicrovoxelMiningMath.requiredTicks(6.0f, 1.5f, true, 1.0f), 7.5f),
                "Iron-pick mining stone must take 7.5 ticks");
        require(close(MicrovoxelMiningMath.requiredTicks(8.0f, 50.0f, true, 1.0f), 187.5f),
                "Diamond-pick mining obsidian must take 187.5 ticks");
        require(Float.isInfinite(MicrovoxelMiningMath.requiredTicks(1.0f, -1.0f, true, 1.0f)),
                "Unbreakable material must never finish");
        require(close(MicrovoxelMiningMath.requiredTicks(6.0f, 1.5f, true, 0.5f), 3.75f),
                "The configured multiplier must scale mining time");
        require(close(MicrovoxelMiningMath.requiredTicks(6.0f, 1.5f, true, 0.0f), 7.5f),
                "A non-positive multiplier must fall back to vanilla time");
        require(close(MicrovoxelMiningMath.requiredTicksFromProgressPerTick(0.13333333f, 1.0f), 7.5f),
                "Live vanilla progress must translate into the same tick budget");
    }

    private static void testCrackStages() {
        require(MicrovoxelMiningMath.crackStage(0.0f, 150.0f) == 0,
                "Fresh progress must show crack stage 0");
        require(MicrovoxelMiningMath.crackStage(74.9f, 150.0f) == 4,
                "Progress just below half must stay on stage 4");
        require(MicrovoxelMiningMath.crackStage(75.0f, 150.0f) == 5,
                "Progress at exactly half must reach stage 5");
        require(MicrovoxelMiningMath.crackStage(149.9f, 150.0f) == 9,
                "Progress at 99% must show the final crack stage");
        require(MicrovoxelMiningMath.crackStage(500.0f, 150.0f) == 9,
                "Over-completed progress must never exceed stage 9");
        require(MicrovoxelMiningMath.crackStage(0.0f, Float.POSITIVE_INFINITY) == 0,
                "Unbreakable material must stay at stage 0");
    }

    private static void testAdvance() {
        MicrovoxelMiningSession fresh = session(7.5f, 0.0f, 100L, -1);
        MicrovoxelMiningEngine.Advance noTime = MicrovoxelMiningEngine.advance(fresh, 100L);
        require(!noTime.breakNow() && noTime.session().progress() == 0.0f
                        && noTime.session().lastTick() == 100L,
                "Advancing with no elapsed time must not accrue progress");

        MicrovoxelMiningEngine.Advance two = MicrovoxelMiningEngine.advance(fresh, 102L);
        require(!two.breakNow() && two.session().progress() == 2.0f
                        && two.session().lastTick() == 102L,
                "Two elapsed ticks must accrue exactly two progress ticks");

        MicrovoxelMiningEngine.Advance burst = MicrovoxelMiningEngine.advance(fresh, 500L);
        require(!burst.breakNow() && burst.session().progress() == 3.0f,
                "A burst of ticks must be capped at MAX_TICKS_PER_ADVANCE");

        MicrovoxelMiningSession run = fresh;
        long tick = 102L;
        while (true) {
            MicrovoxelMiningEngine.Advance step = MicrovoxelMiningEngine.advance(run, tick);
            if (step.breakNow()) break;
            run = step.session();
            tick += 2L;
            require(run.lastStage() >= 0 && run.lastStage() <= 9,
                    "Intermediate crack stages must stay inside 0..9");
        }
        require(tick == 108L,
                "A 7.5-tick budget crossed at 8 accrued ticks must complete on the fourth attack");

        MicrovoxelMiningEngine.Advance done = MicrovoxelMiningEngine.advance(
                session(7.5f, 6.0f, 100L, 8), 104L);
        require(done.breakNow() && done.session().lastStage() == 9,
                "Crossing the budget must report completion at crack stage 9");
    }

    private static void testSessionImmutability() {
        MicrovoxelMiningSession original = session(7.5f, 1.0f, 100L, 1);
        MicrovoxelMiningSession moved = original.withProgress(3.0f, 103L, 3);
        require(original.progress() == 1.0f && moved.progress() == 3.0f,
                "Session records must be immutable");
        require(moved.key().equals(original.key()) && moved.cell() == original.cell()
                        && moved.revision() == original.revision()
                        && moved.requiredTicks() == original.requiredTicks()
                        && moved.toolOk() == original.toolOk(),
                "Progress updates must preserve the target identity");
        require(original.withFeedbackSent().feedbackSent()
                        && !original.feedbackSent(),
                "Feedback flag must flip without mutating the original");
    }

    private static void testRaycasterTargeting() {
        UUID worldId = UUID.randomUUID();
        MicrovoxelKey key = new MicrovoxelKey(worldId, 100, 64, 100);
        MicrovoxelVolume full = MicrovoxelVolume.full("minecraft:stone");
        Map<MicrovoxelKey, MicrovoxelVolume> lookup = new HashMap<>();
        lookup.put(key, full);

        // Eye inside the volume must hit the cell under the eye.
        ServerMicrovoxelRaycaster.Hit inside = cast(worldId, lookup,
                100.5, 64.5, 100.5, 1.0, 0.0, 0.0);
        require(inside != null && inside.key().equals(key)
                        && inside.cell() == MicrovoxelVolume.index(8, 8, 8)
                        && inside.distance() < 1.0E-3,
                "An eye inside the volume must target the cell at the eye position");

        // A straight ray from -Z must enter the volume on its NORTH face, cell row z=0.
        ServerMicrovoxelRaycaster.Hit entry = cast(worldId, lookup,
                100.5, 64.5, 97.5, 0.0, 0.0, 1.0);
        require(entry != null && entry.key().equals(key)
                        && entry.cell() == MicrovoxelVolume.index(8, 8, 0)
                        && entry.face() == ServerMicrovoxelRaycaster.Face.NORTH
                        && entry.distance() > 2.49 && entry.distance() < 2.51,
                "An outside ray must hit the entry cell on the exact crossed face");

        // Beyond reach must miss.
        ServerMicrovoxelRaycaster.Hit miss = cast(worldId, lookup,
                100.5, 64.5, 90.0, 0.0, 0.0, 1.0);
        require(miss == null, "Targets beyond the reach cap must miss");

        // Empty world must miss.
        ServerMicrovoxelRaycaster.Hit empty = cast(worldId, new HashMap<>(), 100.5, 64.5, 97.5,
                0.0, 0.0, 1.0);
        require(empty == null, "A ray in an empty world must miss");

        // The nearest of two aligned volumes must win.
        Map<MicrovoxelKey, MicrovoxelVolume> two = new HashMap<>();
        MicrovoxelKey near = new MicrovoxelKey(worldId, 100, 64, 98);
        two.put(near, MicrovoxelVolume.full("minecraft:stone"));
        two.put(key, full);
        ServerMicrovoxelRaycaster.Hit nearest = cast(worldId, two,
                100.5, 64.5, 96.5, 0.0, 0.0, 1.0);
        require(nearest != null && nearest.key().equals(near),
                "The nearest volume on the ray must win the targeting");
    }

    private static void testProtocolMineStage() throws Exception {
        MicrovoxelKey key = new MicrovoxelKey(UUID.randomUUID(), 12, 64, -34);
        byte[] frame = MicrovoxelProtocol.mineStage(key, 4095, 9);
        DataInputStream input = new DataInputStream(new ByteArrayInputStream(frame));
        require(input.readUnsignedByte() == MicrovoxelProtocol.MAGIC,
                "MINE_STAGE must start with the protocol magic");
        require(MicrovoxelProtocol.readVarInt(input) == MicrovoxelProtocol.VERSION,
                "MINE_STAGE must carry the protocol version");
        require(input.readUnsignedByte() == MicrovoxelProtocol.MINE_STAGE,
                "MINE_STAGE must use opcode 13");
        require(input.readInt() == key.x() && input.readInt() == key.y()
                        && input.readInt() == key.z(),
                "MINE_STAGE must carry the marker position");
        require(MicrovoxelProtocol.readVarInt(input) == 4095,
                "MINE_STAGE must carry the cell index");
        require(input.readByte() == 9 && input.read() == -1,
                "MINE_STAGE must carry the stage and end exactly after it");
        byte[] clear = MicrovoxelProtocol.mineStage(key, 0, -1);
        require(clear[clear.length - 1] == -1,
                "MINE_STAGE stage -1 must encode the crack clear");
        require(MicrovoxelRevision.next(MicrovoxelVolume.full("minecraft:stone").revision()) == 2,
                "Revision arithmetic must stay wrapped and positive");
    }

    private static ServerMicrovoxelRaycaster.Hit cast(
            UUID worldId,
            Map<MicrovoxelKey, MicrovoxelVolume> lookup,
            double ox, double oy, double oz,
            double dx, double dy, double dz) {
        return ServerMicrovoxelRaycaster.castIndexed(worldId, ox, oy, oz, dx, dy, dz,
                MicrovoxelMiningEngine.MAX_REACH, (x, y, z) -> lookup.get(new MicrovoxelKey(worldId, x, y, z)));
    }

    private static MicrovoxelMiningSession session(float requiredTicks, float progress,
                                                   long lastTick, int lastStage) {
        return new MicrovoxelMiningSession(
                UUID.randomUUID(), UUID.randomUUID(),
                new MicrovoxelKey(UUID.randomUUID(), 0, 0, 0),
                0, "minecraft:stone", 1, requiredTicks, progress, lastTick, lastStage, true, false);
    }

    private static boolean close(float a, float b) {
        return Math.abs(a - b) <= EPS;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError("MicrovoxelMiningTest failed: " + message);
        }
    }
}