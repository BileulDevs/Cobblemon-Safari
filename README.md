# Cobblemon Safari

A Safari Zone for Cobblemon (Minecraft 1.21.1, Fabric + NeoForge): a procedural park dimension, timed hunts, and a configurable Poké Ball allow-list enforced server-side.

## Layout

| Module | Role |
|---|---|
| `common` | All gameplay logic, payloads, HUD, worldgen data |
| `fabric` / `neoforge` | Thin bridges from loader events to `SafariServerEvents` |
| `tools/worldgen` | Generator of every worldgen JSON file (do not hand-edit its output) |
| `example/safari-spawns-datapack` | Example Cobblemon spawns targeting the Safari tags |

## Worldgen

The Safari is a single island (shape radius 512 blocks, land to about 435, centred on 0,0) in an endless turquoise sea, enclosed by its own square world border. A summit sits at the centre, ringed by rocky hills and a volcano. Around it, 14 habitats are laid out as sectors from the rocky ring to the coast, each with a whole sector of its own: grassland (north, where players arrive), savanna, dunes, badlands, jungle, marsh, mushroom grove, flower meadow, woodland, deep forest, taiga, snowfield, glacier, rocky hills. Caves (and lush caves in the south) run underneath.

The layout relies on one Java density function type, `cobblemon_safari:island_coordinate`, which exposes distance and direction from the island centre; everything else is data. Sector borders are wobbled by noise (`CLIMATE_NOISE`), so no two maps look alike and no border is a straight line, while the smallest sector of any map keeps about three quarters of its nominal size.

The sky palette (turquoise zenith, golden horizon, coral sunsets, low clouds) comes from biome colours plus `SafariSkyEffects` on the client.

Run `python3 tools/worldgen/generate_worldgen.py` after changing biomes, tags, island size or terrain tuning. It prints the `park` values the server config must use. It only rewrites the folders it owns; the Safari Gate's structure files are left alone.

## Protection

Inside the Safari, players take no damage (except /kill and the void), cannot change the terrain (breaking, placing, buckets, block-transforming tools), and fire never spreads. Creative and spectator players are exempt from the terrain rules when `exemptCreativeAndSpectator` is on. Each rule has a switch under `protection` in the server config.

## Biome tags (spawn contract)

Habitat tags: `safari_plains`, `safari_savanna`, `safari_desert`, `safari_badlands`, `safari_jungle`, `safari_swamp`, `safari_meadow`, `safari_forest`, `safari_deep_forest`, `safari_taiga`, `safari_snow`, `safari_ice`, `safari_rocky`, `safari_mushroom`, `safari_mountain`, `safari_volcanic`, `safari_lake`, `safari_river`, `safari_beach`, `safari_lush_cave`.
Aggregates: `safari_cave`, `safari_grassland`, `safari_wooded`, `safari_water`, `safari_wetland`, `safari_arid`, `safari_frozen`, `safari_highland`, `is_safari`.

Use them in Cobblemon spawn files as `"biomes": ["#cobblemon_safari:safari_forest"]` to add Pokémon to the park only.

Each Safari biome is also bridged into the matching Cobblemon habitat tags (`data/cobblemon/tags/worldgen/biome`, generated from `COBBLEMON_TAG_BRIDGE`), so Cobblemon's natural spawns and spawn datapacks populate the park on their own. Catch-all tags such as `#cobblemon:is_overworld` are left out on purpose so habitats stay distinct. `spawns` in the config filters what comes in: legendary, mythical, ultra beast and paradox Pokémon are refused by default, excluded species never appear, and a same-species cap keeps one Pokémon from flooding a habitat.

## Special Pokémon of the day

Every rotation (4 h by default, `featured.rotationMinutes`, aligned on midnight UTC: 0, 4, 8, 12, 16 and 20 h UTC), the mod draws a few uncommon, rare and ultra-rare species from what is actually installed: Cobblemon's spawn data decides each species' habitat and rarity, and species without a natural spawn get a habitat from their types. The draw is deterministic per world and rotation, mixes rarities, and prefers different habitats. The mod spawns these specials itself, near players and only in their habitat, with caps so none takes over. The list is shown by the Safari Board screens, by `/safari today`, and on entry.

### Safari Board screens

