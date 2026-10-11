#!/usr/bin/env python3
"""
Draws the GUI textures: the safari landscape behind the shop panel, the
wider watering-hole landscape behind the Safari Board screens, and the
Safari points icon used as a font glyph.

Why drawn by code: the pictures are original pixel art in Minecraft's style,
free of rights, and can be redrawn (palette, size) by editing this file.

The landscape comes in three scenes (SCENES: morning, dusk, night, each with
its own creatures); the shop shows the one matching the time of day in game
(SafariLandscapes.forTime). The board has its own scene in the same three
lights (BOARD_*). Every creature is an original design
made for this mod: no Pokémon is drawn, species being protected characters.

Usage (from the repository root; needs Pillow and numpy):
    python3 tools/gui_assets.py                  # writes the shipped textures (every scene)
    python3 tools/gui_assets.py --previews DIR   # every scene, enlarged, into DIR

Outputs (overwritten):
    common/src/main/resources/assets/cobblemon_safari/textures/gui/shop_background_<scene>.png
    common/src/main/resources/assets/cobblemon_safari/textures/gui/board_background_<scene>.png
    common/src/main/resources/assets/cobblemon_safari/textures/font/safari_points.png
Deterministic: the same script draws the same pixels.
"""

import math
import os
import sys

import numpy as np
from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS = os.path.join(ROOT, "common", "src", "main", "resources", "assets", "cobblemon_safari", "textures")

# Background size in texels. Must match SafariShopScreen.PANEL_WIDTH/HEIGHT:
# the panel draws it 1:1 in GUI pixels, so one texel is one GUI pixel, the
# same grain as vanilla GUIs. Another size would be stretched and blur.
WIDTH, HEIGHT = 300, 232

# Fixed seed: the same landscape on every run.
SEED = 7341

# Scale of the enlarged previews (--previews).
PREVIEW_SCALE = 3

# Number of flat colour bands in the upper sky, and the share of each band
# (at its lower edge) that is dithered into the next. More bands look
# smoother and less pixel-art; a wider edge looks noisier.
SKY_BANDS = 9
DITHER_EDGE = 0.3

# Ordered-dither matrix: blends two colours in a pixel-art way instead of a
# smooth gradient, which would look out of place next to Minecraft's GUIs.
BAYER4 = np.array([[0, 8, 2, 10], [12, 4, 14, 6], [3, 11, 1, 9], [15, 7, 13, 5]]) / 16.0

# Horizon height, as a share of the picture from the top.
HORIZON = 0.58


def lerp(a, b, t):
    """Colour between a and b."""
    return tuple(int(round(a[i] + (b[i] - a[i]) * t)) for i in range(3))


def shade(colour, factor):
    """A colour darkened (factor below 1), for creatures seen at night."""
    return tuple(max(0, min(255, int(c * factor))) for c in colour)


def dithered(c0, c1, t, x, y, steps=6):
    """
    Gradient between two colours quantised to flat bands; only a thin strip
    at each band edge is dithered, so the sky reads as clean stripes rather
    than noise.
    """
    level = t * steps
    base = math.floor(level)
    frac = level - base
    if frac > 1.0 - DITHER_EDGE and (frac - (1.0 - DITHER_EDGE)) / DITHER_EDGE > BAYER4[y % 4, x % 4]:
        base += 1
    return lerp(c0, c1, min(1.0, max(0.0, base / steps)))


def ridge(rng, width, base, amplitude, octaves):
    """Height profile of a mountain or hill line: summed sine waves with random phases."""
    xs = np.arange(width)
    h = np.full(width, float(base))
    for o in range(octaves):
        freq = (o + 1) * 2 * math.pi / width * rng.uniform(1.5, 3.5)
        h += amplitude / (o + 1) * np.sin(xs * freq + rng.uniform(0, 2 * math.pi))
    return np.round(h).astype(int)


def put(img, x, y, colour):
    """Sets one pixel, ignoring positions outside the picture."""
    if 0 <= x < img.width and 0 <= y < img.height:
        img.load()[x, y] = colour


