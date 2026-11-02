#!/usr/bin/env python3
"""
Installs the Pokémon sprites used by the wall screens' sprite mode
(board.useSprites = true in the client config), into the mod's own
resources or as a separate resource pack.

Input: a folder of sprites, in either layout:
  * one folder per species, <dex>_<name>/<name>[_<form>][_shiny].png, e.g.
    0019_rattata/rattata_alolan_shiny.png; files whose name does not contain
    the species are skipped (and listed);
  * flat, <dex>_<name>[_<form>].png, as the E19 pack's
    assets/xaerominimap/entity/icon/sprite/regular folder (shiny sprites live
    in a folder of their own there); the species is the part of the name all
    files of a number share (shellos_east and shellos_west: shellos), mapped
    through SPECIES_ALIASES where the pack spells it differently. Number 0000
    (ghost, substitute) is no species and is skipped.

Output: each sprite at
    assets/cobblemon_safari/textures/pokemon_sprites/<species>[_<form>][_shiny].png
either in the mod's resources (--mod; the folder is emptied first, so a
sprite removed from the input disappears) or in a resource pack zip, which
then overrides the mod's sprites of the same name.
<species> being the Cobblemon species id (letters and digits only: ho_oh
becomes hooh, porygon-z porygonz), which is what PokemonSprites looks up.

Only the base sprite of each species is installed (<species>.png): forms,
megas, gigantamax and shiny sprites are left out, the screens then showing the
base sprite for them. A species with no plain sprite (only shellos_west and
shellos_east, say) gets one of its forms as its base sprite: the one named in
DEFAULT_FORMS, else the first ordinary form by name. Add --all-forms to install
every sprite instead.

The sprites are the E19 Cobblemon Minimap Icons by Emery319 (icons by
MBCMechachu), under the Mozilla Public License 2.0: NOTICE.txt, written next
to them, says so, and must stay with them (MPL 2.0, sections 3.1 and 3.2).

Each sprite is stored as an indexed (palette) PNG when it has 256 colours or
fewer, which every sprite of this kind has: the pixels are kept exactly, at
about a quarter of the size of a full-colour PNG (the 1025 base sprites take
about 0.5 MiB in the jar). Minecraft reads both.

Usage (from the repository root; needs Pillow):
    python3 tools/pokemon_sprites_pack.py <sprite folder> --mod [--all-forms]
    python3 tools/pokemon_sprites_pack.py <sprite folder> <output.zip> [--all-forms]
Deterministic: same input, same files.
"""

import io
import json
import os
import re
import shutil
import sys
import zipfile

from PIL import Image

# Resource pack format of Minecraft 1.21.1. A different number makes the
# game list the pack as made for another version (it still loads).
PACK_FORMAT = 34

TARGET = "assets/cobblemon_safari/textures/pokemon_sprites/"
MOD_RESOURCES = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                             "common", "src", "main", "resources")

# Fixed timestamp: identical input gives an identical zip.
ZIP_DATE = (2026, 1, 1, 0, 0, 0)


def species_id(name):
    """Cobblemon's species id for a sprite folder name: lower case, letters and digits only."""
    return re.sub(r"[^a-z0-9]", "", name.lower())


def sprite_name(species, stem):
    """
    The pack name of a sprite file, or None when the file does not name the
    species. The species may sit anywhere in the name (ash_greninja); what is
    left around it is the form.
    """
    tokens = stem.lower().split("_")
    for i in range(len(tokens)):
        for j in range(i + 1, len(tokens) + 1):
            if species_id("".join(tokens[i:j])) == species:
                form = [re.sub(r"[^a-z0-9]", "_", t) for t in tokens[:i] + tokens[j:]]
                return "_".join([species] + form)
    return None


# Species the flat layout names differently from Cobblemon's id.
SPECIES_ALIASES = {"nidoranfemale": "nidoranf", "nidoranmale": "nidoranm"}

# Flat layout file name: four-digit national number, then the name.
FLAT_NAME = re.compile(r"^(\d{4})_(.+)\.png$", re.IGNORECASE)

# National number of the flat layout's non-species icons.
NOT_A_SPECIES = "0000"


def collect_flat(source, names, skipped):
    """Adds the flat layout's sprites (files directly in the folder) to names."""
    by_number = {}
    for file in sorted(os.listdir(source)):
        match = FLAT_NAME.match(file)
        if match is None or os.path.isdir(os.path.join(source, file)):
            continue
        if match.group(1) == NOT_A_SPECIES:
            skipped.append(file)
            continue
        by_number.setdefault(match.group(1), []).append((file, match.group(2).lower().split("_")))
    for files in by_number.values():
        # The species is what every file of the number starts with.
        shared = []
        for tokens in zip(*(parts for _, parts in files)):
            if len(set(tokens)) != 1:
                break
            shared.append(tokens[0])
        species = species_id("".join(shared))
        species = SPECIES_ALIASES.get(species, species)
        for file, parts in files:
            form = [re.sub(r"[^a-z0-9]", "_", t) for t in parts[len(shared):]]
            name = "_".join([species] + form)
            if not species or name in names:
                skipped.append(file)
                continue
            names[name] = file


