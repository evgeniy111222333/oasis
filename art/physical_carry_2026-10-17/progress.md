# SDD ledger — plan: docs/superpowers/plans/2026-10-17-physical-carry.md

Camera publication verified before implementation: 1.5.67, release 20261017-201258, valid signatures and exact downloaded JAR hashes on R2 and GitHub commit 4210d6d. Optional VPS 522. Full prior camera checks passed.

Ruling: Work in the existing codex feature checkout — animation dependencies are dirty/untracked and are required by the feature, so a clean worktree would omit them. No broad staging/commits.
Ruling: User's explicit instruction to implement fully and act authorizes implementation without plan-approval pauses. Do not publish the new mechanic under the earlier camera publication request.
Ruling: Root implements Task 2 while one server implementer owns Task 1; no multiple implementation subagents in parallel, no overlapping source files.

| Tasks | Interface/shared file | Scan |
| --- | --- | --- |
| 1 / 2 | Shared carry payloads + snapshot JSON | Frozen in spec; server owns common package, client consumes it |
| 1 / 3 | Server Gradle/check | Task 3 runs after Task 1 finishes |
| 2 / 3 | Client Gradle/check | Task 3 runs after Task 2 finishes |
| 1 | State tests vs native adapters | Tests must invoke production transitions, not a duplicate model |
| 2 | Client timing vs rendering | Pure animator drives the real HUD; pose math drives the real mixins |
| 3 | Build vs local server | Assemble only; no build finalizer or game launch |

Tasks 1–3 complete on 2026-10-18. Base checkout HEAD 3a4d850b; preexisting dirty changes preserved. No commits or broad staging.

- Server implementation completed by carry_server; root integrated shared placement, native block visuals, transactional prop collection, bounded UTF-8 synchronization and validated initial recovery points.
- Client implementation completed by root: original concept-01 texture with time-based animation, targeting, menu/consent, native placement preview, one-/two-hand articulated poses and interpolated carried display attachment.
- Independent reviewer requested four native integration fixes and one adjacent recovery fix. All were implemented; final scoped verdict approved, no confirmed P1/P2 findings. Original evidence is preserved in review-report.md.
- Final joint command `:server:check :client:check :server:assemble :client:assemble --offline --console=plain`: BUILD SUCCESSFUL in 42s; 84 actionable tasks, 71 executed and 13 up-to-date. Log: verification.log.
- Focused native mutation, multibyte packet budget and recovery tests passed; client tests cover six frame rates, native hook signatures, original asset hash, and 64 chest/shulker orientations.
- Packaged artifacts independently inspected by verify_artifacts.py: client 1.5.68 (5,494,924 bytes), server 1.5.34 (15,649,992 bytes). Paths, SHA-256 and asset/mixin/locale checks are recorded in artifact-inspection.json.
- User instructions and configuration documented in docs/physical-carry.md, including single-volume sculpture support and graceful-restart versus cross-store power-loss limits.
- No Minecraft launch, no game test run, no running-server interruption, no runtime mod copies and no new publication. Published camera client remains the earlier verified 1.5.67; carry artifacts require activation together.

## Publication and activation follow-up — 2026-10-18

The user subsequently asked about publication and requested starting the server. The earlier implementation-only activation boundary is superseded for this follow-up. Invoked the documented full `deployment/publish_update.py` workflow with Russian launcher release notes; its clean/check/build pass succeeded in 48s (87 tasks) and synchronized both runtime distributions. Existing generated distribution Git metadata was moved intact into a timestamped backup under this artifact directory before rebuilding the generated output.

Local server launched hidden with `fabric-server/run.ps1`, Java 25, new server 1.5.34. PID 3500 owns ports 25565/25580. Native status handshake succeeded for Minecraft 26.1.2/protocol 775; carry ledger and configuration initialized. Runtime JAR checksum equals the tested artifact. Evidence: server-start.stdout.log, server-start.stderr.log, server-launch.json, local-server-verification.json. No game client was launched.

Publication release ID: `20261018-095907`. Full publisher finished successfully. R2 and Google Drive uploaded, GitHub dist commit starts `69da824`. Public downloaded manifests have valid signatures and the downloaded client JAR has the exact tested size/hash on R2 and GitHub, including carry/camera classes and unchanged concept-01 asset. All three local client distribution copies match the tested JAR. Remote VPS server deployment and mirror remain unavailable (SSH/HTTP timeouts, best-effort). Evidence: publication.log, published-carry-verification.json, publication-verification.log, local-server-verification.json, publication-result.json.
