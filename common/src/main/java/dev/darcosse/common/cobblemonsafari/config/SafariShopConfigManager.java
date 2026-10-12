package dev.darcosse.common.cobblemonsafari.config;

import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import dev.darcosse.common.cobblemonsafari.spawn.Rarity;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Owns the shop file: loading, validation, rewriting fixed values. Loaded
 * together with the main config, so {@code /safari reload} covers both.
 */
public final class SafariShopConfigManager {

    /** Path under the config directory: the mod's folder keeps its files together. */
    private static final String FILE_NAME = CobblemonSafari.MOD_ID + "/shop.json";
    /** Where the file lived before the mod had its own config folder; moved into it on load. */
    private static final String LEGACY_FILE_NAME = CobblemonSafari.MOD_ID + "_shop.json";

    /** The version whose default Tauros costs 200 points; the old 1500 is lowered. */
    private static final int CHEAPER_TAUROS_VERSION = 7;

    /** The version whose default trades take tags; version 5 defaults are converted. */
    private static final int TAG_TRADES_VERSION = 6;

    /** Version 5 default trade costs and the tag each one becomes. */
    private static final Map<String, String> OLD_TRADE_ITEMS = Map.of(
            "cobblemon:red_apricorn", SafariShopConfig.TRADE_APRICORN_TAG,
            "cobblemon:oran_berry", SafariShopConfig.TRADE_BERRY_TAG,
            "cobblemon:fire_stone", SafariShopConfig.TRADE_STONE_TAG);

    /** The version that added ticket trades; older files get the default ones once. */
    private static final int TICKET_TRADES_VERSION = 5;

    /**
     * Most items one cost may ask for: nine stacks. Beyond that the trade
     * could never fit an inventory alongside anything else.
     */
    private static final int MAX_COST_COUNT = 9 * 64;

    /** The version whose defaults sell a single Pokémon instead of five. */
    private static final int SINGLE_POKEMON_VERSION = 4;

    /** Pokémon sold by default up to version 3, as written in those files. */
    private static final Set<String> OLD_DEFAULT_POKEMON = Set.of("abra level=10", "clefairy level=12",
            "porygon level=20", "dratini level=15", "scyther level=20");

    /** Version written by this build. */
    public static final int CURRENT_VERSION = 7;

    /**
     * Most random Pokémon per rotation. Each one is built for the screen
     * whenever the shop opens; past a few pages of them the screen is a
     * catalogue nobody reads.
     */
    private static final int MAX_ROTATING = 32;

    /** Lowest and highest level Cobblemon accepts by default. */
    private static final int MIN_LEVEL = 1;
    private static final int MAX_LEVEL = 100;

    /**
     * Largest stack an item entry may give. Beyond a full stack the surplus is
     * dropped at the player's feet, which the shop is not meant to do.
     */
    private static final int MAX_COUNT = 64;

    /** Rarity names accepted in rotating.rarities: Cobblemon's spawn buckets, as {@link Rarity} names them. */
    private static final Set<String> KNOWN_RARITIES = Arrays.stream(Rarity.values())
            .map(rarity -> rarity.bucket).collect(Collectors.toUnmodifiableSet());

    /** Item ids ending with this go to the Poké Balls tab by default. */
    private static final String BALL_SUFFIX = "_ball";

    private static final JsonConfigFile<SafariShopConfig> FILE =
            new JsonConfigFile<>(FILE_NAME, LEGACY_FILE_NAME, SafariShopConfig.class, SafariShopConfig::new);

    private static SafariShopConfig config = new SafariShopConfig();

    private SafariShopConfigManager() {
    }

    /** Reads the file, drops broken entries, and rewrites it if anything was fixed. */
    public static void load() {
        SafariShopConfig loaded = FILE.read();
        if (validate(loaded)) {
            FILE.write(loaded);
        }
        config = loaded;
    }

    /** Current shop. Treat as read-only. */
    public static SafariShopConfig get() {
        return config;
    }

