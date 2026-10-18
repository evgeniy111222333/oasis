"""
Master Carver Animation in Blender 5.2+
Implements a completely new, top-tier artisan carving animation in Blender using exclusively:
- ancient_peasant_hammer_fixed.bbmodel
- carver_bag_empty (1).bbmodel
- carver_bag_full (1).bbmodel
- carver_flat_chisel.bbmodel
- carver_point_chisel.bbmodel
"""

import math
import os
import sys
from pathlib import Path

import bpy
from mathutils import Euler, Matrix, Quaternion, Vector

# Import local bb_import module
SCRIPT_DIR = Path(r"D:\23\art\carver_blender_anim")
if str(SCRIPT_DIR) not in sys.path:
    sys.path.append(str(SCRIPT_DIR))

import bb_import

DOWNLOADS = Path(r"C:\Users\HP\Downloads")
MODELS = {
    'hammer': DOWNLOADS / "ancient_peasant_hammer_fixed.bbmodel",
    'point_chisel': DOWNLOADS / "carver_point_chisel.bbmodel",
    'flat_chisel': DOWNLOADS / "carver_flat_chisel.bbmodel",
    'bag_full': DOWNLOADS / "carver_bag_full (1).bbmodel",
    'bag_empty': DOWNLOADS / "carver_bag_empty (1).bbmodel",
}
HUMAN_BASE = Path(r"D:\23\art\carver_animation_human_v2\eclipse_carver_human_v2.bbmodel")

OUT_BLEND = SCRIPT_DIR / "eclipse_carver_master_animation.blend"
PREVIEW_DIR = SCRIPT_DIR / "renders"
PREVIEW_DIR.mkdir(parents=True, exist_ok=True)


