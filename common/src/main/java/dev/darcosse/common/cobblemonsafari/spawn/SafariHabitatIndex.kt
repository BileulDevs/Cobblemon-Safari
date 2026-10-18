package dev.darcosse.common.cobblemonsafari.spawn

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.api.spawning.CobblemonSpawnPools
import com.cobblemon.mod.common.api.spawning.condition.SpawningCondition
import com.cobblemon.mod.common.api.spawning.detail.PokemonSpawnDetail
import com.cobblemon.mod.common.pokemon.Species
import dev.darcosse.common.cobblemonsafari.CobblemonSafari
import dev.darcosse.common.cobblemonsafari.biome.SafariBiomes
import net.minecraft.core.Holder
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.biome.Biome
import java.util.TreeMap

/**
 * Works out, from whatever is installed, which species can live in which
 * Safari habitat.
 *
 * The source of truth is Cobblemon's own world spawn pool, which already merges
 * Cobblemon's spawns and every spawn datapack. A spawn detail matches a Safari
 * biome when one of its biome conditions accepts that biome, which happens
 * through the biome tag bridge (`data/cobblemon/tags/worldgen/biome`). The
 * matching itself is Cobblemon's ([RegistryLikeCondition.fits]), so tag
 * overrides by other datapacks are honoured.
 *
 * Written in Kotlin on purpose: the spawn-detail properties are Kotlin
 * properties, read here exactly as Just Enough Cobblemon reads them.
 *
 * Cost: one pass over every spawn detail times 19 surface biomes, a few
 * hundred thousand tag lookups. Run once per data reload, never per tick.
 */
object SafariHabitatIndex {

    /**
     * Cobblemon spawnable position types that put the Pokémon in water. Fishing
     * spawns count: a species only ever fished in lakes belongs to the lagoon.
     * Anything else (grounded, lava floor, custom types) spawns on land.
     */
    private val WATER_POSITION_TYPES = setOf("submerged", "surface", "seafloor", "fishing")

    /**
     * Habitats guessed from a type when a species has no natural spawn in a
     * Safari-like biome. Kept broad so that every type has somewhere to live;
     * keys are Cobblemon's lowercase type ids.
     */
    private val TYPE_HABITATS: Map<String, List<ResourceKey<Biome>>> = mapOf(
        "normal" to listOf(SafariBiomes.GRASSLAND, SafariBiomes.SAVANNA, SafariBiomes.FLOWER_MEADOW),
        "fire" to listOf(SafariBiomes.VOLCANIC, SafariBiomes.BADLANDS, SafariBiomes.DUNES),
        "water" to listOf(SafariBiomes.LAKE, SafariBiomes.RIVER, SafariBiomes.MARSH, SafariBiomes.SHORE),
        "grass" to listOf(SafariBiomes.WOODLAND, SafariBiomes.JUNGLE, SafariBiomes.FLOWER_MEADOW, SafariBiomes.GRASSLAND),
        "electric" to listOf(SafariBiomes.GRASSLAND, SafariBiomes.SAVANNA, SafariBiomes.ROCKY_HILLS),
        "ice" to listOf(SafariBiomes.GLACIER, SafariBiomes.SNOWFIELD),
        "fighting" to listOf(SafariBiomes.ROCKY_HILLS, SafariBiomes.PEAKS, SafariBiomes.BADLANDS),
        "poison" to listOf(SafariBiomes.MARSH, SafariBiomes.JUNGLE, SafariBiomes.DEEP_FOREST),
        "ground" to listOf(SafariBiomes.DUNES, SafariBiomes.BADLANDS, SafariBiomes.ROCKY_HILLS),
        "flying" to listOf(SafariBiomes.PEAKS, SafariBiomes.GRASSLAND, SafariBiomes.SAVANNA),
        "psychic" to listOf(SafariBiomes.FLOWER_MEADOW, SafariBiomes.DEEP_FOREST, SafariBiomes.MUSHROOM_GROVE),
        "bug" to listOf(SafariBiomes.WOODLAND, SafariBiomes.JUNGLE, SafariBiomes.DEEP_FOREST),
        "rock" to listOf(SafariBiomes.ROCKY_HILLS, SafariBiomes.PEAKS, SafariBiomes.BADLANDS),
        "ghost" to listOf(SafariBiomes.DEEP_FOREST, SafariBiomes.MARSH),
        "dragon" to listOf(SafariBiomes.PEAKS, SafariBiomes.LAKE, SafariBiomes.VOLCANIC),
        "dark" to listOf(SafariBiomes.DEEP_FOREST, SafariBiomes.BADLANDS),
        "steel" to listOf(SafariBiomes.PEAKS, SafariBiomes.ROCKY_HILLS),
        "fairy" to listOf(SafariBiomes.FLOWER_MEADOW, SafariBiomes.MUSHROOM_GROVE),
    )

