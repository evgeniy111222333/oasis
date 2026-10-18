package ua.rp.chat.modeleditor;

import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Real domain, persistence and authority regression checks; no Minecraft world required. */
public final class ModelEditorTest {
    public static void main(String[] args) throws Exception {
        transformBounds();
        documentContract();
        resetCapacity();
        persistenceAndAuthority();
        System.out.println("ModelEditorTest passed: validation, revision, persistence, authority and recovery");
    }

    private static void resetCapacity() {
        java.util.HashMap<String, ModelPlacement> overrides = new java.util.HashMap<>();
        for (int index = 0; index < 128; index++) {
            ModelPlacement placement = shifted("t:" + index, ModelPose.GUI);
            overrides.put(placement.key(), placement);
        }
        ModelEditorDocument full = new ModelEditorDocument(7, overrides);
        ModelEditorDocument reset = full.apply(List.of(ModelPlacement.defaults("t:0", ModelPose.GUI)));
        require(reset.entries().size() == 127 && !reset.entries().containsKey("t:0|GUI"),
                "Saving factory defaults must remove the override and reclaim capacity");
        require(reset.revision() == 8 && full.entries().size() == 128,
                "Factory reset advances one revision without mutating the original document");
        ModelPlacement added = shifted("t:new", ModelPose.GUI);
        rejects(() -> full.apply(List.of(added)));
        ModelEditorDocument replaced = full.apply(List.of(added, ModelPlacement.defaults("t:0", ModelPose.GUI)));
        require(replaced.entries().size() == 128 && replaced.revision() == 8
                        && replaced.get("t:new", ModelPose.GUI).equals(added),
                "An atomic reset and replacement must work at full capacity in either batch order");
        ModelPlacement bagIdentity = new ModelPlacement("eclipseserver:carver_bag", ModelPose.WORN, "",
                ModelAttachment.BACK, ModelTransform.identity());
        ModelEditorDocument explicit = ModelEditorDocument.empty().apply(List.of(bagIdentity));
        require(explicit.entries().size() == 1 && explicit.get(bagIdentity.item(), ModelPose.WORN).equals(bagIdentity),
                "Explicit worn bag identity must survive normalization because its factory transform is different");
        require(explicit.apply(List.of(ModelPlacement.defaults(bagIdentity.item(), ModelPose.WORN))).entries().isEmpty(),
                "Worn bag factory reset must remove an explicit identity override");
    }

    private static void transformBounds() {
        for (float invalid : new float[]{Float.NaN, Float.POSITIVE_INFINITY, -65, 65})
            rejects(() -> new ModelTransform(invalid, 0, 0, 0, 0, 0, 1, 1, 1));
        rejects(() -> new ModelTransform(0, 0, 0, 361, 0, 0, 1, 1, 1));
        rejects(() -> new ModelTransform(0, 0, 0, 0, 0, 0, .049f, 1, 1));
        rejects(() -> new ModelTransform(0, 0, 0, 0, 0, 0, 1, 4.01f, 1));
        ModelTransform edge = new ModelTransform(-64, 64, 0, -360, 360, 0, .05f, 4, 1);
        require(ModelTransform.fromJson(edge.toJson()).equals(edge), "Boundary transform must round-trip");
        JsonObject malformed = edge.toJson();
        malformed.addProperty("x", "12");
        rejects(() -> ModelTransform.fromJson(malformed));
        JsonObject missing = edge.toJson();
        missing.remove("sz");
        rejects(() -> ModelTransform.fromJson(missing));
    }

