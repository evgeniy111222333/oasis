package ua.rp.chat.microvoxel.edit;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import ua.rp.chat.RPChat;
import ua.rp.chat.microvoxel.MicrovoxelBlockStates;
import ua.rp.chat.microvoxel.MicrovoxelBrush;
import ua.rp.chat.microvoxel.MicrovoxelContext;
import ua.rp.chat.microvoxel.MicrovoxelKey;
import ua.rp.chat.microvoxel.MicrovoxelManager;
import ua.rp.chat.microvoxel.MicrovoxelProtocol;
import ua.rp.chat.microvoxel.MicrovoxelRuntime;
import ua.rp.chat.microvoxel.MicrovoxelVolume;
import ua.rp.chat.microvoxel.ServerMicrovoxelRaycaster;
import ua.rp.chat.microvoxel.ChunkKey;
import ua.rp.chat.microvoxel.econ.MicrovoxelMaterialEconomy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Authoritative editing authority: applies every client action (convert, add/remove cell, brush,
 * clipboard, carve) after double raycast validation and revision checks, applies material
 * economy side effects, keeps the projection and collision cache convergent, and delegates
 * history recording to {@link MicrovoxelEditHistory}. All paths run on the server thread.
 */
public final class MicrovoxelEditEngine {
    private static final double MAX_REACH = 6.25;
    private static final double CLIENT_LOOK_MAX_DIVERGENCE_DEGREES = 4.0;
    private static final double CLIENT_LOOK_MIN_DOT =
            Math.cos(Math.toRadians(CLIENT_LOOK_MAX_DIVERGENCE_DEGREES));
    private static final double CLIENT_EYE_MAX_DELTA = 0.75;

    private final MicrovoxelContext context;
    private final MicrovoxelMaterialEconomy economy;
    private final MicrovoxelEditHistory history;
    private final Map<UUID, Long> lastEditTransactions = new ConcurrentHashMap<>();
    private final Map<UUID, ClipboardVolume> clipboards = new HashMap<>();

    public MicrovoxelEditEngine(
            MicrovoxelContext context,
            MicrovoxelMaterialEconomy economy,
            MicrovoxelEditHistory history) {
        this.context = context;
        this.economy = economy;
        this.history = history;
    }

    public void applyAction(ServerPlayer player, QueuedAction action) {
        if (player.connection == null || !context.runtime().storageReady()
                || !RPChat.hasPermission(player, "rpchat.microvoxels.edit", 2)) return;
        if (!withinReach(player, action.key())) {
            context.sync().trace(player, "ACTION_REJECT out-of-reach");
            context.sync().feedback(player, "Микровоксель находится слишком далеко.");
            return;
        }
        if (action.type() == MicrovoxelProtocol.ACTION_BRUSH_REMOVE
                || action.type() == MicrovoxelProtocol.ACTION_BRUSH_ADD) {
            applyBrush(player, action);
            return;
        }
        if (action.type() == MicrovoxelProtocol.ACTION_COPY) {
            copyToClipboard(player, action);
            return;
        }
        if (action.type() == MicrovoxelProtocol.ACTION_PASTE) {
            pasteClipboard(player, action);
            return;
        }
        MicrovoxelVolume before = MicrovoxelEditHistory.copyOrNull(
                context.runtime().store().get(action.key()));
        switch (action.type()) {
            case MicrovoxelProtocol.ACTION_CONVERT -> convert(player, action.key());
            case MicrovoxelProtocol.ACTION_REMOVE -> removeCell(player, action.transactionId(), action.key(),
                    action.cell(), action.expectedRevision(), action.clientLook(), action.clientEye());
            case MicrovoxelProtocol.ACTION_ADD -> addCell(player, action.key(), action.cell(), action.expectedRevision(),
                    action.clientLook(), action.clientEye());
            case MicrovoxelProtocol.ACTION_CARVE_STANDARD -> carveStandardBlock(player, action.transactionId(),
                    action.key(), action.cell(), action.clientLook(), action.clientEye());
            default -> context.sync().trace(player, "ACTION_REJECT unknown-action=" + action.type());
        }
        MicrovoxelVolume after = MicrovoxelEditHistory.copyOrNull(
                context.runtime().store().get(action.key()));
        if (!MicrovoxelEditHistory.sameVolume(before, after)) {
            history.recordEdit(player, action.transactionId(), action.key(), before, after);
        }
    }

    public void onQuit(UUID playerId) {
        lastEditTransactions.remove(playerId);
        clipboards.remove(playerId);
    }

    public ServerMicrovoxelRaycaster.Hit raycastMicrovoxel(ServerPlayer player) {
        Vec3 eye = player.getEyePosition();
        Vec3 direction = player.getViewVector(1.0f).normalize();
        return raycastMicrovoxel(player, eye, direction);
    }

