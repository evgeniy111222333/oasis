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
    /**
     * Last authoritative draft echoed by the server. {@link #draft} carries the optimistic
     * local edits so a released stroke disappears the same frame; if the server never echoes
     * a throttled or rejected stroke inside {@link #OPTIMISTIC_RECONCILE_TICKS}, the working
     * draft snaps back to this snapshot so the artisan's client never lies about the result.
     */
    private static final DraftMask serverDraft = new DraftMask();
    /** Client tick of the newest optimistic edit still awaiting a server echo; -1 when idle. */
    private static long optimisticEditTick = -1L;
    /** Reconcile window in client ticks; generous enough to ride out a slow link. */
    private static final long OPTIMISTIC_RECONCILE_TICKS = 40L;
    /**
     * Post-work finale: after the last blow the artisan steps back and inspects the finished
     * piece, then settles. Runs for {@link #FINISH_TICKS} client ticks and is local-only.
     */
    private static final int FINISH_TICKS = 64;
    private static int finishTicks = -1;
    private static BlockPos finishFocus;
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
    private static final ObservedDraftBoard OBSERVED_DRAFTS = new ObservedDraftBoard();
    private static final long OBSERVED_DRAFT_TTL_TICKS = 200L;
    /**
     * Tick-fresh strike plan for the local artisan, solved once per client tick so
     * the render thread never pays the centroid scan. Null outside sessions.
     */
    private static ua.rp.chat.carver.CarverStrikeAlign.StrikePlan cachedPlan;
    private static int cachedPlanCells = -1;
    /**
     * Frozen per work session: lead-foot side from the lateral contact angle and
     * the left-minus-right floor step in blocks. Sampled once at work start, so
     * the feet never re-decide mid-animation; observers (contact only) fall back
     * to live lateral with a flat floor.
     */
    private static ua.rp.chat.carver.CarverWorkStance.Side workSide =
            ua.rp.chat.carver.CarverWorkStance.Side.LEFT_LEAD;
    private static double workFloorDh;

    /** Frozen lead-foot side for the local work pose. */
    public static ua.rp.chat.carver.CarverWorkStance.Side workSide() {
        return workSide;
    }

    /** Frozen floor step (left minus right, blocks) for the local work pose. */
    public static double workFloorDh() {
        return workFloorDh;
    }
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

    /** True while the local post-work inspection finale plays. */
    public static boolean finishing() {
        return finishTicks >= 0;
    }

    /** Finale progress 0..1 for the inspect pose. */
    public static double finishProgress() {
        if (finishTicks < 0) return 0.0;
        return Math.min(1.0, finishTicks / (double) FINISH_TICKS);
    }

    /** Socket of the just-finished piece, for the inspect gaze. */
    public static BlockPos finishFocus() {
        return finishFocus;
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
        return isPaintable(focus, cell);
    }

    public static boolean isPaintable(BlockPos at, int cell) {
        if (at == null) return false;
        try {
            ua.rp.chat.client.microvoxel.MicrovoxelClientState.CachedVolume cached =
                    ua.rp.chat.client.microvoxel.MicrovoxelClientState.get(at);
            if (cached == null || cached.volume == null) return true;
            if (cell < 0 || cell >= ua.rp.chat.microvoxel.MicrovoxelVolume.CELL_COUNT) {
                return false;
            }
            return cached.volume.occupied(cell);
        } catch (RuntimeException unreadable) {
            return true;
        }
    }

    public static void putObservedDraft(BlockPos pos, DraftMask mask) {
        if (mask == null || mask.isEmpty()) {
            OBSERVED_DRAFTS.remove(pos);
            return;
        }
        OBSERVED_DRAFTS.put(pos, mask, clientTickCounter);
    }

    public static void removeObservedDraft(BlockPos pos) {
        OBSERVED_DRAFTS.remove(pos);
    }

    public static java.util.List<ObservedDraftBoard.Entry> observedDrafts() {
        return OBSERVED_DRAFTS.snapshot();
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
        serverDraft.clearAll();
        optimisticEditTick = -1L;
        finishTicks = -1;
        finishFocus = null;
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
        if (designing && pos.equals(focus)) {
            try {
                DraftMask server = DraftMask.decode(data);
                // Authoritative echo: adopt it and drop every pending optimistic edit, so a
                // throttled or capped stroke is reconciled the moment the server answers.
                draft.clearAll();
                draft.orIn(server);
                serverDraft.clearAll();
                serverDraft.orIn(server);
                optimisticEditTick = -1L;
            } catch (IllegalArgumentException ignored) {
            }
            return;
        }
        try {
            putObservedDraft(pos, DraftMask.decode(data));
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
            try {
                if (minecraft.options != null) minecraft.options.keyJump.setDown(false);
            } catch (RuntimeException ignored) {
            }
        }
        sampleWorkStance(minecraft);
        if (minecraft.screen instanceof CarverDesignScreen) {
            minecraft.setScreen(null);
        }
        int plane = ua.rp.chat.carver.CarverWorkAim.faceNormalAxis(draft);
        trace("work started, total=" + workTotalTicks
                + " plane=" + (plane < 0 ? "-" : "XYZ".charAt(plane)));
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
        CarverPerfLog.endWorkSession();
        CarverPerfLog.endSession();
        CarverWorkPoseCache.clear();
        CarverImpactFx.clear();
        CarverLookLock.disengage();
        cachedPlan = null;
        cachedPlanCells = -1;
        ua.rp.chat.client.microvoxel.MicrovoxelClientState.flushWorkFocus();
        ua.rp.chat.client.microvoxel.MicrovoxelClientState.setWorkFocus(null);
        // Start the local inspection finale on the finished socket before the focus clears.
        finishFocus = pos.immutable();
        finishTicks = 0;
        focus = null;
        overlay(Minecraft.getInstance(), "Готово: снято " + removed + " вокселей.");
    }

    private static void onClose(BlockPos pos, byte[] data) {
        if (!inSession() || !pos.equals(focus)) {
            removeObservedDraft(pos);
            return;
        }
        designing = false;
        working = false;
        mirrorAxes = 0;
        clearPendingBox();
        ua.rp.chat.client.microvoxel.MicrovoxelClientState.flushWorkFocus();
        ua.rp.chat.client.microvoxel.MicrovoxelClientState.setWorkFocus(null);
        focus = null;
        draft.clearAll();
        serverDraft.clearAll();
        optimisticEditTick = -1L;
        finishTicks = -1;
        finishFocus = null;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof CarverDesignScreen) {
            minecraft.setScreen(null);
        }
        CarverCameraRig.end();
        CarverHologram.clear();
        CarverPerfLog.endSession();
        CarverWorkPoseCache.clear();
        CarverImpactFx.clear();
        CarverLookLock.disengage();
        cachedPlan = null;
        cachedPlanCells = -1;
        int reason = data != null && data.length > 0 ? data[0] & 0xFF : 0;
        if (reason != 0) {
            overlay(minecraft, "Чертёж закрыт.");
        }
    }

    public static void sendStroke(boolean add, DraftMask stroke) {
        if (!designing || focus == null) return;
        applyOptimisticDraft(add, stroke);
        send(add ? CarverProtocol.ACTION_STROKE_ADD : CarverProtocol.ACTION_STROKE_ERASE,
                focus, stroke.encode());
    }

    public static void sendClear() {
        if (!designing || focus == null) return;
        // Clearing is instant locally; the echo confirms it against the authoritative mask.
        draft.clearAll();
        optimisticEditTick = clientTickCounter;
        send(CarverProtocol.ACTION_CLEAR_DRAFT, focus, new byte[0]);
    }

    public static void sendBox(boolean add, DraftMask box) {
        if (!designing || focus == null || box.isEmpty()) return;
        applyOptimisticDraft(add, box);
        send(add ? CarverProtocol.ACTION_BOX_ADD : CarverProtocol.ACTION_BOX_ERASE,
                focus, box.encode());
    }

    /**
     * Applies one released stroke/box to the working draft immediately, mirroring the server's
     * own mirror expansion so the optimistic hide matches the authoritative result. The edit is
     * dropped (snapped back to {@link #serverDraft}) if no server echo arrives in time.
     */
    private static void applyOptimisticDraft(boolean add, DraftMask cells) {
        if (cells == null || cells.isEmpty()) return;
        DraftMask twin = cells.copy();
        expandMirroredLocally(twin, mirrorAxes);
        if (add) {
            draft.orIn(twin);
        } else {
            draft.andNot(twin);
        }
        optimisticEditTick = clientTickCounter;
    }

    /**
     * Client mirror of {@code DraftSession.expandMirrored}: unions every mirrored twin of the
     * mask into itself so a mirror-mode stroke hides both halves the moment it is released,
     * instead of waiting for the server to expand and echo the mask back.
     */
    private static void expandMirroredLocally(DraftMask mask, int axes) {
        int mirrorX = 1;
        int mirrorZ = 2;
        axes &= mirrorX | mirrorZ;
        if (axes == 0) return;
        DraftMask twins = new DraftMask();
        for (int cell : mask.cells()) {
            if ((axes & mirrorX) != 0) twins.set(mirrorCellLocally(cell, mirrorX));
            if ((axes & mirrorZ) != 0) twins.set(mirrorCellLocally(cell, mirrorZ));
            if (axes == (mirrorX | mirrorZ)) twins.set(mirrorCellLocally(cell, mirrorX | mirrorZ));
        }
        mask.orIn(twins);
    }

    /** Mirrors one cell around the volume centre exactly like {@code DraftSession.mirrorCell}. */
    private static int mirrorCellLocally(int cell, int axes) {
        int x = DraftMask.x(cell);
        int y = DraftMask.y(cell);
        int z = DraftMask.z(cell);
        if ((axes & 1) != 0) x = 15 - x;
        if ((axes & 2) != 0) z = 15 - z;
        return DraftMask.index(x, y, z);
    }

    /**
     * Snaps the working draft back to the last server-confirmed mask when the optimistic edit
     * was never echoed in time, so a rejected stroke never keeps cells hidden locally.
     */
    private static void reconcileOptimisticDraft() {
        if (optimisticEditTick < 0L) return;
        if (clientTickCounter - optimisticEditTick <= OPTIMISTIC_RECONCILE_TICKS) return;
        draft.clearAll();
        draft.orIn(serverDraft);
        optimisticEditTick = -1L;
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
    public record ObservedWork(BlockPos focus, int totalTicks, long startClientTick, double[] contact,
                               float standYaw, int axis) {
        public ObservedWork(BlockPos focus, int totalTicks, long startClientTick) {
            this(focus, totalTicks, startClientTick, null, 0.0f, -1);
        }

        public ObservedWork(BlockPos focus, int totalTicks, long startClientTick, double[] contact) {
            this(focus, totalTicks, startClientTick, contact, 0.0f, -1);
        }
    }

    private static final java.util.Map<java.util.UUID, ObservedWork> OBSERVED =
            new java.util.concurrent.ConcurrentHashMap<>();

    public static java.util.List<ObservedWork> observedWorks() {
        return java.util.List.copyOf(OBSERVED.values());
    }

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
            double[] contact = null;
            float standYaw = 0.0f;
            int axis = -1;
            try {
                if (input.available() >= 12) {
                    float cx = input.readFloat();
                    float cy = input.readFloat();
                    float cz = input.readFloat();
                    contact = new double[]{focus.getX() + cx / 16.0, focus.getY() + cy / 16.0, focus.getZ() + cz / 16.0};
                }
                // Optional strike-plan tail: stand yaw + face axis. Old servers omit it.
                if (input.available() >= 5) {
                    standYaw = input.readFloat();
                    axis = input.readByte();
                }
            } catch (IOException ignored) {
            }
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft != null && minecraft.player != null
                    && playerId.equals(minecraft.player.getUUID())) {
                return;
            }
            OBSERVED.put(playerId, new ObservedWork(focus.immutable(), total, clientTickCounter, contact,
                    standYaw, axis));
            removeObservedDraft(focus);
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
        serverDraft.clearAll();
        optimisticEditTick = -1L;
        finishTicks = -1;
        finishFocus = null;
        CarverCameraRig.end();
        CarverHologram.clear();
        CarverPerfLog.endSession();
        CarverWorkPoseCache.clear();
        CarverImpactFx.clear();
        CarverLookLock.disengage();
        cachedPlan = null;
        cachedPlanCells = -1;
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

    /** Tick-fresh strike plan for the render thread. Null outside sessions. */
    public static ua.rp.chat.carver.CarverStrikeAlign.StrikePlan cachedStrikePlan() {
        return cachedPlan;
    }

    /** Current render partial for pose interpolation between tick snapshots. */
    public static float tickPartial() {
        return (float) ua.rp.chat.carver.CarverHologramMotion.renderPartial(lastClientTickNanos);
    }

    /**
     * Freezes the work stance once per session: lead-foot side from the lateral
     * contact angle (strict XZ projection, top faces included) and the floor step
     * under the feet from exact collision-shape tops (slabs, stairs, paths and
     * snow included, not integer block coords). One world probe at work start:
     * zero per-frame cost, zero mid-animation re-decisions.
     */
    private static void sampleWorkStance(Minecraft minecraft) {
        workSide = ua.rp.chat.carver.CarverWorkStance.Side.LEFT_LEAD;
        workFloorDh = 0.0;
        try {
            if (minecraft == null || minecraft.player == null || minecraft.level == null
                    || focus == null) return;
            double px = minecraft.player.getX();
            double py = minecraft.player.getY();
            double pz = minecraft.player.getZ();
            float yaw = minecraft.player.getYRot();
            ua.rp.chat.carver.CarverStrikeAlign.StrikePlan plan = null;
            try {
                plan = ua.rp.chat.carver.CarverStrikeAlign.solve(
                        focus.getX(), focus.getY(), focus.getZ(), draft.cells(), px, py, pz);
            } catch (RuntimeException unreadable) {
                plan = null;
            }
            double cx = plan == null ? focus.getX() + 0.5 : plan.contactX();
            double cz = plan == null ? focus.getZ() + 0.5 : plan.contactZ();
            workSide = ua.rp.chat.carver.CarverWorkStance.sideFor(
                    ua.rp.chat.carver.CarverWorkStance.lateralDeg(cx, cz, px, pz, yaw));
            double rad = Math.toRadians(yaw);
            double fx = -Math.sin(rad);
            double fz = Math.cos(rad);
            double lx = fz;
            double lz = -fx;
            double footLx = px + fx * 0.12 + lx * 0.22;
            double footLz = pz + fz * 0.12 + lz * 0.22;
            double footRx = px + fx * 0.12 - lx * 0.22;
            double footRz = pz + fz * 0.12 - lz * 0.22;
            double topL = footTop(minecraft.level, footLx, py, footLz);
            double topR = footTop(minecraft.level, footRx, py, footRz);
            if (!Double.isNaN(topL) && !Double.isNaN(topR)) {
                double dh = topL - topR;
                double max = ua.rp.chat.carver.CarverWorkStance.MAX_FLOOR_STEP;
                workFloorDh = Math.max(-max, Math.min(max, dh));
            }
        } catch (RuntimeException unreadable) {
            workSide = ua.rp.chat.carver.CarverWorkStance.Side.LEFT_LEAD;
            workFloorDh = 0.0;
        }
    }

    /** Exact support height under one foot from collision shapes. NaN if none. */
    private static double footTop(net.minecraft.client.multiplayer.ClientLevel level,
                                  double x, double feetY, double z) {
        try {
            int top = (int) Math.floor(feetY + 1.0);
            for (int y = top; y >= top - 4; y--) {
                BlockPos pos = new BlockPos((int) Math.floor(x), y, (int) Math.floor(z));
                net.minecraft.world.level.block.state.BlockState state;
                try {
                    state = level.getBlockState(pos);
                } catch (RuntimeException unreadable) {
                    return Double.NaN;
                }
                net.minecraft.world.phys.shapes.VoxelShape shape =
                        state.getCollisionShape(level, pos);
                if (shape.isEmpty()) continue;
                return pos.getY() + shape.max(net.minecraft.core.Direction.Axis.Y);
            }
        } catch (RuntimeException unreadable) {
            return Double.NaN;
        }
        return Double.NaN;
    }

    /**
     * Refreshes the cached strike plan once per tick while a session lives. The
     * render thread reads the cached value, so the draft centroid scan never runs
     * at frame rate. Re-solves only when the draft cell count changed.
     */
    private static void refreshStrikePlan(Minecraft minecraft) {
        if (!inSession() || focus == null || minecraft == null || minecraft.player == null) {
            cachedPlan = null;
            cachedPlanCells = -1;
            return;
        }
        int cells = draft.count();
        if (cachedPlan != null && cells == cachedPlanCells) return;
        try {
            cachedPlan = ua.rp.chat.carver.CarverStrikeAlign.solve(
                    focus.getX(), focus.getY(), focus.getZ(), draft.cells(),
                    minecraft.player.getX(), minecraft.player.getY(), minecraft.player.getZ());
            cachedPlanCells = cells;
        } catch (RuntimeException unreadable) {
            cachedPlan = null;
            cachedPlanCells = -1;
        }
    }

    /** Smooth ticks elapsed since a client-tick stamp, for local and observed clocks. */
    public static double smoothSince(long startClientTick) {
        double partial = ua.rp.chat.carver.CarverHologramMotion.renderPartial(lastClientTickNanos);
        return Math.max(0.0, (clientTickCounter - startClientTick) + partial);
    }

    public static void clientTick(Minecraft minecraft) {
        clientTickCounter++;
        lastClientTickNanos = System.nanoTime();
        reconcileOptimisticDraft();
        if (finishTicks >= 0) {
            finishTicks++;
            if (finishTicks > FINISH_TICKS) {
                finishTicks = -1;
                finishFocus = null;
            }
        }
        OBSERVED_DRAFTS.expire(clientTickCounter, OBSERVED_DRAFT_TTL_TICKS);
        // Cursor kill-switch: while engaged (walk, settle or work) the mouse can
        // never own the look, so not one turned frame leaks into IK or gaze.
        CarverLookLock.tick(minecraft);
        if (minecraft.player != null && working) {
            // Hands on the workpiece means eyes on it too: plain mouse-look is
            // reverted every tick (camera orbit via right-drag keeps working),
            // otherwise the artisan turns away under the fixed work camera.
            minecraft.player.setYRot(lockYaw);
            minecraft.player.setXRot(lockPitch);
            try {
                if (minecraft.player.isShiftKeyDown()) {
                    minecraft.player.setShiftKeyDown(false);
                }
                if (minecraft.options != null && minecraft.options.keyShift != null
                        && minecraft.options.keyShift.isDown()) {
                    minecraft.options.keyShift.setDown(false);
                }
            } catch (RuntimeException ignored) {
            }
        }
        if (minecraft.player == null || minecraft.level == null) {
            if (inSession()) {
                designing = false;
                working = false;
                ua.rp.chat.client.microvoxel.MicrovoxelClientState.flushWorkFocus();
                ua.rp.chat.client.microvoxel.MicrovoxelClientState.setWorkFocus(null);
                focus = null;
                draft.clearAll();
                serverDraft.clearAll();
                optimisticEditTick = -1L;
                CarverCameraRig.end();
        CarverHologram.clear();
        CarverPerfLog.endSession();
        CarverWorkPoseCache.clear();
        CarverImpactFx.clear();
        CarverLookLock.disengage();
        cachedPlan = null;
        cachedPlanCells = -1;
            }
            return;
        }
        long tickStart = System.nanoTime();
        CarverCameraRig.tick(minecraft);
        CarverHologram.tick(minecraft);
        CarverWorkFx.tick(minecraft);
        refreshStrikePlan(minecraft);
        CarverPerfLog.tick(System.nanoTime() - tickStart);
    }
}
