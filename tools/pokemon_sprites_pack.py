#!/usr/bin/env python3
"""
Installs the Pokémon sprites used by the Safari Board's sprite mode
(board.pokemonDisplay = SPRITE in the client config), into the mod's own
resources or as a separate resource pack.

Input: a folder of sprites laid out as <dex>_<name>/<name>[_<form>][_shiny].png,
e.g. 0019_rattata/rattata_alolan_shiny.png. Sub-folders and files whose name
does not contain the species are skipped (and listed).

Output: each sprite at
    assets/cobblemon_safari/textures/pokemon_sprites/<species>[_<form>][_shiny].png
either in the mod's resources (--mod; the folder is emptied first, so a
sprite removed from the input disappears) or in a resource pack zip, which
then overrides the mod's sprites of the same name.
<species> being the Cobblemon species id (letters and digits only: ho_oh
becomes hooh, porygon-z porygonz), which is what PokemonSprites looks up.

Only the base sprite of each species is installed (<species>.png): forms,
megas, gigantamax and shiny sprites are left out, the board then showing the
base sprite for them. Add --all-forms to install every sprite instead.

Usage (from the repository root):
    python3 tools/pokemon_sprites_pack.py <sprite folder> --mod [--all-forms]
    python3 tools/pokemon_sprites_pack.py <sprite folder> <output.zip> [--all-forms]
Deterministic: same input, same files.
"""

import json
import os
import re
import shutil
import sys
import zipfile

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


def collect(source):
    """Maps each pack name to its source file; returns (names, skipped)."""
    skipped = []
    names = {}
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


def write_mod(source, names):
    """Copies the sprites into the mod's resources, replacing the folder."""
    target = os.path.join(MOD_RESOURCES, *TARGET.split("/"))
    shutil.rmtree(target, ignore_errors=True)
    os.makedirs(target)
    for name in sorted(names):
        shutil.copyfile(os.path.join(source, names[name]), os.path.join(target, name + ".png"))
    return target


def write_pack(source, names, output):
    """Writes the sprites as a resource pack zip."""
    with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as pack:
        meta = {"pack": {"pack_format": PACK_FORMAT, "description": {
            "translate": "cobblemon_safari.pack.sprites",
            "fallback": "Cobblemon Safari: Pokémon sprites for the Safari Board"}}}
        info = zipfile.ZipInfo("pack.mcmeta", ZIP_DATE)
        pack.writestr(info, json.dumps(meta, indent=2, ensure_ascii=False) + "\n", zipfile.ZIP_DEFLATED)
        for name in sorted(names):
            with open(os.path.join(source, names[name]), "rb") as f:
                data = f.read()
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
        names = {name: path for name, path in names.items() if is_base(name)}
        print("%d form, mega and shiny sprites left out (use %s to keep them)" % (left_out, ALL_FORMS))
    where = write_mod(source, names) if output == "--mod" else write_pack(source, names, output)
    print("%d sprites written to %s" % (len(names), where))
    for item in skipped:
        print("skipped: " + item)


if __name__ == "__main__":
    main()