    public ServerMicrovoxelRaycaster.Hit raycastMicrovoxel(ServerPlayer player, Vec3 eye, Vec3 direction) {
        UUID worldId = context.runtime().worldId(player.level());
        ServerMicrovoxelRaycaster.Hit hit = ServerMicrovoxelRaycaster.castIndexed(
                worldId, eye.x, eye.y, eye.z, direction.x, direction.y, direction.z, MAX_REACH,
                (x, y, z) -> context.runtime().store().get(new MicrovoxelKey(worldId, x, y, z)));
        if (hit == null) return null;
        BlockHitResult obstruction = ((ServerLevel) player.level()).clip(new net.minecraft.world.level.ClipContext(
                eye, eye.add(direction.scale(Math.max(0.0, hit.distance() - 0.001))),
                net.minecraft.world.level.ClipContext.Block.COLLIDER,
                net.minecraft.world.level.ClipContext.Fluid.NONE, player));
        return obstruction.getType() == net.minecraft.world.phys.HitResult.Type.MISS ? hit : null;
    }

    public static boolean validClientLook(Vec3 look) {
        return Double.isFinite(look.x) && Double.isFinite(look.y) && Double.isFinite(look.z)
                && look.lengthSqr() > 0.98 && look.lengthSqr() < 1.02;
    }

    public static boolean validClientEye(Vec3 eye) {
        return Double.isFinite(eye.x) && Double.isFinite(eye.y) && Double.isFinite(eye.z);
    }

