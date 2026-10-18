package ua.rp.chat.modeleditor;

import com.google.gson.JsonObject;

public record ModelTransform(float x, float y, float z, float rx, float ry, float rz,
                             float sx, float sy, float sz) {
    public ModelTransform {
        bound(x, -64, 64, "x"); bound(y, -64, 64, "y"); bound(z, -64, 64, "z");
        bound(rx, -360, 360, "rx"); bound(ry, -360, 360, "ry"); bound(rz, -360, 360, "rz");
        bound(sx, .05f, 4, "sx"); bound(sy, .05f, 4, "sy"); bound(sz, .05f, 4, "sz");
    }
    private static void bound(float value, float minimum, float maximum, String name) {
        if (!Float.isFinite(value) || value < minimum || value > maximum)
            throw new IllegalArgumentException(name + " must be finite and in " + minimum + ".." + maximum);
    }
    public static ModelTransform identity() { return new ModelTransform(0, 0, 0, 0, 0, 0, 1, 1, 1); }
    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("x", x); json.addProperty("y", y); json.addProperty("z", z);
        json.addProperty("rx", rx); json.addProperty("ry", ry); json.addProperty("rz", rz);
        json.addProperty("sx", sx); json.addProperty("sy", sy); json.addProperty("sz", sz);
        return json;
    }
    public static ModelTransform fromJson(JsonObject json) {
        ModelEditorJson.fields(json, "x", "y", "z", "rx", "ry", "rz", "sx", "sy", "sz");
        return new ModelTransform(ModelEditorJson.floating(json, "x"), ModelEditorJson.floating(json, "y"),
                ModelEditorJson.floating(json, "z"), ModelEditorJson.floating(json, "rx"),
                ModelEditorJson.floating(json, "ry"), ModelEditorJson.floating(json, "rz"),
                ModelEditorJson.floating(json, "sx"), ModelEditorJson.floating(json, "sy"),
                ModelEditorJson.floating(json, "sz"));
    }
}