def collect(source):
    """Maps each pack name to its source file, in either layout; returns (names, skipped)."""
    skipped = []
    names = {}
    collect_flat(source, names, skipped)
    for folder in sorted(os.listdir(source)):
        path = os.path.join(source, folder)
        if not os.path.isdir(path) or "_" not in folder:
            continue
        species = species_id(folder.split("_", 1)[1])
        for file in sorted(os.listdir(path)):
            full = os.path.join(path, file)
            if os.path.isdir(full) or not file.lower().endswith(".png"):
                skipped.append(os.path.join(folder, file))
                continue
            name = sprite_name(species, file[:-4])
            if name is None:
                skipped.append(os.path.join(folder, file))
                continue
            if name in names:
                skipped.append("%s (same name as %s)" % (os.path.join(folder, file), names[name]))
                continue
            names[name] = os.path.join(folder, file)
    return names, skipped


# Form used as the base sprite of a species that has no plain sprite: the
# one Cobblemon shows by default (its first form, or the male one). A species
# missing here falls back to its first ordinary form by name; one whose
# chosen form is missing from the input gets no base sprite.
DEFAULT_FORMS = {
    "shellos": "west", "gastrodon": "west",
    "basculin": "red",
    "deerling": "spring", "sawsbuck": "spring",
    "alcremie": "strawberry_vanilla",
    "squawkabilly": "green",
    "tatsugiri": "curly",
    "ogerpon": "teal",
    "hippopotas": "male", "hippowdon": "male", "unfezant": "male",
    "frillish": "male", "jellicent": "male",
    "basculegion": "male", "oinkologne": "male",
    "indeedee": "male", "meowstic": "male", "pyroar": "male",
    "burmy": "plant", "wormadam": "plant",
    "flabebe": "red", "floette": "red", "florges": "red",
    "vivillon": "meadow",
    "lycanroc": "midday",
    "oricorio": "baile",
    "toxtricity": "amped",
    "urshifu": "single",
    "unown": "a",
    "zygarde": "50",
}

# Name parts of sprites never taken as a base sprite: they are not a
# Pokémon's usual look.
NOT_BASE_PARTS = {"shiny", "mega", "gigantamax", "female"}

# Licence notice written next to the sprites (mod resources and pack). The
# date is when the licence page was checked: it records the terms the sprites
# were taken under. Change it only after checking the page again.
NOTICE = """Pokemon sprites: E19 Cobblemon Minimap Icons by Emery319, icons by MBCMechachu.
https://www.curseforge.com/minecraft/texture-packs/e19-cobblemon-minimap-icons

Licence as published on the project's licence page
(https://www.curseforge.com/minecraft/texture-packs/e19-cobblemon-minimap-icons/license)
when these files were taken: Mozilla Public License 2.0, checked on 2026-11-02.
Recorded so that the terms these copies were received under stay known,
whatever the page says later.

These files are subject to the terms of the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, you can obtain one at
https://mozilla.org/MPL/2.0/.

Taken from the pack's assets/xaerominimap/entity/icon/sprite/regular folder.
Changes made by Cobblemon Safari: each sprite centred in its 64 x 64 frame
and stored as an indexed PNG (same pixels); a species with no plain sprite
uses one of its forms.
"""

NOTICE_NAME = "NOTICE.txt"


def with_base_forms(names):
    """
    The base sprites of the input, plus a base sprite taken from a form for
    each species that has none (see DEFAULT_FORMS).
    """
    base = {name: path for name, path in names.items() if is_base(name)}
    forms = {}
    for name in sorted(names):
        if is_base(name):
            continue
        species, form = name.split("_", 1)
        if species in base or NOT_BASE_PARTS & set(form.split("_")):
            continue
        forms.setdefault(species, {})[form] = names[name]
    for species, available in forms.items():
        wanted = DEFAULT_FORMS.get(species)
        if wanted is not None:
            if wanted in available:
                base[species] = available[wanted]
            else:
                print("no %s_%s sprite: %s gets no base sprite" % (species, wanted, species))
            continue
        base[species] = available[sorted(available)[0]]
    return base


# Most colours a palette PNG holds. A sprite with more stays full colour.
PALETTE_SIZE = 256


