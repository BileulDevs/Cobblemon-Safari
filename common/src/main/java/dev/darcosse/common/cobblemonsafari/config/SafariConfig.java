package dev.darcosse.common.cobblemonsafari.config;

import dev.darcosse.common.cobblemonsafari.worldgen.IslandRelief;
import dev.darcosse.common.cobblemonsafari.bounty.BountyKind;

import java.util.ArrayList;
import java.util.List;

/**
 * Server-side rules of the Safari, serialised as {@code config/cobblemon_safari/server.json}.
 *
 * <p>Plain data only: validation and derived values live in
 * {@link SafariConfigManager}. Field names are the JSON keys, so renaming a
 * field silently resets that setting on every existing server.
 *
 * <p>Only what a server owner may want to tune lives here. Values tied to the
 * generated terrain (the park's border, arrival point and rest houses) are in
 * {@code park.ParkGeometry}, written by the worldgen script; fine-tuning
 * values with a single sensible setting are named constants in the classes
 * that use them.
 */
public class SafariConfig {

    /**
     * Format version of the file, see
     * {@link SafariConfigManager#CURRENT_CONFIG_VERSION}. Not a setting:
     * the manager rewrites it.
     */
    public int configVersion = SafariConfigManager.CURRENT_CONFIG_VERSION;

    /** How long a hunt lasts, when the clock runs, and where it ends. */
    public Hunt hunt = new Hunt();

    /** The Safari Ticket, the time bank it fills, and the tickets found in chests. */
    public Tickets tickets = new Tickets();

    /** The Safari Gates found in the overworld: entry, warden and roaming Pokémon. */
    public Gate gate = new Gate();

    /** Which Poké Balls work inside, and the games' loan of Safari Balls. */
    public Balls balls = new Balls();

    /** Pokémon battles inside the Safari. */
    public Battles battles = new Battles();

    /** Catch rate, marks, and Pokémon running away after a failed ball. */
    public Capture capture = new Capture();

    /** What players can and cannot do to themselves and to the terrain inside the Safari. */
    public Protection protection = new Protection();

    /** Cobblemon's natural spawns inside the Safari: which Pokémon may come in, and how many. */
    public Spawns spawns = new Spawns();

    /** The rotation of special Pokémon, its board and the spawner that brings them in. */
    public Specials specials = new Specials();

    /** Rare happenings in the Safari: swarms and full moon nights. */
    public Events events = new Events();

    /** Safari points earned by catching Pokémon, spent at the warden's shop. */
    public Points points = new Points();

    /** The rest houses: their building on a new map, their Safari Guide and the trips to them. */
    public RestHouses restHouses = new RestHouses();

    /** The Escape Rope lent at the entrance, to get out of caves. */
    public EscapeRope escapeRope = new EscapeRope();

    /** Daily regeneration of the Safari map. */
    public Reset reset = new Reset();

    /**
     * The missions of each rotation, paid in Safari points. Last on purpose:
     * Gson writes fields in declaration order, and this long section (one
     * entry per kind) would otherwise bury the short ones after it.
     */
    public Missions missions = new Missions();

    /** How long a hunt lasts, when its clock runs, and where it ends. */
    public static class Hunt {
        /**
         * Hard cap on the remaining time; adding time never goes above it.
         * Also the most banked time one entry spends, the rest staying in
         * the bank.
         */
        public int maxDurationSeconds = 2 * 60 * 60;

        /**
         * When true the clock only runs while the player is online and
         * inside. When false it keeps running while they are offline, and
         * they are escorted out on their next login if it ran out.
         */
        public boolean pauseWhileOffline = true;

        /**
         * Remaining times, in seconds, at which the player gets a warning in
         * chat. Its colour follows the time left, whatever the list holds:
         * white, yellow from 5 minutes, red from 1 minute
         * (SafariSessionManager.WARNING_YELLOW_SECONDS and WARNING_RED_SECONDS).
         */
        public List<Integer> warningsAtSeconds = new ArrayList<>(List.of(900, 600, 300, 60, 30, 10));

        /** Where players go when their hunt ends. */
        public ExitDestination exitDestination = ExitDestination.RETURN_POINT;

        /**
         * Creative and spectator players (building, moderation) may roam
         * inside without a hunt, may enter through a gate, its warden or
         * {@code /safari enter} with an empty time bank (a hunt holding no
         * time, which ends once they switch to survival or adventure), keep
         * their clock stopped while they stay in that mode, and ignore the
         * terrain protection. Not on login: a player who logs in inside
         * without a hunt is sent out whatever their game mode.
         */
        public boolean exemptCreativeAndSpectator = true;
    }

    /** Which Poké Balls work inside, and the games' loan of Safari Balls. */
    public static class Balls {
        /** Poké Ball ids (as in Cobblemon, e.g. {@code cobblemon:safari_ball}) usable inside the Safari. */
        public List<String> allowed = new ArrayList<>(List.of(
                "cobblemon:safari_ball",
                "cobblemon:master_ball"
        ));

        /** Safari Balls lent at the entrance, as in the games. */
        public Loan loan = new Loan();
    }

    /** Pokémon battles inside the Safari. */
    public static class Battles {
        /** Whether Pokémon battles may start inside the Safari. The games forbid them. */
        public boolean enabled = true;

        /**
         * Whether a Pokémon fished inside the Safari starts a battle with the
         * angler, as Cobblemon does everywhere one second after the bite.
         * False leaves the Pokémon free on the bank, to be caught with a ball
         * out of battle (Safari Ball bonus included) or battled on purpose,
         * the Safari way, hence the default.
         */
        public boolean fishingStartsBattle = false;

        /**
         * Extra time a player may stay after their clock hits zero when they
         * are in the middle of a battle, so the battle is not torn apart by a
         * teleport.
         */
        public int graceSeconds = 120;
    }

    /** Rare happenings that change Cobblemon's natural spawns in the Safari for a while. */
    public static class Events {
        /** One species swarming in one habitat for a whole rotation. */
        public Swarm swarm = new Swarm();
        /** Some types coming out more on full moon nights. */
        public FullMoon fullMoon = new FullMoon();
        /** Some rains turning into a downpour, with some types coming out more. */
        public Downpour downpour = new Downpour();
        /** Some thunderstorms bringing rare Pokémon of some types to the high ground. */
        public Storm storm = new Storm();
        /** Some dry days baking the hot lands, with some types coming out more there. */
        public Heatwave heatwave = new Heatwave();
        /** Some clear nights bringing each player one rare Pokémon of some types on the frozen lands. */
        public Aurora aurora = new Aurora();
    }

