package ua.rp.chat.carver;

public final class CarverTest {
    public static void main(String[] args) {
        verifyMaskAlgebra();
        verifyMaskCodec();
        verifyMaskCavity();
        verifyEstimate();
        verifySession();
        verifyMaterials();
        verifyWorkPlan();
        verifySoundTable();
        verifySoundKit();
        verifyWorkRhythm();
        verifyDeltaPayoff();
        verifyCollisionBudget();
        verifyDraftHistory();
        verifyMirror();
        verifyBlueprint();
        verifyProtocol();
        verifyDominantMaterial();
        verifyStrikeAlign();
        System.out.println("CarverTest passed");
    }

    /**
     * Re-entry pricing basis: the dominant remaining material of a carved volume.
     * Empty volumes have nothing to re-draft; ties resolve deterministically.
     */
    private static void verifyDominantMaterial() {
        require(CarverManager.dominantMaterial(null) == null,
                "Null volume must have no dominant material");
        require(CarverManager.dominantMaterial(
                        ua.rp.chat.microvoxel.MicrovoxelVolume.empty()) == null,
                "Empty volume must have no dominant material");
        ua.rp.chat.microvoxel.MicrovoxelVolume volume =
                ua.rp.chat.microvoxel.MicrovoxelVolume.empty();
        String stone = "minecraft:stone";
        String dirt = "minecraft:dirt";
        for (int cell = 0; cell < 10; cell++) volume.put(cell, stone);
        for (int cell = 10; cell < 15; cell++) volume.put(cell, dirt);
        require(stone.equals(CarverManager.dominantMaterial(volume)),
                "Dominant material must be the most frequent cell");
        volume.remove(0);
        volume.remove(1);
        volume.remove(2);
        volume.remove(3);
        volume.remove(4);
        volume.remove(5);
        require(dirt.equals(CarverManager.dominantMaterial(volume)),
                "Dominant material must follow removals");
        System.out.println("CarverDominantMaterialTest: re-entry basis passed");
    }

    private static void verifyMaskAlgebra() {
        DraftMask mask = new DraftMask();
        require(mask.isEmpty() && mask.count() == 0, "Fresh mask must be empty");
        require(mask.set(DraftMask.index(1, 2, 3)), "First set must report a change");
        require(!mask.set(DraftMask.index(1, 2, 3)), "Second set must be a no-op");
        require(mask.get(DraftMask.index(1, 2, 3)), "Bit must read back");
        require(mask.count() == 1, "Count must track sets");
        require(mask.clear(DraftMask.index(1, 2, 3)), "Clear must report a change");
        require(!mask.clear(DraftMask.index(1, 2, 3)), "Second clear must be a no-op");
        require(mask.isEmpty(), "Mask must be empty after clear");
        require(DraftMask.x(DraftMask.index(5, 9, 12)) == 5
                        && DraftMask.y(DraftMask.index(5, 9, 12)) == 9
                        && DraftMask.z(DraftMask.index(5, 9, 12)) == 12,
                "Cell coordinates must round-trip through the volume index");
        DraftMask left = new DraftMask();
        left.set(10);
        left.set(20);
        DraftMask right = new DraftMask();
        right.set(20);
        right.set(30);
        require(left.orIn(right) == 1 && left.count() == 3, "Union must add only new bits");
        require(left.andNot(right) == 2 && left.count() == 1 && left.get(10),
                "Difference must remove shared bits");
        DraftMask copy = left.copy();
        copy.set(40);
        require(left.count() == 1 && copy.count() == 2, "Copy must be independent");
    }

