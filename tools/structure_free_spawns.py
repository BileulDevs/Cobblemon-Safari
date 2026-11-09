"""Generates the Safari's structure-free spawn files.

Cobblemon spawns some species only in structures (villages, mansions,
nether structures, ruins...) or with presets that need structure blocks
("urban", "stronghold", pyramids). The Safari has none of those, so these
species never spawned there. Each gets here one or two spawns of its own,
tied to a biome tag only, and to the Safari dimension: the overworld keeps
Cobblemon's spawns as they are, structures included.
The files go to data/cobblemon_safari/spawn_pool_world: a namespace of their
own, so Cobblemon's files are untouched and nothing depends on load order.

Run from the repository root: python3 tools/structure_free_spawns.py
"""
import json
import os

OUT = "common/src/main/resources/data/cobblemon_safari/spawn_pool_world"

# The Safari dimension (SafariDimensions.key()). Without it the tags below would also
# spawn these Pokémon all over the overworld's biomes of the same kind.
SAFARI_DIMENSION = "cobblemon_safari:safari"

# (dex, species file name, [(pokemon, bucket, level, weight, biome tags, time, sky)])
# time: None, "day" or "night". sky: True (open air), False (underground).
SPAWNS = [
    (52, "meowth", [
        ("meowth", "common", "4-29", 72.0, ["is_grassland", "is_savanna"], "night", True),
        ("meowth alolan", "common", "4-29", 72.0, ["is_jungle", "is_beach"], "night", True),
        ("meowth galarian", "common", "4-29", 72.0, ["is_mountain", "is_freezing", "is_taiga"], None, True),
    ]),
    (53, "persian", [
        ("persian", "common", "28-44", 8.0, ["is_grassland", "is_savanna"], "night", True),
        ("persian alolan", "common", "28-44", 8.0, ["is_jungle", "is_beach"], "night", True),
    ]),
    (58, "growlithe", [
        ("growlithe", "uncommon", "10-35", 40.0, ["is_savanna", "is_grassland"], None, True),
        ("growlithe", "uncommon", "10-35", 20.0, ["is_volcanic"], None, True),
    ]),
    (59, "arcanine", [
        ("arcanine", "uncommon", "30-56", 4.0, ["is_savanna", "is_grassland"], None, True),
    ]),
    (96, "drowzee", [
        ("drowzee", "common", "8-33", 60.0, ["is_forest", "is_grassland"], "night", True),
    ]),
    (97, "hypno", [
        ("hypno", "common", "26-48", 6.0, ["is_forest"], "night", True),
    ]),
    (122, "mrmime", [
        ("mrmime", "uncommon", "26-46", 11.84, ["is_floral", "is_plains"], None, True),
        ("mrmime galarian", "uncommon", "26-46", 11.424, ["is_freezing", "is_snowy"], None, True),
    ]),
    (124, "jynx", [
        ("jynx", "uncommon", "30-46", 11.84, ["is_freezing", "is_snowy"], None, True),
    ]),
    (209, "snubbull", [
        ("snubbull", "common", "5-30", 54.0, ["is_floral", "is_plains"], None, True),
    ]),
    (210, "granbull", [
        ("granbull", "common", "23-45", 4.8, ["is_floral", "is_plains"], None, True),
    ]),
    (235, "smeargle", [
        ("smeargle", "common", "1-25", 20.0, ["is_floral", "is_cherry_blossom"], None, True),
    ]),
    (431, "glameow", [
        ("glameow", "common", "6-31", 54.0, ["is_plains", "is_savanna"], None, True),
    ]),
    (432, "purugly", [
        ("purugly", "common", "38-45", 6.0, ["is_plains", "is_savanna"], None, True),
    ]),
    (439, "mimejr", [
        ("mimejr", "uncommon", "6-31", 4.16, ["is_floral", "is_plains"], None, True),
        ("mimejr region_bias=galar", "uncommon", "6-31", 4.16, ["is_freezing", "is_snowy"], None, True),
    ]),
    (506, "lillipup", [
        ("lillipup", "common", "3-28", 54.0, ["is_grassland", "is_plains"], None, True),
        ("lillipup", "common", "3-28", 30.0, ["is_forest"], None, True),
    ]),
    (507, "herdier", [
        ("herdier", "common", "16-37", 4.32, ["is_grassland", "is_plains", "is_forest"], None, True),
    ]),
    (508, "stoutland", [
        ("stoutland", "common", "32-50", 0.48, ["is_grassland", "is_plains", "is_snowy"], None, True),
    ]),
    (517, "munna", [
        ("munna", "common", "4-29", 60.0, ["is_magical", "is_mushroom"], "night", True),
    ]),
    (518, "musharna", [
        ("musharna", "common", "24-49", 5.0, ["is_magical", "is_mushroom"], "night", True),
    ]),
    (532, "timburr", [
        ("timburr", "common", "6-31", 43.2, ["is_mountain", "is_hills"], None, True),
    ]),
    (533, "gurdurr", [
        ("gurdurr", "common", "25-41", 4.56, ["is_mountain", "is_hills"], None, True),
    ]),
    (534, "conkeldurr", [
        ("conkeldurr", "common", "36-51", 0.24, ["is_mountain", "is_hills"], None, True),
    ]),
    (568, "trubbish", [
        ("trubbish", "common", "8-33", 14.4, ["is_swamp"], None, True),
    ]),
    (569, "garbodor", [
        ("garbodor", "common", "36-47", 1.6, ["is_swamp"], None, True),
    ]),
    (574, "gothita", [
        ("gothita", "common", "4-29", 27.0, ["is_floral", "is_magical"], "night", True),
    ]),
    (575, "gothorita", [
        ("gothorita", "common", "32-39", 2.7, ["is_floral", "is_magical"], "night", True),
    ]),
    (576, "gothitelle", [
        ("gothitelle", "common", "41-49", 0.3, ["is_floral", "is_magical"], "night", True),
    ]),
    (676, "furfrou", [
        ("furfrou", "common", "22-47", 20.0, ["is_plains", "is_savanna"], None, True),
    ]),
    (677, "espurr", [
        ("espurr", "common", "11-36", 60.0, ["is_magical", "is_floral"], None, True),
    ]),
    (678, "meowstic", [
        ("meowstic", "common", "25-47", 5.0, ["is_magical", "is_floral"], None, True),
    ]),
    (679, "honedge", [
        ("honedge", "uncommon", "8-33", 50.0, ["is_spooky"], "night", True),
        ("honedge", "uncommon", "8-33", 25.0, ["is_cave"], None, False),
    ]),
    (680, "doublade", [
        ("doublade", "uncommon", "35-45", 7.0, ["is_spooky"], "night", True),
        ("doublade", "uncommon", "35-45", 3.5, ["is_cave"], None, False),
    ]),
    (681, "aegislash", [
        ("aegislash", "uncommon", "40-50", 0.8, ["is_spooky"], "night", True),
    ]),
    (707, "klefki", [
        ("klefki", "uncommon", "22-47", 20.0, ["is_cave", "is_dripstone"], None, False),
    ]),
    (835, "yamper", [
        ("yamper", "common", "2-27", 54.0, ["is_grassland", "is_plains", "is_savanna"], None, True),
    ]),
    (836, "boltund", [
        ("boltund", "common", "25-49", 4.8, ["is_grassland", "is_plains", "is_savanna"], None, True),
    ]),
    (854, "sinistea", [
        ("sinistea", "common", "6-31", 40.0, ["is_spooky", "is_magical"], "night", True),
    ]),
    (855, "polteageist", [
        ("polteageist", "common", "26-51", 4.0, ["is_spooky", "is_magical"], "night", True),
    ]),
    (863, "perrserker", [
        ("perrserker", "common", "28-44", 10.0, ["is_mountain", "is_freezing", "is_taiga"], None, True),
    ]),
    (866, "mrrime", [
        ("mrrime", "uncommon", "42-52", 0.416, ["is_freezing", "is_snowy"], None, True),
    ]),
    (867, "runerigus", [
        ("runerigus", "common", "34-48", 10.0, ["is_desert", "is_badlands"], "night", True),
    ]),
    (876, "indeedee", [
        ("indeedee", "common", "23-48", 40.0, ["is_magical", "is_floral"], None, True),
    ]),
    (926, "fidough", [
        ("fidough", "common", "6-31", 60.0, ["is_floral", "is_cherry_blossom"], None, True),
    ]),
    (927, "dachsbun", [
        ("dachsbun", "common", "26-48", 8.0, ["is_floral", "is_cherry_blossom"], None, True),
    ]),
    (935, "charcadet", [
        ("charcadet", "uncommon", "1-26", 60.0, ["is_volcanic", "is_thermal"], None, True),
    ]),
    (936, "armarouge", [
        ("armarouge", "uncommon", "21-53", 6.4, ["is_volcanic", "is_thermal"], "day", True),
    ]),
    (937, "ceruledge", [
        ("ceruledge", "uncommon", "21-53", 6.4, ["is_volcanic", "is_thermal"], "night", True),
    ]),
    (942, "maschiff", [
        ("maschiff", "common", "9-34", 60.0, ["is_badlands", "is_savanna"], None, True),
    ]),
    (943, "mabosstiff", [
        ("mabosstiff", "common", "30-51", 4.8, ["is_badlands", "is_savanna"], None, True),
    ]),
    (957, "tinkatink", [
        ("tinkatink", "common", "5-30", 27.0, ["is_mountain", "is_hills"], None, True),
        ("tinkatink", "common", "5-30", 13.5, ["is_cave"], None, False),
    ]),
    (958, "tinkatuff", [
        ("tinkatuff", "common", "24-38", 2.7, ["is_mountain", "is_hills"], None, True),
    ]),
    (959, "tinkaton", [
        ("tinkaton", "common", "38-51", 0.3, ["is_mountain", "is_hills"], None, True),
    ]),
    (999, "gimmighoul", [
        ("gimmighoul roaming=true", "common", "5-30", 60.0, ["is_forest", "is_plains", "is_hills"], None, True),
        ("gimmighoul roaming=false", "rare", "5-30", 60.0, ["is_cave"], None, False),
    ]),
    (1012, "poltchageist", [
        ("poltchageist matcha_authenticity=counterfeit", "uncommon", "6-31", 72.864,
         ["is_cherry_blossom", "is_forest", "is_jungle"], None, True),
        ("poltchageist matcha_authenticity=artisan", "uncommon", "6-31", 0.736,
         ["is_cherry_blossom", "is_forest", "is_jungle"], None, True),
    ]),
    (1013, "sinistcha", [
        ("sinistcha matcha_authenticity=unremarkable", "uncommon", "26-51", 6.336,
         ["is_cherry_blossom", "is_forest", "is_jungle"], None, True),
        ("sinistcha matcha_authenticity=masterpiece", "uncommon", "26-51", 0.064,
         ["is_cherry_blossom", "is_forest", "is_jungle"], None, True),
    ]),
]


