package ua.rp.chat.client.stonemason;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import ua.rp.chat.client.stonemason.StonemasonSyncPayload;
import ua.rp.chat.stonemason.DraftMask;
import ua.rp.chat.stonemason.StonemasonProtocol;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;

/**
 * Client mirror of one drafting session. Applies authoritative server events to the
 * design screen, the camera rig and the work-effects layer, and routes local input
 * back through the action channel. All methods run on the client thread.
 */
public final class StonemasonClientState {
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

    private StonemasonClientState() {
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

    public static void handle(StonemasonSyncPayload payload) {
        if (payload.protocolVersion() != StonemasonProtocol.VERSION) {
            versionMismatches++;
            return;
        }
        BlockPos pos = new BlockPos(payload.x(), payload.y(), payload.z());
        switch (payload.event()) {
            case StonemasonProtocol.EVENT_SESSION_OPEN -> onOpen(pos, payload.data());
            case StonemasonProtocol.EVENT_DRAFT_STATE -> onDraft(pos, payload.data());
            case StonemasonProtocol.EVENT_ESTIMATE -> onEstimate(pos, payload.data());
            case StonemasonProtocol.EVENT_WORK_START -> onWorkStart(pos, payload.data());
            case StonemasonProtocol.EVENT_WORK_PROGRESS -> onWorkProgress(pos, payload.data());
            case StonemasonProtocol.EVENT_WORK_DONE -> onWorkDone(pos, payload.data());
            case StonemasonProtocol.EVENT_SESSION_CLOSE -> onClose(pos, payload.data());
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
        StonemasonCameraRig.beginDesign(pos);
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            minecraft.setScreen(new StonemasonDesignScreen());
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
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof StonemasonDesignScreen) {
            minecraft.setScreen(null);
        }
        StonemasonCameraRig.beginWork(pos);
        overlay(minecraft, "Робота почалася. Не рухайтеся, майстре.");
    }

    private static void onWorkProgress(BlockPos pos, byte[] data) {
        if (!working || !pos.equals(focus)) return;
        try (DataInputStream input = stream(data)) {
            workDoneTicks = input.readInt();
            workTotalTicks = input.readInt();
        } catch (IOException | RuntimeException ignored) {
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
        StonemasonWorkFx.burst(focus);
        StonemasonCameraRig.end();
        focus = null;
        overlay(Minecraft.getInstance(), "Готово: знято " + removed + " вокселів.");
    }

    private static void onClose(BlockPos pos, byte[] data) {
        designing = false;
        working = false;
        focus = null;
        draft.clearAll();
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof StonemasonDesignScreen) {
            minecraft.setScreen(null);
        }
        StonemasonCameraRig.end();
        int reason = data != null && data.length > 0 ? data[0] & 0xFF : 0;
        if (reason != 0) {
            overlay(minecraft, "Креслення закрито.");
        }
    }

    public static void sendStroke(boolean add, DraftMask stroke) {
        if (!designing || focus == null) return;
        send(add ? StonemasonProtocol.ACTION_STROKE_ADD : StonemasonProtocol.ACTION_STROKE_ERASE,
                focus, stroke.encode());
    }

    public static void sendTemplate(int templateId) {
        if (!designing || focus == null) return;
        send(StonemasonProtocol.ACTION_APPLY_TEMPLATE, focus, new byte[]{(byte) templateId});
    }

    public static void sendClear() {
        if (!designing || focus == null) return;
        send(StonemasonProtocol.ACTION_CLEAR_DRAFT, focus, new byte[0]);
    }

    public static void sendApprove() {
        if (!designing || focus == null) return;
        send(StonemasonProtocol.ACTION_APPROVE, focus, new byte[0]);
    }

    public static void sendCancel() {
        if (!inSession() || focus == null) return;
        BlockPos pos = focus;
        send(StonemasonProtocol.ACTION_CANCEL, pos, new byte[0]);
        designing = false;
        working = false;
        focus = null;
        draft.clearAll();
        StonemasonCameraRig.end();
    }

    private static void send(int action, BlockPos pos, byte[] data) {
        if (!ClientPlayNetworking.canSend(StonemasonActionPayload.TYPE)) return;
        ClientPlayNetworking.send(new StonemasonActionPayload(
                StonemasonProtocol.VERSION, action,
                pos.getX(), pos.getY(), pos.getZ(), data));
    }

    private static void overlay(Minecraft minecraft, String text) {
        if (minecraft.player != null) {
            minecraft.gui.setOverlayMessage(Component.literal(text), false);
        }
    }

    private static DataInputStream stream(byte[] data) {
        return new DataInputStream(new ByteArrayInputStream(data == null ? new byte[0] : data));
    }

    public static void clientTick(Minecraft minecraft) {
        if (minecraft.player == null || minecraft.level == null) {
            if (inSession()) {
                designing = false;
                working = false;
                focus = null;
                draft.clearAll();
                StonemasonCameraRig.end();
            }
            return;
        }
        StonemasonCameraRig.tick(minecraft);
        StonemasonWorkFx.tick(minecraft);
    }
}
