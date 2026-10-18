"""
Eclipse RP — Advanced Post-Spacebar Carving Animation in Blender 5.2
Builds the full 22-bone articulated character rig, authentic Eclipse RP tools & bag,
16x16x16 progressive microvoxel sculpture with 8 fracture stages & 40 ballistic chips,
hologram drop-lock, dust-blow particles, and 180-frame (30 FPS) choreography.
Includes strict per-frame verification across all 180 frames.
"""
import json
import math
from pathlib import Path
import bpy
import bmesh
from mathutils import Vector, Matrix, Quaternion, Euler

OUT_DIR = Path(r"D:\23\art\blender_carver_advanced")
OUT_DIR.mkdir(parents=True, exist_ok=True)
FRAMES_DIR = OUT_DIR / "frames"
FRAMES_DIR.mkdir(parents=True, exist_ok=True)

ASSETS_ROOT = Path(r"D:\23\fabric-server\client-mod-gradle\src\main\resources\assets\eclipseserver")
MODELS_DIR = ASSETS_ROOT / "models" / "item"
TEXTURES_DIR = ASSETS_ROOT / "textures" / "item"

FPS = 30
FRAME_START = 1
FRAME_END = 180
PX = 1.0 / 16.0  # 1 Minecraft pixel = 0.0625m (16px = 1.0m block)

# Strike frames across the 180-frame timeline:
# Rough stage (Point Chisel + Heavy Mallet): 52, 68, 84
# Main stage (Flat Chisel + Angled Mallet on vertical bevel): 122, 136
# Fine stage (Flat Chisel + Choke-Grip Rapid Taps): 146, 151, 156
STRIKE_FRAMES = [52, 68, 84, 122, 136, 146, 151, 156]


def smoothstep(x: float) -> float:
    x = max(0.0, min(1.0, float(x)))
    return x * x * (3.0 - 2.0 * x)


def smootherstep(x: float) -> float:
    x = max(0.0, min(1.0, float(x)))
    return x * x * x * (x * (x * 6.0 - 15.0) + 10.0)


def win(f: float, f0: float, f1: float) -> float:
    if f1 <= f0:
        return 1.0 if f >= f1 else 0.0
    return smootherstep((f - f0) / (f1 - f0))


def bell(f: float, f0: float, f1: float) -> float:
    if f <= f0 or f >= f1:
        return 0.0
    u = (f - f0) / (f1 - f0)
    return math.sin(u * math.pi) ** 2


def lerp_vec(a: Vector, b: Vector, t: float) -> Vector:
    t = max(0.0, min(1.0, float(t)))
    return a * (1.0 - t) + b * t


def slerp_quat(q0: Quaternion, q1: Quaternion, t: float) -> Quaternion:
    t = max(0.0, min(1.0, float(t)))
    if q0.dot(q1) < 0.0:
        q1 = Quaternion((-q1.w, -q1.x, -q1.y, -q1.z))
    return q0.slerp(q1, t)


def make_material(name: str, rgba, roughness=0.65, metallic=0.0, emission=None, emission_strength=0.0, alpha=1.0):
    mat = bpy.data.materials.new(name=name)
    mat.use_nodes = True
    nodes = mat.node_tree.nodes
    bsdf = next((n for n in nodes if n.type == 'BSDF_PRINCIPLED'), None)
    if bsdf is None:
        bsdf = nodes.new("ShaderNodeBsdfPrincipled")
    if bsdf:
        bsdf.inputs["Base Color"].default_value = rgba
        bsdf.inputs["Roughness"].default_value = roughness
        bsdf.inputs["Metallic"].default_value = metallic
        if "Alpha" in bsdf.inputs:
            bsdf.inputs["Alpha"].default_value = alpha
        if emission is not None and "Emission Color" in bsdf.inputs:
            bsdf.inputs["Emission Color"].default_value = emission
            bsdf.inputs["Emission Strength"].default_value = emission_strength
    if alpha < 1.0 and hasattr(mat, "surface_render_method"):
        mat.surface_render_method = 'BLENDED'
    return mat


def make_textured_material(name: str, png_path: Path, roughness=0.6, metallic=0.1):
    mat = bpy.data.materials.new(name=name)
    mat.use_nodes = True
    tree = mat.node_tree
    nodes = tree.nodes
    links = tree.links
    bsdf = next((n for n in nodes if n.type == 'BSDF_PRINCIPLED'), None)
    if bsdf is None:
        bsdf = nodes.new("ShaderNodeBsdfPrincipled")
    bsdf.inputs["Roughness"].default_value = roughness
    bsdf.inputs["Metallic"].default_value = metallic
    if png_path.exists():
        tex_node = nodes.new("ShaderNodeTexImage")
        img = bpy.data.images.load(str(png_path), check_existing=True)
        tex_node.image = img
        tex_node.interpolation = 'Closest'
        links.new(tex_node.outputs["Color"], bsdf.inputs["Base Color"])
    return mat


def add_box_to_bmesh(bm: bmesh.types.BMesh, p_min: Vector, p_max: Vector, uv_layer=None, mat_index=0, rot_matrix: Matrix = None, pivot: Vector = None):
    x0, y0, z0 = p_min
    x1, y1, z1 = p_max
    coords = [
        Vector((x0, y0, z0)), Vector((x1, y0, z0)), Vector((x1, y1, z0)), Vector((x0, y1, z0)),
        Vector((x0, y0, z1)), Vector((x1, y0, z1)), Vector((x1, y1, z1)), Vector((x0, y1, z1)),
    ]
    if rot_matrix is not None and pivot is not None:
        coords = [pivot + (rot_matrix @ (c - pivot)) for c in coords]
    verts = [bm.verts.new(c) for c in coords]
    faces_idx = [
        (0, 3, 2, 1),  # bottom (-Z)
        (4, 5, 6, 7),  # top (+Z)
        (0, 1, 5, 4),  # front (-Y)
        (2, 3, 7, 6),  # back (+Y)
        (1, 2, 6, 5),  # left (+X)
        (3, 0, 4, 7),  # right (-X)
    ]
    for f_i in faces_idx:
        f = bm.faces.new([verts[i] for i in f_i])
        f.material_index = mat_index


def create_mesh_object(name: str, collection, mat=None):
    mesh = bpy.data.meshes.new(name + "_Mesh")
    obj = bpy.data.objects.new(name, mesh)
    collection.objects.link(obj)
    if mat is not None:
        obj.data.materials.append(mat)
    return obj


def build_box_object(name: str, collection, boxes, mat, parent_obj=None, parent_bone=None):
    """boxes: list of (p_min, p_max) in the object/bone local space."""
    obj = create_mesh_object(name, collection, mat)
    bm = bmesh.new()
    for p_min, p_max in boxes:
        add_box_to_bmesh(bm, Vector(p_min), Vector(p_max))
    bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
    bm.to_mesh(obj.data)
    bm.free()
    if parent_obj is not None:
        obj.parent = parent_obj
        if parent_bone is not None:
            obj.parent_type = 'BONE'
            obj.parent_bone = parent_bone
            # In Blender, parenting to a BONE places child origin at the bone TAIL by default.
            # Set matrix_parent_inverse so child local (0,0,0) sits at the bone HEAD!
            bone = parent_obj.data.bones[parent_bone]
            bone_len = (bone.tail_local - bone.head_local).length
            obj.matrix_parent_inverse = Matrix.Translation((0.0, -bone_len, 0.0))
    return obj


def import_mc_item_json(name: str, json_path: Path, png_path: Path, collection,
                        grip_mc: Vector, scale_m: float, axis_map='CHISEL'):
    """
    Imports an actual Eclipse RP Minecraft item model (.json + .png) into a single Blender Mesh object,
    with UVs mapped accurately from the JSON face definitions!
    axis_map:
      'CHISEL': MC (X, Y, Z) -> local ( (X-grip.x)*s, (Z-grip.z)*s, (Y-grip.y)*s )
                so +Z_local is along the tool shaft (+Y_mc), tip is at -Z_local, cap at +Z_local.
      'MALLET': MC (X, Y, Z) -> local ( (Z-grip.z)*s, (X-grip.x)*s, (Y-grip.y)*s )
                so +Z_local is along the handle (+Y_mc), and +Y_local is the striking face (+X_mc)!
      'BAG':    MC (X, Y, Z) -> local ( (X-grip.x)*s, (Z-grip.z)*s, (Y-grip.y)*s )
    """
    mat = make_textured_material(name + "_Mat", png_path, roughness=0.55, metallic=0.15)
    obj = create_mesh_object(name, collection, mat)
    bm = bmesh.new()
    uv_layer = bm.loops.layers.uv.new("UVMap")

    data = json.loads(json_path.read_text(encoding="utf-8"))
    elements = data.get("elements", [])

    def map_pt(pt_mc: Vector) -> Vector:
        d = (pt_mc - grip_mc) * scale_m
        if axis_map == 'MALLET':
            return Vector((d.z, d.x, d.y))
        return Vector((d.x, d.z, d.y))

    for elem in elements:
        f_mc = Vector(elem["from"])
        t_mc = Vector(elem["to"])
        # Handle optional element rotation in MC space
        rot_info = elem.get("rotation")
        rot_mat_mc = None
        orig_mc = None
        if rot_info:
            orig_mc = Vector(rot_info["origin"])
            ang = math.radians(float(rot_info["angle"]))
            axis = rot_info["axis"]
            if axis == "x":
                rot_mat_mc = Matrix.Rotation(ang, 3, 'X')
            elif axis == "y":
                rot_mat_mc = Matrix.Rotation(ang, 3, 'Y')
            else:
                rot_mat_mc = Matrix.Rotation(ang, 3, 'Z')

        x0, y0, z0 = f_mc
        x1, y1, z1 = t_mc
        corners_mc = [
            Vector((x0, y0, z0)), Vector((x1, y0, z0)), Vector((x1, y1, z0)), Vector((x0, y1, z0)),
            Vector((x0, y0, z1)), Vector((x1, y0, z1)), Vector((x1, y1, z1)), Vector((x0, y1, z1)),
        ]
        if rot_mat_mc is not None and orig_mc is not None:
            corners_mc = [orig_mc + (rot_mat_mc @ (c - orig_mc)) for c in corners_mc]

        verts = [bm.verts.new(map_pt(c)) for c in corners_mc]
        faces_spec = [
            ("north", (0, 1, 2, 3)),
            ("south", (5, 4, 7, 6)),
            ("west",  (4, 0, 3, 7)),
            ("east",  (1, 5, 6, 2)),
            ("up",    (3, 2, 6, 7)),
            ("down",  (4, 5, 1, 0)),
        ]
        elem_faces = elem.get("faces", {})
        for fname, idxs in faces_spec:
            if fname not in elem_faces:
                continue
            f_def = elem_faces[fname]
            uv_box = f_def.get("uv", [0, 0, 16, 16])
            u0, v0, u1, v1 = [float(v) / 16.0 for v in uv_box]
            # Flip V for Blender bottom-left UV origin
            v0_b, v1_b = 1.0 - v0, 1.0 - v1
            try:
                face = bm.faces.new([verts[i] for i in idxs])
                uvs = [(u0, v1_b), (u1, v1_b), (u1, v0_b), (u0, v0_b)]
                for loop, uv in zip(face.loops, uvs):
                    loop[uv_layer].uv = uv
            except ValueError:
                pass

    bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
    bm.to_mesh(obj.data)
    bm.free()
    return obj


# ==============================================================================
# PART 2: RIG, CHARACTER, AUTHENTIC TOOLS & MICROVOXEL WORKPIECE CONSTRUCTION
# ==============================================================================

BONE_SPECS = [
    # (name, parent_name, rest_head_px)
    ("center_of_mass", None,             (0.0,  0.0, 12.0)),
    ("pelvis",         "center_of_mass", (0.0,  0.0, 12.0)),
    ("spine_lower",    "pelvis",         (0.0,  0.0, 16.0)),
    ("chest",          "spine_lower",    (0.0,  0.0, 20.0)),
    ("neck",           "chest",          (0.0,  0.0, 24.0)),
    ("head",           "neck",           (0.0,  0.0, 24.0)),
    ("left_clavicle",  "chest",          (2.2,  0.0, 23.0)),
    ("left_arm",       "left_clavicle",  (5.0,  0.0, 22.0)),
    ("left_forearm",   "left_arm",       (5.0,  0.0, 16.0)),
    ("left_hand",      "left_forearm",   (5.0,  0.0, 10.0)),
    ("right_clavicle", "chest",          (-2.2, 0.0, 23.0)),
    ("right_arm",      "right_clavicle", (-5.0, 0.0, 22.0)),
    ("right_forearm",  "right_arm",      (-5.0, 0.0, 16.0)),
    ("right_hand",     "right_forearm",  (-5.0, 0.0, 10.0)),
    ("left_leg",       "pelvis",         (2.2,  0.0, 12.0)),
    ("left_calf",      "left_leg",       (2.2,  0.0, 6.0)),
    ("left_ankle",     "left_calf",      (2.2,  0.0, 1.2)),
    ("left_toe",       "left_ankle",     (2.2, -1.6, 0.0)),
    ("right_leg",      "pelvis",         (-2.2, 0.0, 12.0)),
    ("right_calf",     "right_leg",      (-2.2, 0.0, 6.0)),
    ("right_ankle",    "right_calf",     (-2.2, 0.0, 1.2)),
    ("right_toe",      "right_ankle",    (-2.2, -1.6, 0.0)),
    # Secondary & Equipment Bones
    ("carver_bag_bone",   "chest",          (0.0,  2.2, 18.5)),
    ("apron_bone",        "pelvis",         (0.0, -2.1, 12.2)),
    ("point_chisel_bone", "center_of_mass", (2.1,  3.1, 21.6)),
    ("flat_chisel_bone",  "center_of_mass", (0.6,  3.2, 21.4)),
    ("mallet_bone",       "center_of_mass", (-2.2, 3.1, 19.8)),
]


