package ua.rp.chat.client.microvoxel;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;
import ua.rp.chat.microvoxel.MicrovoxelGreedyMesher;
import ua.rp.chat.microvoxel.MicrovoxelBrush;
import ua.rp.chat.microvoxel.MicrovoxelRaycaster;

import java.util.ArrayList;
import java.util.List;

public final class MicrovoxelInteractionController {
    private static final boolean DEBUG = Boolean.getBoolean("rpchat.microvoxel.debug");
    // Action ids mirror MicrovoxelProtocol 1:1 and are shared with the send batcher.
    public static final int ACTION_CONVERT = 1;
    public static final int ACTION_REMOVE = 2;
    public static final int ACTION_ADD = 3;
    public static final int ACTION_CARVE_STANDARD = 4;
    public static final int ACTION_UNDO = 8;
    public static final int ACTION_REDO = 9;
    public static final int ACTION_BRUSH_REMOVE = 10;
    public static final int ACTION_BRUSH_ADD = 11;
    public static final int ACTION_COPY = 12;
    public static final int ACTION_PASTE = 13;
    private static KeyMapping modeKey;
    private static KeyMapping convertKey;
    private static KeyMapping undoKey;
    private static KeyMapping redoKey;
    private static KeyMapping brushKey;
    private static KeyMapping radiusDownKey;
    private static KeyMapping radiusUpKey;
    private static KeyMapping copyKey;
    private static KeyMapping pasteKey;
    private static KeyMapping rotateKey;
    private static KeyMapping mirrorKey;
    private static boolean editing;
    private static MicrovoxelRaycaster.Hit currentHit;
    private static StandardTarget currentStandardTarget;
    private static long interactionTick;
    private static long lastAttackSentTick = Long.MIN_VALUE;
    /**
     * Last transmitted edit, used to coalesce held-button repeats. Resending the same cell
     * every tick while its transaction is still unacknowledged only produces stale-revision
     * rejects and overlay spam on the server; the in-flight transaction already covers it.
     */
    private static int lastSentAction = -1;
    private static BlockPos lastSentPosition;
    private static int lastSentCell = -1;
    private static long lastSentTransactionId = -1L;
    private static int brushShape = MicrovoxelBrush.SINGLE;
    private static int brushRadius = 1;
    private static int clipboardRotation;
    private static boolean clipboardMirror;

    private MicrovoxelInteractionController() {
    }

    public static void register() {
        modeKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.eclipseclient.microvoxel_mode", InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_M, KeyMapping.Category.GAMEPLAY));
        convertKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.eclipseclient.microvoxel_convert", InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_C, KeyMapping.Category.GAMEPLAY));
        undoKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.eclipseclient.microvoxel_undo", InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_Z, KeyMapping.Category.GAMEPLAY));
        redoKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.eclipseclient.microvoxel_redo", InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_Y, KeyMapping.Category.GAMEPLAY));
        brushKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.eclipseclient.microvoxel_brush", InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_B, KeyMapping.Category.GAMEPLAY));
        radiusDownKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.eclipseclient.microvoxel_radius_down", InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_LEFT_BRACKET, KeyMapping.Category.GAMEPLAY));
        radiusUpKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.eclipseclient.microvoxel_radius_up", InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_RIGHT_BRACKET, KeyMapping.Category.GAMEPLAY));
        copyKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.eclipseclient.microvoxel_copy", InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_K, KeyMapping.Category.GAMEPLAY));
        pasteKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.eclipseclient.microvoxel_paste", InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_V, KeyMapping.Category.GAMEPLAY));
        rotateKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.eclipseclient.microvoxel_rotate", InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_R, KeyMapping.Category.GAMEPLAY));
        mirrorKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.eclipseclient.microvoxel_mirror", InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_N, KeyMapping.Category.GAMEPLAY));
    }

