# Task 1 — server physical carrying report

Status: DONE — server/shared implementation delivered. This report was restored on 2026-10-18 after the interrupted implementation turn. Root owns subsequent review fixes and the final joint verification; the successful logs below describe the original server implementation revision, not a claim that every later edit has already passed final checks.

## Changed paths

Original implementation:

- `fabric-server/common/src/main/java/ua/rp/chat/carry/CarryActionPayload.java`, `CarrySyncPayload.java`, `CarryWire.java`.
- `fabric-server/common/src/test/java/ua/rp/chat/carry/CarryWireTest.java`.
- `fabric-server/server/src/main/java/ua/rp/chat/carry/CarryManager.java`, `CarryLedger.java`, `CarryWorld.java`, `CarryDisplays.java`, `CarryBlockSnapshot.java`, `CarryInventory.java`, `CarryOffers.java`, `CarryRules.java`, `CarryVoxelRotation.java`.
- `fabric-server/server/src/main/java/ua/rp/chat/microvoxel/MicrovoxelCarryBridge.java`.
- Surgical bootstrap/lifecycle/network/busy gates in `fabric-server/server/src/main/java/ua/rp/chat/RPChat.java` and carry-aware routing in `interaction/ItemPickupManager.java`.
- One visibility change, private to public `AcquaintanceManager.isBound`, for quiet server validation of restrained hands.
- `fabric-server/server/src/test/java/ua/rp/chat/carry/CarryServerTest.java`, `CarryLedgerTest.java`, `CarryInventoryTest.java`, `CarryBlockSnapshotTest.java`, `CarryPayloadCodecTest.java`, `CarryRulesTest.java`, `CarryVoxelRotationTest.java`.
- `fabric-server/server/build.gradle`: `verifyPhysicalCarry` JavaExec and its dependency from `check`.

Root moved the unchanged `MicrovoxelPortableVolume` decoder from client main sources into common sources at my request, and owns all client files. Later root fixes also introduce shared transforms and strengthen the above implementation; those edits are not claimed as my original work.

## Core design and integration

Frozen payload interfaces were sent to root immediately after writing: `CarryActionPayload(String json)` / `CarrySyncPayload(String json)`, exact channel IDs, 4096-byte action cap and 1 MiB sync cap, strict UTF-8. Encoding/decoding rejects malformed UTF-8, oversized values and null values. Native registry-aware `ItemStack.CODEC` supplies visual JSON.

Small objects occupy the real selected inventory slot and retain vanilla use. Loose stacks split exactly one item; a nonempty hand is rejected. Explicit hold splits one item and preflights storage for the remainder. `CarryInventory` performs the same count, identity and merge-capacity logic used by native transitions. Large ordinary objects and lifted world snapshots live in a unique authoritative `CarryLedger.Entry`; native displays are disposable projections and never generate item drops or become owners.

The ledger records immutable snapshot data, object UUID, optional held owner and recoverable world location. Mutations use reversible native effects, save an fsynced temporary binary file, then atomically replace `rpchat-carry.bin` under the save root. A caught mutation/write failure restores its physical source and retains the previous in-memory owner map. Snapshot byte arrays are copied. Loading enforces unique object IDs/held owners and bounded data. Save and load both cap the file at 128 MiB; entries cap at 4096 and private binary fields at 2 MiB. Startup converts persisted held records to recoverable scene props.

World snapshots preserve complete native block state plus complete block-entity NBT, including inventories and nested item components. Native restoration changes the block-entity coordinates and restores the exact data at the server-selected destination. `CarryBlockSnapshot` is the production codec exercised by tests. Open containers are rejected before lifting. Native displays and visual sync strip container, loot-container and block-entity data; private block snapshot bytes never enter the visual JSON.

`MicrovoxelCarryBridge` uses the existing portable decoder/serializer, authoritative projection writer, protection checks, storage readiness and per-chunk quota. Only an isolated single volume is lifted. Adjacent connected volumes, protected volumes and sculptures containing fluid are rejected intact. Exact material cells and authored sub-cell shape geometry survive portable encoding and quarter turns. Root subsequently changed sculpture permission to regular-player `rpchat.carry.sculpture` rather than requiring editor/operator permission.

Default single-block profiles are chest, barrel, all shulker-box colors, crafting table, bookshelf, chiseled bookshelf, decorated pot, flower pot, lantern, soul lantern and carved pumpkin. `carry-profiles.properties` has an explicit `blocks` CSV allowlist; the sync `liftable` array reports it. Double chests, double-height/bed structures, doors/pistons and fluid-bearing targets are hard rejected. Unsupported machinery is never partially lifted.

Actions validate authenticated, living, non-spectator, conscious, unbound players, authoritative targets, three-block reach, line of sight and loaded/protected locations. The server raycasts instead of trusting supplied UUID/block hints or placement coordinates. Place uses visible upper support; ordinary props use the real support hit position so slabs/table tops work, and world blocks restore to the grid. Quarter turns are finite and snapped. Nearby free spots are searched for safe drops, with block/entity/prop occupancy checked.

