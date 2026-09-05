package ua.rp.chat.client.carver;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import ua.rp.chat.client.carver.CarverSyncPayload;
import ua.rp.chat.carver.CarverFaceSlicer;
import ua.rp.chat.carver.DraftMask;
import ua.rp.chat.carver.CarverProtocol;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;

/**
 * Client mirror of one drafting session. Applies authoritative server events to the
 * design screen, the camera rig and the work-effects layer, and routes local input
 * back through the action channel. All methods run on the client thread.
 */
public final class CarverClientState {
    private static boolean designing;
    private static boolean working;
    private static BlockPos focus;
    private static String materialId = "";
    private static final DraftMask draft = new DraftMask();
    private static int estimateCells;
    private static float estimateSeconds;
    private static float estimateStamina;
    private static int estimateTicks;
    private static int workTotalTicks;
    private static int workDoneTicks;
    private static int versionMismatches;
    private static int mirrorAxes;
    private static double lastFlushedProgress = -1.0;
    private static long clientTickCounter;
    private static long workStartClientTick;
    private static long lastClientTickNanos;
    /**
     * Look locked at work start: the work camera frames the bench, so the artisan
     * must not turn away under it with the mouse. Enforced every client tick
     * while the work session lives.
     */
    private static float lockYaw;
    private static float lockPitch;
    private static CarverFaceSlicer.Face viewFace = CarverFaceSlicer.Face.UP;
    private static int viewLayer;
    private static boolean peelOuterLayers;
    private static boolean isolateFace;

    private CarverClientState() {
    }

    public static boolean designing() {
        return designing;
    }

    public static boolean working() {
        return working;
    }

    public static boolean inSession() {
        return designing || working;
    }

    public static BlockPos focus() {
        return focus;
    }

    public static String materialId() {
        return materialId;
    }

    public static DraftMask draft() {
        return draft;
    }

    /**
     * Whether a volume cell accepts paint: everything on a fresh socket, only
     * still-occupied cells on a re-entered carving. Strokes on air are dead on
     * arrival server-side, so the brush refuses them upfront and the crosshair
     * never promises what the chisel cannot cut.
     */
    public static boolean isPaintable(int cell) {
        if (focus == null) return false;
        try {
            ua.rp.chat.client.microvoxel.MicrovoxelClientState.CachedVolume cached =
                    ua.rp.chat.client.microvoxel.MicrovoxelClientState.get(focus);
            if (cached == null || cached.volume == null) return true;
            if (cell < 0 || cell >= ua.rp.chat.microvoxel.MicrovoxelVolume.CELL_COUNT) {
                return false;
            }
            return cached.volume.occupied(cell);
        } catch (RuntimeException unreadable) {
            return true;
        }
    }

    public static int estimateCells() {
        return estimateCells;
    }

    public static float estimateSeconds() {
        return estimateSeconds;
    }

    public static float estimateStamina() {
        return estimateStamina;
    }

    public static int estimateTicks() {
        return estimateTicks;
    }

    public static int workTotalTicks() {
        return workTotalTicks;
    }

    public static int workDoneTicks() {
        return workDoneTicks;
    }

    public static double workProgress() {
        if (workTotalTicks <= 0) return 0.0;
        return Math.min(1.0, Math.max(0.0, workDoneTicks / (double) workTotalTicks));
    }

    /** Editor view: active face, its layer counted from the viewer, peel and isolation. */
    public static CarverFaceSlicer.Face viewFace() {
        return viewFace;
    }

    public static void setViewFace(CarverFaceSlicer.Face face) {
        if (face != null) viewFace = face;
    }

    public static int viewLayer() {
        return Math.max(0, Math.min(15, viewLayer));
    }

    public static void setViewLayer(int layer) {
        viewLayer = Math.max(0, Math.min(15, layer));
    }

    public static boolean peelOuterLayers() {
        return peelOuterLayers;
    }

