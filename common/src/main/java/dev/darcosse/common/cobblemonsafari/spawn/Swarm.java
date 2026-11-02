package dev.darcosse.common.cobblemonsafari.spawn;

import com.cobblemon.mod.common.api.spawning.CobblemonSpawnPools;
import com.cobblemon.mod.common.api.spawning.detail.PokemonSpawnDetail;
import com.cobblemon.mod.common.api.spawning.detail.SpawnDetail;
import com.cobblemon.mod.common.pokemon.Species;
import dev.darcosse.common.cobblemonsafari.biome.SafariBiomes;
import dev.darcosse.common.cobblemonsafari.config.SafariConfig;
import dev.darcosse.common.cobblemonsafari.config.SafariConfigManager;
import dev.darcosse.common.cobblemonsafari.world.SafariWorldState;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.biome.Biome;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeSet;

/**
 * The swarm of the current rotation, if any ({@code events.swarm}): one
 * uncommon or rare species of the Safari coming out in numbers in one of
 * its habitats until the next rotation.
 *
 * <p>Drawn like the specials, from the rotation index and the world's salt,
 * so it is the same for everyone, survives restarts and changes with
 * {@code /safari rotate}. Candidates: species with natural spawn data in
 * the Safari, uncommon or rare there, let in by {@code spawns}, not of a
 * special category. The habitat is one of the species' own, so its spawn
 * data already accepts it.
 *
 * <p>Server side; the spawn threads read the published {@link #active()}
 * snapshot only.
 */
public final class Swarm {

    /** Bucket the swarming species is offered in, besides its own: Cobblemon's most picked one. */
    static final String COMMON_BUCKET = "common";

    /** Mixed into the draw seed, so the swarm does not follow the specials' own draw. */
    private static final long SEED_MIX = 0x5A4D_5741_524DL;

    /**
     * The swarm as the spawn threads need it, published as one immutable
     * object.
     *
     * @param species   the swarming species
     * @param speciesId its id, as spawn details write it once normalized ("cobblemon:dratini")
     * @param biome     the habitat it swarms in
     * @param rarity    its rarity in the Safari
     * @param details   its natural spawn details outside the common bucket, offered in the common one
     * @param aspects   aspects of its most common natural spawn (regional form...), for the board's model
     */
    public record Active(Species species, String speciesId, ResourceKey<Biome> biome, Rarity rarity,
                         List<SpawnDetail> details, List<String> aspects) {
    }

    /** The swarm the spawn threads see; null when there is none. */
    private static volatile Active active;
    /** Rotation and index revision {@link #active} was drawn for. Server thread only. */
    private static long drawnPeriod = Long.MIN_VALUE;
    private static long drawnRevision = Long.MIN_VALUE;
    /** The config object the draw used (replaced on every load, so identity tells a reload). */
    private static SafariConfig drawnConfig;

    private Swarm() {
    }

    /** Forgets the swarm; for a server start, as single player reuses this class. */
    public static void reset() {
        active = null;
        drawnPeriod = Long.MIN_VALUE;
        drawnRevision = Long.MIN_VALUE;
        drawnConfig = null;
    }

    /** The current swarm for the spawn threads, or null. */
    @Nullable
    static Active active() {
        return active;
    }

    /**
     * The swarm of the current rotation, drawn on first use after a new
     * rotation, a data reload or a config reload. Server thread. Cost of a
     * draw: one pass over the habitat index and one over Cobblemon's spawn
     * details, a few times a day.
     */
    @Nullable
    public static Active current(MinecraftServer server) {
        long period = FeaturedRotation.period();
        long revision = FeaturedRotation.revision();
        SafariConfig config = SafariConfigManager.get();
        if (period != drawnPeriod || revision != drawnRevision || config != drawnConfig) {
            Map<ResourceLocation, HabitatEntry> index = FeaturedRotation.index(server);
            if (index.isEmpty()) {
                // Species not loaded yet: no swarm, and try again next time.
                return null;
            }
            active = draw(server, index, period, config);
            drawnPeriod = period;
            drawnRevision = revision;
            drawnConfig = config;
        }
        return active;
    }

