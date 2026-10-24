#!/usr/bin/env python3
"""
Draws the atlas of the Escape Rope's travel cinematic: a trainer walks out
of a cave onto a cliff ledge, a bird swoops in, the trainer jumps on its
back and they fly off, then fly on above the clouds until the trip ends.

Why drawn by code: the pictures are original pixel art in Minecraft's
style, free of rights, and can be redrawn (palette, poses) by editing this
file. The trainer and the bird are original designs made for this mod; no
Pokemon is drawn, species being protected characters.

The animation itself (who moves where, and when) is played in code by
client.travel.CaveEscapeAnimation; this script only draws the still frames.

Usage (from the repository root; needs Pillow and numpy):
    python3 tools/travel_cave_escape.py                  # writes the shipped atlas
    python3 tools/travel_cave_escape.py --previews DIR   # also an enlarged copy into DIR

Output (overwritten):
    common/src/main/resources/assets/cobblemon_safari/textures/gui/travel/cave_escape.png
Deterministic: the same script draws the same pixels.
"""

import math
import os
import sys

import numpy as np
from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "common", "src", "main", "resources", "assets", "cobblemon_safari", "textures", "gui",
                   "travel", "cave_escape.png")

# Atlas layout. EVERY value here is mirrored by a constant of
# client.travel.CaveEscapeAnimation (CAVE_BG_U, TRAINER_V and so on):
# moving a region without updating the Java side draws the wrong part of
# the atlas.
ATLAS_W, ATLAS_H = 512, 256
SCENE_W, SCENE_H = 192, 108          # both backgrounds, 16:9 so a widescreen shows them whole
CAVE_BG = (0, 0)                     # the cliff, the cave and the valley
SKY_BG = (192, 0)                    # open sky for the flight that follows
CLOUDS = (0, 108)                    # a strip of clouds, tileable left to right
CLOUDS_W, CLOUDS_H = 192, 40
TRAINER = (0, 160)                   # walk 0..3, then jump, each TRAINER_W x TRAINER_H
TRAINER_W, TRAINER_H = 16, 20
BIRD = (0, 184)                      # wings up, level, down; each BIRD_W x BIRD_H
BIRD_W, BIRD_H = 32, 24
RIDER = (0, 208)                     # the bird with the trainer seated, same three wing poses
RIDER_W, RIDER_H = 32, 32
# Where the seated trainer sits inside a rider frame (its top-left corner).
# Mirrored by SEAT_X, SEAT_Y: the jump of the animation lands there.
SEAT_X, SEAT_Y = 9, 1

# Where the cliff ledge is, in scene texels: the trainer's feet stand on
# LEDGE_Y, the ledge ends (and the drop begins) at LEDGE_END_X, the cave's
# mouth is at CAVE_MOUTH_X. Mirrored in CaveEscapeAnimation too.
LEDGE_Y = 78
LEDGE_END_X = 112
CAVE_MOUTH_X = 44

# Fixed seed: the same rocks and clouds on every run.
SEED = 2207

# Scale of the enlarged preview (--previews).
PREVIEW_SCALE = 4

# Ordered dither, as in gui_assets.py: two colours blended in a pixel-art way.
BAYER4 = np.array([[0, 8, 2, 10], [12, 4, 14, 6], [3, 11, 1, 9], [15, 7, 13, 5]]) / 16.0


def rgb(hex_colour):
    """An opaque RGBA tuple from '#rrggbb'."""
    h = hex_colour.lstrip("#")
    return int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), 255


OUTLINE = rgb("#1b1b24")

# Trainer palette. A safari explorer: red cap, khaki jacket, big backpack.
TRAINER_PALETTE = {
    "K": OUTLINE,
    "R": rgb("#c8402c"), "r": rgb("#8e2a1f"),
    "S": rgb("#f0c39a"), "s": rgb("#c98f68"),
    "H": rgb("#4a2e1c"),
    "E": OUTLINE,
    "G": rgb("#8d9a48"), "g": rgb("#646f2e"),
    "B": rgb("#9a6431"), "b": rgb("#6b4420"),
    "P": rgb("#3b4a6b"), "p": rgb("#2a3550"),
    "O": rgb("#5a3a22"),
}

