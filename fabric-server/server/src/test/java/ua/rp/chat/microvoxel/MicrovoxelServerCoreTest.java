package ua.rp.chat.microvoxel;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.shapes.BitSetDiscreteVoxelShape;
import net.minecraft.world.phys.shapes.CubeVoxelShape;
import net.minecraft.world.phys.shapes.DiscreteVoxelShape;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.level.block.Blocks;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import ua.rp.chat.heavyhammer.HeavyHammerImpact;
import ua.rp.chat.heavyhammer.HeavyHammerProtocol;
import ua.rp.chat.heavyhammer.HeavyHammerRules;
import ua.rp.chat.interaction.ItemPickupRules;
import ua.rp.chat.interaction.ItemPickupManager;

public final class MicrovoxelServerCoreTest {
    public static void main(String[] args) throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        UUID pickupTarget = UUID.randomUUID();
        UUID otherPlayer = UUID.randomUUID();
        require(ItemPickupRules.mayPickUp(true, 0, pickupTarget, pickupTarget, 8.99),
                "Right-click pickup must allow the vanilla pickup target inside interaction range");
        require(!ItemPickupRules.mayPickUp(true, 0, pickupTarget, otherPlayer, 1.0),
                "Right-click pickup must preserve vanilla's target lock");
        require(ItemPickupRules.mayPickUp(true, 0, null, otherPlayer, 1.0),
                "The entity that threw an untargeted item must not become a permanent owner lock");
        require(!ItemPickupRules.mayPickUp(true, 1, null, pickupTarget, 1.0),
                "Right-click pickup must preserve the vanilla pickup delay");
        require(!ItemPickupRules.mayPickUp(true, 0, null, pickupTarget, 9.01),
                "Right-click pickup must reject items outside close interaction range");
        double visibleBoxDistance = ItemPickupManager.distanceSquaredToBox(
                new Vec3(0.0, 1.62, 0.0),
                new AABB(2.75, 0.0, -0.125, 3.0, 0.25, 0.125).inflate(0.14));
        require(visibleBoxDistance <= 9.0,
                "Pickup range must measure the aimed item bounds instead of its farther center point");

        MicrovoxelVolume volume = MicrovoxelVolume.full("minecraft:red_wool");
        require(volume.occupiedCount() == 4096, "Converted block must contain all 4096 cells");
        require(volume.collisionCuboids().size() == 1, "Full volume must merge to one collider");
        require(volume.collisionPlan().backend() == MicrovoxelVolume.CollisionBackend.CUBOIDS,
                "Simple volumes must retain the low-overhead cuboid collision backend");
        int removed = MicrovoxelVolume.index(3, 7, 11);
        volume.remove(removed);
        volume.put(removed, "minecraft:stone");
        require(volume.material(removed).equals("minecraft:stone"), "Per-cell material palette must persist");

        MicrovoxelVolume palettePressure = MicrovoxelVolume.full("minecraft:stone");
        int paletteCell = MicrovoxelVolume.index(0, 0, 0);
        for (int material = 0; material < 30; material++) {
            palettePressure.remove(paletteCell);
            palettePressure.put(paletteCell, "test:retired_material_" + material);
        }
        palettePressure.remove(paletteCell);
        require(palettePressure.palette().size() == MicrovoxelVolume.MAX_PALETTE,
                "Palette pressure fixture must reach the hard protocol limit");
        int revisionBeforeCompaction = palettePressure.revision();
        MicrovoxelVolume.CollisionPlan planBeforeCompaction = palettePressure.collisionPlan();
        require(palettePressure.compactPalette(), "Unused historical materials must be compacted");
        require(palettePressure.palette().equals(List.of("", "minecraft:stone")),
                "Compaction must retain only referenced materials in stable order");
        require(palettePressure.revision() == revisionBeforeCompaction,
                "Representation-only palette compaction must not create a fake geometry revision");
        require(palettePressure.collisionPlan() == planBeforeCompaction,
                "Palette compaction must preserve the already compiled occupancy collision plan");
        palettePressure.put(paletteCell, "minecraft:oak_planks");
        require(palettePressure.material(paletteCell).equals("minecraft:oak_planks"),
                "A compacted volume must accept a genuinely new material again");
        require(MicrovoxelManager.isBlockEntityState(Blocks.FURNACE.defaultBlockState()),
                "Container/data-bearing block types must be rejected before microvoxel conversion");
        require(!MicrovoxelManager.isBlockEntityState(Blocks.STONE.defaultBlockState()),
                "Ordinary material blocks must not be mistaken for block entities");