    /**
     * Whether a Pokémon of this species at this spot belongs to the current
     * swarm: the swarming species, in the swarm's habitat.
     */
    public static boolean isSwarming(Species species, Holder<Biome> biome) {
        Active swarm = active;
        return swarm != null && swarm.species() == species && biome.is(swarm.biome());
    }

    /**
     * Same-species cap around a player for a species: the swarm's
     * ({@code events.swarm.maxNearby}) for the swarming species, so a swarm
     * can look like one, the usual one otherwise. Read by the spawn threads.
     *
     * @param usual {@code spawns.maxSameSpeciesNearby}; 0 or less means no cap, and stays so
     */
    static int sameSpeciesCap(String speciesId, int usual) {
        Active swarm = active;
        int swarmCap = SafariConfigManager.get().events.swarm.maxNearby;
        if (usual <= 0 || swarm == null || swarmCap <= 0 || !swarm.speciesId().equals(speciesId)) {
            return usual;
        }
        return Math.max(usual, swarmCap);
    }

    /** Draws the swarm of a rotation, or null when it has none. */
    @Nullable
    private static Active draw(MinecraftServer server, Map<ResourceLocation, HabitatEntry> index,
                               long period, SafariConfig config) {
        SafariConfig.Swarm settings = config.events.swarm;
        if (!settings.enabled || !config.spawns.nativeSpawns) {
            return null;
        }
        Random random = new Random(period * 31L ^ SafariWorldState.get(server).rotationSalt() ^ SEED_MIX);
        if (random.nextDouble() >= settings.chance) {
            return null;
        }
        // The index is sorted by species id: the candidate order, and so the draw, is reproducible.
        List<HabitatEntry> candidates = new ArrayList<>();
        for (HabitatEntry entry : index.values()) {
            if (entry.natural() && (entry.rarity() == Rarity.UNCOMMON || entry.rarity() == Rarity.RARE)
                    && SpeciesRules.isAllowed(entry.species(), SpawnSource.NATURAL)
                    && !SpeciesRules.isSpecial(entry.species()) && !habitatsOf(entry).isEmpty()) {
                candidates.add(entry);
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }
        HabitatEntry chosen = candidates.get(random.nextInt(candidates.size()));
        List<ResourceKey<Biome>> habitats = habitatsOf(chosen);
        ResourceKey<Biome> biome = habitats.get(random.nextInt(habitats.size()));
        String speciesId = chosen.species().getResourceIdentifier().toString();
        // Sorted, as for the specials: the same data always gives the same payload.
        List<String> aspects = chosen.template() == null ? List.of()
                : List.copyOf(new TreeSet<>(chosen.template().getAspects()));
        return new Active(chosen.species(), speciesId, biome, chosen.rarity(), detailsOf(speciesId), aspects);
    }

    /** The species' Safari habitats, land then water, in the Safari's canonical order. */
    private static List<ResourceKey<Biome>> habitatsOf(HabitatEntry entry) {
        List<ResourceKey<Biome>> habitats = new ArrayList<>();
        for (ResourceKey<Biome> biome : SafariBiomes.SURFACE_HABITATS) {
            if (entry.landHabitats().contains(biome) || entry.waterHabitats().contains(biome)) {
                habitats.add(biome);
            }
        }
        return habitats;
    }

    /** The species' natural spawn details outside the common bucket. */
    private static List<SpawnDetail> detailsOf(String speciesId) {
        List<SpawnDetail> details = new ArrayList<>();
        for (SpawnDetail detail : CobblemonSpawnPools.WORLD_SPAWN_POOL.getDetails()) {
            if (detail instanceof PokemonSpawnDetail pokemonDetail && !COMMON_BUCKET.equals(detail.getBucket())) {
                String species = pokemonDetail.getPokemon().getSpecies();
                if (species != null && SpeciesRules.normalizeId(species).equals(speciesId)) {
                    details.add(detail);
                }
            }
        }
        return List.copyOf(details);
    }
}
