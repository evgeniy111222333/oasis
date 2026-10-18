package ua.rp.chat.carry;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Collection;

/** A replacement snapshot has an exact UTF-8 budget, including commas and metadata. */
public final class CarrySyncBuilder {
    public static final int VISUAL_BYTES = 131_072;
    private final JsonObject root = new JsonObject();
    private final JsonArray players = new JsonArray(), props = new JsonArray(), offers = new JsonArray();
    private int used, playerBytes;

    public CarrySyncBuilder(long revision, Collection<String> liftable) {
        root.addProperty("type", "state"); root.addProperty("revision", revision);
        JsonArray profiles = new JsonArray();
        liftable.stream().filter(id -> id.length() <= 256).sorted().limit(256).forEach(profiles::add);
        root.add("liftable", profiles);
        root.add("players", players); root.add("props", props); root.add("offers", offers);
        used = bytes(root.toString());
    }
    public boolean player(JsonObject record) {
        int size = bytes(record.toString()) + (players.isEmpty() ? 0 : 1);
        if (size > CarrySyncPayload.MAX_BYTES || playerBytes + size > 350_000 || used + size > 800_000) return false;
        playerBytes += size; used += size; players.add(record.deepCopy()); return true;
    }
    public boolean prop(JsonObject record) { return append(props, record, 800_000); }
    public boolean offer(JsonObject record) { return append(offers, record, CarrySyncPayload.MAX_BYTES); }
    private boolean append(JsonArray array, JsonObject record, int limit) {
        int size = bytes(record.toString()) + (array.isEmpty() ? 0 : 1);
        if (size > CarrySyncPayload.MAX_BYTES || used + size > limit) return false;
        used += size; array.add(record.deepCopy()); return true;
    }
    public String json() {
        String result = root.toString();
        CarryWire.utf8(result, CarrySyncPayload.MAX_BYTES);
        return result;
    }
    /** Remote poses and placed props need metadata only; local preview may fall back to its native display. */
    public static void visual(JsonObject record, JsonElement item) {
        if (bytes(item.toString()) <= VISUAL_BYTES) record.add("item", item);
    }
    public static String name(String value) {
        int points = Math.min(120, value.codePointCount(0, value.length()));
        return value.substring(0, value.offsetByCodePoints(0, points));
    }
    private static int bytes(String value) {
        try { return CarryWire.utf8(value, CarrySyncPayload.MAX_BYTES).length; }
        catch (IllegalArgumentException invalid) { return CarrySyncPayload.MAX_BYTES + 1; }
    }
}
