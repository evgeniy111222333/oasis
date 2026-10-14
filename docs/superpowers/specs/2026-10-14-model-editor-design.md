# In-game model placement editor

The user approved the in-game editor described in chat and explicitly requested its complete implementation. Preserve the current working sources and imported models. Implement and verify the feature without further approval gates.

## Outcome

Administrators use `/modeleditor` to choose any registered item and adjust its appearance in worn, left/right first-person hand, left/right third-person hand, inventory, dropped, head and item-frame contexts. They choose a replacement item model, move/rotate/scale it, preview a player from different directions in idle, walk, crouch and carving poses, and save the settings to the server for every connected player and future joins. Editing is a client-only draft until explicitly saved. Closing discards unsaved changes. No model geometry editor is required: this feature edits presentation and attachment, not cubes or UVs.

## Data and authority

Shared immutable placement records use item registry IDs, an explicit context enum, an optional replacement item ID, attachment BACK/BELT/RIGHT_HAND/LEFT_HAND, and nine finite numbers: position in model pixels, Euler rotation in degrees, per-axis scale. Positions are bounded to 64 pixels, rotation to 360 degrees and scales to 0.05..4. Item contexts apply an adjustment after the imported vanilla display transform. Worn settings are absolute attachment transforms. The worn bag default uses X=180 degrees to put its front pocket outside, Y=6, Z=3.5 and uniform scale 0.72.

The server owns `config/RPChat/model-placements.json`. Versioned JSON is size bounded, persisted by atomic replace, and sent on join and save. Only authenticated administrators may open or save. Saves check the document revision to avoid overwriting a second administrator's changes. A rejected save keeps the local draft and gives a useful message. Permission checks happen on the server, including unsolicited packets. Invalid item/model IDs and malformed transforms are rejected before any file mutation. Retain a previous valid file when loading damaged config fails.

## Editor and rendering

Use a native Minecraft Screen with Russian labels, compact navigation, number inputs and increment/decrement controls, model/target search by registry ID, attachment/context selectors, undo/redo, factory reset, discard and explicit global save. Preview uses a detached client player so it never changes the user's inventory, network pose or equipped items. Reuse real model, item and bag rendering, not a drawn imitation. Orbit/zoom views and animation selection are available. Item context previews resolve the same context as the selected live render path.

All runtime placement lookups merge the current server document with the editor draft only during preview. Wearable bag full/partial/empty variants remain coherent during carving. Replacement models preserve the original item's game identity and gameplay. Resource reload and GUI item cache identities include presentation changes. Disconnect clears settings and drafts, preventing settings from a previous server leaking into another one.

## Verification and delivery

Test JSON bounds, round trips, revisions, persistence/restart, invalid input, unauthorized saving, reset and editor draft isolation. Run existing server/client checks and a real Fabric client game test covering the editor screen, transforms, model replacement, contexts, worn front-facing orientation and animations. Inspect generated screenshots. Build client and server artifacts, update the launcher distribution using the existing authorized publication workflow, and restart the local Fabric server safely. Keep unrelated dirty files untouched.