    private void applyBrush(ServerPlayer player, QueuedAction action) {
        int originCell = MicrovoxelBrush.cell(action.cell());
        int shape = MicrovoxelBrush.shape(action.cell());
        int radius = MicrovoxelBrush.radius(action.cell());
        boolean adding = action.type() == MicrovoxelProtocol.ACTION_BRUSH_ADD;
        MicrovoxelVolume originVolume = context.runtime().store().get(action.key());
        if (adding && originVolume == null) {
            if (action.expectedRevision() != 0) {
                context.sync().sendRemove(player, action.key());
                return;
            }
        } else if (!validRevision(player, action.key(), originVolume, action.expectedRevision())) {
            return;
        }

        ServerMicrovoxelRaycaster.Hit hit = validatedHit(player, action.key(), originCell,
                action.clientLook(), action.clientEye(), !adding);
        if (hit == null) {
            if (originVolume == null) context.sync().sendRemove(player, action.key());
            else context.sync().sendUpsert(player, action.key(), originVolume);
            context.sync().feedback(player, "Цель кисти изменилась. Наведитесь на поверхность ещё раз.");
            return;
        }
        if (adding) {
            ServerMicrovoxelRaycaster.AdjacentTarget adjacent = hit.adjacentTarget();
            if (!adjacent.key().equals(action.key()) || adjacent.cell() != originCell) {
                if (originVolume == null) context.sync().sendRemove(player, action.key());
                else context.sync().sendUpsert(player, action.key(), originVolume);
                context.sync().feedback(player, "Цель кисти изменилась. Наведитесь на поверхность ещё раз.");
                return;
            }
        } else if (!hit.key().equals(action.key()) || hit.cell() != originCell) {
            context.sync().sendUpsert(player, action.key(), originVolume);
            context.sync().feedback(player, "Цель кисти изменилась. Наведитесь на поверхность ещё раз.");
            return;
        }

        MicrovoxelBrush.Axis axis = switch (hit.face()) {
            case EAST, WEST -> MicrovoxelBrush.Axis.X;
            case UP, DOWN -> MicrovoxelBrush.Axis.Y;
            case NORTH, SOUTH -> MicrovoxelBrush.Axis.Z;
        };
        List<MicrovoxelBrush.Target> targets = MicrovoxelBrush.targets(
                action.key().x(), action.key().y(), action.key().z(), originCell, shape, radius, axis);
        if (targets.size() > 729) {
            context.sync().feedback(player, "Кисть превышает безопасный лимит одной транзакции.");
            return;
        }

        LinkedHashMap<MicrovoxelKey, MicrovoxelVolume> before = new LinkedHashMap<>();
        LinkedHashMap<MicrovoxelKey, MicrovoxelVolume> working = new LinkedHashMap<>();
        LinkedHashMap<String, Integer> removedMaterials = new LinkedHashMap<>();
        ServerLevel level = (ServerLevel) player.level();
        MicrovoxelMaterialEconomy.SelectedMaterial selected = adding ? economy.selectedMaterial(player) : null;
        String material = selected == null ? null : MicrovoxelBlockStates.getBlockStateString(selected.state());
        if (adding && selected == null) {
            context.sync().feedback(player, "Возьмите в основную или вторую руку полноразмерный блок.");
            return;
        }

        for (MicrovoxelBrush.Target target : targets) {
            if (target.blockY() < level.getMinY() || target.blockY() >= level.getMaxY()) continue;
            MicrovoxelKey key = new MicrovoxelKey(action.key().worldId(),
                    target.blockX(), target.blockY(), target.blockZ());
            MicrovoxelVolume authoritative = context.runtime().store().get(key);
            MicrovoxelVolume volume = working.get(key);
            if (volume == null) {
                before.put(key, MicrovoxelEditHistory.copyOrNull(authoritative));
                if (authoritative == null) {
                    if (!adding) continue;
                    BlockPos pos = new BlockPos(key.x(), key.y(), key.z());
                    if (!level.getBlockState(pos).isAir()) continue;
                    volume = MicrovoxelVolume.empty();
                } else {
                    volume = authoritative.copy();
                }
                working.put(key, volume);
            }
            if (adding) {
                if (volume.occupied(target.cell())) continue;
                if (!volume.palette().contains(material)
                        && volume.palette().size() >= MicrovoxelVolume.MAX_PALETTE) {
                    volume.compactPalette();
                }
                if (!volume.palette().contains(material)
                        && volume.palette().size() >= MicrovoxelVolume.MAX_PALETTE) {
                    context.sync().feedback(player,
                            "Один из объёмов достиг лимита материалов; транзакция отменена.");
                    return;
                }
                volume.put(target.cell(), material);
            } else {
                if (!volume.occupied(target.cell())) continue;
                removedMaterials.merge(volume.material(target.cell()), 1, Integer::sum);
                volume.remove(target.cell());
            }
        }

        working.entrySet().removeIf(entry ->
                MicrovoxelEditHistory.sameVolume(before.get(entry.getKey()), entry.getValue()));
        before.keySet().retainAll(working.keySet());
        if (working.isEmpty()) {
            context.sync().feedback(player,
                    adding ? "Кисти нечего добавить." : "Кисти нечего удалить.");
            return;
        }
        int modifiedCellCount;
        if (adding) {
            int additions = working.values().stream().mapToInt(MicrovoxelVolume::occupiedCount).sum()
                    - before.values().stream().filter(java.util.Objects::nonNull)
                    .mapToInt(MicrovoxelVolume::occupiedCount).sum();
            modifiedCellCount = additions;
            if (economy.availableMaterialUnits(player, selected) < additions) {
                context.sync().feedback(player,
                        "Недостаточно материала: нужно " + additions + " микровокселей.");
                return;
            }
            Map<ChunkKey, Integer> newPerChunk = new HashMap<>();
            for (MicrovoxelKey key : working.keySet()) {
                if (before.get(key) == null) newPerChunk.merge(ChunkKey.of(key), 1, Integer::sum);
            }
            for (Map.Entry<ChunkKey, Integer> entry : newPerChunk.entrySet()) {
                ChunkKey chunk = entry.getKey();
                if (context.runtime().store().countInChunk(chunk.worldId(), chunk.x(), chunk.z())
                        + entry.getValue() > MicrovoxelRuntime.MAX_PER_CHUNK) {
                    context.sync().feedback(player, "Кисть превысит лимит микровоксельных объёмов в чанке.");
                    return;
                }
            }
            economy.consumeMaterialUnits(player, selected, additions);
        } else {
            modifiedCellCount = removedMaterials.values().stream().mapToInt(Integer::intValue).sum();
        }

        List<MicrovoxelEditHistory.EditChange> historyChanges = new ArrayList<>(working.size());
        List<MicrovoxelProtocol.StateChange> networkChanges = new ArrayList<>(working.size());
        for (Map.Entry<MicrovoxelKey, MicrovoxelVolume> entry : working.entrySet()) {
            MicrovoxelKey key = entry.getKey();
            MicrovoxelVolume volume = entry.getValue();
            MicrovoxelVolume after;
            context.collision().invalidate(key);
            if (volume.occupiedCount() == 0) {
                context.runtime().projection().dematerialize(key);
                after = null;
            } else {
                context.runtime().projection().materialize(key, volume);
                after = volume.copy();
            }
            historyChanges.add(new MicrovoxelEditHistory.EditChange(key, before.get(key), after));
            networkChanges.add(new MicrovoxelProtocol.StateChange(key, after));
        }
        for (Map.Entry<String, Integer> refund : removedMaterials.entrySet()) {
            economy.refundMaterialUnits(player, refund.getKey(), refund.getValue());
        }
        context.sync().broadcastTransaction(action.transactionId(), networkChanges);
        history.recordEdit(player, action.transactionId(), historyChanges);
        ua.rp.chat.microvoxel.MicrovoxelMetrics.inc("edits.applied.brush");
        ua.rp.chat.microvoxel.MicrovoxelMetrics.add("edits.brush.cells", modifiedCellCount);
        for (MicrovoxelEditHistory.EditChange change : historyChanges) {
            ua.rp.chat.microvoxel.MicrovoxelEvents.fireEdit(player, change.key(),
                    change.before(), change.after());
        }
        context.sync().feedback(player, (adding ? "Добавлено " : "Удалено ")
                + modifiedCellCount + " ячеек кистью; транзакция атомарна.");
    }

