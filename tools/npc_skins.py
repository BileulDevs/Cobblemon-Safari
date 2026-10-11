#!/usr/bin/env python3
"""
Derives the Safari Clerk's skin from the Safari Warden's: same face and hat,
but a green uniform shirt and khaki trousers, so players tell the shopkeeper
of the rest houses from the warden of the gates at a glance.

Usage (from the repository root; needs Pillow):
    python3 tools/npc_skins.py

Reads  common/src/main/resources/assets/cobblemon_safari/textures/npcs/safari_warden.png
Writes common/src/main/resources/assets/cobblemon_safari/textures/npcs/safari_clerk.png
Deterministic. Rerun after changing the warden's skin.
"""

import colorsys
import os

from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
NPCS = os.path.join(ROOT, "common", "src", "main", "resources", "assets", "cobblemon_safari", "textures", "npcs")

# Rows of the 64x64 skin below the head: body, arms and legs (both layers).
# The head (rows 0-15) is left untouched.
BODY_FIRST_ROW = 16

# The warden's off-white shirt: light, barely saturated pixels. Becomes a
# Safari green, keeping each pixel's lightness so the folds stay.
SHIRT_MIN_LIGHTNESS = 0.62
SHIRT_MAX_SATURATION = 0.30
SHIRT_HUE = 0.30          # green
SHIRT_SATURATION = 0.32
SHIRT_LIGHTNESS_SCALE = 0.52

# The warden's blue trousers: hue around blue. Become khaki.
TROUSERS_HUE_RANGE = (0.55, 0.72)
TROUSERS_HUE = 0.11       # khaki
TROUSERS_SATURATION = 0.40
# Trousers are dark; lightness is remapped to BASE + l * SCALE so they turn
# light tan while keeping their shading.
TROUSERS_LIGHTNESS_BASE = 0.38
TROUSERS_LIGHTNESS_SCALE = 0.7


def recolour(pixel):
    """New colour of one body pixel, or the same pixel when it is neither shirt nor trousers."""
    r, g, b, a = pixel
    if a == 0:
        return pixel
    h, l, s = colorsys.rgb_to_hls(r / 255, g / 255, b / 255)
    if l >= SHIRT_MIN_LIGHTNESS and s <= SHIRT_MAX_SATURATION:
        nr, ng, nb = colorsys.hls_to_rgb(SHIRT_HUE, l * SHIRT_LIGHTNESS_SCALE, SHIRT_SATURATION)
    elif TROUSERS_HUE_RANGE[0] <= h <= TROUSERS_HUE_RANGE[1] and s > 0.15:
        nr, ng, nb = colorsys.hls_to_rgb(TROUSERS_HUE, min(0.85, TROUSERS_LIGHTNESS_BASE + l * TROUSERS_LIGHTNESS_SCALE),
                                         TROUSERS_SATURATION)
    else:
        return pixel
    return int(nr * 255), int(ng * 255), int(nb * 255), a


def main():
    skin = Image.open(os.path.join(NPCS, "safari_warden.png")).convert("RGBA")
    px = skin.load()
    for y in range(BODY_FIRST_ROW, skin.height):
        for x in range(skin.width):
            px[x, y] = recolour(px[x, y])
    skin.save(os.path.join(NPCS, "safari_clerk.png"), optimize=True)
    print("Wrote safari_clerk.png")


if __name__ == "__main__":
    main()
