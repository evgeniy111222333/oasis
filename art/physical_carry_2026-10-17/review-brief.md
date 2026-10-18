# Physical carry integration review

Read the spec at `D:\23\docs\superpowers\specs\2026-10-17-physical-carry-design.md`; user authorized complete one-/two-hand carrying and chose original pickup highlight concept 01, animated. They prohibit entering or launching a game client. New mechanic is not yet published and must not interrupt the existing local server.

Review only this feature, not unrelated dirty/untracked animation/deployment work. Root implemented client; a single implementer implemented server/shared. No commits: inspect actual files. Task 1/2 complete enough for review; focused and full client/server check/assemble have passed (final joint run follows fixes).

Server/common feature surface:
- `fabric-server/common/src/main/java/ua/rp/chat/carry/*`
- shared relocated unchanged `common/src/main/java/ua/rp/chat/microvoxel/MicrovoxelPortableVolume.java` (removed client main copy)
- `fabric-server/server/src/main/java/ua/rp/chat/carry/*`, `microvoxel/MicrovoxelCarryBridge.java`
- surgical integrations in `RPChat.java`, `interaction/ItemPickupManager.java`, public visibility of `acquaintance/AcquaintanceManager.isBound`, server Gradle tests.
- server carry tests in `server/src/test/java/ua/rp/chat/carry` and common carry tests.

Client feature surface:
- `fabric-server/client-mod-gradle/src/client/java/ua/rp/chat/client/carry/*`
- new `mixin/carry/*`
- added calls in existing `mixin/render/{AvatarAnimationRendererMixin,PlayerModelMixin,HumanoidModelBreathingMixin,PlayerItemInHandLayerMixin,ItemStackRenderStateMixin}.java`
- `animation/PlayerArmorAnimationRig.applyCarryArms`, `mixin/ItemLayerRenderStateAccessor` applyTransform invoker
- pickup old-server fallback and GroundedLootRenderer native outline color
- `src/main/java/ua/rp/chat/microvoxel/MicrovoxelItemScale` NONE now1.0 for full size world displays
- entrypoint/mixin JSON, original PNG asset `src/main/resources/assets/eclipseclient/textures/gui/carry/concept_01.png`, three keybinding translations, client Gradle tests
- `src/test/java/ua/rp/chat/carry/{CarryPresentationTest,CarryNativeContractsTest}.java`

Focus on actionable P1/P2 integration defects: ownership conservation and rollback/native side effects, actual container/voxel preservation, stale or spoofed actions/offers, auth/bound/busy restrictions, lifecycle/display duplication, network bounding, preview/server geometry and yaw agreement, articulated armor/skin pose ordering and normal small-item use, native render submit/extraction contracts. Tests don't launch actual client; reflect and use local 26.1.2 javap if unsure. Potential crash consistency between separate vanilla world saves and atomic carry file is distinct from the tested graceful shutdown/restart; do not invent a guarantee, report material findings accurately.

Do not edit files, spawn subagents, publish, start Minecraft or restart a server. Write findings to `D:\23\art\physical_carry_2026-10-17\review-report.md` with severity, exact file/line, scenario, expected correction and evidence; separate optional minor suggestions. Send immediate concrete blockers to root. Final response concise verdict plus report path.
