#!/usr/bin/env python3
"""
Generates the Safari Gate assets:

  * the Safari Warden's skin       assets/cobblemon_safari/textures/npcs/safari_warden.png
                                   (from tools/assets/safari_warden_skin.png)
  * the structure template         data/cobblemon_safari/structure/safari_gate.nbt

Run from the repository root:  python3 tools/safari_gate_assets.py
Requires Pillow. Re-running overwrites both files.

The gate's pane has no texture file: it is the nether portal's texture,
recoloured in game by the atlas source in assets/minecraft/atlases/blocks.json
(RecolouredSpriteSource).

The structure is written as a plain block list (no jigsaw blocks): the
worldgen JSON uses it as the single piece of a jigsaw structure. Its layout
must keep the four warden spots clear (see GateResidents.WARDEN_ALONG and
WARDEN_ACROSS): centre +-3 along the gate, +-2 across it.
"""

import gzip
import io
import math
import os
import struct

from PIL import Image

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "common", "src", "main", "resources")
ASSETS = os.path.join(ROOT, "assets", "cobblemon_safari")
DATA = os.path.join(ROOT, "data", "cobblemon_safari")

# Data version of Minecraft 1.21.1. Structure files written with an older
# number are run through the data fixers on load, a newer one is refused.
DATA_VERSION = 3955


# --------------------------------------------------------------------------
# Warden skin (vanilla 64x64 player layout, used with Cobblemon's steve model)
# --------------------------------------------------------------------------

# The skin itself is hand-made: tools/assets/safari_warden_skin.png, a normal
# 64x64 player skin (classic arms). This step only fills the corner Cobblemon
# reads its extra face parts from.
WARDEN_SKIN_SOURCE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "assets", "safari_warden_skin.png")

# Cobblemon's steve.geo.json samples its eyelids and mouth from the top-left
# 8x8 corner, which player skins leave empty. Each part is copied from the
# face pixels around it: eyelids from the skin just below each eye (row 13;
# the eyes are on row 12, the row the eyelid boxes cover), the mouth from the
# face where it sits (row 14), so it blends in unless an animation moves it.
# (corner pixel, face pixel) pairs:
WARDEN_FACE_PARTS = [
    ((1, 1), (9, 13)), ((2, 1), (10, 13)),     # right eyelid, front face
    ((1, 3), (13, 13)), ((2, 3), (14, 13)),    # left eyelid, front face
    ((1, 5), (11, 14)), ((2, 5), (12, 14)),    # mouth, front face
]


def warden_skin():
    """Copies the hand-made skin and fills Cobblemon's eyelid and mouth pixels."""
    image = Image.open(WARDEN_SKIN_SOURCE).convert("RGBA")
    if image.size != (64, 64):
        raise ValueError("the warden skin must be a 64x64 player skin, got %s" % (image.size,))
    # Every pixel of each part's box, not only the front: the eyelid boxes
    # are 1 deep, so their sides show when the head turns.
    for (cx, cy), (fx, fy) in WARDEN_FACE_PARTS:
        colour = image.getpixel((fx, fy))
        for dy in (-1, 0, 1):
            for dx in (-1, 0, 1, 2):
                x, y = cx + dx, cy + dy
                if 0 <= x < 8 and 0 <= y < 8 and image.getpixel((x, y))[3] == 0:
                    image.putpixel((x, y), colour)
    path = os.path.join(ASSETS, "textures", "npcs", "safari_warden.png")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    image.save(path)


# --------------------------------------------------------------------------
# Structure
# --------------------------------------------------------------------------

# Template footprint and height. The gate stands in the middle, along X,
# with the plaza a disc of this radius around it.
SIZE_X, SIZE_Y, SIZE_Z = 15, 9, 15
CENTRE = 7
PLAZA_RADIUS = 7.2
INNER_RADIUS = 4.6


class Nbt:
    """Minimal big-endian NBT writer: just the tag types a structure file needs."""

    END, BYTE, INT, STRING, LIST, COMPOUND = 0, 1, 3, 8, 9, 10

    def __init__(self):
        self.out = io.BytesIO()

    def name(self, text):
        data = text.encode("utf-8")
        self.out.write(struct.pack(">H", len(data)) + data)

    def payload(self, value):
        kind = self.kind(value)
        if kind == self.INT:
            self.out.write(struct.pack(">i", value))
        elif kind == self.STRING:
            self.name(value)
        elif kind == self.LIST:
            element = self.kind(value[0]) if value else self.END
            self.out.write(struct.pack(">bi", element, len(value)))
            for item in value:
                self.payload(item)
        elif kind == self.COMPOUND:
            for key, item in value.items():
                self.out.write(struct.pack(">b", self.kind(item)))
                self.name(key)
                self.payload(item)
            self.out.write(struct.pack(">b", self.END))

    def kind(self, value):
        if isinstance(value, bool):
            raise TypeError("booleans are not used in structure files")
        if isinstance(value, int):
            return self.INT
        if isinstance(value, str):
            return self.STRING
        if isinstance(value, list):
            return self.LIST
        if isinstance(value, dict):
            return self.COMPOUND
        raise TypeError(value)

    def root(self, value):
        self.out.write(struct.pack(">b", self.COMPOUND))
        self.name("")
        self.payload(value)
        return self.out.getvalue()


