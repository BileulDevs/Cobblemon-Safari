"""Draws the icons of the Safari's event marks for Cobblemon's mark list.

Output, in common/src/main/resources/assets/cobblemon_safari/textures/gui/mark/:
  mark_safari_star.png     the special of the day: a white star on gold
  mark_safari_swarm.png    the swarm: a cluster of white dots on orange
  mark_safari_moon.png     the full moon: a pale moon with craters on night blue
  mark_safari_angler.png   the fished Pokemon: a white hook on water blue

Same frame as the Safari Mark (tools/safari_mark_icon.py) and Cobblemon's own
icons: 32x32, a disc of the mark's colour inside a white band and a dark
outline, both two pixels wide, one pixel short of the edge. Drawn from shapes
(no source image), so each can be tuned here and redrawn.

Each disc colour is the mark's "titleColor" in data/cobblemon_safari/marks/:
change both together.

Run from the repository root: python3 tools/safari_event_mark_icons.py
"""

import math
from pathlib import Path

from PIL import Image

SIZE = 32
CENTRE = 15.5

WHITE = (255, 255, 255, 255)
# Cobblemon's outline colour (sampled from its mark_uncommon.png).
OUTLINE = (47, 47, 47, 255)
CLEAR = (0, 0, 0, 0)

# Disc, white band and outline radii, as in the Safari Mark.
DISC_RADIUS = 9.5
BAND_RADIUS = DISC_RADIUS + 2
OUTLINE_RADIUS = BAND_RADIUS + 2

OUT_DIR = Path("common/src/main/resources/assets/cobblemon_safari/textures/gui/mark")

# Disc colours: the marks' titleColor.
GOLD = (201, 154, 26, 255)        # C99A1A
ORANGE = (200, 100, 42, 255)      # C8642A
NIGHT = (59, 74, 140, 255)        # 3B4A8C
WATER = (42, 122, 184, 255)       # 2A7AB8

MOON = (240, 232, 190, 255)
CRATER = (196, 188, 150, 255)


def inside(x: float, y: float, cx: float, cy: float, r: float) -> bool:
    """Whether the centre of pixel (x, y) lies within the circle."""
    return (x + 0.5 - cx - 0.5) ** 2 + (y + 0.5 - cy - 0.5) ** 2 <= r * r


def in_polygon(px: float, py: float, points) -> bool:
    """Even-odd test of a point against a polygon."""
    result = False
    j = len(points) - 1
    for i, (xi, yi) in enumerate(points):
        xj, yj = points[j]
        if (yi > py) != (yj > py) and px < (xj - xi) * (py - yi) / (yj - yi) + xi:
            result = not result
        j = i
    return result


# A five-pointed star centred on the disc, point up.
STAR_OUTER = 7.0
STAR_INNER = 2.9
STAR = [(CENTRE + (STAR_OUTER if k % 2 == 0 else STAR_INNER) * math.sin(k * math.pi / 5),
         CENTRE + 0.6 - (STAR_OUTER if k % 2 == 0 else STAR_INNER) * math.cos(k * math.pi / 5))
        for k in range(10)]


def star(x: int, y: int):
    return WHITE if in_polygon(x + 0.5, y + 0.5, STAR) else GOLD


# Seven dots of a swarm, as (x, y, radius): a loose cluster.
SWARM_DOTS = [(15.5, 15.5, 2.0), (11.0, 12.0, 1.6), (20.0, 11.5, 1.6), (10.5, 18.5, 1.5),
              (20.5, 19.0, 1.5), (15.5, 9.5, 1.3), (15.5, 21.5, 1.3)]


def swarm(x: int, y: int):
    return WHITE if any(inside(x, y, dx, dy, dr) for dx, dy, dr in SWARM_DOTS) else ORANGE


# The moon fills most of the disc, with three craters.
MOON_RADIUS = 7.0
CRATERS = [(13.0, 12.5, 1.6), (18.5, 16.0, 2.0), (13.5, 19.0, 1.2)]


def moon(x: int, y: int):
    if not inside(x, y, CENTRE, CENTRE, MOON_RADIUS):
        return NIGHT
    if any(inside(x, y, cx, cy, cr) for cx, cy, cr in CRATERS):
        return CRATER
    return MOON


def hook(x: int, y: int):
    """A fish hook: an eye on top, a shank, a curved bend and a barb."""
    # Eye: a small ring at the top of the shank.
    eye = inside(x, y, 18.0, 8.5, 2.0) and not inside(x, y, 18.0, 8.5, 0.9)
    # Shank: two pixels wide, down the right side.
    shank = x in (17, 18) and 10 <= y <= 18
    # Bend: the lower half of a ring, from the shank round to the left.
    bend = (inside(x, y, 14.0, 18.0, 4.6) and not inside(x, y, 14.0, 18.0, 2.6) and y >= 18)
    # Point rising on the left, with its barb.
    point = x in (9, 10) and 14 <= y <= 18
    barb = (x, y) in ((11, 15), (11, 16), (12, 16))
    return WHITE if eye or shank or bend or point or barb else WATER


ICONS = {
    "mark_safari_star": star,
    "mark_safari_swarm": swarm,
    "mark_safari_moon": moon,
    "mark_safari_angler": hook,
}


def framed(glyph, x: int, y: int):
    """One pixel of an icon: the outline, the band, then the glyph on its disc."""
    if not inside(x, y, CENTRE, CENTRE, OUTLINE_RADIUS):
        return CLEAR
    if not inside(x, y, CENTRE, CENTRE, BAND_RADIUS):
        return OUTLINE
    if not inside(x, y, CENTRE, CENTRE, DISC_RADIUS):
        return WHITE
    return glyph(x, y)


def main() -> None:
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    for name, glyph in ICONS.items():
        image = Image.new("RGBA", (SIZE, SIZE), CLEAR)
        for y in range(SIZE):
            for x in range(SIZE):
                image.putpixel((x, y), framed(glyph, x, y))
        path = OUT_DIR / f"{name}.png"
        image.save(path)
        print(f"wrote {path}")


if __name__ == "__main__":
    main()
