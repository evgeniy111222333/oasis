#!/usr/bin/env python3
"""Генерирует тяжёлый рабочий молот Eclipse RP в стилистике Minecraft."""

from __future__ import annotations

import json
import math
import shutil
import struct
from dataclasses import dataclass
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont


ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "art" / "heavy_hammer_minecraft"
RUNTIME = ROOT / "client-mod-gradle" / "src" / "main" / "resources" / "assets" / "eclipseclient"
SIZE = 128
SEED = 23140714
MAIN_GRIP_Y = -8.2

REGIONS = {
    "iron": (0, 0, 8, 8),
    "iron_dark": (0, 8, 8, 16),
    "wood": (8, 0, 12, 16),
    "wrap": (12, 0, 16, 8),
    "wedge": (12, 8, 16, 12),
}
COLORS = {
    "iron": np.array((73, 78, 79)),
    "iron_dark": np.array((48, 51, 51)),
    "wood": np.array((104, 63, 31)),
    "wrap": np.array((61, 49, 40)),
    "wedge": np.array((137, 91, 43)),
}


@dataclass(frozen=True)
class Cube:
    name: str
    start: tuple[float, float, float]
    end: tuple[float, float, float]
    material: str
    bone: str


def cubes() -> list[Cube]:
    c: list[Cube] = []
    # Цельное ясеневое древко без рукояти меча, навершия и декоративного воротника.
    # Обе руки держат один и тот же непрерывный кусок дерева.
    c += [
        Cube("solid_ash_haft", (7.02, -12.0, 7.06), (8.98, 17.1, 8.94), "wood", "handle"),
        # Широкий простой кованый боёк; ступени имитируют ручные фаски.
        Cube("head_core", (0.0, 15.65, 4.55), (16.0, 22.35, 11.45), "iron", "head"),
        Cube("head_left_neck", (-3.0, 16.15, 5.05), (0.0, 21.85, 10.95), "iron", "head"),
        Cube("head_right_neck", (16.0, 16.15, 5.05), (19.0, 21.85, 10.95), "iron", "head"),
        Cube("left_striking_face", (-4.25, 15.45, 4.35), (-3.0, 22.55, 11.65), "iron_dark", "head"),
        Cube("right_striking_face", (19.0, 15.45, 4.35), (20.25, 22.55, 11.65), "iron_dark", "head"),
        Cube("left_face_highlight", (-4.42, 16.05, 4.95), (-4.24, 21.95, 11.05), "iron", "head"),
        Cube("right_face_highlight", (20.24, 16.05, 4.95), (20.42, 21.95, 11.05), "iron", "head"),
        # Дешёвые железные полосы не дают бойку расколоть древко.
        Cube("strap_front", (6.45, 10.95, 6.38), (7.05, 17.0, 9.62), "iron_dark", "handle"),
        Cube("strap_back", (8.95, 10.95, 6.38), (9.55, 17.0, 9.62), "iron_dark", "handle"),
        Cube("strap_left", (6.38, 10.95, 6.45), (9.62, 17.0, 7.05), "iron_dark", "handle"),
        Cube("strap_right", (6.38, 10.95, 8.95), (9.62, 17.0, 9.55), "iron_dark", "handle"),
        # Два видимых клина делают конструкцию понятной и ремонтопригодной.
        Cube("ash_wedge", (5.8, 22.35, 6.65), (10.2, 23.05, 9.35), "wedge", "head"),
        Cube("cross_wedge", (7.45, 23.0, 5.85), (8.55, 23.42, 10.15), "iron_dark", "head"),
    ]
    return c


def faces_for(region: tuple[int, int, int, int]) -> dict:
    uv = list(region)
    return {face: {"uv": uv, "texture": "#0"}
            for face in ("north", "east", "south", "west", "up", "down")}


