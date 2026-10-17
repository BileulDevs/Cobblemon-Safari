#!/usr/bin/env python3
"""
Draws the textures of the Safari decor blocks: the walnut of the banner
poles and bars and the coat rack (its hat in 16 colours).

Pixel art at the block resolution (16 x 16), drawn by rule rather than
painted, so that the 16 hat colours share one design: each colour only
changes the palette the felt is drawn with. Run from the project root:

    python3 tools/decor_textures.py

Output: common/src/main/resources/assets/cobblemon_safari/textures/block/decor/
Requires Pillow. Deterministic: the same script draws the same pixels.
"""
import colorsys
import math
import os
import random

from PIL import Image

OUT = os.path.join("common", "src", "main", "resources", "assets", "cobblemon_safari",
                   "textures", "block", "decor")
SIZE = 16

# Mean colour of each vanilla wool texture, so a hat matches the dye that
# coloured it. Changing one shifts the whole felt of that colour.
WOOL = {
    "white": (0xE9, 0xEC, 0xEC), "orange": (0xF0, 0x76, 0x13), "magenta": (0xBD, 0x44, 0xB3),
    "light_blue": (0x3A, 0xAF, 0xD9), "yellow": (0xF8, 0xC5, 0x27), "lime": (0x70, 0xB9, 0x19),
    "pink": (0xED, 0x8D, 0xAC), "gray": (0x3E, 0x44, 0x47), "light_gray": (0x8E, 0x8E, 0x86),
    "cyan": (0x15, 0x89, 0x91), "purple": (0x79, 0x2A, 0xAC), "blue": (0x35, 0x39, 0x9D),
    "brown": (0x72, 0x47, 0x28), "green": (0x54, 0x6D, 0x1B), "red": (0xA0, 0x27, 0x22),
    "black": (0x2A, 0x2B, 0x30),
}

# Brightness steps of a fabric ramp, darkest first. Index 3 is the base colour.
RAMP_STEPS = (0.52, 0.66, 0.82, 1.0, 1.12, 1.24)


def clamp(v):
    return max(0, min(255, int(round(v))))


def shade(rgb, factor, sat=1.0):
    """The colour made darker or lighter, keeping its hue; saturation scaled by sat."""
    h, l, s = colorsys.rgb_to_hls(*(c / 255.0 for c in rgb))
    l = max(0.0, min(1.0, l * factor if factor <= 1 else l + (1 - l) * (factor - 1) * 1.6))
    s = max(0.0, min(1.0, s * sat))
    return tuple(clamp(c * 255) for c in colorsys.hls_to_rgb(h, l, s))


def ramp(rgb):
    """Six shades of a fabric, darkest first. Shadows are a little more saturated, as cloth reads."""
    return [shade(rgb, f, 1.12 if f < 1 else 0.92) for f in RAMP_STEPS]


def new():
    return Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))


def put(img, x, y, rgb):
    if 0 <= x < SIZE and 0 <= y < SIZE:
        img.putpixel((x, y), tuple(rgb) + (255,))


def save(img, name):
    os.makedirs(OUT, exist_ok=True)
    img.save(os.path.join(OUT, name + ".png"))


# ---------------------------------------------------------------- wood

WALNUT = [(0x2E, 0x1C, 0x10), (0x3F, 0x27, 0x16), (0x52, 0x34, 0x1E), (0x65, 0x42, 0x27), (0x7A, 0x52, 0x31)]


