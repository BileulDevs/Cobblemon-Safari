package dev.darcosse.common.cobblemonsafari.worldgen;

import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.structure.Structure;

import java.util.Map;

/**
 * Keeps lava lakes out of the mod's structures: the Safari Gate in the
 * overworld and the Safari's ruins and towers.
 *
 * <p>Lakes are features of the "lakes" step, placed after the structures
 * have chosen their spot but before the buildings are pasted, and nothing in
 * vanilla's lake feature looks at structures any more (it used to skip
 * villages). The height check of {@link DryJigsawStructure} reads the noise,
 * where a lake does not exist yet, so a gate could be built right over a
 * lava pool: the lava stayed in the floor wherever the template leaves its
 * own blocks out, and around it.
 *
 * <p>A lake is refused when one of the chunks it may cover holds a
 * reference to a structure tagged {@link #KEEPS_LAKES_AWAY}. References are
 * written to every chunk a structure's box touches, before any feature runs,
 * so the answer is known when the lake is placed.
 */
public final class LakeGuard {

    /** Structures that no lake may cut into: {@code data/cobblemon_safari/tags/worldgen/structure/keeps_lakes_away.json}. */
    public static final TagKey<Structure> KEEPS_LAKES_AWAY =
            TagKey.create(Registries.STRUCTURE, CobblemonSafari.id("keeps_lakes_away"));

    /**
     * Blocks a lake reaches east and south of its origin. Vanilla's lake
     * feature fills a 16 by 16 box starting at its origin (offsets 0 to 15);
     * a smaller value would miss the chunks on its far side.
     */
    private static final int LAKE_SPAN = 15;

    private LakeGuard() {
    }

    /**
     * Whether a lake starting at this origin must not be placed. Cost, for
     * each lake tried (a few per hundred chunks): the structure references
     * of up to four chunks, all in memory, and a tag check per structure
     * referenced. World generation only.
     */
    public static boolean refuses(WorldGenLevel level, BlockPos origin) {
        Registry<Structure> structures = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        int minChunkX = SectionPos.blockToSectionCoord(origin.getX());
        int minChunkZ = SectionPos.blockToSectionCoord(origin.getZ());
        int maxChunkX = SectionPos.blockToSectionCoord(origin.getX() + LAKE_SPAN);
        int maxChunkZ = SectionPos.blockToSectionCoord(origin.getZ() + LAKE_SPAN);
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                // Inside the generation region: the lake itself writes to these chunks.
                ChunkAccess chunk = level.getChunk(chunkX, chunkZ);
                for (Map.Entry<Structure, ?> reference : chunk.getAllReferences().entrySet()) {
                    if (structures.wrapAsHolder(reference.getKey()).is(KEEPS_LAKES_AWAY)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
