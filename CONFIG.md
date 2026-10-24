# Cobblemon Safari: configuration

Every setting of the mod, file by file.

## The files

All three live in `config/cobblemon_safari/` and are created with their default values on the first start.

| File | Side | What it holds | Reloaded by |
|---|---|---|---|
| `server.json` | Server (and single player) | The rules of the Safari | `/safari reload` |
| `shop.json` | Server (and single player) | What the shop sells | `/safari reload` |
| `client.json` | Each player's game | How the HUD and the wall screens look | Restarting the game |

How the files behave:

- **A key left out** takes its default value, and the mod writes it back the next time it saves the file.
- **An invalid value** (a negative price, an hour of 25, a colour that is not a colour...) is replaced by its default or clamped into range, a warning names it in the log, and the file is rewritten with the fixed value.
- **A file that cannot be read at all** (a missing comma, a stray quote) is never overwritten: the mod runs on the defaults, logs the error, and reads the file again once you fix it.
- **Unknown keys** are ignored and dropped when the file is rewritten.
- **`configVersion`** is the format version of the file (`3` for `server.json`). It is not a setting: the mod writes it. A `server.json` at version `1` is converted once on load: its `spawns` species rules are copied to `featured` (see below), so nothing changes in game until you edit them.

Formats used below:

- **Ids** are written `namespace:path`, e.g. `cobblemon:safari_ball`, `minecraft:torch`.
- **Item tags** are ids prefixed with `#`, e.g. `#cobblemon:apricorns`. Where a tag is accepted, any item of the tag counts.
- **Cobblemon properties** are the strings of Cobblemon's `/spawnpokemon` and `/givepokemon`, e.g. `"tauros level=45"`, `"eevee shiny=yes"`, `"vulpix alolan level=20"`.
- **Times** are in seconds unless the key says otherwise (`rotationMinutes`, `cooldownHours`).

A complete, commented example of the three files is in [`example/config/`](example/config).

---

## `server.json`: rules of the Safari

### General

| Key | Default | What it does |
|---|---|---|
| `maxDurationSeconds` | `7200` (2 h) | Longest a hunt can last. Adding time never goes above it, and one entry spends at most this much banked time: the rest stays in the bank. At least 1. |
| `allowedBalls` | `["cobblemon:safari_ball", "cobblemon:master_ball"]` | Poké Balls that work inside the Safari, by Cobblemon id. Any other ball is refused when thrown. An empty list means no capture at all (the log warns about it). |
| `pauseTimeWhileOffline` | `true` | `true`: the clock only runs while the player is online and inside. `false`: it keeps running while they are offline, and they are escorted out at their next login if it ran out. |
| `allowBattles` | `true` | Whether Pokémon battles may start inside. `false` forbids them, as in the original games, but then there is no weakening a Pokémon before throwing. |
| `battleGraceSeconds` | `120` | Extra time a player gets when their clock hits zero in the middle of a battle, so the battle ends normally before they are sent out. 0 or more. |
| `exitDestination` | `"RETURN_POINT"` | Where players go when the hunt ends. `"RETURN_POINT"`: exactly where they entered from. `"WORLD_SPAWN"`: the overworld spawn, for a hub server. |
| `exemptCreativeAndSpectator` | `true` | Creative and spectator players may enter and roam without a session, and ignore the terrain protection: for building and moderation. Does not apply at login: a player who logs in inside without a session is always sent out. |
| `warningThresholdsSeconds` | `[900, 600, 300, 60, 30, 10]` | Time left, in seconds, at which the player gets a chat warning. The colour follows the time left: white, yellow from 5 minutes, red from 1 minute. Zero, negative and duplicate values are dropped. |

### `protection`

| Key | Default | What it does |
|---|---|---|
| `invulnerablePlayers` | `true` | Players inside take no damage. `/kill` and falling out of the world still apply, so a player in the void does not fall forever. |
| `protectBlocks` | `true` | Players cannot break or place blocks, fill or empty buckets, or use tools that change blocks (hoe, shovel, axe stripping, flint and steel, bone meal). Fire does not spread or burn blocks, and explosions break nothing. |
| `placeableBlocks` | `["cobblemon:poke_cake", "cobblemon:poke_snack"]` | Blocks players may still place and break while `protectBlocks` is on. By default Cobblemon's baits. Anything placed disappears with the daily map. |

