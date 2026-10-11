#!/usr/bin/env python3
"""
Draws the shop's GUI textures: the safari landscape behind the shop panel,
and the Safari points icon used as a font glyph.

Why drawn by code: the pictures are original pixel art in Minecraft's style,
free of rights, and can be redrawn (palette, size) by editing this file.

Usage (from the repository root; needs Pillow and numpy):
    python3 tools/gui_assets.py

Outputs (overwritten):
    common/src/main/resources/assets/cobblemon_safari/textures/gui/shop_background.png
    common/src/main/resources/assets/cobblemon_safari/textures/font/safari_points.png
Deterministic: the same script draws the same pixels.
"""

import math
import os

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

# Palette, from the top of the sky down to the grass in front.
SKY_TOP = (38, 70, 94)
SKY_MID = (226, 132, 74)
SKY_LOW = (250, 205, 120)
SUN = (255, 236, 170)
SUN_RIM = (255, 214, 130)
MOUNTAIN_FAR = (150, 104, 98)
MOUNTAIN_NEAR = (104, 80, 88)
PLAIN = (170, 150, 70)
PLAIN_DARK = (140, 122, 58)
TREE = (44, 36, 34)
TREE_LEAF = (52, 58, 36)
GRASS_BACK = (96, 120, 46)
GRASS_FRONT = (62, 92, 36)
GRASS_TIP = (190, 186, 84)

# Ordered-dither matrix: blends two colours in a pixel-art way instead of a
# smooth gradient, which would look out of place next to Minecraft's GUIs.
# Number of flat colour bands in the upper sky, and the share of each band
# (at its lower edge) that is dithered into the next. More bands look
# smoother and less pixel-art; a wider edge looks noisier.
SKY_BANDS = 9
DITHER_EDGE = 0.3

BAYER4 = np.array([[0, 8, 2, 10], [12, 4, 14, 6], [3, 11, 1, 9], [15, 7, 13, 5]]) / 16.0


def lerp(a, b, t):
    """Colour between a and b."""
    return tuple(int(round(a[i] + (b[i] - a[i]) * t)) for i in range(3))


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
    return lerp(c0, c1, min(1.0, base / steps))


def ridge(rng, width, base, amplitude, octaves):
    """Height profile of a mountain or hill line: summed sine waves with random phases."""
    xs = np.arange(width)
    h = np.full(width, float(base))
    for o in range(octaves):
        freq = (o + 1) * 2 * math.pi / width * rng.uniform(1.5, 3.5)
        h += amplitude / (o + 1) * np.sin(xs * freq + rng.uniform(0, 2 * math.pi))
    return np.round(h).astype(int)


