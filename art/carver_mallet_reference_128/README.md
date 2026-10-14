# Carver mallet — reference model

- `carver_mallet.bbmodel`: editable Blockbench project with embedded texture.
- `carver_mallet.geo.json`: GeckoLib-targeted Bedrock geometry 1.12.0; identifier `geometry.carver_mallet`.
- `carver_mallet.png`: single 128 × 128 RGBA pixel-art texture, no interpolation.
- `preview.png`, `preview_side.png`: actual Blockbench renders.
- `build_blockbench.js`: deterministic construction script; run once in an empty Bedrock project through Blockbench JavaScript API.

177 cubes, 7 bones, root `mallet`, pivot `[0, 6, 0]`. Head, hoops, socket, handle, pommel and rivets are separate child bones. Bevels use rotated cubes for GeckoLib compatibility; the small three-way corner transitions are cube approximations.

Suggested resource locations under your mod namespace:

```
assets/<modid>/geo/carver_mallet.geo.json
assets/<modid>/textures/item/carver_mallet.png
```

The mod renderer must reference these resources. This delivery does not add item registration, renderer code or animations.

Validated: PNG dimensions, JSON parsing, unique bones and parent references, all positive cube dimensions, all 1,062 face UV rectangles within the atlas. Visually inspected in Blockbench from front, back, side and three-quarter views. In-game rendering has not been tested.
