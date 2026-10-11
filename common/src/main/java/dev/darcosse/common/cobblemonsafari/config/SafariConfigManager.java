package dev.darcosse.common.cobblemonsafari.config;

import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import dev.darcosse.common.cobblemonsafari.util.Ticks;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
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

    private static final String FILE_NAME = CobblemonSafari.MOD_ID + ".json";

    private static final int MINUTES_PER_HOUR = 60;
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
     * Most missions per rotation. The board lists them on one page; past
     * about eight lines they no longer fit a two-block-high screen.
     */
    private static final int MAX_BOUNTIES = 8;

    /** Version written by this build. Raise it together with a new step in {@link #migrate}. */
    public static final int CURRENT_CONFIG_VERSION = 13;

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

    /**
     * Upper bound on loaned balls. A full inventory holds 36 stacks; beyond a
     * few stacks the surplus is dropped on the ground at the entrance, where
     * other players could pick it up.
     */
    private static final int MAX_LOANED_BALLS = 256;

    /**
     * Largest radius accepted for entity checks. Each check scans every entity
     * section the box touches; beyond a few chunks it stops being cheap.
     */
    private static final int MAX_CHECK_RADIUS = 128;

    /**
     * Farthest a special Pokémon may spawn from its player. Past the usual
     * entity simulation distance it would spawn frozen and despawn unseen.
     */
    private static final int MAX_SPAWN_DISTANCE = 96;

    /** Shortest session accepted. Zero would expire on the very tick it starts. */
    private static final int MIN_DURATION_SECONDS = 1;

    /** Arrival spread is capped to this fraction of the border half-size so arrivals never land on the border. */
    private static final float MAX_SPREAD_FRACTION_OF_BORDER = 0.5f;

    private static final JsonConfigFile<SafariConfig> FILE =
            new JsonConfigFile<>(FILE_NAME, SafariConfig.class, SafariConfig::new);

    private static SafariConfig config = new SafariConfig();
    private static Set<ResourceLocation> allowedBalls = Set.of();
    private static ResourceKey<Level> dimension = defaultDimension();
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

    /** Dimension currently acting as the Safari. */
    public static ResourceKey<Level> dimension() {
        return dimension;
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

    /** Key of the Safari dimension shipped with the mod, used when the configured id is unusable. */
    private static ResourceKey<Level> defaultDimension() {
        return ResourceKey.create(Registries.DIMENSION, CobblemonSafari.id("safari"));
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

        ResourceLocation dimensionId = ResourceLocation.tryParse(config.dimension);
        dimension = dimensionId != null
                ? ResourceKey.create(Registries.DIMENSION, dimensionId)
                : defaultDimension();

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
     * Upgrades a file written by an older build, step by step.
     *
     * @return true if the file must be rewritten
     */
    private static boolean migrate(SafariConfig c) {
        if (c.configVersion >= CURRENT_CONFIG_VERSION) {
            return false;
        }
        if (c.configVersion < 3) {
            // v1 described an infinite world, v2 a 288-block island with a
            // circular boundary. v3 is a 512-block island inside a square world
            // border: the whole park section changed meaning, so it is reset.
            // The v2 "radius" key is simply ignored by Gson and dropped on rewrite.
            c.park = new SafariConfig.Park();
            CobblemonSafari.LOGGER.info("Migrated {} to version {}: park geometry reset to match the island",
                    FILE_NAME, CURRENT_CONFIG_VERSION);
        }
        if (c.configVersion < 4) {
            // Nothing to reset: Gson already filled the new sections with their
            // defaults, the rewrite below just makes them visible in the file.
            CobblemonSafari.LOGGER.info("Migrated {} to version 4: added the loan and ticket sections", FILE_NAME);
        }
        if (c.configVersion < 5) {
            CobblemonSafari.LOGGER.info("Migrated {} to version 5: added the spawns, featured and restHouses sections",
                    FILE_NAME);
        }
        if (c.configVersion < 6 && c.featured != null) {
            if (c.featured.rotationHours != null) {
                c.featured.rotationMinutes = c.featured.rotationHours * MINUTES_PER_HOUR;
            }
            CobblemonSafari.LOGGER.info("Migrated {} to version 6: featured.rotationHours is now "
                    + "featured.rotationMinutes ({})", FILE_NAME, c.featured.rotationMinutes);
        }
        if (c.configVersion < 7) {
            CobblemonSafari.LOGGER.info("Migrated {} to version 7: added ticket credit, capture and spawn rate "
                    + "settings", FILE_NAME);
        }
        if (c.configVersion < 8) {
            CobblemonSafari.LOGGER.info("Migrated {} to version 8: added the reset section", FILE_NAME);
        }
        if (c.configVersion < 12) {
            CobblemonSafari.LOGGER.info("Migrated {} to version 12: added the bounties section", FILE_NAME);
        }
        if (c.configVersion < 11) {
            CobblemonSafari.LOGGER.info("Migrated {} to version 11: added protection.placeableBlocks", FILE_NAME);
        }
        if (c.configVersion < 10) {
            CobblemonSafari.LOGGER.info("Migrated {} to version 10: added the points and escapeRope sections",
                    FILE_NAME);
        }
        if (c.configVersion < 13 && c.featured != null
                && c.featured.rotationMinutes == LEGACY_DEFAULT_ROTATION_MINUTES) {
            c.featured.rotationMinutes = DEFAULT_ROTATION_MINUTES;
            CobblemonSafari.LOGGER.info("Migrated {} to version 13: featured.rotationMinutes moved from the old "
                    + "default {} to the new default {}", FILE_NAME, LEGACY_DEFAULT_ROTATION_MINUTES,
                    DEFAULT_ROTATION_MINUTES);
        }
        if (c.configVersion < 9) {
            // The old key is simply ignored by Gson and dropped on rewrite.
            CobblemonSafari.LOGGER.info("Migrated {} to version 9: defaultDurationSeconds removed (entry now always "
                    + "spends banked time), added the gate section", FILE_NAME);
        }
        if (c.featured != null) {
            // Dropped in every case: Gson leaves null fields out of the rewritten file.
            c.featured.rotationHours = null;
        }
        c.configVersion = CURRENT_CONFIG_VERSION;
        return true;
    }

    /**
     * Clamps every value into its valid domain.
     *
     * @return true if at least one value was corrected
     */
    private static boolean validate(SafariConfig c) {
        boolean corrected = false;
        SafariConfig defaults = new SafariConfig();

        if (c.dimension == null || ResourceLocation.tryParse(c.dimension) == null) {
            warn("dimension", c.dimension, defaults.dimension);
            c.dimension = defaults.dimension;
            corrected = true;
        }
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

        if (c.park == null) {
            c.park = new SafariConfig.Park();
            corrected = true;
        }
        if (c.park.borderHalfSize < 0) {
            warn("park.borderHalfSize", c.park.borderHalfSize, 0);
            c.park.borderHalfSize = 0;
            corrected = true;
        }
        if (c.park.arrivalSpread < 0) {
            warn("park.arrivalSpread", c.park.arrivalSpread, 0);
            c.park.arrivalSpread = 0;
            corrected = true;
        }
        if (c.park.borderHalfSize > 0) {
            int maxSpread = (int) (c.park.borderHalfSize * MAX_SPREAD_FRACTION_OF_BORDER);
            if (c.park.arrivalSpread > maxSpread) {
                warn("park.arrivalSpread", c.park.arrivalSpread, maxSpread);
                c.park.arrivalSpread = maxSpread;
                corrected = true;
            }
            // An arrival point outside the border would leave newcomers stuck
            // outside it; fall back to the centre, which is always inside.
            if (Math.abs(c.park.arrivalX - c.park.centerX) > c.park.borderHalfSize
                    || Math.abs(c.park.arrivalZ - c.park.centerZ) > c.park.borderHalfSize) {
                warn("park.arrivalX/arrivalZ", c.park.arrivalX + "," + c.park.arrivalZ,
                        c.park.centerX + "," + c.park.centerZ);
                c.park.arrivalX = c.park.centerX;
                c.park.arrivalZ = c.park.centerZ;
                corrected = true;
            }
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
        if (c.capture.safariBallItem == null || ResourceLocation.tryParse(c.capture.safariBallItem) == null) {
            warn("capture.safariBallItem", c.capture.safariBallItem, new SafariConfig.Capture().safariBallItem);
            c.capture.safariBallItem = new SafariConfig.Capture().safariBallItem;
            corrected = true;
        }
        corrected |= validateGate(c);
        corrected |= validatePoints(c);
        corrected |= validateBounties(c);
        if (c.escapeRope == null) {
            c.escapeRope = new SafariConfig.EscapeRope();
            corrected = true;
        }
        if (c.escapeRope.cooldownSeconds < 0) {
            warn("escapeRope.cooldownSeconds", c.escapeRope.cooldownSeconds, 0);
            c.escapeRope.cooldownSeconds = 0;
            corrected = true;
        }
        corrected |= validateSpawns(c);
        corrected |= validateFeatured(c);
        corrected |= validateRestHouses(c);
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
        if (p.maxBalance <= 0) {
            int fixed = new SafariConfig.Points().maxBalance;
            warn("points.maxBalance", p.maxBalance, fixed);
            p.maxBalance = fixed;
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
        if (b.typeMinCount < 1 || b.typeMaxCount < b.typeMinCount) {
            SafariConfig.Bounties defaults = new SafariConfig.Bounties();
            warn("bounties.typeMinCount/typeMaxCount", b.typeMinCount + ".." + b.typeMaxCount,
                    defaults.typeMinCount + ".." + defaults.typeMaxCount);
            b.typeMinCount = defaults.typeMinCount;
            b.typeMaxCount = defaults.typeMaxCount;
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
        if (c.spawns.maxSameSpeciesNearby < 0) {
            warn("spawns.maxSameSpeciesNearby", c.spawns.maxSameSpeciesNearby, 0);
            c.spawns.maxSameSpeciesNearby = 0;
            corrected = true;
        }
        if (c.spawns.sameSpeciesRadius < 1 || c.spawns.sameSpeciesRadius > MAX_CHECK_RADIUS) {
            int fixed = Math.max(1, Math.min(MAX_CHECK_RADIUS, c.spawns.sameSpeciesRadius));
            warn("spawns.sameSpeciesRadius", c.spawns.sameSpeciesRadius, fixed);
            c.spawns.sameSpeciesRadius = fixed;
            corrected = true;
        }
        return corrected;
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
        if (f.nearbyRadius < 1 || f.nearbyRadius > MAX_CHECK_RADIUS) {
            int fixed = Math.max(1, Math.min(MAX_CHECK_RADIUS, f.nearbyRadius));
            warn("featured.nearbyRadius", f.nearbyRadius, fixed);
            f.nearbyRadius = fixed;
            corrected = true;
        }
        if (f.minDistance < 1 || f.maxDistance < f.minDistance || f.maxDistance > MAX_SPAWN_DISTANCE) {
            SafariConfig.Featured defaults = new SafariConfig.Featured();
            warn("featured.minDistance/maxDistance", f.minDistance + ".." + f.maxDistance,
                    defaults.minDistance + ".." + defaults.maxDistance);
            f.minDistance = defaults.minDistance;
            f.maxDistance = defaults.maxDistance;
            corrected = true;
        }
        if (f.fallbackMinLevel < 1 || f.fallbackMaxLevel < f.fallbackMinLevel) {
            SafariConfig.Featured defaults = new SafariConfig.Featured();
            warn("featured.fallbackMinLevel/fallbackMaxLevel", f.fallbackMinLevel + ".." + f.fallbackMaxLevel,
                    defaults.fallbackMinLevel + ".." + defaults.fallbackMaxLevel);
            f.fallbackMinLevel = defaults.fallbackMinLevel;
            f.fallbackMaxLevel = defaults.fallbackMaxLevel;
            corrected = true;
        }
        return corrected;
    }

    /** Fixes the restHouses section; returns true if anything changed. */
    private static boolean validateRestHouses(SafariConfig c) {
        if (c.restHouses == null) {
            c.restHouses = new SafariConfig.RestHouses();
            return true;
        }
        if (c.restHouses.positions == null) {
            c.restHouses.positions = new ArrayList<>();
            return true;
        }
        int before = c.restHouses.positions.size();
        c.restHouses.positions.removeIf(java.util.Objects::isNull);
        return c.restHouses.positions.size() != before;
    }

    /** Logs a corrected config value with the replacement used. */
    private static void warn(String field, Object bad, Object replacement) {
        CobblemonSafari.LOGGER.warn("Invalid {}={} in {}, using {}", field, bad, FILE_NAME, replacement);
    }
}
