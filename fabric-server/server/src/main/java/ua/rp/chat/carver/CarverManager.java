package ua.rp.chat.carver;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import ua.rp.chat.RPChat;
import ua.rp.chat.client.carver.CarverSyncPayload;
import ua.rp.chat.microvoxel.MicrovoxelBlockStates;
import ua.rp.chat.microvoxel.MicrovoxelKey;
import ua.rp.chat.microvoxel.MicrovoxelMetrics;
import ua.rp.chat.microvoxel.MicrovoxelProtocol;
import ua.rp.chat.microvoxel.MicrovoxelVolume;
import ua.rp.chat.microvoxel.edit.MicrovoxelEditHistory;
import ua.rp.chat.microvoxel.edit.MicrovoxelEligibility;
import ua.rp.chat.vitals.StaminaManager;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Server authority of the Carver drafting system.
 *
 * <p>Owns one {@link DraftSession} per player: validates scroll targeting with an
 * independent server raycast, keeps the authoritative removal mask, prices the draft,
 * simulates the carving work over time with dust, sound and animation heartbeats, and
 * commits the result as one atomic microvoxel transaction (projection, collision,
 * broadcast, history and material refund converge through the same path as brush
 * edits). All entry points run on the server thread.</p>
 */
public final class CarverManager {
    private final RPChat plugin;
    private final ua.rp.chat.microvoxel.MicrovoxelManager microvoxels;
    private final StaminaManager stamina;
    private final CarverTuning tuning = new CarverTuning();
    private final Map<UUID, DraftSession> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, Vec3> anchors = new ConcurrentHashMap<>();
    private final Map<UUID, WorkPlan> plans = new ConcurrentHashMap<>();
    private final Map<UUID, CarverSoundKit> kits = new ConcurrentHashMap<>();
    private final Map<UUID, net.minecraft.core.particles.ParticleOptions> dust = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastDraftOpAt = new ConcurrentHashMap<>();
    private final AtomicLong transactions = new AtomicLong(1L);
    /** Minimum milliseconds between accepted stroke packets per player. */
    private static final long STROKE_THROTTLE_MS = 100L;

    public CarverManager(RPChat plugin,
                             ua.rp.chat.microvoxel.MicrovoxelManager microvoxels,
                             StaminaManager stamina) {
        this.plugin = plugin;
        this.microvoxels = microvoxels;
        this.stamina = stamina;
    }

    public CarverTuning tuning() {
        return tuning;
    }

    public void reloadTuning() {
        tuning.reload(plugin.getConfig());
    }

    public DraftSession sessionOf(UUID playerId) {
        return sessions.get(playerId);
    }

    /** Scroll right-click on a block: the only entry into design mode. */
    public boolean tryEnterDesign(ServerPlayer player, BlockPos pos) {
        if (player == null || player.level() == null) return false;
        if (!CarverItems.holdsScroll(player)) return false;
        if (!CarverItems.hasBag(player)) {
            player.sendSystemMessage(Component.literal(
                    "Без сумки резчика на груди свиток не слушается. Экипируйте её в нагрудный слот."), true);
            return true;
        }
        if (!(player.level() instanceof ServerLevel level)) return false;
        if (!withinReach(player, pos)) {
            player.sendSystemMessage(Component.literal("Блок слишком далеко для разметки."), true);
            return true;
        }
        BlockState state = level.getBlockState(pos);
        String blockId;
        String displayMaterial;
        float hardness;
        if (MicrovoxelEligibility.isEligibleFullBlock(state, pos, level)) {
            blockId = blockId(state);
            displayMaterial = blockId;
            hardness = state.getDestroySpeed(level, pos);
        } else if (ua.rp.chat.microvoxel.MicrovoxelBlocks.isMarker(state)) {
            // Re-entry onto an already carved volume: the draft keeps addressing the
            // same 16x16x16 cells, priced by the dominant remaining material, so an
            // artisan can refine unfinished work instead of starting over elsewhere.
            MicrovoxelKey volumeKey = keyFor(level, pos);
            if (microvoxels.isProtected(volumeKey)) {
                player.sendSystemMessage(Component.literal("Этот блок защищён от изменений."), true);
                return true;
            }
            ua.rp.chat.microvoxel.MicrovoxelVolume volume =
                    microvoxels.microvolumes().get(volumeKey);
            if (volume == null || volume.occupiedCount() <= 0) {
                player.sendSystemMessage(Component.literal(
                        "Здесь уже нечего размечать: объём полностью снят."), true);
                return true;
            }
            String dominant = dominantMaterial(volume);
            if (dominant == null) {
                player.sendSystemMessage(Component.literal(
                        "Не удалось распознать материал объёма."), true);
                return true;
            }
            BlockState materialState;
            try {
                materialState = ua.rp.chat.microvoxel.MicrovoxelBlockStates.parseBlockState(dominant);
            } catch (RuntimeException unreadable) {
                player.sendSystemMessage(Component.literal(
                        "Не удалось распознать материал объёма."), true);
                return true;
            }
            blockId = blockId(materialState);
            // The hologram renders this exact string (properties included), so
            // biome tints and covers survive; the session keeps the bare registry
            // id for stable approve comparisons.
            displayMaterial = dominant;
            hardness = materialState.getDestroySpeed(level, pos);
        } else {
            player.sendSystemMessage(Component.literal(
                    "Свитком размечают сплошной полный блок без содержимого или ранее вырезанный микровоксельный объём: камень, дерево, шерсть, глину."), true);
            return true;
        }
        if (!DraftMaterialProfile.isCarvableHardness(hardness)) {
            player.sendSystemMessage(Component.literal(
                    "Этот блок не поддаётся резцу мастера."), true);
            return true;
        }
        MicrovoxelKey key = keyFor(level, pos);
        if (microvoxels.isProtected(key)) {
            player.sendSystemMessage(Component.literal("Этот блок защищён от изменений."), true);
            return true;
        }
        closeSession(player, DraftSession.CancelReason.PLAYER_REQUEST, false);
        DraftSession session = new DraftSession();
        session.beginDesign(pos.getX(), pos.getY(), pos.getZ(), blockId,
                tuning.designTimeoutSeconds * DraftEstimate.TICKS_PER_SECOND);
        session.setMaterialMultiplier(DraftMaterialProfile.timeMultiplier(hardness));
        net.minecraft.world.item.ItemStack held = player.getItemInHand(
                net.minecraft.world.InteractionHand.MAIN_HAND);
        CarverBlueprint.Decoded blueprint =
                held != null && held.is(CarverItems.SCROLL) ? CarverBlueprint.readScroll(held) : null;
        sessions.put(player.getUUID(), session);
        anchors.put(player.getUUID(), player.position());
        if (blueprint != null) {
            session.mask().orIn(blueprint.mask());
            MicrovoxelMetrics.inc("carver.blueprint.load");
        }
        sendEvent(player, CarverProtocol.EVENT_SESSION_OPEN, pos,
                CarverSyncPayload.openData(displayMaterial, tuning.designTimeoutSeconds * 20));
        if (blueprint != null) {
            sendDraft(player, session);
            broadcastDraft(player, session);
            player.sendSystemMessage(Component.literal("Чертёж из свитка загружен: "
                    + blueprint.cells() + " вокселей."), true);
        }
        sendEstimate(player, session);
        player.sendSystemMessage(Component.literal(
                "Режим проектирования: чертите удаление на сетке 16×16×16, Пробел — начать работу."), true);
        level.playSound(null, pos, SoundEvents.BOOK_PAGE_TURN, SoundSource.BLOCKS, 0.7f, 1.0f);
        MicrovoxelMetrics.inc("carver.design.open");
        return true;
    }

