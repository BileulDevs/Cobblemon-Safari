#!/usr/bin/env python3
"""
Draws the Safari Gate's portal texture: a 16x16 animated sprite in savanna
colours with only gentle movement.

Why drawn by code: the texture is original (no Mojang pixels) and its palette
and pace can be changed here. Several styles are kept in STYLES; STYLE picks
the one shipped.

Usage (from the repository root; needs Pillow):
    python3 tools/portal_textures.py                 # writes the shipped sprite
    python3 tools/portal_textures.py --previews DIR  # every style as an animated GIF

Output (overwritten):
    common/src/main/resources/assets/cobblemon_safari/textures/block/safari_gate_portal.png (+ .mcmeta)
Deterministic.
"""

import json
import math
import os
import sys

from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "common", "src", "main", "resources", "assets", "cobblemon_safari", "textures", "block")

SIZE = 16
# Opacity of the pane. The nether portal is about 190: the world behind
# shows a little. Lower is more see-through, 255 is an opaque wall.
ALPHA = 200
# Ticks per animation frame. Vanilla's portal uses 1-2 (fast swirl); high
# values keep the movement calm. Changing it changes the pace only.
FRAMETIME = 6

# Savanna palette: dusk sky to dry grass.
DEEP = (120, 50, 40)
TERRACOTTA = (186, 86, 48)
ORANGE = (232, 136, 52)
GOLD = (246, 196, 80)
SAND = (250, 226, 150)
OLIVE = (150, 156, 64)
GREEN = (96, 132, 56)
TEAL = (52, 120, 110)

BAYER4 = [[0, 8, 2, 10], [12, 4, 14, 6], [3, 11, 1, 9], [15, 7, 13, 5]]


def lerp(a, b, t):
    """Colour between a and b."""
    return tuple(int(round(a[i] + (b[i] - a[i]) * t)) for i in range(3))


def ramp(colours, t, x, y):
    """Position t (0..1) on a colour ramp, dithered between neighbouring steps like a pixel artist would."""
    t = max(0.0, min(0.999, t))
    scaled = t * (len(colours) - 1)
    i = int(scaled)
    frac = scaled - i
    return colours[i + 1] if frac * 16 > BAYER4[y % 4][x % 4] else colours[i]


