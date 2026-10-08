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

The Safari is a single island (shape radius 512 blocks, land to about 435, centred on 0,0) in an endless turquoise sea, enclosed by its own square world border. A summit sits at the centre, ringed by rocky hills and a volcano. Around it, 13 habitats are laid out as sectors from the rocky ring to the coast: grassland (north, where players arrive), savanna, dunes, badlands, jungle, marsh, flower meadow, woodland, deep forest, taiga, snowfield, glacier, rocky hills. Coastal lowlands turn into marsh or a mushroom grove in some sectors; caves (and lush caves in the south) run underneath.

The layout relies on one Java density function type, `cobblemon_safari:island_coordinate`, which exposes distance and direction from the island centre; everything else is data. Sector borders are wobbled by noise, so no two worlds look alike and no border is a straight line.

The sky palette (turquoise zenith, golden horizon, coral sunsets, low clouds) comes from biome colours plus `SafariSkyEffects` on the client.

Run `python3 tools/worldgen/generate_worldgen.py` after changing biomes, tags, island size or terrain tuning. It prints the `park` values the server config must use.

## Protection

Inside the Safari, players take no damage (except /kill and the void), cannot change the terrain (breaking, placing, buckets, block-transforming tools), and fire never spreads. Creative and spectator players are exempt from the terrain rules when `exemptCreativeAndSpectator` is on. Each rule has a switch under `protection` in the server config.

## Biome tags (spawn contract)

Habitat tags: `safari_plains`, `safari_savanna`, `safari_desert`, `safari_badlands`, `safari_jungle`, `safari_swamp`, `safari_meadow`, `safari_forest`, `safari_deep_forest`, `safari_taiga`, `safari_snow`, `safari_ice`, `safari_rocky`, `safari_mushroom`, `safari_mountain`, `safari_volcanic`, `safari_lake`, `safari_river`, `safari_beach`, `safari_lush_cave`.
Aggregates: `safari_cave`, `safari_grassland`, `safari_wooded`, `safari_water`, `safari_wetland`, `safari_arid`, `safari_frozen`, `safari_highland`, `is_safari`.

Use them in Cobblemon spawn files as `"biomes": ["#cobblemon_safari:safari_forest"]` to add Pokémon to the park only.

Each Safari biome is also bridged into the matching Cobblemon habitat tags (`data/cobblemon/tags/worldgen/biome`, generated from `COBBLEMON_TAG_BRIDGE`), so Cobblemon's natural spawns and spawn datapacks populate the park on their own. Catch-all tags such as `#cobblemon:is_overworld` are left out on purpose so habitats stay distinct. `spawns` in the config filters what comes in: legendary, mythical, ultra beast and paradox Pokémon are refused by default, excluded species never appear, and a same-species cap keeps one Pokémon from flooding a habitat.

## Special Pokémon of the day

Every rotation (24 h by default, aligned on midnight UTC), the mod draws a few uncommon, rare and ultra-rare species from what is actually installed: Cobblemon's spawn data decides each species' habitat and rarity, and species without a natural spawn get a habitat from their types. The draw is deterministic per world and rotation, mixes rarities, and prefers different habitats. The mod spawns these specials itself, near players and only in their habitat, with caps so none takes over. The list is shown by the Safari Board screens, by `/safari today`, and on entry.

### Safari Board screens

`cobblemon_safari:safari_board` is a display panel. Boards placed side by side, facing the same way, merge into one screen (rectangles only, up to 16 × 9 blocks), like a departure board in a station. The screen shows the specials of the day with their animated Cobblemon models, a scrolling ticker with habitats and levels, and a progress bar: a viewer with a session sees their own hunting time drain, anyone else sees the time until the next rotation. Specials are split over pages when they do not fit. Everything is drawn client side from data the server pushes (`SafariFeaturedPayload`), so a board works anywhere, in any dimension, without configuration.

## Rest houses

Small Pokémon Centers with a Cobblemon Healing Machine that never runs out of charge, built once per world at the positions listed in `restHouses.positions`. The first one is the entrance and holds a 2 × 2 Safari Board screen.

## Protection

Inside the Safari, by default: players take no damage (except /kill and the void), cannot break or place blocks or use block-changing tools, fire never spreads, and explosions destroy no block. Creative and spectator players are exempt from the terrain rules. Each rule can be switched off in the `protection` section of the config.

## Safari Game loop

- **Entry**: a Safari Ticket (`cobblemon_safari:safari_ticket`, creative tab Tools & Utilities), `/safari enter`, or any custom access method through `SafariAccess`. A ticket can carry its own duration: `/give @p cobblemon_safari:safari_ticket[custom_data={duration_seconds:600}]`. Used inside, a ticket extends the running session.
- **Loaned Safari Balls**: 30 by default (`loan.count`), handed out on entry and taken back on exit. They only work inside the Safari and are reclaimed from any player found outside with one.
- **HUD**: time left, loaned balls left, Pokémon caught.
- **Recap** at the end of each game, and lifetime records: `/safari stats [player]`, `/safari top`.

## Extension points

- `SafariAccess.requestEntry(...)` + `EntryRequirement`: tickets, fees, NPC gates.
- `SafariSessionManager.addListener(...)`: start, time change, catch and end events (the built-in statistics use it).
- `SafariSessionManager.addTime / setTime`: time items.

## Commands

`/safari enter [targets] [seconds]`, `/safari exit <targets>`, `/safari leave`, `/safari time`, `/safari time add|remove|set <targets> <seconds>`, `/safari reload`, `/safari stats [player]`, `/safari top`, `/safari today`.
