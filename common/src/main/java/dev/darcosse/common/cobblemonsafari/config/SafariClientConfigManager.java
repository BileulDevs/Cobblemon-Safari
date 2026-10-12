package dev.darcosse.common.cobblemonsafari.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.darcosse.common.cobblemonsafari.CobblemonSafari;

import java.lang.reflect.Field;

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
     * centre) and the board got pokemonDisplay. Version 6: the board
     * colours moved into board.colors and pokemonDisplay became useSprites
     * (see {@link #upgrade}).
     */
    public static final int CURRENT_CONFIG_VERSION = 6;

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
    /** The version that moved the board colours into board.colors and made the sprite choice a boolean. */
    private static final int BOARD_SECTIONS_VERSION = 6;

    /** Version 5 key of the sprite choice and its value meaning sprites; read by {@link #upgrade} only. */
    private static final String LEGACY_DISPLAY_KEY = "pokemonDisplay";
    private static final String LEGACY_SPRITE_VALUE = "SPRITE";

    /**
     * JSON keys {@link #upgrade} works on. They must match the field names
     * of SafariClientConfig (Gson maps keys to fields by name): renaming one
     * of those fields means updating its key here.
     */
    private static final String VERSION_KEY = "configVersion";
    private static final String BOARD_KEY = "board";
    private static final String COLORS_KEY = "colors";
    private static final String USE_SPRITES_KEY = "useSprites";

    private static final JsonConfigFile<SafariClientConfig> FILE = new JsonConfigFile<>(FILE_NAME, LEGACY_FILE_NAME,
            SafariClientConfig.class, SafariClientConfig::new, SafariClientConfigManager::upgrade);

    private static SafariClientConfig config = new SafariClientConfig();
    private static BoardPalette palette = BoardPalette.from(new SafariClientConfig.BoardColors());

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
     * Converts the board section of a version 5 or older file to the version
     * 6 layout, on the raw JSON before it becomes a SafariClientConfig:
     * <ul>
     *   <li>the colours move from {@code board} into {@code board.colors};</li>
     *   <li>{@code board.pokemonDisplay} ({@code "MODEL"} / {@code "SPRITE"})
     *       becomes {@code board.useSprites}.</li>
     * </ul>
     * Without this, Gson would ignore the keys at their old place and the
     * player's colours and sprite choice would silently reset. Current files
     * pass through untouched.
     */
    private static JsonObject upgrade(JsonObject json) {
        if (fileVersion(json) >= BOARD_SECTIONS_VERSION || !json.has(BOARD_KEY) || !json.get(BOARD_KEY).isJsonObject()) {
            return json;
        }
        JsonObject board = json.getAsJsonObject(BOARD_KEY);
        JsonObject colors = new JsonObject();
        for (Field field : SafariClientConfig.BoardColors.class.getFields()) {
            JsonElement value = board.remove(field.getName());
            if (value != null) {
                colors.add(field.getName(), value);
            }
        }
        if (!colors.isEmpty()) {
            board.add(COLORS_KEY, colors);
        }
        JsonElement display = board.remove(LEGACY_DISPLAY_KEY);
        if (display != null && display.isJsonPrimitive()) {
            // Anything but SPRITE, including a typo, was drawn as models: keep it that way.
            board.addProperty(USE_SPRITES_KEY, LEGACY_SPRITE_VALUE.equalsIgnoreCase(display.getAsString()));
        }
        return json;
    }

    /**
     * The configVersion of a raw file, 0 when missing or not a number (then
     * every migration runs, which is what a file that old needs).
     */
    private static int fileVersion(JsonObject json) {
        JsonElement version = json.get(VERSION_KEY);
        if (version == null || !version.isJsonPrimitive() || !version.getAsJsonPrimitive().isNumber()) {
            return 0;
        }
        return version.getAsInt();
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
