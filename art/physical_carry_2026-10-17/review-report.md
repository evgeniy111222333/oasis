# Final follow-up verdict — 2026-10-18

**Approved within the scoped review: no remaining confirmed P1/P2 findings.** All four original findings and the adjacent initial recovery-location issue are resolved in the inspected production source. Original evidence and intermediate verdicts are preserved below as review history.

Final recovery verification: `CarryManager.takeItem` and `hold` now evaluate `initialRecovery(player, snapshot)` before `ledger.introduce` can mutate inventory/entities. It delegates to the same native support/protection/collision/line-of-sight validation used by `dropSpot`, through the bounded 25-candidate `CarryRecovery.find`. A cramped room rejects without transferring ownership; no persisted location comes from the forward visual anchor. Read `CarryRecoveryTest` and the actual `recovery-green.log`: `:server:verifyPhysicalCarry` succeeded in 14 seconds, including block-mutation, inventory, serialization, sync-budget and recovery checks. Its restart assertion exercises `recoverAll` on the ledger; broader file round trips remain covered separately by `CarryLedgerTest`.

The root's final integrated check/assemble after this last patch is still in progress, so this verdict is a source/integration review approval rather than a claim that those final builds have completed. No game process was launched and no running server was interrupted.

---
# Physical carry independent review

Verdict: changes requested. Four P2 native integration/spec issues remain in the reviewed files. No P1 issue confirmed. Root received each finding while review was in progress. Locations below refer to the current uncommitted source reviewed on 2026-10-18; implementation is actively changing.

## Actionable findings

1. **P2 — Native placement callbacks escape the ownership transaction.** `fabric-server/server/src/main/java/ua/rp/chat/carry/CarryWorld.java:85` (default profile at line 29). A lifted carved pumpkin placed on a copper block or complete snow/iron golem base invokes native `CarvedPumpkinBlock.onPlace`, which consumes pattern blocks and spawns a golem before `CarryLedger.commit` saves ownership. Successful placement no longer restores the promised furnishing; a failed persistence write restores ledger ownership but its single-position rollback cannot undo the spawned entity or consumed support blocks. Evidence: local Minecraft 26.1.2 `javap -c -p` confirms `onPlace -> trySpawnGolem -> clearPatternBlocks/addFreshEntity`, and `CarryLedger.java:69` applies the effect before saving. Exclude blocks with unsafe placement callbacks (also from configured additions), reject triggering configurations, or implement mutation/rollback that prevents these effects until a successful commit. A postcondition check alone is insufficient.

2. **P2 — Failed lift does not roll back neighbor destruction.** `fabric-server/server/src/main/java/ua/rp/chat/carry/CarryWorld.java:74`. Lifting a bookshelf/crafting table with an attached wall torch updates neighbors immediately. If the following carry-store write fails, `CarryManager.take` restores only the original furnishing; the attached torch has already broken and dropped. Thus a rejected operation changes the world and can trigger further redstone/attachment changes. Evidence: native `Level.setBlock` invokes neighbor notifications and shape updates; its shape-update flags clear both neighbor and suppress-drops bits (`flags & -34`). The ledger's test uses an integer effect and does not cover these native effects. Reject dependent structures before lifting, or perform a genuinely reversible mutation with notifications deferred until commit; add a failure fixture involving an attached block.

3. **P2 — Preview reports known-invalid placements as allowed.** `fabric-server/client-mod-gradle/src/client/java/ua/rp/chat/client/carry/CarryWorldFeedback.java:46`–51. The preview accepts replaceable blocks and checks collision, but omits the server's air-only destination, empty-fluid and rotated-state `canSurvive` rules (`CarryWorld.canRestore`). A lifted hanging lantern previewed over a floor with no ceiling is green but cannot be placed; grass/water destinations can likewise be green yet rejected. Its replaceable-hit destination also differs from the server's unconditional support-above destination for snapshots. Reuse equivalent client-known placement geometry and validity checks; private permission checks can remain server-only.

