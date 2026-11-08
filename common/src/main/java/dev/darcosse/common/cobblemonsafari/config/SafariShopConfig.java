package dev.darcosse.common.cobblemonsafari.config;

import com.google.gson.annotations.JsonAdapter;
import com.google.gson.annotations.SerializedName;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The Safari Warden's shop, serialised as {@code config/cobblemon_safari/shop.json}.
 *
 * <p>Laid out by tab: each tab holds its fixed offers ({@code fixed}) and
 * its rotating ones ({@code rotatingPokemon}, {@code rotatingItems},
 * {@code rotatingSnacks}), and may split them into {@code sections}, shown
 * on pages of their own. Every offer, fixed or drawn, has the same price and
 * limit keys. Plain data only: {@link SafariShopConfigManager} validates it
 * and flattens the fixed offers into {@link #entries}. Field names are the
 * JSON keys; null fields are left out of the file.
 */
public class SafariShopConfig {

    /**
     * Format version of the file, see
     * {@link SafariShopConfigManager#CURRENT_VERSION}. Not a setting: the
     * manager rewrites it.
     */
    public int configVersion = SafariShopConfigManager.CURRENT_VERSION;

    /** Whether the shop opens at all. */
    public boolean enabled = true;

    /** Values an offer takes when it does not set them. */
    public Defaults defaults = new Defaults();

    /** The tabs of the screen, in display order, with what each one sells. */
    public List<Tab> tabs = defaultTabs();

    /**
     * Every fixed offer of every tab and section, in file order, each with
     * its tab and section filled in. Built by the manager on load; not part
     * of the file ({@code transient}).
     */
    public transient List<Entry> entries = new ArrayList<>();

    static final String DEFAULT_POKEMON = "tauros level=45";
    static final int DEFAULT_POKEMON_PRICE = 200;
    static final String COOKING_ICON = "cobblemon:campfire_pot_red";
    static final String FREE_BALLS_ITEM = "cobblemon:safari_ball";
    /**
     * Safari Balls given free once a day: half a stack, so two claims fill
     * one slot. Only for new files; an existing shop.json keeps its count.
     */
    static final int FREE_BALLS_COUNT = 32;
    static final int FREE_BALLS_COOLDOWN_HOURS = 24;
    public static final String TICKET_ITEM = "cobblemon_safari:safari_ticket";
    static final int TRADE_APRICORNS = 16;
    static final int TRADE_BERRIES = 24;
    static final int TRADE_STONES = 1;
    static final int TRADE_FOSSILS = 1;
    /** Trades per player and rotation: a farm must not become an endless ticket supply. */
    static final int TRADE_LIMIT = 1;
    static final String TRADE_APRICORN_TAG = "#cobblemon:apricorns";
    static final String TRADE_BERRY_TAG = "#cobblemon:berries";
    static final String TRADE_STONE_TAG = "#cobblemon:evolution_stones";
    static final String TRADE_FOSSIL_TAG = "#cobblemon:fossils";
    static final String POKE_SNACK = "cobblemon:poke_snack";
    static final String POKE_CAKE = "cobblemon:poke_cake";
    /** Price of the plain Poké Snack: the cheapest bait, a first try at a habitat. */
    static final int PLAIN_SNACK_PRICE = 150;
    /** Plain snacks a player may buy per rotation; more and the shop becomes the hunt. */
    static final int PLAIN_SNACK_PER_ROTATION = 3;
    /** Themed snacks of each kind a player may buy per rotation. */
    static final int THEMED_SNACK_PER_ROTATION = 2;

    /** Names of the default sections of the cooking tab. */
    public static final String SECTION_INGREDIENTS = "ingredients";
    public static final String SECTION_PREPARED = "prepared";

    /** The default tabs, filled as the shop ships. */
    static List<Tab> defaultTabs() {
        Tab pokemon = Tab.of(Tab.POKEMON, "cobblemon:poke_ball");
        // The fixed Pokémon is a once-only welcome gift, not a farm.
        pokemon.fixed.add(Entry.pokemon(DEFAULT_POKEMON, DEFAULT_POKEMON_PRICE).withLimit(Limit.lifetime(1)));
        pokemon.rotatingPokemon = new RotatingPokemon();

        Tab items = Tab.of(Tab.ITEMS, "cobblemon:rare_candy");
        items.fixed.add(Entry.item("cobblemon:rare_candy", 1, 60));
        items.fixed.add(Entry.item("cobblemon:exp_candy_m", 3, 40));
        items.fixed.add(Entry.item("cobblemon:ability_capsule", 1, 400));
        items.rotatingItems = RotatingGoods.items();

        Tab balls = Tab.of(Tab.BALLS, "cobblemon:great_ball");
        balls.fixed.add(Entry.item(FREE_BALLS_ITEM, FREE_BALLS_COUNT, 0)
                .withLimit(Limit.cooldown(FREE_BALLS_COOLDOWN_HOURS)));
        balls.fixed.add(Entry.item("cobblemon:poke_ball", 10, 20));
        balls.fixed.add(Entry.item("cobblemon:great_ball", 5, 40));
        balls.fixed.add(Entry.item("cobblemon:ultra_ball", 5, 80));
        balls.rotatingItems = RotatingGoods.balls();

        Tab cooking = Tab.of(Tab.COOKING, COOKING_ICON);
        // Ready-made dishes first: they are what most hunters come for, the
        // ingredients are for those who cook their own.
        cooking.sections = new ArrayList<>(List.of(preparedSection(), ingredientsSection()));

        Tab tickets = Tab.of(Tab.TICKETS, TICKET_ITEM);
        tickets.fixed.add(Entry.ticketTrade(TRADE_APRICORN_TAG, TRADE_APRICORNS));
        tickets.fixed.add(Entry.ticketTrade(TRADE_BERRY_TAG, TRADE_BERRIES));
        tickets.fixed.add(Entry.ticketTrade(TRADE_STONE_TAG, TRADE_STONES));
        tickets.fixed.add(fossilTrade());
        return new ArrayList<>(List.of(pokemon, items, balls, cooking, tickets));
    }

    /**
     * What to cook in the Poké Pot: Cobblemon's own ingredients, which do not
     * grow everywhere, plus the vanilla staples its recipes ask for, and a few
     * berries each rotation. Prices against about 600 points per hour of
     * hunting: all a Poké Cake needs costs under twenty minutes of it.
     */
    static Section ingredientsSection() {
        Section section = Section.named(SECTION_INGREDIENTS);
        section.fixed.add(Entry.item("cobblemon:hearty_grains", 8, 40));
        section.fixed.add(Entry.item("cobblemon:vivichoke", 2, 60));
        section.fixed.add(Entry.item("cobblemon:medicinal_leek", 4, 40));
        section.fixed.add(Entry.item("cobblemon:tasty_tail", 1, 80));
        section.fixed.add(Entry.item("cobblemon:galarica_nuts", 4, 60));
        section.fixed.add(Entry.item("cobblemon:pep_up_flower", 4, 40));
        section.fixed.add(Entry.item("cobblemon:energy_root", 2, 60));
        section.fixed.add(Entry.item("cobblemon:big_root", 2, 50));
        section.fixed.add(Entry.item("cobblemon:revival_herb", 1, 120));
        section.fixed.add(Entry.item("cobblemon:moomoo_milk", 2, 40));
        section.fixed.add(Entry.item("minecraft:honey_bottle", 4, 30));
        section.fixed.add(Entry.item("minecraft:sugar", 16, 20));
        section.fixed.add(Entry.item("minecraft:egg", 8, 20));
        section.fixed.add(Entry.item("minecraft:wheat", 16, 20));
        section.fixed.add(Entry.item("minecraft:milk_bucket", 1, 20));
        RotatingGoods berries = new RotatingGoods();
        berries.count = 6;
        berries.prices = new ArrayList<>(List.of(PriceRule.of("#cobblemon:berries", 4, 30)));
        section.rotatingItems = berries;
        return section;
    }

    /**
     * Ready-made baits: a plain Poké Snack, eight themed ones, two rare ones
     * and a Poké Cake, plus two seasoned snacks drawn each rotation.
     *
     * <p>Seasonings picked by their bait effect in Cobblemon's data
     * ({@code spawn_bait_effects/berries}): type berries (Passho for Water...)
     * and egg-group berries (Aspear for Water 1 and 2...) pull the same kind of
     * Pokémon, so each themed snack has a clear use. Oran, Sitrus and the other
     * {@code bite_time} berries are left out: they only act on fishing.
     *
     * <p>Limited per rotation: a snack brings Pokémon for a long while, so a
     * stack of them would empty the hunt of its search. The rare ones
     * (shiny reroll, hidden ability) are one per rotation and priced well
     * above the rest: they change what a hunt is worth.
     */
    static Section preparedSection() {
        Section section = Section.named(SECTION_PREPARED);
        section.fixed.add(Entry.snack(Snack.of(POKE_SNACK), PLAIN_SNACK_PRICE)
                .withLimit(Limit.perRotation(PLAIN_SNACK_PER_ROTATION)));
        themed(section, 300, "cobblemon:passho_berry", "cobblemon:aspear_berry");                   // Water
        themed(section, 300, "cobblemon:occa_berry", "cobblemon:rawst_berry");                      // Fire, Field
        themed(section, 300, "cobblemon:rindo_berry", "cobblemon:cheri_berry");                     // Grass, Fairy
        themed(section, 300, "cobblemon:wacan_berry", "cobblemon:chilan_berry");                    // Electric, Normal
        themed(section, 300, "cobblemon:coba_berry", "cobblemon:chesto_berry");                     // Flying, Human-Like
        themed(section, 300, "cobblemon:tanga_berry", "cobblemon:pecha_berry");                     // Bug, Water 3
        themed(section, 375, "cobblemon:charti_berry", "cobblemon:shuca_berry", "cobblemon:persim_berry"); // Rock, Ground, Mineral
        themed(section, 375, "cobblemon:kasib_berry", "cobblemon:payapa_berry", "cobblemon:colbur_berry"); // Ghost, Psychic, Dark
        themed(section, 450, "cobblemon:haban_berry", "cobblemon:lum_berry");                       // Dragon, Monster
        section.fixed.add(Entry.snack(Snack.of(POKE_SNACK, "cobblemon:enigma_berry"), 1200)        // hidden ability
                .withLimit(Limit.perRotation(1)));
        section.fixed.add(Entry.snack(Snack.of(POKE_SNACK, "cobblemon:starf_berry"), 2000)         // shiny reroll
                .withLimit(Limit.perRotation(1)));
        section.fixed.add(Entry.snack(Snack.of(POKE_CAKE, "cobblemon:razz_berry"), 500)
                .withLimit(Limit.perRotation(1)));
        section.rotatingSnacks = new RotatingSnacks();
        return section;
    }

    /** Adds a themed Poké Snack, two per player and rotation. */
    private static void themed(Section section, int price, String... seasonings) {
        section.fixed.add(Entry.snack(Snack.of(POKE_SNACK, seasonings), price)
                .withLimit(Limit.perRotation(THEMED_SNACK_PER_ROTATION)));
    }

    /** The fossil ticket trade; also added to files older than version 2 by the manager. */
    static Entry fossilTrade() {
        return Entry.ticketTrade(TRADE_FOSSIL_TAG, TRADE_FOSSILS);
    }

    /** Values an offer takes when it does not set them. */
    public static class Defaults {
        /** Limit of an offer without one; null (the default) for none. */
        public Limit limit;
    }

    /**
     * How often a player may buy an offer. Every limit is per player.
     * Null fields mean "no such limit"; all set ones apply together.
     */
    public static class Limit {
        /** Purchases allowed; null for no count limit, 0 to make the offer unbuyable. */
        public Integer max;
        /**
         * Whether {@link #max} counts over the player's whole life in this
         * world rather than per rotation of the specials. Optional: absent
         * (or false) means per rotation.
         */
        public Boolean lifetime;
        /**
         * Hours to wait after a purchase before the next one, in real time
         * (the wait goes on while the player or the server is offline).
         */
        public Integer cooldownHours;

        /** Gson needs a no-argument constructor. */
        public Limit() {
        }

        /** At most {@code max} purchases per rotation. */
        public static Limit perRotation(int max) {
            Limit limit = new Limit();
            limit.max = max;
            return limit;
        }

        /** At most {@code max} purchases in a player's life. */
        public static Limit lifetime(int max) {
            Limit limit = perRotation(max);
            limit.lifetime = true;
            return limit;
        }

        /** A wait of {@code hours} after each purchase. */
        static Limit cooldown(int hours) {
            Limit limit = new Limit();
            limit.cooldownHours = hours;
            return limit;
        }

        /** Whether {@link #max} counts for life rather than per rotation. */
        public boolean isLifetime() {
            return Boolean.TRUE.equals(lifetime);
        }

        /** Whether this limit restricts anything at all. */
        public boolean isEmpty() {
            return max == null && cooldownHours == null;
        }
    }

    /**
     * What one tab or section sells: its fixed offers and its rotating
     * draws. Each draw is optional; absent, the shelf has none of that kind.
     */
    public abstract static class Shelf {
        /** Offers always listed. */
        public List<Entry> fixed = new ArrayList<>();
        /** Pokémon drawn each rotation; null for none. */
        public RotatingPokemon rotatingPokemon;
        /** Items drawn each rotation; null for none. */
        public RotatingGoods rotatingItems;
        /** Seasoned Poké Snacks drawn each rotation; null for none. */
        public RotatingSnacks rotatingSnacks;
    }

    /** One tab of the shop screen, and what it sells outside its sections. */
    public static class Tab extends Shelf {
        /** Built-in tab ids, with translated names. */
        public static final String POKEMON = "pokemon";
        public static final String ITEMS = "items";
        public static final String BALLS = "balls";
        public static final String TICKETS = "tickets";
        public static final String COOKING = "cooking";

        /** For a built-in id the name comes from {@code cobblemon_safari.shop.tab.<id>}. */
        public String id = "";
        /** Name shown instead of the translated one; empty to use the translation (or the id). */
        public String name = "";
        /** Item id drawn on the tab, e.g. {@code cobblemon:poke_ball}; empty or unknown for none. */
        public String icon = "";
        /**
         * Parts of the tab, shown one after the other after what the tab
         * itself sells, in this order: a way to group offers in the file.
         * Null when the tab has none, so the key only shows in tabs that use it.
         */
        public List<Section> sections;

        /** Gson needs a no-argument constructor. */
        public Tab() {
        }

        /** A tab with a translated name and nothing for sale yet. */
        static Tab of(String id, String icon) {
            Tab tab = new Tab();
            tab.id = id;
            tab.icon = icon;
            return tab;
        }
    }

    /** A named part of a tab. */
    public static class Section extends Shelf {
        /** Its name, unique in the tab; it tells the parts apart in the file and in the log. */
        public String name = "";

        /** Gson needs a no-argument constructor. */
        public Section() {
        }

        static Section named(String name) {
            Section section = new Section();
            section.name = name;
            return section;
        }
    }

    /** Price, weight and switch of one rarity in the Pokémon draw. */
    public static class RarityRule {
        /** Whether Pokémon of this rarity are drawn. */
        public boolean allowed = true;
        /** Base price, multiplied by the evolution stage. */
        public int price = 0;
        /**
         * Chance of a slot going to this rarity, against the others' weights:
         * with 10, 6 and 3, about half the slots are common.
         */
        public int weight = 1;

        /** Gson needs a no-argument constructor. */
        public RarityRule() {
        }

        static RarityRule of(boolean allowed, int price, int weight) {
            RarityRule rule = new RarityRule();
            rule.allowed = allowed;
            rule.price = price;
            rule.weight = weight;
            return rule;
        }
    }

    /** The four rarities of the Pokémon draw. Keys are Cobblemon's bucket names. */
    public static class Rarities {
        public RarityRule common = RarityRule.of(true, 250, 10);
        public RarityRule uncommon = RarityRule.of(true, 500, 6);
        public RarityRule rare = RarityRule.of(true, 1000, 3);
        @SerializedName("ultra-rare")
        public RarityRule ultraRare = RarityRule.of(false, 2500, 1);
    }

    /** One legendary-class category in the Pokémon draw, sold on top of the ordinary slots. */
    public static class CategoryRule {
        /** Whether Pokémon of this category are drawn. */
        public boolean allowed = false;
        /** Price of one, whatever its stage. */
        public int price = 0;
        /** How many of the category are listed per rotation, on top of {@code count}. */
        public int maxPerRotation = 1;
        /** Limit of each of these offers; null to use the draw's own. */
        public Limit limit;

        /** Gson needs a no-argument constructor. */
        public CategoryRule() {
        }

        static CategoryRule of(boolean allowed, int price, int maxPerRotation) {
            CategoryRule rule = new CategoryRule();
            rule.allowed = allowed;
            rule.price = price;
            rule.maxPerRotation = maxPerRotation;
            // One each: a legendary bought by one player stays a rare sight.
            rule.limit = Limit.perRotation(1);
            return rule;
        }
    }

    /** The legendary-class categories of the Pokémon draw, as in {@code spawns}. */
    public static class Categories {
        public CategoryRule legendary = CategoryRule.of(false, 20000, 1);
        public CategoryRule mythical = CategoryRule.of(false, 20000, 1);
        public CategoryRule ultraBeast = CategoryRule.of(false, 8000, 1);
        // On, as in spawns: paradox Pokémon are regular finds, not legends.
        public CategoryRule paradox = CategoryRule.of(true, 5000, 2);
    }

    /** Level of the drawn Pokémon. */
    public static class Level {
        /** Value of {@link #mode}: the lowest level it spawns at in the Safari. */
        public static final String SPAWN = "spawn";
        /** Value of {@link #mode}: a level drawn between {@link #min} and {@link #max}. */
        public static final String RANGE = "range";

        /** {@link #SPAWN} or {@link #RANGE}. */
        public String mode = SPAWN;
        /** Lowest level, for {@link #RANGE}. */
        public int min = 5;
        /** Highest level, for {@link #RANGE}. */
        public int max = 30;
    }

    /** Random extras of the drawn Pokémon, with what they add to the price. */
    public static class Modifiers {
        /**
         * Chance, 0 to 1, that a drawn Pokémon is shiny: 1 % by default, so a
         * shiny shows up in the shop about once every six or seven rotations
         * of 15 Pokémon.
         */
        public double shinyChance = 0.01;
        /** Price multiplier of a shiny one. */
        public double shinyPriceMultiplier = 3.0;
    }

    /**
     * Pokémon drawn each rotation among the species living in the Safari:
     * {@link #count} ordinary ones by rarity weight, plus the legendary-class
     * ones each allowed category adds.
     */
    public static class RotatingPokemon {
        public boolean enabled = true;
        /** Ordinary Pokémon listed per rotation; the legendary-class ones come on top. */
        public int count = 15;
        public Rarities rarities = new Rarities();
        public Categories categories = new Categories();
        public Level level = new Level();
        public Modifiers modifiers = new Modifiers();
        /** Leave out the species on the specials board this rotation, so the shop does not spoil the hunt. */
        public boolean excludeFeatured = true;
        /**
         * Pokémon never drawn, as Cobblemon properties: {@code "arceus"} for a
         * species and all its forms, {@code "vulpix alolan"} for one form.
         */
        public List<String> excludeList = new ArrayList<>();
        /** Limit of each drawn offer; null for none. */
        public Limit limit;
    }

    /**
     * Items drawn each rotation: those its price rules cover, minus the
     * excluded ones and those the fixed offers sell.
     */
    public static class RotatingGoods {
        public boolean enabled = true;
        /** Items drawn besides {@link #always}. */
        public int count = 8;
        /** Items listed every rotation; each needs a price rule. */
        public List<String> always = new ArrayList<>();
        /** Items or {@code #tags} never drawn. */
        public List<String> excluded = new ArrayList<>();
        /**
         * Prices, in priority order: the first rule covering an item gives its
         * stack and price, so specific items go before the tags holding them.
         * Only items some rule covers can be drawn.
         */
        public List<PriceRule> prices = new ArrayList<>();
        /** Limit of each drawn offer; null for none. */
        public Limit limit;

        /** Gson needs a no-argument constructor. */
        public RotatingGoods() {
        }

        static RotatingGoods items() {
            RotatingGoods goods = new RotatingGoods();
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

        static RotatingGoods balls() {
            RotatingGoods goods = new RotatingGoods();
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

    /** How many seasonings a drawn snack gets, and from where. */
    public static class SeasoningDraw {
        /** Items or {@code #tags} to pick from; only those Cobblemon knows as seasonings count. */
        public List<String> pool = new ArrayList<>(List.of("#cobblemon:berries"));
        public int min = 1;
        public int max = 3;
    }

    /** Price of a drawn snack: a base plus a part per seasoning. */
    public static class SnackPrice {
        public int base = 150;
        public int perSeasoning = 75;
    }

    /** Seasoned snacks drawn each rotation. */
    public static class RotatingSnacks {
        public boolean enabled = true;
        public int count = 2;
        /** The dish: {@code cobblemon:poke_snack}, {@code cobblemon:poke_cake}... */
        public String base = POKE_SNACK;
        public SeasoningDraw seasonings = new SeasoningDraw();
        public SnackPrice price = new SnackPrice();
        /**
         * Cobblemon seasoning processors to run; null for the dish's own
         * (see {@link Snack#processors}).
         */
        public List<String> processors;
        /** Limit of each drawn offer; one per rotation by default. */
        public Limit limit = Limit.perRotation(1);
    }

    /** A dish already cooked with its seasonings, as the Poké Pot would make it. */
    public static class Snack {
        /** The dish item. */
        public String base = POKE_SNACK;
        /** Seasoning item ids, in order; empty for a plain dish. */
        public List<String> seasonings = new ArrayList<>();
        /**
         * Cobblemon seasoning processors to run (what the seasonings add:
         * colour, ingredients list, bait effects...). Optional: absent, the
         * dish's own as its Poké Pot recipe lists them.
         */
        public List<String> processors;

        /** Gson needs a no-argument constructor. */
        public Snack() {
        }

        static Snack of(String base, String... seasonings) {
            Snack snack = new Snack();
            snack.base = base;
            snack.seasonings = new ArrayList<>(List.of(seasonings));
            return snack;
        }
    }

    /** A stack and price for the items one rule covers. */
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

        /** A rule with the given item or tag, stack and price, for the defaults. */
        static PriceRule of(String item, int count, int price) {
            PriceRule rule = new PriceRule();
            rule.item = item;
            rule.count = count;
            rule.price = price;
            return rule;
        }
    }

    /** Items an offer takes in exchange. */
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

        /** A cost of the given item or tag and count, for the defaults. */
        static Cost of(String item, int count) {
            Cost cost = new Cost();
            cost.item = item;
            cost.count = count;
            return cost;
        }
    }

    /**
     * One offer: an item stack, a Pokémon or a ready-made dish, exactly one
     * of {@link #item}, {@link #pokemon} and {@link #snack}. Fixed offers come
     * from the file; drawn ones are built in memory the same way.
     */
    public static class Entry {
        /** Item id, e.g. {@code cobblemon:rare_candy}; null for a Pokémon or a dish. */
        public String item;
        /** Stack size given per purchase, for an item or a dish; null means 1. */
        public Integer count;
        /** Cobblemon properties, e.g. {@code "dratini level=15"}; null for an item or a dish. */
        public String pokemon;
        /** A dish cooked with its seasonings; null for an item or a Pokémon. */
        public Snack snack;
        /**
         * Cost in Safari points, or {@code "auto"} in the file
         * ({@link #AUTO_PRICE} here) for a Pokémon priced like the drawn ones.
         */
        @JsonAdapter(ShopPriceAdapter.class)
        public int price = 0;
        /** Items taken in exchange, on top of {@link #price}; null for a plain purchase. */
        public List<Cost> cost;
        /** How often a player may buy it; null for {@code defaults.limit}. */
        public Limit limit;

        /** Tab id showing it; filled in by the manager or the draw. */
        public transient String tab = "";
        /** Section name of its page, empty for the tab's own part; filled in like {@link #tab}. */
        public transient String section = "";

        /**
         * In-memory value of {@link #price} for {@code "auto"}. Negative, so
         * it can never be a real price.
         */
        public static final int AUTO_PRICE = -1;

        /** Gson needs a no-argument constructor. */
        public Entry() {
        }

        static Entry item(String id, int count, int price) {
            Entry entry = new Entry();
            entry.item = id;
            entry.count = count;
            entry.price = price;
            return entry;
        }

        static Entry pokemon(String properties, int price) {
            Entry entry = new Entry();
            entry.pokemon = properties;
            entry.price = price;
            return entry;
        }

        static Entry snack(Snack snack, int price) {
            Entry entry = new Entry();
            entry.snack = snack;
            entry.price = price;
            return entry;
        }

        /** A trade of overworld goods for one Safari Ticket, limited per rotation. */
        static Entry ticketTrade(String item, int count) {
            Entry entry = item(TICKET_ITEM, 1, 0);
            entry.cost = new ArrayList<>(List.of(Cost.of(item, count)));
            entry.limit = Limit.perRotation(TRADE_LIMIT);
            return entry;
        }

        /** This offer with the given limit; for the defaults and the draws. */
        Entry withLimit(Limit offerLimit) {
            limit = offerLimit;
            return this;
        }

        /** A drawn item, placed on its shelf. */
        public static Entry drawnItem(String id, int count, int price, @Nullable Limit limit, String tab,
                                      String section) {
            return item(id, count, price).withLimit(limit).placed(tab, section);
        }

        /** A drawn Pokémon, placed on its shelf. */
        public static Entry drawnPokemon(String properties, int price, @Nullable Limit limit, String tab,
                                         String section) {
            return pokemon(properties, price).withLimit(limit).placed(tab, section);
        }

        /** A drawn dish, placed on its shelf. */
        public static Entry drawnSnack(Snack snack, int price, @Nullable Limit limit, String tab, String section) {
            return snack(snack, price).withLimit(limit).placed(tab, section);
        }

        /** Sets the tab and section; returns this. */
        public Entry placed(String tabId, String sectionName) {
            tab = tabId;
            section = sectionName;
            return this;
        }

        /** Whether this offer asks for items. */
        public boolean hasCost() {
            return cost != null && !cost.isEmpty();
        }

        /** Whether the price is computed rather than given. */
        public boolean hasAutoPrice() {
            return price == AUTO_PRICE;
        }

        /** Whether this offer sells a Pokémon. */
        public boolean isPokemon() {
            return pokemon != null;
        }

        /** Whether this offer sells a ready-made dish. */
        public boolean isSnack() {
            return snack != null && pokemon == null;
        }

        /** Stack size given, 1 when unset. */
        public int stackCount() {
            return count == null ? 1 : count;
        }

        /**
         * A copy with a resolved price, for the shop's listing; the config
         * offer keeps {@code "auto"}. Lists are shared, which is safe: the
         * listing never changes them.
         */
        public Entry withPrice(int resolvedPrice) {
            Entry copy = new Entry();
            copy.item = item;
            copy.count = count;
            copy.pokemon = pokemon;
            copy.snack = snack;
            copy.price = resolvedPrice;
            copy.cost = cost;
            copy.limit = limit;
            copy.tab = tab;
            copy.section = section;
            return copy;
        }
    }
}