def gate_blocks():
    """Every block of the template as {(x, y, z): (name, properties)}."""
    blocks = {}

    def put(x, y, z, name, **props):
        blocks[(x, y, z)] = (name, {k: str(v).lower() for k, v in props.items()})

    # Air everywhere above the plaza clears grass and saplings; columns
    # outside the disc are left out, so the terrain there stays as it was.
    for x in range(SIZE_X):
        for z in range(SIZE_Z):
            r = math.hypot(x - CENTRE, z - CENTRE)
            if r > PLAZA_RADIUS:
                continue
            for y in range(1, SIZE_Y):
                put(x, y, z, "minecraft:air")
            # Paths do not count as dirt: no tree or flower grows on the plaza.
            if r <= INNER_RADIUS or abs(x - CENTRE) <= 1:
                put(x, 0, z, "minecraft:dirt_path")
            elif r > PLAZA_RADIUS - 1.0:
                put(x, 0, z, "minecraft:mossy_cobblestone")
            else:
                put(x, 0, z, "minecraft:coarse_dirt" if (x * 7 + z * 3) % 5 == 0 else "minecraft:dirt_path")

    # Posts and portal: a three-wide, four-high doorway along X.
    for y in range(1, 5):
        put(CENTRE - 2, y, CENTRE, "minecraft:stripped_jungle_log", axis="y")
        put(CENTRE + 2, y, CENTRE, "minecraft:stripped_jungle_log", axis="y")
        for x in range(CENTRE - 1, CENTRE + 2):
            put(x, y, CENTRE, "cobblemon_safari:safari_gate", axis="x")
    put(CENTRE - 2, 0, CENTRE, "minecraft:mossy_stone_bricks")
    put(CENTRE + 2, 0, CENTRE, "minecraft:mossy_stone_bricks")

    # Lintel, thatch roof, and a screen of the day on each face.
    for x in range(CENTRE - 3, CENTRE + 4):
        put(x, 5, CENTRE, "minecraft:jungle_log", axis="x")
    for x in range(CENTRE - 2, CENTRE + 3):
        for z in range(CENTRE - 1, CENTRE + 2):
            put(x, 6, z, "minecraft:hay_block", axis="y")
    for x in range(CENTRE - 1, CENTRE + 2):
        put(x, 7, CENTRE, "minecraft:hay_block", axis="x")
        put(x, 5, CENTRE + 1, "cobblemon_safari:safari_board", facing="south")
        put(x, 5, CENTRE - 1, "cobblemon_safari:safari_board", facing="north")

    # Stone pillars with lanterns at the ends of the lintel.
    for x in (CENTRE - 3, CENTRE + 3):
        put(x, 1, CENTRE, "minecraft:mossy_cobblestone")
        put(x, 2, CENTRE, "minecraft:lantern", hanging=False, waterlogged=False)

    # Lantern posts on the diagonals, clear of the warden spots (+-3, +-2).
    for dx, dz in ((-4, -4), (4, -4), (-4, 4), (4, 4)):
        x, z = CENTRE + dx, CENTRE + dz
        put(x, 1, z, "minecraft:jungle_fence")
        put(x, 2, z, "minecraft:jungle_fence")
        put(x, 3, z, "minecraft:lantern", hanging=False, waterlogged=False)

    # Bushes at the edge of the plaza, on the axis of the gate.
    for x in (CENTRE - 6, CENTRE + 6):
        put(x, 1, CENTRE, "minecraft:flowering_azalea")
        put(x, 0, CENTRE, "minecraft:moss_block")

    return blocks


def structure():
    """Writes the gate template."""
    blocks = gate_blocks()
    palette, index = [], {}
    entries = []
    for (x, y, z), (name, props) in sorted(blocks.items(), key=lambda item: (item[0][1], item[0][2], item[0][0])):
        key = (name, tuple(sorted(props.items())))
        if key not in index:
            index[key] = len(palette)
            state = {"Name": name}
            if props:
                state["Properties"] = dict(sorted(props.items()))
            palette.append(state)
        entries.append({"pos": [x, y, z], "state": index[key]})
    root = {
        "DataVersion": DATA_VERSION,
        "size": [SIZE_X, SIZE_Y, SIZE_Z],
        "palette": palette,
        "blocks": entries,
        "entities": [],
    }
    path = os.path.join(DATA, "structure", "safari_gate.nbt")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as out:
        # mtime=0: identical input gives an identical file, so re-running the
        # script does not show the structure as changed.
        out.write(gzip.compress(Nbt().root(root), mtime=0))
    return len(entries), len(palette)


if __name__ == "__main__":
    warden_skin()
    count, states = structure()
    print("warden skin, structure (%d blocks, %d states) written" % (count, states))
