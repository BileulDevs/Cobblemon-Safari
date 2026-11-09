package dev.darcosse.common.cobblemonsafari.spawn

import com.cobblemon.mod.common.api.spawning.CobblemonSpawnPools
import com.cobblemon.mod.common.api.spawning.condition.SpawningCondition
import com.cobblemon.mod.common.api.spawning.detail.PokemonSpawnDetail
import com.cobblemon.mod.common.api.spawning.detail.SpawnDetail
import com.cobblemon.mod.common.api.spawning.position.SpawnablePosition
import com.cobblemon.mod.common.api.spawning.position.calculators.SpawnablePositionCalculator
import com.cobblemon.mod.common.pokemon.Species
import dev.darcosse.common.cobblemonsafari.CobblemonSafari
import dev.darcosse.common.cobblemonsafari.biome.SafariBiomes
import net.minecraft.core.Holder
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.biome.Biome
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.IdentityHashMap
import java.util.TreeMap

/**
 * Works out which natural spawn details a swarm can use, habitat by habitat,
 * and how to judge them at a spot.
 *
 * Why this exists apart from [SafariHabitatIndex]: the index only asks
 * whether a detail's biome condition accepts a Safari biome. That is enough
 * to place a species on the board, whose own spawner ignores the rest of the
 * detail, but not for a swarm, which goes through Cobblemon's spawner and
 * therefore through every condition of the detail.
 *
 * A detail is usable in a habitat when its spawnable position type is one a
 * player's area spawner produces (fishing details are only reachable with a
 * rod), one of its conditions accepts the habitat's biome without requiring a
 * structure (the Safari carries none of those Cobblemon's spawn files name),
 * and no anticondition excludes the biome.
 *
 * Poké Snacks. Since Cobblemon 1.8 many species (Gallade, most fully evolved
 * Pokémon) only have details marked `isPokeSnack: true`, which a player's
 * spawner never satisfies: before 1.4.1 such a swarm was announced and never
 * spawned. A swarm is an exceptional event, so those species may swarm
 * anyway, but only where they have no ordinary spawn: in a habitat with at
 * least one ordinary detail, the snack-only ones are left out (Cobblemon 1.8
 * duplicates many spawns as snack-only copies, which would add nothing). The
 * snack-only details kept get a copy of their conditions without the snack
 * requirement ([SpeciesDetails.snackFree]); [satisfies] judges them with it.
 * Cobblemon's own objects are never modified: its spawner keeps refusing
 * those details in natural spawns, so they only come through [SwarmSpawner].
 *
 * Time, light, weather and block conditions are left to Cobblemon: they can
 * be met in the habitat at some point, and are checked on every spawn.
 *
 * Written in Kotlin for the same reason as [SafariHabitatIndex]: the spawn
 * detail and condition fields are Kotlin properties, some `lateinit`.
 *
 * Cost: one pass over every spawn detail times the Safari's surface habitats,
 * the same order as the index. Built on the server thread at the first swarm
 * draw after a server start or a data reload, then cached by [Swarm].
 */
object SwarmSpawnData {

    /**
     * The details of one species a swarm can use, by Safari habitat.
     *
     * @property species   the Cobblemon species
     * @property speciesId its id ("cobblemon:gallade")
     * @property byHabitat usable details per habitat, habitats in the Safari's canonical order
     * @property snackFree for the snack-only details kept, their conditions without the snack
     *                     requirement; keyed by identity. A detail absent here is judged by
     *                     Cobblemon's own `isSatisfiedBy`.
     */
    class SpeciesDetails(
        @JvmField val species: Species,
        @JvmField val speciesId: String,
        @JvmField val byHabitat: Map<ResourceKey<Biome>, List<PokemonSpawnDetail>>,
        @JvmField val snackFree: Map<SpawnDetail, List<SpawningCondition<*>>>
    )

    /** One detail's fit in one habitat, before the habitat's details are chosen. */
    private class Fit(val detail: PokemonSpawnDetail, val ordinary: Boolean)

    /** Copies already made, so a detail usable in several habitats is copied once. Build-local. */
    private class Copies {
        val byDetail = IdentityHashMap<SpawnDetail, List<SpawningCondition<*>>?>()
        var failed = 0
    }

