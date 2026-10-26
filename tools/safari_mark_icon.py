"""Draws the Safari Mark's icon for Cobblemon's mark list.

Output: common/src/main/resources/assets/cobblemon_safari/textures/gui/mark/mark_safari.png

Cobblemon's mark icons are 32x32: a glyph in one colour with white details,
inside a white band and a dark outline, both two pixels wide. This one is a
Safari Ball: the green of the mark's title colour, a lighter green for its
camouflage spots, a white band and a white button. Drawn from shapes (no
source image), so it can be tuned here and redrawn.

Run from the repository root: python3 tools/safari_mark_icon.py
"""

from pathlib import Path

from PIL import Image

SIZE = 32
CENTRE = 15.5

# Same as the mark's "titleColor" in data/cobblemon_safari/marks/mark_safari.json.
GREEN = (106, 142, 58, 255)
# The spots: GREEN halfway to white, the tone Cobblemon's icons use for shading.
LIGHT_GREEN = (180, 199, 156, 255)
WHITE = (255, 255, 255, 255)
# Cobblemon's outline colour (sampled from its mark_uncommon.png).
OUTLINE = (47, 47, 47, 255)
CLEAR = (0, 0, 0, 0)

# Radii in pixels: the ball, then the white band and the outline around it.
# 9.5 + 2 + 2 leaves the icon one pixel short of the edge, like Cobblemon's.
BALL_RADIUS = 9.5
BAND_RADIUS = BALL_RADIUS + 2
OUTLINE_RADIUS = BAND_RADIUS + 2

# The button in the middle of the ball, and its green ring.
BUTTON_RADIUS = 3.0
BUTTON_RING = 1.6

# Camouflage spots on the ball's upper half, as (x, y, radius).
SPOTS = [(11.0, 10.0, 1.6), (17.5, 8.5, 1.4), (21.5, 12.0, 1.5), (13.5, 13.0, 1.0)]

OUT = Path("common/src/main/resources/assets/cobblemon_safari/textures/gui/mark/mark_safari.png")


def inside(x: float, y: float, cx: float, cy: float, r: float) -> bool:
    """Whether the centre of pixel (x, y) lies within the circle."""
    return (x + 0.5 - cx - 0.5) ** 2 + (y + 0.5 - cy - 0.5) ** 2 <= r * r


def pixel(x: int, y: int):
    """The colour of one pixel, from the outermost shape in."""
    if not inside(x, y, CENTRE, CENTRE, OUTLINE_RADIUS):
        return CLEAR
    if not inside(x, y, CENTRE, CENTRE, BAND_RADIUS):
        return OUTLINE
    if not inside(x, y, CENTRE, CENTRE, BALL_RADIUS):
        return WHITE
    if inside(x, y, CENTRE, CENTRE, BUTTON_RING):
        return GREEN
    if inside(x, y, CENTRE, CENTRE, BUTTON_RADIUS):
        return WHITE
    if inside(x, y, CENTRE, CENTRE, BUTTON_RADIUS + 1.2):
        return GREEN
    # The band across the middle, two pixels high, as on a Poké Ball.
    if y in (15, 16):
        return WHITE
    if y < 15 and any(inside(x, y, sx, sy, sr) for sx, sy, sr in SPOTS):
        return LIGHT_GREEN
    return GREEN


def main() -> None:
    image = Image.new("RGBA", (SIZE, SIZE), CLEAR)
    for y in range(SIZE):
        for x in range(SIZE):
            image.putpixel((x, y), pixel(x, y))
    OUT.parent.mkdir(parents=True, exist_ok=True)
    image.save(OUT)
    print(f"wrote {OUT}")


if __name__ == "__main__":
    main()
