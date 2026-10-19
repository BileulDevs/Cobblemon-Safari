#!/usr/bin/env python3
"""
Draws the icon of the Safari timer HUD: a stopwatch made of a Safari Ball
(16 x 16, one texel per GUI pixel like a hotbar item).

The ball keeps its camouflage cap; its front is a white dial with a black
pin in the middle; a gold crown sits on top and a gold pusher on the right,
as on a stopwatch. Colours of the cap come from Cobblemon's own Safari Ball
texture, so the icon reads as that ball.

The dial has no hand: SafariTimerHud draws the red hand turning around the
pin, faster as time runs out. Its HAND_CELLS name dial cells of this
picture (pin at x 8, y 8; dial 5 cells across); moving the dial means
changing them.

The picture is written cell by cell below (one character per pixel, see
PALETTE) rather than drawn with shapes: at 16 x 16 every pixel is a choice.

Usage (from the repository root; needs Pillow):
    python3 tools/timer_icon.py
Deterministic.
"""

import os

from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "common", "src", "main", "resources", "assets", "cobblemon_safari", "textures", "gui",
                   "safari_timer.png")

# One character per pixel. Changing a colour here changes every pixel using it.
PALETTE = {
    ".": (0, 0, 0, 0),
    "K": (40, 40, 40, 255),      # outline, band and the dial's pin
    "G": (49, 158, 54, 255),     # camouflage green
    "t": (224, 186, 148, 255),   # camouflage tan, light
    "T": (201, 160, 118, 255),   # camouflage tan
    "b": (151, 114, 77, 255),    # camouflage brown
    "L": (163, 187, 61, 255),    # camouflage lime
    "o": (107, 128, 20, 255),    # camouflage olive
    "H": (255, 255, 255, 255),   # highlight on the cap
    "F": (244, 238, 222, 255),   # dial
    "W": (177, 170, 176, 255),   # lower shell, light
    "w": (154, 147, 153, 255),   # lower shell
    "s": (107, 100, 106, 255),   # lower shell, shade
    "S": (83, 77, 82, 255),      # lower shell, dark
    "Y": (236, 196, 70, 255),    # crown and pusher
    "y": (176, 132, 36, 255),    # crown and pusher, shade
}

# The drawn part (columns 2 to 13, rows 0 to 13) is what SafariTimerHud lays
# out and centres on the hotbar (its ICON_DRAWN_* constants): drawing beyond
# it means changing them.
PIXELS = """
.......YY.......
......yYYy......
.......KK....y..
.....KKGGKK.yY..
....KtTLGGTKy...
...KHLGKKKbGK...
..KTGGKFFFKoGK..
..KGGKFFFFFKbK..
..KKKKFFKFFKKK..
..KssKFFFFFKsK..
...KssKFFFKsK...
...KWsKKKKsWK...
....KSwWWwSK....
......KKKK......
................
................
"""


def main():
    rows = PIXELS.strip("\n").split("\n")
    assert len(rows) == 16 and all(len(row) == 16 for row in rows), "the icon must be 16 x 16"
    image = Image.new("RGBA", (16, 16))
    for y, row in enumerate(rows):
        for x, cell in enumerate(row):
            image.putpixel((x, y), PALETTE[cell])
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    image.save(OUT)
    print("Wrote", os.path.relpath(OUT, ROOT))


if __name__ == "__main__":
    main()