    public void handleAction(ServerPlayer player, int action, int x, int y, int z, byte[] data) {
        if (player == null || !CarverProtocol.isAction(action)) return;
        if (!RPChat.hasPermission(player, "rpchat.carver", 0)) return;
        switch (action) {
            case CarverProtocol.ACTION_STROKE_ADD -> applyStroke(player, x, y, z, data, true);
            case CarverProtocol.ACTION_STROKE_ERASE -> applyStroke(player, x, y, z, data, false);
            case CarverProtocol.ACTION_BOX_ADD -> applyBox(player, x, y, z, data, true);
            case CarverProtocol.ACTION_BOX_ERASE -> applyBox(player, x, y, z, data, false);
            case CarverProtocol.ACTION_UNDO -> applyHistory(player, x, y, z, true);
            case CarverProtocol.ACTION_REDO -> applyHistory(player, x, y, z, false);
            case CarverProtocol.ACTION_MIRROR_SET -> applyMirror(player, x, y, z, data);
            case CarverProtocol.ACTION_SAVE -> applySave(player, x, y, z);
            case CarverProtocol.ACTION_AUTOWALK -> applyAutowalk(player, x, y, z);
            case CarverProtocol.ACTION_CLEAR_DRAFT -> clearDraft(player, x, y, z);
            case CarverProtocol.ACTION_APPROVE -> approve(player, x, y, z);
            case CarverProtocol.ACTION_CANCEL -> closeSession(player,
                    DraftSession.CancelReason.PLAYER_REQUEST, true);
            default -> {
            }
        }
    }

    public void tick() {
        for (Map.Entry<UUID, DraftSession> entry : sessions.entrySet()) {
            UUID playerId = entry.getKey();
            DraftSession session = entry.getValue();
            ServerPlayer player = playerById(playerId);
            if (player == null || !player.isAlive()) {
                dropSession(playerId);
                continue;
            }
            if (!(player.level() instanceof ServerLevel level)) continue;
            BlockPos focus = new BlockPos(session.blockX(), session.blockY(), session.blockZ());
            if (session.state() == DraftSession.State.DESIGN) {
                if (!session.autowalk() && leashedOut(player, tuning.designLeashBlocks)) {
                    closeSession(player, DraftSession.CancelReason.MOVED, true);
                    continue;
                }
                if (session.tickDesign()) {
                    player.sendSystemMessage(Component.literal("Чертёж рассыпался пылью: время вышло."), true);
                    closeSession(player, DraftSession.CancelReason.TIMEOUT, true);
                }
            } else if (session.state() == DraftSession.State.WORK) {
                if (leashedOut(player, tuning.workLeashBlocks)) {
                    cancelWork(player, level, DraftSession.CancelReason.MOVED,
                            "Движение сбило руку мастера.");
                    continue;
                }
                tickWork(player, level, focus, session);
            } else {
                sessions.remove(playerId);
                anchors.remove(playerId);
            }
        }
    }

