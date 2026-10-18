package ua.rp.chat.client.modeleditor;

import ua.rp.chat.modeleditor.*;
import java.util.*;

/** Render-independent draft state: server authority is never mutated by a preview. */
public final class ModelEditorDraft {
    private ModelEditorDocument document = ModelEditorDocument.empty();
    private final Map<String, ModelPlacement> edits = new LinkedHashMap<>();
    private final Deque<Map<String, ModelPlacement>> undo = new ArrayDeque<>(), redo = new ArrayDeque<>();
    private boolean active;
    private long saveRevision;
    public ModelEditorDocument document() { return document; }
    public long saveRevision() { return saveRevision; }
    public void rebase() { saveRevision = document.revision(); }
    public boolean active() { return active; }
    public void begin() { end(); active = true; rebase(); }
    public void end() { active = false; edits.clear(); undo.clear(); redo.clear(); rebase(); }
    public void clear() { end(); document = ModelEditorDocument.empty(); rebase(); }
    public void update(ModelEditorDocument value) {
        document = Objects.requireNonNull(value);
        normalize();
    }
    public ModelPlacement get(String item, ModelPose pose, boolean preview) {
        String key = item + "|" + pose.name();
        return preview && active && edits.containsKey(key) ? edits.get(key) : document.get(item, pose);
    }
    public int dirtyCount() { return edits.size(); }
    public List<ModelPlacement> changes() { return List.copyOf(edits.values()); }
    public boolean canUndo() { return !undo.isEmpty(); }
    public boolean canRedo() { return !redo.isEmpty(); }
    public void edit(ModelPlacement placement) {
        if (!active || get(placement.item(), placement.context(), true).equals(placement)) return;
        if (edits.isEmpty()) rebase();
        undo.push(Map.copyOf(edits));
        while (undo.size() > 100) undo.removeLast();
        redo.clear();
        edits.put(placement.key(), placement);
        normalize();
    }
    public void undo() {
        if (!canUndo()) return;
        redo.push(Map.copyOf(edits)); restore(undo.pop());
    }
    public void redo() {
        if (!canRedo()) return;
        undo.push(Map.copyOf(edits)); restore(redo.pop());
    }
    public void discard() { edits.clear(); undo.clear(); redo.clear(); rebase(); }
    private void restore(Map<String, ModelPlacement> value) { edits.clear(); edits.putAll(value); normalize(); }
    private void normalize() {
        edits.values().removeIf(value -> value.equals(document.get(value.item(), value.context())));
        if (edits.isEmpty()) rebase();
    }
}
