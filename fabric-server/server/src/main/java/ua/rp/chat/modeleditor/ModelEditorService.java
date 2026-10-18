package ua.rp.chat.modeleditor;

import com.google.gson.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.function.Predicate;

/** Pure authoritative transaction boundary; the runtime adapter supplies authentication and registry checks. */
public final class ModelEditorService {
    public static final int MAX_REQUEST_BYTES = 30_000;
    private final ModelEditorStore store;
    private final Predicate<String> registeredItem;
    private ModelEditorDocument document = ModelEditorDocument.empty();
    private String loadWarning = "";

    public ModelEditorService(ModelEditorStore store, Predicate<String> registeredItem) {
        this.store = store;
        this.registeredItem = registeredItem;
        try {
            ModelEditorDocument loaded = store.load(this::validate);
            document = loaded;
        } catch (IOException | IllegalArgumentException invalid) {
            loadWarning = "Настройки моделей не загружены; файлы сохранены: " + invalid.getMessage();
        }
    }
    public synchronized ModelEditorDocument document() { return document; }
    public String loadWarning() { return loadWarning; }
    public record Result(boolean success, boolean changed, String type, String message, ModelEditorDocument document) {}

    public synchronized Result handle(boolean authenticated, boolean administrator, String request) {
        // This check precedes parsing and all filesystem access, including open and reload.
        if (!authenticated || !administrator)
            return error("Редактор доступен только авторизованным администраторам.");
        try {
            JsonObject json = ModelEditorJson.parse(request, MAX_REQUEST_BYTES);
            String action = ModelEditorJson.string(json, "action");
            return switch (action) {
                case "open" -> {
                    ModelEditorJson.fields(json, "action");
                    yield new Result(true, false, "open", loadWarning, document);
                }
                case "save" -> {
                    ModelEditorJson.fields(json, "action", "revision", "entries");
                    if (ModelEditorJson.integer(json, "revision") != document.revision())
                        yield conflict();
                    JsonArray array = ModelEditorJson.array(json, "entries");
                    if (array.isEmpty() || array.size() > ModelEditorDocument.MAX_BATCH_ENTRIES)
                        throw new IllegalArgumentException("Сохранение содержит от 1 до 128 изменений.");
                    ArrayList<ModelPlacement> changes = new ArrayList<>();
                    for (JsonElement element : array) {
                        if (!element.isJsonObject()) throw new IllegalArgumentException("Ожидается объект размещения.");
                        ModelPlacement placement = ModelPlacement.fromJson(element.getAsJsonObject());
                        validate(placement);
                        changes.add(placement);
                    }
                    yield persist(document.apply(changes), "Настройки моделей сохранены для всех игроков.");
                }
                case "reset" -> {
                    ModelEditorJson.fields(json, "action", "revision", "item", "context");
                    if (ModelEditorJson.integer(json, "revision") != document.revision()) yield conflict();
                    ModelPlacement placement = ModelPlacement.defaults(ModelEditorJson.string(json, "item"),
                            ModelPose.valueOf(ModelEditorJson.string(json, "context")));
                    validate(placement);
                    yield persist(document.remove(placement.item(), placement.context()), "Размещение сброшено.");
                }
                case "reload" -> {
                    ModelEditorJson.fields(json, "action");
                    ModelEditorDocument loaded = store.load(this::validate);
                    long base = Math.max(document.revision(), loaded.revision());
                    if (base == Long.MAX_VALUE) throw new IllegalArgumentException("Счётчик версий исчерпан.");
                    // Reload must invalidate every draft, even if a disk backup has an older revision.
                    yield persist(new ModelEditorDocument(base + 1, loaded.entries()), "Настройки моделей перезагружены.");
                }
                default -> error("Неизвестное действие редактора моделей.");
            };
        } catch (IllegalArgumentException invalid) {
            return error("Некорректные настройки: " + invalid.getMessage());
        } catch (IOException failure) {
            return error("Не удалось сохранить или загрузить настройки; текущие настройки сохранены.");
        }
    }
    private Result persist(ModelEditorDocument next, String message) throws IOException {
        store.save(next);
        document = next;
        loadWarning = "";
        return new Result(true, true, "saved", message, next);
    }
    private Result conflict() { return error("Настройки изменены другим администратором. Получена новая версия; ваш черновик сохранён."); }
    private Result error(String message) { return new Result(false, false, "error", message, document); }
    private void validate(ModelEditorDocument loaded) { loaded.entries().values().forEach(this::validate); }
    private void validate(ModelPlacement placement) {
        if (!registeredItem.test(placement.item())) throw new IllegalArgumentException("Неизвестный предмет: " + placement.item());
        if (!placement.model().isEmpty() && !registeredItem.test(placement.model()))
            throw new IllegalArgumentException("Неизвестная модель предмета: " + placement.model());
    }
}
