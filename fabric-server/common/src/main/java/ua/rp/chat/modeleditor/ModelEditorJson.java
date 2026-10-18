package ua.rp.chat.modeleditor;

import com.google.gson.*;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/** Strict, bounded JSON boundary shared by disk and network consumers. */
public final class ModelEditorJson {
    private ModelEditorJson() {}

    public static String bounded(String json, int maximumBytes) {
        if (json == null || json.length() > maximumBytes
                || json.getBytes(StandardCharsets.UTF_8).length > maximumBytes)
            throw new IllegalArgumentException("Model editor JSON exceeds " + maximumBytes + " bytes");
        return json;
    }

    public static JsonObject parse(String json, int maximumBytes) {
        bounded(json, maximumBytes);
        try (JsonReader reader = new JsonReader(new StringReader(json))) {
            reader.setStrictness(Strictness.STRICT);
            JsonElement root = read(reader, 0);
            if (!root.isJsonObject() || reader.peek() != JsonToken.END_DOCUMENT)
                throw new IllegalArgumentException("Expected one JSON object");
            return root.getAsJsonObject();
        } catch (IOException | IllegalStateException error) {
            throw new IllegalArgumentException("Malformed model editor JSON", error);
        }
    }

    private static JsonElement read(JsonReader reader, int depth) throws IOException {
        if (depth > 8) throw new IllegalArgumentException("JSON is too deeply nested");
        return switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                reader.beginObject();
                JsonObject object = new JsonObject();
                while (reader.hasNext()) {
                    String key = reader.nextName();
                    if (object.has(key)) throw new IllegalArgumentException("Duplicate JSON field: " + key);
                    object.add(key, read(reader, depth + 1));
                }
                reader.endObject();
                yield object;
            }
            case BEGIN_ARRAY -> {
                reader.beginArray();
                JsonArray array = new JsonArray();
                while (reader.hasNext()) {
                    if (array.size() >= ModelEditorDocument.MAX_ENTRIES)
                        throw new IllegalArgumentException("Too many model placements");
                    array.add(read(reader, depth + 1));
                }
                reader.endArray();
                yield array;
            }
            case STRING -> new JsonPrimitive(reader.nextString());
            case NUMBER -> {
                try { yield new JsonPrimitive(new BigDecimal(reader.nextString())); }
                catch (NumberFormatException invalid) { throw new IllegalArgumentException("Invalid JSON number", invalid); }
            }
            case BOOLEAN -> new JsonPrimitive(reader.nextBoolean());
            case NULL -> { reader.nextNull(); yield JsonNull.INSTANCE; }
            default -> throw new IllegalArgumentException("Unexpected JSON token");
        };
    }

    public static void fields(JsonObject json, String... fields) {
        if (json == null || !json.keySet().equals(Set.of(fields)))
            throw new IllegalArgumentException("Missing or unknown JSON fields");
    }
    public static String string(JsonObject json, String key) {
        JsonElement value = json.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
            throw new IllegalArgumentException("Expected string: " + key);
        return value.getAsString();
    }
    public static long integer(JsonObject json, String key) {
        try { return number(json, key).longValueExact(); }
        catch (ArithmeticException invalid) { throw new IllegalArgumentException("Expected bounded integer: " + key, invalid); }
    }
    public static float floating(JsonObject json, String key) { return number(json, key).floatValue(); }
    private static BigDecimal number(JsonObject json, String key) {
        JsonElement value = json.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
            throw new IllegalArgumentException("Expected number: " + key);
        try { return value.getAsBigDecimal(); }
        catch (NumberFormatException invalid) { throw new IllegalArgumentException("Invalid number: " + key, invalid); }
    }
    public static JsonObject object(JsonObject json, String key) {
        JsonElement value = json.get(key);
        if (value == null || !value.isJsonObject()) throw new IllegalArgumentException("Expected object: " + key);
        return value.getAsJsonObject();
    }
    public static JsonArray array(JsonObject json, String key) {
        JsonElement value = json.get(key);
        if (value == null || !value.isJsonArray()) throw new IllegalArgumentException("Expected array: " + key);
        return value.getAsJsonArray();
    }
}
