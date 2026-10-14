package ua.rp.chat.modeleditor;

import com.google.gson.*;
import java.util.*;

public record ModelEditorDocument(long revision, Map<String, ModelPlacement> entries) {
    public static final int VERSION = 1;
    public static final int MAX_ENTRIES = 128;
    public static final int MAX_BATCH_ENTRIES = MAX_ENTRIES;
    public static final int MAX_JSON_BYTES = 28_000;

    public ModelEditorDocument {
        if (revision < 0 || entries == null || entries.size() > MAX_ENTRIES)
            throw new IllegalArgumentException("Invalid model placement revision or entry count");
        TreeMap<String, ModelPlacement> copy = new TreeMap<>();
        entries.forEach((key, value) -> {
            if (value == null || !value.key().equals(key)) throw new IllegalArgumentException("Invalid placement key");
            copy.put(key, value);
        });
        entries = Collections.unmodifiableMap(copy);
        ModelEditorJson.bounded(json(revision, entries).toString(), MAX_JSON_BYTES);
    }
    public static ModelEditorDocument empty() { return new ModelEditorDocument(0, Map.of()); }
    public ModelPlacement get(String item, ModelPose context) {
        ModelPlacement fallback = ModelPlacement.defaults(item, context);
        return entries.getOrDefault(fallback.key(), fallback);
    }
    public String encode() { return json(revision, entries).toString(); }
    private static JsonObject json(long revision, Map<String, ModelPlacement> entries) {
        JsonObject root = new JsonObject();
        root.addProperty("version", VERSION); root.addProperty("revision", revision);
        JsonArray array = new JsonArray();
        entries.values().forEach(value -> array.add(value.toJson()));
        root.add("entries", array);
        return root;
    }
    public static ModelEditorDocument decode(String text) {
        JsonObject root = ModelEditorJson.parse(text, MAX_JSON_BYTES);
        ModelEditorJson.fields(root, "version", "revision", "entries");
        if (ModelEditorJson.integer(root, "version") != VERSION)
            throw new IllegalArgumentException("Unsupported model placement version");
        Map<String, ModelPlacement> entries = new HashMap<>();
        for (JsonElement element : ModelEditorJson.array(root, "entries")) {
            if (!element.isJsonObject()) throw new IllegalArgumentException("Expected placement object");
            ModelPlacement placement = ModelPlacement.fromJson(element.getAsJsonObject());
            if (entries.putIfAbsent(placement.key(), placement) != null)
                throw new IllegalArgumentException("Duplicate model placement");
        }
        return new ModelEditorDocument(ModelEditorJson.integer(root, "revision"), entries);
    }
    public ModelEditorDocument apply(List<ModelPlacement> changes) {
        if (changes == null || changes.size() > MAX_BATCH_ENTRIES)
            throw new IllegalArgumentException("Too many changes in one save");
        Map<String, ModelPlacement> next = new HashMap<>(entries);
        Set<String> changed = new HashSet<>();
        for (ModelPlacement placement : changes) {
            if (placement == null || !changed.add(placement.key()))
                throw new IllegalArgumentException("Null or duplicate placement change");
            if (placement.equals(ModelPlacement.defaults(placement.item(), placement.context())))
                next.remove(placement.key());
            else next.put(placement.key(), placement);
        }
        return new ModelEditorDocument(nextRevision(), next);
    }
    public ModelEditorDocument remove(String item, ModelPose context) {
        Map<String, ModelPlacement> next = new HashMap<>(entries);
        next.remove(ModelPlacement.defaults(item, context).key());
        return new ModelEditorDocument(nextRevision(), next);
    }
    private long nextRevision() {
        if (revision == Long.MAX_VALUE) throw new IllegalArgumentException("Model editor revision exhausted");
        return revision + 1;
    }
}
