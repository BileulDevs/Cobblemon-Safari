#!/usr/bin/env python3
"""
Generates every worldgen JSON file of the Safari dimension.

Why a generator instead of hand-written JSON:
  * The terrain reuses the vanilla overworld density-function tree, which is
    thousands of lines long. Copying it by hand is error-prone; transforming it
    mechanically is not.
  * Minecraft refuses to load a world when two biomes of the same dimension list
    features in a contradictory order ("Feature order cycle found"). Building
    every biome from ONE global order computed from vanilla makes that
    impossible by construction.

Usage (from the repository root):
    python3 tools/worldgen/generate_worldgen.py

Vanilla data is downloaded once from misode/mcmeta (tag 1.21.1-data) into
tools/worldgen/.vanilla_cache/, then reused. Bump MC_DATA_TAG when porting.

The island shape needs the Java density function type
cobblemon_safari:island_coordinate (IslandCoordinateFunction): the generated
data does not load without the mod.

Every output file is overwritten. Do not hand-edit generated files: change this
script and regenerate, otherwise the next run silently drops your edits.
"""

import json
import math
import os
import shutil
import sys
import urllib.request

MC_DATA_TAG = "1.21.1-data"
MC_REGISTRIES_TAG = "1.21.1-registries"
MCMETA = "https://raw.githubusercontent.com/misode/mcmeta"

MOD_ID = "cobblemon_safari"
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
CACHE = os.path.join(os.path.dirname(os.path.abspath(__file__)), ".vanilla_cache")
OUT = os.path.join(ROOT, "common", "src", "main", "resources", "data", MOD_ID)

# ---------------------------------------------------------------------------
# Island layout
# ---------------------------------------------------------------------------
# The Safari is ONE island in an endless sea, built around a custom density
# function (cobblemon_safari:island_coordinate) that exposes the distance and
# the X/Z offset from the centre in "radii". Every climate parameter is a
# deterministic layout driven by that position, plus noise so borders wander.

ISLAND_CENTER_X = 0
ISLAND_CENTER_Z = 0

# Radius, in blocks, of the island's shape. Land ends around 0.85 of it (see
# SHORE_START / COAST_STEEPEN_*), so 512 gives about 900 blocks of land across.
# Everything below scales with it, so changing it keeps the same layout at
# another size. The mod reads the matching park geometry (border, arrival)
# from park/ParkGeometry.java, which this script writes.
ISLAND_RADIUS = 512

# The world border is a square of half-side ISLAND_RADIUS * (1 + BORDER_MARGIN):
# it encloses the whole island plus a strip of lagoon, and its corners are
# open sea. Below ~0.05 the border cuts into beaches on the widest capes.
BORDER_MARGIN = 0.13

# Arrival point, as a fraction of the radius north of the centre: inside the
# grassland sector, past the rocky ring, well before the coast.
ARRIVAL_NORTH = 0.55

# Arrivals are spread this far around the arrival point, in blocks, so a
# group does not land on one block. Larger scatters players into the hills.
ARRIVAL_SPREAD = 16

# Rest houses (Pokémon Centers inside the park), as anchors: the mod builds
# each one at the best dry, flat spot near its anchor. The first is the
# entrance, this many blocks north of the arrival point so players land in
# front of it; moving it farther makes new players walk to find it.
ENTRANCE_HOUSE_NORTH_OF_ARRIVAL = 14
# The others stand east, south and west of the centre, at this fraction of
# the radius (the arrival's ring): inland, past the rocky ring, before the
# coast. Toward 1 they end up on beaches, toward 0 on the central summit.
REST_HOUSE_RING = ARRIVAL_NORTH

# Java class holding the park geometry the mod uses at runtime (border,
# boundary, arrival), written from the values above so code and terrain
# can never disagree.
PARK_GEOMETRY_JAVA = os.path.join(ROOT, "common", "src", "main", "java", "dev", "darcosse", "common",
                                  "cobblemonsafari", "park", "ParkGeometry.java")

# Continentalness at the centre, and the fraction of the radius where the coast
# begins (continentalness -0.11, vanilla "coast" threshold). The slope follows.
# Raising SHORE_START moves beaches outward and shrinks the lagoon ring.
CONTINENT_AT_CENTER = 0.5
SHORE_START = 0.85
CONTINENT_SLOPE = (CONTINENT_AT_CENTER - (-0.11)) / SHORE_START

# Beyond COAST_STEEPEN_FROM (fraction of the radius) the slope is multiplied
# by COAST_STEEPEN_FACTOR. The beach band width is 0.08 / slope radii, so it
# grows with ISLAND_RADIUS: at 512 and factor 2 it was ~30 blocks and the
# largest habitat of the island; at 3.5 it is ~16 blocks and land ends at
# about 0.85 radius.
COAST_STEEPEN_FROM = 0.8
COAST_STEEPEN_FACTOR = 3.5

# Amplitude of the coastline wobble. 0.12 moves the shore by about
# 0.12 / CONTINENT_SLOPE = 17% of the radius: bays and capes without islets.
# Above ~0.25 the island breaks into an archipelago.
COAST_NOISE = 0.12

# Lowest continentalness, reached in the open sea around the island.
# Vanilla's terrain spline RAISES the sea floor again below -1.02 (that is
# where mushroom islands come from): clamping at -1.1, as this script once
# did, grew sand and clay mounds up to the surface all around the island, in
# the sea inside the border. -1.0 stays in vanilla's deep-ocean band
# (-1.05 to -0.455), whose floor is flat and deep. Lower brings the mounds
# back; higher makes the open sea shallower.
CONTINENT_FLOOR = -1.0

# Erosion goes from sharp relief at the centre (a central mountain) to flat
# lowlands on the coast. With these values: peaks within ~15% of the radius,
# rocky ring to ~35%, rolling hills to ~74%, flat lowlands beyond. The centre
# starts below -1 (clamped) so the summit survives the erosion noise; a
# gentler slope widens the rocky ring until it becomes the dominant habitat.
EROSION_AT_CENTER = -1.1
EROSION_SLOPE = 2.1
EROSION_NOISE = 0.25

# Noise added to the direction vector before the nearest-sector lookup. 0.15
# swings sector borders by about 9 degrees each way (~45 blocks at mid-radius):
# borders still interlock, and the smallest sector of a map keeps about three
# quarters of its nominal size whatever the seed. At 0.3 (before 0.6.1) the
# smallest sector of some maps shrank to half its size, and the daily map
# reset made that visible: some days one habitat was a thin strip. Above ~0.5
# small sectors start to vanish in places.
CLIMATE_NOISE = 0.15

# Radius of the climate circle in parameter space. multi_noise ranks candidates
# by the SUM of squared distances over all parameters, so the sector lookup
# must contribute almost nothing, or sector biomes lose to ANY-climate biomes
# (shore, river, caves) that miss their real criterion (continentalness,
# weirdness, depth) by a small margin. At 1.0 caves appeared on the surface
# and beaches spread 100 blocks inland. Nearest-point wedges are scale
# invariant, so shrinking the circle keeps the sectors identical. Must stay
# well above the 1e-4 quantisation of climate parameters.
CLIMATE_SCALE = 0.05

# firstOctave of each noise. One step lower doubles the feature size. With the
# router's 0.25 xz_scale, octave -6 gives features about 256 blocks across,
# the right grain for a ~600 block island.
NOISE_FIRST_OCTAVE = {
    "continentalness": -6,
    "erosion": -6,
    "ridge": -6,
    "temperature": -6,
    "vegetation": -6,
}

# Density function types provided by the mod (registered in Java).
CUSTOM_DENSITY_FUNCTION_TYPES = {f"{MOD_ID}:island_coordinate"}
# Java structure types of the mod (SafariWorldgen.registerStructureTypes).
CUSTOM_STRUCTURE_TYPES = {f"{MOD_ID}:dry_jigsaw"}

