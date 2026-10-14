"""Build the native TAB textures from the supplied Astrolabe concept 8 vectors.

Usage:
    python tools/build_astrolabe_tab_assets.py
    python tools/build_astrolabe_tab_assets.py --import-html path/to/concept8.html

Pillow and Playwright are build-time dependencies only. Microsoft Edge renders the
checked-in SVG sources; Minecraft loads the resulting transparent PNG textures.
The downloaded HTML is only required when explicitly refreshing those sources.
No screenshots, sample game world, labels or fake player faces enter the assets.
"""

from __future__ import annotations

import argparse
from copy import deepcopy
import io
import math
from pathlib import Path
import re
import xml.etree.ElementTree as ET

from PIL import Image
from playwright.sync_api import sync_playwright


ROOT = Path(__file__).resolve().parents[1]
SOURCES = ROOT / "tools/astrolabe-tab-svg"
DEST = ROOT / (
    "fabric-server/client-mod-gradle/src/main/resources/assets/"
    "eclipseclient/textures/gui/tab/astrolabe"
)
SIZES = {
    "outer": (1024, 1024),
    "middle": (1024, 1024),
    "inner": (1024, 1024),
    "crosshairs": (1024, 1024),
    "glow": (1024, 1024),
    "eclipse": (128, 128),
    "core": (256, 256),
    "backdrop": (512, 256),
    "pill": (256, 48),
    "ember": (16, 16),
}
RING_IDS = {
    "outer": "ring-outer-static",
    "middle": "ring-middle-orbit",
    "inner": "ring-inner-chrono",
    "crosshairs": "ring-crosshairs",
}


def svg_document(view_box: str, size: tuple[int, int]) -> ET.Element:
    return ET.Element("svg", {
        "xmlns": "http://www.w3.org/2000/svg",
        "viewBox": view_box,
        "width": str(size[0]),
        "height": str(size[1]),
    })


def find_id(root: ET.Element, identifier: str) -> ET.Element:
    element = root.find(f".//*[@id='{identifier}']")
    if element is None:
        raise ValueError(f"Missing concept vector group: {identifier}")
    return element


def write_svg(name: str, document: ET.Element) -> None:
    ET.indent(document, space="  ")
    text = ET.tostring(document, encoding="unicode")
    (SOURCES / f"{name}.svg").write_text(text + "\n", encoding="utf-8")


def add_shadow(root: ET.Element, content: ET.Element,
               color: str, opacity: str, blur: str) -> None:
    """Bake the original CSS drop-shadow behind a layer, retaining source alpha."""
    definitions = root.find("defs")
    if definitions is None:
        definitions = ET.SubElement(root, "defs")
    shadow = ET.SubElement(definitions, "filter", {
        "id": "layerShadow", "x": "-50%", "y": "-50%",
        "width": "200%", "height": "200%",
        "color-interpolation-filters": "sRGB",
    })
    ET.SubElement(shadow, "feFlood", {
        "flood-color": color, "flood-opacity": opacity, "result": "gold",
    })
    ET.SubElement(shadow, "feComposite", {
        "in": "gold", "in2": "SourceAlpha", "operator": "in", "result": "mask",
    })
    ET.SubElement(shadow, "feGaussianBlur", {
        "in": "mask", "stdDeviation": blur, "result": "soft",
    })
    merge = ET.SubElement(shadow, "feMerge")
    ET.SubElement(merge, "feMergeNode", {"in": "soft"})
    ET.SubElement(merge, "feMergeNode", {"in": "SourceGraphic"})
    wrapper = ET.SubElement(root, "g", {"filter": "url(#layerShadow)"})
    wrapper.append(content)


