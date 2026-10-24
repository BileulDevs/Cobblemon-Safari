package dev.darcosse.common.cobblemonsafari.config;

import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import dev.darcosse.common.cobblemonsafari.spawn.Rarity;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Owns the shop file: loading, validation, rewriting fixed values. Loaded
 * together with the main config, so {@code /safari reload} covers both.
 */
public final class SafariShopConfigManager {

    /** Path under the config directory: the mod's folder keeps its files together. */
    private static final String FILE_NAME = CobblemonSafari.MOD_ID + "/shop.json";

    /**
     * Most items one cost may ask for: nine stacks. Beyond that the trade
     * could never fit an inventory alongside anything else.
     */
    private static final int MAX_COST_COUNT = 9 * 64;

    /**
     * Longest wait an entry may ask, in hours: a year. Beyond it the entry
     * can in practice be bought once, which limitPerRotation 0 says better.
     */
    private static final int MAX_COOLDOWN_HOURS = 24 * 365;

    /**
     * Format version written to the file. Raise it when a field changes
     * meaning and add the conversion to {@link #validate}; a field added or
     * removed needs no bump, Gson fills new fields with their defaults and
     * ignores unknown keys.
     *
     * <p>History: 2 added the fossil ticket trade to existing files
     * ({@link #addFossilTrade}).
     */
    public static final int CURRENT_VERSION = 2;

    /** First version whose default entries include the fossil ticket trade. */
    private static final int FOSSIL_TRADE_VERSION = 2;

    /**
     * Most random Pokémon per rotation. Each one is built for the screen
     * whenever the shop opens; past a few pages of them the screen is a
     * catalogue nobody reads.
     */
    private static final int MAX_ROTATING = 32;

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
            new JsonConfigFile<>(FILE_NAME, SafariShopConfig.class, SafariShopConfig::new);

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

    /**
     * Adds the default trade of a fossil for a ticket to a file written
     * before it existed, unless the file already trades fossils. Once only:
     * the version then moves on, so an operator who removes the entry
     * afterwards keeps it removed.
     */
    private static void addFossilTrade(SafariShopConfig c) {
        for (SafariShopConfig.Entry entry : c.entries) {
            if (entry != null && entry.hasCost()) {
                for (SafariShopConfig.Cost cost : entry.cost) {
                    if (cost != null && SafariShopConfig.TRADE_FOSSIL_TAG.equals(cost.item)) {
                        return;
                    }
                }
            }
        }
        c.entries.add(SafariShopConfig.fossilTrade());
        CobblemonSafari.LOGGER.info("Added the fossil ticket trade to {} (shop version {} -> {})", FILE_NAME,
                c.configVersion, CURRENT_VERSION);
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
        if (c.configVersion < FOSSIL_TRADE_VERSION && c.entries != null) {
            addFossilTrade(c);
        }
        if (c.configVersion != CURRENT_VERSION) {
            // The rewrite also drops keys Gson ignored, so the file shows what is read.
            c.configVersion = CURRENT_VERSION;
            corrected = true;
        }
        if (c.tabs == null || c.tabs.isEmpty()) {
            // Without tabs no entry could be shown: the default tabs come back.
            c.tabs = new SafariShopConfig().tabs;
            corrected = true;
        }
        corrected |= validateTabs(c);
        corrected |= validateRotating(c);
        if (c.rotatingItems == null) {
            c.rotatingItems = SafariShopConfig.RotatingGoods.items();
            corrected = true;
        }
        if (c.rotatingBalls == null) {
            c.rotatingBalls = SafariShopConfig.RotatingGoods.balls();
            corrected = true;
        }
        corrected |= validateGoods(c, c.rotatingItems, "rotatingItems");
        corrected |= validateGoods(c, c.rotatingBalls, "rotatingBalls");
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
            if (entry.price < 0 && !entry.hasAutoPrice()) {
                CobblemonSafari.LOGGER.warn("Shop entry with a negative price set to 0: {} {}",
                        entry.item, entry.pokemon);
                entry.price = 0;
                corrected = true;
            }
            if (entry.hasAutoPrice() && !entry.isPokemon()) {
                // Only a Pokémon has a rarity and a stage to price from; a free
                // item would be worse than a missing one.
                CobblemonSafari.LOGGER.warn("Removing shop item entry '{}': \"auto\" prices Pokémon only", entry.item);
                corrected = true;
                continue;
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
        if (entry.cooldownHours != null) {
            // 0 or less means no wait: dropped so the file shows none.
            Integer hours = entry.cooldownHours <= 0 ? null : Math.min(MAX_COOLDOWN_HOURS, entry.cooldownHours);
            if (!Objects.equals(hours, entry.cooldownHours)) {
                entry.cooldownHours = hours;
                corrected = true;
            }
        }
        return corrected;
    }

    /**
     * Fixes the random Pokémon settings: missing section, counts and prices
     * out of range, unknown rarities, a tab that does not exist.
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
        if (count != r.count) {
            r.count = count;
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
     * Fixes a random items section: count in range, missing lists, malformed
     * ids in the lists, price rules with a bad id or out-of-range values, a
     * tab that does not exist. Ids are only checked for syntax: the config
     * loads before other mods register their items and tags.
     *
     * @param name the section's key, for the log
     * @return true if anything changed
     */
    private static boolean validateGoods(SafariShopConfig c, SafariShopConfig.RotatingGoods g, String name) {
        boolean corrected = false;
        int count = Math.max(0, Math.min(MAX_ROTATING, g.count));
        if (count != g.count) {
            g.count = count;
            corrected = true;
        }
        if (g.always == null) {
            g.always = new ArrayList<>();
            corrected = true;
        }
        if (g.excluded == null) {
            g.excluded = new ArrayList<>();
            corrected = true;
        }
        if (g.prices == null) {
            g.prices = new ArrayList<>();
            corrected = true;
        }
        if (g.always.removeIf(id -> id == null || ResourceLocation.tryParse(id) == null)) {
            CobblemonSafari.LOGGER.warn("Removed invalid item ids from {}.always in {}", name, FILE_NAME);
            corrected = true;
        }
        if (g.excluded.removeIf(id -> id == null || ResourceLocation.tryParse(
                id.startsWith(SafariShopConfig.Cost.TAG_PREFIX) ? id.substring(1) : id) == null)) {
            CobblemonSafari.LOGGER.warn("Removed invalid ids from {}.excluded in {}", name, FILE_NAME);
            corrected = true;
        }
        if (g.prices.removeIf(rule -> rule == null || rule.id() == null)) {
            CobblemonSafari.LOGGER.warn("Removed price rules with an invalid item from {}.prices in {}", name, FILE_NAME);
            corrected = true;
        }
        for (SafariShopConfig.PriceRule rule : g.prices) {
            int stack = Math.max(1, Math.min(MAX_COUNT, rule.count));
            int price = Math.max(0, rule.price);
            if (stack != rule.count || price != rule.price) {
                rule.count = stack;
                rule.price = price;
                corrected = true;
            }
        }
        if (g.tab == null || c.tabs.stream().noneMatch(tab -> tab.id.equals(g.tab))) {
            String fallback = c.tabs.get(0).id;
            CobblemonSafari.LOGGER.warn("{}.tab '{}' is not a tab of {}; using '{}'", name, g.tab, FILE_NAME, fallback);
            g.tab = fallback;
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
