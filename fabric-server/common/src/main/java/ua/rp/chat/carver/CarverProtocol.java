package ua.rp.chat.carver;

/**
 * Wire contract of the Carver drafting system. Two Fabric channels share one
 * version so mismatched clients fail closed with a readable message:
 * {@code rpchat:carver_action} (client to server) and {@code rpchat:carver}
 * (server to client).
 */
public final class CarverProtocol {
    /** v3 binds every drafting action to the per-login session token (anti-replay). */
    public static final int VERSION = 3;
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
    /** Free-hand mark of the additive draft, carrying the chosen material. */
    public static final int ACTION_STROKE_PLACE = 15;
    /** Whole-box mark of the additive draft, carrying the chosen material. */
    public static final int ACTION_BOX_PLACE = 16;

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
        return action >= ACTION_STROKE_ADD && action <= ACTION_BOX_PLACE;
    }

    /**
     * Additive marking payload: {@code [materialLen byte][material UTF-8][DraftMask bytes]}.
     * Kept here so client and server decode the exact same framing.
     */
    public static byte[] encodePlace(String material, byte[] mask) {
        byte[] materialBytes = (material == null ? "" : material)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (materialBytes.length > 255) {
            throw new IllegalArgumentException("material id is too long");
        }
        byte[] out = new byte[1 + materialBytes.length + mask.length];
        out[0] = (byte) materialBytes.length;
        System.arraycopy(materialBytes, 0, out, 1, materialBytes.length);
        System.arraycopy(mask, 0, out, 1 + materialBytes.length, mask.length);
        return out;
    }

    public static String decodePlaceMaterial(byte[] data) {
        int length = placeMaterialLength(data);
        return new String(data, 1, length, java.nio.charset.StandardCharsets.UTF_8);
    }

    public static byte[] decodePlaceMask(byte[] data) {
        int length = placeMaterialLength(data);
        byte[] mask = new byte[data.length - 1 - length];
        System.arraycopy(data, 1 + length, mask, 0, mask.length);
        return mask;
    }

    /** Full draft echo: both masks plus the per-cell placement materials. */
    public record DraftState(DraftMask remove, DraftMask place,
                             java.util.Map<Integer, String> materials) {
    }

    /** {@code [remove 512][place 512][int keyBytesLen][keyBytes]} for the draft echo. */
    public static byte[] encodeDraftState(DraftMask remove, DraftMask place,
                                          java.util.Map<Integer, String> materials) {
        try {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            try (java.io.DataOutputStream output = new java.io.DataOutputStream(bytes)) {
                output.write(remove == null ? new DraftMask().encode() : remove.encode());
                output.write(place == null ? new DraftMask().encode() : place.encode());
                StringBuilder keys = new StringBuilder();
                if (materials != null) {
                    for (java.util.Map.Entry<Integer, String> entry
                            : new java.util.TreeMap<>(materials).entrySet()) {
                        if (entry.getValue() == null || entry.getValue().isEmpty()) continue;
                        if (!keys.isEmpty()) keys.append('\n');
                        keys.append(entry.getKey()).append('=').append(entry.getValue());
                    }
                }
                byte[] keyBytes = keys.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                output.writeInt(keyBytes.length);
                output.write(keyBytes);
            }
            return bytes.toByteArray();
        } catch (java.io.IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public static DraftState decodeDraftState(byte[] data) {
        int masks = DraftMask.PACKED_BYTES * 2;
        if (data == null || data.length < masks + 4) {
            throw new IllegalArgumentException("draft state too short");
        }
        byte[] removeBytes = java.util.Arrays.copyOfRange(data, 0, DraftMask.PACKED_BYTES);
        byte[] placeBytes = java.util.Arrays.copyOfRange(data,
                DraftMask.PACKED_BYTES, masks);
        int keyLength = ((data[masks] & 0xFF) << 24) | ((data[masks + 1] & 0xFF) << 16)
                | ((data[masks + 2] & 0xFF) << 8) | (data[masks + 3] & 0xFF);
        int offset = masks + 4;
        if (keyLength < 0 || data.length < offset + keyLength) {
            throw new IllegalArgumentException("truncated draft state");
        }
        String keys = new String(data, offset, keyLength, java.nio.charset.StandardCharsets.UTF_8);
        DraftMask remove = DraftMask.decode(removeBytes);
        DraftMask place = DraftMask.decode(placeBytes);
        java.util.Map<Integer, String> materials = new java.util.LinkedHashMap<>();
        for (String pair : keys.split("\n")) {
            if (pair.isEmpty()) continue;
            int split = pair.indexOf('=');
            if (split <= 0) continue;
            try {
                int cell = Integer.parseInt(pair.substring(0, split));
                if (cell >= 0 && cell < DraftMask.CELL_COUNT && place.get(cell)) {
                    materials.put(cell, pair.substring(split + 1));
                }
            } catch (NumberFormatException ignored) {
                // skip malformed pair
            }
        }
        return new DraftState(remove, place, materials);
    }

    private static int placeMaterialLength(byte[] data) {
        if (data == null || data.length < 1) {
            throw new IllegalArgumentException("empty place payload");
        }
        int length = data[0] & 0xFF;
        if (data.length < 1 + length) {
            throw new IllegalArgumentException("truncated place payload");
        }
        return length;
    }

    public static boolean isEvent(int event) {
        return event >= EVENT_SESSION_OPEN && event <= EVENT_WORK_OBSERVED_END;
    }
}