def populate_ticks(root: ET.Element) -> None:
    """Reproduce the two original JavaScript loops, including two-place coords."""
    outer = find_id(root, "outer-degree-ticks")
    inner = find_id(root, "inner-chrono-ticks")
    for step, group in ((2, outer), (3, inner)):
        for degree in range(0, 360, step):
            if step == 2:
                major = degree % 30 == 0
                middle = degree % 10 == 0
                r1 = 265
                r2 = 248 if major else (255 if middle else 260)
                width = "1.2" if major else "0.65"
                opacity = "0.85" if major else ("0.55" if middle else "0.28")
            else:
                major = degree % 15 == 0
                r1 = 148
                r2 = 135 if major else 141
                width = "1.0" if major else "0.55"
                opacity = "0.78" if major else "0.36"
            radian = degree * math.pi / 180
            ET.SubElement(group, "line", {
                "x1": f"{430 + math.cos(radian) * r1:.2f}",
                "y1": f"{430 + math.sin(radian) * r1:.2f}",
                "x2": f"{430 + math.cos(radian) * r2:.2f}",
                "y2": f"{430 + math.sin(radian) * r2:.2f}",
                "stroke-width": width,
                "stroke-opacity": opacity,
            })
    assert len(outer) == 180 and len(inner) == 120


def import_html(path: Path) -> None:
    """Extract vector art only, never the embedded reference world photograph."""
    html = path.read_text(encoding="utf-8")
    ring_match = re.search(
        r'<svg\b[^>]*class="astrolabe-svg"[^>]*>.*?</svg>', html, re.DOTALL,
    )
    eclipse_match = re.search(
        r'<svg\b[^>]*class="eclipse-svg"[^>]*>.*?</svg>', html, re.DOTALL,
    )
    if ring_match is None or eclipse_match is None:
        raise ValueError("Expected concept 8 astrolabe and eclipse SVG elements")
    rings = ET.fromstring(ring_match.group())
    populate_ticks(rings)
    for name, identifier in RING_IDS.items():
        document = svg_document("0 0 860 860", SIZES[name])
        document.append(deepcopy(rings.find("defs")))
        add_shadow(document, deepcopy(find_id(rings, identifier)),
                   "#d8b467", "0.18", "12")
        write_svg(name, document)

    glow = svg_document("0 0 860 860", SIZES["glow"])
    glow.append(deepcopy(rings.find("defs")))
    ambient = rings.find("circle")
    if ambient is None or ambient.get("fill") != "url(#coreGlow)":
        raise ValueError("Expected the original coreGlow ambient circle")
    glow.append(deepcopy(ambient))
    write_svg("glow", glow)

    eclipse = ET.fromstring(eclipse_match.group())
    document = svg_document("0 0 100 100", SIZES["eclipse"])
    shapes = ET.Element("g")
    for child in eclipse:
        shapes.append(deepcopy(child))
    # Original eclipse is displayed at 68px with an 8px CSS drop-shadow.
    add_shadow(document, shapes, "#f3d88c", "0.35", str(8 * 100 / 68))
    write_svg("eclipse", document)


def make_support_sources() -> None:
    """Small CSS-equivalent atmospheric and reusable button textures."""
    core = svg_document("0 0 256 256", SIZES["core"])
    definitions = ET.SubElement(core, "defs")
    gradient = ET.SubElement(definitions, "radialGradient", {
        "id": "coreShade", "gradientUnits": "userSpaceOnUse",
        "cx": "128", "cy": "128", "r": str(128 * math.sqrt(2)),
    })
    # CSS circle gradients reach the square's farthest corner; border-radius
    # clips the fill to a disc. This retains the original .34-alpha outer edge.
    for offset, opacity in (("0", "0.72"), ("0.68", "0.38"), ("1", "0")):
        ET.SubElement(gradient, "stop", {
            "offset": offset, "stop-color": "#0e0b08", "stop-opacity": opacity,
        })
    ET.SubElement(core, "circle", {"cx": "128", "cy": "128", "r": "128",
                                    "fill": "url(#coreShade)"})
    write_svg("core", core)

    pill = svg_document("0 0 256 48", SIZES["pill"])
    ET.SubElement(pill, "rect", {
        "x": "3", "y": "3", "width": "250", "height": "42", "rx": "21",
        "fill": "#18130d", "fill-opacity": "0.88",
        "stroke": "#f3d88c", "stroke-opacity": "0.86", "stroke-width": "1.5",
    })
    write_svg("pill", pill)

    ember = svg_document("0 0 16 16", SIZES["ember"])
    definitions = ET.SubElement(ember, "defs")
    gradient = ET.SubElement(definitions, "radialGradient", {"id": "mote"})
    for offset, opacity in (("0", "0.9"), ("0.16", "0.72"),
                            ("0.45", "0.13"), ("1", "0")):
        ET.SubElement(gradient, "stop", {
            "offset": offset, "stop-color": "#f3d88c", "stop-opacity": opacity,
        })
    ET.SubElement(ember, "circle", {"cx": "8", "cy": "8", "r": "8",
                                     "fill": "url(#mote)"})
    write_svg("ember", ember)