    /**
     * Removes entries that could never be sold and clamps the rest.
     *
     * <p>Item ids are only checked for syntax here: the config loads during
     * mod init, before other mods have registered their items. An id that
     * still names nothing when the shop opens is shown as unavailable
     * ({@link #itemExists}) rather than deleted from the file.
     *
     * @return true if the file must be rewritten
     */
    private static boolean validate(SafariShopConfig c) {
        boolean corrected = false;
        if (c.configVersion < SINGLE_POKEMON_VERSION && c.entries != null && holdsOldDefaultPokemon(c.entries)) {
            replaceOldDefaultPokemon(c.entries);
            CobblemonSafari.LOGGER.info("Migrated {}: the five default Pokémon became a single {}",
                    FILE_NAME, SafariShopConfig.DEFAULT_POKEMON);
        }
        if (c.configVersion < TICKET_TRADES_VERSION) {
            addTicketTrades(c);
        } else if (c.configVersion < TAG_TRADES_VERSION) {
            convertTradesToTags(c);
        }
        if (c.configVersion < CHEAPER_TAUROS_VERSION && c.entries != null) {
            for (SafariShopConfig.Entry entry : c.entries) {
                // Only the untouched default: same properties, old price.
                if (entry != null && entry.isPokemon() && SafariShopConfig.DEFAULT_POKEMON.equals(entry.pokemon)
                        && entry.price == SafariShopConfig.OLD_DEFAULT_POKEMON_PRICE) {
                    entry.price = SafariShopConfig.DEFAULT_POKEMON_PRICE;
                    CobblemonSafari.LOGGER.info("Migrated {}: {} now costs {} points", FILE_NAME,
                            entry.pokemon, entry.price);
                }
            }
        }
        if (c.configVersion != CURRENT_VERSION) {
            c.configVersion = CURRENT_VERSION;
            corrected = true;
        }
        if (c.tabs == null || c.tabs.isEmpty()) {
            // Version 1 files have no tabs: they get the default ones.
            c.tabs = new SafariShopConfig().tabs;
            corrected = true;
        }
        corrected |= validateTabs(c);
        corrected |= validateRotating(c);
        if (c.entries == null) {
            c.entries = new SafariShopConfig().entries;
            assignTabs(c);
            return true;
        }
        List<SafariShopConfig.Entry> kept = new ArrayList<>();
        for (SafariShopConfig.Entry entry : c.entries) {
            if (entry == null) {
                corrected = true;
                continue;
            }
            if (entry.price < 0) {
                CobblemonSafari.LOGGER.warn("Shop entry with a negative price set to 0: {} {}",
                        entry.item, entry.pokemon);
                entry.price = 0;
                corrected = true;
            }
            if (entry.isPokemon()) {
                if (entry.pokemon == null || entry.pokemon.isBlank()) {
                    CobblemonSafari.LOGGER.warn("Removing a shop Pokémon entry with no properties");
                    corrected = true;
                    continue;
                }
                // Item fields mean nothing here: cleared so the file does not show them.
                if (entry.item != null || entry.count != null) {
                    entry.item = null;
                    entry.count = null;
                    corrected = true;
                }
            } else {
                if (!SafariShopConfig.Entry.TYPE_ITEM.equals(entry.type)) {
                    CobblemonSafari.LOGGER.warn("Unknown shop entry type '{}', treated as an item", entry.type);
                    entry.type = SafariShopConfig.Entry.TYPE_ITEM;
                    corrected = true;
                }
                if (entry.item == null || ResourceLocation.tryParse(entry.item) == null) {
                    CobblemonSafari.LOGGER.warn("Removing a shop item entry with an invalid id '{}'", entry.item);
                    corrected = true;
                    continue;
                }
                if (entry.count == null) {
                    entry.count = 1;
                    corrected = true;
                } else if (entry.count < 1 || entry.count > MAX_COUNT) {
                    entry.count = Math.max(1, Math.min(MAX_COUNT, entry.count));
                    corrected = true;
                }
                // The Pokémon field means nothing here: cleared so the file does not show it.
                if (entry.pokemon != null) {
                    entry.pokemon = null;
                    corrected = true;
                }
            }
            corrected |= validateCostAndLimit(entry);
            kept.add(entry);
        }
        c.entries = kept;
        corrected |= assignTabs(c);
        return corrected;
    }

    /**
     * Drops invalid costs (bad id, count out of range clamped) and a
     * negative limit. An empty cost list is removed so the file shows a
     * plain purchase.
     *
     * @return true if anything changed
     */
    private static boolean validateCostAndLimit(SafariShopConfig.Entry entry) {
        boolean corrected = false;
        if (entry.cost != null) {
            int before = entry.cost.size();
            entry.cost.removeIf(cost -> cost == null || cost.id() == null);
            if (entry.cost.size() != before) {
                CobblemonSafari.LOGGER.warn("Removed invalid costs from a shop entry {}{}", entry.item, entry.pokemon);
                corrected = true;
            }
            for (SafariShopConfig.Cost cost : entry.cost) {
                int count = Math.max(1, Math.min(MAX_COST_COUNT, cost.count));
                if (count != cost.count) {
                    cost.count = count;
                    corrected = true;
                }
            }
            if (entry.cost.isEmpty()) {
                entry.cost = null;
                corrected = true;
            }
        }
        if (entry.limitPerRotation != null && entry.limitPerRotation < 0) {
            entry.limitPerRotation = 0;
            corrected = true;
        }
        return corrected;
    }

