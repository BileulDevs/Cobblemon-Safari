#!/usr/bin/env python3
"""
Turns the Escape Rope sprite in tools/assets into its 16 x 16 item texture.

The source (tools/assets/safari_escape_rope_source.png) is a screenshot of a
pixel-art sprite: each sprite pixel is a blurred block of about 11 x 11
screen pixels on a black background, and its grid is not a whole number of
screen pixels. This script reads one colour per sprite pixel (the median of
the block's centre, away from the blurred edges), snaps it to a small fixed
palette so the result has the flat colours of a vanilla item, drops the
black background, and fits the 17 x 15 sprite in 16 x 16: the leftmost
column, which only held part of the metal clip, is dropped and the handle
redrawn in the three columns that remain.

Usage (from the repository root; needs Pillow and numpy):
    python3 tools/escape_rope_icon.py
Deterministic.
"""

import colorsys
import os

import numpy as np
from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SOURCE = os.path.join(ROOT, "tools", "assets", "safari_escape_rope_source.png")
OUT = os.path.join(ROOT, "common", "src", "main", "resources", "assets", "cobblemon_safari", "textures", "item",
                   "safari_escape_rope.png")

SIZE = 16

# The sprite in the screenshot: its grid (columns, rows) and the screen box it
# covers (left, top, width, height), measured on the source. A new source
# needs new values; wrong ones sample between sprite pixels and blur colours.
GRID = (17, 15)
BOX = (17, 13, 183, 165)

# Half-size of the square sampled at the centre of each sprite pixel, in
# screen pixels. Larger reaches the blurred edges of the block.
SAMPLE_HALF = 2

# Source pixels whose RGB sum is below this are the black background.
BACKGROUND_SUM = 20

# Rope browns, from the outline to the highlight. A cell's brightness picks
# one (BROWN_STEPS are the luminance limits between them).
BROWN = [(38, 27, 18), (78, 53, 34), (122, 86, 54), (168, 124, 80), (204, 160, 113), (228, 190, 146)]
BROWN_STEPS = [40, 70, 105, 140, 170]

# The handle: the red and white of a Poké Ball and the grey of its metal clip.
RED = (206, 46, 52)
RED_DARK = (132, 27, 31)
WHITE = (232, 228, 236)
GREY = (146, 150, 166)
GREY_DARK = (58, 59, 66)

# Saturation above which a reddish cell is the handle's red, below which a
# cell is grey or white rather than brown.
RED_SATURATION = 0.45
GREY_SATURATION = 0.18

# The handle redrawn, bottom-left, rows of texture pixels from y 11:
# r/R dark and light red, g/G dark and light grey, W white, . transparent.
HANDLE_TOP = 11
HANDLE = [".rR", "rRR", "ggg", "WWG", ".G."]
HANDLE_COLOURS = {"r": RED_DARK, "R": RED, "g": GREY_DARK, "G": GREY, "W": WHITE}


def snap(colour):
    """The palette colour of one sampled sprite pixel."""
    r, g, b = (channel / 255 for channel in colour)
    hue, lightness, saturation = colorsys.rgb_to_hls(r, g, b)
    if saturation > RED_SATURATION and (hue < 0.03 or hue > 0.95):
        return RED if lightness > 0.38 else RED_DARK
    if saturation < GREY_SATURATION:
        return WHITE if lightness > 0.6 else GREY if lightness > 0.3 else GREY_DARK
    luminance = 0.3 * colour[0] + 0.59 * colour[1] + 0.11 * colour[2]
    return BROWN[sum(luminance >= step for step in BROWN_STEPS)]


def texture():
    """The 16 x 16 item texture."""
    source = np.array(Image.open(SOURCE).convert("RGB")).astype(int)
    columns, rows = GRID
    left, top, width, height = BOX
    pitch_x, pitch_y = width / columns, height / rows
    image = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    for row in range(rows):
        # Column 0 dropped (see the module doc); one empty row on top centres the sprite.
        for column in range(1, columns):
            cx = int(left + pitch_x * (column + 0.5))
            cy = int(top + pitch_y * (row + 0.5))
            block = source[cy - SAMPLE_HALF:cy + SAMPLE_HALF + 1, cx - SAMPLE_HALF:cx + SAMPLE_HALF + 1]
            colour = np.median(block.reshape(-1, 3), axis=0)
            if colour.sum() < BACKGROUND_SUM:
                continue
            image.putpixel((column - 1, row + 1), snap(colour) + (255,))
    for dy, line in enumerate(HANDLE):
        for x, key in enumerate(line):
            colour = (0, 0, 0, 0) if key == "." else HANDLE_COLOURS[key] + (255,)
            image.putpixel((x, HANDLE_TOP + dy), colour)
    return image


def main():
    texture().save(OUT, optimize=True)
    print("wrote " + os.path.relpath(OUT, ROOT))


if __name__ == "__main__":
    main()
