#!/usr/bin/env python3
"""
Generates 8 high quality stylized cartoon cloud / work process particle sprites.
32x32 RGBA PNGs with volumetric shading, sunlit highlights, crevice shadows,
and clean anti-aliased cartoon contours.
"""
import os
from PIL import Image, ImageDraw

HI = 128
SIZE = 32
OUT_DIR = os.path.join(
    os.path.dirname(os.path.abspath(__file__)),
    "..", "src", "main", "resources",
    "assets", "eclipseclient", "textures", "particle", "dust"
)

def draw_cloud_sprite(lobes, alpha):
    img = Image.new('RGBA', (HI, HI), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    c = HI / 2.0

    # Pass 0: Soft cartoon silhouette rim / ambient crease (slate-blue outline)
    for lx, ly, r in lobes:
        draw.ellipse([c + lx - r - 2, c + ly - r - 2, c + lx + r + 2, c + ly + r + 2],
                     fill=(135, 155, 180, int(185 * alpha)))

    # Pass 1: Volumetric underside shade (soft periwinkle ambient occlusion)
    for lx, ly, r in lobes:
        sx = c + lx + r * 0.10
        sy = c + ly + r * 0.15
        draw.ellipse([sx - r, sy - r, sx + r, sy + r],
                     fill=(198, 214, 230, int(250 * alpha)))

    # Pass 2: Diffuse middle volume
    for lx, ly, r in lobes:
        draw.ellipse([c + lx - r * 0.94, c + ly - r * 0.94,
                      c + lx + r * 0.94, c + ly + r * 0.94],
                     fill=(236, 244, 252, int(255 * alpha)))

    # Pass 3: Main crisp white cloud body (biased towards upper-left light source)
    for lx, ly, r in lobes:
        bx = c + lx - r * 0.07
        by = c + ly - r * 0.09
        draw.ellipse([bx - r * 0.85, by - r * 0.85, bx + r * 0.85, by + r * 0.85],
                     fill=(252, 254, 255, int(255 * alpha)))

    # Pass 4: Brilliant sunlit crest highlights on each billow
    for lx, ly, r in lobes:
        hx = c + lx - r * 0.22
        hy = c + ly - r * 0.26
        hr = r * 0.54
        draw.ellipse([hx - hr, hy - hr, hx + hr, hy + hr],
                     fill=(255, 255, 255, int(255 * alpha)))

    return img.resize((SIZE, SIZE), Image.Resampling.LANCZOS)

def main():
    os.makedirs(OUT_DIR, exist_ok=True)
    frames_data = [
        # 0: Spawn pop - tight energetic puff + tiny satellite poofs
        ([(0, 2, 18), (-8, -5, 13), (8, -4, 14), (-1, 8, 12), (-19, -10, 4), (18, -8, 4), (-13, 16, 4)], 1.0),
        # 1: Rapidly expanding fluff + 2 satellite droplets
        ([(0, 3, 24), (-12, -7, 18), (12, -6, 19), (-8, 10, 16), (9, 9, 17), (-22, 5, 5), (22, -12, 5)], 1.0),
        # 2: Volumetric billow + curled side wisp
        ([(0, 3, 29), (-16, -9, 22), (15, -10, 23), (0, -18, 20), (-14, 12, 20), (14, 11, 21), (-22, -2, 13)], 1.0),
        # 3: Peak mature cartoon cloud - lush, full, rich billows
        ([(0, 0, 33), (-18, -11, 25), (18, -12, 25), (-2, -21, 23), (-17, 13, 22), (16, 13, 22), (0, 16, 20), (25, -4, 13)], 1.0),
        # 4: Swirling billow - centrifugal stretch with curved contours
        ([(5, -2, 29), (-18, -13, 24), (21, -8, 24), (-6, -21, 21), (-18, 14, 21), (17, 13, 21), (-26, 2, 12)], 0.95),
        # 5: Separating cloudlets - billow breaking apart into fluffy bubbles
        ([(-20, -11, 19), (20, -12, 19), (-3, -20, 18), (-17, 15, 17), (18, 15, 17), (0, 3, 13)], 0.78),
        # 6: Delicate dissolving wisps
        ([(-22, -10, 15), (22, -11, 15), (-3, -19, 14), (-17, 14, 13), (17, 14, 13)], 0.52),
        # 7: Tiny dissipating vapor puffs
        ([(-23, -9, 10), (22, -10, 10), (-2, -17, 9), (17, 13, 8)], 0.28)
    ]

    for i, (lobes, alpha) in enumerate(frames_data):
        frame = draw_cloud_sprite(lobes, alpha)
        target = os.path.join(OUT_DIR, f"puff_{i}.png")
        frame.save(target, "PNG")
        print(f"Wrote {target}: {frame.size}, {os.path.getsize(target)} bytes")

if __name__ == "__main__":
    main()
