# Carver tool concepts — Blockbench handoff

Five separate concept sheets were generated using the user-provided Minecraft vehicle image as a **quality and texturing reference only**. Each sheet shows multiple orthographic views and a larger perspective view of one tool. These are visual concepts, not exact technical projections: use the dimensions below to resolve any discrepancy between drawn views.

## Shared construction rules

- Build from cuboids and stepped bevels. Keep the main silhouette readable from Minecraft's normal third-person camera distance.
- Work in Blockbench model units, with 16 units corresponding to one Minecraft block. Treat the dimensions below as an initial proportion guide and adjust for the character hand and the existing carver bag.
- For every tool, set the local handle/grip axis to **Y**. Put the grip end near Y=0 and the working end toward positive Y. Rotate the concept sheet as needed when translating it; the art sometimes presents the working tip downward.
- Use the same material palette across the kit: warm dark walnut, charcoal forged steel, narrow worn-silver working edges, muted brass ferrules, and oxblood leather. Keep the brightest pixels on cutting edges and contact faces.
- Give every model its own `thirdperson_righthand`, `thirdperson_lefthand`, `firstperson_righthand`, `firstperson_lefthand`, `gui`, and `fixed` display transforms. Check the tool's contact point against the animated hand and workpiece before finalizing those transforms.
- Use a 64×64 pixel atlas as a starting point, with contiguous pixel clusters and limited colors; inspect the model at native Minecraft scale rather than only in a zoomed Blockbench viewport.

| File | Tool | Starting proportions (model units) | Key silhouette |
| --- | --- | --- | --- |
| `01_mallet.png` | Striking mallet | 24 high; head 14×5×5; grip 3×17×3 | Broad horizontal rectangular head on a short handle |
| `02_point_chisel.png` | Point chisel | 24 high; grip 4×11×4; steel shaft and tip 10–12 | Square shaft narrowing in steps to one point |
| `03_flat_chisel.png` | Flat chisel | 23 high; grip 4×10×4; blade 7×7×1 | Broad straight bright edge, thin side profile |
| `04_fine_graver.png` | Precision graver | 18 high; palm grip 4×10×4; nib 2×6×2 | Compact palm grip and short V-shaped cutting tip |
| `05_material_scoop.png` | Placement scoop | 21 long; grip 3×10×3; tray 8×5×2 | Open, shallow rectangular tray on a narrow neck |

The point and flat chisels have striking caps so they can be paired with the mallet. The graver is a hand-pressure tool. The scoop's four tray walls and inner floor should be separate visible cuboids; its cavity is the most important feature.

## Image generation prompt set

All sheets were generated with the built-in image generation tool. The common prompt requested one mechanically coherent Minecraft Blockbench tool per sheet, six orthographic views (front, back, left, right, top, bottom), a three-quarter hero view, cuboid geometry, pixel-textured walnut/steel/brass/leather, a dark neutral background, and no characters or text. The attached vehicle was specified solely as a reference for modeling and texture quality.

- Mallet: short one-handed walnut handle, squat rectangular oak head, dark-steel bands and muted brass rivets.
- Point chisel: square walnut grip, oxblood wrap, brass ferrule, steel striking cap and a narrow stepped point.
- Flat chisel: matching grip and striking cap, wide straight blade with a bright cutting bevel and visibly thin sides.
- Fine graver: shorter palm grip, small V-shaped steel nib, brass collar, and no striking cap.
- Material scoop: short grip, narrow neck, and a shallow open-top rectangular dark-steel tray.