    /**
     * A swarm: with each rotation of the specials, maybe, one species of the
     * Safari comes out in numbers in one of its habitats. It is announced to
     * the players in the Safari and on the specials board. Only species a
     * player's spawner can really bring there are drawn (no Poké Snack-only
     * or fishing-only spawns). In that habitat, the species is offered with
     * the common Pokémon in Cobblemon's natural spawns, with its weight
     * multiplied, and the swarm's own spawner keeps a few around each player.
     */
    public static class Swarm {
        /** Master switch. */
        public boolean enabled = true;

        /** Chance, from 0 to 1, that a rotation has a swarm. 1 makes every rotation have one. */
        public double chance = 0.5;

        /**
         * Spawn buckets a swarm may be drawn from: "common", "uncommon",
         * "rare", "ultra-rare". A species' bucket is its most common one in the
         * habitat considered. Legendary-class species are always "ultra-rare"
         * and also need their category on in {@link #rarities}. Empty: no swarm.
         */
        public List<String> buckets = new ArrayList<>(List.of("uncommon", "rare", "ultra-rare"));

        /**
         * Legendary-class categories in swarms (named "rarities" in the file:
         * they are rarities above ultra-rare), each with allowSpawn and
         * maxSpawnPerPokemon. These replace the categories of spawns for the
         * swarming species in its habitat: a legendary swarm is neither
         * stopped by spawns.legendary nor uses up its slots. allowSpawn off,
         * no species of that category swarms; on, it may, as an "ultra-rare"
         * swarm. maxSpawnPerPokemon: most Pokémon of the swarming species a
         * swarm brings in a rotation, counting those caught plus those still
         * roaming (one that leaves uncaught gives its place back); 0, no cap
         * other than maxNearby.
         */
        public SwarmCategories rarities = new SwarmCategories();

        /**
         * Chance of each bucket being the swarm's, relative to the others:
         * with 10, 4 and 1 for uncommon, rare and ultra-rare, two swarms in
         * three are uncommon. Per bucket, not per species, so a bucket's share
         * does not grow with the number of species in it. A bucket at 0, or
         * missing from {@link #buckets}, is never drawn; a bucket with no
         * candidate passes its turn to the others.
         */
        public SpawnWeights weights = new SpawnWeights();

        /**
         * Pokémon that never swarm, written as Cobblemon properties:
         * "arceus" leaves out every form of the species, "vulpix alolan"
         * only Alolan Vulpix. Unknown species or forms are logged and ignored.
         */
        public List<String> excludeList = new ArrayList<>();

        /**
         * How much more often the swarm is picked in its habitat, among the
         * common Pokémon of Cobblemon's natural spawns. 1 leaves only the move
         * into the common pick. 1 to 100.
         */
        public double weightMultiplier = 10.0;

        /**
         * Same-species cap around a player for the swarming species, instead
         * of spawns.maxSameSpeciesNearby, so a swarm can look like one. 0 or
         * less keeps the usual cap.
         */
        public int maxNearby = 10;

        /**
         * Swarm Pokémon the swarm's own spawner keeps within 48 blocks of a
         * player standing in its habitat, whatever else lives there. Capped by
         * maxNearby. 0 turns that spawner off, leaving only Cobblemon's
         * natural spawns (the 1.4.0 behaviour).
         */
        public int minNearby = 4;

        /**
         * Seconds between two top-ups of the swarm's own spawner, at most one
         * Pokémon per player each time. Lower fills the habitat faster after
         * catches. 1 to 300.
         */
        public int checkEverySeconds = 5;
    }

    /**
     * The legendary-class categories in swarms ({@code events.swarm.rarities}):
     * the same switch and cap as in {@code spawns}, for the swarm alone.
     */
    public static class SwarmCategories {
        /**
         * Default cap of a swarm category: none but {@code maxNearby}. A swarm
         * is meant to bring many; 1, the natural default, would end it at once.
         */
        public static final int DEFAULT_CAP = 0;

        /** Pokémon labelled {@code legendary} (Rayquaza, Mewtwo...). */
        public SpecialCategory legendary = SpecialCategory.of(false, DEFAULT_CAP);
        /** Pokémon labelled {@code mythical} (Mew, Celebi...). */
        public SpecialCategory mythical = SpecialCategory.of(false, DEFAULT_CAP);
        /** Pokémon labelled {@code ultra_beast} (Nihilego, Buzzwole...). */
        public SpecialCategory ultraBeast = SpecialCategory.of(false, DEFAULT_CAP);
        /**
         * Pokémon labelled {@code paradox} (Great Tusk, Iron Treads...). On by
         * default, as in spawns: paradox Pokémon are regular finds, not legends.
         */
        public SpecialCategory paradox = SpecialCategory.of(true, DEFAULT_CAP);
    }

    /**
     * Full moon nights: while the moon is full and it is night in the
     * Safari, Pokémon of some types come out more often in Cobblemon's
     * natural spawns. Announced to the players in the Safari at nightfall.
     */
    public static class FullMoon {
        /** Master switch. */
        public boolean enabled = true;

        /** Cobblemon type names whose Pokémon come out more. */
        public List<String> types = new ArrayList<>(List.of("fairy", "ghost"));

        /** How much more often those Pokémon are picked, within their rarity. 1 to 20. */
        public double weightMultiplier = 3.0;
    }

    /**
     * The downpour: each time it starts raining in the Safari, maybe, the
     * rain is a downpour for a while. Pokémon of the configured types come
     * out more where the rain falls (not in snow, nor in the dunes and the
     * volcano, which get no rain, nor under a roof), and catches made there
     * get the Downpour Mark. Announced to the players in the Safari. The
     * rain is the Safari's ordinary weather: nothing here makes it rain.
     */
    public static class Downpour {
        /** Master switch. */
        public boolean enabled = true;

        /**
         * Chance, from 0 to 1, that a rain starting in the Safari is a
         * downpour. Low on purpose: it rains often, an event must stay rare.
         */
        public double chance = 0.15;

