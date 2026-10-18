"""Compile a measured voxel design into every runtime and preview artifact.

The design JSON is the only artistic source of truth. This compiler emits:
  * the Minecraft item model,
  * its item definition,
  * a flat palette atlas,
  * exact preview geometry,
  * a deterministic orthographic render,
  * and a hash manifest used by the Gradle verification task.

ImageGen remains an art-direction source only. Once its proportions and palette
are described in the design JSON, no generated raster is sampled as a texture.
"""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import math
from pathlib import Path
from typing import Any

from PIL import Image, ImageDraw


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_DESIGN = ROOT / "design/embedded_arrow.design.json"
DIRECTIONS = ("north", "south", "east", "west", "up", "down")


def canonical_json(value: Any) -> bytes:
    return (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode("utf-8")


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def validate_design(design: dict[str, Any], design_path: Path) -> None:
    if design.get("format") != 1:
        raise ValueError("Only voxel design format 1 is supported")
    asset = design.get("asset", "")
    if not asset or any(character not in "abcdefghijklmnopqrstuvwxyz0123456789_" for character in asset):
        raise ValueError(f"Invalid asset id: {asset!r}")

    atlas = design["atlas"]
    size = int(atlas["size"])
    cell_size = int(atlas["cellSize"])
    if size <= 0 or cell_size <= 0 or size % cell_size:
        raise ValueError("Atlas size must be a positive multiple of cellSize")

    cells: set[tuple[int, int]] = set()
    names: set[str] = set()
    for swatch in atlas["swatches"]:
        name = swatch["name"]
        cell = tuple(swatch["cell"])
        if name in names:
            raise ValueError(f"Duplicate swatch name: {name}")
        if cell in cells:
            raise ValueError(f"Duplicate atlas cell: {cell}")
        if len(cell) != 2 or any(value < 0 or value >= size // cell_size for value in cell):
            raise ValueError(f"Swatch {name} lies outside the atlas")
        if len(swatch["rgb"]) != 3 or any(value < 0 or value > 255 for value in swatch["rgb"]):
            raise ValueError(f"Swatch {name} has invalid RGB")
        names.add(name)
        cells.add(cell)

    for profile_name, profile in design["faceProfiles"].items():
        if set(profile) != set(DIRECTIONS):
            raise ValueError(f"Face profile {profile_name} must define exactly {DIRECTIONS}")
        unknown = set(profile.values()) - names
        if unknown:
            raise ValueError(f"Face profile {profile_name} uses unknown swatches: {sorted(unknown)}")

    reference_file = design_path.parent / design["reference"]["file"]
    if not reference_file.is_file():
        raise ValueError(f"Approved reference is missing: {reference_file}")


def swatch_maps(design: dict[str, Any]) -> tuple[dict[str, list[int]], dict[str, list[int]]]:
    atlas = design["atlas"]
    size = atlas["size"]
    cell_size = atlas["cellSize"]
    uv: dict[str, list[int]] = {}
    colors: dict[str, list[int]] = {}
    for swatch in atlas["swatches"]:
        column, row = swatch["cell"]
        uv_unit = 16 / size
        u1 = column * cell_size * uv_unit
        v1 = row * cell_size * uv_unit
        u2 = (column + 1) * cell_size * uv_unit
        v2 = (row + 1) * cell_size * uv_unit
        uv[swatch["name"]] = [number if number % 1 else int(number) for number in (u1, v1, u2, v2)]
        colors[swatch["name"]] = swatch["rgb"]
    return uv, colors


def compile_atlas(design: dict[str, Any]) -> bytes:
    atlas_spec = design["atlas"]
    size = atlas_spec["size"]
    cell_size = atlas_spec["cellSize"]
    image = Image.new("RGBA", (size, size), (0, 0, 0, 255))
    draw = ImageDraw.Draw(image)
    for swatch in atlas_spec["swatches"]:
        column, row = swatch["cell"]
        x1, y1 = column * cell_size, row * cell_size
        x2, y2 = x1 + cell_size - 1, y1 + cell_size - 1
        draw.rectangle((x1, y1, x2, y2), fill=tuple(swatch["rgb"]) + (255,))
    output = io.BytesIO()
    image.save(output, "PNG", optimize=True)
    return output.getvalue()


def compile_model(design: dict[str, Any]) -> tuple[dict[str, Any], dict[str, Any]]:
    asset = design["asset"]
    cx, _, cz = design["origin"]
    uv, _ = swatch_maps(design)
    profiles = design["faceProfiles"]
    elements: list[dict[str, Any]] = []

    def face_set(profile_or_swatch: str) -> dict[str, Any]:
        profile = profiles.get(profile_or_swatch)
        tones = profile if profile is not None else {direction: profile_or_swatch for direction in DIRECTIONS}
        return {
            direction: {"texture": f"#{tone}", "uv": uv[tone]}
            for direction, tone in tones.items()
        }

    def add_box(name: str, start: list[float], end: list[float], profile: str) -> None:
        if any(end[index] <= start[index] for index in range(3)):
            raise ValueError(f"Degenerate cuboid {name}: {start} -> {end}")
        elements.append({
            "name": name,
            "from": [round(value, 4) for value in start],
            "to": [round(value, 4) for value in end],
            "faces": face_set(profile),
        })

    def add_centered(name: str, y1: float, y2: float, half: float, profile: str) -> None:
        add_box(name, [cx - half, y1, cz - half], [cx + half, y2, cz + half], profile)

    geometry = design["geometry"]
    tip = geometry["tip"]
    for stage in tip["stages"]:
        add_centered(f"bodkin_{stage['name']}", stage["from"], stage["to"], stage["half"], tip["profile"])
    for stage in tip["socket"]:
        add_centered(f"socket_{stage['name']}", stage["from"], stage["to"], stage["half"], tip["profile"])

    shaft = geometry["shaft"]
    for segment in shaft["segments"]:
        add_centered(
            f"shaft_{segment['name']}", segment["from"], segment["to"], shaft["half"], shaft["profile"])
    for index, joint in enumerate(shaft["joints"], start=1):
        half_width = joint["width"] / 2
        add_centered(
            f"shaft_joint_{index}", joint["at"] - half_width, joint["at"] + half_width,
            shaft["half"] + 0.025, "wood_dark")

    bindings = geometry["bindings"]
    for index, ring in enumerate(bindings["rings"], start=1):
        add_centered(f"binding_{index}", ring["from"], ring["to"], bindings["half"], bindings["profile"])
    collar = bindings["fletchingCollar"]
    add_centered("fletching_collar", collar["from"], collar["to"], collar["half"], bindings["profile"])

    fletching = geometry["fletching"]
    thickness = fletching["thickness"]
    shaft_half = fletching["shaftHalf"]
    for direction in fletching["directions"]:
        for stage in fletching["stages"]:
            extension = stage["extension"]
            if direction == "north":
                start = [cx - thickness / 2, stage["from"], cz - extension]
                end = [cx + thickness / 2, stage["to"], cz - shaft_half]
            elif direction == "south":
                start = [cx - thickness / 2, stage["from"], cz + shaft_half]
                end = [cx + thickness / 2, stage["to"], cz + extension]
            elif direction == "west":
                start = [cx - extension, stage["from"], cz - thickness / 2]
                end = [cx - shaft_half, stage["to"], cz + thickness / 2]
            elif direction == "east":
                start = [cx + shaft_half, stage["from"], cz - thickness / 2]
                end = [cx + extension, stage["to"], cz + thickness / 2]
            else:
                raise ValueError(f"Unsupported fletching direction: {direction}")
            add_box(f"feather_{direction}_{stage['name']}", start, end, fletching["profile"])

    nock = geometry["nock"]
    nock_profile = nock["profile"]
    add_centered("nock_collar", nock["collar"]["from"], nock["collar"]["to"], nock["collar"]["half"], nock_profile)
    add_box(
        "nock_left",
        [cx - nock["outerHalf"], nock["from"], cz - nock["depthHalf"]],
        [cx - nock["notchHalf"], nock["to"], cz + nock["depthHalf"]],
        nock_profile)
    add_box(
        "nock_right",
        [cx + nock["notchHalf"], nock["from"], cz - nock["depthHalf"]],
        [cx + nock["outerHalf"], nock["to"], cz + nock["depthHalf"]],
        nock_profile)
    cap = nock["cap"]
    add_box(
        "nock_cap_left",
        [cx - cap["half"], cap["from"], cz - nock["depthHalf"]],
        [cx - nock["notchHalf"], cap["to"], cz + nock["depthHalf"]],
        "wood_dark")
    add_box(
        "nock_cap_right",
        [cx + nock["notchHalf"], cap["from"], cz - nock["depthHalf"]],
        [cx + cap["half"], cap["to"], cz + nock["depthHalf"]],
        "wood_dark")

    minimum_y = min(element["from"][1] for element in elements)
    maximum_y = max(element["to"][1] for element in elements)
    total_length = maximum_y - minimum_y
    tip_end = bindings["rings"][0]["from"]
    tip_ratio = (tip_end - minimum_y) / total_length
    fletching_start = min(stage["from"] for stage in fletching["stages"])
    fletching_end = max(stage["to"] for stage in fletching["stages"])
    fletching_ratio = (fletching_end - fletching_start) / total_length
    targets = design["reference"]["targetRatios"]
    tolerance = design["reference"]["ratioTolerance"]
    for label, actual in (("tip", tip_ratio), ("fletching", fletching_ratio)):
        if abs(actual - targets[label]) > tolerance:
            raise ValueError(
                f"{label} ratio {actual:.4f} misses approved target {targets[label]:.4f} ± {tolerance:.4f}")

    texture_path = f"eclipseclient:item/{asset}_atlas"
    anchor_y = design["display"]["anchorY"]
    translation_y = round(8.0 - anchor_y, 4)
    scale = design["display"]["fixedScale"]
    model = {
        "credit": "Compiled from Eclipse RP deterministic voxel design",
        "ambientocclusion": True,
        "textures": {
            **{swatch["name"]: texture_path for swatch in design["atlas"]["swatches"]},
            "particle": texture_path,
        },
        "display": {
            "fixed": {
                "translation": [0, translation_y, 0],
                "scale": [scale, scale, scale],
            }
        },
        "elements": elements,
    }
    metrics = {
        "elementCount": len(elements),
        "bounds": {
            "min": [
                min(element["from"][axis] for element in elements) for axis in range(3)
            ],
            "max": [
                max(element["to"][axis] for element in elements) for axis in range(3)
            ],
        },
        "ratios": {
            "tip": round(tip_ratio, 6),
            "fletching": round(fletching_ratio, 6),
        },
        "anchorY": anchor_y,
    }
    return model, metrics


def render_compiled_model(model: dict[str, Any], colors: dict[str, list[int]]) -> bytes:
    width, height = 1420, 540
    supersample = 2
    background = (13, 17, 25, 255)
    image = Image.new("RGBA", (width * supersample, height * supersample), background)
    draw = ImageDraw.Draw(image)

    direction_vectors = {
        "west": (-1, 0, 0), "east": (1, 0, 0),
        "down": (0, -1, 0), "up": (0, 1, 0),
        "north": (0, 0, -1), "south": (0, 0, 1),
    }
    face_corners = {
        "west": (0, 3, 7, 4), "east": (1, 5, 6, 2),
        "down": (0, 1, 5, 4), "up": (3, 7, 6, 2),
        "north": (0, 3, 2, 1), "south": (4, 5, 6, 7),
    }
    view = (1.0, 0.42, 1.0)

    vertices_by_element: list[tuple[dict[str, Any], list[tuple[float, float, float]]]] = []
    all_projected: list[tuple[float, float]] = []

    def raw_project(point: tuple[float, float, float]) -> tuple[float, float]:
        x, y, z = point
        return y * 1.0 + (x - z) * 0.34, -y * 0.30 - (x + z - 16.0) * 0.47

    for element in model["elements"]:
        x1, y1, z1 = element["from"]
        x2, y2, z2 = element["to"]
        vertices = [
            (x1, y1, z1), (x2, y1, z1), (x2, y2, z1), (x1, y2, z1),
            (x1, y1, z2), (x2, y1, z2), (x2, y2, z2), (x1, y2, z2),
        ]
        vertices_by_element.append((element, vertices))
        all_projected.extend(raw_project(vertex) for vertex in vertices)

    min_x = min(point[0] for point in all_projected)
    max_x = max(point[0] for point in all_projected)
    min_y = min(point[1] for point in all_projected)
    max_y = max(point[1] for point in all_projected)
    usable_width = width - 130
    usable_height = height - 90
    scale = min(usable_width / (max_x - min_x), usable_height / (max_y - min_y))
    offset_x = (width - (max_x - min_x) * scale) / 2 - min_x * scale
    offset_y = (height - (max_y - min_y) * scale) / 2 - min_y * scale

    faces_to_draw: list[tuple[float, list[tuple[int, int]], tuple[int, int, int, int]]] = []
    for element, vertices in vertices_by_element:
        for direction, normal in direction_vectors.items():
            if normal[0] * view[0] + normal[1] * view[1] + normal[2] * view[2] >= 0:
                continue
            corners = face_corners[direction]
            face_vertices = [vertices[index] for index in corners]
            depth = sum(
                vertex[0] * view[0] + vertex[1] * view[1] + vertex[2] * view[2]
                for vertex in face_vertices) / 4
            points = []
            for vertex in face_vertices:
                px, py = raw_project(vertex)
                points.append((
                    round((px * scale + offset_x) * supersample),
                    round((py * scale + offset_y) * supersample),
                ))
            tone = element["faces"][direction]["texture"][1:]
            color = tuple(colors[tone]) + (255,)
            faces_to_draw.append((depth, points, color))

    for _, points, color in sorted(faces_to_draw, key=lambda item: item[0], reverse=True):
        draw.polygon(points, fill=color)

    image = image.resize((width, height), Image.Resampling.LANCZOS)
    output = io.BytesIO()
    image.save(output, "PNG", optimize=True)
    return output.getvalue()


def compile_outputs(design_path: Path) -> dict[Path, bytes]:
    design_bytes = design_path.read_bytes()
    design = json.loads(design_bytes)
    validate_design(design, design_path)
    asset = design["asset"]
    model, metrics = compile_model(design)
    uv, colors = swatch_maps(design)
    atlas_bytes = compile_atlas(design)
    model_bytes = canonical_json(model)
    item_bytes = canonical_json({
        "model": {"type": "minecraft:model", "model": f"eclipseclient:item/{asset}"}
    })
    preview_data_bytes = canonical_json({
        "format": 1,
        "asset": asset,
        "metrics": metrics,
        "uv": uv,
        "colors": colors,
        "display": model["display"],
        "elements": model["elements"],
    })
    render_bytes = render_compiled_model(model, colors)

    reference_path = design_path.parent / design["reference"]["file"]
    outputs = {
        ROOT / f"src/main/resources/assets/eclipseclient/models/item/{asset}.json": model_bytes,
        ROOT / f"src/main/resources/assets/eclipseclient/items/{asset}.json": item_bytes,
        ROOT / f"src/main/resources/assets/eclipseclient/textures/item/{asset}_atlas.png": atlas_bytes,
        ROOT / f"build/{asset}-preview-data.json": preview_data_bytes,
        ROOT / f"build/{asset}-compiled-render.png": render_bytes,
    }
    manifest = {
        "format": 1,
        "asset": asset,
        "design": str(design_path.relative_to(ROOT)).replace("\\", "/"),
        "designSha256": sha256(design_bytes),
        "referenceSha256": sha256(reference_path.read_bytes()),
        "modelSha256": sha256(model_bytes),
        "itemSha256": sha256(item_bytes),
        "atlasSha256": sha256(atlas_bytes),
        "previewDataSha256": sha256(preview_data_bytes),
        "compiledRenderSha256": sha256(render_bytes),
        **metrics,
    }
    outputs[ROOT / f"build/{asset}-compile-manifest.json"] = canonical_json(manifest)
    return outputs


def main() -> None:
    parser = argparse.ArgumentParser(description="Compile an Eclipse RP voxel design")
    parser.add_argument("design", nargs="?", type=Path, default=DEFAULT_DESIGN)
    parser.add_argument("--check", action="store_true", help="Fail if generated outputs are stale")
    args = parser.parse_args()
    design_path = args.design.resolve()
    outputs = compile_outputs(design_path)

    stale: list[Path] = []
    for path, expected in outputs.items():
        if args.check:
            if not path.is_file() or path.read_bytes() != expected:
                stale.append(path)
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(expected)

    if stale:
        joined = "\n".join(f"  - {path}" for path in stale)
        raise SystemExit(f"Voxel asset outputs are stale:\n{joined}")

    manifest_path = next(path for path in outputs if path.name.endswith("-compile-manifest.json"))
    manifest = json.loads(outputs[manifest_path])
    action = "Verified" if args.check else "Compiled"
    print(
        f"{action} {manifest['asset']}: {manifest['elementCount']} elements, "
        f"tip ratio {manifest['ratios']['tip']:.3f}, "
        f"fletching ratio {manifest['ratios']['fletching']:.3f}.")


if __name__ == "__main__":
    main()