# ---------------------------------------------------------------------------
# Atmosphere
# ---------------------------------------------------------------------------
# Applied to every Safari biome. Sky colour is blended between neighbouring
# biomes, so a single palette avoids patches of different sky while walking.
# Turquoise zenith + golden horizon haze reads as a warm tropical afternoon,
# clearly unlike the overworld's blue-violet sky and pale blue fog.
SKY_COLOR = 0x5EC8E5
FOG_COLOR = 0xF1D9A6
WATER_COLOR = 0x2FBFC9
WATER_FOG_COLOR = 0x0E4A57
# Biomes that keep their model's water colours (the marsh must stay murky).
KEEP_MODEL_WATER = {"marsh"}

# Id of the client-side dimension effects (sunrise colour, cloud height).
# Must match SafariDimensions.EFFECTS_ID in Java.
DIMENSION_EFFECTS = f"{MOD_ID}:safari"

# Vanilla density functions that depend on the climate noises. They are copied
# into our namespace so that terrain follows OUR noises; every other vanilla
# density function (caves, base 3D noise, shifts) is referenced as-is.
CLIMATE_DENSITY_FUNCTIONS = [
    "continents", "erosion", "ridges", "ridges_folded",
    "offset", "factor", "jaggedness", "depth", "sloped_cheese",
]

# Features removed from every Safari biome, whatever the vanilla model says.
EXCLUDED_FEATURES = {
    # Dungeon spawners keep producing hostile mobs even with empty biome spawners.
    "minecraft:monster_room",
    "minecraft:monster_room_deep",
    # Open lava pools on the surface burn players and Pokemon for no gameplay value.
    "minecraft:lake_lava_surface",
}

# ---------------------------------------------------------------------------
# Biomes
# ---------------------------------------------------------------------------
# Each Safari biome borrows features, colours and climate from one or more
# vanilla models. Features are the UNION of the models, re-sorted in the global
# vanilla order, then EXCLUDED_FEATURES are removed and "extra_features" added.
# "climate" overrides the model's precipitation/temperature/downfall.
BIOMES = {
    # Surface habitats laid out as sectors (see SECTORS).
    "grassland":      {"models": ["plains", "sunflower_plains"], "base": "plains"},
    "savanna":        {"models": ["savanna"], "base": "savanna"},
    "dunes":          {"models": ["desert"], "base": "desert"},
    "badlands":       {"models": ["badlands"], "base": "badlands"},
    "jungle":         {"models": ["jungle"], "base": "jungle"},
    "marsh":          {"models": ["swamp"], "base": "swamp"},
    "flower_meadow":  {"models": ["meadow", "cherry_grove"], "base": "cherry_grove"},
    "woodland":       {"models": ["forest", "birch_forest"], "base": "forest"},
    "deep_forest":    {"models": ["dark_forest"], "base": "dark_forest"},
    "taiga":          {"models": ["taiga"], "base": "taiga"},
    "snowfield":      {"models": ["snowy_plains"], "base": "snowy_plains"},
    "glacier":        {"models": ["ice_spikes"], "base": "ice_spikes"},
    "rocky_hills":    {"models": ["windswept_gravelly_hills"], "base": "windswept_gravelly_hills"},
    # A sector of its own between the marsh and the flower meadow. It used to
    # be only the coastal lowland of the flower meadow sector, which left it a
    # thin strip on the coast and the meadow half its share.
    "mushroom_grove": {"models": ["mushroom_fields"], "base": "mushroom_fields"},
    # Rings around the central summit.
    "peaks":          {"models": ["jagged_peaks"], "base": "jagged_peaks"},
    "volcanic":       {"models": ["stony_peaks"], "base": "stony_peaks",
                       # Lava pools are the point of this biome; players are
                       # invulnerable and fire does not spread in the Safari.
                       "extra_features": ["minecraft:lake_lava_surface"],
                       "climate": {"has_precipitation": False, "temperature": 2.0, "downfall": 0.0}},
    # Water and shores.
    "lake":           {"models": ["ocean"], "base": "ocean"},
    "river":          {"models": ["river"], "base": "river"},
    "shore":          {"models": ["beach"], "base": "beach"},
    # Underground.
    "cavern":         {"models": ["dripstone_caves"], "base": "dripstone_caves"},
    "lush_grotto":    {"models": ["lush_caves"], "base": "lush_caves"},
}

# ---------------------------------------------------------------------------
# Sound: music and wild Pokemon calls of each habitat
# ---------------------------------------------------------------------------

# Music zone of each habitat: the Safari's own tracks, composed by
# tools/music/compose_safari_music.py into sounds/music/<zone>.ogg. They
# replace vanilla music inside the Safari. The rest houses play the "center"
# zone (client mixin, not a biome). Delays are in ticks between two tracks;
# vanilla overworld biomes wait 10 to 20 minutes, the Safari 1 to 3 so a hunt
# hears its music. Shorter delays make it more present and more repetitive.
MUSIC_MIN_DELAY = 1200
MUSIC_MAX_DELAY = 3600
MUSIC_ZONES = ["plains", "forest", "arid", "snow", "caves", "center"]
MUSIC_DIR = os.path.join(ROOT, "common", "src", "main", "resources", "assets", MOD_ID, "sounds", "music")
GATE_SOUND_DIR = os.path.join(ROOT, "common", "src", "main", "resources", "assets", MOD_ID, "sounds", "gate")
# event id: (file under sounds/gate, subtitle key suffix, streamed). The
# 25-second loop is streamed like music; the short chime is loaded whole.
GATE_SOUNDS = {
    "block.safari_gate.ambient": ("ambience", "block.safari_gate.ambient", True),
    "block.safari_gate.chime": ("chime", "block.safari_gate.chime", False),
}

# Calls of wild Pokemon heard now and then (the biome's "additions" sound):
# chance per tick, so 0.004 is one call every ~12 seconds on average. Played
# at this volume, not positional, like vanilla cave additions.
CRY_TICK_CHANCE = 0.004
CRY_VOLUME = 0.3

# Cobblemon cry files are assets/cobblemon/sounds/pokemon/<species>/<species>_cry.ogg.
# Only species one can meet there without bait: common spawns of the
# habitat's bridged tags in Cobblemon 1.8.0, with a cry file. A cry of a
# species that never shows up promises a Pokemon the habitat does not have;
# re-check these lists when the bridge or Cobblemon's spawn data changes.
HABITAT_SOUNDS = {
    "grassland":      ("plains", ["rattata", "yungoos", "nymble", "jigglypuff", "scatterbug"]),
    "savanna":        ("plains", ["meowth", "purrloin", "komala", "ekans", "sandshrew"]),
    "dunes":          ("arid",   ["cacnea", "silicobra", "maractus", "sandshrew", "golett"]),
    "badlands":       ("arid",   ["dwebble", "orthworm", "cacnea", "sandshrew", "bramblin"]),
    "jungle":         ("forest", ["oddish", "bellsprout", "caterpie", "weedle"]),
    "marsh":          ("forest", ["wooper", "croagunk", "grimer", "stunfisk", "karrablast"]),
    "mushroom_grove": ("forest", ["foongus", "paras", "shroomish", "morelull", "toedscool"]),
    "flower_meadow":  ("plains", ["roselia", "cherubi", "fomantis", "budew", "jigglypuff"]),
    "woodland":       ("forest", ["bidoof", "skwovet", "stunky", "wurmple", "buneary"]),
    "deep_forest":    ("forest", ["misdreavus", "gastly", "stunky", "skwovet", "nincada"]),
    "taiga":          ("forest", ["pineco", "bidoof", "zigzagoon", "buneary", "skwovet"]),
    "snowfield":      ("snow",   ["snom", "sandshrew", "buneary", "bidoof", "zigzagoon"]),
    "glacier":        ("snow",   ["bergmite", "seel", "spheal", "shellder", "snom"]),
    "rocky_hills":    ("arid",   ["machop", "buneary", "buizel", "azurill"]),
    "peaks":          ("snow",   ["geodude", "meditite", "teddiursa", "makuhita", "nymble"]),
    "volcanic":       ("arid",   ["slugma", "sizzlipede", "cubone", "magcargo"]),
    "lake":           ("plains", ["shellos", "corsola", "wishiwashi", "mareanie"]),
    "river":          ("plains", ["poliwag", "slowpoke", "psyduck", "marill", "bidoof"]),
    "shore":          ("plains", ["krabby", "wimpod", "slowpoke", "crabrawler", "staryu"]),
    "cavern":         ("caves",  ["zubat", "geodude", "onix", "roggenrola", "lunatone"]),
    "lush_grotto":    ("caves",  ["ferroseed", "shuckle", "morelull", "goomy", "venipede"]),
}

