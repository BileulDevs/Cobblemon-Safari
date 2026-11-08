package dev.darcosse.common.cobblemonsafari.config;

import dev.darcosse.common.cobblemonsafari.spawn.SwarmRules;
import dev.darcosse.common.cobblemonsafari.worldgen.IslandRelief;
import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import dev.darcosse.common.cobblemonsafari.bounty.BountyKind;
import dev.darcosse.common.cobblemonsafari.bounty.SafariBounties;
import dev.darcosse.common.cobblemonsafari.util.Ticks;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Owns the server configuration: loading, validation, and the derived values
 * the rest of the mod reads every tick.
 *
 * <p>Derived values (parsed ids, tick conversions) are computed once per load
 * instead of on every access, because several of them are read from the
 * per-tick session loop.
 */
public final class SafariConfigManager {

    /** Path under the config directory: the mod's folder keeps its files together. */
    private static final String FILE_NAME = CobblemonSafari.MOD_ID + "/server.json";

    /** Last valid hour of the day for reset.hour. */
    private static final int LAST_HOUR = 23;

    /**
     * Range of the spawn multipliers. Below 0.1 the Safari is practically
     * empty; above 10 each player triggers ten times Cobblemon's spawn scans,
     * which a busy server feels.
     */
    private static final double MIN_SPAWN_MULTIPLIER = 0.1;
    private static final double MAX_SPAWN_MULTIPLIER = 10.0;

    /**
     * Most roaming Pokémon per gate. Each one is an entity kept alive and
     * checked every few seconds while a player is near the gate.
     */
    private static final int MAX_GATE_COMPANIONS = 8;

    /**
     * Range of gate.roamRadius. Below 3 the Pokémon crowd the gate's
     * doorway; above 24 they wander out of the structure and out of sight.
     */
    private static final int MIN_GATE_ROAM_RADIUS = 3;
    private static final int MAX_GATE_ROAM_RADIUS = 24;

    /**
     * Most missions per rotation. The clerk lists them in chat; past
     * about eight lines the list scrolls out of the chat box. Bound to the
     * clerk's swap functions, one per mission (SafariBounties.MAX_MISSIONS).
     */
    private static final int MAX_BOUNTIES = SafariBounties.MAX_MISSIONS;

    /**
     * Format version written to the file. Raise it when a field changes
     * meaning and add the conversion to {@link #load}; a field added or
     * removed needs no bump, Gson fills new fields with their defaults and
     * ignores unknown keys.
     *
     * <p>History: 2 split the species rules. Up to 1, {@code spawns.legendary}
     * and its siblings governed every way into the Safari; from 2 they only
     * govern Cobblemon's natural spawns and {@code featured} carries its own
     * set for the board (see {@link #migrate}). 3 moved the rest house
     * travel wait from {@code escapeRope} to {@code restHouseTravel}. 4 gave
     * each kind of mission its own tuning ({@code bounties.kinds}), where the
     * three former rewards of {@code bounties} moved. 5 raised the default
     * ticket loot (every village chest), applied to files still holding the
     * old default list. 6 added the fishing missions to
     * {@code bounties.allowedKinds}, once, in files written before they existed.
     * 7 grouped the settings by subject and renamed several (see
     * {@link #migrateToSections}); older files are converted on load.
     * 8 added events.swarm.buckets, rarities, weights, excludeList,
     * minNearby and checkEverySeconds, and protection.invulnerablePokemon:
     * nothing to convert, raised only so that existing files are rewritten
     * once and show the new keys.
     */
    public static final int CURRENT_CONFIG_VERSION = 8;

    /** First version that knows the fishing missions. */
    private static final int FISHING_MISSION_VERSION = 6;

    /** First version with the 1.3.0 ticket loot defaults. */
    private static final int TICKET_LOOT_VERSION = 5;

    /** First version in which every kind of mission has its tuning under {@code bounties.kinds}. */
    private static final int BOUNTY_KINDS_VERSION = 4;

    /** Most swaps a player may make per rotation: as many as the missions themselves. */
    private static final int MAX_BOUNTY_SWAPS = 8;

    /**
     * Highest level a level mission may ask: Cobblemon's maximum. Above it
     * the mission could never be done.
     */
    private static final int MAX_POKEMON_LEVEL = 100;

    /** Spawn buckets a species filter may name: Cobblemon's own. */
    private static final Set<String> BOUNTY_RARITIES = Set.of("common", "uncommon", "rare", "ultra-rare");

    /** First version in which {@code featured} has its own species rules. */
    private static final int SPLIT_SPECIES_RULES_VERSION = 2;

    /**
     * First version with the {@code restHouseTravel} section; before it, its
     * wait was {@code escapeRope.restHouseCooldownSeconds}.
     */
    private static final int TRAVEL_SECTION_VERSION = 3;

    /**
     * Default length of a featured rotation, in minutes: four hours, so the
     * specials and bounties change several times in a play day.
     */
    public static final int DEFAULT_ROTATION_MINUTES = 240;

    /**
     * Default spawns.caughtSpeciesWeight. Lower, a caught species is rare
     * enough to feel gone from the Safari; higher, the novelty push is
     * barely noticeable.
     */
    public static final double DEFAULT_CAUGHT_SPECIES_WEIGHT = 0.4;

    /** Default spawns.snackClearRadius, in blocks: twice a Poké Snack's own spawn radius (8). */
    public static final int DEFAULT_SNACK_CLEAR_RADIUS = 16;

    /**
     * Largest spawns.snackClearRadius. Beyond it the cleared disc covers most
     * of a player's spawn area and the snack search reaches extra chunks.
     */
    private static final int MAX_SNACK_CLEAR_RADIUS = 32;

    /** Default spawns.snackDensityMultiplier: enough for a snack to bite in an area the Safari keeps full. */
    public static final double DEFAULT_SNACK_DENSITY_MULTIPLIER = 1.5;

    /** Bounds of spawns.snackDensityMultiplier: below 1 Cobblemon's own cap wins; above 4 a snack draws a crowd. */
    private static final double MIN_SNACK_DENSITY_MULTIPLIER = 1.0;
    private static final double MAX_SNACK_DENSITY_MULTIPLIER = 4.0;

    /**
     * Default restHouses.travel.cooldownSeconds. Long enough that the houses
     * are not an instant network; short enough to come back to a house
     * after the next cave.
     */
    public static final int DEFAULT_TRAVEL_COOLDOWN_SECONDS = 30;

    /**
     * Default restHouses.travel.cinematicSeconds: long enough to read the
     * destination, short enough not to feel like a loading screen.
     */
    public static final int DEFAULT_TRAVEL_CINEMATIC_SECONDS = 3;

    /**
     * Longest cinematic accepted, in seconds. Beyond it the trip feels
     * stuck; the wait for the destination itself has its own limit
     * (RestHouseTravel.MAX_WAIT_SECONDS).
     */
    private static final int MAX_TRAVEL_CINEMATIC_SECONDS = 15;

    /**
     * Upper bound on loaned balls. A full inventory holds 36 stacks; beyond a
     * few stacks the surplus is dropped on the ground at the entrance, where
     * other players could pick it up.
     */
    private static final int MAX_LOANED_BALLS = 256;

    /** Shortest session accepted. Zero would expire on the very tick it starts. */
    private static final int MIN_DURATION_SECONDS = 1;

    private static final JsonConfigFile<SafariConfig> FILE =
            new JsonConfigFile<>(FILE_NAME, SafariConfig.class, SafariConfig::new,
                    SafariConfigManager::migrateTree);

    private static SafariConfig config = new SafariConfig();
    private static Set<ResourceLocation> allowedBalls = Set.of();
    private static Set<Integer> warningTicks = Set.of();
    private static Set<ResourceLocation> placeableBlocks = Set.of();
    @Nullable
    private static ResourceLocation loanedBallItem;

