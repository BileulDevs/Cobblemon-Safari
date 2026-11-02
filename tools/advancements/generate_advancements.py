#!/usr/bin/env python3
"""
Generates the Safari advancement tab and its translations.

Writes:
  * common/src/main/resources/data/cobblemon_safari/advancement/safari/**.json
    (the folder is wiped first: an advancement removed here disappears);
  * the advancement keys (advancements.cobblemon_safari.*) and the
    stat.cobblemon_safari.pokemon_fished key into every language file of
    assets/cobblemon_safari/lang. Other keys are left untouched, in place.

Why a generator: 48 advancements in 12 languages are 1,150 strings and
48 JSON files whose criteria repeat the same few shapes. One table keeps
the tree, the triggers and every translation side by side, and a missing
translation fails the run instead of showing a raw key in game.

Usage (from the repository root):
    python3 tools/advancements/generate_advancements.py

Most criteria use the mod's trigger cobblemon_safari:safari_event
(SafariTrigger); its event ids are the constants of SafariAdvancements and
the paths of the SafariStatistics ids. Renaming one on either side breaks
the advancements that listen to it.
"""

import json
import os
import shutil
import sys

MOD_ID = "cobblemon_safari"
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
RESOURCES = os.path.join(ROOT, "common", "src", "main", "resources")
ADVANCEMENT_DIR = os.path.join(RESOURCES, "data", MOD_ID, "advancement", "safari")
LANG_DIR = os.path.join(RESOURCES, "assets", MOD_ID, "lang")

TRIGGER = MOD_ID + ":safari_event"
DIMENSION = MOD_ID + ":safari"
# The tab's backdrop, tiled behind the tree. Any 16x16 block texture works.
BACKGROUND = "minecraft:textures/block/moss_block.png"

LANGUAGES = ["en_us", "fr_fr", "de_de", "es_es", "es_mx", "it_it", "ja_jp", "ko_kr", "pl_pl", "pt_br",
             "ru_ru", "zh_cn"]

# The fourteen habitats of the island tour; peaks, volcano, water and caves are not habitats.
HABITAT_BIOMES = ["grassland", "savanna", "dunes", "badlands", "jungle", "marsh", "mushroom_grove",
                  "flower_meadow", "woodland", "deep_forest", "taiga", "snowfield", "glacier", "rocky_hills"]

# The small ruins allowed in the Safari (see the allowed_in_safari structure tag).
RUIN_STRUCTURES = ["ruin_stone_henge", "ruin_moon_henge", "ruin_sun_henge", "ruin_toppled_pillars",
                   "ruin_shaded", "ruin_overgrown_trial"]

# The Cobblemon habitats allowed in the Safari (see the allowed_in_safari structure tag).
HABITAT_STRUCTURES = ["habitat_berry_patch", "habitat_freshwater_pond", "habitat_sunflowerbed_clearing",
                      "habitat_flowerbed_clearing", "habitat_pinkflowerbed_clearing", "habitat_sandpit_clearing",
                      "habitat_oak_wildfire_scar", "habitat_birch_wildfire_scar", "habitat_parched_peat_bog",
                      "habitat_bug_mound", "habitat_fae_mounds", "habitat_carved_ice_spikes",
                      "habitat_badlands_shaded_rock", "habitat_meteorite_impact", "habitat_lush_canopy",
                      "habitat_lush_peat_bog", "habitat_zen_garden", "habitat_thermal_crevices"]


# ------------------------------------------------------------------ criteria

def event(name, minimum=None, where=None):
    """A SafariTrigger criterion: an event, optionally a minimum value and a location of the player."""
    conditions = {"event": name}
    if minimum is not None:
        conditions["value"] = {"min": minimum}
    if where is not None:
        conditions["player"] = player_at(where)
    return {"trigger": TRIGGER, "conditions": conditions}


def player_at(location):
    """A player predicate (list form) requiring the player to stand somewhere."""
    return [{"condition": "minecraft:entity_properties", "entity": "this", "predicate": {"location": location}}]


def located(location):
    """Vanilla location criterion: checked about once a second for every online player."""
    return {"trigger": "minecraft:location", "conditions": {"player": player_at(location)}}


def in_safari(location=None):
    """A location inside the Safari dimension, narrowed by the given fields."""
    result = {"dimension": DIMENSION}
    result.update(location or {})
    return result


def placed(block):
    """Vanilla criterion: the player placed the block inside the Safari."""
    return {"trigger": "minecraft:placed_block", "conditions": {"location": [
        {"condition": "minecraft:location_check", "predicate": {"dimension": DIMENSION}},
        {"condition": "minecraft:block_state_property", "block": block},
    ]}}


def holding(item):
    """Vanilla criterion: the item (or #tag) entered the inventory while inside the Safari."""
    return {"trigger": "minecraft:inventory_changed", "conditions": {
        "player": player_at(in_safari()),
        "items": [{"items": item}],
    }}


def ruins(ids):
    """Structure ids of the mod's safari_ruins folder."""
    return [MOD_ID + ":safari_ruins/" + path for path in ids]


# ------------------------------------------------------------------ the tree
#
# name: (parent, icon, frame, hidden, criteria, requirements)
# requirements None means "every criterion" (vanilla's default); a list of
# lists is vanilla's AND of ORs.

ADVANCEMENTS = {
    "root": (None, "cobblemon:safari_ball", "task", False, {
        "has_ticket": {"trigger": "minecraft:inventory_changed",
                       "conditions": {"items": [{"items": MOD_ID + ":safari_ticket"}]}},
        "entered": {"trigger": "minecraft:changed_dimension", "conditions": {"to": DIMENSION}},
    }, [["has_ticket", "entered"]]),

    # Discovery
    "enter": ("root", "minecraft:short_grass", "task", False, {
        "entered": {"trigger": "minecraft:changed_dimension", "conditions": {"to": DIMENSION}},
    }, None),
    "island_tour": ("enter", "minecraft:filled_map", "challenge", False, {
        biome: located(in_safari({"biomes": MOD_ID + ":" + biome})) for biome in HABITAT_BIOMES
    }, None),
    "caverns": ("enter", "minecraft:glow_berries", "task", False, {
        "deep_cavern": located(in_safari({"biomes": MOD_ID + ":deep_cavern"})),
        "lush_grotto": located(in_safari({"biomes": MOD_ID + ":lush_grotto"})),
    }, [["deep_cavern", "lush_grotto"]]),
    "summit": ("enter", "minecraft:magma_block", "task", False, {
        "peaks": located(in_safari({"biomes": MOD_ID + ":peaks"})),
        "volcanic": located(in_safari({"biomes": MOD_ID + ":volcanic"})),
    }, [["peaks", "volcanic"]]),
    "archaeologist": ("enter", "minecraft:brush", "task", False, {
        "ruin": located(in_safari({"structures": ruins(RUIN_STRUCTURES)})),
        "fossil": holding("#cobblemon:fossils"),
    }, [["ruin", "fossil"]]),
    "habitat": ("enter", "minecraft:sweet_berries", "task", False, {
        "habitat": located(in_safari({"structures": ruins(HABITAT_STRUCTURES)})),
    }, None),
    "first_trip": ("enter", MOD_ID + ":safari_escape_rope", "task", False, {
        "trip": event("rest_house_trips", 1),
    }, None),
    "frequent_traveller": ("first_trip", "minecraft:feather", "goal", False, {
        "trips": event("rest_house_trips", 25),
    }, None),

    # Hunting
    "first_catch": ("enter", "cobblemon:poke_ball", "task", False, {"catch": event("catch")}, None),
    "old_school": ("first_catch", "cobblemon:safari_ball", "task", False, {
        "catch": event("catch_safari_ball_out_of_battle"),
    }, None),
    "special_of_the_day": ("first_catch", "cobblemon:premier_ball", "goal", False, {
        "catch": event("catch_special"),
    }, None),
    "ultra_rare": ("special_of_the_day", "cobblemon:dream_ball", "challenge", False, {
        "catch": event("catch_ultra_rare_special"),
    }, None),
    "wild_shine": ("first_catch", "cobblemon:shiny_stone", "challenge", False, {
        "catch": event("catch_shiny"),
    }, None),
    "night_owl": ("first_catch", "cobblemon:dusk_ball", "task", False, {"catch": event("catch_at_night")}, None),
    "good_haul": ("first_catch", "cobblemon:great_ball", "goal", False, {"catches": event("hunt_catches", 10)}, None),
    "safari_express": ("good_haul", "cobblemon:quick_ball", "challenge", False, {
        "catches": event("hunt_catches", 30),
    }, None),
    "encyclopedist": ("first_catch", "cobblemon:pokedex_red", "challenge", False, {
        "species": event("species_caught", 50),
    }, None),
    "no_half_measures": ("first_catch", "cobblemon:master_ball", "task", True, {
        "catch": event("catch_master_ball"),
    }, None),
    "park_legend": ("first_catch", "minecraft:nether_star", "challenge", True, {
        "catch": event("catch_legendary"),
    }, None),

    # Bait
    "snack_time": ("enter", "cobblemon:poke_snack", "task", False, {"placed": placed("cobblemon:poke_snack")}, None),
    "dinner_is_served": ("snack_time", "cobblemon:oran_berry", "goal", False, {
        "catch": event("catch_snack_visitor"),
    }, None),
    "layer_cake": ("snack_time", "cobblemon:poke_cake", "task", False, {"placed": placed("cobblemon:poke_cake")}, None),

    # The clock
    "piggy_bank": ("root", MOD_ID + ":safari_ticket", "goal", False, {"full": event("bank_full")}, None),
    "to_the_end": ("enter", "minecraft:clock", "goal", False, {"hunt": event("full_hunt")}, None),
    "photo_finish": ("to_the_end", "cobblemon:timer_ball", "challenge", True, {
        "catch": event("catch_last_seconds"),
    }, None),
    "nothing_wasted": ("enter", "minecraft:hopper", "task", False, {"refund": event("refund")}, None),

    # Missions, points, shop
    "reporting_in": ("enter", "minecraft:writable_book", "task", False, {
        "mission": event("missions_completed", 1),
    }, None),
    "clean_sweep": ("reporting_in", "minecraft:written_book", "goal", False, {"all": event("all_missions")}, None),
    "peaceful_hunter": ("reporting_in", "minecraft:poppy", "goal", False, {
        "mission": event("mission_out_of_battle"),
    }, None),
    "wild_card": ("reporting_in", "minecraft:paper", "task", False, {"swap": event("mission_swap")}, None),
    "nest_egg": ("enter", "minecraft:gold_nugget", "goal", False, {"points": event("points_earned", 1000)}, None),
    "safari_fortune": ("nest_egg", "minecraft:gold_block", "challenge", False, {
        "points": event("points_earned", 25000),
    }, None),
    "first_purchase": ("enter", "minecraft:emerald", "task", False, {"purchase": event("shop_purchase")}, None),
    "fair_trade": ("enter", "cobblemon:red_apricorn", "task", False, {"trade": event("shop_trade")}, None),

    # Loyalty
    "regular": ("enter", "minecraft:compass", "goal", False, {"hunts": event("hunts", 10)}, None),
    "veteran": ("regular", "minecraft:recovery_compass", "challenge", False, {"hunts": event("hunts", 50)}, None),
    "new_deal": ("regular", "minecraft:map", "goal", False, {"maps": event("maps_hunted", 7)}, None),
    "hands_off": ("root", "minecraft:wooden_sword", "task", True, {"hit": event("gate_resident_hit")}, None),

    # Fishing
    "fishing/first_bite": ("enter", "cobblemon:poke_rod", "task", False, {"fished": event("fished")}, None),
    "fishing/hook_line_and_ball": ("fishing/first_bite", "cobblemon:safari_rod", "task", False, {
        "catch": event("catch_fished"),
    }, None),
    "fishing/angler": ("fishing/first_bite", "cobblemon:great_rod", "goal", False, {
        "fished": event("pokemon_fished", 10),
    }, None),
    "fishing/master_angler": ("fishing/angler", "cobblemon:master_rod", "challenge", False, {
        "fished": event("pokemon_fished", 50),
    }, None),
    "fishing/tasty_bait": ("fishing/first_bite", "cobblemon:poke_bait", "task", False, {
        "fished": event("fished_with_bait"),
    }, None),
    "fishing/glint_below": ("fishing/first_bite", "minecraft:glow_ink_sac", "challenge", True, {
        "fished": event("fished_shiny"),
    }, None),
    "fishing/the_big_one": ("fishing/first_bite", "cobblemon:dive_ball", "challenge", False, {
        "fished": event("fished_special"),
    }, None),
    "fishing/underground_angler": ("fishing/first_bite", "minecraft:glow_lichen", "goal", False, {
        "fished": event("fished", where=in_safari({"biomes": "#" + MOD_ID + ":safari_cave"})),
    }, None),
    "fishing/all_aboard": ("fishing/first_bite", "minecraft:oak_boat", "task", False, {
        "fished": event("fished", where=in_safari({"structures": "#cobblemon:fishing_boat"})),
    }, None),
}