SOUNDS_OUT = os.path.join(ROOT, "common", "src", "main", "resources", "assets", MOD_ID, "sounds.json")


def ambient_event(biome):
    """Id of the sound event holding a habitat's Pokemon calls."""
    return f"ambient.{biome}"


def music_event(zone):
    """Id of the sound event playing a music zone. Mirrored by SafariMusic.CENTER_EVENT for "center"."""
    return f"music.{zone}"


def write_sounds():
    """
    Writes assets/<mod>/sounds.json: one event per habitat, picking one of
    its Pokemon cries at random, and one streamed event per music zone. The
    file is owned by this script.
    """
    missing = set(HABITAT_SOUNDS) ^ set(BIOMES)
    if missing:
        sys.exit(f"HABITAT_SOUNDS and BIOMES disagree on: {sorted(missing)}")
    unknown_zones = {zone for zone, _ in HABITAT_SOUNDS.values()} - set(MUSIC_ZONES)
    if unknown_zones:
        sys.exit(f"HABITAT_SOUNDS uses music zones missing from MUSIC_ZONES: {sorted(unknown_zones)}")
    missing_tracks = [z for z in MUSIC_ZONES if not os.path.exists(os.path.join(MUSIC_DIR, f"{z}.ogg"))]
    if missing_tracks:
        sys.exit(f"Missing music tracks {missing_tracks}: run tools/music/compose_safari_music.py first")
    events = {}
    # The Safari Gate's own sounds (tools/music/compose_safari_music.py, "gate"):
    # its relaxed tune, looped by the client near a gate (GateAmbience), and the
    # chime played when a player steps into the pane. Mirrored by GateSounds.
    for event, (file, subtitle, stream) in GATE_SOUNDS.items():
        if not os.path.exists(os.path.join(GATE_SOUND_DIR, f"{file}.ogg")):
            sys.exit(f"Missing gate sound {file}.ogg: run tools/music/compose_safari_music.py gate first")
        entry = {"name": f"{MOD_ID}:gate/{file}"}
        if stream:
            entry["stream"] = True
        events[event] = {"subtitle": f"subtitles.{MOD_ID}.{subtitle}", "sounds": [entry]}
    for zone in MUSIC_ZONES:
        # stream: music is read from disk while it plays instead of being
        # decoded whole into memory, as vanilla does for its own music.
        events[music_event(zone)] = {"sounds": [{"name": f"{MOD_ID}:music/{zone}", "stream": True}]}
    for biome, (_, cries) in sorted(HABITAT_SOUNDS.items()):
        events[ambient_event(biome)] = {
            "subtitle": f"subtitles.{MOD_ID}.ambient.pokemon",
            "sounds": [{"name": f"cobblemon:pokemon/{c}/{c}_cry", "volume": CRY_VOLUME} for c in cries],
        }
    with open(SOUNDS_OUT, "w", encoding="utf-8", newline="\n") as f:
        json.dump(events, f, indent=2)
        f.write("\n")
    return len(events)


# Vanilla biomes only used to compute the global feature order. The more of
# them, the more constraints are known; they must all be overworld biomes.
ORDER_REFERENCE_BIOMES = sorted({m for b in BIOMES.values() for m in b["models"]} | {
    "old_growth_spruce_taiga", "lukewarm_ocean", "snowy_taiga", "wooded_badlands",
})

# ---------------------------------------------------------------------------
# Tags: this is the public contract used by Cobblemon spawn files.
# Renaming a tag breaks every spawn file that references it.
# ---------------------------------------------------------------------------
TAGS = {
    # One tag per habitat.
    "safari_plains": ["grassland"],
    "safari_savanna": ["savanna"],
    "safari_desert": ["dunes"],
    "safari_badlands": ["badlands"],
    "safari_jungle": ["jungle"],
    "safari_swamp": ["marsh"],
    "safari_meadow": ["flower_meadow"],
    "safari_forest": ["woodland"],
    "safari_deep_forest": ["deep_forest"],
    "safari_taiga": ["taiga"],
    "safari_snow": ["snowfield"],
    "safari_ice": ["glacier"],
    "safari_rocky": ["rocky_hills"],
    "safari_mushroom": ["mushroom_grove"],
    "safari_mountain": ["peaks"],
    "safari_volcanic": ["volcanic"],
    "safari_lake": ["lake"],
    "safari_river": ["river"],
    "safari_beach": ["shore"],
    "safari_cave": ["cavern", "lush_grotto"],
    "safari_lush_cave": ["lush_grotto"],
    # Aggregates, for Pokemon that roam several habitats.
    "safari_grassland": ["grassland", "savanna", "flower_meadow"],
    "safari_wooded": ["woodland", "deep_forest", "taiga", "jungle"],
    "safari_water": ["lake", "river"],
    "safari_wetland": ["marsh", "river", "lake", "shore", "mushroom_grove"],
    "safari_arid": ["dunes", "savanna", "badlands", "volcanic"],
    "safari_frozen": ["snowfield", "glacier", "peaks"],
    "safari_highland": ["rocky_hills", "peaks", "volcanic"],
    "is_safari": list(BIOMES.keys()),
}

# ---------------------------------------------------------------------------
# Bridge to Cobblemon's biome tags
# ---------------------------------------------------------------------------
# Cobblemon's natural spawns (and most spawn datapacks) target its own biome
# tags (#cobblemon:is_desert...). Adding each Safari biome to the matching tags
# is what lets the native spawner populate the Safari with the right fauna,
# including Pokemon added by datapacks, with no spawn file to maintain here.
#
# Deliberately absent from surface habitats: #cobblemon:is_overworld (only the
# underground cavern takes it, see there), is_temperate-style catch-alls
# used by "spawns everywhere" Pokemon, and has_structure/*. Adding them would
# pour the generic overworld pool into every habitat and erase the difference
# between zones. The Java SafariHabitatIndex reads these same tags at runtime,
# so this table is the single source of truth for "which Pokemon fit where".
COBBLEMON_TAG_BRIDGE = {
    "grassland":      ["is_plains", "is_grassland"],
    "savanna":        ["is_savanna", "is_arid"],
    "dunes":          ["is_desert", "is_arid", "is_sandy"],
    "badlands":       ["is_badlands", "is_arid"],
    "jungle":         ["is_jungle"],
    "marsh":          ["is_swamp"],
    # is_plains: the meadow's open-field fauna; 32 species without bait alone.
    "flower_meadow":  ["is_floral", "is_cherry_blossom", "is_plains"],
    "woodland":       ["is_forest"],
    "deep_forest":    ["is_forest", "is_spooky"],
    "taiga":          ["is_taiga", "is_cold"],
    # is_snowy and is_tundra alone left the snowfield about six species one can
    # meet without bait in Cobblemon 1.8.0: the snowy forests and taigas add
    # the snow-country fauna (Buneary, Alolan Vulpix, Bidoof, Absol...).
    "snowfield":      ["is_snowy", "is_tundra", "is_snowy_forest", "is_snowy_taiga"],
    # is_frozen_ocean brings the icy coast (Spheal, Seel, Cubchoo, Shellder,
    # Lapras), which no other habitat bridged.
    "glacier":        ["is_glacial", "is_snowy", "is_freezing", "is_frozen_ocean"],
    "rocky_hills":    ["is_hills", "is_highlands"],
    # is_magical: fairy and mystic species, at home among giant mushrooms;
    # is_mushroom alone gives 23 species.
    "mushroom_grove": ["is_mushroom", "is_magical"],
    "peaks":          ["is_peak", "is_mountain"],
    # is_badlands: the rocky, arid fauna of the volcano's slopes; the volcanic
    # tags alone give 18 species.
    "volcanic":       ["is_volcanic", "is_thermal", "is_badlands"],
    "lake":           ["is_ocean", "is_warm_ocean"],
    "river":          ["is_river", "is_freshwater"],
    "shore":          ["is_beach", "is_coast"],
    # is_overworld, the one catch-all allowed: Cobblemon spawns its cave
    # Pokemon (Zubat, Geodude, Onix, Gible, Gastly... 126 species) through it
    # with underground conditions, so is_cave alone left 13 species. Safe here
    # only because the cavern is underground (depth D_UNDERGROUND): it is what
    # any cave of a normal Cobblemon world gets, surface strays included.
    "cavern":         ["is_cave", "is_dripstone", "is_overworld"],
    "lush_grotto":    ["is_cave", "is_lush"],
}

