"""Build a contact-checked Blockbench animation study; never edits game assets."""
from pathlib import Path
import base64
import copy
import io
import json
import math
import uuid

import numpy as np
from PIL import Image
from scipy.spatial.transform import Rotation

OUT = Path(__file__).resolve().parent
ROOT = OUT.parents[1]
FPS = 30
GROUPS, ELEMENTS, TEXTURES = [], [], []
BONES = {}


def uid():
    return str(uuid.uuid4())


def vec(v):
    return np.array(v, dtype=float)


def rot(x=0, y=0, z=0):
    return Rotation.from_euler('ZYX', [z, y, x], degrees=True).as_matrix()


def group(name, origin, parent=None):
    g = dict(name=name, origin=origin, rotation=[0, 0, 0], uuid=uid(),
             export=True, isOpen=False, visibility=True, children=[])
    GROUPS.append({k: v for k, v in g.items() if k != 'children'})
    BONES[name] = g
    if parent:
        BONES[parent]['children'].append(g)
    return g


PALETTE = ['#C69B74', '#AD7A56', '#332D29', '#243336', '#3F6968', '#578483',
           '#243C40', '#77543C', '#A4774D', '#CFB991', '#404A4B', '#5D6461',
           '#78807A', '#91978B', '#D0CDB8', '#1B2528', '#E8DEC2', '#463B31',
           '#806344', '#A49C86', '#D9D5BF', '#344144', '#253131', '#6B4B37']