    /** Namespace of species written without one in spawn files, as Cobblemon itself assumes. */
    private const val DEFAULT_SPECIES_NAMESPACE = "cobblemon"

    /**
     * Builds the index for the current data. Keys are sorted by species id so
     * that the daily draw, which walks this map, is reproducible.
     *
     * @param includeWithoutNaturalSpawn also index implemented species that
     *        never spawn naturally in a Safari-like biome, with type-based habitats
     */
    @JvmStatic
    fun build(server: MinecraftServer, includeWithoutNaturalSpawn: Boolean): Map<ResourceLocation, HabitatEntry> {
        val registry = server.registryAccess().registryOrThrow(Registries.BIOME)
        // A Safari biome missing from the registry means the datapack defining
        // it was removed; skipping it keeps the rest of the Safari working.
        val habitats: List<Pair<ResourceKey<Biome>, Holder<Biome>>> = SafariBiomes.SURFACE_HABITATS.mapNotNull { key ->
            runCatching { key to registry.getHolderOrThrow(key) }.getOrNull()
        }

        val builders = HashMap<ResourceLocation, EntryBuilder>()
        for (detail in CobblemonSpawnPools.WORLD_SPAWN_POOL.details) {
            if (detail !is PokemonSpawnDetail) continue
            val species = resolveSpecies(detail.pokemon.species) ?: continue
            if (!species.implemented) continue
            val matched = habitats.filter { (_, holder) -> accepts(detail, holder) }.map { it.first }
            if (matched.isEmpty()) continue
            // lateinit in Cobblemon: a broken spawn file can leave it unset.
            val positionType = runCatching { detail.spawnablePositionType.name }.getOrNull()
            val water = positionType in WATER_POSITION_TYPES
            val windows = windowsOf(detail, habitats, water)
            builders.getOrPut(species.resourceIdentifier) { EntryBuilder(species) }.add(detail, matched, water, windows)
        }

        val index = TreeMap<ResourceLocation, HabitatEntry>()
        builders.forEach { (id, builder) -> index[id] = builder.build() }

        if (includeWithoutNaturalSpawn) {
            for (species in PokemonSpecies.implemented) {
                if (index.containsKey(species.resourceIdentifier)) continue
                fromTypes(species)?.let { index[species.resourceIdentifier] = it }
            }
        }
        CobblemonSafari.LOGGER.info("Safari habitat index: {} species ({} from natural spawns)",
            index.size, builders.size)
        return index
    }

    /**
     * Whether a spawn detail accepts a biome: at least one condition names a
     * biome that fits, and no anticondition excludes it. Conditions without
     * any biome filter say nothing about habitat and are ignored, otherwise
     * every "spawns anywhere at night" detail would place its species everywhere.
     */
    private fun accepts(detail: PokemonSpawnDetail, holder: Holder<Biome>): Boolean {
        val included = detail.conditions.any { condition -> fitsBiome(condition, holder) }
        return included && detail.anticonditions.none { condition -> fitsBiome(condition, holder) }
    }