# Biome tags shipped by Cobblemon 1.8.0. A bridged tag missing from this list
# is not an error (it would simply be a new tag no Cobblemon spawn uses), but
# it is almost always a typo, so the generator refuses it.
COBBLEMON_KNOWN_BIOME_TAGS = {
    "is_arid", "is_badlands", "is_bamboo", "is_beach", "is_cave", "is_cherry_blossom", "is_coast",
    "is_cold", "is_cold_ocean", "is_deep_dark", "is_deep_ocean", "is_desert", "is_dripstone", "is_end",
    "is_floral", "is_forest", "is_freezing", "is_freshwater", "is_frozen_ocean", "is_glacial",
    "is_grassland", "is_highlands", "is_hills", "is_island", "is_jungle", "is_lukewarm_ocean", "is_lush",
    "is_magical", "is_mirage_island", "is_mountain", "is_mushroom", "is_ocean", "is_overworld",
    "is_peak", "is_plains", "is_plateau", "is_river", "is_sandy", "is_savanna", "is_shrubland",
    "is_sky", "is_snowy", "is_snowy_forest", "is_snowy_taiga", "is_spooky", "is_stony_beach",
    "is_swamp", "is_taiga", "is_temperate", "is_temperate_ocean", "is_thermal", "is_tropical_island",
    "is_tundra", "is_volcanic", "is_warm_ocean",
}

# Where the bridge files go: Cobblemon's namespace, merged into its tags
# because they use "replace": false.
COBBLEMON_TAG_OUT = os.path.join(ROOT, "common", "src", "main", "resources", "data", "cobblemon",
                                 "tags", "worldgen", "biome")

# ---------------------------------------------------------------------------
# Climate placement (multi_noise)
# ---------------------------------------------------------------------------
# Surface habitats are SECTORS around the central summit. The router feeds
# temperature = direction_x and humidity = direction_z (the unit vector from
# the centre, plus noise), so every column sits near the unit circle in the
# (temperature, humidity) plane. Each sector habitat is a POINT on that circle;
# multi_noise picks the nearest point, whose Voronoi cells on a circle are
# wedges. Result: each habitat gets a slice from the rocky ring to the coast,
# boundaries wander with the noise, and cells can never overlap.
#
# Order is clockwise starting north (the entrance), so neighbours are climatic
# neighbours: warm arid east, humid south, cold west, back to the entrance.
SECTORS = [
    # (habitat, flat coastal-lowland variant)
    ("grassland", "grassland"),
    ("savanna", "savanna"),
    ("dunes", "dunes"),
    ("badlands", "badlands"),
    ("jungle", "jungle"),
    ("marsh", "marsh"),
    ("mushroom_grove", "mushroom_grove"),
    ("flower_meadow", "flower_meadow"),
    ("woodland", "woodland"),
    ("deep_forest", "deep_forest"),
    ("taiga", "taiga"),
    ("snowfield", "snowfield"),
    ("glacier", "glacier"),
    ("rocky_hills", "rocky_hills"),
]
# Angle of the first sector, in degrees, with 0 = east and 90 = south
# (Minecraft's +Z). -90 puts grassland due north, where the arrival point is.
FIRST_SECTOR_ANGLE = -90.0

# Rocky ring around the summit: (habitat, angle). Nearest-point again, so the
# volcano takes the eastern third, facing the arid sectors.
ROCKY_RING = [("volcanic", -10.0), ("rocky_hills", 110.0), ("rocky_hills", 230.0)]

# Continentalness bands (vanilla thresholds): below -0.19 water, then coast.
C_WATER = [-1.2, -0.19]
C_COAST = [-0.19, -0.11]
C_INLAND = [-0.11, 1.0]
C_COAST_AND_INLAND = [-0.19, 1.0]

# Erosion bands, driven by the distance to the centre (see EROSION_*).
E_ALL = [-1.0, 1.0]
E_PEAK = [-1.0, -0.78]
E_ROCKY = [-0.78, -0.375]
E_ROLLING = [-0.375, 0.45]
E_FLAT = [0.45, 1.0]
E_NOT_STEEP = [-0.375, 1.0]

W_ALL = [-1.0, 1.0]
W_VALLEY = [-0.05, 0.05]
W_NOT_VALLEY = [[-1.0, -0.05], [0.05, 1.0]]

# Climate values leave [-1, 1] once noise is added; ranges meant to cover
# "anything" must be wider than that or far values would snap to other points.
ANY = [-2.0, 2.0]

# Underground: vanilla convention, depth 0 is the surface, 0.2+ is below it.
D_SURFACE = 0.0
D_UNDERGROUND = [0.2, 1.0]
# Humidity above which caves are lush: the southern sectors (direction_z > 0.5).
LUSH_HUMIDITY = 0.5 * CLIMATE_SCALE


def point(biome, t, h, c=C_INLAND, e=E_ALL, w=W_ALL, depth=D_SURFACE):
    """One multi_noise entry. Numbers are points, pairs are ranges; offset 0 = no preference."""
    return {
        "biome": f"{MOD_ID}:{biome}",
        "parameters": {
            "temperature": t, "humidity": h, "continentalness": c,
            "erosion": e, "weirdness": w, "depth": depth, "offset": 0.0,
        },
    }


def on_circle(angle_degrees):
    a = math.radians(angle_degrees)
    # 5 decimals: the climate circle is small (CLIMATE_SCALE), 4 would move points.
    return round(CLIMATE_SCALE * math.cos(a), 5), round(CLIMATE_SCALE * math.sin(a), 5)


def climate_points():
    pts = [
        point("lake", ANY, ANY, c=C_WATER),
        # The coast is sand only where terrain is gentle; steep coasts stay rocky.
        point("shore", ANY, ANY, c=C_COAST, e=E_NOT_STEEP),
        point("peaks", ANY, ANY, c=C_COAST_AND_INLAND, e=E_PEAK),
        # Rivers only on valley floors of non-steep inland terrain.
        point("river", ANY, ANY, c=C_INLAND, e=E_NOT_STEEP, w=W_VALLEY),
        # Caves under the island (not under the sea).
        point("cavern", ANY, [-2.0, LUSH_HUMIDITY], c=C_COAST_AND_INLAND, depth=D_UNDERGROUND),
        point("lush_grotto", ANY, [LUSH_HUMIDITY, 2.0], c=C_COAST_AND_INLAND, depth=D_UNDERGROUND),
    ]
    for biome, angle in ROCKY_RING:
        t, h = on_circle(angle)
        pts.append(point(biome, t, h, c=C_COAST_AND_INLAND, e=E_ROCKY))
    step = 360.0 / len(SECTORS)
    for i, (habitat, lowland) in enumerate(SECTORS):
        t, h = on_circle(FIRST_SECTOR_ANGLE + i * step)
        for w in W_NOT_VALLEY:
            pts.append(point(habitat, t, h, e=E_ROLLING, w=w))
            pts.append(point(lowland, t, h, e=E_FLAT, w=w))
    return pts

# ---------------------------------------------------------------------------
# Vanilla data access
# ---------------------------------------------------------------------------