    private void copyToClipboard(ServerPlayer player, QueuedAction action) {
        MicrovoxelVolume volume = context.runtime().store().get(action.key());
        if (!validRevision(player, action.key(), volume, action.expectedRevision())) return;
        ServerMicrovoxelRaycaster.Hit hit = validatedHit(player, action.key(), action.cell(),
                action.clientLook(), action.clientEye(), true);
        if (hit == null || !hit.key().equals(action.key()) || hit.cell() != action.cell()) {
            context.sync().sendUpsert(player, action.key(), volume);
            context.sync().feedback(player, "Цель копирования изменилась.");
            return;
        }
        clipboards.put(player.getUUID(), new ClipboardVolume(volume.copy(), action.cell()));
        context.sync().feedback(player, "Объём скопирован: " + volume.occupiedCount()
                + " ячеек, точка привязки сохранена.");
    }

    private void pasteClipboard(ServerPlayer player, QueuedAction action) {
        if (player.gameMode.getGameModeForPlayer() != GameType.CREATIVE) {
            context.sync().feedback(player,
                    "Вставка доступна в creative: это исключает создание материала из ничего.");
            return;
        }
        ClipboardVolume clipboard = clipboards.get(player.getUUID());
        if (clipboard == null) {
            context.sync().feedback(player, "Буфер микровокселей пуст. Сначала скопируйте объём.");
            return;
        }
        int targetCell = action.cell() & 0x0FFF;
        int rotation = (action.cell() >>> 12) & 3;
        boolean mirrorX = ((action.cell() >>> 14) & 1) != 0;
        MicrovoxelVolume targetVolume = context.runtime().store().get(action.key());
        if (targetVolume == null) {
            if (action.expectedRevision() != 0) {
                context.sync().sendRemove(player, action.key());
                return;
            }
        } else if (!validRevision(player, action.key(), targetVolume, action.expectedRevision())) {
            return;
        }
        ServerMicrovoxelRaycaster.Hit hit = validatedHit(player, action.key(), targetCell,
                action.clientLook(), action.clientEye(), false);
        if (hit == null) {
            if (targetVolume == null) context.sync().sendRemove(player, action.key());
            else context.sync().sendUpsert(player, action.key(), targetVolume);
            context.sync().feedback(player, "Цель вставки изменилась.");
            return;
        }
        ServerMicrovoxelRaycaster.AdjacentTarget adjacent = hit.adjacentTarget();
        if (!adjacent.key().equals(action.key()) || adjacent.cell() != targetCell) {
            context.sync().feedback(player, "Цель вставки изменилась.");
            return;
        }

        MicrovoxelVolume source = clipboard.volume();
        int anchorX = MicrovoxelVolume.x(clipboard.anchorCell());
        int anchorY = MicrovoxelVolume.y(clipboard.anchorCell());
        int anchorZ = MicrovoxelVolume.z(clipboard.anchorCell());
        int targetGlobalX = action.key().x() * 16 + MicrovoxelVolume.x(targetCell);
        int targetGlobalY = action.key().y() * 16 + MicrovoxelVolume.y(targetCell);
        int targetGlobalZ = action.key().z() * 16 + MicrovoxelVolume.z(targetCell);
        ServerLevel level = (ServerLevel) player.level();
        LinkedHashMap<MicrovoxelKey, MicrovoxelVolume> before = new LinkedHashMap<>();
        LinkedHashMap<MicrovoxelKey, MicrovoxelVolume> working = new LinkedHashMap<>();

        for (int sourceCell = 0; sourceCell < MicrovoxelVolume.CELL_COUNT; sourceCell++) {
            if (!source.occupied(sourceCell)) continue;
            int dx = MicrovoxelVolume.x(sourceCell) - anchorX;
            int dy = MicrovoxelVolume.y(sourceCell) - anchorY;
            int dz = MicrovoxelVolume.z(sourceCell) - anchorZ;
            if (mirrorX) dx = -dx;
            int rotatedX = switch (rotation) {
                case 1 -> -dz;
                case 2 -> -dx;
                case 3 -> dz;
                default -> dx;
            };
            int rotatedZ = switch (rotation) {
                case 1 -> dx;
                case 2 -> -dz;
                case 3 -> -dx;
                default -> dz;
            };
            int globalX = targetGlobalX + rotatedX;
            int globalY = targetGlobalY + dy;
            int globalZ = targetGlobalZ + rotatedZ;
            int blockY = Math.floorDiv(globalY, 16);
            if (blockY < level.getMinY() || blockY >= level.getMaxY()) {
                context.sync().feedback(player, "Вставка выходит за вертикальную границу мира.");
                return;
            }
            MicrovoxelKey key = new MicrovoxelKey(action.key().worldId(),
                    Math.floorDiv(globalX, 16), blockY, Math.floorDiv(globalZ, 16));
            int cell = MicrovoxelVolume.index(
                    Math.floorMod(globalX, 16), Math.floorMod(globalY, 16), Math.floorMod(globalZ, 16));
            MicrovoxelVolume volume = working.get(key);
            if (volume == null) {
                MicrovoxelVolume authoritative = context.runtime().store().get(key);
                before.put(key, MicrovoxelEditHistory.copyOrNull(authoritative));
                if (authoritative == null) {
                    BlockPos pos = new BlockPos(key.x(), key.y(), key.z());
                    if (!level.getBlockState(pos).isAir()) {
                        context.sync().feedback(player,
                                "Вставка пересекает обычный блок; транзакция отменена.");
                        return;
                    }
                    volume = MicrovoxelVolume.empty();
                } else {
                    volume = authoritative.copy();
                }
                working.put(key, volume);
            }
            if (volume.occupied(cell)) {
                context.sync().feedback(player,
                        "Вставка пересекает существующий микровоксель; транзакция отменена.");
                return;
            }
            String material = source.material(sourceCell);
            if (!volume.palette().contains(material)
                    && volume.palette().size() >= MicrovoxelVolume.MAX_PALETTE) {
                volume.compactPalette();
            }
            if (!volume.palette().contains(material)
                    && volume.palette().size() >= MicrovoxelVolume.MAX_PALETTE) {
                context.sync().feedback(player, "Вставка превысит палитру одного из объёмов.");
                return;
            }
            volume.put(cell, material);
        }
        Map<ChunkKey, Integer> newPerChunk = new HashMap<>();
        for (MicrovoxelKey key : working.keySet()) {
            if (before.get(key) == null) newPerChunk.merge(ChunkKey.of(key), 1, Integer::sum);
        }
        for (Map.Entry<ChunkKey, Integer> entry : newPerChunk.entrySet()) {
            ChunkKey chunk = entry.getKey();
            if (context.runtime().store().countInChunk(chunk.worldId(), chunk.x(), chunk.z())
                    + entry.getValue() > MicrovoxelRuntime.MAX_PER_CHUNK) {
                context.sync().feedback(player, "Вставка превысит лимит объёмов в чанке.");
                return;
            }
        }

        List<MicrovoxelEditHistory.EditChange> historyChanges = new ArrayList<>(working.size());
        List<MicrovoxelProtocol.StateChange> networkChanges = new ArrayList<>(working.size());
        for (Map.Entry<MicrovoxelKey, MicrovoxelVolume> entry : working.entrySet()) {
            MicrovoxelKey key = entry.getKey();
            MicrovoxelVolume after = entry.getValue();
            context.collision().invalidate(key);
            context.runtime().projection().materialize(key, after);
            historyChanges.add(new MicrovoxelEditHistory.EditChange(key, before.get(key), after.copy()));
            networkChanges.add(new MicrovoxelProtocol.StateChange(key, after));
        }
        context.sync().broadcastTransaction(action.transactionId(), networkChanges);
        history.recordEdit(player, action.transactionId(), historyChanges);
        context.sync().feedback(player, "Вставлено " + source.occupiedCount()
                + " ячеек; поворот " + (rotation * 90) + "°, отражение "
                + (mirrorX ? "включено" : "выключено") + ".");
    }

