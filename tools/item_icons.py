#!/usr/bin/env python3
"""
Turns the hand-made item pictures in tools/assets into item textures.

The sources are large drawings (256 px); an item texture is drawn at 16 GUI
pixels, so a 256 px texture is shrunk by the game with nearest-neighbour
sampling and comes out speckled. This script shrinks them once, carefully:
crops to the drawing, centres it on a square, downsamples with a box filter
(an average of each block of source pixels), then makes every pixel either
opaque or transparent so the edge stays crisp like vanilla items.

Usage (from the repository root; needs Pillow):
    python3 tools/item_icons.py
Deterministic.
"""

import os

from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SOURCES = os.path.join(ROOT, "tools", "assets")
OUT = os.path.join(ROOT, "common", "src", "main", "resources", "assets", "cobblemon_safari", "textures", "item")

# Texture size. 32 keeps the drawing's details (the giraffe, the perforations)
# readable at GUI scale 2 and above; 16 loses them, 64 adds nothing on screen.
SIZE = 32

# Alpha above which a pixel is kept opaque. Lower thickens the outline,
# higher eats into thin parts.
ALPHA_CUTOFF = 110

# Source file in tools/assets -> item texture name.
ICONS = {
    "safari_ticket_source.png": "safari_ticket.png",
}


def icon(source):
    """One source drawing as a SIZE x SIZE item texture."""
    image = Image.open(source).convert("RGBA")
    image = image.crop(image.getbbox())
    side = max(image.size)
    square = Image.new("RGBA", (side, side))
    square.paste(image, ((side - image.width) // 2, (side - image.height) // 2))
    # Premultiplied alpha before averaging, or transparent pixels (black RGB)
    # darken the edge.
    small = square.convert("RGBa").resize((SIZE, SIZE), Image.BOX).convert("RGBA")
    pixels = small.load()
    for y in range(SIZE):
        for x in range(SIZE):
            r, g, b, a = pixels[x, y]
            pixels[x, y] = (r, g, b, 255) if a >= ALPHA_CUTOFF else (0, 0, 0, 0)
    return small


def main():
    os.makedirs(OUT, exist_ok=True)
    for source, target in ICONS.items():
        icon(os.path.join(SOURCES, source)).save(os.path.join(OUT, target), optimize=True)
        print("wrote " + target)


if __name__ == "__main__":
    main()