    private static void documentContract() {
        ModelPlacement placement = new ModelPlacement("minecraft:stick", ModelPose.GUI,
                "minecraft:stone", ModelAttachment.BACK, ModelTransform.identity());
        rejects(() -> ModelPlacement.defaults("Stick", ModelPose.GUI));
        rejects(() -> new ModelPlacement("minecraft:stick", ModelPose.GUI, "../bad", ModelAttachment.BACK,
                ModelTransform.identity()));
        ModelEditorDocument initial = ModelEditorDocument.empty();
        ModelEditorDocument changed = initial.apply(List.of(placement));
        require(initial.entries().isEmpty() && initial.revision() == 0, "Apply must preserve original document");
        require(changed.revision() == 1 && changed.get("minecraft:stick", ModelPose.GUI).equals(placement),
                "Apply must advance revision and preserve data");
        require(ModelEditorDocument.decode(changed.encode()).equals(changed), "Document must round-trip");
        java.util.HashMap<String, ModelPlacement> mutable = new java.util.HashMap<>(changed.entries());
        ModelEditorDocument copied = new ModelEditorDocument(1, mutable);
        mutable.clear();
        require(copied.entries().size() == 1, "Construction must copy caller-owned mutable maps");
        rejects(() -> changed.entries().clear());
        rejects(() -> new ModelEditorDocument(0, Map.of("wrong", placement)));
        rejects(() -> changed.apply(List.of(placement, placement)));
        require(changed.remove("minecraft:stick", ModelPose.GUI).revision() == 2, "Reset advances revision");
        require(changed.remove("minecraft:stick", ModelPose.GUI).entries().isEmpty(), "Reset removes override");
        ModelTransform bag = initial.get("eclipseserver:carver_bag", ModelPose.WORN).transform();
        require(bag.rx() == 180 && bag.y() == 5.25f && bag.z() == 3 && bag.sx() == .79f,
                "Factory worn bag must face outside with intended gap");
        for (String bad : List.of("{\"version\":2,\"revision\":0,\"entries\":[]}",
                "{\"version\":1,\"revision\":-1,\"entries\":[]}",
                "{\"version\":1,\"revision\":0.5,\"entries\":[]}",
                "{\"version\":1,\"revision\":0,\"revision\":1,\"entries\":[]}",
                "{\"version\":1,\"revision\":0,\"entries\":[],\"unknown\":true}",
                "{\"version\":1,\"revision\":0,\"entries\":[]} trailing"))
            rejects(() -> ModelEditorDocument.decode(bad));
        rejects(() -> ModelEditorDocument.decode(" ".repeat(40_000)));
        java.util.ArrayList<ModelPlacement> tooMany = new java.util.ArrayList<>();
        for (int i = 0; i < 129; i++) tooMany.add(ModelPlacement.defaults("minecraft:item_" + i, ModelPose.GUI));
        rejects(() -> initial.apply(tooMany));
        java.util.HashMap<String, ModelPlacement> overCap = new java.util.HashMap<>();
        for (int i = 0; i < 129; i++) {
            ModelPlacement entry = ModelPlacement.defaults("minecraft:item_" + i, ModelPose.GUI);
            overCap.put(entry.key(), entry);
        }
        rejects(() -> new ModelEditorDocument(0, overCap));
        rejects(() -> new ModelEditorDocument(Long.MAX_VALUE, Map.of()).apply(List.of(placement)));
    }

