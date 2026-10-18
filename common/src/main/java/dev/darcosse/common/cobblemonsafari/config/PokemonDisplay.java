package dev.darcosse.common.cobblemonsafari.config;

/**
 * How the wall screens draw a Pokémon. In common because the client
 * config is parsed by common code; it carries no client-only type.
 */
public enum PokemonDisplay {
    /** The Cobblemon 3D model, turning slowly. */
    MODEL,
    /** The flat sprite shipped with the mod (or a resource pack's); the model when there is none. */
    SPRITE
}