`cobblemon_safari:safari_board` is a display panel. Boards placed side by side, facing the same way, merge into one screen (rectangles only, up to 16 × 9 blocks), like a departure board in a station. The screen shows the current specials with their animated Cobblemon models, rarity and habitat (scrolling when too long), and a progress bar: a viewer with a session sees their own hunting time drain, anyone else sees the time until the next rotation. Specials are split over pages when they do not fit. Everything is drawn client side from data the server pushes (`SafariFeaturedPayload`), so a board works anywhere, in any dimension, without configuration. Behind the panels, the screen shows its own pixel-art landscape (a watering hole at the foot of the volcano, `textures/gui/board_background_<light>.png`, drawn by `tools/gui_assets.py`) in the light of the time of day in game (morning, dusk or night), dimmed and cropped around the horizon; `board.backgroundImage` turns it off (plain `board.background` colour) and `board.panelOpacity` (0 to 1, default 0.72) sets how much the title band, cells and bars hide it. Every colour of the screen can be changed in `config/cobblemon_safari-client.json` (`board` section, `#RRGGBB` or `#AARRGGBB`; a colour with its own alpha keeps it over the landscape). The rotation length is `featured.rotationMinutes` in `config/cobblemon_safari.json`.

`cobblemon_safari:safari_board_frame` dresses a screen up as a mission board. Place frames around the screens: each one shapes itself from its neighbours in the wall plane, like a fence. Beams join neighbouring frames and reach down to solid ground (legs), and planks fill the inside between frames and screens, flush with the screen. When a whole side of a screen is framed, the picture spreads over those planks, leaving a thin band of wood. The bottom block of a leg standing on the ground gets splayed feet. Right-clicking a frame with vanilla planks gives that wood to the planks of the whole board (the planks are not used up). The frame models and blockstate are generated by `tools/board_frame_models.py`. A frame takes the facing of the screen or frame it is placed against.

## Rest houses

11 × 11 Pokémon Centers built once per map near the positions listed in `restHouses.positions`, door toward the island centre (`structure/RestHouseBuilder`). Each position is an anchor: `structure/RestHouseSites` reads the terrain the generator will produce (without generating chunks) on a grid around it and builds at the flattest dry spot inside the park, at least 64 blocks from the other houses and clear of the ruin and tower spots; the entrance stays within 32 blocks of its anchor, the others within 64. Where each house went is saved in the world state. Inside: a Cobblemon Healing Machine on the counter that never runs out of charge, a Cobblemon PC, a cooking corner with a Poké Pot on a Cobblemon campfire, and seats. Outside: a stone plaza with lanterns and flower beds. The first house is the entrance: a framed 3 × 2 Safari Board stands on its plaza. Houses are built when a map is generated, so a layout change shows after the next map reset. Spawns and arrivals keep clear of the whole footprint, plaza included (`RestHouses.FOOTPRINT_REACH`).

In the Safari, right-clicking a Cobblemon station (PC, Healing Machine, campfire) always works whatever the player holds, and a crouching click on a campfire is refused so the Poké Pot stays.

## Ruins and towers

Every map has Cobblemon ruins and Gimmighoul towers, through the Safari's own structures (`worldgen/structure/safari_ruins/`, written by `tools/worldgen/generate_worldgen.py`, `SAFARI_STRUCTURES`) that reuse Cobblemon's template pools: towers in six looks (temperate, lush, rooted, deserted, sunscorched, frozen) and ruins standing above ground (henges, toppled pillars, shaded ruins, a desert town centre, a frozen altar, an overgrown trial), each limited to the habitats it suits. Two structure sets spread them: a tower candidate every 320 blocks and a ruin candidate every 224 blocks, so a map has several of each in practice. They are generated with the terrain, so they blend in like in the overworld, and change with the daily map.

## Ambience

The Safari has its own music: six original tracks composed and rendered by `tools/music/compose_safari_music.py` (pure synthesis, no samples, deterministic) into `assets/cobblemon_safari/sounds/music/`. Each biome plays the track of its zone (`HABITAT_SOUNDS` in `tools/worldgen/generate_worldgen.py`): `plains` (grassland, savanna, flower meadow, water), `forest` (woodland, deep forest, taiga, jungle, marsh, mushroom grove), `arid` (dunes, badlands, rocky hills, volcanic), `snow` (snowfield, glacier, peaks) and `caves`. Vanilla music is never heard in the Safari. Tracks are 1 to 3 minutes apart, and crossing into another zone lets the current track finish.

Inside a rest house the `center` theme starts at once (`client/music/SafariMusic`, through `MinecraftMusicMixin` on `Minecraft.getSituationalMusic`); it loops while the player stays inside. The client knows where the houses are from the rules payload. Creative players hear the zone music too, instead of vanilla's creative music.

The ambient sound of each biome is the cry of a Pokémon typical of the habitat, a few species per biome, played quietly at random (`additions_sound`, chance 0.004 per tick, about once every 12 seconds). These `ambient.<biome>` events point at Cobblemon's own cry files.

To change the music: edit `THEMES` in the composer, run it, then run `generate_worldgen.py` (which checks every zone has its track and rewrites `sounds.json` and the biomes).

## Protection