    private SafariConfigManager() {
    }

    /** Reads the file, fixes invalid values, and rewrites it if anything was corrected. */
    public static void load() {
        SafariConfig loaded = FILE.read();
        boolean outdated = loaded.configVersion != CURRENT_CONFIG_VERSION;
        migrate(loaded);
        // The rewrite also drops keys Gson ignored, so the file shows what is read.
        loaded.configVersion = CURRENT_CONFIG_VERSION;
        if (validate(loaded) || outdated) {
            FILE.write(loaded);
        }
        config = loaded;
        rebuildDerived();
        // Same lifecycle: /safari reload re-reads the shop too.
        SafariShopConfigManager.load();
    }

    /**
     * Converts the values of a file written by an older version of the mod,
     * once its keys are in their current place ({@link #migrateTree}) and
     * before it is validated. Each step only runs for files older than the
     * version that introduced it.
     */
    private static void migrate(SafariConfig c) {
        if (c.configVersion < TICKET_LOOT_VERSION) {
            migrateTicketLoot(c);
        }
        // A file older than the fishing missions could not list them, so its list
        // says nothing against them: they are added once, like a new default.
        if (c.configVersion < FISHING_MISSION_VERSION && c.missions != null && c.missions.allowedKinds != null
                && !c.missions.allowedKinds.contains(BountyKind.FISHING)) {
            c.missions.allowedKinds.add(BountyKind.FISHING);
            CobblemonSafari.LOGGER.info("Added the fishing missions (FISHING) to missions.allowedKinds in {}",
                    FILE_NAME);
        }
    }

    /**
     * Puts every key of an older file in its current place, on the raw tree
     * before binding: a key the config classes no longer have would be lost
     * once bound. Steps that depend on the version read the file's own
     * {@code configVersion}; the moves themselves only act on keys that are
     * there, so a file already converted goes through unchanged.
     */
    private static void migrateTree(String fileName, JsonObject root) {
        ConfigTreeMigration tree = new ConfigTreeMigration(fileName, root);
        int version = tree.version(CURRENT_CONFIG_VERSION);
        migrateLegacyKeys(tree, version);
        migrateToSections(tree);
        // The 1.4.1 development builds wrote the swarm's bucket list under
        // "rarities", which now holds the legendary-class switches: an array
        // there would fail to bind and the whole file would be refused.
        if (tree.get("events.swarm.rarities") instanceof JsonArray) {
            tree.move("events.swarm.rarities", "events.swarm.buckets");
        }
        // Later ones wrote each category as a bare switch; it is now an object
        // with a cap, and a boolean there would fail to bind just the same.
        for (String category : List.of("legendary", "mythical", "ultraBeast", "paradox")) {
            String path = "events.swarm.rarities." + category;
            if (tree.get(path) instanceof JsonPrimitive flag && flag.isBoolean()) {
                JsonObject converted = new JsonObject();
                converted.addProperty("allowSpawn", flag.getAsBoolean());
                converted.addProperty("maxSpawnPerPokemon", SafariConfig.SwarmCategories.DEFAULT_CAP);
                tree.set(path, converted, "a swarm category now has a cap");
            }
        }
    }

    /**
     * The key moves of versions 2 to 6 and of the 1.4.0 development builds,
     * written against the layout of their time; {@link #migrateToSections}
     * then carries the result into the current one.
     */
    private static void migrateLegacyKeys(ConfigTreeMigration tree, int version) {
        // Version 2 split the species rules: up to 1, spawns' categories
        // governed every way in. Gson builds a missing section from its
        // defaults, so "absent" cannot be told from "set to the defaults":
        // the version is the only reliable signal. Without this copy, a server
        // that allowed legendaries in v1 would lose them from the board.
        if (version < SPLIT_SPECIES_RULES_VERSION && tree.get("spawns") instanceof JsonObject spawns) {
            JsonObject featured = tree.object("featured");
            for (String key : List.of("legendary", "mythical", "ultraBeast", "paradox", "excludedSpecies")) {
                if (spawns.has(key)) {
                    featured.add(key, spawns.get(key).deepCopy());
                }
            }
            CobblemonSafari.LOGGER.info("{}: copied the species rules of spawns to featured (config version {})",
                    FILE_NAME, version);
        }
        // Version 3 moved the rest house wait out of escapeRope. Dropped whatever
        // the version: a build between 2 and 3 may have left it in a file marked 3.
        if (version < TRAVEL_SECTION_VERSION) {
            tree.move("escapeRope.restHouseCooldownSeconds", "restHouseTravel.cooldownSeconds");
        }
        tree.remove("escapeRope.restHouseCooldownSeconds", "replaced by restHouses.travel.cooldownSeconds");
        // Version 4 gave each kind of mission its own tuning, where the three
        // rewards of bounties went. Written straight under the current names
        // of the kinds' fields, which migrateToSections then leaves alone.
        BountyKinds kindDefaults = new BountyKinds();
        if (version < BOUNTY_KINDS_VERSION) {
            moveLegacyReward(tree, "bounties.typeRewardPerCatch", "bounties.kinds.type", kindDefaults.type,
                    "pointsPerCatch");
            moveLegacyReward(tree, "bounties.speciesReward", "bounties.kinds.species", kindDefaults.species,
                    "rewardPoints");
            moveLegacyReward(tree, "bounties.featuredReward", "bounties.kinds.featured", kindDefaults.special,
                    "rewardPoints");
        }
        for (String legacy : List.of("typeRewardPerCatch", "speciesReward", "featuredReward")) {
            tree.remove("bounties." + legacy, "replaced by missions.kinds");
        }
        migrateLegacyFlee(tree);
    }

    /**
     * Carries one pre-4 mission reward into its kind, creating the kind from
     * its defaults when absent. Set, not {@link ConfigTreeMigration#move}:
     * the kind created from its defaults already holds the field, and in a
     * pre-4 file the old reward is the one the admin chose, as before 1.4.0.
     */
    private static void moveLegacyReward(ConfigTreeMigration tree, String from, String kindPath, Object kindDefaults,
                                         String field) {
        JsonElement reward = tree.get(from);
        if (reward != null) {
            tree.objectOr(kindPath, kindDefaults);
            tree.set(kindPath + "." + field, reward.deepCopy(), "from " + from);
            tree.remove(from, "moved into " + kindPath);
        }
    }

    /**
     * The flee keys of the 1.4.0 development builds: four flat chances, one
     * switch for the four categories, then a switch-only {@code rarity}
     * object. Moved into the entries of {@code capture.flee.rarities}; an
     * entry they create holds only those keys, and validation completes it
     * from that entry's own defaults. {@code rarity} goes last: a file with
     * both comes from the later build, whose switches are the more precise.
     */
    private static void migrateLegacyFlee(ConfigTreeMigration tree) {
        String flee = "capture.flee";
        String[] rarities = {"common", "uncommon", "rare", "ultraRare"};
        for (String rarity : rarities) {
            tree.move(flee + "." + rarity + "Chance", flee + ".rarities." + rarity + ".chance");
        }
        String[] categories = {"legendary", "mythical", "ultraBeast", "paradox"};
        JsonElement special = tree.get(flee + ".specialCategories");
        if (special != null) {
            for (String category : categories) {
                if (!tree.has(flee + ".rarities." + category + ".enabled")) {
                    tree.set(flee + ".rarities." + category + ".enabled", special.deepCopy(),
                            "from capture.flee.specialCategories");
                }
            }
            tree.remove(flee + ".specialCategories", "replaced by capture.flee.rarities");
        }
        if (tree.get(flee + ".rarity") instanceof JsonObject switches) {
            for (Map.Entry<String, JsonElement> entry : switches.entrySet()) {
                tree.set(flee + ".rarities." + entry.getKey() + ".enabled", entry.getValue().deepCopy(),
                        "from capture.flee.rarity");
            }
            tree.remove(flee + ".rarity", "replaced by capture.flee.rarities");
        }
    }