def minecraft_model(parts: list[Cube]) -> dict:
    return {
        "credit": "Процедурный тяжёлый рабочий молот Eclipse RP",
        "ambientocclusion": True,
        "texture_size": [SIZE, SIZE],
        "textures": {"0": "eclipseclient:item/heavy_hammer", "particle": "eclipseclient:item/heavy_hammer"},
        "elements": [
            {"name": part.name, "from": list(part.start), "to": list(part.end),
             "shade": True, "faces": faces_for(REGIONS[part.material])}
            for part in parts
        ],
        "display": {
            "thirdperson_righthand": {"rotation": [0, 90, -38], "translation": [0, 1.5, -2.5], "scale": [0.62, 0.62, 0.62]},
            "thirdperson_lefthand": {"rotation": [0, -90, 38], "translation": [0, 1.5, -2.5], "scale": [0.62, 0.62, 0.62]},
            "firstperson_righthand": {"rotation": [0, -92, 24], "translation": [1.1, 2.3, 0.2], "scale": [0.70, 0.70, 0.70]},
            "firstperson_lefthand": {"rotation": [0, 92, -24], "translation": [1.1, 2.3, 0.2], "scale": [0.70, 0.70, 0.70]},
            "ground": {"rotation": [0, 0, 78], "translation": [0, 3.0, 0], "scale": [0.43, 0.43, 0.43]},
            "gui": {"rotation": [25, 142, -32], "translation": [0, 0.6, 0], "scale": [0.58, 0.58, 0.58]},
            "fixed": {"rotation": [0, 0, -45], "translation": [0, 0, 0], "scale": [0.57, 0.57, 0.57]},
        },
    }


def block_noise(rng: np.random.Generator, shape: tuple[int, int], cell: int = 4) -> np.ndarray:
    small = rng.integers(-18, 19, (math.ceil(shape[0] / cell), math.ceil(shape[1] / cell)))
    return np.repeat(np.repeat(small, cell, axis=0), cell, axis=1)[:shape[0], :shape[1]]


def make_textures() -> tuple[Image.Image, Image.Image, Image.Image]:
    rng = np.random.default_rng(SEED)
    color = np.zeros((SIZE, SIZE, 3), dtype=np.int16)
    height = np.full((SIZE, SIZE), 128, dtype=np.int16)
    spec = np.full((SIZE, SIZE), 28, dtype=np.uint8)

    # Кованое железо: крупные пиксельные пятна, копоть, сбитые блики и тупые царапины.
    iron = COLORS["iron"] + block_noise(rng, (64, 64), 4)[..., None]
    iron = np.clip(iron, 38, 112)
    color[0:64, 0:64] = iron
    height[0:64, 0:64] = 128 + block_noise(rng, (64, 64), 4)
    spec[0:64, 0:64] = 74
    dark = COLORS["iron_dark"] + block_noise(rng, (64, 64), 4)[..., None]
    color[64:128, 0:64] = np.clip(dark, 25, 78)
    height[64:128, 0:64] = 122 + block_noise(rng, (64, 64), 4)
    spec[64:128, 0:64] = 52
    # Ржавчина и сколы редкие: это рабочий, а не заброшенный инструмент.
    for _ in range(55):
        x, y = int(rng.integers(2, 62)), int(rng.integers(2, 126))
        s = int(rng.choice((2, 2, 3, 4)))
        color[y:y+s, x:x+s] = (83, 52, 31)
        height[y:y+s, x:x+s] = 106
        spec[y:y+s, x:x+s] = 30
    for _ in range(34):
        x, y = int(rng.integers(2, 58)), int(rng.integers(2, 126))
        length = int(rng.integers(4, 14))
        color[y:y+2, x:min(64, x+length)] = (116, 120, 116)
        height[y:y+2, x:min(64, x+length)] = 148

    # Ясень: вертикальные волокна, сучок и тёмные рабочие пятна.
    for x in range(64, 96):
        band = 12 * math.sin((x - 64) * 0.75) + 7 * math.sin((x - 64) * 0.19)
        for y in range(SIZE):
            jitter = int(block_noise(rng, (1, 1), 1)[0, 0] * 0.25)
            color[y, x] = np.clip(COLORS["wood"] + band + jitter, 25, 160)
            height[y, x] = int(np.clip(128 + band * 0.75, 90, 170))
    color[70:79, 76:85] = (71, 40, 20)
    color[73:76, 73:88] = (83, 48, 22)
    height[70:79, 76:85] = 108
    spec[:, 64:96] = 18

    # Потёртая ткань и необработанный клин занимают последнюю четверть атласа.
    cloth = COLORS["wrap"] + block_noise(rng, (64, 32), 4)[..., None]
    color[0:64, 96:128] = np.clip(cloth, 24, 94)
    for y in range(3, 64, 8):
        color[y:y+2, 96:128] = (85, 67, 52)
        height[y:y+2, 96:128] = 151
    height[0:64, 96:128] += block_noise(rng, (64, 32), 4)
    spec[0:64, 96:128] = 10
    wedge = COLORS["wedge"] + block_noise(rng, (32, 32), 4)[..., None]
    color[64:96, 96:128] = np.clip(wedge, 55, 183)
    for x in range(98, 128, 6):
        color[64:96, x:x+2] = (93, 57, 27)
        height[64:96, x:x+2] = 144
    spec[64:96, 96:128] = 15
    color[96:128, 96:128] = (35, 34, 32)

    gy, gx = np.gradient(height.astype(np.float32) / 255.0)
    nx, ny, nz = -gx * 3.5, -gy * 3.5, np.ones_like(gx)
    length = np.sqrt(nx * nx + ny * ny + nz * nz)
    normal = np.stack((nx / length, ny / length, nz / length), axis=-1)
    normal = ((normal * 0.5 + 0.5) * 255).astype(np.uint8)
    return Image.fromarray(color.clip(0, 255).astype(np.uint8), "RGB"), Image.fromarray(normal, "RGB"), Image.fromarray(spec, "L")