    /** Movement/damage during work breaks the carving; design only breaks on leaving. */
    public void onDamaged(ServerPlayer player) {
        if (player == null) return;
        DraftSession session = sessions.get(player.getUUID());
        if (session == null) return;
        if (session.state() == DraftSession.State.WORK
                && player.level() instanceof ServerLevel level) {
            cancelWork(player, level, DraftSession.CancelReason.DAMAGED,
                    "Удар сбил руку мастера.");
        } else if (session.state() == DraftSession.State.DESIGN && session.autowalk()) {
            closeSession(player, DraftSession.CancelReason.DAMAGED, true);
            player.sendSystemMessage(Component.literal("Удар прервал подход мастера."), true);
        }
    }

    public void onQuit(ServerPlayer player) {
        if (player == null) return;
        dropSession(player.getUUID());
    }

    /**
     * Drops every per-player session record. The artisan notification stays with
     * the explicit close path; observers always get a silent close so their chalk
     * never pins a stale outline.
     */
    private void dropSession(UUID playerId) {
        DraftSession session = sessions.get(playerId);
        sessions.remove(playerId);
        anchors.remove(playerId);
        plans.remove(playerId);
        kits.remove(playerId);
        dust.remove(playerId);
        lastDraftOpAt.remove(playerId);
        if (session != null && (session.state() == DraftSession.State.DESIGN
                || session.state() == DraftSession.State.WORK)) {
            broadcastSessionClose(playerId, session);
        }
    }

    /**
     * Mirrors the live draft to nearby players without sessions of their own, so
     * watchers see the chalk outline. Players mid-draft are skipped: their client
     * owns the channel for its own focus, and old builds would misread a foreign
     * close as their own.
     */
    private void broadcastDraft(ServerPlayer artisan, DraftSession session) {
        if (!(artisan.level() instanceof ServerLevel level)) return;
        BlockPos pos = new BlockPos(session.blockX(), session.blockY(), session.blockZ());
        byte[] data = session.mask().encode();
        for (ServerPlayer observer : level.players()) {
            if (observer == null || observer.getUUID().equals(artisan.getUUID())) continue;
            if (sessions.containsKey(observer.getUUID())) continue;
            try {
                ServerPlayNetworking.send(observer, new CarverSyncPayload(
                        CarverProtocol.VERSION, CarverProtocol.EVENT_DRAFT_STATE,
                        pos.getX(), pos.getY(), pos.getZ(), data));
            } catch (RuntimeException broadcastFailed) {
                MicrovoxelMetrics.inc("carver.observed.broadcast.failed");
            }
        }
    }

    /** Silent observer close: reason zero keeps foreign clients quiet. */
    private void broadcastSessionClose(UUID playerId, DraftSession session) {
        ServerPlayer artisan = playerById(playerId);
        if (artisan == null || !(artisan.level() instanceof ServerLevel level)) return;
        BlockPos pos = new BlockPos(session.blockX(), session.blockY(), session.blockZ());
        byte[] data = CarverSyncPayload.closeData(DraftSession.CancelReason.PLAYER_REQUEST.ordinal());
        for (ServerPlayer observer : level.players()) {
            if (observer == null || observer.getUUID().equals(playerId)) continue;
            if (sessions.containsKey(observer.getUUID())) continue;
            try {
                ServerPlayNetworking.send(observer, new CarverSyncPayload(
                        CarverProtocol.VERSION, CarverProtocol.EVENT_SESSION_CLOSE,
                        pos.getX(), pos.getY(), pos.getZ(), data));
            } catch (RuntimeException broadcastFailed) {
                MicrovoxelMetrics.inc("carver.observed.broadcast.failed");
            }
        }
    }

    public void giveKit(ServerPlayer player) {
        if (player == null) return;
        if (!CarverItems.hasBag(player)) player.getInventory().add(CarverItems.bagStack());
        if (!CarverItems.holdsScroll(player)
                && !player.getInventory().contains(CarverItems.scrollStack())) {
            player.getInventory().add(CarverItems.scrollStack());
        }
        net.minecraft.world.item.ItemStack flat = new net.minecraft.world.item.ItemStack(
                CarverItems.CHISEL_FLAT);
        net.minecraft.world.item.ItemStack point = new net.minecraft.world.item.ItemStack(
                CarverItems.CHISEL_POINT);
        if (!player.getInventory().contains(flat)) player.getInventory().add(flat);
        if (!player.getInventory().contains(point)) player.getInventory().add(point);
        player.sendSystemMessage(Component.literal(
                "Набор резчика выдан: сумка — в нагрудный слот, свиток — в главную руку, "
                        + "долота — во вторую руку (плоское для массы, точечное для деталей)."), true);
    }

    private void applyStroke(ServerPlayer player, int x, int y, int z, byte[] data, boolean add) {
        DraftSession session = sessions.get(player.getUUID());
        if (session == null || session.state() != DraftSession.State.DESIGN
                || !session.targets(x, y, z)) {
            MicrovoxelMetrics.inc("carver.drop.target");
            return;
        }
        long now = System.currentTimeMillis();
        Long previous = lastDraftOpAt.get(player.getUUID());
        if (previous != null && now - previous < STROKE_THROTTLE_MS) {
            MicrovoxelMetrics.inc("carver.drop.throttle");
            return;
        }
        lastDraftOpAt.put(player.getUUID(), now);
        DraftMask stroke;
        try {
            stroke = DraftMask.decode(data);
        } catch (IllegalArgumentException invalid) {
            MicrovoxelMetrics.inc("carver.drop.codec");
            return;
        }
        if (stroke.count() == 0 || stroke.count() > tuning.maxStrokeCells) {
            MicrovoxelMetrics.inc("carver.drop.cap");
            return;
        }
        session.pushHistory();
        if (add) {
            session.mask().orIn(stroke);
            DraftSession.expandMirrored(session.mask(), session.mirrorAxes());
        } else {
            DraftMask erase = stroke.copy();
            DraftSession.expandMirrored(erase, session.mirrorAxes());
            session.mask().andNot(erase);
        }
        sendDraft(player, session);
        broadcastDraft(player, session);
        sendEstimate(player, session);
        MicrovoxelMetrics.add(add ? "carver.stroke.add" : "carver.stroke.erase", stroke.count());
    }