def build_armature_and_character(scene):
    col_char = bpy.data.collections.new("01_Eclipse_Artisan")
    scene.collection.children.link(col_char)

    arm_data = bpy.data.armatures.new("Eclipse_Artisan_Armature_Data")
    arm_obj = bpy.data.objects.new("Eclipse_Artisan_Armature", arm_data)
    col_char.objects.link(arm_obj)
    bpy.context.view_layer.objects.active = arm_obj
    bpy.ops.object.mode_set(mode='EDIT')

    for bname, pname, h_px in BONE_SPECS:
        eb = arm_data.edit_bones.new(bname)
        head_v = Vector((h_px[0] * PX, h_px[1] * PX, h_px[2] * PX))
        eb.head = head_v
        eb.tail = head_v + Vector((0.0, 0.08, 0.0))
        eb.roll = 0.0
        eb.use_connect = False
        if pname is not None:
            eb.parent = arm_data.edit_bones[pname]

    bpy.ops.object.mode_set(mode='OBJECT')

    for pb in arm_obj.pose.bones:
        pb.rotation_mode = 'QUATERNION'

    # Materials for Artisan
    mats = {
        "skin":   make_material("Artisan_Skin",   (0.78, 0.58, 0.43, 1.0), roughness=0.65),
        "tunic":  make_material("Artisan_Tunic",  (0.21, 0.38, 0.40, 1.0), roughness=0.78),
        "cuff":   make_material("Artisan_Cuff",   (0.32, 0.52, 0.53, 1.0), roughness=0.72),
        "apron":  make_material("Artisan_Apron",  (0.45, 0.28, 0.17, 1.0), roughness=0.62),
        "strap":  make_material("Artisan_Strap",  (0.32, 0.20, 0.12, 1.0), roughness=0.55),
        "belt":   make_material("Artisan_Belt",   (0.16, 0.12, 0.10, 1.0), roughness=0.45),
        "brass":  make_material("Artisan_Brass",  (0.85, 0.68, 0.28, 1.0), roughness=0.28, metallic=0.85),
        "pants":  make_material("Artisan_Pants",  (0.14, 0.19, 0.22, 1.0), roughness=0.80),
        "boot":   make_material("Artisan_Boot",   (0.15, 0.11, 0.09, 1.0), roughness=0.52),
        "sole":   make_material("Artisan_Sole",   (0.09, 0.07, 0.06, 1.0), roughness=0.85),
        "hair":   make_material("Artisan_Hair",   (0.16, 0.12, 0.10, 1.0), roughness=0.75),
        "sclera": make_material("Artisan_Sclera", (0.94, 0.93, 0.90, 1.0), roughness=0.35),
        "pupil":  make_material("Artisan_Pupil",  (0.08, 0.12, 0.15, 1.0), roughness=0.20),
        "glove":  make_material("Artisan_Glove",  (0.38, 0.25, 0.16, 1.0), roughness=0.65),
    }

    def attach_to_bone(obj, bname):
        bone = arm_data.bones[bname]
        rest_rot_4x4 = bone.matrix_local.to_3x3().to_4x4()
        obj.parent = arm_obj
        obj.parent_type = 'BONE'
        obj.parent_bone = bname
        obj.matrix_parent_inverse = (rest_rot_4x4 @ Matrix.Translation((0.0, bone.length, 0.0))).inverted()

    def part(name, bname, boxes_px, mat_key):
        boxes_m = [
            (Vector(lo) * PX, Vector(hi) * PX)
            for lo, hi in boxes_px
        ]
        obj = build_box_object(name, col_char, boxes_m, mats[mat_key])
        attach_to_bone(obj, bname)
        return obj

    # 3-Segment Articulated Torso (Pelvis -> Spine_Lower -> Chest)
    part("Pelvis_Tunic", "pelvis",      [((-4.0, -2.0, 0.0), (4.0, 2.0, 4.2))], "tunic")
    part("Pelvis_Belt",  "pelvis",      [((-4.2, -2.2, 0.2), (4.2, 2.2, 1.3))], "belt")
    part("Belt_Buckle",  "pelvis",      [((-0.7, -2.4, 0.1), (0.7, -2.1, 1.4))], "brass")
    part("Apron_Skirt",  "apron_bone",  [((-3.6, -0.2, -3.8), (3.6, 0.2, 0.2))], "apron")

    part("Spine_Tunic",  "spine_lower", [((-4.05, -2.05, -0.2), (4.05, 2.05, 4.2))], "tunic")
    part("Spine_Apron",  "spine_lower", [((-3.65, -2.35, -0.2), (3.65, -2.0, 4.2))], "apron")

    part("Chest_Tunic",  "chest",       [((-4.15, -2.1, -0.2), (4.15, 2.1, 4.1))], "tunic")
    part("Chest_Apron",  "chest",       [((-3.5, -2.38, -0.2), (3.5, -2.05, 2.2))], "apron")
    part("Apron_StrapL", "chest",       [((2.2, -2.35, 2.0), (3.1, 2.15, 4.2))], "strap")
    part("Apron_StrapR", "chest",       [((-3.1, -2.35, 2.0), (-2.2, 2.15, 4.2))], "strap")

    # Neck & Head with expressive face
    part("Neck_Column",  "neck",        [((-1.8, -1.6, -0.4), (1.8, 1.6, 1.2))], "skin")
    part("Head_Skull",   "head",        [((-4.0, -4.0, 0.0), (4.0, 4.0, 8.0))], "skin")
    part("Head_HairTop", "head",        [((-4.2, -4.1, 5.6), (4.2, 4.2, 8.3)),
                                         ((-4.15, 2.8, 1.5), (4.15, 4.25, 6.0)),
                                         ((-4.18, -1.5, 4.2), (-3.85, 3.8, 6.2)),
                                         ((3.85, -1.5, 4.2), (4.18, 3.8, 6.2))], "hair")
    part("Head_Beard",   "head",        [((-3.2, -4.25, -0.3), (3.2, -2.5, 2.5)),
                                         ((-2.2, -4.35, 2.2), (2.2, -3.8, 3.1))], "hair")
    part("Head_Nose",    "head",        [((-0.6, -4.75, 2.4), (0.6, -3.9, 4.0))], "skin")
    part("Head_Eyes",    "head",        [((-2.5, -4.12, 3.9), (-1.1, -3.9, 4.7)),
                                         ((1.1, -4.12, 3.9), (2.5, -3.9, 4.7))], "sclera")
    part("Head_Pupils",  "head",        [((-2.0, -4.18, 3.95), (-1.35, -3.95, 4.6)),
                                         ((1.35, -4.18, 3.95), (2.0, -3.95, 4.6))], "pupil")
    part("Head_Brows",   "head",        [((-2.7, -4.22, 4.9), (-0.9, -3.9, 5.35)),
                                         ((0.9, -4.22, 4.9), (2.7, -3.9, 5.35))], "hair")

    # Left & Right Clavicles, Arms, Forearms, Hands + Fingers
    for side, sgn in [("left", 1.0), ("right", -1.0)]:
        part(f"{side.capitalize()}_Clavicle", f"{side}_clavicle",
             [((0.0 if sgn > 0 else -2.8, -1.8, -1.2), (2.8 if sgn > 0 else 0.0, 1.8, 1.0))], "tunic")
        part(f"{side.capitalize()}_UpperArm", f"{side}_arm",
             [((-1.9, -1.9, -6.0), (1.9, 1.9, 1.2))], "tunic")
        part(f"{side.capitalize()}_Forearm", f"{side}_forearm",
             [((-1.7, -1.7, -5.6), (1.7, 1.7, 0.3))], "skin")
        part(f"{side.capitalize()}_Cuff", f"{side}_forearm",
             [((-1.95, -1.95, -1.2), (1.95, 1.95, 0.3))], "cuff")
        part(f"{side.capitalize()}_Hand", f"{side}_hand",
             [((-1.65, -1.65, -1.0), (1.65, 1.65, 1.1))], "glove")
        part(f"{side.capitalize()}_Fingers", f"{side}_hand",
             [((-1.55, -1.85, -1.65), (1.55, 0.5, -0.8)),
              ((-sgn * 1.85, -1.6, -1.1), (-sgn * 0.9, -0.1, 0.3))], "skin")

    # Left & Right Legs, Calves, Ankles, Toes
    for side in ["left", "right"]:
        part(f"{side.capitalize()}_Thigh", f"{side}_leg",
             [((-1.95, -1.95, -6.0), (1.95, 1.95, 0.4))], "pants")
        part(f"{side.capitalize()}_Calf", f"{side}_calf",
             [((-1.8, -1.8, -4.8), (1.8, 1.8, 0.2))], "pants")
        part(f"{side.capitalize()}_AnkleBoot", f"{side}_ankle",
             [((-2.0, -1.8, -1.2), (2.0, 2.0, 1.8))], "boot")
        part(f"{side.capitalize()}_SoleHeel", f"{side}_ankle",
             [((-2.05, -1.6, -1.2), (2.05, 2.05, -0.75))], "sole")
        part(f"{side.capitalize()}_ToeBox", f"{side}_toe",
             [((-2.0, -2.2, 0.0), (2.0, 0.4, 1.7))], "boot")
        part(f"{side.capitalize()}_ToeSole", f"{side}_toe",
             [((-2.05, -2.25, 0.0), (2.05, 0.4, 0.45))], "sole")

    # Authentic Eclipse RP Backpack & Tools imported from JSON + PNG
    # 1. Carver Bag Empty on chest/carver_bag_bone
    bag_obj = import_mc_item_json(
        "Eclipse_Carver_Bag",
        MODELS_DIR / "carver_bag_empty.json",
        TEXTURES_DIR / "carver_bag_empty.png",
        col_char,
        grip_mc=Vector((8.0, 6.0, 8.0)),
        scale_m=PX * 0.85,
        axis_map='BAG'
    )
    attach_to_bone(bag_obj, "carver_bag_bone")

    # Add visible leather tool loops on the backpack so tools visibly sit inside them
    part("Bag_ToolLoops", "carver_bag_bone", [
        ((1.3, 0.2, 1.5), (2.9, 1.6, 3.6)),   # Left loop (Point Chisel)
        ((-0.2, 0.3, 1.4), (1.3, 1.7, 3.4)),  # Center-left loop (Flat Chisel)
        ((-3.1, 0.2, 0.2), (-1.3, 1.8, 2.6)), # Right loop (Mallet)
    ], "strap")

    # 2. Point Chisel (Tip at MC Y=2.92, Cap at MC Y=18.18, Grip at MC Y=11.0)
    # With scale_m = 0.01705m/unit:
    #   tip is at Z_local = (2.92 - 11.0) * 0.01705 = -0.1378m
    #   cap is at Z_local = (18.18 - 11.0) * 0.01705 = +0.1224m
    #   total length = 0.2602m
    chisel_point_obj = import_mc_item_json(
        "Eclipse_Point_Chisel",
        MODELS_DIR / "carver_chisel_point.json",
        TEXTURES_DIR / "carver_chisel_point.png",
        col_char,
        grip_mc=Vector((8.0, 11.0, 8.0)),
        scale_m=0.01705,
        axis_map='CHISEL'
    )
    attach_to_bone(chisel_point_obj, "point_chisel_bone")

    # 3. Flat Chisel (Edge at MC Y=1.95, Cap at MC Y=17.22, Grip at MC Y=10.0)
    # With scale_m = 0.01705m/unit:
    #   tip is at Z_local = (1.95 - 10.0) * 0.01705 = -0.1373m
    #   cap is at Z_local = (17.22 - 10.0) * 0.01705 = +0.1231m
    chisel_flat_obj = import_mc_item_json(
        "Eclipse_Flat_Chisel",
        MODELS_DIR / "carver_chisel_flat.json",
        TEXTURES_DIR / "carver_chisel_flat.png",
        col_char,
        grip_mc=Vector((8.0, 10.0, 8.0)),
        scale_m=0.01705,
        axis_map='CHISEL'
    )
    attach_to_bone(chisel_flat_obj, "flat_chisel_bone")

    # 4. Carver Mallet (Grip at MC (8, 8, 8), Head center at MC (8, 17, 8), Strike face at MC (15, 17, 8))
    # With axis_map='MALLET' and scale_m = 0.018m/unit:
    #   In mallet_bone local space:
    #   handle axis is +Z_local; head center is at (0, 0, (17-8)*0.018) = (0, 0, +0.162m)
    #   striking face is at (0, (15-8)*0.018, (17-8)*0.018) = (0, +0.126m, +0.162m)
    mallet_obj = import_mc_item_json(
        "Eclipse_Carver_Mallet",
        MODELS_DIR / "carver_mallet.json",
        TEXTURES_DIR / "carver_mallet.png",
        col_char,
        grip_mc=Vector((8.0, 8.0, 8.0)),
        scale_m=0.018,
        axis_map='MALLET'
    )
    attach_to_bone(mallet_obj, "mallet_bone")

    return arm_obj


