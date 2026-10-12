package dev.darcosse.common.cobblemonsafari.config;

import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The Safari Warden's shop, serialised as {@code config/cobblemon_safari/shop.json}.
 *
 * <p>Kept out of the main config because a shop list grows long. Plain data
 * only: {@link SafariShopConfigManager} validates it. Field names are the
 * JSON keys.
 */
public class SafariShopConfig {

    /**
     * Bumped when a field changes meaning, so old files can be migrated.
     * Version 1: first version. Version 2: added tabs and Entry.tab.
     * Version 3: added the rotating section (no existing value changes).
     * Version 4: the default Pokémon list became a single Tauros; files still
     * holding the five old default Pokémon get it too.
     * Version 5: added Entry.cost and Entry.limitPerRotation, the tickets tab
     * and the default ticket trades (added to existing files once).
     * Version 6: costs accept item tags; the version 5 default trades (red
     * apricorns, oran berries, a fire stone) become any apricorn, berry and
     * evolution stone.
     * Version 7: the default Tauros costs 200 points instead of 1500.
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
            Tab.of(Tab.BALLS, "cobblemon:great_ball"),
            Tab.of(Tab.TICKETS, TICKET_ITEM)
    ));

    /**
     * The one Pokémon sold by default, a nod to the Safari Zone's Tauros, and
     * its price. The other Pokémon come from {@link #rotating}.
     */
    static final String DEFAULT_POKEMON = "tauros level=45";
    static final int DEFAULT_POKEMON_PRICE = 200;

    /** Its price in versions 4 to 6; a file still asking that much gets the new one. */
    static final int OLD_DEFAULT_POKEMON_PRICE = 1500;

    /**
     * What is for sale, in display order within each tab (8 per page). By
     * default a single fixed Pokémon and the usual items; the rotating
     * Pokémon are added after these.
     */
    public List<Entry> entries = new ArrayList<>(List.of(
            Entry.pokemon(DEFAULT_POKEMON, DEFAULT_POKEMON_PRICE),
            Entry.item("cobblemon:rare_candy", 1, 60),
            Entry.item("cobblemon:exp_candy_m", 3, 40),
            Entry.item("cobblemon:ability_capsule", 1, 400),
            Entry.item("cobblemon:poke_ball", 10, 20),
            Entry.item("cobblemon:great_ball", 5, 40),
            Entry.item("cobblemon:ultra_ball", 5, 80),
            Entry.ticketTrade(TRADE_APRICORN_TAG, TRADE_APRICORNS),
            Entry.ticketTrade(TRADE_BERRY_TAG, TRADE_BERRIES),
            Entry.ticketTrade(TRADE_STONE_TAG, TRADE_STONES)
    ));

    /** The Safari Ticket's item id, what ticket trades give. */
    public static final String TICKET_ITEM = "cobblemon_safari:safari_ticket";

    /**
     * Default ticket trades: overworld goods the warden takes for one
     * ticket, each once per player and rotation (TRADE_LIMIT), any kind
     * of each (Cobblemon's item tags). Apricorns and berries are farmed,
     * evolution stones are mined: three ways in for three play styles. With 3 trades and a 4 h rotation, trading alone gives up
     * to 1 h 30 of Safari time per rotation; raise the counts or lower the
     * limit to make tickets scarcer.
     */
    static final int TRADE_APRICORNS = 16;
    static final int TRADE_BERRIES = 24;
    static final int TRADE_STONES = 1;
    static final int TRADE_LIMIT = 1;
    static final String TRADE_APRICORN_TAG = "#cobblemon:apricorns";
    static final String TRADE_BERRY_TAG = "#cobblemon:berries";
    static final String TRADE_STONE_TAG = "#cobblemon:evolution_stones";

    /**
     * Pokémon drawn at random for each rotation of the Safari specials, sold
     * after the fixed {@link #entries}. Every player sees the same ones; they
     * change with the specials ({@code featured.rotationMinutes} in the main
     * config).
     */
    public Rotating rotating = new Rotating();

    /** Settings of the random Pokémon of the shop. */
    public static class Rotating {
        /** Master switch; off, the shop only sells {@link SafariShopConfig#entries}. */
        public boolean enabled = true;

