package dev.darcosse.common.cobblemonsafari.config;

import com.google.gson.annotations.JsonAdapter;
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
     * Version 8: rotating.level removed; the random Pokémon come at their
     * natural Safari level and their price grows with their evolution stage.
     * Version 9: rotating.rarePrice 1000 to 500; a file still on 1000 follows.
     * Version 10: rotating.count 4 to 15 (a file still on 4 follows); added
     * rotatingItems and rotatingBalls.
     * Version 11: added the cooking tab and its default ingredients (added to
     * existing files once).
     * Version 12: added Entry.cooldownHours and the free daily Safari Balls
     * (added to existing files once), which replace the ball loan.
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
            Tab.of(Tab.COOKING, COOKING_ICON),
            Tab.of(Tab.TICKETS, TICKET_ITEM)
    ));

    /**
     * The one Pokémon sold by default, a nod to the Safari Zone's Tauros, and
     * its price. The other Pokémon come from {@link #rotating}.
     */
    static final String DEFAULT_POKEMON = "tauros level=45";
    static final int DEFAULT_POKEMON_PRICE = 200;

    /**
     * Base price of a rare random Pokémon, and the one before version 9. At
     * 1000, twice the uncommon price, rarity outweighed strength: a second
     * stage Kirlia cost four times a Centiskorch.
     */
    static final int RARE_PRICE = 500;
    static final int OLD_RARE_PRICE = 1000;

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
            Entry.freeBalls(),
            Entry.item("cobblemon:poke_ball", 10, 20),
            Entry.item("cobblemon:great_ball", 5, 40),
            Entry.item("cobblemon:ultra_ball", 5, 80),
            Entry.cooking("cobblemon:hearty_grains", 8, 40),
            Entry.cooking("cobblemon:vivichoke", 2, 60),
            Entry.cooking("cobblemon:medicinal_leek", 4, 40),
            Entry.cooking("cobblemon:tasty_tail", 1, 80),
            Entry.cooking("cobblemon:galarica_nuts", 4, 60),
            Entry.cooking("cobblemon:pep_up_flower", 4, 40),
            Entry.cooking("cobblemon:energy_root", 2, 60),
            Entry.cooking("minecraft:honey_bottle", 4, 30),
            Entry.cooking("minecraft:sugar", 16, 20),
            Entry.cooking("minecraft:milk_bucket", 1, 20),
            Entry.ticketTrade(TRADE_APRICORN_TAG, TRADE_APRICORNS),
            Entry.ticketTrade(TRADE_BERRY_TAG, TRADE_BERRIES),
            Entry.ticketTrade(TRADE_STONE_TAG, TRADE_STONES)
    ));

    /**
     * Icon of the cooking tab: the Poké Pot the rest houses cook in, the
     * same item RestHouseBuilder puts on their campfire.
     */
    static final String COOKING_ICON = "cobblemon:campfire_pot_red";

    /**
     * The free Safari Balls: what the loan used to lend, now the player's to
     * keep, once every FREE_BALLS_COOLDOWN_HOURS hours (real time). A day
     * keeps them a daily help rather than a supply; fewer hours or more balls
     * make Poké Balls bought in the shop pointless.
     */
    static final String FREE_BALLS_ITEM = "cobblemon:safari_ball";
    static final int FREE_BALLS_COUNT = 30;
    static final int FREE_BALLS_COOLDOWN_HOURS = 24;

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

    /**
     * Cobblemon items drawn at random for each rotation, sold in the items
     * tab after the fixed entries, priced by kind (stones, potions, candies...).
     */
    public RotatingGoods rotatingItems = RotatingGoods.items();

    /** Poké Balls drawn at random for each rotation, sold in the balls tab; Safari Balls always. */
    public RotatingGoods rotatingBalls = RotatingGoods.balls();

    /** Default number of random Pokémon per rotation, and the one before version 10. */
    static final int ROTATING_POKEMON = 15;
    static final int OLD_ROTATING_POKEMON = 4;

    /** Settings of the random Pokémon of the shop. */
    public static class Rotating {
        /** Master switch; off, the shop only sells {@link SafariShopConfig#entries}. */
        public boolean enabled = true;

        /** How many Pokémon are drawn per rotation. */
        public int count = ROTATING_POKEMON;

        /** Tab showing them; an unknown id falls back to {@code pokemon}, then to the first tab. */
        public String tab = Tab.POKEMON;

        /**
         * Rarities they are drawn from, as Cobblemon spawn buckets:
         * {@code common}, {@code uncommon}, {@code rare}, {@code ultra-rare}.
         * Only species that spawn naturally in the Safari are drawn, with
         * their rarity there.
         */
        public List<String> rarities = new ArrayList<>(List.of("common", "uncommon", "rare"));

        /**
         * Base price per rarity, in Safari points: the price of a first-stage
         * Pokémon. Later stages cost more (RotatingShop.STAGE_MULTIPLIERS):
         * a Pokémon's spawn rarity says how often it appears, not how strong
         * it is, and Cobblemon files many final evolutions as common.
         */
        public int commonPrice = 250;
        public int uncommonPrice = 500;
        public int rarePrice = RARE_PRICE;
        public int ultraRarePrice = 2500;

        /**
         * Leaves out the specials of the current rotation, so the shop never
         * sells what players are hunting today.
         */
        public boolean excludeSpecials = true;

        /** Allows legendary, mythical, ultra beast and paradox species. */
        public boolean allowSpecialSpecies = false;

        /** Species never drawn, as Cobblemon ids, e.g. {@code cobblemon:ditto}. */
        public List<String> excludedSpecies = new ArrayList<>();
    }

    /**
     * Items drawn at random for each rotation of the specials, the same for
     * every player, like the random Pokémon. Only items matched by a
     * {@link #prices} rule can be drawn: the rules are both the catalogue and
     * the price list, so nothing is ever sold without a price chosen for it.
     */
    public static class RotatingGoods {
        /** Master switch. */
        public boolean enabled = true;

        /** How many items are drawn per rotation, on top of {@link #always}. */
        public int count = 8;

        /** Tab showing them; an unknown id falls back to the first tab. */
        public String tab = Tab.ITEMS;

        /**
         * Items sold every rotation whatever the draw, as item ids; each
         * still needs a {@link #prices} rule, which gives its price and stack.
         */
        public List<String> always = new ArrayList<>();

        /**
         * Items never drawn: item ids, or item tags prefixed with {@code #}
         * (e.g. {@code #cobblemon:mints}). Items already sold by the fixed
         * entries are left out too, so the shop never lists one twice.
         */
        public List<String> excluded = new ArrayList<>();

        /**
         * What can be drawn and at what price: each rule names an item id or
         * an item tag ({@code #}), the stack sold and its price in points.
         * The first rule matching an item wins, so specific items go before
         * the tags that hold them (a Max Potion before {@code #cobblemon:potions}).
         */
        public List<PriceRule> prices = new ArrayList<>();

        /** Gson needs a no-argument constructor. */
        public RotatingGoods() {
        }

        /**
         * Default random items: Cobblemon's medicine, candies, mints, battle
         * items, gems, held items and evolution items, priced against about
         * 600 points per hour of hunting (a stone is half an hour, a mint or
         * an XL candy forty minutes, an Ability Capsule over an hour).
         */
        static RotatingGoods items() {
            RotatingGoods goods = new RotatingGoods();
            goods.tab = Tab.ITEMS;
            goods.count = 8;
            goods.excluded = new ArrayList<>(List.of(
                    // Changes a hidden ability for good: a prize, not a shop item.
                    "cobblemon:ability_patch",
                    // Blocks that sit in Cobblemon's evolution tag for Sinistcha.
                    "#cobblemon:teapots", "#cobblemon:teacups",
                    // Vanilla items Cobblemon lets Pokémon hold.
                    "minecraft:bone", "minecraft:snowball"));
            goods.prices = new ArrayList<>(List.of(
                    PriceRule.of("cobblemon:full_restore", 1, 200),
                    PriceRule.of("cobblemon:max_potion", 1, 150),
                    PriceRule.of("cobblemon:hyper_potion", 2, 120),
                    PriceRule.of("cobblemon:super_potion", 3, 90),
                    PriceRule.of("#cobblemon:potions", 5, 50),
                    PriceRule.of("cobblemon:max_revive", 1, 300),
                    PriceRule.of("#cobblemon:revives", 1, 150),
                    PriceRule.of("cobblemon:full_heal", 2, 100),
                    PriceRule.of("#cobblemon:restores", 3, 60),
                    PriceRule.of("#cobblemon:remedies", 3, 30),
                    PriceRule.of("cobblemon:max_elixir", 1, 400),
                    PriceRule.of("cobblemon:elixir", 1, 250),
                    PriceRule.of("cobblemon:max_ether", 1, 200),
                    PriceRule.of("#cobblemon:ethers", 1, 100),
                    PriceRule.of("cobblemon:rare_candy", 1, 150),
                    PriceRule.of("cobblemon:exp_candy_xl", 1, 400),
                    PriceRule.of("cobblemon:exp_candy_l", 1, 200),
                    PriceRule.of("cobblemon:exp_candy_m", 2, 150),
                    PriceRule.of("cobblemon:exp_candy_s", 3, 100),
                    PriceRule.of("cobblemon:exp_candy_xs", 5, 75),
                    PriceRule.of("#cobblemon:iv_candies", 1, 300),
                    PriceRule.of("#cobblemon:mochis", 1, 250),
                    PriceRule.of("#cobblemon:mints", 1, 400),
                    PriceRule.of("#cobblemon:ability_changers", 1, 800),
                    PriceRule.of("#cobblemon:battle_items", 3, 100),
                    PriceRule.of("#cobblemon:type_gems", 1, 150),
                    PriceRule.of("#cobblemon:evolution_stones", 1, 300),
                    PriceRule.of("#cobblemon:evolution_items", 1, 500),
                    PriceRule.of("#cobblemon:held/is_held_item", 1, 600)));
            return goods;
        }

        /**
         * Default random balls: Safari Balls every rotation, plus a few balls
         * by Cobblemon's tiers, stacked so a purchase is a useful handful.
         * Safari Balls cost more than Poké Balls: in the Safari they get the
         * out-of-battle capture bonus.
         */
        static RotatingGoods balls() {
            RotatingGoods goods = new RotatingGoods();
            goods.tab = Tab.BALLS;
            goods.count = 6;
            goods.always = new ArrayList<>(List.of("cobblemon:safari_ball"));
            goods.excluded = new ArrayList<>(List.of("#cobblemon:tier_5_poke_balls", "cobblemon:cherish_ball"));
            goods.prices = new ArrayList<>(List.of(
                    PriceRule.of("cobblemon:safari_ball", 10, 150),
                    PriceRule.of("#cobblemon:tier_1_poke_balls", 10, 50),
                    PriceRule.of("#cobblemon:tier_2_poke_balls", 5, 100),
                    PriceRule.of("#cobblemon:tier_3_poke_balls", 5, 200),
                    PriceRule.of("#cobblemon:tier_4_poke_balls", 1, 400)));
            return goods;
        }
    }

    /** One line of a {@link RotatingGoods#prices} list. */
    public static class PriceRule {
        /** Item id, or item tag prefixed with {@code #}. */
        public String item = "";
        /** Stack sold per purchase. */
        public int count = 1;
        /** Price of the stack, in Safari points. */
        public int price = 0;

        /** Gson needs a no-argument constructor. */
        public PriceRule() {
        }

        /** Whether {@link #item} names an item tag. */
        public boolean isTag() {
            return item != null && item.startsWith(Cost.TAG_PREFIX);
        }

        /** The id of the item or tag, without the tag prefix; null when malformed. */
        @Nullable
        public ResourceLocation id() {
            if (item == null) {
                return null;
            }
            return ResourceLocation.tryParse(isTag() ? item.substring(Cost.TAG_PREFIX.length()) : item);
        }

        static PriceRule of(String item, int count, int price) {
            PriceRule rule = new PriceRule();
            rule.item = item;
            rule.count = count;
            rule.price = price;
            return rule;
        }
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
         * What to cook in the Poké Pot: Cobblemon's own ingredients, which
         * do not grow everywhere, plus a few vanilla staples its recipes ask
         * for. Prices against about 600 points per hour of hunting: all a
         * Poké Cake needs costs under twenty minutes of it.
         */
        public static final String COOKING = "cooking";

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

        /**
         * Cost in Safari points, or {@code "auto"} in the file
         * ({@link #AUTO_PRICE} here) for a Pokémon priced like the shop's
         * random ones: its rarity in the Safari times its evolution stage.
         * The shop resolves it each time it lists its entries, so it follows
         * the rotating prices of the file.
         */
        @JsonAdapter(ShopPriceAdapter.class)
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
         * Hours each player must wait after buying this entry before buying
         * it again, counted in real time (the wait goes on while the player
         * or the server is offline); null for no wait. Independent of the
         * rotations, unlike {@link #limitPerRotation}; both may apply.
         */
        public Integer cooldownHours;

        /**
         * Id of the tab showing this entry (see {@link SafariShopConfig#tabs}).
         * Empty picks one from the type: {@code pokemon} for Pokémon,
         * {@code balls} for items whose id ends in {@code _ball}, {@code items}
         * otherwise. Filled in by the config manager.
         */
        public String tab = "";

        /**
         * In-memory value of {@link #price} for {@code "auto"}. Negative, so
         * it can never be a real price; checked before any use of the price
         * as a number of points.
         */
        public static final int AUTO_PRICE = -1;

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

        /** An ingredient of the cooking tab. */
        static Entry cooking(String id, int count, int price) {
            Entry entry = item(id, count, price);
            entry.tab = Tab.COOKING;
            return entry;
        }

        /** The free Safari Balls, once every {@link #FREE_BALLS_COOLDOWN_HOURS} hours per player. */
        static Entry freeBalls() {
            Entry entry = item(FREE_BALLS_ITEM, FREE_BALLS_COUNT, 0);
            entry.cooldownHours = FREE_BALLS_COOLDOWN_HOURS;
            return entry;
        }

        /** Whether this is the default free Safari Balls entry, whatever its count and wait. */
        boolean isFreeBalls() {
            return !isPokemon() && FREE_BALLS_ITEM.equals(item) && price == 0 && cooldownHours != null;
        }

        /** A trade of overworld goods for one Safari Ticket, limited per rotation. */
        static Entry ticketTrade(String item, int count) {
            Entry entry = item(TICKET_ITEM, 1, 0);
            entry.cost = new ArrayList<>(List.of(Cost.of(item, count)));
            entry.limitPerRotation = TRADE_LIMIT;
            return entry;
        }

        /**
         * An item of the current rotation (see {@link RotatingGoods}). Built
         * in memory only, never written to the file.
         */
        public static Entry rotatingItem(String id, int count, int price, String tab) {
            Entry entry = item(id, count, price);
            entry.tab = tab;
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

        /** Whether the price is computed rather than given. */
        public boolean hasAutoPrice() {
            return price == AUTO_PRICE;
        }

        /**
         * A copy with a resolved price, for the shop's listing; the config
         * entry keeps {@code "auto"}. Lists are shared, which is safe: the
         * listing never changes them.
         */
        public Entry withPrice(int resolvedPrice) {
            Entry copy = new Entry();
            copy.type = type;
            copy.item = item;
            copy.count = count;
            copy.pokemon = pokemon;
            copy.price = resolvedPrice;
            copy.cost = cost;
            copy.limitPerRotation = limitPerRotation;
            copy.cooldownHours = cooldownHours;
            copy.tab = tab;
            return copy;
        }

        /** Whether this entry sells a Pokémon. */
        public boolean isPokemon() {
            return TYPE_POKEMON.equals(type);
        }
    }
}
