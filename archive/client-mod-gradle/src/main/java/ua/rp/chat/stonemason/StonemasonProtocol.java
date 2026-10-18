package ua.rp.chat.stonemason;

/**
 * Wire contract of the stonemason drafting system. Two Fabric channels share one
 * version so mismatched clients fail closed with a readable message:
 * {@code rpchat:stonemason_action} (client to server) and {@code rpchat:stonemason}
 * (server to client).
 */
public final class StonemasonProtocol {
    public static final int VERSION = 1;
    public static final String SYNC_CHANNEL = "rpchat:stonemason";
    public static final String ACTION_CHANNEL = "rpchat:stonemason_action";

    public static final int ACTION_ENTER_DESIGN = 1;
    public static final int ACTION_STROKE_ADD = 2;
    public static final int ACTION_STROKE_ERASE = 3;
    public static final int ACTION_APPLY_TEMPLATE = 4;
    public static final int ACTION_CLEAR_DRAFT = 5;
    public static final int ACTION_APPROVE = 6;
    public static final int ACTION_CANCEL = 7;

    public static final int EVENT_SESSION_OPEN = 1;
    public static final int EVENT_DRAFT_STATE = 2;
    public static final int EVENT_ESTIMATE = 3;
    public static final int EVENT_WORK_START = 4;
    public static final int EVENT_WORK_PROGRESS = 5;
    public static final int EVENT_WORK_DONE = 6;
    public static final int EVENT_SESSION_CLOSE = 7;

    private StonemasonProtocol() {
    }

    public static boolean isAction(int action) {
        return action >= ACTION_ENTER_DESIGN && action <= ACTION_CANCEL;
    }

    public static boolean isEvent(int event) {
        return event >= EVENT_SESSION_OPEN && event <= EVENT_SESSION_CLOSE;
    }
}