### `loan`

The original games' loan of Safari Balls, off by default: players bring their own allowed balls, and the shop gives 30 free Safari Balls a day.

| Key | Default | What it does |
|---|---|---|
| `count` | `0` | Balls lent to each player on entry; 0 turns the loan off. Lent balls are marked, cannot be thrown outside the Safari, and are taken back on exit or as soon as the player is found outside with one. 0 to 256. |
| `ballItem` | `"cobblemon:safari_ball"` | The ball lent. It always works inside, even if it is missing from `allowedBalls`. It is also the ball that gets the `capture` bonus. |

### `capture`

| Key | Default | What it does |
|---|---|---|
| `safariBallOutOfBattleMultiplier` | `3.0` | Multiplies the catch rate when a Safari Ball (`loan.ballItem`) hits a wild Pokémon that is not in a battle, inside the Safari. Cobblemon halves every capture made outside a battle, so at 3 the Safari Ball out of battle works 1.5 times as well as a normal throw in battle, before Cobblemon's own Safari Ball bonus. 1 turns it off; lower values are raised to 1. |

### `reset`: the daily new map

At the first server start of a new day, the Safari dimension is deleted and regenerated with another layout. Everything built or dropped inside is lost; sessions, statistics, banked time and points are kept.

| Key | Default | What it does |
|---|---|---|
| `enabled` | `true` | `false` keeps the same map forever. |
| `hour` | `4` | Hour (0 to 23) at which a new map day starts. With 4, a server restarted at 3:00 still belongs to the previous day and keeps its map. |
| `timeZone` | `""` | Time zone of `hour`, e.g. `"Europe/Paris"`. Empty uses the server's own. |

`/safari reset` forces a new map at the next start, whatever this section says.

### `ticket`

A Safari Ticket used outside the Safari banks its time; entering spends the bank. Used inside, it extends the running hunt.

| Key | Default | What it does |
|---|---|---|
| `defaultDurationSeconds` | `1800` (30 min) | Time a ticket adds. A ticket can carry its own length instead: `/give @p cobblemon_safari:safari_ticket[custom_data={duration_seconds:600}]`. At least 1. |
| `minAddedSeconds` | `1200` (20 min) | Least time a ticket may add. When the bank or the hunt is nearly full, a ticket adds only what fits; if that is less than this, the ticket is refused and kept. 0 accepts any amount. |
| `extendsActiveSession` | `true` | Using a ticket inside adds its time to the running hunt (up to `maxDurationSeconds`). `false` refuses tickets inside. |
| `maxCreditSeconds` | `14400` (4 h) | Most Safari time a player can bank. |
| `refundUnusedTimeOnLeave` | `true` | When a hunt paid with banked time ends early because the player left through the clerk or died inside, the unused time goes back to the bank. Time running out, or an operator ending the hunt, never refunds. |

### `ticketLoot`: tickets in chests

Read when loot tables load: after a change, `/reload` (or restart) for chests generated afterwards.

| Key | Default | What it does |
|---|---|---|
| `enabled` | `true` | Master switch. |
| `sources` | 8 sources, see below | Loot tables that may hold a ticket, each with its chance per chest. |

Each element of `sources`:

| Key | What it does |
|---|---|
| `table` | Loot table id, e.g. `minecraft:chests/desert_pyramid`, or a folder ending in `/` for every table in it, e.g. `cobblemon:ruins/common/`. Tables of other mods work too. |
| `chance` | Chance per chest, from 0 to 1. When a table matches several sources, the exact one wins, then the longest folder. |

The defaults: village houses (plains, desert, taiga, snowy `0.05`, savanna `0.08`), desert pyramids and jungle temples (`0.10`), Cobblemon ruins (`0.15`). Chests inside the Safari never hold tickets, whatever is listed.

### `gate`: Safari Gates

How often gates generate is set by the datapack (`data/cobblemon_safari/worldgen/structure_set/safari_gate.json`), not here.