# ------------------------------------------------------------- translations
#
# language: {name: (title, description)}. Every language must cover every
# advancement; the run fails otherwise.

FISHED_STAT = {
    "en_us": "Pokémon Fished in the Safari",
    "fr_fr": "Pokémon pêchés au Safari",
    "de_de": "In der Safari geangelte Pokémon",
    "es_es": "Pokémon pescados en el Safari",
    "es_mx": "Pokémon pescados en el Safari",
    "it_it": "Pokémon pescati nel Safari",
    "ja_jp": "サファリで釣ったポケモン",
    "ko_kr": "사파리에서 낚은 포켓몬",
    "pl_pl": "Pokémony wyłowione w Safari",
    "pt_br": "Pokémon pescados no Safári",
    "ru_ru": "Покемонов выловлено в Сафари",
    "zh_cn": "在狩猎地带钓到的宝可梦",
}

TEXT = {
    "en_us": {
        "root": ("Safari Zone", "Get a Safari Ticket or step into the Safari"),
        "enter": ("Into the Tall Grass", "Enter the Safari for the first time"),
        "island_tour": ("Island Tour", "Visit all fourteen habitats of the Safari"),
        "caverns": ("Below the Surface", "Reach one of the Safari's two great caverns"),
        "summit": ("Top of the Park", "Reach the Safari's peaks or its volcano"),
        "archaeologist": ("Archaeologist", "Find a henge or a ruin in the Safari, or dig up a fossil there"),
        "habitat": ("Natural Habitat", "Find one of the small Pokémon habitats of the Safari"),
        "first_trip": ("Connecting Trip", "Travel to a rest house with the Safari Guide or an Escape Rope"),
        "frequent_traveller": ("Frequent Traveller", "Make 25 trips to the Safari's rest houses"),
        "first_catch": ("First Catch", "Catch a Pokémon in the Safari"),
        "old_school": ("The Old-Fashioned Way", "Catch a Pokémon outside of battle with a Safari Ball"),
        "special_of_the_day": ("Special of the Day", "Catch one of today's special Pokémon"),
        "ultra_rare": ("Once in a Blue Moon", "Catch an ultra-rare special Pokémon"),
        "wild_shine": ("Wild Shine", "Catch a shiny Pokémon in the Safari"),
        "night_owl": ("Night Owl", "Catch a Pokémon in the Safari at night"),
        "good_haul": ("Good Haul", "Catch 10 Pokémon in a single hunt"),
        "safari_express": ("Safari Express", "Catch 30 Pokémon in a single hunt"),
        "encyclopedist": ("Encyclopedist", "Catch 50 different species in the Safari"),
        "no_half_measures": ("No Half Measures", "Catch a Pokémon with a Master Ball in the Safari"),
        "park_legend": ("Legend of the Park", "Catch a legendary or mythical Pokémon in the Safari"),
        "snack_time": ("Snack Time", "Place a Poké Snack in the Safari"),
        "dinner_is_served": ("Dinner Is Served", "Catch a Pokémon that came to eat a Poké Snack"),
        "layer_cake": ("Layer Cake", "Place a Poké Cake in the Safari"),
        "piggy_bank": ("Piggy Bank", "Fill your Safari time bank to the brim"),
        "to_the_end": ("To the Very End", "Hunt for the longest time allowed, down to the last second"),
        "photo_finish": ("Photo Finish", "Catch a Pokémon in the last 10 seconds of a hunt"),
        "nothing_wasted": ("Nothing Wasted", "Leave early through a clerk and get your unused time back"),
        "reporting_in": ("Reporting In", "Complete a Safari mission"),
        "clean_sweep": ("Clean Sweep", "Complete every mission of a rotation"),
        "peaceful_hunter": ("Peaceful Hunter", "Complete an out-of-battle mission"),
        "wild_card": ("Wild Card", "Have a clerk swap one of your missions"),
        "nest_egg": ("Nest Egg", "Earn 1,000 Safari points"),
        "safari_fortune": ("Safari Fortune", "Earn 25,000 Safari points"),
        "first_purchase": ("First Purchase", "Buy something with Safari points"),
        "fair_trade": ("Fair Trade", "Trade goods at the Safari shop"),
        "regular": ("Regular", "Complete 10 Safari hunts"),
        "veteran": ("Veteran", "Complete 50 Safari hunts"),
        "new_deal": ("New Deal", "Hunt on 7 different Safari maps"),
        "hands_off": ("Hands Off!", "Try to hurt the Safari Warden or one of his Pokémon"),
        "fishing/first_bite": ("Something's Biting", "Fish a Pokémon in the Safari"),
        "fishing/hook_line_and_ball": ("Hook, Line and Ball", "Catch a Pokémon you fished in the Safari"),
        "fishing/angler": ("Angler", "Fish 10 Pokémon in the Safari"),
        "fishing/master_angler": ("Master Angler", "Fish 50 Pokémon in the Safari"),
        "fishing/tasty_bait": ("Tasty Bait", "Fish a Pokémon in the Safari with bait on your rod"),
        "fishing/glint_below": ("Glint Below the Surface", "Fish a shiny Pokémon in the Safari"),
        "fishing/the_big_one": ("The Big One", "Fish one of today's special Pokémon"),
        "fishing/underground_angler": ("Underground Angler", "Fish a Pokémon in one of the Safari's caves"),
        "fishing/all_aboard": ("All Aboard", "Fish a Pokémon from a fishing boat in the Safari"),
    },
    "fr_fr": {
        "root": ("Parc Safari", "Obtenir un Ticket Safari ou entrer dans le Parc Safari"),
        "enter": ("Hautes herbes", "Entrer dans le Parc Safari pour la première fois"),
        "island_tour": ("Tour de l'île", "Visiter les quatorze habitats du Parc Safari"),
        "caverns": ("Sous la surface", "Atteindre l'une des deux grandes cavernes du Parc Safari"),
        "summit": ("Sur le toit du parc", "Atteindre les sommets ou le volcan du Parc Safari"),
        "archaeologist": ("Archéologue", "Trouver un henge ou une ruine dans le Parc Safari, ou y déterrer un fossile"),
        "habitat": ("Habitat naturel", "Trouver l'un des petits habitats de Pokémon du Parc Safari"),
        "first_trip": ("Correspondance", "Rejoindre un refuge avec le Guide du Safari ou une Corde Sortie"),
        "frequent_traveller": ("Grand voyageur", "Faire 25 trajets vers les refuges du Parc Safari"),
        "first_catch": ("Première prise", "Capturer un Pokémon dans le Parc Safari"),
        "old_school": ("À l'ancienne", "Capturer un Pokémon hors combat avec une Safari Ball"),
        "special_of_the_day": ("Spécial du jour", "Capturer l'un des Pokémon spéciaux du jour"),
        "ultra_rare": ("Rarissime", "Capturer un Pokémon spécial ultra-rare"),
        "wild_shine": ("Éclat sauvage", "Capturer un Pokémon chromatique dans le Parc Safari"),
        "night_owl": ("Oiseau de nuit", "Capturer un Pokémon dans le Parc Safari la nuit"),
        "good_haul": ("Beau tableau", "Capturer 10 Pokémon en une seule chasse"),
        "safari_express": ("Safari express", "Capturer 30 Pokémon en une seule chasse"),
        "encyclopedist": ("Encyclopédiste", "Capturer 50 espèces différentes dans le Parc Safari"),
        "no_half_measures": ("Pas de demi-mesure", "Capturer un Pokémon avec une Master Ball dans le Parc Safari"),
        "park_legend": ("Légende du parc", "Capturer un Pokémon légendaire ou fabuleux dans le Parc Safari"),
        "snack_time": ("L'heure du goûter", "Poser un Poké Snack dans le Parc Safari"),
        "dinner_is_served": ("À table !", "Capturer un Pokémon venu manger un Poké Snack"),
        "layer_cake": ("Pièce montée", "Poser un Poké Gâteau dans le Parc Safari"),
        "piggy_bank": ("Tirelire", "Remplir à ras bord votre réserve de temps Safari"),
        "to_the_end": ("Jusqu'au bout", "Chasser pendant le temps maximal autorisé, jusqu'à la dernière seconde"),
        "photo_finish": ("Sur le fil", "Capturer un Pokémon dans les 10 dernières secondes d'une chasse"),
        "nothing_wasted": ("Rien ne se perd", "Partir en avance auprès d'un vendeur et récupérer le temps non utilisé"),
        "reporting_in": ("Au rapport", "Accomplir une mission du Safari"),
        "clean_sweep": ("Carton plein", "Accomplir toutes les missions d'une rotation"),
        "peaceful_hunter": ("Chasseur pacifique", "Accomplir une mission sans combat"),
        "wild_card": ("Joker", "Faire échanger l'une de vos missions par un vendeur"),
        "nest_egg": ("Petite épargne", "Gagner 1 000 points Safari"),
        "safari_fortune": ("Fortune du Safari", "Gagner 25 000 points Safari"),
        "first_purchase": ("Premier achat", "Acheter quelque chose avec des points Safari"),
        "fair_trade": ("Troc", "Échanger des marchandises à la boutique du Safari"),
        "regular": ("Habitué", "Terminer 10 chasses au Safari"),
        "veteran": ("Vétéran", "Terminer 50 chasses au Safari"),
        "new_deal": ("Nouvelle donne", "Chasser sur 7 cartes différentes du Parc Safari"),
        "hands_off": ("Pas touche !", "Essayer de blesser le Garde du Safari ou l'un de ses Pokémon"),
        "fishing/first_bite": ("Ça mord !", "Pêcher un Pokémon dans le Parc Safari"),
        "fishing/hook_line_and_ball": ("Prise de la ligne", "Capturer un Pokémon que vous avez pêché dans le Parc Safari"),
        "fishing/angler": ("Pêcheur du dimanche", "Pêcher 10 Pokémon dans le Parc Safari"),
        "fishing/master_angler": ("Maître pêcheur", "Pêcher 50 Pokémon dans le Parc Safari"),
        "fishing/tasty_bait": ("Appât savoureux", "Pêcher un Pokémon dans le Parc Safari avec un appât sur la canne"),
        "fishing/glint_below": ("Reflet doré", "Pêcher un Pokémon chromatique dans le Parc Safari"),
        "fishing/the_big_one": ("Le gros poisson", "Pêcher l'un des Pokémon spéciaux du jour"),
        "fishing/underground_angler": ("Pêche souterraine", "Pêcher un Pokémon dans l'une des grottes du Parc Safari"),
        "fishing/all_aboard": ("À bord !", "Pêcher un Pokémon depuis un bateau de pêche du Parc Safari"),
    },
    "de_de": {
        "root": ("Safari-Zone", "Besorge dir ein Safari-Ticket oder betritt die Safari"),
        "enter": ("Ins hohe Gras", "Betritt die Safari zum ersten Mal"),
        "island_tour": ("Inselrundfahrt", "Besuche alle vierzehn Lebensräume der Safari"),
        "caverns": ("Unter der Oberfläche", "Erreiche eine der beiden großen Höhlen der Safari"),
        "summit": ("Dach des Parks", "Erreiche die Gipfel oder den Vulkan der Safari"),
        "archaeologist": ("Archäologe", "Finde einen Steinkreis oder eine Ruine in der Safari oder grabe dort ein Fossil aus"),
        "habitat": ("Natürlicher Lebensraum", "Finde einen der kleinen Pokémon-Lebensräume der Safari"),
        "first_trip": ("Anschlussreise", "Reise mit dem Safari-Führer oder einem Fluchtseil zu einem Rasthaus"),
        "frequent_traveller": ("Vielreisender", "Unternimm 25 Reisen zu den Rasthäusern der Safari"),
        "first_catch": ("Erster Fang", "Fange ein Pokémon in der Safari"),
        "old_school": ("Wie in alten Zeiten", "Fange ein Pokémon außerhalb eines Kampfes mit einem Safariball"),
        "special_of_the_day": ("Tagesspezialität", "Fange eines der besonderen Pokémon des Tages"),
        "ultra_rare": ("Alle Jubeljahre", "Fange ein ultraseltenes besonderes Pokémon"),
        "wild_shine": ("Wilder Glanz", "Fange ein schillerndes Pokémon in der Safari"),
        "night_owl": ("Nachteule", "Fange nachts ein Pokémon in der Safari"),
        "good_haul": ("Reiche Beute", "Fange 10 Pokémon bei einer einzigen Jagd"),
        "safari_express": ("Safari-Express", "Fange 30 Pokémon bei einer einzigen Jagd"),
        "encyclopedist": ("Enzyklopädist", "Fange 50 verschiedene Arten in der Safari"),
        "no_half_measures": ("Keine halben Sachen", "Fange ein Pokémon mit einem Meisterball in der Safari"),
        "park_legend": ("Legende des Parks", "Fange ein legendäres oder mysteriöses Pokémon in der Safari"),
        "snack_time": ("Snackpause", "Platziere einen Poké-Snack in der Safari"),
        "dinner_is_served": ("Es ist angerichtet", "Fange ein Pokémon, das von einem Poké-Snack fressen wollte"),
        "layer_cake": ("Schichttorte", "Platziere einen Poké-Kuchen in der Safari"),
        "piggy_bank": ("Sparschwein", "Fülle dein Safari-Zeitkonto bis zum Rand"),
        "to_the_end": ("Bis zum Schluss", "Jage so lange wie erlaubt, bis zur letzten Sekunde"),
        "photo_finish": ("Fotofinish", "Fange ein Pokémon in den letzten 10 Sekunden einer Jagd"),
        "nothing_wasted": ("Nichts verschwendet", "Verlasse die Safari früher über einen Angestellten und erhalte deine ungenutzte Zeit zurück"),
        "reporting_in": ("Melde gehorsamst", "Schließe eine Safari-Mission ab"),
        "clean_sweep": ("Volle Punktzahl", "Schließe alle Missionen einer Rotation ab"),
        "peaceful_hunter": ("Friedlicher Jäger", "Schließe eine Mission ohne Kampf ab"),
        "wild_card": ("Joker", "Lass einen Angestellten eine deiner Missionen tauschen"),
        "nest_egg": ("Notgroschen", "Verdiene 1.000 Safari-Punkte"),
        "safari_fortune": ("Safari-Vermögen", "Verdiene 25.000 Safari-Punkte"),
        "first_purchase": ("Erster Einkauf", "Kaufe etwas mit Safari-Punkten"),
        "fair_trade": ("Fairer Handel", "Tausche Waren im Safari-Laden"),
        "regular": ("Stammgast", "Beende 10 Safari-Jagden"),
        "veteran": ("Veteran", "Beende 50 Safari-Jagden"),
        "new_deal": ("Neu gemischt", "Jage auf 7 verschiedenen Safari-Karten"),
        "hands_off": ("Finger weg!", "Versuche, den Safari-Wärter oder eines seiner Pokémon zu verletzen"),
        "fishing/first_bite": ("Es beißt!", "Angle ein Pokémon in der Safari"),
        "fishing/hook_line_and_ball": ("Haken, Schnur und Ball", "Fange ein Pokémon, das du in der Safari geangelt hast"),
        "fishing/angler": ("Angler", "Angle 10 Pokémon in der Safari"),
        "fishing/master_angler": ("Meisterangler", "Angle 50 Pokémon in der Safari"),
        "fishing/tasty_bait": ("Leckerer Köder", "Angle ein Pokémon in der Safari mit einem Köder an der Angel"),
        "fishing/glint_below": ("Glanz unter Wasser", "Angle ein schillerndes Pokémon in der Safari"),
        "fishing/the_big_one": ("Der dicke Fisch", "Angle eines der besonderen Pokémon des Tages"),
        "fishing/underground_angler": ("Höhlenangler", "Angle ein Pokémon in einer der Höhlen der Safari"),
        "fishing/all_aboard": ("Alle an Bord", "Angle ein Pokémon von einem Fischerboot in der Safari aus"),
    },
    "es_es": {
        "root": ("Zona Safari", "Consigue un Ticket Safari o entra en el Safari"),
        "enter": ("Entre la hierba alta", "Entra en el Safari por primera vez"),
        "island_tour": ("Vuelta a la isla", "Visita los catorce hábitats del Safari"),
        "caverns": ("Bajo la superficie", "Llega a una de las dos grandes cavernas del Safari"),
        "summit": ("La cima del parque", "Llega a las cumbres o al volcán del Safari"),
        "archaeologist": ("Arqueólogo", "Encuentra un crómlech o unas ruinas en el Safari, o desentierra allí un fósil"),
        "habitat": ("Hábitat natural", "Encuentra uno de los pequeños hábitats de Pokémon del Safari"),
        "first_trip": ("Transbordo", "Viaja a una casa de descanso con el Guía del Safari o una Cuerda Huida"),
        "frequent_traveller": ("Viajero frecuente", "Haz 25 viajes a las casas de descanso del Safari"),
        "first_catch": ("Primera captura", "Captura un Pokémon en el Safari"),
        "old_school": ("A la antigua", "Captura un Pokémon fuera de combate con una Safari Ball"),
        "special_of_the_day": ("Especial del día", "Captura uno de los Pokémon especiales del día"),
        "ultra_rare": ("De uvas a peras", "Captura un Pokémon especial ultrarraro"),
        "wild_shine": ("Brillo salvaje", "Captura un Pokémon variocolor en el Safari"),
        "night_owl": ("Ave nocturna", "Captura un Pokémon en el Safari de noche"),
        "good_haul": ("Buena cosecha", "Captura 10 Pokémon en una sola cacería"),
        "safari_express": ("Safari exprés", "Captura 30 Pokémon en una sola cacería"),
        "encyclopedist": ("Enciclopedista", "Captura 50 especies distintas en el Safari"),
        "no_half_measures": ("Sin medias tintas", "Captura un Pokémon con una Master Ball en el Safari"),
        "park_legend": ("Leyenda del parque", "Captura un Pokémon legendario o singular en el Safari"),
        "snack_time": ("Hora de merendar", "Coloca un Poké Señuelo en el Safari"),
        "dinner_is_served": ("¡A la mesa!", "Captura un Pokémon que vino a comer de un Poké Señuelo"),
        "layer_cake": ("Tarta de pisos", "Coloca una Poké Tarta en el Safari"),
        "piggy_bank": ("Hucha", "Llena hasta arriba tu reserva de tiempo del Safari"),
        "to_the_end": ("Hasta el final", "Caza durante el tiempo máximo permitido, hasta el último segundo"),
        "photo_finish": ("Por los pelos", "Captura un Pokémon en los últimos 10 segundos de una cacería"),
        "nothing_wasted": ("Nada se pierde", "Sal antes de tiempo a través de un dependiente y recupera el tiempo no usado"),
        "reporting_in": ("Misión cumplida", "Completa una misión del Safari"),
        "clean_sweep": ("Pleno", "Completa todas las misiones de una rotación"),
        "peaceful_hunter": ("Cazador pacífico", "Completa una misión sin combate"),
        "wild_card": ("Comodín", "Pide a un dependiente que cambie una de tus misiones"),
        "nest_egg": ("Ahorrillos", "Gana 1.000 puntos Safari"),
        "safari_fortune": ("Fortuna del Safari", "Gana 25.000 puntos Safari"),
        "first_purchase": ("Primera compra", "Compra algo con puntos Safari"),
        "fair_trade": ("Trueque", "Intercambia mercancías en la tienda del Safari"),
        "regular": ("Habitual", "Completa 10 cacerías en el Safari"),
        "veteran": ("Veterano", "Completa 50 cacerías en el Safari"),
        "new_deal": ("Nueva baraja", "Caza en 7 mapas distintos del Safari"),
        "hands_off": ("¡Quietas las manos!", "Intenta herir al Guarda del Safari o a uno de sus Pokémon"),
        "fishing/first_bite": ("¡Algo pica!", "Pesca un Pokémon en el Safari"),
        "fishing/hook_line_and_ball": ("Anzuelo, sedal y Ball", "Captura un Pokémon que hayas pescado en el Safari"),
        "fishing/angler": ("Pescador", "Pesca 10 Pokémon en el Safari"),
        "fishing/master_angler": ("Maestro pescador", "Pesca 50 Pokémon en el Safari"),
        "fishing/tasty_bait": ("Cebo sabroso", "Pesca un Pokémon en el Safari con cebo en la caña"),
        "fishing/glint_below": ("Destello bajo el agua", "Pesca un Pokémon variocolor en el Safari"),
        "fishing/the_big_one": ("El pez gordo", "Pesca uno de los Pokémon especiales del día"),
        "fishing/underground_angler": ("Pesca subterránea", "Pesca un Pokémon en una de las cuevas del Safari"),
        "fishing/all_aboard": ("¡Todos a bordo!", "Pesca un Pokémon desde un barco pesquero del Safari"),
    },
    "es_mx": {
        "root": ("Zona Safari", "Consigue un Boleto Safari o entra al Safari"),
        "enter": ("Entre la hierba alta", "Entra al Safari por primera vez"),
        "island_tour": ("Vuelta a la isla", "Visita los catorce hábitats del Safari"),
        "caverns": ("Bajo la superficie", "Llega a una de las dos grandes cavernas del Safari"),
        "summit": ("La cima del parque", "Llega a las cumbres o al volcán del Safari"),
        "archaeologist": ("Arqueólogo", "Encuentra un crómlech o unas ruinas en el Safari, o desentierra ahí un fósil"),
        "habitat": ("Hábitat natural", "Encuentra uno de los pequeños hábitats de Pokémon del Safari"),
        "first_trip": ("Transbordo", "Viaja a una casa de descanso con el Guía del Safari o una Cuerda Huida"),
        "frequent_traveller": ("Viajero frecuente", "Haz 25 viajes a las casas de descanso del Safari"),
        "first_catch": ("Primera captura", "Atrapa un Pokémon en el Safari"),
        "old_school": ("A la antigua", "Atrapa un Pokémon fuera de combate con una Safari Ball"),
        "special_of_the_day": ("Especial del día", "Atrapa uno de los Pokémon especiales del día"),
        "ultra_rare": ("Muy de vez en cuando", "Atrapa un Pokémon especial ultrarraro"),
        "wild_shine": ("Brillo salvaje", "Atrapa un Pokémon shiny en el Safari"),
        "night_owl": ("Ave nocturna", "Atrapa un Pokémon en el Safari de noche"),
        "good_haul": ("Buena cosecha", "Atrapa 10 Pokémon en una sola cacería"),
        "safari_express": ("Safari exprés", "Atrapa 30 Pokémon en una sola cacería"),
        "encyclopedist": ("Enciclopedista", "Atrapa 50 especies distintas en el Safari"),
        "no_half_measures": ("Sin medias tintas", "Atrapa un Pokémon con una Master Ball en el Safari"),
        "park_legend": ("Leyenda del parque", "Atrapa un Pokémon legendario o singular en el Safari"),
        "snack_time": ("Hora del refrigerio", "Coloca un Poké Señuelo en el Safari"),
        "dinner_is_served": ("¡A comer!", "Atrapa un Pokémon que vino a comer de un Poké Señuelo"),
        "layer_cake": ("Pastel de pisos", "Coloca un Poké Pastel en el Safari"),
        "piggy_bank": ("Alcancía", "Llena hasta el tope tu reserva de tiempo del Safari"),
        "to_the_end": ("Hasta el final", "Caza durante el tiempo máximo permitido, hasta el último segundo"),
        "photo_finish": ("De panzazo", "Atrapa un Pokémon en los últimos 10 segundos de una cacería"),
        "nothing_wasted": ("Nada se pierde", "Sal antes de tiempo con un empleado y recupera el tiempo que no usaste"),
        "reporting_in": ("Misión cumplida", "Completa una misión del Safari"),
        "clean_sweep": ("Pleno", "Completa todas las misiones de una rotación"),
        "peaceful_hunter": ("Cazador pacífico", "Completa una misión sin combate"),
        "wild_card": ("Comodín", "Pide a un empleado que cambie una de tus misiones"),
        "nest_egg": ("Guardadito", "Gana 1,000 puntos Safari"),
        "safari_fortune": ("Fortuna del Safari", "Gana 25,000 puntos Safari"),
        "first_purchase": ("Primera compra", "Compra algo con puntos Safari"),
        "fair_trade": ("Trueque", "Intercambia mercancías en la tienda del Safari"),
        "regular": ("Cliente frecuente", "Completa 10 cacerías en el Safari"),
        "veteran": ("Veterano", "Completa 50 cacerías en el Safari"),
        "new_deal": ("Nueva baraja", "Caza en 7 mapas distintos del Safari"),
        "hands_off": ("¡No se toca!", "Intenta lastimar al Guarda del Safari o a uno de sus Pokémon"),
        "fishing/first_bite": ("¡Algo picó!", "Pesca un Pokémon en el Safari"),
        "fishing/hook_line_and_ball": ("Anzuelo, sedal y Ball", "Atrapa un Pokémon que hayas pescado en el Safari"),
        "fishing/angler": ("Pescador", "Pesca 10 Pokémon en el Safari"),
        "fishing/master_angler": ("Maestro pescador", "Pesca 50 Pokémon en el Safari"),
        "fishing/tasty_bait": ("Carnada sabrosa", "Pesca un Pokémon en el Safari con carnada en la caña"),
        "fishing/glint_below": ("Destello bajo el agua", "Pesca un Pokémon shiny en el Safari"),
        "fishing/the_big_one": ("El pez gordo", "Pesca uno de los Pokémon especiales del día"),
        "fishing/underground_angler": ("Pesca subterránea", "Pesca un Pokémon en una de las cuevas del Safari"),
        "fishing/all_aboard": ("¡Todos a bordo!", "Pesca un Pokémon desde un barco pesquero del Safari"),
    },
    "it_it": {
        "root": ("Zona Safari", "Ottieni un Biglietto Safari o entra nel Safari"),
        "enter": ("Nell'erba alta", "Entra nel Safari per la prima volta"),
        "island_tour": ("Giro dell'isola", "Visita tutti i quattordici habitat del Safari"),
        "caverns": ("Sotto la superficie", "Raggiungi una delle due grandi caverne del Safari"),
        "summit": ("Il tetto del parco", "Raggiungi le vette o il vulcano del Safari"),
        "archaeologist": ("Archeologo", "Trova un cerchio di pietre o una rovina nel Safari, o dissotterra lì un fossile"),
        "habitat": ("Habitat naturale", "Trova uno dei piccoli habitat di Pokémon del Safari"),
        "first_trip": ("Coincidenza", "Raggiungi una casa di riposo con la Guida del Safari o una Fune di fuga"),
        "frequent_traveller": ("Viaggiatore abituale", "Fai 25 viaggi verso le case di riposo del Safari"),
        "first_catch": ("Prima cattura", "Cattura un Pokémon nel Safari"),
        "old_school": ("Alla vecchia maniera", "Cattura un Pokémon fuori dalla lotta con una Safari Ball"),
        "special_of_the_day": ("Speciale del giorno", "Cattura uno dei Pokémon speciali del giorno"),
        "ultra_rare": ("Una volta ogni morte di papa", "Cattura un Pokémon speciale ultrararo"),
        "wild_shine": ("Splendore selvatico", "Cattura un Pokémon cromatico nel Safari"),
        "night_owl": ("Nottambulo", "Cattura un Pokémon nel Safari di notte"),
        "good_haul": ("Bel bottino", "Cattura 10 Pokémon in una sola caccia"),
        "safari_express": ("Safari express", "Cattura 30 Pokémon in una sola caccia"),
        "encyclopedist": ("Enciclopedista", "Cattura 50 specie diverse nel Safari"),
        "no_half_measures": ("Niente mezze misure", "Cattura un Pokémon con una Master Ball nel Safari"),
        "park_legend": ("Leggenda del parco", "Cattura un Pokémon leggendario o misterioso nel Safari"),
        "snack_time": ("Ora della merenda", "Piazza un Poké Snack nel Safari"),
        "dinner_is_served": ("A tavola!", "Cattura un Pokémon venuto a mangiare un Poké Snack"),
        "layer_cake": ("Torta a piani", "Piazza una Poké Torta nel Safari"),
        "piggy_bank": ("Salvadanaio", "Riempi fino all'orlo la tua riserva di tempo Safari"),
        "to_the_end": ("Fino alla fine", "Caccia per il tempo massimo consentito, fino all'ultimo secondo"),
        "photo_finish": ("Al fotofinish", "Cattura un Pokémon negli ultimi 10 secondi di una caccia"),
        "nothing_wasted": ("Nulla va perso", "Esci in anticipo tramite un commesso e recupera il tempo non usato"),
        "reporting_in": ("A rapporto", "Completa una missione del Safari"),
        "clean_sweep": ("En plein", "Completa tutte le missioni di una rotazione"),
        "peaceful_hunter": ("Cacciatore pacifico", "Completa una missione senza lotta"),
        "wild_card": ("Jolly", "Fai sostituire una delle tue missioni da un commesso"),
        "nest_egg": ("Gruzzoletto", "Guadagna 1.000 punti Safari"),
        "safari_fortune": ("Fortuna del Safari", "Guadagna 25.000 punti Safari"),
        "first_purchase": ("Primo acquisto", "Compra qualcosa con i punti Safari"),
        "fair_trade": ("Baratto", "Scambia merci al negozio del Safari"),
        "regular": ("Cliente abituale", "Completa 10 cacce nel Safari"),
        "veteran": ("Veterano", "Completa 50 cacce nel Safari"),
        "new_deal": ("Nuova mano", "Caccia su 7 mappe diverse del Safari"),
        "hands_off": ("Giù le mani!", "Prova a ferire il Guardiano del Safari o uno dei suoi Pokémon"),
        "fishing/first_bite": ("Abbocca!", "Pesca un Pokémon nel Safari"),
        "fishing/hook_line_and_ball": ("Amo, lenza e Ball", "Cattura un Pokémon che hai pescato nel Safari"),
        "fishing/angler": ("Pescatore", "Pesca 10 Pokémon nel Safari"),
        "fishing/master_angler": ("Maestro pescatore", "Pesca 50 Pokémon nel Safari"),
        "fishing/tasty_bait": ("Esca gustosa", "Pesca un Pokémon nel Safari con un'esca sulla canna"),
        "fishing/glint_below": ("Riflesso sott'acqua", "Pesca un Pokémon cromatico nel Safari"),
        "fishing/the_big_one": ("Il pesce grosso", "Pesca uno dei Pokémon speciali del giorno"),
        "fishing/underground_angler": ("Pesca sotterranea", "Pesca un Pokémon in una delle grotte del Safari"),
        "fishing/all_aboard": ("Tutti a bordo!", "Pesca un Pokémon da un peschereccio del Safari"),
    },
    "ja_jp": {
        "root": ("サファリゾーン", "サファリチケットを手に入れるか、サファリに入る"),
        "enter": ("くさむらの向こうへ", "はじめてサファリに入る"),
        "island_tour": ("島めぐり", "サファリの14の生息地をすべて訪れる"),
        "caverns": ("地面の下", "サファリの2つの大洞窟のどちらかにたどり着く"),
        "summit": ("パークのてっぺん", "サファリの山頂か火山にたどり着く"),
        "archaeologist": ("考古学者", "サファリでストーンサークルか遺跡を見つけるか、化石を掘り出す"),
        "habitat": ("自然の生息地", "サファリにあるポケモンの小さな生息地を見つける"),
        "first_trip": ("乗り継ぎ", "サファリガイドかあなぬけのヒモで休憩所へ移動する"),
        "frequent_traveller": ("旅慣れた人", "サファリの休憩所へ25回移動する"),
        "first_catch": ("はじめての捕獲", "サファリでポケモンを捕まえる"),
        "old_school": ("昔ながらのやり方", "バトルをせずにサファリボールでポケモンを捕まえる"),
        "special_of_the_day": ("本日の特別", "本日の特別なポケモンを1匹捕まえる"),
        "ultra_rare": ("千載一遇", "超レアな特別なポケモンを捕まえる"),
        "wild_shine": ("野生の輝き", "サファリで色違いのポケモンを捕まえる"),
        "night_owl": ("夜ふかし", "夜にサファリでポケモンを捕まえる"),
        "good_haul": ("大漁", "1回の狩りでポケモンを10匹捕まえる"),
        "safari_express": ("サファリ特急", "1回の狩りでポケモンを30匹捕まえる"),
        "encyclopedist": ("博物学者", "サファリで50種類のポケモンを捕まえる"),
        "no_half_measures": ("出し惜しみなし", "サファリでマスターボールを使ってポケモンを捕まえる"),
        "park_legend": ("パークの伝説", "サファリで伝説か幻のポケモンを捕まえる"),
        "snack_time": ("おやつの時間", "サファリにポケモンスナックを置く"),
        "dinner_is_served": ("ごはんですよ", "ポケモンスナックを食べに来たポケモンを捕まえる"),
        "layer_cake": ("段重ねケーキ", "サファリにポケモンケーキを置く"),
        "piggy_bank": ("ちょきんばこ", "サファリの持ち時間をいっぱいまでためる"),
        "to_the_end": ("最後の最後まで", "許された最大の時間、最後の1秒まで狩りをする"),
        "photo_finish": ("ギリギリセーフ", "狩りの残り10秒以内にポケモンを捕まえる"),
        "nothing_wasted": ("むだなし", "店員に頼んで早めに出て、使わなかった時間を取り戻す"),
        "reporting_in": ("任務完了", "サファリのミッションを1つ達成する"),
        "clean_sweep": ("全部クリア", "1回のローテーションのミッションをすべて達成する"),
        "peaceful_hunter": ("平和なハンター", "バトルなしのミッションを達成する"),
        "wild_card": ("切り札", "店員にミッションを1つ入れ替えてもらう"),
        "nest_egg": ("へそくり", "サファリポイントを1,000獲得する"),
        "safari_fortune": ("サファリの財産", "サファリポイントを25,000獲得する"),
        "first_purchase": ("はじめての買い物", "サファリポイントで何かを買う"),
        "fair_trade": ("物々交換", "サファリのショップで品物を交換する"),
        "regular": ("常連", "サファリでの狩りを10回終える"),
        "veteran": ("ベテラン", "サファリでの狩りを50回終える"),
        "new_deal": ("仕切り直し", "7つの異なるサファリのマップで狩りをする"),
        "hands_off": ("さわっちゃダメ！", "サファリの係員か、その係員のポケモンを傷つけようとする"),
        "fishing/first_bite": ("つれた！", "サファリでポケモンを釣る"),
        "fishing/hook_line_and_ball": ("釣って捕まえて", "サファリで釣ったポケモンを捕まえる"),
        "fishing/angler": ("釣り人", "サファリでポケモンを10匹釣る"),
        "fishing/master_angler": ("釣り名人", "サファリでポケモンを50匹釣る"),
        "fishing/tasty_bait": ("おいしいエサ", "釣り竿にエサをつけてサファリでポケモンを釣る"),
        "fishing/glint_below": ("水面下のきらめき", "サファリで色違いのポケモンを釣る"),
        "fishing/the_big_one": ("大物", "本日の特別なポケモンを1匹釣る"),
        "fishing/underground_angler": ("地底の釣り人", "サファリの洞窟でポケモンを釣る"),
        "fishing/all_aboard": ("乗船！", "サファリの釣り船の上からポケモンを釣る"),
    },
    "ko_kr": {
        "root": ("사파리존", "사파리 티켓을 얻거나 사파리에 들어가기"),
        "enter": ("풀숲 속으로", "처음으로 사파리에 들어가기"),
        "island_tour": ("섬 일주", "사파리의 서식지 14곳을 모두 방문하기"),
        "caverns": ("땅속으로", "사파리의 두 거대 동굴 중 하나에 도달하기"),
        "summit": ("공원의 꼭대기", "사파리의 산봉우리나 화산에 도달하기"),
        "archaeologist": ("고고학자", "사파리에서 환상열석이나 유적을 찾거나 화석을 발굴하기"),
        "habitat": ("자연 서식지", "사파리의 작은 포켓몬 서식지 하나를 찾기"),
        "first_trip": ("환승", "사파리 가이드나 동굴탈출로프로 휴게소에 이동하기"),
        "frequent_traveller": ("단골 여행자", "사파리 휴게소로 25번 이동하기"),
        "first_catch": ("첫 포획", "사파리에서 포켓몬 잡기"),
        "old_school": ("옛날 방식", "배틀 없이 사파리볼로 포켓몬 잡기"),
        "special_of_the_day": ("오늘의 특별 포켓몬", "오늘의 특별 포켓몬 중 하나 잡기"),
        "ultra_rare": ("가뭄에 콩 나듯", "초희귀 특별 포켓몬 잡기"),
        "wild_shine": ("야생의 빛", "사파리에서 색이 다른 포켓몬 잡기"),
        "night_owl": ("올빼미족", "밤에 사파리에서 포켓몬 잡기"),
        "good_haul": ("풍성한 수확", "한 번의 사냥에서 포켓몬 10마리 잡기"),
        "safari_express": ("사파리 특급", "한 번의 사냥에서 포켓몬 30마리 잡기"),
        "encyclopedist": ("백과사전", "사파리에서 서로 다른 종 50가지 잡기"),
        "no_half_measures": ("아낌없이", "사파리에서 마스터볼로 포켓몬 잡기"),
        "park_legend": ("공원의 전설", "사파리에서 전설 또는 환상의 포켓몬 잡기"),
        "snack_time": ("간식 시간", "사파리에 포켓스낵 놓기"),
        "dinner_is_served": ("식사 준비 완료", "포켓스낵을 먹으러 온 포켓몬 잡기"),
        "layer_cake": ("층층이 케이크", "사파리에 포켓케이크 놓기"),
        "piggy_bank": ("돼지 저금통", "사파리 시간 저장소를 가득 채우기"),
        "to_the_end": ("끝까지", "허용된 최대 시간 동안 마지막 1초까지 사냥하기"),
        "photo_finish": ("간발의 차", "사냥 종료 10초 전 안에 포켓몬 잡기"),
        "nothing_wasted": ("낭비 없음", "점원을 통해 일찍 나가서 남은 시간을 돌려받기"),
        "reporting_in": ("임무 보고", "사파리 미션 하나 완료하기"),
        "clean_sweep": ("싹쓸이", "한 로테이션의 미션을 모두 완료하기"),
        "peaceful_hunter": ("평화로운 사냥꾼", "배틀 없는 미션 완료하기"),
        "wild_card": ("와일드카드", "점원에게 미션 하나를 바꿔 달라고 하기"),
        "nest_egg": ("비상금", "사파리 포인트 1,000점 획득하기"),
        "safari_fortune": ("사파리의 부자", "사파리 포인트 25,000점 획득하기"),
        "first_purchase": ("첫 구매", "사파리 포인트로 무언가 사기"),
        "fair_trade": ("물물교환", "사파리 상점에서 물건 교환하기"),
        "regular": ("단골", "사파리 사냥 10번 마치기"),
        "veteran": ("베테랑", "사파리 사냥 50번 마치기"),
        "new_deal": ("새판 짜기", "서로 다른 사파리 맵 7곳에서 사냥하기"),
        "hands_off": ("손대지 마!", "사파리 관리인이나 그의 포켓몬을 다치게 하려고 하기"),
        "fishing/first_bite": ("입질이 왔다!", "사파리에서 포켓몬 낚기"),
        "fishing/hook_line_and_ball": ("낚고 잡고", "사파리에서 낚은 포켓몬 잡기"),
        "fishing/angler": ("낚시꾼", "사파리에서 포켓몬 10마리 낚기"),
        "fishing/master_angler": ("낚시 명인", "사파리에서 포켓몬 50마리 낚기"),
        "fishing/tasty_bait": ("맛있는 미끼", "낚싯대에 미끼를 달고 사파리에서 포켓몬 낚기"),
        "fishing/glint_below": ("물속의 반짝임", "사파리에서 색이 다른 포켓몬 낚기"),
        "fishing/the_big_one": ("월척", "오늘의 특별 포켓몬 중 하나 낚기"),
        "fishing/underground_angler": ("동굴 낚시꾼", "사파리의 동굴에서 포켓몬 낚기"),
        "fishing/all_aboard": ("모두 승선!", "사파리의 낚싯배 위에서 포켓몬 낚기"),
    },
    "pl_pl": {
        "root": ("Strefa Safari", "Zdobądź Bilet Safari lub wejdź do Safari"),
        "enter": ("W wysokiej trawie", "Wejdź do Safari po raz pierwszy"),
        "island_tour": ("Wycieczka po wyspie", "Odwiedź wszystkie czternaście siedlisk Safari"),
        "caverns": ("Pod powierzchnią", "Dotrzyj do jednej z dwóch wielkich jaskiń Safari"),
        "summit": ("Dach parku", "Dotrzyj na szczyty lub do wulkanu Safari"),
        "archaeologist": ("Archeolog", "Znajdź krąg kamienny lub ruiny w Safari albo wykop tam skamielinę"),
        "habitat": ("Naturalne siedlisko", "Znajdź jedno z małych siedlisk Pokémonów w Safari"),
        "first_trip": ("Przesiadka", "Dotrzyj do domu odpoczynku z Przewodnikiem Safari lub Liną ucieczki"),
        "frequent_traveller": ("Stały podróżnik", "Odbądź 25 podróży do domów odpoczynku Safari"),
        "first_catch": ("Pierwszy połów", "Złap Pokémona w Safari"),
        "old_school": ("Po staremu", "Złap Pokémona poza walką za pomocą Safari Balla"),
        "special_of_the_day": ("Danie dnia", "Złap jednego z wyjątkowych Pokémonów dnia"),
        "ultra_rare": ("Raz na ruski rok", "Złap ultrarzadkiego wyjątkowego Pokémona"),
        "wild_shine": ("Dziki blask", "Złap lśniącego Pokémona w Safari"),
        "night_owl": ("Nocny marek", "Złap Pokémona w Safari nocą"),
        "good_haul": ("Udane łowy", "Złap 10 Pokémonów podczas jednego polowania"),
        "safari_express": ("Safari ekspres", "Złap 30 Pokémonów podczas jednego polowania"),
        "encyclopedist": ("Encyklopedysta", "Złap 50 różnych gatunków w Safari"),
        "no_half_measures": ("Bez półśrodków", "Złap Pokémona Master Ballem w Safari"),
        "park_legend": ("Legenda parku", "Złap legendarnego lub mitycznego Pokémona w Safari"),
        "snack_time": ("Pora na przekąskę", "Postaw Poké Przekąskę w Safari"),
        "dinner_is_served": ("Podano do stołu", "Złap Pokémona, który przyszedł zjeść Poké Przekąskę"),
        "layer_cake": ("Tort piętrowy", "Postaw Poké Ciasto w Safari"),
        "piggy_bank": ("Skarbonka", "Napełnij po brzegi swój zapas czasu Safari"),
        "to_the_end": ("Do samego końca", "Poluj przez cały dozwolony czas, do ostatniej sekundy"),
        "photo_finish": ("Na ostatnią chwilę", "Złap Pokémona w ostatnich 10 sekundach polowania"),
        "nothing_wasted": ("Nic się nie marnuje", "Wyjdź wcześniej przez sprzedawcę i odzyskaj niewykorzystany czas"),
        "reporting_in": ("Melduję wykonanie", "Ukończ misję Safari"),
        "clean_sweep": ("Komplet", "Ukończ wszystkie misje jednej rotacji"),
        "peaceful_hunter": ("Pokojowy łowca", "Ukończ misję bez walki"),
        "wild_card": ("Dżoker", "Poproś sprzedawcę o wymianę jednej z twoich misji"),
        "nest_egg": ("Grosz na czarną godzinę", "Zdobądź 1000 punktów Safari"),
        "safari_fortune": ("Fortuna Safari", "Zdobądź 25 000 punktów Safari"),
        "first_purchase": ("Pierwszy zakup", "Kup coś za punkty Safari"),
        "fair_trade": ("Wymiana handlowa", "Wymień towary w sklepie Safari"),
        "regular": ("Stały bywalec", "Ukończ 10 polowań w Safari"),
        "veteran": ("Weteran", "Ukończ 50 polowań w Safari"),
        "new_deal": ("Nowe rozdanie", "Poluj na 7 różnych mapach Safari"),
        "hands_off": ("Ręce przy sobie!", "Spróbuj zranić Strażnika Safari lub jednego z jego Pokémonów"),
        "fishing/first_bite": ("Bierze!", "Wyłów Pokémona w Safari"),
        "fishing/hook_line_and_ball": ("Haczyk, żyłka i Ball", "Złap Pokémona wyłowionego w Safari"),
        "fishing/angler": ("Wędkarz", "Wyłów 10 Pokémonów w Safari"),
        "fishing/master_angler": ("Mistrz wędkarstwa", "Wyłów 50 Pokémonów w Safari"),
        "fishing/tasty_bait": ("Smaczna przynęta", "Wyłów Pokémona w Safari z przynętą na wędce"),
        "fishing/glint_below": ("Błysk pod wodą", "Wyłów lśniącego Pokémona w Safari"),
        "fishing/the_big_one": ("Gruba ryba", "Wyłów jednego z wyjątkowych Pokémonów dnia"),
        "fishing/underground_angler": ("Podziemny wędkarz", "Wyłów Pokémona w jednej z jaskiń Safari"),
        "fishing/all_aboard": ("Wszyscy na pokład!", "Wyłów Pokémona z łodzi rybackiej w Safari"),
    },
    "pt_br": {
        "root": ("Zona Safári", "Consiga um Ingresso Safári ou entre no Safári"),
        "enter": ("No meio do mato alto", "Entre no Safári pela primeira vez"),
        "island_tour": ("Volta à ilha", "Visite todos os catorze habitats do Safári"),
        "caverns": ("Abaixo da superfície", "Chegue a uma das duas grandes cavernas do Safári"),
        "summit": ("O topo do parque", "Chegue aos picos ou ao vulcão do Safári"),
        "archaeologist": ("Arqueólogo", "Encontre um círculo de pedras ou ruínas no Safári, ou desenterre lá um fóssil"),
        "habitat": ("Habitat natural", "Encontre um dos pequenos habitats de Pokémon do Safári"),
        "first_trip": ("Conexão", "Viaje até uma casa de descanso com o Guia do Safári ou uma Corda de Fuga"),
        "frequent_traveller": ("Viajante frequente", "Faça 25 viagens às casas de descanso do Safári"),
        "first_catch": ("Primeira captura", "Capture um Pokémon no Safári"),
        "old_school": ("À moda antiga", "Capture um Pokémon fora de batalha com uma Bola Safári"),
        "special_of_the_day": ("Especial do dia", "Capture um dos Pokémon especiais do dia"),
        "ultra_rare": ("Uma vez na vida", "Capture um Pokémon especial ultrarraro"),
        "wild_shine": ("Brilho selvagem", "Capture um Pokémon brilhante no Safári"),
        "night_owl": ("Coruja da noite", "Capture um Pokémon no Safári à noite"),
        "good_haul": ("Boa colheita", "Capture 10 Pokémon em uma única caçada"),
        "safari_express": ("Safári expresso", "Capture 30 Pokémon em uma única caçada"),
        "encyclopedist": ("Enciclopedista", "Capture 50 espécies diferentes no Safári"),
        "no_half_measures": ("Sem meias medidas", "Capture um Pokémon com uma Bola Mestra no Safári"),
        "park_legend": ("Lenda do parque", "Capture um Pokémon lendário ou mítico no Safári"),
        "snack_time": ("Hora do lanche", "Coloque um Poké-lanche no Safári"),
        "dinner_is_served": ("O jantar está servido", "Capture um Pokémon que veio comer um Poké-lanche"),
        "layer_cake": ("Bolo de andares", "Coloque um Poké-bolo no Safári"),
        "piggy_bank": ("Cofrinho", "Encha até a borda sua reserva de tempo do Safári"),
        "to_the_end": ("Até o fim", "Cace pelo tempo máximo permitido, até o último segundo"),
        "photo_finish": ("No último segundo", "Capture um Pokémon nos últimos 10 segundos de uma caçada"),
        "nothing_wasted": ("Nada se perde", "Saia mais cedo por um atendente e recupere o tempo não usado"),
        "reporting_in": ("Missão cumprida", "Conclua uma missão do Safári"),
        "clean_sweep": ("Gabaritou", "Conclua todas as missões de uma rotação"),
        "peaceful_hunter": ("Caçador pacífico", "Conclua uma missão sem batalha"),
        "wild_card": ("Curinga", "Peça a um atendente que troque uma das suas missões"),
        "nest_egg": ("Pé-de-meia", "Ganhe 1.000 pontos Safári"),
        "safari_fortune": ("Fortuna do Safári", "Ganhe 25.000 pontos Safári"),
        "first_purchase": ("Primeira compra", "Compre algo com pontos Safári"),
        "fair_trade": ("Escambo", "Troque mercadorias na loja do Safári"),
        "regular": ("Freguês", "Conclua 10 caçadas no Safári"),
        "veteran": ("Veterano", "Conclua 50 caçadas no Safári"),
        "new_deal": ("Novas cartas", "Cace em 7 mapas diferentes do Safári"),
        "hands_off": ("Tira a mão!", "Tente ferir o Guarda do Safári ou um dos Pokémon dele"),
        "fishing/first_bite": ("Fisgou!", "Pesque um Pokémon no Safári"),
        "fishing/hook_line_and_ball": ("Anzol, linha e Bola", "Capture um Pokémon que você pescou no Safári"),
        "fishing/angler": ("Pescador", "Pesque 10 Pokémon no Safári"),
        "fishing/master_angler": ("Mestre pescador", "Pesque 50 Pokémon no Safári"),
        "fishing/tasty_bait": ("Isca saborosa", "Pesque um Pokémon no Safári com isca na vara"),
        "fishing/glint_below": ("Brilho sob a água", "Pesque um Pokémon brilhante no Safári"),
        "fishing/the_big_one": ("O peixe grande", "Pesque um dos Pokémon especiais do dia"),
        "fishing/underground_angler": ("Pesca subterrânea", "Pesque um Pokémon em uma das cavernas do Safári"),
        "fishing/all_aboard": ("Todos a bordo!", "Pesque um Pokémon de um barco de pesca do Safári"),
    },
    "ru_ru": {
        "root": ("Сафари-зона", "Получите билет в Сафари или войдите в Сафари"),
        "enter": ("В высокую траву", "Впервые войдите в Сафари"),
        "island_tour": ("Тур по острову", "Посетите все четырнадцать мест обитания Сафари"),
        "caverns": ("Под поверхностью", "Доберитесь до одной из двух больших пещер Сафари"),
        "summit": ("Крыша парка", "Доберитесь до вершин или вулкана Сафари"),
        "archaeologist": ("Археолог", "Найдите каменный круг или руины в Сафари или откопайте там окаменелость"),
        "habitat": ("Естественная среда", "Найдите одно из небольших мест обитания покемонов в Сафари"),
        "first_trip": ("Пересадка", "Доберитесь до дома отдыха с гидом Сафари или верёвкой побега"),
        "frequent_traveller": ("Заядлый путешественник", "Совершите 25 поездок к домам отдыха Сафари"),
        "first_catch": ("Первый улов", "Поймайте покемона в Сафари"),
        "old_school": ("По старинке", "Поймайте покемона вне битвы сафариболом"),
        "special_of_the_day": ("Особый покемон дня", "Поймайте одного из особых покемонов дня"),
        "ultra_rare": ("Раз в сто лет", "Поймайте сверхредкого особого покемона"),
        "wild_shine": ("Дикий блеск", "Поймайте шайни-покемона в Сафари"),
        "night_owl": ("Сова", "Поймайте покемона в Сафари ночью"),
        "good_haul": ("Богатый улов", "Поймайте 10 покемонов за одну охоту"),
        "safari_express": ("Сафари-экспресс", "Поймайте 30 покемонов за одну охоту"),
        "encyclopedist": ("Энциклопедист", "Поймайте 50 разных видов в Сафари"),
        "no_half_measures": ("Без полумер", "Поймайте покемона мастерболом в Сафари"),
        "park_legend": ("Легенда парка", "Поймайте легендарного или мифического покемона в Сафари"),
        "snack_time": ("Время перекуса", "Поставьте поке-лакомство в Сафари"),
        "dinner_is_served": ("Кушать подано", "Поймайте покемона, пришедшего полакомиться поке-лакомством"),
        "layer_cake": ("Многоярусный торт", "Поставьте поке-торт в Сафари"),
        "piggy_bank": ("Копилка", "Заполните до краёв свой запас времени Сафари"),
        "to_the_end": ("До самого конца", "Охотьтесь максимально разрешённое время, до последней секунды"),
        "photo_finish": ("На последних секундах", "Поймайте покемона в последние 10 секунд охоты"),
        "nothing_wasted": ("Ничего не пропадает", "Уйдите пораньше через продавца и верните неиспользованное время"),
        "reporting_in": ("Задание выполнено", "Выполните миссию Сафари"),
        "clean_sweep": ("Полный набор", "Выполните все миссии одной ротации"),
        "peaceful_hunter": ("Мирный охотник", "Выполните миссию без битвы"),
        "wild_card": ("Джокер", "Попросите продавца заменить одну из ваших миссий"),
        "nest_egg": ("Заначка", "Заработайте 1000 очков Сафари"),
        "safari_fortune": ("Состояние Сафари", "Заработайте 25 000 очков Сафари"),
        "first_purchase": ("Первая покупка", "Купите что-нибудь за очки Сафари"),
        "fair_trade": ("Бартер", "Обменяйте товары в магазине Сафари"),
        "regular": ("Завсегдатай", "Завершите 10 охот в Сафари"),
        "veteran": ("Ветеран", "Завершите 50 охот в Сафари"),
        "new_deal": ("Новая раздача", "Поохотьтесь на 7 разных картах Сафари"),
        "hands_off": ("Руки прочь!", "Попытайтесь ранить смотрителя Сафари или одного из его покемонов"),
        "fishing/first_bite": ("Клюёт!", "Выловите покемона в Сафари"),
        "fishing/hook_line_and_ball": ("Крючок, леска и бол", "Поймайте покемона, которого вы выловили в Сафари"),
        "fishing/angler": ("Рыболов", "Выловите 10 покемонов в Сафари"),
        "fishing/master_angler": ("Мастер-рыболов", "Выловите 50 покемонов в Сафари"),
        "fishing/tasty_bait": ("Вкусная наживка", "Выловите покемона в Сафари с наживкой на удочке"),
        "fishing/glint_below": ("Блеск под водой", "Выловите шайни-покемона в Сафари"),
        "fishing/the_big_one": ("Крупная рыба", "Выловите одного из особых покемонов дня"),
        "fishing/underground_angler": ("Подземный рыболов", "Выловите покемона в одной из пещер Сафари"),
        "fishing/all_aboard": ("Все на борт!", "Выловите покемона с рыболовной лодки в Сафари"),
    },
    "zh_cn": {
        "root": ("狩猎地带", "获得狩猎地带门票或进入狩猎地带"),
        "enter": ("踏入草丛", "第一次进入狩猎地带"),
        "island_tour": ("环岛之旅", "造访狩猎地带全部十四个栖息地"),
        "caverns": ("地表之下", "抵达狩猎地带两座大洞窟之一"),
        "summit": ("公园之巅", "抵达狩猎地带的山峰或火山"),
        "archaeologist": ("考古学家", "在狩猎地带找到石阵或遗迹，或在那里挖出化石"),
        "habitat": ("自然栖息地", "找到狩猎地带中的一处宝可梦小型栖息地"),
        "first_trip": ("中转", "通过狩猎地带向导或离洞绳前往休息屋"),
        "frequent_traveller": ("常旅客", "前往狩猎地带休息屋25次"),
        "first_catch": ("初次捕获", "在狩猎地带捕捉一只宝可梦"),
        "old_school": ("老派做法", "不经对战，用狩猎球捕捉一只宝可梦"),
        "special_of_the_day": ("今日特别", "捕捉一只今日的特别宝可梦"),
        "ultra_rare": ("千载难逢", "捕捉一只超稀有的特别宝可梦"),
        "wild_shine": ("野性光彩", "在狩猎地带捕捉一只异色宝可梦"),
        "night_owl": ("夜猫子", "在夜晚于狩猎地带捕捉一只宝可梦"),
        "good_haul": ("满载而归", "在一次狩猎中捕捉10只宝可梦"),
        "safari_express": ("狩猎特快", "在一次狩猎中捕捉30只宝可梦"),
        "encyclopedist": ("百科全书", "在狩猎地带捕捉50种不同的宝可梦"),
        "no_half_measures": ("毫不保留", "在狩猎地带用大师球捕捉一只宝可梦"),
        "park_legend": ("公园传说", "在狩猎地带捕捉一只传说或幻之宝可梦"),
        "snack_time": ("点心时间", "在狩猎地带放置一块宝点心"),
        "dinner_is_served": ("开饭啦", "捕捉一只前来吃宝点心的宝可梦"),
        "layer_cake": ("多层蛋糕", "在狩猎地带放置一个宝蛋糕"),
        "piggy_bank": ("存钱罐", "把你的狩猎时间储备存满"),
        "to_the_end": ("坚持到底", "以允许的最长时间狩猎，直到最后一秒"),
        "photo_finish": ("压哨一击", "在狩猎的最后10秒内捕捉一只宝可梦"),
        "nothing_wasted": ("物尽其用", "通过店员提前离开并取回未使用的时间"),
        "reporting_in": ("任务汇报", "完成一项狩猎任务"),
        "clean_sweep": ("全部达成", "完成一轮轮换中的所有任务"),
        "peaceful_hunter": ("和平猎人", "完成一项不经对战的任务"),
        "wild_card": ("万能牌", "请店员替换你的一项任务"),
        "nest_egg": ("小金库", "获得1,000狩猎点数"),
        "safari_fortune": ("狩猎财富", "获得25,000狩猎点数"),
        "first_purchase": ("首次购物", "用狩猎点数购买物品"),
        "fair_trade": ("以物易物", "在狩猎地带商店交换货物"),
        "regular": ("常客", "完成10次狩猎"),
        "veteran": ("老手", "完成50次狩猎"),
        "new_deal": ("重新洗牌", "在7张不同的狩猎地带地图上狩猎"),
        "hands_off": ("别碰！", "试图伤害狩猎地带管理员或他的宝可梦"),
        "fishing/first_bite": ("上钩了！", "在狩猎地带钓到一只宝可梦"),
        "fishing/hook_line_and_ball": ("钓起再捕捉", "捕捉一只你在狩猎地带钓到的宝可梦"),
        "fishing/angler": ("钓鱼人", "在狩猎地带钓到10只宝可梦"),
        "fishing/master_angler": ("钓鱼大师", "在狩猎地带钓到50只宝可梦"),
        "fishing/tasty_bait": ("美味鱼饵", "在钓竿上挂着鱼饵，于狩猎地带钓到一只宝可梦"),
        "fishing/glint_below": ("水下闪光", "在狩猎地带钓到一只异色宝可梦"),
        "fishing/the_big_one": ("大鱼", "钓到一只今日的特别宝可梦"),
        "fishing/underground_angler": ("地下钓客", "在狩猎地带的洞窟中钓到一只宝可梦"),
        "fishing/all_aboard": ("全员登船！", "在狩猎地带的渔船上钓到一只宝可梦"),
    },
}