def build_scene():
    print("Clearing scene...")
    bpy.ops.object.select_all(action='SELECT')
    bpy.ops.object.delete(use_global=False)

    for c in list(bpy.data.collections):
        bpy.data.collections.remove(c)

    main_col = bpy.data.collections.new("01_Master_Carver")
    bpy.context.scene.collection.children.link(main_col)

    # 1. Import Artisan & Workbench base
    print("Importing artisan base and workbench...")
    artisan_root, artisan_parts = bb_import.import_bbmodel(
        str(HUMAN_BASE), "artisan", collection=main_col
    )

    # Remove old low-poly tools from human base
    for old_tool in ['chisel', 'mallet']:
        if old_tool in artisan_parts and artisan_parts[old_tool]:
            bpy.data.objects.remove(artisan_parts[old_tool], do_unlink=True)
            del artisan_parts[old_tool]

    # 2. Import the 5 authentic user models
    print("Importing authentic tools from Downloads...")
    imported_tools = {}
    for key, path in MODELS.items():
        r_empty, p_dict = bb_import.import_bbmodel(str(path), f"tool_{key}", collection=main_col)
        imported_tools[key] = (r_empty, p_dict)

    # 3. Parenting & Tool Sockets
    r_hand = artisan_parts.get("right_hand")
    l_hand = artisan_parts.get("left_hand")
    body = artisan_parts.get("body")
    workbench = artisan_parts.get("workbench")

    tool_hammer = bpy.data.objects.get("tool_hammer")
    tool_point = bpy.data.objects.get("tool_point_chisel")
    tool_flat = bpy.data.objects.get("tool_flat_chisel")
    tool_bag = bpy.data.objects.get("tool_bag_full")
    tool_bag_empty = bpy.data.objects.get("tool_bag_empty")

    # Hammer in Right Hand
    tool_hammer.parent = r_hand
    tool_hammer.location = Vector((0.0, 0.0, -0.56))
    tool_hammer.rotation_mode = 'XYZ'
    tool_hammer.rotation_euler = Euler((math.radians(20), math.radians(-30), math.radians(110)), 'XYZ')

    # Point Chisel in Left Hand
    tool_point.parent = l_hand
    tool_point.location = Vector((0.0, 0.0, -0.48))
    tool_point.rotation_mode = 'XYZ'
    tool_point.rotation_euler = Euler((math.radians(135), math.radians(15), math.radians(-25)), 'XYZ')

    # Flat Chisel in Left Hand
    tool_flat.parent = l_hand
    tool_flat.location = Vector((0.0, 0.0, -0.48))
    tool_flat.rotation_mode = 'XYZ'
    tool_flat.rotation_euler = Euler((math.radians(135), math.radians(15), math.radians(-25)), 'XYZ')
    tool_flat.hide_viewport = True
    tool_flat.hide_render = True

    # Carver Bag slung on the hip / back strap
    tool_bag.parent = body
    tool_bag.location = Vector((0.24, -0.10, 0.08))
    tool_bag.rotation_mode = 'XYZ'
    tool_bag.rotation_euler = Euler((math.radians(10), math.radians(-15), math.radians(-10)), 'XYZ')

    tool_bag_empty.parent = body
    tool_bag_empty.location = Vector((0.24, -0.10, 0.08))
    tool_bag_empty.rotation_mode = 'XYZ'
    tool_bag_empty.rotation_euler = Euler((math.radians(10), math.radians(-15), math.radians(-10)), 'XYZ')
    tool_bag_empty.hide_viewport = True
    tool_bag_empty.hide_render = True

    # 4. Lighting & Camera Setup
    world = bpy.context.scene.world or bpy.data.worlds.new("Master_World")
    bpy.context.scene.world = world
    world.use_nodes = True
    bg = world.node_tree.nodes.get("Background")
    if bg:
        bg.inputs["Color"].default_value = (0.04, 0.035, 0.03, 1.0)
        bg.inputs["Strength"].default_value = 0.5

    # Key Sunlight
    sun_data = bpy.data.lights.new("Master_Sun", 'SUN')
    sun_data.energy = 4.2
    sun_data.color = (1.0, 0.94, 0.85)
    sun = bpy.data.objects.new("Master_Sun", sun_data)
    main_col.objects.link(sun)
    sun.location = Vector((2.8, 3.2, 4.5))
    sun.rotation_euler = (math.radians(-50), math.radians(20), math.radians(135))

    # Rim light
    rim_data = bpy.data.lights.new("Master_Rim", 'POINT')
    rim_data.energy = 55.0
    rim_data.color = (0.75, 0.88, 1.0)
    rim = bpy.data.objects.new("Master_Rim", rim_data)
    main_col.objects.link(rim)
    rim.location = Vector((-2.0, -1.8, 2.2))

    # Fill bounce light
    fill_data = bpy.data.lights.new("Master_Fill", 'POINT')
    fill_data.energy = 35.0
    fill_data.color = (1.0, 0.88, 0.72)
    fill = bpy.data.objects.new("Master_Fill", fill_data)
    main_col.objects.link(fill)
    fill.location = Vector((1.4, 2.2, 1.3))

    # Camera
    cam_data = bpy.data.cameras.new("Master_Camera")
    cam_data.lens = 38.0
    cam = bpy.data.objects.new("Master_Camera", cam_data)
    main_col.objects.link(cam)
    bpy.context.scene.camera = cam
    cam.location = Vector((2.1, 2.8, 1.55))
    aim = Vector((0.0, 0.45, 0.85))
    cam.rotation_euler = (aim - cam.location).to_track_quat('-Z', 'Y').to_euler()

    return artisan_parts, imported_tools


