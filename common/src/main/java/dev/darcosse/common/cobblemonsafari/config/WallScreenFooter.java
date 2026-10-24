package dev.darcosse.common.cobblemonsafari.config;

/**
 * What the bar at the bottom of a wall screen counts down, chosen per kind
 * of screen in the client config ({@code board.featuredFooter},
 * {@code statsFooter}, {@code bountiesFooter}).
 *
 * <p>The constant names are the JSON values: renaming one resets that
 * setting in every client.json to its default.
 */
public enum WallScreenFooter {
    /** The viewer's hunting time while they hunt in the Safari; the next rotation otherwise. */
    SESSION,
    /** Always the time left before the next rotation of the specials and missions. */
    ROTATION
}