def acacia(img, x0, ground, size, rng, pal):
    """Silhouette of an acacia: leaning trunk forking into two branches, two flat crowns."""
    trunk_h = int(size * 0.7)
    width = 2 if size >= 10 else 1
    for dy in range(trunk_h):
        lean = int(dy * 0.2)
        for w in range(width):
            put(img, x0 + lean + w, ground - dy, pal["tree"])
    fork_x, fork_y = x0 + int(trunk_h * 0.2), ground - trunk_h
    crowns = []
    for direction in (-1, 1):
        length = int(size * 0.45)
        for d in range(length):
            put(img, fork_x + direction * (d // 2 + 1), fork_y - d, pal["tree"])
        crowns.append((fork_x + direction * (length // 2 + 1), fork_y - length))
    for cx, cy in crowns:
        reach = int(size * 0.9) + int(rng.integers(0, 2))
        for layer in range(3):
            r = reach - layer * 2
            for x in range(cx - r, cx + r + 1):
                put(img, x, cy - layer, pal["tree_leaf"] if (x * 7 + layer) % 4 else pal["tree"])
        for x in range(cx - reach + 2, cx + reach - 1):
            put(img, x, cy + 1, pal["tree"])


def sprite(img, rows, colours, x0, y0, scale=1, mirror=False, factor=1.0, glow=""):
    """
    Draws an ASCII sprite with its top-left corner at (x0, y0). Colours are
    darkened by factor, except the characters listed in glow (eyes, glowing
    stripes) which keep their colour at night.
    """
    px = img.load()
    width = len(rows[0])
    for r, row in enumerate(rows):
        for c, ch in enumerate(row):
            if ch == ".":
                continue
            colour = colours[ch] if isinstance(colours, dict) else colours
            if ch not in glow:
                colour = shade(colour, factor)
            cx = (width - 1 - c) if mirror else c
            for dy in range(scale):
                for dx in range(scale):
                    x, y = x0 + cx * scale + dx, y0 + r * scale + dy
                    if 0 <= x < img.width and 0 <= y < img.height:
                        px[x, y] = colour


# ---------------------------------------------------------------------------
# Scenes: palette and cast of each one
# ---------------------------------------------------------------------------

PALETTES = {
    "dusk": {
        "sky": [(38, 70, 94), (226, 132, 74), (250, 205, 120)],
        "sun": (255, 236, 170), "sun_rim": (255, 214, 130), "moon": False, "stars": False,
        "mountain_far": (150, 104, 98), "mountain_near": (104, 80, 88),
        "plain": (170, 150, 70), "plain_dark": (140, 122, 58),
        "tree": (44, 36, 34), "tree_leaf": (52, 58, 36),
        "grass_back": (96, 120, 46), "grass_front": (62, 92, 36), "grass_tip": (190, 186, 84),
        "light": 1.0,
    },
    "morning": {
        "sky": [(96, 160, 220), (160, 206, 236), (226, 240, 236)],
        "sun": (255, 248, 210), "sun_rim": (255, 240, 180), "moon": False, "stars": False,
        "mountain_far": (150, 176, 196), "mountain_near": (112, 146, 150),
        "plain": (150, 182, 82), "plain_dark": (124, 160, 70),
        "tree": (70, 56, 44), "tree_leaf": (66, 122, 56),
        "grass_back": (92, 150, 58), "grass_front": (58, 120, 44), "grass_tip": (200, 220, 110),
        "light": 1.0,
    },
    "night": {
        "sky": [(10, 14, 36), (30, 40, 84), (64, 70, 120)],
        "sun": (236, 240, 255), "sun_rim": (180, 196, 236), "moon": True, "stars": True,
        "mountain_far": (46, 52, 90), "mountain_near": (32, 36, 66),
        "plain": (52, 70, 76), "plain_dark": (40, 56, 64),
        "tree": (16, 18, 30), "tree_leaf": (22, 30, 40),
        "grass_back": (36, 62, 56), "grass_front": (24, 46, 42), "grass_tip": (90, 130, 110),
        "light": 0.55,
    },
}

# Original creatures, one character per pixel ("." is transparent), drawn
# facing right. Each is a design of this mod, deliberately unlike any
# Pokémon: a horned grazer, a walking grass tuft, a dune beetle, a kite-like
# flier and a floating sky jelly.
CALFLING = [
    ".........tt....",
    "........tttt...",
    "...CCCC.CCCCC..",
    "..CCCCCCCCeCCn.",
    ".CCbbCCCCCCCC..",
    ".CCbbbCCCCCC...",
    ".CCCCCCCbbCC...",
    "..CCCCCCCCCC...",
    "..LL.LL..LL.L..",
    "..kk.kk..kk.k..",
]
CALFLING_COLOURS = {"t": (64, 170, 160), "C": (236, 222, 190), "b": (150, 100, 64), "e": (30, 28, 34),
                    "n": (90, 60, 50), "L": (200, 184, 150), "k": (80, 60, 46)}

TUFT = [
    "....r....",
    "...rYr...",
    "..g.r.g..",
    ".gGgGgGg.",
    ".GGGGGGG.",
    "GGeGGGeGG",
    "GGGGGGGGG",
    ".GGGmGGG.",
    ".gGGGGGg.",
    "..f...f..",
]
TUFT_COLOURS = {"r": (236, 120, 150), "Y": (255, 220, 90), "g": (70, 130, 50), "G": (110, 176, 70),
                "e": (24, 30, 24), "m": (60, 100, 40), "f": (226, 150, 70)}

BEETLE = [
    "....SSSSSS.....",
    "..SSSSSSSSSS...",
    ".SSzzzzzzzzSS..",
    "SSSSSSSSSSSSSdd",
    "SSSSSSSSSSSSdde",
    ".SSSSSSSSSSSS..",
    "..l.l.l..l.l...",
]
BEETLE_COLOURS = {"S": (210, 170, 110), "z": (80, 220, 210), "d": (90, 66, 46), "e": (250, 250, 210),
                  "l": (90, 66, 46)}

KITEBIRD = [
    "....R....",
    "...RRR...",
    "..RRYRR..",
    ".RRRYRRR.",
    "..RRYRR..",
    "...RRR...",
    "....R....",
    "....y....",
    "...y.....",
    "....y....",
]
KITEBIRD_COLOURS = {"R": (210, 70, 60), "Y": (255, 214, 90), "y": (255, 214, 90)}

JELLY = [
    "..JJJJJ..",
    ".JhJJJJJ.",
    "JJJJJJJJJ",
    "JJeJJJeJJ",
    "JJJJcJJJJ",
    ".JJJJJJJ.",
    ".j.j.j.j.",
    "j..j..j.j",
]
JELLY_COLOURS = {"J": (150, 230, 200), "h": (230, 255, 245), "e": (30, 50, 60), "c": (240, 150, 170),
                 "j": (120, 200, 180)}

CREATURES = {
    "calfling": (CALFLING, CALFLING_COLOURS, "e"),
    "tuft": (TUFT, TUFT_COLOURS, "e"),
    "beetle": (BEETLE, BEETLE_COLOURS, "ze"),
    "kitebird": (KITEBIRD, KITEBIRD_COLOURS, ""),
    "jelly": (JELLY, JELLY_COLOURS, "Jhecj"),
}

# The trainer: cap, jacket, backpack, one arm pointing ahead, as in a
# "go!" pose. Drawn facing right, mirrored to face the plain on the left.
TRAINER = [
    ".....CCCC.....",
    "....CCCCCCc...",
    "....hhhhhhcc..",
    "....HSSSSS....",
    "....HSSSES....",
    ".....SSSS.....",
    "...JJJJJJJ....",
    "..PJJJJJJJJJSS",
    "..PJJJJJJJJJSS",
    "..PJJJJJJ.....",
    "..PJJJJJJ.....",
    "...JJJJJJ.....",
    "...LLLLLL.....",
    "...LLL.LLL....",
    "...LLL..LL....",
    "...LL...LL....",
    "..OOO...OOO...",
]
TRAINER_COLOURS = {
    "C": (70, 140, 80), "c": (44, 96, 52), "h": (44, 96, 52), "H": (92, 60, 36), "S": (230, 186, 146),
    "E": (30, 30, 40), "J": (52, 74, 138), "P": (214, 172, 64), "L": (64, 62, 74), "O": (62, 42, 30),
}

# The trainer and his companions stand in the lower right: short tabs leave
# empty slots there, so that is where the landscape shows most. Foreground
# figures are drawn at twice the texel size so they read as the subject.
TRAINER_POS = (238, 166)
TRAINER_SCALE = 2

# Each scene: palette, then the cast as (creature, x, y, scale, mirror).
# Mirrored creatures face left (toward the plain), the others face right.
SCENES = {
    "dusk": {
        "palette": "dusk",
        "cast": [
            ("calfling", 202, 178, 2, True),
            ("tuft", 150, 168, 1, False), ("tuft", 132, 171, 1, False),
            ("beetle", 96, 160, 1, False),
            ("calfling", 64, 150, 1, False), ("calfling", 44, 151, 1, False),
            ("kitebird", 222, 26, 1, False), ("kitebird", 252, 34, 1, False),
        ],
    },
    "morning": {
        "palette": "morning",
        "cast": [
            ("tuft", 208, 180, 2, True),
            ("calfling", 150, 154, 1, False), ("calfling", 128, 156, 1, True),
            ("calfling", 106, 153, 1, False), ("calfling", 86, 150, 1, False),
            ("jelly", 176, 96, 1, False), ("jelly", 214, 80, 1, False), ("jelly", 60, 104, 1, False),
            ("kitebird", 250, 30, 1, False),
        ],
    },
    "night": {
        "palette": "night",
        "cast": [
            ("beetle", 196, 186, 2, True),
            ("jelly", 160, 128, 2, False), ("jelly", 112, 116, 1, False), ("jelly", 84, 132, 1, False),
            ("jelly", 220, 104, 1, False),
            ("tuft", 140, 172, 1, False),
        ],
    },
}

def background(scene_name):
    """A landscape: sky and sun (or moon and stars), mountains, plain with acacias, cast, grass in front."""
    scene = SCENES[scene_name]
    pal = PALETTES[scene["palette"]]
    rng = np.random.default_rng(SEED)
    img = Image.new("RGB", (WIDTH, HEIGHT))
    px = img.load()
    horizon = int(HEIGHT * HORIZON)
    top, mid, low = pal["sky"]
    for y in range(horizon):
        t = y / horizon
        for x in range(WIDTH):
            px[x, y] = dithered(top, mid, t / 0.7, x, y, SKY_BANDS) if t < 0.7 \
                else dithered(mid, low, (t - 0.7) / 0.3, x, y, SKY_BANDS // 2)

    if pal["stars"]:
        star_rng = np.random.default_rng(SEED + 1)
        for _ in range(90):
            x, y = int(star_rng.integers(0, WIDTH)), int(star_rng.integers(22, horizon - 30))
            px[x, y] = (255, 255, 230) if star_rng.random() < 0.3 else (170, 180, 220)

    # Sun low on the horizon with a ring, or a crescent moon high up.
    if pal["moon"]:
        sx, sy, sr = int(WIDTH * 0.8), 52, 13
    else:
        sx, sy, sr = int(WIDTH * 0.72), horizon - 18, 22
    for y in range(max(0, sy - sr - 3), min(HEIGHT, sy + sr + 4)):
        for x in range(max(0, sx - sr - 3), min(WIDTH, sx + sr + 4)):
            d = math.hypot(x - sx, y - sy)
            if pal["moon"] and math.hypot(x - sx - 6, y - sy + 3) <= sr - 1:
                continue
            if d <= sr:
                px[x, y] = pal["sun"]
            elif d <= sr + 2 and (x + y) % 2 == 0:
                px[x, y] = pal["sun_rim"]

    # Mountains: far and near lines, the near one darker.
    for profile, colour in ((ridge(rng, WIDTH, horizon - 22, 10, 3), pal["mountain_far"]),
                            (ridge(rng, WIDTH, horizon - 8, 7, 4), pal["mountain_near"])):
        for x in range(WIDTH):
            for y in range(max(0, profile[x]), horizon + 1):
                px[x, y] = colour

    # The plain, banded toward the viewer.
    for y in range(horizon, HEIGHT):
        t = (y - horizon) / (HEIGHT - horizon)
        for x in range(WIDTH):
            px[x, y] = dithered(pal["plain_dark"], pal["plain"], t, x, y, 3)

    # Acacias, smaller toward the horizon.
    for x0, size in ((30, 18), (98, 9), (150, 6), (205, 13), (262, 8)):
        acacia(img, x0, horizon + size // 2 + 2, size, rng, pal)

    # Back grass, then the cast and the trainer, then the front grass over their feet.
    grass(img, rng, pal, 0, pal["grass_back"], HEIGHT - 26, 14)
    for name, x, y, scale, mirror in scene["cast"]:
        rows, colours, glow = CREATURES[name]
        sprite(img, rows, colours, x, y, scale, mirror, pal["light"], glow)
    draw_trainer(img, pal)
    grass(img, rng, pal, 1, pal["grass_front"], HEIGHT - 1, 26)
    if pal["stars"]:
        fireflies(img)
    return img


def grass(img, rng, pal, layer, colour, base, height):
    """One layer of tall grass blades with lighter tips, solid below its base line."""
    px = img.load()
    for x in range(WIDTH):
        blade = int(height * (0.55 + 0.45 * abs(math.sin(x * 0.7 + layer * 1.3 + rng.uniform(0, 0.6)))))
        if x % 2 == layer % 2:
            blade = int(blade * 0.7)
        lean = 1 if (x // 3) % 2 else 0
        for dy in range(blade):
            y = base - dy
            xx = x + (lean if dy > blade * 0.6 else 0)
            if 0 <= xx < WIDTH and 0 <= y < HEIGHT:
                px[xx, y] = pal["grass_tip"] if dy >= blade - 2 else colour
        for y in range(base, HEIGHT):
            px[x, y] = colour


def fireflies(img):
    """Glowing specks over the night grass."""
    rng = np.random.default_rng(SEED + 2)
    for _ in range(26):
        x, y = int(rng.integers(0, WIDTH)), int(rng.integers(140, 215))
        put(img, x, y, (230, 255, 150))
        if rng.random() < 0.5:
            put(img, x + 1, y, (150, 190, 90))


def draw_trainer(img, pal):
    """The trainer, standing in a clump of tall grass up to his boots."""
    x0, y0 = TRAINER_POS
    sprite(img, TRAINER, TRAINER_COLOURS, x0, y0, TRAINER_SCALE, mirror=True, factor=pal["light"] ** 0.5)
    px = img.load()
    ground = y0 + len(TRAINER) * TRAINER_SCALE
    for x in range(x0 - 6, x0 + len(TRAINER[0]) * TRAINER_SCALE + 6):
        blade = 8 + (x * 7) % 6
        for dy in range(blade):
            y = ground - dy
            if 0 <= x < WIDTH and 0 <= y < HEIGHT and (x + dy // 3) % 2 == 0:
                px[x, y] = pal["grass_tip"] if dy >= blade - 2 else pal["grass_back"]



# ---------------------------------------------------------------------------
# Safari Board landscape: a different, wider scene than the shop's
# ---------------------------------------------------------------------------

# Board background size in texels. Boards are wide (2:1 for the usual 4 x 2
# screen); the renderer crops the picture to each screen's shape, so other
# sizes work, but a very different ratio crops away more of it. Must match
# SafariLandscapes.BOARD (width, height, horizon).
BOARD_WIDTH, BOARD_HEIGHT = 384, 192
BOARD_HORIZON = 0.52

# What the board scene adds to each shop palette: the watering hole, the
# volcano's smoke or glow, and the rest house on its hill.
BOARD_EXTRAS = {
    "dusk": {
        "water": (214, 140, 96), "water_light": (252, 210, 140), "water_dark": (130, 92, 90),
        "volcano": (120, 86, 84), "volcano_dark": (90, 66, 70), "smoke": (196, 150, 136), "lava": None,
        "mesa": (176, 98, 70), "mesa_dark": (136, 76, 62), "roof": (176, 64, 52), "wall": (226, 196, 160),
        "window": (255, 214, 120), "reed": (70, 82, 40),
    },
    "morning": {
        "water": (120, 186, 220), "water_light": (220, 240, 246), "water_dark": (70, 130, 170),
        "volcano": (128, 150, 166), "volcano_dark": (100, 120, 138), "smoke": (236, 240, 244), "lava": None,
        "mesa": (200, 140, 96), "mesa_dark": (170, 110, 80), "roof": (206, 70, 60), "wall": (244, 236, 222),
        "window": (110, 160, 200), "reed": (70, 120, 50),
    },
    "night": {
        "water": (36, 50, 96), "water_light": (190, 200, 240), "water_dark": (20, 28, 60),
        "volcano": (36, 40, 72), "volcano_dark": (26, 30, 56), "smoke": (56, 54, 84), "lava": (255, 120, 50),
        "mesa": (52, 44, 70), "mesa_dark": (40, 34, 58), "roof": (70, 36, 48), "wall": (90, 92, 120),
        "window": (255, 210, 110), "reed": (22, 40, 40),
    },
}

# An original long-legged wader standing in the shallows: lilac body, leaf
# crest, beak dipped toward the water. A design of this mod, like the rest.
WADER = [
    "....gg....",
    "...gLL....",
    "...LLLe...",
    "....LLLbb.",
    "....LL..b.",
    "...LLL....",
    ".LLLLLL...",
    "LwLLLLLL..",
    ".LLLLLL...",
    "...l.l....",
    "...l.l....",
    "...l..l...",
]
WADER_COLOURS = {"g": (90, 170, 80), "L": (190, 160, 220), "w": (150, 120, 190), "e": (30, 28, 40),
                 "b": (240, 180, 70), "l": (120, 90, 80)}
CREATURES["wader"] = (WADER, WADER_COLOURS, "e")

# The cast of the board scene, per palette: (creature, x, y, scale, mirror).
# Grazers drink at the water's edge, waders stand in it, fliers and sky
# jellies keep to the upper half, where the board's title band hides less.
BOARD_CASTS = {
    "dusk": [
        ("calfling", 118, 118, 1, False), ("calfling", 134, 119, 1, False), ("calfling", 282, 118, 1, True),
        ("wader", 196, 104, 1, False), ("wader", 226, 106, 1, True),
        ("tuft", 64, 150, 2, False), ("beetle", 312, 160, 2, True),
        ("kitebird", 250, 30, 1, False), ("kitebird", 276, 22, 1, False), ("kitebird", 300, 36, 1, False),
    ],
    "morning": [
        ("calfling", 112, 118, 1, False), ("calfling", 128, 119, 1, True), ("calfling", 290, 118, 1, True),
        ("calfling", 306, 117, 1, True), ("calfling", 60, 158, 2, False),
        ("wader", 186, 104, 1, False), ("wader", 214, 106, 1, False), ("wader", 244, 105, 1, True),
        ("tuft", 330, 148, 2, True), ("jelly", 80, 44, 1, False), ("jelly", 104, 30, 1, False),
        ("kitebird", 290, 26, 1, False),
    ],
    "night": [
        ("wader", 206, 106, 1, True),
        ("jelly", 150, 70, 1, False), ("jelly", 182, 54, 2, False), ("jelly", 246, 66, 1, False),
        ("jelly", 300, 48, 1, False), ("jelly", 90, 60, 1, False),
        ("beetle", 70, 160, 2, False), ("tuft", 300, 150, 1, True),
    ],
}


def board_background(scene_name):
    """
    The board's scene: a watering hole at the foot of the island's volcano,
    badland mesas to the east, a rest house on its hill, creatures drinking
    and wading, a big acacia framing the left edge. Same three lights as the
    shop (palette per scene).
    """
    pal = dict(PALETTES[scene_name], **BOARD_EXTRAS[scene_name])
    w, h = BOARD_WIDTH, BOARD_HEIGHT
    rng = np.random.default_rng(SEED + 10)
    img = Image.new("RGB", (w, h))
    px = img.load()
    horizon = int(h * BOARD_HORIZON)

    top, mid, low = pal["sky"]
    for y in range(horizon):
        t = y / horizon
        for x in range(w):
            px[x, y] = dithered(top, mid, t / 0.7, x, y, SKY_BANDS) if t < 0.7 \
                else dithered(mid, low, (t - 0.7) / 0.3, x, y, SKY_BANDS // 2)
    if pal["stars"]:
        star_rng = np.random.default_rng(SEED + 11)
        for _ in range(120):
            x, y = int(star_rng.integers(0, w)), int(star_rng.integers(4, horizon - 24))
            px[x, y] = (255, 255, 230) if star_rng.random() < 0.3 else (170, 180, 220)

    # Sun or moon to the east, over the mesas.
    sx, sy, sr = (int(w * 0.86), 30, 11) if pal["moon"] else (int(w * 0.80), horizon - 22, 18)
    for y in range(max(0, sy - sr - 3), min(h, sy + sr + 4)):
        for x in range(max(0, sx - sr - 3), min(w, sx + sr + 4)):
            d = math.hypot(x - sx, y - sy)
            if pal["moon"] and math.hypot(x - sx - 5, y - sy + 3) <= sr - 1:
                continue
            if d <= sr:
                px[x, y] = pal["sun"]
            elif d <= sr + 2 and (x + y) % 2 == 0:
                px[x, y] = pal["sun_rim"]

    # Far hills all along the horizon, then the volcano and the mesas on them.
    hills = ridge(rng, w, horizon - 10, 5, 3)
    for x in range(w):
        for y in range(max(0, hills[x]), horizon + 1):
            px[x, y] = pal["mountain_far"]
    draw_volcano(img, pal, 118, horizon, 62, 58)
    for x0, x1, height in ((292, 336, 26), (340, 372, 18), (262, 284, 14)):
        draw_mesa(img, pal, x0, x1, horizon, height)
    near = ridge(rng, w, horizon - 3, 3, 4)
    for x in range(w):
        for y in range(max(0, near[x]), horizon + 1):
            px[x, y] = pal["mountain_near"]

    # The plain.
    for y in range(horizon, h):
        t = (y - horizon) / (h - horizon)
        for x in range(w):
            px[x, y] = dithered(pal["plain_dark"], pal["plain"], t, x, y, 3)

    # Rest house on a low hill, between the volcano and the mesas.
    draw_hill(img, pal, 252, horizon + 2, 26, 7)
    draw_house(img, pal, 244, horizon - 4)

    # The watering hole: an ellipse, sky reflections in streaks, darker rim.
    cx, cy, rx, ry = 212, horizon + 22, 92, 12
    for y in range(cy - ry - 1, cy + ry + 2):
        for x in range(cx - rx - 2, cx + rx + 3):
            d = ((x - cx) / rx) ** 2 + ((y - cy) / ry) ** 2
            if d <= 1.0:
                colour = pal["water"]
                if (y - cy + ry) % 4 == 0 and (x * 3 + y * 5) % 11 < 6:
                    colour = pal["water_light"]
                if d > 0.82:
                    colour = pal["water_dark"]
                put(img, x, y, colour)
    reflect_x = sx if not pal["moon"] else sx - 40
    for y in range(cy - ry + 2, cy + ry - 1, 2):
        for dx in range(-6 + (y % 3), 7 - (y % 3)):
            x = min(cx + rx - 16, max(cx - rx + 16, reflect_x)) + dx
            if ((x - cx) / rx) ** 2 + ((y - cy) / ry) ** 2 < 0.8:
                put(img, x, y, pal["sun_rim"])
    for x in list(range(cx - rx - 2, cx - rx + 14, 2)) + list(range(cx + rx - 12, cx + rx + 3, 2)):
        tall = 4 + (x * 5) % 5
        base = cy + int(ry * 0.5)
        for dy in range(tall):
            put(img, x, base - dy, pal["reed"])

    # Acacias in the distance, the big one framing the left edge.
    for x0, size in ((172, 7), (318, 8), (356, 6), (36, 9)):
        acacia(img, x0, horizon + size // 2 + 2, size, rng, pal)

    for name, x, y, scale, mirror in BOARD_CASTS[scene_name]:
        rows, colours, glow = CREATURES[name]
        sprite(img, rows, colours, x, y, scale, mirror, pal["light"], glow)

    draw_meadow(img, pal)
    big_acacia(img, pal, 14, h - 12)
    board_grass(img, pal, rng)
    if pal["stars"]:
        frng = np.random.default_rng(SEED + 12)
        for _ in range(30):
            put(img, int(frng.integers(0, w)), int(frng.integers(130, h - 4)), (230, 255, 150))
    return img


def draw_volcano(img, pal, cx, base, half_width, height):
    """A cone with a notched crater, a lit and a shaded flank, smoke or a lava glow above."""
    crater = 7
    for y in range(base - height, base + 1):
        t = (y - (base - height)) / height
        half = int(crater + (half_width - crater) * t ** 0.8)
        for x in range(cx - half, cx + half + 1):
            colour = pal["volcano"] if x < cx + half // 4 else pal["volcano_dark"]
            # Lava streaks or ridges down the flank.
            if (x - cx + y // 2) % 9 == 0 and t > 0.12:
                colour = pal["lava"] if pal["lava"] and t < 0.55 else pal["volcano_dark"]
            put(img, x, y, colour)
    top = base - height
    for x in range(cx - crater + 2, cx + crater - 1):
        put(img, x, top, pal["lava"] or pal["volcano_dark"])
    # Smoke: a few round puffs drifting east, lighter higher up.
    for i, (dx, dy, r) in enumerate(((2, -6, 4), (7, -13, 5), (14, -20, 6), (23, -26, 6))):
        for y in range(top + dy - r, top + dy + r + 1):
            for x in range(cx + dx - r, cx + dx + r + 1):
                d = math.hypot(x - cx - dx, y - top - dy)
                if d <= r:
                    # Shaded on the lower right of each puff, lit on the upper left.
                    lit = (x - cx - dx) + (y - top - dy) < -r * 0.3
                    put(img, x, y, pal["smoke"] if lit or d < r * 0.5 else shade(pal["smoke"], 0.85))


def draw_mesa(img, pal, x0, x1, base, height):
    """A flat-topped badland butte with banded strata and steep sides."""
    for y in range(base - height, base + 1):
        slope = (y - (base - height)) // 3
        for x in range(x0 - slope, x1 + slope + 1):
            colour = pal["mesa_dark"] if (y // 3) % 2 or x > x1 - 2 else pal["mesa"]
            put(img, x, y, colour)


def draw_hill(img, pal, cx, base, half_width, height):
    """A low grassy rise, a little lighter than the plain."""
    for x in range(cx - half_width, cx + half_width + 1):
        t = 1 - ((x - cx) / half_width) ** 2
        for dy in range(int(height * t) + 1):
            put(img, x, base - dy, pal["plain"])


def draw_house(img, pal, x0, ground):
    """A small rest house: pale walls, a red pitched roof, lit windows and a door."""
    width, wall = 18, 7
    for y in range(ground - wall, ground + 1):
        for x in range(x0, x0 + width):
            put(img, x, y, pal["wall"])
    for i in range(6):
        for x in range(x0 - 2 + i, x0 + width + 2 - i):
            put(img, x, ground - wall - 1 - i, pal["roof"])
    for wx in (x0 + 3, x0 + 12):
        for y in range(ground - 5, ground - 2):
            put(img, wx, y, pal["window"])
            put(img, wx + 1, y, pal["window"])
    for y in range(ground - 4, ground + 1):
        put(img, x0 + 8, y, pal["tree"])
        put(img, x0 + 9, y, pal["tree"])


# Foreground flower patches and rocks on the plain, as (x, y): they fill the
# lower half, which the board's cells mostly cover but show between them.
MEADOW_FLOWERS = [(96, 150), (150, 160), (176, 146), (232, 168), (268, 152), (350, 136), (118, 172), (300, 172)]
MEADOW_ROCKS = [(88, 132, 7), (334, 126, 5), (196, 176, 9)]
FLOWER_COLOURS = [(240, 220, 90), (236, 120, 150), (240, 240, 230), (170, 130, 220)]


def draw_meadow(img, pal):
    """Clumps of wildflowers and a few boulders scattered over the near plain."""
    light = pal["light"]
    for i, (x0, y0) in enumerate(MEADOW_FLOWERS):
        colour = shade(FLOWER_COLOURS[i % len(FLOWER_COLOURS)], light)
        for j in range(7):
            x, y = x0 + (j * 5) % 11 - 5, y0 + (j * 3) % 5 - 2
            put(img, x, y + 1, pal["grass_front"])
            put(img, x, y, colour)
    rock = shade((150, 146, 136), light)
    rock_dark = shade((104, 100, 96), light)
    rock_light = shade((196, 192, 180), light)
    for x0, y0, size in MEADOW_ROCKS:
        for y in range(y0 - size // 2, y0 + 1):
            half = int(size * (1 - ((y0 - y) / (size / 2 + 1)) ** 2))
            for x in range(x0 - half, x0 + half + 1):
                colour = rock_light if y < y0 - size // 3 and x < x0 else rock_dark if x > x0 + half // 2 else rock
                put(img, x, y, colour)


def big_acacia(img, pal, x0, ground):
    """A large foreground acacia whose crown spreads over the upper left corner."""
    trunk = 110
    for dy in range(trunk):
        lean = int(dy * 0.16)
        thickness = 6 if dy < 20 else 5 if dy < 60 else 4
        for wdx in range(thickness):
            put(img, x0 + lean + wdx, ground - dy, pal["tree"])
    fork_x, fork_y = x0 + int(trunk * 0.16) + 2, ground - trunk
    for direction, length in ((-1, 18), (1, 34)):
        for d in range(length):
            for t in range(3):
                put(img, fork_x + direction * d + t, fork_y - d // 2, pal["tree"])
    leaf_light = lerp(pal["tree_leaf"], pal["grass_tip"], 0.25)
    for cx, cy, reach in ((fork_x - 18, fork_y - 10, 26), (fork_x + 34, fork_y - 18, 40),
                          (fork_x + 8, fork_y - 24, 28)):
        for layer in range(7):
            r = reach - layer * 3
            for x in range(cx - r, cx + r + 1):
                colour = pal["tree_leaf"] if (x * 7 + layer * 3) % 5 else pal["tree"]
                if layer >= 5 and (x + layer) % 3:
                    colour = leaf_light
                put(img, x, cy - layer, colour)
        for x in range(cx - reach + 3, cx + reach - 2):
            put(img, x, cy + 1, pal["tree"])


def board_grass(img, pal, rng):
    """Two bands of tall grass along the bottom, back and front."""
    px = img.load()
    w, h = img.size
    for layer, colour, base, height in ((0, pal["grass_back"], h - 10, 12), (1, pal["grass_front"], h - 1, 16)):
        for x in range(w):
            blade = int(height * (0.5 + 0.5 * abs(math.sin(x * 0.63 + layer * 1.7 + rng.uniform(0, 0.6)))))
            if x % 2 == layer % 2:
                blade = int(blade * 0.7)
            lean = 1 if (x // 3) % 2 else 0
            for dy in range(blade):
                y = base - dy
                xx = x + (lean if dy > blade * 0.6 else 0)
                if 0 <= xx < w and 0 <= y < h:
                    px[xx, y] = pal["grass_tip"] if dy >= blade - 2 else colour
            for y in range(base, h):
                px[x, y] = colour


# Safari points icon: a 16x16 gold coin with a green leaf, drawn as a font
# glyph at 8 GUI pixels high (16 texels for crisp rendering at GUI scale 2+).
ICON_SIZE = 16
COIN = (232, 182, 58)
COIN_LIGHT = (255, 226, 120)
COIN_DARK = (156, 106, 30)
OUTLINE = (70, 44, 14)
LEAF = (70, 160, 70)
LEAF_DARK = (36, 100, 44)
LEAF_LIGHT = (140, 210, 110)


def points_icon():
    """The Safari points glyph: gold coin, dark outline, a leaf stamped in the middle."""
    img = Image.new("RGBA", (ICON_SIZE, ICON_SIZE), (0, 0, 0, 0))
    px = img.load()
    c = (ICON_SIZE - 1) / 2
    for y in range(ICON_SIZE):
        for x in range(ICON_SIZE):
            d = math.hypot(x - c, y - c)
            if d > 7.6:
                continue
            if d > 6.6:
                px[x, y] = OUTLINE + (255,)
            elif d > 5.6:
                # Rim: lit from the top left.
                px[x, y] = (COIN_LIGHT if (x - c) + (y - c) < 0 else COIN_DARK) + (255,)
            else:
                px[x, y] = COIN + (255,)
    # Leaf: an almond shape along the diagonal, with a vein.
    for y in range(ICON_SIZE):
        for x in range(ICON_SIZE):
            u = ((x - c) - (y - c)) / math.sqrt(2)   # along the leaf, bottom left to top right
            v = ((x - c) + (y - c)) / math.sqrt(2)   # across it, negative toward the top left
            if abs(u) <= 4.2 and abs(v) <= 2.3 * (1 - (u / 4.4) ** 2):
                px[x, y] = (LEAF_LIGHT if v < -0.9 else LEAF_DARK if v > 0.9 else LEAF) + (255,)
            if abs(v) < 0.5 and abs(u) <= 3.8:
                px[x, y] = LEAF_DARK + (255,)
    return img


def main():
    if len(sys.argv) == 3 and sys.argv[1] == "--previews":
        out = sys.argv[2]
        os.makedirs(out, exist_ok=True)
        for name in SCENES:
            preview = background(name).resize((WIDTH * PREVIEW_SCALE, HEIGHT * PREVIEW_SCALE), Image.NEAREST)
            preview.save(os.path.join(out, f"shop_background_{name}.png"))
            board = board_background(name)
            board.resize((BOARD_WIDTH * PREVIEW_SCALE, BOARD_HEIGHT * PREVIEW_SCALE), Image.NEAREST) \
                .save(os.path.join(out, f"board_background_{name}.png"))
        print(f"Wrote {2 * len(SCENES)} previews to {out}")
        return
    os.makedirs(os.path.join(ASSETS, "gui"), exist_ok=True)
    os.makedirs(os.path.join(ASSETS, "font"), exist_ok=True)
    # File names are read by SafariShopScreen: renaming a scene renames its texture.
    for name in SCENES:
        background(name).save(os.path.join(ASSETS, "gui", f"shop_background_{name}.png"), optimize=True)
        # Read by SafariLandscapes.BOARD.
        board_background(name).save(os.path.join(ASSETS, "gui", f"board_background_{name}.png"), optimize=True)
    points_icon().save(os.path.join(ASSETS, "font", "safari_points.png"), optimize=True)
    print(f"Wrote {len(SCENES)} shop backgrounds, {len(SCENES)} board backgrounds and safari_points.png")


if __name__ == "__main__":
    main()
