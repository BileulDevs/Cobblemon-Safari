package dev.darcosse.common.cobblemonsafari.spawn;

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies;
import com.cobblemon.mod.common.api.spawning.detail.PokemonSpawnDetail;
import com.cobblemon.mod.common.api.spawning.detail.SpawnDetail;
import com.cobblemon.mod.common.api.spawning.influence.SpawningInfluence;
import com.cobblemon.mod.common.api.spawning.position.SpawnablePosition;
import com.cobblemon.mod.common.api.spawning.spawner.PlayerSpawner;
import com.cobblemon.mod.common.pokemon.Species;
import dev.darcosse.common.cobblemonsafari.config.SafariConfigManager;
import dev.darcosse.common.cobblemonsafari.dimension.SafariDimensions;
import dev.darcosse.common.cobblemonsafari.util.FormTypes;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Applies the Safari's rare happenings to Cobblemon's natural spawns around
 * players in the Safari:
 * <ul>
 *   <li>the swarm ({@link Swarm}): in its habitat, the swarm's spawn details
 *       are also offered among the common Pokémon (each still checks the
 *       spot), and their weight is multiplied there. Only the swarm's own
 *       details: a form left out by {@code events.swarm.excludeList} keeps
 *       its usual weight. How much of the habitat's spawns this makes depends
 *       on what else lives there, so {@link SwarmSpawner} keeps a floor of
 *       swarm Pokémon around players on top of it;</li>
 *   <li>the full moon ({@link SafariHappenings}): at night under a full
 *       moon, Pokémon of the configured types weigh more, within their
 *       rarity;</li>
 *   <li>the downpour ({@link Downpour}): during one, Pokémon of its types
 *       weigh more, within their rarity, on spots the rain falls on;</li>
 *   <li>the heatwave ({@link Heatwave}): during one, Pokémon of its types
 *       weigh more, within their rarity, in its hot habitats.</li>
 * </ul>
 *
 * <p>One shared instance on each player's spawner, put there by
 * {@link SafariSpawnBoost}. Outside the Safari, or for a spawner that is not
 * a player's, it changes nothing.
 *
 * <p>Thread safety: Cobblemon may weigh spawns off the server thread; this
 * reads the published snapshots of {@link Swarm} and
 * {@link SafariHappenings} and a concurrent cache of species types.
 */
public final class SafariHappeningsInfluence implements SpawningInfluence {

    /** The one instance; spawners hold it, so it is found again by identity. */
    public static final SafariHappeningsInfluence INSTANCE = new SafariHappeningsInfluence();

    /**
     * Spawn detail to the lower-case type names of the form it makes. By
     * detail rather than by species: an Alolan Vulpix detail is Ice, the
     * plain one Fire. Details are rebuilt on a data reload, which clears this.
     */
    private static final Map<PokemonSpawnDetail, Set<String>> TYPES = new ConcurrentHashMap<>();

    private SafariHappeningsInfluence() {
    }

    /** Forgets the type cache, after a data reload or a server start. */
    public static void clear() {
        TYPES.clear();
    }

    /**
     * Offers the swarming species among the common Pokémon, at a spot of its
     * habitat. Called by Cobblemon for every bucket of every spot asked;
     * returns null at once anywhere else.
     */
    @Override
    public List<SpawnDetail> injectSpawns(String bucket, SpawnablePosition position) {
        Swarm.Active swarm = Swarm.active();
        if (swarm == null || swarm.offered().isEmpty() || !Swarm.COMMON_BUCKET.equals(bucket)
                || !applies(position) || !position.getBiomeHolder().is(swarm.biome())) {
            return null;
        }
        List<SpawnDetail> offered = new ArrayList<>(swarm.offered().size());
        for (SpawnDetail detail : swarm.offered()) {
            // Cobblemon does not check injected details itself: the detail's own
            // conditions (kind of spot, light, time) must accept the spot.
            if (detail.isSatisfiedBy(position)) {
                offered.add(detail);
            }
        }
        return offered.isEmpty() ? null : offered;
    }

    /**
     * Multiplies the weight of the swarm's details in its habitat, of the
     * full moon's types on a full moon night, of the downpour's types
     * where it falls, and of the heatwave's types in its habitats. Called for every candidate
     * of every spawn attempt: a few field reads, set lookups and an identity
     * scan of the swarm's details (a handful).
     */
    @Override
    public float affectWeight(SpawnDetail detail, SpawnablePosition position, float weight) {
        if (!(detail instanceof PokemonSpawnDetail pokemonDetail) || !applies(position)) {
            return weight;
        }
        String raw = pokemonDetail.getPokemon().getSpecies();
        if (raw == null) {
            return weight;
        }
        String speciesId = SpeciesRules.normalizeId(raw);
        float result = weight;
        Swarm.Active swarm = Swarm.active();
        // By detail, not by species: the swarm is made of chosen details, and an
        // excluded form of the same species must not ride on its boost.
        if (swarm != null && swarm.speciesId().equals(speciesId) && swarm.details().contains(detail)
                && position.getBiomeHolder().is(swarm.biome())) {
            result *= (float) SafariConfigManager.get().events.swarm.weightMultiplier;
        }
        if (SafariHappenings.fullMoon() && hasType(pokemonDetail, SafariHappenings.fullMoonTypes())) {
            result *= (float) SafariConfigManager.get().events.fullMoon.weightMultiplier;
        }
        // The type test first: it is a cached set lookup, the spot test a biome temperature read.
        if (Downpour.isActive() && hasType(pokemonDetail, Downpour.types())
                && Downpour.fallsOnSpot(position.getCanSeeSky(), position.getBiomeHolder().value(),
                position.getPosition())) {
            result *= (float) SafariConfigManager.get().events.downpour.weightMultiplier;
        }
        if (Heatwave.isActive() && hasType(pokemonDetail, Heatwave.types()) && Heatwave.bakes(position.getBiomeHolder())) {
            result *= (float) SafariConfigManager.get().events.heatwave.weightMultiplier;
        }
        return result;
    }

    /** Whether the spot is one of a player's spawner in the Safari. */
    private static boolean applies(SpawnablePosition position) {
        return position.getSpawner() instanceof PlayerSpawner && SafariDimensions.isSafari(position.getWorld());
    }

    /** Whether one of the types of the form the detail makes is in a happening's favoured types. */
    private static boolean hasType(PokemonSpawnDetail detail, Set<String> favoured) {
        if (favoured.isEmpty()) {
            return false;
        }
        Set<String> types = TYPES.computeIfAbsent(detail, SafariHappeningsInfluence::typesOf);
        for (String type : types) {
            if (favoured.contains(type)) {
                return true;
            }
        }
        return false;
    }

    /** Type names of the detail's form, lower case; empty for an unknown species. */
    private static Set<String> typesOf(PokemonSpawnDetail detail) {
        String raw = detail.getPokemon().getSpecies();
        ResourceLocation id = raw == null ? null : ResourceLocation.tryParse(SpeciesRules.normalizeId(raw));
        Species species = id == null ? null : PokemonSpecies.getByIdentifier(id);
        return species == null ? Set.of() : FormTypes.of(species, detail.getPokemon());
    }
}
