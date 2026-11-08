# Changelog

## 1.4.1

New network protocol (`1.6.0`): clients and servers must both update (the specials board now sends every habitat of each Pokémon).

### Fixed

- **Some swarms never spawned** (Gallade, for one): the swarm was announced, but its species never appeared and was not even listed by `/checkspawn`. The swarm was drawn among the species whose spawn files name a Safari biome, without looking at the rest of their conditions. Since Cobblemon 1.8, many species (most fully evolved ones, Gallade among them) only have spawns that require a Poké Snack, which a player's natural spawner never satisfies; such a swarm was drawn, its spawns refused at every spot, and nothing came. Spawns tied to a structure (villages...) and fishing-only spawns had the same problem: those are no longer drawn. Poké Snack-only species can still swarm, and now really do: the swarm's own spawner (below) lifts the snack requirement for them, and for them only, every other condition of their spawn still applies.
- **Swarms are now dependable once drawn**: in Cobblemon's natural spawns the swarm only weighed more among the Pokémon of its habitat, so a light species in a crowded habitat could swarm and barely show. A swarm spawner of its own now keeps a few of the swarming species (`events.swarm.minNearby`, 4 by default) around each hunting player standing in the habitat, topped up every `events.swarm.checkEverySeconds` (5 s). It spawns through Cobblemon's own machinery: every condition of the species' spawn (hour, light, kind of spot), the Safari's caps and exclusions and Cobblemon's spawning gamerule still apply. `minNearby: 0` brings back the 1.4.0 behaviour (and then a Poké Snack-only swarm shows nothing).
- **Legendary swarms stopped after one Pokémon**: the natural per-rotation cap of a category (`spawns.legendary.maxSpawnPerPokemon`, 1 by default) applied to the swarm too. The swarming species in its habitat now follows `events.swarm.rarities` alone, with a cap of its own, and its spawns and catches never use up natural slots.
- **A special caught could take a natural slot**: a special uncapped in `specials` but capped in `spawns` was not noted when it spawned, so its catch was charged to the natural spawns. Specials and swarm Pokémon are now always noted, and charged to the way they came in.
- **The specials board showed one habitat only**: a Pokémon living in Snowfield and Glacier (`#is_freezing`) was shown in Snowfield alone. The wall screens and `/safari today` now list every habitat, the main one first; a long list scrolls.
- **Missions no longer ask for Pokémon met only through Poké Snacks**: species missions are drawn among spawns a player can meet walking around.
- **The Safari Pokédex no longer lists Pokémon met only through Poké Snacks**, and a species wild in one habitat but snack-only in another is listed under the habitat where it is wild. The Pokédex may count fewer species than in 1.4.0; what was caught stays caught.
- **Habitats are named without "Safari"** in the guide's specials list and travel menu, the swarm line and on the screens ("Dunes" rather than "Safari Dunes"); the biomes keep their full name elsewhere (F3, other mods).
- **The travel bird no longer flies through the rest house's roof**: it takes off and lands two blocks further from the house.
- **Pokémon in the shop no longer move too fast**: their animation followed the frame rate (the higher the FPS, the faster they moved); it now plays in real time, at the speed it has in the world and on the specials board.
- **`/safari rotate` restarts the rotation timer**: the new rotation lasts a whole `specials.rotationMinutes` from the command, instead of ending at the next scheduled change (a rotation forced with 1:20:30 left used to keep 1:20:30). The following rotations keep that pace from that moment, so they are no longer aligned on midnight UTC once a rotation has been forced.

### New

