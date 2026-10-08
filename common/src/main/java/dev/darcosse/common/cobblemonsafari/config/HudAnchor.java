package dev.darcosse.common.cobblemonsafari.config;

/**
 * Screen corner or edge the Safari timer is attached to.
 *
 * <p>Lives in common rather than in the client package because the client
 * config is parsed by common code; it carries no client-only type.
 */
public enum HudAnchor {
    TOP_LEFT(0.0f, 0.0f),
    TOP_CENTER(0.5f, 0.0f),
    TOP_RIGHT(1.0f, 0.0f),
    BOTTOM_LEFT(0.0f, 1.0f),
    BOTTOM_CENTER(0.5f, 1.0f),
    BOTTOM_RIGHT(1.0f, 1.0f);

    /** Horizontal position of the anchor as a fraction of the screen width. */
    public final float xFraction;
    /** Vertical position of the anchor as a fraction of the screen height. */
    public final float yFraction;

    HudAnchor(float xFraction, float yFraction) {
        this.xFraction = xFraction;
        this.yFraction = yFraction;
    }
}