def fetch(tag, path):
    local = os.path.join(CACHE, tag, path)
    if not os.path.exists(local):
        os.makedirs(os.path.dirname(local), exist_ok=True)
        url = f"{MCMETA}/{tag}/{path}"
        print(f"  downloading {url}")
        with urllib.request.urlopen(url, timeout=60) as r:
            data = r.read()
        with open(local, "wb") as f:
            f.write(data)
    with open(local, encoding="utf-8") as f:
        return json.load(f)


def vanilla(path):
    return fetch(MC_DATA_TAG, f"data/minecraft/{path}.json")


def registry(name):
    return set(fetch(MC_REGISTRIES_TAG, f"{name}/data.json"))


# ---------------------------------------------------------------------------
# Transformations
# ---------------------------------------------------------------------------

def rename_refs(node):
    """Points climate density functions and noises to our namespace."""
    if isinstance(node, dict):
        return {k: rename_refs(v) for k, v in node.items()}
    if isinstance(node, list):
        return [rename_refs(v) for v in node]
    if isinstance(node, str):
        for df in CLIMATE_DENSITY_FUNCTIONS:
            if node == f"minecraft:overworld/{df}":
                return f"{MOD_ID}:safari/{df}"
        for noise in NOISE_FIRST_OCTAVE:
            if node == f"minecraft:{noise}":
                return f"{MOD_ID}:{noise}"
    return node


def global_feature_order():
    """Per generation step, a total order consistent with every reference biome."""
    steps = [dict() for _ in range(11)]  # feature -> set of successors
    for name in ORDER_REFERENCE_BIOMES:
        for i, feats in enumerate(vanilla(f"worldgen/biome/{name}")["features"]):
            for a_idx, a in enumerate(feats):
                steps[i].setdefault(a, set())
                for b in feats[a_idx + 1:]:
                    steps[i][a].add(b)
    orders = []
    for i, graph in enumerate(steps):
        indeg = {n: 0 for n in graph}
        for succ in graph.values():
            for b in succ:
                indeg[b] = indeg.get(b, 0) + 1
        ready = sorted(n for n, d in indeg.items() if d == 0)
        order = []
        while ready:
            n = ready.pop(0)
            order.append(n)
            for b in sorted(graph.get(n, ())):
                indeg[b] -= 1
                if indeg[b] == 0:
                    ready.append(b)
            ready.sort()
        if len(order) != len(indeg):
            sys.exit(f"Vanilla feature order cycle in step {i}: reference biomes are inconsistent")
        orders.append({f: idx for idx, f in enumerate(order)})
    return orders


def build_biome(name, spec, orders):
    base = vanilla(f"worldgen/biome/{spec['base']}")
    features = []
    for step in range(11):
        union = set()
        for model in spec["models"]:
            union.update(vanilla(f"worldgen/biome/{model}")["features"][step])
        union -= EXCLUDED_FEATURES
        for extra in spec.get("extra_features", []):
            if extra in orders[step]:
                union.add(extra)
        features.append(sorted(union, key=lambda f: orders[step][f]))
    placed = {f for step in features for f in step}
    missing = set(spec.get("extra_features", [])) - placed
    if missing:
        sys.exit(f"extra_features {missing} of {name} are not in the global order of any step")
    effects = dict(base["effects"])
    zone, cries = HABITAT_SOUNDS[name]
    # Inline sound event, like the cries below. replace_current_music stays
    # false: crossing into another zone lets the current track finish
    # instead of cutting it mid-phrase.
    effects["music"] = {"sound": {"sound_id": f"{MOD_ID}:{music_event(zone)}"}, "min_delay": MUSIC_MIN_DELAY,
                        "max_delay": MUSIC_MAX_DELAY, "replace_current_music": False}
    if cries:
        # Inline sound event: the id only has to exist in sounds.json (client),
        # not in the sound event registry.
        effects["additions_sound"] = {"sound": {"sound_id": f"{MOD_ID}:{ambient_event(name)}"},
                                      "tick_chance": CRY_TICK_CHANCE}
    effects["sky_color"] = SKY_COLOR
    effects["fog_color"] = FOG_COLOR
    if name not in KEEP_MODEL_WATER:
        effects["water_color"] = WATER_COLOR
        effects["water_fog_color"] = WATER_FOG_COLOR
    climate = {k: base[k] for k in ("has_precipitation", "temperature", "downfall")}
    climate.update(spec.get("climate", {}))
    biome = {
        "has_precipitation": climate["has_precipitation"],
        "temperature": climate["temperature"],
        "downfall": climate["downfall"],
        "effects": effects,
        "carvers": base["carvers"],
        "features": features,
        # Vanilla mobs are deliberately absent: the Safari only hosts Pokemon.
        "spawners": {k: [] for k in base["spawners"]},
        "spawn_costs": {},
    }
    if "temperature_modifier" in base:
        biome["temperature_modifier"] = base["temperature_modifier"]
    return biome


# ---------------------------------------------------------------------------
# Island density functions
# ---------------------------------------------------------------------------

def island(mode):
    return {"type": f"{MOD_ID}:island_coordinate", "mode": mode,
            "center_x": ISLAND_CENTER_X, "center_z": ISLAND_CENTER_Z, "radius": ISLAND_RADIUS}


def noise_df(noise):
    return {"type": "minecraft:shifted_noise", "noise": f"{MOD_ID}:{noise}",
            "shift_x": "minecraft:shift_x", "shift_y": 0.0, "shift_z": "minecraft:shift_z",
            "xz_scale": 0.25, "y_scale": 0.0}


def df_add(a, b):
    return {"type": "minecraft:add", "argument1": a, "argument2": b}


def df_mul(a, b):
    return {"type": "minecraft:mul", "argument1": a, "argument2": b}


def df_clamp(x, lo, hi):
    return {"type": "minecraft:clamp", "input": x, "min": lo, "max": hi}


def flat_cache(x):
    # Outermost so the value is computed once per column, not once per block.
    return {"type": "minecraft:flat_cache", "argument": x}


def continents_df():
    radial = island("radial")
    beyond = {"type": "minecraft:max", "argument1": df_add(radial, -COAST_STEEPEN_FROM), "argument2": 0.0}
    extra_slope = CONTINENT_SLOPE * (COAST_STEEPEN_FACTOR - 1.0)
    shape = df_add(df_add(CONTINENT_AT_CENTER, df_mul(-CONTINENT_SLOPE, radial)), df_mul(-extra_slope, beyond))
    return flat_cache(df_clamp(df_add(shape, df_mul(COAST_NOISE, noise_df("continentalness"))),
                               CONTINENT_FLOOR, 1.0))


def erosion_df():
    shape = df_add(EROSION_AT_CENTER, df_mul(EROSION_SLOPE, island("radial")))
    return flat_cache(df_clamp(df_add(shape, df_mul(EROSION_NOISE, noise_df("erosion"))), -1.0, 1.0))


def temperature_df():
    return df_mul(CLIMATE_SCALE, df_add(island("direction_x"), df_mul(CLIMATE_NOISE, noise_df("temperature"))))


def humidity_df():
    return df_mul(CLIMATE_SCALE, df_add(island("direction_z"), df_mul(CLIMATE_NOISE, noise_df("vegetation"))))


# ---------------------------------------------------------------------------
# Surface rule
# ---------------------------------------------------------------------------

def block(name, props=None):
    state = {"Name": name}
    if props:
        state["Properties"] = props
    return {"type": "minecraft:block", "result_state": state}


def cond(if_true, then_run):
    return {"type": "minecraft:condition", "if_true": if_true, "then_run": then_run}


def seq(*rules):
    return {"type": "minecraft:sequence", "sequence": list(rules)}


def biome_is(*names):
    return {"type": "minecraft:biome", "biome_is": [f"{MOD_ID}:{n}" for n in names]}


def noise_between(noise, lo, hi):
    return {"type": "minecraft:noise_threshold", "noise": noise, "min_threshold": lo, "max_threshold": hi}