def centred(image):
    """
    The sprite moved so its visible pixels sit in the middle of its frame,
    the extra pixel of an odd size going up and left: the screens draw the
    whole frame, so a sprite drawn low in its frame (as some sources do)
    would stand off its spot. Sprites already centred are unchanged.
    """
    box = image.getbbox()
    if box is None:
        return image
    width, height = box[2] - box[0], box[3] - box[1]
    left, top = image.width // 2 - (width + 1) // 2, image.height // 2 - (height + 1) // 2
    if (left, top) == box[:2]:
        # Untouched, so the colour stored under its transparent pixels (and
        # with it the file) does not change for nothing.
        return image
    frame = Image.new("RGBA", image.size, (0, 0, 0, 0))
    frame.paste(image.crop(box), (left, top))
    return frame


def compact_png(path):
    """
    The sprite's PNG bytes, centred in its frame, as an indexed PNG when it has PALETTE_SIZE colours
    or fewer, else as an optimised full-colour PNG. Lossless either way: the
    palette holds each colour exactly, alpha included (tRNS chunk), and the
    result is checked against the centred sprite.
    """
    image = centred(Image.open(path).convert("RGBA"))
    pixels = list(image.getdata())
    out = io.BytesIO()
    colours = image.getcolors(PALETTE_SIZE)
    if colours is None:
        image.save(out, "PNG", optimize=True)
        return out.getvalue()
    # Sorted, so the same sprite always gets the same palette and file.
    palette = sorted(colour for _, colour in colours)
    index = {colour: i for i, colour in enumerate(palette)}
    indexed = Image.new("P", image.size)
    indexed.putdata([index[pixel] for pixel in pixels])
    indexed.putpalette([channel for colour in palette for channel in colour[:3]])
    indexed.save(out, "PNG", optimize=True, transparency=bytes(colour[3] for colour in palette))
    data = out.getvalue()
    if list(Image.open(io.BytesIO(data)).convert("RGBA").getdata()) != pixels:
        sys.exit("palette conversion changed the pixels of " + path)
    return data


def write_mod(source, names):
    """Copies the sprites into the mod's resources, replacing the folder."""
    target = os.path.join(MOD_RESOURCES, *TARGET.split("/"))
    shutil.rmtree(target, ignore_errors=True)
    os.makedirs(target)
    for name in sorted(names):
        with open(os.path.join(target, name + ".png"), "wb") as f:
            f.write(compact_png(os.path.join(source, names[name])))
    with open(os.path.join(target, NOTICE_NAME), "w", encoding="utf-8", newline="\n") as f:
        f.write(NOTICE)
    return target


def write_pack(source, names, output):
    """Writes the sprites as a resource pack zip."""
    with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as pack:
        meta = {"pack": {"pack_format": PACK_FORMAT, "description": {
            "translate": "cobblemon_safari.pack.sprites",
            "fallback": "Cobblemon Safari: Pokémon sprites for the wall screens"}}}
        info = zipfile.ZipInfo("pack.mcmeta", ZIP_DATE)
        pack.writestr(info, json.dumps(meta, indent=2, ensure_ascii=False) + "\n", zipfile.ZIP_DEFLATED)
        pack.writestr(zipfile.ZipInfo(TARGET + NOTICE_NAME, ZIP_DATE), NOTICE, zipfile.ZIP_DEFLATED)
        for name in sorted(names):
            data = compact_png(os.path.join(source, names[name]))
            pack.writestr(zipfile.ZipInfo(TARGET + name + ".png", ZIP_DATE), data, zipfile.ZIP_DEFLATED)
    return output


# Option keeping every form, mega, gigantamax and shiny sprite.
ALL_FORMS = "--all-forms"


def is_base(name):
    """Whether a pack name is a species' base sprite: species ids hold no underscore, forms add one."""
    return "_" not in name


def main():
    args = sys.argv[1:]
    all_forms = ALL_FORMS in args
    args = [a for a in args if a != ALL_FORMS]
    if len(args) != 2:
        sys.exit(__doc__)
    source, output = args
    names, skipped = collect(source)
    if not all_forms:
        # Kept out on purpose (not "skipped"): listing about two thousand
        # files would bury the real problems of the input.
        left_out = sum(1 for name in names if not is_base(name))
        names = with_base_forms(names)
        print("%d form, mega and shiny sprites left out (use %s to keep them)" % (left_out, ALL_FORMS))
    where = write_mod(source, names) if output == "--mod" else write_pack(source, names, output)
    print("%d sprites written to %s" % (len(names), where))
    for item in skipped:
        print("skipped: " + item)


if __name__ == "__main__":
    main()