FACE_CORNERS = {
    "north": (0, 1, 2, 3), "south": (5, 4, 7, 6),
    "west": (4, 0, 3, 7), "east": (1, 5, 6, 2),
    "down": (4, 5, 1, 0), "up": (3, 2, 6, 7),
}
FACE_NORMALS = {
    "north": (0, 0, -1), "south": (0, 0, 1), "west": (-1, 0, 0),
    "east": (1, 0, 0), "down": (0, -1, 0), "up": (0, 1, 0),
}


def cube_vertices(part: Cube) -> np.ndarray:
    x0, y0, z0 = part.start
    x1, y1, z1 = part.end
    return np.asarray(((x0, y0, z0), (x1, y0, z0), (x1, y1, z0), (x0, y1, z0),
                       (x0, y0, z1), (x1, y0, z1), (x1, y1, z1), (x0, y1, z1)), dtype=np.float32)


def build_gltf(parts: list[Cube], texture_name: str) -> None:
    blob = bytearray()
    views, accessors, meshes, nodes = [], [], [], [
        {"name": "HeavyHammer_Root", "children": [1, 2, 3, 4, 5]},
        {"name": "Handle_Bone", "children": []}, {"name": "Head_Bone", "children": []},
        {"name": "Grip_Main"}, {"name": "Grip_Offhand", "translation": [0, 10.0 / 16.0, 0]},
        {"name": "Impact_Point", "translation": [-12.42 / 16.0, (19.0 - MAIN_GRIP_Y) / 16.0, 0]},
    ]

    def add_accessor(array: np.ndarray, component: int, kind: str, target: int | None = None, bounds: bool = False) -> int:
        while len(blob) % 4:
            blob.append(0)
        array = np.ascontiguousarray(array)
        offset = len(blob)
        blob.extend(array.tobytes())
        view = {"buffer": 0, "byteOffset": offset, "byteLength": array.nbytes}
        if target:
            view["target"] = target
        views.append(view)
        acc = {"bufferView": len(views) - 1, "componentType": component, "count": len(array), "type": kind}
        if bounds:
            acc["min"] = np.min(array, axis=0).astype(float).tolist()
            acc["max"] = np.max(array, axis=0).astype(float).tolist()
        accessors.append(acc)
        return len(accessors) - 1

    for part in parts:
        base = cube_vertices(part)
        # Единицы модели Minecraft переводятся в масштаб glTF с основным хватом в начале координат.
        base[:, 0] = (base[:, 0] - 8.0) / 16.0
        base[:, 1] = (base[:, 1] - MAIN_GRIP_Y) / 16.0
        base[:, 2] = (base[:, 2] - 8.0) / 16.0
        pos, norm, uv, indices = [], [], [], []
        region = REGIONS[part.material]
        u0, v0, u1, v1 = (value / 16.0 for value in region)
        for face_name, corners in FACE_CORNERS.items():
            start = len(pos)
            for corner, tex in zip(corners, ((u0, v1), (u1, v1), (u1, v0), (u0, v0))):
                pos.append(base[corner])
                norm.append(FACE_NORMALS[face_name])
                uv.append(tex)
            indices += [start, start + 1, start + 2, start, start + 2, start + 3]
        primitive = {"attributes": {
            "POSITION": add_accessor(np.asarray(pos, np.float32), 5126, "VEC3", 34962, True),
            "NORMAL": add_accessor(np.asarray(norm, np.float32), 5126, "VEC3", 34962),
            "TEXCOORD_0": add_accessor(np.asarray(uv, np.float32), 5126, "VEC2", 34962)},
            "indices": add_accessor(np.asarray(indices, np.uint16), 5123, "SCALAR", 34963), "material": 0}
        meshes.append({"name": part.name, "primitives": [primitive]})
        index = len(nodes)
        nodes.append({"name": part.name, "mesh": len(meshes) - 1})
        nodes[2 if part.bone == "head" else 1]["children"].append(index)

    clips = {
        "idle_ready": ([0, .5, 1, 1.5, 2], [-18, -15, -18, -21, -18]),
        "heavy_overhead_strike": ([0, .2, .52, .72, .86, 1.12, 1.45], [-18, -45, -128, -145, 62, 15, -18]),
        "quarry_side_strike": ([0, .25, .55, .74, .9, 1.25], [-24, -72, -118, 48, 8, -24]),
        "carry_walk": ([0, .25, .5, .75, 1], [-62, -57, -62, -67, -62]),
    }
    animations = []
    for name, (times, angles) in clips.items():
        rotations = []
        for angle in angles:
            half = math.radians(angle) * .5
            rotations.append((0, 0, math.sin(half), math.cos(half)))
        input_acc = add_accessor(np.asarray(times, np.float32), 5126, "SCALAR", bounds=True)
        output_acc = add_accessor(np.asarray(rotations, np.float32), 5126, "VEC4")
        animations.append({"name": name, "samplers": [{"input": input_acc, "output": output_acc, "interpolation": "LINEAR"}],
                           "channels": [{"sampler": 0, "target": {"node": 0, "path": "rotation"}}]})

    gltf = {"asset": {"version": "2.0", "generator": "Eclipse RP Minecraft hammer generator"},
            "scene": 0, "scenes": [{"nodes": [0]}], "nodes": nodes, "meshes": meshes,
            "materials": [{"name": "pixel_atlas", "pbrMetallicRoughness": {"baseColorTexture": {"index": 0},
                           "metallicFactor": 0.0, "roughnessFactor": 0.82}}],
            "textures": [{"sampler": 0, "source": 0}], "samplers": [{"magFilter": 9728, "minFilter": 9984}],
            "images": [{"uri": texture_name}], "bufferViews": views, "accessors": accessors,
            "buffers": [{"uri": "heavy_hammer_minecraft.bin", "byteLength": len(blob)}],
            "animations": animations, "extras": {"style": "Minecraft-native", "pixelsPerBlock": 16}}
    (OUT / "heavy_hammer_minecraft.bin").write_bytes(blob)
    (OUT / "heavy_hammer_minecraft.gltf").write_text(json.dumps(gltf, indent=2) + "\n", encoding="utf-8")


