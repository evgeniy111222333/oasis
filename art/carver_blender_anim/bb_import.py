"""Blockbench (.bbmodel) -> Blender importer used by the carver animation build.

Run inside Blender. Converts cubes (with per-element rotation, inflate, per-face UV and
face rotation) into meshes that keep the exact pixel-art texturing of the source model.

Coordinates: Blockbench is Y-up, Blender is Z-up. One Blender unit = one Minecraft block
= 16 model pixels, so (x, y, z)_bb -> (x, -z, y) / 16.
"""
import base64
import json
import math
import os

import bpy
from mathutils import Euler, Matrix, Vector

PX = 1.0 / 16.0

# Face corners as [top-left, top-right, bottom-right, bottom-left] seen from outside,
# using index triples into (from, to): 0 -> from, 1 -> to, per axis.
FACE_CORNERS = {
    'north': [(1, 1, 0), (0, 1, 0), (0, 0, 0), (1, 0, 0)],
    'south': [(0, 1, 1), (1, 1, 1), (1, 0, 1), (0, 0, 1)],
    'east':  [(1, 1, 1), (1, 1, 0), (1, 0, 0), (1, 0, 1)],
    'west':  [(0, 1, 0), (0, 1, 1), (0, 0, 1), (0, 0, 0)],
    'up':    [(0, 1, 0), (1, 1, 0), (1, 1, 1), (0, 1, 1)],
    'down':  [(0, 0, 1), (1, 0, 1), (1, 0, 0), (0, 0, 0)],
}
FACE_NORMAL = {
    'north': (0, 0, -1), 'south': (0, 0, 1), 'east': (1, 0, 0),
    'west': (-1, 0, 0), 'up': (0, 1, 0), 'down': (0, -1, 0),
}


def bb_to_bl(v):
    """Blockbench pixel coordinates -> Blender units."""
    return Vector((v[0] * PX, -v[2] * PX, v[1] * PX))


def bb_rot_matrix(rot):
    """Blockbench element/group rotation [x, y, z] (degrees, ZYX intrinsic) as a 3x3
    matrix acting on Blockbench coordinates."""
    rx, ry, rz = (math.radians(a) for a in rot)
    return (Matrix.Rotation(rz, 3, 'Z') @ Matrix.Rotation(ry, 3, 'Y')
            @ Matrix.Rotation(rx, 3, 'X'))


def _image_from_texture(tex, cache_dir, prefix):
    src = tex.get('source', '')
    name = prefix + '_' + tex.get('name', 'texture.png')
    if not name.lower().endswith('.png'):
        name += '.png'
    path = os.path.join(cache_dir, name)
    if src.startswith('data:image'):
        data = base64.b64decode(src.split(',', 1)[1])
        with open(path, 'wb') as fh:
            fh.write(data)
    img = bpy.data.images.load(path, check_existing=True)
    img.pack()
    return img


def pixel_material(name, image, roughness=0.8, metallic=0.0):
    """Nearest-filtered pixel-art material with alpha cut-out."""
    mat = bpy.data.materials.get(name) or bpy.data.materials.new(name)
    mat.use_nodes = True
    nt = mat.node_tree
    nt.nodes.clear()
    out = nt.nodes.new('ShaderNodeOutputMaterial')
    bsdf = nt.nodes.new('ShaderNodeBsdfPrincipled')
    texn = nt.nodes.new('ShaderNodeTexImage')
    texn.image = image
    texn.interpolation = 'Closest'
    bsdf.inputs['Roughness'].default_value = roughness
    bsdf.inputs['Metallic'].default_value = metallic
    nt.links.new(texn.outputs['Color'], bsdf.inputs['Base Color'])
    nt.links.new(texn.outputs['Alpha'], bsdf.inputs['Alpha'])
    nt.links.new(bsdf.outputs['BSDF'], out.inputs['Surface'])
    out.location = (300, 0)
    texn.location = (-400, 0)
    try:
        mat.surface_render_method = 'DITHERED'
    except AttributeError:
        pass
    return mat


def _cube_polys(el, uv_size, tex_index_of):
    """Yield (corner_positions_bl[4], uvs[4], texture_index) for one cube element."""
    inflate = el.get('inflate', 0.0)
    lo = [el['from'][i] - inflate for i in range(3)]
    hi = [el['to'][i] + inflate for i in range(3)]
    pair = (lo, hi)
    origin = Vector(el.get('origin', [0, 0, 0]))
    rot = el.get('rotation', [0, 0, 0])
    if isinstance(rot, dict):  # legacy java_block {angle, axis, origin}
        axis = rot.get('axis', 'y')
        ang = rot.get('angle', 0)
        rot = [ang if axis == 'x' else 0, ang if axis == 'y' else 0, ang if axis == 'z' else 0]
        origin = Vector(el['rotation'].get('origin', origin))
    m = bb_rot_matrix(rot)
    for face, spec in el.get('faces', {}).items():
        if spec is None or spec.get('texture') is None:
            continue
        ti = tex_index_of(spec['texture'])
        if ti is None:
            continue
        corners = []
        for ix, iy, iz in FACE_CORNERS[face]:
            p = Vector((pair[ix][0], pair[iy][1], pair[iz][2]))
            p = m @ (p - origin) + origin
            corners.append(bb_to_bl(p))
        uw, vh = uv_size(ti)
        u1, v1, u2, v2 = spec.get('uv', [0, 0, 0, 0])
        uvs = [(u1 / uw, 1 - v1 / vh), (u2 / uw, 1 - v1 / vh),
               (u2 / uw, 1 - v2 / vh), (u1 / uw, 1 - v2 / vh)]
        k = int(round(spec.get('rotation', 0) / 90.0)) % 4
        if k:
            uvs = uvs[-k:] + uvs[:-k]
        # Fix winding so the polygon normal points outward.
        expected = m @ Vector(FACE_NORMAL[face])
        expected = Vector((expected[0], -expected[2], expected[1]))
        nrm = (corners[1] - corners[0]).cross(corners[2] - corners[0])
        if nrm.length > 1e-12 and nrm.dot(expected) < 0:
            corners.reverse()
            uvs.reverse()
        yield corners, uvs, ti


