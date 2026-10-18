# First-person sprint camera correction — client 1.5.67

Input: `C:\Users\HP\AppData\Roaming\.eclipse-rp\screenshots\2026-10-17_22.57.06.png`.

The authored sprint shifts the rendered neck 0.34–0.48 blocks forward while the
old camera used a fixed 0.25-block offset. The camera therefore moved behind the
neck and the downward view intersected the player's own torso. Native camera
update also precedes avatar extraction, so following the previously extracted
neck retained a one-frame lag, observable during sprint startup at 15 FPS.

The corrected camera prepares the current native avatar state before placement,
follows its horizontal neck displacement and retains the previous vertical
stabilization and camera-volume collision sweep. Backward braking never pulls
the camera behind its original forward clearance. Authored crouch no longer
gets the additional native crouch lean offset. A per-avatar exact-input frame
cache shares the immutable animation/contact solution across camera and render
extraction without advancing it twice. No server protocol or gameplay change.

## Verification

- `gradlew.bat :client:check :client:assemble --offline --console=plain`: passed.
- The authoritative client-only publishing command repeated `:client:check :client:build`: passed.
- `verifyFirstPersonCameraAnchor`: 159,720 view samples over all 22 clips,
  directions/scales, invalid input and native fallback. The old fixed camera
  reproduced 276 downward torso intersections; the corrected anchor had zero.
- Startup/braking and repeated extraction tested at 15/30/60/144 FPS, with all
  sampled bone transforms compared to an independent once-per-frame stream.
- Same-time position, support, scale and preview changes invalidate the cache.
- `python -m unittest test_build_distribution` from `deployment/`: 2 tests passed.
- Independent read-only review: no actionable P1/P2 findings.
- No game/client was started. Native visual appearance was not rechecked in game.

## Release

Published with the documented `deployment/publish_update.py --client-only` workflow.
Release ID: `20261017-201258`.
Artifact: `eclipse-client-1.5.67.jar` (4,425,648 bytes).
SHA-256: `37720c049b585e7e4f8e827beb75586b9a82c2f4617bbe05ef6fdfaae3e1ed49`.

Logs: `verification.log` and `publish_update.log`.
Run `python art/first_person_sprint_2026-10-17/verify_public_release.py` from the
repository root after publication to verify launcher signatures and downloaded
JAR bytes on R2/GitHub/VPS. Results are written to `public-verification.json`.