    /**
     * Version 7: the settings grouped by subject, with clearer names. Each
     * line is one key's old and new place.
     */
    private static void migrateToSections(ConfigTreeMigration tree) {
        tree.move("maxDurationSeconds", "hunt.maxDurationSeconds");
        tree.move("pauseTimeWhileOffline", "hunt.pauseWhileOffline");
        tree.move("warningThresholdsSeconds", "hunt.warningsAtSeconds");
        tree.move("exitDestination", "hunt.exitDestination");
        tree.move("exemptCreativeAndSpectator", "hunt.exemptCreativeAndSpectator");

        tree.move("ticket.defaultDurationSeconds", "tickets.defaultDurationSeconds");
        tree.move("ticket.minAddedSeconds", "tickets.minAddedSeconds");
        tree.move("ticket.extendsActiveSession", "tickets.addToCurrentHunt");
        tree.move("ticket.maxCreditSeconds", "tickets.maxBankedSeconds");
        tree.move("ticket.refundUnusedTimeOnLeave", "tickets.refundUnusedTimeOnLeave");
        tree.removeIfEmpty("ticket");
        tree.move("ticketLoot", "tickets.loot");

        tree.move("allowedBalls", "balls.allowed");
        tree.move("loan", "balls.loan");

        tree.move("allowBattles", "battles.enabled");
        tree.move("fishingStartsBattle", "battles.fishingStartsBattle");
        tree.move("battleGraceSeconds", "battles.graceSeconds");

        tree.move("capture.awardSafariMark", "capture.marks.safari");
        for (String mark : List.of("special", "swarm", "fullMoon", "angler")) {
            tree.move("capture.eventMarks." + mark, "capture.marks." + mark);
        }
        tree.removeIfEmpty("capture.eventMarks");

        if (!tree.has("specials")) {
            tree.move("featured", "specials");
        }
        String[][] specials = {
                {"uncommonSlots", "slots.uncommon"}, {"rareSlots", "slots.rare"},
                {"ultraRareSlots", "slots.ultraRare"},
                {"spawnIntervalSeconds", "spawn.checkEverySeconds"}, {"spawnChance", "spawn.chance"},
                {"maxNearbyPerPlayer", "spawn.maxAroundPlayer"},
                {"weightUncommon", "spawn.weights.uncommon"}, {"weightRare", "spawn.weights.rare"},
                {"weightUltraRare", "spawn.weights.ultraRare"}};
        for (String[] pair : specials) {
            tree.move("specials." + pair[0], "specials." + pair[1]);
        }

        for (String rarity : List.of("common", "uncommon", "rare", "ultraRare")) {
            tree.move("points." + rarity, "points.perCatch." + rarity);
        }
        tree.move("points.featuredBonus", "points.specialBonus");

        if (!tree.has("missions")) {
            tree.move("bounties", "missions");
        }
        tree.move("missions.allDoneBonus", "missions.allMissionsBonusPoints");
        tree.move("missions.kinds.featured", "missions.kinds.special");
        tree.renameInEach("missions.kinds", "reward", "rewardPoints");
        tree.renameInEach("missions.kinds", "rewardPerCatch", "pointsPerCatch");
        if (tree.get("missions.allowedKinds") instanceof JsonArray kinds) {
            for (int i = 0; i < kinds.size(); i++) {
                if (kinds.get(i) instanceof JsonPrimitive kind && "FEATURED".equals(kind.getAsString())) {
                    kinds.set(i, new JsonPrimitive(BountyKind.SPECIAL.name()));
                    CobblemonSafari.LOGGER.info("{}: renamed FEATURED to SPECIAL in missions.allowedKinds", FILE_NAME);
                }
            }
        }

        if (!tree.has("restHouses")) {
            tree.move("restHouseTravel", "restHouses");
        }
        tree.move("restHouses.prebuildHouses", "restHouses.prebuild");
        tree.move("restHouses.guideEnabled", "restHouses.travel.guide");
        tree.move("restHouses.ropeEnabled", "escapeRope.toRestHouse");
        // An earlier 1.4.0 build wrote these straight under restHouses.
        for (String key : List.of("guide", "cooldownSeconds", "cinematicSeconds")) {
            tree.move("restHouses." + key, "restHouses.travel." + key);
        }
        // Not a setting any more: the trips are always filmed, falling back on
        // their own to the painted scenes when the flight cannot be staged.
        tree.remove("restHouses.cinematicStyle", "the trips are always filmed");
    }

    /**
     * Puts the 1.3.0 ticket loot defaults (every village chest, slightly
     * higher chances) in a file that still holds the defaults of earlier
     * versions, entry for entry. A list someone edited is theirs and is left
     * alone: only an untouched list can be told to mean "the defaults".
     */
    private static void migrateTicketLoot(SafariConfig c) {
        if (c.tickets == null || c.tickets.loot == null || c.tickets.loot.sources == null
                || !sameSources(c.tickets.loot.sources, SafariConfig.TicketLoot.PRE_1_3_DEFAULTS)) {
            return;
        }
        c.tickets.loot.sources = new SafariConfig.TicketLoot().sources;
        CobblemonSafari.LOGGER.info("Updated tickets.loot.sources in {} to the 1.3.0 defaults (every village chest)",
                FILE_NAME);
    }

