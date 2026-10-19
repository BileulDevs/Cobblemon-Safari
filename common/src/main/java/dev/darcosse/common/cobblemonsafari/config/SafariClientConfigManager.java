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

    /**
     * Format version written to the file. Raise it when a field changes
     * meaning and add the conversion to {@link #load}; a field added or
     * removed needs no bump, Gson fills new fields with their defaults and
     * ignores unknown keys.
     */
    public static final int CURRENT_CONFIG_VERSION = 1;

    /**
     * Default timer position, see {@link SafariClientConfig#hudOffsetX}: 20
     * puts the middle of the timer far enough from the left edge for a time
     * like 19:59 (about 30 px wide) to keep a few pixels of margin; -16
     * leaves 5 px between the 22 px widget and the bottom of the screen.
     * Closer to the edge, the time looks glued to it.
     */
    public static final HudAnchor DEFAULT_HUD_ANCHOR = HudAnchor.BOTTOM_LEFT;
    public static final int DEFAULT_HUD_OFFSET_X = 20;
    public static final int DEFAULT_HUD_OFFSET_Y = -16;

    private static final JsonConfigFile<SafariClientConfig> FILE =
            new JsonConfigFile<>(FILE_NAME, SafariClientConfig.class, SafariClientConfig::new);

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

    /** Board colours parsed from the current preferences. */
    public static BoardPalette palette() {
        return palette;
    }

    /** Current preferences. */
    public static SafariClientConfig get() {
        return config;
    }
}
