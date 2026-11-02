package dev.darcosse.common.cobblemonsafari.config;

import com.google.gson.JsonObject;
import dev.darcosse.common.cobblemonsafari.CobblemonSafari;

/**
 * Owns the client display preferences.
 *
 * <p>Only ever loaded from a client entry point; a dedicated server never
 * creates the client file.
 */
public final class SafariClientConfigManager {

    /** Path under the config directory: the mod's folder keeps its files together. */
    private static final String FILE_NAME = CobblemonSafari.MOD_ID + "/client.json";

    /**
     * Format version written to the file. Raise it when a field changes
     * meaning and add the conversion to {@link #load}; a field added or
     * removed needs no bump, Gson fills new fields with their defaults and
     * ignores unknown keys.
     *
     * <p>History: 2 grouped the timer settings under {@code hud} and the
     * footers under {@code board.footers} (see {@link #migrateTree}).
     */
    public static final int CURRENT_CONFIG_VERSION = 2;

    /**
     * Default timer position, see {@link SafariClientConfig.Hud#offsetX}: 20
     * puts the middle of the timer far enough from the left edge for a time
     * like 19:59 (about 30 px wide) to keep a few pixels of margin; -16
     * leaves 5 px between the 22 px widget and the bottom of the screen.
     * Closer to the edge, the time looks glued to it.
     */
    public static final HudAnchor DEFAULT_HUD_ANCHOR = HudAnchor.BOTTOM_LEFT;
    public static final int DEFAULT_HUD_OFFSET_X = 20;
    public static final int DEFAULT_HUD_OFFSET_Y = -16;

    private static final JsonConfigFile<SafariClientConfig> FILE =
            new JsonConfigFile<>(FILE_NAME, SafariClientConfig.class, SafariClientConfig::new,
                    SafariClientConfigManager::migrateTree);

    private static SafariClientConfig config = new SafariClientConfig();
    private static BoardPalette palette = BoardPalette.from(new SafariClientConfig.BoardColors());

    private SafariClientConfigManager() {
    }

    /** Reads the file and repairs out-of-range values, rewriting it when anything was corrected. */
    public static void load() {
        SafariClientConfig loaded = FILE.read();
        boolean corrected = false;
        if (loaded.configVersion != CURRENT_CONFIG_VERSION) {
            // The rewrite also drops keys Gson ignored, so the file shows what is read.
            loaded.configVersion = CURRENT_CONFIG_VERSION;
            corrected = true;
        }
        if (loaded.hud == null) {
            loaded.hud = new SafariClientConfig.Hud();
            corrected = true;
        }
        SafariClientConfig.Hud hud = loaded.hud;
        if (hud.anchor == null) {
            hud.anchor = new SafariClientConfig.Hud().anchor;
            corrected = true;
        }
        if (hud.criticalTimeSeconds < 0) {
            hud.criticalTimeSeconds = 0;
            corrected = true;
        }
        // Critical must not exceed low, otherwise the amber stage is skipped entirely.
        if (hud.lowTimeSeconds < hud.criticalTimeSeconds) {
            hud.lowTimeSeconds = hud.criticalTimeSeconds;
            corrected = true;
        }
        if (loaded.board == null) {
            loaded.board = new SafariClientConfig.Board();
            corrected = true;
        }
        if (loaded.board.footers == null) {
            loaded.board.footers = new SafariClientConfig.Footers();
            corrected = true;
        }
        // A value Gson could not match (a typo) reads as null: back to the default.
        SafariClientConfig.Footers footers = loaded.board.footers;
        SafariClientConfig.Footers footerDefaults = new SafariClientConfig.Footers();
        if (footers.specials == null) {
            footers.specials = footerDefaults.specials;
            corrected = true;
        }
        if (footers.stats == null) {
            footers.stats = footerDefaults.stats;
            corrected = true;
        }
        if (footers.missions == null) {
            footers.missions = footerDefaults.missions;
            corrected = true;
        }
        if (footers.dex == null) {
            footers.dex = footerDefaults.dex;
            corrected = true;
        }
        if (loaded.board.colors == null) {
            loaded.board.colors = new SafariClientConfig.BoardColors();
            corrected = true;
        }
        corrected |= BoardPalette.repair(loaded.board.colors, FILE_NAME);
        if (!(loaded.board.panelOpacity >= 0.0D && loaded.board.panelOpacity <= 1.0D)) {
            // Also catches NaN, which fails both comparisons.
            loaded.board.panelOpacity = new SafariClientConfig.Board().panelOpacity;
            corrected = true;
        }
        if (corrected) {
            FILE.write(loaded);
        }
        config = loaded;
        palette = BoardPalette.from(loaded.board.colors);
    }

    /**
     * Puts the keys of a version 1 file in their current place, on the raw
     * tree before binding (see {@link ConfigTreeMigration}): the six timer
     * keys into {@code hud}, the four footers into {@code board.footers}.
     * Only keys that are there move, so a converted file goes through
     * unchanged.
     */
    private static void migrateTree(String fileName, JsonObject root) {
        ConfigTreeMigration tree = new ConfigTreeMigration(fileName, root);
        tree.move("hudEnabled", "hud.enabled");
        tree.move("hudAnchor", "hud.anchor");
        tree.move("hudOffsetX", "hud.offsetX");
        tree.move("hudOffsetY", "hud.offsetY");
        tree.move("lowTimeSeconds", "hud.lowTimeSeconds");
        tree.move("criticalTimeSeconds", "hud.criticalTimeSeconds");
        tree.move("board.featuredFooter", "board.footers.specials");
        tree.move("board.statsFooter", "board.footers.stats");
        tree.move("board.bountiesFooter", "board.footers.missions");
        tree.move("board.dexFooter", "board.footers.dex");
    }

    /** Board colours parsed from the current preferences. */
    public static BoardPalette palette() {
        return palette;
    }

    /** Current preferences. */
    public static SafariClientConfig get() {
        return config;
    }
}