    /**
     * Turns the version 5 default ticket trades (one item each) into the tag
     * trades of version 6. Only a ticket trade asking for exactly one of
     * those items is converted; any trade the admin wrote stays as it is.
     */
    private static void convertTradesToTags(SafariShopConfig c) {
        if (c.entries == null) {
            return;
        }
        for (SafariShopConfig.Entry entry : c.entries) {
            if (entry == null || !SafariShopConfig.TICKET_ITEM.equals(entry.item) || entry.cost == null
                    || entry.cost.size() != 1 || entry.cost.get(0) == null) {
                continue;
            }
            String tag = OLD_TRADE_ITEMS.get(entry.cost.get(0).item);
            if (tag != null) {
                CobblemonSafari.LOGGER.info("Ticket trade for {} now takes any of {}", entry.cost.get(0).item, tag);
                entry.cost.get(0).item = tag;
            }
        }
    }

    /**
     * Brings the tickets tab and the default ticket trades into a file
     * written before they existed, once: unless the file already has an
     * entry giving tickets (the admin's own trade or sale).
     */
    private static void addTicketTrades(SafariShopConfig c) {
        SafariShopConfig defaults = new SafariShopConfig();
        if (c.tabs != null && c.tabs.stream().noneMatch(tab -> tab != null
                && SafariShopConfig.Tab.TICKETS.equals(tab.id))) {
            defaults.tabs.stream().filter(tab -> SafariShopConfig.Tab.TICKETS.equals(tab.id))
                    .findFirst().ifPresent(c.tabs::add);
        }
        if (c.entries == null || c.entries.stream().anyMatch(entry -> entry != null
                && SafariShopConfig.TICKET_ITEM.equals(entry.item))) {
            return;
        }
        defaults.entries.stream().filter(SafariShopConfig.Entry::hasCost).forEach(c.entries::add);
        CobblemonSafari.LOGGER.info("Added the default ticket trades to {}", FILE_NAME);
    }

    /**
     * Whether the file still sells exactly the Pokémon of the version 1 to 3
     * defaults (same properties, any order). Any edit to them, or another
     * Pokémon added, means the admin chose their list: it is then kept.
     */
    private static boolean holdsOldDefaultPokemon(List<SafariShopConfig.Entry> entries) {
        Set<String> pokemon = new HashSet<>();
        for (SafariShopConfig.Entry entry : entries) {
            if (entry != null && entry.isPokemon()) {
                pokemon.add(entry.pokemon);
            }
        }
        return pokemon.equals(OLD_DEFAULT_POKEMON);
    }

    /** Swaps the old default Pokémon for the single new one, at the place of the first. */
    private static void replaceOldDefaultPokemon(List<SafariShopConfig.Entry> entries) {
        int first = -1;
        for (int i = 0; i < entries.size(); i++) {
            SafariShopConfig.Entry entry = entries.get(i);
            if (entry != null && entry.isPokemon()) {
                first = first < 0 ? i : first;
            }
        }
        SafariShopConfig.Entry tauros = new SafariShopConfig().entries.stream()
                .filter(SafariShopConfig.Entry::isPokemon).findFirst().orElseThrow();
        entries.removeIf(entry -> entry != null && entry.isPokemon());
        entries.add(Math.min(first, entries.size()), tauros);
    }

