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
     * <p>{@code Level#getHeight} is no substitute: for a chunk without a
     * ticket it returns the bottom of the world (no arrival spot would ever
     * look safe), and for a chunk that has a ticket but is still loading it
     * waits for it anyway, without saying so (see {@link LoadedChunks}).
     */
    public static BlockPos forced(ServerLevel level, int x, int z) {
        LevelChunk chunk = level.getChunk(SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(z));
        return surfaceOf(chunk, x, z);
    }

    /**
     * Same as {@link #forced}, but only when the chunk has finished loading.
     * Never waits: a chunk with a ticket that is still generating counts as
     * not loaded, since reading it would make the server thread wait for it.
     *
     * @return null when the column's chunk is not fully loaded
     */
    @Nullable
    public static BlockPos loaded(ServerLevel level, int x, int z) {
        LevelChunk chunk = LoadedChunks.chunkIfReady(level, x, z);
        return chunk == null ? null : surfaceOf(chunk, x, z);
    }

    /** First free block above the ground of a column, read from the chunk already in hand. */
    private static BlockPos surfaceOf(LevelChunk chunk, int x, int z) {
        // Heightmaps store chunk-local coordinates; +1 turns "top block" into "first free block".
        int y = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x & 15, z & 15) + 1;
        return new BlockPos(x, y, z);
    }
}