        /**
         * Shortest and longest downpour, in real minutes, drawn for each one.
         * It ends sooner when the rain stops (a rain lasts about 10 to 20
         * minutes). 1 to 120, max at least min.
         */
        public int minDurationMinutes = 5;
        public int maxDurationMinutes = 10;

        /** Cobblemon type names whose Pokémon come out more. */
        public List<String> types = new ArrayList<>(List.of("water", "electric"));

        /** How much more often those Pokémon are picked, within their rarity. 1 to 20. */
        public double weightMultiplier = 2.0;
    }

    /**
     * The aurora: at each nightfall without rain in the Safari, maybe, an
     * aurora lights the frozen lands for a while, and each hunting player is
     * owed one rare Pokémon of the configured types: it appears near them as
     * soon as they stand in one of {@code habitats} or where snow falls,
     * retried until it does or the aurora ends. Its species are the Safari's
     * own uncommon, rare and ultra-rare species of those types, under the
     * natural spawns' rules; not the one the player got at their previous
     * aurora, and those they caught less often. It glows for a moment so it can be found, and gets the
     * Aurora Mark when caught. It ends when its time is up, at dawn or when
     * rain comes.
     */
    public static class Aurora {
        /** Master switch. */
        public boolean enabled = true;

        /** Chance, from 0 to 1, that a nightfall without rain brings an aurora. */
        public double chance = 0.1;

        /**
         * Shortest and longest aurora, in real minutes, drawn for each one.
         * It ends sooner at dawn (a night lasts about 7 minutes) or when rain
         * comes. 1 to 120, max at least min.
         */
        public int minDurationMinutes = 3;
        public int maxDurationMinutes = 6;

        /** Cobblemon type names of the Pokémon the aurora brings. */
        public List<String> types = new ArrayList<>(List.of("ice"));

        /** Pokémon the aurora never brings, as Cobblemon properties: "lapras", or one form, "vulpix alolan". */
        public List<String> excludeList = new ArrayList<>();

        /** Habitats where the aurora's Pokémon come, by biome id; any spot where snow falls counts too. */
        public List<String> habitats = new ArrayList<>(List.of(
                "cobblemon_safari:glacier", "cobblemon_safari:snowfield", "cobblemon_safari:taiga"));

        /**
         * Seconds the aurora's Pokémon glows when it appears, so it can be
         * found 16 to 32 blocks away, in the snow, at night. 0 for none, up to 60.
         */
        public int glowSeconds = 20;
    }

    /**
     * The heatwave: at each dawn without rain in the Safari, maybe, the day
     * bakes the hot lands for a while: Pokémon of the configured types come
     * out more in {@code habitats}. It ends when its time is up, when rain
     * comes or when night falls. It only weighs Cobblemon's own spawns: no
     * species appears that does not already live there. Announced to the
     * players in the Safari; no mark.
     */
    public static class Heatwave {
        /** Master switch. */
        public boolean enabled = true;

        /** Chance, from 0 to 1, that a dawn without rain brings a heatwave. */
        public double chance = 0.15;

        /**
         * Shortest and longest heatwave, in real minutes, drawn for each one.
         * It ends sooner at nightfall (a day lasts 10 minutes) or when rain
         * comes. 1 to 120, max at least min.
         */
        public int minDurationMinutes = 4;
        public int maxDurationMinutes = 8;

        /** Cobblemon type names whose Pokémon come out more. */
        public List<String> types = new ArrayList<>(List.of("fire", "ground"));

        /** Habitats the heatwave bakes, by biome id. */
        public List<String> habitats = new ArrayList<>(List.of("cobblemon_safari:dunes", "cobblemon_safari:volcanic",
                "cobblemon_safari:badlands", "cobblemon_safari:savanna"));

        /** How much more often those Pokémon are picked, within their rarity. 1 to 20. */
        public double weightMultiplier = 2.0;
    }

    /**
     * The storm: each time a thunderstorm starts in the Safari, maybe, rare
     * Pokémon of the configured types come to the high ground for a while:
     * the storm's own spawner brings them near the players standing in one
     * of {@code habitats} or high enough ({@code minAltitudeAboveSea}), with
     * a harmless lightning flash. They come from the Safari's own rare and
     * ultra-rare species of those types, whatever their usual habitat, under the natural spawns'
     * rules (spawns: excluded species, legendary-class categories and their
     * caps, counted apart from the natural ones). A Pokémon the storm brought
     * gets the Storm Mark when caught. The thunderstorm is the Safari's
     * ordinary weather: nothing here makes it thunder.
     */
    public static class Storm {
        /** Master switch. */
        public boolean enabled = true;

        /** Chance, from 0 to 1, that a thunderstorm starting in the Safari brings the storm event. */
        public double chance = 0.5;

        /**
         * Shortest and longest storm event, in real minutes, drawn for each
         * one. It ends sooner when the thunder stops (a thunderstorm lasts
         * about 3 to 13 minutes). 1 to 120, max at least min.
         */
        public int minDurationMinutes = 3;
        public int maxDurationMinutes = 8;

        /** Cobblemon type names of the Pokémon the storm brings. */
        public List<String> types = new ArrayList<>(List.of("electric", "dragon"));

        /** Pokémon the storm never brings, as Cobblemon properties: "dragonite", or one form, "vulpix alolan". */
        public List<String> excludeList = new ArrayList<>();

        /** Habitats where the storm brings its Pokémon, by biome id; high ground anywhere counts too. */
        public List<String> habitats = new ArrayList<>(List.of(
                "cobblemon_safari:peaks", "cobblemon_safari:rocky_hills", "cobblemon_safari:volcanic"));

        /**
         * Height above the sea, in blocks, from which any spot counts as high
         * ground, for the island as generated in "ROLLING" relief: it is
         * scaled with the map's relief (about 16 blocks on a "FLAT" map, 72 on
         * a "MOUNTAINOUS" one). 0 makes the whole island count.
         */
        public int minAltitudeAboveSea = 45;

        /** Seconds between two spawn attempts around each player. 1 to 300. */
        public int checkEverySeconds = 15;

        /** Chance, from 0 to 1, that an attempt around a player brings a Pokémon. */
        public double spawnChance = 0.25;