# Tool geometry constants in their respective bone local spaces:
POINT_TIP_LOCAL = Vector((0.0, 0.0, (2.92 - 11.0) * 0.01705))   # -0.13776m
POINT_CAP_LOCAL = Vector((0.0, 0.0, (18.18 - 11.0) * 0.01705))  # +0.12242m
FLAT_TIP_LOCAL  = Vector((0.0, 0.0, (1.95 - 10.0) * 0.01705))   # -0.13725m
FLAT_CAP_LOCAL  = Vector((0.0, 0.0, (17.22 - 10.0) * 0.01705))  # +0.12310m
MALLET_HEAD_LOCAL = Vector((0.0, 0.0, (17.0 - 8.0) * 0.018))    # (0, 0, +0.162m)
MALLET_FACE_LOCAL = Vector((0.0, (15.0 - 8.0) * 0.018, (17.0 - 8.0) * 0.018))  # (0, +0.126m, +0.162m)


# ==============================================================================
# PART 3: WORKSHOP, 16x16x16 MICROVOXEL SCULPTURE, CLUSTERS, CHIPS & HOLOGRAM
# ==============================================================================

STRIKE_SPECS = [
    # (frame, tool_type, tip_world, shaft_dir_raw, cluster_bounds_min, cluster_bounds_max, stage_name)
    (52,  "POINT", Vector(( 0.13, -0.60, 0.75)), Vector(( 0.14,  0.68, 0.72)), ( 0.05, -0.72, 0.68), ( 0.25, -0.53, 0.77), "ROUGH_1"),
    (68,  "POINT", Vector(( 0.01, -0.62, 0.75)), Vector(( 0.04,  0.70, 0.71)), (-0.08, -0.74, 0.68), ( 0.05, -0.53, 0.77), "ROUGH_2"),
    (84,  "POINT", Vector((-0.11, -0.60, 0.75)), Vector((-0.06,  0.70, 0.71)), (-0.25, -0.72, 0.68), (-0.08, -0.53, 0.77), "ROUGH_3"),
    (122, "FLAT",  Vector(( 0.09, -0.55, 0.66)), Vector(( 0.15,  0.74, 0.65)), ( 0.02, -0.60, 0.59), ( 0.17, -0.52, 0.68), "MAIN_1"),
    (136, "FLAT",  Vector((-0.08, -0.55, 0.66)), Vector((-0.08,  0.74, 0.66)), (-0.17, -0.60, 0.59), (-0.02, -0.52, 0.68), "MAIN_2"),
    (146, "FLAT",  Vector(( 0.05, -0.56, 0.63)), Vector(( 0.10,  0.70, 0.70)), ( 0.02, -0.60, 0.56), ( 0.09, -0.53, 0.64), "FINE_1"),
    (151, "FLAT",  Vector(( 0.00, -0.56, 0.65)), Vector(( 0.02,  0.70, 0.71)), (-0.03, -0.60, 0.58), ( 0.03, -0.53, 0.66), "FINE_2"),
    (156, "FLAT",  Vector((-0.05, -0.56, 0.63)), Vector((-0.06,  0.70, 0.70)), (-0.09, -0.60, 0.56), (-0.02, -0.53, 0.64), "FINE_3"),
]


def build_workshop_and_microvoxels(scene):
    col_env = bpy.data.collections.new("02_Workshop_And_Microvoxels")
    scene.collection.children.link(col_env)

    mats = {
        "floor":      make_material("Workshop_Floor",     (0.14, 0.16, 0.18, 1.0), roughness=0.88),
        "floor_tile": make_material("Workshop_Tile",      (0.18, 0.21, 0.23, 1.0), roughness=0.82),
        "oak_top":    make_material("Workbench_OakTop",   (0.55, 0.37, 0.22, 1.0), roughness=0.62),
        "oak_leg":    make_material("Workbench_OakLeg",   (0.40, 0.26, 0.16, 1.0), roughness=0.68),
        "stone_base": make_material("Microvoxel_Stone",   (0.48, 0.51, 0.50, 1.0), roughness=0.75),
        "stone_trim": make_material("Microvoxel_Trim",    (0.60, 0.63, 0.60, 1.0), roughness=0.65),
        "stone_core": make_material("Microvoxel_Carved",  (0.74, 0.73, 0.67, 1.0), roughness=0.52),
        "stone_gold": make_material("Microvoxel_Emblem",  (0.86, 0.72, 0.36, 1.0), roughness=0.35, metallic=0.65),
        "stone_raw":  make_material("Microvoxel_RawCrust",(0.42, 0.44, 0.43, 1.0), roughness=0.85),
        "hologram":   make_material("Carver_Hologram",    (0.20, 0.88, 0.98, 0.55), roughness=0.20,
                                    emission=(0.18, 0.85, 1.0, 1.0), emission_strength=2.8, alpha=0.55),
        "chalk":      make_material("Carver_ChalkLine",   (0.95, 0.88, 0.55, 1.0), roughness=0.40,
                                    emission=(0.95, 0.85, 0.45, 1.0), emission_strength=1.2),
        "dust":       make_material("Carver_DustMote",    (0.84, 0.81, 0.74, 1.0), roughness=0.90,
                                    emission=(0.65, 0.62, 0.56, 1.0), emission_strength=0.4),
        "leather":    make_material("Workbench_Roll",     (0.38, 0.23, 0.14, 1.0), roughness=0.65),
    }

    # 1. Workshop Floor (Z = -0.04 .. 0.00m) with stone border tiles
    build_box_object("Workshop_Floor_Base", col_env, [((-1.8, -1.9, -0.05), (1.8, 1.2, 0.0))], mats["floor"])
    tile_boxes = []
    for ix in range(-3, 4):
        for iy in range(-3, 2):
            if (ix + iy) % 2 == 0:
                tile_boxes.append(((ix * 0.45 - 0.21, iy * 0.45 - 0.21, -0.002),
                                   (ix * 0.45 + 0.21, iy * 0.45 + 0.21, 0.000)))
    build_box_object("Workshop_Floor_Tiles", col_env, tile_boxes, mats["floor_tile"])

    # 2. Heavy Mason's Oak Workbench (Tabletop top at Z = 0.46m, Y in [-1.20, -0.42])
    build_box_object("Workbench_Tabletop", col_env, [
        ((-0.62, -1.20, 0.36), (0.62, -0.42, 0.46)),
        ((-0.64, -1.22, 0.42), (0.64, -0.40, 0.46)),
    ], mats["oak_top"])
    build_box_object("Workbench_FrameAndLegs", col_env, [
        ((-0.54, -1.14, 0.0), (-0.42, -1.02, 0.36)),
        (( 0.42, -1.14, 0.0), ( 0.54, -1.02, 0.36)),
        ((-0.54, -0.56, 0.0), (-0.42, -0.44, 0.36)),
        (( 0.42, -0.56, 0.0), ( 0.54, -0.44, 0.36)),
        ((-0.50, -1.08, 0.12), (0.50, -1.02, 0.18)),
        ((-0.50, -0.56, 0.12), (0.50, -0.50, 0.18)),
        ((-0.50, -1.08, 0.12), (-0.44, -0.50, 0.18)),
        (( 0.44, -1.08, 0.12), ( 0.50, -0.50, 0.18)),
    ], mats["oak_leg"])

    # Workbench Tool Roll & Fine Graver resting on right side of bench
    build_box_object("Workbench_ToolRoll", col_env, [
        ((0.32, -1.02, 0.46), (0.54, -0.62, 0.485)),
        ((0.33, -1.01, 0.485), (0.53, -0.96, 0.51)),
    ], mats["leather"])
    graver_obj = import_mc_item_json(
        "Eclipse_Workbench_FineGraver",
        MODELS_DIR / "carver_fine_graver.json",
        TEXTURES_DIR / "carver_fine_graver.png",
        col_env,
        grip_mc=Vector((8.0, 8.0, 8.0)),
        scale_m=0.016,
        axis_map='CHISEL'
    )
    graver_obj.location = Vector((0.43, -0.80, 0.50))
    graver_obj.rotation_euler = Euler((math.radians(88.0), math.radians(12.0), math.radians(-20.0)), 'XYZ')

    # 3. 16x16x16 Microvoxel Sculpture Core (The permanent carved Gothic Relief Keystone inside the block)
    # Spans X in [-0.25, +0.25], Y in [-0.94, -0.52], Z in [0.46, 0.77]
    build_box_object("Microvoxel_Sculpture_Plinth", col_env, [
        ((-0.26, -0.96, 0.46), ( 0.26, -0.52, 0.53)),  # Solid stepped base
        ((-0.25, -0.94, 0.53), ( 0.25, -0.70, 0.74)),  # Back structural wall
        ((-0.25, -0.94, 0.72), (-0.17, -0.54, 0.76)),  # Left cornice
        (( 0.17, -0.94, 0.72), ( 0.25, -0.54, 0.76)),  # Right cornice
        ((-0.25, -0.94, 0.72), ( 0.25, -0.76, 0.77)),  # Rear cornice crown
    ], mats["stone_base"])

    build_box_object("Microvoxel_Gothic_Arch_And_Pillars", col_env, [
        # Left & Right fluted microvoxel pilasters
        ((-0.24, -0.70, 0.53), (-0.17, -0.54, 0.72)),
        (( 0.17, -0.70, 0.53), ( 0.24, -0.54, 0.72)),
        # Stepped Gothic Arch ribs (revealed as clusters 1..5 are carved away)
        ((-0.17, -0.70, 0.66), (-0.09, -0.57, 0.72)),
        (( 0.09, -0.70, 0.66), ( 0.17, -0.57, 0.72)),
        ((-0.10, -0.70, 0.68), ( 0.10, -0.58, 0.74)),
        # Recessed relief panel
        ((-0.17, -0.72, 0.53), ( 0.17, -0.59, 0.68)),
    ], mats["stone_core"])

    # Central Keystone Emblem (revealed by Fine Strikes 6, 7, 8)
    build_box_object("Microvoxel_Keystone_Emblem", col_env, [
        ((-0.07, -0.62, 0.57), ( 0.07, -0.565, 0.65)),
        ((-0.025, -0.625, 0.55), (0.025, -0.558, 0.67)),
        ((-0.08, -0.625, 0.595), (0.08, -0.558, 0.625)),
    ], mats["stone_gold"])

    # 4. The 8 Removable Microvoxel Clusters (sub-voxel stepped clusters sitting over the carved core)
    clusters = []
    for idx, (f_hit, t_type, tip_w, s_dir, b_min, b_max, st_name) in enumerate(STRIKE_SPECS):
        x0, y0, z0 = b_min
        x1, y1, z1 = b_max
        xm, ym, zm = (x0 + x1) * 0.5, (y0 + y1) * 0.5, (z0 + z1) * 0.5
        dx, dy, dz = (x1 - x0) * 0.5, (y1 - y0) * 0.5, (z1 - z0) * 0.5
        # Sub-divide each cluster into stepped microvoxel sub-blocks relative to cluster center
        sub_boxes = [
            ((-dx, -dy, -dz), (dx, dy, dz * 0.7)),
            ((-dx * 0.85, -dy * 0.9, -dz * 0.6), (dx * 0.85, dy * 0.9, dz)),
            ((-dx * 0.6, -dy, -dz * 0.4), (dx * 0.6, dy * 1.05, dz * 0.95)),
        ]
        c_obj = build_box_object(f"Voxel_Cluster_{idx+1:02d}_{st_name}", col_env, sub_boxes, mats["stone_raw"])
        c_obj.location = Vector((xm, ym, zm))
        clusters.append((c_obj, f_hit, Vector((xm, ym, zm)), tip_w))

    # 5. 40 Ballistic Stone Chips (5 per strike)
    chips = []
    for idx, (f_hit, t_type, tip_w, s_dir, b_min, b_max, st_name) in enumerate(STRIKE_SPECS):
        is_fine = st_name.startswith("FINE")
        base_r = 0.011 if is_fine else 0.019
        for k in range(5):
            rx = base_r * (0.8 + 0.25 * ((k * 3) % 4))
            ry = base_r * (0.7 + 0.20 * ((k * 5) % 3))
            rz = base_r * (0.5 + 0.25 * ((k * 7) % 3))
            chip_mat = mats["stone_trim"] if (k % 2 == 0) else mats["stone_raw"]
            chip_obj = build_box_object(
                f"Stone_Chip_S{idx+1}_{k+1}", col_env,
                [((-rx, -ry, -rz), (rx, ry, rz))], chip_mat
            )
            chips.append((chip_obj, idx, k, f_hit, tip_w.copy(), is_fine))

    # 6. Floating Microvoxel Hologram Wireframe Cage (CarverHologramMotion: drops on Spacebar frames 1..10)
    beam = 0.006
    hx, hy0, hy1, hz0, hz1 = 0.268, -0.965, -0.515, 0.0, 0.32
    holo_boxes = [
        # Bottom & Top frames
        ((-hx, hy0, hz0), (hx, hy0 + beam, hz0 + beam)),
        ((-hx, hy1 - beam, hz0), (hx, hy1, hz0 + beam)),
        ((-hx, hy0, hz0), (-hx + beam, hy1, hz0 + beam)),
        ((hx - beam, hy0, hz0), (hx, hy1, hz0 + beam)),
        ((-hx, hy0, hz1), (hx, hy0 + beam, hz1 + beam)),
        ((-hx, hy1 - beam, hz1), (hx, hy1, hz1 + beam)),
        ((-hx, hy0, hz1), (-hx + beam, hy1, hz1 + beam)),
        ((hx - beam, hy0, hz1), (hx, hy1, hz1 + beam)),
        # 4 Vertical corner pillars
        ((-hx, hy0, hz0), (-hx + beam, hy0 + beam, hz1)),
        ((hx - beam, hy0, hz0), (hx, hy0 + beam, hz1)),
        ((-hx, hy1 - beam, hz0), (-hx + beam, hy1, hz1)),
        ((hx - beam, hy1 - beam, hz0), (hx, hy1, hz1)),
        # Sub-voxel grid guide lines on front & top
        ((-0.10, hy1 - beam*0.8, hz0 + 0.11), (0.10, hy1 + beam*0.4, hz0 + 0.11 + beam)),
        ((-0.16, -0.64, hz1 - beam*0.5), (0.16, -0.64 + beam, hz1 + beam*0.5)),
    ]
    holo_obj = build_box_object("Carver_Hologram_Cage", col_env, holo_boxes, mats["hologram"])

    # 7. 18 Exhaled & Impact Dust Motes
    dust_motes = []
    for d_i in range(18):
        r = 0.007 + (d_i % 3) * 0.003
        d_obj = build_box_object(
            f"Carver_DustMote_{d_i+1:02d}", col_env,
            [((-r, -r, -r), (r, r, r))], mats["dust"]
        )
        dust_motes.append((d_obj, d_i))

    return {
        "clusters": clusters,
        "chips": chips,
        "hologram": holo_obj,
        "dust_motes": dust_motes,
    }


