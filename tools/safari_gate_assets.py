#!/usr/bin/env python3
"""
Generates the Safari Gate assets:

  * the Safari Warden's skin       assets/cobblemon_safari/textures/npcs/safari_warden.png
                                   (from tools/assets/safari_warden_skin.png)
  * the structure template         data/cobblemon_safari/structure/safari_gate.nbt

Run from the repository root:  python3 tools/safari_gate_assets.py
Requires Pillow. Writes the skin only: the gate shipped with the mod is now
built by hand in game. Add --structure to regenerate the old generated lodge
(which overwrites the hand-built template).

The gate's pane texture is drawn by tools/portal_textures.py.

The structure is a savanna lodge: the gate between two acacia and mud-brick
towers under a thatched roof, a palisade along its plane, an avenue with
lantern posts and flower beds, and around the plaza the warden's ticket
hut, a campfire, a water trough and an acacia. It is written as a plain
block list (no jigsaw blocks): the worldgen JSON uses it as the single piece
of a jigsaw structure. Its layout must keep the four warden spots clear (see
GateResidents.WARDEN_ALONG and WARDEN_ACROSS): centre +-3 along the gate,
+-2 across it, two blocks high.
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

# Warden spots, mirrored from GateResidents: checked to be clear after building.
WARDEN_ALONG, WARDEN_ACROSS = 3, 2

# Template footprint and height. The gate stands in the middle, along X
# (the template's "along" axis), its doorway facing Z ("across"); the jigsaw
# rotates the whole thing at random. CENTRE is the middle column.
SIZE_X, SIZE_Y, SIZE_Z = 27, 12, 27
CENTRE = 13

# Radii from the middle column, in blocks.
# PLAZA_RADIUS: the paved plaza; BORDER_RADIUS: its ring of mud bricks;
# CLEAR_RADIUS: up to here the template clears the ground above (trees,
# grass) and lays its own grass, so the outbuildings stand in a tidy clearing.
# Beyond it the terrain is left as generated. Growing CLEAR_RADIUS past the
# template's half-width does nothing; the dry-ground check of
# worldgen/structure/safari_gate.json (footprint_radius) should cover it.
PLAZA_RADIUS = 9.5
BORDER_RADIUS = 10.5
CLEAR_RADIUS = 13.5

# The warden's Pokémon roam within gate.roamRadius (8 by default), at least
# 2 blocks across from the gate's plane, on the standable spot nearest the
# gate's floor level (GateResidents.standingSpot, 6 blocks up or down). The
# outbuildings still sit beyond ROAM_RADIUS and the roof fringe is leaves,
# so the plaza stays open for them to wander and nothing low enough to be
# reached stands on a roof.
ROAM_RADIUS = 8

# Palette: savanna lodge. Acacia wood, mud bricks, terracotta, hay thatch.
LOG = "minecraft:acacia_log"
STRIPPED = "minecraft:stripped_acacia_log"
PLANKS = "minecraft:acacia_planks"
FENCE = "minecraft:acacia_fence"
THATCH = "minecraft:hay_block"
LEAVES = "minecraft:acacia_leaves"
MUD = "minecraft:mud_bricks"
CLAY = "minecraft:orange_terracotta"

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
    """Every block of the template as {(x, y, z): (name, properties)}, built on top of each other in order."""
    blocks = {}

    def put(dx, y, dz, name, **props):
        """Places a block relative to the middle column (dx along the gate, dz across it)."""
        x, z = CENTRE + dx, CENTRE + dz
        if 0 <= x < SIZE_X and 0 <= y < SIZE_Y and 0 <= z < SIZE_Z:
            blocks[(x, y, z)] = (name, {k: str(v).lower() for k, v in props.items()})

    def leaves(dx, y, dz):
        # persistent: placed leaves far from a log would otherwise decay.
        put(dx, y, dz, LEAVES, distance=1, persistent=True, waterlogged=False)

    def lantern(dx, y, dz, hanging=False):
        put(dx, y, dz, "minecraft:lantern", hanging=hanging, waterlogged=False)

    def fence(dx, y, dz, along=False, across=False):
        # Connections set by hand: a template keeps the states it is given.
        put(dx, y, dz, FENCE, east=along, west=along, north=across, south=across, waterlogged=False)

    ground(put)
    gatehouse(put, leaves, lantern)
    palisade(put, lantern, fence)
    avenue(put, leaves, lantern, fence)
    warden_hut(put, lantern)
    campsite(put)
    trough(put)
    acacia_tree(put, leaves)
    return blocks


def ground(put):
    """
    The clearing: air above, a paved plaza with a mud-brick ring, grass and
    tufts beyond it, and the avenue through the gate from edge to edge.
    """
    half = CENTRE
    for dx in range(-half, half + 1):
        for dz in range(-half, half + 1):
            r = math.hypot(dx, dz)
            avenue = abs(dx) <= 1
            if r > CLEAR_RADIUS and not avenue:
                continue
            for y in range(1, SIZE_Y):
                put(dx, y, dz, "minecraft:air")
            if avenue:
                put(dx, 0, dz, "minecraft:dirt_path")
            elif r <= PLAZA_RADIUS:
                # Paths do not count as dirt: no tree or flower grows on the plaza.
                speck = (dx * 7 + dz * 13) % 9
                put(dx, 0, dz, "minecraft:packed_mud" if speck == 0 else
                    "minecraft:coarse_dirt" if speck == 4 else "minecraft:dirt_path")
            elif r <= BORDER_RADIUS:
                put(dx, 0, dz, MUD)
            else:
                put(dx, 0, dz, "minecraft:grass_block", snowy=False)
                tuft = (dx * 5 + dz * 3) % 7
                if tuft == 0:
                    put(dx, 1, dz, "minecraft:short_grass")
                elif tuft == 3 and (dx + dz) % 2 == 0:
                    put(dx, 1, dz, "minecraft:dandelion")
            # Coarse verges beyond the plaza, so the avenue reads as a road.
            if abs(dx) == 2 and r > BORDER_RADIUS:
                put(dx, 0, dz, "minecraft:coarse_dirt")


def gatehouse(put, leaves, lantern):
    """
    The gate itself: the pane between two square towers of acacia and mud
    brick, a three-deep lintel, a 3 x 2 Safari Board on each face, and a
    thatched roof over the whole span with a leafy fringe.
    """
    # The pane: three wide, four high, along X. The towers are its frame.
    for y in range(1, 5):
        for dx in range(-1, 2):
            put(dx, y, 0, "cobblemon_safari:safari_gate", axis="x")

    for side in (-1, 1):
        for dx in (2 * side, 3 * side):
            for dz in (-1, 0, 1):
                put(dx, 0, dz, MUD)
                put(dx, 1, dz, MUD)
                corner = dz != 0
                for y in range(2, 8):
                    if corner:
                        put(dx, y, dz, LOG, axis="y")
                    elif abs(dx) == 2:
                        # The pane's edge: a smooth stripped log.
                        put(dx, y, dz, STRIPPED, axis="y")
                    else:
                        put(dx, y, dz, CLAY if y < 7 else PLANKS)
            # Banners on both faces of each tower, above the warden's spots
            # (they have no collision, the warden stands under them).
            for dz, facing in ((2, "south"), (-2, "north")):
                put(dx, 5, dz, "minecraft:orange_wall_banner" if abs(dx) == 3 else "minecraft:yellow_wall_banner",
                    facing=facing)

    # Lintel over the doorway, then the boards' backing and the boards.
    for dx in range(-1, 2):
        for dz in (-1, 0, 1):
            put(dx, 5, dz, LOG, axis="x")
        for y in (6, 7):
            put(dx, y, 0, PLANKS)
            put(dx, y, 1, "cobblemon_safari:safari_board", facing="south")
            put(dx, y, -1, "cobblemon_safari:safari_board", facing="north")

    # Thatch: three courses stepping in, a leafy fringe across the faces
    # (leaves, not thatch, so no Pokémon can be placed up there).
    for dx in range(-4, 5):
        for dz in (-1, 0, 1):
            put(dx, 8, dz, THATCH, axis="y")
        leaves(dx, 8, 2)
        leaves(dx, 8, -2)
    for dx in range(-3, 4):
        for dz in (-1, 0, 1):
            put(dx, 9, dz, THATCH, axis="y")
    for dx in range(-2, 3):
        put(dx, 10, 0, THATCH, axis="x")
    for side in (-1, 1):
        for dz in (-1, 1):
            lantern(4 * side, 7, dz, hanging=True)


def palisade(put, lantern, fence):
    """A low acacia palisade running from each tower along the gate's plane, posts topped with lanterns."""
    for side in (-1, 1):
        for step in range(4, 10):
            dx = step * side
            if step in (6, 9):
                for y in (1, 2, 3):
                    put(dx, y, 0, LOG, axis="y")
                lantern(dx, 4, 0)
                put(dx, 0, 0, MUD)
            else:
                fence(dx, 1, 0, along=True)
                fence(dx, 2, 0, along=True)