    private static void persistenceAndAuthority() throws Exception {
        Path directory = Files.createTempDirectory("model-editor-test-");
        Path file = directory.resolve("model-placements.json");
        ModelEditorStore store = new ModelEditorStore(file);
        ModelEditorService service = new ModelEditorService(store,
                id -> id.equals("minecraft:stick") || id.equals("minecraft:stone"));
        ModelPlacement placement = new ModelPlacement("minecraft:stick", ModelPose.GUI,
                "minecraft:stone", ModelAttachment.BACK, ModelTransform.identity());
        JsonObject save = save(0, placement);
        require(!service.handle(false, true, save.toString()).success(), "Unauthenticated admin cannot save");
        require(!service.handle(true, false, save.toString()).success(), "Authenticated non-admin cannot save");
        require(!service.handle(false, false, "malformed").success(), "Unauthorized requests fail before parsing");
        require(!Files.exists(file), "Rejected requests cannot create config");
        require(!service.handle(false, true, "{\"action\":\"reload\"}").success(), "Reload requires authentication");
        require(service.handle(true, true, save.toString()).success(), "Valid authorized save succeeds");
        String persisted = Files.readString(file);
        require(new ModelEditorStore(file).load().equals(service.document()), "Restart restores saved document");
        require(!service.handle(true, true, save.toString()).success(), "Stale save must reject CAS");
        require(Files.readString(file).equals(persisted), "Conflict cannot mutate persisted file");
        ModelPlacement invalid = ModelPlacement.defaults("minecraft:missing", ModelPose.GUI);
        require(!service.handle(true, true, save(1, invalid).toString()).success(), "Unknown item rejected");
        ModelPlacement invalidModel = new ModelPlacement("minecraft:stick", ModelPose.GUI,
                "minecraft:missing", ModelAttachment.BACK, ModelTransform.identity());
        require(!service.handle(true, true, save(1, invalidModel).toString()).success(), "Unknown model rejected");
        require(Files.readString(file).equals(persisted), "Registry rejection cannot mutate file");
        require(!service.handle(true, true,
                "{\"action\":\"reset\",\"revision\":0,\"item\":\"minecraft:stick\",\"context\":\"GUI\"}").success(),
                "Stale reset cannot erase another administrator's changes");
        require(service.handle(true, true,
                "{\"action\":\"reset\",\"revision\":1,\"item\":\"minecraft:stick\",\"context\":\"GUI\"}").success(),
                "Authorized reset succeeds");
        require(service.document().revision() == 2 && service.document().entries().isEmpty(), "Reset persists revision");
        Files.writeString(file, "damaged config");
        ModelEditorDocument recovered = new ModelEditorStore(file).load();
        require(recovered.revision() == 1 && recovered.entries().size() == 1, "Damaged primary loads last valid backup");
        require(Files.readString(file).equals("damaged config"), "Loading preserves damaged primary for inspection");
        long beforeReload = service.document().revision();
        require(service.handle(true, true, "{\"action\":\"reload\"}").success(), "Valid backup can be reloaded");
        require(service.document().revision() > beforeReload, "Reload cannot roll revision back and enable stale drafts");
        // An unusable primary and backup leave the live document untouched on reload.
        Files.writeString(file, "damaged config again");
        Files.writeString(file.resolveSibling("model-placements.json.bak"), "broken backup");
        ModelEditorDocument before = service.document();
        require(!service.handle(true, true, "{\"action\":\"reload\"}").success(), "Unrecoverable reload fails");
        require(service.document().equals(before), "Failed reload retains authoritative valid state");
        // A failed replace must not publish an in-memory revision either.
        Path blocked = directory.resolve("blocked.json");
        ModelEditorService blockedService = new ModelEditorService(new ModelEditorStore(blocked), id -> true);
        Files.createDirectory(blocked);
        Files.writeString(blocked.resolve("child"), "keep");
        require(!blockedService.handle(true, true, save.toString()).success(), "Persistence failure is reported");
        require(blockedService.document().revision() == 0, "Failed persistence cannot publish unsaved state");
        // A primary damaged after startup still has a known valid in-memory predecessor.
        Path changedFile = directory.resolve("changed.json");
        Files.writeString(changedFile, persisted);
        ModelEditorService changedService = new ModelEditorService(new ModelEditorStore(changedFile), id -> true);
        Files.writeString(changedFile, "damaged after load");
        require(changedService.handle(true, true, save(1, placement).toString()).success(), "Save after external damage succeeds");
        Path changedBackup = changedFile.resolveSibling("changed.json.bak");
        require(Files.exists(changedBackup) && ModelEditorDocument.decode(Files.readString(changedBackup)).revision() == 1,
                "Save must retain known valid predecessor even if primary was damaged after loading");
        Path semanticFile = directory.resolve("semantic.json");
        ModelPlacement unknown = shifted("minecraft:missing", ModelPose.GUI);
        Files.writeString(semanticFile, ModelEditorDocument.empty().apply(List.of(unknown)).encode());
        Files.writeString(semanticFile.resolveSibling("semantic.json.bak"), persisted);
        ModelEditorService semanticService = new ModelEditorService(new ModelEditorStore(semanticFile),
                id -> id.equals("minecraft:stick") || id.equals("minecraft:stone"));
        require(semanticService.document().revision() == 1 && semanticService.document().entries().containsKey(placement.key()),
                "Unknown registry IDs in primary must fall back to a semantically valid backup");
        require(Files.readString(semanticFile).contains("minecraft:missing"), "Recovery must preserve rejected primary");
        // One explicit save of six items in every context must be one atomic transaction, not partial batches.
        Path wholeFile = directory.resolve("whole.json");
        ModelEditorService wholeService = new ModelEditorService(new ModelEditorStore(wholeFile), id -> true);
        JsonObject wholeSave = new JsonObject();
        wholeSave.addProperty("action", "save"); wholeSave.addProperty("revision", 0);
        com.google.gson.JsonArray allContexts = new com.google.gson.JsonArray();
        for (int item = 0; item < 6; item++) for (ModelPose pose : ModelPose.values())
            allContexts.add(shifted("minecraft:item_" + item, pose).toJson());
        wholeSave.add("entries", allContexts);
        require(wholeService.handle(true, true, wholeSave.toString()).success(), "54 changes must save in one request");
        require(wholeService.document().revision() == 1 && wholeService.document().entries().size() == 54,
                "Whole save must publish exactly one revision containing all contexts");
        com.google.gson.JsonArray allDefaults = new com.google.gson.JsonArray();
        for (int item = 0; item < 6; item++) for (ModelPose pose : ModelPose.values())
            allDefaults.add(ModelPlacement.defaults("minecraft:item_" + item, pose).toJson());
        wholeSave.addProperty("revision", 1);
        wholeSave.add("entries", allDefaults);
        require(wholeService.handle(true, true, wholeSave.toString()).success(), "Factory reset save succeeds atomically");
        require(wholeService.document().revision() == 2 && wholeService.document().entries().isEmpty(),
                "Factory reset save removes every persisted override in one revision");
        require(new ModelEditorStore(wholeFile).load().equals(wholeService.document()),
                "Restart must retain reclaimed capacity rather than recreate factory default entries");
    }

    private static ModelPlacement shifted(String item, ModelPose pose) {
        return new ModelPlacement(item, pose, "", ModelAttachment.BACK,
                new ModelTransform(1, 0, 0, 0, 0, 0, 1, 1, 1));
    }

    private static JsonObject save(long revision, ModelPlacement placement) {
        JsonObject request = new JsonObject();
        request.addProperty("action", "save");
        request.addProperty("revision", revision);
        com.google.gson.JsonArray entries = new com.google.gson.JsonArray();
        entries.add(placement.toJson());
        request.add("entries", entries);
        return request;
    }
    private static void rejects(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException | UnsupportedOperationException expected) { return; }
        throw new AssertionError("Expected invalid input to be rejected");
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
