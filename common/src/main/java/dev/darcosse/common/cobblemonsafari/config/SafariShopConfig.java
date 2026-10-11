package dev.darcosse.common.cobblemonsafari.config;

import java.util.ArrayList;
import java.util.List;

/**
 * The Safari Warden's shop, serialised as {@code config/cobblemon_safari_shop.json}.
 *
 * <p>Kept out of the main config because a shop list grows long. Plain data
 * only: {@link SafariShopConfigManager} validates it. Field names are the
 * JSON keys.
 */
public class SafariShopConfig {

    /**
     * Bumped when a field changes meaning, so old files can be migrated.
     * Version 1: first version. Version 2: added tabs and Entry.tab.
     */
    public int configVersion = SafariShopConfigManager.CURRENT_VERSION;

    /** Master switch. When false the warden's "Shop" answer says the shop is closed. */
    public boolean enabled = true;

    /**
     * Tabs of the shop screen, in display order. Each entry names its tab
     * with {@link Entry#tab}. A tab with no entry is not shown.
     */
    public List<Tab> tabs = new ArrayList<>(List.of(
            Tab.of(Tab.POKEMON, "cobblemon:poke_ball"),
            Tab.of(Tab.ITEMS, "cobblemon:rare_candy"),
            Tab.of(Tab.BALLS, "cobblemon:great_ball")
    ));

    /**
     * What is for sale, in display order within each tab (8 per page).
     * Defaults recall the Game Corner prize counters of the original games.
     */
    public List<Entry> entries = new ArrayList<>(List.of(
            Entry.pokemon("abra level=10", 150),
            Entry.pokemon("clefairy level=12", 300),
            Entry.pokemon("porygon level=20", 1500),
            Entry.pokemon("dratini level=15", 800),
            Entry.pokemon("scyther level=20", 1200),
            Entry.item("cobblemon:rare_candy", 1, 60),
            Entry.item("cobblemon:exp_candy_m", 3, 40),
            Entry.item("cobblemon:ability_capsule", 1, 400),
            Entry.item("cobblemon:poke_ball", 10, 20),
            Entry.item("cobblemon:great_ball", 5, 40),
            Entry.item("cobblemon:ultra_ball", 5, 80)
    ));

    /** One tab of the shop screen. */
    public static class Tab {
        /** Built-in tab ids, with translated names. */
        public static final String POKEMON = "pokemon";
        public static final String ITEMS = "items";
        public static final String BALLS = "balls";

        /**
         * Id entries refer to. For a built-in id the name comes from the
         * translation key {@code cobblemon_safari.shop.tab.<id>}.
         */
        public String id = "";

        /** Name shown instead of the translated one; empty to use the translation (or the id). */
        public String name = "";

        /** Item id drawn on the tab, e.g. {@code cobblemon:poke_ball}; empty or unknown for none. */
        public String icon = "";

        /** Gson needs a no-argument constructor. */
        public Tab() {
        }

        /** A tab with a translated name. */
        static Tab of(String id, String icon) {
            Tab tab = new Tab();
            tab.id = id;
            tab.icon = icon;
            return tab;
        }
    }

    /**
     * One thing for sale.
     *
     * <p>Fields of the other type stay null and Gson, which skips nulls,
     * leaves them out of the file: an item entry is written without
     * {@code pokemon}, a Pokémon entry without {@code item} and {@code count}.
     * The config manager clears them when it finds them filled.
     */
    public static class Entry {
        /** {@code "item"} or {@code "pokemon"}. */
        public String type = TYPE_ITEM;

        /** Item id, for {@code "item"} entries, e.g. {@code cobblemon:rare_candy}; null otherwise. */
        public String item;

        /**
         * Stack size given per purchase, for {@code "item"} entries; null
         * otherwise. Boxed so that it can be left out of Pokémon entries; the
         * manager sets it to 1 on item entries that omit it.
         */
        public Integer count;

        /**
         * Cobblemon properties, for {@code "pokemon"} entries, e.g.
         * {@code "dratini level=15"} or {@code "eevee shiny=yes"}; null otherwise.
         */
        public String pokemon;

        /** Cost in Safari points. */
        public int price = 0;

        /**
         * Id of the tab showing this entry (see {@link SafariShopConfig#tabs}).
         * Empty picks one from the type: {@code pokemon} for Pokémon,
         * {@code balls} for items whose id ends in {@code _ball}, {@code items}
         * otherwise. Filled in by the config manager.
         */
        public String tab = "";

        /** Value of {@link #type} for an item. */
        public static final String TYPE_ITEM = "item";
        /** Value of {@link #type} for a Pokémon. */
        public static final String TYPE_POKEMON = "pokemon";

        /** Gson needs a no-argument constructor. */
        public Entry() {
        }

        /** An item entry. */
        static Entry item(String id, int count, int price) {
            Entry entry = new Entry();
            entry.type = TYPE_ITEM;
            entry.item = id;
            entry.count = count;
            entry.price = price;
            return entry;
        }

        /** A Pokémon entry. */
        static Entry pokemon(String properties, int price) {
            Entry entry = new Entry();
            entry.type = TYPE_POKEMON;
            entry.pokemon = properties;
            entry.price = price;
            return entry;
        }

        /** Whether this entry sells a Pokémon. */
        public boolean isPokemon() {
            return TYPE_POKEMON.equals(type);
        }
    }
}