Inside the Safari, by default: players take no damage (except /kill and the void), cannot break or place blocks or use block-changing tools, fire never spreads, and explosions destroy no block. Creative and spectator players are exempt from the terrain rules. Blocks listed in `protection.placeableBlocks` (by default Cobblemon's Poké Cake and Poké Snack) can still be placed and picked up. Breaking a protected block is refused only when it would break, so mining shows its cracks and the warning appears once. Each rule can be switched off in the `protection` section of the config.

## Safari Game loop

- **Entry**: always paid with banked Safari time, which only tickets (or `/safari credit add`) provide: walk into a Safari Gate, talk to its warden, `/safari start`, `/safari enter` (op), or any custom access method through `SafariAccess`. A ticket can carry its own duration: `/give @p cobblemon_safari:safari_ticket[custom_data={duration_seconds:600}]`. Used inside, a ticket extends the running session.
- **Loaned Safari Balls**: 30 by default (`loan.count`), handed out on entry and taken back on exit. They only work inside the Safari and are reclaimed from any player found outside with one.
- **HUD**: time left, loaned balls left, Pokémon caught.
- **Recap** at the end of each game, and lifetime records: `/safari stats [player]`, `/safari top`.

## Extension points

- `SafariAccess.requestEntry(...)` + `EntryRequirement`: tickets, fees, NPC gates.
- `SafariSessionManager.addListener(...)`: start, time change, catch and end events (the built-in statistics use it).
- `SafariSessionManager.addTime / setTime`: time items.

## Commands

`/safari enter [targets]` (op, spends the targets' banked time), `/safari enter <targets> <seconds>` (op, explicit length for testing), `/safari gate`, `/safari exit <targets>`, `/safari leave`, `/safari time`, `/safari time add|remove|set <targets> <seconds>`, `/safari reload`, `/safari stats [player]`, `/safari top`, `/safari today`.

## Safari time, tickets and capture

Safari Tickets (plain, 15 min, 30 min, 1 h) bank Safari time when used outside the park, up to `ticket.maxCreditSeconds`; used inside, they extend the hunt. `/safari start` enters by spending the bank (one session at most, the rest stays banked; disable with `ticket.allowStartCommand` once a gate exists), `/safari credit` shows it and `/safari credit add <players> <seconds>` (op) fills it. Leaving early with `/safari leave` returns the unused time of a hunt paid with the bank (`ticket.refundUnusedTimeOnLeave`).

Out of battle, inside the Safari, a Safari Ball multiplies the species catch rate by `capture.safariBallOutOfBattleMultiplier` (3 by default), which offsets Cobblemon halving every capture made outside a battle.

While a player is in the Safari, Cobblemon's spawner around them runs `spawns.spawnRateMultiplier` times faster and allows `spawns.pokemonPerChunkMultiplier` times more Pokémon per chunk (2 and 2 by default).

## Safari Gates

Rare overworld structure (`cobblemon_safari:safari_gate`) in plains, meadows, light forests and savannas: a savanna lodge in a clearing: the shimmering pane between two acacia and mud-brick towers under a thatched roof, a 3 × 2 Safari Board on each face, a palisade along the gate, an avenue lined with lanterns and flower beds, and around the plaza the warden's ticket hut, a campfire, a water trough and an acacia; the Safari Warden and his Pokémon roam the plaza. `/locate structure cobblemon_safari:safari_gate` finds the nearest one. It only generates on dry, fairly even ground: the structure uses the mod's `cobblemon_safari:dry_jigsaw` type (`worldgen/DryJigsawStructure`), a jigsaw structure that reads the terrain from the noise around its centre (`footprint_radius`) and skips spots with water or more than `max_relief` blocks of relief. The Safari's ruins and towers use it too.

- **Entry**: works like a nether portal (vanilla `Portal` mechanism, same delay game rules, amber overlay; pane texture drawn by `tools/portal_textures.py`). Instead of the nether portal's hum and whoosh, a gate plays a relaxed looping tune heard within 16 blocks (`client/gate/GateAmbience`, Jukebox/Note Blocks volume slider) and a soft chime when a player steps in (`LocalPlayerPortalSoundMixin`); both are composed by `tools/music/compose_safari_music.py gate`: when the countdown completes, banked time is spent and the player goes in; without time nothing happens but a message on the action bar. The player is moved a step out of the pane just before entering, so the return point is not inside it. The warden's dialogue does the same through `/safari gate`, which only works within 10 blocks of a gate. Turn entry off with `gate.allowEntry`.
- **Warden**: a Cobblemon NPC (`data/cobblemon_safari/npcs/safari_warden.json`, dialogue `data/cobblemon_safari/dialogues/safari_warden.json`, skin `assets/cobblemon_safari/textures/npcs/safari_warden.png`, source `tools/assets/safari_warden_skin.png`). Wanders within `gate.roamRadius` of the gate, never despawns. He and his Pokémon take no damage from anything but `/kill` and the void. `gate.spawnWarden` turns him off.
- **Roaming Pokémon**: one per entry of `gate.companions` (Cobblemon properties), within `gate.roamRadius` blocks. Uncatchable, invulnerable, battles against them are cancelled. They despawn when nobody is around and come back when a player is within 48 blocks.
- **Frequency**: `data/cobblemon_safari/worldgen/structure_set/safari_gate.json` (spacing 48 chunks, separation 20). Override it in a datapack to change it, or point the biome tag `has_structure/safari_gate` to nothing to disable the structure. Only new chunks are affected.
- **Operators** can build their own gate with the creative Safari Gate block (unbreakable in survival): residents settle at any gate up to 8 blocks wide.
- The template (27 × 27) is generated by `tools/safari_gate_assets.py`, together with the warden's skin. Within `gate.roamRadius` (8) of the gate and off its plane, nothing tall and solid stands (roof fringes are leaves), so the Pokémon never appear on a roof; the outbuildings stand beyond it.
- **Logging in** inside the Safari without a session sends the player out, creative and spectator included (the `exemptCreativeAndSpectator` exemption only applies to players already connected).

## Safari points and the shop

Every capture inside the Safari earns Safari points (`points` section: by the species' rarity in the Safari, plus a bonus for today's specials and for shinies), a currency separate from Safari time. Points always show with their logo, a gold coin glyph of the mod's font (`assets/cobblemon_safari/font/icons.json`, `points/SafariPointsIcon`), in chat, the shop and on the board.

Two shopkeepers open the shop: the Safari Warden at every Safari Gate (he holds a Safari Ball) and the Safari Clerk behind the counter of every rest house (NPC class `safari_clerk`, put back automatically when a loaded house has none, `structure/RestHouseClerk`). Their dialogue opens a prize-counter screen over a pixel-art safari landscape that follows the time of day in game: morning, dusk or night (`textures/gui/shop_background_<scene>.png`, drawn by `tools/gui_assets.py`), each with a trainer and original creatures of the mod: tabs (by default Pokémon, Items, Poké Balls), then cards with gently swaying animated Cobblemon models or enlarged items, 8 per page.

`config/cobblemon_safari_shop.json` (version 2) lists `tabs` (`id`, optional `name` shown as is, `icon` item id; built-in ids `pokemon`, `items`, `balls` have translated names) and `entries`: items (`"type": "item"`, `item`, `count`) and Pokémon (`"type": "pokemon"`, Cobblemon properties in `pokemon`), each with a `price` in points and a `tab`; fields of the other type are left out of the file. An entry without a valid tab goes to the one matching its type (ids ending in `_ball` go to `balls`); a tab without entries is hidden. Select a card, then press Buy; the server re-checks everything (points, being at a gate or in a rest house, unchanged price); a Pokémon goes to the party, or the PC when it is full. `/safari shop` opens it at a gate or in a rest house (anywhere for operators), `/safari points` shows the balance, `/safari points add <players> <amount>` (op) gives points. `/safari reload` re-reads the shop file.

## Bounties (missions of the rotation)

With each rotation of the specials, `bounties.count` missions are drawn (deterministically, from the rotation and the world's salt, so they survive restarts): catch one of the current specials, catch N Pokémon of a type, catch a given species (two of a common one, one of an uncommon one). They are the same for every player and drawn only from species that spawn in the Safari as common or uncommon, so each can be done within one rotation. Each pays its reward in Safari points once per player, plus `bounties.allDoneBonus` when all are done. The Safari Board alternates between the specials and the missions (with the viewer's own progress); `/safari bounties` lists them in chat. Progress is saved per player and per rotation (`data/cobblemon_safari_bounties.dat`): unfinished missions are lost when the rotation changes.

## Escape Rope

Lent on entry (`escapeRope.enabled`) and taken back on exit, never worn out. Used in a cave of the Safari (no sky above), it brings the player to the surface right above; it does nothing in the open air, during a battle or outside the Safari. Any rope found outside the Safari is removed.

## Daily map reset

At the first server start of each new day (`reset.hour`, `reset.timeZone`), the Safari dimension folder is deleted before the levels load and regenerated with a new terrain seed (a salt mixed into the world seed for that dimension only, `ChunkMapMixin`). Rest houses are rebuilt on the new map; players who logged out inside are moved to the arrival point. Sessions, statistics and banked time are kept. State: `<world>/cobblemon_safari_map.json`. `/safari reset` (op) forces a reset at the next start. Turn it off with `reset.enabled`.
