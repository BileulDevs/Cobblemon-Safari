package dev.darcosse.common.cobblemonsafari.config;

/**
 * Per-player display preferences, serialised as {@code config/cobblemon_safari-client.json}.
 *
 * <p>Separate from {@link SafariConfig} because these belong to the player, not
 * to the server: a server must never be able to move someone's HUD.
 */
public class SafariClientConfig {

    /** Bumped when a field changes meaning. */
    public int configVersion = SafariClientConfigManager.CURRENT_CONFIG_VERSION;

    /** Master switch for the timer HUD. */
    public boolean hudEnabled = true;

    /** Where the timer is attached. */
    public HudAnchor hudAnchor = HudAnchor.BOTTOM_CENTER;

    /**
     * Offset from the anchor, in GUI pixels. The default sits the timer just
     * above the vanilla action bar (drawn 68 px from the bottom), so Safari
     * warnings shown in the action bar never cover it.
     */
    public int hudOffsetX = 0;
    public int hudOffsetY = -84;

    /** Below this many seconds the timer turns amber. */
    public int lowTimeSeconds = 60;

    /** Below this many seconds the timer turns red and pulses. */
    public int criticalTimeSeconds = 10;

    /** Shows the "Safari" label in front of the time. */
    public boolean showLabel = true;

    /** Shows how many loaned Safari Balls are left (only when the server lends balls). */
    public boolean showBalls = true;

    /** Shows how many Pokémon were caught this session. */
    public boolean showCatches = true;

    /** Colours of the Safari Board screens. */
    public Board board = new Board();

    /**
     * Colours of the Safari Board screens, as {@code "#RRGGBB"} or
     * {@code "#AARRGGBB"} (the alpha matters for the overlay effects only).
     * An invalid value is replaced by its default and the file is rewritten.
     */
    public static class Board {
        /** Thin border around the whole picture. */
        public String bezel = "#1A1C22";
        /** Screen background. */
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