def animate_master_carver(artisan_parts):
    """
    Creates a full 210-frame (7.0s at 30 FPS) Master Carver Choreography:
    - Act 1 (1-25): Breathing, stance setup, planting point chisel
    - Act 2 (26-55): Strike 1 - Power rough blow (Impact at 38, chip_0 flies)
    - Act 3 (56-85): Strike 2 - Second heavy blow (Impact at 68, chip_1 & chip_2 fly)
    - Act 4 (86-120): Craftsman pause, leaning in, blowing away dust
    - Act 5 (121-135): Switch to Flat Chisel with choke-grip
    - Act 6 (136-175): Precision rhythmic planing taps (Taps at 142, 150, 158)
    - Act 7 (176-210): Triumph, sweeping dust off surface, satisfied nod, loop settle
    """
    scene = bpy.context.scene
    scene.frame_start = 1
    scene.frame_end = 210
    scene.render.fps = 30

    body = artisan_parts["body"]
    head = artisan_parts["head"]
    l_arm = artisan_parts["left_arm"]
    l_fore = artisan_parts["left_forearm"]
    l_hand = artisan_parts["left_hand"]
    r_arm = artisan_parts["right_arm"]
    r_fore = artisan_parts["right_forearm"]
    r_hand = artisan_parts["right_hand"]
    l_leg = artisan_parts["left_leg"]
    r_leg = artisan_parts["right_leg"]

    tool_point = bpy.data.objects.get("tool_point_chisel")
    tool_flat = bpy.data.objects.get("tool_flat_chisel")

    chips = [artisan_parts.get(f"chip_{i}") for i in range(5)]

    def kf(obj, frame, loc=None, rot_deg=None, scale=None, hide=None):
        if obj is None:
            return
        if loc is not None:
            obj.location = Vector(loc)
            obj.keyframe_insert(data_path="location", frame=frame)
        if rot_deg is not None:
            obj.rotation_mode = 'XYZ'
            obj.rotation_euler = Euler([math.radians(a) for a in rot_deg], 'XYZ')
            obj.keyframe_insert(data_path="rotation_euler", frame=frame)
        if scale is not None:
            obj.scale = Vector(scale)
            obj.keyframe_insert(data_path="scale", frame=frame)
        if hide is not None:
            obj.hide_viewport = hide
            obj.hide_render = hide
            obj.keyframe_insert(data_path="hide_viewport", frame=frame)
            obj.keyframe_insert(data_path="hide_render", frame=frame)

    # Initial Chip State (Hidden inside block before detachment)
    for c in chips:
        if c:
            kf(c, 1, scale=(0, 0, 0))

    # Tool visibility timeline
    kf(tool_point, 1, hide=False)
    kf(tool_point, 124, hide=False)
    kf(tool_point, 125, hide=True)

    kf(tool_flat, 1, hide=True)
    kf(tool_flat, 124, hide=True)
    kf(tool_flat, 125, hide=False)

    # Stance legs (planted firmly)
    kf(l_leg, 1, rot_deg=(12, -4, 2))
    kf(r_leg, 1, rot_deg=(8, 4, -2))
    kf(l_leg, 210, rot_deg=(12, -4, 2))
    kf(r_leg, 210, rot_deg=(8, 4, -2))

    # -------------------------------------------------------------
    # ACT 1: Breathing & Planting Chisel (Frames 1 - 25)
    # -------------------------------------------------------------
    # Frame 1: Idle start
    kf(body, 1, loc=(0, 0, 0.75), rot_deg=(4, 0, 0))
    kf(head, 1, rot_deg=(10, 0, 0))
    kf(l_arm, 1, rot_deg=(30, 10, -5))
    kf(l_fore, 1, rot_deg=(25, -15, 10))
    kf(l_hand, 1, rot_deg=(10, 5, -10))
    kf(r_arm, 1, rot_deg=(45, -10, 15))
    kf(r_fore, 1, rot_deg=(40, 10, -10))
    kf(r_hand, 1, rot_deg=(-10, -5, 10))

    # Frame 15: Inhale, raising hammer into high ready position, planting chisel tip
    kf(body, 15, loc=(0, 0, 0.752), rot_deg=(6, -2, 0))
    kf(head, 15, rot_deg=(18, 5, -2))
    kf(l_arm, 15, rot_deg=(48, 12, -12))
    kf(l_fore, 15, rot_deg=(38, -22, 12))
    kf(l_hand, 15, rot_deg=(14, 8, -15))
    kf(r_arm, 15, rot_deg=(68, -18, 22))
    kf(r_fore, 15, rot_deg=(55, 12, -12))
    kf(r_hand, 15, rot_deg=(-18, -8, 12))

    # Frame 25: Solid contact established, eye focused on cut line
    kf(body, 25, loc=(0, 0, 0.75), rot_deg=(8, -3, 0))
    kf(head, 25, rot_deg=(22, 6, -3))
    kf(l_arm, 25, rot_deg=(52, 14, -14))
    kf(l_fore, 25, rot_deg=(42, -24, 14))
    kf(l_hand, 25, rot_deg=(15, 10, -18))
    kf(r_arm, 25, rot_deg=(72, -20, 24))
    kf(r_fore, 25, rot_deg=(58, 14, -14))
    kf(r_hand, 25, rot_deg=(-20, -10, 14))

    # -------------------------------------------------------------
    # ACT 2: Strike 1 - Heavy Roughing Blow (Frames 26 - 55)
    # -------------------------------------------------------------
    # Frame 34: Backswing anticipation (Hammer cocked back high, body coils)
    kf(body, 34, loc=(0, -0.015, 0.755), rot_deg=(2, 6, -2))
    kf(head, 34, rot_deg=(20, 4, -2))
    kf(l_arm, 34, rot_deg=(50, 12, -12))
    kf(l_fore, 34, rot_deg=(42, -24, 14))
    kf(r_arm, 34, rot_deg=(92, -28, 35))
    kf(r_fore, 34, rot_deg=(82, 18, -18))
    kf(r_hand, 34, rot_deg=(-35, -15, 20))

    # Frame 37: Downswing acceleration (Hammer speeding down, elbow leading)
    kf(body, 37, loc=(0, 0.01, 0.748), rot_deg=(12, -4, 1))
    kf(r_arm, 37, rot_deg=(42, -10, 12))
    kf(r_fore, 37, rot_deg=(35, 8, -8))
    kf(r_hand, 37, rot_deg=(5, 5, -5))

    # Frame 38: IMPACT! Solid clang on chisel poll!
    kf(body, 38, loc=(0, 0.02, 0.742), rot_deg=(15, -6, 2))
    kf(head, 38, rot_deg=(28, 8, -4))
    # Chisel shivers with impact shock
    kf(l_arm, 38, rot_deg=(56, 16, -16))
    kf(l_fore, 38, rot_deg=(46, -26, 16))
    kf(l_hand, 38, rot_deg=(18, 12, -22))
    # Hammer striking squarely
    kf(r_arm, 38, rot_deg=(28, -5, 6))
    kf(r_fore, 38, rot_deg=(22, 4, -4))
    kf(r_hand, 38, rot_deg=(18, 10, -10))

    # Chip 0 detaches & flies
    if chips[0]:
        kf(chips[0], 37, loc=(0.06, 0.65, 0.76), scale=(0, 0, 0), rot_deg=(0, 0, 0))
        kf(chips[0], 38, loc=(0.06, 0.65, 0.76), scale=(1, 1, 1), rot_deg=(15, 20, 0))
        kf(chips[0], 44, loc=(0.14, 0.78, 0.88), scale=(1, 1, 1), rot_deg=(120, 180, 45))
        kf(chips[0], 52, loc=(0.22, 0.86, 0.76), scale=(1, 1, 1), rot_deg=(240, 360, 90))

    # Frame 41: Elastic recoil & shockwave
    kf(body, 41, loc=(0, 0.015, 0.745), rot_deg=(13, -5, 1))
    kf(l_arm, 41, rot_deg=(53, 14, -14))
    kf(l_fore, 41, rot_deg=(43, -24, 14))
    kf(r_arm, 41, rot_deg=(38, -8, 10))
    kf(r_fore, 41, rot_deg=(32, 6, -6))
    kf(r_hand, 41, rot_deg=(5, 2, -2))

    # Frame 50: Re-settle after Strike 1
    kf(body, 50, loc=(0, 0, 0.75), rot_deg=(9, -3, 0))
    kf(head, 50, rot_deg=(22, 6, -3))
    kf(l_arm, 50, rot_deg=(52, 14, -14))
    kf(l_fore, 50, rot_deg=(42, -24, 14))
    kf(r_arm, 50, rot_deg=(65, -16, 20))
    kf(r_fore, 50, rot_deg=(52, 12, -12))
    kf(r_hand, 50, rot_deg=(-15, -6, 8))

    # -------------------------------------------------------------
    # ACT 3: Strike 2 - Second Heavy Power Blow (Frames 56 - 85)
    # -------------------------------------------------------------
    # Frame 58: Reposition chisel tip slightly deeper
    kf(l_hand, 58, rot_deg=(16, 12, -20))

    # Frame 64: High backswing
    kf(body, 64, loc=(0, -0.018, 0.756), rot_deg=(1, 7, -2))
    kf(head, 64, rot_deg=(19, 4, -2))
    kf(r_arm, 64, rot_deg=(95, -30, 38))
    kf(r_fore, 64, rot_deg=(85, 20, -20))
    kf(r_hand, 64, rot_deg=(-38, -16, 22))

    # Frame 67: Downward acceleration
    kf(body, 67, loc=(0, 0.012, 0.747), rot_deg=(13, -5, 1))
    kf(r_arm, 67, rot_deg=(44, -12, 14))
    kf(r_fore, 67, rot_deg=(36, 8, -8))
    kf(r_hand, 67, rot_deg=(6, 6, -6))

    # Frame 68: IMPACT 2! Deeper fracture
    kf(body, 68, loc=(0, 0.022, 0.740), rot_deg=(16, -7, 2))
    kf(head, 68, rot_deg=(30, 9, -5))
    kf(l_arm, 68, rot_deg=(58, 18, -18))
    kf(l_fore, 68, rot_deg=(48, -28, 18))
    kf(l_hand, 68, rot_deg=(20, 14, -24))
    kf(r_arm, 68, rot_deg=(26, -4, 5))
    kf(r_fore, 68, rot_deg=(20, 3, -3))
    kf(r_hand, 68, rot_deg=(20, 12, -12))

    # Chips 1 & 2 fly out!
    if chips[1]:
        kf(chips[1], 67, loc=(0.04, 0.67, 0.76), scale=(0, 0, 0))
        kf(chips[1], 68, loc=(0.04, 0.67, 0.76), scale=(1, 1, 1), rot_deg=(10, -30, 0))
        kf(chips[1], 74, loc=(-0.08, 0.82, 0.90), scale=(1, 1, 1), rot_deg=(150, -180, 60))
        kf(chips[1], 82, loc=(-0.18, 0.92, 0.76), scale=(1, 1, 1), rot_deg=(280, -360, 120))
    if chips[2]:
        kf(chips[2], 67, loc=(0.08, 0.65, 0.76), scale=(0, 0, 0))
        kf(chips[2], 68, loc=(0.08, 0.65, 0.76), scale=(1, 1, 1), rot_deg=(25, 45, 0))
        kf(chips[2], 75, loc=(0.18, 0.80, 0.86), scale=(1, 1, 1), rot_deg=(180, 220, -45))
        kf(chips[2], 84, loc=(0.28, 0.90, 0.76), scale=(1, 1, 1), rot_deg=(360, 400, -90))

    # Frame 72: Recoil dampening
    kf(body, 72, loc=(0, 0.015, 0.745), rot_deg=(14, -5, 1))
    kf(r_arm, 72, rot_deg=(40, -10, 12))
    kf(r_fore, 72, rot_deg=(34, 8, -8))

    # Frame 82: Settle
    kf(body, 82, loc=(0, 0, 0.75), rot_deg=(10, -3, 0))
    kf(head, 82, rot_deg=(24, 7, -3))
    kf(l_arm, 82, rot_deg=(52, 14, -14))
    kf(l_fore, 82, rot_deg=(42, -24, 14))
    kf(r_arm, 82, rot_deg=(55, -12, 16))
    kf(r_fore, 82, rot_deg=(42, 10, -10))

    # -------------------------------------------------------------
    # ACT 4: Inspection & Blowing Dust (Frames 86 - 120)
    # -------------------------------------------------------------
    # Frame 92: Lowering hammer to rest near thigh, leaning in to inspect cut
    kf(body, 92, loc=(0, 0.03, 0.745), rot_deg=(16, -2, 0))
    kf(head, 92, rot_deg=(32, 12, -4))
    kf(l_arm, 92, rot_deg=(40, 10, -10))
    kf(l_fore, 92, rot_deg=(30, -18, 10))
    kf(r_arm, 92, rot_deg=(22, 5, -5))
    kf(r_fore, 92, rot_deg=(15, 2, -2))
    kf(r_hand, 92, rot_deg=(0, 0, 0))

    # Frame 100: Leaning down over stone block to blow away dust
    kf(body, 100, loc=(0, 0.06, 0.738), rot_deg=(24, 0, 0))
    kf(head, 100, rot_deg=(42, 0, 0))
    kf(l_arm, 100, rot_deg=(32, 8, -8))
    kf(l_fore, 100, rot_deg=(22, -12, 8))

    # Frame 106: Exhaling breath / blowing dust
    kf(body, 106, loc=(0, 0.065, 0.736), rot_deg=(25, 0, 0))
    kf(head, 106, rot_deg=(44, 0, 0))

    # Frame 114: Straightening back, thoughtful approving nod
    kf(body, 114, loc=(0, 0.015, 0.748), rot_deg=(10, -2, 0))
    kf(head, 114, rot_deg=(16, 5, -2))

    # -------------------------------------------------------------
    # ACT 5: Transition to Flat Chisel (Frames 121 - 135)
    # -------------------------------------------------------------
    # Chisel swaps at 125. Artisan takes precision choke-grip.
    kf(body, 125, loc=(0, 0, 0.75), rot_deg=(8, -2, 0))
    kf(head, 125, rot_deg=(24, 6, -3))
    kf(l_arm, 125, rot_deg=(54, 16, -16))
    kf(l_fore, 125, rot_deg=(44, -26, 16))
    kf(l_hand, 125, rot_deg=(18, 12, -20))
    kf(r_arm, 125, rot_deg=(58, -14, 18))
    kf(r_fore, 125, rot_deg=(48, 10, -10))
    kf(r_hand, 125, rot_deg=(-12, -6, 8))

    # -------------------------------------------------------------
    # ACT 6: Precision Detailing & Rhythmic Planing (Frames 136 - 175)
    # -------------------------------------------------------------
    # Tap 1 (142): Light precision tap, planing first smooth bevel
    kf(r_arm, 139, rot_deg=(72, -18, 22))
    kf(r_fore, 139, rot_deg=(62, 12, -12))
    # Impact 142
    kf(r_arm, 142, rot_deg=(36, -8, 10))
    kf(r_fore, 142, rot_deg=(28, 6, -6))
    kf(l_hand, 142, rot_deg=(22, 14, -22))
    if chips[3]:
        kf(chips[3], 141, loc=(0.07, 0.66, 0.76), scale=(0, 0, 0))
        kf(chips[3], 142, loc=(0.07, 0.66, 0.76), scale=(1, 1, 1), rot_deg=(0, 15, 0))
        kf(chips[3], 147, loc=(0.12, 0.74, 0.82), scale=(1, 1, 1), rot_deg=(90, 120, 30))
        kf(chips[3], 154, loc=(0.18, 0.82, 0.76), scale=(1, 1, 1), rot_deg=(180, 240, 60))

    # Tap 2 (150): Quick rhythmic second tap
    kf(r_arm, 147, rot_deg=(68, -16, 20))
    kf(r_fore, 147, rot_deg=(58, 10, -10))
    # Impact 150
    kf(r_arm, 150, rot_deg=(34, -7, 9))
    kf(r_fore, 150, rot_deg=(26, 5, -5))

    # Tap 3 (158): Finishing crisp bevel shaving
    kf(r_arm, 155, rot_deg=(66, -15, 18))
    kf(r_fore, 155, rot_deg=(56, 9, -9))
    # Impact 158
    kf(r_arm, 158, rot_deg=(32, -6, 8))
    kf(r_fore, 158, rot_deg=(24, 4, -4))
    if chips[4]:
        kf(chips[4], 157, loc=(0.05, 0.68, 0.76), scale=(0, 0, 0))
        kf(chips[4], 158, loc=(0.05, 0.68, 0.76), scale=(1, 1, 1), rot_deg=(15, -20, 0))
        kf(chips[4], 164, loc=(-0.04, 0.78, 0.84), scale=(1, 1, 1), rot_deg=(110, -140, 45))
        kf(chips[4], 172, loc=(-0.12, 0.88, 0.76), scale=(1, 1, 1), rot_deg=(220, -280, 90))

    # -------------------------------------------------------------
    # ACT 7: Triumph, Inspection & Settling Pose (Frames 176 - 210)
    # -------------------------------------------------------------
    # Frame 180: Straightening back, stepping out of work stance
    kf(body, 180, loc=(0, -0.01, 0.755), rot_deg=(2, 0, 0))
    kf(head, 180, rot_deg=(8, 2, -1))
    kf(r_arm, 180, rot_deg=(20, 0, 0))
    kf(r_fore, 180, rot_deg=(12, 0, 0))
    kf(r_hand, 180, rot_deg=(0, 0, 0))

    # Left hand sweeping softly across finished carving surface
    kf(l_arm, 185, rot_deg=(38, 5, -5))
    kf(l_fore, 185, rot_deg=(28, -10, 5))
    kf(l_hand, 185, rot_deg=(8, 4, -5))

    # Frame 195: Confident proud craftsman nod
    kf(body, 195, loc=(0, 0, 0.75), rot_deg=(4, 0, 0))
    kf(head, 195, rot_deg=(4, 0, 0))
    kf(head, 202, rot_deg=(14, 0, 0))  # Nod down
    kf(head, 210, rot_deg=(8, 0, 0))   # Settle

    # Frame 210: Loop settle (matches Frame 1)
    kf(body, 210, loc=(0, 0, 0.75), rot_deg=(4, 0, 0))
    kf(l_arm, 210, rot_deg=(30, 10, -5))
    kf(l_fore, 210, rot_deg=(25, -15, 10))
    kf(l_hand, 210, rot_deg=(10, 5, -10))
    kf(r_arm, 210, rot_deg=(45, -10, 15))
    kf(r_fore, 210, rot_deg=(40, 10, -10))
    kf(r_hand, 210, rot_deg=(-10, -5, 10))

    print("Master animation keyframed successfully across 210 frames.")


