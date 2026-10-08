package dev.darcosse.common.cobblemonsafari.config;

/**
 * Where a player is sent when their Safari session ends.
 */
public enum ExitDestination {
    /** Back to the exact spot the player entered from, like leaving through the gate. */
    RETURN_POINT,
    /** To the overworld spawn, for servers that run the entrance from a hub. */
    WORLD_SPAWN
}