    /**
     * Fixes the random Pokémon settings: missing section, counts, prices and
     * level out of range, unknown rarities, a tab that does not exist.
     *
     * @return true if anything changed
     */
    private static boolean validateRotating(SafariShopConfig c) {
        boolean corrected = false;
        if (c.rotating == null) {
            c.rotating = new SafariShopConfig.Rotating();
            return true;
        }
        SafariShopConfig.Rotating r = c.rotating;
        int count = Math.max(0, Math.min(MAX_ROTATING, r.count));
        int level = Math.max(MIN_LEVEL, Math.min(MAX_LEVEL, r.level));
        if (count != r.count || level != r.level) {
            r.count = count;
            r.level = level;
            corrected = true;
        }
        if (r.commonPrice < 0 || r.uncommonPrice < 0 || r.rarePrice < 0 || r.ultraRarePrice < 0) {
            r.commonPrice = Math.max(0, r.commonPrice);
            r.uncommonPrice = Math.max(0, r.uncommonPrice);
            r.rarePrice = Math.max(0, r.rarePrice);
            r.ultraRarePrice = Math.max(0, r.ultraRarePrice);
            corrected = true;
        }
        if (r.rarities == null) {
            r.rarities = new SafariShopConfig.Rotating().rarities;
            corrected = true;
        }
        if (r.rarities.removeIf(name -> name == null || !KNOWN_RARITIES.contains(name))) {
            CobblemonSafari.LOGGER.warn("Removed unknown rarities from rotating.rarities in {}; known: {}",
                    FILE_NAME, KNOWN_RARITIES);
            corrected = true;
        }
        if (r.excludedSpecies == null) {
            r.excludedSpecies = new ArrayList<>();
            corrected = true;
        }
        if (r.tab == null || c.tabs.stream().noneMatch(tab -> tab.id.equals(r.tab))) {
            String fallback = c.tabs.stream().anyMatch(tab -> tab.id.equals(SafariShopConfig.Tab.POKEMON))
                    ? SafariShopConfig.Tab.POKEMON : c.tabs.get(0).id;
            CobblemonSafari.LOGGER.warn("rotating.tab '{}' is not a tab of {}; using '{}'", r.tab, FILE_NAME, fallback);
            r.tab = fallback;
            corrected = true;
        }
        return corrected;
    }

    /**
     * Drops tabs without an id and duplicate ids (the first wins); fixes null
     * names and icons.
     *
     * @return true if anything changed
     */
    private static boolean validateTabs(SafariShopConfig c) {
        boolean corrected = false;
        Set<String> seen = new HashSet<>();
        List<SafariShopConfig.Tab> kept = new ArrayList<>();
        for (SafariShopConfig.Tab tab : c.tabs) {
            if (tab == null || tab.id == null || tab.id.isBlank() || !seen.add(tab.id)) {
                CobblemonSafari.LOGGER.warn("Removing a shop tab with no id or a duplicate id: {}",
                        tab == null ? null : tab.id);
                corrected = true;
                continue;
            }
            if (tab.name == null) {
                tab.name = "";
                corrected = true;
            }
            if (tab.icon == null) {
                tab.icon = "";
                corrected = true;
            }
            kept.add(tab);
        }
        if (kept.isEmpty()) {
            kept = new SafariShopConfig().tabs;
            corrected = true;
        }
        c.tabs = kept;
        return corrected;
    }

    /**
     * Gives every entry a tab that exists: its own if valid, otherwise the
     * one its type suggests, otherwise the first tab. Written back to the
     * file so the admin sees where each entry went.
     *
     * @return true if any entry changed tab
     */
    private static boolean assignTabs(SafariShopConfig c) {
        Set<String> ids = new HashSet<>();
        for (SafariShopConfig.Tab tab : c.tabs) {
            ids.add(tab.id);
        }
        boolean corrected = false;
        for (SafariShopConfig.Entry entry : c.entries) {
            if (entry.tab != null && ids.contains(entry.tab)) {
                continue;
            }
            if (entry.tab != null && !entry.tab.isEmpty()) {
                CobblemonSafari.LOGGER.warn("Shop entry {}{} names an unknown tab '{}'; moved",
                        entry.item, entry.pokemon, entry.tab);
            }
            String suggested = suggestedTab(entry);
            entry.tab = ids.contains(suggested) ? suggested : c.tabs.get(0).id;
            corrected = true;
        }
        return corrected;
    }

    /** The built-in tab matching an entry's type. */
    private static String suggestedTab(SafariShopConfig.Entry entry) {
        if (entry.isPokemon()) {
            return SafariShopConfig.Tab.POKEMON;
        }
        if (SafariShopConfig.TICKET_ITEM.equals(entry.item)) {
            return SafariShopConfig.Tab.TICKETS;
        }
        ResourceLocation id = ResourceLocation.tryParse(entry.item);
        return id != null && id.getPath().endsWith(BALL_SUFFIX) ? SafariShopConfig.Tab.BALLS : SafariShopConfig.Tab.ITEMS;
    }

    /** Index of a tab id in the tab list, or 0 when missing (cannot happen after validation). */
    public static int tabIndex(String id) {
        List<SafariShopConfig.Tab> tabs = config.tabs;
        for (int i = 0; i < tabs.size(); i++) {
            if (tabs.get(i).id.equals(id)) {
                return i;
            }
        }
        return 0;
    }

    /** Whether an item entry names an item that exists right now. */
    public static boolean itemExists(SafariShopConfig.Entry entry) {
        ResourceLocation id = ResourceLocation.tryParse(entry.item);
        return id != null && BuiltInRegistries.ITEM.containsKey(id);
    }
}
