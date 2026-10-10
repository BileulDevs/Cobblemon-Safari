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

    /** Bumped when a field changes meaning, so old files can be migrated. Version 1: first version. */
    public int configVersion = SafariShopConfigManager.CURRENT_VERSION;

    /** Master switch. When false the warden's "Shop" answer says the shop is closed. */
    public boolean enabled = true;

    /**
     * What is for sale, in display order (45 per page). Defaults recall the
     * Game Corner prize counters of the original games.
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
            Entry.item("cobblemon:poke_ball", 10, 20)
    ));

    /** One thing for sale. */
    public static class Entry {
        /** {@code "item"} or {@code "pokemon"}. */
        public String type = TYPE_ITEM;

        /** Item id, for {@code "item"} entries, e.g. {@code cobblemon:rare_candy}. */
        public String item = "";

        /** Stack size given per purchase, for {@code "item"} entries. */
        public int count = 1;

        /**
         * Cobblemon properties, for {@code "pokemon"} entries, e.g.
         * {@code "dratini level=15"} or {@code "eevee shiny=yes"}.
         */
        public String pokemon = "";

        /** Cost in Safari points. */
        public int price = 0;

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