| Key | Default | What it does |
|---|---|---|
| `allowEntry` | `true` | Standing in a gate, or asking its warden, starts a hunt. `false` makes gates decoration only. |
| `spawnWarden` | `true` | Keeps a Safari Warden next to each generated gate. Gates placed by hand never have one. A warden removed with `/kill` comes back within a few seconds while a player is near. |
| `companions` | `["kangaskhan level=30", "tauros level=30", "chansey level=30"]` | The warden's Pokémon roaming around each gate, as Cobblemon properties, one per entry. They cannot be caught, battled or hurt. Empty for none; 8 at most. |
| `roamRadius` | `8` | How far, in blocks, they may wander from the gate before walking back (teleported back beyond twice this). 3 to 24. The warden himself keeps to a few blocks around his spot. |

### `points`: Safari points

A currency separate from Safari time, earned with every capture inside and spent at the shop.

| Key | Default | What it does |
|---|---|---|
| `enabled` | `true` | `false`: captures earn nothing. The shop still accepts the points already earned. |
| `common` | `10` | Points for a common species (by its rarity in the Safari). |
| `uncommon` | `20` | Points for an uncommon species. |
| `rare` | `50` | Points for a rare species. |
| `ultraRare` | `120` | Points for an ultra-rare species. |
| `featuredBonus` | `50` | Extra points when the species is one of today's specials. |
| `shinyBonus` | `250` | Extra points for a shiny. |

With the default missions, the defaults give about 600 points per hour of active hunting. No value may be negative.

### `escapeRope`

| Key | Default | What it does |
|---|---|---|
| `enabled` | `true` | Lends an Escape Rope to every player entering, taken back on exit. Used in the Safari, it offers to climb to the nearest safe spot in the open air (from a cave) or to go to the nearest rest house (see `restHouseTravel`). |

### `restHouseTravel`: trips between the rest houses

The Safari Guide of every rest house takes players to any other house of the island, named by its biome (with its direction, "Jungle Safari (north)", when two houses share a biome). The Escape Rope can take them to the nearest one. Every trip shows a short cinematic while the destination loads; a house nobody went near yet is built during it.

| Key | Default | What it does |
|---|---|---|
| `guideEnabled` | `true` | The Safari Guide of every rest house. `false` removes the guides from the houses. |
| `ropeEnabled` | `true` | The Escape Rope's "nearest rest house" option. Climbing to the open air stays either way. |
| `prebuildHouses` | `true` | Builds every rest house of a new map in the background, one at a time, from 30 s after the server starts, so trips rarely wait. `false` builds a house the first time a player goes near it or travels to it. |
| `cooldownSeconds` | `30` | Seconds before the same player can travel again, by the guide or by the rope: one wait for both, so the two cannot be chained. 0 removes the wait. At least 0. |
| `cinematicSeconds` | `3` | Shortest length of the cinematic, even when the destination is ready at once; longer while it loads or builds (45 s at most, then the trip is given up). 0 to 15. |
| `cinematicStyle` | `"FLIGHT"` | How the guide's trips are shown. `"FLIGHT"`: a double of the player, wearing their skin, takes off in front of the house on the first Pokémon of their party that can be ridden in the air (a copy of it: the party is not touched), or on a Staraptor when none can, and lands in front of the destination, filmed by the game camera; players around see it too. `"PANORAMA"`: the painted landscape between black bands. FLIGHT falls back to PANORAMA by itself when the mount has no open sky in front of the house, or when the player has no flyer and Staraptor is missing. The Escape Rope always plays its own pixel-art scene. |

A file of version 2 had the wait as `escapeRope.restHouseCooldownSeconds`; its value is moved here on load.

### `bounties`: missions

A few missions drawn with each rotation of the specials, the same for every player, each paid once per player.

| Key | Default | What it does |
|---|---|---|
| `enabled` | `true` | Master switch for the missions, their rewards and their page on the wall screens. |
| `count` | `3` | Missions per rotation, 0 to 8. |
| `typeRewardPerCatch` | `30` | Reward of a "catch N Pokémon of a type" mission, per Pokémon asked (N is 3 to 5): 3 Pokémon = 90 points. |
| `speciesReward` | `120` | Reward of a "catch this species" mission (two of a common species, one of an uncommon one). |
| `featuredReward` | `250` | Reward of a "catch one of today's specials" mission. There is no such mission when the specials are off. |
| `allDoneBonus` | `200` | Extra points once every mission of the rotation is done. |

