# Example configuration files

Copy `cobblemon_safari/` into the `config/` folder of the game (client) or of the server, then restart. `/safari reload` also re-reads `server.json` and `shop.json`; `client.json` is read when the game starts.

The three files are complete and valid: every key is present with its value. Most values are the defaults; the ones below were changed to show what each kind of setting does. A key left out of a file takes its default value, and the mod writes it back the next time it saves the file.

JSON has no comments, so this page says what the example files change. [CONFIG.md](../../CONFIG.md) explains every key.

## What the example files change

### `server.json`

| Key | Example | Default | Effect |
|---|---|---|---|
| `maxDurationSeconds` | `3600` | `7200` | Hunts last one hour at most. |
| `allowedBalls` | adds `cobblemon:premier_ball` | Safari and Master Balls | Premier Balls work inside the park too. |
| `exitDestination` | `"WORLD_SPAWN"` | `"RETURN_POINT"` | Players leaving the Safari arrive at the world spawn (for a hub server) instead of where they entered. The only two values. |
| `protection.placeableBlocks` | adds `minecraft:torch` | Poké Cake, Poké Snack | Players may place torches in caves (they vanish with the daily map). |
| `loan.count` | `30` | `0` | The games' loan: 30 Safari Balls lent on entry, taken back on exit. |
| `reset.timeZone` | `"Europe/Paris"` | `""` | The new map starts at 4:00 Paris time, whatever the server's clock. |
| `ticketLoot.sources` | adds `minecraft:chests/shipwreck_treasure` at `0.2` | 8 sources | Shipwreck treasure chests may hold a ticket. A folder ending in `/` (`cobblemon:ruins/common/`) covers every table under it. |
| `gate.companions` | adds `scyther level=30 shiny=yes` | Kangaskhan, Tauros, Chansey | A shiny Scyther roams around each gate. Any Cobblemon properties work. |
| `bounties.count` | `4` | `3` | Four missions per rotation. |
| `spawns.legendary.allowSpawn` | `true` | `false` | Legendaries can appear, at most one of each species per rotation (`maxSpawnPerPokemon`). |
| `spawns.excludedSpecies` | `["cobblemon:ditto"]` | `[]` | Ditto never appears in the Safari. |
| `featured.rotationMinutes` | `1440` | `240` | One set of specials, missions and shop Pokémon per day instead of every four hours. |

### `shop.json`

| Key | Example | Effect |
|---|---|---|
| `tabs` | adds `{"id": "rewards", "name": "Ranger rewards", "icon": "minecraft:emerald"}` | A sixth tab with its own name. A built-in tab takes its name from the translation key `cobblemon_safari.shop.tab.<id>` instead. |
| `entries` | `eevee level=10` with `"price": "auto"` | A fixed Pokémon priced like the random ones: its rarity in the Safari times its evolution stage. |
| `entries` | `dratini level=15 shiny=yes`, 3000 points, `"limitPerRotation": 1`, tab `rewards` | A shiny Dratini, once per player and per rotation. |
| `entries` | `cobblemon:master_ball`, 500 points plus a `cost` of 4 items of `#cobblemon:evolution_stones` and 2 diamonds, `"limitPerRotation": 1`, `"cooldownHours": 168` | A trade: points and items together, at most once a week per player. A cost takes an item id or a `#tag`. |
| `rotating.count`, `rotating.excludedSpecies` | `12`, `["cobblemon:ditto"]` | 12 random Pokémon per rotation, never Ditto. |
| `rotatingItems.always` | `["cobblemon:full_restore"]` | Full Restores are sold every rotation, at the price of their rule in `rotatingItems.prices`. |

The default entries, kept in the file, show the other kinds:
- the free daily Safari Balls: `"price": 0`, `"cooldownHours": 24`;
- plain item and Pokémon purchases;
- the tickets traded for apricorns, berries or an evolution stone: `cost` with `limitPerRotation`.

### `client.json`

| Key | Example | Default | Effect |
|---|---|---|---|
| `hudAnchor`, `hudOffsetX`, `hudOffsetY` | `"TOP_RIGHT"`, `-24`, `24` | `"BOTTOM_LEFT"`, `20`, `-16` | The timer sits in the top right corner. Anchors: `TOP_LEFT`, `TOP_CENTER`, `TOP_RIGHT`, `BOTTOM_LEFT`, `BOTTOM_CENTER`, `BOTTOM_RIGHT`. |
| `board.useSprites` | `true` | `false` | Wall screens draw flat sprites instead of 3D models. |
| `board.panelOpacity` | `0.6` | `0.72` | More of the landscape shows through the panels. |
| `board.colors.title` | `"#7CFC00"` | `"#FFB000"` | Green titles. |
| `board.colors.cell` | `"#CC111D33"` | `"#111D33"` | A colour with its own alpha (`#AARRGGBB`) keeps it, whatever `panelOpacity` says. |

# Key reference

Every key of the three files, with its default and what it does, is in [CONFIG.md](../../CONFIG.md) at the root of the repository.
