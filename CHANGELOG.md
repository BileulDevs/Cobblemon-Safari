# Changelog

## 1.3.0

### New

- **Safari statistics** in the game's Statistics screen (General tab): hunts, time hunted, Pokémon, shinies and specials caught, best hunt, Safari points earned and spent, missions completed and rest house trips. Your Safari record from earlier versions is carried over on your next login.
- **Safari Mark**: every Pokémon caught in the Safari gets a mark, "the Safari Find", in its summary's marks page; its title shows when the Pokémon has no other one (`capture.awardSafariMark`).
- **Island relief** (`reset.relief`): `FLAT`, `ROLLING` (as before) or `MOUNTAINOUS`, for each new map.

### Fixed

- The Safari Guide could take you to another rest house than the one you picked ("Deep Forest" landing in the jungle) after you scrolled his list with the mouse wheel: the click went to the answer that had just scrolled out. The same happened in any long Cobblemon dialogue.

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
