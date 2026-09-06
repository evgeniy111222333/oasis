package ua.rp.chat.microvoxel.mining;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import ua.rp.chat.microvoxel.MicrovoxelBlockStates;
import ua.rp.chat.microvoxel.MicrovoxelContext;
import ua.rp.chat.microvoxel.MicrovoxelKey;
import ua.rp.chat.microvoxel.MicrovoxelProtocol;
import ua.rp.chat.microvoxel.MicrovoxelVolume;
import ua.rp.chat.microvoxel.ServerMicrovoxelRaycaster;
import ua.rp.chat.microvoxel.econ.MicrovoxelMaterialEconomy;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Authoritative per-cell mining. The server owns the destroy timer: each attack tick the
 * engine re-casts from the player's eye along the server look, picks the cell under the
 * crosshair, accrues vanilla destroy progress against the cell's own material state and
 * drives the per-cell crack via MINE_STAGE frames. Sessions expire on release, survive only
 * while the exact same target is held and are never part of the undo ledger.
 */
public final class MicrovoxelMiningEngine {
    public static final int MAX_TICKS_PER_ADVANCE = 3;
    public static final long SESSION_TIMEOUT_TICKS = 30L;
    public static final int MAX_RETARGET_PASSES = 2;
    public static final double MAX_REACH = 6.25;
    private static final long WRONG_TOOL_FEEDBACK_INTERVAL_MS = 2_000L;

    private final MicrovoxelContext context;
    private final MicrovoxelMaterialEconomy economy;
    private final float multiplier;
    private final boolean wrongToolBlocks;
    private final Map<UUID, MicrovoxelMiningSession> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, Long> wrongToolCooldown = new ConcurrentHashMap<>();

    public MicrovoxelMiningEngine(
            MicrovoxelContext context,
            MicrovoxelMaterialEconomy economy,
            float multiplier,
            boolean wrongToolBlocks) {
        this.context = context;
        this.economy = economy;
        this.multiplier = multiplier;
        this.wrongToolBlocks = wrongToolBlocks;
    }

    /** End-of-tick maintenance: expire sessions whose owner stopped attacking. */
    public void tick() {
        long now = context.runtime().serverTick();
        for (UUID playerId : List.copyOf(sessions.keySet())) {
            MicrovoxelMiningSession session = sessions.get(playerId);
            if (session != null && now - session.lastTick() >= SESSION_TIMEOUT_TICKS) {
                dropSession(playerId);
            }
        }
    }

    public void onQuit(UUID playerId) {
        sessions.remove(playerId);
        wrongToolCooldown.remove(playerId);
    }

    /**
     * Attack-tick hook. Server-authoritative: re-casts the crosshair and either starts,
     * continues or resets the session; the ideally finished cell is removed synchronously
     * and the loop re-targets the newly exposed surface in the same call.
     */
    /**
     * Attack-tick entry point. Mining requires the same edit permission as direct cell edits;
     * otherwise a player without build rights could still drain volumes through survival mining.
     */
    public void startMining(ServerPlayer player, BlockPos pos) {
        if (player == null || !context.runtime().storageReady()) return;
        if (!ua.rp.chat.RPChat.hasPermission(player, "rpchat.microvoxels.edit", 2)) {
            dropSession(player.getUUID());
            return;
        }
        for (int pass = 0; pass < MAX_RETARGET_PASSES; pass++) {
            if (!minePass(player, pos)) return;
        }
    }

    private boolean minePass(ServerPlayer player, BlockPos pos) {
        UUID playerId = player.getUUID();
        if (player.gameMode.getGameModeForPlayer() != GameType.SURVIVAL
                || player.isShiftKeyDown()) {
            dropSession(playerId);
            return false;
        }
        UUID worldId = context.runtime().worldId(player.level());
        MicrovoxelKey key = new MicrovoxelKey(worldId, pos.getX(), pos.getY(), pos.getZ());
        Vec3 look = player.getViewVector(1.0f);
        ServerMicrovoxelRaycaster.Hit hit = ServerMicrovoxelRaycaster.castIndexed(
                worldId,
                player.getEyePosition().x, player.getEyePosition().y, player.getEyePosition().z,
                (float) look.x, (float) look.y, (float) look.z,
                MAX_REACH,
                (x, y, z) -> context.runtime().store().get(new MicrovoxelKey(worldId, x, y, z)));
        if (hit == null || !hit.key().equals(key)) {
            dropSession(playerId);
            return false;
        }
        MicrovoxelVolume volume = context.runtime().store().get(key);
        if (volume == null || !volume.occupied(hit.cell())) {
            dropSession(playerId);
            return false;
        }
        int cell = hit.cell();
        String material = volume.material(cell);
        BlockState cellState = MicrovoxelBlockStates.parseBlockState(material);
        ServerLevel level = context.runtime().getWorld(worldId);
        if (level == null) {
            dropSession(playerId);
            return false;
        }
        float progressPerTick = cellState.getDestroyProgress(player, level, pos);
        if (progressPerTick <= 0.0f) {
            dropSession(playerId);
            return false;
        }
        boolean toolOk = player.hasCorrectToolForDrops(cellState);
        if (!toolOk && wrongToolBlocks) {
            dropSession(playerId);
            wrongToolFeedback(player);
            return false;
        }
        float requiredTicks = MicrovoxelMiningMath.requiredTicksFromProgressPerTick(
                progressPerTick, multiplier);
        long now = context.runtime().serverTick();
        MicrovoxelMiningSession current = sessions.get(playerId);
        if (current == null || !current.key().equals(key) || current.cell() != cell
                || current.revision() != volume.revision()
                || !current.material().equals(material)) {
            sessions.put(playerId, new MicrovoxelMiningSession(
                    playerId, worldId, key, cell, material, volume.revision(), requiredTicks,
                    0.0f, now, -1, toolOk, false));
            return false;
        }
        Advance advance = advance(current, now);
        if (advance.breakNow()) {
            breakCell(player, advance.session());
            sessions.remove(playerId);
            wrongToolCooldown.remove(playerId);
            return true;
        }
        MicrovoxelMiningSession next = advance.session();
        if (next.lastStage() != current.lastStage()) {
            sendMineStage(player, next.key(), next.cell(), next.lastStage());
        }
        if (next.progress() != current.progress() || next.lastStage() != current.lastStage()) {
            sessions.put(playerId, next);
        }
        return false;
    }