### Species rules: `spawns` and `featured`

Pokémon come into the Safari two ways, and each has its own species rules:

- **`spawns`** for Cobblemon's natural spawns;
- **`featured`** for the Pokémon of the day: what the wall screens may draw, and what the mod's own spawner brings in.

So a legendary can be one of the specials without roaming the park through the natural spawns, or the reverse. Both sections take the same five keys:

| Key | Default | What it does |
|---|---|---|
| `legendary` | `allowSpawn`: `false`, `maxSpawnPerPokemon`: `1` | Pokémon labelled `legendary` (Rayquaza, Mewtwo...). See the category keys below. |
| `mythical` | `allowSpawn`: `false`, `maxSpawnPerPokemon`: `1` | Pokémon labelled `mythical` (Mew, Celebi...). |
| `ultraBeast` | `allowSpawn`: `false`, `maxSpawnPerPokemon`: `1` | Pokémon labelled `ultra_beast` (Nihilego, Buzzwole...). |
| `paradox` | `allowSpawn`: `true`, `maxSpawnPerPokemon`: `0` | Pokémon labelled `paradox` (Great Tusk, Iron Treads...). Allowed and uncapped by default: they are ordinary wild Pokémon in their own games. |
| `excludedSpecies` | `[]` | Species that never come in this way, e.g. `["cobblemon:ditto"]`. |

Each category (`legendary`, `mythical`, `ultraBeast`, `paradox`) has two keys:

| Key | What it does |
|---|---|
| `allowSpawn` | Whether Pokémon of this category may come in this way at all. A species in two categories needs both allowed. |
| `maxSpawnPerPokemon` | Most Pokémon of each species of this category coming in this way per rotation, counting those caught in the Safari plus those still roaming: with 2, once two Rayquaza are caught, no third comes in this way before the next rotation. One that leaves uncaught (despawned, fled, knocked out) gives its place back within about ten seconds. 0 lifts the cap. |

The two ways count apart: with a cap of `1` in both sections, one Rayquaza may come from the natural spawns and another from the specials in the same rotation.

These rules only cover the Safari: they never change what spawns in the rest of the world. The missions follow `spawns` (they are met with natural Pokémon); the shop has its own rules (`rotating` in `shop.json`).

A species refused by `spawns` is taken out of Cobblemon's spawn draw itself, so it no longer shows in `/checkspawn` there, and one with a huge weight (a datapack spawning Mew as common, say) cannot starve the other species. Species at their `maxSpawnPerPokemon`, or already `maxSameSpeciesNearby` times around the player, leave the draw the same way, until a slot frees up.

### `spawns`: Cobblemon's natural spawns

On top of the species rules above:

| Key | Default | What it does |
|---|---|---|
| `nativeSpawns` | `true` | Lets Cobblemon's natural spawner work in the Safari. `false` leaves only the specials, and the park very quiet. |
| `maxSameSpeciesNearby` | `4` | No natural spawn of a species when this many of it already roam nearby, so one common species cannot flood a habitat. 0 turns the cap off. |
| `spawnRateMultiplier` | `2.0` | How much faster Cobblemon's spawner works for players in the Safari. 1 is Cobblemon's own pace. Higher costs more server time. 0.1 to 10. |
| `pokemonPerChunkMultiplier` | `2.0` | Multiplies Cobblemon's Pokémon-per-chunk cap for players in the Safari, so faster spawning is not wasted on a full cap. 0.1 to 10. |
| `caughtSpeciesWeight` | `0.4` | Weight of the species a player already caught in the Safari, in the natural spawns around them (against 1 for species they have not). At 0.4 a new species is two and a half times as likely as a caught one. 1 turns it off; 0 stops caught species entirely while anything else can spawn. 0 to 1. |

Cobblemon's `/gamerule doPokemonSpawning false` stops every spawn in the Safari, specials included.

### `featured`: the Pokémon of the day

A few uncommon, rare and ultra-rare species, drawn from what is installed, shown on the wall screens and spawned by the mod in their own habitat. On top of the species rules above:

