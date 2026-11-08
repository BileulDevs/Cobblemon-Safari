package dev.darcosse.common.cobblemonsafari.spawn;

import com.cobblemon.mod.common.api.spawning.condition.SpawningCondition;
import com.cobblemon.mod.common.api.spawning.detail.PokemonSpawnDetail;
import com.cobblemon.mod.common.api.spawning.detail.SpawnDetail;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.cobblemon.mod.common.pokemon.Species;
import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
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
import java.util.EnumMap;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

/**
 * The swarm of the current rotation, if any ({@code events.swarm}): one
 * species of the Safari coming out in numbers in one of its habitats until
 * the next rotation.
 *
 * <p>Drawn like the specials, from the rotation index and the world's salt,
 * so it is the same for everyone, survives restarts and changes with
 * {@code /safari rotate}. Candidates are pairs of a species and a habitat
 * where a player's spawner can really bring that species
 * ({@link SwarmSpawnData}), filtered by:
 * <ul>
 *   <li>the excluded species of {@code spawns}, as for every natural spawn;</li>
 *   <li>{@code events.swarm.excludeList}, form by form ({@link SwarmRules});</li>
 *   <li>{@code events.swarm.rarities}: the legendary-class categories that
 *       may swarm. They replace those of {@code spawns} for the swarm, with
 *       their own per-rotation cap ({@link SpawnSource#SWARM});</li>
 *   <li>{@code events.swarm.buckets}: the species' bucket in that habitat,
 *       its most common one there. Legendary-class species always count as
 *       ultra-rare, as everywhere in the Safari.</li>
 * </ul>
 * A bucket is drawn first, by {@code events.swarm.weights}, among those with
 * candidates; then a species of that bucket, then one of its habitats where it
 * is of that bucket.
 *
 * <p>The swarm is made of the species' usable details in that habitat. They
 * reach the players two ways: inside Cobblemon's natural spawns
 * ({@link SafariHappeningsInfluence}) and through the swarm's own spawner
 * ({@link SwarmSpawner}), which keeps some around players in the habitat
 * whatever else lives there. A species met only through Poké Snacks there
 * (Gallade since Cobblemon 1.8) may swarm too, through the swarm's spawner
 * alone, which lifts that one requirement for it.
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
     * @param rarity    its rarity in that habitat
     * @param details   the spawn details the swarm is made of: the species' usable details in
     *                  that habitat, excluded forms removed. Compared by identity with the
     *                  details Cobblemon weighs, which are the same objects.
     * @param offered   those of {@code details} outside the common bucket, offered in the common
     *                  one (the others are there already)
     * @param aspects   aspects of the swarm's main detail (regional form...), for the board's model
     * @param excluded  the parsed excludeList of the draw, to tell an excluded form caught in the
     *                  habitat from a swarm Pokémon
     * @param snackFree for the details of {@code details} that only spawn from a Poké Snack, their
     *                  conditions without that requirement, keyed by identity ({@link SwarmSpawnData}).
     *                  Those details only come through {@link SwarmSpawner}: Cobblemon's own
     *                  spawner keeps refusing them, so they are not in {@code offered}.
     */
    public record Active(Species species, String speciesId, ResourceKey<Biome> biome, Rarity rarity,
                         List<SpawnDetail> details, List<SpawnDetail> offered, List<String> aspects,
                         List<SwarmRules.Exclusion> excluded,
                         Map<SpawnDetail, List<SpawningCondition<?>>> snackFree) {
    }

    /** One habitat a candidate species may swarm in, with what it would be made of there. */
    private record Habitat(ResourceKey<Biome> biome, Rarity rarity, List<PokemonSpawnDetail> details) {
    }

    /** A species that may swarm in one bucket, with its habitats of that bucket. */
    private record Candidate(SwarmSpawnData.SpeciesDetails data, List<Habitat> habitats) {
    }

    /** The swarm the spawn threads see; null when there is none. */
    private static volatile Active active;
    /** Rotation and index revision {@link #active} was drawn for. Server thread only. */
    private static long drawnPeriod = Long.MIN_VALUE;
    private static long drawnRevision = Long.MIN_VALUE;
    /** The config object the draw used (replaced on every load, so identity tells a reload). */
    private static SafariConfig drawnConfig;
    /**
     * Usable spawn details for the current data, built at the first draw that
     * needs them and kept until a data reload: they do not depend on the
     * config, and rebuilding them on every rotation would cost a pass over
     * every spawn detail each time. Server thread only.
     */
    @Nullable
    private static List<SwarmSpawnData.SpeciesDetails> spawnData;

    private Swarm() {
    }

    /** Forgets the swarm and the spawn data; for a server start, as single player reuses this class. */
    public static void reset() {
        active = null;
        drawnPeriod = Long.MIN_VALUE;
        drawnRevision = Long.MIN_VALUE;
        drawnConfig = null;
        spawnData = null;
    }

    /**
     * Drops the cached spawn data after a datapack reload, which may change
     * spawn files, biome tags or species. The swarm itself is redrawn with
     * the next index revision.
     */
    public static void invalidateSpawnData() {
        spawnData = null;
    }

    /** The current swarm for the spawn threads, or null. */
    @Nullable
    static Active active() {
        return active;
    }

    /**
     * The swarm of the current rotation, drawn on first use after a new
     * rotation, a data reload or a config reload. Server thread. Cost of a
     * draw: a walk of the cached spawn data, a few times a day; the first
     * draw after a start or a data reload also builds that data (see
     * {@link SwarmSpawnData}).
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
            active = draw(server, period, config);
            drawnPeriod = period;
            drawnRevision = revision;
            drawnConfig = config;
        }
        return active;
    }

    /**
     * Whether this Pokémon at this spot belongs to the current swarm: the
     * swarming species, in the swarm's habitat, and not of a form
     * {@code excludeList} leaves out (an Alolan Vulpix caught during a Vulpix
     * swarm that excludes it is not part of the swarm).
     */
    public static boolean isSwarming(Pokemon pokemon, Holder<Biome> biome) {
        Active swarm = active;
        return swarm != null && swarm.species() == pokemon.getSpecies() && biome.is(swarm.biome())
                && !SwarmRules.isExcluded(swarm.excluded(), pokemon);
    }

    /**
     * Whether this spawn detail at this spot is one of the current swarm's:
     * for the spawn threads, before any Pokémon exists. Identity of the
     * detail, so an excluded form of the swarming species is not one.
     */
    static boolean isSwarmDetail(SpawnDetail detail, Holder<Biome> biome) {
        Active swarm = active;
        return swarm != null && swarm.details().contains(detail) && biome.is(swarm.biome());
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

    /** The usable spawn data, built on first use. */
    private static List<SwarmSpawnData.SpeciesDetails> spawnData(MinecraftServer server) {
        if (spawnData == null) {
            spawnData = SwarmSpawnData.build(server);
        }
        return spawnData;
    }

    /** Draws the swarm of a rotation, or null when it has none. */
    @Nullable
    private static Active draw(MinecraftServer server, long period, SafariConfig config) {
        SafariConfig.Swarm settings = config.events.swarm;
        if (!settings.enabled || !config.spawns.nativeSpawns) {
            return null;
        }
        Random random = new Random(period * 31L ^ SafariWorldState.get(server).rotationSalt() ^ SEED_MIX);
        if (random.nextDouble() >= settings.chance) {
            return null;
        }
        Set<Rarity> buckets = SwarmRules.allowedBuckets(settings.buckets);
        List<SwarmRules.Exclusion> exclusions = SwarmRules.parseExclusions(settings.excludeList);
        // The spawn data is sorted by species id: the candidate order, and so the draw, is reproducible.
        Map<Rarity, List<Candidate>> byBucket = new EnumMap<>(Rarity.class);
        for (SwarmSpawnData.SpeciesDetails data : spawnData(server)) {
            // The swarm's own categories (events.swarm.rarities), plus the natural
            // spawns' excluded species: see SpawnSource.SWARM.
            if (!SpeciesRules.isAllowed(data.species, SpawnSource.SWARM)) {
                continue;
            }
            for (Map.Entry<Rarity, List<Habitat>> entry : habitatsByBucket(data, exclusions).entrySet()) {
                if (buckets.contains(entry.getKey()) && SwarmRules.weightOf(entry.getKey(), settings.weights) > 0) {
                    byBucket.computeIfAbsent(entry.getKey(), rarity -> new ArrayList<>())
                            .add(new Candidate(data, entry.getValue()));
                }
            }
        }
        // Bucket first, by its weight, then a species of it, then one of its habitats
        // of that bucket: a bucket's chance is its weight, however many species it holds.
        Rarity bucket = drawBucket(byBucket, settings.weights, random);
        if (bucket == null) {
            CobblemonSafari.LOGGER.info("Safari swarm: no species fits events.swarm (buckets {}, {} exclusions),"
                    + " no swarm this rotation", settings.buckets, exclusions.size());
            return null;
        }
        List<Candidate> candidates = byBucket.get(bucket);
        Candidate chosen = candidates.get(random.nextInt(candidates.size()));
        Habitat habitat = chosen.habitats().get(random.nextInt(chosen.habitats().size()));

        List<SpawnDetail> details = List.copyOf(habitat.details());
        List<SpawnDetail> offered = new ArrayList<>();
        // Identity, like the spawn data: spawn details are compared as objects everywhere here.
        Map<SpawnDetail, List<SpawningCondition<?>>> snackFree = new IdentityHashMap<>();
        PokemonSpawnDetail main = null;
        for (PokemonSpawnDetail detail : habitat.details()) {
            List<SpawningCondition<?>> withoutSnack = chosen.data().snackFree.get(detail);
            if (withoutSnack != null) {
                snackFree.put(detail, withoutSnack);
            } else if (!COMMON_BUCKET.equals(detail.getBucket())) {
                // A snack-only detail would be refused at every spot: not offered.
                offered.add(detail);
            }
            // The heaviest detail is the form players meet most: the one the board shows.
            if (main == null || detail.getWeight() > main.getWeight()) {
                main = detail;
            }
        }
        // Sorted, as for the specials: the same data always gives the same payload.
        List<String> aspects = List.copyOf(new TreeSet<>(main.getPokemon().getAspects()));
        CobblemonSafari.LOGGER.info("Safari swarm: {} in {} ({}, {} spawn details{}, drawn among {} {} species)",
                chosen.data().speciesId, habitat.biome().location(), habitat.rarity(), details.size(),
                snackFree.isEmpty() ? "" : ", Poké Snack-only: swarm spawner only", candidates.size(), bucket);
        return new Active(chosen.data().species, chosen.data().speciesId, habitat.biome(), habitat.rarity(),
                details, List.copyOf(offered), aspects, exclusions, Collections.unmodifiableMap(snackFree));
    }

    /**
     * A weighted pick among the buckets that have candidates; null when none has.
     * The candidates were only kept for buckets of positive weight.
     */
    @Nullable
    private static Rarity drawBucket(Map<Rarity, List<Candidate>> byBucket, SafariConfig.SpawnWeights weights,
                                     Random random) {
        int total = 0;
        for (Rarity rarity : byBucket.keySet()) {
            total += SwarmRules.weightOf(rarity, weights);
        }
        if (total <= 0) {
            return null;
        }
        // EnumMap iterates in declaration order: the roll maps to the same bucket every time.
        int roll = random.nextInt(total);
        for (Rarity rarity : byBucket.keySet()) {
            roll -= SwarmRules.weightOf(rarity, weights);
            if (roll < 0) {
                return rarity;
            }
        }
        return null;
    }

    /**
     * A species' habitats grouped by its bucket there, once the excluded forms
     * are removed. Its bucket in a habitat is its most common one among the
     * details left; legendary-class species are ultra-rare wherever they live,
     * as in the habitat index.
     */
    private static Map<Rarity, List<Habitat>> habitatsByBucket(SwarmSpawnData.SpeciesDetails data,
                                                               List<SwarmRules.Exclusion> exclusions) {
        boolean special = SpeciesRules.isSpecial(data.species);
        Map<Rarity, List<Habitat>> grouped = new EnumMap<>(Rarity.class);
        for (Map.Entry<ResourceKey<Biome>, List<PokemonSpawnDetail>> entry : data.byHabitat.entrySet()) {
            List<PokemonSpawnDetail> kept = new ArrayList<>();
            Rarity rarity = null;
            for (PokemonSpawnDetail detail : entry.getValue()) {
                if (SwarmRules.isExcluded(exclusions, data.speciesId, detail.getPokemon())) {
                    continue;
                }
                kept.add(detail);
                // In Cobblemon 1.8.0 the bucket is a plain String (see SafariHabitatIndex).
                Rarity detailRarity = Rarity.fromBucket(detail.getBucket());
                if (rarity == null || detailRarity.ordinal() < rarity.ordinal()) {
                    rarity = detailRarity;
                }
            }
            if (!kept.isEmpty()) {
                Rarity effective = special ? Rarity.ULTRA_RARE : rarity;
                grouped.computeIfAbsent(effective, key -> new ArrayList<>())
                        .add(new Habitat(entry.getKey(), effective, List.copyOf(kept)));
            }
        }
        return grouped;
    }
}