    private void carveStandardBlock(ServerPlayer player, long transactionId, MicrovoxelKey key,
                                    int cell, Vec3 clientLook, Vec3 clientEye) {
        MicrovoxelVolume existing = context.runtime().store().get(key);
        if (existing != null) {
            removeCell(player, transactionId, key, cell, existing.revision(), clientLook, clientEye);
            return;
        }
        Vec3 clientLocation = boundedClientEye(player, clientEye);
        if (clientLocation == null) return;
        BlockHitResult trace = ((ServerLevel) player.level()).clip(new net.minecraft.world.level.ClipContext(
                clientLocation, clientLocation.add(clientLook.scale(MAX_REACH)),
                net.minecraft.world.level.ClipContext.Block.COLLIDER,
                net.minecraft.world.level.ClipContext.Fluid.NONE, player));
        if (trace.getType() == net.minecraft.world.phys.HitResult.Type.MISS) {
            context.sync().trace(player, "ACTION_REJECT carve-standard-target-mismatch");
            context.sync().feedback(player, "Нужно навестись на обычный полный блок ещё раз.");
            return;
        }
        BlockPos pos = trace.getBlockPos();
        if (pos.getX() != key.x() || pos.getY() != key.y() || pos.getZ() != key.z()
                || !MicrovoxelEligibility.isEligibleFullBlock(
                ((ServerLevel) player.level()).getBlockState(pos), pos, ((ServerLevel) player.level()))) {
            context.sync().trace(player, "ACTION_REJECT carve-standard-target-mismatch");
            context.sync().feedback(player, "Нужно навестись на обычный полный блок ещё раз.");
            return;
        }
        int authoritativeCell = cellAtStandardHit(key, trace);
        if (authoritativeCell != cell) {
            context.sync().trace(player, "ACTION_REBASE carve-standard-cell expected=" + cell
                    + " actual=" + authoritativeCell);
            context.sync().feedback(player, "Цель изменилась. Наведитесь на ячейку ещё раз.");
            return;
        }
        if (context.runtime().store().countInChunk(key.worldId(), key.chunkX(), key.chunkZ())
                >= MicrovoxelRuntime.MAX_PER_CHUNK) {
            context.sync().feedback(player, "В этом чанке достигнут безопасный лимит микровоксельных блоков.");
            return;
        }
        BlockState blockState = ((ServerLevel) player.level()).getBlockState(pos);
        String blockDataStr = MicrovoxelBlockStates.getBlockStateString(blockState);
        MicrovoxelVolume volume = MicrovoxelVolume.full(blockDataStr);
        volume.remove(cell);
        economy.refundMaterialUnit(player, blockDataStr);
        context.runtime().projection().materialize(key, volume);
        context.sync().broadcastUpsert(key, volume);
        context.sync().trace(player, "ACTION_APPLIED carve-standard cell=" + cell
                + " revision=" + volume.revision());
    }