    public static void tick(Minecraft minecraft) {
        interactionTick++;
        if (minecraft.player == null || minecraft.level == null) {
            currentHit = null;
            currentStandardTarget = null;
            editing = false;
            MicrovoxelActionBatcher.clear();
            return;
        }
        while (modeKey != null && modeKey.consumeClick()) {
            if (!editing && !ClientPlayNetworking.canSend(MicrovoxelActionPayload.TYPE)) {
                trace("MODE_REJECTED channel-unavailable");
                minecraft.gui.setOverlayMessage(Component.literal(
                        "Сервер не поддерживает редактирование микровокселей или канал ещё не готов."), false);
                continue;
            }
            editing = !editing;
            trace("MODE " + (editing ? "enabled" : "disabled"));
            minecraft.gui.setOverlayMessage(Component.literal(editing
                    ? "Режим микровокселей включён: C — преобразовать, ЛКМ/ПКМ — убрать/добавить"
                    : "Режим микровокселей выключен"), false);
        }
        currentHit = editing ? raycast(minecraft) : null;
        currentStandardTarget = editing && currentHit == null ? standardTarget(minecraft) : null;
        while (editing && convertKey != null && convertKey.consumeClick()) convertTarget(minecraft);
        while (editing && undoKey != null && undoKey.consumeClick()) {
            send(minecraft, ACTION_UNDO, 0, 0, 0, 0, 0);
        }
        while (editing && redoKey != null && redoKey.consumeClick()) {
            send(minecraft, ACTION_REDO, 0, 0, 0, 0, 0);
        }
        while (editing && brushKey != null && brushKey.consumeClick()) {
            brushShape = (brushShape + 1) % 4;
            showBrush(minecraft);
        }
        while (editing && radiusDownKey != null && radiusDownKey.consumeClick()) {
            brushRadius = Math.max(1, brushRadius - 1);
            showBrush(minecraft);
        }
        while (editing && radiusUpKey != null && radiusUpKey.consumeClick()) {
            brushRadius = Math.min(MicrovoxelBrush.MAX_RADIUS, brushRadius + 1);
            showBrush(minecraft);
        }
        while (editing && copyKey != null && copyKey.consumeClick()) copyTarget(minecraft);
        while (editing && pasteKey != null && pasteKey.consumeClick()) pasteTarget(minecraft);
        while (editing && rotateKey != null && rotateKey.consumeClick()) {
            clipboardRotation = (clipboardRotation + 1) & 3;
            showClipboardTransform(minecraft);
        }
        while (editing && mirrorKey != null && mirrorKey.consumeClick()) {
            clipboardMirror = !clipboardMirror;
            showClipboardTransform(minecraft);
        }
        // End of the 50ms coalescing window: lone clicks keep single-packet latency,
        // bursts leave as one batch packet per 16 entries.
        MicrovoxelActionBatcher.flush(minecraft);
    }

    public static void handleContinuousAttack(Minecraft minecraft) {
        handleAttack(minecraft);
    }

