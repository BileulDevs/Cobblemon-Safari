# Changelog

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
