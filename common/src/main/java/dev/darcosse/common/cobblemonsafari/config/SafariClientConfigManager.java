package dev.darcosse.common.cobblemonsafari.config;

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
    /** Where the file lived before the mod had its own config folder; moved into it on load. */
    private static final String LEGACY_FILE_NAME = CobblemonSafari.MOD_ID + "-client.json";

    /**
     * Version written by this build. Version 2 added showBalls and showCatches,
     * version 3 the board colours, version 4 the board's landscape and panel
     * opacity; Gson fills new fields with their defaults, the bump only
     * forces a rewrite so they appear in existing files. Version 5: the
     * timer became a Safari Ball with the time under it (showLabel,
     * showBalls and showCatches removed, the offset now places the ball's
     * centre) and the board got pokemonDisplay.
     */
    public static final int CURRENT_CONFIG_VERSION = 5;

    /** Default timer position, see {@link SafariClientConfig#hudOffsetX}. */
    public static final int DEFAULT_HUD_OFFSET_X = -136;
    public static final int DEFAULT_HUD_OFFSET_Y = -28;

    /**
     * Offsets written by default before version 5 (a panel above the action
     * bar). A file still holding them never moved the timer: it gets the new
     * defaults, since the old ones would put the ball over the action bar.
     */
    private static final int LEGACY_HUD_OFFSET_X = 0;
    private static final int LEGACY_HUD_OFFSET_Y = -84;
    private static final int TIMER_REWORK_VERSION = 5;

    private static final JsonConfigFile<SafariClientConfig> FILE =
            new JsonConfigFile<>(FILE_NAME, LEGACY_FILE_NAME, SafariClientConfig.class, SafariClientConfig::new);

    private static SafariClientConfig config = new SafariClientConfig();
    private static BoardPalette palette = BoardPalette.from(new SafariClientConfig.Board());

    private SafariClientConfigManager() {
    }

    /** Reads the file and repairs out-of-range values. */
    public static void load() {
        SafariClientConfig loaded = FILE.read();
        boolean corrected = false;
        if (loaded.configVersion < TIMER_REWORK_VERSION
                && loaded.hudOffsetX == LEGACY_HUD_OFFSET_X && loaded.hudOffsetY == LEGACY_HUD_OFFSET_Y) {
            loaded.hudOffsetX = DEFAULT_HUD_OFFSET_X;
            loaded.hudOffsetY = DEFAULT_HUD_OFFSET_Y;
        }
        if (loaded.configVersion < CURRENT_CONFIG_VERSION) {
            // The removed keys (showLabel...) are ignored by Gson and dropped by this rewrite.
            loaded.configVersion = CURRENT_CONFIG_VERSION;
            corrected = true;
        }
        if (loaded.hudAnchor == null) {
            loaded.hudAnchor = new SafariClientConfig().hudAnchor;
            corrected = true;
        }
        if (loaded.criticalTimeSeconds < 0) {
            loaded.criticalTimeSeconds = 0;
            corrected = true;
        }
        // Critical must not exceed low, otherwise the amber stage is skipped entirely.
        if (loaded.lowTimeSeconds < loaded.criticalTimeSeconds) {
            loaded.lowTimeSeconds = loaded.criticalTimeSeconds;
            corrected = true;
        }
        if (loaded.board == null) {
            loaded.board = new SafariClientConfig.Board();
            corrected = true;
        }
        corrected |= BoardPalette.repair(loaded.board, FILE_NAME);
        if (loaded.board.pokemonDisplay == null) {
            // Unknown value in the file: Gson leaves the enum null.
            loaded.board.pokemonDisplay = PokemonDisplay.MODEL;
            corrected = true;
        }
        if (!(loaded.board.panelOpacity >= 0.0D && loaded.board.panelOpacity <= 1.0D)) {
            // Also catches NaN, which fails both comparisons.
            loaded.board.panelOpacity = new SafariClientConfig.Board().panelOpacity;
            corrected = true;
        }
        if (corrected) {
            FILE.write(loaded);
        }
        config = loaded;
        palette = BoardPalette.from(loaded.board);
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