def acacia(img, x0, ground, size, rng):
    """Silhouette of an acacia: leaning trunk forking into two branches, two flat crowns."""
    px = img.load()

    def put(x, y, colour):
        if 0 <= x < WIDTH and 0 <= y < HEIGHT:
            px[x, y] = colour

    trunk_h = int(size * 0.7)
    width = 2 if size >= 10 else 1
    for dy in range(trunk_h):
        lean = int(dy * 0.2)
        for w in range(width):
            put(x0 + lean + w, ground - dy, TREE)
    fork_x, fork_y = x0 + int(trunk_h * 0.2), ground - trunk_h
    crowns = []
    for direction in (-1, 1):
        length = int(size * 0.45)
        for d in range(length):
            put(fork_x + direction * (d // 2 + 1), fork_y - d, TREE)
        crowns.append((fork_x + direction * (length // 2 + 1), fork_y - length))
    for cx, cy in crowns:
        reach = int(size * 0.9) + int(rng.integers(0, 2))
        for layer in range(3):
            r = reach - layer * 2
            for x in range(cx - r, cx + r + 1):
                put(x, cy - layer, TREE_LEAF if (x * 7 + layer) % 4 else TREE)
        for x in range(cx - reach + 2, cx + reach - 1):
            put(x, cy + 1, TREE)


def background():
    """The landscape: dusk sky and sun, two mountain lines, plain with acacias, tall grass in front."""
    rng = np.random.default_rng(SEED)
    img = Image.new("RGB", (WIDTH, HEIGHT))
    px = img.load()
    horizon = int(HEIGHT * 0.58)
    for y in range(horizon):
        t = y / horizon
        for x in range(WIDTH):
            px[x, y] = dithered(SKY_TOP, SKY_MID, t / 0.7, x, y, SKY_BANDS) if t < 0.7 \
                else dithered(SKY_MID, SKY_LOW, (t - 0.7) / 0.3, x, y, SKY_BANDS // 2)

    # Sun low on the horizon, with a ring.
    sx, sy, sr = int(WIDTH * 0.72), horizon - 18, 22
    for y in range(max(0, sy - sr - 3), min(HEIGHT, sy + sr + 4)):
        for x in range(max(0, sx - sr - 3), min(WIDTH, sx + sr + 4)):
            d = math.hypot(x - sx, y - sy)
            if d <= sr:
                px[x, y] = SUN
            elif d <= sr + 2 and (x + y) % 2 == 0:
                px[x, y] = SUN_RIM

    # Mountains: far and near lines, the near one darker.
    for profile, colour in ((ridge(rng, WIDTH, horizon - 22, 10, 3), MOUNTAIN_FAR),
                            (ridge(rng, WIDTH, horizon - 8, 7, 4), MOUNTAIN_NEAR)):
        for x in range(WIDTH):
            for y in range(max(0, profile[x]), horizon + 1):
                px[x, y] = colour

    # The plain, banded toward the viewer.
    for y in range(horizon, HEIGHT):
        t = (y - horizon) / (HEIGHT - horizon)
        for x in range(WIDTH):
            px[x, y] = dithered(PLAIN_DARK, PLAIN, t, x, y, 3)

    # Acacias, smaller toward the horizon.
    for x0, size in ((30, 18), (98, 9), (150, 6), (205, 13), (262, 8)):
        acacia(img, x0, horizon + size // 2 + 2, size, rng)

    # Tall grass: two layers of blades, lighter tips.
    for layer, (colour, base, height) in enumerate(((GRASS_BACK, HEIGHT - 26, 14), (GRASS_FRONT, HEIGHT - 1, 26))):
        for x in range(WIDTH):
            blade = int(height * (0.55 + 0.45 * abs(math.sin(x * 0.7 + layer * 1.3 + rng.uniform(0, 0.6)))))
            if x % 2 == layer % 2:
                blade = int(blade * 0.7)
            lean = 1 if (x // 3) % 2 else 0
            for dy in range(blade):
                y = base - dy
                xx = x + (lean if dy > blade * 0.6 else 0)
                if 0 <= xx < WIDTH and 0 <= y < HEIGHT:
                    px[xx, y] = GRASS_TIP if dy >= blade - 2 else colour
            for y in range(base, HEIGHT):
                px[x, y] = colour
    draw_wildlife(img)
    draw_trainer(img)
    return img


# Original sprites, one character per pixel ("." is transparent). No
# Pokémon are drawn here: species are protected characters. The shop screen
# draws real Cobblemon models over this picture instead (SafariShopScreen
# SCENE_SPOTS): the trainer's companion beside him and wild Pokémon on the
# plain he points at. Keep the trainer and those spots in step.
#
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
BIRD = [
    "b...b",
    ".b.b.",
    "..b..",
]
# The trainer stands in the lower right, where short tabs leave empty slots
# and the landscape shows. Drawn at twice the texel size so he reads as the
# subject, not as a speck. Moving him means moving SCENE_SPOTS too.
TRAINER_POS = (238, 166)
TRAINER_SCALE = 2
BIRDS = [(214, 28), (226, 24), (238, 30), (252, 26), (264, 31)]
BIRD_COLOUR = (60, 46, 50)


def sprite(img, rows, colours, x0, y0, scale=1, mirror=False):
    """Draws an ASCII sprite with its top-left corner at (x0, y0)."""
    px = img.load()
    width = len(rows[0])
    for r, row in enumerate(rows):
        for c, ch in enumerate(row):
            if ch == ".":
                continue
            colour = colours[ch] if isinstance(colours, dict) else colours
            cx = (width - 1 - c) if mirror else c
            for dy in range(scale):
                for dx in range(scale):
                    x, y = x0 + cx * scale + dx, y0 + r * scale + dy
                    if 0 <= x < WIDTH and 0 <= y < HEIGHT:
                        px[x, y] = colour


def draw_wildlife(img):
    """A flight of birds over the plain. The Pokémon come from the game itself, see TRAINER."""
    for x, y in BIRDS:
        sprite(img, BIRD, BIRD_COLOUR, x, y)


def draw_trainer(img):
    """The trainer, standing in a clump of tall grass up to his boots."""
    x0, y0 = TRAINER_POS
    sprite(img, TRAINER, TRAINER_COLOURS, x0, y0, TRAINER_SCALE, mirror=True)
    px = img.load()
    ground = y0 + len(TRAINER) * TRAINER_SCALE
    for x in range(x0 - 6, x0 + len(TRAINER[0]) * TRAINER_SCALE + 6):
        blade = 8 + (x * 7) % 6
        for dy in range(blade):
            y = ground - dy
            if 0 <= x < WIDTH and 0 <= y < HEIGHT and (x + dy // 3) % 2 == 0:
                px[x, y] = GRASS_TIP if dy >= blade - 2 else GRASS_BACK


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
    os.makedirs(os.path.join(ASSETS, "gui"), exist_ok=True)
    os.makedirs(os.path.join(ASSETS, "font"), exist_ok=True)
    background().save(os.path.join(ASSETS, "gui", "shop_background.png"), optimize=True)
    points_icon().save(os.path.join(ASSETS, "font", "safari_points.png"), optimize=True)
    print("Wrote shop_background.png and safari_points.png")


if __name__ == "__main__":
    main()
