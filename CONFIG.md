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
- **`configVersion`** is the format version of the file (`16` for `server.json`, `4` for `shop.json`, `2` for `client.json`). It is not a setting: the mod writes it. A file written by an older version of the mod is converted on load, whatever its version, and every key it moves is named in the log: see [Older files](#older-files) at the end.

Formats used below:

- **Ids** are written `namespace:path`, e.g. `cobblemon:safari_ball`, `minecraft:torch`.
- **Item tags** are ids prefixed with `#`, e.g. `#cobblemon:apricorns`. Where a tag is accepted, any item of the tag counts.
- **Cobblemon properties** are the strings of Cobblemon's `/spawnpokemon` and `/givepokemon`, e.g. `"tauros level=45"`, `"eevee shiny=yes"`, `"vulpix alolan level=20"`.
- **Times** are in seconds unless the key says otherwise (`rotationMinutes`, `cooldownHours`).

A complete, commented example of the three files is in [`example/config/`](example/config).

---

## `server.json`: rules of the Safari

The sections, in the order of the file: `hunt`, `tickets`, `gate`, `balls`, `battles`, `capture`, `protection`, `spawns`, `specials`, `events`, `points`, `restHouses`, `escapeRope`, `reset`, `missions`.

### `hunt`: the hunt and its clock

| Key | Default | What it does |
|---|---|---|
| `maxDurationSeconds` | `7200` (2 h) | Longest a hunt can last. Adding time never goes above it, and one entry spends at most this much banked time: the rest stays in the bank. At least 1. |
| `pauseWhileOffline` | `true` | `true`: the clock only runs while the player is online and inside. `false`: it keeps running while they are offline, and they are escorted out at their next login if it ran out. |
| `warningsAtSeconds` | `[900, 600, 300, 60, 30, 10]` | Time left, in seconds, at which the player gets a chat warning. The colour follows the time left: white, yellow from 5 minutes, red from 1 minute. Zero, negative and duplicate values are dropped. |
| `exitDestination` | `"RETURN_POINT"` | Where players go when the hunt ends. `"RETURN_POINT"`: exactly where they entered from. `"WORLD_SPAWN"`: the overworld spawn, for a hub server. |
| `exemptCreativeAndSpectator` | `true` | For building and moderation, creative and spectator players: may roam inside without a session; may enter through a gate, its warden or `/safari enter` with an empty time bank (the session then holds no time, and ends as soon as they switch to survival or adventure); keep their clock stopped while they stay in creative or spectator, banked hunt time included; ignore the terrain protection. Does not apply at login: a player who logs in inside without a session is always sent out. |

### `tickets`: Safari Tickets

A Safari Ticket used outside the Safari banks its time; entering spends the bank. Used inside, it extends the running hunt.

| Key | Default | What it does |
|---|---|---|
| `defaultDurationSeconds` | `1800` (30 min) | Time a ticket adds. A ticket can carry its own length instead: `/give @p cobblemon_safari:safari_ticket[custom_data={duration_seconds:600}]`. At least 1. |
| `minAddedSeconds` | `1200` (20 min) | Least time a ticket may add. When the bank or the hunt is nearly full, a ticket adds only what fits; if that is less than this, the ticket is refused and kept. 0 accepts any amount. |
| `addToCurrentHunt` | `true` | Using a ticket inside adds its time to the running hunt (up to `hunt.maxDurationSeconds`). `false` refuses tickets inside. |
| `maxBankedSeconds` | `14400` (4 h) | Most Safari time a player can bank. |
| `refundUnusedTimeOnLeave` | `true` | When a hunt paid with banked time ends early because the player left through the clerk, died inside, or was taken out by other means (a `/tp`, a home or waystone command, another mod's teleport), the unused time goes back to the bank. Time running out, or an operator ending the hunt, never refunds. |
| `loot` | see below | Tickets found in chests. |

#### `tickets.loot`: tickets in chests

Read when loot tables load: after a change, `/reload` (or restart) for chests generated afterwards.

| Key | Default | What it does |
|---|---|---|
| `enabled` | `true` | Master switch. |
| `sources` | 5 sources, see below | Loot tables that may hold a ticket, each with its chance per chest. |

Each element of `sources`:

| Key | What it does |
|---|---|
| `table` | Loot table id, e.g. `minecraft:chests/desert_pyramid`, or a folder ending in `/` for every table in it, e.g. `cobblemon:ruins/common/`. Tables of other mods work too. |
| `chance` | Chance per chest, from 0 to 1. When a table matches several sources, the exact one wins, then the longest folder. |

The defaults: every village chest (`minecraft:chests/village/`, the 16 tables of houses, smiths, temple, fisher and so on: `0.04`), savanna houses a little more (`0.10`), desert pyramids and jungle temples (`0.12`), Cobblemon ruins (`0.18`). A file of version 4 or older that still holds the earlier defaults (village houses only, `0.05` to `0.15`) gets these on load; an edited list is kept. Chests inside the Safari never hold tickets, whatever is listed.

### `gate`: Safari Gates

How often gates generate is set by the datapack (`data/cobblemon_safari/worldgen/structure_set/safari_gate.json`), not here.

| Key | Default | What it does |
|---|---|---|
| `allowEntry` | `true` | Standing in a gate, or asking its warden, starts a hunt. `false` makes gates decoration only. |
| `spawnWarden` | `true` | Keeps a Safari Warden next to each generated gate. Gates placed by hand never have one. A warden removed with `/kill` comes back within a few seconds while a player is near. |
| `companions` | `["kangaskhan level=30", "tauros level=30", "chansey level=30"]` | The warden's Pokémon roaming around each gate, as Cobblemon properties, one per entry. They cannot be caught, battled or hurt. Empty for none; 8 at most. |
| `roamRadius` | `8` | How far, in blocks, they may wander from the gate before walking back (teleported back beyond twice this). 3 to 24. The warden himself keeps to a few blocks around his spot. |

### `balls`: Poké Balls

| Key | Default | What it does |
|---|---|---|
| `allowed` | `["cobblemon:safari_ball", "cobblemon:master_ball"]` | Poké Balls that work inside the Safari, by Cobblemon id. Any other ball is refused when thrown. An empty list means no capture at all (the log warns about it). |
| `loan` | see below | Balls lent on entry. |

#### `balls.loan`

The original games' loan of Safari Balls, off by default: players bring their own allowed balls, and the shop gives 32 free Safari Balls a day.

| Key | Default | What it does |
|---|---|---|
| `count` | `0` | Balls lent to each player on entry; 0 turns the loan off. Lent balls are marked, cannot be thrown outside the Safari, and are taken back on exit or as soon as the player is found outside with one. 0 to 256. |
| `ballItem` | `"cobblemon:safari_ball"` | The ball lent. It always works inside, even if it is missing from `balls.allowed`. It is also the ball that gets the `capture` bonus. |

### `battles`

| Key | Default | What it does |
|---|---|---|
| `enabled` | `true` | Whether Pokémon battles may start inside. `false` forbids them, as in the original games, but then there is no weakening a Pokémon before throwing. |
| `fishingStartsBattle` | `false` | Whether a Pokémon fished inside starts a battle with the angler, as Cobblemon does one second after the bite. `false`: the Pokémon is left free, to be caught with a ball out of battle (Safari Ball bonus included) or battled on purpose. |
| `graceSeconds` | `120` | Extra time a player gets when their clock hits zero in the middle of a battle, so the battle ends normally before they are sent out. 0 or more. |

### `capture`

| Key | Default | What it does |
|---|---|---|
| `safariBallOutOfBattleMultiplier` | `3.0` | Multiplies the catch rate when a Safari Ball (`balls.loan.ballItem`) hits a wild Pokémon that is not in a battle, inside the Safari. Cobblemon halves every capture made outside a battle, so at 3 the Safari Ball out of battle works 1.5 times as well as a normal throw in battle, before Cobblemon's own Safari Ball bonus. 1 turns it off; lower values are raised to 1. |
| `marks` | see below | Marks given to the Pokémon caught in the Safari. |
| `flee` | see below | Pokémon running away after a failed ball. |

#### `capture.marks`: marks of the Safari

Every Pokémon caught in the Safari gets the Safari Mark ("the Safari Find"); one caught at a remarkable moment also gets that moment's mark, whose title becomes the active one in place of the Safari Mark's (a Cobblemon weather or personality title stays). `false` stops giving that mark; marks already given stay.

| Key | Default | Mark |
|---|---|---|
| `safari` | `true` | "the Safari Find": every Pokémon caught in the Safari. Its title becomes the active one when the Pokémon has none. |
| `special` | `true` | "the Star of the Day": one of the specials of the rotation. |
| `swarm` | `true` | "of the Swarm": the swarming species, caught in the swarm's habitat (see `events.swarm`). |
| `fullMoon` | `true` | "of the Full Moon": caught on a full moon night. |
| `downpour` | `true` | "of the Downpour": caught where a downpour falls (see `events.downpour`). |
| `storm` | `true` | "of the Storm": a Pokémon a storm brought (see `events.storm`). |
| `aurora` | `true` | "of the Aurora": a Pokémon an aurora brought (see `events.aurora`); not the natural ones caught during it. |
| `angler` | `true` | "the Safari Catch": fished with a rod in the Safari, then caught. |
| `nest` | `true` | "of the Nest": hatched from an egg found in a Safari nest (see `events.eggNests`). Given at the hatch, wherever it happens; such a Pokémon gets no Safari Mark, never having been caught. |

#### `capture.flee`: Pokémon running away

After a ball fails on a wild Pokémon outside of a battle, inside the Safari, the Pokémon may run away (it vanishes in a puff, and the thrower is told), as in the Safari Zone of the games. Never in a battle.

| Key | Default | What it does |
|---|---|---|
| `enabled` | `true` | `false`: no Pokémon ever runs away. |
| `perFailedBall` | `0.05` | Added to the chance for each ball that already failed on the same Pokémon: at 0.05, the fourth failed ball on a rare one is a 25 % risk. 0 keeps it flat. 0 to 1, shared by every entry of `rarities`. |
| `maxChance` | `0.35` | Highest chance, whatever the entry and the failed balls. 0 to 1. |
| `shinies` | `false` | Whether shiny Pokémon may run away. |
| `rarities` | see below | One entry per kind of Pokémon: whether it may run away, and how likely. |

`capture.flee.rarities` holds eight entries, each `{ "enabled": ..., "chance": ... }`. `enabled: false` keeps those Pokémon from ever running away; `chance` (0 to 1) is the chance after the first failed ball, before `perFailedBall` and `maxChance`.

The first four are the rarities of the Safari (a species the Safari does not know counts as common). The last four are categories, and take precedence: a legendary, mythical, ultra beast or paradox Pokémon follows its category's entry only, never its rarity's, so a legendary the Safari lists as ultra-rare follows `legendary`. A Pokémon in two categories stays as soon as one of them is disabled, and otherwise takes the higher chance of the two.

| Entry | `enabled` | `chance` |
|---|---|---|
| `common` | `true` | `0.05` |
| `uncommon` | `true` | `0.08` |
| `rare` | `true` | `0.10` |
| `ultraRare` | `true` | `0.12` |
| `legendary` | `false` | `0.25` |
| `mythical` | `false` | `0.25` |
| `ultraBeast` | `false` | `0.20` |
| `paradox` | `false` | `0.18` |

A key left out of an entry takes that entry's default.

### `protection`

| Key | Default | What it does |
|---|---|---|
| `invulnerablePlayers` | `true` | Players inside take no damage. `/kill` and falling out of the world still apply, so a player in the void does not fall forever. |
| `invulnerablePokemon` | `true` | Pokémon inside, wild or sent out, take no damage: they cannot be killed with a sword or a bow, and are spared falls, lava and mobs. Battles are not affected. `/kill` and falling out of the world still apply. |
| `hunger` | `false` | Whether hunger is frozen inside. `true`: the food bar and its saturation stay where they were on entering (eating still fills them). `false`: the bar goes down as everywhere else. |
| `protectBlocks` | `true` | Players cannot break or place blocks, fill or empty buckets, or use tools that change blocks (hoe, shovel, axe stripping, flint and steel, bone meal). Fire does not spread or burn blocks, and explosions break nothing. |
| `placeableBlocks` | `["cobblemon:poke_cake", "cobblemon:poke_snack"]` | Blocks players may still place and break while `protectBlocks` is on. By default Cobblemon's baits. Anything placed disappears with the daily map. |
| `interact` | see below | Which Cobblemon plants players may still harvest while `protectBlocks` is on. |

`protection.interact`: one switch per Cobblemon plant. The first three are picked with a click and grow back, so they are allowed by default; the others are harvested by breaking them, which leaves nothing for the next player until the daily map, so they are protected by default. Only read while `protectBlocks` is on. Holding a block or a block-changing tool against a plant is still refused, whatever the switch.

| Key | Default | What it allows |
|---|---|---|
| `berries` | `true` | Picking ripe berries with a right-click. |
| `apricorns` | `true` | Picking ripe apricorns with a right- or left-click. |
| `galaricaNuts` | `true` | Picking ripe Galarica nuts with a right-click. |
| `mints` | `false` | Breaking mint plants (their harvest). |
| `revivalHerbs` | `false` | Breaking Revival Herbs. |
| `medicinalLeeks` | `false` | Breaking Medicinal Leeks. |
| `vivichokes` | `false` | Breaking Vivichokes. |
| `heartyGrains` | `false` | Breaking Hearty Grains. |
| `bugwort` | `false` | Breaking Bugwort. |
| `roots` | `false` | Shearing big and energy roots (which leaves a smaller root and drops one) and breaking them. |

Turning off one of the first three refuses every click on that plant.

### Species rules: `spawns` and `specials`

Pokémon come into the Safari two ways, and each has its own species rules:

- **`spawns`** for Cobblemon's natural spawns;
- **`specials`** for the Pokémon of the day: what the wall screens may draw, and what the mod's own spawner brings in.

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
| `maxSpawnPerPokemon` | Most Pokémon of each species of this category coming in this way per rotation, counting those caught in the Safari plus those still roaming: with 2, once two Rayquaza are caught, no third comes in this way before the next rotation. One that leaves uncaught (despawned, fled, knocked out) gives its place back within about ten seconds. 0 lifts the cap. The swarm is not held by this cap: see `events.swarm.rarities`. |

The two ways count apart: with a cap of `1` in both sections, one Rayquaza may come from the natural spawns and another from the specials in the same rotation.

These rules only cover the Safari: they never change what spawns in the rest of the world. The missions follow `spawns` (they are met with natural Pokémon); the shop has its own rules (`rotatingPokemon` in `shop.json`, with its own `categories`).

A species refused by `spawns` is taken out of Cobblemon's spawn draw itself, so it no longer shows in `/checkspawn` there, and one with a huge weight (a datapack spawning Mew as common, say) cannot starve the other species. Species at their `maxSpawnPerPokemon`, or already `maxSameSpeciesNearby` times around the player, leave the draw the same way, until a slot frees up.

### `spawns`: Cobblemon's natural spawns

On top of the species rules above:

| Key | Default | What it does |
|---|---|---|
| `nativeSpawns` | `true` | Lets Cobblemon's natural spawner work in the Safari. `false` leaves only the specials, and the park very quiet. |
| `maxSameSpeciesNearby` | `4` | No natural spawn of a species when this many of it already roam nearby, so one common species cannot flood a habitat. 0 turns the cap off. |
| `spawnRateMultiplier` | `2.0` | How much faster Cobblemon's spawner works for players in the Safari. 1 is Cobblemon's own pace. Higher costs more server time. 0.1 to 10. |
| `pokemonPerChunkMultiplier` | `2.0` | Multiplies Cobblemon's Pokémon-per-chunk cap for players in the Safari, so faster spawning is not wasted on a full cap. 0.1 to 10. |
| `caughtSpeciesWeight` | `0.3` | Weight of the species a player already caught in the Safari, in the natural spawns around them (against 1 for species they have not). At 0.3 a new species is more than three times as likely as a caught one. 1 turns it off; 0 stops caught species entirely while anything else can spawn. 0 to 1. |
| `snackClearRadius` | `16` | Distance from a Poké Snack, in blocks, within which no natural Pokémon spawns, so the snack has room to bring its own. Larger empties more of the surroundings; smaller lets the natural Pokémon fill the snack's area again. 0 turns the clearing off. 0 to 32. |
| `snackDensityMultiplier` | `1.5` | Cap of a Poké Snack's spawner, as a multiple of the Safari's density (Cobblemon's Pokémon per chunk times `pokemonPerChunkMultiplier`). Above 1 a snack still brings Pokémon in a full area; higher packs more around it. 1 to 4. |

Cobblemon's `/gamerule doPokemonSpawning false` stops every spawn in the Safari, specials included.

### `specials`: the Pokémon of the day

A few species, uncommon, rare and ultra-rare by default, drawn from what is installed, shown on the wall screens and spawned by the mod in their own habitat. On top of the species rules above:

| Key | Default | What it does |
|---|---|---|
| `enabled` | `true` | Master switch for the rotation, the wall screens' specials page and the special spawner. |
| `rotationMinutes` | `240` (4 h) | Length of a rotation, in real minutes. When it divides a day, rotations are aligned on midnight UTC: 240 changes the specials at 0, 4, 8, 12, 16 and 20 h UTC. Missions, the shop's random goods and the purchase limits follow the same rotation. At least 1. |
| `slots` | see below | How many specials of each rarity a rotation draws. 64 in all at most (what the clients read): above, each rarity is cut to 16, with a warning. |
| `spawn` | see below | How the mod brings the specials into the park. |

`specials.slots`: species drawn per rotation, by rarity in the Safari. 0 or more.

| Key | Default | What it does |
|---|---|---|
| `common` | `0` | Common species. None by default: the specials are what is worth a trip. Commons only ever fill their own slots. |
| `uncommon` | `1` | Uncommon species. |
| `rare` | `2` | Rare species. |
| `ultraRare` | `3` | Ultra-rare species; legendaries land here when `specials.legendary` allows them. A slot a rarity cannot fill goes to the next more common one, down to uncommon. |

`specials.spawn`: every `checkEverySeconds`, for each player hunting in the Safari, the mod may bring one special near them, in its own habitat.

| Key | Default | What it does |
|---|---|---|
| `checkEverySeconds` | `15` | Seconds between two attempts around each player. At least 1. |
| `chance` | `0.35` | Chance that an attempt goes ahead, 0 to 1. |
| `maxAroundPlayer` | `2` | Specials allowed around one player at once (never two of the same species). |
| `weights` | `common` 0, `uncommon` 10, `rare` 4, `ultraRare` 1 | When an attempt goes ahead, which of the specials living there comes, by rarity: with the defaults, an uncommon one comes ten times as often as an ultra-rare one. 0 never brings that rarity (the log warns when a rarity has slots but a weight of 0: its specials would show on the board and never appear). |

After `/safari reload`, the specials are redrawn with the new rules right away.

`/safari rotate` starts a new rotation now, which lasts a whole `rotationMinutes` from then (the following rotations keep that pace, no longer aligned on midnight UTC); `/safari today` lists the current specials.

### `events`: swarms, full moon nights, downpours, storms, heatwaves, auroras and egg nests

Rare happenings that change Cobblemon's natural spawns in the Safari for a while, announced in the chat (with a chime) to the players in the Safari.

**`events.swarm`**: with each rotation of the specials, maybe, one species of the Safari comes out in numbers in one of its habitats until the next rotation. It also shows on the specials board (wall screens and `/safari today`). Only species a player's spawner can bring there are drawn: spawns that need a structure or a fishing rod do not count. A species met there only through Poké Snacks may swarm too: the swarm's own spawner (`minNearby`) lifts that one requirement for it, so with `minNearby` at 0 such a swarm shows nothing. Species in `spawns.excludedSpecies` never swarm. Legendary-class species count as `ultra-rare` and follow `events.swarm.rarities` instead of the categories of `spawns`: a swarm is neither stopped by `spawns.legendary` nor uses up its slots.

| Key | Default | What it does |
|---|---|---|
| `enabled` | `true` | `false`: no swarm. |
| `chance` | `0.5` | Chance (0 to 1) that a rotation has a swarm. |
| `buckets` | `["uncommon", "rare", "ultra-rare"]` | Spawn buckets a swarm may be drawn from: `common`, `uncommon`, `rare`, `ultra-rare`. A species' bucket is its most common one in the habitat considered. Empty: no swarm. |
| `rarities` | `allowSpawn`: `false` (`paradox`: `true`), `maxSpawnPerPokemon`: `0` | Legendary-class categories in swarms: `legendary`, `mythical`, `ultraBeast`, `paradox`, with the same two keys as in `spawns`, for the swarm alone (the swarming species in its habitat, whichever spawner brings it). `allowSpawn`: the category may swarm, as an `ultra-rare` swarm (`ultra-rare` must be in `buckets`). `maxSpawnPerPokemon`: most Pokémon of the swarming species a swarm brings in a rotation, counting those caught plus those still roaming; one that leaves uncaught gives its place back within about ten seconds. 0: no cap but `maxNearby`. Swarm spawns and catches never use up the natural slots of `spawns`. |
| `weights` | `0` / `10` / `4` / `1` | Chance of each bucket (`common`, `uncommon`, `rare`, `ultraRare`) being the swarm's, against the others: by default two swarms in three are uncommon. Per bucket, not per species: the bucket is drawn first, then a species in it. 0 never draws that bucket; a bucket with no candidate leaves its turn to the others. |
| `excludeList` | `[]` | Pokémon that never swarm, as Cobblemon properties. `"arceus"` leaves out the species and all its forms; `"vulpix alolan"` only Alolan Vulpix, Vulpix can still swarm. Unknown species or forms are named in the log and ignored. A form only given when the Pokémon is created (`region_bias=...` in the spawn files) can only be left out with its species. |
| `weightMultiplier` | `10.0` | In its habitat, the swarm is also offered among the common Pokémon of Cobblemon's natural spawns, and its weight is multiplied by this. How much of the habitat it makes up depends on what else lives there; `minNearby` gives the floor. 1 to 100. |
| `maxNearby` | `10` | Same-species cap around a player for the swarming species, instead of `spawns.maxSameSpeciesNearby`, so a swarm can look like one. 0 keeps the usual cap. |
| `minNearby` | `4` | Swarm Pokémon the swarm's own spawner keeps within 48 blocks of each hunting player standing in its habitat, whatever else lives there, through Cobblemon's spawn rules (hour, light, kind of spot) and the Safari's caps. Never above `maxNearby`. 0 turns it off (Cobblemon's natural spawns only, as in 1.4.0). |
| `checkEverySeconds` | `5` | Seconds between two top-ups of the swarm's spawner, one Pokémon per player at most each time. 1 to 300. |

**`events.fullMoon`**: on full moon nights in the Safari, Pokémon of some types come out more.

| Key | Default | What it does |
|---|---|---|
| `enabled` | `true` | `false`: no full moon effect. |
| `types` | `["fairy", "ghost", "dark", "psychic"]` | Cobblemon type names favoured. |
| `weightMultiplier` | `3.0` | How much more often those Pokémon are picked, within their rarity. 1 to 20. |

**`events.downpour`**: some rains of the Safari turn into a downpour, during which Pokémon of some types come out more where the rain falls (not in snow, nor in the dunes and the volcano, which get no rain, nor under a roof), and catches made there get the Downpour Mark. The rain is the Safari's ordinary weather (the overworld's): the mod never makes it rain. Each time a rain starts, a draw decides whether it is a downpour; it then lasts a drawn time, or less if the rain stops first. Players in the Safari are told when it starts (or when they come in during one) and when it ends, and it shows in `/safari today` while it lasts. A server restart ends it, and a rain already falling when the server starts is never drawn.

| Key | Default | What it does |
|---|---|---|
| `enabled` | `true` | `false`: no downpour. |
| `chance` | `0.15` | Chance (0 to 1) that a rain starting in the Safari is a downpour. |
| `minDurationMinutes` | `5` | Shortest downpour, in real minutes. 1 to 120. |
| `maxDurationMinutes` | `10` | Longest downpour, in real minutes, at least `minDurationMinutes`. 1 to 120. A rain lasts about 10 to 20 minutes. |
| `types` | `["water", "electric"]` | Cobblemon type names favoured. |
| `weightMultiplier` | `2.0` | How much more often those Pokémon are picked, within their rarity. 1 to 20. |

**`events.storm`**: some thunderstorms bring rare Pokémon of some types to the high ground: the storm's own spawner brings them near the hunting players standing in one of `habitats` or high enough above the sea, each with a harmless lightning flash, and a Pokémon the storm brought gets the Storm Mark when caught. Its species are the Safari's own rare and ultra-rare natural species of those types (three rare for one ultra-rare), whatever their usual habitat, under the natural spawns' rules: `spawns.excludedSpecies` never come, and legendary-class species follow `spawns.legendary`, `mythical`, `ultraBeast` and `paradox` (switch and cap), the storm's catches counted apart from the natural ones. The thunderstorm is the Safari's ordinary weather: as for the downpour, a draw at each thunderstorm start, a drawn duration, ended by the thunder stopping or a restart. It adds up with a downpour, a thunderstorm being a rain too. Its Pokémon never use up the natural slots of `spawns`.

| Key | Default | What it does |
|---|---|---|
| `enabled` | `true` | `false`: no storm event. |
| `chance` | `0.5` | Chance (0 to 1) that a thunderstorm starting in the Safari brings the storm event. |
| `minDurationMinutes` | `3` | Shortest storm event, in real minutes. 1 to 120. |
| `maxDurationMinutes` | `8` | Longest storm event, in real minutes, at least `minDurationMinutes`. 1 to 120. A thunderstorm lasts about 3 to 13 minutes. |
| `types` | `["electric", "dragon"]` | Cobblemon type names of the Pokémon the storm brings. |
| `excludeList` | `[]` | Pokémon the storm never brings, as Cobblemon properties: `"dragonite"`, or one form, `"vulpix alolan"`. |
| `habitats` | `["cobblemon_safari:peaks", "cobblemon_safari:rocky_hills", "cobblemon_safari:volcanic"]` | Habitats where the storm brings its Pokémon, by biome id. |
| `minAltitudeAboveSea` | `45` | Blocks above the sea from which any spot counts as high ground too, for a `ROLLING` map; scaled with `reset.relief` (about 16 on `FLAT`, 29 on `GENTLE`, 72 on `MOUNTAINOUS`). 0: the whole island. 0 to 256. |
| `checkEverySeconds` | `15` | Seconds between two spawn attempts around each player. 1 to 300. |
| `spawnChance` | `0.25` | Chance (0 to 1) that an attempt brings a Pokémon. |
| `maxNearby` | `2` | Most storm Pokémon within 64 blocks of a player; none is brought beyond. |
| `lightning` | `true` | A harmless lightning flash (no damage, no fire) where a storm Pokémon appears. |
| `lightningCooldownSeconds` | `30` | Shortest time between two of those flashes in the whole Safari, so several players do not set off a barrage. 0 to 600. |

**`events.heatwave`**: at each dawn without rain, a draw may make the day a heatwave: Pokémon of some types come out more in the hot habitats, within their rarity. It only weighs Cobblemon's own spawns there: no species appears that does not already live there. It lasts a drawn time and ends sooner when rain comes or night falls; rain stopping later the same day does not draw again. Announced to the players in the Safari and shown in `/safari today`; no mark. A server restart ends it, and the first dawn after a start is drawn normally (the day already begun when the server starts is not).

| Key | Default | What it does |
|---|---|---|
| `enabled` | `true` | `false`: no heatwave. |
| `chance` | `0.15` | Chance (0 to 1) that a dawn without rain brings a heatwave. |
| `minDurationMinutes` | `4` | Shortest heatwave, in real minutes. 1 to 120. |
| `maxDurationMinutes` | `8` | Longest heatwave, in real minutes, at least `minDurationMinutes`. 1 to 120. A day lasts 10 minutes. |
| `types` | `["fire", "ground"]` | Cobblemon type names favoured. |
| `habitats` | `["cobblemon_safari:dunes", "cobblemon_safari:volcanic", "cobblemon_safari:badlands", "cobblemon_safari:savanna"]` | Habitats the heatwave bakes, by biome id. |
| `weightMultiplier` | `2.0` | How much more often those Pokémon are picked there, within their rarity. 1 to 20. |

**`events.aurora`**: at each nightfall without rain, a draw may light an aurora over the Safari. Each hunting player is then owed two Pokémon, one per group: one of `frozenTypes` on the frozen lands (one of `frozenHabitats`, or anywhere snow falls: cold biomes, and high ground cold enough for snow) and one of `otherTypes` anywhere else. Each appears 16 to 32 blocks away, on ground of its own group, as soon as the player stands where that group lives, retried every 3 seconds until it does or the aurora ends. One per player, group and aurora, kept by player for the whole aurora (leaving and coming back neither loses nor renews it). The frozen group draws the Safari's own uncommon, rare and ultra-rare natural species of its types (five uncommon for three rare and one ultra-rare: Cobblemon has almost no rare Ice species, so rare-only would always bring Glaceon); the other group rare and ultra-rare only (three to one), Psychic and Fairy having many. Both follow the natural spawns' rules (`spawns.excludedSpecies`, legendary-class switches and caps, counted apart from the natural ones). The pick is the player's own: never the species they got of that group at their previous aurora (unless nothing else is left), and the species they already caught in the Safari weigh `spawns.caughtSpeciesWeight`, as in the natural spawns. It glows for a while, the player is told, and it gets the Aurora Mark when caught. **While the aurora lasts, every Pokémon appearing in the Safari is `shinyChanceMultiplier` times more likely to be shiny**: natural spawns, specials, swarms, the events' own Pokémon, fished ones (any Pokémon Cobblemon rolls for a player standing in the Safari). The aurora lasts a drawn time and ends sooner at dawn or when rain comes. Announced and shown in `/safari today`.

| Key | Default | What it does |
|---|---|---|
| `enabled` | `true` | `false`: no aurora. |
| `chance` | `0.1` | Chance (0 to 1) that a nightfall without rain brings an aurora. |
| `minDurationMinutes` | `3` | Shortest aurora, in real minutes. 1 to 120. |
| `maxDurationMinutes` | `6` | Longest aurora, in real minutes, at least `minDurationMinutes`. 1 to 120. A night lasts about 7 minutes. |
| `frozenTypes` | `["ice"]` | Cobblemon type names of the Pokémon the aurora brings on the frozen lands. |
| `otherTypes` | `["psychic", "fairy"]` | Cobblemon type names of the Pokémon the aurora brings everywhere else. |
| `shinyChanceMultiplier` | `4` | How many times more likely every Pokémon appearing in the Safari is to be shiny during an aurora: 4 turns Cobblemon's 1 in 8192 into 1 in 2048. Stacks with the Shiny Charm. 1 for no effect; no upper bound, 8192 or more makes every Pokémon shiny during an aurora. |
| `excludeList` | `[]` | Pokémon the aurora never brings, as Cobblemon properties: `"lapras"`, or one form, `"vulpix alolan"`. |
| `frozenHabitats` | `["cobblemon_safari:glacier", "cobblemon_safari:snowfield", "cobblemon_safari:taiga"]` | Biomes counted as frozen lands, by biome id, on top of any spot where snow falls: only useful for biomes where it rains (the taiga). It does not limit where the aurora acts. |
| `glowSeconds` | `20` | Seconds the Pokémon glows when it appears, so it can be found. 0 to 60. |

**`events.eggNests`**: a permanent event, never announced. Every `checkEverySeconds`, each hunting player gets a draw (`spawnChance`); on a success a straw nest holding a Pokémon egg appears on open, dry ground `minDistance` to `maxDistance` blocks away, unless another nest is already within `exclusionRadius` (only nests in loaded chunks are seen). A right click on the nest takes the egg: with `firstComeFirstServed` the nest is then gone, otherwise each player may take one egg from it. A nest disappears after `lifetimeMinutes`, taken or not, even across a restart. With the defaults, a player alone finds about one nest every 10 minutes.

The egg is of a Pokémon living in the habitat where the nest lies, as the specials board lists the habitats: an evolution line counts there as soon as one of its stages lives there (on land or in the water), every such line is as likely, and when none of them is allowed in that habitat no nest appears. The egg hatches into the line's first stage (a Pikachu line egg hatches a Pichu), standard form, at level 1, with the Nest Mark. A line is drawn when one of its species has egg groups and none of them is in `excludedEggGroups`, when none of its species is of a legendary-class category left off in `rarities`, and when its first stage is neither in `excludeList` nor in `spawns.excludedSpecies`. The egg hatches after `hatchMinutes` in the inventory of a connected player, anywhere (Safari or not; not in a chest); its tooltip shows the time left, never the species. A party with one of `hatchSpeedAbilities` hatches it `hatchSpeedMultiplier` times faster. The Pokémon is rolled at the hatch, with the player's shiny boosts of the moment (an aurora in the Safari, a Shiny Charm); it goes to the party, or the PC when the party is full; with no room anywhere, the egg waits and tries again a minute later.

| Key | Default | What it does |
|---|---|---|
| `enabled` | `true` | `false`: no new nest. The nests already out stay until their time is up. |
| `checkEverySeconds` | `30` | Seconds between two draws for each hunting player. 5 to 3600. |
| `spawnChance` | `0.05` | Chance (0 to 1) that a draw puts a nest near the player. |
| `minDistance` | `24` | Closest a new nest is to the player, in blocks. 8 to 96. |
| `maxDistance` | `48` | Farthest a new nest is from the player, in blocks, at least `minDistance`. 8 to 96. |
| `exclusionRadius` | `128` | No new nest within this many blocks of a nest already out. 16 to 512. |
| `lifetimeMinutes` | `20` | Minutes a nest stays out. 1 to 1440. |
| `firstComeFirstServed` | `true` | `true`: the first player to take the egg empties the nest. `false`: each player may take one egg per nest. |
| `excludedEggGroups` | `["undiscovered"]` | Egg groups never drawn: `monster`, `water_1`, `bug`, `flying`, `field`, `fairy`, `grass`, `human_like`, `water_3`, `mineral`, `amorphous`, `water_2`, `ditto`, `dragon`, `undiscovered`. |
| `rarities` | all `false` | `legendary`, `mythical`, `ultraBeast`, `paradox`: whether lines of that category may be drawn. Most are also Undiscovered, so they only come once `undiscovered` is out of `excludedEggGroups` too. |
| `excludeList` | `[]` | Species never drawn, by the species that hatches (the first stage): `"pichu"`. |
| `hatchMinutes` | `10` | Minutes an egg takes to hatch. Written on the egg when it is found: a change only affects eggs found afterwards. 1 to 1440. |
| `hatchSpeedAbilities` | `["flamebody"]` | Abilities, as Cobblemon names them, that warm the eggs carried by a player whose party has one (`"magmaarmor"`, `"steamengine"`...). |
| `hatchSpeedMultiplier` | `2` | How many times faster such a party hatches the eggs. 1 to 10. |

### `points`: Safari points

A currency separate from Safari time, earned with every capture inside and spent at the shop.

| Key | Default | What it does |
|---|---|---|
| `enabled` | `true` | `false`: captures earn nothing. The shop still accepts the points already earned. |
| `perCatch` | `common` 10, `uncommon` 20, `rare` 50, `ultraRare` 120 | Points for a capture, by the species' rarity in the Safari. |
| `specialBonus` | `50` | Extra points when the species is one of today's specials. |
| `shinyBonus` | `250` | Extra points for a shiny. |

With the default missions, the defaults give about 600 points per hour of active hunting. No value may be negative.

### `restHouses`: the rest houses

| Key | Default | What it does |
|---|---|---|
| `prebuild` | `true` | Builds every rest house of a new map in the background, one at a time, from 30 s after the server starts, so trips rarely wait. It pauses while the server is busy (ticks above 40 ms) and for 20 s after a player comes into the Safari, the entrance house aside. `false` builds a house the first time a player goes near it or travels to it. The entrance house is always built in the background right after the start, whatever this says. |
| `travel` | see below | The trips between the houses. |

#### `restHouses.travel`: trips between the rest houses

The Safari Guide of every rest house takes players to any other house of the island, named by its biome (with its direction, "Jungle (north)", when two houses share a biome). The Escape Rope can take them to the nearest one. Every trip is filmed while the destination loads, and a house nobody went near yet is built during it: a double of the player, wearing their skin, takes off on the first Pokémon of their party that can be ridden in the air (a copy of it: the party is not touched), or on a Staraptor when none can, and lands in front of the destination, filmed by the game camera; players around see it too. The guide's trips take off from the house's steps; the Escape Rope's from where it was used, and from a cave the player is first brought to the nearest safe open-air spot, within 12 blocks around, under a black screen. A trip into or out of a cave burrows, and the rope in the water swims, the same way. When the trip cannot be filmed (no open sky to take off, no open-air spot within 12 blocks of a cave, no flyer and no Staraptor), it shows a painted landscape for the guide and a pixel-art scene for the rope. How the trips are shown is not a setting.

| Key | Default | What it does |
|---|---|---|
| `guide` | `true` | The Safari Guide of every rest house. `false` removes the guides from the houses. |
| `cooldownSeconds` | `30` | Seconds before the same player can travel again, by the guide or by the rope: one wait for both, so the two cannot be chained. 0 removes the wait. At least 0. |
| `cinematicSeconds` | `3` | Shortest length of the cinematic, even when the destination is ready at once; longer while it loads or builds (45 s at most, then the trip is given up). 0 to 15. |

### `escapeRope`

| Key | Default | What it does |
|---|---|---|
| `enabled` | `true` | Lends an Escape Rope to every player entering, taken back on exit. Used in the Safari, it offers to climb to the nearest safe spot in the open air (from a cave) or to go to the nearest rest house. |
| `toRestHouse` | `true` | The rope's "nearest rest house" option, with the trip of `restHouses.travel` (its wait and cinematic). Climbing to the open air stays either way. |

### `reset`: the daily new map

Each new day (from `hour`), the Safari gets a brand new map with another layout. With `mode` `"LIVE"` (the default) it happens while the server runs, as soon as nobody is inside; if players stay, they get `maxWaitMinutes`, then a countdown of `countdownSeconds`, then they are sent out with their unused banked time given back (whatever `tickets.refundUnusedTimeOnLeave` and the bank's cap say) and can come back in a moment. With `"START"` it happens at the first server start of the new day, as before 1.5.0. A server that was down when the day changed resets at its start in both modes. Everything built or dropped inside is lost; statistics, banked time and points are kept.

| Key | Default | What it does |
|---|---|---|
| `enabled` | `true` | `false` keeps the same map forever. |
| `hour` | `4` | Hour (0 to 23) at which a new map day starts. With 4, a server restarted at 3:00 still belongs to the previous day and keeps its map. |
| `timeZone` | `""` | Time zone of `hour`, e.g. `"Europe/Paris"`. Empty uses the server's own. |
| `mode` | `"LIVE"` | `"LIVE"`: the reset happens while the server runs (see above). `"START"`: only at a server start. |
| `maxWaitMinutes` | `60` | LIVE: minutes a due reset waits for the Safari to empty before forcing it. 1 to 1440. |
| `countdownSeconds` | `300` | Seconds of warnings for the players inside before a forced reset (the wait over, or `/safari reset force`). 0 sends them out at once. 0 to 1800. |
| `relief` | `"GENTLE"` | How hilly each new map is. `"FLAT"`: low, gentle land, a few blocks above the sea, no peaks (height ×0.35). `"GENTLE"`: rolling but softer, the default since 1.4.0 (height ×0.65, peaks ×0.5): the plateaus' walls over the rivers lose a third of their height. `"ROLLING"`: the island as in earlier versions, with plateaus up to some forty blocks above the rivers. `"MOUNTAINOUS"`: higher land and sharper peaks (height ×1.6, peaks ×1.8). The coast and the sea floor do not move. Only a new map takes it: the map on disk keeps the relief it was generated with (new chunks would rise as cliffs against the old ones), and the log says so at start. To see a new relief now: `/safari reset force`. |

`/safari reset` makes a new map due now: at the next start in START mode, as soon as the Safari is empty in LIVE mode (with `enabled` on). `/safari reset force` renews the map right away, while the server runs, whatever the mode: at once if nobody is inside, else after the countdown.

---

### `missions`

`count` missions drawn with each rotation of the specials, the same for every player, each paid once per player. Each mission is of a kind drawn by weight among `allowedKinds`; a rotation draws different kinds first, and repeats a kind only when too few kinds can be drawn. A kind that cannot be met this rotation is skipped (no specials, no habitat with enough species...).

| Key | Default | What it does |
|---|---|---|
| `enabled` | `true` | Master switch for the missions, their rewards and their page on the wall screens. |
| `count` | `3` | Missions per rotation, 0 to 8. |
| `allowedKinds` | every kind but `HIDDEN_ABILITY` and `SHINY` | The kinds that may be drawn (table below). An unknown name is dropped with a warning. |
| `allMissionsBonusPoints` | `200` | Extra points once every mission of the rotation is done. |
| `kinds` | see below | The tuning of each kind. |
| `speciesFilter` | see below | Which species the missions may ask. |
| `swap` | see below | Swapping a mission at the clerk. |

The kinds (`allowedKinds` names, `kinds` keys):

| Kind | `kinds` key | Mission | Default tuning |
|---|---|---|---|
| `SPECIES` | `species` | Catch N of a species. N follows its rarity: `commonCount` 2, `uncommonCount` 1, `rareCount` 1 (when `speciesFilter.rarities` lets rare ones in). | weight 3, 120 points |
| `TYPE` | `type` | Catch N Pokémon of a type, the type weighted by how many species have it. | weight 3, 3 to 5, 30 per catch |
| `SPECIAL` | `special` | Catch N of the current specials (never more than there are specials). Not drawn while the specials are off. | weight 2, 1, 250 points |
| `HABITAT` | `habitat` | Catch N Pokémon in a Safari habitat (where the Pokémon stands when caught). Only habitats with at least 3 species of the filter are named. | weight 2, 3 to 4, 25 per catch |
| `ANY` | `any` | Catch N Pokémon. | weight 1, 5 to 8, 15 per catch |
| `DIFFERENT` | `different` | Catch N different species. | weight 2, 3 to 5, 30 per catch |
| `OUT_OF_BATTLE` | `outOfBattle` | Catch N Pokémon without battling them. | weight 2, 2 to 4, 30 per catch |
| `NIGHT` | `night` | Catch N Pokémon at night (Cobblemon's night, about 12500 to 23500 ticks of the day). A rotation shorter than a game day may have no night. | weight 1, 2 to 3, 40 per catch |
| `EVOLVED` | `evolved` | Catch N fully evolved Pokémon (a species that evolved and evolves no further). | weight 1, 1 to 2, 80 per catch |
| `LEVEL` | `level` | Catch N Pokémon of at least a level, drawn between `minLevel` 20 and `maxLevel` 40 by steps of 5, and lowered to the highest wild level of the filter's species. | weight 1, 1, 120 points |
| `FISHING` | `fishing` | Fish N Pokémon with a rod in the Safari, counted when one bites (caught afterwards or not). Not drawn when nothing the Safari lets in can be fished in its habitats. | weight 2, 2 to 3, 45 per catch |
| `HIDDEN_ABILITY` | `hiddenAbility` | Catch N Pokémon with their hidden ability. Rare: Cobblemon gives one only to some fished Pokémon and to those drawn by a honeyed tree. | weight 1, 1, 400 points |
| `SHINY` | `shiny` | Catch N shiny Pokémon. Luck more than skill. | weight 1, 1, 1000 points |

Every entry of `kinds` has the same keys (`species` and `level` add theirs, above):

| Key | What it does |
|---|---|
| `weight` | How often the kind is drawn against the other allowed kinds. 0 never draws it. |
| `minCount`, `maxCount` | Catches asked, drawn between the two. At least 1. Ignored by `species` (its counts follow the rarity). |
| `rewardPoints` | Points paid on completion, whatever the count. |
| `pointsPerCatch` | Points paid on completion for each catch asked: `rewardPoints + pointsPerCatch × N`. |

Write every key of an entry you change: a key left out of an entry present in the file takes the generic value (weight 1, counts 1, rewards 0), not the kind's default. An entry left out entirely keeps its defaults.

`speciesFilter` decides which species a species mission may name, which types a type mission may ask, which habitats a habitat mission may name, and the levels a level mission may ask. A species is kept when one of its natural spawns in the Safari's surface habitats is of an allowed rarity and passes every switch: a species that also spawns in a plain way somewhere stays, even if its other spawns are fished or nocturnal. Legendary-class species and anything `spawns` refuses are always left out.

| Key | Default | What it does |
|---|---|---|
| `rarities` | `["common", "uncommon", "rare"]` | Spawn buckets that may be asked: `common`, `uncommon`, `rare`, `ultra-rare`. |
| `excludedSpecies` | `[]` | Species never asked, e.g. `"cobblemon:ditto"`. |
| `excludedTypes` | `[]` | Types never asked, and whose species are never asked either, e.g. `"dragon"`. |
| `allowWater` | `false` | Species met in the water (fishing, surfacing, diving). |
| `allowCaves` | `true` | Species met underground (a spawn that wants no sky). |
| `allowTimeRestricted` | `false` | Species met only at some hours (night, dawn, dusk). |
| `allowWeatherRestricted` | `false` | Species met only in some weather (rain, thunder). |

`swap`: a player who finds a mission too hard asks the clerk to replace it, for themselves only. The new mission is of another kind when one can be drawn, starts from zero, and a mission already done cannot be swapped.

| Key | Default | What it does |
|---|---|---|
| `enabled` | `true` | Whether the clerk offers it. |
| `cost` | `50` | Safari points it costs. 0 makes it free. |
| `perRotation` | `1` | Swaps each player may make per rotation, 0 to 8. |

## `shop.json`: the shop

The Safari Warden (at every gate) and the Safari Clerk (in every rest house) open the shop. The file is laid out by tab: each tab holds its fixed offers (`fixed`) and the offers it draws each rotation (`rotatingPokemon`, `rotatingItems`, `rotatingSnacks`), and may split them into `sections`, shown one after the other with no gap. Every player sees the same drawn offers; they change with the specials.

| Key | Default | What it does |
|---|---|---|
| `enabled` | `true` | `false` closes the shop: the NPCs say so. |
| `defaults.limit` | none | Limit of an offer that sets none (see `limit` below). |
| `tabs` | Pokémon, Items, Poké Balls, Cooking, Tickets | The tabs of the screen, in display order, with what they sell. 64 at most: the ones after are dropped, with a warning. |

### `tabs`

A tab with nothing in it is hidden.

| Key | What it does |
|---|---|
| `id` | The built-in ids `pokemon`, `items`, `balls`, `cooking` and `tickets` have translated names. Ids must be unique. |
| `name` | Name shown as is, instead of the translated one. Empty: the translation, or the id. |
| `icon` | Item drawn on the tab, e.g. `"cobblemon:poke_ball"`. Empty for none. |
| `fixed` | Offers always listed, in file order, 8 per page. See below. |
| `rotatingPokemon`, `rotatingItems`, `rotatingSnacks` | What the tab draws each rotation, each optional. See below. |
| `sections` | Optional parts of the tab: each has a `name` and the same `fixed` and `rotating…` keys. The tab's own offers come first, then each section in the order of the file, with no gap or title between them: sections only group offers in the file (and name them in the log). |

### Offers (`fixed`)

Each offer sells exactly one of: an item, a Pokémon, or a ready-made dish.

| Key | What it does |
|---|---|
| `item` | Item id, e.g. `"cobblemon:rare_candy"`. An id that names no item (a mod removed) shows the offer as unavailable. |
| `pokemon` | Cobblemon properties, e.g. `"dratini level=15"`. The Pokémon goes to the party, or the PC when the party is full. |
| `snack` | A dish already cooked: `{"base": "cobblemon:poke_snack", "seasonings": ["cobblemon:passho_berry", ...]}`, up to 3 seasonings, as the Poké Pot would make it (colour, ingredients, bait effects). `processors` (optional) lists the Cobblemon seasoning processors to run; absent, those of the dish's own Poké Pot recipe. A seasoning's bait effect comes from Cobblemon's data: type berries (Passho, Occa...) and egg-group berries (Aspear, Rawst...) bring those Pokémon, Starf rerolls for a shiny, Enigma gives a chance of a hidden ability; Oran, Sitrus and other fishing berries do nothing on a snack. |
| `count` | Stack given, for an item or a dish, 1 to 64. Absent: 1. |
| `price` | Cost in Safari points (0 for free). For a Pokémon, `"auto"` prices it like the drawn ones. |
| `cost` | Items taken in exchange, on top of `price`: a list of `{"item": ..., "count": ...}`, each `item` an id or a `#tag` (any mix of the tag's items counts; a player carrying several kinds picks which one is given first). 16 costs at most per offer, and 512 items of a tag at most (the clients read no more): the rest is dropped, with a warning. |
| `limit` | How often a player may buy it, see below. Absent: `defaults.limit`. |

`limit`, for every offer, fixed or drawn; all its keys are optional and each set one applies:

| Key | What it does |
|---|---|
| `max` | Purchases per player. Per rotation, unless `lifetime` is `true`. |
| `lifetime` | `true`: `max` counts over the player's whole life in this world. Absent or `false`: per rotation. |
| `cooldownHours` | Hours to wait after each purchase, in real time (the wait goes on while the player or the server is offline). Up to a year. |

The default offers show every kind: a level 45 Tauros once per player for life, Rare Candy and other items, 32 free Safari Balls every 24 h, Poké Balls, a dozen ready-made dishes (a plain Poké Snack, nine snacks each themed on a type or an egg group, a hidden ability one, a shiny one and a Poké Cake) and the Poké Pot's ingredients, and one Safari Ticket per rotation for 16 apricorns, 24 berries, an evolution stone or a fossil.

### `rotatingPokemon`: drawn Pokémon

Drawn among the species that spawn naturally in the Safari: `count` ordinary ones, then the legendary-class ones each allowed category adds on top.

| Key | Default | What it does |
|---|---|---|
| `enabled` | `true` | Master switch. |
| `count` | `15` | Ordinary Pokémon per rotation, 0 to 32. |
| `rarities` | see below | `common`, `uncommon`, `rare`, `ultra-rare`, each with `allowed`, `price` and `weight`. A slot goes to a Pokémon with a chance proportional to its rarity's `weight`. The price is `price` times the evolution stage (a second stage costs twice as much, a third three times; a species that does not evolve counts as a second stage), rounded to 50. Defaults: 250 / 500 / 1000 / 2500 points, weights 10 / 6 / 3 / 1, ultra-rare off. |
| `categories` | see below | `legendary`, `mythical`, `ultraBeast`, `paradox`, each with `allowed`, `price` (whatever the stage), `maxPerRotation` (how many are listed, on top of `count`, 0 to 8) and `limit` (absent: the draw's `limit`). Defaults: 20000 / 20000 / 8000 / 5000 points, 1 / 1 / 1 / 2 per rotation, one each per player and rotation; only `paradox` on. A species in several categories needs them all allowed. A `limit` with `"lifetime": true` counts per offer content (species, form, level, shininess, price): a player who bought a legendary cannot buy it again when it comes back identical, but can when it comes back shiny or at another price or level. |
| `level` | `{"mode": "spawn"}` | `"spawn"`: the lowest level it spawns at in the Safari. `"range"`: a level drawn between `min` and `max`. |
| `modifiers` | `{"shinyChance": 0.01, "shinyPriceMultiplier": 3}` | Chance (0 to 1) that a drawn Pokémon is shiny (1 % by default), and its price multiplier. |
| `excludeFeatured` | `true` | Leaves out today's specials, so the shop never sells what players are hunting. |
| `excludeList` | `[]` | Pokémon never drawn, as Cobblemon properties: `"arceus"` for a species and all its forms, `"vulpix alolan"` for one form. |
| `limit` | none | Limit of each drawn Pokémon. |

A Pokémon the fixed offers already sell (same species and form) is never drawn.

### `rotatingItems`: drawn goods

Only items matched by a `prices` rule can be drawn: the rules are the catalogue and the price list at once.

| Key | Default (items / balls / ingredients) | What it does |
|---|---|---|
| `enabled` | `true` | Master switch. |
| `count` | `8` / `6` / `6` | Items drawn per rotation, on top of `always`. 0 to 32. |
| `always` | `[]` / `["cobblemon:safari_ball"]` / `[]` | Items listed every rotation whatever the draw. Each still needs a `prices` rule. |
| `excluded` | Ability Patch, teapots and teacups, bone, snowball / tier 5 balls, Cherish Ball / none | Items never drawn: ids or `#tags`. Items the fixed offers sell are left out too. |
| `prices` | 29 rules / 5 rules / 4 berries for 30 | Each rule: `item` (an id, or a `#tag` for every item of it), `count` (stack, 1 to 64) and `price`. The first rule matching an item wins, so specific items go before the tags that hold them. |
| `limit` | none | Limit of each drawn item. |

### `rotatingSnacks`: drawn dishes

| Key | Default | What it does |
|---|---|---|
| `enabled` | `true` | Master switch. |
| `count` | `2` | Dishes drawn per rotation, 0 to 32. |
| `base` | `"cobblemon:poke_snack"` | The dish. |
| `seasonings` | `{"pool": ["#cobblemon:berries"], "min": 1, "max": 3}` | Where its seasonings come from (ids or `#tags`; only items Cobblemon knows as seasonings are used) and how many, 0 to 3. |
| `price` | `{"base": 150, "perSeasoning": 75}` | Price: the base plus a part per seasoning. |
| `processors` | absent | As for a fixed dish. |
| `limit` | `{"max": 1}` | Limit of each drawn dish: one per player and rotation. |

---

## `client.json`: each player's display

These settings belong to each player; a server cannot change them.

| Key | Default | What it does |
|---|---|---|
| `hud` | see below | The Safari timer. |
| `board` | see below | Look of the Safari Wall Screens. |

### `hud`: the timer

| Key | Default | What it does |
|---|---|---|
| `enabled` | `true` | Shows the Safari timer. |
| `anchor` | `"BOTTOM_LEFT"` | Corner or edge the timer is attached to: `TOP_LEFT`, `TOP_CENTER`, `TOP_RIGHT`, `BOTTOM_LEFT`, `BOTTOM_CENTER`, `BOTTOM_RIGHT`. |
| `offsetX` | `20` | Horizontal offset of the middle of the timer from the anchor, in GUI pixels. |
| `offsetY` | `-16` | Vertical offset, in GUI pixels (negative is up). The timer always stays on screen. |
| `lowTimeSeconds` | `60` | Below this many seconds the time turns amber, and so does the hunt bar of the wall screens. Never below `criticalTimeSeconds`. |
| `criticalTimeSeconds` | `10` | Below this many seconds the time turns red and pulses; the wall screens' hunt bar turns red. |

### `board`: wall screens

| Key | Default | What it does |
|---|---|---|
| `useSprites` | `false` | Draws the Pokémon as the mod's flat pixel sprites instead of turning 3D Cobblemon models. A Pokémon without a sprite keeps its model. Resource packs can replace the sprites (`assets/cobblemon_safari/textures/pokemon_sprites/`). |
| `backgroundImage` | `true` | Shows the safari landscape of the time of day behind the screen. `false` uses the plain `colors.background`. |
| `panelOpacity` | `0.72` | Opacity of the title band, cells and bars over the landscape, from 0 (invisible) to 1 (opaque). |
| `footers` | see below | What the bar at the bottom of each kind of screen counts down. |
| `colors` | see below | Every colour of the screens. |

### `board.footers`

Each key takes `"SESSION"`, your hunting time while you hunt (the next rotation otherwise), or `"ROTATION"`, always the next rotation.

| Key | Default | Screens |
|---|---|---|
| `specials` | `"SESSION"` | The specials of the day. |
| `stats` | `"ROTATION"` | Your Safari record. |
| `missions` | `"SESSION"` | The missions. |
| `dex` | `"ROTATION"` | The Safari Pokédex. |

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
| `barSessionLow` | `"#FFC04D"` | Same, below `hud.lowTimeSeconds` |
| `barSessionCritical` | `"#FF5555"` | Same, below `hud.criticalTimeSeconds` |
| `rarityCommon` | `"#FFFFFF"` | Rarity strip and name, common |
| `rarityUncommon` | `"#55FF55"` | Same, uncommon |
| `rarityRare` | `"#55FFFF"` | Same, rare |
| `rarityUltraRare` | `"#FF55FF"` | Same, ultra-rare |
| `scanline` | `"#12FFFFFF"` | The light band rolling down the screen (mostly transparent) |
| `shimmer` | `"#50FFFFFF"` | The light sweeping along the progress bar |
| `flash` | `"#90FFFFFF"` | The flash when the list changes (its alpha is the flash's peak) |

---

## Older files

A file written by an older version of the mod is converted on load, then saved in the current layout; every key moved is named in the log (`moved X to Y`). A key is only moved when it is there, so a file converted by hand, or half converted, is left as it is: when both the old and the new key are set, the new one is kept and the old one dropped, with a warning.

`server.json` before version 7 (Cobblemon Safari 1.3.x and earlier):

| Before | Now |
|---|---|
| `maxDurationSeconds`, `exitDestination`, `exemptCreativeAndSpectator` | `hunt.` same name |
| `pauseTimeWhileOffline` | `hunt.pauseWhileOffline` |
| `warningThresholdsSeconds` | `hunt.warningsAtSeconds` |
| `ticket.defaultDurationSeconds`, `ticket.minAddedSeconds`, `ticket.refundUnusedTimeOnLeave` | `tickets.` same name |
| `ticket.extendsActiveSession` | `tickets.addToCurrentHunt` |
| `ticket.maxCreditSeconds` | `tickets.maxBankedSeconds` |
| `ticketLoot` | `tickets.loot` |
| `allowedBalls` | `balls.allowed` |
| `loan` | `balls.loan` |
| `allowBattles` | `battles.enabled` |
| `fishingStartsBattle` | `battles.fishingStartsBattle` |
| `battleGraceSeconds` | `battles.graceSeconds` |
| `capture.awardSafariMark` | `capture.marks.safari` |
| `capture.eventMarks.*` | `capture.marks.*` |
| `featured` | `specials` |
| `featured.uncommonSlots`, `rareSlots`, `ultraRareSlots` | `specials.slots.uncommon`, `rare`, `ultraRare` |
| `featured.weightUncommon`, `weightRare`, `weightUltraRare` | `specials.spawn.weights.uncommon`, `rare`, `ultraRare` |
| `featured.spawnIntervalSeconds` | `specials.spawn.checkEverySeconds` |
| `featured.spawnChance` | `specials.spawn.chance` |
| `featured.maxNearbyPerPlayer` | `specials.spawn.maxAroundPlayer` |
| `points.common`, `uncommon`, `rare`, `ultraRare` | `points.perCatch.` same name |
| `points.featuredBonus` | `points.specialBonus` |
| `bounties` | `missions` |
| `bounties.allDoneBonus` | `missions.allMissionsBonusPoints` |
| `bounties.kinds.featured` | `missions.kinds.special` |
| `FEATURED` in `bounties.allowedKinds` | `SPECIAL` in `missions.allowedKinds` |
| `reward`, `rewardPerCatch` of each kind | `rewardPoints`, `pointsPerCatch` |
| `restHouseTravel.prebuildHouses` | `restHouses.prebuild` |
| `restHouseTravel.cooldownSeconds`, `cinematicSeconds` | `restHouses.travel.` same name |
| `restHouseTravel.guideEnabled` | `restHouses.travel.guide` |
| `restHouseTravel.cinematicStyle` | removed: the trips are always filmed, as `FLIGHT` did |
| `restHouseTravel.ropeEnabled` | `escapeRope.toRestHouse` |

Older conversions still apply on the way:

- **Version 1** (Cobblemon Safari 1.0.0): its `spawns` species rules are copied to `specials`, so nothing changes in game until you edit them.
- **Version 2**: the rest house wait `escapeRope.restHouseCooldownSeconds` goes to `restHouses.travel.cooldownSeconds`.
- **Before version 4** (Cobblemon Safari 1.1.0 and before): the three mission rewards `bounties.typeRewardPerCatch`, `speciesReward` and `featuredReward` go to `missions.kinds.type.pointsPerCatch`, `kinds.species.rewardPoints` and `kinds.special.rewardPoints`.
- **Version 4 or older**: a ticket loot list still holding the earlier defaults gets the 1.3.0 ones.
- **Before version 6**: the fishing missions (`FISHING`) are added once to `missions.allowedKinds`.
- **Earlier 1.4.0 builds**: `capture.flee.commonChance`... `ultraRareChance`, `specialCategories` and `rarity` go into `capture.flee.rarities`; `restHouses.guide`, `cooldownSeconds` and `cinematicSeconds` go into `restHouses.travel`, and `restHouses.cinematicStyle` is removed.

`client.json` before version 2: `hudEnabled`, `hudAnchor`, `hudOffsetX`, `hudOffsetY`, `lowTimeSeconds` and `criticalTimeSeconds` go to `hud.enabled`, `hud.anchor`, `hud.offsetX`, `hud.offsetY`, `hud.lowTimeSeconds` and `hud.criticalTimeSeconds`; `board.featuredFooter`, `statsFooter`, `bountiesFooter` and `dexFooter` go to `board.footers.specials`, `stats`, `missions` and `dex`.

`shop.json` before version 3: `rotating` becomes `rotatingPokemon`. A `shop.json` at version 1 also gets the fossil ticket trade added once. `shop.json` before version 4 is laid out by tab: each entry moves into the `fixed` list of its `tab` (`type` and `tab` dropped, `limitPerRotation` and `cooldownHours` into `limit`), `rotatingPokemon`, `rotatingItems` and `rotatingBalls` move into their tab (`rotatingBalls` as that tab's `rotatingItems`), the Pokémon draw's `xxxPrice` and `rarities` into `rarities.xxx`, `allowSpecialSpecies` into `categories.xxx.allowed` for legendary, mythical and ultra beast (paradox stays on), `excludeSpecials` into `excludeFeatured`, `excludedSpecies` into `excludeList`; the cooking tab's items become its `ingredients` section, after a `prepared` section with the default dishes.

---

## What is not in the files

Some things are fixed on purpose, or live in data packs:

- **The island**: its size, border, arrival point and rest houses are generated with the terrain and always match it.
- **The dimension id**: `cobblemon_safari:safari`.
- **Gate frequency and biomes**: `data/cobblemon_safari/worldgen/structure_set/safari_gate.json` and the biome tag `has_structure/safari_gate`, in a datapack.
- **Pokémon of the park**: Cobblemon's spawn files. Target the Safari only with its biome tags, e.g. `"biomes": ["#cobblemon_safari:safari_forest"]`.
- **NPCs and their dialogues**: `data/cobblemon_safari/npcs/` and `dialogues/`, in a datapack.

See [TECHNICAL.md](TECHNICAL.md) for the details, and [`example/`](example) for a sample datapack and resource pack.