# Upper body, facing right (rows 0..14). The arm swings with the step.
TRAINER_TOP = [
    "......KKKKK.....",
    ".....KRRRRRK....",
    "....KRRRRRRRK...",
    "....KrrrrrrRRRK.",
    "....KHHSSSSKKK..",
    "...KBKHSSESSK...",
    "...KBKHSSSSSK...",
    "..KBBKKsSSsK....",
    "..KBBKGGGGGK....",
    "..KBBKGGGGGSK...",
    "..KBbKGGGGGSK...",
    "..KbbKGgGGGK....",
    "...KKKGgggGK....",
    ".....KPPPPPK....",
    ".....KPPpPPK....",
]

# Legs (rows 15..19): striding, then together, for the walk.
LEGS_STRIDE = [
    "....KPKKKPPK....",
    "...KPPK..KPPK...",
    "...KPK....KPK...",
    "..KOOK....KOOK..",
    "..KKKK....KKKK..",
]
LEGS_TOGETHER = [
    ".....KPPPPK.....",
    ".....KPKKPK.....",
    ".....KPK.KPK....",
    ".....KOOKKOOK...",
    ".....KKKKKKKK...",
]
LEGS_TUCKED = [
    ".....KPPPPPK....",
    "....KPPKKPPK....",
    "....KOOK.KOOK...",
    "....KKKK.KKKK...",
    "................",
]
# Seated on the bird: legs forward along its back.
LEGS_SEATED = [
    ".....KPPPPPKKK..",
    ".....KPPPPPPOOK.",
    "......KKKKKKKKK.",
]


def sprite(rows, palette):
    """An RGBA image from rows of palette letters ('.' is transparent)."""
    h, w = len(rows), len(rows[0])
    img = np.zeros((h, w, 4), dtype=np.uint8)
    for y, row in enumerate(rows):
        assert len(row) == w, "ragged sprite row %d: %r" % (y, row)
        for x, ch in enumerate(row):
            if ch != ".":
                img[y, x] = palette[ch]
    return img


def trainer_frames():
    """Walk 0..3 (stride, together, stride, together) and the jump."""
    stride = sprite(TRAINER_TOP + LEGS_STRIDE, TRAINER_PALETTE)
    together = sprite(TRAINER_TOP + LEGS_TOGETHER, TRAINER_PALETTE)
    jump = sprite(TRAINER_TOP + LEGS_TUCKED, TRAINER_PALETTE)
    # The body bobs one texel up on the "together" steps, so the walk reads.
    bob = np.zeros_like(together)
    bob[:-1] = together[1:]
    return [stride, bob, stride, bob, jump]


def seated_trainer():
    """The trainer seated, 16 x 18: upper body down to the hips, legs forward."""
    return sprite(TRAINER_TOP[:15] + LEGS_SEATED, TRAINER_PALETTE)


# Bird palette: an original rust-and-cream hawk, nothing like any species.
FEATHER = rgb("#b5652d")
FEATHER_SHADE = rgb("#7f4320")
WING_TIP = rgb("#4a2a14")
CREAM = rgb("#f2dfb5")
BEAK = rgb("#f2b632")
CREST = rgb("#3f7f8c")

# Wing outlines for the three poses, as polygons in bird texels. The wing
# root sits on the bird's back (around x 11..21, y 10..12).
WING_POSES = {
    "up": [(11, 11), (21, 11), (17, 4), (12, 0), (7, 1)],
    "level": [(10, 11), (22, 11), (12, 8), (3, 7)],
    "down": [(11, 12), (21, 12), (15, 21), (9, 22), (8, 18)],
}


def inside_polygon(px, py, poly):
    """Even-odd test of a texel centre against a polygon."""
    inside = False
    n = len(poly)
    for i in range(n):
        x1, y1 = poly[i]
        x2, y2 = poly[(i + 1) % n]
        if (y1 > py) != (y2 > py):
            cross = x1 + (py - y1) * (x2 - x1) / (y2 - y1)
            if px < cross:
                inside = not inside
    return inside


def outline(img, colour=OUTLINE):
    """Adds a one-texel outline around the opaque shape, outside it."""
    alpha = img[:, :, 3] > 0
    grown = alpha.copy()
    grown[1:] |= alpha[:-1]
    grown[:-1] |= alpha[1:]
    grown[:, 1:] |= alpha[:, :-1]
    grown[:, :-1] |= alpha[:, 1:]
    ring = grown & ~alpha
    img[ring] = colour
    return img