    /**
     * Builds the usable details of every species for the current data,
     * sorted by species id so that the swarm draw, which walks this list in
     * order, is reproducible.
     */
    @JvmStatic
    fun build(server: MinecraftServer): List<SpeciesDetails> {
        val registry = server.registryAccess().registryOrThrow(Registries.BIOME)
        // A Safari biome missing from the registry means the datapack defining
        // it was removed; skipping it keeps the rest working, as in the index.
        val habitats: List<Pair<ResourceKey<Biome>, Holder<Biome>>> = SafariBiomes.SURFACE_HABITATS.mapNotNull { key ->
            runCatching { key to registry.getHolderOrThrow(key) }.getOrNull()
        }
        // Read from Cobblemon rather than hard-coded, so position types added by
        // other mods' area calculators count too. Fishing has no area calculator.
        val areaTypes = SpawnablePositionCalculator.prioritizedAreaCalculators.map { it.name }.toSet()

        val bySpecies = TreeMap<ResourceLocation, Pair<Species, LinkedHashMap<ResourceKey<Biome>, MutableList<Fit>>>>()
        var skippedUnreachable = 0
        for (detail in CobblemonSpawnPools.WORLD_SPAWN_POOL.details) {
            if (detail !is PokemonSpawnDetail) continue
            val species = SafariHabitatIndex.resolveSpecies(detail.pokemon.species) ?: continue
            if (!species.implemented) continue
            // lateinit in Cobblemon: a broken spawn file can leave it unset.
            val positionType = runCatching { detail.spawnablePositionType.name }.getOrNull() ?: continue
            // No condition at all says nothing about the habitat: ignored, as in the index.
            if (detail.conditions.isEmpty()) continue
            val placeable = detail.conditions.filter { it.structures.isNullOrEmpty() }
            if (positionType !in areaTypes || placeable.isEmpty()) {
                skippedUnreachable++
                continue
            }
            val byHabitat = lazy { bySpecies.getOrPut(species.resourceIdentifier) { species to LinkedHashMap() }.second }
            for ((key, holder) in habitats) {
                if (detail.anticonditions.any { SafariHabitatIndex.fitsBiome(it, holder) }) continue
                val fitting = placeable.filter { SafariHabitatIndex.fitsBiome(it, holder) }
                if (fitting.isEmpty()) continue
                val ordinary = fitting.any { it.isPokeSnack != true }
                byHabitat.value.getOrPut(key) { ArrayList() }.add(Fit(detail, ordinary))
            }
        }

        val copies = Copies()
        var snackOnlyHabitats = 0
        val result = bySpecies.map { (id, entry) ->
            val (species, fitsByHabitat) = entry
            // Re-ordered on the canonical habitat order: the draw picks a habitat
            // by index, so the order must not depend on the spawn files' order.
            val ordered = LinkedHashMap<ResourceKey<Biome>, List<PokemonSpawnDetail>>()
            val snackFree = IdentityHashMap<SpawnDetail, List<SpawningCondition<*>>>()
            for (key in SafariBiomes.SURFACE_HABITATS) {
                val fits = fitsByHabitat[key] ?: continue
                val ordinary = fits.filter { it.ordinary }.map { it.detail }
                if (ordinary.isNotEmpty()) {
                    ordered[key] = ordinary
                    continue
                }
                val kept = fits.mapNotNull { fit ->
                    copyWithoutSnack(fit.detail, copies)?.let { conditions ->
                        snackFree[fit.detail] = conditions
                        fit.detail
                    }
                }
                if (kept.isNotEmpty()) {
                    ordered[key] = kept
                    snackOnlyHabitats++
                }
            }
            SpeciesDetails(species, id.toString(), ordered, Collections.unmodifiableMap(snackFree))
        }.filter { it.byHabitat.isNotEmpty() }
        CobblemonSafari.LOGGER.info("Safari swarm data: {} species usable by swarms, {} habitats through Poké Snack-only " +
            "spawns ({} details out of a player's reach skipped)", result.size, snackOnlyHabitats, skippedUnreachable)
        if (copies.failed > 0) {
            CobblemonSafari.LOGGER.warn("Safari swarm data: {} Poké Snack-only spawns could not be copied and cannot swarm",
                copies.failed)
        }
        return result
    }

    /**
     * Whether a swarm detail accepts this spot: Cobblemon's own check, or for
     * a snack-only detail the same check with its snack-free conditions.
     *
     * The second branch mirrors `SpawnDetail.isSatisfiedBy` step by step (the
     * influences' pre-filter, any condition, no anticondition, the composite
     * condition, the post-filter), so only the snack requirement differs.
     *
     * @param snackFree the detail's entry in [SpeciesDetails.snackFree], or null
     */
    @JvmStatic
    fun satisfies(detail: SpawnDetail, position: SpawnablePosition, snackFree: List<SpawningCondition<*>>?): Boolean {
        if (snackFree == null) return detail.isSatisfiedBy(position)
        if (!position.preFilter(detail)) return false
        if (snackFree.isNotEmpty() && snackFree.none { it.isSatisfiedBy(position) }) return false
        if (detail.anticonditions.isNotEmpty() && detail.anticonditions.any { it.isSatisfiedBy(position) }) return false
        if (detail.compositeCondition?.satisfiedBy(position) == false) return false
        return position.postFilter(detail)
    }

    /**
     * The detail's conditions, each one requiring a Poké Snack replaced by a
     * copy that does not; null when a copy cannot be made.
     */
    private fun copyWithoutSnack(detail: PokemonSpawnDetail, copies: Copies): List<SpawningCondition<*>>? {
        if (copies.byDetail.containsKey(detail)) return copies.byDetail[detail]
        val conditions = runCatching {
            detail.conditions.map { condition ->
                if (condition.isPokeSnack == true) {
                    val copy = shallowCopy(condition)
                    copy.isPokeSnack = null
                    copy
                } else {
                    condition
                }
            }
        }.onFailure { error ->
            if (copies.failed == 0) {
                CobblemonSafari.LOGGER.warn("Safari swarm data: cannot copy the conditions of a {} spawn", detail.pokemon.species, error)
            }
            copies.failed++
        }.getOrNull()
        copies.byDetail[detail] = conditions
        return conditions
    }

    /**
     * A field-by-field copy of a spawn condition, of its own class.
     *
     * By reflection because Cobblemon offers no complete copy: `copyFrom`
     * only merges the fields presets use (it misses the weather, the markers
     * and the snack flag), and subclasses add fields of their own. Shallow on
     * purpose: lists and ranges are shared with the original, which is fine
     * since conditions are only read once loaded.
     */
    private fun <C : SpawningCondition<*>> shallowCopy(original: C): C {
        @Suppress("UNCHECKED_CAST")
        val type = original.javaClass as Class<C>
        val constructor = type.getDeclaredConstructor()
        constructor.isAccessible = true
        val copy = constructor.newInstance()
        var current: Class<*>? = type
        while (current != null && current != Any::class.java) {
            for (field in current.declaredFields) {
                if (Modifier.isStatic(field.modifiers)) continue
                field.isAccessible = true
                field.set(copy, field.get(original))
            }
            current = current.superclass
        }
        return copy
    }
}