        /** Most storm Pokémon around a player (within 64 blocks); none is brought beyond. */
        public int maxNearby = 2;

        /** A harmless lightning flash where a storm Pokémon appears (no damage, no fire). */
        public boolean lightning = true;

        /**
         * Shortest time between two of those flashes in the whole Safari, in
         * seconds, so several players do not set off a barrage. 0 to 600.
         */
        public int lightningCooldownSeconds = 30;
    }

    /**
     * Which species one way into the Safari lets through: the four special
     * categories and a list of excluded species. Shared by {@link Spawns}
     * (Cobblemon's natural spawns) and {@link Specials} (the board and the
     * mod's own spawner), which each carry their own copy, so a legendary can
     * be on the board while staying out of the natural spawns, or the reverse.
     *
     * <p>A base class rather than a nested section so the JSON keys stay
     * where they always were ({@code spawns.legendary}, not
     * {@code spawns.rules.legendary}): Gson writes inherited fields into the
     * same object.
     */
    public abstract static class SpeciesRulesSection {
        /**
         * Defaults of the paradox category; see {@link #paradox}. Changing
         * them only affects new config files and files without a paradox
         * section: an existing section keeps what it says.
         */
        public static final boolean PARADOX_DEFAULT_ALLOWED = true;
        public static final int PARADOX_DEFAULT_CAP = 0;

        /** Pokémon labelled {@code legendary} (Rayquaza, Mewtwo...). */
        public SpecialCategory legendary = new SpecialCategory();
        /** Pokémon labelled {@code mythical} (Mew, Celebi...). */
        public SpecialCategory mythical = new SpecialCategory();
        /** Pokémon labelled {@code ultra_beast} (Nihilego, Buzzwole...). */
        public SpecialCategory ultraBeast = new SpecialCategory();
        /**
         * Pokémon labelled {@code paradox} (Great Tusk, Iron Treads...).
         * Allowed and uncapped by default, unlike the other categories: they
         * are ordinary wild Pokémon in their own games, not one-of-a-kind
         * legends.
         */
        public SpecialCategory paradox = SpecialCategory.of(PARADOX_DEFAULT_ALLOWED, PARADOX_DEFAULT_CAP);

        /** Species ids that never come in this way, e.g. {@code cobblemon:ditto}. */
        public List<String> excludedSpecies = new ArrayList<>();
    }

    /**
     * Cobblemon's natural spawns inside the Safari. The special Pokémon of
     * the day follow {@link Specials} instead, categories included.
     *
     * <p>Cobblemon's own spawner populates the Safari through the biome tag
     * bridge ({@code data/cobblemon/tags/worldgen/biome}): each habitat gets the
     * Pokémon that naturally live in the matching Cobblemon biomes, including
     * those added by spawn datapacks. The categories and exclusions inherited
     * from {@link SpeciesRulesSection} take a species out of Cobblemon's draw
     * itself, so a forbidden species with a huge weight does not starve the
     * others (see spawn.SafariSpawnFilter).
     */
    public static class Spawns extends SpeciesRulesSection {
        /**
         * Lets Cobblemon's natural spawner work in the Safari. When false, only the
         * daily special Pokémon appear, which makes the park very quiet.
         */
        public boolean nativeSpawns = true;

        /**
         * A natural spawn is cancelled when this many Pokémon of the same species
         * already roam within NativeSpawnRules.SAME_SPECIES_RADIUS. Keeps one
         * common species from flooding a habitat. 0 disables the cap.
         */
        public int maxSameSpeciesNearby = 4;

        /**
         * How much faster Cobblemon's spawner ticks for players in the Safari.
         * 2 makes a spawn attempt twice as often; 1 is Cobblemon's normal pace.
         * Cost grows with it: each attempt scans a zone of blocks.
         */
        public double spawnRateMultiplier = 2.0;

        /**
         * Multiplies Cobblemon's Pokémon-per-chunk cap for players in the
         * Safari, so faster spawning is not wasted on a full cap. 1 keeps
         * Cobblemon's value.
         */
        public double pokemonPerChunkMultiplier = 2.0;

        /**
         * Weight, in Cobblemon's natural spawns around a player, of the species
         * that player has already caught in the Safari, against 1 for species
         * they have not: 0.3 makes a new species more than three times as likely
         * as a caught one of the same weight. Caught species still appear, only
         * less often; lower values make them feel gone. 1 turns it off; 0 stops
         * them entirely while anything else can spawn. Acts within Cobblemon's
         * rarity buckets, so a habitat whose species are all caught looks the
         * same.
         */
        public double caughtSpeciesWeight = SafariConfigManager.DEFAULT_CAUGHT_SPECIES_WEIGHT;

        /**
         * Distance from a Poké Snack, in blocks, within which no natural
         * Pokémon spawns in the Safari, so the snack's own spawner has room
         * to bring some. 16 is twice the snack's spawn radius. Larger empties
         * more of the surroundings (and searches a little further for
         * snacks each second); smaller leaves natural Pokémon close enough to
         * fill the snack's count again. 0 turns the clearing off. 0 to 32.
         */
        public int snackClearRadius = SafariConfigManager.DEFAULT_SNACK_CLEAR_RADIUS;

        /**
         * Cap of a Poké Snack's spawner in the Safari, as a multiple of the
         * Safari's density (Cobblemon's Pokémon per chunk times
         * pokemonPerChunkMultiplier). Above 1 the snack still brings
         * Pokémon in a full area; higher packs more around it. 1 to 4.
         */
        public double snackDensityMultiplier = SafariConfigManager.DEFAULT_SNACK_DENSITY_MULTIPLIER;
    }

    /**
     * Whether, and how often, one category of special Pokémon (legendary,
     * mythical, ultra beast, paradox) may come into the Safari through one
     * way: {@code spawns} for Cobblemon's natural spawns, {@code specials}
     * for the board and the mod's spawner. Each way keeps its own count.
     */
    public static class SpecialCategory {
        /** Default {@link #maxSpawnPerPokemon} of every category but paradox. */
        public static final int DEFAULT_CAP = 1;
        /** Pokémon of this category may appear at all. */
        public boolean allowSpawn = false;

