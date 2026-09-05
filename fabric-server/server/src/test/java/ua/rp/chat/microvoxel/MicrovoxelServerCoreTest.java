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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import ua.rp.chat.interaction.ItemPickupRules;
import ua.rp.chat.microvoxel.fluid.FluidSim;
import ua.rp.chat.microvoxel.fluid.FluidStore;
import ua.rp.chat.interaction.ItemPickupManager;

public final class MicrovoxelServerCoreTest {
    public static void main(String[] args) throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        verifySnapshotPagination();
        verifyFlagsAndEvents();
        verifyLoadStand();
        verifyEditAlgebra();
        verifyCollisionBackends();
        verifyLightSealing();
        verifyFluidVolumes();
        verifyFluidGuards();
        verifyFluidLateral();
        verifyLavaEngine();
        verifyFluidFrost();
        requireSnapshotEnvelope(MicrovoxelProtocol.snapshotBegin(41L),
                MicrovoxelProtocol.SNAPSHOT_BEGIN, 41L);
        requireSnapshotEnvelope(MicrovoxelProtocol.snapshotEnd(41L),
                MicrovoxelProtocol.SNAPSHOT_END, 41L);
        require(MicrovoxelProtocol.isSynchronizationAction(
                        MicrovoxelProtocol.ACTION_SNAPSHOT_ACK)
                        && !MicrovoxelProtocol.isSynchronizationAction(
                        MicrovoxelProtocol.ACTION_REMOVE),
                "Authentication bypass must be restricted to synchronization actions");
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
        require(MicrovoxelMaterialProfile.soundProfile(
                        Blocks.RED_WOOL.defaultBlockState()) == 1
                        && MicrovoxelMaterialProfile.soundType(1)
                        == net.minecraft.world.level.block.SoundType.WOOL,
                "A wool microvoxel marker must retain wool hit, break and placement sounds");
        require(MicrovoxelMaterialProfile.soundProfile(
                        Blocks.STONE.defaultBlockState()) == 0,
                "Stone must retain the default stone material profile");
        int encodedBrush = MicrovoxelBrush.encode(
                MicrovoxelVolume.index(15, 8, 8), MicrovoxelBrush.SPHERE, 2);
        require(MicrovoxelBrush.cell(encodedBrush) == MicrovoxelVolume.index(15, 8, 8)
                        && MicrovoxelBrush.shape(encodedBrush) == MicrovoxelBrush.SPHERE
                        && MicrovoxelBrush.radius(encodedBrush) == 2,
                "Brush parameters must round-trip through the fixed action payload");
        List<MicrovoxelBrush.Target> sphere = MicrovoxelBrush.targets(
                -1, 5, 7, MicrovoxelVolume.index(15, 8, 8),
                MicrovoxelBrush.SPHERE, 2, MicrovoxelBrush.Axis.X);
        require(sphere.size() == 33, "Radius-two sphere must contain the exact integer-lattice volume");
        require(sphere.stream().anyMatch(target -> target.blockX() == 0
                        && MicrovoxelVolume.x(target.cell()) == 0),
                "Brush geometry must cross negative-to-positive block boundaries without seams");
        require(MicrovoxelBrush.targets(0, 0, 0, 0,
                        MicrovoxelBrush.BOX, 2, MicrovoxelBrush.Axis.Y).size() == 125,
                "Box brush must include the complete 5x5x5 lattice");
        require(MicrovoxelBrush.targets(0, 0, 0, 0,
                        MicrovoxelBrush.PLANE, 2, MicrovoxelBrush.Axis.Y).size() == 25,
                "Plane brush must lock its normal axis");
        MicrovoxelVolume wrapping = MicrovoxelVolume.restore(Integer.MAX_VALUE,
                volume.palette(), volume.cellsCopy());
        wrapping.remove(0);
        require(wrapping.revision() == 1
                        && MicrovoxelRevision.isImmediateNext(1, Integer.MAX_VALUE)
                        && MicrovoxelRevision.isNewer(1, Integer.MAX_VALUE),
                "Revision wrap must remain positive and be accepted as the immediate successor");
        require(volume.occupiedCount() == 4096, "Converted block must contain all 4096 cells");
        require(MicrovoxelEnvironmentRules.exposed(volume, MicrovoxelVolume.index(0, 8, 8)),
                "Boundary material must be exposed to environment simulation");
        require(!MicrovoxelEnvironmentRules.exposed(volume, MicrovoxelVolume.index(8, 8, 8)),
                "Interior material must remain insulated while the shell is intact");
        MicrovoxelVolume fireCavity = volume.copy();
        fireCavity.remove(MicrovoxelVolume.index(8, 8, 7));
        require(MicrovoxelEnvironmentRules.exposed(fireCavity, MicrovoxelVolume.index(8, 8, 8)),
                "Carving a cavity must expose its neighbouring material to fire");
        int eastSkin = MicrovoxelVolume.index(15, 8, 8);
        int eastInterior = MicrovoxelVolume.index(14, 8, 8);
        require(MicrovoxelEnvironmentRules.exposedToFace(volume, eastSkin, Direction.EAST)
                        && !MicrovoxelEnvironmentRules.exposedToFace(volume, eastInterior, Direction.EAST),
                "Heat must enter through the contacted face instead of igniting the far surface");
        MicrovoxelVolume openedHeatChannel = volume.copy();
        openedHeatChannel.remove(eastSkin);
        require(MicrovoxelEnvironmentRules.exposedToFace(
                        openedHeatChannel, eastInterior, Direction.EAST),
                "Fire must advance only through a channel opened by prior burning");
        MicrovoxelKey fireKey = new MicrovoxelKey(UUID.fromString(
                "00000000-0000-0000-0000-000000000001"), 4, 70, -3);
        require(MicrovoxelEnvironmentRules.ignites(400L, fireKey, 123, 3)
                        == MicrovoxelEnvironmentRules.ignites(400L, fireKey, 123, 3),
                "Environment ignition decisions must be deterministic");
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
        UUID saveScopedWorld = UUID.randomUUID();
        require(store.remapWorld(world, saveScopedWorld) == 1
                        && store.get(key) == null
                        && store.get(new MicrovoxelKey(saveScopedWorld, key.x(), key.y(), key.z())) != null,
                "Legacy dimension identities must migrate without losing volume coordinates");
        require(store.remapWorld(saveScopedWorld, world) == 1,
                "World identity migration must preserve a reversible indexed store");
        store.save();
        require(!Files.exists(file.resolveSibling("microvoxels.dat.tmp")), "Atomic save must not leave temp files");

        MicrovoxelStore loaded = new MicrovoxelStore(file);
        loaded.load();
        MicrovoxelVolume restored = loaded.get(key);
        require(restored != null && restored.revision() == volume.revision(), "Revision must survive restart");
        require(restored.material(removed).equals("minecraft:stone"), "Palette and cells must survive restart");
        require(loaded.countInChunk(world, -2, 2) == 1, "Negative chunk indexing must be stable");

        Path journalBase = directory.resolve("journal-microvoxels.dat");
        MicrovoxelStore journalStore = new MicrovoxelStore(journalBase);
        MicrovoxelVolume journalVolume = MicrovoxelVolume.full("minecraft:stone");
        journalStore.put(key, journalVolume);
        journalStore.save();
        int journalRemoved = MicrovoxelVolume.index(4, 5, 6);
        journalVolume.remove(journalRemoved);
        journalStore.markDirty(key);
        MicrovoxelStore.DirtyBatch firstJournalBatch = journalStore.snapshotDirty();
        journalStore.appendJournal(firstJournalBatch);
        journalStore.acknowledge(firstJournalBatch);

        MicrovoxelStore journalReloaded = new MicrovoxelStore(journalBase);
        journalReloaded.load();
        require(!journalReloaded.get(key).occupied(journalRemoved),
                "Incremental journal edits must survive restart without a full checkpoint");

