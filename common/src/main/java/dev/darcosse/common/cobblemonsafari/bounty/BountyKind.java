package dev.darcosse.common.cobblemonsafari.bounty;

/**
 * What a mission asks the player to catch.
 *
 * <p>Two formats rest on this enum: the constant names are the values of
 * {@code bounties.allowedKinds} in server.json (renaming one drops it from
 * every config file), and the ordinals are the wire format of the missions
 * sent to clients (SafariBountiesPayload): append new kinds at the end,
 * never reorder.
 */
public enum BountyKind {
    /** N Pokémon of an elemental type. Target: the type's name ({@code "grass"}). */
    TYPE,
    /** N of a given species. Target: the species id. */
    SPECIES,
    /** One of the specials of the rotation. */
    FEATURED,
    /** N Pokémon caught in a Safari habitat. Target: the biome id. */
    HABITAT,
    /** N Pokémon, any of them. */
    ANY,
    /** N different species. */
    DIFFERENT,
    /** N Pokémon caught without a battle: the Safari's own way. */
    OUT_OF_BATTLE,
    /** N Pokémon caught at night. */
    NIGHT,
    /** N fully evolved Pokémon: species that evolved and evolve no further. */
    EVOLVED,
    /** N Pokémon of at least a level. Target: the level. */
    LEVEL,
    /**
     * N Pokémon with their hidden ability. Rare in the wild: Cobblemon gives
     * one only to some fished Pokémon and to those drawn by a honeyed tree.
     */
    HIDDEN_ABILITY,
    /** N shiny Pokémon. */
    SHINY
}