    private void convert(ServerPlayer player, MicrovoxelKey key) {
        if (context.runtime().store().get(key) != null) {
            context.sync().sendUpsert(player, key, context.runtime().store().get(key));
            return;
        }
        BlockHitResult trace = rayTraceBlocks(player, MAX_REACH);
        if (trace.getType() == net.minecraft.world.phys.HitResult.Type.MISS) {
            context.sync().feedback(player, "Блок не выбран.");
            return;
        }
        BlockPos pos = trace.getBlockPos();
        if (pos.getX() != key.x() || pos.getY() != key.y() || pos.getZ() != key.z()) {
            context.sync().feedback(player, "Нужно смотреть прямо на преобразуемый блок.");
            return;
        }
        BlockState blockState = ((ServerLevel) player.level()).getBlockState(pos);
        if (!MicrovoxelEligibility.isEligibleFullBlock(
                blockState, pos, ((ServerLevel) player.level()))) {
            context.sync().feedback(player,
                    "Можно преобразовать только обычный полноразмерный блок без содержимого.");
            return;
        }
        if (context.runtime().store().countInChunk(key.worldId(), key.chunkX(), key.chunkZ())
                >= MicrovoxelRuntime.MAX_PER_CHUNK) {
            context.sync().feedback(player, "В этом чанке достигнут безопасный лимит микровоксельных блоков.");
            return;
        }
        String blockDataStr = MicrovoxelBlockStates.getBlockStateString(blockState);
        MicrovoxelVolume volume = MicrovoxelVolume.full(blockDataStr);
        context.runtime().projection().materialize(key, volume);
        context.sync().broadcastUpsert(key, volume);
        context.sync().feedback(player,
                "Блок преобразован в сетку 16×16×16. ЛКМ убирает, ПКМ добавляет микровоксель.");
    }

    private void removeCell(ServerPlayer player, long transactionId, MicrovoxelKey key, int cell,
                            int expectedRevision, Vec3 clientLook, Vec3 clientEye) {
        MicrovoxelVolume volume = context.runtime().store().get(key);
        long previousTransaction = lastEditTransactions.getOrDefault(player.getUUID(), Long.MIN_VALUE);
        if (transactionId <= previousTransaction) {
            context.sync().sendEditResult(player, transactionId, false, key, volume);
            return;
        }
        lastEditTransactions.put(player.getUUID(), transactionId);
        ServerMicrovoxelRaycaster.Hit hit = validatedHit(
                player, key, cell, clientLook, clientEye, true);
        if (volume == null || !volume.occupied(cell)) {
            context.sync().trace(player, "ACTION_REJECT remove-cell-not-occupied");
            context.sync().sendEditResult(player, transactionId, false, key, volume);
            context.sync().feedback(player, "Эта ячейка уже изменена. Сетка синхронизирована.");
            return;
        }
        if (hit == null || !hit.key().equals(key) || hit.cell() != cell) {
            context.sync().trace(player, "ACTION_REJECT remove-raycast-mismatch");
            context.sync().sendEditResult(player, transactionId, false, key, volume);
            context.sync().feedback(player, "Цель изменилась. Наведитесь на ячейку ещё раз.");
            return;
        }
        // Stale revisions are rejected (not rebased): applying a remove on top of a newer
        // volume would silently discard another player's edit made in between.
        if (!validRevision(player, key, volume, expectedRevision)) {
            context.sync().sendEditResult(player, transactionId, false, key, volume);
            return;
        }
        String removedMaterial = volume.material(cell);
        MicrovoxelVolume beforeRemove = volume.copy();
        volume.remove(cell);
        economy.refundMaterialUnit(player, removedMaterial);
        ua.rp.chat.microvoxel.MicrovoxelMetrics.inc("edits.applied");
        ua.rp.chat.microvoxel.MicrovoxelEvents.fireEdit(player, key, beforeRemove, volume);
        if (volume.occupiedCount() == 0) {
            context.collision().invalidate(key);
            context.runtime().projection().dematerialize(key);
            context.sync().broadcastRemoveExcept(key, player);
            context.sync().sendEditResult(player, transactionId, true, key, null);
        } else {
            context.runtime().projection().materialize(key, volume);
            context.sync().broadcastDeltaExcept(key, volume, cell, "", player);
            context.sync().sendEditResult(player, transactionId, true, key, volume);
        }
        context.sync().trace(player, "ACTION_APPLIED remove cell=" + cell + " revision=" + volume.revision());
    }