def make_backdrop() -> Image.Image:
    """Original eventide radial scrim: warm 25% center, dark 68% corners.

    CSS radial-gradient(circle ...) extends to the farthest corner, so the
    normalized radius is circular in screen pixels, rather than an oval.
    The transparent PNG leaves the real game world visible underneath.
    """
    width, height = SIZES["backdrop"]
    result = Image.new("RGBA", (width, height))
    pixels = result.load()
    radius = math.hypot(width / 2, height / 2)
    for y in range(height):
        for x in range(width):
            fraction = min(1.0, math.hypot(x + 0.5 - width / 2,
                                         y + 0.5 - height / 2) / radius)
            alpha = 0.25 + 0.43 * fraction
            # Browser gradients interpolate premultiplied color/alpha.
            color = tuple(round((a * 0.25 * (1 - fraction) + b * 0.68 * fraction)
                                / alpha) for a, b in ((12, 6), (9, 5), (7, 8)))
            pixels[x, y] = (*color, round(alpha * 255))
    return result


def render_vectors() -> None:
    # Two-times sampling keeps subpixel .55px ticks readable when scaled in-game.
    sample_scale = 2
    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(
            channel="msedge", headless=True, args=["--force-color-profile=srgb"],
        )
        page = browser.new_page(device_scale_factor=1)
        try:
            for name, (width, height) in SIZES.items():
                if name == "backdrop":
                    raster = make_backdrop()
                else:
                    vector = (SOURCES / f"{name}.svg").read_text(encoding="utf-8")
                    rendered_width, rendered_height = width * sample_scale, height * sample_scale
                    page.set_viewport_size({"width": rendered_width, "height": rendered_height})
                    page.set_content(
                        '<html><head><meta charset="utf-8"><style>'
                        'html,body{margin:0;padding:0;background:transparent;overflow:hidden}'
                        f'svg{{display:block;width:{rendered_width}px;height:{rendered_height}px}}'
                        '</style></head><body>' + vector + '</body></html>',
                        wait_until="load",
                    )
                    raster = Image.open(io.BytesIO(page.screenshot(omit_background=True))).convert("RGBA")
                    raster = raster.resize((width, height), Image.Resampling.LANCZOS)
                raster.save(DEST / f"{name}.png", optimize=True)
                (DEST / f"{name}.png.mcmeta").write_text(
                    '{"texture":{"blur":true,"clamp":true}}\n', encoding="utf-8")
                print(f"{name}.png: {width}x{height}, {(DEST / f'{name}.png').stat().st_size} bytes")
        finally:
            browser.close()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--import-html", type=Path,
                        help="Refresh checked-in vector sources from the concept HTML")
    arguments = parser.parse_args()
    SOURCES.mkdir(parents=True, exist_ok=True)
    DEST.mkdir(parents=True, exist_ok=True)
    if arguments.import_html is not None:
        import_html(arguments.import_html)
    make_support_sources()
    missing = [name for name in (*RING_IDS, "glow", "eclipse")
               if not (SOURCES / f"{name}.svg").is_file()]
    if missing:
        parser.error("Vector sources are missing; first run with --import-html CONCEPT8.html")
    render_vectors()
    print(f"Built {len(SIZES)} transparent native TAB textures in {DEST}")


if __name__ == "__main__":
    main()
