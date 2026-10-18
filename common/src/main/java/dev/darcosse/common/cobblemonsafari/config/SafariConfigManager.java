package dev.darcosse.common.cobblemonsafari.config;

import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import dev.darcosse.common.cobblemonsafari.util.Ticks;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.lang.reflect.Field;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
    /** Where the file lived before the mod had its own config folder; moved into it on load. */
    private static final String LEGACY_FILE_NAME = CobblemonSafari.MOD_ID + ".json";

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
     * Most missions per rotation. /safari bounties lists them in chat; past
     * about eight lines the list scrolls out of the chat box.
     */
    private static final int MAX_BOUNTIES = 8;

    /** Version written by this build. Raise it together with a new step in {@link #migrate}. */
    public static final int CURRENT_CONFIG_VERSION = 22;

    /**
     * Default length of a featured rotation, in minutes: four hours, so the
     * specials and bounties change several times in a play day.
     */
    public static final int DEFAULT_ROTATION_MINUTES = 240;

    /**
     * The default before version 13 (one day). A file holding exactly this
     * value is taken as never customised and moved to the new default; any
     * other value was chosen by the admin and is kept.
     */
    private static final int LEGACY_DEFAULT_ROTATION_MINUTES = 1440;

    /** First version whose default rotation is DEFAULT_ROTATION_MINUTES. */
    private static final int SHORT_ROTATION_VERSION = 13;

    /** First version whose default special slots are 1/2/3. */
    private static final int REVERSED_SLOTS_VERSION = 14;

    /**
     * Default spawns.caughtSpeciesWeight, and the one of version 19. At 0.25
     * a caught species was rare enough to feel gone from the Safari.
     */
    public static final double DEFAULT_CAUGHT_SPECIES_WEIGHT = 0.4;
    private static final double LEGACY_CAUGHT_SPECIES_WEIGHT = 0.25;

    /** First version without a ball loan by default; a file still lending the old 30 follows. */
    private static final int NO_LOAN_VERSION = 22;

    /** Balls lent per session by default up to version 21. */
    private static final int LEGACY_LOAN_COUNT = 30;

    /** First version with one section per special category instead of the allow* flags. */
    private static final int SPECIAL_CATEGORIES_VERSION = 21;

    /** First version whose default caughtSpeciesWeight is 0.4. */
    private static final int GENTLER_NOVELTY_VERSION = 20;

    /** First version whose default capture points and bounty rewards are about five times higher. */
    private static final int RICHER_REWARDS_VERSION = 18;

    /**
     * Capture points and bounty rewards by default before version 18, by
     * field name. A value still equal to its old default was never chosen
     * by the admin and follows the new one; any other value is kept. Field
     * names are the JSON keys, so a renamed field must be renamed here too.
     */
    private static final Map<String, Integer> LEGACY_POINTS = Map.of(
            "common", 2, "uncommon", 5, "rare", 12, "ultraRare", 30,
            "featuredBonus", 10, "shinyBonus", 50);
    private static final Map<String, Integer> LEGACY_BOUNTY_REWARDS = Map.of(
            "typeRewardPerCatch", 12, "speciesReward", 40, "featuredReward", 80, "allDoneBonus", 50);

    /**
     * Special slots by default before version 14. A file holding exactly
     * these never changed them and gets the new defaults; any other mix was
     * chosen by the admin and is kept.
     */
    private static final int LEGACY_UNCOMMON_SLOTS = 3;
    private static final int LEGACY_RARE_SLOTS = 2;
    private static final int LEGACY_ULTRA_RARE_SLOTS = 1;

    /**
     * Upper bound on loaned balls. A full inventory holds 36 stacks; beyond a
     * few stacks the surplus is dropped on the ground at the entrance, where
     * other players could pick it up.
     */
    private static final int MAX_LOANED_BALLS = 256;

    /** Shortest session accepted. Zero would expire on the very tick it starts. */
    private static final int MIN_DURATION_SECONDS = 1;


    private static final JsonConfigFile<SafariConfig> FILE =
            new JsonConfigFile<>(FILE_NAME, LEGACY_FILE_NAME, SafariConfig.class, SafariConfig::new);

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
        boolean migrated = migrate(loaded);
        if (validate(loaded) || migrated) {
            FILE.write(loaded);
        }
        config = loaded;
        rebuildDerived();
        // Same lifecycle: /safari reload re-reads the shop too.
        SafariShopConfigManager.load();
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
        // from allowedBalls would otherwise hand out 30 balls nobody can throw.
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
     * Upgrades a file written by an older build. Only changes of meaning or
     * of default need a step here: a field added or removed needs none, Gson
     * fills new fields with their defaults, ignores removed ones, and the
     * rewrite that follows a version bump shows the result in the file.
     *
     * @return true if the file must be rewritten
     */
    private static boolean migrate(SafariConfig c) {
        if (c.configVersion >= CURRENT_CONFIG_VERSION) {
            return false;
        }
        // Defaults that changed: a value still equal to the old default was
        // never chosen by the admin, so it follows the new one.
        if (c.configVersion < SHORT_ROTATION_VERSION && c.featured != null
                && c.featured.rotationMinutes == LEGACY_DEFAULT_ROTATION_MINUTES) {
            c.featured.rotationMinutes = DEFAULT_ROTATION_MINUTES;
        }
        if (c.configVersion < REVERSED_SLOTS_VERSION && c.featured != null && c.featured.uncommonSlots == LEGACY_UNCOMMON_SLOTS
                && c.featured.rareSlots == LEGACY_RARE_SLOTS && c.featured.ultraRareSlots == LEGACY_ULTRA_RARE_SLOTS) {
            SafariConfig.Featured defaults = new SafariConfig.Featured();
            c.featured.uncommonSlots = defaults.uncommonSlots;
            c.featured.rareSlots = defaults.rareSlots;
            c.featured.ultraRareSlots = defaults.ultraRareSlots;
        }
        if (c.configVersion < RICHER_REWARDS_VERSION) {
            if (c.points != null) {
                followNewDefaults(c.points, new SafariConfig.Points(), LEGACY_POINTS);
            }
            if (c.bounties != null) {
                followNewDefaults(c.bounties, new SafariConfig.Bounties(), LEGACY_BOUNTY_REWARDS);
            }
        }
        // Exact comparison on purpose: only the untouched old default follows.
        if (c.configVersion < GENTLER_NOVELTY_VERSION && c.spawns != null
                && c.spawns.caughtSpeciesWeight == LEGACY_CAUGHT_SPECIES_WEIGHT) {
            c.spawns.caughtSpeciesWeight = DEFAULT_CAUGHT_SPECIES_WEIGHT;
        }
        if (c.configVersion < SPECIAL_CATEGORIES_VERSION && c.spawns != null) {
            moveLegacyAllowFlags(c.spawns);
        }
        // Exact comparison: a server that chose its own number keeps its loan.
        if (c.configVersion < NO_LOAN_VERSION && c.loan != null && c.loan.count == LEGACY_LOAN_COUNT) {
            c.loan.count = 0;
            CobblemonSafari.LOGGER.info("Migrated {}: the ball loan is now off (loan.count 0)", FILE_NAME);
        }
        CobblemonSafari.LOGGER.info("Migrated {} from version {} to {}", FILE_NAME, c.configVersion,
                CURRENT_CONFIG_VERSION);
        c.configVersion = CURRENT_CONFIG_VERSION;
        return true;
    }

    /**
     * Carries the version 20 allow* flags into the category sections, then
     * clears them so the rewrite drops their keys. A flag missing from the
     * file keeps the section's default (not allowed).
     */
    private static void moveLegacyAllowFlags(SafariConfig.Spawns spawns) {
        // Runs before validate(), which would otherwise create missing sections:
        // a hand-written "legendary": null must not swallow the old flag.
        ensureCategories(spawns);
        if (spawns.allowLegendary != null) {
            spawns.legendary.allowSpawn = spawns.allowLegendary;
        }
        if (spawns.allowMythical != null) {
            spawns.mythical.allowSpawn = spawns.allowMythical;
        }
        if (spawns.allowUltraBeast != null) {
            spawns.ultraBeast.allowSpawn = spawns.allowUltraBeast;
        }
        if (spawns.allowParadox != null) {
            spawns.paradox.allowSpawn = spawns.allowParadox;
        }
        spawns.allowLegendary = null;
        spawns.allowMythical = null;
        spawns.allowUltraBeast = null;
        spawns.allowParadox = null;
    }

    /**
     * Replaces missing category sections with defaults.
     *
     * @return true if one was missing
     */
    private static boolean ensureCategories(SafariConfig.Spawns spawns) {
        boolean missing = false;
        if (spawns.legendary == null) {
            spawns.legendary = new SafariConfig.SpecialCategory();
            missing = true;
        }
        if (spawns.mythical == null) {
            spawns.mythical = new SafariConfig.SpecialCategory();
            missing = true;
        }
        if (spawns.ultraBeast == null) {
            spawns.ultraBeast = new SafariConfig.SpecialCategory();
            missing = true;
        }
        if (spawns.paradox == null) {
            spawns.paradox = SafariConfig.SpecialCategory.of(SafariConfig.Spawns.PARADOX_DEFAULT_ALLOWED,
                    SafariConfig.Spawns.PARADOX_DEFAULT_CAP);
            missing = true;
        }
        return missing;
    }

    /**
     * Moves each int field of a config section still holding its old default
     * to the current default (read from a fresh instance), field by field.
     * Reflection keeps the old defaults a plain table instead of one if per
     * field; an unknown name in the table is a programming error, logged.
     */
    private static void followNewDefaults(Object section, Object defaults, Map<String, Integer> legacy) {
        for (Map.Entry<String, Integer> old : legacy.entrySet()) {
            try {
                Field field = section.getClass().getField(old.getKey());
                if (field.getInt(section) == old.getValue()) {
                    field.setInt(section, field.getInt(defaults));
                }
            } catch (ReflectiveOperationException | IllegalArgumentException e) {
                CobblemonSafari.LOGGER.error("Cannot migrate {}.{} in {}: {}",
                        section.getClass().getSimpleName(), old.getKey(), FILE_NAME, e.toString());
            }
        }
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
        corrected |= validateSpawns(c);
        corrected |= validateFeatured(c);
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

    /** Fixes the bounties section: sane counts, no negative reward. Returns true if anything changed. */
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
        if (b.typeRewardPerCatch < 0 || b.speciesReward < 0 || b.featuredReward < 0 || b.allDoneBonus < 0) {
            warn("bounties rewards", "a negative value", "0");
            b.typeRewardPerCatch = Math.max(0, b.typeRewardPerCatch);
            b.speciesReward = Math.max(0, b.speciesReward);
            b.featuredReward = Math.max(0, b.featuredReward);
            b.allDoneBonus = Math.max(0, b.allDoneBonus);
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
        if (c.spawns.excludedSpecies == null) {
            c.spawns.excludedSpecies = new ArrayList<>();
            corrected = true;
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
        corrected |= ensureCategories(c.spawns);
        corrected |= validateCategory("spawns.legendary", c.spawns.legendary);
        corrected |= validateCategory("spawns.mythical", c.spawns.mythical);
        corrected |= validateCategory("spawns.ultraBeast", c.spawns.ultraBeast);
        corrected |= validateCategory("spawns.paradox", c.spawns.paradox);
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
        return corrected;
    }

    /** Logs a corrected config value with the replacement used. */
    private static void warn(String field, Object bad, Object replacement) {
        CobblemonSafari.LOGGER.warn("Invalid {}={} in {}, using {}", field, bad, FILE_NAME, replacement);
    }
}