    public static void setPeelOuterLayers(boolean peel) {
        peelOuterLayers = peel;
    }

    public static boolean isolateFace() {
        return isolateFace;
    }

    public static void setIsolateFace(boolean isolate) {
        isolateFace = isolate;
    }

    /** Layer index of an absolute cell counted from the given face. */
    public static int layerOf(CarverFaceSlicer.Face face, int cell) {
        return switch (face) {
            case UP -> 15 - DraftMask.y(cell);
            case DOWN -> DraftMask.y(cell);
            case NORTH -> DraftMask.z(cell);
            case SOUTH -> 15 - DraftMask.z(cell);
            case WEST -> DraftMask.x(cell);
            case EAST -> 15 - DraftMask.x(cell);
        };
    }

    public static void handle(CarverSyncPayload payload) {
        if (payload.protocolVersion() != CarverProtocol.VERSION) {
            versionMismatches++;
            return;
        }
        BlockPos pos = new BlockPos(payload.x(), payload.y(), payload.z());
        switch (payload.event()) {
            case CarverProtocol.EVENT_SESSION_OPEN -> onOpen(pos, payload.data());
            case CarverProtocol.EVENT_DRAFT_STATE -> onDraft(pos, payload.data());
            case CarverProtocol.EVENT_ESTIMATE -> onEstimate(pos, payload.data());
            case CarverProtocol.EVENT_WORK_START -> onWorkStart(pos, payload.data());
            case CarverProtocol.EVENT_WORK_PROGRESS -> onWorkProgress(pos, payload.data());
            case CarverProtocol.EVENT_WORK_DONE -> onWorkDone(pos, payload.data());
            case CarverProtocol.EVENT_SESSION_CLOSE -> onClose(pos, payload.data());
            case CarverProtocol.EVENT_MIRROR_STATE -> onMirror(payload.data());
            case CarverProtocol.EVENT_WORK_OBSERVED_START -> onObservedStart(payload.data());
            case CarverProtocol.EVENT_WORK_OBSERVED_END -> onObservedEnd(payload.data());
            default -> {
            }
        }
    }

    private static void onOpen(BlockPos pos, byte[] data) {
        designing = true;
        working = false;
        focus = pos;
        draft.clearAll();
        workDoneTicks = 0;
        workTotalTicks = 0;
        try (DataInputStream input = stream(data)) {
            int idLength = input.readInt();
            if (idLength < 0 || idLength > 256) throw new IOException("Bad material id");
            byte[] id = input.readNBytes(idLength);
            if (id.length != idLength) throw new IOException("Truncated material id");
            materialId = new String(id, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException invalid) {
            materialId = "";
        }
        CarverPerfLog.beginEntry();
        CarverCameraRig.beginDesign(pos);
        CarverPerfLog.stage("camera");
        CarverHologram.begin(Minecraft.getInstance(), pos, materialId);
        CarverPerfLog.stage("hologram");
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            net.minecraft.world.phys.Vec3 look =
                    minecraft.player.getViewVector(1.0f).normalize();
            viewFace = CarverFaceSlicer.defaultFace(look.x, look.y, look.z);
            mirrorAxes = 0;
            viewLayer = 0;
            peelOuterLayers = false;
            isolateFace = false;
            minecraft.setScreen(new CarverDesignScreen());
            CarverPerfLog.stage("screen");
            CarverPerfLog.endEntry();
        }
    }

    private static void onDraft(BlockPos pos, byte[] data) {
        if (!designing || !pos.equals(focus)) return;
        try {
            DraftMask server = DraftMask.decode(data);
            draft.clearAll();
            draft.orIn(server);
        } catch (IllegalArgumentException ignored) {
        }
    }

