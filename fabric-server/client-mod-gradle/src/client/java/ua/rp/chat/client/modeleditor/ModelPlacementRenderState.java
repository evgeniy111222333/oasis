package ua.rp.chat.client.modeleditor;

import ua.rp.chat.modeleditor.ModelPlacement;

/** Applies a presentation adjustment to every vanilla item layer. */
public interface ModelPlacementRenderState {
    void eclipse$applyPlacement(ModelPlacement placement);
}
