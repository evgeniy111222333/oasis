"""Procedural pixel-art textures for the carver scene (skin, oak log, floor, stone).

Everything is painted with numpy into RGBA arrays (row 0 = top of the image, the way a
Minecraft skin is drawn) and pushed into packed Blender images.
"""
import numpy as np
import bpy

RNG = np.random.default_rng(23)


def _c(hexstr):
    hexstr = hexstr.lstrip('#')
    return np.array([int(hexstr[i:i + 2], 16) for i in (0, 2, 4)] + [255], dtype=float)


class Canvas:
    def __init__(self, w, h):
        self.a = np.zeros((h, w, 4), dtype=float)
        self.w, self.h = w, h

    def rect(self, x, y, w, h, color, noise=7.0, seed=None):
        rng = np.random.default_rng(seed) if seed is not None else RNG
        col = _c(color) if isinstance(color, str) else np.array(color, dtype=float)
        block = np.tile(col, (h, w, 1))
        if noise:
            n = rng.normal(0, noise, (h, w, 1))
            block[..., :3] += n
        self.a[y:y + h, x:x + w] = block

    def px(self, x, y, color, noise=0.0):
        col = _c(color) if isinstance(color, str) else np.array(color, dtype=float)
        if noise:
            col = col.copy()
            col[:3] += RNG.normal(0, noise)
        self.a[y, x] = col

    def shade(self, x, y, w, h, factor):
        self.a[y:y + h, x:x + w, :3] *= factor

    def to_image(self, name):
        img = bpy.data.images.get(name)
        if img is None or img.size[0] != self.w or img.size[1] != self.h:
            if img:
                bpy.data.images.remove(img)
            img = bpy.data.images.new(name, self.w, self.h, alpha=True)
        data = np.clip(self.a / 255.0, 0, 1)[::-1].astype(np.float32)
        img.pixels.foreach_set(data.ravel())
        img.update()
        img.pack()
        return img


def box_regions(u, v, w, h, d):
    """Minecraft box-UV regions for a box (w x h x d) whose UV starts at (u, v)."""
    return {
        'top': (u + d, v, w, d), 'bottom': (u + d + w, v, w, d),
        'right': (u, v + d, d, h), 'front': (u + d, v + d, w, h),
        'left': (u + d + w, v + d, d, h), 'back': (u + 2 * d + w, v + d, w, h),
    }


SKIN = '#c8916b'
SKIN_D = '#a8714f'
HAIR = '#3f2a1c'
HAIR_L = '#56392530'[:7]
BEARD = '#4d3424'
SHIRT = '#cfc4a4'
SHIRT_D = '#b0a585'
LEATHER = '#7a4e30'
LEATHER_D = '#5b3920'
LEATHER_L = '#94623d'
BELT = '#3a281c'
TROUSER = '#434a55'
TROUSER_D = '#353b45'
BOOT = '#3b2a1f'
BOOT_L = '#5a4231'