    /**
     * Whole-box select validated exactly like a stroke: same session, target and
     * throttle gates, only the cell cap is larger. Rectangularity is a client
     * concern; the count cap is the abuse boundary.
     */
    private void applyBox(ServerPlayer player, int x, int y, int z, byte[] data, boolean add) {
        DraftSession session = sessions.get(player.getUUID());
        if (session == null || session.state() != DraftSession.State.DESIGN
                || !session.targets(x, y, z)) {
            MicrovoxelMetrics.inc("carver.drop.target");
            return;
        }
        long now = System.currentTimeMillis();
        Long previous = lastDraftOpAt.get(player.getUUID());
        if (previous != null && now - previous < STROKE_THROTTLE_MS) {
            MicrovoxelMetrics.inc("carver.drop.throttle");
            return;
        }
        lastDraftOpAt.put(player.getUUID(), now);
        DraftMask box;
        try {
            box = DraftMask.decode(data);
        } catch (IllegalArgumentException invalid) {
            MicrovoxelMetrics.inc("carver.drop.codec");
            return;
        }
        if (box.count() == 0 || box.count() > tuning.maxBoxCells) {
            MicrovoxelMetrics.inc("carver.drop.cap");
            return;
        }
        session.pushHistory();
        if (add) {
            session.mask().orIn(box);
            DraftSession.expandMirrored(session.mask(), session.mirrorAxes());
        } else {
            DraftMask erase = box.copy();
            DraftSession.expandMirrored(erase, session.mirrorAxes());
            session.mask().andNot(erase);
        }
        sendDraft(player, session);
        broadcastDraft(player, session);
        sendEstimate(player, session);
        MicrovoxelMetrics.add(add ? "carver.box.add" : "carver.box.erase", box.count());
    }

    private void clearDraft(ServerPlayer player, int x, int y, int z) {
        DraftSession session = sessions.get(player.getUUID());
        if (session == null || session.state() != DraftSession.State.DESIGN
                || !session.targets(x, y, z)) return;
        if (session.mask().isEmpty()) return;
        session.pushHistory();
        session.mask().clearAll();
        sendDraft(player, session);
        broadcastDraft(player, session);
        sendEstimate(player, session);
    }

    /** Pre-commit undo/redo over draft snapshots; the world history stays separate. */
    private void applyHistory(ServerPlayer player, int x, int y, int z, boolean undo) {
        DraftSession session = sessions.get(player.getUUID());
        if (session == null || session.state() != DraftSession.State.DESIGN
                || !session.targets(x, y, z)) return;
        boolean moved = undo ? session.undo() : session.redo();
        if (!moved) return;
        sendDraft(player, session);
        broadcastDraft(player, session);
        sendEstimate(player, session);
        MicrovoxelMetrics.inc(undo ? "carver.undo" : "carver.redo");
    }

    private void applyMirror(ServerPlayer player, int x, int y, int z, byte[] data) {
        DraftSession session = sessions.get(player.getUUID());
        if (session == null || session.state() != DraftSession.State.DESIGN
                || !session.targets(x, y, z)) return;
        if (data == null || data.length < 1) return;
        session.setMirrorAxes(data[0] & 0xFF);
        sendEvent(player, CarverProtocol.EVENT_MIRROR_STATE,
                new BlockPos(x, y, z), CarverSyncPayload.mirrorData(session.mirrorAxes()));
        MicrovoxelMetrics.inc("carver.mirror");
    }

    /** Writes the draft into the held scroll as a tradable NBT blueprint. */
    private void applySave(ServerPlayer player, int x, int y, int z) {
        DraftSession session = sessions.get(player.getUUID());
        if (session == null || session.state() != DraftSession.State.DESIGN
                || !session.targets(x, y, z)) return;
        if (session.mask().isEmpty()) {
            player.sendSystemMessage(Component.literal(
                    "Пустой чертёж нечего записывать."), true);
            return;
        }
        net.minecraft.world.item.ItemStack scroll = player.getMainHandItem();
        if (scroll == null || !scroll.is(CarverItems.SCROLL)) {
            player.sendSystemMessage(Component.literal(
                    "Держите свиток в главной руке для записи."), true);
            return;
        }
        CarverBlueprint.writeScroll(scroll, session.mask(), session.materialId(),
                player.getGameProfile().name());
        player.sendSystemMessage(Component.literal("Чертёж записан в свиток: "
                + session.mask().count() + " вокселей. Передайте его другому мастеру."), true);
        MicrovoxelMetrics.inc("carver.blueprint.save");
    }

    /** The artisan approaches on foot: leash suspended, clock still ticking. */
    private void applyAutowalk(ServerPlayer player, int x, int y, int z) {
        DraftSession session = sessions.get(player.getUUID());
        if (session == null || session.state() != DraftSession.State.DESIGN
                || !session.targets(x, y, z)) return;
        session.setAutowalk(true);
        anchors.put(player.getUUID(), player.position());
        MicrovoxelMetrics.inc("carver.autowalk");
    }