| Key | Default | What it does |
|---|---|---|
| `enabled` | `true` | Master switch for the rotation, the wall screens' specials page and the special spawner. |
| `rotationMinutes` | `240` (4 h) | Length of a rotation, in real minutes. When it divides a day, rotations are aligned on midnight UTC: 240 changes the specials at 0, 4, 8, 12, 16 and 20 h UTC. Missions, the shop's random goods and the purchase limits follow the same rotation. At least 1. |
| `uncommonSlots` | `1` | Uncommon species per rotation. |
| `rareSlots` | `2` | Rare species per rotation. |
| `ultraRareSlots` | `3` | Ultra-rare species per rotation; legendaries land here when `featured.legendary` allows them. A slot a rarity cannot fill goes to the next more common one. |
| `weightUncommon` | `10` | Relative chance of an uncommon special being picked for a spawn. |
| `weightRare` | `4` | Relative chance of a rare special. |
| `weightUltraRare` | `1` | Relative chance of an ultra-rare special. |
| `spawnIntervalSeconds` | `15` | Seconds between two spawn attempts around each player in the Safari. At least 1. |
| `spawnChance` | `0.35` | Chance that an attempt goes ahead, 0 to 1. |
| `maxNearbyPerPlayer` | `2` | Specials allowed around one player at once (never two of the same species). |

After `/safari reload`, the specials are redrawn with the new rules right away.

`/safari rotate` starts a new rotation now; `/safari today` lists the current specials.

---

## `shop.json`: the shop

The Safari Warden (at every gate) and the Safari Clerk (in every rest house) open the shop. It sells, in this order: the fixed `entries` of this file, then the random Pokémon (`rotating`), items (`rotatingItems`) and Poké Balls (`rotatingBalls`) of the current rotation. Every player sees the same random goods; they change with the specials.

| Key | Default | What it does |
|---|---|---|
| `enabled` | `true` | `false` closes the shop: the NPCs say so. |
| `tabs` | Pokémon, Items, Poké Balls, Cooking, Tickets | Tabs of the shop screen, in display order. See below. |
| `entries` | 21 entries, see below | The fixed list of what is for sale. |
| `rotating` | see below | The random Pokémon. |
| `rotatingItems` | see below | The random items. |
| `rotatingBalls` | see below | The random Poké Balls. |

### `tabs`

A tab with nothing in it is hidden.

| Key | What it does |
|---|---|
| `id` | What entries refer to. The built-in ids `pokemon`, `items`, `balls`, `cooking` and `tickets` have translated names. Ids must be unique. |
| `name` | Name shown as is, instead of the translated one. Empty: the translation, or the id. |
| `icon` | Item drawn on the tab, e.g. `"cobblemon:poke_ball"`. Empty for none. |

### `entries`

Each entry is an item or a Pokémon. Entries appear in the order of the file within their tab, 8 per page.