4. **P2 — Special-rendered furnishing visuals lose saved block orientation.** `fabric-server/server/src/main/java/ua/rp/chat/carry/CarryDisplays.java:39`–41 and `fabric-server/client-mod-gradle/src/client/java/ua/rp/chat/client/carry/CarryWorldFeedback.java:59`–76. Chest/shulker snapshots whose render shape is ENTITYBLOCK_ANIMATED become plain ItemDisplay/item preview models, with no transform derived from their saved blockState. The actual restored block rotates the saved state. Therefore an EAST-facing chest shown/placed at yaw 0 uses the special item's fixed base orientation in the preview/display but restores EAST; directional shulkers also lose vertical orientation. Preserve the saved base facing in the special-renderer transform or render a native block-entity state that retains it, and test an asymmetric chest/shulker orientation against restoration.

## Finding already addressed during review

The original positive-Y item/voxel preview rotation disagreed with native fixed DisplayRenderer's negative entity-Y rotation at 90/270 degrees. Root reproduced it red and changed preview transformation to `CarryMath.previewRotation(-yaw)` semantics; current source uses that helper. This sign correction does not address finding 4's missing base block orientation.

## Scope and evidence limits

Reviewed authoritative manager/ledger/inventory/offers, native block serialization and displays, voxel bridge/rotation, relevant server interaction gates, client targeting/state/input/pose/preview and native hooks. Read existing production-focused tests and inspected local 26.1.2 bytecode for display orientation, block updates, and pumpkin side effects. No Minecraft client/server was launched, no running server interrupted, and no source modified. This review did not rerun the full builds; root owns the final joint check/assemble run after fixes. Independent vanilla world saves versus carry-file crash consistency is outside the graceful-restart guarantee and is not counted as a new blocker.

No optional minor suggestions.

## Bounded follow-up review — 2026-10-18

All four original P2 findings are resolved in the inspected production paths:

- **1 resolved:** `CarryWorld` uses `CarryBlockMutation.setSilently`; flags 818 contain native skip-onPlace and skip-block-entity-side-effects bits. Local 26.1.2 `LevelChunk` bytecode independently confirms these guards, plus absence of removal neighbor effects without flags 1/64. Pumpkin placement recipes are not invoked.
- **2 resolved:** forward and rollback writes use the same silent path; native immediate-neighbor `canSurvive` is sampled before and after the mutation. Unsupported attachments reject before persistence and roll back silently. `CarryLedger.commit` only publishes notifications after both saving and updating ownership. The voxel projection adapter uses the scoped silent flags as well. Read the actual `CarryBlockMutationTest` failure/dependency fixtures.
- **3 resolved:** shared `CarryPlacement.snapshotDestination` is invoked by both `CarryWorld.canRestore` and client preview, enforcing air, no fluid and rotated native survival; both target support.above for snapshots. Read native lantern/grass/water fixture coverage.
- **4 resolved:** every saved block state now uses a native BlockDisplay. Special preview resolves the rotated block state with `BlockModelResolver`/`BlockModelRenderState`, matching native 26.1 display handling. Read the 64 chest/shulker orientation cases, including six shulker directions and Christmas conditional chest models.

Also reviewed exact UTF-8 record accounting/deep copies in `CarrySyncBuilder`, local-first sync ordering and metadata-only remote/prop records, native ItemDisplay preview fallback for omitted large meshes, and interpolated owner/shared crouch-anchor display extraction. No confirmed P1/P2 issue found in those changes. Full integrated check/assemble remains root's verification task; this follow-up did not launch any game process.

### One adjacent P2 still requiring correction

`fabric-server/server/src/main/java/ua/rp/chat/carry/CarryManager.java:109` and `:124` initialize large loose-item/selected-item ledger records using `at(player)`, the forward visual anchor. This location is not validated. The new recovery path falls back to it when `dropSpot` fails, despite calling it the last safe location. Hold a large selected item while pressed against a wall in a cramped room, then disconnect before any successful periodic safe-point update: the anchor's full target box can lie inside the wall, and strict line-of-sight collection cannot retrieve the prop without breaking the wall (possibly protected). Use a validated initial recovery position or a non-solid player-position fallback distinct from the display anchor. Original world-block lifts and collected props already start at their actual source locations.

Follow-up verdict pending that recovery-location correction; original four findings closed.

