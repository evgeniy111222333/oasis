package ua.rp.chat.carry;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;

public final class CarrySyncBuilderTest {
    public static void main(String[] args) {
        var builder = new CarrySyncBuilder(42, List.of("minecraft:chest"));
        JsonObject local = new JsonObject(); local.addProperty("player", "local");
        local.addProperty("name", "Кухоль 🏺 \"довга назва\"");
        JsonObject item = new JsonObject(); item.addProperty("custom_data", "а".repeat(60_000));
        CarrySyncBuilder.visual(local, item);
        require(local.has("item") && builder.player(local), "Local preview is lost at a normal visual size");
        local.addProperty("name", "mutated after insertion");
        for (int i = 0; i < 2000; i++) {
            JsonObject record = new JsonObject(); record.addProperty("id", i);
            record.addProperty("name", "Предмет 🏺".repeat(40));
            if (!builder.prop(record)) break;
        }
        for (int i = 0; i < 500; i++) {
            JsonObject record = new JsonObject(); record.addProperty("id", i);
            record.addProperty("name", "Пропозиція 🏺".repeat(20));
            builder.offer(record);
        }
        String json = builder.json();
        require(CarryWire.utf8(json, CarrySyncPayload.MAX_BYTES).length <= CarrySyncPayload.MAX_BYTES,
                "UTF-8 snapshot exceeds the actual wire budget");
        var snapshot = JsonParser.parseString(json).getAsJsonObject();
        require(snapshot.getAsJsonArray("players").get(0).getAsJsonObject().get("name").getAsString().startsWith("Кухоль"),
                "Late record changes invalidate the counted snapshot");
        JsonObject enormous = new JsonObject(); enormous.addProperty("custom_data", "а".repeat(100_000));
        JsonObject bounded = new JsonObject(); CarrySyncBuilder.visual(bounded, enormous);
        require(!bounded.has("item"), "Oversized public mesh must use its native display fallback");
        var alreadyFull = new CarrySyncBuilder(1, List.of());
        JsonObject absurd = new JsonObject(); absurd.addProperty("name", "x".repeat(CarrySyncPayload.MAX_BYTES));
        require(!alreadyFull.prop(absurd) && !alreadyFull.player(absurd), "Oversized record crashes or overfills snapshot");
        String unicode = "🏺".repeat(200);
        String shortened = CarrySyncBuilder.name(unicode);
        require(shortened.codePointCount(0, shortened.length()) == 120, "Name trimming splits surrogate pairs");
        System.out.println("CarrySyncBuilderTest: multibyte/escaped packet budgets, local priority, immutable records and visual fallback passed");
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