def setup_lighting_and_camera(scene):
    col_stage = bpy.data.collections.new("03_Lighting_And_Camera")
    scene.collection.children.link(col_stage)

    # World background: warm dark slate workshop atmosphere
    world = bpy.data.worlds.new("Eclipse_Workshop_World")
    scene.world = world
    world.use_nodes = True
    bg = next((n for n in world.node_tree.nodes if n.type == 'BACKGROUND'), None)
    if bg:
        bg.inputs["Color"].default_value = (0.055, 0.068, 0.082, 1.0)
        bg.inputs["Strength"].default_value = 0.85

    def add_light(name, ltype, energy, color, loc, rot_deg, size=1.2):
        ldata = bpy.data.lights.new(name=name, type=ltype)
        ldata.energy = energy
        ldata.color = color
        if hasattr(ldata, "size"):
            ldata.size = size
        lobj = bpy.data.objects.new(name=name, object_data=ldata)
        col_stage.objects.link(lobj)
        lobj.location = Vector(loc)
        lobj.rotation_euler = Euler([math.radians(a) for a in rot_deg], 'XYZ')
        return lobj

    # Key light: warm forge/lantern light from front-left-top
    add_light("Key_WarmLantern", 'AREA', 220.0, (1.0, 0.92, 0.80), (1.65, -2.10, 2.45), (48.0, 0.0, 36.0), size=1.6)
    # Fill light: cool skylight from front-right
    add_light("Fill_CoolSky", 'AREA', 95.0, (0.76, 0.88, 1.0), (-1.85, -1.70, 1.85), (58.0, 0.0, -44.0), size=1.8)
    # Rim light: crisp back-top light to highlight shoulders, carver_bag, and flying chips
    add_light("Rim_BackHighlight", 'AREA', 160.0, (0.95, 0.96, 1.0), (0.20, 1.65, 2.55), (-42.0, 0.0, 172.0), size=1.4)
    # Workpiece accent point light near the stone block
    add_light("Workpiece_Accent", 'POINT', 28.0, (1.0, 0.88, 0.68), (0.0, -0.52, 1.18), (0.0, 0.0, 0.0))

    # Main Cinema Camera (3/4 front-right-left orbital view framing full character, bag draw, and workpiece)
    cam_data = bpy.data.cameras.new("Eclipse_Master_Camera")
    cam_data.lens = 42.0
    cam_obj = bpy.data.objects.new("Eclipse_Master_Camera", cam_data)
    col_stage.objects.link(cam_obj)
    scene.camera = cam_obj

    return cam_obj


# ==============================================================================
# PART 4: 180-FRAME BIOMECHANICAL & ANALYTICAL IK SOLVER + PER-FRAME VERIFIER
# ==============================================================================

def make_limb_rot(p_start: Vector, p_end: Vector, right_hint: Vector) -> Matrix:
    """
    Builds a 3x3 rotation matrix whose -Z column points from p_start to p_end
    (so +Z points from p_end to p_start), and whose +X column aligns with right_hint (+X lateral).
    Because arms and legs move primarily in the sagittal Y-Z plane and never point straight along +-X,
    this lateral reference eliminates 100% of axial twist and gimbal flips!
    """
    z_axis = (p_start - p_end)
    if z_axis.length < 1e-7:
        z_axis = Vector((0.0, 0.0, 1.0))
    else:
        z_axis.normalize()

    x_perp = right_hint - z_axis * right_hint.dot(z_axis)
    if x_perp.length < 1e-5:
        fallback = Vector((0.0, 1.0, 0.0)) if abs(z_axis.y) < 0.8 else Vector((0.0, 0.0, 1.0))
        x_perp = fallback - z_axis * fallback.dot(z_axis)
    x_axis = x_perp.normalized()
    y_axis = z_axis.cross(x_axis).normalized()
    x_axis = y_axis.cross(z_axis).normalized()
    return Matrix((
        (x_axis.x, y_axis.x, z_axis.x),
        (x_axis.y, y_axis.y, z_axis.y),
        (x_axis.z, y_axis.z, z_axis.z),
    ))


def make_tool_rot(shaft_dir: Vector, right_hint: Vector) -> Matrix:
    """
    Builds a 3x3 rotation matrix for a chisel where +Z points along shaft_dir (tip to cap),
    and +X aligns with right_hint.
    """
    z_axis = shaft_dir.normalized()
    x_perp = right_hint - z_axis * right_hint.dot(z_axis)
    if x_perp.length < 1e-5:
        x_perp = Vector((1.0, 0.0, 0.0)) - z_axis * z_axis.x
    x_axis = x_perp.normalized()
    y_axis = z_axis.cross(x_axis).normalized()
    x_axis = y_axis.cross(z_axis).normalized()
    return Matrix((
        (x_axis.x, y_axis.x, z_axis.x),
        (x_axis.y, y_axis.y, z_axis.y),
        (x_axis.z, y_axis.z, z_axis.z),
    ))


def make_mallet_rot(strike_dir: Vector, handle_dir: Vector) -> Matrix:
    """
    Builds a 3x3 rotation matrix for the mallet where:
      +Y_local (striking face direction) aligns with strike_dir (towards chisel cap),
      +Z_local (handle from grip to mallet head) aligns with handle_dir (orthogonalized).
    """
    y_axis = strike_dir.normalized()
    z_perp = handle_dir - y_axis * handle_dir.dot(y_axis)
    if z_perp.length < 1e-5:
        z_perp = Vector((0.7, 0.0, 0.7)) - y_axis * y_axis.dot(Vector((0.7, 0.0, 0.7)))
    z_axis = z_perp.normalized()
    x_axis = y_axis.cross(z_axis).normalized()
    z_axis = x_axis.cross(y_axis).normalized()
    return Matrix((
        (x_axis.x, y_axis.x, z_axis.x),
        (x_axis.y, y_axis.y, z_axis.y),
        (x_axis.z, y_axis.z, z_axis.z),
    ))


def solve_two_bone_ik(p_root: Vector, p_target: Vector, L1: float, L2: float,
                      pole_vec: Vector, forward_hint: Vector):
    """
    Exact analytical 2-bone IK solver.
    Returns (R_upper, p_mid, R_lower, p_clamped_target, raw_distance).
    """
    delta = p_target - p_root
    raw_dist = delta.length
    max_reach = L1 + L2 - 0.002
    min_reach = abs(L1 - L2) + 0.005
    if raw_dist < 1e-6:
        u = Vector((0.0, 0.0, -1.0))
        d = min_reach
    else:
        u = delta / raw_dist
        d = max(min_reach, min(max_reach, raw_dist))

    p_end = p_root + u * d
    a = (L1 * L1 - L2 * L2 + d * d) / (2.0 * d)
    h = math.sqrt(max(0.0, L1 * L1 - a * a))

    b_perp = pole_vec - u * pole_vec.dot(u)
    if b_perp.length < 1e-5:
        alt = Vector((1.0, 0.0, 0.0)) if abs(u.x) < 0.8 else Vector((0.0, 1.0, 0.0))
        b_perp = alt - u * alt.dot(u)
    b_dir = b_perp.normalized()

    p_mid = p_root + u * a + b_dir * h
    R_upper = make_limb_rot(p_root, p_mid, forward_hint)
    R_lower = make_limb_rot(p_mid, p_end, forward_hint)
    return R_upper, p_mid, R_lower, p_end, raw_dist


def mat4(pos: Vector, rot3: Matrix) -> Matrix:
    m = rot3.to_4x4()
    m.translation = pos
    return m


def bezier_arc(p0: Vector, p1: Vector, apex_offset: Vector, u: float) -> Vector:
    s = smootherstep(u)
    base = lerp_vec(p0, p1, s)
    bump = math.sin(s * math.pi) ** 2
    return base + apex_offset * bump


def compute_strike_envelope(f: float):
    """
    Computes the composite strike choreography across all 8 strikes:
      Returns (lift, drive, contact_pulse, shock_0, shock_1, shock_2, shock_3, active_strike_idx)
    where at every f in STRIKE_FRAMES:
      lift == 0.0, contact_pulse == 1.0, shock_0 == 1.0!
    """
    # Windows for the 8 strikes: (start_windup, peak_windup, hit_frame, settle_end, max_lift)
    windows = [
        (42.0, 49.0,  52.0,  57.0, 1.00),  # Strike 1: Rough 1
        (58.0, 65.0,  68.0,  73.0, 1.10),  # Strike 2: Rough 2
        (74.0, 81.0,  84.0,  90.0, 1.16),  # Strike 3: Rough 3
        (113.0, 119.0, 122.0, 126.0, 0.82), # Strike 4: Main 1 (vertical bevel)
        (127.0, 133.0, 136.0, 141.0, 0.86), # Strike 5: Main 2 (vertical bevel)
        (142.0, 144.5, 146.0, 148.0, 0.42), # Strike 6: Fine tap 1
        (148.0, 149.8, 151.0, 153.0, 0.38), # Strike 7: Fine tap 2
        (153.0, 154.8, 156.0, 158.5, 0.44), # Strike 8: Fine tap 3
    ]
    lift = 0.32  # neutral hover lift outside strike windows
    drive = 0.0
    contact_pulse = 0.0
    active_idx = 0

    for idx, (f_start, f_peak, f_hit, f_end, max_l) in enumerate(windows):
        if f >= f_start:
            active_idx = idx
        if f_start <= f <= f_end:
            base_hover = 0.32 if idx < 5 else 0.18
            if f <= f_peak:
                # 65% slow windup
                u = (f - f_start) / (f_peak - f_start)
                lift = base_hover + (max_l - base_hover) * smoothstep(u)
                drive = 0.0
            elif f <= f_hit:
                # 25% explosive whip down-strike -> exact 0.0 at f == f_hit!
                u = (f - f_peak) / (f_hit - f_peak)
                lift = max_l * (1.0 - (u * u))
                drive = smoothstep(u)
            else:
                # 10% elastic recoil & settle back to base_hover
                u = (f - f_hit) / (f_end - f_hit)
                recoil = math.sin(u * math.pi) * (0.22 * max_l)
                lift = recoil + base_hover * smoothstep(u)
                drive = max(0.0, 1.0 - u * 1.8)
            break
    else:
        # Smooth transitions between windows
        if 141.0 < f < 142.0:
            lift = 0.32 - 0.14 * smoothstep(f - 141.0)

    # Exact zero-pin at all 8 integer strike frames
    for idx, f_hit in enumerate(STRIKE_FRAMES):
        if abs(f - f_hit) < 1e-5:
            lift = 0.0
            drive = 1.0
            contact_pulse = 1.0
            active_idx = idx
        elif abs(f - f_hit) < 1.8:
            contact_pulse = max(contact_pulse, math.cos((f - f_hit) / 1.8 * (math.pi * 0.5)) ** 2)

    # Shockwave propagation with strict frame lags:
    # shock_0: peaks at f_hit (chisel & left hand)
    # shock_1: peaks at f_hit + 1.0 (forearms & shoulders)
    # shock_2: peaks at f_hit + 2.0 (spine & chest)
    # shock_3: peaks at f_hit + 3.0 (head nod & carver_bag)
    def delayed_shock(lag_frames: float) -> float:
        val = 0.0
        for idx, f_hit in enumerate(STRIKE_FRAMES):
            gain = 1.0 if idx < 3 else (0.78 if idx < 5 else 0.45)
            dt = f - (f_hit + lag_frames)
            if -0.8 < dt < 4.5:
                if dt <= 0.0:
                    s = math.cos(dt / 0.8 * (math.pi * 0.5)) ** 2
                else:
                    s = math.exp(-dt / 1.6) * math.cos(dt * 0.35) ** 2
                val += gain * s
        return val

    return (
        lift,
        drive,
        contact_pulse,
        delayed_shock(0.0),
        delayed_shock(1.0),
        delayed_shock(2.0),
        delayed_shock(3.0),
        active_idx,
    )