# ------------------------------------------------------------------ output

def key(name):
    """Translation key of an advancement: its path, dots for slashes."""
    return "advancements." + MOD_ID + "." + name.replace("/", ".")


def advancement_json(name, parent, icon, frame, hidden, criteria, requirements):
    """The JSON object of one advancement."""
    display = {
        "icon": {"id": icon},
        "title": {"translate": key(name) + ".title"},
        "description": {"translate": key(name) + ".description"},
        "frame": frame,
    }
    if parent is None:
        display["background"] = BACKGROUND
        # The root is a gateway, not a feat: no toast, no chat line.
        display["show_toast"] = False
        display["announce_to_chat"] = False
    if hidden:
        display["hidden"] = True
    result = {}
    if parent is not None:
        result["parent"] = MOD_ID + ":safari/" + parent
    result["display"] = display
    result["criteria"] = criteria
    if requirements is not None:
        result["requirements"] = requirements
    result["sends_telemetry_event"] = False
    return result


def check():
    """Fails on a dangling parent, a requirement naming no criterion, or a missing translation."""
    errors = []
    for name, (parent, _icon, frame, _hidden, criteria, requirements) in ADVANCEMENTS.items():
        if parent is not None and parent not in ADVANCEMENTS:
            errors.append(f"{name}: unknown parent {parent}")
        if frame not in ("task", "goal", "challenge"):
            errors.append(f"{name}: bad frame {frame}")
        for group in requirements or []:
            for criterion in group:
                if criterion not in criteria:
                    errors.append(f"{name}: requirement names unknown criterion {criterion}")
    for language in LANGUAGES:
        texts = TEXT.get(language, {})
        for name in ADVANCEMENTS:
            if name not in texts:
                errors.append(f"{language}: missing {name}")
        for name in texts:
            if name not in ADVANCEMENTS:
                errors.append(f"{language}: translation of unknown advancement {name}")
        if language not in FISHED_STAT:
            errors.append(f"{language}: missing the pokemon_fished statistic")
    if errors:
        sys.exit("\n".join(errors))


