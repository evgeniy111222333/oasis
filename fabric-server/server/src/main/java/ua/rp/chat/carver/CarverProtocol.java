package ua.rp.chat.carver;

/**
 * Wire contract of the Carver drafting system. Two Fabric channels share one
 * version so mismatched clients fail closed with a readable message:
 * {@code rpchat:carver_action} (client to server) and {@code rpchat:carver}
 * (server to client).
 */
public final class CarverProtocol {
    public static final int VERSION = 1;
    public static final String SYNC_CHANNEL = "rpchat:carver";
    public static final String ACTION_CHANNEL = "rpchat:carver_action";

    public static final int ACTION_STROKE_ADD = 2;
    public static final int ACTION_STROKE_ERASE = 3;
    public static final int ACTION_CLEAR_DRAFT = 5;
    public static final int ACTION_APPROVE = 6;
    public static final int ACTION_CANCEL = 7;
    /** Whole-box mask add, validated exactly like a stroke with a larger cell cap. */
    public static final int ACTION_BOX_ADD = 8;
    /** Whole-box mask erase, validated exactly like a stroke with a larger cell cap. */
    public static final int ACTION_BOX_ERASE = 9;
    /** Restores the previous draft snapshot from the session undo stack. */
    public static final int ACTION_UNDO = 10;
    /** Reapplies the snapshot undone last. */
    public static final int ACTION_REDO = 11;
    /** Sets the mirror axes bitmask (bit 0 mirrors X, bit 1 mirrors Z). */
    public static final int ACTION_MIRROR_SET = 12;
    /** Writes the draft into the held scroll as a tradable NBT blueprint. */
    public static final int ACTION_SAVE = 13;
    /** The artisan walks up tight to the workpiece itself before approving. */
    public static final int ACTION_AUTOWALK = 14;

    public static final int EVENT_SESSION_OPEN = 1;
    public static final int EVENT_DRAFT_STATE = 2;
    public static final int EVENT_ESTIMATE = 3;
    public static final int EVENT_WORK_START = 4;
    public static final int EVENT_WORK_PROGRESS = 5;
    public static final int EVENT_WORK_DONE = 6;
    public static final int EVENT_SESSION_CLOSE = 7;
    /** Authoritative mirror axes bitmask for the editor toggles. */
    public static final int EVENT_MIRROR_STATE = 8;
    /** A nearby artisan started work: carries their id, focus and total ticks. */
    public static final int EVENT_WORK_OBSERVED_START = 9;
    /** A nearby artisan stopped work: carries their id. */
    public static final int EVENT_WORK_OBSERVED_END = 10;

    private CarverProtocol() {
    }

    /**
     * Design entry travels through the scroll right-click hook, never through this
     * channel, so action ids start at {@link #ACTION_STROKE_ADD}.
     */
    public static boolean isAction(int action) {
        return action >= ACTION_STROKE_ADD && action <= ACTION_AUTOWALK;
    }

    public static boolean isEvent(int event) {
        return event >= EVENT_SESSION_OPEN && event <= EVENT_WORK_OBSERVED_END;
    }
}