def render(parts: list[Cube], angle_y: float, angle_x: float, angle_z: float = -8, size: int = 800) -> Image.Image:
    ay, ax, az = map(math.radians, (angle_y, angle_x, angle_z))
    ry = np.array(((math.cos(ay), 0, math.sin(ay)), (0, 1, 0), (-math.sin(ay), 0, math.cos(ay))))
    rx = np.array(((1, 0, 0), (0, math.cos(ax), -math.sin(ax)), (0, math.sin(ax), math.cos(ax))))
    rz = np.array(((math.cos(az), -math.sin(az), 0), (math.sin(az), math.cos(az), 0), (0, 0, 1)))
    rot = rz @ rx @ ry
    polygons = []
    all_points = []
    light = np.array((-0.45, .75, .55)); light /= np.linalg.norm(light)
    for part in parts:
        verts = cube_vertices(part)
        verts[:, 0] -= 8; verts[:, 1] += 1; verts[:, 2] -= 8
        verts = verts @ rot.T
        all_points.append(verts)
        for face, corners in FACE_CORNERS.items():
            pts = verts[list(corners)]
            normal = np.asarray(FACE_NORMALS[face], float) @ rot.T
            shade = .48 + .58 * abs(float(np.dot(normal, light)))
            color = tuple(np.clip(COLORS[part.material] * shade, 0, 255).astype(int))
            polygons.append((float(np.mean(pts[:, 2])), pts[:, :2], color))
    points = np.concatenate(all_points)
    lo, hi = points[:, :2].min(axis=0), points[:, :2].max(axis=0)
    center = (lo + hi) / 2; extent = max(hi - lo); scale = size * .72 / extent
    im = Image.new("RGB", (size, size), (18, 21, 23)); draw = ImageDraw.Draw(im)
    draw.ellipse((size*.18, size*.82, size*.82, size*.91), fill=(8, 9, 10))
    for _, pts, color in sorted(polygons, reverse=True, key=lambda v: v[0]):
        screen = [((p[0]-center[0])*scale+size/2, size/2-(p[1]-center[1])*scale) for p in pts]
        draw.polygon(screen, fill=color)
        draw.line(screen + [screen[0]], fill=tuple(max(0, c-24) for c in color), width=2)
    return im