def bird_frame(pose):
    """The bird facing right, 32 x 24, its wing in one of WING_POSES."""
    img = np.zeros((BIRD_H, BIRD_W, 4), dtype=np.uint8)
    for y in range(BIRD_H):
        for x in range(BIRD_W):
            cx, cy = x + 0.5, y + 0.5
            # Tail: a fan behind the body.
            if 2 <= cx <= 10 and abs(cy - 13) <= (cx - 2) * 0.45 + 1.5:
                img[y, x] = WING_TIP if cx < 4.5 else FEATHER_SHADE
            # Body: an ellipse, rust above, cream belly below.
            if ((cx - 16) / 8.5) ** 2 + ((cy - 13) / 5.0) ** 2 <= 1:
                img[y, x] = CREAM if cy > 13.5 else FEATHER
            # Head and its cream cheek.
            if (cx - 24.5) ** 2 + (cy - 9) ** 2 <= 16:
                img[y, x] = CREAM if cy > 10.5 and cx > 23 else FEATHER
    # A small teal crest, the bird's own mark.
    for x, y in [(22, 4), (23, 4), (21, 5), (22, 5)]:
        img[y, x] = CREST
    # Beak and eye.
    for x, y in [(28, 8), (29, 8), (28, 9), (29, 9), (30, 9), (28, 10)]:
        img[y, x] = BEAK
    img[8, 26] = OUTLINE
    # Feet, tucked under the belly.
    for x, y in [(15, 18), (18, 18)]:
        img[y, x] = BEAK
    outline(img)
    # The wing goes over the body, with its own outline so it stays readable
    # against the feathers of the back.
    wing = np.zeros_like(img)
    poly = WING_POSES[pose]
    for y in range(BIRD_H):
        for x in range(BIRD_W):
            if inside_polygon(x + 0.5, y + 0.5, poly):
                wing[y, x] = FEATHER_SHADE
    # Dark flight feathers at the far end of the wing.
    far = max(poly, key=lambda p: abs(p[1] - 11) + abs(p[0] - 16) * 0.3)
    for y in range(BIRD_H):
        for x in range(BIRD_W):
            if wing[y, x, 3] and math.hypot(x - far[0], y - far[1]) < 4.5:
                wing[y, x] = WING_TIP
    outline(wing)
    mask = wing[:, :, 3] > 0
    img[mask] = wing[mask]
    return img


def rider_frame(pose):
    """The bird with the trainer seated on its back, 32 x 32."""
    img = np.zeros((RIDER_H, RIDER_W, 4), dtype=np.uint8)
    bird = bird_frame(pose)
    seated = seated_trainer()
    # The trainer behind the bird's head, seated on its back: drawn first,
    # so the bird's body hides the legs' lower edge.
    place(img, seated, SEAT_X, SEAT_Y)
    # The bird under the trainer, wing included (a raised wing passes in
    # front of the trainer, as it would).
    place(img, bird, 0, RIDER_H - BIRD_H)
    return img


def place(dst, src, x, y):
    """Copies the opaque texels of src onto dst at (x, y)."""
    h, w = src.shape[:2]
    region = dst[y:y + h, x:x + w]
    mask = src[:h, :w, 3] > 0
    region[mask] = src[mask]


def sky_gradient(img, x0, w, h):
    """Morning sky: deep blue at the top to warm gold at the horizon, dithered."""
    top = np.array(rgb("#3d6fb5")[:3], dtype=float)
    mid = np.array(rgb("#8fb9e0")[:3], dtype=float)
    low = np.array(rgb("#f3c58f")[:3], dtype=float)
    bands = 8
    for y in range(h):
        t = y / (h - 1)
        level = t * bands
        for x in range(w):
            # Dithered between two flat bands rather than a smooth ramp.
            band = math.floor(level + BAYER4[y % 4, x % 4] - 0.5)
            band = max(0, min(bands, band))
            s = band / bands
            colour = top + (mid - top) * (s / 0.6) if s < 0.6 else mid + (low - mid) * ((s - 0.6) / 0.4)
            img[y, x0 + x] = (*np.clip(colour, 0, 255).astype(np.uint8), 255)