    private void approve(ServerPlayer player, int x, int y, int z) {
        DraftSession session = sessions.get(player.getUUID());
        if (session == null || session.state() != DraftSession.State.DESIGN
                || !session.targets(x, y, z)) return;
        if (session.mask().isEmpty()) {
            player.sendSystemMessage(Component.literal("Пустой чертёж: отметьте воксели на удаление."), true);
            return;
        }
        if (!(player.level() instanceof ServerLevel level)) return;
        BlockPos pos = new BlockPos(x, y, z);
        BlockState state = level.getBlockState(pos);
        if (ua.rp.chat.microvoxel.MicrovoxelBlocks.isMarker(state)) {
            MicrovoxelKey volumeKey = keyFor(level, pos);
            ua.rp.chat.microvoxel.MicrovoxelVolume volume =
                    microvoxels.microvolumes().get(volumeKey);
            String dominant = volume == null ? null : dominantMaterial(volume);
            String dominantId = null;
            if (dominant != null) {
                try {
                    dominantId = blockId(
                            ua.rp.chat.microvoxel.MicrovoxelBlockStates.parseBlockState(dominant));
                } catch (RuntimeException unreadable) {
                    dominantId = null;
                }
            }
            if (volume == null || volume.occupiedCount() <= 0
                    || dominantId == null || !dominantId.equals(session.materialId())) {
                closeSession(player, DraftSession.CancelReason.BLOCK_CHANGED, true);
                player.sendSystemMessage(Component.literal("Блок изменился, пока вы чертили."), true);
                return;
            }
        } else {
            String blockId = blockId(state);
            if (!blockId.equals(session.materialId())
                    || !MicrovoxelEligibility.isEligibleFullBlock(state, pos, level)) {
                closeSession(player, DraftSession.CancelReason.BLOCK_CHANGED, true);
                player.sendSystemMessage(Component.literal("Блок изменился, пока вы чертили."), true);
                return;
            }
        }
        List<Integer> cells = session.mask().cells();
        double multiplier = session.materialMultiplier();
        double fill = DraftEstimate.fillRatio(cells);
        int span = DraftEstimate.depthSpan(cells);
        int tool = CarverItems.chiselOf(player.getOffhandItem());
        int workTicks = Math.max(1, DraftEstimate.workTicks(
                cells.size(), fill, span, multiplier, tool));
        double cost = DraftEstimate.staminaCost(cells.size(), fill, span, multiplier, tool);
        if (stamina.escapeStamina(player) < cost) {
            player.sendSystemMessage(Component.literal("Нехватка сил: нужно "
                    + Math.round(cost) + "% стамины, отдохните."), true);
            closeSession(player, DraftSession.CancelReason.NO_STAMINA, true);
            return;
        }
        stamina.consumeWorkEffort(player, cost, cost * 0.3);
        anchors.put(player.getUUID(), player.position());
        plans.put(player.getUUID(), new WorkPlan(session.mask().cells(), session.materialId()));
        kits.put(player.getUUID(), CarverSoundKit.forState(state));
        dust.put(player.getUUID(), new net.minecraft.core.particles.BlockParticleOption(
                net.minecraft.core.particles.ParticleTypes.BLOCK, state));
        if (!session.approve(workTicks)) {
            plans.remove(player.getUUID());
            kits.remove(player.getUUID());
            dust.remove(player.getUUID());
            lastDraftOpAt.remove(player.getUUID());
            return;
        }
        level.playSound(null, pos, kits.get(player.getUUID()).strike(),
                SoundSource.BLOCKS, 0.9f, 1.0f);
        sendEvent(player, CarverProtocol.EVENT_WORK_START, pos,
                CarverSyncPayload.workStartData(workTicks));
        double[] centroid = null;
        try {
            if (cells != null && !cells.isEmpty()) {
                double cx = 0.0;
                double cy = 0.0;
                double cz = 0.0;
                for (int cell : cells) {
                    cx += DraftMask.x(cell) + 0.5;
                    cy += DraftMask.y(cell) + 0.5;
                    cz += DraftMask.z(cell) + 0.5;
                }
                centroid = new double[]{cx / cells.size(), cy / cells.size(), cz / cells.size()};
            }
        } catch (RuntimeException ignored) {
        }
        CarverStrikeAlign.StrikePlan plan = null;
        try {
            plan = CarverStrikeAlign.solve(pos.getX(), pos.getY(), pos.getZ(), cells,
                    player.getX(), player.getY(), player.getZ());
        } catch (RuntimeException ignored) {
        }
        broadcastObserved(level, player, CarverProtocol.EVENT_WORK_OBSERVED_START, pos,
                CarverSyncPayload.observedStartData(
                        player.getUUID(), pos.getX(), pos.getY(), pos.getZ(), workTicks, centroid, plan));
        player.sendSystemMessage(Component.literal("Работа началась. Не двигайтесь, мастер."), true);
        MicrovoxelMetrics.inc("carver.work.start");
    }

    /** One notch of wear on the off-hand chisel per strike; breakage just ends the bonus. */
    private void wearChisel(ServerPlayer player, ServerLevel level) {
        try {
            net.minecraft.world.item.ItemStack held = player.getOffhandItem();
            if (CarverItems.chiselOf(held) == 0 || !(held.getItem() instanceof net.minecraft.world.item.Item)) {
                return;
            }
            held.hurtAndBreak(1, player, net.minecraft.world.entity.EquipmentSlot.OFFHAND);
        } catch (RuntimeException worn) {
            MicrovoxelMetrics.inc("carver.chisel.wear.failed");
        }
    }