def walnut(seed):
    """Dark walnut of the banner poles and bars (SafariBannerRenderer): long grain with a knot."""
    rnd = random.Random(seed)
    img = new()
    for y in range(SIZE):
        offset = rnd.randint(0, 3)
        for x in range(SIZE):
            g = (x * 2 + offset + (y // 4)) % 7
            level = 2 if g < 3 else 3 if g < 5 else 1
            if rnd.random() < 0.06:
                level = 4
            put(img, x, y, WALNUT[level])
    for x, y in ((10, 5), (11, 5), (10, 6), (11, 6)):
        put(img, x, y, WALNUT[0])
    put(img, 9, 5, WALNUT[1])
    put(img, 12, 6, WALNUT[1])
    return img


# ---------------------------------------------------------------- coat rack

BRASS = [(0x6B, 0x4A, 0x16), (0x9C, 0x72, 0x24), (0xC9, 0x9A, 0x3A), (0xE8, 0xC4, 0x62), (0xFA, 0xE8, 0xA6)]
LEATHER = [(0x2B, 0x17, 0x0C), (0x45, 0x26, 0x13), (0x5E, 0x35, 0x1B), (0x7A, 0x47, 0x25), (0x96, 0x5D, 0x33)]
TEAK = [(0x5A, 0x38, 0x1C), (0x7A, 0x4E, 0x28), (0x98, 0x66, 0x36), (0xB5, 0x80, 0x48), (0xCF, 0x9C, 0x60)]
KHAKI = [(0x6E, 0x60, 0x3E), (0x8C, 0x7C, 0x52), (0xA8, 0x97, 0x66), (0xC2, 0xB1, 0x7E), (0xD9, 0xCB, 0x9A)]


def teak(seed):
    """Turned teak of the rack: warm grain along the pole."""
    rnd = random.Random(seed)
    img = new()
    for y in range(SIZE):
        for x in range(SIZE):
            g = (x * 3 + (y // 5)) % 6
            level = 2 if g < 3 else 3 if g < 5 else 1
            if rnd.random() < 0.05:
                level = 4
            put(img, x, y, TEAK[level])
    return img


def jacket(front):
    """Ranger's safari jacket: khaki drill, stitched patch pockets, brass buttons, epaulettes."""
    img = new()
    for y in range(SIZE):
        for x in range(SIZE):
            level = 2 if (x + 2 * y) % 3 else 1
            put(img, x, y, KHAKI[level])
    if front:
        # Button placket down the middle.
        for y in range(SIZE):
            put(img, 8, y, KHAKI[0])
            if y % 4 == 2:
                put(img, 7, y, BRASS[3])
        # Patch pockets with flaps.
        for px in (2, 10):
            for y in range(6, 11):
                for x in range(px, px + 4):
                    put(img, x, y, KHAKI[3])
            for x in range(px, px + 4):
                put(img, x, 6, KHAKI[1])
                put(img, x, 10, KHAKI[1])
            put(img, px + 1, 7, BRASS[3])
        # Collar.
        for x in range(4, 13):
            put(img, x, 0, KHAKI[4])
            put(img, x, 1, KHAKI[0] if x in (7, 8, 9) else KHAKI[3])
    else:
        # Epaulette and side seam.
        for x in range(3, 13):
            put(img, x, 1, KHAKI[4])
        for y in range(SIZE):
            put(img, 8, y, KHAKI[0] if y % 2 else KHAKI[1])
    return img


def hat_felt(colour, seed):
    """Felt of the ranger hat, dyed: a soft brushed nap with a pinched crease in the middle."""
    base = WOOL[colour] if colour != "brown" else (0x8A, 0x5E, 0x34)
    r = ramp(base)
    rnd = random.Random(seed)
    img = new()
    for y in range(SIZE):
        for x in range(SIZE):
            level = 3 if (x // 2 + y) % 3 else 2
            if rnd.random() < 0.06:
                level = 4
            put(img, x, y, r[level])
    for y in range(SIZE):
        put(img, 7, y, r[1])
        put(img, 8, y, r[4])
    return img


def hat_band():
    """Leather hat band with a brass buckle and a little feather of savanna grass."""
    img = new()
    for y in range(SIZE):
        for x in range(SIZE):
            put(img, x, y, LEATHER[2] if y % 4 else LEATHER[1])
    for y in range(SIZE):
        put(img, 5, y, BRASS[3])
        put(img, 7, y, BRASS[3])
    for y in range(0, SIZE, 4):
        put(img, 6, y, BRASS[4])
    put(img, 11, 0, (0xD8, 0xC0, 0x5A))
    put(img, 12, 0, (0xB8, 0xA0, 0x48))
    return img


def coat_rack_icon():
    """Inventory icon of the coat rack: pole on crossed feet, jacket on its hook, brown hat on top."""
    img = new()
    hat = ramp((0x8A, 0x5E, 0x34))
    for y in range(4, 16):
        put(img, 7, y, TEAK[2])
        put(img, 8, y, TEAK[1])
    for x in range(4, 12):
        put(img, x, 15, TEAK[1])
    for x in range(3, 13):
        put(img, x, 3, hat[2])
    for y in range(0, 3):
        for x in range(5, 11):
            put(img, x, y, hat[3] if y else hat[4])
    for x in range(5, 11):
        put(img, x, 2, LEATHER[2])
    for y in range(5, 12):
        for x in range(4, 12):
            if x in (7, 8):
                continue
            put(img, x, y, KHAKI[2] if (x + y) % 3 else KHAKI[1])
    put(img, 5, 8, KHAKI[4])
    put(img, 10, 8, KHAKI[4])
    return img


def main():
    save(walnut(7), "walnut")
    save(teak(41), "teak")
    save(jacket(True), "jacket_front")
    save(jacket(False), "jacket_side")
    save(hat_band(), "hat_band")
    for i, colour in enumerate(WOOL):
        save(hat_felt(colour, 400 + i), "hat_felt_" + colour)
    item_dir = os.path.join(os.path.dirname(OUT), "..", "item")
    os.makedirs(item_dir, exist_ok=True)
    coat_rack_icon().save(os.path.join(item_dir, "coat_rack.png"))


if __name__ == "__main__":
    main()
