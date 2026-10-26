package dev.darcosse.common.cobblemonsafari.config;

import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import dev.darcosse.common.cobblemonsafari.bounty.BountyKind;
import dev.darcosse.common.cobblemonsafari.bounty.SafariBounties;
import dev.darcosse.common.cobblemonsafari.util.Ticks;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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
     * three former rewards of {@code bounties} moved.
     */
    public static final int CURRENT_CONFIG_VERSION = 4;

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

    /**
     * Default restHouseTravel.cooldownSeconds. Long enough that the houses
     * are not an instant network; short enough to come back to a house
     * after the next cave.
     */
    public static final int DEFAULT_TRAVEL_COOLDOWN_SECONDS = 30;

    /**
     * Default restHouseTravel.cinematicSeconds: long enough to read the
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
            new JsonConfigFile<>(FILE_NAME, SafariConfig.class, SafariConfig::new);

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
     * Converts a file written by an older version of the mod, before it is
     * validated. Each step only runs for files older than the version that
     * introduced it.
     */
    private static void migrate(SafariConfig c) {
        // Gson builds a missing featured section, or missing keys in it, from
        // the default constructor, so "absent" cannot be told from "set to the
        // defaults": the version is the only reliable signal. Without this
        // copy, a server that allowed legendaries in v1 would silently lose
        // them from the board on update.
        if (c.configVersion < SPLIT_SPECIES_RULES_VERSION && c.spawns != null) {
            if (c.featured == null) {
                c.featured = new SafariConfig.Featured();
            }
            ensureCategories(c.spawns);
            if (c.spawns.excludedSpecies == null) {
                c.spawns.excludedSpecies = new ArrayList<>();
            }
            c.featured.copyRulesFrom(c.spawns);
            CobblemonSafari.LOGGER.info("Copied the species rules of spawns to featured in {} (config version {} -> {})",
                    FILE_NAME, c.configVersion, CURRENT_CONFIG_VERSION);
        }
        // Read whatever the version: a file saved by a build between 2 and 3
        // may carry the old key with version 2 or, rewritten by hand, 3.
        if (c.escapeRope != null && c.escapeRope.restHouseCooldownSeconds != null) {
            if (c.restHouseTravel == null) {
                c.restHouseTravel = new SafariConfig.RestHouseTravel();
            }
            if (c.configVersion < TRAVEL_SECTION_VERSION) {
                c.restHouseTravel.cooldownSeconds = c.escapeRope.restHouseCooldownSeconds;
                CobblemonSafari.LOGGER.info("Moved escapeRope.restHouseCooldownSeconds to restHouseTravel.cooldownSeconds in {}",
                        FILE_NAME);
            }
            c.escapeRope.restHouseCooldownSeconds = null;
        }
        migrateBounties(c);
    }

    /**
     * Moves the three rewards {@code bounties} had before version 4 into the
     * kinds they belong to. Read whatever the version, for a file edited by
     * hand; only a file older than 4 has its values carried over.
     */
    private static void migrateBounties(SafariConfig c) {
        SafariConfig.Bounties b = c.bounties;
        if (b == null) {
            return;
        }
        if (b.kinds == null) {
            b.kinds = new BountyKinds();
        }
        b.kinds.fillMissing();
        if (c.configVersion < BOUNTY_KINDS_VERSION) {
            boolean moved = false;
            if (b.typeRewardPerCatch != null) {
                b.kinds.type.rewardPerCatch = b.typeRewardPerCatch;
                moved = true;
            }
            if (b.speciesReward != null) {
                b.kinds.species.reward = b.speciesReward;
                moved = true;
            }
            if (b.featuredReward != null) {
                b.kinds.featured.reward = b.featuredReward;
                moved = true;
            }
            if (moved) {
                CobblemonSafari.LOGGER.info("Moved the bounty rewards of {} into bounties.kinds", FILE_NAME);
            }
        }
        b.typeRewardPerCatch = null;
        b.speciesReward = null;
        b.featuredReward = null;
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
        return Ticks.fromSeconds(config.maxDurationSeconds);
    }

    /** Extra time allowed after expiry while in battle, in ticks. */
    public static int battleGraceTicks() {
        return Ticks.fromSeconds(config.battleGraceSeconds);
    }

    /** Recomputes the parsed and converted values read on hot paths, after every load. */
    private static void rebuildDerived() {
        Set<ResourceLocation> balls = new LinkedHashSet<>();
        for (String raw : config.allowedBalls) {
            ResourceLocation id = ResourceLocation.tryParse(raw);
            if (id != null) {
                balls.add(id);
            }
        }
        loanedBallItem = config.loan.count > 0 ? ResourceLocation.tryParse(config.loan.ballItem) : null;
        // The loaned ball must always work inside: an admin removing safari_ball
        // from allowedBalls would otherwise hand out balls nobody can throw.
        // Ball ids and item ids coincide for every Cobblemon ball.
        if (loanedBallItem != null) {
            balls.add(loanedBallItem);
        }
        allowedBalls = Collections.unmodifiableSet(balls);

        Set<Integer> ticks = new LinkedHashSet<>();
        for (int seconds : config.warningThresholdsSeconds) {
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
     * or {@code featured}).
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

        if (c.maxDurationSeconds < MIN_DURATION_SECONDS) {
            warn("maxDurationSeconds", c.maxDurationSeconds, defaults.maxDurationSeconds);
            c.maxDurationSeconds = defaults.maxDurationSeconds;
            corrected = true;
        }
        if (c.battleGraceSeconds < 0) {
            warn("battleGraceSeconds", c.battleGraceSeconds, 0);
            c.battleGraceSeconds = 0;
            corrected = true;
        }
        if (c.exitDestination == null) {
            warn("exitDestination", null, defaults.exitDestination);
            c.exitDestination = defaults.exitDestination;
            corrected = true;
        }

        if (c.allowedBalls == null) {
            warn("allowedBalls", null, defaults.allowedBalls);
            c.allowedBalls = defaults.allowedBalls;
            corrected = true;
        } else {
            List<String> kept = new ArrayList<>();
            for (String raw : c.allowedBalls) {
                if (raw != null && ResourceLocation.tryParse(raw) != null) {
                    kept.add(raw);
                } else {
                    CobblemonSafari.LOGGER.warn("Ignoring invalid ball id '{}' in allowedBalls", raw);
                    corrected = true;
                }
            }
            c.allowedBalls = kept;
        }
        // An empty list is legal (no capture at all), but almost always a mistake worth flagging.
        if (c.allowedBalls.isEmpty()) {
            CobblemonSafari.LOGGER.warn("allowedBalls is empty: no Pokémon can be caught inside the Safari");
        }

        if (c.warningThresholdsSeconds == null) {
            c.warningThresholdsSeconds = defaults.warningThresholdsSeconds;
            corrected = true;
        } else {
            List<Integer> kept = new ArrayList<>();
            for (Integer seconds : c.warningThresholdsSeconds) {
                if (seconds != null && seconds > 0 && !kept.contains(seconds)) {
                    kept.add(seconds);
                } else {
                    corrected = true;
                }
            }
            kept.sort(Comparator.reverseOrder());
            c.warningThresholdsSeconds = kept;
        }

        if (c.protection == null) {
            c.protection = new SafariConfig.Protection();
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
        if (c.loan == null) {
            c.loan = new SafariConfig.Loan();
            corrected = true;
        }
        if (c.loan.count < 0 || c.loan.count > MAX_LOANED_BALLS) {
            int fixed = Math.max(0, Math.min(MAX_LOANED_BALLS, c.loan.count));
            warn("loan.count", c.loan.count, fixed);
            c.loan.count = fixed;
            corrected = true;
        }
        if (c.loan.ballItem == null || ResourceLocation.tryParse(c.loan.ballItem) == null) {
            warn("loan.ballItem", c.loan.ballItem, new SafariConfig.Loan().ballItem);
            c.loan.ballItem = new SafariConfig.Loan().ballItem;
            corrected = true;
        }
        if (c.ticket == null) {
            c.ticket = new SafariConfig.Ticket();
            corrected = true;
        }
        if (c.ticket.defaultDurationSeconds < MIN_DURATION_SECONDS) {
            int fixed = new SafariConfig.Ticket().defaultDurationSeconds;
            warn("ticket.defaultDurationSeconds", c.ticket.defaultDurationSeconds, fixed);
            c.ticket.defaultDurationSeconds = fixed;
            corrected = true;
        }
        if (c.ticketLoot == null) {
            c.ticketLoot = new SafariConfig.TicketLoot();
            corrected = true;
        }
        if (c.ticketLoot.sources == null) {
            c.ticketLoot.sources = new ArrayList<>();
            corrected = true;
        }
        int sourceCount = c.ticketLoot.sources.size();
        c.ticketLoot.sources.removeIf(source -> source == null || source.table == null
                || ResourceLocation.tryParse(source.table) == null);
        if (c.ticketLoot.sources.size() != sourceCount) {
            CobblemonSafari.LOGGER.warn("Removed ticketLoot sources with an invalid table id from {}", FILE_NAME);
            corrected = true;
        }
        for (SafariConfig.TicketLoot.Source source : c.ticketLoot.sources) {
            if (!(source.chance >= 0.0D && source.chance <= 1.0D)) {
                warn("ticketLoot chance of " + source.table, source.chance, 0);
                source.chance = Math.max(0.0D, Math.min(1.0D, Double.isNaN(source.chance) ? 0.0D : source.chance));
                corrected = true;
            }
        }
        if (c.ticket.minAddedSeconds < 0) {
            warn("ticket.minAddedSeconds", c.ticket.minAddedSeconds, 0);
            c.ticket.minAddedSeconds = 0;
            corrected = true;
        }
        if (c.ticket.maxCreditSeconds < 0) {
            warn("ticket.maxCreditSeconds", c.ticket.maxCreditSeconds, 0);
            c.ticket.maxCreditSeconds = 0;
            corrected = true;
        }
        if (c.reset == null) {
            c.reset = new SafariConfig.Reset();
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
        corrected |= validateGate(c);
        corrected |= validatePoints(c);
        corrected |= validateBounties(c);
        if (c.escapeRope == null) {
            c.escapeRope = new SafariConfig.EscapeRope();
            corrected = true;
        }
        corrected |= validateTravel(c);
        corrected |= validateSpawns(c);
        corrected |= validateFeatured(c);
        return corrected;
    }

    /** Fixes the rest house travel section: no negative wait, a bounded cinematic. Returns true if anything changed. */
    private static boolean validateTravel(SafariConfig c) {
        if (c.restHouseTravel == null) {
            c.restHouseTravel = new SafariConfig.RestHouseTravel();
            return true;
        }
        SafariConfig.RestHouseTravel t = c.restHouseTravel;
        boolean corrected = false;
        if (t.cooldownSeconds < 0) {
            warn("restHouseTravel.cooldownSeconds", t.cooldownSeconds, 0);
            t.cooldownSeconds = 0;
            corrected = true;
        }
        if (t.cinematicSeconds < 0 || t.cinematicSeconds > MAX_TRAVEL_CINEMATIC_SECONDS) {
            int fixed = Math.max(0, Math.min(MAX_TRAVEL_CINEMATIC_SECONDS, t.cinematicSeconds));
            warn("restHouseTravel.cinematicSeconds", t.cinematicSeconds, fixed);
            t.cinematicSeconds = fixed;
            corrected = true;
        }
        // Gson leaves null for a value that names no constant (a typo).
        if (t.cinematicStyle == null) {
            CobblemonSafari.LOGGER.warn("{}: restHouseTravel.cinematicStyle is not FLIGHT or PANORAMA; using FLIGHT",
                    FILE_NAME);
            t.cinematicStyle = TravelCinematicStyle.FLIGHT;
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
        if (p.common < 0 || p.uncommon < 0 || p.rare < 0 || p.ultraRare < 0
                || p.featuredBonus < 0 || p.shinyBonus < 0) {
            warn("points rewards", "a negative value", "0");
            p.common = Math.max(0, p.common);
            p.uncommon = Math.max(0, p.uncommon);
            p.rare = Math.max(0, p.rare);
            p.ultraRare = Math.max(0, p.ultraRare);
            p.featuredBonus = Math.max(0, p.featuredBonus);
            p.shinyBonus = Math.max(0, p.shinyBonus);
            corrected = true;
        }
        return corrected;
    }

    /**
     * Fixes the bounties section: a count of 0 to 8, known kinds only,
     * sane tunings, known rarities. Returns true if anything changed.
     */
    private static boolean validateBounties(SafariConfig c) {
        if (c.bounties == null) {
            c.bounties = new SafariConfig.Bounties();
            return true;
        }
        SafariConfig.Bounties b = c.bounties;
        boolean corrected = false;
        if (b.count < 0 || b.count > MAX_BOUNTIES) {
            int fixed = Math.max(0, Math.min(MAX_BOUNTIES, b.count));
            warn("bounties.count", b.count, fixed);
            b.count = fixed;
            corrected = true;
        }
        if (b.allDoneBonus < 0) {
            warn("bounties.allDoneBonus", b.allDoneBonus, 0);
            b.allDoneBonus = 0;
            corrected = true;
        }
        if (b.allowedKinds == null) {
            b.allowedKinds = new SafariConfig.Bounties().allowedKinds;
            corrected = true;
        } else {
            // Gson reads a name that is no kind as null; duplicates would only weigh twice.
            List<BountyKind> kinds = new ArrayList<>(new LinkedHashSet<>(b.allowedKinds));
            kinds.removeIf(Objects::isNull);
            if (kinds.size() != b.allowedKinds.size()) {
                CobblemonSafari.LOGGER.warn("{}: bounties.allowedKinds had unknown or repeated kinds; kept {}",
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
            corrected |= validateKind("bounties.kinds." + jsonKey(kind), b.kinds.of(kind));
        }
        BountyKinds.SpeciesBounty species = b.kinds.species;
        if (species.commonCount < 1 || species.uncommonCount < 1 || species.rareCount < 1) {
            warn("bounties.kinds.species counts", "below 1", "1");
            species.commonCount = Math.max(1, species.commonCount);
            species.uncommonCount = Math.max(1, species.uncommonCount);
            species.rareCount = Math.max(1, species.rareCount);
            corrected = true;
        }
        BountyKinds.LevelBounty level = b.kinds.level;
        int minLevel = Math.max(1, Math.min(MAX_POKEMON_LEVEL, level.minLevel));
        int maxLevel = Math.max(minLevel, Math.min(MAX_POKEMON_LEVEL, level.maxLevel));
        if (minLevel != level.minLevel || maxLevel != level.maxLevel) {
            warn("bounties.kinds.level levels", level.minLevel + "-" + level.maxLevel, minLevel + "-" + maxLevel);
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
            warn("bounties.speciesFilter.rarities", filter.rarities, rarities);
            filter.rarities = rarities;
            corrected = true;
        }
        if (b.swap == null) {
            b.swap = new BountySwap();
            corrected = true;
        }
        if (b.swap.cost < 0 || b.swap.perRotation < 0 || b.swap.perRotation > MAX_BOUNTY_SWAPS) {
            warn("bounties.swap", b.swap.cost + " / " + b.swap.perRotation, "clamped");
            b.swap.cost = Math.max(0, b.swap.cost);
            b.swap.perRotation = Math.max(0, Math.min(MAX_BOUNTY_SWAPS, b.swap.perRotation));
            corrected = true;
        }
        return corrected;
    }

    /** The key of a kind under {@code bounties.kinds}, as the file spells it: OUT_OF_BATTLE is outOfBattle. */
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
        if (settings.weight < 0 || settings.reward < 0 || settings.rewardPerCatch < 0) {
            warn(field, "a negative weight or reward", "0");
            settings.weight = Math.max(0, settings.weight);
            settings.reward = Math.max(0, settings.reward);
            settings.rewardPerCatch = Math.max(0, settings.rewardPerCatch);
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

    /** Fixes the featured section; returns true if anything changed. */
    private static boolean validateFeatured(SafariConfig c) {
        if (c.featured == null) {
            c.featured = new SafariConfig.Featured();
            return true;
        }
        SafariConfig.Featured f = c.featured;
        boolean corrected = false;
        if (f.rotationMinutes < 1) {
            warn("featured.rotationMinutes", f.rotationMinutes, 1);
            f.rotationMinutes = 1;
            corrected = true;
        }
        if (f.uncommonSlots < 0 || f.rareSlots < 0 || f.ultraRareSlots < 0) {
            f.uncommonSlots = Math.max(0, f.uncommonSlots);
            f.rareSlots = Math.max(0, f.rareSlots);
            f.ultraRareSlots = Math.max(0, f.ultraRareSlots);
            warn("featured.*Slots", "negative", 0);
            corrected = true;
        }
        if (f.weightUncommon < 0 || f.weightRare < 0 || f.weightUltraRare < 0) {
            f.weightUncommon = Math.max(0, f.weightUncommon);
            f.weightRare = Math.max(0, f.weightRare);
            f.weightUltraRare = Math.max(0, f.weightUltraRare);
            warn("featured.weight*", "negative", 0);
            corrected = true;
        }
        if (f.spawnIntervalSeconds < 1) {
            warn("featured.spawnIntervalSeconds", f.spawnIntervalSeconds, 1);
            f.spawnIntervalSeconds = 1;
            corrected = true;
        }
        if (!(f.spawnChance >= 0.0 && f.spawnChance <= 1.0)) {
            double fixed = Double.isNaN(f.spawnChance) ? 0.0 : Math.max(0.0, Math.min(1.0, f.spawnChance));
            warn("featured.spawnChance", f.spawnChance, fixed);
            f.spawnChance = fixed;
            corrected = true;
        }
        if (f.maxNearbyPerPlayer < 0) {
            warn("featured.maxNearbyPerPlayer", f.maxNearbyPerPlayer, 0);
            f.maxNearbyPerPlayer = 0;
            corrected = true;
        }
        corrected |= validateSpeciesRules("featured", f);
        return corrected;
    }

    /** Logs a corrected config value with the replacement used. */
    private static void warn(String field, Object bad, Object replacement) {
        CobblemonSafari.LOGGER.warn("Invalid {}={} in {}, using {}", field, bad, FILE_NAME, replacement);
    }
}
