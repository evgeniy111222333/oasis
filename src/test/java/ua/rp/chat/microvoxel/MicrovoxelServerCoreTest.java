package ua.rp.chat.microvoxel;

import org.bukkit.util.BoundingBox;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import ua.rp.chat.interaction.ItemPickupRules;

public final class MicrovoxelServerCoreTest {
    public static void main(String[] args) throws Exception {
        UUID itemOwner = UUID.randomUUID();
        UUID otherPlayer = UUID.randomUUID();
        require(ItemPickupRules.mayPickUp(true, 0, itemOwner, itemOwner, 8.99),
                "Right-click pickup must allow the owner inside interaction range");
        require(!ItemPickupRules.mayPickUp(true, 0, itemOwner, otherPlayer, 1.0),
                "Right-click pickup must preserve an item's owner lock");
        require(!ItemPickupRules.mayPickUp(true, 1, null, itemOwner, 1.0),
                "Right-click pickup must preserve the vanilla pickup delay");
        require(!ItemPickupRules.mayPickUp(true, 0, null, itemOwner, 9.01),
                "Right-click pickup must reject items outside close interaction range");

        MicrovoxelVolume volume = MicrovoxelVolume.full("minecraft:red_wool");
        require(volume.occupiedCount() == 4096, "Converted block must contain all 4096 cells");
        require(volume.collisionCuboids().size() == 1, "Full volume must merge to one collider");
        int removed = MicrovoxelVolume.index(3, 7, 11);
        volume.remove(removed);
        volume.put(removed, "minecraft:stone");
        require(volume.material(removed).equals("minecraft:stone"), "Per-cell material palette must persist");

        Path directory = Files.createTempDirectory("eclipse-microvoxel-store-");
        Path file = directory.resolve("microvoxels.dat");
        UUID world = UUID.randomUUID();
        MicrovoxelKey key = new MicrovoxelKey(world, -20, 64, 35);
        MicrovoxelStore store = new MicrovoxelStore(file);
        store.put(key, volume);
        store.save();
        require(!Files.exists(file.resolveSibling("microvoxels.dat.tmp")), "Atomic save must not leave temp files");

        MicrovoxelStore loaded = new MicrovoxelStore(file);
        loaded.load();
        MicrovoxelVolume restored = loaded.get(key);
        require(restored != null && restored.revision() == volume.revision(), "Revision must survive restart");
        require(restored.material(removed).equals("minecraft:stone"), "Palette and cells must survive restart");
        require(loaded.countInChunk(world, -2, 2) == 1, "Negative chunk indexing must be stable");

        loaded.save();
        Files.write(file, new byte[]{0, 1, 2, 3});
        MicrovoxelStore recovered = new MicrovoxelStore(file);
        recovered.load();
        require(recovered.loadedFromBackup(), "Corrupt primary storage must fall back to the last valid backup");
        require(recovered.get(key) != null, "Backup recovery must retain every committed volume");

        BoundingBox player = new BoundingBox(0.0, 0.0, 0.0, 0.6, 1.8, 0.6);
        BoundingBox wall = new BoundingBox(0.75, 0.0, 0.0, 1.0, 1.0, 1.0);
        double clippedForward = MicrovoxelManager.clipAgainst(player, wall, 0.5, MicrovoxelManager.Axis.X);
        require(close(clippedForward, 0.15), "Server collision must stop exactly at a microvoxel face");
        BoundingBox leftWall = new BoundingBox(-0.5, 0.0, 0.0, -0.25, 1.0, 1.0);
        double clippedBackward = MicrovoxelManager.clipAgainst(player, leftWall, -0.5, MicrovoxelManager.Axis.X);
        require(close(clippedBackward, -0.25), "Negative movement must clip symmetrically");
        BoundingBox overhead = new BoundingBox(0.75, 2.0, 0.0, 1.0, 3.0, 1.0);
        require(close(MicrovoxelManager.clipAgainst(player, overhead, 0.5, MicrovoxelManager.Axis.X), 0.5),
                "Separated axes must never create phantom collision");

        MicrovoxelVolume rayVolume = MicrovoxelVolume.full("minecraft:stone");
        MicrovoxelKey near = new MicrovoxelKey(world, 0, 0, 0);
        MicrovoxelKey far = new MicrovoxelKey(world, 2, 0, 0);
        ServerMicrovoxelRaycaster.Hit nearest = ServerMicrovoxelRaycaster.cast(
                -1.0, 0.5, 0.5, 1.0, 0.0, 0.0, 6.0,
                List.of(Map.entry(far, rayVolume), Map.entry(near, rayVolume)));
        require(nearest != null && nearest.key().equals(near) && MicrovoxelVolume.x(nearest.cell()) == 0,
                "Authoritative raycast must select the nearest occupied microvoxel");
        require(nearest.face() == ServerMicrovoxelRaycaster.Face.WEST,
                "Raycast must preserve the entered face for adjacent placement");

        boolean duplicatePaletteRejected = false;
        try {
            MicrovoxelVolume.restore(1, List.of("", "minecraft:stone", "minecraft:stone"),
                    new byte[MicrovoxelVolume.CELL_COUNT]);
        } catch (IllegalArgumentException expected) {
            duplicatePaletteRejected = true;
        }
        require(duplicatePaletteRejected, "Duplicate palette materials must be rejected before persistence");

        byte[] packet = MicrovoxelProtocol.upsert(key, restored);
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(packet))) {
            require(input.readUnsignedByte() == MicrovoxelProtocol.UPSERT, "Packet type must be UPSERT");
            require(input.readInt() == key.x() && input.readInt() == key.y() && input.readInt() == key.z(),
                    "Packet coordinates must be exact");
            require(MicrovoxelProtocol.readVarInt(input) == restored.revision(), "Packet revision must be exact");
            int palette = MicrovoxelProtocol.readVarInt(input);
            require(palette == restored.palette().size(), "Packet palette size must match");
            for (int index = 0; index < palette; index++) {
                int length = MicrovoxelProtocol.readVarInt(input);
                input.readNBytes(length);
            }
            require(input.readUnsignedByte() == 1, "Mostly uniform volume must use RLE encoding");
        }

        // Test REGISTER_MATERIAL
        byte[] regPacket = MicrovoxelProtocol.registerMaterial(42, "minecraft:deepslate");
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(regPacket))) {
            require(input.readUnsignedByte() == MicrovoxelProtocol.REGISTER_MATERIAL, "Packet type must be REGISTER_MATERIAL");
            require(MicrovoxelProtocol.readVarInt(input) == 42, "ID must match");
            int len = MicrovoxelProtocol.readVarInt(input);
            byte[] bytes = input.readNBytes(len);
            require(new String(bytes, java.nio.charset.StandardCharsets.UTF_8).equals("minecraft:deepslate"), "String must match");
        }

        // Test CLEAR_CHUNK
        byte[] clearPacket = MicrovoxelProtocol.clearChunk(10, -5);
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(clearPacket))) {
            require(input.readUnsignedByte() == MicrovoxelProtocol.CLEAR_CHUNK, "Packet type must be CLEAR_CHUNK");
            require(input.readInt() == 10, "chunkX must match");
            require(input.readInt() == -5, "chunkZ must match");
        }

        // Test BATCH_UPSERT
        MicrovoxelVolume batchVol = MicrovoxelVolume.full("minecraft:deepslate");
        MicrovoxelKey batchKey = new MicrovoxelKey(world, 17, 65, -75); // chunkX = 1, chunkZ = -5
        int chunkX = batchKey.chunkX();
        int chunkZ = batchKey.chunkZ();
        java.util.Map<String, Integer> testDict = java.util.Map.of("", 1, "minecraft:deepslate", 100);

        byte[] batchPacket = MicrovoxelProtocol.batchUpsert(chunkX, chunkZ, java.util.List.of(java.util.Map.entry(batchKey, batchVol)), testDict);
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(batchPacket))) {
            require(input.readUnsignedByte() == MicrovoxelProtocol.BATCH_UPSERT, "Packet type must be BATCH_UPSERT");
            require(input.readInt() == chunkX, "chunkX must match");
            require(input.readInt() == chunkZ, "chunkZ must match");
            require(MicrovoxelProtocol.readVarInt(input) == 1, "size must be 1");

            int posXZ = input.readUnsignedByte();
            int posY = input.readShort();
            int decodedX = (chunkX << 4) | ((posXZ >> 4) & 15);
            int decodedZ = (chunkZ << 4) | (posXZ & 15);

            require(decodedX == batchKey.x(), "Packed X must decode exactly");
            require(decodedZ == batchKey.z(), "Packed Z must decode exactly");
            require(posY == batchKey.y(), "Packed Y must decode exactly");

            require(MicrovoxelProtocol.readVarInt(input) == batchVol.revision(), "Revision must match");
            require(MicrovoxelProtocol.readVarInt(input) == batchVol.palette().size(), "Palette size must match");
            require(MicrovoxelProtocol.readVarInt(input) == 1, "First dictionary ID (empty string) must resolve to 1");
            require(MicrovoxelProtocol.readVarInt(input) == 100, "Second dictionary ID (deepslate) must resolve to 100");

            require(input.readUnsignedByte() == 1, "RLE encoding expected");
            require(MicrovoxelProtocol.readVarInt(input) == 1, "Runs count should be 1");
            require(MicrovoxelProtocol.readVarInt(input) == 4096, "First run length must be 4096");
            require(input.readByte() == 1, "First run material index must be 1");
        }

        // Test DELTA_UPSERT
        byte[] deltaPacket = MicrovoxelProtocol.deltaUpsert(chunkX, chunkZ, batchKey, 15, 2048, 100);
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(deltaPacket))) {
            require(input.readUnsignedByte() == MicrovoxelProtocol.DELTA_UPSERT, "Packet type must be DELTA_UPSERT");
            require(input.readInt() == chunkX, "chunkX must match");
            require(input.readInt() == chunkZ, "chunkZ must match");
            int posXZ = input.readUnsignedByte();
            int posY = input.readShort();
            int decodedX = (chunkX << 4) | ((posXZ >> 4) & 15);
            int decodedZ = (chunkZ << 4) | (posXZ & 15);

            require(decodedX == batchKey.x(), "Packed X must decode exactly");
            require(decodedZ == batchKey.z(), "Packed Z must decode exactly");
            require(posY == batchKey.y(), "Packed Y must decode exactly");

            require(MicrovoxelProtocol.readVarInt(input) == 15, "Revision must match");
            require(MicrovoxelProtocol.readVarInt(input) == 2048, "Cell index must match");
            require(MicrovoxelProtocol.readVarInt(input) == 100, "Registry ID must match");
        }

        Files.deleteIfExists(file);
        Files.deleteIfExists(file.resolveSibling("microvoxels.dat.bak"));
        Files.deleteIfExists(directory);
        System.out.println("MicrovoxelServerCoreTest passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static boolean close(double left, double right) {
        return Math.abs(left - right) < 1.0E-9;
    }
}
