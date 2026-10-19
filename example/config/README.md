# Example configuration files

Copy `cobblemon_safari/` into the `config/` folder of the game (client) or of the server, then restart. `/safari reload` also re-reads `server.json` and `shop.json`; `client.json` is read when the game starts.

The three files are complete and valid: every key is present with its value. Most values are the defaults; the ones below were changed to show what each kind of setting does. A key left out of a file takes its default value, and the mod writes it back the next time it saves the file.

JSON has no comments, so this page documents the files. The key reference at the end says what each key does and gives its default.

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
| `hudAnchor`, `hudOffsetX`, `hudOffsetY` | `"TOP_RIGHT"`, `-24`, `24` | `"BOTTOM_LEFT"`, `10`, `-11` | The timer sits in the top right corner. Anchors: `TOP_LEFT`, `TOP_CENTER`, `TOP_RIGHT`, `BOTTOM_LEFT`, `BOTTOM_CENTER`, `BOTTOM_RIGHT`. |
| `board.useSprites` | `true` | `false` | Wall screens draw flat sprites instead of 3D models. |
| `board.panelOpacity` | `0.6` | `0.72` | More of the landscape shows through the panels. |
| `board.colors.title` | `"#7CFC00"` | `"#FFB000"` | Green titles. |
| `board.colors.cell` | `"#CC111D33"` | `"#111D33"` | A colour with its own alpha (`#AARRGGBB`) keeps it, whatever `panelOpacity` says. |

# Key reference

Generated from the Javadoc of `SafariConfig`, `SafariShopConfig` and `SafariClientConfig`. If a key here disagrees with the code, the code is right.

## Server rules (`server.json`)