    public static boolean handleAttack(Minecraft minecraft) {
        if (!editing || minecraft.player == null) return false;
        if (lastAttackSentTick == interactionTick) return true;
        // Minecraft calls continueAttack before the END_CLIENT_TICK callback refreshes the
        // preview. Reusing currentHit therefore sent the cell removed on the previous tick and
        // produced a stream of false "target changed" rejects. Always raycast the predicted
        // geometry at the instant the action is emitted.
        MicrovoxelRaycaster.Hit hit = resolveHit(minecraft);
        if (hit == null) {
            StandardTarget standard = standardTarget(minecraft);
            if (standard != null) {
                trace("ACTION_SENT carve-standard pos=" + standard.position.toShortString() + " cell=" + standard.cell);
                send(minecraft, ACTION_CARVE_STANDARD, standard.position.getX(), standard.position.getY(),
                        standard.position.getZ(), standard.cell, 0);
                lastAttackSentTick = interactionTick;
                minecraft.player.swing(InteractionHand.MAIN_HAND);
                return true;
            }
        }
        if (hit == null) {
            // A microvoxel marker must never fall through to vanilla breaking: its visual block is only a
            // server-side anchor and vanilla would briefly erase it before the server restores it.
            if (targetsKnownVolume(minecraft)) {
                trace("REMOVE_BLOCKED marker-targeted-without-cell");
                minecraft.gui.setOverlayMessage(Component.literal(
                        "Не удалось определить ячейку. Наведитесь на поверхность микровокселя."), false);
                return true;
            }
            return false;
        }
        currentHit = hit;
        trace("ACTION_SENT remove pos=" + hit.entry().x() + "," + hit.entry().y() + "," + hit.entry().z()
                + " cell=" + hit.cell() + " revision=" + hit.entry().volume().revision());
        int action = brushShape == MicrovoxelBrush.SINGLE ? ACTION_REMOVE : ACTION_BRUSH_REMOVE;
        int cell = brushShape == MicrovoxelBrush.SINGLE ? hit.cell()
                : MicrovoxelBrush.encode(hit.cell(), brushShape, brushRadius);
        int expectedRevision = hit.entry().volume().revision();
        BlockPos hitPosition = new BlockPos(hit.entry().x(), hit.entry().y(), hit.entry().z());
        if (isRepeatOfInflight(action, hitPosition, hit.cell())) {
            return true;
        }
        long transactionId = MicrovoxelClientState.nextTransactionId();
        if (ClientPlayNetworking.canSend(MicrovoxelActionPayload.TYPE)) {
            if (action == ACTION_REMOVE) {
                if (!MicrovoxelClientState.predictRemove(
                        new BlockPos(hit.entry().x(), hit.entry().y(), hit.entry().z()),
                        hit.cell(), transactionId)) {
                    // Geometry changed between raycast and prediction; wait for the next tick
                    // instead of transmitting an operation already known to be invalid.
                    currentHit = null;
                    return true;
                }
            } else {
                // Brush removals predict the whole stroke at once across every touched volume.
                MicrovoxelBrush.Axis axis = axisFor(hit.face());
                List<MicrovoxelBrush.Target> brushTargets = MicrovoxelBrush.targets(
                        hit.entry().x(), hit.entry().y(), hit.entry().z(), hit.cell(),
                        brushShape, brushRadius, axis);
                List<MicrovoxelClientState.BrushOp> ops = new ArrayList<>(brushTargets.size());
                for (MicrovoxelBrush.Target brushTarget : brushTargets) {
                    ops.add(new MicrovoxelClientState.BrushOp(
                            new BlockPos(brushTarget.blockX(), brushTarget.blockY(), brushTarget.blockZ()),
                            brushTarget.cell(), ""));
                }
                MicrovoxelClientState.predictBrush(transactionId, ops);
            }
        }
        send(minecraft, transactionId, action, hit.entry().x(), hit.entry().y(), hit.entry().z(),
                cell, expectedRevision);
        rememberSent(action, hitPosition, hit.cell(), transactionId);
        lastAttackSentTick = interactionTick;
        minecraft.player.swing(InteractionHand.MAIN_HAND);
        return true;
    }