| Key | Applies to | What it does |
|---|---|---|
| `type` | both | `"item"` or `"pokemon"`. |
| `item` | items | Item id, e.g. `"cobblemon:rare_candy"`. An id that names no item (a mod removed) shows the entry as unavailable. |
| `count` | items | Stack given per purchase, 1 to 64. |
| `pokemon` | Pokémon | Cobblemon properties, e.g. `"dratini level=15"`. The Pokémon goes to the party, or the PC when the party is full. |
| `price` | both | Cost in Safari points (0 for free). For a Pokémon, `"auto"` prices it like the random ones: the base price of its rarity in the Safari times its evolution stage. |
| `cost` | both | Items taken in exchange, on top of `price`: a list of `{"item": ..., "count": ...}`, each `item` an id or a `#tag` (any mix of the tag's items counts; a player carrying several kinds picks which one is given first). Leave it out for a plain purchase. |
| `limitPerRotation` | both | How many times each player may buy it per rotation. Leave it out for no limit. |
| `cooldownHours` | both | Hours each player waits after buying it before buying it again, in real time (the wait goes on while the player or the server is offline). Leave it out for no wait. Up to a year. |
| `tab` | both | Id of the tab showing it. Empty or unknown: `pokemon` for Pokémon, `balls` for items whose id ends in `_ball`, `tickets` for Safari Tickets, `items` otherwise. The mod writes the chosen tab back. |

The default entries show every kind:

- **Pokémon**: a level 45 Tauros for 200 points.
- **Items**: Rare Candy, Exp. Candy M, Ability Capsule, Poké Balls, Great Balls and Ultra Balls.
- **Free daily Safari Balls**: 30 `cobblemon:safari_ball` for 0 points with `"cooldownHours": 24`. The card shows "Free" and a "Take" button.
- **Cooking**: what the rest houses' Poké Pot needs (Hearty Grains, Vivichoke, Medicinal Leek, Tasty Tail, Galarica Nuts, Pep-Up Flower, Energy Root, honey, sugar, milk).
- **Ticket trades**: one Safari Ticket for 16 `#cobblemon:apricorns`, 24 `#cobblemon:berries`, 1 `#cobblemon:evolution_stones` or 1 `#cobblemon:fossils`, each `"limitPerRotation": 1`. A `shop.json` from before the fossil trade (version 1) gets it added once on load, unless it already trades fossils.

### `rotating`: random Pokémon

Drawn among the species that spawn naturally in the Safari, at the lowest level they spawn at there.

| Key | Default | What it does |
|---|---|---|
| `enabled` | `true` | `false`: the shop only sells its fixed entries. |
| `count` | `15` | Pokémon drawn per rotation, 0 to 32. |
| `tab` | `"pokemon"` | Tab showing them. |
| `rarities` | `["common", "uncommon", "rare"]` | Rarities they are drawn from, as Cobblemon spawn buckets: `common`, `uncommon`, `rare`, `ultra-rare`. |
| `commonPrice` | `250` | Price of a first-stage common Pokémon. A second stage costs twice as much, a third stage three times; a species that does not evolve counts as a second stage. Prices are rounded to 50. |
| `uncommonPrice` | `500` | Same, uncommon. |
| `rarePrice` | `500` | Same, rare. |
| `ultraRarePrice` | `2500` | Same, ultra-rare. |
| `excludeSpecials` | `true` | Leaves out today's specials, so the shop never sells what players are hunting. |
| `allowSpecialSpecies` | `false` | Allows legendary, mythical, ultra beast and paradox species. |
| `excludedSpecies` | `[]` | Species never drawn, e.g. `["cobblemon:ditto"]`. |

A Pokémon the fixed entries already sell (same species and form) is never drawn.

### `rotatingItems` and `rotatingBalls`: random goods

Both sections have the same keys. Only items matched by a `prices` rule can be drawn: the rules are the catalogue and the price list at once.

| Key | Default (items / balls) | What it does |
|---|---|---|
| `enabled` | `true` / `true` | Master switch. |
| `count` | `8` / `6` | Items drawn per rotation, on top of `always`. 0 to 32. |
| `tab` | `"items"` / `"balls"` | Tab showing them. |
| `always` | `[]` / `["cobblemon:safari_ball"]` | Items sold every rotation whatever the draw. Each still needs a `prices` rule. |
| `excluded` | Ability Patch, teapots and teacups, bone, snowball / tier 5 balls, Cherish Ball | Items never drawn: ids or `#tags`. Items the fixed entries sell are left out too. |
| `prices` | 29 rules / 5 rules | See below. |

Each rule of `prices`:

| Key | What it does |
|---|---|
| `item` | An item id, or a `#tag` for every item of the tag. |
| `count` | Stack sold per purchase, 1 to 64. |
| `price` | Price of the stack, in Safari points. |

The first rule matching an item wins, so specific items go before the tags that hold them (a Max Potion before `#cobblemon:potions`). The default rules price Cobblemon's medicine, candies, mints, battle items, gems, evolution items and held items, and its balls by tier.

---

## `client.json`: each player's display

These settings belong to each player; a server cannot change them.

| Key | Default | What it does |
|---|---|---|
| `hudEnabled` | `true` | Shows the Safari timer. |
| `hudAnchor` | `"BOTTOM_LEFT"` | Corner or edge the timer is attached to: `TOP_LEFT`, `TOP_CENTER`, `TOP_RIGHT`, `BOTTOM_LEFT`, `BOTTOM_CENTER`, `BOTTOM_RIGHT`. |
| `hudOffsetX` | `20` | Horizontal offset of the middle of the timer from the anchor, in GUI pixels. |
| `hudOffsetY` | `-16` | Vertical offset, in GUI pixels (negative is up). The timer always stays on screen. |
| `lowTimeSeconds` | `60` | Below this many seconds the time turns amber. Never below `criticalTimeSeconds`. |
| `criticalTimeSeconds` | `10` | Below this many seconds the time turns red and pulses. |
| `board` | see below | Look of the Safari Wall Screens. |

### `board`: wall screens

| Key | Default | What it does |
|---|---|---|
| `useSprites` | `false` | Draws the Pokémon as the mod's flat pixel sprites instead of turning 3D Cobblemon models. A Pokémon without a sprite keeps its model. Resource packs can replace the sprites (`assets/cobblemon_safari/textures/pokemon_sprites/`). |
| `backgroundImage` | `true` | Shows the safari landscape of the time of day behind the screen. `false` uses the plain `colors.background`. |
| `panelOpacity` | `0.72` | Opacity of the title band, cells and bars over the landscape, from 0 (invisible) to 1 (opaque). |
| `featuredFooter` | `"SESSION"` | What the bar at the bottom of the specials screens counts down: `"SESSION"`, your hunting time while you hunt (the next rotation otherwise), or `"ROTATION"`, always the next rotation. |
| `statsFooter` | `"ROTATION"` | Same, for the record screens. |
| `bountiesFooter` | `"SESSION"` | Same, for the missions screens. |
| `colors` | see below | Every colour of the screens. |

### `board.colors`

Colours are `"#RRGGBB"`, or `"#AARRGGBB"` with an alpha. A colour with its own alpha keeps it whatever `panelOpacity` says. An invalid colour falls back to its default.

| Key | Default | Colour of |
|---|---|---|
| `bezel` | `"#1A1C22"` | The thin border around the picture |
| `background` | `"#0A1220"` | The screen background, when `backgroundImage` is off |
| `header` | `"#13213B"` | The title band |
| `title` | `"#FFB000"` | The title text |
| `cell` | `"#111D33"` | Each Pokémon's cell |
| `text` | `"#FFFFFF"` | Pokémon names |
| `muted` | `"#8C9BB5"` | Secondary text: habitat, LIVE label, page counter, bar label |
| `star` | `"#FFD700"` | The star in front of legendary-class species |
| `liveOn` | `"#FF3B3B"` | The LIVE light, lit |
| `liveOff` | `"#5A1A1A"` | The LIVE light, unlit |
| `barBackground` | `"#1E2B45"` | The empty part of the progress bar |
| `barRotation` | `"#35C8E8"` | The bar and time counting down to the next rotation |
| `barSessionOk` | `"#4CD964"` | The bar and time of your own hunt, plenty of time left |
| `barSessionLow` | `"#FFC04D"` | Same, below `lowTimeSeconds` |
| `barSessionCritical` | `"#FF5555"` | Same, below `criticalTimeSeconds` |
| `rarityCommon` | `"#FFFFFF"` | Rarity strip and name, common |
| `rarityUncommon` | `"#55FF55"` | Same, uncommon |
| `rarityRare` | `"#55FFFF"` | Same, rare |
| `rarityUltraRare` | `"#FF55FF"` | Same, ultra-rare |
| `scanline` | `"#12FFFFFF"` | The light band rolling down the screen (mostly transparent) |
| `shimmer` | `"#50FFFFFF"` | The light sweeping along the progress bar |
| `flash` | `"#90FFFFFF"` | The flash when the list changes (its alpha is the flash's peak) |

---

## What is not in the files

Some things are fixed on purpose, or live in data packs:

- **The island**: its size, border, arrival point and rest houses are generated with the terrain and always match it.
- **The dimension id**: `cobblemon_safari:safari`.
- **Gate frequency and biomes**: `data/cobblemon_safari/worldgen/structure_set/safari_gate.json` and the biome tag `has_structure/safari_gate`, in a datapack.
- **Pokémon of the park**: Cobblemon's spawn files. Target the Safari only with its biome tags, e.g. `"biomes": ["#cobblemon_safari:safari_forest"]`.
- **NPCs and their dialogues**: `data/cobblemon_safari/npcs/` and `dialogues/`, in a datapack.

See [TECHNICAL.md](TECHNICAL.md) for the details, and [`example/`](example) for a sample datapack and resource pack.
