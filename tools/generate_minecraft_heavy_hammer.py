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
MAIN_GRIP_Y = 2.4
OFFHAND_GRIP_Y = 9.6

REGIONS = {
    "iron": (0, 0, 8, 8),
    "iron_dark": (0, 8, 8, 16),
    "wood": (8, 0, 12, 16),
    "face": (12, 0, 16, 8),
    "wedge": (12, 8, 16, 12),
}
COLORS = {
    "iron": np.array((47, 50, 52)),
    "iron_dark": np.array((30, 33, 35)),
    "wood": np.array((113, 65, 31)),
    "face": np.array((91, 94, 97)),
    "wedge": np.array((155, 86, 33)),
}


@dataclass(frozen=True)
class Cube:
    name: str
    start: tuple[float, float, float]
    end: tuple[float, float, float]
    material: str
    bone: str


def cubes() -> list[Cube]:
    # Простая ремонтопригодная конструкция из референса: длинное цельное древко,
    # прямоугольный кованый боёк, ступенчатые шейки и светлые рабочие торцы.
    return [
        Cube("ash_haft", (7.0, 0.0, 7.0), (9.0, 25.0, 9.0), "wood", "handle"),
        Cube("socket", (6.35, 21.0, 6.25), (9.65, 26.0, 9.75), "iron_dark", "head"),
        Cube("head_core", (-0.5, 23.0, 4.65), (16.5, 30.0, 11.35), "iron", "head"),
        Cube("left_neck", (-3.0, 23.5, 5.15), (-0.5, 29.5, 10.85), "iron_dark", "head"),
        Cube("right_neck", (16.5, 23.5, 5.15), (19.0, 29.5, 10.85), "iron_dark", "head"),
        Cube("left_cap", (-4.45, 22.75, 4.35), (-3.0, 30.25, 11.65), "iron_dark", "head"),
        Cube("right_cap", (19.0, 22.75, 4.35), (20.45, 30.25, 11.65), "iron_dark", "head"),
        Cube("left_striking_face", (-4.65, 23.45, 5.05), (-4.43, 29.55, 10.95), "face", "head"),
        Cube("right_striking_face", (20.43, 23.45, 5.05), (20.65, 29.55, 10.95), "face", "head"),
        Cube("wood_wedge", (5.85, 30.0, 6.55), (10.15, 30.75, 9.45), "wedge", "head"),
        Cube("cross_wedge", (7.35, 30.72, 5.9), (8.65, 31.15, 10.1), "iron_dark", "head"),
    ]


def faces_for(region: tuple[int, int, int, int]) -> dict:
    uv = list(region)
    return {face: {"uv": uv, "texture": "#0"}
            for face in ("north", "east", "south", "west", "up", "down")}


def minecraft_model(parts: list[Cube]) -> dict:
    return {
        "credit": "Процедурный тяжёлый рабочий молот Eclipse RP 1.4.1",
        "parent": "minecraft:item/handheld",
        "ambientocclusion": True,
        "texture_size": [SIZE, SIZE],
        "textures": {"0": "eclipseclient:item/heavy_hammer", "particle": "eclipseclient:item/heavy_hammer"},
        "elements": [
            {"name": part.name, "from": list(part.start), "to": list(part.end),
             "shade": True, "faces": faces_for(REGIONS[part.material])}
            for part in parts
        ],
        "display": {
            "thirdperson_righthand": {"rotation": [0, -90, 55], "translation": [0, 3.9, 0.5], "scale": [0.57, 0.57, 0.57]},
            "thirdperson_lefthand": {"rotation": [0, 90, -55], "translation": [0, 3.9, 0.5], "scale": [0.57, 0.57, 0.57]},
            "firstperson_righthand": {"rotation": [0, -90, 25], "translation": [1.13, 3.2, 1.13], "scale": [0.61, 0.61, 0.61]},
            "firstperson_lefthand": {"rotation": [0, 90, -25], "translation": [1.13, 3.2, 1.13], "scale": [0.61, 0.61, 0.61]},
            "ground": {"rotation": [0, 0, 78], "translation": [0, 2.4, 0], "scale": [0.34, 0.34, 0.34]},
            "gui": {"rotation": [25, 142, -32], "translation": [0, -0.5, 0], "scale": [0.43, 0.43, 0.43]},
            "fixed": {"rotation": [0, 0, -45], "translation": [0, -1.0, 0], "scale": [0.40, 0.40, 0.40]},
        },
    }