def y_above(y, mult=0):
    return {"type": "minecraft:y_above", "anchor": {"absolute": y}, "surface_depth_multiplier": mult, "add_stone_depth": False}


ON_FLOOR = {"type": "minecraft:stone_depth", "offset": 0, "add_surface_depth": False, "secondary_depth_range": 0, "surface_type": "floor"}
UNDER_FLOOR = {"type": "minecraft:stone_depth", "offset": 0, "add_surface_depth": True, "secondary_depth_range": 0, "surface_type": "floor"}
DEEP_UNDER_FLOOR = {"type": "minecraft:stone_depth", "offset": 0, "add_surface_depth": True, "secondary_depth_range": 6, "surface_type": "floor"}
# True when the column is above the local water surface (i.e. dry land).
ABOVE_WATER = {"type": "minecraft:water", "offset": -1, "surface_depth_multiplier": 0, "add_stone_depth": False}
NOT_UNDERWATER = {"type": "minecraft:water", "offset": 0, "surface_depth_multiplier": 0, "add_stone_depth": False}
STEEP = {"type": "minecraft:steep"}

GRASS = block("minecraft:grass_block", {"snowy": "false"})
DIRT = block("minecraft:dirt")
COARSE = block("minecraft:coarse_dirt")
SAND = block("minecraft:sand")
SANDSTONE = block("minecraft:sandstone")
GRAVEL = block("minecraft:gravel")
STONE = block("minecraft:stone")
MUD = block("minecraft:mud")
CLAY = block("minecraft:clay")
SNOW = block("minecraft:snow_block")
CALCITE = block("minecraft:calcite")
PODZOL = block("minecraft:podzol", {"snowy": "false"})
MYCELIUM = block("minecraft:mycelium", {"snowy": "false"})
RED_SAND = block("minecraft:red_sand")
# Vanilla's striped terracotta rule, the same one badlands use.
BANDLANDS = {"type": "minecraft:bandlands"}
BASALT = block("minecraft:basalt", {"axis": "y"})
BLACKSTONE = block("minecraft:blackstone")
MAGMA = block("minecraft:magma_block")
PACKED_ICE = block("minecraft:packed_ice")

# Above this Y, peaks get a snow cap. Below it they show bare rock. The noise
# "surface" breaks the line so the snow edge is ragged instead of a flat ring.
PEAK_SNOW_LINE = 150


def surface_rule(vanilla_rule):
    bedrock = vanilla_rule["sequence"][0]
    deepslate = vanilla_rule["sequence"][2]

    floor = seq(
        cond(biome_is("dunes", "shore"), SAND),
        cond(biome_is("badlands"), seq(
            # Red sand only on gentle, low ground; slopes show the terracotta bands.
            cond(STEEP, BANDLANDS),
            cond(noise_between("minecraft:surface", -0.1, 0.4), RED_SAND),
            BANDLANDS,
        )),
        cond(biome_is("volcanic"), seq(
            cond(noise_between("minecraft:surface", 0.45, 1.0), MAGMA),
            cond(noise_between("minecraft:gravel", -0.1, 1.0), BASALT),
            BLACKSTONE,
        )),
        cond(biome_is("glacier"), seq(
            cond(noise_between("minecraft:surface", -0.15, 0.25), PACKED_ICE),
            SNOW,
        )),
        cond(biome_is("mushroom_grove"), seq(
            cond(ABOVE_WATER, MYCELIUM),
            DIRT,
        )),
        cond(biome_is("peaks"), seq(
            cond(y_above(PEAK_SNOW_LINE, 2), SNOW),
            cond(noise_between("minecraft:surface", 0.0, 1.0), CALCITE),
            STONE,
        )),
        cond(biome_is("rocky_hills"), seq(
            cond(noise_between("minecraft:gravel", -0.2, 1.0), GRAVEL),
            cond(STEEP, STONE),
            cond(ABOVE_WATER, GRASS),
            STONE,
        )),
        cond(biome_is("marsh"), seq(
            cond(noise_between("minecraft:surface_swamp", 0.0, 1.0), MUD),
            cond(ABOVE_WATER, GRASS),
            MUD,
        )),
        cond(biome_is("savanna"), seq(
            cond(noise_between("minecraft:surface", 0.2, 1.0), COARSE),
            cond(ABOVE_WATER, GRASS),
            DIRT,
        )),
        cond(biome_is("deep_forest", "taiga"), seq(
            cond(noise_between("minecraft:surface", 0.35, 1.0), PODZOL),
            cond(ABOVE_WATER, GRASS),
            DIRT,
        )),
        cond(biome_is("lake"), seq(
            cond(noise_between("minecraft:surface", -0.1, 0.2), CLAY),
            cond(ABOVE_WATER, SAND),
            GRAVEL,
        )),
        cond(biome_is("river"), seq(
            cond(ABOVE_WATER, GRASS),
            cond(noise_between("minecraft:surface", 0.0, 1.0), SAND),
            GRAVEL,
        )),
        cond(STEEP, STONE),
        cond(ABOVE_WATER, GRASS),
        DIRT,
    )

    under_floor = seq(
        cond(biome_is("dunes", "shore"), seq(cond(DEEP_UNDER_FLOOR, SANDSTONE), SAND)),
        cond(biome_is("badlands"), BANDLANDS),
        cond(biome_is("volcanic"), BLACKSTONE),
        cond(biome_is("glacier"), seq(cond(DEEP_UNDER_FLOOR, PACKED_ICE), SNOW)),
        cond(biome_is("peaks", "rocky_hills"), STONE),
        cond(biome_is("marsh"), MUD),
        cond(biome_is("lake"), GRAVEL),
        DIRT,
    )

    return seq(
        bedrock,
        cond({"type": "minecraft:above_preliminary_surface"}, seq(
            cond(ON_FLOOR, floor),
            cond(UNDER_FLOOR, under_floor),
        )),
        deepslate,
    )


# ---------------------------------------------------------------------------
# Cobblemon ruins and towers in the Safari
# ---------------------------------------------------------------------------
# Cobblemon's own structures (Gimmighoul towers and ruins) are restricted to
# overworld biome tags, and their structure sets are spaced for a whole
# overworld: on a 900-block island they would rarely appear. The Safari gets
# its own structures reusing Cobblemon's template pools (the buildings are
# Cobblemon's, nothing is copied), each variant limited to the Safari
# habitats it suits, in two structure sets spaced so that every map has
# several candidate spots: at least one tower and one ruin in practice.
#
# Each variant: Cobblemon start pool, Safari habitats, and the jigsaw
# settings copied from the matching Cobblemon structure (start height,
# terrain adaptation...) so the building sits in the ground as it does in
# the overworld. If Cobblemon renames a pool, the world refuses to load
# with an unbound-value error naming it: update the pool here.
SAFARI_STRUCTURE_SETS = {
    # spacing/separation in chunks. Towers: one candidate per 320 blocks,
    # about seven on the island. Ruins: one per 224 blocks, about fifteen.
    # Larger spacing means fewer structures and a real chance of none.
    # dry: ground check of DryJigsawStructure around the structure's centre,
    # radius and relief in blocks. Towers are tall and narrow-based; ruins
    # spread wider but bear more relief, their beard filling the gaps.
    "towers": {"spacing": 20, "separation": 8, "salt": 1650283461,
               "dry": {"footprint_radius": 8, "max_relief": 4}},
    "ruins": {"spacing": 14, "separation": 6, "salt": 1729384751,
              "dry": {"footprint_radius": 10, "max_relief": 6}},
}
SURFACE_JIGSAW = {"step": "surface_structures", "start_height": {"absolute": 0}, "size": 7,
                  "terrain_adaptation": "beard_thin"}