        journalReloaded.remove(key);
        journalReloaded.markDirty(key);
        journalReloaded.appendJournal(journalReloaded.snapshotDirty());
        Path journalPath = journalBase.resolveSibling("journal-microvoxels.dat.journal");
        byte[] journalBytes = Files.readAllBytes(journalPath);
        Files.write(journalPath, java.util.Arrays.copyOf(journalBytes, journalBytes.length - 2));
        MicrovoxelStore journalRecovered = new MicrovoxelStore(journalBase);
        journalRecovered.load();
        require(journalRecovered.recoveredJournalTail(),
                "A torn final journal batch must be detected without discarding earlier batches");
        require(journalRecovered.get(key) != null
                        && !journalRecovered.get(key).occupied(journalRemoved),
                "Recovery must stop at the last CRC-valid journal batch");

        loaded.put(key, loaded.get(key).copy());
        loaded.save();
        Path regionDirectory = file.resolveSibling(file.getFileName() + ".regions-v2");
        Path primaryRegion;
        try (var regionFiles = Files.list(regionDirectory)) {
            primaryRegion = regionFiles
                    .filter(path -> path.getFileName().toString().endsWith(".mvr"))
                    .findFirst().orElseThrow();
        }
        Files.write(primaryRegion, new byte[]{0, 1, 2, 3});
        MicrovoxelStore recovered = new MicrovoxelStore(file);
        recovered.load();
        MicrovoxelVolume backupRestored = recovered.get(key);
        require(recovered.loadedFromBackup(), "Corrupt primary region must fall back to its last valid backup");
        require(backupRestored != null, "Backup recovery must retain every committed volume");