    /**
     * The time and light conditions of a spawn detail, one window per
     * condition that names Safari biomes: each condition of a detail is an
     * alternative (Gastly: in the dark anywhere underground, or in a spooky
     * forest at night), so each keeps its own biomes.
     */
    private fun windowsOf(detail: PokemonSpawnDetail, habitats: List<Pair<ResourceKey<Biome>, Holder<Biome>>>,
                          water: Boolean): List<SpawnWindow> =
        detail.conditions.mapNotNull { condition ->
            val biomes = habitats.filter { (_, holder) ->
                fitsBiome(condition, holder) && detail.anticonditions.none { fitsBiome(it, holder) }
            }.map { it.first }.toSet()
            if (biomes.isEmpty()) null
            else SpawnWindow.of(biomes, water, condition.timeRange?.ranges?.map { intArrayOf(it.first, it.last) } ?: emptyList(),
                condition.minLight, condition.maxLight, condition.minSkyLight, condition.maxSkyLight, condition.canSeeSky)
        }

    private fun fitsBiome(condition: SpawningCondition<*>, holder: Holder<Biome>): Boolean =
        condition.biomes?.any { it.fits(holder) } == true

    private fun resolveSpecies(raw: String?): Species? {
        if (raw.isNullOrBlank()) return null
        val id = if (raw.contains(':')) ResourceLocation.tryParse(raw)
        else ResourceLocation.tryParse("$DEFAULT_SPECIES_NAMESPACE:$raw")
        return id?.let { PokemonSpecies.getByIdentifier(it) }
    }

    /** Entry for a species with no natural Safari spawn, placed by its types. */
    private fun fromTypes(species: Species): HabitatEntry? {
        val land = LinkedHashSet<ResourceKey<Biome>>()
        val water = LinkedHashSet<ResourceKey<Biome>>()
        for (type in species.types) {
            for (biome in TYPE_HABITATS[type.showdownId] ?: continue) {
                if (biome in SafariBiomes.WATER_HABITATS) water.add(biome) else land.add(biome)
            }
        }
        if (land.isEmpty() && water.isEmpty()) return null
        // No natural spawn usually means a legendary, a starter-like species or
        // a datapack addition: rare is the honest default, special labels go higher.
        val rarity = if (SpeciesRules.isSpecial(species)) Rarity.ULTRA_RARE else Rarity.RARE
        return HabitatEntry(species, land, water, rarity, null, 0, 0, false, emptyList())
    }

    /** Accumulates every natural spawn detail of one species. */
    private class EntryBuilder(val species: Species) {
        private val land = LinkedHashSet<ResourceKey<Biome>>()
        private val water = LinkedHashSet<ResourceKey<Biome>>()
        private var rarity: Rarity? = null
        private var best: PokemonSpawnDetail? = null
        private val windows = ArrayList<SpawnWindow>()

        fun add(detail: PokemonSpawnDetail, biomes: List<ResourceKey<Biome>>, inWater: Boolean,
                detailWindows: List<SpawnWindow>) {
            if (inWater) water.addAll(biomes) else land.addAll(biomes)
            windows.addAll(detailWindows)
            // In the published Cobblemon 1.8.0 the bucket is a plain String
            // (`var bucket = ""` in SpawnDetail). Cobblemon's later main branch
            // turned it into a SpawnBucket object, which is what `.name` assumed.
            val detailRarity = Rarity.fromBucket(detail.bucket)
            // The most common bucket is the species' real abundance; its detail
            // also carries the form most players will expect to meet.
            if (rarity == null || detailRarity.ordinal < rarity!!.ordinal) {
                rarity = detailRarity
                best = detail
            }
        }

        fun build(): HabitatEntry {
            val special = SpeciesRules.isSpecial(species)
            val finalRarity = if (special) Rarity.ULTRA_RARE else rarity ?: Rarity.RARE
            val range = best?.levelRange
            return HabitatEntry(species, land, water, finalRarity, best?.pokemon?.copy(),
                range?.first ?: 0, range?.last ?: 0, true, windows.toList())
        }
    }
}