def detail(name, index, pokemon, bucket, level, weight, biomes, time, sky):
    """One spawn detail: natural and wild, grounded, the Safari only, a biome condition and nothing structural."""
    condition = {"dimensions": [SAFARI_DIMENSION], "canSeeSky": sky, "biomes": ["#cobblemon:" + tag for tag in biomes]}
    if time:
        condition["timeRange"] = time
    return {
        "id": f"cobblemon_safari-{name}-{index}",
        "pokemon": pokemon,
        "presets": ["natural", "wild"],
        "type": "pokemon",
        "spawnablePositionType": "grounded",
        "bucket": bucket,
        "level": level,
        "weight": weight,
        "condition": condition,
    }


def main():
    os.makedirs(OUT, exist_ok=True)
    for dex, name, spawns in SPAWNS:
        data = {
            "enabled": True,
            "neededInstalledMods": [],
            "neededUninstalledMods": [],
            "spawns": [detail(name, i + 1, *spawn) for i, spawn in enumerate(spawns)],
        }
        path = os.path.join(OUT, f"{dex:04d}_{name}.json")
        with open(path, "w", encoding="utf-8") as file:
            json.dump(data, file, indent=2)
            file.write("\n")
    print(f"{len(SPAWNS)} files written to {OUT}")


if __name__ == "__main__":
    main()
