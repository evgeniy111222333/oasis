# Physical carry verification — 2026-10-18

Final result: client **1.5.68** and server **1.5.34** assembled successfully. Independent scoped review approved; no remaining confirmed P1/P2 findings.

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-25.0.3'
.\gradlew.bat :server:check :client:check :server:assemble :client:assemble --offline --console=plain
```

Run from `D:\23\fabric-server`. Final run: **BUILD SUCCESSFUL in 42s**, **84 actionable tasks: 71 executed, 13 up-to-date**. Complete output is in `verification.log`. Native corruption/listener-failure fixtures deliberately log warnings/errors while testing isolation; their suites pass. JOML also emits its existing Java 25 Unsafe deprecation warning.

Carry-specific server coverage runs the real rules, wire codecs, ledger/file store, inventory components, block-entity NBT snapshots, voxel rotation, silent native mutations, exact UTF-8 snapshot builder and recovery search. Attached wall-torch survival is checked with actual native block state logic. Neighbor notifications occur after persistence; rollback writes use the same silent flags. An immediate restart keeps a validated recovery location even when the forward display point is blocked.

Client coverage checks the original concept-01 asset bytes/transparency/UVs and registered native hooks, six presentation frame rates, obstruction/range and preview yaw, articulated ModelPart joints/wearables, full-scale portable voxel display, 64 native chest/shulker orientations and shared lantern/grass/water placement validity. All existing camera, animation and other feature checks also passed.

`verify_artifacts.py` inspected both final JARs, Java 25 class versions, Fabric versions, common payload classes, client mixin registration, translated keys and unchanged asset SHA-256. `artifact-inspection.json` contains exact artifact sizes and hashes. No test-only carry classes are packaged.

Review evidence is in `review-report.md`; the initial four findings and follow-up recovery finding remain recorded below the final approval. Server implementation report is in `task-1-report.md`. User-facing controls, supported objects, configuration and persistence limits are documented in `docs/physical-carry.md`.

No client or server was launched for these tests; no game rendering/multiplayer playtest was performed. Running-server mods and published distributions were not replaced. The earlier camera release 1.5.67 was separately published and verified before carry work. The new carry pair is built and ready for a coordinated activation, with no automatic publication under that prior request.

## Subsequent authorized publication and local server start

The user then requested publication status and starting the server. The documented full publisher succeeded: 48s clean/check/build (87 tasks), synchronized server 1.5.34 and client 1.5.68, release `20261018-095907` uploaded to R2/Drive/GitHub. Downloaded R2 and immutable-commit GitHub manifests were signature-verified; downloaded 5,494,924-byte client JARs match SHA-256 `eb7a09277c17abe309c7ec90d9608a6252b22eea49153656ebb17767c5227a6b`. All three local client distribution copies match. Remote VPS gameplay deployment/mirror failed connection and remain unchanged.

Local server launched hidden from the normal run.ps1 with the verified 1.5.34 runtime JAR. Its status handshake reports Minecraft 26.1.2/protocol 775, game port 25565 and auth port 25580 are listening, and the carry ledger/configuration initialized without carry startup errors. The game client was never launched. Public and runtime evidence is recorded in published-carry-verification.json, local-server-verification.json and publication-result.json.