def avenue(put, leaves, lantern, fence):
    """Lantern posts and flower beds lining the avenue on both sides of the gate, clear of the warden's spots."""
    flowers = ["minecraft:orange_tulip", "minecraft:dandelion", "minecraft:allium", "minecraft:poppy"]
    for side in (-1, 1):
        for dz in (5 * side, 9 * side):
            for dx in (-2, 2):
                fence(dx, 1, dz)
                fence(dx, 2, dz)
                lantern(dx, 3, dz)
        # Beds from 4 to 8 blocks out; the warden's spots are 2 out.
        for step in range(4, 9):
            dz = step * side
            for dx in (-4, -3, 3, 4):
                put(dx, 0, dz, "minecraft:grass_block", snowy=False)
                pick = (step + dx) % 5
                if pick == 4:
                    leaves(dx, 1, dz)
                else:
                    put(dx, 1, dz, flowers[pick % len(flowers)])
            for dx in (-5, 5):
                put(dx, 0, dz, MUD)


def warden_hut(put, lantern):
    """
    The warden's ticket hut, on a diagonal beyond the Pokémon's roaming
    radius: an open-fronted acacia stall with a counter, crates and a
    thatched roof.
    """
    x0, x1, z0, z1 = 8, 12, 7, 11
    # Its roof overhang must stay out of the roaming circle.
    assert (x0 - 1) ** 2 + (z0 - 1) ** 2 > ROAM_RADIUS ** 2
    for dx in range(x0, x1 + 1):
        for dz in range(z0, z1 + 1):
            put(dx, 0, dz, PLANKS)
            for y in range(1, 4):
                put(dx, y, dz, "minecraft:air")
    for dx in (x0, x1):
        for dz in (z0, z1):
            for y in (1, 2, 3):
                put(dx, y, dz, LOG, axis="y")
    # Back and far side walls; the plaza side (z0) and the near side (x0) stay open.
    for y in (1, 2, 3):
        for dx in range(x0 + 1, x1):
            put(dx, y, z1, PLANKS)
        for dz in range(z0 + 1, z1):
            put(x1, y, dz, PLANKS)
    # Counter along the open front.
    put(x0 + 1, 1, z0, "minecraft:barrel", facing="up", open=False)
    put(x0 + 2, 1, z0, STRIPPED, axis="x")
    put(x0 + 3, 1, z0, "minecraft:barrel", facing="up", open=False)
    lantern(x0 + 1, 2, z0)
    put(x0 + 3, 2, z0, "minecraft:potted_dead_bush")
    # Inside: crates and a map table.
    put(x1 - 1, 1, z1 - 1, "minecraft:barrel", facing="north", open=False)
    put(x1 - 1, 2, z1 - 1, "minecraft:barrel", facing="up", open=False)
    put(x1 - 1, 1, z1 - 2, THATCH, axis="y")
    put(x0 + 1, 1, z1 - 1, "minecraft:cartography_table")
    # Thatch roof, stepping in.
    for layer, inset in ((4, -1), (5, 0), (6, 1), (7, 2)):
        for dx in range(x0 + inset, x1 - inset + 1):
            for dz in range(z0 + inset, z1 - inset + 1):
                put(dx, layer, dz, THATCH, axis="y")
    lantern((x0 + x1) // 2, 3, (z0 + z1) // 2, hanging=True)


def campsite(put):
    """A rangers' campfire with log benches, on the opposite diagonal."""
    cx, cz = -10, -9
    for dx in range(-2, 3):
        for dz in range(-2, 3):
            put(cx + dx, 0, cz + dz, "minecraft:coarse_dirt")
    put(cx, 0, cz, "minecraft:packed_mud")
    put(cx, 1, cz, "minecraft:campfire", lit=True, signal_fire=False, facing="north", waterlogged=False)
    put(cx, 1, cz + 2, STRIPPED, axis="x")
    put(cx + 1, 1, cz + 2, STRIPPED, axis="x")
    put(cx, 1, cz - 2, STRIPPED, axis="x")
    put(cx - 1, 1, cz - 2, STRIPPED, axis="x")
    put(cx + 2, 1, cz, STRIPPED, axis="z")
    put(cx - 2, 1, cz, STRIPPED, axis="z")
    put(cx - 2, 1, cz + 1, STRIPPED, axis="z")
    put(cx + 2, 1, cz - 2, "minecraft:barrel", facing="up", open=False)


def trough(put):
    """A water trough and a stack of hay for the warden's Pokémon."""
    put(9, 1, -9, "minecraft:water_cauldron", level=3)
    put(10, 1, -9, "minecraft:water_cauldron", level=3)
    put(9, 0, -9, MUD)
    put(10, 0, -9, MUD)
    for dx, y, dz in ((11, 1, -10), (11, 1, -9), (12, 1, -10), (11, 2, -10), (10, 1, -11)):
        put(dx, y, dz, THATCH, axis="y")


def acacia_tree(put, leaves):
    """A small leaning acacia on the last diagonal, with a flat two-tier crown."""
    x, z = -9, 10
    for y in range(1, 5):
        put(x + (1 if y >= 3 else 0), y, z, LOG, axis="y")
    top = (x + 1, 5, z)
    for dx in range(-2, 3):
        for dz in range(-2, 3):
            if abs(dx) + abs(dz) <= 3:
                leaves(top[0] + dx, 5, top[2] + dz)
    for dx in range(-1, 2):
        for dz in range(-1, 2):
            leaves(top[0] + dx, 6, top[2] + dz)
    put(top[0], 5, top[2], LOG, axis="y")


def structure():
    """Writes the gate template."""
    blocks = gate_blocks()
    for sx in (-1, 1):
        for sz in (-1, 1):
            for y in (1, 2):
                spot = (CENTRE + sx * WARDEN_ALONG, y, CENTRE + sz * WARDEN_ACROSS)
                if blocks.get(spot, ("minecraft:air",))[0] != "minecraft:air":
                    raise ValueError("warden spot %s is blocked by %s" % (spot, blocks[spot][0]))
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


# Option that rebuilds the structure template. Off by default since the gate
# is built by hand in game (safari_gate_v1.1): running the script plainly
# must not overwrite that build with this older generated lodge.
STRUCTURE_FLAG = "--structure"


if __name__ == "__main__":
    import sys
    warden_skin()
    if STRUCTURE_FLAG in sys.argv[1:]:
        count, states = structure()
        print("warden skin, structure (%d blocks, %d states) written" % (count, states))
    else:
        print("warden skin written; structure left as is (pass %s to regenerate the old lodge)" % STRUCTURE_FLAG)