def sunset_bands(frame, frames):
    """
    Horizontal bands from deep red at the top to green at the bottom, like
    a savanna dusk, with a soft light band drifting down one pixel per frame.
    The bands themselves do not move: the only motion is that glint.
    """
    colours = [DEEP, TERRACOTTA, ORANGE, GOLD, SAND, GOLD, OLIVE, GREEN]
    img = Image.new("RGBA", (SIZE, SIZE))
    px = img.load()
    glint = (frame * SIZE // frames) % SIZE
    for y in range(SIZE):
        for x in range(SIZE):
            colour = ramp(colours, (y + 0.5) / SIZE, x, y)
            if (y - glint) % SIZE in (0, 1):
                colour = lerp(colour, SAND, 0.35 if (y - glint) % SIZE == 0 else 0.15)
            px[x, y] = colour + (ALPHA,)
    return img


def golden_grass(frame, frames):
    """
    Tall golden grass blades over a teal-to-olive ground, swaying by one
    pixel at the tips, slowly and not all together.
    """
    img = Image.new("RGBA", (SIZE, SIZE))
    px = img.load()
    for y in range(SIZE):
        for x in range(SIZE):
            px[x, y] = ramp([TEAL, GREEN, OLIVE, ORANGE], (y + 0.5) / SIZE, x, y) + (ALPHA,)
    phase = frame / frames * 2 * math.pi
    for bx in range(0, SIZE, 3):
        height = 9 + (bx * 5) % 5
        sway = round(math.sin(phase + bx * 0.9))
        for dy in range(height):
            y = SIZE - 1 - dy
            lean = sway if dy > height * 0.6 else 0
            x = (bx + lean) % SIZE
            px[x, y] = (SAND if dy >= height - 2 else GOLD) + (ALPHA,)
    return img


def tribal_mosaic(frame, frames):
    """
    A static savanna mosaic (diamonds of terracotta, ochre and green on a
    warm ground) whose light pulses gently. No shape moves at all.
    """
    img = Image.new("RGBA", (SIZE, SIZE))
    px = img.load()
    pulse = 0.5 + 0.5 * math.sin(frame / frames * 2 * math.pi)
    for y in range(SIZE):
        for x in range(SIZE):
            # Diamond distance from the centre of each 8x8 cell.
            d = abs((x % 8) - 3.5) + abs((y % 8) - 3.5)
            cell = (x // 8 + y // 8) % 2
            if d < 1.5:
                colour = SAND
            elif d < 3.0:
                colour = GOLD if cell else ORANGE
            elif d < 4.5:
                colour = TERRACOTTA if cell else GREEN
            else:
                colour = DEEP if cell else TEAL
            colour = lerp(colour, SAND, 0.18 * pulse)
            px[x, y] = colour + (ALPHA,)
    return img


def amber_dust(frame, frames):
    """
    A warm amber field, darker toward the edges of each block, with a few
    golden specks that brighten and fade in turn: twinkling, no drift.
    """
    img = Image.new("RGBA", (SIZE, SIZE))
    px = img.load()
    for y in range(SIZE):
        for x in range(SIZE):
            # Seamless soft vignette: cosine bumps repeat exactly every block.
            v = 0.5 + 0.25 * math.cos(2 * math.pi * (x + 0.5) / SIZE) + 0.25 * math.cos(2 * math.pi * (y + 0.5) / SIZE)
            px[x, y] = ramp([TERRACOTTA, ORANGE, GOLD], 1 - v * 0.9, x, y) + (ALPHA,)
    specks = [(2, 3), (11, 2), (6, 9), (13, 12), (3, 13), (9, 6), (14, 7), (7, 14)]
    for i, (x, y) in enumerate(specks):
        glow = max(0.0, math.sin((frame / frames + i / len(specks)) * 2 * math.pi))
        if glow > 0.2:
            px[x, y] = lerp(px[x, y][:3], SAND, glow) + (ALPHA,)
    return img


def woven_straw(frame, frames):
    """
    A woven straw mat, ochre and gold strands over and under, with a green
    border thread; static, the light breathing slowly.
    """
    img = Image.new("RGBA", (SIZE, SIZE))
    px = img.load()
    pulse = 0.5 + 0.5 * math.sin(frame / frames * 2 * math.pi)
    for y in range(SIZE):
        for x in range(SIZE):
            over = ((x // 4) + (y // 4)) % 2 == 0
            # Strand shading: lighter in the middle of each 4-pixel strand.
            along = (y % 4) if over else (x % 4)
            base = GOLD if over else ORANGE
            colour = lerp(base, SAND, 0.35) if along in (1, 2) else lerp(base, TERRACOTTA, 0.3)
            if x % 16 == 0 or y % 16 == 0:
                colour = GREEN
            px[x, y] = lerp(colour, SAND, 0.15 * pulse) + (ALPHA,)
    return img


def heat_haze(frame, frames):
    """
    Bands of savanna colour running diagonally, like heat over dry grass,
    sliding one pixel every few ticks. Seamless across blocks.
    """
    colours = [TERRACOTTA, ORANGE, GOLD, SAND, GOLD, OLIVE, GREEN, OLIVE, GOLD, ORANGE]
    img = Image.new("RGBA", (SIZE, SIZE))
    px = img.load()
    shift = frame * SIZE / frames
    for y in range(SIZE):
        for x in range(SIZE):
            t = ((x + y + shift) % SIZE) / SIZE
            px[x, y] = ramp(colours, t, x, y) + (ALPHA,)
    return img


def wavy_dunes(frame, frames):
    """
    Soft wavy stripes of sand, ochre and terracotta, like dunes seen from
    above; the waves roll by a fraction of a pixel per frame.
    """
    colours = [TERRACOTTA, ORANGE, GOLD, SAND, GOLD, ORANGE]
    img = Image.new("RGBA", (SIZE, SIZE))
    px = img.load()
    phase = frame / frames * 2 * math.pi
    for y in range(SIZE):
        for x in range(SIZE):
            wave = 1.6 * math.sin(2 * math.pi * x / SIZE + phase)
            t = ((y + wave) % (SIZE / 2)) / (SIZE / 2)
            px[x, y] = ramp(colours, t, x, y) + (ALPHA,)
    return img


# name: (drawing function, number of frames)
STYLES = {
    "sunset": (sunset_bands, 16),
    "grass": (golden_grass, 8),
    "mosaic": (tribal_mosaic, 12),
    "amber_dust": (amber_dust, 16),
    "woven_straw": (woven_straw, 12),
    "heat_haze": (heat_haze, 16),
    "wavy_dunes": (wavy_dunes, 16),
}
STYLE = "amber_dust"


def sheet(style):
    """The animation strip: frames stacked vertically, as Minecraft expects."""
    draw, frames = STYLES[style]
    strip = Image.new("RGBA", (SIZE, SIZE * frames))
    for f in range(frames):
        strip.paste(draw(f, frames), (0, f * SIZE))
    return strip


def preview(style, out):
    """An animated GIF of a 3x3 pane wall, enlarged, at the game's pace (20 ticks per second)."""
    draw, frames = STYLES[style]
    scale = 8
    images = []
    for f in range(frames):
        tile = draw(f, frames)
        wall = Image.new("RGBA", (SIZE * 3, SIZE * 3))
        for i in range(3):
            for j in range(3):
                wall.paste(tile, (i * SIZE, j * SIZE))
        backdrop = Image.new("RGBA", wall.size, (90, 130, 200, 255))
        images.append(Image.alpha_composite(backdrop, wall).convert("RGB")
                      .resize((SIZE * 3 * scale, SIZE * 3 * scale), Image.NEAREST))
    images[0].save(os.path.join(out, f"portal_{style}.gif"), save_all=True, append_images=images[1:],
                   duration=FRAMETIME * 50, loop=0)


def main():
    if len(sys.argv) == 3 and sys.argv[1] == "--previews":
        os.makedirs(sys.argv[2], exist_ok=True)
        for style in STYLES:
            preview(style, sys.argv[2])
        print(f"Wrote {len(STYLES)} previews to {sys.argv[2]}")
        return
    os.makedirs(OUT, exist_ok=True)
    sheet(STYLE).save(os.path.join(OUT, "safari_gate_portal.png"), optimize=True)
    with open(os.path.join(OUT, "safari_gate_portal.png.mcmeta"), "w", encoding="utf-8", newline="\n") as f:
        # interpolate: frames blend into each other, which softens the motion further.
        json.dump({"animation": {"frametime": FRAMETIME, "interpolate": True}}, f, indent=2)
        f.write("\n")
    print(f"Wrote safari_gate_portal.png (style {STYLE})")


if __name__ == "__main__":
    main()
