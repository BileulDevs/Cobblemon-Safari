package dev.darcosse.common.cobblemonsafari.config;

/**
 * Per-player display preferences, serialised as {@code config/cobblemon_safari/client.json}.
 *
 * <p>Separate from {@link SafariConfig} because these belong to the player, not
 * to the server: a server must never be able to move someone's HUD.
 */
public class SafariClientConfig {

    /**
     * Bumped when a field changes meaning. Version 6: the board colours moved
     * from {@code board} to {@code board.colors}, and
     * {@code board.pokemonDisplay} (MODEL / SPRITE) became the boolean
     * {@code board.useSprites}; {@link SafariClientConfigManager} converts
     * older files. Version 7: the timer became one line (stopwatch, then
     * time) in the bottom left corner, level with the hotbar; a file still on
     * the old default position follows.
     */
    public int configVersion = SafariClientConfigManager.CURRENT_CONFIG_VERSION;

    /** Master switch for the timer HUD. */
    public boolean hudEnabled = true;

    /** Where the timer is attached. */
    public HudAnchor hudAnchor = SafariClientConfigManager.DEFAULT_HUD_ANCHOR;

    /**
     * Offset of the middle of the timer's stopwatch from the anchor, in GUI
     * pixels; the time is on its right. The default puts it in the bottom
     * left corner, its middle level with the middle of the hotbar (22 px
     * high, at the bottom of the screen).
     */
    public int hudOffsetX = SafariClientConfigManager.DEFAULT_HUD_OFFSET_X;
    public int hudOffsetY = SafariClientConfigManager.DEFAULT_HUD_OFFSET_Y;

    /** Below this many seconds the timer turns amber. */
    public int lowTimeSeconds = 60;

    /** Below this many seconds the timer turns red and pulses. */
    public int criticalTimeSeconds = 10;

    /** Look of the wall screens (the section keeps its old name, "board", so existing files still load). */
    public Board board = new Board();

    /**
     * How the wall screens look. The few real choices come first;
     * the colours, many and rarely touched, have their own section.
     */
    public static class Board {
        /**
         * Draws the Pokémon as the flat sprites the mod ships
         * (textures/pokemon_sprites, which resource packs can override)
         * instead of their turning 3D Cobblemon models. A Pokémon without a
         * sprite keeps its model.
         */
        public boolean useSprites = false;

        /**
         * Shows the safari landscape of the time of day behind the screen
         * (the shop's pictures) instead of the plain background colour.
         */
        public boolean backgroundImage = true;

        /**
         * Opacity of the title band, cells and bars over the landscape, from
         * 0 (invisible) to 1 (opaque, the landscape only shows between them).
         * A colour given with its own alpha ({@code #AARRGGBB}) keeps it.
         */
        public double panelOpacity = 0.72;

        /** Colours of the screens. */
        public BoardColors colors = new BoardColors();
    }

    /**
     * Colours of the wall screens, as {@code "#RRGGBB"} or
     * {@code "#AARRGGBB"} (the alpha matters for the overlay effects only).
     * An invalid value is replaced by its default and the file is rewritten.
     *
     * <p>{@link BoardPalette} and the version 6 migration both walk every
     * public String field of this class: a colour added here is validated
     * and migrated without further code. Keep anything that is not a colour
     * out of it.
     */
    public static class BoardColors {
        /** Thin border around the whole picture. */
        public String bezel = "#1A1C22";
        /** Screen background, when {@link Board#backgroundImage} is off. */
        public String background = "#0A1220";
        /** Title band. */
        public String header = "#13213B";
        /** Title text. */
        public String title = "#FFB000";
        /** Background of each Pokémon's cell. */
        public String cell = "#111D33";
        /** Pokémon names. */
        public String text = "#FFFFFF";
        /** Secondary text: habitat, LIVE label, page counter, bar label. */
        public String muted = "#8C9BB5";
        /** Star in front of legendary-class species. */
        public String star = "#FFD700";
        /** LIVE light, lit and unlit. */
        public String liveOn = "#FF3B3B";
        public String liveOff = "#5A1A1A";
        /** Empty part of the progress bar. */
        public String barBackground = "#1E2B45";
        /** Bar and time when counting down to the next rotation. */
        public String barRotation = "#35C8E8";
        /** Bar and time of the viewer's own hunt, by remaining time (same thresholds as the HUD). */
        public String barSessionOk = "#4CD964";
        public String barSessionLow = "#FFC04D";
        public String barSessionCritical = "#FF5555";
        /** Rarity strip and rarity name, per tier. */
        public String rarityCommon = "#FFFFFF";
        public String rarityUncommon = "#55FF55";
        public String rarityRare = "#55FFFF";
        public String rarityUltraRare = "#FF55FF";
        /** Light band rolling down the screen; mostly transparent. */
        public String scanline = "#12FFFFFF";
        /** Light sweeping along the progress bar. */
        public String shimmer = "#50FFFFFF";
        /** Flash when the list changes; its alpha is the flash's peak. */
        public String flash = "#90FFFFFF";
    }
}
