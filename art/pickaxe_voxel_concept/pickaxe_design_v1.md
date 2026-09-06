# Voxel pickaxe — design v1

The four-view sheet is a geometry reference, not a baked Minecraft item model.
All four quadrants describe the same tool.

## Proposed Blockbench proportions

- Overall height: 30 model pixels.
- Head span: 18 model pixels; maximum thickness: 3 pixels.
- Central forged socket: 3.5 × 3 × 3 pixels.
- Handle: 26 × 1.5 × 1.5 pixels with a faceted rectangular section.
- Pointed pick: 8 pixels from the socket, reduced in four stepped segments.
- Chisel/adze: 6 pixels from the socket, widening to a 3.5-pixel cutting edge.
- Lower oxblood wrap: 5 pixels high; collar wrap: 1.5 pixels high.
- Steel butt cap: 2 × 2 × 1.75 pixels.

## Material language

- Forged steel: charcoal base, muted silver working edges, no polished gloss.
- Handle: warm dark ash/walnut with large, low-frequency pixel bands.
- Wrap: oxblood leather using four restrained red-brown tones.
- Geometry: cuboids and stepped wedges only; no smooth cylinders or curves.

The eventual in-game model should use a compact 32×32 atlas and preserve the
front/side/top silhouettes shown in `pickaxe_four_view_concept_v1.png`.
