package dev.darcosse.common.cobblemonsafari.config;

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

    /**
     * Hard cap on the remaining time; adding time never goes above it. Also
     * the most banked time one entry spends, the rest staying in the bank.
     */
    public int maxDurationSeconds = 2 * 60 * 60;

    /** Poké Ball ids (as in Cobblemon, e.g. {@code cobblemon:safari_ball}) usable inside the Safari. */
    public List<String> allowedBalls = new ArrayList<>(List.of(
            "cobblemon:safari_ball",
            "cobblemon:master_ball"
    ));

    /**
     * When true the clock only runs while the player is online and inside.
     * When false it keeps running while they are offline, and they are escorted
     * out on their next login if it ran out.
     */
    public boolean pauseTimeWhileOffline = true;

    /** Whether Pokémon battles may start inside the Safari. The games forbid them. */
    public boolean allowBattles = true;

    /**
     * Extra time a player may stay after their clock hits zero when they are in
     * the middle of a battle, so the battle is not torn apart by a teleport.
     */
    public int battleGraceSeconds = 120;

    /** Where players go when their session ends. */
    public ExitDestination exitDestination = ExitDestination.RETURN_POINT;

    /**
     * Creative and spectator players may enter and roam without a session
     * (building, moderation). Not on login: a player who logs in inside
     * without a session is sent out whatever their game mode.
     */
    public boolean exemptCreativeAndSpectator = true;

    /**
     * Remaining times, in seconds, at which the player gets a warning in
     * chat. Its colour follows the time left, whatever the list holds:
     * white, yellow from 5 minutes, red from 1 minute
     * (SafariSessionManager.WARNING_YELLOW_SECONDS and WARNING_RED_SECONDS).
     */
    public List<Integer> warningThresholdsSeconds = new ArrayList<>(List.of(900, 600, 300, 60, 30, 10));

    /** What players can and cannot do to themselves and to the terrain inside the Safari. */
    public Protection protection = new Protection();

    /** Safari Balls lent at the entrance, as in the games. */
    public Loan loan = new Loan();

    /** Catch rate adjustments inside the Safari. */
    public Capture capture = new Capture();

    /** Daily regeneration of the Safari map. */
    public Reset reset = new Reset();

    /** Behaviour of the Safari Ticket item. */
    public Ticket ticket = new Ticket();

    /** Safari Tickets found in overworld chests. */
    public TicketLoot ticketLoot = new TicketLoot();

    /** The Safari Gates found in the overworld: entry, warden and roaming Pokémon. */
    public Gate gate = new Gate();

    /** Safari points earned by catching Pokémon, spent at the warden's shop. */
    public Points points = new Points();

    /** The Escape Rope lent at the entrance, to get out of caves. */
    public EscapeRope escapeRope = new EscapeRope();

    /** Travel to the rest houses: by the Safari Guide of each house, and by the Escape Rope. */
    public RestHouseTravel restHouseTravel = new RestHouseTravel();

    /** Daily missions shown on the wall screens, paid in Safari points. */
    public Bounties bounties = new Bounties();

    /** Cobblemon's natural spawns inside the Safari: which Pokémon may come in, and how many. */
    public Spawns spawns = new Spawns();

    /** The daily rotation of special Pokémon, its board and the spawner that brings them in. */
    public Featured featured = new Featured();

    /**
     * Which species one way into the Safari lets through: the four special
     * categories and a list of excluded species. Shared by {@link Spawns}
     * (Cobblemon's natural spawns) and {@link Featured} (the board and the
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

        /**
         * Copies another section's categories and exclusions into this one.
         * Used once, to carry the rules of a pre-split file over to the
         * section that did not exist yet; the copies are independent.
         */
        public void copyRulesFrom(SpeciesRulesSection other) {
            legendary = other.legendary.copy();
            mythical = other.mythical.copy();
            ultraBeast = other.ultraBeast.copy();
            paradox = other.paradox.copy();
            excludedSpecies = new ArrayList<>(other.excludedSpecies);
        }
    }

    /**
     * Cobblemon's natural spawns inside the Safari. The special Pokémon of
     * the day follow {@link Featured} instead, categories included.
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
         * they have not: 0.4 makes a new species two and a half times as likely
         * as a caught one of the same weight. Caught species still appear, only
         * less often; lower values make them feel gone. 1 turns it off; 0 stops
         * them entirely while anything else can spawn. Acts within Cobblemon's
         * rarity buckets, so a habitat whose species are all caught looks the
         * same.
         */
        public double caughtSpeciesWeight = SafariConfigManager.DEFAULT_CAUGHT_SPECIES_WEIGHT;
    }

    /**
     * Whether, and how often, one category of special Pokémon (legendary,
     * mythical, ultra beast, paradox) may come into the Safari through one
     * way: {@code spawns} for Cobblemon's natural spawns, {@code featured}
     * for the board and the mod's spawner. Each way keeps its own count.
     */
    public static class SpecialCategory {
        /** Default {@link #maxSpawnPerPokemon} of every category but paradox. */
        public static final int DEFAULT_CAP = 1;
        /** Pokémon of this category may appear at all. */
        public boolean allowSpawn = false;

        /**
         * Most Pokémon of each species of this category per rotation of the
         * specials (featured.rotationMinutes) coming in this way, counting
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

        /** An independent copy, so two sections never share one object. */
        public SpecialCategory copy() {
            return of(allowSpawn, maxSpawnPerPokemon);
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
         * Cobblemon spawn bucket). Sized with the bounties for about 600
         * points per hour of active hunting, which buys a first-stage Pokémon
         * of the shop in about a quarter of an hour.
         */
        public int common = 10;
        public int uncommon = 20;
        public int rare = 50;
        public int ultraRare = 120;

        /** Extra points when the species is one of today's specials. */
        public int featuredBonus = 50;

        /** Extra points for a shiny. */
        public int shinyBonus = 250;
    }

    /**
     * The missions of each rotation: {@link #count} drawn at random among the
     * {@link #allowedKinds}, the same for every player, each paid once per
     * player. Every kind has its own tuning in {@link #kinds}; the species
     * the missions name are drawn under {@link #speciesFilter}, and a player
     * may swap a mission at the clerk ({@link #swap}).
     */
    public static class Bounties {
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
                BountyKind.FEATURED, BountyKind.HABITAT, BountyKind.ANY, BountyKind.DIFFERENT,
                BountyKind.OUT_OF_BATTLE, BountyKind.NIGHT, BountyKind.EVOLVED, BountyKind.LEVEL));

        /** Extra points once every mission of the rotation is done. */
        public int allDoneBonus = 200;

        /** The tuning of each kind: how often it is drawn, how many catches it asks, what it pays. */
        public BountyKinds kinds = new BountyKinds();

        /** Which species the missions may name, and which count toward the type and habitat missions' pools. */
        public BountySpeciesFilter speciesFilter = new BountySpeciesFilter();

        /** Swapping a mission at the rest house clerk. */
        public BountySwap swap = new BountySwap();

        /** Former reward of a type mission, per catch; moved to {@code kinds.type.rewardPerCatch} (config version 4). */
        @Deprecated
        public Integer typeRewardPerCatch = null;
        /** Former reward of a species mission; moved to {@code kinds.species.reward} (config version 4). */
        @Deprecated
        public Integer speciesReward = null;
        /** Former reward of a specials mission; moved to {@code kinds.featured.reward} (config version 4). */
        @Deprecated
        public Integer featuredReward = null;
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
         * Former home of {@link RestHouseTravel#cooldownSeconds}, read once
         * by the config manager to carry an older file's value over (config
         * version 2), then cleared. Null in any current file: Gson leaves
         * null fields out when writing, so the key disappears.
         */
        @Deprecated
        public Integer restHouseCooldownSeconds = null;
    }

    /**
     * Travel to the rest houses. Every trip shows a short loading cinematic
     * while the destination loads, and builds itself first if nobody went
     * near it on this map yet.
     */
    public static class RestHouseTravel {
        /** The Safari Guide of every rest house; when false, the houses have no guide. */
        public boolean guideEnabled = true;

        /** The Escape Rope's "nearest rest house" option; climbing to the open air stays. */
        public boolean ropeEnabled = true;

        /**
         * Builds every rest house of a new map in the background, one at a
         * time, from shortly after the server starts, so trips rarely wait
         * for a house to build. Each house generates its few dozen chunks
         * off the server thread, then places its blocks over a few ticks.
         * When false, a house is built the first time a player goes near it
         * or travels to it.
         */
        public boolean prebuildHouses = true;

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

        /**
         * How the trips to a rest house are shown: FLIGHT (the player's
         * double flies off on their own flying Pokémon, or a Staraptor, seen
         * by everyone around; from the guide's steps, or from where the rope
         * was used, after a climb to the open air in a cave) or PANORAMA (the
         * painted landscape for the guide, the pixel-art scene for the rope).
         * FLIGHT falls back to PANORAMA's scenes on its own when the sky is
         * closed, or when the player has no flyer and Staraptor is missing.
         */
        public TravelCinematicStyle cinematicStyle = TravelCinematicStyle.FLIGHT;
    }

    /** Catch rate adjustments inside the Safari. */
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
    }

    /**
     * Special Pokémon of the day: a handful of uncommon, rare and ultra-rare
     * species drawn from what is installed (Cobblemon and datapacks), shown on
     * the wall screens and spawned in their habitat by the mod itself.
     *
     * <p>The categories and exclusions inherited from
     * {@link SpeciesRulesSection} decide which species the board may draw and
     * the mod's spawner may bring in; Cobblemon's natural spawns follow
     * {@link Spawns} instead.
     */
    public static class Featured extends SpeciesRulesSection {
        /** Master switch for the rotation, the board content and the special spawner. */
        public boolean enabled = true;

        /**
         * Length of one rotation, in real minutes (240 = four hours, 1440 =
         * one day). Rotations are aligned on midnight UTC when the length
         * divides a day, so 240 changes the specials at 0, 4, 8, 12, 16 and
         * 20 h UTC. The bounties are redrawn with each rotation, so a shorter
         * rotation also means more bounty sets (and bonuses) per day.
         * Changing it starts a new draw right away.
         */
        public int rotationMinutes = SafariConfigManager.DEFAULT_ROTATION_MINUTES;

        /**
         * Species featured per rotation, by rarity. Weighted toward the rare
         * end: the specials are what is worth a trip, and the board shows
         * six of them at most comfortably on a 4 x 2 screen. A slot a rarity
         * cannot fill goes to the next more common one.
         */
        public int uncommonSlots = 1;
        /** Rare species featured per rotation. */
        public int rareSlots = 2;
        /** Ultra-rare species featured per rotation. Legendaries land here when allowed. */
        public int ultraRareSlots = 3;

        /** Relative chance of an uncommon special being picked for a spawn. */
        public int weightUncommon = 10;
        /** Relative chance of a rare special being picked for a spawn. */
        public int weightRare = 4;
        /** Relative chance of an ultra-rare special being picked for a spawn. */
        public int weightUltraRare = 1;

        /** Seconds between two spawn attempts around each player in the Safari. */
        public int spawnIntervalSeconds = 15;

        /** Chance that an attempt goes ahead, between 0 and 1. */
        public double spawnChance = 0.35;

        /**
         * Special Pokémon allowed around one player at once (within
         * FeaturedSpawner.NEARBY_RADIUS). Also caps each species to one around
         * the player, so no special takes over.
         */
        public int maxNearbyPerPlayer = 2;

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
         * even if it is missing from {@code allowedBalls}.
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
                Source.of("minecraft:chests/village/village_plains_house", 0.05),
                Source.of("minecraft:chests/village/village_savanna_house", 0.08),
                Source.of("minecraft:chests/village/village_desert_house", 0.05),
                Source.of("minecraft:chests/village/village_taiga_house", 0.05),
                Source.of("minecraft:chests/village/village_snowy_house", 0.05),
                Source.of("minecraft:chests/desert_pyramid", 0.10),
                Source.of("minecraft:chests/jungle_temple", 0.10),
                Source.of("cobblemon:ruins/common/", 0.15)
        ));

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
     * The Safari Ticket: the built-in item access method. A ticket may carry its
     * own duration in its custom data ({@code duration_seconds}), so tickets
     * sold at different prices can grant different hunts.
     */
    public static class Ticket {
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
         * Using a ticket while already inside adds its duration to the running
         * session (capped by {@code maxDurationSeconds}) instead of being refused.
         */
        public boolean extendsActiveSession = true;

        /**
         * Most Safari time a player can bank with tickets, in seconds. Tickets
         * used outside the Safari add to this bank; entering spends it.
         */
        public int maxCreditSeconds = 4 * 60 * 60;

        /**
         * Gives the unused time of a hunt paid with banked time back to the bank
         * when the player leaves on purpose (through the clerk) or dies
         * inside. Time running out or an operator ending the hunt never refund.
         */
        public boolean refundUnusedTimeOnLeave = true;
    }

    /**
     * Protection rules. Creative and spectator players are exempt from the
     * terrain rules when {@link #exemptCreativeAndSpectator} is on, so builders
     * can still work on the park.
     */
    public static class Protection {
        /**
         * Players inside take no damage. Damage that bypasses invulnerability
         * (/kill, falling out of the world) still applies, otherwise a player
         * in the void would fall forever.
         */
        public boolean invulnerablePlayers = true;

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
    }
}