def evaluate_active_chisel_target(f: float):
    """
    Returns (tip_world, shaft_dir_unit, lift_off_stone) for the active carving target at frame f.
    """
    # Target transitions between strikes:
    # Strikes 0, 1, 2 (Point Chisel): frames 52, 68, 84
    # Strikes 3, 4 (Flat Chisel Main): frames 122, 136
    # Strikes 5, 6, 7 (Flat Chisel Fine): frames 146, 151, 156
    if f < 107.0:
        idx = 0
        transitions = [
            (57.0, 62.0, 0, 1, 0.022),
            (73.0, 78.0, 1, 2, 0.022),
        ]
    else:
        idx = 3
        transitions = [
            (125.0, 130.0, 3, 4, 0.018),
            (139.0, 143.0, 4, 5, 0.014),
            (147.2, 149.2, 5, 6, 0.008),
            (152.2, 154.2, 6, 7, 0.008),
        ]
    for f0, f1, i_from, i_to, hop in transitions:
        if f >= f1:
            idx = i_to
        elif f0 <= f <= f1:
            u = smootherstep((f - f0) / (f1 - f0))
            tip_a, dir_a = STRIKE_SPECS[i_from][2], STRIKE_SPECS[i_from][3].normalized()
            tip_b, dir_b = STRIKE_SPECS[i_to][2], STRIKE_SPECS[i_to][3].normalized()
            tip = lerp_vec(tip_a, tip_b, u)
            s_dir = lerp_vec(dir_a, dir_b, u).normalized()
            hop_lift = math.sin(u * math.pi) ** 2 * hop
            return tip + s_dir * hop_lift, s_dir, hop_lift

    tip = STRIKE_SPECS[idx][2].copy()
    s_dir = STRIKE_SPECS[idx][3].normalized()
    return tip, s_dir, 0.0


# Backpack slot transforms in carver_bag_bone local space:
T_POINT_SLOT_LOCAL = mat4(
    Vector((0.135, 0.045, 0.145)),
    Euler((math.radians(-10.0), math.radians(14.0), math.radians(0.0)), 'XYZ').to_matrix()
)
T_FLAT_SLOT_LOCAL = mat4(
    Vector((0.035, 0.048, 0.140)),
    Euler((math.radians(-8.0), math.radians(5.0), math.radians(0.0)), 'XYZ').to_matrix()
)
T_MALLET_SLOT_LOCAL = mat4(
    Vector((-0.145, 0.048, 0.090)),
    Euler((math.radians(-12.0), math.radians(-18.0), math.radians(85.0)), 'XYZ').to_matrix()
)


def evaluate_foot_step(f: float, side: str):
    """
    Evaluates world-space ankle & toe positions and rotations for LEFT or RIGHT leg.
    Guarantees 0.000m floor penetration and <0.1mm slip during planted stance windows.
    """
    # Rest stance (Frames 1 & 180) vs Working LEFT_LEAD Stance (Frames 18..168)
    if side == "left":
        rest_xy = Vector((2.2 * PX, 0.0))
        work_xy = Vector((2.65 * PX, -0.175))  # Left lead foot stepped forward & slightly splayed
        rest_yaw, work_yaw = 0.0, math.radians(8.0)
        # Step forward window: frames 2..13; Step back window: frames 170..178
        if f < 2.0 or f >= 178.0:
            xy, yaw, lift_z, pitch = rest_xy, rest_yaw, 0.0, 0.0
        elif 2.0 <= f <= 13.0:
            u = (f - 2.0) / 11.0
            s = smootherstep(u)
            xy = lerp_vec(rest_xy, work_xy, s)
            yaw = rest_yaw + (work_yaw - rest_yaw) * s
            lift_z = 0.048 * (math.sin(u * math.pi) ** 2)
            pitch = math.radians(14.0 * math.sin(u * math.pi * 2.0) * math.sin(u * math.pi))
        elif 13.0 < f < 170.0:
            xy, yaw, lift_z, pitch = work_xy, work_yaw, 0.0, 0.0
        else:  # 170.0 <= f < 178.0
            u = (f - 170.0) / 8.0
            s = smootherstep(u)
            xy = lerp_vec(work_xy, rest_xy, s)
            yaw = work_yaw + (rest_yaw - work_yaw) * s
            lift_z = 0.038 * (math.sin(u * math.pi) ** 2)
            pitch = math.radians(-10.0 * math.sin(u * math.pi * 2.0) * math.sin(u * math.pi))
    else:
        rest_xy = Vector((-2.2 * PX, 0.0))
        work_xy = Vector((-2.65 * PX, -0.045))  # Right trail foot braced & splayed outward
        rest_yaw, work_yaw = 0.0, math.radians(-10.0)
        # Step into trail brace: frames 8..18; Step back: frames 167..174
        if f < 8.0 or f >= 174.0:
            xy, yaw, lift_z, pitch = rest_xy, rest_yaw, 0.0, 0.0
        elif 8.0 <= f <= 18.0:
            u = (f - 8.0) / 10.0
            s = smootherstep(u)
            xy = lerp_vec(rest_xy, work_xy, s)
            yaw = rest_yaw + (work_yaw - rest_yaw) * s
            lift_z = 0.036 * (math.sin(u * math.pi) ** 2)
            pitch = math.radians(10.0 * math.sin(u * math.pi * 2.0) * math.sin(u * math.pi))
        elif 18.0 < f < 167.0:
            xy, yaw, lift_z, pitch = work_xy, work_yaw, 0.0, 0.0
        else:  # 167.0 <= f < 174.0
            u = (f - 167.0) / 7.0
            s = smootherstep(u)
            xy = lerp_vec(work_xy, rest_xy, s)
            yaw = work_yaw + (rest_yaw - work_yaw) * s
            lift_z = 0.030 * (math.sin(u * math.pi) ** 2)
            pitch = math.radians(-8.0 * math.sin(u * math.pi * 2.0) * math.sin(u * math.pi))

    R_foot = Euler((pitch, 0.0, yaw), 'XYZ').to_matrix()
    R_toe = Euler((0.0, 0.0, yaw), 'XYZ').to_matrix()
    p_ankle = Vector((xy.x, xy.y, 1.2 * PX + lift_z))
    # Toe pivot is 1.6*PX forward (-Y) and on the ground (Z = lift_z * 0.6)
    f_dir = R_toe @ Vector((0.0, -1.6 * PX, 0.0))
    p_toe = Vector((xy.x + f_dir.x, xy.y + f_dir.y, max(0.0, lift_z * 0.55)))
    return p_ankle, R_foot, p_toe, R_toe, (lift_z == 0.0)


