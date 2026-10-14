"""Paint the Carver Bag atlas and describe its Blockbench cube groups.

Run this file to regenerate carver_bag.png and geometry.json. The latter is
consumed by the Blockbench MCP build step; the saved .bbmodel is the deliverable.
"""

from __future__ import annotations

import json
import random
from pathlib import Path

from PIL import Image, ImageDraw


OUT = Path(__file__).resolve().parent
R = random.Random(2317)
im = Image.new("RGBA", (128, 128), (0, 0, 0, 0))
dr = ImageDraw.Draw(im)

COLORS = {
    "canvas": (99, 83, 67),
    "canvas_hi": (131, 111, 88),
    "canvas_dark": (61, 52, 44),
    "flap": (104, 43, 45),
    "flap_hi": (151, 70, 66),
    "flap_dark": (60, 28, 33),
    "leather": (68, 42, 34),
    "leather_hi": (109, 66, 46),
    "leather_dark": (40, 27, 26),
    "brass": (180, 128, 59),
    "brass_hi": (234, 184, 92),
    "brass_dark": (91, 62, 39),
    "steel": (143, 151, 145),
    "steel_hi": (220, 219, 198),
    "steel_dark": (72, 80, 79),
    "wood": (150, 98, 59),
    "wood_hi": (197, 140, 79),
    "wood_dark": (88, 51, 39),
    "thread": (210, 177, 120),
}

# All coordinates are 128x128 atlas pixels. UV rectangles intentionally avoid
# transparent pixels and are assigned explicitly to cube faces in Blockbench.
UV = {
    "body_front": [0, 0, 32, 32],
    "body_back": [32, 0, 64, 32],
    "body_side": [64, 0, 80, 32],
    "body_top": [80, 0, 112, 16],
    "body_bottom": [112, 0, 128, 16],
    "flap_front": [0, 32, 32, 48],
    "flap_back": [32, 32, 64, 48],
    "flap_top": [64, 32, 96, 48],
    "pocket_front": [96, 32, 112, 48],
    "gusset": [112, 32, 128, 48],
    "leather": [0, 64, 16, 80],
    "red_leather": [16, 64, 32, 80],
    "edge": [32, 64, 48, 80],
    "brass": [48, 64, 64, 80],
    "wood": [64, 64, 80, 80],
    "steel": [80, 64, 96, 80],
    "thread": [96, 64, 112, 80],
    "dark": [112, 64, 128, 80],
    "red_trim": [0, 80, 16, 96],
    "brass_dark": [16, 80, 32, 96],
}


