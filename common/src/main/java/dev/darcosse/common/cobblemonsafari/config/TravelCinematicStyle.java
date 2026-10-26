package dev.darcosse.common.cobblemonsafari.config;

/**
 * How a trip to a rest house is shown, by the Safari Guide or by the Escape
 * Rope, chosen in the server config ({@code restHouseTravel.cinematicStyle}).
 * When the flight cannot be played, the guide's trips show the painted
 * landscape and the rope's its own pixel-art scene.
 *
 * <p>The constant names are the JSON values: renaming one resets that
 * setting in every server.json to its default.
 */
public enum TravelCinematicStyle {
    /**
     * A double of the player, wearing their skin, flies off on the first
     * Pokémon of their party that can be ridden in the air (a Staraptor when
     * none can) and lands at the destination, filmed by the game's own
     * camera; other players see it too. The rope's flight takes off where
     * it was used, after a climb to the open air when used in a cave. Falls
     * back on its own when it cannot be played (no open sky to take off, no
     * flyer and no Staraptor).
     */
    FLIGHT,
    /** The painted Safari landscape for the guide, the pixel-art cave escape for the rope. */
    PANORAMA
}
