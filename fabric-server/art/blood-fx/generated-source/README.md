# Blood decal source library v2

These ImageGen sheets are art sources, not runtime atlases.  Each contains
exactly eight isolated silhouettes in a 4×2 layout:

- `blood_splats_low_*`: drops, compact pools and short smears.
- `blood_splats_medium_*`: impact stars, directional streaks and arcs.
- `blood_splats_high_*`: large bursts, sprays and multi-lobe impacts.

`*_source_v2.png` is the preserved chroma-key generation. `*_alpha_v2.png` is
the locally key-removed intermediate.

Run the deterministic converter from `fabric-server/client-mod-gradle`:

```powershell
python .\tools\build_blood_splat_library.py
```

It produces 24 families × 4 coherent stages as 32×32 RGBA runtime sprites:

```text
splat_<l|m|h><00..07>_<fresh|settled|drying|dry>.png
```

The conversion deliberately quantizes every sprite to five authored colors,
hard alpha and nearest-neighbour texels. Dry stages retain the same identity;
they do not ask a generator to redraw the stain.