        /** How many Pokémon are drawn per rotation. */
        public int count = 4;

        /** Tab showing them; an unknown id falls back to {@code pokemon}, then to the first tab. */
        public String tab = Tab.POKEMON;

        /**
         * Rarities they are drawn from, as Cobblemon spawn buckets:
         * {@code common}, {@code uncommon}, {@code rare}, {@code ultra-rare}.
         * Only species that spawn naturally in the Safari are drawn, with
         * their rarity there.
         */
        public List<String> rarities = new ArrayList<>(List.of("common", "uncommon", "rare"));

        /** Price per rarity, in Safari points. */
        public int commonPrice = 250;
        public int uncommonPrice = 500;
        public int rarePrice = 1000;
        public int ultraRarePrice = 2500;

        /** Level of the Pokémon sold. */
        public int level = 15;

        /**
         * Leaves out the specials of the current rotation, so the shop never
         * sells what players are hunting on the Safari Board.
         */
        public boolean excludeSpecials = true;

        /** Allows legendary, mythical, ultra beast and paradox species. */
        public boolean allowSpecialSpecies = false;

        /** Species never drawn, as Cobblemon ids, e.g. {@code cobblemon:ditto}. */
        public List<String> excludedSpecies = new ArrayList<>();
    }

    /** Items an entry takes in exchange. */
    public static class Cost {
        /**
         * Item id, e.g. {@code cobblemon:red_apricorn}, or an item tag
         * prefixed with {@code #}, e.g. {@code #cobblemon:apricorns}: any
         * mix of the tag's items then counts toward {@link #count}.
         */
        public String item = "";
        /** How many of it. */
        public int count = 1;

        /** Prefix marking {@link #item} as an item tag. */
        public static final String TAG_PREFIX = "#";

        /** Gson needs a no-argument constructor. */
        public Cost() {
        }

        /** Whether {@link #item} names an item tag. */
        public boolean isTag() {
            return item != null && item.startsWith(TAG_PREFIX);
        }

        /** The id of the item or tag, without the tag prefix; null when malformed. */
        @Nullable
        public ResourceLocation id() {
            if (item == null) {
                return null;
            }
            return ResourceLocation.tryParse(isTag() ? item.substring(TAG_PREFIX.length()) : item);
        }

        static Cost of(String item, int count) {
            Cost cost = new Cost();
            cost.item = item;
            cost.count = count;
            return cost;
        }
    }

    /** One tab of the shop screen. */
    public static class Tab {
        /** Built-in tab ids, with translated names. */
        public static final String POKEMON = "pokemon";
        public static final String ITEMS = "items";
        public static final String BALLS = "balls";
        public static final String TICKETS = "tickets";

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
         * Items taken in exchange, on top of {@link #price} (which a trade
         * usually leaves at 0); null or empty for a plain points purchase.
         * Any stack of the item counts, whatever its name or data.
         */
        public List<Cost> cost;

        /**
         * How many times each player may buy this entry per rotation of the
         * specials; null for no limit. Keeps a farm from turning into an
         * endless supply of what it buys.
         */
        public Integer limitPerRotation;

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

        /** A trade of overworld goods for one Safari Ticket, limited per rotation. */
        static Entry ticketTrade(String item, int count) {
            Entry entry = item(TICKET_ITEM, 1, 0);
            entry.cost = new ArrayList<>(List.of(Cost.of(item, count)));
            entry.limitPerRotation = TRADE_LIMIT;
            return entry;
        }

        /** Whether this entry asks for items. */
        public boolean hasCost() {
            return cost != null && !cost.isEmpty();
        }

        /**
         * A random Pokémon of the current rotation (see {@link Rotating}).
         * Built in memory only, never written to the file.
         */
        public static Entry rotating(String properties, int price, String tab) {
            Entry entry = pokemon(properties, price);
            entry.tab = tab;
            return entry;
        }

        /** Whether this entry sells a Pokémon. */
        public boolean isPokemon() {
            return TYPE_POKEMON.equals(type);
        }
    }
}
