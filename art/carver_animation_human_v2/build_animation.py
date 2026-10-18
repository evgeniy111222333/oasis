"""Human motion study based on First Cut; keeps all v1 artifacts intact."""
from pathlib import Path
import copy
import json
import math
import sys

import numpy as np
from scipy.interpolate import PchipInterpolator, CubicSpline
from scipy.spatial.transform import Rotation

OUT = Path(__file__).resolve().parent
sys.path.insert(0, str(OUT.parent / 'carver_animation_first_cut'))
import build_animation as base

V = base.vec
R = base.rot
FPS = 30
NEAR = V([0, 0, 0])
FAR = V([0, 0, -23.5])
TIP_A = V([1, 12.035, -9])
TIP_B = V([-0.65, 12.035, -9.65])
BACK_A = V([-1, 12.535, -13.8])
BACK_B = V([0.4, 12.535, -13.8])
START_HIT = [1.08]
FRONT_HITS = [0.50, 1.46, 2.63, 3.62]
BACK_HITS = [0.56, 1.63, 2.49]
FULL_HITS = START_HIT + [1.6 + t for t in FRONT_HITS] + [10.4 + t for t in BACK_HITS]


def curve(points):
    return PchipInterpolator([p[0] for p in points], [p[1] for p in points], extrapolate=False)


def sample(c, t):
    return float(c(np.clip(t, c.x[0], c.x[-1])))


def smooth(x):
    x = float(np.clip(x, 0, 1))
    return x * x * (3 - 2 * x)


def window(t, start, end):
    return smooth((t - start) / (end - start))


def pulse(t, events, lag=0, gain=1):
    total = 0.0
    for event in events:
        age = t - event - lag
        if 0 < age < 0.28:
            total += gain * math.sin(age / 0.28 * math.pi) * math.exp(-age / 0.08)
    return total


ENTRY_LIFT = curve([(0, 0), (.24, .38), (.55, 2.1), (.85, 3.3),
                    (.95, 3.4), (1.035, .9), (1.08, 0), (1.14, .68), (1.34, 1.15), (1.6, .9)])
WORK_LIFT = curve([(0, .9), (.27, 2.05), (.40, 1.7), (.475, .28), (.50, 0),
                   (.56, .54), (.78, 1.2), (1.13, 3.35), (1.28, 3.40),
                   (1.425, .29), (1.46, 0), (1.52, .76), (1.82, 1.35),
                   (2.20, 3.5), (2.43, 3.8), (2.595, .30), (2.63, 0),
                   (2.69, .67), (2.95, 1.24), (3.32, 2.2), (3.48, 1.8),
                   (3.59, .22), (3.62, 0), (3.68, .48), (3.96, 1.03), (4.2, .9)])
BACK_LIFT = curve([(0, .9), (.34, 2.7), (.50, .8), (.56, 0), (.63, .65),
                   (.91, 1.25), (1.36, 3.55), (1.58, .36), (1.63, 0),
                   (1.70, .72), (1.98, 1.15), (2.31, 1.9), (2.45, .23),
                   (2.49, 0), (2.55, .46), (2.86, 1.17), (3.2, .9)])
GAZE_FRONT = curve([(0, 0), (.8, -1), (1.65, 0), (2.0, 5.5), (2.6, 1.5),
                    (3.0, 0), (3.82, -4.2), (4.2, 0)])
GAZE_BACK = curve([(0, 0), (.8, 1), (1.0, -3), (1.7, 2.5), (2.3, 0), (2.8, 3), (3.2, 0)])


def target_path(t, length, a, b, shift=(1.66, 2.15), back=(3.78, 4.2)):
    amount = window(t, *shift) * (1 - window(t, *back))
    lift = 0.0
    for start, end in [shift, back]:
        if start < t < end:
            lift += math.sin((t - start) / (end - start) * math.pi) ** 2 * .65
    tip = a * (1 - amount) + b * amount
    return tip + V([0, lift, 0])