def preview(parts: list[Cube], texture: Image.Image) -> None:
    p = OUT / "previews"; p.mkdir(parents=True, exist_ok=True)
    views = [render(parts, 38, -14), render(parts, 0, 0), render(parts, 90, 0), render(parts, 145, -28)]
    sheet = Image.new("RGB", (1600, 1600), (12, 14, 16))
    for i, image in enumerate(views): sheet.paste(image, ((i%2)*800, (i//2)*800))
    sheet.save(p / "heavy_hammer_minecraft_model_sheet.png")
    views[0].save(p / "heavy_hammer_minecraft_hero.png")
    tex_sheet = Image.new("RGB", (1024, 1024), (15, 17, 19))
    tex_sheet.paste(texture.resize((896, 896), Image.Resampling.NEAREST), (64, 96))
    ImageDraw.Draw(tex_sheet).text((64, 44), "128x128 PIXEL ATLAS — IRON / WOOD / CLOTH / WEDGE",
                                   fill=(218, 211, 193), font=ImageFont.load_default())
    tex_sheet.save(p / "heavy_hammer_minecraft_texture_sheet.png")


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    parts = cubes()
    texture, normal, spec = make_textures()
    tex_dir = OUT / "textures"; tex_dir.mkdir(parents=True, exist_ok=True)
    texture.save(tex_dir / "heavy_hammer.png")
    normal.save(tex_dir / "heavy_hammer_n.png")
    spec.save(tex_dir / "heavy_hammer_s.png")
    model = minecraft_model(parts)
    (OUT / "heavy_hammer.json").write_text(json.dumps(model, indent=2) + "\n", encoding="utf-8")
    animation = {"format": 1, "rig": {"root": "HeavyHammer_Root", "mainHand": "Grip_Main",
                 "offHand": "Grip_Offhand", "impact": "Impact_Point"},
                 "clips": {"idle_ready": {"duration": 2.0, "loop": True},
                           "heavy_overhead_strike": {"duration": 1.70, "impact": 1.05},
                           "quarry_side_strike": {"duration": 1.25, "impact": 0.74},
                           "carry_walk": {"duration": 1.0, "loop": True}}}
    (OUT / "heavy_hammer.animation.json").write_text(json.dumps(animation, indent=2) + "\n", encoding="utf-8")
    build_gltf(parts, "textures/heavy_hammer.png")
    preview(parts, texture)

    model_dir = RUNTIME / "models" / "item"; model_dir.mkdir(parents=True, exist_ok=True)
    runtime_tex = RUNTIME / "textures" / "item"; runtime_tex.mkdir(parents=True, exist_ok=True)
    anim_dir = RUNTIME / "animations"; anim_dir.mkdir(parents=True, exist_ok=True)
    shutil.copy2(OUT / "heavy_hammer.json", model_dir / "heavy_hammer.json")
    shutil.copy2(tex_dir / "heavy_hammer.png", runtime_tex / "heavy_hammer.png")
    shutil.copy2(tex_dir / "heavy_hammer_n.png", runtime_tex / "heavy_hammer_n.png")
    shutil.copy2(tex_dir / "heavy_hammer_s.png", runtime_tex / "heavy_hammer_s.png")
    shutil.copy2(OUT / "heavy_hammer.animation.json", anim_dir / "heavy_hammer.animation.json")

    manifest = {"style": "Утилитарный средневековый инструмент в стилистике Minecraft", "texture": [SIZE, SIZE],
                "cuboids": len(parts), "triangles": len(parts)*12, "animations": list(animation["clips"]),
                "runtimeModel": "eclipseclient:models/item/heavy_hammer.json"}
    (OUT / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    (OUT / "README.md").write_text(
        "# Тяжёлый рабочий молот Minecraft\n\n"
        "Недорогой ремонтопригодный средневековый инструмент: грубое кованое железо, ясеневое древко, "
        "простая обмотка, четыре защитные полосы и видимые клинья. Без бронзы, геральдики и декоративной кожи.\n\n"
        "Модель предмета и пиксельный атлас 128 px копируются в ресурсы клиента Eclipse. "
        "glTF-источник содержит жёсткие узлы хватов, точки контакта и четыре клипа.\n",
        encoding="utf-8")
    print(json.dumps(manifest, indent=2))


if __name__ == "__main__":
    main()
