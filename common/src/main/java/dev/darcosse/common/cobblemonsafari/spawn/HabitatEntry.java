package dev.darcosse.common.cobblemonsafari.spawn;

import com.cobblemon.mod.common.api.pokemon.PokemonProperties;
import com.cobblemon.mod.common.pokemon.Species;
import dev.darcosse.common.cobblemonsafari.biome.SafariBiomes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
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
 * @param windows       its natural spawns' time and light conditions per habitat; empty when it has
 *                      no natural spawn (habitats from its types), in which case it may appear at any time
 * @param wildHabitats  those of its habitats where it spawns without a Poké Snack (land or water);
 *                      empty for a species met only through Poké Snacks, or with no natural spawn.
 *                      Since Cobblemon 1.8 many species have Poké Snack-only spawns: the habitats
 *                      above list them too, which is right for the board, whose spawner ignores
 *                      that condition, but not for what players must find in the wild.
 */
public record HabitatEntry(Species species, Set<ResourceKey<Biome>> landHabitats,
                           Set<ResourceKey<Biome>> waterHabitats, Rarity rarity,
                           @Nullable PokemonProperties template, int minLevel, int maxLevel, boolean natural,
                           List<SpawnWindow> windows, Set<ResourceKey<Biome>> wildHabitats) {

    /**
     * Whether a special of this species may appear at this spot now: one of
     * its natural spawns for that biome and kind of spot (land or water)
     * accepts the hour and the light there. A species without spawn data has
     * no window and always may.
     *
     * @param standing the block the Pokémon would stand in
     */
    public boolean canSpawnAt(ServerLevel level, BlockPos standing, Holder<Biome> biome, boolean water) {
        if (windows.isEmpty()) {
            return true;
        }
        for (SpawnWindow window : windows) {
            if (window.covers(biome, water) && window.allows(level, standing)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Every habitat of the species, land and water, for display: the main one
     * ({@link #mainHabitat}) first, then the others in the Safari's canonical
     * order. Empty when it has none.
     */
    public List<ResourceKey<Biome>> allHabitats() {
        List<ResourceKey<Biome>> all = new ArrayList<>();
        ResourceKey<Biome> main = mainHabitat();
        if (main != null) {
            all.add(main);
        }
        for (ResourceKey<Biome> biome : SafariBiomes.SURFACE_HABITATS) {
            if (!biome.equals(main) && (landHabitats.contains(biome) || waterHabitats.contains(biome))) {
                all.add(biome);
            }
        }
        return List.copyOf(all);
    }

    /**
     * The main habitat among those where the species spawns without a Poké
     * Snack, by the same rule as {@link #mainHabitat}; null when there is
     * none. For what a player must find in the wild (the Safari Pokédex).
     */
    @Nullable
    public ResourceKey<Biome> wildMainHabitat() {
        for (ResourceKey<Biome> biome : SafariBiomes.SURFACE_HABITATS) {
            if (landHabitats.contains(biome) && wildHabitats.contains(biome)) {
                return biome;
            }
        }
        for (ResourceKey<Biome> biome : SafariBiomes.SURFACE_HABITATS) {
            if (waterHabitats.contains(biome) && wildHabitats.contains(biome)) {
                return biome;
            }
        }
        return null;
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
