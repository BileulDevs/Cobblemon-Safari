# Changelog

## 1.4.0

New network protocol (`1.5.0`): clients and servers must both update.

### New

- **A Safari advancement tab**, with 47 advancements: discovering the island (its fourteen habitats, the caverns, the summit, the ruins and fossils, the Pokémon habitats, the rest house trips), hunting (first catch, out of battle with a Safari Ball, specials, ultra-rare specials, shinies, at night, 10 and 30 Pokémon in one hunt, 50 species, and two hidden ones), baits (Poké Snacks and Cakes), the clock (a full time bank, a hunt to the last second, a catch in the last 10 seconds, an early exit with a refund), missions, points and the shop, loyalty (10 and 50 hunts, 7 maps), and fishing (first Pokémon fished, a fished Pokémon caught, 10 and 50 fished, with bait, a shiny, a special, in a cave, from a fishing boat). Translated in the twelve languages of the mod. Thresholds already passed are granted at the next login; the number of maps hunted on is counted from this version on.
- **New statistic: Pokémon fished in the Safari** (`cobblemon_safari:pokemon_fished`).
- **Hunger can be frozen in the Safari** (`protection.hunger`, `false` by default): set it to `true` and the food bar stays where it was on entering, so a timed hunt is not spent eating. With `false`, hunger works as everywhere else.
- **Two underground rest houses**, one in the giant cavern and one in the lush cavern, each in a chamber dug beside the passage that leads down to it. The Safari Guide offers them as their own destinations ("Safari Giant Cavern", "Safari Lush Cavern"), and the Escape Rope's nearest rest house includes them when used in a cave. On a map generated before 1.4.0, the lush cavern's house is dug where the new, larger cavern will be, so it may not meet the old passage until the next map: the guide still reaches it.
- **Burrow cinematic** for trips into or out of a cave (the Safari Guide to or from an underground rest house, the Escape Rope's nearest rest house from a cave, and the Escape Rope's climb to the open air from a cave): your double rides a Pokémon that digs into the ground in front of the house, throwing up earth, and comes out of the ground in front of the destination. The Pokémon is the first of your party that digs naturally (Dugtrio, Hippowdon, Rhydon, Krookodile, Sandslash, Excadrill, Steelix, Sandaconda, Dudunsparce, Orthworm, Trapinch...) and could learn Dig, or a Dugtrio; small ones are grown to the size of a double-sized Dugtrio, and your double sits on its back.
- **Dive cinematic** for the Escape Rope used in the water: your double rides a Pokémon of your party that could learn Dive and swims with a rider (or a Wailmer) under the water to the nearest shore, then the trip goes on from there, flying or, in a cave, burrowing.

### Changed

- **New relief `GENTLE`, now the default** (`reset.relief`): the land two thirds as high and the peaks half as sharp as `ROLLING`, whose plateaus rose up to some forty blocks above the rivers in sheer walls. `ROLLING` is unchanged and still available. A config file that already says `"ROLLING"` keeps it: change it to `"GENTLE"`. Only new maps take it.
- **A new map is created faster**: the entrance rest house is no longer built during the server start, which generated its chunks on the server thread for several seconds. It is built in the background right after, like the other houses; a player entering before it stands has it built on arrival.
- **The lush cavern is as large as the giant cavern** (radius 120 instead of 80, 120 blocks tall instead of 90), lush from end to end, and lies a little further south (around z 307), so the two cave systems keep solid rock between them. Shows on the next map.
- **No structure in the Safari but those it allows**: the Safari's biomes are bridged into Cobblemon's habitat tags for their spawns, and Cobblemon's structures came along through the same tags (its habitats, ruins and Gimmighoul towers, on top of the Safari's own ruins and towers, and the cave arches repeated every few chunks), as did its template features (fossil sites, tidepools, the deep crypt in the deep cavern...), the desert well and the buried fossils. Now nothing is generated in the Safari but the structures tagged `cobblemon_safari:allowed_in_safari` and the building features tagged `cobblemon_safari:allowed_in_safari` (placed feature tag), the rest houses, placed by code, aside: the Safari's three henges (stone, moon, sun), its low ruins (toppled pillars, shaded ruins, overgrown trial) Cobblemon's surface fossil sites (suspicious mounds, lush den, mud pit, rooted pits, frozen pond and spike, powdered deposit, submerged spike, preserved skeleton, hydrothermal vents), its root nurseries, sandy tidepools, rocky outcrops and fishing boats, and eighteen of its small habitats (berry patches, ponds, flower and sunflower clearings, sandpits, wildfire scars, peat bogs, bug mounds, fae mounds, carved ice spikes, shaded rocks, meteorite impacts, the jungle's canopy, peat bog, zen garden and thermal crevices). The habitats are the Safari's own copies, drawn at random among those that suit each spot, about seven or eight per map, spread out and kept away from the ruins. The Gimmighoul towers, the desert town centre, the frozen altar and Cobblemon's own copies of the ruins stay out. Terrain features (trees, geodes, ice spikes, huge mushrooms) stay.
- **The Escape Rope comes first in the shop's items tab**, as the Safari Balls do in theirs.

### Fixed

- A rest house's clerk or guide could stand on the roof, mostly in the jungle, where the leaves of a tree left beside the house spread over it. The staff now always find the floor from the house's own roof, and those already on a roof are put back behind the counter.
- Poké Snacks placed in the Safari were nearly never bitten: the boosted spawns filled their area to the snack's own limit, so the snack had no room to bring a Pokémon. In the Safari, no natural Pokémon spawns within 16 blocks of a snack any more, and a snack may bring Pokémon up to 1.5 times the Safari's density.
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
