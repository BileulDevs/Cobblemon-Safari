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
}
