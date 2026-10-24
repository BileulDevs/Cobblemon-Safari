package dev.darcosse.common.cobblemonsafari.spawn;

import dev.darcosse.common.cobblemonsafari.config.SafariConfig;

/**
 * The two ways a wild Pokémon comes into the Safari. Each one has its own
 * species rules in the config and its own per-rotation count, so the board
 * and Cobblemon's natural spawns can be tuned apart.
 *
 * <p>The constant names are written to the save of {@link SpecialSpawnCounter}:
 * renaming one makes its roaming entries load as {@link #NATURAL}.
 */
public enum SpawnSource {

    /** Cobblemon's own spawner, filtered by {@code spawns} in the config. */
    NATURAL,

    /** The special Pokémon of the day: the board's draw and the mod's spawner, filtered by {@code featured}. */
    FEATURED;

    /** The config section holding this way's categories and exclusions. */
    public SafariConfig.SpeciesRulesSection rules(SafariConfig config) {
        return this == NATURAL ? config.spawns : config.featured;
    }
}
