package dev.darcosse.common.cobblemonsafari.config;

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
     * Bumped when a field changes meaning, so {@link SafariConfigManager} can
     * migrate old files. Version 3: the island grew and the boundary became a
     * square world border, so older park sections no longer match the terrain.
     * Version 4: added the loan and ticket sections (no existing value changes).
     * Version 5: added the spawns, featured and restHouses sections (no existing value changes).
     * Version 6: featured.rotationHours became featured.rotationMinutes.
     * Version 7: added ticket credit settings, the capture section and spawn
     * rate multipliers (no existing value changes).
     * Version 8: added the reset section (no existing value changes).
     * Version 9: removed the top-level defaultDurationSeconds (no entry path
     * grants free time any more) and added the gate section.
     * Version 10: added the points and escapeRope sections (no existing value
     * changes). The shop itself lives in cobblemon_safari/shop.json.
     * Version 11: added protection.placeableBlocks (no existing value changes).
     * Version 12: added the bounties section (no existing value changes).
     * Version 14: added ticket.minAddedSeconds (no existing value changes);
     * the default specials went from 3 uncommon, 2 rare, 1 ultra-rare to
     * 1, 2, 3, and files still on the old defaults follow.
     * Version 15: added the ticketLoot section (no existing value changes).
     * Version 13: the default rotation went from 1440 to 240 minutes; files
     * still on the old default follow it.
     * Version 16: removed dimension, park, featured.rotationHours and the
     * fine-tuning values (now constants), capture.safariBallItem (the bonus
     * follows loan.ballItem) and protection.preventFireSpread /
     * preventExplosionBlockDamage (both follow protectBlocks). Their keys are
     * dropped from the file on the next rewrite.
     * Version 17: removed the restHouses section; the houses are always
     * built, at anchors generated with the terrain (park.ParkGeometry).
     * Version 18: capture points and bounty rewards about five times higher,
     * so an hour of hunting buys a Pokémon of the shop; values still on the
     * old defaults follow.
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

    /** Remaining times, in seconds, at which the player gets a warning. */
    public List<Integer> warningThresholdsSeconds = new ArrayList<>(List.of(300, 60, 30, 10));

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

    /** Daily missions shown on the Safari Board, paid in Safari points. */
    public Bounties bounties = new Bounties();

    /** Which Pokémon may appear in the Safari, whoever spawns them. */
    public Spawns spawns = new Spawns();

    /** The daily rotation of special Pokémon and the spawner that brings them in. */
    public Featured featured = new Featured();

    /**
     * Rules applied to every Pokémon appearing in the Safari.
     *
     * <p>Cobblemon's own spawner populates the Safari through the biome tag
     * bridge ({@code data/cobblemon/tags/worldgen/biome}): each habitat gets the
     * Pokémon that naturally live in the matching Cobblemon biomes, including
     * those added by spawn datapacks.
     */
    public static class Spawns {
        /**
         * Lets Cobblemon's natural spawner work in the Safari. When false, only the
         * daily special Pokémon appear, which makes the park very quiet.
         */
        public boolean nativeSpawns = true;

        /** Pokémon labelled {@code legendary} may appear. */
        public boolean allowLegendary = false;
        /** Pokémon labelled {@code mythical} may appear. */
        public boolean allowMythical = false;
        /** Pokémon labelled {@code ultra_beast} may appear. */
        public boolean allowUltraBeast = false;
        /** Pokémon labelled {@code paradox} may appear. */
        public boolean allowParadox = false;

        /** Species ids that never appear in the Safari, e.g. {@code cobblemon:ditto}. */
        public List<String> excludedSpecies = new ArrayList<>();

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

        /** Keeps a Safari Warden standing next to each gate, who explains how entry works. */
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
         * How far, in blocks, the warden and his Pokémon may wander from the
         * gate before being walked back (teleported beyond twice this).
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

        /** Extra points when the species is one of today's specials on the Safari Board. */
        public int featuredBonus = 50;

        /** Extra points for a shiny. */
        public int shinyBonus = 250;
    }

    /**
     * Bounties: a few missions drawn with each rotation of the specials, the
     * same for every player, each paid once per player in Safari points.
     * They make variety pay more than catching the same Pokémon over and over.
     */
    public static class Bounties {
        /** Master switch for the missions, their rewards and their page on the board. */
        public boolean enabled = true;

        /** Missions per rotation. More missions mean more points per day; the board shows them all. */
        public int count = 3;

        /**
         * Points for a "catch N Pokémon of a type" mission, per Pokémon asked
         * (N between SafariBounties.TYPE_MIN_COUNT and TYPE_MAX_COUNT):
         * 3 Pokémon at 30 = 90 points.
         */
        public int typeRewardPerCatch = 30;

        /** Points for catching a given species (two of a common one, one of an uncommon one). */
        public int speciesReward = 120;

        /** Points for catching any of today's specials. Off when the specials are switched off. */
        public int featuredReward = 250;

        /** Extra points once every mission of the rotation is done. */
        public int allDoneBonus = 200;
    }

    /**
     * The Escape Rope: lent on entry, taken back on exit. Used in a cave of
     * the Safari, it brings the player back to the open air right above.
     * It never wears out.
     */
    public static class EscapeRope {
        /** Lends one rope to every player entering. */
        public boolean enabled = true;
    }

    /** Catch rate adjustments inside the Safari. */
    public static class Capture {
        /**
         * Multiplies the species catch rate when the loaned ball
         * ({@link Loan#ballItem}) hits a wild Pokémon that is NOT in a
         * battle, inside the Safari only. Cobblemon
         * halves every capture made outside a battle, so 3 means the Safari
         * Ball out of battle works 1.5 times as well as a normal throw in
         * battle (before Cobblemon's own 1.5 Safari Ball bonus). 1 disables it.
         */
        public double safariBallOutOfBattleMultiplier = 3.0;
    }

    /**
     * Special Pokémon of the day: a handful of uncommon, rare and ultra-rare
     * species drawn from what is installed (Cobblemon and datapacks), shown on
     * the Safari Board and spawned in their habitat by the mod itself.
     */
    public static class Featured {
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
         * FeaturedSpawner.NEARBY_RADIUS). Also caps each
         * species to one around the player, so no special takes over.
         */
        public int maxNearbyPerPlayer = 2;

        /** Lists the specials of the day in chat when a player enters. */
        public boolean announceOnEntry = true;
    }

    /**
     * Safari Balls handed out on entry and taken back on exit, like the 30 balls
     * of the original Safari Zones.
     *
     * <p>Loaned balls carry a marker, cannot be thrown outside the Safari, and
     * are removed from the inventory as soon as the player is outside: they
     * can never be farmed by entering and leaving repeatedly.
     */
    public static class Loan {
        /** Balls handed out per session. 0 disables the loan; players then bring their own allowed balls. */
        public int count = 30;

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
         * when the player leaves on purpose (/safari leave). Time running out,
         * death or an operator ending the hunt never refund.
         */
        public boolean refundUnusedTimeOnLeave = true;

        /**
         * Lets players enter with /safari start, spending their banked time.
         * Meant to be turned off once entry goes through the overworld
         * Safari gate; operators keep /safari enter.
         */
        public boolean allowStartCommand = true;
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