        CoalescingWorkQueue<String> markerQueue = new CoalescingWorkQueue<>();
        require(markerQueue.schedule("world:0:0"),
                "The first chunk-load callback must schedule marker restoration");
        require(!markerQueue.schedule("world:0:0") && markerQueue.scheduledCount() == 1,
                "Repeated C2ME chunk-load callbacks must coalesce into one restore lease");
        require("world:0:0".equals(markerQueue.poll()),
                "The restore lease must be available to the bounded end-of-tick consumer");
        markerQueue.requeue("world:0:0");
        require("world:0:0".equals(markerQueue.poll()),
                "A partially processed marker batch must resume on a later tick");
        markerQueue.complete("world:0:0");
        require(markerQueue.schedule("world:0:0"),
                "A future unload/load cycle must be able to schedule the chunk again");
        markerQueue.complete("world:0:0");

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

        AABB player = new AABB(0.0, 0.0, 0.0, 0.6, 1.8, 0.6);
        AABB wall = new AABB(0.75, 0.0, 0.0, 1.0, 1.0, 1.0);
        double clippedForward = MicrovoxelManager.clipAgainst(player, wall, 0.5, MicrovoxelManager.Axis.X);
        require(close(clippedForward, 0.15), "Server collision must stop exactly at a microvoxel face");
        AABB leftWall = new AABB(-0.5, 0.0, 0.0, -0.25, 1.0, 1.0);
        double clippedBackward = MicrovoxelManager.clipAgainst(player, leftWall, -0.5, MicrovoxelManager.Axis.X);
        require(close(clippedBackward, -0.25), "Negative movement must clip symmetrically");
        AABB overhead = new AABB(0.75, 2.0, 0.0, 1.0, 3.0, 1.0);
        require(close(MicrovoxelManager.clipAgainst(player, overhead, 0.5, MicrovoxelManager.Axis.X), 0.5),
                "Separated axes must never create phantom collision");