def render_keyframes_preview():
    """Renders key storytelling frames for inspection."""
    key_frames = [
        (1, "01_stance_focus"),
        (38, "02_strike1_heavy_impact"),
        (68, "03_strike2_fracture_burst"),
        (102, "04_dust_blowing_pause"),
        (150, "05_precision_planing_flat_chisel"),
        (195, "06_triumph_proud_nod"),
    ]

    scene = bpy.context.scene
    scene.render.image_settings.file_format = 'PNG'
    scene.render.resolution_x = 960
    scene.render.resolution_y = 540

    rendered = []
    for f, name in key_frames:
        scene.frame_set(f)
        out_path = PREVIEW_DIR / f"frame_{f:03d}_{name}.png"
        scene.render.filepath = str(out_path)
        bpy.ops.render.render(write_still=True)
        rendered.append(str(out_path))
        print(f"Rendered frame {f}: {out_path.name}")

    return rendered


def main():
    artisan_parts, imported_tools = build_scene()
    animate_master_carver(artisan_parts)

    # Save .blend file
    bpy.ops.wm.save_as_mainfile(filepath=str(OUT_BLEND))
    print(f"Saved master file to: {OUT_BLEND}")

    rendered_images = render_keyframes_preview()
    return rendered_images


if __name__ == "__main__":
    main()