    private void addCell(ServerPlayer player, MicrovoxelKey key, int cell, int expectedRevision,
                         Vec3 clientLook, Vec3 clientEye) {
        MicrovoxelVolume volume = context.runtime().store().get(key);
        ServerMicrovoxelRaycaster.Hit hit = validatedHit(player, key, cell, clientLook, clientEye, false);
        if (hit == null) {
            if (volume == null) context.sync().sendRemove(player, key);
            else context.sync().sendUpsert(player, key, volume);
            context.sync().feedback(player, "Цель изменилась. Наведитесь на грань ячейки ещё раз.");
            return;
        }

        boolean creatingVolume = volume == null;
        if (creatingVolume) {
            if (expectedRevision != 0) {
                context.sync().sendRemove(player, key);
                context.sync().feedback(player, "Целевой микровоксельный блок изменился. Повторите действие.");
                return;
            }
            ServerLevel level = (ServerLevel) player.level();
            BlockPos targetPos = new BlockPos(key.x(), key.y(), key.z());
            if (!level.getBlockState(targetPos).isAir()) {
                context.sync().feedback(player, "Продолжить форму можно только в свободное пространство.");
                return;
            }
            if (context.runtime().store().countInChunk(key.worldId(), key.chunkX(), key.chunkZ())
                    >= MicrovoxelRuntime.MAX_PER_CHUNK) {
                context.sync().feedback(player, "В этом чанке достигнут безопасный лимит микровоксельных блоков.");
                return;
            }
            volume = MicrovoxelVolume.empty();
        } else if (!validRevision(player, key, volume, expectedRevision)) {
            return;
        }

        if (volume.occupied(cell)) {
            context.sync().trace(player, "ACTION_REJECT add-cell-occupied");
            context.sync().sendUpsert(player, key, volume);
            context.sync().feedback(player, "Эта ячейка уже занята. Сетка синхронизирована.");
            return;
        }
        MicrovoxelMaterialEconomy.SelectedMaterial selected = economy.selectedMaterial(player);
        if (selected == null) {
            context.sync().feedback(player, "Возьмите в основную или вторую руку полноразмерный блок.");
            return;
        }
        BlockState material = selected.state();
        String matStr = MicrovoxelBlockStates.getBlockStateString(material);
        MicrovoxelVolume updated = volume.copy();
        boolean paletteCompacted = false;
        if (!updated.palette().contains(matStr)
                && updated.palette().size() >= MicrovoxelVolume.MAX_PALETTE) {
            paletteCompacted = updated.compactPalette();
        }
        try {
            updated.put(cell, matStr);
        } catch (IllegalStateException error) {
            context.sync().sendUpsert(player, key, volume);
            context.sync().feedback(player, "В этом микровоксельном блоке достигнут лимит материалов.");
            return;
        }
        MicrovoxelVolume beforeAdd = creatingVolume ? null : volume.copy();
        context.runtime().projection().materialize(key, updated);
        economy.consumeMaterialUnit(player, selected);
        if (creatingVolume || paletteCompacted) {
            context.sync().broadcastUpsert(key, updated);
        } else {
            context.sync().broadcastDelta(key, updated, cell, matStr);
        }
        ua.rp.chat.microvoxel.MicrovoxelMetrics.inc("edits.applied");
        ua.rp.chat.microvoxel.MicrovoxelEvents.fireEdit(player, key, beforeAdd, updated);
        context.sync().trace(player, "ACTION_APPLIED add cell=" + cell + " revision=" + updated.revision());
    }

