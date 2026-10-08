package dev.darcosse.common.cobblemonsafari.util;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jetbrains.annotations.Nullable;

/**
 * Finds the surface of a column, the one way every feature of the mod does it.
 *
 * <p>Two variants on purpose. {@link #loaded} never loads a chunk: for
 * per-tick features (spawning) that must not stall the server. {@link #forced}
 * loads or generates the chunk: for rare actions (teleports, building) that
 * must get a real answer.
 */
public final class Surface {

    private Surface() {
    }

    /**
     * First free block above the ground, ignoring leaves so results are not on
     * the canopy. Loads (and if needed generates) the chunk.
     *
     * <p>{@code Level#getHeight} would NOT load it: for a chunk that is not
     * loaded it returns the bottom of the world. That was the cause of the
     * "no safe arrival" bug of 0.1.0.
     */
    public static BlockPos forced(ServerLevel level, int x, int z) {
        LevelChunk chunk = level.getChunk(SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(z));
        // Heightmaps store chunk-local coordinates; +1 turns "top block" into "first free block".
        int y = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x & 15, z & 15) + 1;
        return new BlockPos(x, y, z);
    }

    /**
     * Same as {@link #forced}, but only when the chunk is already loaded.
     *
     * @return null when the column's chunk is not loaded
     */
    @Nullable
    public static BlockPos loaded(ServerLevel level, int x, int z) {
        BlockPos column = new BlockPos(x, level.getMinBuildHeight(), z);
        if (!level.hasChunkAt(column)) {
            return null;
        }
        return forced(level, x, z);
    }
}
