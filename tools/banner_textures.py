#!/usr/bin/env python3
"""
Draws the pictures shipped for the Safari Banners, the banner item icon and
the animation files of the animated pictures.

A picture is any texture under textures/block/banner/ (see
decor/BannerDesigns.java): 32 x 64 here, the cloth's 1:2 ratio. An animated
one stacks its frames vertically (32 x 64n) and has a .png.mcmeta giving the
frame size, as any animated block texture. Run from the project root:

    python3 tools/banner_textures.py

Original art only: Safari motifs, no characters. Deterministic.
Requires Pillow.
"""
import json
import math
import os
import random

from PIL import Image

ROOT = os.path.join("common", "src", "main", "resources", "assets", "cobblemon_safari", "textures")
BANNERS = os.path.join(ROOT, "block", "banner")
W, H = 32, 64

# Frames of the animated pictures and ticks per frame. More frames move
# more smoothly and make a taller texture in the atlas.
FRAMES = 8
FRAME_TICKS = 4

# Pictures that need a different pace. The waterfall's 16 frames move its
# streaks one pixel per frame; fewer frames would make them jump. Fast ticks
# (2) suit falling water and rain; slower loops (16 frames of 4 ticks, 3.2 s)
# suit things that drift. Each extra frame adds a 32 x 64 tile to the atlas.
WATERFALL_FRAMES = 16
SLOW_FRAMES = 16
FAST_FRAME_TICKS = 2
CAMPFIRE_FRAME_TICKS = 3


def rgb(h):
    return tuple(int(h[i:i + 2], 16) for i in (1, 3, 5))


def mix(a, b, t):
    return tuple(int(round(a[i] + (b[i] - a[i]) * t)) for i in range(3))


class Canvas:
    """A 32 x 64 picture with a few drawing helpers."""

    def __init__(self, fill):
        self.img = Image.new("RGBA", (W, H), fill + (255,))

    def put(self, x, y, c):
        if 0 <= x < W and 0 <= y < H:
            self.img.putpixel((int(x), int(y)), tuple(c) + (255,))

    def get(self, x, y):
        return self.img.getpixel((x, y))[:3]

    def rect(self, x0, y0, x1, y1, c):
        for y in range(y0, y1):
            for x in range(x0, x1):
                self.put(x, y, c)

    def disc(self, cx, cy, r, c):
        for y in range(int(cy - r - 1), int(cy + r + 2)):
            for x in range(int(cx - r - 1), int(cx + r + 2)):
                if (x - cx) ** 2 + (y - cy) ** 2 <= r * r:
                    self.put(x, y, c)

    def line(self, x0, y0, x1, y1, c):
        n = int(max(abs(x1 - x0), abs(y1 - y0))) + 1
        for i in range(n + 1):
            t = i / n
            self.put(round(x0 + (x1 - x0) * t), round(y0 + (y1 - y0) * t), c)


def hem(cv, dark, light, notches=True):
    """Stitched border of the cloth: dark outline, light stitch dots, swallowtail notch at the bottom."""
    for x in range(W):
        cv.put(x, 0, dark)
        cv.put(x, 1, light if x % 2 else dark)
    for y in range(H):
        cv.put(0, y, dark)
        cv.put(W - 1, y, dark)
        if y % 2 and 2 < y < H - 4:
            cv.put(1, y, light)
            cv.put(W - 2, y, light)
    for x in range(W):
        cv.put(x, H - 1, dark)
    if notches:
        # A V cut into the bottom, filled with transparency.
        for x in range(W):
            depth = 6 - abs(x - (W - 1) / 2) * 6 / (W / 2)
            for d in range(int(max(0, depth))):
                cv.img.putpixel((x, H - 1 - d), (0, 0, 0, 0))
            if depth > 0:
                cv.put(x, H - 1 - int(depth), dark)


def acacia(cv, x, ground, c, scale=1.0):
    """Flat-topped acacia: thin forked trunk, wide umbrella crown."""
    h = int(9 * scale)
    for y in range(ground - h, ground):
        cv.put(x, y, c)
    cv.line(x, ground - h + 2, x - 3 * scale, ground - h - 1, c)
    cv.line(x, ground - h + 3, x + 3 * scale, ground - h, c)
    for dx in range(int(-7 * scale), int(7 * scale) + 1):
        thick = 2 if abs(dx) < 5 * scale else 1
        for dy in range(thick):
            cv.put(x + dx, ground - h - 2 - dy, c)


