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

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Keeps every structure out of the Safari but those it allows.
 *
 * <p>The Safari's own buildings, its rest houses, are placed by code, not
 * generated as structures. Anything else used to come in uninvited: the
 * Safari's biomes are bridged into Cobblemon's habitat tags so its natural
 * spawns reach the park ({@code #cobblemon:is_jungle}, {@code #cobblemon:is_cave}...),
 * and Cobblemon's structures select their biomes through the same tags, so
 * its habitats, ruins and Gimmighoul towers came along, on top of the
 * Safari's own ruins and towers. A biome tag cannot be split between spawns
 * and structures, so the structures are refused here, all of them, unless
 * tagged {@link #ALLOWED_IN_SAFARI}: a list that starts empty and that a
 * datapack can fill, one structure at a time.
 *
 * <p>Two levels. A structure set none of whose structures is allowed is
 * dropped from the Safari when its chunk map is created
 * ({@link #refuses(StructureSet, BiomeSource)}): the generator never even
 * looks for places to put it. A structure not allowed in a set it shares
 * with allowed ones is refused each time the generator tries to start it
 * ({@link #refuses(Holder, BiomeSource)}), and the generator then tries the
 * set's other structures, as it does when one does not fit.
 */
public final class SafariStructureFilter {

    /**
     * The only structures generated in the Safari:
     * {@code data/cobblemon_safari/tags/worldgen/structure/allowed_in_safari.json}.
     */
    public static final TagKey<Structure> ALLOWED_IN_SAFARI =
            TagKey.create(Registries.STRUCTURE, CobblemonSafari.id("allowed_in_safari"));

    /**
     * Whether each biome source met is the Safari's. Weak keys: a level's
     * source goes with it. Synchronized: the chunk generator asks from its
     * worker threads.
     */
    private static final Map<BiomeSource, Boolean> SAFARI_SOURCES = Collections.synchronizedMap(new WeakHashMap<>());

    private SafariStructureFilter() {
    }

    /**
     * Whether a structure set must not be placed by the given biome source:
     * the source is the Safari's and none of the set's structures is tagged
     * {@link #ALLOWED_IN_SAFARI}.
     */
    public static boolean refuses(StructureSet set, BiomeSource biomeSource) {
        if (!isSafari(biomeSource)) {
            return false;
        }
        for (StructureSet.StructureSelectionEntry entry : set.structures()) {
            if (entry.structure().is(ALLOWED_IN_SAFARI)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether one structure must not be started by the given biome source:
     * the source is the Safari's and the structure is not tagged
     * {@link #ALLOWED_IN_SAFARI}. Called by the chunk generator for each
     * start it tries: a tag check, and the Safari check cached per biome
     * source.
     */
    public static boolean refuses(Holder<Structure> structure, BiomeSource biomeSource) {
        return !structure.is(ALLOWED_IN_SAFARI) && isSafari(biomeSource);
    }

    /**
     * Whether a biome source is the Safari's. Cached per source: asked for
     * every structure start tried and every feature placed, on the world
     * generation threads. Shared with {@link SafariFeatureFilter}.
     */
    public static boolean isSafari(BiomeSource biomeSource) {
        Boolean known = SAFARI_SOURCES.get(biomeSource);
        if (known != null) {
            return known;
        }
        boolean safari = lookUp(biomeSource);
        SAFARI_SOURCES.put(biomeSource, safari);
        return safari;
    }

    /**
     * Whether a biome source is the Safari's. Its biomes are the mod's own and
     * appear in no other dimension, so one of them is enough; the source is
     * read rather than the level, which this part of the generator does not
     * know.
     */
    private static boolean lookUp(BiomeSource biomeSource) {
        for (Holder<Biome> biome : biomeSource.possibleBiomes()) {
            if (biome.is(SafariBiomeTags.IS_SAFARI)) {
                return true;
            }
        }
        return false;
    }
}