class Route:
    def __init__(self):
        waypoints = np.array([[0, 0], [13.8, .6], [15.5, -11.4], [13.8, -25.2], [0, -23.5]])
        self.spline = CubicSpline(np.linspace(0, 1, len(waypoints)), waypoints, bc_type='clamped')
        self.u = np.linspace(0, 1, 3001)
        dense = self.spline(self.u)
        distance = np.r_[0, np.cumsum(np.linalg.norm(np.diff(dense, axis=0), axis=1))]
        self.length = float(distance[-1])
        self.arc_to_u = PchipInterpolator(distance / distance[-1], self.u)

    def at(self, t):
        q = np.clip((t - 5.95) / 4.10, 0, 1)
        # Short acceleration/deceleration, constant pace across the corners.
        ramp = .10
        if q < ramp:
            arc = q * q / (2 * ramp * (1 - ramp))
        elif q > 1 - ramp:
            arc = 1 - (1 - q) ** 2 / (2 * ramp * (1 - ramp))
        else:
            arc = (q - ramp / 2) / (1 - ramp)
        u = float(self.arc_to_u(arc))
        x, z = self.spline(u)
        dx, dz = self.spline(max(.00001, min(.99999, u)), 1)
        yaw = math.degrees(math.atan2(-dx, -dz))
        if q <= 0:
            yaw = 0
        else:
            yaw *= window(t, 5.84, 6.17)
        yaw = yaw * (1 - window(t, 9.76, 10.28)) + 180 * window(t, 9.76, 10.28)
        if t >= 10.28:
            yaw = 180
        return V([x, 0, z]), yaw


ROUTE = Route()


class Feet:
    def __init__(self):
        self.moves = {}
        for side, hip, offset in [('left', 2, -.3), ('right', -2, .4)]:
            current = V([hip, 0, offset])
            yaw = 0.0
            events = []
            first = 5.84 if side == 'left' else 6.19
            for i in range(7):
                start, end = first + .7 * i, first + .7 * i + .34
                root, target_yaw = ROUTE.at(end + .15)
                target = root + R(y=target_yaw) @ V([hip, 0, offset])
                if end >= 10.15:
                    target = FAR + R(y=180) @ V([hip, 0, offset])
                    target_yaw = 180
                events.append((start, end, current.copy(), target.copy(), yaw, target_yaw))
                current, yaw = target, target_yaw
            self.moves[side] = events

    def at(self, side, t):
        moves = self.moves[side]
        foot, yaw = moves[0][2].copy(), 0.0
        for start, end, a, b, ya, yb in moves:
            if t < start:
                break
            if t >= end:
                foot, yaw = b.copy(), yb
                continue
            u = (t - start) / (end - start)
            h = smooth(u)
            foot = a * (1 - h) + b * h
            foot[1] = math.sin(u * math.pi) * .95
            yaw = ya + (yb - ya + 180) % 360 * h - 180 * h
            return foot, yaw
        return foot, yaw


FEET = Feet()


def ctx(plant_l=1, plant_r=1, lift=.9, tip=None, root=None, yaw=0,
        clock=0, period=4.2, events=(), event_t=0, gaze=0, tilt=12, walking=0):
    return dict(plant_l=plant_l, plant_r=plant_r, lift=lift,
                tip=TIP_A if tip is None else tip, root=NEAR if root is None else root,
                yaw=yaw, clock=clock, period=period, events=events, event_t=event_t,
                gaze=gaze, tilt=tilt, walking=walking)


def entry_state(t):
    return ctx(plant_l=window(t, .10, .76), plant_r=window(t, .04, .66),
               lift=sample(ENTRY_LIFT, t), clock=t - 1.6, event_t=t,
               events=START_HIT, gaze=-3 * math.sin(t / 1.6 * math.pi))


def front_state(t, vertical=False):
    if vertical:
        a, b = V([1, 10.2, -6.02]), V([-.65, 10.2, -6.02])
    else:
        a, b = TIP_A, TIP_B
    return ctx(lift=sample(WORK_LIFT, t), tip=target_path(t, 4.2, a, b),
               clock=t, events=FRONT_HITS, event_t=t, gaze=sample(GAZE_FRONT, t),
               tilt=65 if vertical else 12)


def back_state(t):
    return ctx(lift=sample(BACK_LIFT, t), tip=target_path(t, 3.2, BACK_A, BACK_B,
               shift=(.88, 1.27), back=(2.81, 3.2)), root=FAR, yaw=180,
               clock=t, period=3.2, events=BACK_HITS, event_t=t, gaze=sample(GAZE_BACK, t))


def move_state(t):
    absolute = t + 5.8
    root, yaw = ROUTE.at(absolute)
    withdrawal = 1 - window(t, .02, .36)
    resume_l = window(t, 4.02, 4.60)
    resume_r = window(t, 3.94, 4.50)
    left, right = withdrawal + resume_l, (1 - window(t, 0, .29)) + resume_r
    tip = TIP_A if t < 2.2 else BACK_A
    tip = tip + V([0, math.sin(window(t, .01, .40) * math.pi) * 1.1, 0])
    walking = window(absolute, 5.84, 6.18) * (1 - window(absolute, 9.78, 10.48))
    return ctx(plant_l=left, plant_r=right, lift=.9 * max(left, right),
               tip=tip, root=root, yaw=yaw, clock=absolute, gaze=0,
               walking=walking)