        /**
         * Most Pokémon of each species of this category per rotation of the
         * specials (specials.rotationMinutes) coming in this way, counting
         * those caught in the Safari plus those still roaming: with 2, once
         * two Rayquaza from this way are caught, no third shows up through it
         * before the next rotation. The other way counts separately, so caps
         * of 1 in both sections allow two Rayquaza per rotation in all. One
         * that leaves uncaught (despawned, fled, knocked out) gives its place
         * back within about ten seconds, so another player gets a chance.
         * 0 lifts the cap. A species in two categories follows the lower cap.
         */
        public int maxSpawnPerPokemon = DEFAULT_CAP;

        /**
         * A category with other defaults than the no-argument constructor's.
         * Gson still uses that constructor for a section present in the file,
         * so a section written without one of the keys gets the plain
         * defaults (not allowed, DEFAULT_CAP), not these.
         */
        public static SpecialCategory of(boolean allowSpawn, int cap) {
            SpecialCategory category = new SpecialCategory();
            category.allowSpawn = allowSpawn;
            category.maxSpawnPerPokemon = cap;
            return category;
        }
    }

    /**
     * Daily regeneration of the Safari map. At the first server start of a new
     * day the Safari dimension is deleted and regenerated with a new layout.
     * Everything built or dropped inside is lost; sessions, statistics and
     * banked time are kept (they live with the overworld).
     */
    public static class Reset {
        /** Master switch. When false, the map is kept forever. */
        public boolean enabled = true;

        /**
         * Hour (0 to 23) at which a new map day starts. A server restarted at
         * 3:00 with hour 4 still belongs to the previous day and keeps its map.
         */
        public int hour = 4;

        /** Time zone of {@link #hour}, e.g. "Europe/Paris"; empty for the server's own. */
        public String timeZone = "";

        /**
         * How hilly each new map is: FLAT, GENTLE (the default), ROLLING (the
         * island as it was before 1.4.0) or MOUNTAINOUS. Only new maps take
         * it: the map on disk keeps the relief it was generated with, or new
         * chunks would rise as cliffs against the old ones.
         */
        public IslandRelief relief = IslandRelief.GENTLE;
    }

    /**
     * Safari Gates: rare structures of the overworld, where players enter the
     * Safari with their banked time. How often they generate is set by the
     * datapack ({@code data/cobblemon_safari/worldgen/structure_set/safari_gate.json}),
     * not here: worldgen is fixed once the chunks exist.
     */
    public static class Gate {
        /**
         * Standing in a gate (like a nether portal), or asking its warden,
         * starts a hunt paid with banked time. When false, gates are
         * decoration only.
         */
        public boolean allowEntry = true;

        /**
         * Keeps a Safari Warden next to each generated gate, who explains how
         * entry works. Gates placed by hand never have one (see
         * SafariGateBlockEntity.KEEPS_WARDEN_KEY). One removed with /kill
         * comes back within a few seconds while a player is near.
         */
        public boolean spawnWarden = true;

        /**
         * The warden's Pokémon roaming around each gate, as Cobblemon
         * properties (one entry per Pokémon). They cannot be caught, battled
         * or hurt. Empty for none.
         */
        public List<String> companions = new ArrayList<>(List.of(
                "kangaskhan level=30",
                "tauros level=30",
                "chansey level=30"
        ));

        /**
         * How far, in blocks, the warden's Pokémon may wander from the gate
         * before being walked back (teleported beyond twice this). The warden
         * keeps to a few blocks around his spot, whatever this says.
         */
        public int roamRadius = 8;
    }

    /**
     * Safari points: a currency separate from Safari time, earned with every
     * capture inside the Safari and spent at the warden's shop
     * ({@code config/cobblemon_safari/shop.json}).
     */
    public static class Points {
        /** Master switch: when false, captures earn nothing (the shop still sells for points already earned). */
        public boolean enabled = true;

        /**
         * Points for a capture, by the species' rarity in the Safari (its
         * Cobblemon spawn bucket). Sized with the missions for about 600
         * points per hour of active hunting, which buys a first-stage Pokémon
         * of the shop in about a quarter of an hour.
         */
        public CatchPoints perCatch = new CatchPoints();

        /** Extra points when the species is one of the specials of the rotation. */
        public int specialBonus = 50;

        /** Extra points for a shiny. */
        public int shinyBonus = 250;
    }

    /** Points for a capture by rarity ({@code points.perCatch}). */
    public static class CatchPoints {
        public int common = 10;
        public int uncommon = 20;
        public int rare = 50;
        public int ultraRare = 120;
    }

    /**
     * The missions of each rotation: {@link #count} drawn at random among the
     * {@link #allowedKinds}, the same for every player, each paid once per
     * player. Every kind has its own tuning in {@link #kinds}; the species
     * the missions name are drawn under {@link #speciesFilter}, and a player
     * may swap a mission at the clerk ({@link #swap}).
     */
    public static class Missions {
        /** Master switch for the missions, their rewards and their page on the board. */
        public boolean enabled = true;

        /** Missions per rotation, 0 to 8. More missions mean more points per day; the board shows them all. */
        public int count = 3;

        /**
         * The kinds of missions that may be drawn. A rotation draws different
         * kinds first, and the same kind twice only when fewer kinds than
         * {@link #count} can be drawn. A kind that cannot be met this rotation
         * (no specials, no species for a habitat) is skipped.
         */
        public List<BountyKind> allowedKinds = new ArrayList<>(List.of(BountyKind.SPECIES, BountyKind.TYPE,
                BountyKind.SPECIAL, BountyKind.HABITAT, BountyKind.ANY, BountyKind.DIFFERENT,
                BountyKind.OUT_OF_BATTLE, BountyKind.NIGHT, BountyKind.EVOLVED, BountyKind.LEVEL, BountyKind.FISHING));

        /** Extra points once every mission of the rotation is done. */
        public int allMissionsBonusPoints = 200;

        /** The tuning of each kind: how often it is drawn, how many catches it asks, what it pays. */
        public BountyKinds kinds = new BountyKinds();

        /** Which species the missions may name, and which count toward the type and habitat missions' pools. */
        public BountySpeciesFilter speciesFilter = new BountySpeciesFilter();

        /** Swapping a mission at the rest house clerk. */
        public BountySwap swap = new BountySwap();
    }


