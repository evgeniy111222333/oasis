package ua.rp.chat.modeleditor;

import com.google.gson.JsonObject;

public record ModelPlacement(String item, ModelPose context, String model,
                             ModelAttachment attachment, ModelTransform transform) {
    public ModelPlacement {
        validateId(item);
        model = model == null ? "" : model;
        if (!model.isEmpty()) validateId(model);
        if (context == null || attachment == null || transform == null)
            throw new IllegalArgumentException("Placement context, attachment and transform are required");
    }
    public static void validateId(String id) {
        if (id == null || id.length() > 128 || !id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))
            throw new IllegalArgumentException("Expected namespaced item ID");
    }
    public static ModelPlacement defaults(String item, ModelPose context) {
        boolean bag = item != null && (item.equals("eclipseserver:carver_bag")
                || item.equals("eclipseserver:carver_bag_no_point") || item.equals("eclipseserver:carver_bag_empty"));
        ModelTransform transform = context == ModelPose.WORN && bag
                ? new ModelTransform(0, 5.25f, 3, 180, 0, 0, .79f, .79f, .79f) : ModelTransform.identity();
        return new ModelPlacement(item, context, "", ModelAttachment.BACK, transform);
    }
    public String key() { return item + "|" + context.name(); }
    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("item", item); json.addProperty("context", context.name());
        json.addProperty("model", model); json.addProperty("attachment", attachment.name());
        json.add("transform", transform.toJson());
        return json;
    }
    public static ModelPlacement fromJson(JsonObject json) {
        ModelEditorJson.fields(json, "item", "context", "model", "attachment", "transform");
        return new ModelPlacement(ModelEditorJson.string(json, "item"),
                ModelPose.valueOf(ModelEditorJson.string(json, "context")), ModelEditorJson.string(json, "model"),
                ModelAttachment.valueOf(ModelEditorJson.string(json, "attachment")),
                ModelTransform.fromJson(ModelEditorJson.object(json, "transform")));
    }
}