SAFARI_STRUCTURES = {
    # Gimmighoul towers, one look per climate.
    "tower_temperate": ("towers", "cobblemon:ruins/temperate_gimmi_tower",
                        ["grassland", "savanna", "woodland", "flower_meadow", "taiga", "deep_forest", "rocky_hills"],
                        SURFACE_JIGSAW),
    "tower_lush": ("towers", "cobblemon:ruins/lush_gimmi_tower", ["jungle", "mushroom_grove"],
                   dict(SURFACE_JIGSAW, start_height={"absolute": -1})),
    "tower_rooted": ("towers", "cobblemon:ruins/rooted_gimmi_tower", ["marsh"],
                     dict(SURFACE_JIGSAW, terrain_adaptation="beard_box")),
    "tower_deserted": ("towers", "cobblemon:ruins/deserted_gimmi_tower", ["dunes"], SURFACE_JIGSAW),
    "tower_sunscorched": ("towers", "cobblemon:ruins/sunscorched_gimmi_tower", ["badlands", "volcanic"],
                          SURFACE_JIGSAW),
    "tower_frozen": ("towers", "cobblemon:ruins/frozen_gimmi_tower", ["snowfield", "glacier"], SURFACE_JIGSAW),
    # Ruins standing out of the ground (the buried ones would go unseen).
    "ruin_stone_henge": ("ruins", "cobblemon:ruins/stonjourner_henge_ruins", ["grassland", "rocky_hills"],
                         dict(SURFACE_JIGSAW, step="underground_structures", start_height={"absolute": -1})),
    "ruin_moon_henge": ("ruins", "cobblemon:ruins/lunatone_henge_ruins", ["savanna"],
                        dict(SURFACE_JIGSAW, step="underground_structures", start_height={"absolute": -1})),
    "ruin_sun_henge": ("ruins", "cobblemon:ruins/solrock_henge_ruins", ["taiga", "deep_forest"],
                       dict(SURFACE_JIGSAW, step="underground_structures", start_height={"absolute": -1})),
    "ruin_toppled_pillars": ("ruins", "cobblemon:ruins/toppled_pillars_ruins",
                             ["woodland", "flower_meadow", "marsh"],
                             dict(SURFACE_JIGSAW, step="underground_structures", start_height={"absolute": -1},
                                  terrain_adaptation="beard_box")),
    "ruin_shaded": ("ruins", "cobblemon:ruins/sunscorched_shaded_ruins", ["badlands", "volcanic"],
                    dict(SURFACE_JIGSAW, step="underground_structures")),
    "ruin_town_center": ("ruins", "cobblemon:ruins/deserted_town_center_ruins", ["dunes"],
                         dict(SURFACE_JIGSAW, step="underground_structures", start_height={"absolute": -1},
                              terrain_adaptation="beard_box")),
    "ruin_frozen_altar": ("ruins", "cobblemon:ruins/frozen_altar_ruins", ["snowfield", "glacier"],
                          dict(SURFACE_JIGSAW, step="underground_structures", terrain_adaptation="beard_box")),
    "ruin_overgrown_trial": ("ruins", "cobblemon:ruins/overgrown_trial_ruins", ["jungle", "mushroom_grove"],
                             dict(SURFACE_JIGSAW, step="underground_structures")),
}
# Folders owned by write_safari_structures, rebuilt from scratch on each run.
SAFARI_STRUCTURE_DIR = "safari_ruins"
SAFARI_STRUCTURE_TAG_DIR = "safari_structures"


def write_safari_structures(written):
    """Writes the Safari's ruin and tower structures, their biome tags and the two structure sets."""
    for name, (set_name, pool, habitats, settings) in SAFARI_STRUCTURES.items():
        unknown = set(habitats) - set(BIOMES)
        if unknown:
            sys.exit(f"Structure {name} names unknown habitats {sorted(unknown)}")
        if set_name not in SAFARI_STRUCTURE_SETS:
            sys.exit(f"Structure {name} names unknown structure set {set_name}")
        tag = f"{SAFARI_STRUCTURE_TAG_DIR}/{name}"
        write(f"tags/worldgen/biome/{tag}.json",
              {"replace": False, "values": [f"{MOD_ID}:{h}" for h in habitats]}, written)
        jigsaw = {
            "type": "minecraft:jigsaw",
            "biomes": f"#{MOD_ID}:{tag}",
            "spawn_overrides": {},
            "start_pool": pool,
            "size": settings["size"],
            "step": settings["step"],
            "start_height": settings["start_height"],
            "project_start_to_heightmap": "WORLD_SURFACE_WG",
            "max_distance_from_center": 100,
            "terrain_adaptation": settings["terrain_adaptation"],
            "use_expansion_hack": False,
        }
        # Wrapped in the mod's dry_jigsaw type (DryJigsawStructure): a land
        # biome still has ponds and flooded hollows, and a structure projected
        # onto water would float in them.
        dry = SAFARI_STRUCTURE_SETS[set_name]["dry"]
        write(f"worldgen/structure/{SAFARI_STRUCTURE_DIR}/{name}.json", {
            "type": f"{MOD_ID}:dry_jigsaw",
            "biomes": jigsaw["biomes"],
            "spawn_overrides": {},
            "step": jigsaw["step"],
            "terrain_adaptation": jigsaw["terrain_adaptation"],
            "footprint_radius": dry["footprint_radius"],
            "max_relief": dry["max_relief"],
            "jigsaw": jigsaw,
        }, written)
    for set_name, placement in SAFARI_STRUCTURE_SETS.items():
        members = [n for n, v in SAFARI_STRUCTURES.items() if v[0] == set_name]
        # Several variants per set: at a candidate spot, Minecraft tries them in
        # turn until one suits the biome there.
        write(f"worldgen/structure_set/{SAFARI_STRUCTURE_DIR}/{set_name}.json", {
            "structures": [{"structure": f"{MOD_ID}:{SAFARI_STRUCTURE_DIR}/{m}", "weight": 1} for m in members],
            "placement": {
                "type": "minecraft:random_spread",
                "spacing": placement["spacing"],
                "separation": placement["separation"],
                "salt": placement["salt"],
            },
        }, written)


# ---------------------------------------------------------------------------
# Validation
# ---------------------------------------------------------------------------

# Keys whose string values are free-form seeds or labels, not registry ids.
NON_REGISTRY_KEYS = {"random_name", "mode", "sound_id"}


def collect_refs(node, out):
    if isinstance(node, dict):
        for k, v in node.items():
            if k not in NON_REGISTRY_KEYS:
                collect_refs(v, out)
    elif isinstance(node, list):
        for v in node:
            collect_refs(v, out)
    elif isinstance(node, str) and ":" in node and " " not in node:
        out.add(node)


def validate(files):
    known = set()
    for reg in ["worldgen/placed_feature", "worldgen/configured_feature", "worldgen/density_function",
                "worldgen/noise", "worldgen/configured_carver", "block", "sound_event",
                "worldgen/density_function_type", "worldgen/material_rule", "worldgen/material_condition",
                "worldgen/biome_source", "worldgen/chunk_generator", "worldgen/biome",
                "int_provider_type", "dimension_type", "worldgen/carver",
                "worldgen/structure_placement", "worldgen/structure_type"]:
        known |= {f"minecraft:{x}" for x in registry(reg)}
    for path in files:
        parts = os.path.relpath(path, OUT).split(os.sep)
        if parts[0] == "tags":
            continue
        name_parts = parts[2:] if parts[0] == "worldgen" else parts[1:]
        known.add(f"{MOD_ID}:" + os.path.splitext("/".join(name_parts))[0])
    known.add(f"{MOD_ID}:safari")
    known |= CUSTOM_DENSITY_FUNCTION_TYPES
    known |= CUSTOM_STRUCTURE_TYPES
    missing = set()
    for path in files:
        refs = set()
        with open(path, encoding="utf-8") as f:
            collect_refs(json.load(f), refs)
        for r in refs:
            if r.startswith("#"):
                continue
            if r.startswith("minecraft:") or r.startswith(f"{MOD_ID}:"):
                if r not in known:
                    missing.add((os.path.relpath(path, ROOT), r))
    if missing:
        for p, r in sorted(missing):
            print(f"  UNRESOLVED {r} in {p}")
        sys.exit("Validation failed: unresolved references")
    print(f"  validated {len(files)} files, all references resolve")


# ---------------------------------------------------------------------------

