package dev.darcosse.common.cobblemonsafari.util;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jetbrains.annotations.Nullable;

/**
 * The one way the mod asks "can I read blocks here without stalling the
 * server thread?".
 *
 * <p>Why not {@code Level#hasChunkAt} / {@code Level#hasChunksAt}: on a
 * server they answer whether a chunk has a ticket at the full-chunk level
 * ({@code ServerChunkCache#hasChunk}), not whether that chunk has finished
 * loading. Right after a chunk ticket is added (rest house prebuild, guide
 * trip), they answer true while the chunk is still being read from disk or
 * generated. Any block or heightmap read that follows then waits for it on
 * the server thread ({@code getChunk(..., FULL, true)}): a stall of seconds,
 * and with C2ME's chunk system a server-watchdog crash (issue #5).
 * {@code Level#getHeight} has the same guard internally, so it is just as
 * unsafe in that window.
 *
 * <p>{@code ServerChunkCache#getChunkNow} returns the chunk only once it has
 * reached the full status, and never schedules or waits for anything.
 *
 * <p>Server thread only: off it, {@code getChunkNow} does not answer for the
 * live chunk map. Every caller in the mod runs on the server thread.
 */
public final class LoadedChunks {

    private LoadedChunks() {
    }

    /**
     * The fully loaded chunk holding a block column, or null while it is
     * absent, loading or generating. Never loads anything.
     */
    @Nullable
    public static LevelChunk chunkIfReady(ServerLevel level, int blockX, int blockZ) {
        return level.getChunkSource().getChunkNow(
                SectionPos.blockToSectionCoord(blockX), SectionPos.blockToSectionCoord(blockZ));
    }

    /**
     * Whether the column's chunk is fully loaded, so blocks and heightmaps
     * of that column can be read without the server thread waiting.
     */
    public static boolean isReady(ServerLevel level, int blockX, int blockZ) {
        return chunkIfReady(level, blockX, blockZ) != null;
    }

    /**
     * Whether the column of a position is ready; see {@link #isReady(ServerLevel, int, int)}.
     * The height is ignored: a read above or below the world returns void
     * air without touching the chunk.
     */
    public static boolean isReady(ServerLevel level, BlockPos pos) {
        return isReady(level, pos.getX(), pos.getZ());
    }

    /**
     * Whether every chunk overlapping a rectangle of block columns (bounds
     * included) is fully loaded. Cost: one chunk-map lookup per chunk, stops
     * at the first missing one.
     */
    public static boolean areaReady(ServerLevel level, int minX, int minZ, int maxX, int maxZ) {
        int minChunkX = SectionPos.blockToSectionCoord(minX);
        int maxChunkX = SectionPos.blockToSectionCoord(maxX);
        int minChunkZ = SectionPos.blockToSectionCoord(minZ);
        int maxChunkZ = SectionPos.blockToSectionCoord(maxZ);
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                if (level.getChunkSource().getChunkNow(chunkX, chunkZ) == null) {
                    return false;
                }
            }
        }
        return true;
    }
}