def finish_state(t):
    left = 1 - window(t, .12, 1.35)
    right = 1 - window(t, .03, 1.05)
    return ctx(plant_l=left, plant_r=right, lift=.9 * right, tip=BACK_A,
               root=FAR, yaw=180, clock=3.2 + t, period=3.2,
               gaze=7 * math.sin(t / 1.4 * math.pi))


def full_state(t):
    if t < 1.6:
        return entry_state(t)
    if t < 5.8:
        return front_state(t - 1.6)
    if t < 10.4:
        return move_state(t - 5.8)
    if t < 13.6:
        return back_state(t - 10.4)
    return finish_state(t - 13.6)


def limb(shoulder, end, pole):
    delta = end - shoulder
    distance = float(np.linalg.norm(delta))
    if distance >= 11.999:
        raise ValueError(f'Unreachable limb: {distance:.4f}, shoulder={shoulder}, end={end}')
    direction = delta / distance
    bend = V(pole) - direction * np.dot(pole, direction)
    bend /= np.linalg.norm(bend)
    elbow = shoulder + delta / 2 + bend * math.sqrt(36 - distance ** 2 / 4)
    return base.direction_frame(elbow - shoulder), base.direction_frame(end - elbow)


def pose(c, feet_time=None):
    left, right, lift = c['plant_l'], c['plant_r'], c['lift']
    planted = min(1, .60 * left + .40 * right)
    phase = 2 * math.pi * c['clock'] / c['period']
    body_shock = pulse(c['event_t'], c['events'], lag=.035, gain=2.7)
    head_shock = pulse(c['event_t'], c['events'], lag=.075, gain=4.0)
    brace = pulse(c['event_t'], c['events'], lag=-.045, gain=1.8)
    walk = c['walking']
    root = c['root'].copy()
    root[1] = -.18 - 1.08 * walk - .04 * planted * math.sin(phase) ** 2
    if walk:
        root[1] += .05 * math.cos((c['clock'] - 5.84) / .35 * 2 * math.pi) * walk
    root_rot = R(y=c['yaw'])
    body_rot = R(planted * (-7 + lift * .58 - body_shock * 1.55) - 2.4 * walk,
                 planted * (-2.6 - lift * .68 + .75 * math.sin(phase)),
                 planted * (-.7 + .55 * math.sin(phase + .6)) + walk * .9 * math.sin(c['clock'] * 7.85))
    body_pos = V([planted * (-.16 + .18 * math.sin(phase)), -body_shock * .07,
                  -.10 * planted * math.sin(phase + .4)])
    result = {'artisan_root': {'rotation': [0, c['yaw'], 0], 'position': root.tolist()},
              'body': {'rotation': base.euler(body_rot), 'position': body_pos.tolist()},
              'head': {'rotation': [-27 * planted - head_shock * 2 - 6 * walk,
                                     c['gaze'] - 2 * planted + .75 * math.sin(phase) * planted,
                                     planted * (.6 + .5 * math.sin(phase + 1.1))]}}
    tip = root_rot.T @ (c['tip'] - root)
    tool_rot = R(c['tilt'] + planted * (.65 * math.sin(phase) + brace * .35))
    cap = tip + tool_rot @ V([0, base.CAP_LENGTH, 0])
    planted_left = tip + tool_rot @ V([0, base.GRIP, 0])
    # Independent preparation envelopes, curved carry paths, stable tool contact.
    left_idle = V([5, 10.6 + .15 * walk * math.sin(c['clock'] * 7.85), -.2])
    lhand = left_idle * (1 - left) + planted_left * left
    lhand += V([.7, 2.25, -.4]) * math.sin(left * math.pi)
    lrot = Rotation.from_rotvec(Rotation.from_matrix(tool_rot).as_rotvec() * left).as_matrix()
    hammer_rot = tool_rot @ R(lift * 6.8) @ R(z=-90)
    # The swing changes plane together with the chisel, including vertical surfaces.
    displacement = tool_rot @ V([-lift * .55, lift, -lift * .43])
    rhand_target = cap - hammer_rot @ base.MALLET_FACE + displacement
    right_idle = V([-5, 10.65 - .20 * walk * math.sin(c['clock'] * 7.85), .25])
    rhand = right_idle * (1 - right) + rhand_target * right
    rhand += V([-.55, 1.25, -.25]) * math.sin(right * math.pi)
    rrot = Rotation.from_rotvec(Rotation.from_matrix(hammer_rot).as_rotvec() * right).as_matrix()
    for side, hand, hand_rot, sign in [('left', lhand, lrot, 1), ('right', rhand, rrot, -1)]:
        shoulder = V([0, 12, 0]) + body_pos + body_rot @ V([sign * 5, 10, 0])
        upper, fore = limb(shoulder, hand, V([sign, .10 + brace * .08, .28]))
        result[side + '_arm'] = {'rotation': base.euler(body_rot.T @ upper)}
        result[side + '_forearm'] = {'rotation': base.euler(upper.T @ fore)}
        result[side + '_hand'] = {'rotation': base.euler(fore.T @ hand_rot)}
    for side, hip, offset in [('left', 2, -.3), ('right', -2, .4)]:
        if feet_time is None:
            foot_world = c['root'] + root_rot @ V([hip, 0, offset])
            foot_world[1] = 0
            foot_yaw = c['yaw']
        else:
            foot_world, foot_yaw = FEET.at(side, feet_time)
        foot_local = root_rot.T @ (foot_world - root)
        thigh, shin = limb(V([hip, 12, 0]), foot_local, V([0, 0, -1]))
        foot_rot = root_rot.T @ R(y=foot_yaw)
        result[side + '_leg'] = {'rotation': base.euler(thigh)}
        result[side + '_shin'] = {'rotation': base.euler(thigh.T @ shin)}
        result[side + '_foot'] = {'rotation': base.euler(shin.T @ foot_rot)}
    world_cap = root + root_rot @ cap
    world_face = root + root_rot @ (rhand + rrot @ base.MALLET_FACE)
    return result, dict(tip=c['tip'].tolist(), cap=world_cap.tolist(),
                        mallet_face=world_face.tolist(), root=root.tolist(), yaw=c['yaw'])