def write(rel, data, written):
    path = os.path.join(OUT, rel)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        json.dump(data, f, indent=2)
        f.write("\n")
    written.append(path)


def write_cobblemon_bridge():
    """Writes one file per Cobblemon tag, listing the Safari biomes bridged into it."""
    missing = set(COBBLEMON_TAG_BRIDGE) ^ set(BIOMES)
    if missing:
        sys.exit(f"COBBLEMON_TAG_BRIDGE and BIOMES disagree on: {sorted(missing)}")
    by_tag = {}
    for biome, tags in COBBLEMON_TAG_BRIDGE.items():
        for tag in tags:
            if tag not in COBBLEMON_KNOWN_BIOME_TAGS:
                sys.exit(f"Unknown Cobblemon biome tag '{tag}' bridged from '{biome}'")
            by_tag.setdefault(tag, []).append(f"{MOD_ID}:{biome}")
    # The directory holds nothing but bridge files, so it can be rebuilt from scratch.
    shutil.rmtree(COBBLEMON_TAG_OUT, ignore_errors=True)
    os.makedirs(COBBLEMON_TAG_OUT)
    for tag, values in sorted(by_tag.items()):
        with open(os.path.join(COBBLEMON_TAG_OUT, f"{tag}.json"), "w", encoding="utf-8", newline="\n") as f:
            json.dump({"replace": False, "values": sorted(values)}, f, indent=2)
            f.write("\n")
    return len(by_tag)


def main():
    # Only the folders this script owns. worldgen/structure*, worldgen/template_pool
    # and tags/worldgen/biome/has_structure hold the Safari Gate, written by
    # hand and by safari_gate_assets.py: wiping all of worldgen deleted them.
    for sub in [os.path.join("worldgen", d) for d in ("biome", "density_function", "noise", "noise_settings")] \
            + [os.path.join("worldgen", "structure", SAFARI_STRUCTURE_DIR),
               os.path.join("worldgen", "structure_set", SAFARI_STRUCTURE_DIR),
               os.path.join("tags", "worldgen", "biome", SAFARI_STRUCTURE_TAG_DIR)] \
            + ["dimension", "dimension_type"]:
        shutil.rmtree(os.path.join(OUT, sub), ignore_errors=True)
    # Biome tags: only the files directly in the folder, never its subfolders.
    tag_dir = os.path.join(OUT, "tags", "worldgen", "biome")
    if os.path.isdir(tag_dir):
        for name in os.listdir(tag_dir):
            path = os.path.join(tag_dir, name)
            if os.path.isfile(path):
                os.remove(path)
    written = []

    for noise, octave in NOISE_FIRST_OCTAVE.items():
        data = dict(vanilla(f"worldgen/noise/{noise}"))
        data["firstOctave"] = octave
        write(f"worldgen/noise/{noise}.json", data, written)

    for df in CLIMATE_DENSITY_FUNCTIONS:
        if df == "continents":
            data = continents_df()
        elif df == "erosion":
            data = erosion_df()
        else:
            data = rename_refs(vanilla(f"worldgen/density_function/overworld/{df}"))
        write(f"worldgen/density_function/safari/{df}.json", data, written)

    settings = rename_refs(vanilla("worldgen/noise_settings/overworld"))
    settings["noise_router"]["temperature"] = temperature_df()
    settings["noise_router"]["vegetation"] = humidity_df()
    settings["surface_rule"] = surface_rule(vanilla("worldgen/noise_settings/overworld")["surface_rule"])
    write("worldgen/noise_settings/safari.json", settings, written)

    orders = global_feature_order()
    for name, spec in BIOMES.items():
        write(f"worldgen/biome/{name}.json", build_biome(name, spec, orders), written)

    dim_type = dict(vanilla("dimension_type/overworld"))
    # No raids: an Illager raid in a timed Pokemon park is pure noise.
    dim_type["has_raids"] = False
    # Custom client effects: warmer sunrise, lower clouds. Clients without the
    # mod fall back to overworld effects, which is harmless.
    dim_type["effects"] = DIMENSION_EFFECTS
    write("dimension_type/safari.json", dim_type, written)

    write("dimension/safari.json", {
        "type": f"{MOD_ID}:safari",
        "generator": {
            "type": "minecraft:noise",
            "settings": f"{MOD_ID}:safari",
            "biome_source": {"type": "minecraft:multi_noise", "biomes": climate_points()},
        },
    }, written)

    for tag, members in TAGS.items():
        write(f"tags/worldgen/biome/{tag}.json",
              {"replace": False, "values": [f"{MOD_ID}:{m}" for m in members]}, written)

    write_safari_structures(written)

    validate(written)
    bridge_count = write_cobblemon_bridge()
    sound_count = write_sounds()
    print(f"Generated {sound_count} sound events (ambience and music) in {os.path.relpath(SOUNDS_OUT, ROOT)}")
    print(f"Generated {len(written)} files under {os.path.relpath(OUT, ROOT)}")
    print(f"Generated {bridge_count} Cobblemon bridge tags under {os.path.relpath(COBBLEMON_TAG_OUT, ROOT)}")
    write_park_geometry()
    print(f"Generated {os.path.relpath(PARK_GEOMETRY_JAVA, ROOT)}")


def write_park_geometry():
    """
    Writes park/ParkGeometry.java: the island centre, the world border, the
    arrival point and the rest house anchors the mod uses, derived from the
    terrain constants above.
    They used to be server config values that had to be copied by hand from
    this script's output; a server that changed one alone moved the border
    or the arrival off the island.
    """
    border = round(ISLAND_RADIUS * (1 + BORDER_MARGIN))
    arrival_z = ISLAND_CENTER_Z - round(ISLAND_RADIUS * ARRIVAL_NORTH)
    ring = round(ISLAND_RADIUS * REST_HOUSE_RING)
    houses = [
        (ISLAND_CENTER_X, arrival_z - ENTRANCE_HOUSE_NORTH_OF_ARRIVAL),
        (ISLAND_CENTER_X + ring, ISLAND_CENTER_Z),
        (ISLAND_CENTER_X, ISLAND_CENTER_Z + ring),
        (ISLAND_CENTER_X - ring, ISLAND_CENTER_Z),
    ]
    house_lines = ",\n".join(f"            new Column({x}, {z})" for x, z in houses)
    source = f"""package dev.darcosse.common.cobblemonsafari.park;

import java.util.List;

/**
 * Where the Safari island lies, its world border, its arrival point and its
 * rest houses, in blocks. GENERATED by tools/worldgen/generate_worldgen.py from the terrain
 * it generates: edit the constants there and run it, never this file, or
 * the border and the arrival no longer match the island.
 */
public final class ParkGeometry {{

    /** Centre of the island and of the world border. */
    public static final int CENTER_X = {ISLAND_CENTER_X};
    public static final int CENTER_Z = {ISLAND_CENTER_Z};

    /** Half the side of the square world border: the island plus a strip of lagoon. */
    public static final int BORDER_HALF_SIZE = {border};

    /** Arrival point: in the grassland north of the centre, like the entrance of the games' Safari Zones. */
    public static final int ARRIVAL_X = {ISLAND_CENTER_X};
    public static final int ARRIVAL_Z = {arrival_z};

    /** Arrivals are spread this far around the arrival point, so a group does not land on one block. */
    public static final int ARRIVAL_SPREAD = {ARRIVAL_SPREAD};

    /**
     * Anchors of the rest houses, entrance first (it holds the Safari Board):
     * each house is built at the best spot near its anchor. The anchor also
     * identifies the house in the saved world state, so changing one builds
     * a new house there on the next map.
     */
    public static final List<Column> REST_HOUSES = List.of(
{house_lines}
    );

    /** A column of the park, x and z in blocks. */
    public record Column(int x, int z) {{
    }}

    private ParkGeometry() {{
    }}
}}
"""
    with open(PARK_GEOMETRY_JAVA, "w", encoding="utf-8", newline="\n") as f:
        f.write(source)


if __name__ == "__main__":
    main()
