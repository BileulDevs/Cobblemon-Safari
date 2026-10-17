#!/usr/bin/env python3
"""
Writes the JSON assets and data of the Safari decor blocks: block models,
block states, item models and loot tables of the Safari Banners and the
coat rack, plus the axe mining tag.

The coat rack models are drawn facing north and must match the outlines
of decor/CoatRackBlock.java. Run from the project root:

    python3 tools/decor_assets.py

Textures come from tools/decor_textures.py.
"""
import json
import os

ROOT = os.path.join("common", "src", "main", "resources")
ASSETS = os.path.join(ROOT, "assets", "cobblemon_safari")
DATA = os.path.join(ROOT, "data", "cobblemon_safari")
NS = "cobblemon_safari"

COLOURS = ["white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray", "light_gray",
           "cyan", "purple", "blue", "brown", "green", "red", "black"]
FACING_Y = {"north": 0, "east": 90, "south": 180, "west": 270}


def write(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(obj, f, indent=2)
        f.write("\n")


def cube(frm, to, faces, name=None):
    """A model element; faces maps direction -> texture variable (without #)."""
    element = {"from": frm, "to": to,
               "faces": {d: {"texture": "#" + t} for d, t in faces.items()}}
    if frm[1] == 0 and "down" in faces:
        element["faces"]["down"]["cullface"] = "down"
    if name:
        element["name"] = name
    return element


ALL = ("north", "south", "east", "west", "up", "down")


def every(texture, **overrides):
    faces = {d: texture for d in ALL}
    faces.update(overrides)
    return faces


# ---------------------------------------------------------------- banners

def banner_files():
    """
    Banners are drawn by their block entity renderer (client/banner/SafariBannerRenderer):
    the block model only gives the breaking particles. The wall banner has no
    loot table of its own, it drops like the standing one (dropsLike in SafariDecor).
    """
    particle = {"textures": {"particle": f"{NS}:block/decor/walnut"}}
    write(os.path.join(ASSETS, "models", "block", "decor", "safari_banner.json"), particle)
    for name in ("safari_banner", "safari_wall_banner"):
        write(os.path.join(ASSETS, "blockstates", name + ".json"),
              {"variants": {"": {"model": f"{NS}:block/decor/safari_banner"}}})
    write(os.path.join(ASSETS, "models", "item", "safari_banner.json"),
          {"parent": "minecraft:item/generated", "textures": {"layer0": f"{NS}:item/safari_banner"}})
    loot("safari_banner")


# ---------------------------------------------------------------- coat rack

def coat_rack_files():
    """Two halves facing north (jacket on the north hook); the upper half is drawn once per hat colour."""
    wood = every("wood")
    lower = {"parent": "minecraft:block/block",
             "textures": {"wood": f"{NS}:block/decor/teak", "jacket": f"{NS}:block/decor/jacket_front",
                          "jacket_side": f"{NS}:block/decor/jacket_side", "particle": f"{NS}:block/decor/teak"},
             "elements": [
                 cube([3, 0, 7], [13, 1, 9], wood, "foot"),
                 cube([7, 0, 3], [9, 1, 13], wood, "foot"),
                 cube([7, 1, 7], [9, 16, 9], wood, "pole"),
                 cube([4, 6, 2], [12, 16, 6], every("jacket_side", north="jacket"), "jacket_tails"),
             ]}
    write(os.path.join(ASSETS, "models", "block", "decor", "coat_rack_lower.json"), lower)
    upper = {"parent": "minecraft:block/block",
             "textures": {"wood": f"{NS}:block/decor/teak", "jacket": f"{NS}:block/decor/jacket_front",
                          "jacket_side": f"{NS}:block/decor/jacket_side", "band": f"{NS}:block/decor/hat_band",
                          "particle": f"{NS}:block/decor/teak"},
             "elements": [
                 cube([7, 0, 7], [9, 12, 9], wood, "pole"),
                 cube([6.5, 12, 6.5], [9.5, 13, 9.5], wood, "knob"),
                 cube([7.5, 8, 3], [8.5, 9, 7], wood, "front_hook"),
                 cube([3, 8, 7.5], [7, 9, 8.5], wood, "side_hook"),
                 cube([9, 8, 7.5], [13, 9, 8.5], wood, "side_hook"),
                 cube([7.5, 8, 9], [8.5, 9, 13], wood, "back_hook"),
                 cube([4, 0, 2], [12, 9, 6], every("jacket_side", north="jacket"), "jacket_shoulders"),
                 cube([3, 13, 3], [13, 14, 13], every("felt"), "hat_brim"),
                 cube([5, 14, 5], [11, 17, 11], every("felt"), "hat_crown"),
                 cube([4.9, 14, 4.9], [11.1, 15.2, 11.1],
                      {"north": "band", "south": "band", "east": "band", "west": "band"}, "hat_band"),
             ]}
    write(os.path.join(ASSETS, "models", "block", "decor", "coat_rack_upper.json"), upper)
    variants = {}
    for colour in COLOURS:
        write(os.path.join(ASSETS, "models", "block", "decor", f"coat_rack_upper_{colour}.json"),
              {"parent": f"{NS}:block/decor/coat_rack_upper",
               "textures": {"felt": f"{NS}:block/decor/hat_felt_{colour}"}})
        for facing, y in FACING_Y.items():
            for half in ("lower", "upper"):
                model = "coat_rack_lower" if half == "lower" else f"coat_rack_upper_{colour}"
                variant = {"model": f"{NS}:block/decor/{model}"}
                if y:
                    variant["y"] = y
                variants[f"facing={facing},half={half},hat={colour}"] = variant
    write(os.path.join(ASSETS, "blockstates", "coat_rack.json"), {"variants": variants})
    write(os.path.join(ASSETS, "models", "item", "coat_rack.json"),
          {"parent": "minecraft:item/generated", "textures": {"layer0": f"{NS}:item/coat_rack"}})
    # Only the lower half drops: breaking the upper one is handled by CoatRackBlock.playerWillDestroy.
    write(os.path.join(DATA, "loot_table", "blocks", "coat_rack.json"), {
        "type": "minecraft:block",
        "pools": [{"rolls": 1, "bonus_rolls": 0,
                   "entries": [{"type": "minecraft:item", "name": f"{NS}:coat_rack"}],
                   "conditions": [{"condition": "minecraft:survives_explosion"},
                                  {"condition": "minecraft:block_state_property", "block": f"{NS}:coat_rack",
                                   "properties": {"half": "lower"}}]}],
        "random_sequence": f"{NS}:blocks/coat_rack"})


def loot(name):
    """Drops itself, like any piece of furniture."""
    write(os.path.join(DATA, "loot_table", "blocks", name + ".json"), {
        "type": "minecraft:block",
        "pools": [{"rolls": 1, "bonus_rolls": 0,
                   "entries": [{"type": "minecraft:item", "name": f"{NS}:{name}"}],
                   "conditions": [{"condition": "minecraft:survives_explosion"}]}],
        "random_sequence": f"{NS}:blocks/{name}"})


def mineable():
    """Adds the decor to the axe tag, keeping the entries already there."""
    path = os.path.join(ROOT, "data", "minecraft", "tags", "block", "mineable", "axe.json")
    with open(path, encoding="utf-8") as f:
        tag = json.load(f)
    wanted = [f"{NS}:safari_banner", f"{NS}:safari_wall_banner", f"{NS}:coat_rack"]
    for entry in wanted:
        if entry not in tag["values"]:
            tag["values"].append(entry)
    write(path, tag)


def main():
    banner_files()
    coat_rack_files()
    mineable()


if __name__ == "__main__":
    main()