def safari_crest():
    """The Safari crest: a sun over savanna hills and an acacia, on deep green, gold-hemmed."""
    green, dark, gold, pale = rgb("#2F5D3A"), rgb("#1C3A24"), rgb("#E2B33C"), rgb("#F6E3A1")
    cv = Canvas(green)
    for y in range(H):
        for x in range(W):
            if (x + y * 3) % 7 == 0:
                cv.put(x, y, mix(green, dark, 0.3))
    # Emblem disc.
    cx, cy = 15.5, 24
    cv.disc(cx, cy, 12, gold)
    cv.disc(cx, cy, 11, mix(rgb("#F2994A"), gold, 0.3))
    for y in range(13, 36):
        for x in range(4, 28):
            if (x - cx) ** 2 + (y - cy) ** 2 <= 121:
                t = (y - 13) / 22
                cv.put(x, y, mix(rgb("#F7C46C"), rgb("#E0703A"), t))
    cv.disc(cx, 22, 4.5, pale)
    # Sun rays.
    for a in range(0, 360, 30):
        r = math.radians(a)
        cv.line(cx + math.cos(r) * 6, 22 + math.sin(r) * 6, cx + math.cos(r) * 8, 22 + math.sin(r) * 8, pale)
    # Hills.
    for x in range(4, 28):
        hill = 28 + int(2 * math.sin(x / 3.0))
        for y in range(hill, 36):
            if (x - cx) ** 2 + (y - cy) ** 2 <= 121:
                cv.put(x, y, rgb("#6B4B2A") if y > hill + 1 else rgb("#8A6436"))
    acacia(cv, 19, 30, rgb("#2A1B10"), 0.8)
    # Ring.
    for a in range(360):
        r = math.radians(a)
        cv.put(round(cx + math.cos(r) * 12), round(cy + math.sin(r) * 12), dark)
    # Laurel under the emblem.
    for i in range(6):
        cv.put(9 + i, 38 + (i % 2), gold)
        cv.put(22 - i, 38 + (i % 2), gold)
    # Three stripes.
    for i, c in enumerate((gold, pale, gold)):
        cv.rect(4, 44 + i * 3, 28, 45 + i * 3, c)
    hem(cv, dark, gold)
    return cv.img


def ranger_compass():
    """The ranger's compass rose on khaki canvas, north marked in red."""
    khaki, dark, red, cream = rgb("#B59B6A"), rgb("#6E5A36"), rgb("#B03A2E"), rgb("#EFE3C2")
    cv = Canvas(khaki)
    rnd = random.Random(3)
    for y in range(H):
        for x in range(W):
            if rnd.random() < 0.15:
                cv.put(x, y, mix(khaki, dark, 0.2))
            if y % 4 == 0 and x % 2 == 0:
                cv.put(x, y, mix(khaki, cream, 0.15))
    cx, cy = 15.5, 26
    for a in range(360):
        r = math.radians(a)
        for rad in (11, 12):
            cv.put(round(cx + math.cos(r) * rad), round(cy + math.sin(r) * rad), dark)
    for a in range(0, 360, 15):
        r = math.radians(a)
        cv.put(round(cx + math.cos(r) * 10), round(cy + math.sin(r) * 10), cream)
    # Eight-point star: long cardinal points, short diagonals.
    for a, length, colour in ((0, 10, cream), (90, 10, cream), (180, 10, cream), (270, 10, red),
                              (45, 6, dark), (135, 6, dark), (225, 6, dark), (315, 6, dark)):
        r = math.radians(a)
        tip = (cx + math.cos(r) * length, cy + math.sin(r) * length)
        side = math.radians(a + 90)
        for t in range(0, 101, 2):
            k = t / 100
            px, py = cx + (tip[0] - cx) * k, cy + (tip[1] - cy) * k
            half = (1 - k) * 2.2
            cv.line(px - math.cos(side) * half, py - math.sin(side) * half,
                    px + math.cos(side) * half, py + math.sin(side) * half, colour)
    cv.disc(cx, cy, 1.5, dark)
    # "N" above the rose.
    for y in range(10, 15):
        cv.put(13, y, red)
        cv.put(17, y, red)
        cv.put(13 + (y - 10), y, red)
    # Rope knot band.
    for x in range(3, 29):
        cv.put(x, 46, dark if x % 3 == 0 else cream)
        cv.put(x, 47, cream if x % 3 == 1 else dark)
    hem(cv, dark, cream)
    return cv.img


def paw_trail():
    """A trail of paw prints crossing sand, bigger toward the bottom as if walking closer."""
    sand, shade, print_c, light = rgb("#E3C98E"), rgb("#C9AA6A"), rgb("#7A5531"), rgb("#F5E6BC")
    cv = Canvas(sand)
    rnd = random.Random(5)
    for y in range(H):
        for x in range(W):
            n = rnd.random()
            if n < 0.12:
                cv.put(x, y, shade)
            elif n > 0.94:
                cv.put(x, y, light)
    for i, (px, py, s) in enumerate(((10, 8, 0.7), (20, 16, 0.8), (11, 26, 0.9), (21, 37, 1.0), (12, 49, 1.1))):
        cv.disc(px, py, 2.6 * s, print_c)
        for dx, dy in ((-3, -3), (-1, -4.5), (1.5, -4.5), (3.5, -3)):
            cv.disc(px + dx * s, py + dy * s, 1.0 * s, print_c)
    hem(cv, shade, light)
    return cv.img


