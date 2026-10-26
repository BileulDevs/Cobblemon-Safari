package dev.darcosse.common.cobblemonsafari.bounty;

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies;
import com.cobblemon.mod.common.api.spawning.CobblemonSpawnPools;
import com.cobblemon.mod.common.api.spawning.condition.SpawningCondition;
import com.cobblemon.mod.common.api.spawning.detail.PokemonSpawnDetail;
import com.cobblemon.mod.common.api.spawning.detail.SpawnDetail;
import com.cobblemon.mod.common.api.types.ElementalType;
import com.cobblemon.mod.common.pokemon.Species;
import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import dev.darcosse.common.cobblemonsafari.biome.SafariBiomes;
import dev.darcosse.common.cobblemonsafari.config.BountySpeciesFilter;
import dev.darcosse.common.cobblemonsafari.spawn.Rarity;
import dev.darcosse.common.cobblemonsafari.spawn.SpawnSource;
import dev.darcosse.common.cobblemonsafari.spawn.SpeciesRules;
import kotlin.ranges.IntRange;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.biome.Biome;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The species missions can be met with: those whose natural spawns in the
 * Safari include one easy enough under {@code bounties.speciesFilter}, with
 * what the draw needs from them (rarity, types, habitats, levels).
 *
 * <p>Why read Cobblemon's spawn pool here rather than the habitat index of
 * the specials: the index merges a species' spawns and rewrites cave spawns
 * as night ones for the surface spawner, so it can no longer tell a plain
 * spawn from a fished, cave, nocturnal or rainy one. The filter needs each
 * spawn as written.
 *
 * <p>Built on demand and kept until the filter or the data changes (the
 * draw calls {@link #of} with the current values). Server side.
 */
final class BountyPool {

    /** Spawn positions Cobblemon counts as water, as in SafariHabitatIndex. */
    private static final Set<String> WATER_POSITION_TYPES = Set.of("submerged", "surface", "seafloor", "fishing");

    /**
     * Highest sky light an underground spawn may ask for, as in SpawnWindow:
     * below 8 a spawn wants to be out of the sky. Changing one without the
     * other would make the specials and the missions disagree on caves.
     */
    private static final int UNDERGROUND_MAX_SKY_LIGHT = 8;

    /** The pool and what it was built from, to rebuild it only when they change. */
    private static BountyPool cached;
    private static Object cachedFilter;
    private static Object cachedSpawnPool;
    private static int cachedSpawnCount = -1;

    /** Eligible species, sorted by id so that the draw is reproducible. */
    final List<Species> species;
    /** The most common rarity of each species among its eligible spawns. */
    final Map<Species, Rarity> rarity;
    /** How many eligible species have each type, by type name, in a stable order. */
    final Map<String, Integer> typeWeights;
    /** How many eligible species can be met in each Safari habitat, in the habitats' canonical order. */
    final Map<ResourceKey<Biome>, Integer> habitats;
    /** Eligible species that evolved and evolve no further. */
    final List<Species> evolved;
    /** Highest wild level of each eligible species. */
    final Map<Species, Integer> maxLevel;

    private BountyPool(List<Species> species, Map<Species, Rarity> rarity, Map<String, Integer> typeWeights,
                       Map<ResourceKey<Biome>, Integer> habitats, List<Species> evolved, Map<Species, Integer> maxLevel) {
        this.species = species;
        this.rarity = rarity;
        this.typeWeights = typeWeights;
        this.habitats = habitats;
        this.evolved = evolved;
        this.maxLevel = maxLevel;
    }

    /**
     * The pool for the current spawn data and filter. Cost: on a change, one
     * pass over Cobblemon's natural spawns, each condition checked against
     * the Safari's habitats (a few thousand biome tests); nothing otherwise.
     */
    static BountyPool of(MinecraftServer server, BountySpeciesFilter filter) {
        Object spawnPool = CobblemonSpawnPools.WORLD_SPAWN_POOL;
        int spawnCount = spawnPool == null ? 0 : CobblemonSpawnPools.WORLD_SPAWN_POOL.getDetails().size();
        if (cached != null && cachedFilter == filter && cachedSpawnPool == spawnPool && cachedSpawnCount == spawnCount) {
            return cached;
        }
        cached = build(server, filter);
        cachedFilter = filter;
        cachedSpawnPool = spawnPool;
        cachedSpawnCount = spawnCount;
        CobblemonSafari.LOGGER.info("Safari missions can name {} species", cached.species.size());
        return cached;
    }

    /** Forgets the pool; the next draw rebuilds it (data or config reload). */
    static void invalidate() {
        cached = null;
    }

    private static BountyPool build(MinecraftServer server, BountySpeciesFilter filter) {
        Registry<Biome> registry = server.registryAccess().registryOrThrow(Registries.BIOME);
        Map<ResourceKey<Biome>, Holder<Biome>> holders = new LinkedHashMap<>();
        for (ResourceKey<Biome> key : SafariBiomes.SURFACE_HABITATS) {
            registry.getHolder(key).ifPresent(holder -> holders.put(key, holder));
        }
        Set<Rarity> rarities = new HashSet<>();
        for (String bucket : filter.rarities) {
            rarities.add(Rarity.fromBucket(bucket));
        }
        Set<String> excludedSpecies = new HashSet<>();
        for (String id : filter.excludedSpecies) {
            excludedSpecies.add(SpeciesRules.normalizeId(id));
        }
        Set<String> excludedTypes = new HashSet<>();
        for (String type : filter.excludedTypes) {
            excludedTypes.add(type.toLowerCase(Locale.ROOT));
        }

        Map<ResourceLocation, Species> eligible = new TreeMap<>();
        Map<Species, Rarity> rarity = new LinkedHashMap<>();
        Map<Species, Set<ResourceKey<Biome>>> where = new LinkedHashMap<>();
        Map<Species, Integer> maxLevel = new LinkedHashMap<>();
        if (CobblemonSpawnPools.WORLD_SPAWN_POOL != null) {
            for (SpawnDetail raw : CobblemonSpawnPools.WORLD_SPAWN_POOL.getDetails()) {
                if (!(raw instanceof PokemonSpawnDetail detail)) {
                    continue;
                }
                Species species = speciesOf(detail);
                if (species == null || !species.getImplemented() || !allowed(species, excludedSpecies, excludedTypes)) {
                    continue;
                }
                Rarity spawnRarity = Rarity.fromBucket(detail.getBucket());
                if (!rarities.contains(spawnRarity)) {
                    continue;
                }
                boolean water = isWater(detail);
                if (water && !filter.allowWater) {
                    continue;
                }
                Set<ResourceKey<Biome>> biomes = easyBiomes(detail, holders, filter);
                if (biomes.isEmpty()) {
                    continue;
                }
                eligible.put(species.getResourceIdentifier(), species);
                rarity.merge(species, spawnRarity, (a, b) -> a.ordinal() <= b.ordinal() ? a : b);
                where.computeIfAbsent(species, ignored -> new HashSet<>()).addAll(biomes);
                IntRange levels = detail.getLevelRange();
                if (levels != null) {
                    maxLevel.merge(species, levels.getLast(), Math::max);
                }
            }
        }

        List<Species> species = List.copyOf(eligible.values());
        Map<String, Integer> typeWeights = new TreeMap<>();
        List<Species> evolved = new ArrayList<>();
        for (Species one : species) {
            for (ElementalType type : one.getTypes()) {
                typeWeights.merge(type.getName(), 1, Integer::sum);
            }
            if (one.getEvolutions().isEmpty() && one.getPreEvolution() != null) {
                evolved.add(one);
            }
        }
        Map<ResourceKey<Biome>, Integer> habitats = new LinkedHashMap<>();
        for (ResourceKey<Biome> key : holders.keySet()) {
            int count = 0;
            for (Species one : species) {
                if (where.get(one).contains(key)) {
                    count++;
                }
            }
            if (count > 0) {
                habitats.put(key, count);
            }
        }
        evolved.sort(Comparator.comparing(one -> one.getResourceIdentifier().toString()));
        return new BountyPool(species, rarity, typeWeights, habitats, List.copyOf(evolved), maxLevel);
    }

    /**
     * The Safari habitats where one condition of this spawn applies and
     * passes the filter's cave, hour and weather switches. Each condition
     * of a spawn is an alternative (Gastly: in the dark underground, or in a
     * spooky forest at night), so each is judged on its own.
     */
    private static Set<ResourceKey<Biome>> easyBiomes(PokemonSpawnDetail detail,
                                                      Map<ResourceKey<Biome>, Holder<Biome>> holders,
                                                      BountySpeciesFilter filter) {
        Set<ResourceKey<Biome>> out = new HashSet<>();
        for (SpawningCondition<?> condition : detail.getConditions()) {
            if (condition.getBiomes() == null || condition.getBiomes().isEmpty()) {
                // Says nothing about the habitat, as in SafariHabitatIndex: "anywhere at night".
                continue;
            }
            boolean underground = Boolean.FALSE.equals(condition.getCanSeeSky())
                    || (condition.getMaxSkyLight() != null && condition.getMaxSkyLight() < UNDERGROUND_MAX_SKY_LIGHT);
            boolean timed = condition.getTimeRange() != null;
            boolean weathered = condition.isRaining() != null || condition.isThundering() != null;
            if ((underground && !filter.allowCaves) || (timed && !filter.allowTimeRestricted)
                    || (weathered && !filter.allowWeatherRestricted)) {
                continue;
            }
            for (Map.Entry<ResourceKey<Biome>, Holder<Biome>> habitat : holders.entrySet()) {
                if (fits(condition, habitat.getValue()) && !excluded(detail, habitat.getValue())) {
                    out.add(habitat.getKey());
                }
            }
        }
        return out;
    }

    /** Whether a condition names a biome that fits. */
    private static boolean fits(SpawningCondition<?> condition, Holder<Biome> holder) {
        return condition.getBiomes() != null && condition.getBiomes().stream().anyMatch(biome -> biome.fits(holder));
    }

    /** Whether an anticondition of the spawn rules this biome out. */
    private static boolean excluded(PokemonSpawnDetail detail, Holder<Biome> holder) {
        for (SpawningCondition<?> anti : detail.getAnticonditions()) {
            if (fits(anti, holder)) {
                return true;
            }
        }
        return false;
    }

    /** Whether a spawn happens in the water; a spawn file left without a position type counts as land. */
    private static boolean isWater(PokemonSpawnDetail detail) {
        try {
            return WATER_POSITION_TYPES.contains(detail.getSpawnablePositionType().getName());
        } catch (RuntimeException e) {
            // lateinit in Cobblemon: a broken spawn file can leave it unset.
            return false;
        }
    }

    /** Whether the Safari's rules and the filter's exclusions let a species be asked. */
    private static boolean allowed(Species species, Set<String> excludedSpecies, Set<String> excludedTypes) {
        if (SpeciesRules.isSpecial(species) || !SpeciesRules.isAllowed(species, SpawnSource.NATURAL)
                || excludedSpecies.contains(species.getResourceIdentifier().toString())) {
            return false;
        }
        for (ElementalType type : species.getTypes()) {
            if (excludedTypes.contains(type.getName())) {
                return false;
            }
        }
        return true;
    }

    /** The species a spawn names, with or without a namespace; null when unknown. */
    private static Species speciesOf(PokemonSpawnDetail detail) {
        String raw = detail.getPokemon() == null ? null : detail.getPokemon().getSpecies();
        if (raw == null || raw.isBlank()) {
            return null;
        }
        ResourceLocation id = ResourceLocation.tryParse(SpeciesRules.normalizeId(raw));
        return id == null ? null : PokemonSpecies.getByIdentifier(id);
    }
}