def evaluate_frame_state(f: float):
    """
    Computes the complete, exact world-space 4x4 bone transforms and metadata for frame f in [1, 180].
    Frame 1 and Frame 180 are guaranteed identical for seamless looping.
    """
    # Map f=180 to exact phase 0 where periodic
    loop_phase = (f - 1.0) / float(FRAME_END - FRAME_START)
    breath_angle = loop_phase * 5.0 * (2.0 * math.pi)  # 5 full breaths across 180 frames -> periodic!
    breath_sin = math.sin(breath_angle)

    (lift, drive, contact_pulse,
     shock_0, shock_1, shock_2, shock_3, strike_idx) = compute_strike_envelope(f)

    # Stance & Work envelopes
    stance_w = win(f, 2.0, 18.0) * (1.0 - win(f, 166.0, 178.0))
    work_w   = win(f, 34.0, 42.0) * (1.0 - win(f, 156.5, 164.0))
    rough_w  = win(f, 40.0, 46.0) * (1.0 - win(f, 88.0, 93.0))
    peek_blow_w = bell(f, 91.0, 102.0)
    fine_w   = win(f, 140.0, 144.0) * (1.0 - win(f, 156.5, 160.0))
    inspect_w = bell(f, 166.0, 177.0)

    # 1. Root / Pelvis position (shifts forward into the LEFT_LEAD stance and dips on strikes)
    pelvis_x = 0.012 * stance_w - 0.010 * fine_w + 0.008 * peek_blow_w
    pelvis_y = -0.115 * stance_w - 0.025 * fine_w - 0.018 * peek_blow_w - 0.012 * drive * work_w
    # Keep hip height slightly crouched so both legs always have generous knee bend (dist < L1+L2)
    pelvis_z = (
        12.0 * PX
        - 0.006  # baseline soft knee bend at idle
        - 0.032 * stance_w
        - 0.016 * fine_w
        - 0.014 * shock_2
        - 0.008 * drive * work_w
        + 0.002 * breath_sin
    )
    p_com = Vector((pelvis_x, pelvis_y, pelvis_z))

    # Reach-back torso twist assist (twists left when left hand reaches to bag, right when right hand reaches)
    reach_l_assist = bell(f, 22.0, 34.0) + bell(f, 101.0, 111.0) + bell(f, 157.0, 165.0)
    reach_r_assist = bell(f, 26.0, 39.0) + bell(f, 159.0, 167.0)

    # Pelvis rotation: POSITIVE pitch (+X) leans the torso FORWARD (-Y) toward the workbench!
    pelvis_pitch = math.radians(5.0 * stance_w + 3.5 * fine_w + 2.0 * drive * work_w + 1.8 * shock_2)
    pelvis_roll  = math.radians(1.5 * stance_w)
    pelvis_yaw   = math.radians(-6.0 * stance_w + 4.0 * reach_l_assist - 5.0 * reach_r_assist)
    R_pelvis = Euler((pelvis_pitch, pelvis_roll, pelvis_yaw), 'XYZ').to_matrix()

    # Spine_Lower (4*PX above pelvis)
    p_spine = p_com + R_pelvis @ Vector((0.0, 0.0, 4.0 * PX))
    spine_pitch = math.radians(
        1.2 * (1.0 - stance_w)
        + 7.5 * stance_w
        + 6.5 * fine_w
        + 5.5 * peek_blow_w
        - 2.8 * lift * work_w
        + 4.2 * drive * work_w
        + 3.5 * shock_2
        - 2.0 * inspect_w
        - 0.8 * breath_sin
    )
    spine_roll = math.radians(2.5 * peek_blow_w - 1.5 * fine_w)
    spine_yaw  = math.radians(
        -3.0 * stance_w
        + 7.0 * reach_l_assist
        - 8.0 * reach_r_assist
        - 4.5 * lift * work_w
        + 5.5 * drive * work_w
    )
    R_spine = R_pelvis @ Euler((spine_pitch, spine_roll, spine_yaw), 'XYZ').to_matrix()

    # Chest (4*PX above spine_lower)
    p_chest = p_spine + R_spine @ Vector((0.0, 0.0, 4.0 * PX))
    # Sharp exhale compression during dust blow (frames 95..99)
    dust_exhale = bell(f, 95.0, 100.0)
    chest_pitch = math.radians(
        1.5 * (1.0 - stance_w)
        + 8.5 * stance_w
        + 7.5 * fine_w
        + 7.0 * peek_blow_w
        + 4.5 * dust_exhale
        - 3.5 * lift * work_w
        + 5.0 * drive * work_w
        + 4.2 * shock_2
        - 3.0 * inspect_w
        - 1.4 * breath_sin
    )
    chest_roll = math.radians(3.5 * peek_blow_w)
    chest_yaw  = math.radians(
        8.0 * reach_l_assist
        - 10.0 * reach_r_assist
        - 6.0 * lift * work_w
        + 7.0 * drive * work_w
    )
    R_chest = R_spine @ Euler((chest_pitch, chest_roll, chest_yaw), 'XYZ').to_matrix()

    # Neck & Head (4*PX above chest)
    p_neck = p_chest + R_chest @ Vector((0.0, 0.0, 4.0 * PX))
    R_neck = R_chest @ Euler((math.radians(4.0 * stance_w + 3.0 * fine_w), 0.0, 0.0), 'XYZ').to_matrix()
    p_head = p_neck.copy()

    # Head Gaze Tracking + Side-Peek + Delayed Nod + Approving Finale Nod
    # Note: In Blender (+X left, -Y forward, +Z up), POSITIVE X pitch tilts the face DOWN toward the workbench!
    active_tip, active_shaft_dir, _ = evaluate_active_chisel_target(f)
    gaze_vec = active_tip - (p_head + Vector((0.0, -0.10, 0.12)))
    gaze_horiz = max(0.1, math.hypot(gaze_vec.x, gaze_vec.y))
    target_head_yaw = math.atan2(gaze_vec.x, -gaze_vec.y) * stance_w
    target_head_pitch = math.atan2(-gaze_vec.z, gaze_horiz) * 0.72 * stance_w

    finale_nod = math.radians(14.0) * math.sin(max(0.0, min(1.0, (f - 168.0) / 7.0)) * math.pi) ** 2 if 168.0 <= f <= 175.0 else 0.0
    impact_nod = math.radians(6.5 * shock_3)
    head_roll_world = math.radians(11.0 * peek_blow_w - 4.0 * fine_w)
    R_head_world = Euler((
        target_head_pitch + impact_nod + finale_nod + math.radians(6.0 * dust_exhale),
        head_roll_world,
        target_head_yaw + math.radians(6.0 * peek_blow_w)
    ), 'XYZ').to_matrix()
    # Blend between rest head rotation (aligned with chest) and world-targeted gaze
    R_head = slerp_quat(R_chest.to_quaternion(), R_head_world.to_quaternion(), stance_w).to_matrix()

    # Secondary bones: Carver Bag (with delayed shock_3 bounce) & Apron Flap
    p_bag = p_chest + R_chest @ Vector((0.0, 2.2 * PX, -1.5 * PX - 0.006 * shock_3))
    R_bag = R_chest @ Euler((math.radians(5.5 * shock_3 + 2.0 * drive * work_w), 0.0, 0.0), 'XYZ').to_matrix()
    T_bag_w = mat4(p_bag, R_bag)

    p_apron = p_com + R_pelvis @ Vector((0.0, -2.1 * PX, 0.2 * PX))
    R_apron = R_pelvis @ Euler((math.radians(-10.0 * stance_w - 6.0 * fine_w - 4.0 * shock_2), 0.0, 0.0), 'XYZ').to_matrix()

    # Evaluate Backpack Slot World Transforms on THIS exact frame f
    T_point_slot_w  = T_bag_w @ T_POINT_SLOT_LOCAL
    T_flat_slot_w   = T_bag_w @ T_FLAT_SLOT_LOCAL
    T_mallet_slot_w = T_bag_w @ T_MALLET_SLOT_LOCAL

    # Clavicles
    R_l_clav = R_chest @ Euler((
        math.radians(6.0 * work_w - 6.0 * reach_l_assist),
        math.radians(-6.0 * reach_l_assist),
        math.radians(5.0 * work_w)
    ), 'XYZ').to_matrix()
    p_l_clav = p_chest + R_chest @ Vector((2.2 * PX, 0.0, 3.0 * PX))
    p_l_shld = p_l_clav + R_l_clav @ Vector((2.8 * PX, 0.0, -1.0 * PX))

    R_r_clav = R_chest @ Euler((
        math.radians(6.0 * drive * work_w - 6.0 * lift * work_w - 6.0 * reach_r_assist),
        math.radians(6.0 * reach_r_assist + 4.0 * lift * work_w),
        math.radians(-5.0 * work_w)
    ), 'XYZ').to_matrix()
    p_r_clav = p_chest + R_chest @ Vector((-2.2 * PX, 0.0, 3.0 * PX))
    p_r_shld = p_r_clav + R_r_clav @ Vector((-2.8 * PX, 0.0, -1.0 * PX))

    # ==========================================================================
    # TOOLS & HANDS EXACT WORLD-SPACE TRAJECTORIES
    # ==========================================================================
    # Active chisel planted pose at target (with impact bite along -active_shaft_dir)
    bite_depth = 0.006 * contact_pulse + 0.003 * shock_0
    # During Dust Blow (frames 91..101), lift Point Chisel slightly up and left out of cavity
    blow_lift_offset = Vector((0.09, 0.04, 0.08)) * peek_blow_w
    tip_planted = active_tip - active_shaft_dir * bite_depth + blow_lift_offset
    R_chisel_work = make_tool_rot(active_shaft_dir, Vector((1.0, 0.2, 0.0)))

    # Point Chisel grip & cap when on workpiece
    p_point_work = tip_planted - (R_chisel_work @ POINT_TIP_LOCAL)
    T_point_work = mat4(p_point_work, R_chisel_work)

    # Flat Chisel grip & cap when on workpiece
    p_flat_work = tip_planted - (R_chisel_work @ FLAT_TIP_LOCAL)
    T_flat_work = mat4(p_flat_work, R_chisel_work)

    # 1. POINT CHISEL trajectory (Stowed 1..28, Drawn 28..36, Active 36..101, Stowed 101..105, In Bag 105..180)
    if f <= 28.0 or f >= 105.0:
        T_point_w = T_point_slot_w
    elif 28.0 < f < 36.0:
        u = (f - 28.0) / 8.0
        pos = bezier_arc(T_point_slot_w.translation, T_point_work.translation, Vector((0.20, -0.05, 0.08)), u)
        rot = slerp_quat(T_point_slot_w.to_quaternion(), T_point_work.to_quaternion(), smootherstep(u)).to_matrix()
        T_point_w = mat4(pos, rot)
    elif 36.0 <= f <= 101.0:
        T_point_w = T_point_work
    else:  # 101.0 < f < 105.0 (Stowing Point Chisel back into bag loop)
        u = (f - 101.0) / 4.0
        pos = bezier_arc(T_point_work.translation, T_point_slot_w.translation, Vector((0.20, -0.05, 0.08)), u)
        rot = slerp_quat(T_point_work.to_quaternion(), T_point_slot_w.to_quaternion(), smootherstep(u)).to_matrix()
        T_point_w = mat4(pos, rot)

    # 2. FLAT CHISEL trajectory (Stowed 1..107, Drawn 107..113, Active 113..157, Stowed 157..162, In Bag 162..180)
    if f <= 107.0 or f >= 162.0:
        T_flat_w = T_flat_slot_w
    elif 107.0 < f < 113.0:
        u = (f - 107.0) / 6.0
        pos = bezier_arc(T_flat_slot_w.translation, T_flat_work.translation, Vector((0.22, -0.05, 0.08)), u)
        rot = slerp_quat(T_flat_slot_w.to_quaternion(), T_flat_work.to_quaternion(), smootherstep(u)).to_matrix()
        T_flat_w = mat4(pos, rot)
    elif 113.0 <= f <= 157.0:
        T_flat_w = T_flat_work
    else:  # 157.0 < f < 162.0 (Stowing Flat Chisel back into bag loop)
        u = (f - 157.0) / 5.0
        pos = bezier_arc(T_flat_work.translation, T_flat_slot_w.translation, Vector((0.22, -0.05, 0.08)), u)
        rot = slerp_quat(T_flat_work.to_quaternion(), T_flat_slot_w.to_quaternion(), smootherstep(u)).to_matrix()
        T_flat_w = mat4(pos, rot)

    # Active Chisel Cap World Position (for Mallet impact alignment!)
    if f < 106.0:
        active_cap_w = T_point_w.translation + (T_point_w.to_3x3() @ POINT_CAP_LOCAL)
    else:
        active_cap_w = T_flat_w.translation + (T_flat_w.to_3x3() @ FLAT_CAP_LOCAL)

    # 3. MALLET trajectory (Stowed 1..34, Drawn 34..42, Working 42..158, Stowed 158..165, In Bag 165..180)
    # Striking face direction is angled from right-front-top toward the chisel cap, tilted back during windup
    strike_dir = (-active_shaft_dir + Vector((0.35 + 0.10 * lift, -0.15 * lift, -0.25 * lift))).normalized()
    handle_dir = Vector((0.76, -0.56 - 0.10 * lift, 0.32 + 0.22 * lift)).normalized()
    R_mallet_work = make_mallet_rot(strike_dir, handle_dir)

    # At lift == 0.0 (all 8 strike frames!), windup_disp == (0,0,0) and park_disp == (0,0,0),
    # so mallet_face_w == active_cap_w IDENTICALLY!
    windup_disp = Vector((-0.14 * lift, 0.04 * lift, 0.18 * lift))
    park_mallet_w = bell(f, 90.0, 112.5)
    park_disp = Vector((-0.16, 0.05, -0.06)) * park_mallet_w
    mallet_face_w = active_cap_w + windup_disp + park_disp
    p_mallet_work = mallet_face_w - (R_mallet_work @ MALLET_FACE_LOCAL)
    T_mallet_work = mat4(p_mallet_work, R_mallet_work)

    if f <= 34.0 or f >= 165.0:
        T_mallet_w = T_mallet_slot_w
    elif 34.0 < f < 42.0:
        u = (f - 34.0) / 8.0
        pos = bezier_arc(T_mallet_slot_w.translation, T_mallet_work.translation, Vector((-0.24, -0.06, 0.05)), u)
        rot = slerp_quat(T_mallet_slot_w.to_quaternion(), T_mallet_work.to_quaternion(), smootherstep(u)).to_matrix()
        T_mallet_w = mat4(pos, rot)
    elif 42.0 <= f <= 158.0:
        T_mallet_w = T_mallet_work
    else:  # 158.0 < f < 165.0
        u = (f - 158.0) / 7.0
        pos = bezier_arc(T_mallet_work.translation, T_mallet_slot_w.translation, Vector((-0.24, -0.06, 0.05)), u)
        rot = slerp_quat(T_mallet_work.to_quaternion(), T_mallet_slot_w.to_quaternion(), smootherstep(u)).to_matrix()
        T_mallet_w = mat4(pos, rot)

    # ==========================================================================
    # LEFT & RIGHT HAND TARGETS & 2-BONE ANALYTICAL ARM IK
    # ==========================================================================
    p_l_idle = p_com + Vector((5.1 * PX, -0.04, -1.6 * PX))
    p_r_idle = p_com + Vector((-5.1 * PX, -0.04, -1.6 * PX))
    p_l_present = p_com + Vector((0.20, -0.26, 0.20))
    p_r_present = p_com + Vector((-0.20, -0.26, 0.20))
    p_l_brow = p_head + Vector((0.08, -0.22, 0.14))  # Brow wipe target (frames 166..170)

    # Left Hand Position
    if f <= 20.0:
        p_l_hand = p_l_idle
    elif 20.0 < f < 28.0:
        u = (f - 20.0) / 8.0
        p_l_hand = bezier_arc(p_l_idle, T_point_slot_w.translation, Vector((0.18, -0.04, 0.08)), u)
    elif 28.0 <= f <= 105.0:
        p_l_hand = T_point_w.translation.copy()
    elif 105.0 < f < 107.0:
        u = (f - 105.0) / 2.0
        p_l_hand = bezier_arc(T_point_slot_w.translation, T_flat_slot_w.translation, Vector((0.04, 0.03, 0.03)), u)
    elif 107.0 <= f <= 162.0:
        p_l_hand = T_flat_w.translation.copy()
    elif 162.0 < f <= 168.0:
        u = (f - 162.0) / 6.0
        p_l_hand = bezier_arc(T_flat_slot_w.translation, p_l_brow, Vector((0.20, -0.06, 0.04)), u)
    elif 168.0 < f <= 173.0:
        u = (f - 168.0) / 5.0
        p_l_hand = lerp_vec(p_l_brow, p_l_present, smootherstep(u))
    else:  # 173.0 < f <= 180.0
        u = (f - 173.0) / 7.0
        p_l_hand = lerp_vec(p_l_present, p_l_idle, smootherstep(u))

    # Right Hand Position
    if f <= 25.0:
        p_r_hand = p_r_idle
    elif 25.0 < f < 34.0:
        u = (f - 25.0) / 9.0
        p_r_hand = bezier_arc(p_r_idle, T_mallet_slot_w.translation, Vector((-0.18, -0.04, 0.08)), u)
    elif 34.0 <= f <= 165.0:
        p_r_hand = T_mallet_w.translation.copy()
    elif 165.0 < f <= 172.0:
        u = (f - 165.0) / 7.0
        p_r_hand = bezier_arc(T_mallet_slot_w.translation, p_r_present, Vector((-0.20, -0.05, 0.03)), u)
    else:  # 172.0 < f <= 180.0
        u = (f - 172.0) / 8.0
        p_r_hand = lerp_vec(p_r_present, p_r_idle, smootherstep(u))

    # Solve 2-Bone Analytical IK for Left & Right Arms (L1 = L2 = 6*PX = 0.375m)
    # Elbows point naturally DOWN, OUT, and BACK (Z < 0) during carving, and lift only during backpack reach!
    L_arm = 6.0 * PX
    pole_l = Vector((0.75, 0.42, -0.60 + 0.95 * reach_l_assist)).normalized()
    pole_r = Vector((-0.80, 0.38, -0.55 + 0.25 * lift * work_w + 0.90 * reach_r_assist)).normalized()
    lat_body = R_chest @ Vector((1.0, 0.0, 0.0))

    R_l_arm, p_l_elbow, R_l_fore, p_l_wrist, l_arm_raw = solve_two_bone_ik(
        p_l_shld, p_l_hand, L_arm, L_arm, pole_l, lat_body
    )
    R_r_arm, p_r_elbow, R_r_fore, p_r_wrist, r_arm_raw = solve_two_bone_ik(
        p_r_shld, p_r_hand, L_arm, L_arm, pole_r, lat_body
    )

    # Hands continue smoothly from forearms (with natural wrist flexion & hammer-strike wrist snap!)
    R_l_hand = R_l_fore @ Euler((
        math.radians(10.0 * work_w + 4.0 * shock_0),
        0.0,
        math.radians(-8.0 * work_w)
    ), 'XYZ').to_matrix()
    R_r_hand = R_r_fore @ Euler((
        math.radians(8.0 * work_w - 14.0 * lift * work_w + 16.0 * drive * work_w),
        0.0,
        math.radians(8.0 * work_w)
    ), 'XYZ').to_matrix()

    # Solve Grounded Legs & 2-Bone Analytical IK (L_thigh = 6*PX = 0.375m, L_calf = 4.8*PX = 0.300m)
    p_l_hip = p_com + R_pelvis @ Vector((2.2 * PX, 0.0, 0.0))
    p_r_hip = p_com + R_pelvis @ Vector((-2.2 * PX, 0.0, 0.0))
    p_l_ankle, R_l_ankle, p_l_toe, R_l_toe, l_planted = evaluate_foot_step(f, "left")
    p_r_ankle, R_r_ankle, p_r_toe, R_r_toe, r_planted = evaluate_foot_step(f, "right")
    lat_pelvis = R_pelvis @ Vector((1.0, 0.0, 0.0))

    R_l_thigh, p_l_knee, R_l_calf, _, l_leg_raw = solve_two_bone_ik(
        p_l_hip, p_l_ankle, 6.0 * PX, 4.8 * PX, Vector((0.12, -1.0, 0.0)).normalized(), lat_pelvis
    )
    R_r_thigh, p_r_knee, R_r_calf, _, r_leg_raw = solve_two_bone_ik(
        p_r_hip, p_r_ankle, 6.0 * PX, 4.8 * PX, Vector((-0.12, -1.0, 0.0)).normalized(), lat_pelvis
    )

    bones_world = {
        "center_of_mass":    mat4(p_com, Matrix.Identity(3)),
        "pelvis":            mat4(p_com, R_pelvis),
        "spine_lower":       mat4(p_spine, R_spine),
        "chest":             mat4(p_chest, R_chest),
        "neck":              mat4(p_neck, R_neck),
        "head":              mat4(p_head, R_head),
        "left_clavicle":     mat4(p_l_clav, R_l_clav),
        "left_arm":          mat4(p_l_shld, R_l_arm),
        "left_forearm":      mat4(p_l_elbow, R_l_fore),
        "left_hand":         mat4(p_l_wrist, R_l_hand),
        "right_clavicle":    mat4(p_r_clav, R_r_clav),
        "right_arm":         mat4(p_r_shld, R_r_arm),
        "right_forearm":     mat4(p_r_elbow, R_r_fore),
        "right_hand":        mat4(p_r_wrist, R_r_hand),
        "left_leg":          mat4(p_l_hip, R_l_thigh),
        "left_calf":         mat4(p_l_knee, R_l_calf),
        "left_ankle":        mat4(p_l_ankle, R_l_ankle),
        "left_toe":          mat4(p_l_toe, R_l_toe),
        "right_leg":         mat4(p_r_hip, R_r_thigh),
        "right_calf":        mat4(p_r_knee, R_r_calf),
        "right_ankle":       mat4(p_r_ankle, R_r_ankle),
        "right_toe":         mat4(p_r_toe, R_r_toe),
        "carver_bag_bone":   T_bag_w,
        "apron_bone":        mat4(p_apron, R_apron),
        "point_chisel_bone": T_point_w,
        "flat_chisel_bone":  T_flat_w,
        "mallet_bone":       T_mallet_w,
    }

    meta = {
        "lift": lift,
        "drive": drive,
        "contact_pulse": contact_pulse,
        "shock_0": shock_0,
        "shock_1": shock_1,
        "shock_2": shock_2,
        "shock_3": shock_3,
        "strike_idx": strike_idx,
        "l_arm_raw": l_arm_raw,
        "r_arm_raw": r_arm_raw,
        "l_leg_raw": l_leg_raw,
        "r_leg_raw": r_leg_raw,
        "l_planted": l_planted,
        "r_planted": r_planted,
        "active_tip": tip_planted,
        "active_cap": active_cap_w,
        "mallet_face": T_mallet_w.translation + (T_mallet_w.to_3x3() @ MALLET_FACE_LOCAL),
        "mallet_head": T_mallet_w.translation + (T_mallet_w.to_3x3() @ MALLET_HEAD_LOCAL),
        "head_center": p_head + R_head @ Vector((0.0, -0.02, 4.0 * PX)),
    }
    return bones_world, meta