Offers are consent tokens tied to exact held identity, expiring after 30 seconds. Only their recipient accepts or declines. Acceptance rechecks participants, reach, visibility, hand capacity and current identity. Disconnect/decline/expiry retain the giver's object. Stow accepts ordinary inventory items only and preflights merge capacity; world snapshots cannot be packed into inventory.

RPChat integrates start, tick, quit, shutdown, payload registration and existing use/attack/break/combat/microvoxel/carver busy gates. Native death handling and dimension checks recover large objects; small objects stay normal inventory items. Legacy pickup routes to carry when the client supports the new protocol, and always respects large-object busy state. Displays are never saved (`shouldBeSaved=false`); loaded legacy tagged displays are removed, including an initial scan of already loaded entities.

Snapshot additions coordinated with root: `liftable`, `kind`, `stowable`, prop `yaw`. Large native display centered base is approximately 0.85 blocks forward and 0.55 above feet; BlockDisplay transformation translates by (-0.5, 0, -0.5) around that centered anchor. Special block-entity render shapes use a bare ItemDisplay. Root subsequently centralized yaw/transforms and owner interpolation.

## Verification evidence

All commands used Java 25 at `C:\Program Files\Java\jdk-25.0.3` and the Fabric wrapper; no Minecraft client/server was started, stopped or restarted, and no `build` finalizer/deployment task was run.

Initial red/green evidence:

- Standalone `javac` of `CarryWireTest` failed because the new wire helper did not yet exist; implementation plus the same test then printed `CarryWireTest passed`.
- Standalone `javac` of `CarryLedgerTest` failed because the new production ledger/offers did not exist; implementation plus the same test printed `CarryLedgerTest passed`.
- `:server:verifyPhysicalCarry` initially failed compiling the inventory tests because `CarryInventory` did not exist. The production helper then passed real ItemStack count/identity/merge/rollback tests. Minecraft 26.1 item holders require explicit component binding in this headless fixture after registry bootstrap; an initial fixture failure exposed and resolved that requirement.
- `CarryRulesTest` likewise failed for the missing production rules before implementation and then passed.
- These missing-feature compile failures are the red evidence actually observed. I am not claiming every native adapter method received a separate runtime assertion-first cycle. Native snapshot/display coverage was expanded during implementation/review.

Production tests cover:

- Exclusive persisted ownership, stale owner rejection, failure/rollback, handoff, prop release/reclaim and restart recovery.
- Persistence of container and voxel bytes; native chest/barrel snapshots through actual ledger file reload; named ItemStacks and nested shulker inventory components; moved coordinates and native block-state rotation.
- Actual native packet codec round trips, malformed UTF-8, negative/oversized lengths, null/oversized payload construction, and the full sync-size boundary.
- Small-stack conservation, nonempty hand rejection, stale count identity, capacity through merges, and exact inventory rollback.
- Authentication/life/spectator/restraint/consciousness, finite rotation and reach/visibility policy through the production rule functions.
- Offer recipient/identity/race/expiry/cancellation and exact voxel material/shape rotation.
- Visual NBT item/state codec generation, private inventory removal and centered block-display pivot. Root subsequently added a green test for the actual `Display.configure/load` path using a null-Level fixture; my first direct null-Level Entity.load attempt failed because native glowing-state handling dereferenced Level, and was replaced with production NBT/codec checks before the original full run.

Successful original full commands:

`gradlew.bat -p D:\23\fabric-server :server:check :server:assemble --console=plain`

- `art/physical_carry_2026-10-17/server-check.log`: BUILD SUCCESSFUL in 50s, 35 tasks (32 executed, 3 up to date).
- `art/physical_carry_2026-10-17/server-check-final.log`: BUILD SUCCESSFUL in 51s, 35 tasks (33 executed, 2 up to date). Includes all seven carry test groups passing plus the existing full server checks.
- Logs include existing JOML/Java Unsafe notices, a test fixture deprecation notice, and expected microvoxel corrupt-input/listener-hardening warnings. No failing test remains in those successful runs.

Root owns final joint `check`/`assemble` after the later reviewed fixes. I made no production edits or new build runs during this report-restoration turn.

## Concrete limitations and review handoff

- Atomic replacement and reversible caught-failure handling do not form one cross-store crash transaction with Minecraft chunk/player saves or the separate voxel store. A hard process/power loss between native mutation and independently durable world/player state can still create a cross-save inconsistency. No WAL/two-phase crash reconciliation was implemented; graceful shutdown and ledger restart recovery are the verified path. Do not describe this as proven power-loss-safe conservation.
- The original small ground-prop claim used separate claim/consume writes. Root identified this and now owns the atomic `ledger.collect` correction, together with stale-session checks, safe lifecycle recovery locations, active-carver source rejection, yaw/interpolation correction and sync metadata budgeting. Their final verification belongs to root's final run, not the older successful logs.
- Native world/UI/runtime behavior has headless codec/transition coverage and compilation, not an in-game multiplayer visual playtest. No live game launch was authorized for verification.
- Full ledger replacement writes are synchronous on the server thread. With many large private snapshots, latency can grow despite entry/file caps. Startup corruption fails closed; there is no automatic carry-store backup recovery or migration beyond this initial file version.
- Single-volume and explicit block-profile restrictions are deliberate supported-scope limits. External protection systems beyond existing vanilla/spawn/microvoxel checks would need their own integration if installed.
- Dirty animation and unrelated runtime work was preserved. Nothing was committed or published.

