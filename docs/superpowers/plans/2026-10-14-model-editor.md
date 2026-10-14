# Model editor implementation plan

Spec: `docs/superpowers/specs/2026-10-14-model-editor-design.md`.

## Global constraints

Use the actual Fabric project in `D:/23/fabric-server`, Java 25, shared sources under `common`. Preserve existing user modifications. No unrelated commits, resets or clean operations. Agents own disjoint files; shared interfaces below are binding. UI and server work can proceed independently against these interfaces. Parent handles render hooks, client state, integration and release.

## Shared interface (server/domain task produces)

Package `ua.rp.chat.modeleditor`:

* `ModelPose`: WORN, FIRST_PERSON_RIGHT_HAND, FIRST_PERSON_LEFT_HAND, THIRD_PERSON_RIGHT_HAND, THIRD_PERSON_LEFT_HAND, GUI, GROUND, FIXED, HEAD.
* `ModelAttachment`: BACK, BELT, RIGHT_HAND, LEFT_HAND.
* `ModelTransform(float x,float y,float z,float rx,float ry,float rz,float sx,float sy,float sz)`, validated canonical constructor; `identity()`; `toJson()`; `fromJson(JsonObject)`.
* `ModelPlacement(String item,ModelPose context,String model,ModelAttachment attachment,ModelTransform transform)`, validated constructor; `defaults(String,ModelPose)`; `key()`; `toJson()`; `fromJson(JsonObject)`.
* `ModelEditorDocument(long revision,Map<String,ModelPlacement> entries)`, immutable and size bounded; `empty()`; `get(String,ModelPose)` (returns defaults if absent); `encode()`; `decode(String)`; `apply(List<ModelPlacement>)` returns a new document at revision+1; `remove(String,ModelPose)` likewise.
* `ModelEditorRequestPayload(String json)` and `ModelEditorSyncPayload(String json)` under the same package, native CustomPacketPayload with bounded UTF8 codecs.

Request envelopes: action `open`, `save` (revision + entries array), `reset` (revision + item + context), `reload`. Sync envelopes: type `open`, `state`, `saved` or `error`; editable boolean; message string; document JSON object where applicable. Opening via server command uses type=open; join uses type=state. Successful save broadcasts state to all players and sends saved to its author. Stale revisions return error and fresh document without destroying the author's draft.

## Client interface (parent produces)

Package `ua.rp.chat.client.modeleditor`, class `ModelEditorClient`:

`register()`, `requestOpen()`, `beginPreview()`, `endPreview()`, `placement(String,ModelPose)`, `edit(ModelPlacement)`, `reset(String,ModelPose)`, `undo()`, `redo()`, `canUndo()`, `canRedo()`, `dirtyCount()`, `save()`, `editable()`, `status()`, `revision()`, `setPreviewPlayer(RemotePlayer)`, `previewPlayer(int)`, `setPreviewAnimation(String)`, `previewAnimation()`, `previewTicks()`, `beginItemPreview(ModelPose)`, `endItemPreview()`, `itemPreviewContext()`. `placement` returns merged draft while editor preview is active, otherwise authoritative server placement. Render hooks call helper `ModelPlacementRendering` for transforms and replacement resolution. UI opens as `new ModelEditorScreen()` on the server open response. UI is responsible for beginning/ending its preview session, detached player lifecycle and exact input widgets.

## Tasks

1. Shared records, bounded payloads, atomic config storage and server authority: implement with meaningful tests first; wire server init/commands/join. Ownership: common/modeleditor, server/modeleditor, server RPChat.java and server build.gradle.
2. Native editor UI and detached model/item preview: implement ModelEditorScreen and helper UI classes against the client API; inspect native 26.1.2 GUI API via local cached sources/JARs. Ownership: client/modeleditor/ModelEditorScreen.java and its UI-only helpers. Do not change ModelEditorClient or renderer/mixins/init.
3. Parent: implement ModelEditorClient state and undo/redo; model replacement, context transform and identity cache hooks; configurable worn anchors; preview pose integration. Correct the bag's default orientation and gap. Register client networking and disconnect reset.
4. Parent: integrate tests into check; real client editor game test and screenshot inspection. Review server and UI implementations, repair integration issues, then request a broad code review.
5. Parent: build and publish paired updates through deployment README workflow, safely restart the current local server and verify API/readiness/new versions. Write concise user instructions and feature documentation.