def simplify(times, values, forced, tolerance):
    keep = {0, len(times) - 1}
    keep.update(i for i, t in enumerate(times) if any(abs(t - f) < 1e-6 for f in forced))
    def segment(a, b):
        if b <= a + 1:
            return
        weight = ((times[a+1:b] - times[a]) / (times[b] - times[a]))[:, None]
        predicted = values[a] * (1 - weight) + values[b] * weight
        errors = np.max(np.abs(predicted - values[a+1:b]), axis=1)
        i = int(np.argmax(errors)) + a + 1
        if errors[i-a-1] > tolerance:
            keep.add(i); segment(a, i); segment(i, b)
    borders = sorted(keep)
    for a, b in zip(borders, borders[1:]):
        segment(a, b)
    return sorted(keep)


def make_clip(name, length, state_fn, hits, loop='hold', foot_clock=None):
    forced = [0, length] + hits
    for hit in hits:
        forced += [max(0, hit-.02), min(length, hit+.02)]
    times = np.array(sorted(set([round(i/FPS, 6) for i in range(round(length*FPS)+1)] + forced)))
    times = times[times <= length + 1e-8]
    values, info = [], []
    for t in times:
        try:
            p, data = pose(state_fn(float(t)), foot_clock(float(t)) if foot_clock else None)
        except ValueError as e:
            raise ValueError(f'{name} at {t:.4f}s: {e}') from e
        values.append(p); info.append(data)
    animators = {}
    for bone, channels in values[0].items():
        frames = []
        for channel in channels:
            array = np.array([p[bone][channel] for p in values])
            if channel == 'rotation':
                array = np.rad2deg(np.unwrap(np.deg2rad(array), axis=0))
            selected = simplify(times, array, forced, .018 if channel == 'rotation' else .003)
            frames += [base.keyframe(channel, float(times[i]), array[i]) for i in selected]
        animators[base.BONES[bone]['uuid']] = dict(name=bone, type='bone', keyframes=frames)
    for i in range(5):
        frames = []
        for t in times:
            earlier = [h for h in hits if h <= t]
            impact = max(earlier, default=-100)
            age = t - impact
            alive = 0 <= age < .36
            tip = V(pose(state_fn(float(impact)))[1]['tip']) if alive else TIP_A
            yaw = state_fn(float(impact))['yaw'] if alive else 0
            displacement = R(y=yaw) @ V([(i-2)*1.8*age, .25+(3.7+i*.4)*age-19*age**2, -(1.7+i*.3)*age]) if alive else V([0, 0, 0])
            frames += [base.keyframe('position', float(t), tip + displacement),
                       base.keyframe('scale', float(t), [1 if alive else 0] * 3),
                       base.keyframe('rotation', float(t), [age*270 if alive else 0, i*28, age*190 if alive else 0])]
        bone = 'chip_' + str(i)
        animators[base.BONES[bone]['uuid']] = dict(name=bone, type='bone', keyframes=frames)
    if loop == 'loop':
        first, last = values[0], values[-1]
        for bone in first:
            for channel in first[bone]:
                error = np.max(np.abs(V(first[bone][channel]) - V(last[bone][channel])))
                assert error < 1e-6, (name, 'loop seam', bone, channel, error)
    checks = []
    for hit in hits:
        _, contact = pose(state_fn(hit), foot_clock(hit) if foot_clock else None)
        error = float(np.linalg.norm(V(contact['cap'])-V(contact['mallet_face'])))
        assert error < 1e-6, (name, hit, error)
        checks.append(dict(clip=name, time=hit, tip=contact['tip'], contact_error=error))
    return dict(uuid=base.uid(), name=name, loop=loop, override=True, length=length,
                snapping=FPS, selected=False, animators=animators), checks


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    base.OUT = OUT
    base.make_scene()
    # Articulated ankles keep the feet flat while knees flex or the artisan walks.
    for side, hip in [('left', 2), ('right', -2)]:
        boot = next(e for e in base.ELEMENTS if e['name'] == side + ' boot')
        base.BONES[side+'_shin']['children'].remove(boot['uuid'])
        foot = base.group(side+'_foot', [hip, 0, 0], side+'_shin')
        boot['origin'] = [hip, 0, 0]
        foot['children'].append(boot['uuid'])
    floor = next(e for e in base.ELEMENTS if e['name']=='Workshop floor')
    floor['from'] = [-26, -.65, -36]; floor['to'] = [26, -.03, 13]
    specs = [
        ('00_Showcase_Human',15.0,full_state,FULL_HITS,'hold',lambda t:t),
        ('01_Start_Flow',1.6,entry_state,START_HIT,'hold',None),
        ('02_Work_Varied',4.2,front_state,FRONT_HITS,'loop',None),
        ('03_Reposition_Opposite',4.6,move_state,[],'hold',lambda t:t+5.8),
        ('04_Work_Opposite',3.2,back_state,BACK_HITS,'loop',None),
        ('05_Finish_Inspect',1.4,finish_state,[],'hold',None),
        ('06_Work_Vertical_Face',4.2,lambda t:front_state(t,True),FRONT_HITS,'loop',None),
    ]
    clips, checks = [], []
    for spec in specs:
        clip, report = make_clip(*spec); clips.append(clip); checks += report
    clips[0]['selected'] = True
    nested = {g['uuid'] for b in base.BONES.values() for g in b['children'] if isinstance(g,dict)}
    model = dict(meta=dict(format_version='5.0', model_format='free', box_uv=False),
                 name='Eclipse_Carver_Human_V2', model_identifier='eclipse.carver.human.v2',
                 resolution=dict(width=128,height=128), elements=base.ELEMENTS,
                 groups=base.GROUPS, outliner=[b for b in base.BONES.values() if b['uuid'] not in nested],
                 textures=base.TEXTURES, animations=clips)
    (OUT/'eclipse_carver_human_v2.bbmodel').write_text(json.dumps(model,separators=(',',':')),encoding='utf-8')
    report = dict(fps=FPS, clips=[dict(name=c['name'],length=c['length'],loop=c['loop']) for c in clips],
                  contact_checks=checks, route_length=ROUTE.length,
                  near_target=TIP_A.tolist(),other_target=TIP_B.tolist(),opposite_target=BACK_A.tolist(),
                  vertical_target=[1,10.2,-6.02],
                  source='Based on carver_animation_first_cut; v1 files unchanged.',
                  scope='Editable Blockbench motion prototype, not Minecraft runtime integration.')
    (OUT/'timing_and_validation.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
    print(json.dumps(dict(clips=len(clips),contact_checks=len(checks),route_length=ROUTE.length,
                         file=str(OUT/'eclipse_carver_human_v2.bbmodel')),indent=2))


if __name__ == '__main__':
    main()