def sun(img, x0, cx, cy, r):
    """A pale sun disk with a soft dithered halo."""
    core = rgb("#fff4d6")
    halo = rgb("#ffe3a8")
    for y in range(max(0, cy - r * 2), cy + r * 2 + 1):
        for x in range(cx - r * 2, cx + r * 2 + 1):
            d = math.hypot(x - cx, y - cy)
            if d <= r:
                img[y, x0 + x] = core
            elif d <= r * 1.7 and BAYER4[y % 4, x % 4] < 0.5:
                img[y, x0 + x] = halo


def mountains(img, x0, w, base, height, colour, seed, roughness):
    """A ridge of distant mountains whose top follows a few summed sines."""
    rng = np.random.default_rng(seed)
    phases = rng.uniform(0, math.tau, 3)
    for x in range(w):
        top = base - height * (0.55 + 0.25 * math.sin(x / 23.0 + phases[0])
                               + 0.15 * math.sin(x / 9.0 + phases[1])
                               + roughness * math.sin(x / 3.7 + phases[2]))
        for y in range(int(top), SCENE_H):
            img[y, x0 + x] = colour


def forest(img, x0, x_from, w, top):
    """The valley's canopy far below the cliff: dithered greens with bumps."""
    dark = rgb("#2f5a2e")
    light = rgb("#4f8a3a")
    rng = np.random.default_rng(SEED + 5)
    bumps = rng.integers(0, 3, w)
    for x in range(x_from, w):
        crown = top - bumps[x] - (1 if (x // 5) % 2 else 0)
        for y in range(crown, SCENE_H):
            pick = light if (y - crown) < 2 or BAYER4[y % 4, x % 4] < 0.25 else dark
            img[y, x0 + x] = pick


def cliff(img, x0):
    """The rock mass on the left: its grassy top, the ledge, the cave and the drop."""
    rock = rgb("#7a6a5a")
    rock_dark = rgb("#5d5045")
    rock_light = rgb("#9a8a76")
    rock_deep = rgb("#3e352e")
    grass = rgb("#5f9a3c")
    grass_dark = rgb("#3f6f2a")
    rng = np.random.default_rng(SEED)
    for x in range(LEDGE_END_X):
        # The cliff top, wavy, higher toward the left.
        top = int(26 + x * 0.08 + 2 * math.sin(x / 7.0))
        for y in range(top, SCENE_H):
            n = rng.random()
            colour = rock if n > 0.22 else (rock_dark if n > 0.08 else rock_light)
            img[y, x0 + x] = colour
        # Grass on the cliff top.
        img[top, x0 + x] = grass
        img[top + 1, x0 + x] = grass_dark if x % 3 else grass
    # The ledge: a flat shelf of rock under the cave and toward the drop.
    for x in range(LEDGE_END_X):
        img[LEDGE_Y, x0 + x] = grass
        img[LEDGE_Y + 1, x0 + x] = grass_dark
        img[LEDGE_Y + 2, x0 + x] = rock_light
    # The cave: a dark arch whose mouth faces right, darker inside.
    for y in range(44, LEDGE_Y):
        for x in range(0, CAVE_MOUTH_X + 6):
            # Arch: a half-ellipse over the ledge.
            dx = (x - 18) / (CAVE_MOUTH_X - 18 + 6)
            dy = (LEDGE_Y - y) / (LEDGE_Y - 44)
            if dx * dx + dy * dy <= 1 or x < 18:
                depth = min(1.0, max(0.0, (CAVE_MOUTH_X + 6 - x) / (CAVE_MOUTH_X + 6)))
                inner = np.array(rgb("#120f0c")[:3]) * depth + np.array(rgb("#2c241d")[:3]) * (1 - depth)
                # A dithered rim of lighter rock just inside the mouth.
                if dx * dx + dy * dy > 0.82 and BAYER4[y % 4, x % 4] < 0.5:
                    inner = np.array(rock_deep[:3])
                img[y, x0 + x] = (*inner.astype(np.uint8), 255)
    # The cliff face going down from the end of the ledge, shaded.
    for y in range(LEDGE_Y + 3, SCENE_H):
        for x in range(LEDGE_END_X - 4, LEDGE_END_X):
            img[y, x0 + x] = rock_dark if (x + y) % 3 else rock_deep
    # A few tufts on the ledge, for scale.
    for x in [58, 59, 71, 86, 87, 99]:
        img[LEDGE_Y - 1, x0 + x] = grass


def cave_background(atlas):
    """CAVE_BG: sky, sun, mountains, the valley, then the cliff in front."""
    x0, _ = CAVE_BG
    scene = np.zeros((SCENE_H, ATLAS_W, 4), dtype=np.uint8)
    sky_gradient(scene, x0, SCENE_W, SCENE_H)
    sun(scene, x0, 150, 30, 6)
    mountains(scene, x0, SCENE_W, 82, 26, rgb("#9fb0cc"), SEED + 1, 0.05)
    mountains(scene, x0, SCENE_W, 92, 20, rgb("#7486a8"), SEED + 2, 0.08)
    forest(scene, x0, LEDGE_END_X - 6, SCENE_W, 94)
    cliff(scene, x0)
    atlas[0:SCENE_H] = np.where(scene[:, :, 3:4] > 0, scene, atlas[0:SCENE_H])


def sky_background(atlas):
    """SKY_BG: the same morning sky, mountains low under the flight."""
    x0, _ = SKY_BG
    scene = np.zeros((SCENE_H, ATLAS_W, 4), dtype=np.uint8)
    sky_gradient(scene, x0, SCENE_W, SCENE_H)
    sun(scene, x0, 40, 26, 6)
    mountains(scene, x0, SCENE_W, 100, 14, rgb("#9fb0cc"), SEED + 3, 0.05)
    mountains(scene, x0, SCENE_W, 106, 9, rgb("#7486a8"), SEED + 4, 0.08)
    atlas[0:SCENE_H] = np.where(scene[:, :, 3:4] > 0, scene, atlas[0:SCENE_H])


def clouds(atlas):
    """CLOUDS: puffy clouds on transparency, wrapping from right edge to left."""
    x0, y0 = CLOUDS
    white = rgb("#ffffff")
    shade = rgb("#d9e2f0")
    rng = np.random.default_rng(SEED + 9)
    strip = np.zeros((CLOUDS_H, CLOUDS_W, 4), dtype=np.uint8)
    for _ in range(9):
        cx = rng.uniform(0, CLOUDS_W)
        cy = rng.uniform(12, CLOUDS_H - 10)
        for _ in range(5):
            bx = cx + rng.uniform(-14, 14)
            by = cy + rng.uniform(-3, 3)
            r = rng.uniform(4, 8)
            for y in range(CLOUDS_H):
                for x in range(CLOUDS_W):
                    # Distance measured around the strip, so a cloud cut by
                    # the right edge continues at the left and tiling shows no seam.
                    dx = min(abs(x - bx), CLOUDS_W - abs(x - bx))
                    if dx * dx + (y - by) ** 2 * 2.2 <= r * r:
                        strip[y, x] = shade if y > by + r * 0.35 else white
    atlas[y0:y0 + CLOUDS_H, x0:x0 + CLOUDS_W] = strip


def sprites(atlas):
    """Trainer, bird and rider frames, in the rows given by the layout."""
    for i, frame in enumerate(trainer_frames()):
        place(atlas, frame, TRAINER[0] + i * TRAINER_W, TRAINER[1])
    for i, pose in enumerate(["up", "level", "down"]):
        place(atlas, bird_frame(pose), BIRD[0] + i * BIRD_W, BIRD[1])
        place(atlas, rider_frame(pose), RIDER[0] + i * RIDER_W, RIDER[1])


def main():
    """Draws the atlas and writes it, plus a preview when asked."""
    atlas = np.zeros((ATLAS_H, ATLAS_W, 4), dtype=np.uint8)
    cave_background(atlas)
    sky_background(atlas)
    clouds(atlas)
    sprites(atlas)
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    Image.fromarray(atlas, "RGBA").save(OUT, optimize=True)
    print("wrote", OUT)
    if "--previews" in sys.argv:
        out_dir = sys.argv[sys.argv.index("--previews") + 1]
        os.makedirs(out_dir, exist_ok=True)
        preview = Image.fromarray(atlas, "RGBA").resize((ATLAS_W * PREVIEW_SCALE, ATLAS_H * PREVIEW_SCALE),
                                                         Image.NEAREST)
        path = os.path.join(out_dir, "cave_escape_preview.png")
        preview.save(path)
        print("wrote", path)


if __name__ == "__main__":
    main()