    private void tickWork(ServerPlayer player, ServerLevel level, BlockPos focus, DraftSession session) {
        try {
            if (player.isShiftKeyDown()) {
                player.setShiftKeyDown(false);
            }
        } catch (RuntimeException ignored) {
        }
        int done = session.workDoneTicks();
        // One strike, one notch of wear on the held chisel, paced with the work song.
        if (done > 0 && done % CarverWorkRhythm.STRIKE_WEAR_EVERY_TICKS == 0
                && player.level() instanceof ServerLevel workLevel) {
            wearChisel(player, workLevel);
        }
        double progress = session.workProgress();
        CarverSoundKit kit = kits.get(player.getUUID());
        if (kit == null) {
            kit = CarverSoundKit.stoneFallback();
            kits.put(player.getUUID(), kit);
        }
        int swingEvery = CarverWorkRhythm.swingEvery(
                session.materialMultiplier(), tuning.workSwingIntervalTicks);
        CarverWorkRhythm.Slot slot = CarverWorkRhythm.slotForTick(done, swingEvery);
        float volume = (float) (tuning.workSoundVolume * CarverWorkRhythm.volumeEnvelope(progress));
        if (slot == CarverWorkRhythm.Slot.STRIKE) {
            player.swing(InteractionHand.MAIN_HAND);
            float jitter = (player.getRandom().nextFloat() - 0.5f) * 0.06f;
            if (kit.invertBalance()) {
                level.playSound(null, focus, kit.scrape(), SoundSource.BLOCKS,
                        volume, kit.scrapePitch());
            } else {
                level.playSound(null, focus, kit.strike(), SoundSource.BLOCKS,
                        volume, CarverWorkRhythm.strikePitch(progress, jitter));
            }
            if (kit.layer() != null && tuning.workSoundSnipLayer
                    && CarverWorkRhythm.strikeIndex(done, swingEvery)
                    % CarverWorkRhythm.STRIP_EVERY_STRIKE == 0) {
                level.playSound(null, focus, kit.layer(), SoundSource.BLOCKS,
                        volume * 0.5f, 1.0f);
            }
        } else if (slot == CarverWorkRhythm.Slot.SCRAPE) {
            level.playSound(null, focus, kit.scrape(), SoundSource.BLOCKS,
                    volume * kit.scrapeVolume(), kit.scrapePitch());
            if (kit.layer() != null && tuning.workSoundSnipLayer
                    && CarverWorkRhythm.scrapeIndex(done, swingEvery)
                    % CarverWorkRhythm.SNIP_EVERY_SCRAPE == 0) {
                level.playSound(null, focus, kit.layer(), SoundSource.BLOCKS,
                        volume * 0.7f, 1.05f);
            }
        }
        double previous = DraftEstimate.progress(
                Math.max(0, done - 1), session.workTotalTicks());
        if (CarverWorkRhythm.milestoneCrossed(previous, progress)) {
            level.playSound(null, focus, kit.crack(), SoundSource.BLOCKS, volume * 0.8f, 0.95f);
        }
        if (done % tuning.workFxIntervalTicks == 0) {
            net.minecraft.core.particles.ParticleOptions puff =
                    dust.getOrDefault(player.getUUID(),
                            net.minecraft.core.particles.ParticleTypes.CLOUD);
            level.sendParticles(puff,
                    focus.getX() + 0.5, focus.getY() + 0.7, focus.getZ() + 0.5,
                    4, 0.35, 0.25, 0.35, 0.02);
            level.sendParticles(net.minecraft.core.particles.ParticleTypes.POOF,
                    focus.getX() + 0.5, focus.getY() + 0.6, focus.getZ() + 0.5,
                    2, 0.25, 0.2, 0.25, 0.02);
        }
        if (done % 20 == 0 || done + 1 >= session.workTotalTicks()) {
            sendEvent(player, CarverProtocol.EVENT_WORK_PROGRESS, focus,
                    CarverSyncPayload.progressData(done, session.workTotalTicks()));
        }
        boolean finished = session.tickWork();
        applyProgressSlice(player, level, focus, session);
        if (finished && sessions.containsKey(player.getUUID())) {
            finalizeWork(player, level, focus, session, true);
        }
    }