def paint_skin():
    cv = Canvas(64, 64)

    # ---------------------------------------------------------------- head (0,0) 8x8x8
    r = box_regions(0, 0, 8, 8, 8)
    x, y, w, h = r['top'];    cv.rect(x, y, w, h, HAIR, 9)
    x, y, w, h = r['bottom']; cv.rect(x, y, w, h, BEARD, 6)
    for side in ('right', 'left'):
        x, y, w, h = r[side]
        cv.rect(x, y, w, h, SKIN, 5)
        cv.rect(x, y, w, 3, HAIR, 9)
        back_cols = range(x + 4, x + 8) if side == 'right' else range(x, x + 4)
        for cx in back_cols:
            cv.rect(cx, y + 3, 1, 3, HAIR, 9)
        # sideburn + beard along the jaw, ear
        jaw = x + 7 if side == 'right' else x
        cv.rect(jaw if side == 'right' else jaw, y + 3, 1, 5, BEARD, 6)
        ear = x + 4 if side == 'right' else x + 3
        cv.px(ear, y + 4, SKIN_D)
        cv.rect(x if side == 'right' else x + 4, y + 6, 4, 2, BEARD, 6)
    x, y, w, h = r['back']
    cv.rect(x, y, w, h, HAIR, 9)
    cv.rect(x, y + 7, w, 1, SKIN_D, 4)
    # face
    x, y, w, h = r['front']
    cv.rect(x, y, w, h, SKIN, 5)
    cv.rect(x, y, w, 2, HAIR, 9)
    cv.px(x + 0, y + 2, HAIR); cv.px(x + 7, y + 2, HAIR)
    cv.rect(x + 1, y + 2, 2, 1, '#4a3122', 3)          # brows
    cv.rect(x + 5, y + 2, 2, 1, '#4a3122', 3)
    cv.rect(x + 1, y + 3, 2, 1, '#ece8de', 2)          # eye whites (pupils are geometry)
    cv.rect(x + 5, y + 3, 2, 1, '#ece8de', 2)
    cv.px(x + 3, y + 4, SKIN_D); cv.px(x + 4, y + 4, SKIN_D)   # nose
    cv.px(x + 3, y + 3, '#c08463'); cv.px(x + 4, y + 3, '#c08463')
    cv.rect(x + 1, y + 5, 6, 1, BEARD, 5)              # moustache
    cv.rect(x + 0, y + 6, 8, 2, BEARD, 6)              # beard
    cv.rect(x + 3, y + 6, 2, 1, '#6e3f2e', 3)          # mouth
    cv.px(x + 0, y + 3, SKIN_D); cv.px(x + 7, y + 3, SKIN_D)
    cv.px(x + 0, y + 5, BEARD); cv.px(x + 7, y + 5, BEARD)

    # ---------------------------------------------------------------- body (16,16) 8x12x4
    r = box_regions(16, 16, 8, 12, 4)
    for k in ('top', 'bottom', 'right', 'left', 'front', 'back'):
        x, y, w, h = r[k]; cv.rect(x, y, w, h, SHIRT, 6)
    x, y, w, h = r['top']
    cv.rect(x + 1, y, 1, h, LEATHER, 4); cv.rect(x + 6, y, 1, h, LEATHER, 4)   # straps
    x, y, w, h = r['front']
    cv.px(x + 3, y, SKIN); cv.px(x + 4, y, SKIN); cv.px(x + 3, y + 1, SHIRT_D)  # collar
    cv.rect(x + 1, y, 1, 3, LEATHER, 4); cv.rect(x + 6, y, 1, 3, LEATHER, 4)    # straps
    cv.rect(x + 1, y + 3, 6, 9, LEATHER, 7)                                      # apron
    cv.rect(x + 1, y + 3, 6, 1, LEATHER_L, 4)
    cv.rect(x + 0, y + 7, 1, 1, BELT, 2); cv.rect(x + 7, y + 7, 1, 1, BELT, 2)
    cv.rect(x + 2, y + 8, 4, 2, LEATHER_D, 4)                                    # pocket
    cv.rect(x + 2, y + 8, 4, 1, LEATHER_L, 3)
    cv.px(x + 3, y + 9, '#8e8e8e'); cv.px(x + 4, y + 9, '#a37045')               # tool tips
    for k in ('right', 'left'):
        x, y, w, h = r[k]
        cv.rect(x, y + 7, w, 1, BELT, 2)
        cv.px(x + (0 if k == 'right' else 3), y + 8, LEATHER, 3)
    x, y, w, h = r['back']
    cv.rect(x, y + 7, w, 1, BELT, 2)
    for i in range(7):                       # crossed straps
        cv.px(x + 1 + i * 6 // 6 if False else x + min(7, 1 + i), y + i, LEATHER, 4)
        cv.px(x + max(0, 6 - i), y + i, LEATHER, 4)
    cv.shade(*r['right'], 0.9); cv.shade(*r['left'], 0.9)

    # ---------------------------------------------------------------- arms
    def arm(u, v):
        r = box_regions(u, v, 4, 12, 4)
        for k in ('right', 'front', 'left', 'back'):
            x, y, w, h = r[k]
            cv.rect(x, y, w, 5, SHIRT, 6)
            cv.rect(x, y + 5, w, 1, SHIRT_D, 4)                     # rolled cuff
            cv.rect(x, y + 6, w, 6, SKIN, 5)
            cv.rect(x, y + 8, w, 2, LEATHER, 5)                     # leather bracer
            cv.rect(x, y + 8, w, 1, LEATHER_L, 3)
            cv.rect(x, y + 10, w, 2, SKIN, 4)                       # hand
        x, y, w, h = r['front']
        cv.rect(x, y + 11, w, 1, SKIN_D, 3)                         # knuckles
        x, y, w, h = r['top']; cv.rect(x, y, w, h, SHIRT, 5)
        x, y, w, h = r['bottom']; cv.rect(x, y, w, h, SKIN_D, 4)
    arm(40, 16)   # right arm
    arm(32, 48)   # left arm

    # ---------------------------------------------------------------- legs
    def leg(u, v, apron_cols):
        r = box_regions(u, v, 4, 12, 4)
        for k in ('right', 'front', 'left', 'back'):
            x, y, w, h = r[k]
            cv.rect(x, y, w, 9, TROUSER, 6)
            cv.rect(x, y + 6, w, 1, TROUSER_D, 4)
            cv.rect(x, y + 9, w, 3, BOOT, 5)
            cv.rect(x, y + 9, w, 1, BOOT_L, 4)
        x, y, w, h = r['front']
        c0, c1 = apron_cols
        cv.rect(x + c0, y, c1 - c0, 4, LEATHER, 7)
        cv.rect(x + c0, y + 4, c1 - c0, 1, LEATHER_D, 4)
        x, y, w, h = r['top']; cv.rect(x, y, w, h, TROUSER, 5)
        x, y, w, h = r['bottom']; cv.rect(x, y, w, h, '#2a1d15', 3)
    leg(0, 16, (1, 4))     # right leg: apron covers inner cols
    leg(16, 48, (0, 3))    # left leg
    return cv


def paint_oak_log_side():
    cv = Canvas(16, 16)
    cv.rect(0, 0, 16, 16, '#6b5234', 8)
    for x in range(16):
        if RNG.random() < 0.45:
            cv.rect(x, 0, 1, 16, '#5a4329', 6)
    for _ in range(18):
        cv.px(int(RNG.integers(16)), int(RNG.integers(16)), '#463420')
    return cv


def paint_oak_log_top():
    cv = Canvas(16, 16)
    cv.rect(0, 0, 16, 16, '#6b5234', 6)
    cy = cx = 7.5
    for y in range(1, 15):
        for x in range(1, 15):
            d = max(abs(x - cx), abs(y - cy))
            col = ['#b8945f', '#a8844f', '#b8945f', '#9c7a49', '#b08c57', '#a07e4c', '#987546'][int(d) % 7]
            cv.px(x, y, col, 4)
    return cv


def paint_floor():
    """Spruce-plank workshop floor, 16x16 tile."""
    cv = Canvas(16, 16)
    rows = ['#5c4429', '#674c2e', '#533d25', '#614729']
    for i in range(4):
        cv.rect(0, i * 4, 16, 4, rows[i], 6)
        cv.rect(0, i * 4 + 3, 16, 1, '#3e2c1a', 3)
        cut = (i * 7 + 3) % 16
        cv.rect(cut, i * 4, 1, 3, '#3e2c1a', 3)
    return cv


STONE_PALETTE = np.array([
    [0x7f, 0x7f, 0x7f], [0x74, 0x74, 0x74], [0x88, 0x88, 0x88], [0x6b, 0x6b, 0x6b],
    [0x8f, 0x8f, 0x8f], [0x7a, 0x7a, 0x7a], [0x62, 0x62, 0x62], [0x96, 0x96, 0x96],
], dtype=float) / 255.0


def stone_voxel_color(i, j, k, interior):
    """Colour of one microvoxel: Minecraft-stone speckle, a bit warmer and lighter
    when it is freshly cut interior material."""
    h = (i * 73856093) ^ (j * 19349663) ^ (k * 83492791)
    rng = np.random.default_rng(h & 0xffffffff)
    w = np.array([5, 4, 4, 2, 3, 4, 1, 1], dtype=float)
    c = STONE_PALETTE[rng.choice(8, p=w / w.sum())].copy()
    if interior:
        c = c * 1.06 + np.array([0.02, 0.015, 0.0])
    return np.clip(c, 0, 1)