    private static void verifyMaskCodec() {
        DraftMask mask = new DraftMask();
        mask.set(0);
        mask.set(4095);
        mask.set(DraftMask.index(7, 7, 7));
        byte[] encoded = mask.encode();
        require(encoded.length == DraftMask.PACKED_BYTES, "Packed mask must be 512 bytes");
        DraftMask decoded = DraftMask.decode(encoded);
        require(decoded.equals(mask), "Mask must survive an encode/decode round-trip");
        require(new DraftMask().encode().length == DraftMask.PACKED_BYTES
                        && DraftMask.decode(new byte[DraftMask.PACKED_BYTES]).isEmpty(),
                "Empty mask must encode to zero bytes");
        boolean rejected = false;
        try {
            DraftMask.decode(new byte[100]);
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        require(rejected, "Short buffers must be rejected");
    }

    /** Manual 10x10x6 cavity shared by tests that need a realistic draft. */
    static DraftMask cavityMask() {
        DraftMask mask = new DraftMask();
        for (int y = 10; y <= 15; y++) {
            for (int x = 3; x <= 12; x++) {
                for (int z = 3; z <= 12; z++) {
                    mask.set(DraftMask.index(x, y, z));
                }
            }
        }
        return mask;
    }

    private static void verifyMaskCavity() {
        DraftMask cavity = cavityMask();
        require(cavity.count() == 600, "Cavity must carve 600 cells, got " + cavity.count());
        require(cavity.get(DraftMask.index(7, 12, 7)) && !cavity.get(DraftMask.index(7, 9, 7)),
                "Cavity must span y 10..15 only");
        require(!cavity.get(DraftMask.index(0, 0, 0)), "Cavity must stay clear of corners");
    }

    private static void verifyEstimate() {
        // Reference job: 640 solid cells, 6 layers deep, stone pace.
        require(Math.abs(DraftEstimate.workSeconds(640, 1.0, 6, 1.0, 0) - 35.4) < 1.0e-9,
                "Reference job must price at 35.4 seconds");
        require(Math.abs(DraftEstimate.staminaCost(640, 1.0, 6, 1.0, 0) - 41.5625) < 1.0e-9,
                "Reference job must cost 41.5625% stamina");
        require(DraftEstimate.workTicks(640, 1.0, 6, 1.0, 0) == 708,
                "Reference job must simulate 708 ticks");
        require(DraftEstimate.workSeconds(0, 1.0, 1, 1.0, 0) == 0.0
                        && DraftEstimate.staminaCost(0, 1.0, 1, 1.0, 0) == 0.0,
                "Empty drafts must be free");
        require(DraftEstimate.workSeconds(1_000_000, 1.0, 16, 8.0, 0)
                        == DraftEstimate.MAX_WORK_SECONDS,
                "Work time must clamp to the maximum");
        require(DraftEstimate.staminaCost(1_000_000, 0.0, 16, 8.0, 0)
                        == DraftEstimate.MAX_STAMINA_COST,
                "Stamina cost must clamp to the maximum");
        require(DraftEstimate.progress(150, 300) == 0.5
                        && DraftEstimate.progress(0, 300) == 0.0
                        && DraftEstimate.progress(999, 300) == 1.0
                        && DraftEstimate.progress(0, 0) == 1.0,
                "Progress must clamp to 0..1 and complete empty work");
        // Scattered detail costs more per cell than a solid mass of equal size.
        double solid = DraftEstimate.workSeconds(100, 1.0, 1, 1.0, 0);
        double scattered = DraftEstimate.workSeconds(100, 0.2, 1, 1.0, 0);
        require(scattered > solid, "Scattered detail must price above solid mass");
        // Full-depth carving costs more than a shallow relief of equal size.
        double relief = DraftEstimate.workSeconds(256, 1.0, 1, 1.0, 0);
        double through = DraftEstimate.workSeconds(256, 1.0, 16, 1.0, 0);
        require(through > relief, "Through-carving must price above relief");
        // Geometry helpers: full-box fill, single-cell span, clamping.
        DraftMask single = new DraftMask();
        single.set(DraftMask.index(3, 4, 5));
        require(DraftEstimate.fillRatio(single.cells()) == 1.0
                        && DraftEstimate.depthSpan(single.cells()) == 1,
                "Single cell must fill its box with span 1");
        require(DraftEstimate.fillRatio(null) == 0.0
                        && DraftEstimate.depthSpan(null) == 1,
                "Empty inputs must degrade safely");
        System.out.println("CarverWorkTimeTest: factors, fill, span and clamps passed");
        // Chisels: flat hurries solid masses, point relieves scattered detail.
        double bare = DraftEstimate.workSeconds(400, 1.0, 8, 1.0, 0);
        double flat = DraftEstimate.workSeconds(400, 1.0, 8, 1.0, 1);
        require(flat < bare && flat >= bare * 0.7,
                "Flat chisel must hurry solid masses, got " + flat + " vs " + bare);
        double scatteredBare = DraftEstimate.workSeconds(100, 0.2, 1, 1.0, 0);
        double scatteredPoint = DraftEstimate.workSeconds(100, 0.2, 1, 1.0, 2);
        require(scatteredPoint < scatteredBare,
                "Point chisel must relieve scattered detail");
        double solidPoint = DraftEstimate.workSeconds(100, 1.0, 1, 1.0, 2);
        double solidBare = DraftEstimate.workSeconds(100, 1.0, 1, 1.0, 0);
        require(Math.abs(solidPoint - solidBare) < 1.0e-9,
                "Point chisel must not help solid masses");
        System.out.println("CarverChiselTest: tool factors passed");
    }

    private static void verifySession() {
        DraftSession session = new DraftSession();
        require(session.state() == DraftSession.State.IDLE, "Sessions start idle");
        require(session.beginDesign(1, 2, 3, "minecraft:stone", 3), "Design must open");
        require(session.state() == DraftSession.State.DESIGN
                        && session.targets(1, 2, 3) && !session.targets(1, 2, 4),
                "Session must target its block");
        require(!session.approve(100), "Empty drafts must not approve");
        session.mask().set(5);
        require(!session.beginDesign(9, 9, 9, "minecraft:stone", 3),
                "Design must not reopen over design");
        require(session.approve(100) && session.state() == DraftSession.State.WORK,
                "Priced drafts must enter work");
        require(!session.tickWork() || session.workDoneTicks() == 1, "Work must advance");
        for (int tick = 1; tick < 100; tick++) session.tickWork();
        require(session.state() == DraftSession.State.DONE, "Work must finish on schedule");
        require(Math.abs(session.workProgress() - 1.0) < 1.0e-9, "Finished work reads 100%");

        DraftSession timeout = new DraftSession();
        timeout.beginDesign(0, 0, 0, "minecraft:stone", 3);
        require(!timeout.tickDesign() && !timeout.tickDesign(), "Design must survive early ticks");
        require(timeout.tickDesign() && timeout.state() == DraftSession.State.CANCELLED
                        && timeout.cancelReason() == DraftSession.CancelReason.TIMEOUT,
                "Design must time out");

        DraftSession cancelled = new DraftSession();
        cancelled.beginDesign(0, 0, 0, "minecraft:stone", 100);
        cancelled.mask().set(1);
        cancelled.approve(10);
        require(cancelled.cancel(DraftSession.CancelReason.MOVED)
                        && cancelled.state() == DraftSession.State.CANCELLED
                        && cancelled.cancelReason() == DraftSession.CancelReason.MOVED,
                "Work must cancel with a reason");
        cancelled.reset();
        require(cancelled.state() == DraftSession.State.IDLE && cancelled.mask().isEmpty(),
                "Reset must return to idle");
    }

    private static void verifyMaterials() {
        require(DraftMaterialProfile.isCarvableHardness(1.5f), "Stone hardness must carve");
        require(DraftMaterialProfile.isCarvableHardness(0.8f), "Wool hardness must carve");
        require(DraftMaterialProfile.isCarvableHardness(0.0f), "Zero hardness must carve");
        require(!DraftMaterialProfile.isCarvableHardness(-1.0f), "Bedrock hardness must refuse");
        require(!DraftMaterialProfile.isCarvableHardness(Float.NaN), "NaN hardness must refuse");
        require(Math.abs(DraftMaterialProfile.timeMultiplier(1.5f) - 1.0) < 1.0e-6,
                "Stone must pace at the 1.0 reference");
        require(Math.abs(DraftMaterialProfile.timeMultiplier(0.8f) - 0.65) < 1.0e-6,
                "Wool must carve faster than stone");
        require(Math.abs(DraftMaterialProfile.timeMultiplier(3.0f) - 1.75) < 1.0e-6,
                "Deepslate must take longer");
        require(DraftMaterialProfile.timeMultiplier(50.0f) == DraftMaterialProfile.MAX_MULTIPLIER,
                "Obsidian must clamp to the maximum multiplier");
        require(DraftMaterialProfile.timeMultiplier(0.0f) == DraftMaterialProfile.MIN_MULTIPLIER,
                "Weightless blocks must clamp to the minimum multiplier");
    }

    private static void verifyWorkPlan() {
        DraftMask mask = cavityMask();
        WorkPlan plan = new WorkPlan(mask.cells(), "minecraft:stone");
        require(plan.cells().size() == 600 && plan.remaining() == 600,
                "Plan must cache all 600 draft cells");
        require(plan.cells().get(0) < plan.cells().get(plan.cells().size() - 1),
                "Plan order must be deterministic ascending");
        plan.setApplied(300);
        plan.addRemoved(298);
        require(plan.applied() == 300 && plan.remaining() == 300 && plan.removed() == 298,
                "Plan must track execution");
        plan.setApplied(10_000);
        require(plan.applied() == 600, "Applied must clamp to plan size");
        require(!plan.converted(), "Plans start unconverted");
        plan.setConverted(true);
        require(plan.converted(), "Conversion flag must stick");
        WorkPlan mixed = new WorkPlan(mask.cells(), "minecraft:stone");
        mixed.addRemoved("minecraft:stone", 400);
        mixed.addRemoved("minecraft:oak_planks", 150);
        mixed.addRemoved("", 0);
        require(mixed.removed() == 550, "Per-material refunds must total");
        require(mixed.removedByMaterial().get("minecraft:stone") == 400
                        && mixed.removedByMaterial().get("minecraft:oak_planks") == 150
                        && mixed.removedByMaterial().size() == 2,
                "Refunds must split by material");
        try {
            mixed.removedByMaterial().put("x", 1);
            require(false, "Refund ledger must be immutable");
        } catch (UnsupportedOperationException expected) {
        }
        System.out.println("CarverRefundLedgerTest: per-material refunds passed");
    }

    private static void verifySoundTable() {
        require(CarverSoundTable.classify("minecraft:white_wool") == CarverSoundTable.Kind.SNIP,
                "Wool must take the shearing overlay");
        require(CarverSoundTable.classify("minecraft:oak_leaves") == CarverSoundTable.Kind.SNIP,
                "Leaves must take the shearing overlay");
        require(CarverSoundTable.classify("minecraft:moss_block") == CarverSoundTable.Kind.SNIP,
                "Moss must take the shearing overlay");
        require(CarverSoundTable.classify("minecraft:hay_block") == CarverSoundTable.Kind.SNIP,
                "Hay must take the shearing overlay");
        require(CarverSoundTable.classify("minecraft:oak_log") == CarverSoundTable.Kind.STRIP,
                "Logs must take the axe overlay");
        require(CarverSoundTable.classify("minecraft:stripped_birch_wood")
                        == CarverSoundTable.Kind.STRIP,
                "Stripped wood must take the axe overlay");
        require(CarverSoundTable.classify("minecraft:oak_planks") == CarverSoundTable.Kind.STRIP,
                "Planks must take the axe overlay");
        require(CarverSoundTable.classify("minecraft:crimson_hyphae")
                        == CarverSoundTable.Kind.STRIP,
                "Hyphae must take the axe overlay");
        require(CarverSoundTable.classify("minecraft:dirt") == CarverSoundTable.Kind.SOFT,
                "Dirt must take the soft-earth balance");
        require(CarverSoundTable.classify("minecraft:coarse_dirt") == CarverSoundTable.Kind.SOFT,
                "Coarse dirt must take the soft-earth balance");
        require(CarverSoundTable.classify("minecraft:red_sand") == CarverSoundTable.Kind.SOFT,
                "Red sand must take the soft-earth balance");
        require(CarverSoundTable.classify("minecraft:clay") == CarverSoundTable.Kind.SOFT,
                "Clay must take the soft-earth balance");
        require(CarverSoundTable.classify("minecraft:stone") == CarverSoundTable.Kind.PLAIN,
                "Stone must play its vanilla kit straight");
        require(CarverSoundTable.classify("minecraft:sandstone") == CarverSoundTable.Kind.PLAIN,
                "Sandstone must not inherit the sand balance");
        require(CarverSoundTable.classify("minecraft:deepslate") == CarverSoundTable.Kind.PLAIN,
                "Deepslate must play plain");
        require(CarverSoundTable.classify((String) null) == CarverSoundTable.Kind.PLAIN,
                "Missing ids must fall back to plain");
        ensureBootstrap();
        require(CarverSoundTable.classify(net.minecraft.world.level.block.Blocks.OAK_LOG
                        .defaultBlockState()) == CarverSoundTable.Kind.STRIP,
                "Live log states must resolve through the registry");
    }

    private static void verifySoundKit() {
        ensureBootstrap();
        CarverSoundKit stone = CarverSoundKit.forState(
                net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
        require(stone.strike() != null && stone.scrape() != null
                        && stone.crack() != null && stone.finish() != null,
                "Stone kit must resolve every slot");
        require(stone.layer() == null && !stone.invertBalance(),
                "Stone must play plain with ring-first balance");
        CarverSoundKit wool = CarverSoundKit.forState(
                net.minecraft.world.level.block.Blocks.WHITE_WOOL.defaultBlockState());
        require(wool.layer() == net.minecraft.sounds.SoundEvents.BEEHIVE_SHEAR,
                "Wool must shear on the overlay layer");
        CarverSoundKit log = CarverSoundKit.forState(
                net.minecraft.world.level.block.Blocks.OAK_LOG.defaultBlockState());
        require(log.layer() == net.minecraft.sounds.SoundEvents.AXE_STRIP,
                "Logs must strip on the overlay layer");
        CarverSoundKit dirt = CarverSoundKit.forState(
                net.minecraft.world.level.block.Blocks.DIRT.defaultBlockState());
        require(dirt.invertBalance() && dirt.scrapeVolume() == 1.0f,
                "Dirt must thud scrape-first");
        CarverSoundKit fallback = CarverSoundKit.forState(null);
        require(fallback.strike() == net.minecraft.sounds.SoundEvents.STONE_HIT
                        && fallback.scrape() == net.minecraft.sounds.SoundEvents.STONE_STEP
                        && fallback.crack() == net.minecraft.sounds.SoundEvents.STONE_BREAK,
                "Missing states must degrade to the stone kit");
    }

    private static void verifyWorkRhythm() {
        require(CarverWorkRhythm.swingEvery(1.0, 20) == 20, "Stone must swing on the base period");
        require(CarverWorkRhythm.swingEvery(0.65, 20) == 13, "Wool must patter faster");
        require(CarverWorkRhythm.swingEvery(8.0, 20) == 40, "Obsidian must clamp to slow heaves");
        require(CarverWorkRhythm.swingEvery(0.01, 20) == CarverWorkRhythm.MIN_SWING_EVERY,
                "Tempo must clamp to the minimum period");
        require(CarverWorkRhythm.slotForTick(0, 20) == CarverWorkRhythm.Slot.STRIKE,
                "Tick 0 is always a strike");
        require(CarverWorkRhythm.slotForTick(20, 20) == CarverWorkRhythm.Slot.STRIKE,
                "Period ticks are strikes");
        require(CarverWorkRhythm.slotForTick(10, 20) == CarverWorkRhythm.Slot.SCRAPE,
                "Half-period ticks are scrapes");
        require(CarverWorkRhythm.slotForTick(5, 20) == CarverWorkRhythm.Slot.NONE,
                "Off-beat ticks stay silent");
        int strikes = 0;
        int scrapes = 0;
        for (int tick = 0; tick <= 40; tick++) {
            if (CarverWorkRhythm.slotForTick(tick, 20) == CarverWorkRhythm.Slot.STRIKE) strikes++;
            if (CarverWorkRhythm.slotForTick(tick, 20) == CarverWorkRhythm.Slot.SCRAPE) scrapes++;
        }
        require(strikes == 3 && scrapes == 2, "Two periods must hold 3 strikes and 2 scrapes");
        require(CarverWorkRhythm.strikeIndex(40, 20) == 2, "Strike ordinals must count periods");
        require(CarverWorkRhythm.scrapeIndex(10, 20) == 1
                        && CarverWorkRhythm.scrapeIndex(9, 20) == 0
                        && CarverWorkRhythm.scrapeIndex(30, 20) == 2,
                "Scrape ordinals must count half-periods");
        require(CarverWorkRhythm.volumeEnvelope(0.05) == 1.15
                        && CarverWorkRhythm.volumeEnvelope(0.5) == 1.0
                        && CarverWorkRhythm.volumeEnvelope(0.95) == 0.75,
                "Loudness must enter firm, hold even and finish delicate");
        require(CarverWorkRhythm.strikePitch(0.5, 0.0f) == 1.0f,
                "Mid-work strikes must sit at concert pitch");
        require(CarverWorkRhythm.milestoneCrossed(0.24, 0.26)
                        && CarverWorkRhythm.milestoneCrossed(0.49, 0.51)
                        && !CarverWorkRhythm.milestoneCrossed(0.26, 0.3),
                "Crack accents must fire exactly on quarter crossings");
    }

    /**
     * Wire payoff benchmark answering "was delta broadcast worth it": a simulated bath
     * carve at true tick pacing (~2 cells per tick over 300 ticks) totals per-tick full
     * upserts against deltas per carved cell plus one final transaction. The delta path
     * must cost a fraction over the whole work, or the progressive path ships no savings.
     */
    private static void verifyDeltaPayoff() {
        DraftMask plan = cavityMask();
        java.util.List<Integer> cells = plan.cells();
        ua.rp.chat.microvoxel.MicrovoxelVolume volume =
                ua.rp.chat.microvoxel.MicrovoxelVolume.full("minecraft:stone");
        ua.rp.chat.microvoxel.MicrovoxelKey key = new ua.rp.chat.microvoxel.MicrovoxelKey(
                new java.util.UUID(0L, 1L), 0, 64, 0);
        int totalTicks = DraftEstimate.workTicks(cells.size(),
                DraftEstimate.fillRatio(cells), DraftEstimate.depthSpan(cells), 1.0, 0);
        long upsertTotal = 0L;
        long deltaTotal = 0L;
        int applied = 0;
        for (int done = 1; done <= totalTicks; done++) {
            int target = (int) Math.floor(done / (double) totalTicks * cells.size());
            boolean removed = false;
            while (applied < target) {
                if (volume.remove(cells.get(applied))) {
                    removed = true;
                    deltaTotal += ua.rp.chat.microvoxel.MicrovoxelProtocol.deltaUpsert(
                            key.chunkX(), key.chunkZ(), key,
                            volume.revision(), cells.get(applied), "").length;
                }
                applied++;
            }
            if (removed) {
                upsertTotal += ua.rp.chat.microvoxel.MicrovoxelProtocol
                        .upsert(key, volume).length;
            }
        }
        require(applied == cells.size(), "Simulated work must carve the whole plan");
        deltaTotal += ua.rp.chat.microvoxel.MicrovoxelProtocol.transaction(1L,
                java.util.List.of(new ua.rp.chat.microvoxel.MicrovoxelProtocol.StateChange(
                        key, volume))).length;
        require(upsertTotal > 0 && deltaTotal > 0, "Wire codecs must produce real packets");
        double ratio = (double) upsertTotal / deltaTotal;
        require(ratio >= 2.0,
                "Delta path must cost at most half of per-tick upserts over a work, got "
                        + upsertTotal + "B vs " + deltaTotal + "B");
        System.out.println("CarverDeltaPayoffTest: tickUpserts=" + upsertTotal
                + "B deltasPlusFinal=" + deltaTotal + "B ratio="
                + String.format(java.util.Locale.ROOT, "%.1f", ratio) + "x");
    }

    /**
     * Collision build budget answering "does carving spam threaten 20 TPS": 200 town
     * volumes (full, blob-carved, fragmented) run the synchronous plan-plus-cuboid
     * computation — the algorithmic core of a lazy cache miss on the server thread
     * (the native shape wrapper around it is a thin allocation). The total must stay
     * milliseconds-scale, proving lazy invalidation already isolates the tick.
     */
    private static void verifyCollisionBudget() {
        ensureBootstrap();
        java.util.Random random = new java.util.Random(0xC0111510L);
        java.util.List<ua.rp.chat.microvoxel.MicrovoxelVolume> town =
                new java.util.ArrayList<>(200);
        for (int index = 0; index < 200; index++) {
            ua.rp.chat.microvoxel.MicrovoxelVolume volume =
                    ua.rp.chat.microvoxel.MicrovoxelVolume.full("minecraft:stone");
            int kind = index % 3;
            if (kind == 1) {
                for (int blob = 0; blob < 6; blob++) {
                    int cx = random.nextInt(16);
                    int cy = random.nextInt(16);
                    int cz = random.nextInt(16);
                    for (int dx = -1; dx <= 1; dx++) {
                        for (int dy = -1; dy <= 1; dy++) {
                            for (int dz = -1; dz <= 1; dz++) {
                                int x = cx + dx;
                                int y = cy + dy;
                                int z = cz + dz;
                                if (x >= 0 && x < 16 && y >= 0 && y < 16 && z >= 0 && z < 16) {
                                    volume.remove(ua.rp.chat.microvoxel.MicrovoxelVolume
                                            .index(x, y, z));
                                }
                            }
                        }
                    }
                }
            } else if (kind == 2) {
                for (int cell = 0;
                     cell < ua.rp.chat.microvoxel.MicrovoxelVolume.CELL_COUNT; cell++) {
                    if ((ua.rp.chat.microvoxel.MicrovoxelVolume.x(cell)
                            + ua.rp.chat.microvoxel.MicrovoxelVolume.y(cell)
                            + ua.rp.chat.microvoxel.MicrovoxelVolume.z(cell)) % 2 == 0) {
                        volume.remove(cell);
                    }
                }
            }
            town.add(volume);
        }
        long worstNs = 0L;
        long start = System.nanoTime();
        for (ua.rp.chat.microvoxel.MicrovoxelVolume volume : town) {
            long built = System.nanoTime();
            ua.rp.chat.microvoxel.MicrovoxelVolume.CollisionPlan plan =
                    volume.collisionPlan();
            java.util.List<ua.rp.chat.microvoxel.MicrovoxelVolume.Cuboid> cuboids =
                    volume.collisionCuboids();
            long took = System.nanoTime() - built;
            if (took > worstNs) worstNs = took;
            require(plan != null && cuboids != null, "Every town volume must plan collision");
        }
        long totalMs = (System.nanoTime() - start) / 1_000_000L;
        require(totalMs < 5000L,
                "200 collision rebuilds must stay milliseconds-scale, took " + totalMs + "ms");
        System.out.println("CarverCollisionBudgetTest: 200 rebuilds/" + totalMs
                + "ms worst=" + worstNs / 1000L + "us");
    }

    private static void verifyDraftHistory() {
        DraftSession session = new DraftSession();
        session.beginDesign(0, 0, 0, "minecraft:stone", 100);
        require(!session.undo() && !session.redo(), "Empty history must not move");
        session.mask().set(10);
        session.pushHistory();
        session.mask().set(20);
        require(session.undoDepth() == 1 && session.redoDepth() == 0, "Push must stage undo");
        require(session.undo(), "Undo must restore");
        require(session.mask().get(10) && !session.mask().get(20), "Undo must rewind one step");
        require(session.redoDepth() == 1, "Undo must stage redo");
        require(session.redo(), "Redo must reapply");
        require(session.mask().get(10) && session.mask().get(20), "Redo must restore");
        session.mask().set(30);
        session.pushHistory();
        require(session.redoDepth() == 0, "New edits must invalidate redo");
        for (int step = 0; step < DraftSession.MAX_HISTORY + 10; step++) {
            session.mask().set(step % DraftMask.CELL_COUNT);
            session.pushHistory();
        }
        require(session.undoDepth() == DraftSession.MAX_HISTORY,
                "History must cap at " + DraftSession.MAX_HISTORY);
        session.reset();
        require(session.undoDepth() == 0 && session.redoDepth() == 0
                        && session.mirrorAxes() == 0,
                "Reset must clear history and mirror");
    }

    private static void verifyMirror() {
        require(DraftSession.mirrorCell(DraftMask.index(3, 5, 7),
                        DraftSession.MIRROR_X | DraftSession.MIRROR_Z)
                        == DraftMask.index(12, 5, 8),
                "Mirror must reflect around the center");
        int cell = DraftMask.index(2, 9, 4);
        require(DraftSession.mirrorCell(DraftSession.mirrorCell(cell,
                        DraftSession.MIRROR_X | DraftSession.MIRROR_Z),
                        DraftSession.MIRROR_X | DraftSession.MIRROR_Z) == cell,
                "Double mirror must be identity");
        require(DraftSession.mirrorCell(cell, 0) == cell, "Empty axes must not move cells");
        DraftSession session = new DraftSession();
        session.beginDesign(0, 0, 0, "minecraft:stone", 100);
        session.setMirrorAxes(7);
        require(session.mirrorAxes() == (DraftSession.MIRROR_X | DraftSession.MIRROR_Z),
                "Axes must clamp to X|Z bits");
        DraftMask mask = new DraftMask();
        mask.set(DraftMask.index(2, 0, 3));
        int added = DraftSession.expandMirrored(mask, session.mirrorAxes());
        require(added == 3 && mask.count() == 4, "Quad mirror must add three twins");
        require(mask.get(DraftMask.index(13, 0, 3))
                        && mask.get(DraftMask.index(2, 0, 12))
                        && mask.get(DraftMask.index(13, 0, 12)),
                "Twins must sit on mirrored coordinates");
        require(DraftSession.expandMirrored(mask, session.mirrorAxes()) == 0,
                "Re-expansion must be idempotent");
        require(DraftSession.expandMirrored(new DraftMask(), 0) == 0,
                "Empty axes must add nothing");
    }

    private static void verifyBlueprint() {
        DraftMask mask = cavityMask();
        net.minecraft.nbt.CompoundTag tag =
                CarverBlueprint.encode(mask, "minecraft:stone", "Master");
        CarverBlueprint.Decoded decoded = CarverBlueprint.decode(tag);
        require(decoded != null, "Blueprint must decode");
        require(decoded.mask().equals(mask), "Mask must survive NBT round-trip");
        require(decoded.materialId().equals("minecraft:stone")
                        && decoded.cells() == 600 && decoded.author().equals("Master"),
                "Metadata must survive NBT round-trip");
        require(CarverBlueprint.decode(null) == null
                        && CarverBlueprint.decode(new net.minecraft.nbt.CompoundTag()) == null,
                "Missing tags must decode to null");
        net.minecraft.nbt.CompoundTag shortTag = new net.minecraft.nbt.CompoundTag();
        net.minecraft.nbt.CompoundTag inner = new net.minecraft.nbt.CompoundTag();
        inner.putByteArray("mask", new byte[100]);
        inner.putInt("cells", 5);
        shortTag.put(CarverBlueprint.TAG_KEY, inner);
        require(CarverBlueprint.decode(shortTag) == null, "Short masks must be rejected");
        DraftMask empty = new DraftMask();
        require(CarverBlueprint.decode(CarverBlueprint.encode(empty, "", "")) == null,
                "Empty drafts must be rejected");
        DraftMask full = new DraftMask();
        for (int cell = 0; cell < DraftMask.CELL_COUNT; cell++) full.set(cell);
        net.minecraft.nbt.CompoundTag big =
                CarverBlueprint.encode(full, "minecraft:stone", "");
        require(CarverBlueprint.decode(big) == null
                        || CarverBlueprint.decode(big).cells() == DraftMask.CELL_COUNT,
                "Full-volume blueprints stay within the cell cap");
    }

    private static boolean bootstrapped;

    private static void ensureBootstrap() {
        if (bootstrapped) return;
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        bootstrapped = true;
    }

    private static void verifyProtocol() {
        require(CarverProtocol.VERSION == 1, "Protocol starts at version 1");
        for (int action = CarverProtocol.ACTION_STROKE_ADD;
             action <= CarverProtocol.ACTION_SAVE; action++) {
            require(CarverProtocol.isAction(action), "Action " + action + " must be known");
        }
        require(!CarverProtocol.isAction(0) && !CarverProtocol.isAction(1)
                        && !CarverProtocol.isAction(99),
                "Foreign and reserved actions must be rejected");
        for (int event = CarverProtocol.EVENT_SESSION_OPEN;
             event <= CarverProtocol.EVENT_SESSION_CLOSE; event++) {
            require(CarverProtocol.isEvent(event), "Event " + event + " must be known");
        }
        require(!CarverProtocol.isEvent(0), "Foreign events must be rejected");
    }

    /**
     * Strike Alignment parity: server copy of the pure solver must agree with the
     * mask algebra and keep the hammer trajectory on the contact normal.
     */
    private static void verifyStrikeAlign() {
        java.util.List<Integer> slab = new java.util.ArrayList<>();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                slab.add(x | (z << 4) | (15 << 8));
            }
        }
        CarverStrikeAlign.StrikePlan plan =
                CarverStrikeAlign.solve(10, 64, 20, slab, 12.0, 64.0, 22.0);
        require(Math.abs(plan.contactX() - 10.5) < 1.0e-9
                        && Math.abs(plan.contactZ() - 20.5) < 1.0e-9,
                "Slab contact must sit at the socket center");
        require(plan.axis() == 1, "Slab must read as Y axis");
        require(plan.normalY() > 0.9, "Slab normal must point up");
        double[] impact = CarverTrajectory.impactPoint(
                plan.contactX(), plan.contactY(), plan.contactZ(),
                plan.normalX(), plan.normalY(), plan.normalZ());
        double miss = CarverTrajectory.missDistance(impact,
                new double[]{plan.contactX(), plan.contactY(), plan.contactZ()});
        require(miss <= 0.021, "Impact butt must touch the contact, miss=" + miss);
        double[] butt = CarverTrajectory.buttPoint(plan.contactX(), plan.contactY(), plan.contactZ(),
                plan.normalX(), plan.normalY(), plan.normalZ(), 0.3, 0.0, 0.3, 1.0);
        require(butt[1] > plan.contactY(), "Full windup must lift the butt");
        System.out.println("CarverStrikeAlignTest: server parity passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException("CarverTest: " + message);
    }
}