    /**
     * The Escape Rope: lent on entry, taken back on exit. Used in the
     * Safari, it offers to climb back to the open air (from a cave) or to go
     * to the nearest rest house. It never wears out.
     */
    public static class EscapeRope {
        /** Lends one rope to every player entering. */
        public boolean enabled = true;

        /**
         * The rope's "nearest rest house" option, with the trip's cooldown
         * and cinematic of {@code restHouses}. Climbing to the open air stays.
         */
        public boolean toRestHouse = true;
    }

    /** The rest houses: their building on a new map, and the trips to them. */
    public static class RestHouses {
        /**
         * Builds every rest house of a new map in the background, one at a
         * time, from shortly after the server starts, so trips rarely wait
         * for a house to build. Each house generates its few dozen chunks
         * off the server thread, then places its blocks over a few ticks.
         * When false, a house is built the first time a player goes near it
         * or travels to it.
         */
        public boolean prebuild = true;

        /** The trips between the houses. */
        public Travel travel = new Travel();
    }

    /**
     * The trips to a rest house, by its Safari Guide or by the Escape Rope.
     * Every trip is filmed while the destination loads (the player's double
     * flies, burrows or swims there, or a painted scene when that cannot be
     * staged; not a setting), and builds the house first if it is not
     * standing yet.
     */
    public static class Travel {
        /** The Safari Guide of every rest house; when false, the houses have no guide. */
        public boolean guide = true;

        /**
         * Seconds before the same player can travel to a rest house again,
         * by the guide or by the rope: one wait for both, or a player could
         * rope to a house and take its guide straight on. 0 removes the wait.
         */
        public int cooldownSeconds = SafariConfigManager.DEFAULT_TRAVEL_COOLDOWN_SECONDS;

        /**
         * Shortest length of the loading cinematic, in seconds, even when the
         * destination is ready at once. Longer when the destination must load
         * or be built first. 0 teleports as soon as it is ready.
         */
        public int cinematicSeconds = SafariConfigManager.DEFAULT_TRAVEL_CINEMATIC_SECONDS;
    }

    /** Catch rate, marks, and Pokémon running away inside the Safari. */
    public static class Capture {
        /**
         * Multiplies the species catch rate when the loaned ball ({@link
         * Loan#ballItem}) hits a wild Pokémon that is NOT in a battle, inside the
         * Safari only. Cobblemon halves every capture made outside a battle, so 3
         * means the Safari Ball out of battle works 1.5 times as well as a normal
         * throw in battle (before Cobblemon's own 1.5 Safari Ball bonus). 1
         * disables it.
         */
        public double safariBallOutOfBattleMultiplier = 3.0;

        /** The marks given to Pokémon caught in the Safari, each with its switch. */
        public Marks marks = new Marks();

        /** Wild Pokémon running away after a failed throw. */
        public Flee flee = new Flee();
    }

    /**
     * The Safari's marks, shown in a caught Pokémon's summary. False stops
     * giving that mark; marks already given stay. The event marks come on
     * top of the Safari Mark, and their title takes the place of its title.
     */
    public static class Marks {
        /** "the Safari Find": every Pokémon caught in the Safari. */
        public boolean safari = true;
        /** "the Star of the Day": one of the specials of the rotation. */
        public boolean special = true;
        /** "of the Swarm": the swarming species, caught in the swarm's habitat. */
        public boolean swarm = true;
        /** "of the Full Moon": caught on a full moon night. */
        public boolean fullMoon = true;
        /** "of the Downpour": caught where a downpour falls. */
        public boolean downpour = true;
        /** "of the Storm": a Pokémon the storm brought. */
        public boolean storm = true;
        /** "of the Aurora": a Pokémon an aurora brought. */
        public boolean aurora = true;
        /** "the Safari Catch": fished with a rod in the Safari, then caught. */
        public boolean angler = true;
    }

    /**
     * After a ball fails on a wild Pokémon outside of a battle, inside the
     * Safari, the Pokémon may run away (it vanishes in a puff), as in the
     * Safari Zone of the games. The chance depends on how rare the species
     * is in the Safari, and grows with each ball that already failed on it.
     * Never in a battle: Cobblemon handles fleeing there.
     */
    public static class Flee {
        /** Master switch. False: no Pokémon ever runs away after a failed ball. */
        public boolean enabled = true;

        /**
         * Added to the chance for each ball that already failed on the same
         * Pokémon: 0.05 makes the fourth failed ball on a rare one a 25 %
         * risk. 0 keeps the chance flat. Shared by every rarity.
         */
        public double perFailedBall = 0.05;

        /**
         * Highest chance, whatever the rarity and the failed balls: keeps a
         * long chase possible. Shared by every rarity. Higher lets a long
         * chase end in a flight more often than not.
         */
        public double maxChance = 0.35;

        /** Whether shiny Pokémon may run away. */
        public boolean shinies = false;

        /**
         * Whether, and how likely, each kind of Pokémon runs away: the four
         * rarities of the Safari, then the four legendary-class categories,
         * which take precedence over the rarity.
         */
        public FleeRarities rarities = new FleeRarities();
    }

    /**
     * The eight entries of {@code capture.flee.rarities}. The four rarities
     * are the Safari's own (habitat index; a species it does not know counts
     * as common). The four categories are Cobblemon's labels: a Pokémon that
     * carries one follows that entry only, never its rarity's, so a
     * legendary the Safari lists as ultra-rare follows {@link #legendary}.
     */
    public static class FleeRarities {
        public FleeRarity common = new FleeRarity(true, 0.05);
        public FleeRarity uncommon = new FleeRarity(true, 0.08);
        public FleeRarity rare = new FleeRarity(true, 0.10);
        public FleeRarity ultraRare = new FleeRarity(true, 0.12);
        public FleeRarity legendary = new FleeRarity(false, 0.25);
        public FleeRarity mythical = new FleeRarity(false, 0.25);
        public FleeRarity ultraBeast = new FleeRarity(false, 0.20);
        public FleeRarity paradox = new FleeRarity(false, 0.18);
    }

    /**
     * One entry of {@code capture.flee.rarities}. Boxed so that a key left
     * out of a hand-written entry reads as null and the config manager can
     * put that entry's own default back, rather than Gson's false and 0.
     */
    public static class FleeRarity {
        /** Whether these Pokémon may run away at all. */
        public Boolean enabled;