def texture_palette():
    im = Image.new('RGBA', (128, 128), '#243336')
    for i, color in enumerate(PALETTE):
        x, y = (i % 8) * 16, (i // 8) * 16
        im.paste(color, (x, y, x + 16, y + 16))
    buf = io.BytesIO()
    im.save(buf, format='PNG')
    TEXTURES.append(dict(uuid=uid(), name='craftsman_palette.png', width=128,
                         height=128, uv_width=128, uv_height=128,
                         source='data:image/png;base64,' + base64.b64encode(buf.getvalue()).decode()))
    im.save(OUT / 'craftsman_palette.png')


def cube(name, lo, hi, bone, color, rotation=None):
    x, y = (color % 8) * 16, (color // 8) * 16
    g = BONES[bone]
    e = dict(name=name, type='cube', uuid=uid(), from_=lo, to=hi,
             origin=g['origin'], box_uv=False, autouv=0, color=color % 8,
             rotation=rotation or [0, 0, 0], visibility=True, export=True,
             faces={s: dict(uv=[x + 3, y + 3, x + 12, y + 12], texture=0)
                    for s in ['north', 'east', 'south', 'west', 'up', 'down']})
    e['from'] = e.pop('from_')
    ELEMENTS.append(e)
    g['children'].append(e['uuid'])
    return e


def import_tool(path, name, parent, hand, grip, scale):
    d = json.loads(path.read_text(encoding='utf-8'))
    texture_offset = len(TEXTURES)
    for t in d['textures']:
        t = copy.deepcopy(t)
        t['uuid'] = uid()
        t.pop('path', None)
        TEXTURES.append(t)
    group(name, list(hand), parent)
    for original in d['elements']:
        e = copy.deepcopy(original)
        e['uuid'] = uid()
        e['name'] = name + ' / ' + e['name']
        e['box_uv'] = False
        for k in ['from', 'to', 'origin']:
            e[k] = (vec(hand) + (vec(e.get(k, grip)) - vec(grip)) * scale).tolist()
        for face in e['faces'].values():
            if isinstance(face.get('texture'), int):
                face['texture'] += texture_offset
        ELEMENTS.append(e)
        BONES[name]['children'].append(e['uuid'])


def make_scene():
    texture_palette()
    group('artisan_root', [0, 0, 0])
    group('body', [0, 12, 0], 'artisan_root')
    cube('Linen tunic', [-4, 12, -2], [4, 24, 2], 'body', 4)
    cube('Leather apron', [-3.55, 12, -2.30], [3.55, 21.3, -2.02], 'body', 7)
    cube('Apron hem', [-3.55, 10.9, -2.25], [3.55, 12.2, -2.02], 'body', 8)
    cube('Apron left strap', [2.4, 21.1, -2.20], [3.1, 24, -2.03], 'body', 8)
    cube('Apron right strap', [-3.1, 21.1, -2.20], [-2.4, 24, -2.03], 'body', 8)
    cube('Belt', [-4.12, 12.1, -2.13], [4.12, 13.0, 2.13], 'body', 2)
    cube('Buckle', [-0.55, 12.1, -2.30], [0.55, 13.05, -2.14], 'body', 9)
    group('head', [0, 24, 0], 'body')
    cube('Head', [-4, 24, -4], [4, 32, 4], 'head', 0)
    cube('Hair', [-4.13, 29.65, -3.1], [4.13, 32.2, 4.12], 'head', 2)
    cube('Hair at back', [-4.1, 25.8, 3.5], [4.1, 30, 4.12], 'head', 2)
    cube('Beard', [-3, 24, -4.13], [3, 26.6, -4.02], 'head', 2)
    cube('Nose', [-0.55, 26.4, -4.65], [0.55, 27.75, -3.95], 'head', 1)
    for x in [-2.2, 1.2]:
        cube('Eye white', [x, 28.05, -4.11], [x + 1, 28.7, -4.02], 'head', 16)
        cube('Eye pupil', [x + 0.25, 28.1, -4.17], [x + 0.67, 28.65, -4.10], 'head', 15)
        cube('Brow', [x - 0.12, 29.05, -4.14], [x + 1.12, 29.35, -4.03], 'head', 2)
    for side, x in [('left', 6), ('right', -6)]:
        shoulder = 5 if side == 'left' else -5
        group(side + '_arm', [shoulder, 22, 0], 'body')
        cube(side + ' upper sleeve', [x - 2, 16, -2], [x + 2, 24, 2], side + '_arm', 4)
        group(side + '_forearm', [shoulder, 16, 0], side + '_arm')
        cube(side + ' cuff', [x - 1.95, 15.2, -1.95], [x + 1.95, 16, 1.95], side + '_forearm', 5)
        cube(side + ' forearm', [x - 1.65, 10.6, -1.65], [x + 1.65, 15.2, 1.65], side + '_forearm', 0)
        group(side + '_hand', [shoulder, 10, 0], side + '_forearm')
        cube(side + ' glove', [shoulder - 1.7, 9.1, -1.7], [shoulder + 1.7, 11.3, 1.7], side + '_hand', 7)
        hip = 2 if side == 'left' else -2
        group(side + '_leg', [hip, 12, 0], 'artisan_root')
        cube(side + ' trousers', [hip - 1.9, 5.8, -1.9], [hip + 1.9, 12, 1.9], side + '_leg', 3)
        group(side + '_shin', [hip, 6, 0], side + '_leg')
        cube(side + ' shin', [hip - 1.8, 1.5, -1.8], [hip + 1.8, 6, 1.8], side + '_shin', 3)
        cube(side + ' boot', [hip - 2, 0, -2.8], [hip + 2, 2.6, 2.05], side + '_shin', 2)
    import_tool(ROOT / 'model/chisel.bbmodel', 'chisel', 'left_hand',
                [5, 10, 0], [0, 9.2, 0], 0.48)
    import_tool(ROOT / 'art/carver_tools_reveal_128/carver_mallet.bbmodel',
                'mallet', 'right_hand', [-5, 10, 0], [8, 8, 8], 0.28)
    group('workbench', [0, 0, -12])
    cube('Oak tabletop', [-10, 6.4, -19], [10, 8, -4], 'workbench', 8)
    for x in [-8, 6]:
        for z in [-17, -7]:
            cube('Bench leg', [x, 0, z], [x + 2, 6.4, z + 2], 'workbench', 7)
    cube('Bench crossbar', [-8, 2.5, -15], [8, 3.5, -13], 'workbench', 7)
    cube('Stone blank', [-5.5, 8, -14.5], [5.5, 12, -6], 'workbench', 12)
    cube('Stone left moulding', [-5.5, 12, -14.5], [-3.9, 12.5, -6], 'workbench', 13)
    cube('Stone back moulding', [-3.9, 12, -14.5], [5.5, 12.5, -13.2], 'workbench', 13)
    cube('Incised line', [0.78, 12.005, -12.8], [1.22, 12.035, -8.85], 'workbench', 10)
    cube('Apron tool roll', [6.7, 8, -14], [8.5, 8.7, -9], 'workbench', 7)
    group('stage', [0, 0, 0])
    cube('Workshop floor', [-23, -0.65, -24], [23, -0.03, 13], 'stage', 21)
    for i in range(5):
        group('chip_' + str(i), [0, 0, 0])
        size = 0.18 + i * 0.025
        cube('Stone flake ' + str(i), [-size, -size * 0.55, -size],
             [size, size * 0.55, size], 'chip_' + str(i), 12 + i % 2)


TIP = vec([1, 12.035, -9])
GRIP = 9.2 * 0.48
CAP_LENGTH = 12.98 * 0.48
MALLET_FACE = vec([7 * 0.28, 9 * 0.28, 0])
P = lambda plant=1, lift=1.1, shock=0, look=0: dict(plant=plant, lift=lift, shock=shock, look=look)
START = [(0, P(0, 0)), (0.30, P(0.08, 0.1)), (0.95, P(1, 1.1)),
         (1.18, P()), (1.88, P(1, 3.9)), (2.00, P(1, 3.9)),
         (2.14, P(1, 0)), (2.20, P(1, 0.64, 1)), (2.48, P()),
         (2.78, P(1, 1.1, 0, 5)), (3.20, P())]
WORK = [(0, P()), (0.55, P(1, 3.0)), (0.62, P(1, 3.0)),
        (0.74, P(1, 0)), (0.80, P(1, 0.56, 0.65)), (1.06, P()),
        (1.55, P(1, 3.6)), (1.65, P(1, 3.6)), (1.79, P(1, 0)),
        (1.85, P(1, 0.62, 0.9)), (2.16, P()), (2.4, P())]
FINISH = [(0, P()), (0.3, P(1, 1.1, 0, 6)), (0.7, P(0.75, 0.8, 0, 9)),
          (1.35, P(0.08, 0.1, 0, 5)), (1.8, P(0, 0))]


def interpolate(knots, t):
    if t <= knots[0][0]:
        return knots[0][1]
    if t >= knots[-1][0]:
        return knots[-1][1]
    for (a, pa), (b, pb) in zip(knots, knots[1:]):
        if a <= t <= b:
            u = (t - a) / (b - a)
            u = u * u * (3 - 2 * u)
            return {k: pa[k] + (pb[k] - pa[k]) * u for k in pa}


def direction_frame(direction):
    y = -direction / np.linalg.norm(direction)
    x = vec([1, 0, 0])
    x -= y * np.dot(x, y)
    if np.linalg.norm(x) < 0.05:
        x = vec([0, 0, 1]); x -= y * np.dot(x, y)
    x /= np.linalg.norm(x)
    z = np.cross(x, y)
    return np.column_stack([x, y, z])


def solve_arm(shoulder, hand, outward):
    delta = hand - shoulder
    distance = np.linalg.norm(delta)
    if distance >= 11.98:
        raise ValueError(f'Unreachable wrist: {distance:.3f}')
    direction = delta / distance
    bend = vec([outward, 0.12, 0.24])
    bend -= direction * np.dot(bend, direction)
    bend /= np.linalg.norm(bend)
    elbow = shoulder + delta / 2 + bend * math.sqrt(36 - distance ** 2 / 4)
    return direction_frame(elbow - shoulder), direction_frame(hand - elbow), elbow


def euler(matrix):
    z, y, x = Rotation.from_matrix(matrix).as_euler('ZYX', degrees=True)
    return [float(x), float(y), float(z)]


def pose(p):
    plant, lift, shock = p['plant'], p['lift'], p['shock']
    body_rot = rot(plant * (-7 + lift * 0.85 - shock * 1.1),
                   plant * (-3 - lift * 0.8), plant * (-1.2 + lift * 0.16))
    body_pos = vec([plant * (-0.12 + lift * 0.045), -shock * 0.06, 0])
    values = {'body': {'rotation': euler(body_rot), 'position': body_pos.tolist()},
              'head': {'rotation': [-14 * plant - shock * 1.7,
                                     p['look'] - 2 * plant, plant * 1.2]}}
    chisel_rot = rot(12 + shock * 0.5)
    planted = TIP + chisel_rot @ vec([0, GRIP, 0])
    left_idle = vec([5, 10.3, -0.2])
    left_hand = left_idle * (1 - plant) + planted * plant
    left_hand += vec([0.9, 2.3, -0.3]) * math.sin(plant * math.pi)
    chisel_rot = Rotation.from_rotvec(
        Rotation.from_matrix(chisel_rot).as_rotvec() * plant).as_matrix()
    cap = TIP + rot(12 + shock * 0.5) @ vec([0, CAP_LENGTH, 0])
    strike_rot = rot(lift * 7) @ rot(z=-90)
    right_target = cap - strike_rot @ MALLET_FACE + vec([-lift * 0.55, lift, -lift * 0.30])
    right_hand = vec([-5, 10.3, 0.4]) * (1 - plant) + right_target * plant
    right_hand += vec([-0.6, 1.3, 0.2]) * math.sin(plant * math.pi)
    strike_rot = Rotation.from_rotvec(
        Rotation.from_matrix(strike_rot).as_rotvec() * plant).as_matrix()
    for side, hand, hand_rot, sign in [('left', left_hand, chisel_rot, 1),
                                      ('right', right_hand, strike_rot, -1)]:
        shoulder_local = vec([sign * 5, 22, 0])
        shoulder = vec([0, 12, 0]) + body_pos + body_rot @ (shoulder_local - vec([0, 12, 0]))
        upper, fore, elbow = solve_arm(shoulder, hand, sign)
        values[side + '_arm'] = {'rotation': euler(body_rot.T @ upper)}
        values[side + '_forearm'] = {'rotation': euler(upper.T @ fore)}
        values[side + '_hand'] = {'rotation': euler(fore.T @ hand_rot)}
    return values, {'tip': TIP.tolist(), 'cap': cap.tolist(),
                    'mallet_face': (right_hand + strike_rot @ MALLET_FACE).tolist(),
                    'left_wrist': left_hand.tolist(), 'right_wrist': right_hand.tolist()}


def keyframe(channel, t, xyz):
    return dict(channel=channel, data_points=[dict(zip('xyz', [round(float(v), 6) for v in xyz]))],
                uuid=uid(), time=round(t, 6), color=-1, interpolation='linear')


def animation(name, knots, impacts, loop):
    length = knots[-1][0]
    times = sorted(set([round(i / FPS, 6) for i in range(round(length * FPS) + 1)] +
                       [t for t, p in knots] + impacts))
    times = [t for t in times if t <= length]
    samples = [pose(interpolate(knots, t))[0] for t in times]
    animators = {}
    for bone, channels in samples[0].items():
        frames = []
        for channel in channels:
            data = np.array([s[bone][channel] for s in samples])
            if channel == 'rotation':
                data = np.rad2deg(np.unwrap(np.deg2rad(data), axis=0))
            frames += [keyframe(channel, t, xyz) for t, xyz in zip(times, data)]
        animators[BONES[bone]['uuid']] = dict(name=bone, type='bone',
                                            rotation_global=False, keyframes=frames)
    for i in range(5):
        frames = []
        for t in times:
            age = min([t - impact for impact in impacts if t >= impact], default=99)
            alive = 0 <= age < 0.38
            x = TIP[0] + (i - 2) * 1.9 * age
            y = TIP[1] + 0.3 + (3.4 + i * 0.55) * age - 19 * age ** 2
            z = TIP[2] - (2 + i * 0.3) * age
            frames += [keyframe('position', t, [x, y, z] if alive else TIP),
                       keyframe('scale', t, [1 if alive else 0] * 3),
                       keyframe('rotation', t, [age * 380, i * 29, age * 240])]
        bone = 'chip_' + str(i)
        animators[BONES[bone]['uuid']] = dict(name=bone, type='bone', keyframes=frames)
    return dict(uuid=uid(), name=name, loop=loop, override=True, length=length,
                snapping=FPS, selected=False, animators=animators)


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    make_scene()
    full = START + [(3.2 + t, p) for t, p in WORK[1:]] + [(5.6 + t, p) for t, p in FINISH[1:]]
    specs = [('00_Showcase', full, [2.14, 3.94, 4.99], 'loop'),
             ('01_Start_First_Cut', START, [2.14], 'hold'),
             ('02_Work_Double_Strike', WORK, [0.74, 1.79], 'loop'),
             ('03_Finish_Inspect', FINISH, [], 'hold')]
    animations = [animation(*s) for s in specs]
    animations[0]['selected'] = True
    nested = {g['uuid'] for b in BONES.values() for g in b['children'] if isinstance(g, dict)}
    outliner = [b for b in BONES.values() if b['uuid'] not in nested]
    model = dict(meta=dict(format_version='5.0', model_format='free', box_uv=False),
                 name='Eclipse_Carver_First_Cut', model_identifier='eclipse.carver.first_cut',
                 resolution=dict(width=128, height=128), elements=ELEMENTS,
                 groups=GROUPS, outliner=outliner, textures=TEXTURES, animations=animations)
    path = OUT / 'eclipse_carver_first_cut.bbmodel'
    path.write_text(json.dumps(model, ensure_ascii=False, separators=(',', ':')), encoding='utf-8')
    checks = []
    for name, knots, impacts, loop in specs:
        for t in impacts:
            values, points = pose(interpolate(knots, t))
            error = float(np.linalg.norm(vec(points['cap']) - vec(points['mallet_face'])))
            assert error < 1e-6, (name, t, error)
            checks.append(dict(clip=name, time=t, contact_error_model_units=error))
        if loop == 'loop':
            start, _ = pose(knots[0][1]); end, _ = pose(knots[-1][1])
            assert start == end, f'Loop seam: {name}'
    report = dict(fps=FPS, clips=[dict(name=a['name'], length=a['length'], loop=a['loop']) for a in animations],
                  impact_checks=checks, notes='Preview asset only. No Minecraft runtime integration.',
                  source_tools=['model/chisel.bbmodel', 'art/carver_tools_reveal_128/carver_mallet.bbmodel'])
    (OUT / 'timing_and_validation.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
    print(json.dumps(dict(model=str(path), cubes=len(ELEMENTS), bones=len(GROUPS),
                         clips=len(animations), impact_checks=checks), indent=2))


if __name__ == '__main__':
    main()