    private ServerMicrovoxelRaycaster.Hit validatedHit(ServerPlayer player, MicrovoxelKey key, int cell,
                                                       Vec3 clientLook, Vec3 clientEye,
                                                       boolean requireRequestedCell) {
        ServerMicrovoxelRaycaster.Hit serverHit = raycastMicrovoxel(player);
        if (matches(serverHit, key, cell, requireRequestedCell)) return serverHit;

        Vec3 eye = player.getEyePosition();
        Vec3 serverLook = player.getViewVector(1.0f).normalize();
        if (serverLook.dot(clientLook) < CLIENT_LOOK_MIN_DOT) {
            context.sync().trace(player, "ACTION_REJECT client-look-diverged dot="
                    + String.format(Locale.ROOT, "%.5f", serverLook.dot(clientLook)));
            return null;
        }
        Vec3 recoveredEye = boundedClientEye(player, clientEye);
        if (recoveredEye == null) return null;
        Vec3 eyeDelta = clientEye.subtract(eye);
        ServerMicrovoxelRaycaster.Hit recovered = raycastMicrovoxel(player, recoveredEye, clientLook);
        if (matches(recovered, key, cell, requireRequestedCell)) {
            context.sync().trace(player, "ACTION_RECOVERED client-eye-ray delta="
                    + String.format(Locale.ROOT, "%.3f", eyeDelta.length()));
            return recovered;
        }
        context.sync().trace(player, "ACTION_RAY_MISMATCH server=" + hitLabel(serverHit)
                + " client=" + hitLabel(recovered)
                + " expected=" + key.x() + "," + key.y() + "," + key.z() + ":" + cell);
        return null;
    }

    private static boolean matches(ServerMicrovoxelRaycaster.Hit hit, MicrovoxelKey key, int cell,
                                   boolean requireRequestedCell) {
        if (hit == null) return false;
        if (requireRequestedCell) return hit.key().equals(key) && hit.cell() == cell;
        ServerMicrovoxelRaycaster.AdjacentTarget target = hit.adjacentTarget();
        return target.key().equals(key) && target.cell() == cell;
    }

    private static String hitLabel(ServerMicrovoxelRaycaster.Hit hit) {
        return hit == null ? "none"
                : hit.key().x() + "," + hit.key().y() + "," + hit.key().z() + ":" + hit.cell();
    }

    private Vec3 boundedClientEye(ServerPlayer player, Vec3 clientEye) {
        Vec3 serverEye = player.getEyePosition();
        Vec3 delta = clientEye.subtract(serverEye);
        if (delta.lengthSqr() > CLIENT_EYE_MAX_DELTA * CLIENT_EYE_MAX_DELTA) {
            context.sync().trace(player, "ACTION_REJECT client-eye-diverged distance="
                    + String.format(Locale.ROOT, "%.3f", delta.length()));
            return null;
        }
        return clientEye;
    }

    private static int cellAtStandardHit(MicrovoxelKey key, BlockHitResult hit) {
        Direction face = hit.getDirection();
        Vec3 point = hit.getLocation();
        if (face != null) {
            point = point.subtract(new Vec3(
                    face.step().x(), face.step().y(), face.step().z()).scale(1.0E-4));
        }
        int x = clampCell((int) Math.floor((point.x - key.x()) * MicrovoxelVolume.RESOLUTION));
        int y = clampCell((int) Math.floor((point.y - key.y()) * MicrovoxelVolume.RESOLUTION));
        int z = clampCell((int) Math.floor((point.z - key.z()) * MicrovoxelVolume.RESOLUTION));
        return MicrovoxelVolume.index(x, y, z);
    }

    private static int clampCell(int cell) {
        return Math.max(0, Math.min(MicrovoxelVolume.RESOLUTION - 1, cell));
    }

    private boolean withinReach(ServerPlayer player, MicrovoxelKey key) {
        UUID worldId = context.runtime().worldId(player.level());
        return worldId.equals(key.worldId())
                && player.getEyePosition().distanceToSqr(
                new Vec3(key.x() + 0.5, key.y() + 0.5, key.z() + 0.5)) <= MAX_REACH * MAX_REACH;
    }

    private boolean validRevision(ServerPlayer player, MicrovoxelKey key, MicrovoxelVolume volume, int expected) {
        if (volume == null) {
            ua.rp.chat.microvoxel.MicrovoxelMetrics.inc("edits.rejected.volume-missing");
            context.sync().trace(player, "ACTION_REJECT volume-missing");
            context.sync().sendRemove(player, key);
            return false;
        }
        if (volume.revision() != expected) {
            ua.rp.chat.microvoxel.MicrovoxelMetrics.inc("edits.rejected.stale-revision");
            context.sync().trace(player, "ACTION_REJECT stale-revision expected=" + expected
                    + " actual=" + volume.revision());
            context.sync().sendUpsert(player, key, volume);
            return false;
        }
        return true;
    }

    private BlockHitResult rayTraceBlocks(ServerPlayer player, double distance) {
        Vec3 start = player.getEyePosition();
        Vec3 dir = player.getViewVector(1.0f);
        Vec3 end = start.add(dir.x * distance, dir.y * distance, dir.z * distance);
        return ((ServerLevel) player.level()).clip(new net.minecraft.world.level.ClipContext(
                start, end, net.minecraft.world.level.ClipContext.Block.COLLIDER,
                net.minecraft.world.level.ClipContext.Fluid.NONE, player));
    }

    public record QueuedAction(long transactionId, int type, MicrovoxelKey key, int cell,
                               int expectedRevision, Vec3 clientLook, Vec3 clientEye) {
    }

    private record ClipboardVolume(MicrovoxelVolume volume, int anchorCell) {
    }
}