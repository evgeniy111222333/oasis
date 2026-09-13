#!/usr/bin/env python3
"""Build the runtime blood-decal library from approved ImageGen source sheets.

The source sheets contain eight isolated sprites in a 4x2 layout.  This tool
removes the last generation artefacts deterministically, fits each silhouette
to Minecraft's 32x32 particle format, applies a small authored palette, and
creates four coherent drying stages without asking a generator to redraw the
same stain four times.
"""

from __future__ import annotations

import hashlib
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont


ROOT = Path(__file__).resolve().parents[1]
SOURCE_DIR = ROOT.parent / "art" / "blood-fx" / "generated-source"
RUNTIME_DIR = (
    ROOT
    / "src"
    / "main"
    / "resources"
    / "assets"
    / "eclipseclient"
    / "textures"
    / "particle"
    / "blood"
)
PREVIEW_PATH = ROOT.parent / "art" / "blood-fx" / "splat_library_preview_v2.png"

TIERS = (
    ("low", "l", "blood_splats_low_alpha_v2.png", 19),
    ("medium", "m", "blood_splats_medium_alpha_v2.png", 25),
    ("high", "h", "blood_splats_high_alpha_v2.png", 29),
)
STAGES = ("fresh", "settled", "drying", "dry")

# Brighter than the desired on-screen result because BloodDecalParticle applies
# material lighting/tint at runtime.  Each row intentionally has only five tones.
PALETTES = (
    ("#5c0810", "#7a0b13", "#a31219", "#c52224", "#ed4935"),
    ("#49090f", "#650c13", "#851219", "#a21c20", "#c1352a"),
    ("#35090d", "#4c0c11", "#651217", "#7e1a1d", "#982b25"),
    ("#26090b", "#370b0e", "#4a1013", "#5e1719", "#71231f"),
)


def stable_byte(*parts: object) -> int:
    digest = hashlib.blake2b(
        "|".join(map(str, parts)).encode("utf-8"), digest_size=1
    ).digest()
    return digest[0]


def cell(sheet: Image.Image, index: int) -> Image.Image:
    column = index % 4
    row = index // 4
    x0 = round(column * sheet.width / 4)
    x1 = round((column + 1) * sheet.width / 4)
    y0 = round(row * sheet.height / 2)
    y1 = round((row + 1) * sheet.height / 2)
    return sheet.crop((x0, y0, x1, y1))


def normalized_sprite(source: Image.Image, target_span: int) -> Image.Image:
    rgba = source.convert("RGBA")
    alpha = rgba.getchannel("A").point(lambda value: 255 if value >= 96 else 0)
    rgba.putalpha(alpha)
    bounds = alpha.getbbox()
    if bounds is None:
        raise ValueError("Generated source cell has no visible pixels")

    cropped = rgba.crop(bounds)
    scale = min(target_span / cropped.width, target_span / cropped.height)
    width = max(1, round(cropped.width * scale))
    height = max(1, round(cropped.height * scale))
    cropped = cropped.resize((width, height), Image.Resampling.NEAREST)

    output = Image.new("RGBA", (32, 32), (0, 0, 0, 0))
    output.alpha_composite(cropped, ((32 - width) // 2, (32 - height) // 2))
    return output


def palette_stage(base: Image.Image, family: int, stage: int) -> Image.Image:
    pixels = [base.getpixel((x, y)) for y in range(32) for x in range(32)]
    visible_luma = [
        (red * 3 + green * 4 + blue) / 8
        for red, green, blue, alpha in pixels
        if alpha > 0
    ]
    low = min(visible_luma)
    high = max(visible_luma)
    span = max(1.0, high - low)
    palette = [tuple(int(color[i : i + 2], 16) for i in (1, 3, 5)) for color in PALETTES[stage]]

    output = Image.new("RGBA", base.size, (0, 0, 0, 0))
    source = base.load()
    target = output.load()
    for y in range(32):
        for x in range(32):
            red, green, blue, alpha = source[x, y]
            if alpha == 0:
                continue
            luma = (red * 3 + green * 4 + blue) / 8
            tone = min(4, int(((luma - low) / span) * 4.999))

            # Dry stains keep the silhouette but lose isolated droplets and gain
            # sparse dark, square texture breaks.  The hash makes this stable.
            neighbours = sum(
                1
                for dx, dy in ((-1, 0), (1, 0), (0, -1), (0, 1))
                if 0 <= x + dx < 32
                and 0 <= y + dy < 32
                and source[x + dx, y + dy][3] > 0
            )
            entropy = stable_byte(family, stage, x, y)
            if stage >= 2 and neighbours <= 1 and entropy < (28 if stage == 2 else 82):
                continue
            if stage == 3 and neighbours == 4 and entropy < 28:
                tone = max(0, tone - 2)

            target[x, y] = (*palette[tone], 255)
    return output


def render_preview(sprites: dict[tuple[int, int], Image.Image]) -> None:
    scale = 5
    left = 116
    top = 54
    cell_size = 32 * scale
    gap = 8
    width = left + 8 * (cell_size + gap) + 24
    height = top + 12 * (cell_size + gap) + 30
    preview = Image.new("RGB", (width, height), "#11141a")
    draw = ImageDraw.Draw(preview)
    font = ImageFont.load_default(size=18)

    for tier_index, (tier_name, _, _, _) in enumerate(TIERS):
        for stage, stage_name in enumerate(STAGES):
            row = tier_index * 4 + stage
            y = top + row * (cell_size + gap)
            draw.text((12, y + 8), tier_name.upper(), fill="#d7dbe2", font=font)
            draw.text((12, y + 34), stage_name, fill="#89919d", font=font)
            for variant in range(8):
                family = tier_index * 8 + variant
                sprite = sprites[(family, stage)].resize(
                    (cell_size, cell_size), Image.Resampling.NEAREST
                )
                tile = Image.new("RGBA", (cell_size, cell_size), "#191d24")
                tile.alpha_composite(sprite)
                preview.paste(tile.convert("RGB"), (left + variant * (cell_size + gap), y))

    PREVIEW_PATH.parent.mkdir(parents=True, exist_ok=True)
    preview.save(PREVIEW_PATH)


def main() -> None:
    RUNTIME_DIR.mkdir(parents=True, exist_ok=True)
    built: dict[tuple[int, int], Image.Image] = {}
    for tier_index, (_, prefix, source_name, target_span) in enumerate(TIERS):
        sheet_path = SOURCE_DIR / source_name
        if not sheet_path.is_file():
            raise FileNotFoundError(f"Missing alpha source sheet: {sheet_path}")
        with Image.open(sheet_path) as source_sheet:
            sheet = source_sheet.convert("RGBA")
        for variant in range(8):
            family = tier_index * 8 + variant
            base = normalized_sprite(cell(sheet, variant), target_span)
            for stage, stage_name in enumerate(STAGES):
                sprite = palette_stage(base, family, stage)
                output = RUNTIME_DIR / f"splat_{prefix}{variant:02d}_{stage_name}.png"
                sprite.save(output, optimize=True)
                built[(family, stage)] = sprite

    render_preview(built)
    print(f"Built {len(built)} runtime sprites in {RUNTIME_DIR}")
    print(f"Wrote preview to {PREVIEW_PATH}")


if __name__ == "__main__":
    main()
