#!/usr/bin/env python3
"""Generates the Safari Board Frame blockstate and models.

Run from the repository root: python tools/board_frame_models.py

The geometry here must match SafariBoardFrameBlock (BEAM_MIN/MAX/FRONT,
PLANKS_FRONT) and the wood list must match the BoardWood enum: the
blockstate refers to one planks model per wood and quarter.
"""
import json
import os

NS = "cobblemon_safari"
ROOT = os.path.join("common", "src", "main", "resources")
BEAM = "minecraft:block/stripped_dark_oak_log"
# Same order and names as BoardWood.
WOODS = ["oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove", "cherry", "bamboo",
         "crimson", "warped"]
DEFAULT_WOOD = "spruce"
# Pixels, north-facing model (front toward -Z, back at Z = 16).
BMIN, BMAX, BFRONT, PFRONT = 6, 10, 10, 13
QUARTERS = {
    # North-facing model: the reader's left is +X.
    "top_left": ([8, 8, PFRONT], [16, 16, 16]),
    "top_right": ([0, 8, PFRONT], [8, 16, 16]),
    "bottom_left": ([8, 0, PFRONT], [16, 8, 16]),
    "bottom_right": ([0, 0, PFRONT], [8, 8, 16]),
}
# Feet: three 3-px steps going down and out on each side of the post, a
# pixel-art splay seen from the side. They may leave the block (Z < 0 or
# > 16), which models allow; they are decorative and not in the hit box.
FOOT_X = (7, 9)
FOOT_STEPS = [((7, 10), (2, 4)), ((4, 7), (1, 3)), ((1, 4), (0, 2)),
              ((16, 19), (2, 4)), ((19, 22), (1, 3)), ((22, 25), (0, 2))]


def face_uv(face, frm, to):
    """UV of a face, starting at the texture origin.

    Minecraft derives UVs from the element position when none is given. Past
    the block edge (Z > 16 for the back feet) that reads outside the sprite,
    into whatever texture sits next to it in the atlas: the blue feet.
    """
    size = {"north": (to[0] - frm[0], to[1] - frm[1]), "south": (to[0] - frm[0], to[1] - frm[1]),
            "east": (to[2] - frm[2], to[1] - frm[1]), "west": (to[2] - frm[2], to[1] - frm[1]),
            "up": (to[0] - frm[0], to[2] - frm[2]), "down": (to[0] - frm[0], to[2] - frm[2])}[face]
    return [0, 0, size[0], size[1]]


def element(name, frm, to, tex, rotate=False):
    """One cuboid, with cull faces on block borders and log grain along horizontal beams."""
    faces = {}
    outside = any(v < 0 or v > 16 for v in frm + to)
    bounds = {"north": frm[2] == 0, "south": to[2] == 16, "west": frm[0] == 0, "east": to[0] == 16,
              "down": frm[1] == 0, "up": to[1] == 16}
    for face in ("north", "south", "east", "west", "up", "down"):
        f = {"texture": "#" + tex}
        if outside:
            f["uv"] = face_uv(face, frm, to)
        if bounds[face]:
            f["cullface"] = face
        if rotate and face in ("north", "south", "up", "down"):
            f["rotation"] = 90
        faces[face] = f
    return {"name": name, "from": frm, "to": to, "faces": faces}


def beam_parts():
    return {
        "core": [element("core", [BMIN, BMIN, BFRONT], [BMAX, BMAX, 16], "beam")],
        "up": [element("up", [BMIN, BMAX, BFRONT], [BMAX, 16, 16], "beam")],
        "down": [element("down", [BMIN, 0, BFRONT], [BMAX, BMIN, 16], "beam")],
        "left": [element("left", [BMAX, BMIN, BFRONT], [16, BMAX, 16], "beam", rotate=True)],
        "right": [element("right", [0, BMIN, BFRONT], [BMIN, BMAX, 16], "beam", rotate=True)],
        "foot": [element("foot", [FOOT_X[0], y[0], z[0]], [FOOT_X[1], y[1], z[1]], "beam")
                 for z, y in FOOT_STEPS],
    }


def write(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=2)
        f.write("\n")


def main():
    models = os.path.join(ROOT, "assets", NS, "models", "block")
    beams = beam_parts()
    for part, elements in beams.items():
        write(os.path.join(models, f"safari_board_frame_{part}.json"),
              {"parent": "minecraft:block/block", "textures": {"particle": BEAM, "beam": BEAM},
               "elements": elements})
    for wood in WOODS:
        planks = f"minecraft:block/{wood}_planks"
        for quarter, (frm, to) in QUARTERS.items():
            write(os.path.join(models, f"safari_board_frame_planks_{quarter}_{wood}.json"),
                  {"parent": "minecraft:block/block", "textures": {"particle": planks, "planks": planks},
                   "elements": [element("planks", frm, to, "planks")]})

    inventory = (beams["core"] + beams["up"] + beams["down"] + beams["left"] + beams["right"]
                 + [element("planks", frm, to, "planks") for frm, to in QUARTERS.values()])
    write(os.path.join(models, "safari_board_frame_inventory.json"),
          {"parent": "minecraft:block/block",
           "textures": {"particle": BEAM, "beam": BEAM, "planks": f"minecraft:block/{DEFAULT_WOOD}_planks"},
           "elements": inventory,
           "display": {"gui": {"rotation": [30, 225, 0], "translation": [0, 0, 0], "scale": [0.625, 0.625, 0.625]},
                       "fixed": {"rotation": [0, 180, 0], "translation": [0, 0, 0], "scale": [0.5, 0.5, 0.5]}}})

    multipart = []
    for facing, y in {"north": 0, "east": 90, "south": 180, "west": 270}.items():
        def apply(model):
            a = {"model": f"{NS}:block/{model}"}
            if y:
                a["y"] = y
            return a
        multipart.append({"when": {"facing": facing}, "apply": apply("safari_board_frame_core")})
        for part in ("up", "down", "left", "right", "foot"):
            multipart.append({"when": {"facing": facing, part: "true"},
                              "apply": apply(f"safari_board_frame_{part}")})
        for wood in WOODS:
            for quarter in QUARTERS:
                multipart.append({"when": {"facing": facing, f"planks_{quarter}": "true", "wood": wood},
                                  "apply": apply(f"safari_board_frame_planks_{quarter}_{wood}")})
    write(os.path.join(ROOT, "assets", NS, "blockstates", "safari_board_frame.json"), {"multipart": multipart})
    print(f"{len(multipart)} multipart entries, {len(WOODS) * len(QUARTERS) + len(beams) + 1} models")


if __name__ == "__main__":
    main()
