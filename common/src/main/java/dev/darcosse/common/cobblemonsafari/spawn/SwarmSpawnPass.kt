package dev.darcosse.common.cobblemonsafari.spawn

import com.cobblemon.mod.common.Cobblemon
import com.cobblemon.mod.common.api.spawning.SpawnCause
import com.cobblemon.mod.common.api.spawning.condition.SpawningCondition
import com.cobblemon.mod.common.api.spawning.detail.SpawnDetail
import com.cobblemon.mod.common.api.spawning.position.SpawnablePosition
import com.cobblemon.mod.common.api.spawning.position.calculators.SpawnablePositionCalculator
import com.cobblemon.mod.common.api.spawning.selection.SpawnSelectionData
import com.cobblemon.mod.common.api.spawning.spawner.PlayerSpawner
import com.cobblemon.mod.common.api.spawning.spawner.SpawningZoneInput
import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.RandomSource
import net.minecraft.world.level.biome.Biome

/**
 * One spawn of the swarming species through Cobblemon's own spawning
 * machinery, for [SwarmSpawner].
 *
 * It scans a small zone exactly as Cobblemon's spawner does (same zone
 * generator, same position resolver, same calculators, the player's spawner
 * and its influences), keeps the positions in the swarm's habitat that one of
 * the swarm's details accepts (`isSatisfiedBy`: every condition of the
 * detail, plus the influences' filters, so the Safari's exclusions, caps and
 * crowding apply; for a Poké Snack-only detail, the same check without the
 * snack requirement, see [SwarmSpawnData.satisfies]), and completes a spawn
 * action from that detail. Completing
 * it fires Cobblemon's spawn event, so [NativeSpawnRules] judges and counts
 * the Pokémon like any natural spawn.
 *
 * What it skips is only Cobblemon's random pick: the bucket roll and the
 * weighted draw among every species of the spot, which is what made a swarm
 * depend on how many other Pokémon live in its habitat.
 *
 * Written in Kotlin: the calls below use Kotlin default arguments, companion
 * properties and star-projected generics that are awkward from Java.
 */
object SwarmSpawnPass {

    /**
     * Width and length of the scanned zone, in blocks. Every block of the zone
     * is tested by Cobblemon's calculators: 8 × 16 × 8 is 1024 blocks, a
     * fraction of one of Cobblemon's own passes. Wider finds a valid spot more
     * often in broken terrain, at a proportional cost.
     */
    private const val ZONE_WIDTH = 8

    /**
     * Height of the scanned zone, in blocks, centred by Cobblemon on the
     * first open block near the requested centre. Taller reaches deeper
     * water for submerged species, at a proportional cost.
     */
    private const val ZONE_HEIGHT = 16

    /**
     * Tries to spawn one Pokémon of the swarm in a zone centred on `center`.
     *
     * @param spawner the player's Cobblemon spawner, whose influences the positions carry
     * @param details   the swarm's details; only these may spawn
     * @param snackFree the snack-free conditions of the swarm's Poké Snack-only details
     *                  ([SwarmSpawnData.satisfies]); empty for an ordinary swarm
     * @param biome     the swarm's habitat; positions elsewhere in the zone are ignored
     * @return true if a Pokémon was spawned (Cobblemon's event let it through)
     */
    @JvmStatic
    fun trySpawn(level: ServerLevel, player: ServerPlayer, spawner: PlayerSpawner, center: BlockPos,
                 details: List<SpawnDetail>, snackFree: Map<SpawnDetail, List<SpawningCondition<*>>>,
                 biome: ResourceKey<Biome>, random: RandomSource): Boolean {
        val cause = SpawnCause(spawner, player)
        val input = SpawningZoneInput(
            cause, level,
            center.x - ZONE_WIDTH / 2, center.y - ZONE_HEIGHT / 2, center.z - ZONE_WIDTH / 2,
            ZONE_WIDTH, ZONE_HEIGHT, ZONE_WIDTH
        )
        // Null when the chunks are not loaded or no open block is near the centre:
        // Cobblemon gives up on such a zone too, rather than loading chunks.
        val area = spawner.constrainArea(input) ?: return false
        val zone = Cobblemon.spawningZoneGenerator.generate(spawner, area)
        val positions = Cobblemon.areaSpawnablePositionResolver.resolve(
            spawner, SpawnablePositionCalculator.prioritizedAreaCalculators, zone
        )

        val options = ArrayList<Pair<SpawnDetail, SpawnablePosition>>()
        val weights = ArrayList<Float>()
        var total = 0F
        for (position in positions) {
            // The zone may straddle the habitat's edge: the swarm stays in its habitat.
            if (!position.biomeHolder.`is`(biome)) continue
            for (detail in details) {
                if (!SwarmSpawnData.satisfies(detail, position, snackFree[detail])) continue
                // The position's weight, as Cobblemon would use it (influences
                // included), so a detail favoured at that spot stays favoured.
                val weight = position.getWeight(detail)
                if (weight <= 0F) continue
                options.add(detail to position)
                weights.add(weight)
                total += weight
            }
        }
        if (options.isEmpty()) return false

        var roll = random.nextFloat() * total
        var chosen = options.last()
        for (i in options.indices) {
            roll -= weights[i]
            if (roll < 0F) {
                chosen = options[i]
                break
            }
        }
        val (detail, position) = chosen
        // EMPTY: one spawn, nothing to prune from a selection. The detail's own
        // bucket is passed on, so the spawned Pokémon reads as what it is.
        val action = detail.choose(position, detail.bucket, SpawnSelectionData.EMPTY)
        return action.complete() != null
    }
}
