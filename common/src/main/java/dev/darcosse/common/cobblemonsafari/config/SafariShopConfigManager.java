package dev.darcosse.common.cobblemonsafari.config;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.darcosse.common.cobblemonsafari.network.SafariShopPayload;
import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Owns the shop file: loading, conversion of older layouts, validation,
 * rewriting fixed values. Loaded together with the main config, so
 * {@code /safari reload} covers both.
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
     * Longest wait an offer may ask, in hours: a year. Beyond it the offer
     * can in practice be bought once, which a lifetime limit says better.
     */
    private static final int MAX_COOLDOWN_HOURS = 24 * 365;

    /**
     * Format version written to the file. Raise it when the layout or a
     * field's meaning changes and add the conversion to {@link #migrateTree};
     * a field added needs no bump, Gson fills it with its default.
     *
     * <p>History: 2 added the fossil ticket trade to existing files. 3
     * renamed {@code rotating} to {@code rotatingPokemon}. 4 laid the file
     * out by tab ({@code fixed} and rotating draws in each tab, optional
     * {@code sections}), gave every offer one {@code limit} object instead of
     * {@code limitPerRotation} and {@code cooldownHours}, priced the Pokémon
     * draw by rarity and legendary-class category, and added the prepared
     * dishes (see {@link #convertToV4}).
     */
    public static final int CURRENT_VERSION = 4;

    /** First version whose default offers include the fossil ticket trade. */
    private static final int FOSSIL_TRADE_VERSION = 2;

    /**
     * Most offers per draw. Each Pokémon is built for the screen whenever the
     * shop opens; past a few pages of them the screen is a catalogue nobody reads.
     */
    private static final int MAX_ROTATING = 32;

    /** Most legendary-class Pokémon one category adds per rotation, same reason. */
    private static final int MAX_PER_CATEGORY = 8;

    /**
     * Largest stack an offer may give. Beyond a full stack the surplus is
     * dropped at the player's feet, which the shop is not meant to do.
     */
    private static final int MAX_COUNT = 64;

    /** Most seasonings a dish may take: the Poké Pot's three seasoning slots. */
    private static final int MAX_SEASONINGS = 3;

    /** Highest level Cobblemon accepts by default. */
    private static final int MAX_LEVEL = 100;

    /** Item ids ending with this went to the Poké Balls tab in version 3 files. */
    private static final String BALL_SUFFIX = "_ball";

    private static final JsonConfigFile<SafariShopConfig> FILE =
            new JsonConfigFile<>(FILE_NAME, SafariShopConfig.class, SafariShopConfig::new,
                    SafariShopConfigManager::migrateTree);

    /**
     * One tab or section and where it sits, for the draws, which must place
     * what they draw on the shelf that asked for it.
     *
     * @param tab     id of the tab
     * @param section name of the section, empty for the tab's own part
     * @param shelf   what it sells
     */
    public record ShelfRef(String tab, String section, SafariShopConfig.Shelf shelf) {
    }

    private static SafariShopConfig config = new SafariShopConfig();
    private static List<ShelfRef> shelves = List.of();

    private SafariShopConfigManager() {
    }

    /** Reads the file, drops broken offers, and rewrites it if anything was fixed. */
    public static void load() {
        SafariShopConfig loaded = FILE.read();
        if (validate(loaded)) {
            FILE.write(loaded);
        }
        List<ShelfRef> refs = new ArrayList<>();
        List<SafariShopConfig.Entry> flat = new ArrayList<>();
        for (SafariShopConfig.Tab tab : loaded.tabs) {
            refs.add(new ShelfRef(tab.id, "", tab));
            if (tab.sections != null) {
                for (SafariShopConfig.Section section : tab.sections) {
                    refs.add(new ShelfRef(tab.id, section.name, section));
                }
            }
        }
        for (ShelfRef ref : refs) {
            for (SafariShopConfig.Entry entry : ref.shelf().fixed) {
                flat.add(entry.placed(ref.tab(), ref.section()));
            }
        }
        loaded.entries = flat;
        shelves = List.copyOf(refs);
        config = loaded;
    }

    /** Current shop. Treat as read-only. */
    public static SafariShopConfig get() {
        return config;
    }

    /** Every tab and section of the current shop, in screen order. */
    public static List<ShelfRef> shelves() {
        return shelves;
    }

    /** The limit an offer follows: its own, else {@code defaults.limit}; null for none. */
    public static SafariShopConfig.Limit limitOf(SafariShopConfig.Entry entry) {
        return entry.limit != null ? entry.limit : config.defaults.limit;
    }

    // ---------------------------------------------------------------- conversion

    /**
     * Brings an older file to the current layout, on the raw tree before
     * binding (see {@link ConfigTreeMigration}). A version 4 file goes
     * through unchanged.
     */
    private static void migrateTree(String fileName, JsonObject root) {
        new ConfigTreeMigration(fileName, root).move("rotating", "rotatingPokemon");
        if (!root.has("entries") && !root.has("rotatingPokemon")) {
            return;
        }
        int version = root.has("configVersion") ? root.get("configVersion").getAsInt() : 1;
        if (version < FOSSIL_TRADE_VERSION && root.get("entries") instanceof JsonArray entries
                && !tradesFossils(entries)) {
            entries.add(GSON.toJsonTree(SafariShopConfig.fossilTrade()));
        }
        convertToV4(fileName, root);
    }

    /** Plain Gson for the conversion: the shop's own types, nulls left out as in the file. */
    private static final Gson GSON = new Gson();

    /** Whether a version 1 file already trades fossils for a ticket. */
    private static boolean tradesFossils(JsonArray entries) {
        for (JsonElement element : entries) {
            if (element instanceof JsonObject entry && entry.get("cost") instanceof JsonArray costs) {
                for (JsonElement cost : costs) {
                    if (cost instanceof JsonObject c && c.has("item")
                            && SafariShopConfig.TRADE_FOSSIL_TAG.equals(c.get("item").getAsString())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Version 3 to 4: every entry goes into the {@code fixed} list of its tab
     * (its {@code tab}, else the one its type suggested), with
     * {@code limitPerRotation} and {@code cooldownHours} in one {@code limit};
     * {@code rotatingPokemon}, {@code rotatingItems} and {@code rotatingBalls}
     * move into their tab, the Pokémon draw's flat prices into
     * {@code rarities}; the cooking tab, if any, gets its ingredients as a
     * section and the default prepared dishes. Nothing sold before is lost.
     */
    private static void convertToV4(String fileName, JsonObject root) {
        Map<String, JsonObject> tabs = new LinkedHashMap<>();
        if (root.get("tabs") instanceof JsonArray oldTabs) {
            for (JsonElement element : oldTabs) {
                if (element instanceof JsonObject tab && tab.has("id")) {
                    tabs.putIfAbsent(tab.get("id").getAsString(), tab);
                }
            }
        }
        if (tabs.isEmpty()) {
            for (SafariShopConfig.Tab tab : SafariShopConfig.defaultTabs()) {
                JsonObject bare = new JsonObject();
                bare.addProperty("id", tab.id);
                bare.addProperty("name", tab.name);
                bare.addProperty("icon", tab.icon);
                tabs.put(tab.id, bare);
            }
        }
        String firstTab = tabs.keySet().iterator().next();

        if (root.get("entries") instanceof JsonArray entries) {
            for (JsonElement element : entries) {
                if (!(element instanceof JsonObject old)) {
                    continue;
                }
                JsonObject entry = old.deepCopy();
                String type = string(entry.remove("type"), "item");
                String tab = string(entry.remove("tab"), "");
                if (!tabs.containsKey(tab)) {
                    String suggested = suggestedTab(type, string(entry.get("item"), ""));
                    tab = tabs.containsKey(suggested) ? suggested : firstTab;
                }
                JsonObject limit = new JsonObject();
                JsonElement perRotation = entry.remove("limitPerRotation");
                if (perRotation != null && !perRotation.isJsonNull()) {
                    limit.add("max", perRotation);
                }
                JsonElement cooldown = entry.remove("cooldownHours");
                if (cooldown != null && !cooldown.isJsonNull()) {
                    limit.add("cooldownHours", cooldown);
                }
                if (limit.size() > 0) {
                    entry.add("limit", limit);
                }
                if ("pokemon".equals(type)) {
                    entry.remove("item");
                    entry.remove("count");
                }
                fixedOf(tabs.get(tab)).add(entry);
            }
        }

        if (root.get("rotatingPokemon") instanceof JsonObject rotating) {
            String tab = string(rotating.get("tab"), SafariShopConfig.Tab.POKEMON);
            tabs.getOrDefault(tab, tabs.get(firstTab)).add("rotatingPokemon", convertRotatingPokemon(rotating));
        }
        moveGoods(fileName, root, "rotatingItems", SafariShopConfig.Tab.ITEMS, tabs, firstTab);
        moveGoods(fileName, root, "rotatingBalls", SafariShopConfig.Tab.BALLS, tabs, firstTab);

        JsonObject cooking = tabs.get(SafariShopConfig.Tab.COOKING);
        if (cooking != null && !cooking.has("sections")) {
            JsonObject ingredients = new JsonObject();
            ingredients.addProperty("name", SafariShopConfig.SECTION_INGREDIENTS);
            ingredients.add("fixed", fixedOf(cooking));
            cooking.add("fixed", new JsonArray());
            JsonArray sections = new JsonArray();
            sections.add(GSON.toJsonTree(SafariShopConfig.preparedSection()));
            sections.add(ingredients);
            cooking.add("sections", sections);
        }

        JsonArray out = new JsonArray();
        tabs.values().forEach(out::add);
        root.add("tabs", out);
        root.remove("entries");
        root.remove("rotatingPokemon");
        root.remove("rotatingItems");
        root.remove("rotatingBalls");
        CobblemonSafari.LOGGER.info("{}: converted to the layout by tab (shop version 4)", fileName);
    }

    /** The {@code fixed} list of a tab object, created when missing. */
    private static JsonArray fixedOf(JsonObject tab) {
        if (!(tab.get("fixed") instanceof JsonArray fixed)) {
            JsonArray created = new JsonArray();
            tab.add("fixed", created);
            return created;
        }
        return fixed;
    }

    /** A version 3 random items section moved into its tab as {@code rotatingItems}. */
    private static void moveGoods(String fileName, JsonObject root, String key, String defaultTab,
                                  Map<String, JsonObject> tabs, String firstTab) {
        if (!(root.get(key) instanceof JsonObject goods)) {
            return;
        }
        String tabId = string(goods.remove("tab"), defaultTab);
        JsonObject tab = tabs.getOrDefault(tabId, tabs.get(firstTab));
        if (tab.has("rotatingItems")) {
            // Both draws aimed at one tab: a tab holds one draw of each kind.
            CobblemonSafari.LOGGER.warn("{}: {} and another random items section share the tab '{}'; {} dropped",
                    fileName, key, tabId, key);
            return;
        }
        tab.add("rotatingItems", goods);
    }

    /** A version 3 {@code rotatingPokemon} in the version 4 shape. */
    private static JsonObject convertRotatingPokemon(JsonObject old) {
        SafariShopConfig.RotatingPokemon converted = new SafariShopConfig.RotatingPokemon();
        if (old.has("enabled")) {
            converted.enabled = old.get("enabled").getAsBoolean();
        }
        if (old.has("count")) {
            converted.count = old.get("count").getAsInt();
        }
        Set<String> allowed = new HashSet<>();
        if (old.get("rarities") instanceof JsonArray rarities) {
            rarities.forEach(value -> allowed.add(value.getAsString()));
        } else {
            allowed.addAll(List.of("common", "uncommon", "rare"));
        }
        SafariShopConfig.Rarities r = converted.rarities;
        r.common.allowed = allowed.contains("common");
        r.uncommon.allowed = allowed.contains("uncommon");
        r.rare.allowed = allowed.contains("rare");
        r.ultraRare.allowed = allowed.contains("ultra-rare");
        r.common.price = intOr(old, "commonPrice", r.common.price);
        r.uncommon.price = intOr(old, "uncommonPrice", r.uncommon.price);
        r.rare.price = intOr(old, "rarePrice", r.rare.price);
        r.ultraRare.price = intOr(old, "ultraRarePrice", r.ultraRare.price);
        // Version 3 had one switch for every legendary-class category.
        boolean specials = old.has("allowSpecialSpecies") && old.get("allowSpecialSpecies").getAsBoolean();
        converted.categories.legendary.allowed = specials;
        converted.categories.mythical.allowed = specials;
        converted.categories.ultraBeast.allowed = specials;
        // Not paradox: they are regular finds, on by default as in spawns,
        // whatever version 3 said for the legends.
        if (old.has("excludeSpecials")) {
            converted.excludeFeatured = old.get("excludeSpecials").getAsBoolean();
        }
        if (old.get("excludedSpecies") instanceof JsonArray excluded) {
            excluded.forEach(value -> converted.excludeList.add(value.getAsString()));
        }
        return GSON.toJsonTree(converted).getAsJsonObject();
    }

    /** The version 3 tab an entry without one went to, from its type. */
    private static String suggestedTab(String type, String item) {
        if ("pokemon".equals(type)) {
            return SafariShopConfig.Tab.POKEMON;
        }
        if (SafariShopConfig.TICKET_ITEM.equals(item)) {
            return SafariShopConfig.Tab.TICKETS;
        }
        ResourceLocation id = ResourceLocation.tryParse(item);
        return id != null && id.getPath().endsWith(BALL_SUFFIX) ? SafariShopConfig.Tab.BALLS : SafariShopConfig.Tab.ITEMS;
    }

    private static String string(JsonElement element, String fallback) {
        return element == null || element.isJsonNull() ? fallback : element.getAsString();
    }

    private static int intOr(JsonObject object, String key, int fallback) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsInt() : fallback;
    }

    // ---------------------------------------------------------------- validation

    /**
     * Removes offers that could never be sold and clamps the rest.
     *
     * <p>Item ids are only checked for syntax here: the config loads during
     * mod init, before other mods have registered their items. An id that
     * still names nothing when the shop opens is shown as unavailable
     * rather than deleted from the file.
     *
     * @return true if the file must be rewritten
     */
    private static boolean validate(SafariShopConfig c) {
        boolean corrected = false;
        if (c.configVersion != CURRENT_VERSION) {
            // The rewrite also drops keys Gson ignored, so the file shows what is read.
            c.configVersion = CURRENT_VERSION;
            corrected = true;
        }
        if (c.defaults == null) {
            c.defaults = new SafariShopConfig.Defaults();
            corrected = true;
        }
        if (c.defaults.limit != null) {
            corrected |= validateLimit(c.defaults.limit);
        }
        if (c.tabs == null || c.tabs.isEmpty()) {
            // Without tabs nothing could be shown: the default shop comes back.
            c.tabs = SafariShopConfig.defaultTabs();
            corrected = true;
        }
        corrected |= validateTabs(c);
        for (SafariShopConfig.Tab tab : c.tabs) {
            corrected |= validateShelf(tab, "tab '" + tab.id + "'");
            if (tab.sections == null) {
                continue;
            }
            Set<String> names = new HashSet<>();
            int before = tab.sections.size();
            tab.sections.removeIf(section -> section == null);
            corrected |= tab.sections.size() != before;
            for (SafariShopConfig.Section section : tab.sections) {
                if (section.name == null || section.name.isBlank() || !names.add(section.name)) {
                    // A section's name tells its pages apart: blank or twice, they would merge.
                    String fixedName = "section " + (names.size() + 1);
                    CobblemonSafari.LOGGER.warn("Shop tab '{}' has a section with no name or a repeated one; named '{}'",
                            tab.id, fixedName);
                    section.name = fixedName;
                    names.add(fixedName);
                    corrected = true;
                }
                corrected |= validateShelf(section, "section '" + section.name + "' of tab '" + tab.id + "'");
            }
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
            kept = SafariShopConfig.defaultTabs();
            corrected = true;
        }
        // More tabs than the client reads would disconnect every player opening the shop.
        if (kept.size() > SafariShopPayload.MAX_TABS) {
            CobblemonSafari.LOGGER.warn("The shop has {} tabs; only the first {} are kept", kept.size(),
                    SafariShopPayload.MAX_TABS);
            kept = new ArrayList<>(kept.subList(0, SafariShopPayload.MAX_TABS));
            corrected = true;
        }
        c.tabs = kept;
        return corrected;
    }

    /** Fixes one tab or section: its fixed offers and its draws. */
    private static boolean validateShelf(SafariShopConfig.Shelf shelf, String where) {
        boolean corrected = false;
        if (shelf.fixed == null) {
            shelf.fixed = new ArrayList<>();
            corrected = true;
        }
        List<SafariShopConfig.Entry> kept = new ArrayList<>();
        for (SafariShopConfig.Entry entry : shelf.fixed) {
            if (entry == null) {
                corrected = true;
                continue;
            }
            Boolean result = validateEntry(entry, where);
            if (result == null) {
                corrected = true;
                continue;
            }
            corrected |= result;
            kept.add(entry);
        }
        shelf.fixed = kept;
        if (shelf.rotatingPokemon != null) {
            corrected |= validateRotatingPokemon(shelf.rotatingPokemon, where);
        }
        if (shelf.rotatingItems != null) {
            corrected |= validateGoods(shelf.rotatingItems, where);
        }
        if (shelf.rotatingSnacks != null) {
            corrected |= validateSnacks(shelf.rotatingSnacks, where);
        }
        return corrected;
    }

    /**
     * Fixes one fixed offer.
     *
     * @return null when the offer must be removed, otherwise whether it changed
     */
    private static Boolean validateEntry(SafariShopConfig.Entry entry, String where) {
        boolean corrected = false;
        int kinds = (entry.item != null ? 1 : 0) + (entry.pokemon != null ? 1 : 0) + (entry.snack != null ? 1 : 0);
        if (kinds != 1) {
            CobblemonSafari.LOGGER.warn("Removing a shop offer in {} that sells {} things at once (item, pokemon, snack)",
                    where, kinds);
            return null;
        }
        if (entry.price < 0 && !entry.hasAutoPrice()) {
            CobblemonSafari.LOGGER.warn("Shop offer in {} with a negative price set to 0", where);
            entry.price = 0;
            corrected = true;
        }
        if (entry.hasAutoPrice() && !entry.isPokemon()) {
            // Only a Pokémon has a rarity and a stage to price from; a free
            // item would be worse than a missing one.
            CobblemonSafari.LOGGER.warn("Removing a shop offer in {}: \"auto\" prices Pokémon only", where);
            return null;
        }
        if (entry.isPokemon()) {
            if (entry.pokemon.isBlank()) {
                CobblemonSafari.LOGGER.warn("Removing a shop Pokémon offer in {} with no properties", where);
                return null;
            }
            if (entry.count != null) {
                entry.count = null;
                corrected = true;
            }
        } else {
            if (entry.item != null && ResourceLocation.tryParse(entry.item) == null) {
                CobblemonSafari.LOGGER.warn("Removing a shop offer in {} with an invalid item id '{}'", where, entry.item);
                return null;
            }
            if (entry.snack != null) {
                Boolean snack = validateSnack(entry.snack, where);
                if (snack == null) {
                    return null;
                }
                corrected |= snack;
            }
            if (entry.count != null && (entry.count < 1 || entry.count > MAX_COUNT)) {
                entry.count = Math.max(1, Math.min(MAX_COUNT, entry.count));
                corrected = true;
            }
        }
        corrected |= validateCost(entry, where);
        if (entry.limit != null) {
            corrected |= validateLimit(entry.limit);
        }
        return corrected;
    }

    /**
     * Fixes a dish: a valid base, at most three valid seasonings.
     *
     * @return null when the dish cannot be made, otherwise whether it changed
     */
    private static Boolean validateSnack(SafariShopConfig.Snack snack, String where) {
        if (snack.base == null || ResourceLocation.tryParse(snack.base) == null) {
            CobblemonSafari.LOGGER.warn("Removing a dish in {} with an invalid base '{}'", where, snack.base);
            return null;
        }
        boolean corrected = false;
        if (snack.seasonings == null) {
            snack.seasonings = new ArrayList<>();
            corrected = true;
        }
        if (snack.seasonings.removeIf(id -> id == null || ResourceLocation.tryParse(id) == null)) {
            CobblemonSafari.LOGGER.warn("Removed invalid seasoning ids from a dish in {}", where);
            corrected = true;
        }
        if (snack.seasonings.size() > MAX_SEASONINGS) {
            snack.seasonings = new ArrayList<>(snack.seasonings.subList(0, MAX_SEASONINGS));
            CobblemonSafari.LOGGER.warn("A dish in {} had more than {} seasonings; the rest dropped", where, MAX_SEASONINGS);
            corrected = true;
        }
        return corrected;
    }

    /** Drops invalid costs and clamps counts; an empty list is removed. */
    private static boolean validateCost(SafariShopConfig.Entry entry, String where) {
        if (entry.cost == null) {
            return false;
        }
        boolean corrected = false;
        int before = entry.cost.size();
        entry.cost.removeIf(cost -> cost == null || cost.id() == null);
        if (entry.cost.size() != before) {
            CobblemonSafari.LOGGER.warn("Removed invalid costs from a shop offer in {}", where);
            corrected = true;
        }
        // More costs than the client reads would disconnect every player opening the shop.
        if (entry.cost.size() > SafariShopPayload.MAX_COSTS) {
            CobblemonSafari.LOGGER.warn("A shop offer in {} has {} costs; only the first {} are kept", where,
                    entry.cost.size(), SafariShopPayload.MAX_COSTS);
            entry.cost = new ArrayList<>(entry.cost.subList(0, SafariShopPayload.MAX_COSTS));
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
        return corrected;
    }

    /** Clamps a limit: no negative count, a wait between 1 hour and a year or none. */
    private static boolean validateLimit(SafariShopConfig.Limit limit) {
        boolean corrected = false;
        if (limit.max != null && limit.max < 0) {
            limit.max = 0;
            corrected = true;
        }
        if (limit.cooldownHours != null) {
            // 0 or less means no wait: dropped so the file shows none.
            Integer hours = limit.cooldownHours <= 0 ? null : Math.min(MAX_COOLDOWN_HOURS, limit.cooldownHours);
            if (!Objects.equals(hours, limit.cooldownHours)) {
                limit.cooldownHours = hours;
                corrected = true;
            }
        }
        return corrected;
    }

    /** Fixes a Pokémon draw: counts, prices, weights, levels and lists. */
    private static boolean validateRotatingPokemon(SafariShopConfig.RotatingPokemon r, String where) {
        boolean corrected = false;
        int count = Math.max(0, Math.min(MAX_ROTATING, r.count));
        if (count != r.count) {
            r.count = count;
            corrected = true;
        }
        if (r.rarities == null) {
            r.rarities = new SafariShopConfig.Rarities();
            corrected = true;
        }
        SafariShopConfig.Rarities defaults = new SafariShopConfig.Rarities();
        if (r.rarities.common == null) {
            r.rarities.common = defaults.common;
            corrected = true;
        }
        if (r.rarities.uncommon == null) {
            r.rarities.uncommon = defaults.uncommon;
            corrected = true;
        }
        if (r.rarities.rare == null) {
            r.rarities.rare = defaults.rare;
            corrected = true;
        }
        if (r.rarities.ultraRare == null) {
            r.rarities.ultraRare = defaults.ultraRare;
            corrected = true;
        }
        for (SafariShopConfig.RarityRule rule : List.of(r.rarities.common, r.rarities.uncommon, r.rarities.rare,
                r.rarities.ultraRare)) {
            if (rule.price < 0 || rule.weight < 0) {
                rule.price = Math.max(0, rule.price);
                rule.weight = Math.max(0, rule.weight);
                corrected = true;
            }
        }
        if (r.categories == null) {
            r.categories = new SafariShopConfig.Categories();
            corrected = true;
        }
        SafariShopConfig.Categories categoryDefaults = new SafariShopConfig.Categories();
        if (r.categories.legendary == null) {
            r.categories.legendary = categoryDefaults.legendary;
            corrected = true;
        }
        if (r.categories.mythical == null) {
            r.categories.mythical = categoryDefaults.mythical;
            corrected = true;
        }
        if (r.categories.ultraBeast == null) {
            r.categories.ultraBeast = categoryDefaults.ultraBeast;
            corrected = true;
        }
        if (r.categories.paradox == null) {
            r.categories.paradox = categoryDefaults.paradox;
            corrected = true;
        }
        for (SafariShopConfig.CategoryRule rule : List.of(r.categories.legendary, r.categories.mythical,
                r.categories.ultraBeast, r.categories.paradox)) {
            int max = Math.max(0, Math.min(MAX_PER_CATEGORY, rule.maxPerRotation));
            if (rule.price < 0 || max != rule.maxPerRotation) {
                rule.price = Math.max(0, rule.price);
                rule.maxPerRotation = max;
                corrected = true;
            }
            if (rule.limit != null) {
                corrected |= validateLimit(rule.limit);
            }
        }
        if (r.level == null) {
            r.level = new SafariShopConfig.Level();
            corrected = true;
        }
        if (!SafariShopConfig.Level.SPAWN.equals(r.level.mode) && !SafariShopConfig.Level.RANGE.equals(r.level.mode)) {
            CobblemonSafari.LOGGER.warn("Unknown level mode '{}' in {}; using '{}'", r.level.mode, where,
                    SafariShopConfig.Level.SPAWN);
            r.level.mode = SafariShopConfig.Level.SPAWN;
            corrected = true;
        }
        int min = Math.max(1, Math.min(MAX_LEVEL, r.level.min));
        int max = Math.max(min, Math.min(MAX_LEVEL, r.level.max));
        if (min != r.level.min || max != r.level.max) {
            r.level.min = min;
            r.level.max = max;
            corrected = true;
        }
        if (r.modifiers == null) {
            r.modifiers = new SafariShopConfig.Modifiers();
            corrected = true;
        }
        double chance = Math.max(0.0, Math.min(1.0, r.modifiers.shinyChance));
        double multiplier = Math.max(0.0, r.modifiers.shinyPriceMultiplier);
        if (chance != r.modifiers.shinyChance || multiplier != r.modifiers.shinyPriceMultiplier) {
            r.modifiers.shinyChance = chance;
            r.modifiers.shinyPriceMultiplier = multiplier;
            corrected = true;
        }
        if (r.excludeList == null) {
            r.excludeList = new ArrayList<>();
            corrected = true;
        }
        if (r.limit != null) {
            corrected |= validateLimit(r.limit);
        }
        return corrected;
    }

    /**
     * Fixes an items draw: count in range, missing lists, malformed ids,
     * price rules with a bad id or out-of-range values. Ids are only checked
     * for syntax: the config loads before other mods register their items.
     */
    private static boolean validateGoods(SafariShopConfig.RotatingGoods g, String where) {
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
            CobblemonSafari.LOGGER.warn("Removed invalid item ids from rotatingItems.always in {}", where);
            corrected = true;
        }
        if (g.excluded.removeIf(id -> id == null || ResourceLocation.tryParse(
                id.startsWith(SafariShopConfig.Cost.TAG_PREFIX) ? id.substring(1) : id) == null)) {
            CobblemonSafari.LOGGER.warn("Removed invalid ids from rotatingItems.excluded in {}", where);
            corrected = true;
        }
        if (g.prices.removeIf(rule -> rule == null || rule.id() == null)) {
            CobblemonSafari.LOGGER.warn("Removed price rules with an invalid item from rotatingItems.prices in {}", where);
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
        if (g.limit != null) {
            corrected |= validateLimit(g.limit);
        }
        return corrected;
    }

    /** Fixes a dishes draw: count, base, seasoning bounds, prices. */
    private static boolean validateSnacks(SafariShopConfig.RotatingSnacks s, String where) {
        boolean corrected = false;
        int count = Math.max(0, Math.min(MAX_ROTATING, s.count));
        if (count != s.count) {
            s.count = count;
            corrected = true;
        }
        if (s.base == null || ResourceLocation.tryParse(s.base) == null) {
            CobblemonSafari.LOGGER.warn("Invalid rotatingSnacks.base '{}' in {}; using a Poké Snack", s.base, where);
            s.base = SafariShopConfig.POKE_SNACK;
            corrected = true;
        }
        if (s.seasonings == null) {
            s.seasonings = new SafariShopConfig.SeasoningDraw();
            corrected = true;
        }
        if (s.seasonings.pool == null) {
            s.seasonings.pool = new SafariShopConfig.SeasoningDraw().pool;
            corrected = true;
        }
        int min = Math.max(0, Math.min(MAX_SEASONINGS, s.seasonings.min));
        int max = Math.max(min, Math.min(MAX_SEASONINGS, s.seasonings.max));
        if (min != s.seasonings.min || max != s.seasonings.max) {
            s.seasonings.min = min;
            s.seasonings.max = max;
            corrected = true;
        }
        if (s.price == null) {
            s.price = new SafariShopConfig.SnackPrice();
            corrected = true;
        }
        if (s.price.base < 0 || s.price.perSeasoning < 0) {
            s.price.base = Math.max(0, s.price.base);
            s.price.perSeasoning = Math.max(0, s.price.perSeasoning);
            corrected = true;
        }
        if (s.limit != null) {
            corrected |= validateLimit(s.limit);
        }
        return corrected;
    }

    // ---------------------------------------------------------------- lookups

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

    /** Whether an id names an item that exists right now. */
    public static boolean itemExists(String id) {
        ResourceLocation location = id == null ? null : ResourceLocation.tryParse(id);
        return location != null && BuiltInRegistries.ITEM.containsKey(location);
    }
}