        byte[] checkerCells = new byte[MicrovoxelVolume.CELL_COUNT];
        for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            if (((x + y + z) & 1) == 0) checkerCells[MicrovoxelVolume.index(x, y, z)] = 1;
        }
        MicrovoxelVolume checker = MicrovoxelVolume.restore(
                1, List.of("", "minecraft:iron_bars"), checkerCells);
        require(checker.collisionCuboids().size() == 2048,
                "Worst-case fragmented geometry must remain representable without information loss");
        require(checker.collisionPlan().backend() == MicrovoxelVolume.CollisionBackend.GRID,
                "Fragmented geometry must switch to the compact grid collision backend");
        require(checker.collisionPlan().xMask(0, 0) == 0x5555,
                "X collision lines must preserve every alternating occupied cell");
        require(checker.collisionPlan().xMask(0, 1) == 0xAAAA,
                "Neighbouring X collision lines must preserve checkerboard phase");
        VoxelShape nativeCheckerShape = nativeGridShape(checker);
        require(nativeCheckerShape.toAabbs().size() == 2048,
                "Minecraft's native bitset shape must retain every disconnected checker cell");

        AABB positiveSweep = new AABB(-0.2, 0.01, 0.01, -0.1, 0.05, 0.05);
        require(close(MicrovoxelManager.clipGrid(checker.collisionPlan(), 0, 0, 0,
                        positiveSweep, 0.5, MicrovoxelManager.Axis.X), 0.1),
                "Grid collision must stop at the exact first occupied microcell");
        AABB shiftedSweep = new AABB(-0.2, 0.01, 0.07, -0.1, 0.05, 0.11);
        require(close(MicrovoxelManager.clipGrid(checker.collisionPlan(), 0, 0, 0,
                        shiftedSweep, 0.5, MicrovoxelManager.Axis.X), 0.1625),
                "Grid collision must pass through an empty cell and stop at the next occupied one");
        AABB negativeSweep = new AABB(1.1, 0.01, 0.01, 1.2, 0.05, 0.05);
        require(close(MicrovoxelManager.clipGrid(checker.collisionPlan(), 0, 0, 0,
                        negativeSweep, -0.5, MicrovoxelManager.Axis.X), -0.1625),
                "Grid collision must clip negative motion symmetrically at the last occupied microcell");

        Random collisionCases = new Random(0xEC11A5EL);
        for (MicrovoxelManager.Axis axis : MicrovoxelManager.Axis.values()) {
            for (int caseIndex = 0; caseIndex < 400; caseIndex++) {
                boolean positive = (caseIndex & 1) == 0;
                double firstMin = -0.2 + collisionCases.nextDouble() * 1.15;
                double firstSize = 0.01 + collisionCases.nextDouble() * 0.35;
                double secondMin = -0.2 + collisionCases.nextDouble() * 1.15;
                double secondSize = 0.01 + collisionCases.nextDouble() * 0.35;
                double axisMin = positive ? -0.35 : 1.05;
                double axisMax = positive ? -0.05 : 1.35;
                AABB moving = orientedBox(axis, axisMin, axisMax,
                        firstMin, firstMin + firstSize, secondMin, secondMin + secondSize);
                double movement = positive ? 1.5 : -1.5;
                double reference = referenceClip(checker, moving, movement, axis);
                double adaptive = MicrovoxelManager.clipGrid(
                        checker.collisionPlan(), 0, 0, 0, moving, movement, axis);
                double nativeCollision = nativeCheckerShape.collide(
                        Direction.Axis.valueOf(axis.name()), moving, movement);
                require(close(reference, adaptive),
                        "Adaptive grid collision must equal exhaustive cuboid collision for " + axis
                                + " case " + caseIndex + ": expected " + reference + ", got " + adaptive);
                require(close(reference, nativeCollision),
                        "Native bitset VoxelShape must equal exhaustive collision for " + axis
                                + " case " + caseIndex + ": expected " + reference + ", got " + nativeCollision);
            }
        }

        MicrovoxelVolume changedChecker = checker.copy();
        MicrovoxelVolume.CollisionPlan oldPlan = changedChecker.collisionPlan();
        changedChecker.remove(MicrovoxelVolume.index(0, 0, 0));
        MicrovoxelVolume.CollisionPlan newPlan = changedChecker.collisionPlan();
        require(oldPlan != newPlan && (newPlan.xMask(0, 0) & 1) == 0,
                "Editing a volume must invalidate and precisely rebuild its collision plan");

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
        ServerMicrovoxelRaycaster.AdjacentTarget westTarget = nearest.adjacentTarget();
        require(westTarget.key().x() == -1 && westTarget.key().y() == 0 && westTarget.key().z() == 0
                        && MicrovoxelVolume.x(westTarget.cell()) == 15,
                "Adjacent placement must wrap the cell and block coordinate across the west boundary");
        MicrovoxelVolume emptyVolume = MicrovoxelVolume.empty();
        require(emptyVolume.occupiedCount() == 0 && emptyVolume.palette().equals(List.of("")),
                "A cross-boundary target volume must start empty and protocol-valid");

        int hammerAnchor = MicrovoxelVolume.index(0, 8, 8);
        List<Integer> dent = HeavyHammerImpact.cells(hammerAnchor, HeavyHammerImpact.Face.WEST);
        require(dent.size() >= 45, "Тяжёлый молот должен оставлять заметную, а не одиночную вмятину");
        require(dent.stream().allMatch(cell -> MicrovoxelVolume.x(cell) >= 0),
                "Вмятина не должна выходить за границы микровоксельного блока");
        require(dent.stream().anyMatch(cell -> MicrovoxelVolume.x(cell) == 3),
                "Вмятина должна углубляться внутрь поверхности");
        require(HeavyHammerRules.canImpact(HeavyHammerRules.IMPACT_TICK),
                "Сервер обязан разрешить воздействие в кадре контакта");
        require(!HeavyHammerRules.canImpact(HeavyHammerRules.IMPACT_TICK - 1),
                "Сервер не должен разрушать материал до контакта");

        byte[] hammerStart = HeavyHammerProtocol.start(UUID.fromString("00000000-0000-0000-0000-000000000123"),
                17, HeavyHammerRules.DURATION_TICKS, HeavyHammerRules.IMPACT_TICK,
                -12, 64, 27, hammerAnchor, HeavyHammerProtocol.Face.WEST);
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(hammerStart))) {
            require(input.readUnsignedByte() == HeavyHammerProtocol.START, "Пакет молота должен содержать тип START");
            require(input.readLong() == 0L && input.readLong() == 0x123L, "UUID анимации должен передаваться без потерь");
            require(input.readInt() == 17, "Последовательность удара должна сохраняться");
            require(input.readUnsignedShort() == HeavyHammerRules.DURATION_TICKS
                            && input.readUnsignedShort() == HeavyHammerRules.IMPACT_TICK,
                    "Клиент и сервер должны получать одинаковый тайминг");
            require(input.readInt() == -12 && input.readInt() == 64 && input.readInt() == 27,
                    "START обязан передавать блок фактической цели");
            require(input.readUnsignedShort() == hammerAnchor
                            && input.readUnsignedByte() == HeavyHammerProtocol.Face.WEST.ordinal(),
                    "START обязан передавать микровоксель и грань контакта");
            require(input.available() == 0, "В пакете молота не должно быть лишних байтов");
        }

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

    private static AABB orientedBox(MicrovoxelManager.Axis axis, double axisMin, double axisMax,
                                    double firstMin, double firstMax, double secondMin, double secondMax) {
        return switch (axis) {
            case X -> new AABB(axisMin, firstMin, secondMin, axisMax, firstMax, secondMax);
            case Y -> new AABB(firstMin, axisMin, secondMin, firstMax, axisMax, secondMax);
            case Z -> new AABB(firstMin, secondMin, axisMin, firstMax, secondMax, axisMax);
        };
    }

    private static double referenceClip(MicrovoxelVolume volume, AABB moving, double movement,
                                        MicrovoxelManager.Axis axis) {
        double clipped = movement;
        double scale = 1.0 / MicrovoxelVolume.RESOLUTION;
        for (MicrovoxelVolume.Cuboid cuboid : volume.collisionCuboids()) {
            AABB obstacle = new AABB(
                    cuboid.minX() * scale, cuboid.minY() * scale, cuboid.minZ() * scale,
                    cuboid.maxX() * scale, cuboid.maxY() * scale, cuboid.maxZ() * scale);
            clipped = MicrovoxelManager.clipAgainst(moving, obstacle, clipped, axis);
        }
        return clipped;
    }

    private static VoxelShape nativeGridShape(MicrovoxelVolume volume) throws Exception {
        BitSetDiscreteVoxelShape discrete = new BitSetDiscreteVoxelShape(
                MicrovoxelVolume.RESOLUTION, MicrovoxelVolume.RESOLUTION, MicrovoxelVolume.RESOLUTION);
        for (int cell = 0; cell < MicrovoxelVolume.CELL_COUNT; cell++) {
            if (volume.occupied(cell)) {
                discrete.fill(MicrovoxelVolume.x(cell), MicrovoxelVolume.y(cell), MicrovoxelVolume.z(cell));
            }
        }
        var constructor = CubeVoxelShape.class.getDeclaredConstructor(DiscreteVoxelShape.class);
        constructor.setAccessible(true);
        return constructor.newInstance(discrete);
    }
}