## Review fixes 1–2 — native side effects (2026-10-18)

Status: DONE for the two server mutation findings. Scope deliberately excludes client preview/orientation and Manager sync; root owns those and the final joint verification/re-review.

Changes:

- Added readable `CarryBlockMutation.java`, the production reversible world-effect boundary. Forward and rollback writes use exactly `Block.UPDATE_CLIENTS | Block.UPDATE_SKIP_ALL_SIDEEFFECTS` = **818**, which retains skip-onPlace (512), skip-block-entity-side-effects (256), known-shape (16), suppress-drops (32) and client-update (2). The old flags 3/2 are not used for native carry writes.
- `CarryLedger.Effect` now has a default `committed()` hook. The ledger invokes it only after successful atomic file replacement and after publishing its in-memory owner map/revision. It is outside the reversible apply/save catch block. A native notification exception is logged; it cannot roll back ownership after a durable commit.
- `CarryWorld.removalEffect(level,pos,snapshot)` and `placementEffect(level,pos,snapshot,turns)` return that production effect. Manager's two native block/voxel ownership transitions now call these methods. Raw world removal/restoration are private, silent operations, including inventory restoration. Carved pumpkins therefore never invoke a fresh native placement recipe, on success or rollback; moving them cannot use `onPlace` to consume a golem pattern and spawn an entity.
- The effect samples adjacent native states/survival before mutation, performs the silent mutation, then checks each previously surviving neighbor using its actual native `BlockState.canSurvive` against the temporarily changed world. Loss of support aborts before persistence and silently restores the source. This rejects, for example, lifting a crafting table/bookshelf with a supported wall torch attached. The check does not invoke `updateShape`, because native shape probes may schedule fluid work. Already-invalid neighbors are not treated as newly broken dependencies.
- Explicit native shape/neighbor/comparator notifications occur only in `committed()`. Shape updates retain the silent callback-suppression flags; the moved source never receives onPlace after commit either.
- `MicrovoxelCarryBridge` wraps its projection forward/removal/restoration calls in a nest-safe, exception-safe `silentProjection()` scope. The existing projection world's setter in `MicrovoxelManager` consults `CarryBlockMutation.projectionFlags()`: ordinary projection operations retain their existing flags 2; carry operations and rollback use 818. Sculpture permissions, exact portable data, protection gates and active Carver source rejection remain intact.
- Formatted CarryWorld, MicrovoxelCarryBridge and the new mutation helper/tests readably. `CarryWorld.canRestore` now uses root's shared `CarryPlacement.snapshotDestination/rotated`; `CarryWorld.rotation` delegates to shared `CarryPlacement.rotation`, keeping client-known destination rules aligned.

Headless verification uses the actual production effect and ledger, with a native `LevelReader` fixture supplying block reads and a fake native-write boundary; there is no separate test ownership/state machine. Native wall-torch `canSurvive` is evaluated against its real support before/after the production write. The fixture records write flags, callback exposure, durable save and notification ordering.

Evidence:

- `native-mutation-red.log`: `:server:verifyPhysicalCarry` failed at compile because the new production mutation boundary did not yet exist (16 missing-symbol errors). This is the observed red evidence; no claim of an earlier in-game failure reproduction is made.
- `native-mutation-green.log`: focused verification passed in 16s. `CarryBlockMutationTest` proves attachment rejection before save, silent disk-failure rollback (`write:818 -> save failure -> write:818`), committed placement ordering (`write:818 -> save -> notify`) with the new owner map already visible, no onPlace callback for moved pumpkin, exact native flag masks, old flags 2/3 exposing the unsafe callback, and nested/exceptional projection-scope restoration to normal flags 2.
- `native-mutation-check.log`: **full `:server:check :server:assemble` passed in 45s, 35 tasks (33 executed, 2 up to date)**, including `CarryBlockMutationTest` and all prior production carry/native snapshot tests. Existing Java/JOML Unsafe notices and expected microvoxel hardening fixture warnings remain; there are no failed tests in the successful run.

Exact integration API supplied to root:

```java
CarryLedger.Effect CarryWorld.removalEffect(ServerLevel level, BlockPos pos, CarryLedger.Snapshot snapshot);
CarryLedger.Effect CarryWorld.placementEffect(ServerLevel level, BlockPos pos, CarryLedger.Snapshot snapshot, int turns);
// Optional post-durable notification hook, default no-op for inventory-only effects:
void CarryLedger.Effect.committed();
```

The Manager integration is already applied. No additional root call is required. Do not call the private raw writes directly. No client file, version, sync section, live server, deployment or publication was changed by this fix.

Remaining practical limit: native post-commit notification failures retain the successfully durable owner state and are logged, because restoring ownership then would duplicate the committed world object. The previous independent world/player/voxel-save hard-crash limitation still applies; this fix addresses caught failures and native callback isolation, not a cross-store power-loss transaction.
