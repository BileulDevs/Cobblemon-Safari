package dev.darcosse.common.cobblemonsafari.config;

import dev.darcosse.common.cobblemonsafari.CobblemonSafari;

/**
 * Owns the client display preferences.
 *
 * <p>Only ever loaded from a client entry point; a dedicated server never
 * creates the client file.
 */
public final class SafariClientConfigManager {

    private static final String FILE_NAME = CobblemonSafari.MOD_ID + "-client.json";

    /**
     * Version written by this build. Version 2 added showBalls and showCatches,
     * version 3 the board colours; Gson fills new fields with their defaults,
     * the bump only forces a rewrite so they appear in existing files.
     */
    public static final int CURRENT_CONFIG_VERSION = 3;

    private static final JsonConfigFile<SafariClientConfig> FILE =
            new JsonConfigFile<>(FILE_NAME, SafariClientConfig.class, SafariClientConfig::new);

    private static SafariClientConfig config = new SafariClientConfig();
    private static BoardPalette palette = BoardPalette.from(new SafariClientConfig.Board());

    private SafariClientConfigManager() {
    }

    /** Reads the file and repairs out-of-range values. */
    public static void load() {
        SafariClientConfig loaded = FILE.read();
        boolean corrected = false;
        if (loaded.configVersion < CURRENT_CONFIG_VERSION) {
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
