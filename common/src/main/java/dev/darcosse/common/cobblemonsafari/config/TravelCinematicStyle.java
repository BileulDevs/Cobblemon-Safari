package dev.darcosse.common.cobblemonsafari.config;

/**
 * How a trip by the Safari Guide is shown, chosen in the server config
 * ({@code restHouseTravel.cinematicStyle}). The Escape Rope always plays its
 * own pixel-art scene, whatever this says.
 *
 * <p>The constant names are the JSON values: renaming one resets that
 * setting in every server.json to its default.
 */
public enum TravelCinematicStyle {
    /**
     * A double of the player, wearing their skin, flies off on the first
     * Pokémon of their party that can be ridden in the air (a Staraptor when
     * none can) and lands at the destination, filmed by the game's own
     * camera; other players see it too. Falls back to {@link #PANORAMA} on its own when it
     * cannot be played (no open sky to take off, no flyer and no
     * Staraptor).
     */
    FLIGHT,
    /** The painted Safari landscape panning behind black bands. */
    PANORAMA
}