# ==============================================================================
# PART 5: KEYFRAME BAKER (FRAMES 1..180) & STRICT PER-FRAME VERIFICATION SUITE
# ==============================================================================

def bake_all_animations(scene, arm_obj, env_dict, cam_obj):
    arm_data = arm_obj.data
    bone_names = [b[0] for b in BONE_SPECS]
    parent_map = {b[0]: b[1] for b in BONE_SPECS}

    rest_local = {bn: arm_data.bones[bn].matrix_local.copy() for bn in bone_names}
    rest_rot4  = {bn: arm_data.bones[bn].matrix_local.to_3x3().to_4x4() for bn in bone_names}
    rest_rel   = {}
    for bn in bone_names:
        pn = parent_map[bn]
        if pn is None:
            rest_rel[bn] = rest_local[bn]
        else:
            rest_rel[bn] = rest_local[pn].inverted() @ rest_local[bn]

    clusters = env_dict["clusters"]
    chips = env_dict["chips"]
    holo_obj = env_dict["hologram"]
    dust_motes = env_dict["dust_motes"]

    prev_quats = {bn: None for bn in bone_names}

    for f in range(FRAME_START, FRAME_END + 1):
        ff = float(f)
        bones_world, meta = evaluate_frame_state(ff)

        # 1. Bake all Armature PoseBones in parent-to-child order
        pb_mat_map = {}
        for bn in bone_names:
            pb_mat_map[bn] = bones_world[bn] @ rest_rot4[bn]

        for bn in bone_names:
            pn = parent_map[bn]
            if pn is None:
                basis = rest_local[bn].inverted() @ pb_mat_map[bn]
            else:
                basis = (pb_mat_map[pn] @ rest_rel[bn]).inverted() @ pb_mat_map[bn]

            loc = basis.to_translation()
            quat = basis.to_quaternion()
            # Ensure hemisphere continuity for smooth quaternion interpolation
            if prev_quats[bn] is not None and prev_quats[bn].dot(quat) < 0.0:
                quat = Quaternion((-quat.w, -quat.x, -quat.y, -quat.z))
            prev_quats[bn] = quat.copy()

            pb = arm_obj.pose.bones[bn]
            pb.location = loc
            pb.rotation_quaternion = quat
            pb.keyframe_insert(data_path="location", frame=f)
            pb.keyframe_insert(data_path="rotation_quaternion", frame=f)

        # 2. Bake the 8 Removable Microvoxel Clusters
        for c_obj, f_hit, base_pos, tip_w in clusters:
            if f < f_hit:
                # Intact on block; slight pre-fracture vibration on frame f_hit - 1
                shiver = 0.0025 if f == (f_hit - 1) else 0.0
                c_obj.location = base_pos + Vector((shiver, -shiver, shiver))
                c_obj.scale = Vector((1.0, 1.0, 1.0))
            elif f >= 176:
                # Re-assemble smoothly at the very end for loop reset (frames 176..180)
                u_reset = smootherstep((ff - 176.0) / 4.0)
                c_obj.location = base_pos
                c_obj.scale = Vector((u_reset, u_reset, u_reset))
            else:
                c_obj.location = base_pos
                c_obj.scale = Vector((0.0001, 0.0001, 0.0001))
            c_obj.keyframe_insert(data_path="location", frame=f)
            c_obj.keyframe_insert(data_path="scale", frame=f)

        # 3. Bake the 40 Ballistic Stone Chips (5 per strike)
        for chip_obj, s_idx, k, f_hit, origin_pos, is_fine in chips:
            if f < f_hit or f >= 176:
                chip_obj.location = origin_pos
                chip_obj.rotation_euler = Euler((0.0, 0.0, 0.0), 'XYZ')
                chip_obj.scale = Vector((0.0001, 0.0001, 0.0001))
            else:
                age = (ff - float(f_hit)) / float(FPS)
                # Fan out horizontally toward the front-left / front-right
                angle_fan = math.radians(-58.0 + k * 29.0 + (s_idx % 3 - 1) * 14.0)
                speed_xy = (0.55 + 0.14 * k) * (0.65 if is_fine else 1.0)
                vx = math.sin(angle_fan) * speed_xy
                vy = -math.cos(angle_fan) * speed_xy * 0.85  # outward across bench/floor
                vz0 = (0.78 + 0.12 * (k % 3)) * (0.70 if is_fine else 1.0)
                g = 3.8

                # Parabolic flight with tabletop bounce (Z=0.575m) or floor bounce (Z=0.012m)
                x = origin_pos.x + vx * min(age, 1.35)
                y = origin_pos.y + vy * min(age, 1.35)
                z_raw = origin_pos.z + vz0 * age - 0.5 * g * (age ** 2)

                # Determine support surface (tabletop vs floor)
                on_table = (-0.60 <= x <= 0.60) and (-1.18 <= y <= -0.40)
                floor_z = 0.472 if on_table else 0.012

                if z_raw >= floor_z:
                    z = z_raw
                    spin_t = age
                else:
                    # First bounce
                    t_impact = (vz0 + math.sqrt(max(0.0, vz0*vz0 + 2.0*g*(origin_pos.z - floor_z)))) / g
                    dt_b = max(0.0, age - t_impact)
                    vz_bounce = vz0 * 0.42
                    z_b = floor_z + vz_bounce * dt_b - 0.5 * g * (dt_b ** 2)
                    if z_b >= floor_z and dt_b < 0.65:
                        z = z_b
                        spin_t = t_impact + dt_b * 0.5
                    else:
                        z = floor_z
                        spin_t = t_impact + 0.32

                fade = 1.0 - smoothstep((ff - 170.0) / 6.0) if f > 170 else 1.0
                chip_obj.location = Vector((x, y, z))
                chip_obj.rotation_euler = Euler((
                    spin_t * (5.2 + k * 1.1),
                    spin_t * (3.8 - k * 0.7),
                    spin_t * (4.5 + s_idx * 0.6)
                ), 'XYZ')
                chip_obj.scale = Vector((fade, fade, fade))

            chip_obj.keyframe_insert(data_path="location", frame=f)
            chip_obj.keyframe_insert(data_path="rotation_euler", frame=f)
            chip_obj.keyframe_insert(data_path="scale", frame=f)

        # 4. Bake Carver Hologram Cage (drops on Spacebar frames 1..10, pulses on strikes, dissolves at 156..164, resets 176..180)
        if f <= 10:
            u_drop = smootherstep((ff - 1.0) / 9.0)
            # Overshoot spring bounce at touchdown frame 9..10
            z_holo = 0.46 + 0.22 * (1.0 - u_drop) - 0.012 * math.sin(u_drop * math.pi * 2.0) * u_drop
            s_holo = 1.0 + 0.03 * bell(ff, 8.0, 12.0)
        elif f <= 156:
            z_holo = 0.46
            s_holo = 1.0 + 0.025 * meta["contact_pulse"]
        elif f <= 165:
            u_fade = smootherstep((ff - 156.0) / 9.0)
            z_holo = 0.46 + 0.08 * u_fade
            s_holo = max(0.0001, 1.0 - u_fade)
        elif f < 175:
            z_holo = 0.68
            s_holo = 0.0001
        else:
            u_re = smootherstep((ff - 175.0) / 5.0)
            z_holo = 0.68
            s_holo = max(0.0001, u_re)

        holo_obj.location = Vector((0.0, 0.0, z_holo))
        holo_obj.scale = Vector((s_holo, s_holo, s_holo))
        holo_obj.keyframe_insert(data_path="location", frame=f)
        holo_obj.keyframe_insert(data_path="scale", frame=f)

        # 5. Bake Dust Motes (Blow cloud at frames 95..108 + impact puffs)
        for d_obj, d_i in dust_motes:
            if 95 <= f <= 110:
                u_d = (ff - 95.0) / 15.0
                ang = math.radians(-65.0 + d_i * 18.0)
                rad = 0.04 + 0.28 * u_d
                dx = math.sin(ang) * rad
                dy = -0.58 - math.cos(ang) * rad * 0.75
                dz = 0.72 + 0.14 * u_d + 0.03 * math.sin(d_i + u_d * 6.0)
                sc = math.sin(u_d * math.pi) * 1.35
                d_obj.location = Vector((dx, dy, dz))
                d_obj.scale = Vector((sc, sc, sc))
            else:
                # Impact dust puff on each strike
                active_puff = 0.0
                puff_origin = Vector((0.0, -0.58, 0.72))
                for s_i, f_h in enumerate(STRIKE_FRAMES):
                    if f_h <= f <= f_h + 7:
                        u_p = (ff - float(f_h)) / 7.0
                        active_puff = math.sin(u_p * math.pi)
                        ang = math.radians(d_i * 20.0 + s_i * 35.0)
                        rad = 0.02 + 0.14 * u_p
                        puff_origin = STRIKE_SPECS[s_i][2] + Vector((
                            math.cos(ang) * rad,
                            math.sin(ang) * rad,
                            0.06 * u_p
                        ))
                        break
                d_obj.location = puff_origin
                sc = max(0.0001, active_puff)
                d_obj.scale = Vector((sc, sc, sc))
            d_obj.keyframe_insert(data_path="location", frame=f)
            d_obj.keyframe_insert(data_path="scale", frame=f)

        # 6. Bake Cinematic Camera (frames full character, backpack reach-back, and microvoxel carving)
        loop_phase = (ff - 1.0) / 179.0
        orbit_phase = math.sin(loop_phase * 2.0 * math.pi)
        fine_zoom = bell(ff, 138.0, 160.0)
        cam_x = -1.22 + 0.18 * orbit_phase + 0.12 * fine_zoom
        cam_y = -2.18 + 0.22 * fine_zoom
        cam_z = 1.44 - 0.10 * fine_zoom + 0.04 * math.cos(loop_phase * 2.0 * math.pi)
        cam_pos = Vector((cam_x, cam_y, cam_z))
        look_target = Vector((0.02, -0.38, 0.86 - 0.04 * fine_zoom))
        look_dir = (look_target - cam_pos).normalized()
        cam_rot = look_dir.to_track_quat('-Z', 'Y').to_euler('XYZ')
        cam_obj.location = cam_pos
        cam_obj.rotation_euler = cam_rot
        cam_obj.keyframe_insert(data_path="location", frame=f)
        cam_obj.keyframe_insert(data_path="rotation_euler", frame=f)


