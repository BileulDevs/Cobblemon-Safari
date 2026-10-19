# Cobblemon Safari examples

One example for each thing a server owner, a pack maker or a player can change without touching the code.

| Folder | What it is | Where it goes |
|---|---|---|
| `config/` | The three configuration files, with a page documenting every key | `config/` of the game or the server |
| `safari-example-datapack/` | Data overrides: spawns, NPCs, dialogue, gates, ruins | `<world>/datapacks/` |
| `safari-example-resourcepack/` | Asset overrides: texts, textures, sounds | `resourcepacks/` of the game |

Each example changes something you can see in game, so you can tell it works. Copy only the files you need: a pack holding one file works as well as a full one.

## How overriding works

A datapack or a resource pack replaces a file of the mod when it holds a file **at the same path**. The whole file is replaced: there is no merging, so copy the mod's file and edit the copy. The mod's own files are in its jar, under `data/` and `assets/`.

There are three exceptions:
- **Tags** (`tags/.../*.json`) are merged when they say `"replace": false`, and replaced with `"replace": true`.
- **Translations** (`lang/*.json`) are merged key by key: a pack only needs the keys it changes.
- **Sounds** (`sounds.json`) are merged event by event: an event with `"replace": true` replaces the mod's list for that event, without it the pack's sounds are added to the mod's.

## Configuration (`config/`)

`config/cobblemon_safari/server.json`, `shop.json` and `client.json`. See [`config/README.md`](config/README.md): what the example files change, then every key with its default.

## Datapack (`safari-example-datapack/`)

Install: copy the folder into `<world>/datapacks/` (in single player, `saves/<world>/datapacks/`), then restart the world. World generation (gates, ruins) is read when the world starts and only changes chunks generated afterwards.

| File | Example | Effect |
|---|---|---|
| `data/cobblemon/spawn_pool_world/cobblemon_safari_example.json` | Kanto Safari Zone Pokémon (Nidoran, Doduo, Kangaskhan, Tauros...) | Adds Cobblemon spawns to the Safari only, through its biome tags: `"biomes": ["#cobblemon_safari:safari_grassland"]`. The tags are listed in the mod's README (Biome tags). |
| `data/cobblemon_safari/dialogues/safari_clerk.json` | Two more answers: "Which Pokémon are special today?" (`q.player.safari_today()`) and "How many Safari points do I have?" (`q.player.safari_status()`: time left, banked time and points) | The rest house clerk's dialogue. Answers call the mod's MoLang functions of the player: `safari_shop`, `safari_bounties`, `safari_gate`, `safari_leave`, `safari_status`, `safari_today`, `safari_balls` (any MoLang works too, `q.player.run_command(...)` included). A text is a translation key; plain text works too. The keys of this example are in the resource pack. |
| `data/cobblemon_safari/npcs/safari_warden.json` | `horizontal_wander_range` 8 instead of 4 | The gate warden: hitbox, name, dialogue, behaviours. `safari_clerk.json` beside it is the clerk. |
| `data/cobblemon_safari/tags/worldgen/biome/has_structure/safari_gate.json` | Adds taiga, cherry grove and `#c:is_forest`, `"replace": false` | Biomes where Safari Gates may generate. `"replace": true` with an empty `values` list turns the gates off. |
| `data/cobblemon_safari/worldgen/structure_set/safari_gate.json` | `spacing` 32, `separation` 12 (default 48 and 20) | How often gates generate, in chunks: lower is more frequent. Leave the `salt` as it is. |
| `data/cobblemon_safari/tags/worldgen/biome/safari_structures/tower_lush.json` | Adds the marsh, `"replace": false` | Safari biomes where each ruin or tower may stand, one tag per structure: `ruin_frozen_altar`, `ruin_moon_henge`, `ruin_overgrown_trial`, `ruin_shaded`, `ruin_stone_henge`, `ruin_sun_henge`, `ruin_toppled_pillars`, `ruin_town_center`, `tower_deserted`, `tower_frozen`, `tower_lush`, `tower_rooted`, `tower_sunscorched`, `tower_temperate`. Seen on the next daily map. |

### Structures (no example file)

