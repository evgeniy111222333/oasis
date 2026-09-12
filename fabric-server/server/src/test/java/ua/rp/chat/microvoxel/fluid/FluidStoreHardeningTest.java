package ua.rp.chat.microvoxel.fluid;

import ua.rp.chat.microvoxel.FluidVolume;
import ua.rp.chat.microvoxel.MicrovoxelKey;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Hardening invariants for {@link FluidStore}: the entry cap fails closed instead of throwing
 * inside a server tick, and a corrupt primary file falls back to an intact backup entirely
 * (never a half-populated store). Pure data, no Minecraft bootstrap needed.
 */
public final class FluidStoreHardeningTest {
    public static void main(String[] args) throws Exception {
        capFailsClosedAndAllowsReplace();
        corruptPrimaryFallsBackToBackup();
        corruptWithoutBackupLeavesStoreEmpty();
        System.out.println("FluidStoreHardeningTest: cap fail-safe and corrupt-load fallback passed");
    }

    private static void capFailsClosedAndAllowsReplace() {
        FluidStore store = new FluidStore(2);
        MicrovoxelKey first = key(1);
        MicrovoxelKey second = key(2);
        MicrovoxelKey third = key(3);
        require(store.put(first, FluidVolume.empty()), "First put under the cap must succeed");
        require(store.put(second, FluidVolume.empty()), "Second put under the cap must succeed");
        require(!store.put(third, FluidVolume.empty()),
                "A new key beyond the cap must fail closed, not throw");
        require(store.put(first, FluidVolume.empty()),
                "Replacing an existing key at the cap must succeed");
        require(store.size() == 2, "The cap must bound the store size");
    }

    private static void corruptPrimaryFallsBackToBackup() throws Exception {
        Path dir = Files.createTempDirectory("fluid-hardening");
        Path file = dir.resolve("fluids-v1.dat");
        MicrovoxelKey key = key(7);
        FluidVolume good = FluidVolume.empty();
        good.setLevel(0, 16);
        FluidStore store = new FluidStore();
        require(store.put(key, good), "Seeding the store must succeed");
        store.save(file); // writes a valid primary and an identical .bak

        // Overwrite only the primary with an out-of-range level: the primary read must fail
        // and load() must restore the intact backup instead of half-loading.
        Files.write(file, withLevel(key, 200));

        FluidStore reloaded = new FluidStore();
        reloaded.load(file);
        require(reloaded.loadedFromBackup(), "A corrupt primary must fall back to the backup");
        require(reloaded.size() == 1 && reloaded.get(key) != null
                        && reloaded.get(key).level(0) == 16,
                "The backup must load completely and correctly");
    }

    private static void corruptWithoutBackupLeavesStoreEmpty() throws Exception {
        Path dir = Files.createTempDirectory("fluid-hardening-solo");
        Path file = dir.resolve("solo.dat");
        Files.write(file, withLevel(key(9), 200));
        FluidStore store = new FluidStore();
        boolean rejected = false;
        try {
            store.load(file);
        } catch (IOException expected) {
            rejected = true;
        }
        require(rejected, "A corrupt store with no backup must throw IOException");
        require(store.size() == 0, "A failed load must not leave partial entries behind");
    }

    /** Writes one valid-shaped entry whose single level byte is out of range. */
    private static byte[] withLevel(MicrovoxelKey key, int level) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(0x4D564631); // MAGIC (mirrors FluidStore)
            out.writeInt(2);          // VERSION
            out.writeInt(1);          // count
            out.writeLong(key.worldId().getMostSignificantBits());
            out.writeLong(key.worldId().getLeastSignificantBits());
            out.writeInt(key.x());
            out.writeInt(key.y());
            out.writeInt(key.z());
            out.writeInt(1);          // revision
            out.writeByte(0);         // water
            byte[] levels = new byte[FluidVolume.CELL_COUNT];
            levels[0] = (byte) level;
            out.write(levels);
        }
        return bytes.toByteArray();
    }

    private static MicrovoxelKey key(int x) {
        return new MicrovoxelKey(UUID.randomUUID(), x, 64, 0);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
