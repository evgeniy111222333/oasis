# Eclipse astrolabe TAB

The client implements `astrolabe_tab_concept8.html` as a native Minecraft overlay.
The assembly, ticks, crosshairs, glow, eclipse and unfolding animation come from the concept.
The background is the actual game world. The top pill reads `ВСЕ В МИРЕ (N)`;
`Day`, the phase and `Online` stay English. There is no nearby-RP filter, corner label or paging footer.

## Behavior

- Hold the native player-list binding (TAB by default); rebinding it in Controls works.
- Five real character faces appear on each of two arcs, with the native face and hat layers.
  Portraits use the existing character appearance system. Smaller viewports reduce visible slots.
- The own character sorts first. Other entries use safe server RP labels, including concealed identities.
  Equal labels use UUID as a stable tie-breaker. An authoritative empty roster stays empty.
- Wheel scrolling advances one character; PgUp/PgDn advance one visible orbit. The hotbar stays unchanged.
  Paging keys can be rebound under Multiplayer. Every opening starts at the beginning.
- The reference outer circle is 530 physical pixels across at Full HD, close to this size in ordinary
  windows, and 1060 pixels across at 4K. Minecraft GUI Scale does not pixelate the text.
- Opening takes 850 ms and closing 650 ms, with staggered layers and continuous ring rotation.
  Releasing/repressing reverses the animation. Screens, hidden GUI, focus loss and disconnect suppress it.
- Long labels use a Unicode ellipsis. The English day/phase line wraps inside the core when necessary.
  The header centers the visible letter contours rather than unused font ascent/descent space.
  Names use the bundled Source Serif 4 Semibold at 22 physical pixels and the header at 16 pixels
  (at Full HD). Core time uses 20 pixels, Online 16 pixels, and the secondary realm label 12 pixels.

## Rendering and cost

There is no browser in TAB and no additional server protocol. The existing acquaintance payload supplies
UUID, safe label and self flag. Unchanged snapshots preserve the immutable sorted list and revision.
Only the current ten portraits are resolved, at most twice per second and immediately on orbit changes.
Appearance fetching remains asynchronous; drawing performs no network calls.

SVG layers are baked into cached 1024-pixel PNGs with linear filtering. One native Minecraft GPU blur
softens the actual world, followed by dimming and a radial scrim. Layout and fitted labels are computed
only when viewport, orbit, roster revision or resource generation changes.
`tools/build_astrolabe_tab_assets.py` rebuilds the textures from `tools/astrolabe-tab-svg/`.

Text bypasses Minecraft's glyph atlas. Both Source Serif 4 Regular and Semibold are bundled under
the included SIL Open Font License, giving every platform the same typography. Unsupported glyphs
use the platform serif fallback. Java rasterizes antialiased coverage at four times physical size,
then averages the coverage into a 2x texture. Native LINEAR filtering now integrates all source
coverage at the displayed size instead of skipping thin strokes in an unfiltered 4x texture.
The cache holds at most 64 labels and 4,194,304 source pixels (16 MiB of GPU
RGBA data), with active labels protected from eviction. Unchanged frames do not rasterize or upload.
Resource reload releases old text textures, reloads the font and rebuilds current metrics.
Font weight is part of both fit metrics and the texture-cache key. The former decorative bottom HUD
and stamina gauge have been removed; vanilla hotbar, health and hunger render normally.

## Verification

From `fabric-server/`:

```powershell
.\gradlew.bat :client:verifyCompactTab --offline
.\gradlew.bat :client:runClientGameTest -PtabGameTest --offline
.\gradlew.bat :client:check :client:build --offline
```

Regular checks cover viewport bounds, orbit coverage for 0-1000 entries, immutable caching, concealed
labels, Unicode truncation, actual smooth Cyrillic/Latin raster edges, metrics, clock phases, bounded
portrait polling and reversible animations at 15-240 FPS.
It also checks that native bilinear minification preserves total Cyrillic/Latin stroke coverage.

The optional real Minecraft suite checks custom face/hat textures, opening/closing, 1280x720, Full HD,
4K, GUI Scale 2/4, long names, 1000 entries, GPU linear text filtering, cache reuse/eviction,
wheel/hotbar isolation, PgUp/PgDn, live shrink, empty roster and resource reload. It saves native
screenshots. Test fixtures are not included in the shipped mod JAR.
The suite additionally captures a single named character against actual textured grass and checks
that Full-HD header lettering is large enough to read.

The regular build synchronizes the JAR and hashes into all three local launcher distribution roots.
Publish this client-only change through `deployment/publish_update.py --client-only`.