        /**
         * Chance, from 0 to 1, that one of them runs away after a failed
         * ball, before {@link Flee#perFailedBall} and {@link Flee#maxChance}.
         */
        public Double chance;

        /** For Gson: an entry whose keys are all missing, filled in by validation. */
        public FleeRarity() {
        }

        /** An entry with its default values. */
        public FleeRarity(boolean enabled, double chance) {
            this.enabled = enabled;
            this.chance = chance;
        }
    }


    /**
     * Special Pokémon of the rotation: a handful of species drawn from what
     * is installed (Cobblemon and datapacks), shown on the wall screens and
     * spawned in their habitat by the mod itself.
     *
     * <p>The categories and exclusions inherited from
     * {@link SpeciesRulesSection} decide which species the board may draw and
     * the mod's spawner may bring in; Cobblemon's natural spawns follow
     * {@link Spawns} instead.
     */
    public static class Specials extends SpeciesRulesSection {
        /** Master switch for the rotation, the board content and the special spawner. */
        public boolean enabled = true;

        /**
         * Length of one rotation, in real minutes (240 = four hours, 1440 =
         * one day). Rotations are aligned on midnight UTC when the length
         * divides a day, so 240 changes the specials at 0, 4, 8, 12, 16 and
         * 20 h UTC. The missions are redrawn with each rotation, so a shorter
         * rotation also means more mission sets (and bonuses) per day.
         * Changing it starts a new draw right away.
         */
        public int rotationMinutes = SafariConfigManager.DEFAULT_ROTATION_MINUTES;

        /** Species drawn per rotation, by rarity. */
        public SpecialSlots slots = new SpecialSlots();

        /** The mod's spawner, which brings the specials into their habitat around the players. */
        public SpecialSpawn spawn = new SpecialSpawn();
    }

    /**
     * Species drawn per rotation, by rarity ({@code specials.slots}).
     * Weighted toward the rare end: the specials are what is worth a trip,
     * and the board shows six of them at most comfortably on a 4 x 2 screen.
     * A rare or ultra-rare slot its rarity cannot fill goes to the next more
     * common one, down to uncommon; commons only fill their own slots, so 0
     * keeps them off the board whatever happens.
     */
    public static class SpecialSlots {
        /** Common species per rotation. 0 by default: a common Pokémon is no reason for a trip. */
        public int common = 0;
        /** Uncommon species per rotation. */
        public int uncommon = 1;
        /** Rare species per rotation. */
        public int rare = 2;
        /** Ultra-rare species per rotation. Legendaries land here when allowed. */
        public int ultraRare = 3;
    }

    /** The specials' own spawner ({@code specials.spawn}). */
    public static class SpecialSpawn {
        /** Seconds between two spawn attempts around each player in the Safari. */
        public int checkEverySeconds = 15;

        /** Chance that an attempt goes ahead, between 0 and 1. */
        public double chance = 0.35;

        /**
         * Special Pokémon allowed around one player at once (within
         * FeaturedSpawner.NEARBY_RADIUS). Also caps each species to one around
         * the player, so no special takes over.
         */
        public int maxAroundPlayer = 2;

        /**
         * Which special comes when an attempt goes ahead: each special's
         * chance is its rarity's weight against the others'. 10 / 4 / 1
         * brings an uncommon one ten times as often as an ultra-rare one.
         */
        public SpawnWeights weights = new SpawnWeights();
    }

    /**
     * Relative weights of the four rarities: of the specials in their spawner
     * ({@code specials.spawn.weights}), and of the buckets in the swarm's draw
     * ({@code events.swarm.weights}). Same defaults for both.
     */
    public static class SpawnWeights {
        /**
         * Weight of common: of a common special, which only matters when
         * {@code specials.slots.common} draws some; of a common swarm.
         */
        public int common = 0;
        public int uncommon = 10;
        public int rare = 4;
        public int ultraRare = 1;
    }

    /**
     * Safari Balls handed out on entry and taken back on exit, like the 30 balls
     * of the original Safari Zones. Off by default: players bring their own
     * allowed balls, and the shop gives free Safari Balls once a day; kept for
     * servers that want the games' rule.
     *
     * <p>Loaned balls carry a marker, cannot be thrown outside the Safari, and
     * are removed from the inventory as soon as the player is outside: they
     * can never be farmed by entering and leaving repeatedly.
     */
    public static class Loan {
        /** Balls handed out per session. 0 (the default) disables the loan; players then bring their own allowed balls. */
        public int count = 0;

        /**
         * Item id of the loaned ball. It is always usable inside the Safari,
         * even if it is missing from {@code balls.allowed}.
         */
        public String ballItem = "cobblemon:safari_ball";
    }

    /**
     * Safari Tickets added to the loot of overworld chests, a reward for
     * exploring. Read when loot tables load: change it, then /reload (or
     * restart) for chests generated afterwards; chests already opened keep
     * what they held.
     */
    public static class TicketLoot {
        /** Master switch. */
        public boolean enabled = true;

        /**
         * Loot tables that may hold a ticket, each with its chance per chest
         * (0 to 1). An id ending in {@code /} covers every table under that
         * folder, e.g. {@code cobblemon:ruins/common/} for every Cobblemon
         * ruin. Tables of other mods work too. Chests inside the Safari
         * itself never hold one, whatever is listed: its map is rebuilt
         * daily, and its ruins would otherwise hand out tickets forever.
         */
        public List<Source> sources = new ArrayList<>(List.of(
                // Every village chest (16 tables: houses, smiths, temple, fisher...), not only
                // the houses: a village holds several chests, so a few percent each is enough;
                // much higher and tickets get common.
                Source.of("minecraft:chests/village/", 0.04),
                // Savanna houses keep a little more, as before: their villages are smaller.
                Source.of("minecraft:chests/village/village_savanna_house", 0.10),
                Source.of("minecraft:chests/desert_pyramid", 0.12),
                Source.of("minecraft:chests/jungle_temple", 0.12),
                Source.of("cobblemon:ruins/common/", 0.18)
        ));