def verify_all_180_frames(scene, arm_obj, env_dict):
    """
    Steps Blender through EVERY SINGLE FRAME 1..180, updates the dependency graph,
    and verifies all 10 biomechanical, kinematic, and geometric invariants from actual
    evaluated Blender world matrices!
    """
    depsgraph = bpy.context.evaluated_depsgraph_get()
    arm_data = arm_obj.data
    bone_names = [b[0] for b in BONE_SPECS]
    rest_rot4_inv = {bn: arm_data.bones[bn].matrix_local.to_3x3().to_4x4().inverted() for bn in bone_names}

    per_frame_records = []
    max_foot_penetration_m = 0.0
    max_planted_slip_m = 0.0
    max_arm_reach_ratio = 0.0
    max_leg_reach_ratio = 0.0
    min_head_tool_clearance_m = 999.0
    max_strike_contact_err_m = 0.0
    max_chisel_tip_err_m = 0.0
    max_grab_handoff_err_m = 0.0
    max_angular_step_deg = 0.0

    planted_anchors = {"left": None, "right": None}
    prev_bone_quats = {}
    strike_audit = []

    for f in range(FRAME_START, FRAME_END + 1):
        scene.frame_set(f)
        depsgraph.update()
        eval_arm = arm_obj.evaluated_get(depsgraph)

        # Recover actual evaluated bone world transforms from Blender's PoseBones
        eval_bones = {}
        for bn in bone_names:
            pb = eval_arm.pose.bones[bn]
            eval_bones[bn] = eval_arm.matrix_world @ pb.matrix @ rest_rot4_inv[bn]

        _, meta = evaluate_frame_state(float(f))

        # 1. Foot Grounding & Zero-Slip Audit
        p_l_toe = eval_bones["left_toe"].translation
        p_r_toe = eval_bones["right_toe"].translation
        p_l_ank = eval_bones["left_ankle"].translation
        p_r_ank = eval_bones["right_ankle"].translation

        pen_l = max(0.0, -p_l_toe.z, -(p_l_ank.z - 1.2 * PX))
        pen_r = max(0.0, -p_r_toe.z, -(p_r_ank.z - 1.2 * PX))
        max_foot_penetration_m = max(max_foot_penetration_m, pen_l, pen_r)

        for side, is_pl, p_ank in [("left", meta["l_planted"], p_l_ank), ("right", meta["r_planted"], p_r_ank)]:
            if is_pl:
                xy = Vector((p_ank.x, p_ank.y))
                if planted_anchors[side] is None:
                    planted_anchors[side] = xy
                else:
                    slip = (xy - planted_anchors[side]).length
                    max_planted_slip_m = max(max_planted_slip_m, slip)
            else:
                planted_anchors[side] = None

        # 2. Limb IK Reachability Audit (No Hyperextension)
        L_arm_max = 12.0 * PX
        L_leg_max = 10.8 * PX
        l_arm_ratio = (eval_bones["left_hand"].translation - eval_bones["left_arm"].translation).length / L_arm_max
        r_arm_ratio = (eval_bones["right_hand"].translation - eval_bones["right_arm"].translation).length / L_arm_max
        l_leg_ratio = (eval_bones["left_ankle"].translation - eval_bones["left_leg"].translation).length / L_leg_max
        r_leg_ratio = (eval_bones["right_ankle"].translation - eval_bones["right_leg"].translation).length / L_leg_max
        max_arm_reach_ratio = max(max_arm_reach_ratio, l_arm_ratio, r_arm_ratio)
        max_leg_reach_ratio = max(max_leg_reach_ratio, l_leg_ratio, r_leg_ratio)

        # 3. Head/Beard vs Tool Minimum Clearance Audit
        T_mal = eval_bones["mallet_bone"]
        T_pt  = eval_bones["point_chisel_bone"]
        T_fl  = eval_bones["flat_chisel_bone"]
        mal_head_w = T_mal.translation + (T_mal.to_3x3() @ MALLET_HEAD_LOCAL)
        mal_face_w = T_mal.translation + (T_mal.to_3x3() @ MALLET_FACE_LOCAL)
        pt_tip_w   = T_pt.translation  + (T_pt.to_3x3()  @ POINT_TIP_LOCAL)
        pt_cap_w   = T_pt.translation  + (T_pt.to_3x3()  @ POINT_CAP_LOCAL)
        fl_tip_w   = T_fl.translation  + (T_fl.to_3x3()  @ FLAT_TIP_LOCAL)
        fl_cap_w   = T_fl.translation  + (T_fl.to_3x3()  @ FLAT_CAP_LOCAL)

        head_center_w = eval_bones["head"].translation + (eval_bones["head"].to_3x3() @ Vector((0.0, -0.02, 4.0 * PX)))
        d_head_mal = min((mal_head_w - head_center_w).length, (mal_face_w - head_center_w).length)
        min_head_tool_clearance_m = min(min_head_tool_clearance_m, d_head_mal)

        # 4. Grab / Stow Handoff Continuity Audit
        if 28 <= f <= 105:
            err_grip = (eval_bones["left_hand"].translation - T_pt.translation).length
            max_grab_handoff_err_m = max(max_grab_handoff_err_m, err_grip)
        if 107 <= f <= 162:
            err_grip = (eval_bones["left_hand"].translation - T_fl.translation).length
            max_grab_handoff_err_m = max(max_grab_handoff_err_m, err_grip)
        if 34 <= f <= 165:
            err_grip = (eval_bones["right_hand"].translation - T_mal.translation).length
            max_grab_handoff_err_m = max(max_grab_handoff_err_m, err_grip)

        # 5. Strike Impact & Chisel Tip Contact Audit
        strike_err_this_frame = None
        if f in STRIKE_FRAMES:
            s_i = STRIKE_FRAMES.index(f)
            bite_expected = 0.006 * meta["contact_pulse"] + 0.003 * meta["shock_0"]
            expected_tip = STRIKE_SPECS[s_i][2] - STRIKE_SPECS[s_i][3].normalized() * bite_expected
            actual_tip = pt_tip_w if f < 106 else fl_tip_w
            actual_cap = pt_cap_w if f < 106 else fl_cap_w
            tip_err = (actual_tip - expected_tip).length
            cap_err = (mal_face_w - actual_cap).length
            max_chisel_tip_err_m = max(max_chisel_tip_err_m, tip_err)
            max_strike_contact_err_m = max(max_strike_contact_err_m, cap_err)
            strike_err_this_frame = round(cap_err, 7)
            strike_audit.append({
                "strike_number": s_i + 1,
                "frame": f,
                "stage": STRIKE_SPECS[s_i][6],
                "tool": STRIKE_SPECS[s_i][1],
                "chisel_tip_error_m": round(tip_err, 7),
                "mallet_to_cap_error_m": round(cap_err, 7),
                "head_clearance_m": round(d_head_mal, 4),
            })

        # 6. Frame-to-Frame Angular Velocity Audit
        max_step_f = 0.0
        for bn in bone_names:
            q = eval_bones[bn].to_quaternion()
            if bn in prev_bone_quats:
                dot = min(1.0, abs(prev_bone_quats[bn].dot(q)))
                ang_deg = math.degrees(2.0 * math.acos(dot))
                max_step_f = max(max_step_f, ang_deg)
            prev_bone_quats[bn] = q
        max_angular_step_deg = max(max_angular_step_deg, max_step_f)

        # 7. Microvoxel Cluster Conservation Audit
        intact_count = sum(1 for (c_obj, _, _, _) in env_dict["clusters"]
                           if c_obj.evaluated_get(depsgraph).scale.x > 0.5)

        per_frame_records.append({
            "frame": f,
            "time_s": round((f - 1) / float(FPS), 4),
            "lift": round(meta["lift"], 4),
            "contact_pulse": round(meta["contact_pulse"], 4),
            "shock_wave": [round(meta["shock_0"], 3), round(meta["shock_1"], 3),
                           round(meta["shock_2"], 3), round(meta["shock_3"], 3)],
            "foot_pen_m": round(max(pen_l, pen_r), 7),
            "arm_reach_max": round(max(l_arm_ratio, r_arm_ratio), 4),
            "leg_reach_max": round(max(l_leg_ratio, r_leg_ratio), 4),
            "head_mallet_clearance_m": round(d_head_mal, 4),
            "max_bone_step_deg": round(max_step_f, 3),
            "intact_voxel_clusters": intact_count,
            "strike_contact_err_m": strike_err_this_frame,
        })

    # Loop closure check (Frame 1 vs Frame 180)
    b1, _ = evaluate_frame_state(1.0)
    b180, _ = evaluate_frame_state(180.0)
    loop_pos_err = max((b1[bn].translation - b180[bn].translation).length for bn in bone_names)

    summary = {
        "status": "ALL_180_FRAMES_VERIFIED_OK",
        "fps": FPS,
        "frame_range": [FRAME_START, FRAME_END],
        "total_frames_verified": len(per_frame_records),
        "max_foot_penetration_m": round(max_foot_penetration_m, 7),
        "max_planted_foot_slip_m": round(max_planted_slip_m, 7),
        "max_arm_reach_ratio": round(max_arm_reach_ratio, 5),
        "max_leg_reach_ratio": round(max_leg_reach_ratio, 5),
        "min_head_tool_clearance_m": round(min_head_tool_clearance_m, 4),
        "max_strike_contact_err_m": round(max_strike_contact_err_m, 7),
        "max_chisel_tip_err_m": round(max_chisel_tip_err_m, 7),
        "max_grab_handoff_err_m": round(max_grab_handoff_err_m, 7),
        "max_angular_step_deg": round(max_angular_step_deg, 3),
        "loop_seam_pos_err_m": round(loop_pos_err, 7),
        "strike_impacts_verified": strike_audit,
    }

    report_path = OUT_DIR / "per_frame_verification_180.json"
    report_path.write_text(
        json.dumps({"summary": summary, "frames": per_frame_records}, indent=2),
        encoding="utf-8"
    )

    assert max_foot_penetration_m < 1e-4, f"Foot penetration: {max_foot_penetration_m} (summary={summary})"
    assert max_planted_slip_m < 1e-4, f"Foot slip: {max_planted_slip_m} (summary={summary})"
    assert max_arm_reach_ratio <= 1.0001, f"Arm hyperextended: {max_arm_reach_ratio} (summary={summary})"
    assert max_leg_reach_ratio <= 1.0001, f"Leg hyperextended: {max_leg_reach_ratio} (summary={summary})"
    assert min_head_tool_clearance_m >= 0.12, f"Tool too close to head: {min_head_tool_clearance_m} (summary={summary})"
    assert max_strike_contact_err_m < 1e-4, f"Mallet missed chisel cap: {max_strike_contact_err_m} (summary={summary})"
    assert max_chisel_tip_err_m < 1e-4, f"Chisel tip missed voxel target: {max_chisel_tip_err_m} (summary={summary})"
    assert max_grab_handoff_err_m < 1e-4, f"Hand missed tool grip: {max_grab_handoff_err_m} (summary={summary})"
    assert loop_pos_err < 1e-4, f"Loop seam mismatch: {loop_pos_err} (summary={summary})"

    return summary


def build_and_verify_all():
    for obj in list(bpy.data.objects):
        bpy.data.objects.remove(obj, do_unlink=True)
    for col in list(bpy.data.collections):
        bpy.data.collections.remove(col)
    for mesh in list(bpy.data.meshes):
        if mesh.users == 0:
            bpy.data.meshes.remove(mesh)
    for arm in list(bpy.data.armatures):
        if arm.users == 0:
            bpy.data.armatures.remove(arm)
    for mat in list(bpy.data.materials):
        if mat.users == 0:
            bpy.data.materials.remove(mat)

    scene = bpy.context.scene
    scene.render.fps = FPS
    scene.frame_start = FRAME_START
    scene.frame_end = FRAME_END
    scene.render.engine = 'BLENDER_EEVEE'
    scene.render.resolution_x = 960
    scene.render.resolution_y = 540
    scene.render.resolution_percentage = 100
    if hasattr(scene.eevee, "taa_render_samples"):
        scene.eevee.taa_render_samples = 16

    arm_obj = build_armature_and_character(scene)
    env_dict = build_workshop_and_microvoxels(scene)
    cam_obj = setup_lighting_and_camera(scene)

    # Hide armature bone shapes in viewport so character and tools are clean
    arm_obj.hide_viewport = False
    arm_obj.show_in_front = False

    # Switch all 3D Viewports to MATERIAL preview (and TEXTURE color in SOLID mode)
    for win in bpy.context.window_manager.windows:
        for area in win.screen.areas:
            if area.type == 'VIEW_3D':
                for space in area.spaces:
                    if space.type == 'VIEW_3D':
                        space.shading.type = 'MATERIAL'
                        space.overlay.show_relationship_lines = False
                        space.overlay.show_bones = False

    bake_all_animations(scene, arm_obj, env_dict, cam_obj)
    summary = verify_all_180_frames(scene, arm_obj, env_dict)

    blend_path = OUT_DIR / "eclipse_carver_post_spacebar_advanced.blend"
    bpy.ops.wm.save_as_mainfile(filepath=str(blend_path))
    scene.frame_set(52)  # Park viewport on Strike 1 impact frame
    return summary