def textured_rect(box, base, spread=8, border=None):
    x0, y0, x1, y1 = box
    for y in range(y0, y1):
        for x in range(x0, x1):
            n = R.randint(-spread, spread)
            # Broad irregular shade patches stay readable at native resolution.
            n += 4 if ((x // 5 + y // 6) % 3 == 0) else -2
            im.putpixel((x, y), tuple(max(0, min(255, c + n)) for c in base) + (255,))
    if border:
        dr.rectangle((x0, y0, x1 - 1, y1 - 1), outline=border + (255,))


for name, base, spread in [
    ("body_front", COLORS["canvas"], 10),
    ("body_back", COLORS["canvas_dark"], 10),
    ("body_side", COLORS["canvas"], 9),
    ("body_top", COLORS["canvas_hi"], 8),
    ("body_bottom", COLORS["canvas_dark"], 6),
    ("flap_front", COLORS["flap"], 8),
    ("flap_back", COLORS["flap_dark"], 6),
    ("flap_top", COLORS["flap_hi"], 7),
    ("pocket_front", COLORS["leather_hi"], 9),
    ("gusset", COLORS["canvas_dark"], 8),
    ("leather", COLORS["leather"], 7),
    ("red_leather", COLORS["flap"], 6),
    ("edge", COLORS["leather_hi"], 7),
    ("brass", COLORS["brass"], 5),
    ("wood", COLORS["wood"], 8),
    ("steel", COLORS["steel"], 5),
    ("thread", COLORS["thread"], 4),
    ("dark", COLORS["leather_dark"], 4),
    ("red_trim", COLORS["flap_hi"], 6),
    ("brass_dark", COLORS["brass_dark"], 4),
]:
    textured_rect(UV[name], base, spread)

# Canvas weave, shaded corners and hand stitching.
for name in ("body_front", "body_back", "body_side"):
    x0, y0, x1, y1 = UV[name]
    for y in range(y0 + 2, y1 - 2, 3):
        for x in range(x0 + 2 + (y % 2), x1 - 2, 4):
            dr.point((x, y), fill=COLORS["canvas_hi"] + (255,))
    dr.line((x0, y0, x1 - 1, y0), fill=COLORS["canvas_hi"] + (255,))
    dr.line((x0, y1 - 2, x1 - 1, y1 - 2), fill=COLORS["canvas_dark"] + (255,))
for x in range(3, 30, 4):
    dr.line((x, 2, x + 1, 3), fill=COLORS["thread"] + (255,))
    dr.line((x, 29, x + 1, 30), fill=COLORS["thread"] + (255,))
for y in range(5, 28, 4):
    dr.line((2, y, 3, y + 1), fill=COLORS["thread"] + (255,))
    dr.line((28, y, 29, y + 1), fill=COLORS["thread"] + (255,))

# Burgundy flap, stepped edge and an etched carver's chisel emblem.
dr.rectangle((1, 33, 30, 46), outline=COLORS["flap_dark"] + (255,))
dr.line((2, 34, 29, 34), fill=COLORS["flap_hi"] + (255,))
dr.line((3, 44, 28, 44), fill=COLORS["flap_dark"] + (255,))
for x in range(4, 29, 4):
    dr.line((x, 45, x + 1, 45), fill=COLORS["thread"] + (255,))
# Offset the emblem from the central buckle so it stays visible.
dr.polygon([(8, 36), (12, 40), (8, 44), (4, 40)], outline=COLORS["brass"] + (255,))
dr.line((8, 37, 8, 41), fill=COLORS["brass_hi"] + (255,))
dr.polygon([(7, 41), (9, 41), (8, 43)], fill=COLORS["brass_hi"] + (255,))
dr.rectangle((33, 33, 62, 46), outline=COLORS["flap_dark"] + (255,))
dr.line((65, 34, 94, 34), fill=COLORS["flap_hi"] + (255,))

# Leather grain and purposeful material details.
for name in ("pocket_front", "leather", "red_leather", "edge", "wood"):
    x0, y0, x1, y1 = UV[name]
    for _ in range((x1 - x0) * (y1 - y0) // 12):
        x = R.randrange(x0 + 1, x1 - 1)
        y = R.randrange(y0 + 1, y1 - 1)
        dr.point((x, y), fill=COLORS["leather_hi"] + (255,) if name != "wood" else COLORS["wood_hi"] + (255,))
for y in (36, 39, 42, 45):
    dr.point((98, y), fill=COLORS["thread"] + (255,))
    dr.point((109, y), fill=COLORS["thread"] + (255,))
for x in (67, 71, 75, 78):
    dr.line((x, 66, x, 77), fill=COLORS["wood_dark"] + (255,))
dr.line((65, 65, 79, 65), fill=COLORS["wood_hi"] + (255,))

# Metallic swatches have hard pixel highlights, not soft gradients.
for name, hi, lo in [
    ("brass", COLORS["brass_hi"], COLORS["brass_dark"]),
    ("steel", COLORS["steel_hi"], COLORS["steel_dark"]),
]:
    x0, y0, x1, y1 = UV[name]
    dr.line((x0 + 1, y0 + 1, x1 - 3, y0 + 1), fill=hi + (255,))
    dr.line((x0 + 1, y0 + 1, x0 + 1, y1 - 3), fill=hi + (255,))
    dr.line((x1 - 2, y0 + 2, x1 - 2, y1 - 2), fill=lo + (255,))
    dr.line((x0 + 2, y1 - 2, x1 - 2, y1 - 2), fill=lo + (255,))
for x in range(98, 112, 3):
    dr.line((x, 66, x + 1, 67), fill=COLORS["leather_dark"] + (255,))

im.save(OUT / "carver_bag.png")

parts = []


def cube(name, group, material, a, b, rotation=None, origin=None):
    assert all(a[i] < b[i] for i in range(3)), name
    obj = {"name": name, "group": group, "material": material, "from": a, "to": b}
    if rotation is not None:
        obj["rotation"] = rotation
        obj["origin"] = origin
    parts.append(obj)


# Body is an actual layered satchel silhouette, with slim inset side gussets.
cube("canvas_body", "body", "body", [-5.5, 1.5, -2.3], [5.5, 10.3, 2.3])
cube("back_leather_panel", "body", "dark", [-5.2, 2, -2.6], [5.2, 10, -2.28])
cube("left_gusset", "body", "gusset", [-5.85, 2.2, -1.9], [-5.48, 9.4, 1.9])
cube("right_gusset", "body", "gusset", [5.48, 2.2, -1.9], [5.85, 9.4, 1.9])
cube("bottom_reinforcement", "body", "leather", [-5.7, 1.1, -2.5], [5.7, 2.1, 2.5])
cube("top_dark_opening", "body", "dark", [-5.3, 10.25, -2.1], [5.3, 10.5, 2.1])
cube("rolled_top_lip", "body", "edge", [-5.5, 10.25, -2.55], [5.5, 10.75, -1.9])
for side, x0, x1 in (("left", -5.9, -4.8), ("right", 4.8, 5.9)):
    cube(f"{side}_bottom_corner", "body", "leather", [x0, 1.1, -2.6], [x1, 3, 2.65])
    cube(f"{side}_upper_corner", "body", "leather", [x0, 8.6, -2.55], [x1, 10.2, 2.65])
    cube(f"{side}_side_seam", "body", "thread", [x0 - .03, 3, 1.8], [x0 + .1, 8.4, 1.96])

# The hanging front flap uses separately stepped corners and a visible hem.
cube("flap_top_fold", "flap", "flap", [-5.7, 10.3, -2.4], [5.7, 11.1, 3.0])
cube("flap_face", "flap", "flap", [-5.15, 7.05, 2.5], [5.15, 10.8, 3.17])
cube("flap_left_step", "flap", "flap", [-5.75, 7.7, 2.5], [-5.1, 10.65, 3.14])
cube("flap_right_step", "flap", "flap", [5.1, 7.7, 2.5], [5.75, 10.65, 3.14])
cube("flap_bottom_hem", "flap", "red_trim", [-4.95, 6.83, 3.03], [4.95, 7.2, 3.43])
for x, side in ((-5.45, "left"), (5.45, "right")):
    cube(f"flap_{side}_edge", "flap", "edge", [x - .15, 7.7, 3.1], [x + .15, 10.4, 3.37])
for x in (-4.5, -3.4, -2.3, -1.2, 1.2, 2.3, 3.4, 4.5):
    cube(f"flap_stitch_{x}", "flap", "thread", [x - .1, 6.94, 3.4], [x + .1, 7.07, 3.47])

# Central belt and bright buckle form the main focal point.
cube("front_closure_belt", "closure", "leather", [-.72, 4.2, 3.12], [.72, 10.7, 3.55])
cube("belt_bottom_tip", "closure", "edge", [-.64, 3.95, 3.13], [.64, 4.35, 3.54])
cube("buckle_backplate", "closure", "dark", [-1.24, 7.42, 3.49], [1.24, 9.25, 3.78])
cube("buckle_left", "closure", "brass", [-1.3, 7.35, 3.72], [-.95, 9.35, 4.04])
cube("buckle_right", "closure", "brass", [.95, 7.35, 3.72], [1.3, 9.35, 4.04])
cube("buckle_top", "closure", "brass", [-1.27, 9.05, 3.72], [1.27, 9.38, 4.04])
cube("buckle_bottom", "closure", "brass", [-1.27, 7.32, 3.72], [1.27, 7.65, 4.04])
cube("buckle_tongue", "closure", "brass_dark", [-.05, 8.18, 3.98], [.98, 8.48, 4.15])
cube("belt_keeper", "closure", "red_leather", [-.91, 5.13, 3.47], [.91, 5.57, 3.68])
cube("brass_rivet_low", "closure", "brass", [-.2, 4.42, 3.52], [.2, 4.82, 3.72])

# Two organizer pockets along the lower face. Each has a separate stitched lip.
for side, a, b in (("left", -4.6, -1.45), ("right", 1.45, 4.6)):
    cube(f"{side}_pocket", "pockets", "pocket", [a, 2.1, 2.25], [b, 6.45, 3.16])
    cube(f"{side}_pocket_lip", "pockets", "edge", [a - .1, 6.35, 2.85], [b + .1, 6.78, 3.37])
    cube(f"{side}_pocket_bottom", "pockets", "dark", [a + .1, 2.02, 3.08], [b - .1, 2.26, 3.3])
    for x in (a + .22, b - .28):
        cube(f"{side}_pocket_seam_{x}", "pockets", "thread", [x, 2.65, 3.14], [x + .09, 5.9, 3.24])

# Tool loops on the left and a shallow awl/sheath on the right break the outline.
for side, x0, x1 in (("left", -6.5, -5.82), ("right", 5.82, 6.5)):
    cube(f"{side}_tool_pouch", "tools", "leather", [x0, 3.0, -.45], [x1, 7.0, 1.35])
    cube(f"{side}_tool_pouch_lip", "tools", "edge", [x0 - .07, 6.8, -.55], [x1 + .07, 7.2, 1.45])
    cube(f"{side}_tool_handle", "tools", "wood", [x0 + .17, 6.95, .05], [x1 - .17, 10.35, .8])
    cube(f"{side}_tool_ferrule", "tools", "brass", [x0 + .13, 9.7, .0], [x1 - .13, 10.2, .86])
    cube(f"{side}_tool_blade", "tools", "steel", [x0 + .24, 10.2, .18], [x1 - .24, 11.9, .68])

# Shoulder strap rises above the bag and attaches through brass rings.
for side, x0, x1 in (("left", -5.65, -4.65), ("right", 4.65, 5.65)):
    cube(f"{side}_strap_anchor", "strap", "leather", [x0, 8.35, -2.9], [x1, 10.75, -2.48])
    cube(f"{side}_strap_rivet", "strap", "brass", [(x0+x1)/2-.24, 9.25, -3.1], [(x0+x1)/2+.24, 9.73, -2.88])
cube("strap_left_riser", "strap", "leather", [-5.4, 10.45, -2.65], [-4.48, 14.05, -1.9], [0, 0, -15], [-4.9, 10.45, -2.25])
cube("strap_right_riser", "strap", "leather", [4.48, 10.45, -2.65], [5.4, 14.05, -1.9], [0, 0, 15], [4.9, 10.45, -2.25])
cube("strap_top_arc", "strap", "leather", [-4.5, 13.25, -2.58], [4.5, 14.05, -1.85])
cube("strap_top_stitch", "strap", "thread", [-3.7, 13.91, -2.51], [3.7, 14.02, -1.94])
cube("strap_adjuster", "strap", "brass", [2.75, 13.1, -2.65], [3.72, 14.2, -1.78])
cube("strap_adjuster_hole", "strap", "dark", [2.98, 13.34, -2.7], [3.49, 13.95, -1.74])

# Tiny diamond rivets anchor the front reinforcement and add warm highlights.
for x in (-5.2, 5.2):
    for y in (2.1, 8.95):
        cube(f"front_rivet_{x}_{y}", "hardware", "brass", [x-.18, y-.18, 2.67], [x+.18, y+.18, 2.92])

(OUT / "geometry.json").write_text(json.dumps({"uv": UV, "parts": parts}, indent=2), encoding="utf-8")
print(f"Generated {len(parts)} cubes and a 128x128 texture in {OUT}")