def write_advancements():
    """Rewrites the safari/ advancement folder from the table."""
    if os.path.isdir(ADVANCEMENT_DIR):
        shutil.rmtree(ADVANCEMENT_DIR)
    for name, spec in ADVANCEMENTS.items():
        path = os.path.join(ADVANCEMENT_DIR, *name.split("/")) + ".json"
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8") as out:
            json.dump(advancement_json(name, *spec), out, indent=2, ensure_ascii=False)
            out.write("\n")


def write_translations():
    """Sets the advancement keys in every language file, keeping every other key where it is."""
    for language in LANGUAGES:
        path = os.path.join(LANG_DIR, language + ".json")
        with open(path, encoding="utf-8") as source:
            entries = json.load(source)
        # Drop keys of advancements no longer in the table, then (re)write the current ones.
        prefix = "advancements." + MOD_ID + "."
        entries = {k: v for k, v in entries.items() if not k.startswith(prefix)}
        entries = with_after(entries, "stat." + MOD_ID + ".rest_house_trips",
                             "stat." + MOD_ID + ".pokemon_fished", FISHED_STAT[language])
        for name in ADVANCEMENTS:
            title, description = TEXT[language][name]
            entries[key(name) + ".title"] = title
            entries[key(name) + ".description"] = description
        with open(path, "w", encoding="utf-8") as out:
            json.dump(entries, out, indent=2, ensure_ascii=False)
            out.write("\n")


def with_after(entries, anchor, new_key, value):
    """Sets a key, placing it right after the anchor key when it is new (at the end without the anchor)."""
    if new_key in entries or anchor not in entries:
        entries[new_key] = value
        return entries
    result = {}
    for k, v in entries.items():
        result[k] = v
        if k == anchor:
            result[new_key] = value
    return result


def main():
    check()
    write_advancements()
    write_translations()
    print(f"{len(ADVANCEMENTS)} advancements, {len(LANGUAGES)} languages")


if __name__ == "__main__":
    main()