def tropical_leaves():
    """Split monstera and palm leaves on a deep jungle green."""
    back, deep, leaf, vein = rgb("#16402B"), rgb("#0D2A1B"), rgb("#3E8E4E"), rgb("#9ED38B")
    cv = Canvas(back)
    for y in range(H):
        for x in range(W):
            if (x * 5 + y * 7) % 11 == 0:
                cv.put(x, y, deep)

    def monstera(cx, cy, r, tilt):
        for y in range(int(cy - r), int(cy + r) + 1):
            for x in range(int(cx - r), int(cx + r) + 1):
                dx, dy = x - cx, y - cy
                if dx * dx + (dy * 1.2) ** 2 > r * r:
                    continue
                ang = math.atan2(dy, dx) + tilt
                # Splits: thin wedges cut out of the leaf.
                if math.sin(ang * 5) > 0.85 and dx * dx + dy * dy > (r * 0.45) ** 2:
                    continue
                shade = 0.85 + 0.15 * math.cos(ang)
                cv.put(x, y, mix(deep, leaf, shade))
        cv.line(cx, cy, cx + math.cos(-tilt) * r, cy + math.sin(-tilt) * r, vein)

    monstera(11, 14, 9, 0.6)
    monstera(22, 36, 10, 2.4)
    monstera(9, 52, 7, 1.2)
    # Palm frond.
    for i in range(14):
        x, y = 26 - i, 4 + i * 1.6
        cv.put(x, y, vein)
        cv.line(x, y, x - 4, y - 2, leaf)
        cv.line(x, y, x + 3, y + 2, leaf)
    hem(cv, deep, vein)
    return cv.img


def river_waves(frame):
    """Rolling river waves; the crests travel one wavelength over the animation."""
    top, bottom, foam, crest = rgb("#2E7FB8"), rgb("#123D66"), rgb("#E8F6FF"), rgb("#7CC4E8")
    cv = Canvas(top)
    phase = frame / FRAMES * 2 * math.pi
    for y in range(H):
        base = mix(top, bottom, y / H)
        for x in range(W):
            cv.put(x, y, base)
    for row in range(5, H, 9):
        for x in range(W):
            wy = row + 2 * math.sin(x / 32 * 2 * math.pi * 2 + phase + row)
            cv.put(x, round(wy), crest)
            cv.put(x, round(wy) + 1, mix(crest, bottom, 0.5))
            if math.sin(x / 32 * 2 * math.pi * 2 + phase + row) > 0.8:
                cv.put(x, round(wy) - 1, foam)
    # A leaping fish silhouette that rises and dips once per loop.
    fy = 30 - 6 * math.sin(phase)
    for dx in range(-3, 4):
        cv.put(16 + dx, round(fy + abs(dx) * 0.3), rgb("#0B2238"))
    cv.put(20, round(fy - 1), rgb("#0B2238"))
    cv.put(20, round(fy + 1), rgb("#0B2238"))
    hem(cv, bottom, foam)
    return cv.img


def fireflies(frame):
    """Savanna night: stars and an acacia, fireflies blinking in turn over the loop."""
    sky_top, sky_low, ground, glow = rgb("#0B1230"), rgb("#2B2350"), rgb("#14100A"), rgb("#F4F18A")
    cv = Canvas(sky_top)
    for y in range(H):
        for x in range(W):
            cv.put(x, y, mix(sky_top, sky_low, min(1, y / 46)))
    rnd = random.Random(9)
    for _ in range(26):
        x, y = rnd.randrange(2, W - 2), rnd.randrange(2, 38)
        cv.put(x, y, rgb("#C9D3FF") if rnd.random() < 0.7 else rgb("#FFFFFF"))
    cv.disc(23, 10, 3.5, rgb("#F3EBD0"))
    cv.disc(24.5, 9, 3, mix(sky_top, sky_low, 0.15))
    for x in range(W):
        hill = 46 + int(2 * math.sin(x / 4.0))
        for y in range(hill, H):
            cv.put(x, y, ground)
    acacia(cv, 9, 48, ground, 1.0)
    for i in range(7):
        rx, ry = rnd.randrange(3, W - 3), rnd.randrange(22, 52)
        lit = (frame + i * 3) % FRAMES
        if lit < 3:
            strength = (1, 0.6, 0.3)[lit]
            cv.put(rx, ry, mix(sky_low, glow, strength))
            if lit == 0:
                for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                    cv.put(rx + dx, ry + dy, mix(cv.get(min(W - 1, max(0, rx + dx)), min(H - 1, max(0, ry + dy))), glow, 0.35))
    hem(cv, sky_top, rgb("#8C88C0"))
    return cv.img


def sunset_savanna():
    """Banded sunset over the savanna, acacias and a line of birds."""
    bands = [rgb(h) for h in ("#3B1E54", "#7A2F5E", "#C4485A", "#EE7A45", "#F7B04B", "#FBD877")]
    cv = Canvas(bands[0])
    for y in range(H):
        t = min(1, y / 40) * (len(bands) - 1)
        i = int(t)
        c = mix(bands[i], bands[min(i + 1, len(bands) - 1)], t - i)
        # Dithered band edges, as pixel-art skies.
        if (t - i) > 0.5 and (y + 0) % 2 == 0:
            c = bands[min(i + 1, len(bands) - 1)]
        for x in range(W):
            cv.put(x, y, c)
    cv.disc(16, 38, 7, rgb("#FFE9A8"))
    silhouette = rgb("#2A1420")
    for x in range(W):
        for y in range(42 + int(1.5 * math.sin(x / 5)), H):
            cv.put(x, y, silhouette)
    acacia(cv, 7, 44, silhouette, 1.1)
    acacia(cv, 25, 45, silhouette, 0.7)
    for bx, by in ((12, 14), (16, 12), (20, 15), (14, 19)):
        cv.put(bx - 1, by, silhouette)
        cv.put(bx, by + 1, silhouette)
        cv.put(bx + 1, by, silhouette)
    hem(cv, silhouette, rgb("#F7B04B"))
    return cv.img