- `configVersion` = `24`: Bumped when a field changes meaning, so `SafariConfigManager` can migrate old files. Version 3: the island grew and the boundary became a square world border, so older park sections no longer match the terrain. Version 4: added the loan and ticket sections (no existing value changes). Version 5: added the spawns, featured and restHouses sections (no existing value changes). Version 6: featured.rotationHours became featured.rotationMinutes. Version 7: added ticket credit settings, the capture section and spawn rate multipliers (no existing value changes). Version 8: added the reset section (no existing value changes). Version 9: removed the top-level defaultDurationSeconds (no entry path grants free time any more) and added the gate section. Version 10: added the points and escapeRope sections (no existing value changes). The shop itself lives in cobblemon_safari/shop.json. Version 11: added protection.placeableBlocks (no existing value changes). Version 12: added the bounties section (no existing value changes). Version 14: added ticket.minAddedSeconds (no existing value changes); the default specials went from 3 uncommon, 2 rare, 1 ultra-rare to 1, 2, 3, and files still on the old defaults follow. Version 15: added the ticketLoot section (no existing value changes). Version 13: the default rotation went from 1440 to 240 minutes; files still on the old default follow it. Version 16: removed dimension, park, featured.rotationHours and the fine-tuning values (now constants), capture.safariBallItem (the bonus follows loan.ballItem) and protection.preventFireSpread preventExplosionBlockDamage (both follow protectBlocks). Their keys are dropped from the file on the next rewrite. Version 17: removed the restHouses section; the houses are always built, at anchors generated with the terrain (park.ParkGeometry). Version 18: capture points and bounty rewards about five times higher, so an hour of hunting buys a Pokémon of the shop; values still on the old defaults follow. Version 19: added spawns.caughtSpeciesWeight (no existing value changes). Version 20: caughtSpeciesWeight 0.25 to 0.4; a file still on 0.25 follows. Version 21: spawns.allowLegendary, allowMythical, allowUltraBeast and allowParadox became the sections spawns.legendary, mythical, ultraBeast and paradox ({allowSpawn, maxSpawnPerPokemon}); the old values move in. Version 22: loan.count 30 to 0, players bring their own balls (the shop gives free Safari Balls once a day); a file still on 30 follows. Version 23: warningThresholdsSeconds gains 15 and 10 minutes (900, 600, 300, 60, 30, 10); a file still on the old 300, 60, 30, 10 follows. featured.announceOnEntry removed (the specials are no longer listed on entry; the wall screens and /safari today show them). Version 24: ticket.allowStartCommand removed with /safari start: players enter through a Safari Gate (or an operator's /safari enter).
- `maxDurationSeconds` = `7200`: Hard cap on the remaining time; adding time never goes above it. Also the most banked time one entry spends, the rest staying in the bank.
- `allowedBalls` = `["cobblemon:safari_ball", "cobblemon:master_ball"]`: Poké Ball ids (as in Cobblemon, e.g. `cobblemon:safari_ball`) usable inside the Safari.
- `pauseTimeWhileOffline` = `true`: When true the clock only runs while the player is online and inside. When false it keeps running while they are offline, and they are escorted out on their next login if it ran out.
- `allowBattles` = `true`: Whether Pokémon battles may start inside the Safari. The games forbid them.
- `battleGraceSeconds` = `120`: Extra time a player may stay after their clock hits zero when they are in the middle of a battle, so the battle is not torn apart by a teleport.
- `exitDestination` = `"RETURN_POINT"`: Where players go when their session ends.
- `exemptCreativeAndSpectator` = `true`: Creative and spectator players may enter and roam without a session (building, moderation). Not on login: a player who logs in inside without a session is sent out whatever their game mode.
- `warningThresholdsSeconds` = `[900, 600, 300, 60, 30, 10]`: Remaining times, in seconds, at which the player gets a warning in chat. Its colour follows the time left, whatever the list holds: white, yellow from 5 minutes, red from 1 minute.

### `protection`

What players can and cannot do to themselves and to the terrain inside the Safari.

- `protection.invulnerablePlayers` = `true`: Players inside take no damage. Damage that bypasses invulnerability (/kill, falling out of the world) still applies, otherwise a player in the void would fall forever.
- `protection.protectBlocks` = `true`: Players cannot break blocks, place blocks, empty or fill buckets, or use tools that change blocks (hoe, shovel, axe stripping, flint and steel, bone meal). Placement is included on purpose: breaking alone leaves TNT and lava buckets as a way to wreck the terrain. Also stops fire from spreading or burning blocks, and explosions from breaking any: protecting blocks from players while fire eats them would make no sense.
- `protection.placeableBlocks` = `["cobblemon:poke_cake", "cobblemon:poke_snack"]`: Blocks players may still place, and break again, while `#protectBlocks` is on. Default: Cobblemon's Poké Cake and Poké Snack, the baits that attract wild Pokémon. Anything placed is lost at the daily map reset.

### `loan`

Safari Balls lent at the entrance, as in the games.

- `loan.count` = `0`: Balls handed out per session. 0 (the default) disables the loan; players then bring their own allowed balls.
- `loan.ballItem` = `"cobblemon:safari_ball"`: Item id of the loaned ball. It is always usable inside the Safari, even if it is missing from `allowedBalls`.

### `capture`

Catch rate adjustments inside the Safari.

- `capture.safariBallOutOfBattleMultiplier` = `3.0`: Multiplies the species catch rate when the loaned ball (`Loan#ballItem`) hits a wild Pokémon that is NOT in a battle, inside the Safari only. Cobblemon halves every capture made outside a battle, so 3 means the Safari Ball out of battle works 1.5 times as well as a normal throw in battle (before Cobblemon's own 1.5 Safari Ball bonus). 1 disables it.

### `reset`

Daily regeneration of the Safari map.

- `reset.enabled` = `true`: Master switch. When false, the map is kept forever.
- `reset.hour` = `4`: Hour (0 to 23) at which a new map day starts. A server restarted at 3:00 with hour 4 still belongs to the previous day and keeps its map.
- `reset.timeZone` = `""`: Time zone of `#hour`, e.g. "Europe/Paris"; empty for the server's own.

### `ticket`

Behaviour of the Safari Ticket item.

- `ticket.defaultDurationSeconds` = `1800`: Time a Safari Ticket adds, unless the ticket carries its own (custom data).
- `ticket.minAddedSeconds` = `1200`: Fewest seconds a ticket may add. When the bank or the hunt is nearly full, a ticket adds only what fits; if that is less than this, it is refused and the player keeps it, rather than burning a ticket for a few seconds. 0 accepts any amount.
- `ticket.extendsActiveSession` = `true`: Using a ticket while already inside adds its duration to the running session (capped by `maxDurationSeconds`) instead of being refused.
- `ticket.maxCreditSeconds` = `14400`: Most Safari time a player can bank with tickets, in seconds. Tickets used outside the Safari add to this bank; entering spends it.
- `ticket.refundUnusedTimeOnLeave` = `true`: Gives the unused time of a hunt paid with banked time back to the bank when the player leaves on purpose (through the clerk) or dies inside. Time running out or an operator ending the hunt never refund.

### `ticketLoot`

Safari Tickets found in overworld chests.

- `ticketLoot.enabled` = `true`: Master switch.

### `ticketLoot.sources` (list, 8 by default; each element has the keys below)

Loot tables that may hold a ticket, each with its chance per chest (0 to 1). An id ending in `/` covers every table under that folder, e.g. `cobblemon:ruins/common/` for every Cobblemon ruin. Tables of other mods work too. Chests inside the Safari itself never hold one, whatever is listed: its map is rebuilt daily, and its ruins would otherwise hand out tickets forever.

- `ticketLoot.sources[].table` = `"minecraft:chests/village/village_plains_house"`: Loot table id, or a folder ending in `/`.
- `ticketLoot.sources[].chance` = `0.05`: Chance per chest, 0 to 1.

### `gate`

The Safari Gates found in the overworld: entry, warden and roaming Pokémon.

- `gate.allowEntry` = `true`: Standing in a gate (like a nether portal), or asking its warden, starts a hunt paid with banked time. When false, gates are decoration only.
- `gate.spawnWarden` = `true`: Keeps a Safari Warden next to each generated gate, who explains how entry works. Gates placed by hand never have one (see SafariGateBlockEntity.KEEPS_WARDEN_KEY). One removed with /kill comes back within a few seconds while a player is near.
- `gate.companions` = `["kangaskhan level=30", "tauros level=30", "chansey level=30"]`: The warden's Pokémon roaming around each gate, as Cobblemon properties (one entry per Pokémon). They cannot be caught, battled or hurt. Empty for none.
- `gate.roamRadius` = `8`: How far, in blocks, the warden's Pokémon may wander from the gate before being walked back (teleported beyond twice this). The warden keeps to a few blocks around his spot, whatever this says.

### `points`

Safari points earned by catching Pokémon, spent at the warden's shop.

- `points.enabled` = `true`: Master switch: when false, captures earn nothing (the shop still sells for points already earned).
- `points.common` = `10`: Points for a capture, by the species' rarity in the Safari (its Cobblemon spawn bucket). Sized with the bounties for about 600 points per hour of active hunting, which buys a first-stage Pokémon of the shop in about a quarter of an hour.
- `points.uncommon` = `20`: 
- `points.rare` = `50`: 
- `points.ultraRare` = `120`: 
- `points.featuredBonus` = `50`: Extra points when the species is one of today's specials.
- `points.shinyBonus` = `250`: Extra points for a shiny.

### `escapeRope`

The Escape Rope lent at the entrance, to get out of caves.

- `escapeRope.enabled` = `true`: Lends one rope to every player entering.

### `bounties`

Daily missions shown on the wall screens, paid in Safari points.

- `bounties.enabled` = `true`: Master switch for the missions, their rewards and their page on the board.
- `bounties.count` = `3`: Missions per rotation. More missions mean more points per day; the board shows them all.
- `bounties.typeRewardPerCatch` = `30`: Points for a "catch N Pokémon of a type" mission, per Pokémon asked (N between SafariBounties.TYPE_MIN_COUNT and TYPE_MAX_COUNT): 3 Pokémon at 30 = 90 points.
- `bounties.speciesReward` = `120`: Points for catching a given species (two of a common one, one of an uncommon one).
- `bounties.featuredReward` = `250`: Points for catching any of today's specials. Off when the specials are switched off.
- `bounties.allDoneBonus` = `200`: Extra points once every mission of the rotation is done.

### `spawns`

Which Pokémon may appear in the Safari, whoever spawns them.

- `spawns.nativeSpawns` = `true`: Lets Cobblemon's natural spawner work in the Safari. When false, only the daily special Pokémon appear, which makes the park very quiet.

### `spawns.legendary`

Pokémon labelled `legendary` (Rayquaza, Mewtwo...).

- `spawns.legendary.allowSpawn` = `false`: Pokémon of this category may appear at all.
- `spawns.legendary.maxSpawnPerPokemon` = `1`: Most Pokémon of each species of this category per rotation of the specials (featured.rotationMinutes), counting those caught in the Safari plus those still roaming: with 2, once two Rayquaza are caught, no third shows up before the next rotation. One that leaves uncaught (despawned, fled, knocked out) gives its place back within about ten seconds, so another player gets a chance. 0 lifts the cap. A species in two categories follows the lower cap.

### `spawns.mythical`

Pokémon labelled `mythical` (Mew, Celebi...).

- `spawns.mythical.allowSpawn` = `false`: Pokémon of this category may appear at all.
- `spawns.mythical.maxSpawnPerPokemon` = `1`: Most Pokémon of each species of this category per rotation of the specials (featured.rotationMinutes), counting those caught in the Safari plus those still roaming: with 2, once two Rayquaza are caught, no third shows up before the next rotation. One that leaves uncaught (despawned, fled, knocked out) gives its place back within about ten seconds, so another player gets a chance. 0 lifts the cap. A species in two categories follows the lower cap.

### `spawns.ultraBeast`

Pokémon labelled `ultra_beast` (Nihilego, Buzzwole...).

- `spawns.ultraBeast.allowSpawn` = `false`: Pokémon of this category may appear at all.
- `spawns.ultraBeast.maxSpawnPerPokemon` = `1`: Most Pokémon of each species of this category per rotation of the specials (featured.rotationMinutes), counting those caught in the Safari plus those still roaming: with 2, once two Rayquaza are caught, no third shows up before the next rotation. One that leaves uncaught (despawned, fled, knocked out) gives its place back within about ten seconds, so another player gets a chance. 0 lifts the cap. A species in two categories follows the lower cap.

### `spawns.paradox`

Pokémon labelled `paradox` (Great Tusk, Iron Treads...). Allowed and uncapped by default, unlike the other categories: they are ordinary wild Pokémon in their own games, not one-of-a-kind legends.

- `spawns.paradox.allowSpawn` = `true`: Pokémon of this category may appear at all.
- `spawns.paradox.maxSpawnPerPokemon` = `0`: Most Pokémon of each species of this category per rotation of the specials (featured.rotationMinutes), counting those caught in the Safari plus those still roaming: with 2, once two Rayquaza are caught, no third shows up before the next rotation. One that leaves uncaught (despawned, fled, knocked out) gives its place back within about ten seconds, so another player gets a chance. 0 lifts the cap. A species in two categories follows the lower cap.
- `spawns.excludedSpecies` = `[]`: Species ids that never appear in the Safari, e.g. `cobblemon:ditto`.
- `spawns.maxSameSpeciesNearby` = `4`: A natural spawn is cancelled when this many Pokémon of the same species already roam within NativeSpawnRules.SAME_SPECIES_RADIUS. Keeps one common species from flooding a habitat. 0 disables the cap.
- `spawns.spawnRateMultiplier` = `2.0`: How much faster Cobblemon's spawner ticks for players in the Safari. 2 makes a spawn attempt twice as often; 1 is Cobblemon's normal pace. Cost grows with it: each attempt scans a zone of blocks.
- `spawns.pokemonPerChunkMultiplier` = `2.0`: Multiplies Cobblemon's Pokémon-per-chunk cap for players in the Safari, so faster spawning is not wasted on a full cap. 1 keeps Cobblemon's value.
- `spawns.caughtSpeciesWeight` = `0.4`: Weight, in Cobblemon's natural spawns around a player, of the species that player has already caught in the Safari, against 1 for species they have not: 0.4 makes a new species two and a half times as likely as a caught one of the same weight. Caught species still appear, only less often; lower values make them feel gone. 1 turns it off; 0 stops them entirely while anything else can spawn. Acts within Cobblemon's rarity buckets, so a habitat whose species are all caught looks the same.

### `featured`

The daily rotation of special Pokémon and the spawner that brings them in.

- `featured.enabled` = `true`: Master switch for the rotation, the board content and the special spawner.
- `featured.rotationMinutes` = `240`: Length of one rotation, in real minutes (240 = four hours, 1440 = one day). Rotations are aligned on midnight UTC when the length divides a day, so 240 changes the specials at 0, 4, 8, 12, 16 and 20 h UTC. The bounties are redrawn with each rotation, so a shorter rotation also means more bounty sets (and bonuses) per day. Changing it starts a new draw right away.
- `featured.uncommonSlots` = `1`: Species featured per rotation, by rarity. Weighted toward the rare end: the specials are what is worth a trip, and the board shows six of them at most comfortably on a 4 x 2 screen. A slot a rarity cannot fill goes to the next more common one.
- `featured.rareSlots` = `2`: Rare species featured per rotation.
- `featured.ultraRareSlots` = `3`: Ultra-rare species featured per rotation. Legendaries land here when allowed.
- `featured.weightUncommon` = `10`: Relative chance of an uncommon special being picked for a spawn.
- `featured.weightRare` = `4`: Relative chance of a rare special being picked for a spawn.
- `featured.weightUltraRare` = `1`: Relative chance of an ultra-rare special being picked for a spawn.
- `featured.spawnIntervalSeconds` = `15`: Seconds between two spawn attempts around each player in the Safari.
- `featured.spawnChance` = `0.35`: Chance that an attempt goes ahead, between 0 and 1.
- `featured.maxNearbyPerPlayer` = `2`: Special Pokémon allowed around one player at once (within FeaturedSpawner.NEARBY_RADIUS). Also caps each species to one around the player, so no special takes over.

## Shop (`shop.json`)

- `configVersion` = `12`: Bumped when a field changes meaning, so old files can be migrated. Version 1: first version. Version 2: added tabs and Entry.tab. Version 3: added the rotating section (no existing value changes). Version 4: the default Pokémon list became a single Tauros; files still holding the five old default Pokémon get it too. Version 5: added Entry.cost and Entry.limitPerRotation, the tickets tab and the default ticket trades (added to existing files once). Version 6: costs accept item tags; the version 5 default trades (red apricorns, oran berries, a fire stone) become any apricorn, berry and evolution stone. Version 7: the default Tauros costs 200 points instead of 1500. Version 8: rotating.level removed; the random Pokémon come at their natural Safari level and their price grows with their evolution stage. Version 9: rotating.rarePrice 1000 to 500; a file still on 1000 follows. Version 10: rotating.count 4 to 15 (a file still on 4 follows); added rotatingItems and rotatingBalls. Version 11: added the cooking tab and its default ingredients (added to existing files once). Version 12: added Entry.cooldownHours and the free daily Safari Balls (added to existing files once), which replace the ball loan.
- `enabled` = `true`: Master switch. When false the warden's "Shop" answer says the shop is closed.

### `tabs` (list, 5 by default; each element has the keys below)

Tabs of the shop screen, in display order. Each entry names its tab with `Entry#tab`. A tab with no entry is not shown.

- `tabs[].id` = `"pokemon"`: Id entries refer to. For a built-in id the name comes from the translation key `cobblemon_safari.shop.tab.`.
- `tabs[].name` = `""`: Name shown instead of the translated one; empty to use the translation (or the id).
- `tabs[].icon` = `"cobblemon:poke_ball"`: Item id drawn on the tab, e.g. `cobblemon:poke_ball`; empty or unknown for none.

### `entries` (list, 21 by default; each element has the keys below)

What is for sale, in display order within each tab (8 per page). By default a single fixed Pokémon and the usual items; the rotating Pokémon are added after these.

- `entries[].type` = `"pokemon"`: `"item"` or `"pokemon"`.
- `entries[].pokemon` = `"tauros level=45"`: Cobblemon properties, for `"pokemon"` entries, e.g. `"dratini level=15"` or `"eevee shiny=yes"`; null otherwise.
- `entries[].price` = `200`: Cost in Safari points, or `"auto"` in the file (`#AUTO_PRICE` here) for a Pokémon priced like the shop's random ones: its rarity in the Safari times its evolution stage. The shop resolves it each time it lists its entries, so it follows the rotating prices of the file.
- `entries[].tab` = `""`: Id of the tab showing this entry (see `SafariShopConfig#tabs`). Empty picks one from the type: `pokemon` for Pokémon, `balls` for items whose id ends in `_ball`, `items` otherwise. Filled in by the config manager.

### `rotating`

Pokémon drawn at random for each rotation of the Safari specials, sold after the fixed `#entries`. Every player sees the same ones; they change with the specials (`featured.rotationMinutes` in the main config).

- `rotating.enabled` = `true`: Master switch; off, the shop only sells `SafariShopConfig#entries`.
- `rotating.count` = `15`: How many Pokémon are drawn per rotation.
- `rotating.tab` = `"pokemon"`: Tab showing them; an unknown id falls back to `pokemon`, then to the first tab.
- `rotating.rarities` = `["common", "uncommon", "rare"]`: Rarities they are drawn from, as Cobblemon spawn buckets: `common`, `uncommon`, `rare`, `ultra-rare`. Only species that spawn naturally in the Safari are drawn, with their rarity there.
- `rotating.commonPrice` = `250`: Base price per rarity, in Safari points: the price of a first-stage Pokémon. Later stages cost more (RotatingShop.STAGE_MULTIPLIERS): a Pokémon's spawn rarity says how often it appears, not how strong it is, and Cobblemon files many final evolutions as common.
- `rotating.uncommonPrice` = `500`: 
- `rotating.rarePrice` = `500`: 
- `rotating.ultraRarePrice` = `2500`: 
- `rotating.excludeSpecials` = `true`: Leaves out the specials of the current rotation, so the shop never sells what players are hunting today.
- `rotating.allowSpecialSpecies` = `false`: Allows legendary, mythical, ultra beast and paradox species.
- `rotating.excludedSpecies` = `[]`: Species never drawn, as Cobblemon ids, e.g. `cobblemon:ditto`.

### `rotatingItems`

Cobblemon items drawn at random for each rotation, sold in the items tab after the fixed entries, priced by kind (stones, potions, candies...).

- `rotatingItems.enabled` = `true`: Master switch.
- `rotatingItems.count` = `8`: How many items are drawn per rotation, on top of `#always`.
- `rotatingItems.tab` = `"items"`: Tab showing them; an unknown id falls back to the first tab.
- `rotatingItems.always` = `[]`: Items sold every rotation whatever the draw, as item ids; each still needs a `#prices` rule, which gives its price and stack.
- `rotatingItems.excluded` = `["cobblemon:ability_patch", "#cobblemon:teapots", "#cobblemon:teacu...`: Items never drawn: item ids, or item tags prefixed with `#` (e.g. `#cobblemon:mints`). Items already sold by the fixed entries are left out too, so the shop never lists one twice.

### `rotatingItems.prices` (list, 29 by default; each element has the keys below)

What can be drawn and at what price: each rule names an item id or an item tag (`#`), the stack sold and its price in points. The first rule matching an item wins, so specific items go before the tags that hold them (a Max Potion before `#cobblemon:potions`).

- `rotatingItems.prices[].item` = `"cobblemon:full_restore"`: Item id, or item tag prefixed with `#`.
- `rotatingItems.prices[].count` = `1`: Stack sold per purchase.
- `rotatingItems.prices[].price` = `200`: Price of the stack, in Safari points.

### `rotatingBalls`

Poké Balls drawn at random for each rotation, sold in the balls tab; Safari Balls always.

- `rotatingBalls.enabled` = `true`: Master switch.
- `rotatingBalls.count` = `6`: How many items are drawn per rotation, on top of `#always`.
- `rotatingBalls.tab` = `"balls"`: Tab showing them; an unknown id falls back to the first tab.
- `rotatingBalls.always` = `["cobblemon:safari_ball"]`: Items sold every rotation whatever the draw, as item ids; each still needs a `#prices` rule, which gives its price and stack.
- `rotatingBalls.excluded` = `["#cobblemon:tier_5_poke_balls", "cobblemon:cherish_ball"]`: Items never drawn: item ids, or item tags prefixed with `#` (e.g. `#cobblemon:mints`). Items already sold by the fixed entries are left out too, so the shop never lists one twice.

### `rotatingBalls.prices` (list, 5 by default; each element has the keys below)

What can be drawn and at what price: each rule names an item id or an item tag (`#`), the stack sold and its price in points. The first rule matching an item wins, so specific items go before the tags that hold them (a Max Potion before `#cobblemon:potions`).

- `rotatingBalls.prices[].item` = `"cobblemon:safari_ball"`: Item id, or item tag prefixed with `#`.
- `rotatingBalls.prices[].count` = `10`: Stack sold per purchase.
- `rotatingBalls.prices[].price` = `150`: Price of the stack, in Safari points.

## Client display (`client.json`)

- `configVersion` = `6`: Bumped when a field changes meaning. Version 6: the board colours moved from `board` to `board.colors`, and `board.pokemonDisplay` (MODEL / SPRITE) became the boolean `board.useSprites`; `SafariClientConfigManager` converts older files. Version 7: the timer became one line (stopwatch, then time) in the bottom left corner, level with the hotbar; a file still on the old default position follows.
- `hudEnabled` = `true`: Master switch for the timer HUD.
- `hudAnchor` = `"BOTTOM_LEFT"`: Where the timer is attached.
- `hudOffsetX` = `10`: Offset of the middle of the timer's stopwatch from the anchor, in GUI pixels; the time is on its right. The default puts it in the bottom left corner, its middle level with the middle of the hotbar (22 px high, at the bottom of the screen).
- `hudOffsetY` = `-11`: 
- `lowTimeSeconds` = `60`: Below this many seconds the timer turns amber.
- `criticalTimeSeconds` = `10`: Below this many seconds the timer turns red and pulses.

### `board`

Look of the wall screens (the section keeps its old name, "board", so existing files still load).

- `board.useSprites` = `false`: Draws the Pokémon as the flat sprites the mod ships (textures/pokemon_sprites, which resource packs can override) instead of their turning 3D Cobblemon models. A Pokémon without a sprite keeps its model.
- `board.backgroundImage` = `true`: Shows the safari landscape of the time of day behind the screen (the shop's pictures) instead of the plain background colour.
- `board.panelOpacity` = `0.72`: Opacity of the title band, cells and bars over the landscape, from 0 (invisible) to 1 (opaque, the landscape only shows between them). A colour given with its own alpha (`#AARRGGBB`) keeps it.

### `board.colors`

Colours of the screens.

- `board.colors.bezel` = `"#1A1C22"`: Thin border around the whole picture.
- `board.colors.background` = `"#0A1220"`: Screen background, when `Board#backgroundImage` is off.
- `board.colors.header` = `"#13213B"`: Title band.
- `board.colors.title` = `"#FFB000"`: Title text.
- `board.colors.cell` = `"#111D33"`: Background of each Pokémon's cell.
- `board.colors.text` = `"#FFFFFF"`: Pokémon names.
- `board.colors.muted` = `"#8C9BB5"`: Secondary text: habitat, LIVE label, page counter, bar label.
- `board.colors.star` = `"#FFD700"`: Star in front of legendary-class species.
- `board.colors.liveOn` = `"#FF3B3B"`: LIVE light, lit and unlit.
- `board.colors.liveOff` = `"#5A1A1A"`: 
- `board.colors.barBackground` = `"#1E2B45"`: Empty part of the progress bar.
- `board.colors.barRotation` = `"#35C8E8"`: Bar and time when counting down to the next rotation.
- `board.colors.barSessionOk` = `"#4CD964"`: Bar and time of the viewer's own hunt, by remaining time (same thresholds as the HUD).
- `board.colors.barSessionLow` = `"#FFC04D"`: 
- `board.colors.barSessionCritical` = `"#FF5555"`: 
- `board.colors.rarityCommon` = `"#FFFFFF"`: Rarity strip and rarity name, per tier.
- `board.colors.rarityUncommon` = `"#55FF55"`: 
- `board.colors.rarityRare` = `"#55FFFF"`: 
- `board.colors.rarityUltraRare` = `"#FF55FF"`: 
- `board.colors.scanline` = `"#12FFFFFF"`: Light band rolling down the screen; mostly transparent.
- `board.colors.shimmer` = `"#50FFFFFF"`: Light sweeping along the progress bar.
- `board.colors.flash` = `"#90FFFFFF"`: Flash when the list changes; its alpha is the flash's peak.
