# Physical Carry Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Complete visible one- and two-hand carrying, placement, player handoff and animated concept-01 feedback.

**Architecture:** A server-owned carry manager holds persistent scene props and large object snapshots. Native display entities mirror the state; small held items use the real inventory hand. Shared bounded payloads feed client targeting, action UI and carry poses, with no client authority over object contents.

**Tech Stack:** Fabric 26.1.2, Java 25, Gson, existing JavaExec headless verification tasks.

**Spec:** `docs/superpowers/specs/2026-10-17-physical-carry-design.md`

## Global Constraints

- Do not start a game client or interrupt the running local server.
- Work only under authoritative `fabric-server` and feature documentation/assets; preserve the dirty animation work.
- No new dependencies. Do not publish or sync unfinished artifacts into running server mods.
- Implement the exact wire contract in the spec. Client and server packages have distinct ownership.
- Work in the existing `codex/rp-animations-v3` checkout, preserving its untracked animation dependencies. Do not commit unrelated files or require another user approval.

## Review Focus

1. Loss/duplication after failed placement, simultaneous offers, logout or restart: test production ownership/persistence transitions.
2. Inventory changes while a palm item is held/offered: verify identity and clear stale state.
3. Block entities and microvoxels: preserve data, reject unsupported multi-blocks, never duplicate drops.
4. Carry arm poses after authored locomotion and armor: apply after animation and keep hand item transforms attached.
5. Asset at narrow HUD sizes and different FPS: valid UVs, smooth reversible fades and bounded press/pulse values.

### Task 1: Server authority, shared wire and headless state tests

**Files:** create `fabric-server/common/src/main/java/ua/rp/chat/carry/*`; create `fabric-server/server/src/main/java/ua/rp/chat/carry/*`; scoped edits to `RPChat.java`, `ItemPickupManager.java`, microvoxel bridge if needed, server Gradle test task; tests under common/server carry test packages.

**Interfaces:** consumes the spec wire contract; produces payload records and a registered manager, native derived displays and sync snapshots. Root owns client implementation; do not edit client files or client build.gradle.

- [x] Write and run failing headless transition/wire/persistence tests covering review focus 1–3.
- [x] Implement bounded shared codecs and pure ownership/persistence logic.
- [x] Implement real server adapters, block/voxel snapshots, item/display placement, lifecycle, handoffs and validation.
- [x] Run focused server verification and compile. Do not invoke `build` (it syncs runtime jars).
- [x] Write report and obtain independent review; fix verified findings.

### Task 2: Animated client asset, interaction UI, preview and poses

**Files:** create client `carry/*` and `mixin/carry/*`; modify client entrypoint/mixin JSON, pickup integration, client Gradle verification; copy original concept01 PNG into resources. Root implements this while the server task runs; no overlapping source edits.

**Interfaces:** consume frozen shared payloads and JSON snapshots. Client actions contain only intent/target hints. Poses consume server modes and actual selected hand; native server display handles object rendering.

- [x] Write failing pure timing/rotation/pose/targeting tests for review focus 4–5.
- [x] Build frame-rate independent feedback animator and use the actual selected texture UV region.
- [x] Implement target selection, RMB/Shift+RMB integration, V action screen, placement preview/rotation and handoff feedback.
- [x] Layer carry arm poses after locomotion/armor, keeping small vanilla item use functional.
- [x] Compile and run focused client checks without game launch.

### Task 3: Integrated verification and delivery

**Files:** `docs/physical-carry.md`, feature verification logs under `art/physical_carry_2026-10-17/`, scoped fixes from review.

- [x] Review server/client contract and lifecycle integration against spec.
- [x] Run `:server:check :client:check :server:assemble :client:assemble --offline --console=plain`, fix failures.
- [x] Have a fresh reviewer inspect ownership, native mixin signatures, snapshot restoration and visual composition; fix/recheck findings.
- [x] Inspect both packaged JARs for resources/payloads/mixins and document controls and tested limitations.
- [x] Report camera release verification plus finished carry implementation and headless test evidence. No game launch or automatic feature publication.

Completed 2026-10-18. Final joint run: BUILD SUCCESSFUL, 42 seconds, 84 actionable tasks (71 executed, 13 up-to-date). Independent follow-up approved with no confirmed P1/P2 findings. Packaged client 1.5.68/server 1.5.34 inspected and hashed; see `art/physical_carry_2026-10-17/artifact-inspection.json`. Runtime/distribution activation remains outside this implementation run.
