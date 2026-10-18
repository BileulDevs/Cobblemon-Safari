package dev.darcosse.common.cobblemonsafari.spawn;

import com.cobblemon.mod.common.api.pokemon.PokemonProperties;
import com.cobblemon.mod.common.pokemon.Species;
import dev.darcosse.common.cobblemonsafari.biome.SafariBiomes;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

/**
 * Where and how one species can be met in the Safari, as derived from the
 * installed spawn data.
 *
 * @param species       the Cobblemon species
 * @param landHabitats  Safari biomes where it spawns on dry ground
 * @param waterHabitats Safari biomes where it spawns in the water
 * @param rarity        its most common natural rarity in Safari-like biomes
 * @param template      properties of its most common natural spawn (form, aspects),
 *                      or null when it has no natural spawn
 * @param minLevel      lowest natural level, or 0 when unknown
 * @param maxLevel      highest natural level, or 0 when unknown
 * @param natural       whether the habitats come from real spawn data rather than from its types
 */
public record HabitatEntry(Species species, Set<ResourceKey<Biome>> landHabitats,
                           Set<ResourceKey<Biome>> waterHabitats, Rarity rarity,
                           @Nullable PokemonProperties template, int minLevel, int maxLevel, boolean natural) {

    /** Whether the species lives in the given biome, on land or in the water. */
    public boolean livesIn(ResourceKey<Biome> biome, boolean water) {
        return water ? waterHabitats.contains(biome) : landHabitats.contains(biome);
    }

    /**
     * The habitat shown on the wall screens: the first land habitat in the
     * Safari's canonical order, or the first water one.
     */
    @Nullable
    public ResourceKey<Biome> mainHabitat() {
        for (ResourceKey<Biome> biome : SafariBiomes.SURFACE_HABITATS) {
            if (landHabitats.contains(biome)) {
                return biome;
            }
        }
        for (ResourceKey<Biome> biome : SafariBiomes.SURFACE_HABITATS) {
            if (waterHabitats.contains(biome)) {
                return biome;
            }
        }
        return null;
    }
}