    private static void onEstimate(BlockPos pos, byte[] data) {
        if (!designing || !pos.equals(focus)) return;
        try (DataInputStream input = stream(data)) {
            estimateCells = input.readInt();
            estimateSeconds = input.readFloat();
            estimateStamina = input.readFloat();
            estimateTicks = input.readInt();
        } catch (IOException | RuntimeException ignored) {
        }
    }

    private static void onWorkStart(BlockPos pos, byte[] data) {
        if (!pos.equals(focus)) return;
        designing = false;
        working = true;
        try (DataInputStream input = stream(data)) {
            workTotalTicks = input.readInt();
        } catch (IOException | RuntimeException invalid) {
            workTotalTicks = 0;
        }
        workDoneTicks = 0;
        workStartClientTick = clientTickCounter;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            lockYaw = minecraft.player.getYRot();
            lockPitch = minecraft.player.getXRot();
        }
        if (minecraft.screen instanceof CarverDesignScreen) {
            minecraft.setScreen(null);
        }
        trace("work started, total=" + workTotalTicks);
        CarverCameraRig.beginWork(pos);
        CarverHologram.beginFall();
        CarverHologram.setImpactArmed(true);
        CarverHologram.replaySilentLanding(minecraft);
        lastFlushedProgress = -1.0;
        ua.rp.chat.client.microvoxel.MicrovoxelClientState.setWorkFocus(pos);
        ua.rp.chat.client.microvoxel.MicrovoxelClientState.flushWorkFocus();
        overlay(minecraft, "Работа началась. Не двигайтесь, мастер.");
    }

    private static void onWorkProgress(BlockPos pos, byte[] data) {
        if (!working || !pos.equals(focus)) return;
        try (DataInputStream input = stream(data)) {
            workDoneTicks = input.readInt();
            workTotalTicks = input.readInt();
        } catch (IOException | RuntimeException ignored) {
        }
        double progress = workProgress();
        if (ua.rp.chat.carver.CarverWorkPhases.phasesCrossed(lastFlushedProgress, progress) > 0) {
            lastFlushedProgress = progress;
            ua.rp.chat.client.microvoxel.MicrovoxelClientState.flushWorkFocus();
        }
    }

    private static void onWorkDone(BlockPos pos, byte[] data) {
        if (!pos.equals(focus)) return;
        int removed = 0;
        try (DataInputStream input = stream(data)) {
            removed = input.readInt();
        } catch (IOException | RuntimeException ignored) {
        }
        working = false;
        clearPendingBox();
        CarverWorkFx.finish(focus);
        CarverCameraRig.end();
        CarverHologram.clear();
        CarverPerfLog.endSession();
        ua.rp.chat.client.microvoxel.MicrovoxelClientState.flushWorkFocus();
        ua.rp.chat.client.microvoxel.MicrovoxelClientState.setWorkFocus(null);
        focus = null;
        overlay(Minecraft.getInstance(), "Готово: снято " + removed + " вокселей.");
    }

    private static void onClose(BlockPos pos, byte[] data) {
        designing = false;
        working = false;
        mirrorAxes = 0;
        clearPendingBox();
        ua.rp.chat.client.microvoxel.MicrovoxelClientState.flushWorkFocus();
        ua.rp.chat.client.microvoxel.MicrovoxelClientState.setWorkFocus(null);
        focus = null;
        draft.clearAll();
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof CarverDesignScreen) {
            minecraft.setScreen(null);
        }
        CarverCameraRig.end();
        CarverHologram.clear();
        CarverPerfLog.endSession();
        int reason = data != null && data.length > 0 ? data[0] & 0xFF : 0;
        if (reason != 0) {
            overlay(minecraft, "Чертёж закрыт.");
        }
    }

    public static void sendStroke(boolean add, DraftMask stroke) {
        if (!designing || focus == null) return;
        send(add ? CarverProtocol.ACTION_STROKE_ADD : CarverProtocol.ACTION_STROKE_ERASE,
                focus, stroke.encode());
    }

    public static void sendClear() {
        if (!designing || focus == null) return;
        send(CarverProtocol.ACTION_CLEAR_DRAFT, focus, new byte[0]);
    }

    public static void sendBox(boolean add, DraftMask box) {
        if (!designing || focus == null || box.isEmpty()) return;
        send(add ? CarverProtocol.ACTION_BOX_ADD : CarverProtocol.ACTION_BOX_ERASE,
                focus, box.encode());
    }

    private static int pendingX0 = -1;
    private static int pendingY0 = -1;
    private static int pendingZ0 = -1;
    private static int pendingX1 = -1;
    private static int pendingY1 = -1;
    private static int pendingZ1 = -1;

    /** Live rubber-band box in volume cells, drawn while the player drags. */
    public static void setPendingBox(int x0, int y0, int z0, int x1, int y1, int z1) {
        pendingX0 = x0;
        pendingY0 = y0;
        pendingZ0 = z0;
        pendingX1 = x1;
        pendingY1 = y1;
        pendingZ1 = z1;
    }

    public static void clearPendingBox() {
        pendingX0 = -1;
    }

    public static boolean hasPendingBox() {
        return pendingX0 >= 0;
    }

    public static int[] pendingBox() {
        return new int[]{pendingX0, pendingY0, pendingZ0, pendingX1, pendingY1, pendingZ1};
    }

    /** Notifies the server that the artisan walks up to the workpiece itself. */
    public static void sendAutowalk() {
        if (!designing || focus == null) return;
        send(CarverProtocol.ACTION_AUTOWALK, focus, new byte[0]);
    }

    public static void sendApprove() {
        if (!designing || focus == null) return;
        trace("approve sent, draft=" + draft.count());
        send(CarverProtocol.ACTION_APPROVE, focus, new byte[0]);
        // Optimistic drop: the copy starts falling on SPACE without waiting for the
        // server round-trip, so touchdown lands on time. Touchdown effects stay gated
        // behind the confirmed work start; a rejected draft restores silently instead.
        if (!draft.isEmpty()) {
            CarverHologram.beginFall();
        }
    }

    public static void sendUndo() {
        if (!designing || focus == null) return;
        send(CarverProtocol.ACTION_UNDO, focus, new byte[0]);
    }

    public static void sendRedo() {
        if (!designing || focus == null) return;
        send(CarverProtocol.ACTION_REDO, focus, new byte[0]);
    }

    public static void sendMirror(int axes) {
        if (!designing || focus == null) return;
        mirrorAxes = axes & 0x3;
        send(CarverProtocol.ACTION_MIRROR_SET, focus, new byte[]{(byte) mirrorAxes});
    }

    public static void sendSave() {
        if (!designing || focus == null) return;
        send(CarverProtocol.ACTION_SAVE, focus, new byte[0]);
    }

    public static int mirrorAxes() {
        return mirrorAxes;
    }

    private static void onMirror(byte[] data) {
        if (data != null && data.length > 0) {
            mirrorAxes = data[0] & 0x3;
        }
    }

    /** One observed artisan at their bench, expiring past the announced duration. */
    public record ObservedWork(BlockPos focus, int totalTicks, long startClientTick) {
    }

    private static final java.util.Map<java.util.UUID, ObservedWork> OBSERVED =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Observed work for pose rendering, null for anyone idle. */
    public static ObservedWork observedWork(java.util.UUID playerId) {
        if (playerId == null) return null;
        ObservedWork work = OBSERVED.get(playerId);
        if (work == null) return null;
        if (clientTickCounter - work.startClientTick() > work.totalTicks() + 100L) {
            OBSERVED.remove(playerId);
            return null;
        }
        return work;
    }

    private static void onObservedStart(byte[] data) {
        try (DataInputStream input = stream(data)) {
            java.util.UUID playerId = new java.util.UUID(input.readLong(), input.readLong());
            BlockPos focus = new BlockPos(input.readInt(), input.readInt(), input.readInt());
            int total = input.readInt();
            if (total <= 0) return;
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft != null && minecraft.player != null
                    && playerId.equals(minecraft.player.getUUID())) {
                return;
            }
            OBSERVED.put(playerId, new ObservedWork(focus.immutable(), total, clientTickCounter));
        } catch (IOException | RuntimeException ignored) {
        }
    }

    private static void onObservedEnd(byte[] data) {
        try (DataInputStream input = stream(data)) {
            OBSERVED.remove(new java.util.UUID(input.readLong(), input.readLong()));
        } catch (IOException | RuntimeException ignored) {
        }
    }

    public static void sendCancel() {
        if (!inSession() || focus == null) return;
        BlockPos pos = focus;
        send(CarverProtocol.ACTION_CANCEL, pos, new byte[0]);
        designing = false;
        working = false;
        mirrorAxes = 0;
        clearPendingBox();
        ua.rp.chat.client.microvoxel.MicrovoxelClientState.flushWorkFocus();
        ua.rp.chat.client.microvoxel.MicrovoxelClientState.setWorkFocus(null);
        focus = null;
        draft.clearAll();
        CarverCameraRig.end();
        CarverHologram.clear();
        CarverPerfLog.endSession();
    }

    private static void send(int action, BlockPos pos, byte[] data) {
        if (!ClientPlayNetworking.canSend(CarverActionPayload.TYPE)) return;
        ClientPlayNetworking.send(new CarverActionPayload(
                CarverProtocol.VERSION, action,
                pos.getX(), pos.getY(), pos.getZ(), data));
    }

    private static void overlay(Minecraft minecraft, String text) {
        if (minecraft.player != null) {
            minecraft.gui.setOverlayMessage(Component.literal(text), false);
        }
    }

    private static void trace(String message) {
        try {
            ua.rp.chat.client.EclipseClientMod.LOGGER.info("[CARVER] " + message);
        } catch (RuntimeException ignored) {
        }
    }

    private static DataInputStream stream(byte[] data) {
        return new DataInputStream(new ByteArrayInputStream(data == null ? new byte[0] : data));
    }

    /** Local work clock in client ticks: drives butter-smooth strike animation. */
    public static double smoothWorkTicks() {
        if (!working) return workDoneTicks;
        return smoothSince(workStartClientTick);
    }

    /** Smooth ticks elapsed since a client-tick stamp, for local and observed clocks. */
    public static double smoothSince(long startClientTick) {
        double partial = ua.rp.chat.carver.CarverHologramMotion.renderPartial(lastClientTickNanos);
        return Math.max(0.0, (clientTickCounter - startClientTick) + partial);
    }

    public static void clientTick(Minecraft minecraft) {
        clientTickCounter++;
        lastClientTickNanos = System.nanoTime();
        if (minecraft.player != null && working) {
            // Hands on the workpiece means eyes on it too: plain mouse-look is
            // reverted every tick (camera orbit via right-drag keeps working),
            // otherwise the artisan turns away under the fixed work camera.
            minecraft.player.setYRot(lockYaw);
            minecraft.player.setXRot(lockPitch);
        }
        if (minecraft.player == null || minecraft.level == null) {
            if (inSession()) {
                designing = false;
                working = false;
                ua.rp.chat.client.microvoxel.MicrovoxelClientState.flushWorkFocus();
                ua.rp.chat.client.microvoxel.MicrovoxelClientState.setWorkFocus(null);
                focus = null;
                draft.clearAll();
                CarverCameraRig.end();
        CarverHologram.clear();
        CarverPerfLog.endSession();
            }
            return;
        }
        long tickStart = System.nanoTime();
        CarverCameraRig.tick(minecraft);
        CarverHologram.tick(minecraft);
        CarverWorkFx.tick(minecraft);
        CarverPerfLog.tick(System.nanoTime() - tickStart);
    }
}
