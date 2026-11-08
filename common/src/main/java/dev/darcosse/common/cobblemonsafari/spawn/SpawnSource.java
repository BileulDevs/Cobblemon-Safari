package dev.darcosse.common.cobblemonsafari.spawn;

import dev.darcosse.common.cobblemonsafari.config.SafariConfig;

/**
 * The ways a wild Pokémon comes into the Safari. Each one has its own
 * species rules in the config and its own per-rotation count, so the board,
 * Cobblemon's natural spawns and the swarm can be tuned apart.
 *
 * <p>The constant names are written to the save of {@link SpecialSpawnCounter}:
 * renaming one makes its roaming entries load as {@link #NATURAL}.
 */
public enum SpawnSource {

    /** Cobblemon's own spawner, filtered by {@code spawns} in the config. */
    NATURAL,

    /** The special Pokémon of the day: the board's draw and the mod's spawner, filtered by {@code specials}. */
    FEATURED,

    /**
     * The swarming species in its swarm's habitat, whichever spawner brings
     * it: its categories and caps are {@code events.swarm.rarities}, not
     * those of {@code spawns}, so a capped legendary can still swarm.
     */
    SWARM,

    /**
     * A Pokémon the storm's spawner brought ({@link Storm}): it follows the
     * natural spawns' rules (excluded species, categories and their caps),
     * as every event does but the swarm, counted apart from them.
     */
    STORM;

    /**
     * The config section holding this way's categories and exclusions. The
     * swarm and the storm have no section of that shape: they keep the
     * natural spawns' (a species kept out of the wild stays out of them);
     * only the swarm has categories of its own, which {@link SpeciesRules}
     * reads from {@code events.swarm.rarities}.
     */
    public SafariConfig.SpeciesRulesSection rules(SafariConfig config) {
        return this == FEATURED ? config.specials : config.spawns;
    }
}
