package dev.darcosse.common.cobblemonsafari.spawn;

import com.cobblemon.mod.common.api.spawning.detail.PokemonSpawnDetail;
import com.cobblemon.mod.common.api.spawning.detail.SpawnDetail;
import com.cobblemon.mod.common.api.spawning.influence.SpawningInfluence;
import com.cobblemon.mod.common.api.spawning.position.SpawnablePosition;
import com.cobblemon.mod.common.api.spawning.spawner.PlayerSpawner;
import com.cobblemon.mod.common.api.spawning.spawner.Spawner;
import dev.darcosse.common.cobblemonsafari.dimension.SafariDimensions;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Makes Cobblemon's natural spawns around a player in the Safari favour the
 * species that player has not caught there yet, so a hunter keeps meeting
 * new Pokémon instead of the same ones over and over.
 *
 * <p>One shared instance is put on each player's spawner by
 * {@link SafariSpawnBoost}; it lowers the weight of caught species
 * ({@code spawns.caughtSpeciesWeight}, 0.4 by default) and leaves
 * everything else alone. Outside the Safari, or for a spawner that is not a
 * player's, it changes nothing, so it can stay on the spawner.
 *
 * <p>Thread safety: Cobblemon may weigh spawns off the server thread, so the
 * influence reads only immutable snapshots, swapped in by the server thread
 * through {@link #update} and {@link #forget}.
 */
public final class NoveltyInfluence implements SpawningInfluence {

    /** The one instance; spawners hold it, so it is found again by identity. */
    public static final NoveltyInfluence INSTANCE = new NoveltyInfluence();

    /** Weight of caught species, from the config; 1 means no effect. */
    private static volatile float caughtWeight = 1.0F;

    /** Species each player has caught in the Safari, as immutable sets of ids ("cobblemon:pikachu"). */
    private static final Map<UUID, Set<String>> CAUGHT = new ConcurrentHashMap<>();

    private NoveltyInfluence() {
    }

    /**
     * Sets a player's caught species. Server thread; the set must not change
     * afterwards (callers pass a copy).
     */
    static void update(UUID playerId, Set<String> caught) {
        CAUGHT.put(playerId, caught);
    }

    /** Whether the snapshot of a player has this many species, to skip copying an unchanged set. */
    static boolean hasSize(UUID playerId, int size) {
        Set<String> caught = CAUGHT.get(playerId);
        return caught != null && caught.size() == size;
    }

    /** Drops a player's snapshot, e.g. when they leave the Safari or log out. */
    static void forget(UUID playerId) {
        CAUGHT.remove(playerId);
    }

    /** Drops the snapshots of players no longer online. Server thread. */
    static void retain(Set<UUID> online) {
        CAUGHT.keySet().retainAll(online);
    }

    /** Drops every snapshot; for a server start, as single player reuses this class. */
    static void clear() {
        CAUGHT.clear();
    }

    /** Sets the weight of caught species; read from the config on the server thread. */
    static void setCaughtWeight(double weight) {
        caughtWeight = (float) weight;
    }

    /**
     * Lowers the weight of a species the spawner's player already caught in
     * the Safari. Called by Cobblemon for every candidate of every spawn
     * attempt, so it is a map lookup and a set lookup, nothing more.
     */
    @Override
    public float affectWeight(SpawnDetail detail, SpawnablePosition position, float weight) {
        float factor = caughtWeight;
        if (factor >= 1.0F || !(detail instanceof PokemonSpawnDetail pokemonDetail)) {
            return weight;
        }
        Spawner spawner = position.getSpawner();
        if (!(spawner instanceof PlayerSpawner playerSpawner) || !SafariDimensions.isSafari(position.getWorld())) {
            return weight;
        }
        Set<String> caught = CAUGHT.get(playerSpawner.getUuid());
        String species = pokemonDetail.getPokemon().getSpecies();
        if (caught == null || species == null || !caught.contains(SpeciesRules.normalizeId(species))) {
            return weight;
        }
        return weight * factor;
    }
}