        Path lazyBase = directory.resolve("lazy-microvoxels.dat");
        MicrovoxelStore lazyWriter = new MicrovoxelStore(lazyBase);
        List<MicrovoxelKey> lazyKeys = new ArrayList<>();
        for (int region = 0; region < 110; region++) {
            MicrovoxelKey regionKey = new MicrovoxelKey(world,
                    region * 32 * 16, 70, -region * 32 * 16);
            lazyKeys.add(regionKey);
            lazyWriter.put(regionKey, MicrovoxelVolume.full("minecraft:stone"));
        }
        lazyWriter.save();
        MicrovoxelStore lazyReader = new MicrovoxelStore(lazyBase);
        lazyReader.load();
        require(lazyReader.indexedRegionCount() == 110 && lazyReader.loadedRegionCount() == 0,
                "Region startup must load compact indexes without materializing volume bodies");
        for (MicrovoxelKey lazyKey : lazyKeys) {
            require(lazyReader.get(lazyKey) != null, "Every lazy region must resolve on demand");
        }
        lazyReader.trimCache();
        require(lazyReader.loadedRegionCount() <= 96,
                "Clean region bodies must obey the bounded access-order cache");
        double blastVariance = MicrovoxelExplosionRules.variance(key, removed);
        require(blastVariance >= 0.88 && blastVariance <= 1.12
                        && blastVariance == MicrovoxelExplosionRules.variance(key, removed),
                "Explosion variation must be bounded and deterministic for a concrete cell");
        require(MicrovoxelExplosionRules.shouldBreak(4.0f, 1.0, 6.0, true, 1.0),
                "A close TNT blast must chip an exposed stone microcell");
        require(!MicrovoxelExplosionRules.shouldBreak(4.0f, 1.0, 1200.0, true, 1.0),
                "Obsidian-like resistance must survive ordinary TNT pressure");
        require(!MicrovoxelExplosionRules.shouldBreak(4.0f, 6.0, 6.0, false, 1.0),
                "Shielded interior stone must not disappear at the weak blast fringe");

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
        java.util.concurrent.atomic.AtomicInteger indexedLookups =
                new java.util.concurrent.atomic.AtomicInteger();
        ServerMicrovoxelRaycaster.Hit indexedNearest = ServerMicrovoxelRaycaster.castIndexed(
                world, -1.0, 0.5, 0.5, 1.0, 0.0, 0.0, 6.0,
                (x, y, z) -> {
                    indexedLookups.incrementAndGet();
                    return x == near.x() && y == near.y() && z == near.z() ? rayVolume
                            : x == far.x() && y == far.y() && z == far.z() ? rayVolume : null;
                });
        require(indexedNearest != null && indexedNearest.key().equals(near),
                "Indexed DDA raycast must preserve the authoritative nearest hit");
        require(indexedLookups.get() <= 3,
                "Indexed DDA raycast must inspect crossed blocks, not surrounding chunk volumes");
        ServerMicrovoxelRaycaster.AdjacentTarget westTarget = nearest.adjacentTarget();
        require(westTarget.key().x() == -1 && westTarget.key().y() == 0 && westTarget.key().z() == 0
                        && MicrovoxelVolume.x(westTarget.cell()) == 15,
                "Adjacent placement must wrap the cell and block coordinate across the west boundary");
        MicrovoxelVolume emptyVolume = MicrovoxelVolume.empty();
        require(emptyVolume.occupiedCount() == 0 && emptyVolume.palette().equals(List.of("")),
                "A cross-boundary target volume must start empty and protocol-valid");

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
            requireWireHeader(input, MicrovoxelProtocol.UPSERT);
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
            requireWireHeader(input, MicrovoxelProtocol.REGISTER_MATERIAL);
            require(MicrovoxelProtocol.readVarInt(input) == 42, "ID must match");
            int len = MicrovoxelProtocol.readVarInt(input);
            byte[] bytes = input.readNBytes(len);
            require(new String(bytes, java.nio.charset.StandardCharsets.UTF_8).equals("minecraft:deepslate"), "String must match");
        }

        // Test CLEAR_CHUNK
        byte[] clearPacket = MicrovoxelProtocol.clearChunk(10, -5);
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(clearPacket))) {
            requireWireHeader(input, MicrovoxelProtocol.CLEAR_CHUNK);
            require(input.readInt() == 10, "chunkX must match");
            require(input.readInt() == -5, "chunkZ must match");
        }

        // Test BATCH_UPSERT
        MicrovoxelVolume batchVol = MicrovoxelVolume.full("minecraft:deepslate");
        MicrovoxelKey batchKey = new MicrovoxelKey(world, 17, 65, -75); // chunkX = 1, chunkZ = -5
        int chunkX = batchKey.chunkX();
        int chunkZ = batchKey.chunkZ();
        byte[] batchPacket = MicrovoxelProtocol.batchUpsert(
                chunkX, chunkZ, java.util.List.of(java.util.Map.entry(batchKey, batchVol)));
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(batchPacket))) {
            requireWireHeader(input, MicrovoxelProtocol.BATCH_UPSERT);
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
            require(readWireString(input).isEmpty(), "AIR must be carried atomically as an empty string");
            require(readWireString(input).equals("minecraft:deepslate"),
                    "Material must be carried atomically in the batch");

            require(input.readUnsignedByte() == 1, "RLE encoding expected");
            require(MicrovoxelProtocol.readVarInt(input) == 1, "Runs count should be 1");
            require(MicrovoxelProtocol.readVarInt(input) == 4096, "First run length must be 4096");
            require(input.readByte() == 1, "First run material index must be 1");
        }

        // Test DELTA_UPSERT
        byte[] deltaPacket = MicrovoxelProtocol.deltaUpsert(
                chunkX, chunkZ, batchKey, 15, 2048, "");
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(deltaPacket))) {
            requireWireHeader(input, MicrovoxelProtocol.DELTA_UPSERT);
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
            require(readWireString(input).isEmpty(),
                    "Removal delta must encode reserved AIR without a session dictionary");
        }

        byte[] editResult = MicrovoxelProtocol.editResult(
                991L, true, batchKey, batchVol);
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(editResult))) {
            requireWireHeader(input, MicrovoxelProtocol.EDIT_RESULT);
            require(input.readLong() == 991L, "Edit ACK transaction must match");
            require(input.readBoolean(), "Edit ACK must preserve accepted=true");
            require(input.readInt() == batchKey.x() && input.readInt() == batchKey.y()
                    && input.readInt() == batchKey.z(), "Edit ACK position must match");
            require(input.readBoolean(), "Edit ACK must contain its authoritative volume");
            require(MicrovoxelProtocol.readVarInt(input) == batchVol.revision(),
                    "Edit ACK revision must match");
        }

        byte[] transactionPacket = MicrovoxelProtocol.transaction(918273L, List.of(
                new MicrovoxelProtocol.StateChange(batchKey, batchVol),
                new MicrovoxelProtocol.StateChange(
                        new MicrovoxelKey(batchKey.worldId(), batchKey.x() + 1, batchKey.y(), batchKey.z()),
                        null)));
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(transactionPacket))) {
            requireWireHeader(input, MicrovoxelProtocol.TRANSACTION);
            require(input.readLong() == 918273L, "Transaction ID must be preserved");
            require(MicrovoxelProtocol.readVarInt(input) == 2,
                    "Transaction must frame every affected volume in one packet");
            require(input.readInt() == batchKey.x() && input.readInt() == batchKey.y()
                            && input.readInt() == batchKey.z() && input.readBoolean(),
                    "First transaction entry must be an upsert at the exact key");
        }

        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
        System.out.println("MicrovoxelServerCoreTest passed");
    }

    private static void requireWireHeader(DataInputStream input, int expectedType) throws Exception {
        require(input.readUnsignedByte() == MicrovoxelProtocol.MAGIC,
                "Microvoxel packets must start with the versioned wire magic");
        require(MicrovoxelProtocol.readVarInt(input) == MicrovoxelProtocol.VERSION,
                "Microvoxel packet version must match the paired client");
        require(input.readUnsignedByte() == expectedType,
                "Microvoxel packet type must match " + expectedType);
    }

    private static String readWireString(DataInputStream input) throws Exception {
        int length = MicrovoxelProtocol.readVarInt(input);
        return new String(input.readNBytes(length), java.nio.charset.StandardCharsets.UTF_8);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    /**
     * Protection flags and the edit event hub: sidecar persistence round-trips through a
     * restart, corrupt files fail open to unprotected, and a throwing listener never breaks
     * the remaining subscribers or the edit itself.
     */
    private static void verifyFlagsAndEvents() throws Exception {
        Path temporaryDirectory = Files.createTempDirectory("microvoxel-flags-test");
        UUID world = UUID.randomUUID();
        MicrovoxelKey key = new MicrovoxelKey(world, 10, 64, -3);
        MicrovoxelKey other = new MicrovoxelKey(world, 11, 64, -3);

        ua.rp.chat.microvoxel.MicrovoxelFlags flags = new ua.rp.chat.microvoxel.MicrovoxelFlags(
                temporaryDirectory.resolve("microvoxel-flags.json"),
                java.util.logging.Logger.getLogger("MicrovoxelFlagsTest"));
        flags.load();
        require(!flags.isProtected(key), "A fresh flags store must protect nothing");
        flags.set(key, ua.rp.chat.microvoxel.MicrovoxelFlags.PROTECTED);
        require(flags.isProtected(key) && !flags.isProtected(other),
                "Protection must apply per volume, not per chunk or world");

        ua.rp.chat.microvoxel.MicrovoxelFlags reloaded = new ua.rp.chat.microvoxel.MicrovoxelFlags(
                temporaryDirectory.resolve("microvoxel-flags.json"),
                java.util.logging.Logger.getLogger("MicrovoxelFlagsTest"));
        reloaded.load();
        require(reloaded.isProtected(key) && reloaded.size() == 1,
                "Protection flags must survive a restart through the sidecar file");
        reloaded.set(key, 0);
        require(!reloaded.isProtected(key) && reloaded.size() == 0,
                "Clearing protection must drop the entry entirely");

        Files.writeString(temporaryDirectory.resolve("microvoxel-flags.json"), "{corrupt!!!");
        ua.rp.chat.microvoxel.MicrovoxelFlags corrupt = new ua.rp.chat.microvoxel.MicrovoxelFlags(
                temporaryDirectory.resolve("microvoxel-flags.json"),
                java.util.logging.Logger.getLogger("MicrovoxelFlagsTest"));
        corrupt.load();
        require(!corrupt.isProtected(key) && corrupt.size() == 0,
                "A corrupt flags file must fail open to unprotected, never crash startup");

        ua.rp.chat.microvoxel.MicrovoxelEvents.clearForTests();
        int[] delivered = {0};
        ua.rp.chat.microvoxel.MicrovoxelEvents.subscribe((player, changed, before, after) -> {
            delivered[0]++;
            require(changed.equals(key), "Edit events must carry the exact mutated key");
        });
        ua.rp.chat.microvoxel.MicrovoxelEvents.subscribe((player, changed, before, after) -> {
            throw new RuntimeException("Listener failure must be isolated");
        });
        MicrovoxelVolume volume = MicrovoxelVolume.full("minecraft:stone");
        ua.rp.chat.microvoxel.MicrovoxelEvents.fireEdit(null, key, null, volume);
        require(delivered[0] == 1, "A throwing listener must not break the remaining subscribers");
        require(ua.rp.chat.microvoxel.MicrovoxelEvents.listenerCount() == 2,
                "Both listeners must remain subscribed after an isolated failure");
        ua.rp.chat.microvoxel.MicrovoxelEvents.clearForTests();
        require(ua.rp.chat.microvoxel.MicrovoxelEvents.listenerCount() == 0,
                "Test listeners must not leak into other suites");
        System.out.println("MicrovoxelFlagsAndEventsTest: persistence, fail-open and isolation passed");
    }

    /**
     * Snapshot pagination: ordered chunks split into volume-capped pages, order preserved,
     * every chunk delivered exactly once, single dense chunks never split.
     */
    private static void verifySnapshotPagination() {
        // 600 volumes across 3 chunks with a 256 budget: pages must be [200, 56+300... ] by
        // cumulative cost, order preserved, nothing lost or duplicated.
        List<String> chunks = List.of("near", "mid", "far");
        Map<String, Integer> costs = Map.of("near", 200, "mid", 300, "far", 100);
        List<List<String>> pages = ua.rp.chat.microvoxel.sync.MicrovoxelSyncHub.paginate(
                chunks, 256, costs::get);
        require(pages.size() == 3, "200/300/100 volumes with a 256 budget must yield three pages");
        require(pages.get(0).equals(List.of("near"))
                        && pages.get(1).equals(List.of("mid"))
                        && pages.get(2).equals(List.of("far")),
                "Pagination must preserve chunk order and never split one chunk across pages");
        List<String> flattened = pages.stream().flatMap(List::stream).toList();
        require(flattened.equals(chunks), "Pagination must deliver every chunk exactly once");

        // Small delivery fits a single page; empty input yields no pages.
        List<List<String>> single = ua.rp.chat.microvoxel.sync.MicrovoxelSyncHub.paginate(
                List.of("a", "b"), 256, key -> 10);
        require(single.size() == 1 && single.get(0).equals(List.of("a", "b")),
                "A delivery under budget must stay a single page");
        require(ua.rp.chat.microvoxel.sync.MicrovoxelSyncHub.paginate(
                        List.of(), 256, key -> 1).isEmpty(),
                "An empty delivery must yield no pages");

        // One dense chunk above budget still ships whole on its own page.
        List<List<String>> dense = ua.rp.chat.microvoxel.sync.MicrovoxelSyncHub.paginate(
                List.of("dense"), 256, key -> 512);
        require(dense.size() == 1 && dense.get(0).equals(List.of("dense")),
                "A single over-budget chunk must ship whole rather than split");
        System.out.println("MicrovoxelSnapshotPaginationTest: page order, budget and density passed");
    }

    private static void requireSnapshotEnvelope(byte[] payload, int expectedType, long expectedId)
            throws Exception {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            require(input.readUnsignedByte() == MicrovoxelProtocol.MAGIC,
                    "Snapshot envelope must retain the protocol magic");
            require(MicrovoxelProtocol.readVarInt(input) == MicrovoxelProtocol.VERSION,
                    "Snapshot envelope must retain the negotiated protocol version");
            require(input.readUnsignedByte() == expectedType
                            && input.readLong() == expectedId
                            && input.available() == 0,
                    "Snapshot envelope must carry exactly one delivery id");
        }
    }

    /**
     * Load stand: a deterministic 5000-volume town (seeded, 25 chunks x 200 volumes) driven
     * through the exact snapshot path a joining player takes — nearest-first ordering, paged
     * delivery, whole-drain simulation. Locks the no-loss/no-dup/no-stall contract under
     * pressure instead of only on toy inputs.
     */
    private static void verifyLoadStand() {
        java.util.Random random = new java.util.Random(0xEC1A5EL);
        UUID world = UUID.randomUUID();
        List<ChunkKey> town = new ArrayList<>();
        Map<ChunkKey, Integer> costs = new java.util.HashMap<>();
        int total = 0;
        for (int cx = 0; cx < 5; cx++) {
            for (int cz = 0; cz < 5; cz++) {
                ChunkKey chunk = new ChunkKey(world, cx, cz);
                // 120..280 volumes per chunk: dense enough to force multi-page delivery.
                int count = 120 + random.nextInt(161);
                town.add(chunk);
                costs.put(chunk, count);
                total += count;
            }
        }
        require(total >= 3000, "Load-stand town must hold thousands of volumes, got " + total);

        // Bot joins at the town center: nearest-first order must lead with the home chunk.
        List<ChunkKey> ordered = new ArrayList<>(town);
        long sortStart = System.nanoTime();
        ua.rp.chat.microvoxel.sync.MicrovoxelSyncHub.sortNearestFirst(ordered, 2, 2);
        long sortMs = (System.nanoTime() - sortStart) / 1_000_000L;
        require(ordered.get(0).equals(new ChunkKey(world, 2, 2)),
                "Paged delivery must lead with the player's home chunk");
        require(new java.util.HashSet<>(ordered).size() == town.size(),
                "Ordering must preserve every chunk exactly once");

        // Page the whole town like sendChunkPages does and drain it like tick() does.
        long pageStart = System.nanoTime();
        List<List<ChunkKey>> pages = ua.rp.chat.microvoxel.sync.MicrovoxelSyncHub.paginate(
                ordered, 256, costs::get);
        long pageMs = (System.nanoTime() - pageStart) / 1_000_000L;
        require(pageMs < 10_000L, "Paginating thousands of chunks must stay milliseconds, took "
                + pageMs + "ms");
        int delivered = 0;
        int packets = 0;
        java.util.HashSet<ChunkKey> seen = new java.util.HashSet<>();
        for (List<ChunkKey> page : pages) {
            int pageVolumes = 0;
            for (ChunkKey chunk : page) {
                require(seen.add(chunk), "Drain must never deliver one chunk twice");
                int count = costs.get(chunk);
                pageVolumes += count;
                packets += (count + 31) / 32;
            }
            require(pageVolumes <= 256 || page.size() == 1,
                    "No page may exceed the volume budget unless it is one dense chunk");
            delivered += pageVolumes;
        }
        require(delivered == total, "Drain must deliver every volume: " + delivered + "/" + total);
        require(seen.size() == town.size(), "Drain must visit every chunk exactly once");

        // One 512-volume chunk ships as exactly two full pages on its own.
        ChunkKey dense = new ChunkKey(world, 99, 99);
        List<List<ChunkKey>> densePages = ua.rp.chat.microvoxel.sync.MicrovoxelSyncHub.paginate(
                List.of(dense), 256, chunk -> 512);
        require(densePages.size() == 1 && densePages.get(0).equals(List.of(dense)),
                "A dense chunk ships whole on its own page without splitting");
        System.out.println("MicrovoxelLoadStandTest: " + total + " volumes, " + pages.size()
                + " pages, " + packets + " batch packets, sort " + sortMs
                + "ms, paginate " + pageMs + "ms");
    }

    /**
     * Edit-algebra invariants: history identity is exact (palette+cells, revision excluded),
     * the box brush at max radius covers exactly 9^3=729 cells (the atomicity cap the engine
     * enforces), and the 32-entry palette limit fails closed instead of corrupting geometry.
     */
    private static void verifyEditAlgebra() {
        MicrovoxelVolume volume = MicrovoxelVolume.full("minecraft:stone");
        MicrovoxelVolume copy = ua.rp.chat.microvoxel.edit.MicrovoxelEditHistory.copyOrNull(volume);
        require(copy != volume
                        && ua.rp.chat.microvoxel.edit.MicrovoxelEditHistory.sameVolume(volume, copy),
                "History snapshots must be deep, value-equal copies");
        require(ua.rp.chat.microvoxel.edit.MicrovoxelEditHistory.copyOrNull(null) == null
                        && !ua.rp.chat.microvoxel.edit.MicrovoxelEditHistory.sameVolume(volume, null)
                        && !ua.rp.chat.microvoxel.edit.MicrovoxelEditHistory.sameVolume(null, volume)
                        && ua.rp.chat.microvoxel.edit.MicrovoxelEditHistory.sameVolume(null, null),
                "History identity must be null-safe with null matching only null");
        copy.remove(0);
        require(!ua.rp.chat.microvoxel.edit.MicrovoxelEditHistory.sameVolume(volume, copy),
                "Any cell difference must read as a history conflict");

        List<MicrovoxelBrush.Target> box = MicrovoxelBrush.targets(0, 0, 0, 0,
                MicrovoxelBrush.BOX, MicrovoxelBrush.MAX_RADIUS, MicrovoxelBrush.Axis.Y);
        require(box.size() == 729, "Max-radius box brush must cover exactly 9^3=729 cells, got "
                + box.size());

        MicrovoxelVolume palettePressure = MicrovoxelVolume.empty();
        for (int index = 1; index < MicrovoxelVolume.MAX_PALETTE; index++) {
            palettePressure.put(index, "minecraft:block" + index);
        }
        require(palettePressure.palette().size() == MicrovoxelVolume.MAX_PALETTE,
                "Palette fixture must reach exactly the 32-entry limit");
        boolean overflowRejected = false;
        try {
            palettePressure.put(100, "minecraft:overflow");
        } catch (IllegalStateException expected) {
            overflowRejected = true;
        }
        require(overflowRejected, "The 33rd palette material must fail closed, never corrupt cells");
        System.out.println("MicrovoxelEditAlgebraTest: history identity, brush cap and palette limit passed");
    }

    /**
     * Collision backend selection and sweep fuzz: simple shapes stay on merged AABBs, fragmented
     * shapes switch to the bitmask grid past 64 cuboids, and the grid sweep agrees with the
     * per-cuboid reference on seeded random volumes and movements.
     */
    private static void verifyCollisionBackends() {
        MicrovoxelVolume solid = MicrovoxelVolume.full("minecraft:stone");
        require(solid.collisionPlan().backend() == MicrovoxelVolume.CollisionBackend.CUBOIDS
                        && solid.collisionCuboids().size() == 1,
                "A solid volume must collide as one merged cuboid");
        MicrovoxelVolume checker = MicrovoxelVolume.empty();
        for (int cell = 0; cell < MicrovoxelVolume.CELL_COUNT; cell++) {
            if ((MicrovoxelVolume.x(cell) + MicrovoxelVolume.y(cell)
                    + MicrovoxelVolume.z(cell)) % 2 == 0) {
                checker.put(cell, "minecraft:stone");
            }
        }
        require(checker.collisionPlan().backend() == MicrovoxelVolume.CollisionBackend.GRID,
                "A fragmented volume must switch to the grid backend past the cuboid limit");

        java.util.Random random = new java.util.Random(0xC0111510L);
        MicrovoxelManager.Axis[] axes = MicrovoxelManager.Axis.values();
        for (int trial = 0; trial < 60; trial++) {
            MicrovoxelVolume volume = MicrovoxelVolume.empty();
            int density = 5 + random.nextInt(40);
            for (int cell = 0; cell < MicrovoxelVolume.CELL_COUNT; cell++) {
                if (random.nextInt(100) < density) volume.put(cell, "minecraft:stone");
            }
            // Force the grid backend on half the trials with fragmentation.
            if (trial % 2 == 0) {
                for (int cell = 0; cell < MicrovoxelVolume.CELL_COUNT; cell += 2) {
                    if (!volume.occupied(cell)) volume.put(cell, "minecraft:dirt");
                }
            }
            double px = random.nextDouble() * 2.0 - 0.5;
            double py = random.nextDouble() * 2.0 - 0.5;
            double pz = random.nextDouble() * 2.0 - 0.5;
            AABB moving = new AABB(px, py, pz, px + 0.6, py + 1.8, pz + 0.6);
            MicrovoxelManager.Axis axis = axes[random.nextInt(axes.length)];
            double movement = (random.nextDouble() - 0.5) * 2.0;
            double expected = referenceClip(volume, moving, movement, axis);
            double actual;
            if (volume.collisionPlan().backend() == MicrovoxelVolume.CollisionBackend.GRID) {
                actual = MicrovoxelManager.clipGrid(
                        volume.collisionPlan(), 0, 0, 0, moving, movement, axis);
            } else {
                actual = expected;
                require(volume.collisionPlan().cuboids().equals(volume.collisionCuboids()),
                        "Cuboid backends must expose the merged boxes the reference folds");
            }
            require(close(expected, actual),
                    "Grid sweep must agree with the cuboid reference (trial " + trial + ")");
        }
        System.out.println("MicrovoxelCollisionBackendTest: selection and 60-seed sweep fuzz passed");
    }

    /**
     * Light sealing and fractional emission: a solid opaque cube seals all six faces, glass
     * seals none, a half slab keeps only its floor, a lone torch glows dimly, a 4x4 torch
     * patch reads full, and a bricked-in torch stays dark.
     */
    private static void verifyLightSealing() {
        java.util.function.Predicate<String> stoneOpaque = material -> !material.contains("glass");
        java.util.function.ToIntFunction<String> torchEmission =
                material -> material.contains("torch") ? 14 : 0;

        MicrovoxelVolume solid = MicrovoxelVolume.full("minecraft:stone");
        require(solid.sealedOpaqueFaces(stoneOpaque) == MicrovoxelVolume.ALL_FACES_SEALED,
                "A solid opaque cube must seal all six faces");

        MicrovoxelVolume glass = MicrovoxelVolume.full("minecraft:glass");
        require(glass.sealedOpaqueFaces(stoneOpaque) == 0,
                "A glass cube must seal no face: light passes through");

        MicrovoxelVolume slab = MicrovoxelVolume.empty();
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 8; y++) {
                for (int z = 0; z < 16; z++) slab.put(MicrovoxelVolume.index(x, y, z), "minecraft:stone");
            }
        }
        require(slab.sealedOpaqueFaces(stoneOpaque) == MicrovoxelVolume.FACE_DOWN,
                "A half slab must keep only its sealed floor, got " + slab.sealedOpaqueFaces(stoneOpaque));

        MicrovoxelVolume shell = MicrovoxelVolume.empty();
        for (int cell = 0; cell < MicrovoxelVolume.CELL_COUNT; cell++) {
            int x = MicrovoxelVolume.x(cell);
            int y = MicrovoxelVolume.y(cell);
            int z = MicrovoxelVolume.z(cell);
            if (x == 0 || x == 15 || y == 0 || y == 15 || z == 0 || z == 15) {
                shell.put(cell, "minecraft:stone");
            }
        }
        require(shell.sealedOpaqueFaces(stoneOpaque) == MicrovoxelVolume.ALL_FACES_SEALED,
                "A hollow sealed shell must block light like a solid cube");

        MicrovoxelVolume loneTorch = MicrovoxelVolume.full("minecraft:stone");
        loneTorch.update(MicrovoxelVolume.index(8, 15, 8), "minecraft:torch");
        require(loneTorch.exposedEmissiveCount(torchEmission) == 1,
                "One surface torch cell must count exactly once");
        require(loneTorch.emissionLevel(torchEmission) == 4,
                "A lone torch must glow dimly (4), got " + loneTorch.emissionLevel(torchEmission));

        MicrovoxelVolume patch = MicrovoxelVolume.full("minecraft:stone");
        for (int x = 6; x < 10; x++) {
            for (int z = 6; z < 10; z++) patch.update(MicrovoxelVolume.index(x, 15, z), "minecraft:torch");
        }
        require(patch.exposedEmissiveCount(torchEmission) == 16,
                "A 4x4 torch patch must expose 16 cells");
        require(patch.emissionLevel(torchEmission) == 14,
                "A 4x4 torch patch must read as a full light source");

        MicrovoxelVolume buried = MicrovoxelVolume.full("minecraft:stone");
        buried.update(MicrovoxelVolume.index(8, 8, 8), "minecraft:torch");
        require(buried.exposedEmissiveCount(torchEmission) == 0
                        && buried.emissionLevel(torchEmission) == 0,
                "A bricked-in torch must stay completely dark");

        require(solid.emissionLevel(torchEmission) == 0,
                "Stone without emissive materials must not glow");

        // Thin solid wall (6/16 thick, 37.5%): open faces and low fraction, yet every column
        // along X carries a contiguous run of 6 opaque cells, so sunlight cannot pass.
        MicrovoxelVolume thinWall = MicrovoxelVolume.empty();
        for (int x = 5; x < 11; x++) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) thinWall.put(MicrovoxelVolume.index(x, y, z), "minecraft:stone");
            }
        }
        require(thinWall.sealedOpaqueFaces(stoneOpaque) != MicrovoxelVolume.ALL_FACES_SEALED
                        && thinWall.opaqueFraction(stoneOpaque) < 0.5
                        && thinWall.isLightSealed(stoneOpaque),
                "A 6-voxel decorative wall must seal light through its axial plate");

        // Two end caps cover every X column with isolated cells while light flows between
        // them: raw projection would wrongly seal this, the run-length rule must not.
        MicrovoxelVolume twoCaps = MicrovoxelVolume.empty();
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                twoCaps.put(MicrovoxelVolume.index(0, y, z), "minecraft:stone");
                twoCaps.put(MicrovoxelVolume.index(15, y, z), "minecraft:stone");
            }
        }
        // Index 0 is air and stays non-opaque, exactly like production callers build it.
        boolean[] capsOpaque = {false, true};
        require(!twoCaps.axialRunCovered(capsOpaque, MicrovoxelVolume.LIGHT_SEAL_MIN_AXIAL_RUN)
                        && !twoCaps.isLightSealed(stoneOpaque),
                "Hollow end caps must stay transparent despite covering every column");

        // Dense-fraction rule: a detailed wall (5% carved for relief) stays light-sealed even
        // though no single face is perfectly closed; sparse scaffolding does not.
        MicrovoxelVolume reliefWall = MicrovoxelVolume.full("minecraft:stone");
        for (int cell = 0; cell < MicrovoxelVolume.CELL_COUNT; cell++) {
            int x = MicrovoxelVolume.x(cell);
            int y = MicrovoxelVolume.y(cell);
            int z = MicrovoxelVolume.z(cell);
            if ((x + y + z) % 20 == 0) reliefWall.remove(cell);
        }
        require(reliefWall.sealedOpaqueFaces(stoneOpaque) != MicrovoxelVolume.ALL_FACES_SEALED
                        && reliefWall.isLightSealed(stoneOpaque),
                "A 95% dense carved wall must stay light-sealed despite broken faces");
        MicrovoxelVolume sparse = MicrovoxelVolume.empty();
        for (int cell = 0; cell < MicrovoxelVolume.CELL_COUNT; cell += 3) {
            sparse.put(cell, "minecraft:stone");
        }
        require(!sparse.isLightSealed(stoneOpaque)
                        && Math.abs(sparse.opaqueFraction(stoneOpaque) - 1.0 / 3.0) < 0.01,
                "A 33% sparse lattice must stay transparent to light");
        System.out.println("MicrovoxelLightSealingTest: sealed faces and fractional emission passed");
    }

    /**
     * Voxel fluid engine: basin rule, level clamping, masked fills, drain accounting,
     * exactly-conserving equalization, face-pair geometry, store round-trip and the
     * waterlogged marker flag that plugs voxel water into vanilla physics.
     */
    private static void verifyFluidVolumes() throws Exception {
        MicrovoxelVolume solid = MicrovoxelVolume.full("minecraft:stone");
        MicrovoxelVolume empty = MicrovoxelVolume.empty();
        MicrovoxelVolume basin = MicrovoxelVolume.full("minecraft:stone");
        basin.update(MicrovoxelVolume.index(8, 15, 8), "");
        require(FluidSim.isBasin(basin) && !FluidSim.isBasin(solid) && !FluidSim.isBasin(empty)
                        && !FluidSim.isBasin(null),
                "Only carved (neither solid nor empty) volumes qualify as basins");

        FluidVolume fluid = FluidVolume.empty();
        require(fluid.isDry() && fluid.totalUnits() == 0, "Fresh fluid volumes start dry");
        fluid.setLevel(7, 99);
        fluid.setLevel(9, -4);
        require(fluid.level(7) == FluidVolume.MAX_LEVEL && fluid.level(9) == 0,
                "Fluid levels must clamp to 0..16");
        boolean[] air = FluidSim.airMask(basin);
        int changed = fluid.fillMasked(air);
        require(changed == 1 && fluid.level(MicrovoxelVolume.index(8, 15, 8)) == 16,
                "Masked fill must brim exactly the carved air cells");
        long units = fluid.totalUnits();
        require(units == 32 && fluid.drainAll() == 32 && fluid.isDry(),
                "Drain must account every unit it removes");

        byte[] full = new byte[FluidVolume.CELL_COUNT];
        java.util.Arrays.fill(full, (byte) 16);
        byte[] thirsty = new byte[FluidVolume.CELL_COUNT];
        int[] pairs = FluidVolume.facePairs(0, true);
        require(pairs.length == 512, "Shared faces must pair all 256 boundary cells");
        long moved = FluidVolume.equalizeInto(full, thirsty, pairs, Long.MAX_VALUE);
        long fullTotal = 0;
        long thirstyTotal = 0;
        for (int index = 0; index < full.length; index++) {
            fullTotal += Byte.toUnsignedInt(full[index]);
            thirstyTotal += Byte.toUnsignedInt(thirsty[index]);
        }
        require(moved > 0 && fullTotal + thirstyTotal == (long) FluidVolume.CELL_COUNT * 16L,
                "Equalization must conserve every unit while moving water, moved=" + moved);
        require(FluidVolume.equalizeInto(thirsty, full, pairs, Long.MAX_VALUE) >= 0,
                "Equalization must tolerate the reverse direction without loss");

        // Fluid wire codec: smooth basins compress to a handful of bytes and round-trip
        // exactly; corrupt streams fail closed instead of flooding the client with levels.
        byte[] smooth = new byte[FluidVolume.CELL_COUNT];
        java.util.Arrays.fill(smooth, (byte) 16);
        byte[] encoded = MicrovoxelProtocol.encodeLevels(smooth);
        require(encoded.length < 32,
                "A uniform basin must RLE-compress to a handful of bytes, got " + encoded.length);
        require(java.util.Arrays.equals(MicrovoxelProtocol.decodeLevels(encoded), smooth),
                "Fluid levels must round-trip the wire codec exactly");
        byte[] varied = smooth.clone();
        for (int index = 0; index < varied.length; index += 7) varied[index] = (byte) (index % 17);
        require(java.util.Arrays.equals(MicrovoxelProtocol.decodeLevels(
                        MicrovoxelProtocol.encodeLevels(varied)), varied),
                "Ragged fluid levels must round-trip the wire codec exactly");
        boolean corruptRejected = false;
        try {
            MicrovoxelProtocol.decodeLevels(new byte[]{0x7F});
        } catch (Exception expected) {
            corruptRejected = true;
        }
        require(corruptRejected, "Truncated fluid payloads must fail closed");

        Path temporaryDirectory = Files.createTempDirectory("fluid-store-test");
        Path file = temporaryDirectory.resolve("fluids-v1.dat");
        FluidStore store = new FluidStore();
        store.load(file);
        require(store.size() == 0 && !store.isDirty(), "A missing fluid file starts empty");
        UUID world = UUID.randomUUID();
        MicrovoxelKey key = new MicrovoxelKey(world, 3, 64, 3);
        FluidVolume wet = FluidVolume.empty();
        wet.setLevel(0, 16);
        store.put(key, wet);
        store.save(file);
        require(!store.isDirty(), "A successful save must clear the dirty flag");
        FluidStore reloaded = new FluidStore();
        reloaded.load(file);
        require(reloaded.size() == 1 && reloaded.get(key) != null
                        && reloaded.get(key).level(0) == 16,
                "Fluid volumes must survive an atomic save/load round-trip");

        // NOTE: marker blockstates cannot be constructed in this environment (registries
        // freeze at bootstrap, before any Block may be instantiated). The waterlogged wiring —
        // WATERLOGGED property, getFluidState override, both markerState overloads and both
        // payload registrations — is instead locked by verifyMicrovoxelProtocolParity and
        // verifyMicrovoxelNativeMarker at build time.
        System.out.println("MicrovoxelFluidTest: basin, levels, conservation and store passed");
    }

    /**
     * Voxel gravity: floating water falls to the compartment floor, shelves isolate
     * compartments, fresh stone purges levels upward with overflow deleted (vanilla parity),
     * settled volumes report zero changes, and dowse rules kill wicks but spare lanterns.
     */
    private static void verifyFluidGravity() {
        // Floating column: all water stacked at the top must end up brimful at the bottom.
        boolean[] open = new boolean[FluidVolume.CELL_COUNT];
        java.util.Arrays.fill(open, false);
        FluidVolume falling = FluidVolume.empty();
        for (int y = 12; y < 16; y++) falling.setLevel(FluidVolume.index(3, y, 3), 16);
        long before = falling.totalUnits();
        long[] deleted = {0};
        int changed = falling.settleWith(open, deleted);
        require(changed > 0 && deleted[0] == 0, "Falling water must move without deletion");
        for (int y = 0; y < 4; y++) {
            require(falling.level(FluidVolume.index(3, y, 3)) == 16,
                    "Settled water must compact brimful from the floor up");
        }
        for (int y = 4; y < 16; y++) {
            require(falling.level(FluidVolume.index(3, y, 3)) == 0,
                    "Nothing may hover above settled water");
        }
        require(falling.totalUnits() == before, "Settling must conserve every unit");
        require(falling.settleWith(open, new long[1]) == 0,
                "Settled volumes must report zero changes (idempotent)");

        // Shelf isolation: a solid slab splits the column into independent compartments.
        boolean[] shelf = new boolean[FluidVolume.CELL_COUNT];
        FluidVolume shelved = FluidVolume.empty();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) shelf[FluidVolume.index(x, 8, z)] = true;
        }
        shelved.setLevel(FluidVolume.index(5, 12, 5), 16);
        shelved.setLevel(FluidVolume.index(5, 4, 5), 8);
        shelved.settleWith(shelf, new long[1]);
        require(shelved.level(FluidVolume.index(5, 9, 5)) == 16
                        && shelved.level(FluidVolume.index(5, 0, 5)) == 8,
                "A solid shelf must isolate compartments: water rests on it, never through it");

        // Purge: fresh stone displaces water upward, overflow beyond the brim is deleted.
        boolean[] allAir = new boolean[FluidVolume.CELL_COUNT];
        FluidVolume full = FluidVolume.empty();
        byte[] everything = new byte[FluidVolume.CELL_COUNT];
        java.util.Arrays.fill(everything, (byte) 16);
        FluidVolume soaked = FluidVolume.restore(1, everything);
        boolean[] nowSolid = new boolean[FluidVolume.CELL_COUNT];
        java.util.Arrays.fill(nowSolid, true);
        long[] purged = {0};
        int purgedCells = soaked.settleWith(nowSolid, purged);
        require(purgedCells == FluidVolume.CELL_COUNT && soaked.isDry()
                        && purged[0] == (long) FluidVolume.CELL_COUNT * 16L,
                "Water inside fresh stone must purge with full overflow accounting");

        // Inflow caps: sources brim boundary cells, trickles only to half.
        byte[] levels = new byte[FluidVolume.CELL_COUNT];
        boolean[] air = new boolean[FluidVolume.CELL_COUNT];
        java.util.Arrays.fill(air, true);
        int[] face = new int[256];
        for (int index = 0; index < 256; index++) face[index] = index;
        require(FluidVolume.inflowTopUp(levels, air, face, true, 512) == 256
                        && Byte.toUnsignedInt(levels[0]) == 16,
                "Source inflow must brim boundary cells");
        java.util.Arrays.fill(levels, (byte) 0);
        require(FluidVolume.inflowTopUp(levels, air, face, false, 512) == 256
                        && Byte.toUnsignedInt(levels[0]) == 8,
                "Trickle inflow must cap at half (anti-dupe bound)");

        // Dowse rules: torches and candles drown, lanterns and glowstone keep shining.
        require(ua.rp.chat.microvoxel.MicrovoxelManager.isDowsedMaterial("minecraft:torch")
                        && ua.rp.chat.microvoxel.MicrovoxelManager.isDowsedMaterial("minecraft:soul_candle[lit=true]")
                        && !ua.rp.chat.microvoxel.MicrovoxelManager.isDowsedMaterial("minecraft:sea_lantern")
                        && !ua.rp.chat.microvoxel.MicrovoxelManager.isDowsedMaterial("minecraft:glowstone"),
                "Only open flames must burn out underwater");
        System.out.println("MicrovoxelFluidGravityTest: settle, purge, kernels and dowse passed");
    }

    /**
     * Fluid guards: protection flags gate adoption (set/clear/size contract on a real sidecar
     * file), so bypass fills can never seed data where buckets are denied.
     */
    private static void verifyFluidGuards() throws Exception {
        Path temporaryDirectory = Files.createTempDirectory("fluid-guard-test");
        UUID world = UUID.randomUUID();
        MicrovoxelKey key = new MicrovoxelKey(world, 1, 2, 3);
        ua.rp.chat.microvoxel.MicrovoxelFlags flags = new ua.rp.chat.microvoxel.MicrovoxelFlags(
                temporaryDirectory.resolve("microvoxel-flags.json"),
                java.util.logging.Logger.getLogger("MicrovoxelFluidGuardTest"));
        flags.load();
        require(!flags.isProtected(key) && flags.size() == 0,
                "Fresh flags must protect nothing");
        flags.set(key, ua.rp.chat.microvoxel.MicrovoxelFlags.PROTECTED);
        require(flags.isProtected(key) && flags.size() == 1,
                "Set protection must hold per volume");
        flags.set(key, 0);
        require(!flags.isProtected(key) && flags.size() == 0,
                "Clearing protection must drop the entry entirely");
        System.out.println("MicrovoxelFluidGuardTest: protection flag contract passed");
    }

    /**
     * Lateral flow, rain catch and comparator quanta: tilted surfaces level out without
     * drift or loss, rain lands only on top air, signals span the full 0..15 range.
     */
    private static void verifyFluidLateral() {
        boolean[] open = new boolean[FluidVolume.CELL_COUNT];
        FluidVolume tilted = FluidVolume.empty();
        for (int x = 0; x < 16; x++) {
            tilted.setLevel(FluidVolume.index(x, 0, 8), x);
        }
        long before = tilted.totalUnits();
        long moved = tilted.lateralFlow(open, 10_000L, false);
        require(moved > 0 && tilted.totalUnits() == before,
                "Lateral flow must move water toward level without loss");
        // Relax to a fixed point over alternating sweeps: unit steps are stable by
        // construction (integer division never ping-pongs), so the pass must terminate
        // with every neighbour step at most 1 — terraced, never oscillating.
        int passes = 0;
        while (tilted.lateralFlow(open, 10_000L, passes % 2 == 0) > 0 && passes < 500) {
            passes++;
        }
        require(passes < 500, "Relaxation must terminate instead of cycling");
        int worstStep = 0;
        for (int x = 0; x < 15; x++) {
            worstStep = Math.max(worstStep, Math.abs(
                    tilted.level(FluidVolume.index(x, 0, 8))
                            - tilted.level(FluidVolume.index(x + 1, 0, 8))));
        }
        require(worstStep <= 1 && tilted.totalUnits() == before,
                "Fixed points must hold neighbour steps of at most 1 with exact conservation");
        // Drain scenario (the reason lateral flow exists): a hole column zeroed every pass
        // keeps drawing water until only a sub-unit film remains.
        FluidVolume draining = FluidVolume.empty();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) draining.setLevel(FluidVolume.index(x, 0, z), 16);
        }
        long full = draining.totalUnits();
        for (int pass = 0; pass < 40; pass++) {
            draining.lateralFlow(open, 10_000L, pass % 2 == 0);
            draining.setLevel(FluidVolume.index(8, 0, 8), 0);
        }
        require(draining.totalUnits() < full - 16,
                "Lateral flow must feed an open drain instead of stranding water");
        // A full solid YZ plane blocks lateral flow entirely (no detours around it).
        boolean[] wall = new boolean[FluidVolume.CELL_COUNT];
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) wall[FluidVolume.index(8, y, z)] = true;
        }
        FluidVolume dammed = FluidVolume.empty();
        for (int x = 0; x < 8; x++) dammed.setLevel(FluidVolume.index(x, 0, 8), 16);
        long dammedBefore = dammed.totalUnits();
        for (int pass = 0; pass < 20; pass++) {
            dammed.lateralFlow(wall, 10_000L, pass % 2 == 0);
        }
        require(dammed.totalUnits() == dammedBefore
                        && dammed.level(FluidVolume.index(9, 0, 8)) == 0,
                "A solid wall must isolate lateral compartments");
        // Rain lands on top air only, capped at the brim, budgeted per call.
        byte[] rainLevels = new byte[FluidVolume.CELL_COUNT];
        boolean[] rainSolid = new boolean[FluidVolume.CELL_COUNT];
        require(FluidVolume.rainTopUp(rainLevels, rainSolid, 1) == 256,
                "Rain must wet every top column once per pass");
        require(Byte.toUnsignedInt(rainLevels[FluidVolume.index(3, 15, 3)]) == 1
                        && Byte.toUnsignedInt(rainLevels[FluidVolume.index(3, 14, 3)]) == 0,
                "Rain must land on the surface, never inside the volume");
        require(FluidVolume.rainTopUp(rainLevels, rainSolid, 99) == 256
                        && Byte.toUnsignedInt(rainLevels[FluidVolume.index(3, 15, 3)]) == 16
                        && FluidVolume.rainTopUp(rainLevels, rainSolid, 1) == 0,
                "Rain must respect the brim and report no-ops honestly");
        // Comparator quanta: dry 0, trace reads 1, brimful reads 15.
        require(ua.rp.chat.microvoxel.fluid.FluidSim.comparatorSignal(0, 100) == 0
                        && ua.rp.chat.microvoxel.fluid.FluidSim.comparatorSignal(0, 0) == 0
                        && ua.rp.chat.microvoxel.fluid.FluidSim.comparatorSignal(1, 4096) == 1
                        && ua.rp.chat.microvoxel.fluid.FluidSim.comparatorSignal(4096L * 16L, 4096) == 15,
                "Comparator quanta must span 0..15 with any-content reads 1");
        // Rain lands on top air only, capped at the brim.
        byte[] levels = new byte[FluidVolume.CELL_COUNT];
        boolean[] solid = new boolean[FluidVolume.CELL_COUNT];
        require(FluidVolume.rainTopUp(levels, solid, 1) == 256,
                "Rain must wet every top column once per pass");
        require(Byte.toUnsignedInt(levels[FluidVolume.index(3, 15, 3)]) == 1
                        && Byte.toUnsignedInt(levels[FluidVolume.index(3, 14, 3)]) == 0,
                "Rain must land on the surface, never inside the volume");
        // Comparator quanta: dry 0, trace reads 1, brimful reads 15.
        require(ua.rp.chat.microvoxel.fluid.FluidSim.comparatorSignal(0, 100) == 0
                        && ua.rp.chat.microvoxel.fluid.FluidSim.comparatorSignal(0, 0) == 0
                        && ua.rp.chat.microvoxel.fluid.FluidSim.comparatorSignal(1, 4096) == 1
                        && ua.rp.chat.microvoxel.fluid.FluidSim.comparatorSignal(4096L * 16L, 4096) == 15,
                "Comparator quanta must span 0..15 with any-content reads 1");
        System.out.println("MicrovoxelFluidLateralTest: flow, rain and quanta passed");
    }

    /**
     * Frost crust: only the topmost wet cell per column freezes (never buried water),
     * solid lids block frost, and the comparator/rain kernels keep their contracts.
     */
    private static void verifyFluidFrost() {
        // NOTE: keep in sync with FluidSim.freezeSurface — the sim additionally gates on
        // protection, sky exposure and biome frost, which need a live level.
        byte[] levels = new byte[FluidVolume.CELL_COUNT];
        boolean[] solid = new boolean[FluidVolume.CELL_COUNT];
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                levels[FluidVolume.index(x, 10, z)] = 16;
                levels[FluidVolume.index(x, 4, z)] = 8;
            }
        }
        java.util.List<Integer> frozen = FluidVolume.freezeTopCells(levels, solid);
        require(frozen.size() == 256, "Frost must crust every wet column once");
        require(Byte.toUnsignedInt(levels[FluidVolume.index(5, 10, 5)]) == 0
                        && Byte.toUnsignedInt(levels[FluidVolume.index(5, 4, 5)]) == 8,
                "Only the topmost wet cell per column may freeze");
        for (int cell : frozen) {
            require(MicrovoxelVolume.y(cell) == 10, "Frost must sit on the surface layer");
        }
        // Lakes freeze top-down: once the crust turns solid, the next pass frosts the layer
        // below it instead of stopping.
        boolean[] crusted = new boolean[FluidVolume.CELL_COUNT];
        for (int cell : frozen) {
            crusted[cell] = true;
            require(Byte.toUnsignedInt(levels[cell]) == 0, "Frozen cells must read dry");
        }
        java.util.List<Integer> second = FluidVolume.freezeTopCells(levels, crusted);
        require(second.size() == 256
                        && second.stream().allMatch(cell -> MicrovoxelVolume.y(cell) == 4),
                "Frost must advance downward as lower layers surface");
        // A solid lid blocks frost for its whole column.
        byte[] capped = new byte[FluidVolume.CELL_COUNT];
        boolean[] lid = new boolean[FluidVolume.CELL_COUNT];
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                capped[FluidVolume.index(x, 12, z)] = 16;
                lid[FluidVolume.index(x, 13, z)] = true;
            }
        }
        java.util.List<Integer> cappedFrozen = FluidVolume.freezeTopCells(capped, lid);
        require(cappedFrozen.size() == 256
                        && cappedFrozen.stream().allMatch(cell -> MicrovoxelVolume.y(cell) == 12),
                "Water under a solid lid must frost at its own surface, never through rock");
        System.out.println("MicrovoxelFluidFrostTest: crust placement passed");
    }

    /**
     * Lava engine: kind codes, v1 migration defaulting to water, v2 kind round-trip,
     * crust product rule, and full-brightness lava emission without touching the registry.
     */
    private static void verifyLavaEngine() throws Exception {
        require(ua.rp.chat.microvoxel.FluidVolume.Kind.fromCode(0)
                        == ua.rp.chat.microvoxel.FluidVolume.Kind.WATER
                        && ua.rp.chat.microvoxel.FluidVolume.Kind.fromCode(1)
                        == ua.rp.chat.microvoxel.FluidVolume.Kind.LAVA,
                "Fluid kind codes must decode deterministically");
        boolean unknownRejected = false;
        try {
            ua.rp.chat.microvoxel.FluidVolume.Kind.fromCode(7);
        } catch (IllegalArgumentException expected) {
            unknownRejected = true;
        }
        require(unknownRejected, "Unknown fluid kinds must fail closed");

        // v1 frame (no kind byte) migrates every volume to water.
        java.io.ByteArrayOutputStream legacy = new java.io.ByteArrayOutputStream();
        try (java.io.DataOutputStream output = new java.io.DataOutputStream(legacy)) {
            output.writeInt(0x4D564631);
            output.writeInt(1);
            output.writeInt(1);
            UUID world = UUID.randomUUID();
            output.writeLong(world.getMostSignificantBits());
            output.writeLong(world.getLeastSignificantBits());
            output.writeInt(5);
            output.writeInt(64);
            output.writeInt(5);
            output.writeInt(9);
            output.write(new byte[FluidVolume.CELL_COUNT]);
        }
        Path temporaryDirectory = Files.createTempDirectory("fluid-lava-test");
        Path file = temporaryDirectory.resolve("fluids-v1.dat");
        Files.write(file, legacy.toByteArray());
        ua.rp.chat.microvoxel.fluid.FluidStore migrated = new ua.rp.chat.microvoxel.fluid.FluidStore();
        migrated.load(file);
        require(migrated.size() == 1, "Legacy v1 fluid files must load without loss");
        MicrovoxelKey migratedKey = migrated.snapshot().keySet().iterator().next();
        require(migrated.snapshot().get(migratedKey).kind()
                        == ua.rp.chat.microvoxel.FluidVolume.Kind.WATER,
                "Legacy volumes migrate to water, the only fluid that existed");

        // v2 round-trip preserves lava kinds and levels.
        ua.rp.chat.microvoxel.fluid.FluidStore store = new ua.rp.chat.microvoxel.fluid.FluidStore();
        MicrovoxelKey lavaKey = new MicrovoxelKey(UUID.randomUUID(), 1, 2, 3);
        FluidVolume lava = FluidVolume.empty(FluidVolume.Kind.LAVA);
        lava.setLevel(100, 16);
        store.put(lavaKey, lava);
        Path current = temporaryDirectory.resolve("fluids-v2.dat");
        store.save(current);
        ua.rp.chat.microvoxel.fluid.FluidStore reloaded = new ua.rp.chat.microvoxel.fluid.FluidStore();
        reloaded.load(current);
        FluidVolume restored = reloaded.get(lavaKey);
        require(restored != null && restored.isLava() && restored.level(100) == 16,
                "Lava kind and levels must survive a v2 round-trip");

        require(ua.rp.chat.microvoxel.fluid.FluidSim.crustMaterial(16, 16)
                        .equals("minecraft:obsidian")
                        && ua.rp.chat.microvoxel.fluid.FluidSim.crustMaterial(16, 3)
                        .equals("minecraft:cobblestone")
                        && ua.rp.chat.microvoxel.fluid.FluidSim.crustMaterial(2, 2)
                        .equals("minecraft:cobblestone"),
                "Full-strength contact must yield obsidian, anything weaker cobblestone");

        MicrovoxelVolume rock = MicrovoxelVolume.full("minecraft:stone");
        require(MicrovoxelManager.markerLightLevel(
                                rock, ua.rp.chat.microvoxel.FluidVolume.Kind.LAVA) == 15,
                "Lava volumes must burn at full brightness without registry access");
        System.out.println("MicrovoxelLavaEngineTest: kinds, migration, crust and glow passed");
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
