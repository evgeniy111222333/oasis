package ua.rp.chat;

import ua.rp.chat.client.modeleditor.ModelEditorDraft;
import ua.rp.chat.modeleditor.*;
import java.util.List;

/** Drafts must stay local, survive conflicts, and remain undoable after authoritative updates. */
public final class ModelEditorDraftTest {
    public static void main(String[] args) {
        var draft = new ModelEditorDraft();
        String item = "eclipseserver:carver_bag";
        var baseline = ModelPlacement.defaults(item, ModelPose.WORN);
        var change = new ModelPlacement(item, ModelPose.WORN, "", ModelAttachment.BACK,
                new ModelTransform(1, 6, 3.5f, 180, 0, 0, .72f, .72f, .72f));
        draft.update(ModelEditorDocument.empty());
        draft.begin();
        draft.edit(change);
        require(draft.get(item, ModelPose.WORN, true).equals(change), "Preview must show the draft");
        require(draft.get(item, ModelPose.WORN, false).equals(baseline), "Live world must retain authority");
        require(draft.dirtyCount() == 1, "One edited context is dirty");
        draft.undo();
        require(draft.dirtyCount() == 0 && draft.canRedo(), "Undo restores the original attachment");
        draft.redo();
        require(draft.get(item, ModelPose.WORN, true).equals(change), "Redo restores exact numbers");
        var remote = new ModelPlacement("eclipseserver:ancient_peasant_hammer", ModelPose.GUI, "",
                ModelAttachment.BACK, new ModelTransform(2, 0, 0, 0, 0, 0, 1, 1, 1));
        draft.update(ModelEditorDocument.empty().apply(List.of(remote)));
        require(draft.dirtyCount() == 1, "An unrelated remote save must retain this draft");
        require(saveRevision(draft) == 0, "A remote broadcast must not silently change the draft's save revision");
        require(draft.get(remote.item(), ModelPose.GUI, false).equals(remote), "Remote settings must apply live");
        draft.update(draft.document().apply(List.of(change)));
        require(draft.dirtyCount() == 0, "A save acknowledgement clears only matching edits");
        draft.edit(new ModelPlacement(item, ModelPose.WORN, "", ModelAttachment.BACK,
                new ModelTransform(3, 6, 3.5f, 180, 0, 0, .72f, .72f, .72f)));
        draft.end();
        require(draft.dirtyCount() == 0, "Closing discards unsaved changes");
        require(draft.get(item, ModelPose.WORN, true).equals(change), "Closing keeps saved server settings");
        draft.clear();
        require(draft.document().revision() == 0 && draft.document().entries().isEmpty(),
                "Disconnect clears the previous server's settings");
        System.out.println("ModelEditorDraftTest passed: isolation, undo/redo, remote conflicts, acknowledgement and disconnect");
    }
    private static long saveRevision(ModelEditorDraft draft) {
        try { return ((Number)draft.getClass().getMethod("saveRevision").invoke(draft)).longValue(); }
        catch (ReflectiveOperationException missing) { throw new AssertionError("Editor must pin the revision of a dirty draft", missing); }
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