    /**
     * Progressive carving: the cached plan sheds cells in exact plan order as the work
     * clock advances, so the block visibly disappears in real time instead of popping
     * at the end. Every slice converges projection, collision and broadcast like any
     * other edit; the single undoable history entry is written at finalization.
     */
    private void applyProgressSlice(ServerPlayer player, ServerLevel level,
                                    BlockPos pos, DraftSession session) {
        WorkPlan plan = plans.get(player.getUUID());
        if (plan == null) return;
        UUID worldId = microvoxels.runtimeWorldId(level);
        MicrovoxelKey key = new MicrovoxelKey(worldId, pos.getX(), pos.getY(), pos.getZ());
        int target = (int) Math.floor(session.workProgress() * plan.cells().size());
        if (target <= plan.applied()) return;
        MicrovoxelVolume volume = microvoxels.microvolumes().get(key);
        if (volume == null && !plan.converted()) {
            BlockState state = level.getBlockState(pos);
            if (!blockId(state).equals(plan.materialId())
                    || !MicrovoxelEligibility.isEligibleFullBlock(state, pos, level)) {
                finalizeWork(player, level, pos, session, false);
                player.sendSystemMessage(Component.literal("Блок изменился во время работы."), true);
                closeSession(player, DraftSession.CancelReason.BLOCK_CHANGED, true);
                return;
            }
            if (microvoxels.isProtected(key)) {
                finalizeWork(player, level, pos, session, false);
                player.sendSystemMessage(Component.literal("Этот блок защищён от изменений."), true);
                closeSession(player, DraftSession.CancelReason.BLOCK_CHANGED, true);
                return;
            }
            if (microvoxels.microvolumes().countInChunk(worldId, key.chunkX(), key.chunkZ())
                    >= ua.rp.chat.microvoxel.MicrovoxelRuntime.MAX_PER_CHUNK) {
                finalizeWork(player, level, pos, session, false);
                player.sendSystemMessage(Component.literal(
                        "Лимит микровоксельных объёмов в чанке исчерпан."), true);
                closeSession(player, DraftSession.CancelReason.BLOCK_CHANGED, true);
                return;
            }
            volume = MicrovoxelVolume.full(MicrovoxelBlockStates.getBlockStateString(state));
            plan.setConverted(true);
        }
        if (volume == null) return;
        int removed = 0;
        java.util.Map<String, Integer> sliceMaterials = new java.util.HashMap<>();
        while (plan.applied() < target && plan.applied() < plan.cells().size()) {
            int cell = plan.cells().get(plan.applied());
            String material = volume.occupied(cell) ? volume.material(cell) : null;
            if (volume.remove(cell)) {
                removed++;
                if (material != null && !material.isEmpty()) {
                    sliceMaterials.merge(material, 1, Integer::sum);
                }
                // Deltas, not full upserts: bytes per carved cell instead of kilobytes
                // per slice. The final TRANSACTION converges late joiners and loss.
                microvoxels.syncHub().broadcastDelta(key, volume, cell, "");
                MicrovoxelMetrics.inc("carver.work.delta");
            }
            plan.setApplied(plan.applied() + 1);
        }
        for (java.util.Map.Entry<String, Integer> entry : sliceMaterials.entrySet()) {
            plan.addRemoved(entry.getKey(), entry.getValue());
        }
        if (removed == 0) return;
        microvoxels.collision().invalidate(key);
        if (volume.occupiedCount() == 0) {
            microvoxels.runtime().projection().dematerialize(key);
            microvoxels.syncHub().broadcastRemove(key);
        } else {
            microvoxels.runtime().projection().materialize(key, volume);
        }
        MicrovoxelMetrics.add("carver.work.cells", removed);
    }

    /**
     * Commits whatever the plan has carved so far: one undoable history entry, material
     * refund for actually removed cells, and the done event. Interrupted work keeps its
     * partial result instead of evaporating.
     */
    private void finalizeWork(ServerPlayer player, ServerLevel level,
                              BlockPos pos, DraftSession session, boolean full) {
        WorkPlan plan = plans.get(player.getUUID());
        dropSession(player.getUUID());
        if (plan == null) return;
        UUID worldId = microvoxels.runtimeWorldId(level);
        MicrovoxelKey key = new MicrovoxelKey(worldId, pos.getX(), pos.getY(), pos.getZ());
        MicrovoxelVolume volume = microvoxels.microvolumes().get(key);
        int removed = plan.removed();
        if (removed <= 0 || volume == null) {
            if (full) closeSession(player, DraftSession.CancelReason.EMPTY_DRAFT, true);
            return;
        }
        long transactionId = transactions.getAndIncrement();
        MicrovoxelVolume after = volume.copy();
        List<MicrovoxelProtocol.StateChange> changes =
                List.of(new MicrovoxelProtocol.StateChange(key, after));
        microvoxels.syncHub().broadcastTransaction(transactionId, changes);
        microvoxels.history().recordEdit(player, transactionId, key, null, after);
        // Refunds follow the actually removed cells per material (fragments land
        // in the inventory even in creative: carved matter must not evaporate).
        // The legacy single-material path survives only as a fallback.
        java.util.Map<String, Integer> byMaterial = plan.removedByMaterial();
        if (byMaterial.isEmpty()) {
            String material = after.palette().size() > 1
                    ? after.palette().get(1) : plan.materialId();
            microvoxels.economy().refundMaterialUnits(player, material, removed, true);
        } else {
            for (java.util.Map.Entry<String, Integer> entry : byMaterial.entrySet()) {
                microvoxels.economy().refundMaterialUnits(
                        player, entry.getKey(), entry.getValue(), true);
            }
        }
        ua.rp.chat.microvoxel.MicrovoxelEvents.fireEdit(player, key, null, after);
        MicrovoxelMetrics.inc("carver.work.done");
        CarverSoundKit kit = kits.get(player.getUUID());
        if (full && kit != null) {
            level.playSound(null, pos, kit.finish(), SoundSource.BLOCKS,
                    (float) tuning.workSoundVolume, 1.1f);
        }
        broadcastObserved(level, player, CarverProtocol.EVENT_WORK_OBSERVED_END, pos,
                CarverSyncPayload.observedEndData(player.getUUID()));
        sendEvent(player, CarverProtocol.EVENT_WORK_DONE, pos,
                CarverSyncPayload.doneData(removed));
        player.sendSystemMessage(Component.literal(full
                ? "Готово: снято " + removed + " вокселей."
                : "Работа прервана, но снятые " + removed + " вокселей сохранены."), true);
    }