    /** Whether two lists name the same tables with the same chances, in the same order. */
    private static boolean sameSources(List<SafariConfig.TicketLoot.Source> a, List<SafariConfig.TicketLoot.Source> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            SafariConfig.TicketLoot.Source x = a.get(i);
            SafariConfig.TicketLoot.Source y = b.get(i);
            if (x == null || !Objects.equals(x.table, y.table) || x.chance != y.chance) {
                return false;
            }
        }
        return true;
    }

    /** Re-reads the file; callers are responsible for re-syncing clients afterwards. */
    public static void reload() {
        load();
    }

    /** Current configuration. Treat it as read-only outside this class. */
    public static SafariConfig get() {
        return config;
    }

    /** Poké Ball ids allowed inside the Safari. */
    public static Set<ResourceLocation> allowedBalls() {
        return allowedBalls;
    }

    /** Item id of the loaned ball, or null when the loan is disabled. */
    @Nullable
    public static ResourceLocation loanedBallItem() {
        return loanedBallItem;
    }

    /** Remaining-tick values at which a warning is shown. */
    public static Set<Integer> warningTicks() {
        return warningTicks;
    }

    /** Maximum remaining time, in ticks. */
    public static int maxDurationTicks() {
        return Ticks.fromSeconds(config.hunt.maxDurationSeconds);
    }

    /** Extra time allowed after expiry while in battle, in ticks. */
    public static int battleGraceTicks() {
        return Ticks.fromSeconds(config.battles.graceSeconds);
    }

    /** Recomputes the parsed and converted values read on hot paths, after every load. */
    private static void rebuildDerived() {
        Set<ResourceLocation> balls = new LinkedHashSet<>();
        for (String raw : config.balls.allowed) {
            ResourceLocation id = ResourceLocation.tryParse(raw);
            if (id != null) {
                balls.add(id);
            }
        }
        SafariConfig.Loan loan = config.balls.loan;
        loanedBallItem = loan.count > 0 ? ResourceLocation.tryParse(loan.ballItem) : null;
        // The loaned ball must always work inside: an admin removing safari_ball
        // from balls.allowed would otherwise hand out balls nobody can throw.
        // Ball ids and item ids coincide for every Cobblemon ball.
        if (loanedBallItem != null) {
            balls.add(loanedBallItem);
        }
        allowedBalls = Collections.unmodifiableSet(balls);

        Set<Integer> ticks = new LinkedHashSet<>();
        for (int seconds : config.hunt.warningsAtSeconds) {
            ticks.add(Ticks.fromSeconds(seconds));
        }
        warningTicks = Collections.unmodifiableSet(ticks);

        Set<ResourceLocation> placeable = new LinkedHashSet<>();
        for (String raw : config.protection.placeableBlocks) {
            ResourceLocation id = ResourceLocation.tryParse(raw);
            if (id != null) {
                placeable.add(id);
            }
        }
        placeableBlocks = Collections.unmodifiableSet(placeable);
    }

    /** Block ids players may place and break despite terrain protection. */
    public static Set<ResourceLocation> placeableBlocks() {
        return placeableBlocks;
    }

    /**
     * Replaces missing category sections of one rules section with defaults.
     * Only an explicit {@code null} in the file gets here: an absent key is
     * already filled by Gson from the field initialiser.
     *
     * @return true if one was missing
     */
    private static boolean ensureCategories(SafariConfig.SpeciesRulesSection section) {
        boolean missing = false;
        if (section.legendary == null) {
            section.legendary = new SafariConfig.SpecialCategory();
            missing = true;
        }
        if (section.mythical == null) {
            section.mythical = new SafariConfig.SpecialCategory();
            missing = true;
        }
        if (section.ultraBeast == null) {
            section.ultraBeast = new SafariConfig.SpecialCategory();
            missing = true;
        }
        if (section.paradox == null) {
            section.paradox = SafariConfig.SpecialCategory.of(SafariConfig.SpeciesRulesSection.PARADOX_DEFAULT_ALLOWED,
                    SafariConfig.SpeciesRulesSection.PARADOX_DEFAULT_CAP);
            missing = true;
        }
        return missing;
    }

    /**
     * Fixes the categories and exclusions of one rules section ({@code spawns}
     * or {@code specials}).
     *
     * @param name the section's key, for the log
     * @return true if anything changed
     */
    private static boolean validateSpeciesRules(String name, SafariConfig.SpeciesRulesSection section) {
        boolean corrected = false;
        if (section.excludedSpecies == null) {
            section.excludedSpecies = new ArrayList<>();
            corrected = true;
        }
        corrected |= ensureCategories(section);
        corrected |= validateCategory(name + ".legendary", section.legendary);
        corrected |= validateCategory(name + ".mythical", section.mythical);
        corrected |= validateCategory(name + ".ultraBeast", section.ultraBeast);
        corrected |= validateCategory(name + ".paradox", section.paradox);
        return corrected;
    }

    /**
     * Clamps every value into its valid domain.
     *
     * @return true if at least one value was corrected
     */
    private static boolean validate(SafariConfig c) {
        boolean corrected = false;
        SafariConfig defaults = new SafariConfig();

        if (c.hunt == null) {
            c.hunt = new SafariConfig.Hunt();
            corrected = true;
        }
        SafariConfig.Hunt hunt = c.hunt;
        SafariConfig.Hunt huntDefaults = defaults.hunt;
        if (hunt.maxDurationSeconds < MIN_DURATION_SECONDS) {
            warn("hunt.maxDurationSeconds", hunt.maxDurationSeconds, huntDefaults.maxDurationSeconds);
            hunt.maxDurationSeconds = huntDefaults.maxDurationSeconds;
            corrected = true;
        }
        if (hunt.exitDestination == null) {
            warn("hunt.exitDestination", null, huntDefaults.exitDestination);
            hunt.exitDestination = huntDefaults.exitDestination;
            corrected = true;
        }
        if (hunt.warningsAtSeconds == null) {
            hunt.warningsAtSeconds = huntDefaults.warningsAtSeconds;
            corrected = true;
        } else {
            List<Integer> kept = new ArrayList<>();
            for (Integer seconds : hunt.warningsAtSeconds) {
                if (seconds != null && seconds > 0 && !kept.contains(seconds)) {
                    kept.add(seconds);
                } else {
                    corrected = true;
                }
            }
            kept.sort(Comparator.reverseOrder());
            hunt.warningsAtSeconds = kept;
        }

        if (c.battles == null) {
            c.battles = new SafariConfig.Battles();
            corrected = true;
        }
        if (c.battles.graceSeconds < 0) {
            warn("battles.graceSeconds", c.battles.graceSeconds, 0);
            c.battles.graceSeconds = 0;
            corrected = true;
        }

        if (c.balls == null) {
            c.balls = new SafariConfig.Balls();
            corrected = true;
        }
        SafariConfig.Balls balls = c.balls;
        if (balls.allowed == null) {
            warn("balls.allowed", null, defaults.balls.allowed);
            balls.allowed = defaults.balls.allowed;
            corrected = true;
        } else {
            List<String> kept = new ArrayList<>();
            for (String raw : balls.allowed) {
                if (raw != null && ResourceLocation.tryParse(raw) != null) {
                    kept.add(raw);
                } else {
                    CobblemonSafari.LOGGER.warn("Ignoring invalid ball id '{}' in balls.allowed", raw);
                    corrected = true;
                }
            }
            balls.allowed = kept;
        }
        // An empty list is legal (no capture at all), but almost always a mistake worth flagging.
        if (balls.allowed.isEmpty()) {
            CobblemonSafari.LOGGER.warn("balls.allowed is empty: no Pokémon can be caught inside the Safari");
        }

        if (c.protection == null) {
            c.protection = new SafariConfig.Protection();
            corrected = true;
        }
        if (c.protection.interact == null) {
            c.protection.interact = new SafariConfig.Interact();
            corrected = true;
        }
        if (c.protection.placeableBlocks == null) {
            c.protection.placeableBlocks = new SafariConfig.Protection().placeableBlocks;
            corrected = true;
        } else {
            List<String> kept = new ArrayList<>();
            for (String raw : c.protection.placeableBlocks) {
                if (raw != null && ResourceLocation.tryParse(raw) != null) {
                    kept.add(raw);
                } else {
                    CobblemonSafari.LOGGER.warn("Ignoring invalid block id '{}' in protection.placeableBlocks", raw);
                    corrected = true;
                }
            }
            c.protection.placeableBlocks = kept;
        }
        if (balls.loan == null) {
            balls.loan = new SafariConfig.Loan();
            corrected = true;
        }
        SafariConfig.Loan loan = balls.loan;
        if (loan.count < 0 || loan.count > MAX_LOANED_BALLS) {
            int fixed = Math.max(0, Math.min(MAX_LOANED_BALLS, loan.count));
            warn("balls.loan.count", loan.count, fixed);
            loan.count = fixed;
            corrected = true;
        }
        if (loan.ballItem == null || ResourceLocation.tryParse(loan.ballItem) == null) {
            warn("balls.loan.ballItem", loan.ballItem, new SafariConfig.Loan().ballItem);
            loan.ballItem = new SafariConfig.Loan().ballItem;
            corrected = true;
        }
        corrected |= validateTickets(c);
        if (c.reset == null) {
            c.reset = new SafariConfig.Reset();
            corrected = true;
        }
        if (c.reset.relief == null) {
            // Gson reads a name that is no relief as null.
            warn("reset.relief", "an unknown relief", IslandRelief.GENTLE);
            c.reset.relief = IslandRelief.GENTLE;
            corrected = true;
        }
        if (c.reset.hour < 0 || c.reset.hour > LAST_HOUR) {
            int fixed = Math.max(0, Math.min(LAST_HOUR, c.reset.hour));
            warn("reset.hour", c.reset.hour, fixed);
            c.reset.hour = fixed;
            corrected = true;
        }
        if (c.reset.timeZone == null) {
            c.reset.timeZone = "";
            corrected = true;
        }
        if (c.capture == null) {
            c.capture = new SafariConfig.Capture();
            corrected = true;
        }
        // Below 1 the "bonus" would make the Safari Ball worse than outside.
        if (!(c.capture.safariBallOutOfBattleMultiplier >= 1.0)) {
            warn("capture.safariBallOutOfBattleMultiplier", c.capture.safariBallOutOfBattleMultiplier, 1.0);
            c.capture.safariBallOutOfBattleMultiplier = 1.0;
            corrected = true;
        }
        if (c.capture.marks == null) {
            c.capture.marks = new SafariConfig.Marks();
            corrected = true;
        }
        corrected |= validateGate(c);
        corrected |= validatePoints(c);
        corrected |= validateMissions(c);
        if (c.escapeRope == null) {
            c.escapeRope = new SafariConfig.EscapeRope();
            corrected = true;
        }
        corrected |= validateRestHouses(c);
        corrected |= validateSpawns(c);
        corrected |= validateSpecials(c);
        corrected |= validateFlee(c);
        corrected |= validateEvents(c);
        return corrected;
    }

    /** Fixes the tickets section and its chest loot; returns true if anything changed. */
    private static boolean validateTickets(SafariConfig c) {
        boolean corrected = false;
        if (c.tickets == null) {
            c.tickets = new SafariConfig.Tickets();
            corrected = true;
        }
        SafariConfig.Tickets t = c.tickets;
        if (t.defaultDurationSeconds < MIN_DURATION_SECONDS) {
            int fixed = new SafariConfig.Tickets().defaultDurationSeconds;
            warn("tickets.defaultDurationSeconds", t.defaultDurationSeconds, fixed);
            t.defaultDurationSeconds = fixed;
            corrected = true;
        }
        if (t.minAddedSeconds < 0) {
            warn("tickets.minAddedSeconds", t.minAddedSeconds, 0);
            t.minAddedSeconds = 0;
            corrected = true;
        }
        if (t.maxBankedSeconds < 0) {
            warn("tickets.maxBankedSeconds", t.maxBankedSeconds, 0);
            t.maxBankedSeconds = 0;
            corrected = true;
        }
        if (t.loot == null) {
            t.loot = new SafariConfig.TicketLoot();
            corrected = true;
        }
        if (t.loot.sources == null) {
            t.loot.sources = new ArrayList<>();
            corrected = true;
        }
        int sourceCount = t.loot.sources.size();
        t.loot.sources.removeIf(source -> source == null || source.table == null
                || ResourceLocation.tryParse(source.table) == null);
        if (t.loot.sources.size() != sourceCount) {
            CobblemonSafari.LOGGER.warn("Removed tickets.loot sources with an invalid table id from {}", FILE_NAME);
            corrected = true;
        }
        for (SafariConfig.TicketLoot.Source source : t.loot.sources) {
            if (!(source.chance >= 0.0D && source.chance <= 1.0D)) {
                warn("tickets.loot chance of " + source.table, source.chance, 0);
                source.chance = Math.max(0.0D, Math.min(1.0D, Double.isNaN(source.chance) ? 0.0D : source.chance));
                corrected = true;
            }
        }
        return corrected;
    }

    /** Fixes the rest houses section: no negative wait, a bounded cinematic. Returns true if anything changed. */
    private static boolean validateRestHouses(SafariConfig c) {
        if (c.restHouses == null) {
            c.restHouses = new SafariConfig.RestHouses();
            return true;
        }
        if (c.restHouses.travel == null) {
            c.restHouses.travel = new SafariConfig.Travel();
            return true;
        }
        SafariConfig.Travel t = c.restHouses.travel;
        boolean corrected = false;
        if (t.cooldownSeconds < 0) {
            warn("restHouses.travel.cooldownSeconds", t.cooldownSeconds, 0);
            t.cooldownSeconds = 0;
            corrected = true;
        }
        if (t.cinematicSeconds < 0 || t.cinematicSeconds > MAX_TRAVEL_CINEMATIC_SECONDS) {
            int fixed = Math.max(0, Math.min(MAX_TRAVEL_CINEMATIC_SECONDS, t.cinematicSeconds));
            warn("restHouses.travel.cinematicSeconds", t.cinematicSeconds, fixed);
            t.cinematicSeconds = fixed;
            corrected = true;
        }
        return corrected;
    }

    /** Fixes the points section: no negative reward, a positive cap. Returns true if anything changed. */
    private static boolean validatePoints(SafariConfig c) {
        if (c.points == null) {
            c.points = new SafariConfig.Points();
            return true;
        }
        SafariConfig.Points p = c.points;
        boolean corrected = false;
        if (p.perCatch == null) {
            p.perCatch = new SafariConfig.CatchPoints();
            corrected = true;
        }
        SafariConfig.CatchPoints each = p.perCatch;
        if (each.common < 0 || each.uncommon < 0 || each.rare < 0 || each.ultraRare < 0
                || p.specialBonus < 0 || p.shinyBonus < 0) {
            warn("points", "a negative value", "0");
            each.common = Math.max(0, each.common);
            each.uncommon = Math.max(0, each.uncommon);
            each.rare = Math.max(0, each.rare);
            each.ultraRare = Math.max(0, each.ultraRare);
            p.specialBonus = Math.max(0, p.specialBonus);
            p.shinyBonus = Math.max(0, p.shinyBonus);
            corrected = true;
        }
        return corrected;
    }

    /**
     * Fixes the missions section: a count of 0 to 8, known kinds only,
     * sane tunings, known rarities. Returns true if anything changed.
     */
    private static boolean validateMissions(SafariConfig c) {
        if (c.missions == null) {
            c.missions = new SafariConfig.Missions();
            return true;
        }
        SafariConfig.Missions b = c.missions;
        boolean corrected = false;
        if (b.count < 0 || b.count > MAX_BOUNTIES) {
            int fixed = Math.max(0, Math.min(MAX_BOUNTIES, b.count));
            warn("missions.count", b.count, fixed);
            b.count = fixed;
            corrected = true;
        }
        if (b.allMissionsBonusPoints < 0) {
            warn("missions.allMissionsBonusPoints", b.allMissionsBonusPoints, 0);
            b.allMissionsBonusPoints = 0;
            corrected = true;
        }
        if (b.allowedKinds == null) {
            b.allowedKinds = new SafariConfig.Missions().allowedKinds;
            corrected = true;
        } else {
            // Gson reads a name that is no kind as null; duplicates would only weigh twice.
            List<BountyKind> kinds = new ArrayList<>(new LinkedHashSet<>(b.allowedKinds));
            kinds.removeIf(Objects::isNull);
            if (kinds.size() != b.allowedKinds.size()) {
                CobblemonSafari.LOGGER.warn("{}: missions.allowedKinds had unknown or repeated kinds; kept {}",
                        FILE_NAME, kinds);
                b.allowedKinds = kinds;
                corrected = true;
            }
        }
        if (b.kinds == null) {
            b.kinds = new BountyKinds();
            corrected = true;
        }
        corrected |= b.kinds.fillMissing();
        for (BountyKind kind : BountyKind.values()) {
            corrected |= validateKind("missions.kinds." + jsonKey(kind), b.kinds.of(kind));
        }
        BountyKinds.SpeciesBounty species = b.kinds.species;
        if (species.commonCount < 1 || species.uncommonCount < 1 || species.rareCount < 1) {
            warn("missions.kinds.species counts", "below 1", "1");
            species.commonCount = Math.max(1, species.commonCount);
            species.uncommonCount = Math.max(1, species.uncommonCount);
            species.rareCount = Math.max(1, species.rareCount);
            corrected = true;
        }
        BountyKinds.LevelBounty level = b.kinds.level;
        int minLevel = Math.max(1, Math.min(MAX_POKEMON_LEVEL, level.minLevel));
        int maxLevel = Math.max(minLevel, Math.min(MAX_POKEMON_LEVEL, level.maxLevel));
        if (minLevel != level.minLevel || maxLevel != level.maxLevel) {
            warn("missions.kinds.level levels", level.minLevel + "-" + level.maxLevel, minLevel + "-" + maxLevel);
            level.minLevel = minLevel;
            level.maxLevel = maxLevel;
            corrected = true;
        }
        if (b.speciesFilter == null) {
            b.speciesFilter = new BountySpeciesFilter();
            corrected = true;
        }
        BountySpeciesFilter filter = b.speciesFilter;
        if (filter.rarities == null || filter.excludedSpecies == null || filter.excludedTypes == null) {
            BountySpeciesFilter defaults = new BountySpeciesFilter();
            if (filter.rarities == null) filter.rarities = defaults.rarities;
            if (filter.excludedSpecies == null) filter.excludedSpecies = defaults.excludedSpecies;
            if (filter.excludedTypes == null) filter.excludedTypes = defaults.excludedTypes;
            corrected = true;
        }
        List<String> rarities = new ArrayList<>();
        for (String rarity : filter.rarities) {
            String normal = rarity == null ? "" : rarity.toLowerCase(Locale.ROOT).trim();
            if (BOUNTY_RARITIES.contains(normal) && !rarities.contains(normal)) {
                rarities.add(normal);
            }
        }
        if (!rarities.equals(filter.rarities)) {
            warn("missions.speciesFilter.rarities", filter.rarities, rarities);
            filter.rarities = rarities;
            corrected = true;
        }
        if (b.swap == null) {
            b.swap = new BountySwap();
            corrected = true;
        }
        if (b.swap.cost < 0 || b.swap.perRotation < 0 || b.swap.perRotation > MAX_BOUNTY_SWAPS) {
            warn("missions.swap", b.swap.cost + " / " + b.swap.perRotation, "clamped");
            b.swap.cost = Math.max(0, b.swap.cost);
            b.swap.perRotation = Math.max(0, Math.min(MAX_BOUNTY_SWAPS, b.swap.perRotation));
            corrected = true;
        }
        return corrected;
    }

    /** The key of a kind under {@code missions.kinds}, as the file spells it: OUT_OF_BATTLE is outOfBattle. */
    private static String jsonKey(BountyKind kind) {
        StringBuilder key = new StringBuilder();
        for (String word : kind.name().toLowerCase(Locale.ROOT).split("_")) {
            key.append(key.isEmpty() ? word : Character.toUpperCase(word.charAt(0)) + word.substring(1));
        }
        return key.toString();
    }

    /** Fixes one kind's tuning: no negative weight or reward, at least one catch, min not above max. */
    private static boolean validateKind(String field, BountyKindSettings settings) {
        boolean corrected = false;
        if (settings.weight < 0 || settings.rewardPoints < 0 || settings.pointsPerCatch < 0) {
            warn(field, "a negative weight or reward", "0");
            settings.weight = Math.max(0, settings.weight);
            settings.rewardPoints = Math.max(0, settings.rewardPoints);
            settings.pointsPerCatch = Math.max(0, settings.pointsPerCatch);
            corrected = true;
        }
        int min = Math.max(1, settings.minCount);
        int max = Math.max(min, settings.maxCount);
        if (min != settings.minCount || max != settings.maxCount) {
            warn(field + " counts", settings.minCount + "-" + settings.maxCount, min + "-" + max);
            settings.minCount = min;
            settings.maxCount = max;
            corrected = true;
        }
        return corrected;
    }

    /** Fixes the gate section; returns true if anything changed. */
    private static boolean validateGate(SafariConfig c) {
        boolean corrected = false;
        if (c.gate == null) {
            c.gate = new SafariConfig.Gate();
            return true;
        }
        if (c.gate.companions == null) {
            c.gate.companions = new SafariConfig.Gate().companions;
            corrected = true;
        } else {
            List<String> kept = new ArrayList<>();
            for (String raw : c.gate.companions) {
                if (raw != null && !raw.isBlank() && kept.size() < MAX_GATE_COMPANIONS) {
                    kept.add(raw.trim());
                } else {
                    CobblemonSafari.LOGGER.warn("Ignoring gate companion '{}' (blank or more than {})",
                            raw, MAX_GATE_COMPANIONS);
                    corrected = true;
                }
            }
            c.gate.companions = kept;
        }
        if (c.gate.roamRadius < MIN_GATE_ROAM_RADIUS || c.gate.roamRadius > MAX_GATE_ROAM_RADIUS) {
            int fixed = Math.max(MIN_GATE_ROAM_RADIUS, Math.min(MAX_GATE_ROAM_RADIUS, c.gate.roamRadius));
            warn("gate.roamRadius", c.gate.roamRadius, fixed);
            c.gate.roamRadius = fixed;
            corrected = true;
        }
        return corrected;
    }

    /** Clamps a spawn multiplier into its valid range; NaN becomes 1, Cobblemon's normal pace. */
    private static double clampMultiplier(double value) {
        if (Double.isNaN(value)) {
            return 1.0;
        }
        return Math.max(MIN_SPAWN_MULTIPLIER, Math.min(MAX_SPAWN_MULTIPLIER, value));
    }

    /** Fixes the spawns section; returns true if anything changed. */
    private static boolean validateSpawns(SafariConfig c) {
        boolean corrected = false;
        if (c.spawns == null) {
            c.spawns = new SafariConfig.Spawns();
            return true;
        }
        if (!(c.spawns.spawnRateMultiplier >= MIN_SPAWN_MULTIPLIER && c.spawns.spawnRateMultiplier <= MAX_SPAWN_MULTIPLIER)) {
            double fixed = clampMultiplier(c.spawns.spawnRateMultiplier);
            warn("spawns.spawnRateMultiplier", c.spawns.spawnRateMultiplier, fixed);
            c.spawns.spawnRateMultiplier = fixed;
            corrected = true;
        }
        if (!(c.spawns.pokemonPerChunkMultiplier >= MIN_SPAWN_MULTIPLIER
                && c.spawns.pokemonPerChunkMultiplier <= MAX_SPAWN_MULTIPLIER)) {
            double fixed = clampMultiplier(c.spawns.pokemonPerChunkMultiplier);
            warn("spawns.pokemonPerChunkMultiplier", c.spawns.pokemonPerChunkMultiplier, fixed);
            c.spawns.pokemonPerChunkMultiplier = fixed;
            corrected = true;
        }
        if (!(c.spawns.caughtSpeciesWeight >= 0.0D && c.spawns.caughtSpeciesWeight <= 1.0D)) {
            // Also catches NaN. Above 1 would favour what the player already has.
            double fixed = Double.isNaN(c.spawns.caughtSpeciesWeight) ? 1.0D
                    : Math.max(0.0D, Math.min(1.0D, c.spawns.caughtSpeciesWeight));
            warn("spawns.caughtSpeciesWeight", c.spawns.caughtSpeciesWeight, fixed);
            c.spawns.caughtSpeciesWeight = fixed;
            corrected = true;
        }
        if (c.spawns.snackClearRadius < 0 || c.spawns.snackClearRadius > MAX_SNACK_CLEAR_RADIUS) {
            int fixed = Math.max(0, Math.min(MAX_SNACK_CLEAR_RADIUS, c.spawns.snackClearRadius));
            warn("spawns.snackClearRadius", c.spawns.snackClearRadius, fixed);
            c.spawns.snackClearRadius = fixed;
            corrected = true;
        }
        if (!(c.spawns.snackDensityMultiplier >= MIN_SNACK_DENSITY_MULTIPLIER
                && c.spawns.snackDensityMultiplier <= MAX_SNACK_DENSITY_MULTIPLIER)) {
            // Also catches NaN, which falls back to the default.
            double fixed = Double.isNaN(c.spawns.snackDensityMultiplier) ? DEFAULT_SNACK_DENSITY_MULTIPLIER
                    : Math.max(MIN_SNACK_DENSITY_MULTIPLIER,
                    Math.min(MAX_SNACK_DENSITY_MULTIPLIER, c.spawns.snackDensityMultiplier));
            warn("spawns.snackDensityMultiplier", c.spawns.snackDensityMultiplier, fixed);
            c.spawns.snackDensityMultiplier = fixed;
            corrected = true;
        }
        if (c.spawns.maxSameSpeciesNearby < 0) {
            warn("spawns.maxSameSpeciesNearby", c.spawns.maxSameSpeciesNearby, 0);
            c.spawns.maxSameSpeciesNearby = 0;
            corrected = true;
        }
        corrected |= validateSpeciesRules("spawns", c.spawns);
        return corrected;
    }

    /** Fixes one special category section; returns true if anything changed. */
    private static boolean validateCategory(String name, SafariConfig.SpecialCategory category) {
        if (category.maxSpawnPerPokemon < 0) {
            warn(name + ".maxSpawnPerPokemon", category.maxSpawnPerPokemon, 0);
            category.maxSpawnPerPokemon = 0;
            return true;
        }
        return false;
    }

    /** Fixes the specials section; returns true if anything changed. */
    private static boolean validateSpecials(SafariConfig c) {
        if (c.specials == null) {
            c.specials = new SafariConfig.Specials();
            return true;
        }
        SafariConfig.Specials f = c.specials;
        boolean corrected = false;
        if (f.rotationMinutes < 1) {
            warn("specials.rotationMinutes", f.rotationMinutes, 1);
            f.rotationMinutes = 1;
            corrected = true;
        }
        if (f.slots == null) {
            f.slots = new SafariConfig.SpecialSlots();
            corrected = true;
        }
        SafariConfig.SpecialSlots slots = f.slots;
        if (slots.common < 0 || slots.uncommon < 0 || slots.rare < 0 || slots.ultraRare < 0) {
            slots.common = Math.max(0, slots.common);
            slots.uncommon = Math.max(0, slots.uncommon);
            slots.rare = Math.max(0, slots.rare);
            slots.ultraRare = Math.max(0, slots.ultraRare);
            warn("specials.slots", "negative", 0);
            corrected = true;
        }
        if (f.spawn == null) {
            f.spawn = new SafariConfig.SpecialSpawn();
            corrected = true;
        }
        SafariConfig.SpecialSpawn spawn = f.spawn;
        if (spawn.weights == null) {
            spawn.weights = new SafariConfig.SpawnWeights();
            corrected = true;
        }
        SafariConfig.SpawnWeights weights = spawn.weights;
        if (weights.common < 0 || weights.uncommon < 0 || weights.rare < 0 || weights.ultraRare < 0) {
            weights.common = Math.max(0, weights.common);
            weights.uncommon = Math.max(0, weights.uncommon);
            weights.rare = Math.max(0, weights.rare);
            weights.ultraRare = Math.max(0, weights.ultraRare);
            warn("specials.spawn.weights", "negative", 0);
            corrected = true;
        }
        // Legal, but almost always a slip: such specials show on the board and never appear.
        warnIfUnspawnable("common", slots.common, weights.common);
        warnIfUnspawnable("uncommon", slots.uncommon, weights.uncommon);
        warnIfUnspawnable("rare", slots.rare, weights.rare);
        warnIfUnspawnable("ultraRare", slots.ultraRare, weights.ultraRare);
        if (spawn.checkEverySeconds < 1) {
            warn("specials.spawn.checkEverySeconds", spawn.checkEverySeconds, 1);
            spawn.checkEverySeconds = 1;
            corrected = true;
        }
        if (!(spawn.chance >= 0.0 && spawn.chance <= 1.0)) {
            double fixed = Double.isNaN(spawn.chance) ? 0.0 : Math.max(0.0, Math.min(1.0, spawn.chance));
            warn("specials.spawn.chance", spawn.chance, fixed);
            spawn.chance = fixed;
            corrected = true;
        }
        if (spawn.maxAroundPlayer < 0) {
            warn("specials.spawn.maxAroundPlayer", spawn.maxAroundPlayer, 0);
            spawn.maxAroundPlayer = 0;
            corrected = true;
        }
        corrected |= validateSpeciesRules("specials", f);
        return corrected;
    }

    /** Logs a rarity given slots on the board but a spawn weight of 0, so its specials never appear. */
    private static void warnIfUnspawnable(String rarity, int slots, int weight) {
        if (slots > 0 && weight == 0) {
            CobblemonSafari.LOGGER.warn("{}: specials.slots.{} is {} but specials.spawn.weights.{} is 0: those specials are shown on the board but never spawn",
                    FILE_NAME, rarity, slots, rarity);
        }
    }

    /** Fixes the capture.flee section; returns true if anything changed. */
    private static boolean validateFlee(SafariConfig c) {
        if (c.capture == null) {
            c.capture = new SafariConfig.Capture();
            return true;
        }
        boolean corrected = false;
        if (c.capture.flee == null) {
            c.capture.flee = new SafariConfig.Flee();
            return true;
        }
        SafariConfig.Flee f = c.capture.flee;
        double perFailedBall = clampChance("capture.flee.perFailedBall", f.perFailedBall);
        double maxChance = clampChance("capture.flee.maxChance", f.maxChance);
        corrected |= perFailedBall != f.perFailedBall || maxChance != f.maxChance;
        f.perFailedBall = perFailedBall;
        f.maxChance = maxChance;
        if (f.rarities == null) {
            f.rarities = new SafariConfig.FleeRarities();
            return true;
        }
        SafariConfig.FleeRarities r = f.rarities;
        SafariConfig.FleeRarities defaults = new SafariConfig.FleeRarities();
        FleeCheck check = new FleeCheck();
        r.common = check.entry("common", r.common, defaults.common);
        r.uncommon = check.entry("uncommon", r.uncommon, defaults.uncommon);
        r.rare = check.entry("rare", r.rare, defaults.rare);
        r.ultraRare = check.entry("ultraRare", r.ultraRare, defaults.ultraRare);
        r.legendary = check.entry("legendary", r.legendary, defaults.legendary);
        r.mythical = check.entry("mythical", r.mythical, defaults.mythical);
        r.ultraBeast = check.entry("ultraBeast", r.ultraBeast, defaults.ultraBeast);
        r.paradox = check.entry("paradox", r.paradox, defaults.paradox);
        return corrected || check.corrected;
    }

    /**
     * A chance brought back between 0 and 1, logged when it was not. NaN
     * becomes 0 (no flight), the safe reading of a value that means nothing.
     */
    private static double clampChance(String field, double value) {
        if (value >= 0.0D && value <= 1.0D) {
            return value;
        }
        double fixed = Double.isNaN(value) ? 0.0D : Math.max(0.0D, Math.min(1.0D, value));
        warn(field, value, fixed);
        return fixed;
    }

    /** Checks the entries of {@code capture.flee.rarities}, remembering whether any was fixed. */
    private static final class FleeCheck {
        /** Whether an entry was missing, incomplete or out of bounds. */
        boolean corrected;

        /**
         * The entry, completed from its own defaults where a key is missing
         * (a hand-written entry may give only {@code enabled}) and with its
         * chance between 0 and 1.
         */
        SafariConfig.FleeRarity entry(String name, SafariConfig.FleeRarity entry, SafariConfig.FleeRarity fallback) {
            if (entry == null) {
                corrected = true;
                return fallback;
            }
            if (entry.enabled == null) {
                entry.enabled = fallback.enabled;
                corrected = true;
            }
            if (entry.chance == null) {
                entry.chance = fallback.chance;
                corrected = true;
            }
            double chance = clampChance("capture.flee.rarities." + name + ".chance", entry.chance);
            if (chance != entry.chance) {
                entry.chance = chance;
                corrected = true;
            }
            return entry;
        }
    }

    /** Bounds of events.swarm.weightMultiplier: 1 is no boost; beyond 100 the swarm is all that spawns. */
    private static final double MAX_SWARM_MULTIPLIER = 100.0D;
    /**
     * Bounds of events.swarm.checkEverySeconds. Below 1 s the swarm spawner
     * would scan a zone per player every tick; beyond 5 minutes a caught swarm
     * Pokémon would take that long to be replaced, and the swarm looks empty.
     */
    private static final int MIN_SWARM_CHECK_SECONDS = 1;
    private static final int MAX_SWARM_CHECK_SECONDS = 300;
    /** Upper bound of events.fullMoon.weightMultiplier. */
    private static final double MAX_FULL_MOON_MULTIPLIER = 20.0D;

    /** Fixes the events section; returns true if anything changed. */
    private static boolean validateEvents(SafariConfig c) {
        boolean corrected = false;
        if (c.events == null) {
            c.events = new SafariConfig.Events();
            return true;
        }
        if (c.events.swarm == null) {
            c.events.swarm = new SafariConfig.Swarm();
            corrected = true;
        }
        if (c.events.fullMoon == null) {
            c.events.fullMoon = new SafariConfig.FullMoon();
            corrected = true;
        }
        SafariConfig.Swarm swarm = c.events.swarm;
        if (!(swarm.chance >= 0.0D && swarm.chance <= 1.0D)) {
            double fixed = Double.isNaN(swarm.chance) ? 0.0D : Math.max(0.0D, Math.min(1.0D, swarm.chance));
            warn("events.swarm.chance", swarm.chance, fixed);
            swarm.chance = fixed;
            corrected = true;
        }
        if (!(swarm.weightMultiplier >= 1.0D && swarm.weightMultiplier <= MAX_SWARM_MULTIPLIER)) {
            double fixed = Double.isNaN(swarm.weightMultiplier) ? 1.0D
                    : Math.max(1.0D, Math.min(MAX_SWARM_MULTIPLIER, swarm.weightMultiplier));
            warn("events.swarm.weightMultiplier", swarm.weightMultiplier, fixed);
            swarm.weightMultiplier = fixed;
            corrected = true;
        }
        corrected |= validateSwarmBuckets(swarm);
        if (swarm.rarities == null) {
            swarm.rarities = new SafariConfig.SwarmCategories();
            corrected = true;
        }
        corrected |= ensureSwarmCategories(swarm.rarities);
        corrected |= validateCategory("events.swarm.rarities.legendary", swarm.rarities.legendary);
        corrected |= validateCategory("events.swarm.rarities.mythical", swarm.rarities.mythical);
        corrected |= validateCategory("events.swarm.rarities.ultraBeast", swarm.rarities.ultraBeast);
        corrected |= validateCategory("events.swarm.rarities.paradox", swarm.rarities.paradox);
        if (swarm.weights == null) {
            swarm.weights = new SafariConfig.SpawnWeights();
            corrected = true;
        }
        SafariConfig.SpawnWeights swarmWeights = swarm.weights;
        if (swarmWeights.common < 0 || swarmWeights.uncommon < 0 || swarmWeights.rare < 0 || swarmWeights.ultraRare < 0) {
            CobblemonSafari.LOGGER.warn("Negative events.swarm.weights in {}, using 0 for those", FILE_NAME);
            swarmWeights.common = Math.max(0, swarmWeights.common);
            swarmWeights.uncommon = Math.max(0, swarmWeights.uncommon);
            swarmWeights.rare = Math.max(0, swarmWeights.rare);
            swarmWeights.ultraRare = Math.max(0, swarmWeights.ultraRare);
            corrected = true;
        }
        if (swarm.excludeList == null) {
            swarm.excludeList = new ArrayList<>();
            corrected = true;
        }
        // The entries themselves are checked at the draw: reading them needs
        // Cobblemon's species, which are not loaded yet when the config is.
        if (swarm.minNearby < 0) {
            warn("events.swarm.minNearby", swarm.minNearby, 0);
            swarm.minNearby = 0;
            corrected = true;
        }
        if (swarm.checkEverySeconds < MIN_SWARM_CHECK_SECONDS || swarm.checkEverySeconds > MAX_SWARM_CHECK_SECONDS) {
            int fixed = Math.max(MIN_SWARM_CHECK_SECONDS, Math.min(MAX_SWARM_CHECK_SECONDS, swarm.checkEverySeconds));
            warn("events.swarm.checkEverySeconds", swarm.checkEverySeconds, fixed);
            swarm.checkEverySeconds = fixed;
            corrected = true;
        }
        SafariConfig.FullMoon moon = c.events.fullMoon;
        if (moon.types == null) {
            moon.types = new ArrayList<>();
            corrected = true;
        }
        if (!(moon.weightMultiplier >= 1.0D && moon.weightMultiplier <= MAX_FULL_MOON_MULTIPLIER)) {
            double fixed = Double.isNaN(moon.weightMultiplier) ? 1.0D
                    : Math.max(1.0D, Math.min(MAX_FULL_MOON_MULTIPLIER, moon.weightMultiplier));
            warn("events.fullMoon.weightMultiplier", moon.weightMultiplier, fixed);
            moon.weightMultiplier = fixed;
            corrected = true;
        }
        return corrected;
    }

    /** Gives back a missing swarm category its default; returns true if any was missing. */
    private static boolean ensureSwarmCategories(SafariConfig.SwarmCategories categories) {
        SafariConfig.SwarmCategories defaults = new SafariConfig.SwarmCategories();
        boolean corrected = false;
        if (categories.legendary == null) {
            categories.legendary = defaults.legendary;
            corrected = true;
        }
        if (categories.mythical == null) {
            categories.mythical = defaults.mythical;
            corrected = true;
        }
        if (categories.ultraBeast == null) {
            categories.ultraBeast = defaults.ultraBeast;
            corrected = true;
        }
        if (categories.paradox == null) {
            categories.paradox = defaults.paradox;
            corrected = true;
        }
        return corrected;
    }

    /**
     * Fixes events.swarm.buckets: a missing list gets the default back,
     * values are written in their config spelling ("ultra-rare"), unknown ones
     * and repeats are dropped. Returns true if anything changed.
     */
    private static boolean validateSwarmBuckets(SafariConfig.Swarm swarm) {
        if (swarm.buckets == null) {
            swarm.buckets = new SafariConfig.Swarm().buckets;
            return true;
        }
        List<String> fixed = new ArrayList<>();
        for (String value : swarm.buckets) {
            String canonical = SwarmRules.canonicalRarity(value);
            if (canonical == null) {
                CobblemonSafari.LOGGER.warn("Invalid events.swarm.buckets entry \"{}\" in {}, dropped"
                        + " (expected common, uncommon, rare or ultra-rare)", value, FILE_NAME);
            } else if (!fixed.contains(canonical)) {
                fixed.add(canonical);
            }
        }
        if (fixed.isEmpty()) {
            // Allowed, but worth saying: it silently means no swarm ever.
            CobblemonSafari.LOGGER.warn("events.swarm.buckets in {} allows no bucket: no swarm will be drawn", FILE_NAME);
        }
        if (fixed.equals(swarm.buckets)) {
            return false;
        }
        swarm.buckets = fixed;
        return true;
    }

    /** Logs a corrected config value with the replacement used. */
    private static void warn(String field, Object bad, Object replacement) {
        CobblemonSafari.LOGGER.warn("Invalid {}={} in {}, using {}", field, bad, FILE_NAME, replacement);
    }
}
