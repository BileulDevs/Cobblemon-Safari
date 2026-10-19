package dev.darcosse.common.cobblemonsafari.spawn;

import com.cobblemon.mod.common.api.Priority;
import com.cobblemon.mod.common.api.events.CobblemonEvents;
import com.cobblemon.mod.common.api.events.entity.SpawnEvent;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokemon.Species;
import dev.darcosse.common.cobblemonsafari.config.SafariConfig;
import dev.darcosse.common.cobblemonsafari.config.SafariConfigManager;
import dev.darcosse.common.cobblemonsafari.dimension.SafariDimensions;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;

/**
 * Filters what Cobblemon's own spawner brings into the Safari.
 *
 * <p>Thanks to the biome tag bridge, the native spawner fills each habitat
 * with its natural fauna. This filter adds the Safari's rules on top:
 * Cobblemon's spawning gamerule, forbidden categories and species, the
 * per-rotation cap of legendary-class species ({@link SpecialSpawnCounter}),
 * and a same-species density cap so that one very common Pokémon cannot take
 * over a whole habitat.
 *
 * <p>The mod's own special spawns do not go through Cobblemon's spawner and
 * are not filtered here; {@link FeaturedSpawner} applies the same species
 * rules itself.
 */
public final class NativeSpawnRules {

    /**
     * Radius of the same-species cap (spawns.maxSameSpeciesNearby), in
     * blocks. Wider spreads a herd's cap over more of the habitat; each check
     * is one entity query over the box. Quoted in the comment of
     * spawns.maxSameSpeciesNearby (SafariConfig): keep both in step.
     */
    private static final int SAME_SPECIES_RADIUS = 48;

    private NativeSpawnRules() {
    }

    /** Subscribes to Cobblemon's spawn event. Called once from common init. */
    public static void register() {
        // HIGH rather than HIGHEST: leaves room for a server plugin that wants
        // the final word before anything else reacts to the spawn.
        CobblemonEvents.ENTITY_SPAWN.subscribe(Priority.HIGH, NativeSpawnRules::onSpawn);
    }

    /**
     * Cancels a native spawn breaking a Safari rule.
     *
     * <p>Cost: nothing outside the Safari (one dimension comparison). Inside,
     * one entity query over a box of {@link #SAME_SPECIES_RADIUS}, per spawn.
     * Cobblemon spawns a handful of Pokémon per second at most, so this stays
     * far below a tick budget.
     */
    private static void onSpawn(SpawnEvent<?> event) {
        if (!(event.getEntity() instanceof PokemonEntity pokemonEntity)) {
            return;
        }
        // The spawnable position, not the entity: the entity may not be placed yet.
        ServerLevel level = event.getSpawnablePosition().getWorld();
        if (!SafariDimensions.isSafari(level)) {
            return;
        }
        SafariConfig.Spawns config = SafariConfigManager.get().spawns;
        Species species = pokemonEntity.getPokemon().getSpecies();
        // The gamerule check repeats Cobblemon's own on purpose: spawns started
        // by other means (spawner blocks, other mods' spawners going through
        // Cobblemon's event) are refused in the Safari too.
        if (!config.nativeSpawns || !PokemonSpawningRule.allowed(level.getServer())
                || !SpeciesRules.isAllowed(species)
                || !SpecialSpawnCounter.canSpawn(level.getServer(), species)) {
            event.cancel();
            return;
        }
        if (config.maxSameSpeciesNearby > 0) {
            BlockPos pos = event.getSpawnablePosition().getPosition();
            AABB area = new AABB(pos).inflate(SAME_SPECIES_RADIUS);
            // Wild only: a player's own Pokémon sent out nearby is not part of the habitat.
            int sameSpecies = level.getEntitiesOfClass(PokemonEntity.class, area,
                    other -> other.getPokemon().isWild() && other.getPokemon().getSpecies() == species).size();
            if (sameSpecies >= config.maxSameSpeciesNearby) {
                event.cancel();
                return;
            }
        }
        // Counted when this filter lets the spawn through. If a listener running
        // after this one cancels it, the counter's sweep never finds the entity
        // and gives the slot back a few seconds later.
        SpecialSpawnCounter.recordSpawn(level.getServer(), species, pokemonEntity.getUUID(),
                event.getSpawnablePosition().getPosition());
    }
}