def make_textures() -> tuple[Image.Image, Image.Image, Image.Image]:
    color = np.full((SIZE, SIZE, 3), (22, 24, 25), dtype=np.uint8)
    spec = np.full((SIZE, SIZE), 12, dtype=np.uint8)

    # Матовые крупные тона без каменной крошки, ржавой каши и случайного шума.
    color[0:64, 0:64] = COLORS["iron"]
    color[0:8, 0:64] = (57, 60, 62)
    color[56:64, 0:64] = (39, 42, 44)
    color[16:48, 24:32] = (52, 55, 57)
    spec[0:64, 0:64] = 34

    color[64:128, 0:64] = COLORS["iron_dark"]
    color[64:72, 0:64] = (38, 41, 43)
    color[120:128, 0:64] = (24, 27, 29)
    spec[64:128, 0:64] = 24

    # Древко читается вертикальными пиксельными полосами, как в исходном рендере.
    wood_bands = ((82, 45, 22), (113, 65, 31), (128, 73, 34), (99, 55, 26))
    for index, x in enumerate(range(64, 96, 4)):
        color[:, x:x + 4] = wood_bands[index % len(wood_bands)]
    color[0:8, 64:96] = (136, 78, 37)
    color[120:128, 64:96] = (76, 42, 21)
    spec[:, 64:96] = 10

    color[0:64, 96:128] = COLORS["face"]
    color[0:6, 96:128] = (111, 114, 117)
    color[58:64, 96:128] = (70, 73, 76)
    color[8:56, 102:108] = (98, 101, 104)
    spec[0:64, 96:128] = 48

    color[64:96, 96:128] = COLORS["wedge"]
    color[64:96, 96:102] = (111, 60, 25)
    color[64:96, 120:128] = (177, 99, 39)
    spec[64:96, 96:128] = 8

    normal = np.zeros((SIZE, SIZE, 3), dtype=np.uint8)
    normal[:, :] = (128, 128, 255)
    return Image.fromarray(color, "RGB"), Image.fromarray(normal, "RGB"), Image.fromarray(spec, "L")


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
        {"name": "Grip_Main"},
        {"name": "Grip_Offhand", "translation": [0, (OFFHAND_GRIP_Y - MAIN_GRIP_Y) / 16.0, 0]},
        {"name": "Impact_Point", "translation": [-12.65 / 16.0, (26.5 - MAIN_GRIP_Y) / 16.0, 0]},
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
    ImageDraw.Draw(tex_sheet).text((64, 44), "128x128 PIXEL ATLAS — IRON / WOOD / FACE / WEDGE",
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

    bounds = {
        "min": [min(part.start[index] for part in parts) for index in range(3)],
        "max": [max(part.end[index] for part in parts) for index in range(3)],
    }
    manifest = {"style": "Утилитарный средневековый инструмент в стилистике Minecraft", "texture": [SIZE, SIZE],
                "cuboids": len(parts), "triangles": len(parts)*12, "animations": list(animation["clips"]),
                "mainGripY": MAIN_GRIP_Y, "offhandGripY": OFFHAND_GRIP_Y, "bounds": bounds,
                "runtimeModel": "eclipseclient:models/item/heavy_hammer.json"}
    (OUT / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    (OUT / "README.md").write_text(
        "# Тяжёлый рабочий молот Minecraft\n\n"
        "Недорогой ремонтопригодный средневековый инструмент: матовое кованое железо, длинное ясеневое древко, "
        "ступенчатые шейки, светлые рабочие торцы и видимые клинья. Без ржавого шума, геральдики и декоративной кожи.\n\n"
        "Модель предмета и пиксельный атлас 128 px копируются в ресурсы клиента Eclipse. "
        "glTF-источник содержит жёсткие узлы хватов, точки контакта и четыре клипа.\n",
        encoding="utf-8")
    print(json.dumps(manifest, indent=2))


if __name__ == "__main__":
    main()