    public static boolean handleUse(Minecraft minecraft) {
        if (!editing || minecraft.player == null) return false;
        MicrovoxelRaycaster.Hit hit = currentHit != null ? currentHit : resolveHit(minecraft);
        if (hit == null) {
            if (targetsKnownVolume(minecraft)) {
                trace("ADD_BLOCKED marker-targeted-without-cell");
                minecraft.gui.setOverlayMessage(Component.literal(
                        "Не удалось определить ячейку. Наведитесь на поверхность микровокселя."), false);
                return true;
            }
            return false;
        }
        currentHit = hit;
        MicrovoxelRaycaster.Target target = hit.adjacentTarget();
        MicrovoxelClientState.CachedVolume targetVolume = MicrovoxelClientState.get(
                new BlockPos(target.x(), target.y(), target.z()));
        int targetRevision = targetVolume == null ? 0 : targetVolume.volume.revision();
        trace("ACTION_SENT add pos=" + target.x() + "," + target.y() + "," + target.z()
                + " cell=" + target.cell() + " revision=" + targetRevision);
        int action = brushShape == MicrovoxelBrush.SINGLE ? ACTION_ADD : ACTION_BRUSH_ADD;
        int cell = brushShape == MicrovoxelBrush.SINGLE ? target.cell()
                : MicrovoxelBrush.encode(target.cell(), brushShape, brushRadius);
        BlockPos targetPosition = new BlockPos(target.x(), target.y(), target.z());
        if (isRepeatOfInflight(action, targetPosition, cell)) {
            return true;
        }
        long transactionId = MicrovoxelClientState.nextTransactionId();
        if (ClientPlayNetworking.canSend(MicrovoxelActionPayload.TYPE)) {
            // Predict the placement instantly so building never stalls on latency. A wrong
            // material guess only affects the preview: the authoritative edit result corrects
            // the palette as soon as the server round-trip completes.
            if (action == ACTION_ADD) {
                String previewMaterial = heldMaterialString(minecraft);
                if (previewMaterial != null && !MicrovoxelClientState.predictAdd(
                        new BlockPos(target.x(), target.y(), target.z()),
                        target.cell(), previewMaterial, transactionId)) {
                    currentHit = null;
                    return true;
                }
            } else {
                String previewMaterial = heldMaterialString(minecraft);
                if (previewMaterial != null) {
                    MicrovoxelBrush.Axis axis = axisFor(hit.face());
                    List<MicrovoxelBrush.Target> brushTargets = MicrovoxelBrush.targets(
                            target.x(), target.y(), target.z(), target.cell(),
                            brushShape, brushRadius, axis);
                    List<MicrovoxelClientState.BrushOp> ops = new ArrayList<>(brushTargets.size());
                    for (MicrovoxelBrush.Target brushTarget : brushTargets) {
                        ops.add(new MicrovoxelClientState.BrushOp(
                                new BlockPos(brushTarget.blockX(), brushTarget.blockY(), brushTarget.blockZ()),
                                brushTarget.cell(), previewMaterial));
                    }
                    MicrovoxelClientState.predictBrush(transactionId, ops);
                }
            }
        }
        send(minecraft, transactionId, action, target.x(), target.y(), target.z(), cell, targetRevision);
        rememberSent(action, targetPosition, cell, transactionId);
        minecraft.player.swing(InteractionHand.MAIN_HAND);
        return true;
    }

    /**
     * Coalesces held-button repeats: when the exact same edit is still awaiting its server
     * result, the in-flight transaction already covers it and a resend would only bounce off
     * a stale-revision reject. Any target change (or a settled transaction) transmits again.
     */
    private static boolean isRepeatOfInflight(int action, BlockPos position, int cell) {
        return action == lastSentAction
                && lastSentPosition != null && lastSentPosition.equals(position)
                && lastSentCell == cell
                && MicrovoxelClientState.isPending(lastSentTransactionId);
    }

    private static void rememberSent(int action, BlockPos position, int cell, long transactionId) {
        lastSentAction = action;
        lastSentPosition = position.immutable();
        lastSentCell = cell;
        lastSentTransactionId = transactionId;
    }