    /**
     * Pure tick continuation: accrues at most {@link #MAX_TICKS_PER_ADVANCE} ticks since the
     * last attack, recomputes the crack stage and reports completion.
     */
    static Advance advance(MicrovoxelMiningSession current, long nowTick) {
        long delta = Math.min(MAX_TICKS_PER_ADVANCE, Math.max(0L, nowTick - current.lastTick()));
        if (delta <= 0L) {
            return new Advance(current, false);
        }
        float progress = current.progress() + (float) delta;
        if (progress >= current.requiredTicks()) {
            return new Advance(current.withProgress(current.requiredTicks(), nowTick, 9), true);
        }
        int stage = MicrovoxelMiningMath.crackStage(progress, current.requiredTicks());
        return new Advance(current.withProgress(progress, nowTick, stage), false);
    }

    record Advance(MicrovoxelMiningSession session, boolean breakNow) {
    }

    private void breakCell(ServerPlayer player, MicrovoxelMiningSession session) {
        MicrovoxelKey key = session.key();
        MicrovoxelVolume volume = context.runtime().store().get(key);
        if (volume == null || volume.revision() != session.revision()
                || !volume.occupied(session.cell())
                || !volume.material(session.cell()).equals(session.material())) {
            return;
        }
        BlockState cellState = MicrovoxelBlockStates.parseBlockState(session.material());
        // Re-validate the tool at break time: the session caches speed for the crack Preview,
        // but the refund and drop must reflect the tool actually held on the final tick,
        // otherwise starting with Efficiency V and swapping to an empty hand keeps full speed.
        boolean toolOkNow = player.hasCorrectToolForDrops(cellState);
        if (toolOkNow && !session.material().isBlank()) {
            economy.refundMaterialUnit(player, session.material());
        }
        ServerLevel level = context.runtime().getWorld(session.worldId());
        if (level != null) {
            level.levelEvent(2001, keyBlockPos(key), Block.getId(cellState));
        }
        MicrovoxelVolume beforeMine = volume.copy();
        volume.remove(session.cell());
        ua.rp.chat.microvoxel.MicrovoxelMetrics.inc("mine.breaks");
        ua.rp.chat.microvoxel.MicrovoxelEvents.fireEdit(player, key, beforeMine, volume);
        if (volume.occupiedCount() == 0) {
            context.collision().invalidate(key);
            context.runtime().projection().dematerialize(key);
            context.sync().broadcastRemove(key);
        } else {
            context.collision().invalidate(key);
            context.runtime().projection().materialize(key, volume);
            context.sync().broadcastDelta(key, volume, session.cell(), "");
        }
    }

    private static BlockPos keyBlockPos(MicrovoxelKey key) {
        return new BlockPos(key.x(), key.y(), key.z());
    }

    private void sendMineStage(ServerPlayer player, MicrovoxelKey key, int cell, int stage) {
        ua.rp.chat.microvoxel.MicrovoxelMetrics.inc("mine.stages");
        context.sync().sendPacket(player, MicrovoxelProtocol.mineStage(key, cell, stage));
    }

    private void dropSession(UUID playerId) {
        MicrovoxelMiningSession session = sessions.remove(playerId);
        if (session != null && session.lastStage() >= 0) {
            ServerPlayer player = context.runtime().server().getPlayerList().getPlayer(playerId);
            if (player != null && player.connection != null) {
                sendMineStage(player, session.key(), session.cell(), -1);
            }
        }
        wrongToolCooldown.remove(playerId);
    }

    private void wrongToolFeedback(ServerPlayer player) {
        long now = System.currentTimeMillis();
        Long previous = wrongToolCooldown.get(player.getUUID());
        if (previous != null && now - previous < WRONG_TOOL_FEEDBACK_INTERVAL_MS) return;
        wrongToolCooldown.put(player.getUUID(), now);
        context.sync().feedback(player,
                "Для этой микро-материи нужен подходящий инструмент (без рефанда).");
    }

    public MicrovoxelMiningSession sessionOf(UUID playerId) {
        return sessions.get(playerId);
    }

    public int activeSessionCount() {
        return sessions.size();
    }
}