- **Storm** (`events.storm`): one thunderstorm in two brings rare Electric and Dragon Pokémon of the Safari to the high ground (peaks, rocky hills, volcano, and any spot high enough for the map's relief) near the players standing there, each with a harmless lightning flash (at most one every 30 s). A Pokémon the storm brought earns the new **Storm Mark**, "of the Storm" (`capture.marks.storm`). It follows the natural spawns' rules (excluded species, legendary-class switches and caps) and adds up with a downpour.
- **Downpour** (`events.downpour`): some rains of the Safari (15 % by default) turn into a downpour for 5 to 10 minutes, during which Water and Electric Pokémon come out twice as often where the rain falls. Announced to the players in the Safari and shown by `/safari today`. A catch made in it earns the new **Downpour Mark**, "of the Downpour" (`capture.marks.downpour`).
- **`events.swarm.buckets`**, **`events.swarm.weights`** and **`events.swarm.rarities`**: the spawn buckets a swarm may be drawn from (`["uncommon", "rare", "ultra-rare"]` by default: ultra-rare species such as the starters can now swarm), the chance of each bucket (`common` 0, `uncommon` 10, `rare` 4, `ultraRare` 1: the bucket is drawn first, then a species in it, so a bucket's share does not depend on how many species it holds), and the legendary-class categories in swarms (`legendary`, `mythical`, `ultraBeast`, `paradox`), each with `allowSpawn` (`false` by default, `true` for `paradox` as in `spawns`) and `maxSpawnPerPokemon`, the most a swarm brings per rotation (`0` by default: no cap but `maxNearby`). They replace the categories of `spawns` for the swarm. A legendary-class species counts as ultra-rare. A species' bucket is now its bucket in the habitat drawn.
- **Pokémon take no damage in the Safari** (`protection.invulnerablePokemon`, on by default): wild Pokémon can no longer be killed with a sword or a bow, and sent-out ones are spared falls, lava and mobs. Battles are not affected. `/kill` and the void still apply.
- **`events.swarm.excludeList`**: Pokémon that never swarm, form by form. `"vulpix alolan"` leaves out Alolan Vulpix only, Vulpix can still swarm; `"arceus"` leaves out the species and all its forms. An excluded form caught in the swarm's habitat does not get the "of the Swarm" mark.

### Shop

- **`shop.json` is laid out by tab** (version 4): each tab holds its fixed offers (`fixed`) and what it draws each rotation (`rotatingPokemon`, `rotatingItems`, `rotatingSnacks`), and may be split into `sections`, shown one after the other. A version 3 file is converted on load and keeps everything it sold.
- **One `limit` for every offer**: `max` purchases per player and per rotation, or for life with `"lifetime": true`, and `cooldownHours`. It replaces `limitPerRotation` and `cooldownHours`. The default Tauros can now be bought once per player.
- **Drawn Pokémon by rarity and category**: each rarity has its price and a weight (most slots go to common Pokémon, now and then a rare one), and legendary, mythical, ultra beast and paradox Pokémon have their own switch, price, number per rotation and limit, listed on top of the ordinary ones (only paradox on by default; legendary and mythical 20000 points). A level range, a 1 % shiny chance with its price multiplier and an `excludeList` by form complete it.
- **Cooking tab**: more ingredients (Big Root, Revival Herb, Moomoo Milk, eggs, wheat), berries drawn each rotation, and a dozen ready-made Poké Snacks (plain, themed on a type or an egg group, one for hidden abilities, one for shinies) and a Poké Cake already seasoned, as the Poké Pot makes them, plus two seasoned snacks drawn each rotation, shown first in the tab.
- **Shiny Pokémon in the shop show Cobblemon's shiny sparkle** in the top right corner of their card, as in the PC.
- **Dish tooltips in the shop**: hovering a Poké Snack or a Poké Cake shows its tooltip, with its seasonings and what it attracts, before it is bought. Dishes come first in their tab.
- **The free Safari Balls of the shop are 32 a day** (half a stack) instead of 30, in new `shop.json` files.

### Changed

- **`spawns.caughtSpeciesWeight` defaults to 0.3** (was 0.4): a species not caught yet is more than three times as likely as a caught one. Existing `server.json` files keep their value.
- **The jungle is more open**: patches of thick jungle and of bamboo jungle in a sparse jungle, so Pokémon can be seen and the biome crossed. Still one biome. New maps only.
- **The giant cavern looks like the Caves & Cliffs caves**: smooth rock walls instead of small lumps everywhere, ledges and floors, and stone pillars. New maps only; the lush cavern is unchanged.
- The swarm's weight boost in Cobblemon's spawns applies to the swarm's own spawns only, so an excluded form of the same species keeps its usual weight.
- `server.json` goes to version 8 so that existing files show the new keys; nothing is converted.

## 1.4.0

New network protocol (`1.5.0`): clients and servers must both update.

### New

- **A Safari advancement tab**, with 47 advancements: discovering the island (its fourteen habitats, the caverns, the summit, the ruins and fossils, the Pokémon habitats, the rest house trips), hunting (first catch, out of battle with a Safari Ball, specials, ultra-rare specials, shinies, at night, 10 and 30 Pokémon in one hunt, 50 species, and two hidden ones), baits (Poké Snacks and Cakes), the clock (a full time bank, a hunt to the last second, a catch in the last 10 seconds, an early exit with a refund), missions, points and the shop, loyalty (10 and 50 hunts, 7 maps), and fishing (first Pokémon fished, a fished Pokémon caught, 10 and 50 fished, with bait, a shiny, a special, in a cave, from a fishing boat). Translated in the twelve languages of the mod. Thresholds already passed are granted at the next login; the number of maps hunted on is counted from this version on.
- **Four event marks**, given on top of the Safari Mark to a catch made at a remarkable moment, each with its own icon and switch (`capture.marks`): "the Star of the Day" (a special of the day), "of the Swarm" (the swarming species in its habitat), "of the Full Moon" (a full moon night) and "the Safari Catch" (a Pokémon fished in the Safari). Their title takes the place of the Safari Mark's.
- **Fishing missions** (`FISHING`): "Fish N Pokémon", counted at each bite in the Safari, caught afterwards or not. Drawn like the other kinds (weight 2, 2 to 3 bites, 45 points each, `missions.kinds.fishing`), never on an island where nothing bites. Added once to the allowed kinds of existing config files.
- **Swarms and full moon nights**: with each rotation, maybe (one in two by default), one uncommon or rare species swarms in one of its habitats until the next rotation, shown on the specials board and announced to the players in the Safari; and on full moon nights, Fairy and Ghost Pokémon come out three times as often. Everything is in the new `events` section.
- **Pokémon can run away**, as in the Safari Zone of the games: after a failed ball out of battle, a wild Pokémon may vanish in a puff, more likely the rarer it is and the more balls already failed on it (5 % for a common one, 8 % for an uncommon one, 10 % for a rare one, 12 % for an ultra-rare one, +5 % per failed ball, 35 % at most). Each rarity, and each legendary-class category (legendary, mythical, ultra beast, paradox), has its own entry in `capture.flee.rarities`, with a switch and a chance: the four rarities may run away and the four categories stay by default, as do shiny Pokémon. All of it is in `capture.flee`.
- **Safari Pokédex**: a new wall screen page and a new command, `/safari dex [habitat]`, for every player. They show, habitat by habitat, how many of the species that really live in the Safari you have caught there, and the command lists the ones you are missing in a habitat. Legendaries and species that never spawn naturally in the Safari are left out, so it can be completed.
- **A richer end-of-game recap**: after the Pokémon caught, it now also tells (only when there is something to say) the Safari points earned and the missions completed during the hunt, the species new to your Safari Pokédex with its count, a beaten record of catches in one hunt, and the Safari advancements unlocked.
- **Fishing without the automatic battle** (`battles.fishingStartsBattle`, `false` by default): a Pokémon fished in the Safari no longer starts a battle with you a second after the bite; it is left free to be caught out of battle with a Safari Ball, or battled on purpose. Set it to `true` for Cobblemon's usual battle. A config file that already has the key keeps its value.
- **New statistic: Pokémon fished in the Safari** (`cobblemon_safari:pokemon_fished`).
- **Cobblemon plants harvestable one by one** (`protection.interact`): a switch per plant says whether players may harvest it while the terrain is protected: berries, apricorns and Galarica nuts (picked with a click, allowed by default), mints, Revival Herbs, Medicinal Leeks, Vivichokes, Hearty Grains and Bugwort (harvested by breaking them, protected by default), and big and energy roots (sheared or broken, protected by default).
- **Creative and spectator players without the clock** (`hunt.exemptCreativeAndSpectator`, on by default): they can now enter through a gate, its warden or `/safari enter` with no banked time, and their Safari clock stops while they stay in creative or spectator (paused on the HUD, with a message when it stops and starts again). A player who came in without time is sent out as soon as they switch to survival or adventure, as when time runs out.
- **Hunger can be frozen in the Safari** (`protection.hunger`, `false` by default): set it to `true` and the food bar stays where it was on entering, so a timed hunt is not spent eating. With `false`, hunger works as everywhere else.
- **Two underground rest houses**, one in the giant cavern and one in the lush cavern, each in a chamber dug beside the passage that leads down to it. The Safari Guide offers them as their own destinations ("Safari Giant Cavern", "Safari Lush Cavern"), and the Escape Rope's nearest rest house includes them when used in a cave. On a map generated before 1.4.0, the lush cavern's house is dug where the new, larger cavern will be, so it may not meet the old passage until the next map: the guide still reaches it.
- **Burrow cinematic** for trips into or out of a cave (the Safari Guide to or from an underground rest house, the Escape Rope's nearest rest house from a cave, and the Escape Rope's climb to the open air from a cave): your double rides a Pokémon that scratches the ground, jumps and dives in head first in front of the house, with a burst of earth and dust and a heavy impact, while a rumble runs ahead under the ground; at the destination the ground rumbles before it bursts out, jumps onto the spot facing the camera and lands with a thud. The Pokémon is the first of your party that digs naturally (Dugtrio, Hippowdon, Rhydon, Krookodile, Sandslash, Excadrill, Steelix, Sandaconda, Dudunsparce, Orthworm, Trapinch...) and could learn Dig, or a Dugtrio; small ones are grown to the size of a double-sized Dugtrio, and your double sits on its back. The camera films it from low down, away from the rest house's walls and from banners, signs or flowers in front of the lens.
- **Dive cinematic** for the Escape Rope used in the water: your double rides a Pokémon of your party that could learn Dive and swims with a rider (or a Wailmer) under the water to the nearest shore, then the trip goes on from there, flying or, in a cave, burrowing.

### Changed

- **The configuration files are reorganised, by subject and with clearer names.** `server.json` (version 7) now groups its settings in sections: `hunt` (the clock), `tickets` (with their chest `loot`), `gate`, `balls` (with the `loan`), `battles`, `capture` (with all the `marks` and `flee`), `protection`, `spawns`, `specials` (formerly `featured`, its slots under `slots` and its spawner under `spawn`), `events`, `points` (the catch rewards under `perCatch`), `restHouses` (formerly `restHouseTravel`, the trips under `travel`), `escapeRope` (with the rope's rest house option), `reset` and, last, `missions` (formerly `bounties`). The trips' `cinematicStyle` is no longer a setting: they are always filmed, as `FLIGHT` did, and fall back by themselves to the painted scenes when the flight cannot be staged; a server that had chosen `PANORAMA` now gets the filmed trips. Several keys are renamed so that they say what they do: `tickets.addToCurrentHunt`, `tickets.maxBankedSeconds`, `hunt.warningsAtSeconds`, `missions.allMissionsBonusPoints`, each mission kind's `rewardPoints` and `pointsPerCatch`, `points.specialBonus`; the missions kind `FEATURED` is now `SPECIAL`. `client.json` (version 2) puts the timer settings under `hud` and the wall screens' footers under `board.footers`; `shop.json` (version 3) renames `rotating` to `rotatingPokemon`. **Existing files are converted automatically on the first start**, with every moved key named in the log, and the missions already drawn keep their kind. CONFIG.md lists every old key and its new place.
- The wall screens' sprite mode has new sprites, the E19 Cobblemon Minimap Icons: the big Pokémon (Charizard, Wailord, the legendaries) are drawn smaller (no sprite over 32 pixels tall in its 64-pixel frame), and every species now has one. The 23 that showed their model (Venusaur, Vileplume, Shellos, Gastrodon, Hippopotas, Hippowdon, Unfezant, Basculin, Deerling, Sawsbuck, Frillish, Jellicent, Clauncher, Clawitzer, Wishiwashi, Alcremie, Basculegion, Oinkologne, Squawkabilly, Palafin, Tatsugiri, Gimmighoul, Ogerpon) had none; species drawn in several forms use their usual one. The sprites are credited in the README (MPL 2.0).
- The mod's file is about 3 MB lighter: the Safari's music is encoded at a lower bitrate (soft synthesised music, barely changed to the ear) and the wall screens' Pokémon sprites are stored as indexed PNGs, a quarter of the size.
- **A flight with more impact**: the mount gathers itself, leaps high in a gust of wind and dust, hangs there flapping, then dashes off with a rush of air and a trail of gusts; at the destination it comes in fast, brakes above the spot in a flurry of wings, turns to face the camera and drops onto the ground in a smaller gust. The camera keeps away from the rest houses' walls and from banners, signs and flowers in front of the lens when it can.
- **The rest houses are redecorated**: the lounge has new sofas (with armrests) around a birch coffee table, the hall a wall screen showing the Safari Pokédex (where the TM Machine and the bookshelf stood, now with a potted cherry sapling), flowering azaleas in place of the lilac and peony, a Safari banner and a potted acacia sapling in the lounge, and the specials and record screens tilted less. Houses already built keep their old look until the next map.
- **Quieter server log for the rest houses**: one line per house built, `Safari RestHouse built in Jungle 3/10` (its biome or cavern, and how many of the map's houses stand), instead of several lines of coordinates, felled trees and cleared logs, now at debug level (so is the tree clearing of the Safari Gates).
- **Common Pokémon can be specials** (`specials.slots.common` and `specials.spawn.weights.common`), for servers that want them: both are 0 by default, so nothing changes.
- **New relief `GENTLE`, now the default** (`reset.relief`): the land two thirds as high and the peaks half as sharp as `ROLLING`, whose plateaus rose up to some forty blocks above the rivers in sheer walls. `ROLLING` is unchanged and still available. A config file that already says `"ROLLING"` keeps it: change it to `"GENTLE"`. Only new maps take it.
- **A new map is created faster**: the entrance rest house is no longer built during the server start, which generated its chunks on the server thread for several seconds. It is built in the background right after, like the other houses; a player entering before it stands has it built on arrival.
- **The lush cavern is as large as the giant cavern** (radius 120 instead of 80, 120 blocks tall instead of 90), lush from end to end, and lies a little further south (around z 307), so the two cave systems keep solid rock between them. Shows on the next map.
- **No structure in the Safari but those it allows**: the Safari's biomes are bridged into Cobblemon's habitat tags for their spawns, and Cobblemon's structures came along through the same tags (its habitats, ruins and Gimmighoul towers, on top of the Safari's own ruins and towers, and the cave arches repeated every few chunks), as did its template features (fossil sites, tidepools, the deep crypt in the deep cavern...), the desert well and the buried fossils. Now nothing is generated in the Safari but the structures tagged `cobblemon_safari:allowed_in_safari` and the building features tagged `cobblemon_safari:allowed_in_safari` (placed feature tag), the rest houses, placed by code, aside: the Safari's three henges (stone, moon, sun), its low ruins (toppled pillars, shaded ruins, overgrown trial) Cobblemon's surface fossil sites (suspicious mounds, lush den, mud pit, rooted pits, frozen pond and spike, powdered deposit, submerged spike, preserved skeleton, hydrothermal vents), its root nurseries, sandy tidepools, rocky outcrops and fishing boats, and eighteen of its small habitats (berry patches, ponds, flower and sunflower clearings, sandpits, wildfire scars, peat bogs, bug mounds, fae mounds, carved ice spikes, shaded rocks, meteorite impacts, the jungle's canopy, peat bog, zen garden and thermal crevices). The habitats are the Safari's own copies, drawn at random among those that suit each spot, about seven or eight per map, spread out and kept away from the ruins. The Gimmighoul towers, the desert town centre, the frozen altar and Cobblemon's own copies of the ruins stay out. Terrain features (trees, geodes, ice spikes, huge mushrooms) stay.
- **Less lag when entering a new map**: the rest houses built in the background pause while the server is busy (ticks above 40 ms) and for 20 s after a player comes into the Safari, so their chunks no longer compete with the terrain generated around the players. The entrance house is never held back.
- **The music follows the habitats faster**: it starts fading over to the new habitat's track after 2 s there instead of 5 s.
- **The Escape Rope comes first in the shop's items tab**, as the Safari Balls do in theirs.

### Fixed

- Creating a world, and the first start after each daily reset of the Safari, took about ten seconds longer than without the mod: the server waited for the spot of every rest house to be chosen. The spots are now chosen in the background during the first seconds; only a player entering the Safari before the entrance house's spot is chosen waits for it.
- Shears could strip Cobblemon's big and energy roots in the Safari despite the terrain protection, turning them into a smaller root. It is now refused unless `protection.interact.roots` is on.
- The rest houses built in the background after the server start did not tell the clients their height or their final spot: the Pokémon Center theme could play above or below a house (on the surface over an underground one, in a cave under a surface one), or around the wrong spot, until the player logged in again. Every build now sends the update.
- A rest house's clerk or guide could stand on the roof, mostly in the jungle, where the leaves of a tree left beside the house spread over it. The staff now always find the floor from the house's own roof, and those already on a roof are put back behind the counter.
- Poké Snacks placed in the Safari were nearly never bitten: the boosted spawns filled their area to the snack's own limit, so the snack had no room to bring a Pokémon. In the Safari, no natural Pokémon spawns within 16 blocks of a snack any more, and a snack may bring Pokémon up to 1.5 times the Safari's density. Both can be set: `spawns.snackClearRadius` (16, 0 to 32, 0 turns the clearing off) and `spawns.snackDensityMultiplier` (1.5, 1 to 4).
- A Safari Gate could be built over a lava lake, the lava showing through its floor and around it; the Safari's ruins and towers had the same flaw. Lava lakes are no longer placed where they would reach one of them. Shows in newly generated chunks.
- A player taken out of the Safari by other means than the clerk (a `/tp`, a home or waystone command, another mod's teleport) lost the time left of their hunt: it now goes back to their bank, as when leaving through the clerk.
- Where an underground rest house's chamber sits over a cave, the rock under it hung into the cave as a block of straight columns up to 24 blocks deep: its underside is now a rounded, uneven bulge, thin at the edges. Shows on the next map.
- The answers of a long NPC dialogue (the Safari Guide's destinations) ran over the hotbar at large GUI scales: they now stop above it and scroll.
- Water could be left hanging in the air next to a tree cut around a Safari Gate or a rest house (a trunk or waterlogged leaves standing in a pond), or next to the ground reshaped around a rest house, until a block beside it changed and it poured. Cut trees now leave their water and let it settle as when a player cuts them; the reshaped ground and a house's volume stop short of any water.
- The caverns could open into the sky in wide holes, with floating rock between them, where they come near the ground: their caves now always end under a solid roof.

## 1.3.1

Same network protocol as 1.3.0 (`1.4.0`): a 1.3.1 client joins a 1.3.0 server and the other way round. Only the server needs the update.

### Fixed

- The server could freeze, then be shut down by its watchdog ("A single server tick took ... seconds"), while a rest house was being built in the background, mostly with C2ME installed ([#5](https://github.com/BileulDevs/Cobblemon-Safari/issues/5)). The build read the terrain of chunks that had been asked for but were still generating, and the server waited for them in the middle of a tick. It now waits until the whole area is loaded. The same check is used by the tree removal around Safari Gates and rest houses, the spawning of specials, and the arrival and flight of rest house trips.
- `/safari reload` could freeze the server for several seconds when the entrance rest house was missing (its template failed to load at start): it built it on the spot, generating its chunks on the server thread. It now leaves it to the background build, like the other houses.

## 1.3.0

### New

- **Safari statistics** in the game's Statistics screen (General tab): hunts, time hunted, Pokémon, shinies and specials caught, best hunt, Safari points earned and spent, missions completed and rest house trips. Your Safari record from earlier versions is carried over on your next login.
- **Safari Mark**: every Pokémon caught in the Safari gets a mark, "the Safari Find", in its summary's marks page; its title shows when the Pokémon has no other one (`capture.awardSafariMark`).
- **Smooth music changes** in the Safari: entering a rest house fades the current track out before the center theme rises, and after 5 s in a habitat with another track the music fades over to it instead of letting the old one finish.
- **Island relief** (`reset.relief`): `FLAT`, `ROLLING` (as before) or `MOUNTAINOUS`, for each new map.

### Changed

- **Tickets in every village chest**, not only the houses (4 %, savanna houses 10 %), and a little more in desert pyramids and jungle temples (12 %) and Cobblemon ruins (18 %).
- **Safari Gates are a little more frequent**: one per 40 chunks on average instead of 48 (new chunks only).

### Fixed

- The Safari Guide could take you to another rest house than the one you picked ("Deep Forest" landing in the jungle) after you scrolled his list with the mouse wheel: the click went to the answer that had just scrolled out. The same happened in any long Cobblemon dialogue.

### Config

- `server.json` goes to version 5: new keys `reset.relief` and `capture.awardSafariMark`; a `ticketLoot.sources` list still holding the earlier defaults gets the new ones.

## 1.2.1

Same network protocol as 1.2.0 (`1.4.0`): a 1.2.1 client joins a 1.2.0 server and the other way round.

### Fixed

- The game crashed at launch on Fabric (`@Shadow method addRenderableWidget ... was not located in the target class net.minecraft.class_437`). NeoForge was not affected.

## 1.2.0

Update the mod on the server **and** on every client: the network protocol changed (`1.4.0`), and a client of another version is refused when it joins on NeoForge.

### New

- **Configurable missions**: twelve kinds (a species, a type, a special, a habitat, any Pokémon, different species, without a battle, at night, fully evolved, of a level, with a hidden ability, shiny), each with its own weight, counts and reward. A rotation draws `bounties.count` of them among `bounties.allowedKinds` (every kind but hidden ability and shiny by default).
- **Species filter for the missions** (`bounties.speciesFilter`): rarities that may be asked (common, uncommon and rare by default), excluded species and types, and switches for water, cave, hour and weather spawns (only caves allowed by default), so no mission asks for a Pokémon you can only fish, or only meet at night or in the rain.
- **Swap a mission** at the clerk, for yourself only: 50 Safari points, once per rotation by default (`bounties.swap`). The new mission is of another kind when possible and starts from zero.
- **Escape Rope trips fly** like the Guide's, taking off where the rope is used; from a cave, after a climb to the open air under a black screen. The pixel-art scene stays as the fallback.

### Changed

- Missions are drawn among all the allowed kinds: the specials mission is no longer always the first one, nor always there.

### Fixed

- Cobblemon NPC dialogues with many answers (the Safari Guide's destinations) ran off the bottom of the screen: the answers now scroll, with a scroll bar and the mouse wheel.
- Answers too long for their button spilled over its edges: their text now shrinks to fit.

### Config

- `server.json` goes to version 4: new `bounties` keys `allowedKinds`, `kinds`, `speciesFilter` and `swap`. The former `typeRewardPerCatch`, `speciesReward` and `featuredReward` are moved into `bounties.kinds` on load.

See [CONFIG.md](CONFIG.md) for every key.

## 1.1.0

Update the mod on the server **and** on every client: the network protocol changed (`1.3.0`), and a client of another version is refused when it joins on NeoForge.

### New

- **Safari Guide** in every rest house. He takes you to any other rest house of the island, each one named by its biome ("Jungle Safari", with its direction when two share a biome). Every house can be reached, even one nobody has visited yet: it is built while you travel.
- **Flight cinematic** for the Guide's trips. A double of you, wearing your skin, takes off on the first Pokémon of your party that can be ridden in the air (a Staraptor if none can) and lands at the destination, filmed with the game's own camera. Players around see it too. `restHouseTravel.cinematicStyle: "PANORAMA"` brings back the painted landscape instead.
- **Background building** of the rest houses on a new map, so trips rarely wait (`restHouseTravel.prebuildHouses`).
- **Shop: choose what you give.** When a trade accepts any item of a kind (any evolution stone, any apricorn) and you carry several, the shop asks which one to give.
- **New ticket trade**: one fossil (`#cobblemon:fossils`) for a Safari Ticket, once per rotation. Existing `shop.json` files get it added once.
- **Wall screen footers**: each player picks, per kind of screen, whether the bottom bar counts down their hunting time or the next rotation (`board.featuredFooter`, `statsFooter`, `bountiesFooter` in `client.json`).

### Changed

- **Escape Rope** opens a small menu: climb to the nearest safe spot in the open air (instead of straight up), or go to the nearest rest house, with its own pixel-art scene.
- **Species rules split in two**: `spawns` for Cobblemon's natural spawns, `featured` for the Pokémon of the day, each with its own categories, caps and exclusions. A 1.0.0 `server.json` is converted on load, so nothing changes in game until you edit it.
- **Per-way caps**: `maxSpawnPerPokemon` counts the natural spawns and the specials apart.
- **Refused species leave Cobblemon's spawn draw** itself: they no longer show in `/checkspawn` in the Safari, and a species with a huge datapack weight can no longer starve the others. Species at their cap or crowding the player leave the draw the same way until a slot frees up.
- The Safari Warden swims out of water, stays near his spot, keeps clear of the gate's pane, and his shop and entry work wherever he stands on his leash.

### Fixed

- Talking to the Safari Warden could show a red "too far from the gate" message when he had wandered off or got stuck in water.
- The Escape Rope could leave a player stuck inside a tree crown.
- The shop took the first item of a tag in its own order (a "random" evolution stone, as players saw it) instead of letting the player choose.

### Config

- `server.json` goes to version 3: new section `restHouseTravel` (`guideEnabled`, `ropeEnabled`, `prebuildHouses`, `cooldownSeconds` 30, `cinematicSeconds` 3, `cinematicStyle`), and the `spawns` / `featured` split.
- `shop.json` goes to version 2 (fossil trade).
- `client.json`: new keys `board.featuredFooter`, `board.statsFooter`, `board.bountiesFooter`.

See [CONFIG.md](CONFIG.md) for every key.

## 1.0.0

First release.