def kente():
    """Woven strip pattern: bands of zigzags, diamonds and blocks in earth and gold tones."""
    palette = [rgb(h) for h in ("#B8322A", "#E7B73A", "#1F5E3B", "#1B1B1B", "#F2E3C0")]
    cv = Canvas(palette[3])
    for y in range(H):
        band = (y // 8) % 4
        for x in range(W):
            if band == 0:
                c = palette[0] if (x // 2 + y) % 4 < 2 else palette[1]
            elif band == 1:
                d = abs((x % 8) - 4) + abs((y % 8) - 4)
                c = palette[2] if d < 3 else palette[1] if d < 4 else palette[3]
            elif band == 2:
                c = palette[4] if ((x + 2 * (y % 8)) % 8) < 2 else palette[0]
            else:
                c = palette[1] if (x // 4 + y // 4) % 2 else palette[2]
            if y % 8 == 0:
                c = palette[3]
            cv.put(x, y, c)
    hem(cv, palette[3], palette[1])
    return cv.img


def noise(x, y, seed=0):
    """Deterministic value in [0, 1) for a pixel: texture grain that stays put from frame to frame."""
    n = (x * 374761393 + y * 668265263 + seed * 2147483647) & 0xFFFFFFFF
    n = ((n ^ (n >> 13)) * 1274126177) & 0xFFFFFFFF
    return (n ^ (n >> 16)) / 2 ** 32


def waterfall(frame, frames):
    """A waterfall between mossy cliffs: streaks fall one stripe period per loop, mist puffs breathe below."""
    rock, rock_dark, rock_light = rgb("#4A4F4A"), rgb("#2C302D"), rgb("#6E7469")
    moss, moss_light, fern = rgb("#3D6B32"), rgb("#6FA14B"), rgb("#2E5A2A")
    deep, water, light, foam = rgb("#1E5E86"), rgb("#4FA3D1"), rgb("#A9DDF2"), rgb("#F2FBFF")
    cv = Canvas(rock)
    # Cliffs: layered strata with grain, darker toward the falls.
    for y in range(H):
        for x in range(W):
            strata = (y + int(3 * math.sin(x / 3.0))) % 7
            c = rock_light if strata == 0 else rock
            if noise(x, y, 1) < 0.25:
                c = mix(c, rock_dark, 0.5)
            cv.put(x, y, c)
    # Water column, slightly wider at the bottom where it spreads.
    period = 16
    shift = frame * period / frames
    for y in range(4, 50):
        half = 6 + y / 25
        for x in range(W):
            d = abs(x - 15.5)
            if d > half:
                continue
            v = noise(x, int((y - shift) % period), 2)
            c = mix(water, light, v)
            if v > 0.82:
                c = foam
            if d > half - 1:
                c = mix(c, deep, 0.45)
            cv.put(x, y, c)
    # Lip of the river at the top: a flat band of water and a foam edge.
    for x in range(9, 23):
        cv.put(x, 3, water)
        cv.put(x, 4, foam if (x + frame) % 3 else light)
    # Plunge pool.
    for y in range(50, H):
        for x in range(W):
            t = (y - 50) / (H - 50)
            c = mix(water, deep, t)
            ripple = math.sin((x - 15.5) * 0.9 - frame / frames * 2 * math.pi * 2 + y)
            if ripple > 0.85:
                c = light
            cv.put(x, y, c)
    # Mist: three puffs whose size follows the loop, each a third of it apart.
    for i, mx in enumerate((10, 16, 22)):
        ph = frame / frames * 2 * math.pi + i * 2.1
        r = 3 + 1.2 * math.sin(ph)
        for y in range(int(50 - r), int(50 + r) + 1):
            for x in range(int(mx - r), int(mx + r) + 1):
                if (x - mx) ** 2 + ((y - 50) * 1.4) ** 2 <= r * r and 0 <= x < W and 0 <= y < H:
                    cv.put(x, y, mix(cv.get(x, y), foam, 0.6))
    # Moss on the cliff tops and ferns hanging on both sides.
    for x in range(W):
        if abs(x - 15.5) > 7:
            for y in range(2, 5 + int(2 * noise(x, 0, 3))):
                cv.put(x, y, moss_light if noise(x, y, 4) < 0.4 else moss)
    for fx, fy, side in ((3, 14, 1), (28, 22, -1), (4, 33, 1), (27, 40, -1)):
        sway = math.sin(frame / frames * 2 * math.pi + fy) * 0.8
        for i in range(7):
            x, y = fx + side * i * 0.6 + sway * i / 7, fy + i
            cv.put(round(x), round(y), fern)
            cv.put(round(x + side), round(y - 1), moss_light)
            cv.put(round(x - side), round(y - 1), moss)
    # A faint rainbow in the spray, left of the falls.
    for k, c in enumerate((rgb("#E8615A"), rgb("#F2C14E"), rgb("#7DC46A"), rgb("#5B8FD9"))):
        for a in range(200, 260, 4):
            r = math.radians(a)
            x, y = round(15.5 + math.cos(r) * (9 - k)), round(52 + math.sin(r) * (9 - k))
            if 0 <= x < W and 0 <= y < H:
                cv.put(x, y, mix(cv.get(x, y), c, 0.35))
    hem(cv, rock_dark, foam)
    return cv.img


def campfire(frame, frames):
    """Camp at night: a crackling fire, sparks drifting up, warm light on the ground, twinkling stars."""
    sky_top, sky_low, ground, ground_lit = rgb("#070B1E"), rgb("#1D1838"), rgb("#1A140E"), rgb("#5A3A1E")
    log, log_light = rgb("#3B2414"), rgb("#7A4E2A")
    core, yellow, orange, red = rgb("#FFF6C8"), rgb("#FFD24A"), rgb("#F2862E"), rgb("#B8321F")
    cv = Canvas(sky_top)
    for y in range(H):
        for x in range(W):
            cv.put(x, y, mix(sky_top, sky_low, min(1, y / 44)))
    for i in range(30):
        x, y = int(noise(i, 0, 5) * (W - 4)) + 2, int(noise(i, 1, 5) * 36) + 2
        twinkle = (frame + i) % frames
        c = rgb("#FFFFFF") if twinkle == 0 else rgb("#B9C2F0") if twinkle < frames // 2 else rgb("#6F75A8")
        cv.put(x, y, c)
    # Tree line silhouettes behind the camp.
    for x in range(W):
        top = 40 - int(4 * abs(math.sin(x * 0.7))) - (3 if x % 7 == 3 else 0)
        for y in range(top, 46):
            cv.put(x, y, rgb("#0E1210"))
    # Ground, lit warmly near the fire; the light breathes with the flames.
    flicker = 0.85 + 0.15 * math.sin(frame / frames * 2 * math.pi * 3)
    for y in range(46, H):
        for x in range(W):
            d = math.hypot((x - 15.5) / 1.6, (y - 52) * 1.2)
            light = max(0.0, 1 - d / 13) * flicker
            c = mix(ground, ground_lit, light)
            if noise(x, y, 6) < 0.15:
                c = mix(c, ground, 0.5)
            cv.put(x, y, c)
    # Stone ring.
    for a in range(0, 360, 24):
        r = math.radians(a)
        sx, sy = round(15.5 + math.cos(r) * 8), round(53 + math.sin(r) * 3)
        cv.put(sx, sy, rgb("#8A8378"))
        cv.put(sx + 1, sy, rgb("#5C564D"))
    # Crossed logs.
    for x0, x1 in ((9, 22), (22, 9)):
        for t in range(0, 101, 4):
            x = x0 + (x1 - x0) * t / 100
            y = 55 - 4 * t / 100 if x0 < x1 else 51 + 4 * t / 100
            cv.put(round(x), round(y), log)
            cv.put(round(x), round(y) - 1, log_light)
    # Flames: a height field that changes every frame, coloured from the core outward.
    phase = frame / frames * 2 * math.pi
    for x in range(8, 24):
        d = abs(x - 15.5) / 7.5
        h = (1 - d * d) * 13 + 2.2 * math.sin(x * 1.7 + phase * 2) + 1.5 * math.sin(x * 0.6 - phase)
        for k in range(int(max(0, h))):
            y = 52 - k
            t = k / max(h, 1)
            if d < 0.3 and t < 0.45:
                c = core
            elif t < 0.55 - d * 0.3:
                c = yellow
            elif t < 0.85:
                c = orange
            else:
                c = red
            cv.put(x, y, c)
    # Sparks rising and drifting, each on its own beat of the loop.
    for i in range(6):
        p = (frame / frames + i / 6) % 1
        x = 15.5 + 5 * math.sin(i * 2.4) + 2 * math.sin(p * 6 + i)
        y = 40 - p * 28
        c = yellow if p < 0.4 else orange if p < 0.75 else red
        cv.put(round(x), round(y), c)
    hem(cv, sky_top, rgb("#F2862E"))
    return cv.img


def rainstorm(frame, frames):
    """Rainy season over the savanna: slanting rain, rolling clouds, a lightning flash once per loop."""
    flash = frame == 0
    after = frame == 1
    sky, sky_low = rgb("#2E3A4A"), rgb("#55657A")
    if flash:
        sky, sky_low = rgb("#9AA9C2"), rgb("#C6D2E3")
    elif after:
        sky, sky_low = rgb("#46546A"), rgb("#76879E")
    cloud, cloud_dark = mix(sky, rgb("#FFFFFF"), 0.25), mix(sky, rgb("#000000"), 0.35)
    grass, grass_dark = rgb("#3C5A2A"), rgb("#22351A")
    rain = mix(sky_low, rgb("#DDE8F5"), 0.6)
    cv = Canvas(sky)
    for y in range(H):
        for x in range(W):
            cv.put(x, y, mix(sky, sky_low, y / H))
    # Clouds: two bumpy layers drifting a full width each loop.
    drift = frame * W / frames
    for layer, (base, colour, speed) in enumerate(((8, cloud_dark, 1), (13, cloud, 0.5))):
        for x in range(W):
            xx = (x + drift * speed) % W
            bottom = base + 3 * math.sin(xx / W * 2 * math.pi * 2 + layer) + 2 * math.sin(xx / W * 2 * math.pi * 3)
            for y in range(0, int(bottom)):
                cv.put(x, y, colour if noise(int(xx), y, 7 + layer) > 0.2 else mix(colour, sky, 0.3))
    # Lightning bolt on the flash frame, its afterglow on the next.
    if flash or after:
        bolt = rgb("#FFFFFF") if flash else rgb("#B9C8E8")
        x, y = 21, 12
        while y < 42:
            nx = x + (-2 if noise(x, y, 8) < 0.5 else 2)
            cv.line(x, y, nx, y + 4, bolt)
            if flash:
                cv.line(x + 1, y, nx + 1, y + 4, rgb("#D9E4FF"))
            x, y = nx, y + 4
    # Distant acacia and tall grass.
    acacia(cv, 8, 46, grass_dark, 1.0)
    for x in range(W):
        top = 44 + int(2 * math.sin(x / 2.3))
        for y in range(top, H):
            cv.put(x, y, grass if (x + y) % 3 else grass_dark)
        cv.put(x, top - 1 - (x % 3 == 0), grass_dark)
    # Rain: slanted streaks falling one period per loop.
    period = 8
    fall = frame * period / frames
    for x in range(W):
        # The rain disappears into the grass instead of streaking over it.
        for y in range(H - 18):
            u = (y - fall + x * 0.5) % period
            if u < 2 and noise(x, int((y - fall) // period), 9) < 0.35:
                cv.put(x, y, rain)
    # Splashes in the grass, a few per frame.
    for i in range(4):
        sx = int(noise(i, frame, 10) * (W - 6)) + 3
        cv.put(sx - 1, 47, rain)
        cv.put(sx + 1, 47, rain)
        cv.put(sx, 46, rain)
    hem(cv, cloud_dark, rgb("#B9C8E8"))
    return cv.img


def windy_grass(frame, frames):
    """Golden afternoon: tall grass waving in the wind, a flock of birds crossing the sky."""
    sky_top, sky_low, sun = rgb("#F3C468"), rgb("#FBE7B0"), rgb("#FFF4D2")
    grass_back, grass, grass_tip = rgb("#9C7A2E"), rgb("#C79A3A"), rgb("#F0CF72")
    bird = rgb("#3A2A1A")
    cv = Canvas(sky_top)
    for y in range(H):
        for x in range(W):
            cv.put(x, y, mix(sky_top, sky_low, min(1, y / 34)))
    cv.disc(22, 15, 5, sun)
    cv.disc(22, 15, 6.5, mix(sky_top, sun, 0.4))
    cv.disc(22, 15, 5, sun)
    # Far hills with a lone acacia.
    for x in range(W):
        for y in range(32 + int(2 * math.sin(x / 6.0)), H):
            cv.put(x, y, mix(rgb("#B88A45"), sky_low, 0.35))
    acacia(cv, 7, 34, rgb("#6B4E2A"), 0.7)
    # Grass blades: back row then front row, each leaning with the wind.
    phase = frame / frames * 2 * math.pi
    for row, (base_y, height, colour, step) in enumerate(((44, 12, grass_back, 2), (H - 1, 22, grass, 2))):
        for x0 in range(row, W, step):
            h = height * (0.75 + 0.25 * noise(x0, row, 11))
            lean = 3.5 * math.sin(phase - x0 * 0.35) * (1 + row * 0.4)
            for k in range(int(h)):
                t = k / h
                x = x0 + lean * t * t
                y = base_y - k
                cv.put(round(x), y, grass_tip if t > 0.85 else colour)
    for y in range(44, H):
        for x in range(W):
            if cv.get(x, y) == mix(rgb("#B88A45"), sky_low, 0.35):
                cv.put(x, y, grass_back)
    # Birds: a V flock gliding across one width per loop, wings beating.
    for i, (bx0, by) in enumerate(((0, 9), (5, 7), (5, 11), (10, 5), (10, 13))):
        x = (bx0 + frame * W / frames) % W
        up = (frame + i) % 2 == 0
        cv.put(round(x), by, bird)
        cv.put(round(x) - 1, by - 1 if up else by + 1, bird)
        cv.put(round(x) + 1, by - 1 if up else by + 1, bird)
    hem(cv, rgb("#8A6420"), sun)
    return cv.img


def lily_pond(frame, frames):
    """A still pond seen from above: lily pads, a pink lotus, koi circling and rings spreading."""
    deep, water, shallow, ring = rgb("#0F3B3A"), rgb("#1D5F5A"), rgb("#2F7C70"), rgb("#8FD3C4")
    pad, pad_dark, pad_light = rgb("#3F8A3A"), rgb("#2A6129"), rgb("#7CC062")
    petal, petal_dark, heart = rgb("#F4A9C4"), rgb("#D46A95"), rgb("#F7E07A")
    koi, koi_white = rgb("#F07A2C"), rgb("#F8F3E8")
    cv = Canvas(water)
    for y in range(H):
        for x in range(W):
            v = noise(x // 2, y // 2, 12)
            c = mix(deep, shallow, 0.3 + 0.5 * v)
            cv.put(x, y, c)
    phase = frame / frames * 2 * math.pi
    # Koi swimming round an ellipse, one behind the other.
    for i, col in enumerate((koi, koi_white)):
        a = phase + i * math.pi
        for k in range(6):
            b = a - k * 0.18
            x, y = 15.5 + 9 * math.cos(b), 34 + 18 * math.sin(b)
            c = col if k < 4 else mix(col, deep, 0.4)
            if i == 0 and k in (1, 3):
                c = koi_white
            if i == 1 and k == 2:
                c = koi
            # Two pixels wide at the head, one at the tail.
            cv.put(round(x), round(y), c)
            if k < 4:
                cv.put(round(x) + 1, round(y), mix(c, deep, 0.15))
                cv.put(round(x), round(y) + 1, mix(c, deep, 0.25))
    # Ripple rings: three, each growing over the loop from its own spot.
    for i, (rx, ry) in enumerate(((8, 14), (24, 48), (20, 24))):
        p = (frame / frames + i / 3) % 1
        r = 1 + p * 7
        fade = 1 - p
        for a in range(0, 360, 10):
            t = math.radians(a)
            x, y = round(rx + math.cos(t) * r), round(ry + math.sin(t) * r * 0.8)
            if 0 <= x < W and 0 <= y < H:
                cv.put(x, y, mix(cv.get(x, y), ring, 0.8 * fade))
    # Lily pads with their notch and veins; the pads bob very slightly.
    for i, (px, py, r) in enumerate(((7, 30, 4.5), (24, 12, 4), (25, 38, 5), (9, 54, 4), (16, 8, 2.5))):
        bob = 0.4 * math.sin(phase + i)
        for y in range(int(py - r - 1), int(py + r + 2)):
            for x in range(int(px - r - 1), int(px + r + 2)):
                dx, dy = x - px, y - py - bob
                if dx * dx + dy * dy > r * r:
                    continue
                ang = math.atan2(dy, dx)
                if abs(ang - 0.6) < 0.25:
                    continue
                c = pad_light if dx * dx + dy * dy > (r - 1) ** 2 else pad
                if int(round(math.degrees(ang))) % 45 == 0:
                    c = pad_dark
                cv.put(x, y, c)
    # Lotus flower on the biggest pad.
    lx, ly = 25, 37
    for a in range(0, 360, 45):
        t = math.radians(a + 22)
        for k in range(1, 4):
            cv.put(round(lx + math.cos(t) * k), round(ly + math.sin(t) * k), petal if k < 3 else petal_dark)
    cv.disc(lx, ly, 1, heart)
    # Reeds along the left bank, swaying.
    for i, rx in enumerate((2, 4, 29)):
        for k in range(10):
            x = rx + math.sin(phase + i) * k / 10
            cv.put(round(x), 63 - 4 - k if rx < 10 else 20 - k, rgb("#4F6E2A"))
        cv.put(round(rx + math.sin(phase + i)), 63 - 14 if rx < 10 else 10, rgb("#6B4024"))
    hem(cv, deep, ring)
    return cv.img


def shooting_stars(frame, frames):
    """Night over the Safari camp: the Milky Way, twinkling stars, a shooting star crossing once per loop."""
    sky_top, sky_low = rgb("#04061A"), rgb("#1A1F4A")
    band, band_light = rgb("#3A3E7A"), rgb("#8E8FC8")
    hills, tent, tent_light = rgb("#0A0C14"), rgb("#2A2230"), rgb("#F2B45A")
    cv = Canvas(sky_top)
    for y in range(H):
        for x in range(W):
            c = mix(sky_top, sky_low, min(1, y / 48))
            # Milky Way: a diagonal band of dithered light.
            d = abs((x - 4) - (y - 4) * 0.45 - 6)
            if d < 6:
                v = noise(x, y, 13)
                c = mix(c, band, 0.6 * (1 - d / 6))
                if v > 0.9:
                    c = band_light
            cv.put(x, y, c)
    for i in range(40):
        x, y = int(noise(i, 2, 14) * (W - 4)) + 2, int(noise(i, 3, 14) * 44) + 2
        beat = (frame + i * 5) % frames
        c = rgb("#FFFFFF") if beat == 0 else rgb("#C8CEF5") if beat < 3 else rgb("#7A80B8")
        cv.put(x, y, c)
        if beat == 0 and i % 4 == 0:
            for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                cv.put(x + dx, y + dy, rgb("#9AA2E0"))
    # Shooting star: crosses during the first half of the loop, a fading trail behind.
    if frame < frames // 2:
        p = frame / (frames // 2)
        hx, hy = 28 - p * 22, 6 + p * 14
        for k in range(8):
            c = mix(rgb("#FFFFFF"), sky_low, k / 8)
            cv.put(round(hx + k * 1.3), round(hy - k * 0.8), c)
        cv.put(round(hx), round(hy), rgb("#FFFFFF"))
    # Hills and the camp tent, its door glowing; the glow wavers.
    for x in range(W):
        for y in range(50 + int(2 * math.sin(x / 4.5)), H):
            cv.put(x, y, hills)
    glow = mix(tent_light, rgb("#C9782E"), 0.5 + 0.5 * math.sin(frame / frames * 2 * math.pi * 2))
    for k in range(9):
        y = 42 + k
        for x in range(round(20 - k * 0.9), round(20 + k * 0.9) + 1):
            cv.put(x, y, tent)
        if k > 3:
            for x in range(20 - (k - 4) // 2, 20 + (k - 4) // 2 + 1):
                cv.put(x, y, glow)
    cv.put(20, 41, rgb("#5A4A3A"))
    hem(cv, sky_top, band_light)
    return cv.img


def sun_rays(frame, frames):
    """Midday sun: twelve rays turning slowly over the savanna, heat shimmer on the horizon."""
    sky, sky_ray, sun, sun_core = rgb("#F29A3C"), rgb("#F7BE5A"), rgb("#FFE08A"), rgb("#FFF6D6")
    earth, earth_dark, acacia_c = rgb("#A0522D"), rgb("#6E3518"), rgb("#3A1F12")
    cv = Canvas(sky)
    cx, cy = 15.5, 22
    turn = frame / frames * (2 * math.pi / 12)
    for y in range(H):
        for x in range(W):
            a = math.atan2(y - cy, x - cx) - turn
            # Twelve rays: alternate wedges of 15 degrees.
            c = sky_ray if math.cos(a * 12) > 0 else sky
            d = math.hypot(x - cx, y - cy)
            c = mix(c, sun, max(0.0, 0.5 - d / 40))
            cv.put(x, y, c)
    cv.disc(cx, cy, 7.5, sun)
    cv.disc(cx, cy, 5, sun_core)
    # Horizon with shimmer: the line wobbles a little from frame to frame.
    phase = frame / frames * 2 * math.pi
    for x in range(W):
        top = 40 + round(0.8 * math.sin(x * 0.9 + phase * 2))
        for y in range(top, H):
            t = (y - 40) / (H - 40)
            c = mix(earth, earth_dark, t)
            if noise(x, y, 15) < 0.12:
                c = mix(c, sky, 0.3)
            cv.put(x, y, c)
    acacia(cv, 24, 41, acacia_c, 1.0)
    acacia(cv, 6, 42, acacia_c, 0.6)
    # Cracked earth lines.
    for i in range(5):
        x, y = int(noise(i, 4, 16) * 26) + 3, int(noise(i, 5, 16) * 14) + 46
        cv.line(x, y, x + 3, y + 1, earth_dark)
        cv.line(x + 3, y + 1, x + 4, y + 3, earth_dark)
    hem(cv, earth_dark, sun)
    return cv.img


def item_icon(crest):
    """Inventory icon: a small banner on its pole, its cloth a shrunk copy of the crest."""
    icon = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    pole, bar = (0x5A, 0x3B, 0x22, 255), (0x7A, 0x52, 0x31, 255)
    for y in range(1, 16):
        icon.putpixel((7, y), pole)
    for x in range(3, 13):
        icon.putpixel((x, 1), bar)
    small = crest.resize((8, 13), Image.NEAREST)
    icon.paste(small, (4, 2), small)
    return icon


def save(img, *parts):
    path = os.path.join(*parts)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path)


def save_animated(name, draw, frames=FRAMES, ticks=FRAME_TICKS):
    """Stacks the frames of an animated picture and writes its .png.mcmeta.

    The first pictures draw from (frame) and read FRAMES; the later ones
    take (frame, frames), so their frame count is chosen here.
    """
    two_args = draw.__code__.co_argcount == 2
    strip = Image.new("RGBA", (W, H * frames))
    for f in range(frames):
        strip.paste(draw(f, frames) if two_args else draw(f), (0, f * H))
    save(strip, BANNERS, name + ".png")
    with open(os.path.join(BANNERS, name + ".png.mcmeta"), "w", encoding="utf-8") as fh:
        json.dump({"animation": {"width": W, "height": H, "frametime": ticks, "interpolate": False}}, fh,
                  indent=2)
        fh.write("\n")


def main():
    crest = safari_crest()
    save(crest, BANNERS, "safari_crest.png")
    save(ranger_compass(), BANNERS, "ranger_compass.png")
    save(paw_trail(), BANNERS, "paw_trail.png")
    save(tropical_leaves(), BANNERS, "tropical_leaves.png")
    save(sunset_savanna(), BANNERS, "sunset_savanna.png")
    save(kente(), BANNERS, "woven_bands.png")
    save_animated("river_waves", river_waves)
    save_animated("fireflies", fireflies)
    save_animated("waterfall", waterfall, WATERFALL_FRAMES, FAST_FRAME_TICKS)
    save_animated("campfire", campfire, FRAMES, CAMPFIRE_FRAME_TICKS)
    save_animated("rainstorm", rainstorm, FRAMES, FAST_FRAME_TICKS)
    save_animated("windy_grass", windy_grass)
    save_animated("lily_pond", lily_pond, SLOW_FRAMES)
    save_animated("shooting_stars", shooting_stars, FRAMES, CAMPFIRE_FRAME_TICKS)
    save_animated("sun_rays", sun_rays, SLOW_FRAMES)
    save(item_icon(crest), ROOT, "item", "safari_banner.png")


if __name__ == "__main__":
    main()