    /** Interrupts work keeping the carved-so-far result; design drafts just dissolve. */
    private void cancelWork(ServerPlayer player, ServerLevel level,
                            DraftSession.CancelReason reason, String message) {
        DraftSession session = sessions.get(player.getUUID());
        if (session == null) return;
        BlockPos pos = new BlockPos(session.blockX(), session.blockY(), session.blockZ());
        if (session.state() == DraftSession.State.WORK) {
            finalizeWork(player, level, pos, session, false);
            level.playSound(null, pos, SoundEvents.STONE_BREAK, SoundSource.BLOCKS,
                    (float) (tuning.workSoundVolume * 0.5), 0.7f);
        } else {
            closeSession(player, reason, false);
        }
        if (message != null) player.sendSystemMessage(Component.literal(message), true);
        closeSession(player, reason, true);
    }

    private void closeSession(ServerPlayer player, DraftSession.CancelReason reason, boolean notify) {
        DraftSession session = sessions.get(player.getUUID());
        dropSession(player.getUUID());
        if (session == null) return;
        if (notify) {
            BlockPos pos = new BlockPos(session.blockX(), session.blockY(), session.blockZ());
            sendEvent(player, CarverProtocol.EVENT_SESSION_CLOSE, pos,
                    CarverSyncPayload.closeData(reason.ordinal()));
        }
    }

    private void sendDraft(ServerPlayer player, DraftSession session) {
        BlockPos pos = new BlockPos(session.blockX(), session.blockY(), session.blockZ());
        sendEvent(player, CarverProtocol.EVENT_DRAFT_STATE, pos, session.mask().encode());
    }

    private void sendEstimate(ServerPlayer player, DraftSession session) {
        BlockPos pos = new BlockPos(session.blockX(), session.blockY(), session.blockZ());
        List<Integer> cells = session.mask().cells();
        double multiplier = session.materialMultiplier();
        double fill = DraftEstimate.fillRatio(cells);
        int span = DraftEstimate.depthSpan(cells);
        int tool = CarverItems.chiselOf(player.getOffhandItem());
        double seconds = DraftEstimate.workSeconds(cells.size(), fill, span, multiplier, tool);
        int ticks = Math.max(1, (int) Math.round(seconds * DraftEstimate.TICKS_PER_SECOND));
        double cost = DraftEstimate.staminaCost(cells.size(), fill, span, multiplier, tool);
        sendEvent(player, CarverProtocol.EVENT_ESTIMATE, pos,
                CarverSyncPayload.estimateData(cells.size(), (float) seconds, (float) cost, ticks));
        player.sendSystemMessage(Component.literal("Будет снято: " + cells.size() + " вокселей | Время: ~"
                + Math.round(seconds) + " сек | Стамина: " + Math.round(cost) + "%"), true);
    }

    private void sendEvent(ServerPlayer player, int event, BlockPos pos, byte[] data) {
        ServerPlayNetworking.send(player, new CarverSyncPayload(
                CarverProtocol.VERSION, event, pos.getX(), pos.getY(), pos.getZ(), data));
    }

    /** Nearby artisans see who started or stopped work at which bench. */
    private void broadcastObserved(ServerLevel level, ServerPlayer worker,
                                   int event, BlockPos pos, byte[] data) {
        try {
            for (ServerPlayer observer : level.players()) {
                if (observer == null || observer.getUUID().equals(worker.getUUID())) continue;
                ServerPlayNetworking.send(observer, new CarverSyncPayload(
                        CarverProtocol.VERSION, event, pos.getX(), pos.getY(), pos.getZ(), data));
            }
        } catch (RuntimeException broadcastFailed) {
            MicrovoxelMetrics.inc("carver.observed.broadcast.failed");
        }
    }

    private boolean leashedOut(ServerPlayer player, double leashBlocks) {
        Vec3 anchor = anchors.get(player.getUUID());
        if (anchor == null) return false;
        return player.position().distanceToSqr(anchor) > leashBlocks * leashBlocks;
    }

    private boolean withinReach(ServerPlayer player, BlockPos pos) {
        double reach = tuning.reach;
        return player.getEyePosition().distanceToSqr(
                new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5)) <= reach * reach;
    }

    private ServerPlayer playerById(UUID playerId) {
        if (plugin.getServer() == null) return null;
        return plugin.getServer().getPlayerList().getPlayer(playerId);
    }

    private MicrovoxelKey keyFor(ServerLevel level, BlockPos pos) {
        return new MicrovoxelKey(microvoxels.runtimeWorldId(level),
                pos.getX(), pos.getY(), pos.getZ());
    }

    private static String blockId(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }

    /**
     * Dominant remaining material of a carved volume: delegates to the single
     * parentage resolver so drafting, mining and break feedback never disagree on
     * which material a volume reads as.
     */
    static String dominantMaterial(ua.rp.chat.microvoxel.MicrovoxelVolume volume) {
        return ua.rp.chat.microvoxel.MicrovoxelParentage.dominantMaterial(volume);
    }

    /** Parses eye+look floats (6 x float, 24 bytes) sent with design entry. */
    static float[] eyeLook(byte[] data) throws IOException {
        if (data == null || data.length != 24) throw new IOException("eye+look must be 24 bytes");
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(data))) {
            return new float[]{input.readFloat(), input.readFloat(), input.readFloat(),
                    input.readFloat(), input.readFloat(), input.readFloat()};
        }
    }
}
