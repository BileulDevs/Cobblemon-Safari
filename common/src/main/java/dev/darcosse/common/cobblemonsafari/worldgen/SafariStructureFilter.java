package dev.darcosse.common.cobblemonsafari.worldgen;

import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import dev.darcosse.common.cobblemonsafari.biome.SafariBiomeTags;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;

/**
 * Keeps other mods' structures out of the Safari when the island does not
 * want them.
 *
 * <p>The Safari's biomes are bridged into Cobblemon's habitat tags so its
 * natural spawns reach the park ({@code #cobblemon:is_cave},
 * {@code #cobblemon:is_lush}...). Cobblemon's structures select their
 * biomes through the same tags, so the bridge also invites them in: its cave
 * arches, spaced for a whole overworld, then filled the caverns with the
 * same ruin over and over. A biome tag cannot be split between spawns and
 * structures, so the structures are refused here instead, by a structure
 * tag ({@link #NOT_IN_SAFARI}) a datapack can extend.
 *
 * <p>The filter works on whole structure sets, the unit the chunk generator
 * places: a set is dropped from the Safari when every structure in it is
 * tagged. A set mixing tagged and untagged structures is kept whole, since
 * dropping it would also remove the untagged ones.
 *
 * <p>Runs once per structure set when a level's chunk map is created, never
 * per chunk: refusing the set means the generator never even looks for
 * places to put it.
 */
public final class SafariStructureFilter {

    /** Structures never generated in the Safari: {@code data/cobblemon_safari/tags/worldgen/structure/not_in_safari.json}. */
    public static final TagKey<Structure> NOT_IN_SAFARI =
            TagKey.create(Registries.STRUCTURE, CobblemonSafari.id("not_in_safari"));

    private SafariStructureFilter() {
    }

    /**
     * Whether a structure set must not be placed by the given biome source:
     * the source is the Safari's and every structure of the set is tagged
     * {@link #NOT_IN_SAFARI}.
     */
    public static boolean refuses(StructureSet set, BiomeSource biomeSource) {
        if (set.structures().isEmpty() || !isSafari(biomeSource)) {
            return false;
        }
        for (StructureSet.StructureSelectionEntry entry : set.structures()) {
            if (!entry.structure().is(NOT_IN_SAFARI)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether a biome source is the Safari's. Its biomes are the mod's own and
     * appear in no other dimension, so one of them is enough; the source is
     * read rather than the level, which this part of the generator does not
     * know.
     */
    private static boolean isSafari(BiomeSource biomeSource) {
        for (Holder<Biome> biome : biomeSource.possibleBiomes()) {
            if (biome.is(SafariBiomeTags.IS_SAFARI)) {
                return true;
            }
        }
        return false;
    }
}