Both buildings are structure templates a datapack can replace. Save the new one with a structure block, then put the `.nbt` file at the same path in the datapack.

- **Rest house**, `data/cobblemon_safari/structure/safari_pokecenter.nbt`.
  - Keep its size, 20 × 11 × 19, and its door on the west side, centred (column 9). The clerk's spot (behind the counter, at x 17, z 9) and the arrival in front of the door (x 0, z 9) are computed from them; a template of another size logs a warning and puts them in the wrong place.
  - The bottom layer is the floor and the ground around it: it replaces the terrain.
  - Use only blocks of the installed Minecraft and Cobblemon: an unknown block becomes air.
  - The fossil machine is placed as decoration, and disc shelves can never be emptied in the Safari.
  - Seen after the next map reset (`/safari reset`).
- **Safari Gate**, `data/cobblemon_safari/structure/safari_gate.nbt`.
  - 27 × 11 × 27; the bottom layer is the ground.
  - Its gate blocks need `keeps_warden:1b` (warden) and `clear_trees:1b` (cut tree crowns removed) in their block entity. The mod's README (Safari Gates) explains how to add them.

## Resource pack (`safari-example-resourcepack/`)

Install: copy the folder into `resourcepacks/`, then enable it in Options → Resource Packs, above the mod.

| File | Example | Effect |
|---|---|---|
| `assets/cobblemon_safari/lang/en_us.json`, `fr_fr.json` | The clerk is called "Nina, Safari guide" and greets differently; the balls tab is "Balls"; keys for the datapack's dialogue answers and the banner picture | Any text of the mod. Only the changed keys are needed. The mod's keys are in its jar, `assets/cobblemon_safari/lang/` (12 languages). |
| `assets/cobblemon_safari/textures/pokemon_sprites/tauros_shiny.png` | A golden Tauros | Sprites of the wall screens (with `board.useSprites` on). Named `<species>[_<form>][_shiny].png`, 64 × 64: a pack can replace one (`tauros.png`) or add a form or a shiny one, as here. |
| `assets/cobblemon_safari/textures/gui/board_background_morning.png` | A faded morning landscape | Backgrounds of the screens, 384 × 192: `board_`, `missions_`, `record_` and `shop_background_`, each in `morning`, `dusk` and `night`. The timer's icon is `safari_timer.png` beside them, 16 × 16. |
| `assets/cobblemon_safari/textures/item/safari_ticket.png` | A blue ticket | Item textures: `safari_ticket`, `safari_escape_rope`, `safari_banner`, `coat_rack`. Block textures are in `textures/block/`. |
| `assets/cobblemon_safari/textures/npcs/safari_clerk.png` | The clerk in other colours | NPC skins, 64 × 64 like a player skin: `safari_clerk`, `safari_warden`. To give one another model, override `bedrock/npcs/variations/<npc>.json`. |
| `assets/example_safari/textures/block/banner/example_coast.png` | A new banner picture, "Coast" | Safari Banner pictures: any texture under `textures/block/banner/`, in any namespace, 1:2 (32 × 64 here). Its name comes from the key `cobblemon_safari.banner_design.<namespace>.<file>`. A `.png.mcmeta` beside it animates it: see the mod's animated ones (`river_waves`, `fireflies`...). |
| `assets/cobblemon_safari/sounds.json` | `music.center` plays Minecraft's "Moog City 2"; `ambient.badlands` keeps two of its five cries | Music and ambient sounds. Music events: `music.plains`, `music.forest`, `music.arid`, `music.snow`, `music.caves`, `music.center` (inside rest houses). Each biome has an `ambient.<biome>` event. |

To use your own music, add the file to the pack, for example `assets/example_safari/sounds/music/center.ogg` (Ogg Vorbis), and point the event at it: `"name": "example_safari:music/center", "stream": true`. Or replace `assets/cobblemon_safari/sounds/music/center.ogg` directly, with no `sounds.json`.

## Without a file

- **Ticket length**: a ticket can carry its own duration, in seconds: `/give @p cobblemon_safari:safari_ticket[custom_data={duration_seconds:600}]`.
- **Gate warden on a hand-built gate**: `/data merge block <x> <y> <z> {keeps_warden:1b}` on one of its gate blocks.