        /**
         * The defaults up to 1.2 (config version 4), recognised on load: a
         * file still holding exactly these gets the new defaults
         * (SafariConfigManager.migrateTicketLoot). Never change it.
         */
        static final List<Source> PRE_1_3_DEFAULTS = List.of(
                Source.of("minecraft:chests/village/village_plains_house", 0.05),
                Source.of("minecraft:chests/village/village_savanna_house", 0.08),
                Source.of("minecraft:chests/village/village_desert_house", 0.05),
                Source.of("minecraft:chests/village/village_taiga_house", 0.05),
                Source.of("minecraft:chests/village/village_snowy_house", 0.05),
                Source.of("minecraft:chests/desert_pyramid", 0.10),
                Source.of("minecraft:chests/jungle_temple", 0.10),
                Source.of("cobblemon:ruins/common/", 0.15));

        /** One loot table (or folder of tables) and its chance. */
        public static class Source {
            /** Loot table id, or a folder ending in {@code /}. */
            public String table = "";
            /** Chance per chest, 0 to 1. */
            public double chance = 0.0;

            /** Gson needs a no-argument constructor. */
            public Source() {
            }

            /** A source with the given table and chance, for the defaults. */
            static Source of(String table, double chance) {
                Source source = new Source();
                source.table = table;
                source.chance = chance;
                return source;
            }
        }
    }

    /**
     * The Safari Ticket: the built-in item access method, and the time bank
     * it fills. A ticket may carry its own duration in its custom data
     * ({@code duration_seconds}), so tickets sold at different prices can
     * grant different hunts.
     */
    public static class Tickets {
        /** Time a Safari Ticket adds, unless the ticket carries its own (custom data). */
        public int defaultDurationSeconds = 30 * 60;

        /**
         * Fewest seconds a ticket may add. When the bank or the hunt is
         * nearly full, a ticket adds only what fits; if that is less than
         * this, it is refused and the player keeps it, rather than burning a
         * ticket for a few seconds. 0 accepts any amount.
         */
        public int minAddedSeconds = 20 * 60;

        /**
         * Using a ticket while already inside adds its duration to the
         * current hunt (capped by {@code hunt.maxDurationSeconds}) instead of
         * being refused.
         */
        public boolean addToCurrentHunt = true;

        /**
         * Most Safari time a player can bank with tickets, in seconds. Tickets
         * used outside the Safari add to this bank; entering spends it.
         */
        public int maxBankedSeconds = 4 * 60 * 60;

        /**
         * Gives the unused time of a hunt paid with banked time back to the bank
         * when the player leaves on purpose (through the clerk), dies inside,
         * or is taken out by other means (a /tp, a home or waystone command).
         * Time running out or an operator ending the hunt never refund.
         */
        public boolean refundUnusedTimeOnLeave = true;

        /** Safari Tickets found in overworld chests. */
        public TicketLoot loot = new TicketLoot();
    }

    /**
     * Protection rules. Creative and spectator players are exempt from the
     * terrain rules when {@link Hunt#exemptCreativeAndSpectator} is on, so
     * builders can still work on the park.
     */
    public static class Protection {
        /**
         * Players inside take no damage. Damage that bypasses invulnerability
         * (/kill, falling out of the world) still applies, otherwise a player
         * in the void would fall forever.
         */
        public boolean invulnerablePlayers = true;

        /**
         * Pokémon inside take no damage: wild ones cannot be killed with a
         * sword or a bow, and sent-out ones are spared falls, lava and mobs.
         * Battles are not affected (their damage is not the entity's). Damage
         * that bypasses invulnerability (/kill, the void) still applies.
         */
        public boolean invulnerablePokemon = true;

        /**
         * Whether hunger is frozen for players inside. On, the food bar and
         * its saturation stay where they were on entering (eating still fills
         * them), so a timed hunt is not spent eating. Off (the default), the
         * bar goes down as it does everywhere else.
         */
        public boolean hunger = false;

        /**
         * Players cannot break blocks, place blocks, empty or fill buckets, or
         * use tools that change blocks (hoe, shovel, axe stripping, flint and
         * steel, bone meal). Placement is included on purpose: breaking alone
         * leaves TNT and lava buckets as a way to wreck the terrain. Also stops
         * fire from spreading or burning blocks, and explosions from breaking
         * any: protecting blocks from players while fire eats them would make
         * no sense.
         */
        public boolean protectBlocks = true;

        /**
         * Blocks players may still place, and break again, while
         * {@link #protectBlocks} is on. Default: Cobblemon's Poké Cake and
         * Poké Snack, the baits that attract wild Pokémon. Anything placed
         * is lost at the daily map reset.
         */
        public List<String> placeableBlocks = new ArrayList<>(List.of(
                "cobblemon:poke_cake",
                "cobblemon:poke_snack"
        ));

        /**
         * Cobblemon plants players may harvest despite {@link #protectBlocks},
         * one switch each. Only read while the terrain is protected.
         */
        public Interact interact = new Interact();
    }

    /**
     * The switches of {@code protection.interact}. The defaults keep what
     * the protection always allowed: plants picked with a click (berries,
     * apricorns, Galarica nuts) stay pickable, plants harvested by breaking
     * them stay protected, so they are there for every player until the
     * next map. Roots are the exception: shears used to strip them, a hole
     * in the protection, now closed unless {@link #roots} is on.
     */
    public static class Interact {
        /** Picking ripe berries with a right-click. The tree is never broken. */
        public boolean berries = true;
        /** Picking ripe apricorns with a right- or left-click. The tree is never broken. */
        public boolean apricorns = true;
        /** Picking ripe Galarica nuts with a right-click. The bush is never broken. */
        public boolean galaricaNuts = true;
        /** Breaking mint plants, which is how they are harvested. Gone until the next map. */
        public boolean mints = false;
        /** Breaking Revival Herbs. Gone until the next map. */
        public boolean revivalHerbs = false;
        /** Breaking Medicinal Leeks. Gone until the next map. */
        public boolean medicinalLeeks = false;
        /** Breaking Vivichokes. Gone until the next map. */
        public boolean vivichokes = false;
        /** Breaking Hearty Grains. Gone until the next map. */
        public boolean heartyGrains = false;
        /** Breaking Bugwort. Gone until the next map. */
        public boolean bugwort = false;
        /**
         * Shearing big and energy roots (which leaves a smaller root) and
         * breaking them.
         */
        public boolean roots = false;
    }
}