def _build_mesh(name, polys, materials):
    verts, faces, uvs, mats = [], [], [], []
    for corners, puv, ti in polys:
        base = len(verts)
        verts.extend(corners)
        faces.append([base, base + 1, base + 2, base + 3])
        uvs.extend(puv)
        mats.append(ti)
    me = bpy.data.meshes.new(name)
    me.from_pydata([tuple(v) for v in verts], [], faces)
    uvl = me.uv_layers.new(name='UVMap')
    for i, loop in enumerate(me.loops):
        uvl.data[i].uv = uvs[loop.vertex_index]
    for mat in materials:
        me.materials.append(mat)
    for poly, ti in zip(me.polygons, mats):
        poly.material_index = ti
    me.update()
    return me


def import_bbmodel(path, name, collection=None, cache_dir=None, origin_px=(0, 0, 0),
                   material_kw=None):
    """Import a .bbmodel. Returns (root_empty, {group_name: mesh_object}).

    origin_px: Blockbench point that becomes the root empty's origin.
    Every group becomes its own mesh object (direct child cubes only), parented to the
    root, with its origin at the group pivot so it can be animated or hidden on its own.
    """
    with open(path, encoding='utf-8') as fh:
        data = json.load(fh)
    collection = collection or bpy.context.scene.collection
    cache_dir = cache_dir or os.path.join(os.path.dirname(path), '_tex')
    os.makedirs(cache_dir, exist_ok=True)
    material_kw = material_kw or {}

    res = data.get('resolution', {'width': 16, 'height': 16})
    textures = data.get('textures', [])
    images = [_image_from_texture(t, cache_dir, name) for t in textures]
    materials = [pixel_material(f'{name}_{i}', img, **material_kw)
                 for i, img in enumerate(images)]
    uuid_index = {t.get('uuid'): i for i, t in enumerate(textures)}

    def tex_index_of(ref):
        if isinstance(ref, int):
            return ref if 0 <= ref < len(textures) else None
        if isinstance(ref, str):
            if ref in uuid_index:
                return uuid_index[ref]
            if ref.isdigit():
                return int(ref)
        return None

    def uv_size(ti):
        t = textures[ti]
        return (t.get('uv_width') or res['width'], t.get('uv_height') or res['height'])

    elements = {e['uuid']: e for e in data.get('elements', [])}
    origin = Vector(origin_px)

    root = bpy.data.objects.new(name, None)
    root.empty_display_size = 0.25
    collection.objects.link(root)
    parts = {}

    def add_part(gname, cube_ids, pivot_px, parent_obj, group_rot):
        polys = []
        for cid in cube_ids:
            el = elements.get(cid)
            if el is None or el.get('type', 'cube') != 'cube' or el.get('visibility') is False:
                continue
            polys.extend(_cube_polys(el, uv_size, tex_index_of))
        pivot = Vector(pivot_px)
        obj = None
        if polys:
            # Bake vertices relative to the pivot so the object origin is the pivot.
            shift = bb_to_bl(pivot)
            polys = [([c - shift for c in cs], uv, ti) for cs, uv, ti in polys]
            me = _build_mesh(f'{name}.{gname}', polys, materials)
            obj = bpy.data.objects.new(f'{name}.{gname}', me)
        else:
            obj = bpy.data.objects.new(f'{name}.{gname}', None)
            obj.empty_display_size = 0.05
        collection.objects.link(obj)
        obj.parent = parent_obj
        parent_pivot = parent_obj.get('bb_pivot', list(origin))
        obj.location = bb_to_bl(pivot) - bb_to_bl(Vector(parent_pivot))
        if any(group_rot):
            r = bb_rot_matrix(group_rot)
            conv = Matrix(((1, 0, 0), (0, 0, -1), (0, 1, 0)))
            obj.rotation_mode = 'QUATERNION'
            obj.rotation_quaternion = (conv @ r @ conv.inverted()).to_quaternion()
        obj['bb_pivot'] = list(pivot)
        parts[gname] = obj
        return obj

    root['bb_pivot'] = list(origin)
    loose = []

    group_props = {g.get('uuid'): g for g in data.get('groups', []) if isinstance(g, dict)}

    def walk(node, parent_obj):
        if isinstance(node, str):
            return
        props = dict(group_props.get(node.get('uuid'), {}))
        props.update({k: v for k, v in node.items() if k != 'children'})
        cubes = [c for c in node.get('children', []) if isinstance(c, str)]
        gname = props.get('name') or f'group{len(parts)}'
        obj = add_part(gname, cubes, props.get('origin', [0, 0, 0]), parent_obj,
                       props.get('rotation', [0, 0, 0]))
        for child in node.get('children', []):
            walk(child, obj)

    for node in data.get('outliner', []):
        if isinstance(node, str):
            loose.append(node)
        else:
            walk(node, root)
    if loose:
        add_part('loose', loose, list(origin), root, [0, 0, 0])
    return root, parts