    /**
     * Best-effort preview material for instant placement prediction. Mirrors the server's
     * material preference (main hand, then off hand) but only resolves plain block items;
     * anything else skips prediction and waits for the authoritative result.
     */
    private static String heldMaterialString(Minecraft minecraft) {
        if (minecraft.player == null) return null;
        net.minecraft.world.item.ItemStack held = minecraft.player.getMainHandItem();
        if (!(held.getItem() instanceof net.minecraft.world.item.BlockItem)) {
            held = minecraft.player.getOffhandItem();
            if (!(held.getItem() instanceof net.minecraft.world.item.BlockItem)) return null;
        }
        net.minecraft.world.level.block.state.BlockState state =
                ((net.minecraft.world.item.BlockItem) held.getItem()).getBlock().defaultBlockState();
        StringBuilder result = new StringBuilder(
                net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
        var properties = state.getProperties();
        if (!properties.isEmpty()) {
            result.append('[');
            boolean first = true;
            for (net.minecraft.world.level.block.state.properties.Property<?> property : properties) {
                if (!first) result.append(',');
                first = false;
                result.append(property.getName()).append('=').append(propertyValue(state, property));
            }
            result.append(']');
        }
        return result.toString();
    }

    /** Renders one property value exactly like the server blockstate codec (lowercase). */
    private static <T extends Comparable<T>> String propertyValue(
            net.minecraft.world.level.block.state.BlockState state,
            net.minecraft.world.level.block.state.properties.Property<T> property) {
        return state.getValue(property).toString().toLowerCase(java.util.Locale.ROOT);
    }

    private static MicrovoxelBrush.Axis axisFor(MicrovoxelGreedyMesher.Direction face) {
        return switch (face) {
            case EAST, WEST -> MicrovoxelBrush.Axis.X;
            case UP, DOWN -> MicrovoxelBrush.Axis.Y;
            case NORTH, SOUTH -> MicrovoxelBrush.Axis.Z;
        };
    }

    public static MicrovoxelRaycaster.Hit currentHit() {
        return currentHit;
    }

    public static StandardTarget currentStandardTarget() {
        return currentStandardTarget;
    }

    public static boolean editing() {
        return editing;
    }

    public static List<PreviewCell> brushPreview() {
        MicrovoxelRaycaster.Hit hit = currentHit;
        if (!editing || hit == null || brushShape == MicrovoxelBrush.SINGLE) return List.of();
        MicrovoxelBrush.Axis axis = axisFor(hit.face());
        List<MicrovoxelBrush.Target> targets = MicrovoxelBrush.targets(
                hit.entry().x(), hit.entry().y(), hit.entry().z(), hit.cell(),
                brushShape, brushRadius, axis);
        ArrayList<PreviewCell> preview = new ArrayList<>(targets.size());
        for (MicrovoxelBrush.Target target : targets) {
            BlockPos position = new BlockPos(target.blockX(), target.blockY(), target.blockZ());
            MicrovoxelClientState.CachedVolume cached = MicrovoxelClientState.get(position);
            if (cached != null && cached.volume.occupied(target.cell())) {
                preview.add(new PreviewCell(position, target.cell()));
            }
        }
        return List.copyOf(preview);
    }

    private static void showBrush(Minecraft minecraft) {
        String shape = switch (brushShape) {
            case MicrovoxelBrush.SPHERE -> "сфера";
            case MicrovoxelBrush.BOX -> "куб";
            case MicrovoxelBrush.PLANE -> "плоскость";
            default -> "одна ячейка";
        };
        minecraft.gui.setOverlayMessage(Component.literal("Кисть: " + shape
                + (brushShape == MicrovoxelBrush.SINGLE ? "" : ", радиус " + brushRadius)), false);
    }

    private static void showClipboardTransform(Minecraft minecraft) {
        minecraft.gui.setOverlayMessage(Component.literal("Буфер: поворот "
                + (clipboardRotation * 90) + "°, отражение "
                + (clipboardMirror ? "включено" : "выключено")), false);
    }

    private static void copyTarget(Minecraft minecraft) {
        MicrovoxelRaycaster.Hit hit = currentHit != null ? currentHit : resolveHit(minecraft);
        if (hit == null) {
            minecraft.gui.setOverlayMessage(Component.literal(
                    "Наведитесь на микровоксельный объём для копирования."), false);
            return;
        }
        send(minecraft, ACTION_COPY, hit.entry().x(), hit.entry().y(), hit.entry().z(),
                hit.cell(), hit.entry().volume().revision());
    }

    private static void pasteTarget(Minecraft minecraft) {
        MicrovoxelRaycaster.Hit hit = currentHit != null ? currentHit : resolveHit(minecraft);
        if (hit == null) {
            minecraft.gui.setOverlayMessage(Component.literal(
                    "Наведитесь на грань микровокселя для вставки."), false);
            return;
        }
        MicrovoxelRaycaster.Target target = hit.adjacentTarget();
        MicrovoxelClientState.CachedVolume targetVolume = MicrovoxelClientState.get(
                new BlockPos(target.x(), target.y(), target.z()));
        int revision = targetVolume == null ? 0 : targetVolume.volume.revision();
        int encoded = target.cell() | (clipboardRotation << 12) | (clipboardMirror ? 1 << 14 : 0);
        send(minecraft, ACTION_PASTE, target.x(), target.y(), target.z(), encoded, revision);
    }

    private static void convertTarget(Minecraft minecraft) {
        if (!(minecraft.hitResult instanceof BlockHitResult blockHit)) {
            minecraft.gui.setOverlayMessage(Component.literal("Нужно смотреть на полноразмерный блок."), false);
            return;
        }
        BlockPos position = blockHit.getBlockPos();
        if (MicrovoxelClientState.get(position) != null) return;
        send(minecraft, ACTION_CONVERT, position.getX(), position.getY(), position.getZ(), 0, 0);
    }

    private static MicrovoxelRaycaster.Hit raycast(Minecraft minecraft) {
        Vec3 eye = minecraft.player.getEyePosition(1.0f);
        Vec3 direction = minecraft.player.getViewVector(1.0f).normalize();
        double reach = Math.min(6.25, minecraft.player.blockInteractionRange() + 0.25);
        return MicrovoxelRaycaster.cast(eye.x, eye.y, eye.z, direction.x, direction.y, direction.z, reach,
                MicrovoxelClientState.raycastEntries(eye.x, eye.y, eye.z, reach + 2.0));
    }

    /**
     * The normal path uses the same eye ray as the server.  Some client camera modes can however
     * leave that ray one frame behind the actual crosshair target.  In that case, reconstruct the
     * microvoxel hit from Minecraft's already-resolved block hit before allowing vanilla to act.
     *
     * <p>The reconstruction always spans every nearby volume at full reach, never just the
     * vanilla-hit block: aiming through an empty cavity at a second volume behind it must
     * select the far wall's exact micro-cell with its micro face normal, not the vanilla face
     * of the near block. Only when that full re-cast also misses does the point-mapping
     * last resort run, and it too keeps the DDA-derived normal whenever one exists.</p>
     */
    private static MicrovoxelRaycaster.Hit resolveHit(Minecraft minecraft) {
        MicrovoxelRaycaster.Hit direct = raycast(minecraft);
        if (direct != null || !(minecraft.hitResult instanceof BlockHitResult blockHit)) return direct;

        Vec3 eye = minecraft.player.getEyePosition(1.0f);
        Vec3 target = blockHit.getLocation();
        Vec3 delta = target.subtract(eye);
        double distance = delta.length();
        double reach = Math.min(6.25, minecraft.player.blockInteractionRange() + 0.25);
        if (distance > 1.0E-5 && distance <= 6.5) {
            // Re-cast along the exact eye-to-crosshair line across all nearby volumes. A ray
            // through a carved cavity keeps travelling past empty cells (up to 52 micro-steps
            // per volume) until it meets the first occupied cell in ANY volume, so inner walls
            // of U-shaped hollows and volumes behind cavities stay selectable.
            MicrovoxelRaycaster.Hit reconstructed = MicrovoxelRaycaster.cast(
                    eye.x, eye.y, eye.z, delta.x / distance, delta.y / distance, delta.z / distance,
                    reach, MicrovoxelClientState.raycastEntries(eye.x, eye.y, eye.z, reach + 2.0));
            if (reconstructed != null) return reconstructed;
        }

        // Last-resort protection for a marker selected by vanilla. Nudge inside the clicked face,
        // then map its exact surface point to a 1/16 cell.
        BlockPos position = blockHit.getBlockPos();
        MicrovoxelClientState.CachedVolume cached = MicrovoxelClientState.get(position);
        if (cached == null) return null;
        MicrovoxelRaycaster.Entry entry = new MicrovoxelRaycaster.Entry(
                position.getX(), position.getY(), position.getZ(), cached.volume);
        Direction face = blockHit.getDirection();
        Vec3 point = target.subtract(face.getStepX() * 1.0E-4, face.getStepY() * 1.0E-4, face.getStepZ() * 1.0E-4);
        int cellX = clampCell((int) Math.floor((point.x - position.getX()) * 16.0));
        int cellY = clampCell((int) Math.floor((point.y - position.getY()) * 16.0));
        int cellZ = clampCell((int) Math.floor((point.z - position.getZ()) * 16.0));
        int cell = ua.rp.chat.microvoxel.MicrovoxelVolume.index(cellX, cellY, cellZ);
        if (cached.volume.materialAt(cellX, cellY, cellZ) == 0) return null;
        return new MicrovoxelRaycaster.Hit(entry, cell,
                ua.rp.chat.microvoxel.MicrovoxelGreedyMesher.Direction.valueOf(face.name()), distance);
    }

    private static boolean targetsKnownVolume(Minecraft minecraft) {
        return minecraft.hitResult instanceof BlockHitResult blockHit
                && MicrovoxelClientState.get(blockHit.getBlockPos()) != null;
    }

    /**
     * While in edit mode a normal full block is a valid first cut. The client only proposes the
     * exact cell; the server repeats the standard-block raycast before it converts anything.
     */
    private static StandardTarget standardTarget(Minecraft minecraft) {
        if (!(minecraft.hitResult instanceof BlockHitResult hit) || minecraft.level == null) return null;
        BlockPos position = hit.getBlockPos();
        if (MicrovoxelClientState.get(position) != null) return null;
        var state = minecraft.level.getBlockState(position);
        if (state.isAir() || !state.isSolidRender() || state.hasBlockEntity()) return null;
        Vec3 point = hit.getLocation().subtract(hit.getDirection().getStepX() * 1.0E-4,
                hit.getDirection().getStepY() * 1.0E-4, hit.getDirection().getStepZ() * 1.0E-4);
        int x = clampCell((int) Math.floor((point.x - position.getX()) * 16.0));
        int y = clampCell((int) Math.floor((point.y - position.getY()) * 16.0));
        int z = clampCell((int) Math.floor((point.z - position.getZ()) * 16.0));
        return new StandardTarget(position.immutable(),
                ua.rp.chat.microvoxel.MicrovoxelVolume.index(x, y, z),
                MicrovoxelGreedyMesher.Direction.valueOf(hit.getDirection().name()));
    }

    private static int clampCell(int cell) {
        return Math.max(0, Math.min(15, cell));
    }

    public record StandardTarget(BlockPos position, int cell, MicrovoxelGreedyMesher.Direction face) {
    }

    public record PreviewCell(BlockPos position, int cell) {
    }

    private static void send(Minecraft minecraft, int action, int x, int y, int z, int cell, int revision) {
        send(minecraft, MicrovoxelClientState.nextTransactionId(),
                action, x, y, z, cell, revision);
    }

    private static void send(Minecraft minecraft, long transactionId,
                             int action, int x, int y, int z, int cell, int revision) {
        // High-frequency cell writes ride the 50ms batch window (flushed at tick end);
        // control actions transmit immediately exactly as before.
        if (MicrovoxelActionBatcher.isBatchable(action)) {
            MicrovoxelActionBatcher.enqueue(transactionId, action, x, y, z, cell, revision);
            return;
        }
        if (ClientPlayNetworking.canSend(MicrovoxelActionPayload.TYPE)) {
            Vec3 look = minecraft.player == null ? Vec3.ZERO : minecraft.player.getViewVector(1.0f).normalize();
            Vec3 eye = minecraft.player == null ? Vec3.ZERO : minecraft.player.getEyePosition(1.0f);
            ClientPlayNetworking.send(new MicrovoxelActionPayload(
                    MicrovoxelClientState.PROTOCOL_VERSION, transactionId,
                    action, x, y, z, cell, revision,
                    (float) look.x, (float) look.y, (float) look.z,
                    (float) eye.x, (float) eye.y, (float) eye.z));
        } else {
            trace("ACTION_NOT_SENT channel-unavailable action=" + action);
        }
    }

    private static void trace(String message) {
        if (DEBUG) ua.rp.chat.client.EclipseClientMod.LOGGER.info("[MICROVOXEL] " + message);
    }
}
