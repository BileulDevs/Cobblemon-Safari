package dev.darcosse.common.cobblemonsafari.spawn;

import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.block.entity.PokeSnackBlockEntity;
import dev.darcosse.common.cobblemonsafari.config.SafariConfigManager;
import dev.darcosse.common.cobblemonsafari.dimension.SafariDimensions;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Gives the Poké Snacks placed in the Safari room to work.
 *
 * <p>A snack has its own spawner, and every Pokémon it brings takes a bite.
 * Before spawning, Cobblemon counts every Pokémon around and gives up when
 * there are {@code pokeSnackPokemonPerChunk} per chunk (2 by default). The
 * Safari keeps the density around its players at Cobblemon's
 * {@code pokemonPerChunk} times {@code spawns.pokemonPerChunkMultiplier}
 * (2 by default too), so the area of a snack was always full and the snack
 * nearly never spawned anything: the Pokémon were around it, none ate it.
 *
 * <p>Two things, both only in the Safari:
 * <ul>
 *   <li>a bubble: no natural spawn within {@link #BUBBLE_RADIUS} blocks of a
 *       snack ({@link SafariSpawnFilter#isAllowedPosition}), so the area
 *       empties as its Pokémon wander off or despawn, and fills again
 *       with the snack's own;</li>
 *   <li>a higher cap for the snack's spawner, {@link #SNACK_CAP_FACTOR} times
 *       the Safari's density, so it bites before the bubble has emptied.</li>
 * </ul>
 *
 * <p>The snacks are found once a second around the players in the Safari,
 * from the block entities of the loaded chunks, and published for the spawn
 * threads as an immutable list.
 */
public final class SnackBubble {

    /**
     * Distance from a snack within which no natural Pokémon spawns, in
     * blocks: twice the snack's own spawn radius (8). Larger empties more of
     * the player's surroundings; smaller leaves natural Pokémon close enough
     * to fill the snack's count.
     */
    private static final int BUBBLE_RADIUS = 16;

    private static final long BUBBLE_RADIUS_SQUARED = (long) BUBBLE_RADIUS * BUBBLE_RADIUS;

    /**
     * Cap of a snack's spawner, as a multiple of the Safari's density. Above
     * 1 the snack spawns even in a full area; higher packs more Pokémon
     * around it.
     */
    private static final float SNACK_CAP_FACTOR = 1.5F;

    /**
     * Blocks around a player searched for snacks: as far as Cobblemon puts
     * spawn zones ({@code maximumSpawningZoneDistanceFromPlayer}, 64 by
     * default) plus the bubble, so a zone's candidate spots near a snack
     * are always known.
     */
    private static final int SEARCH_MARGIN = BUBBLE_RADIUS;

    /** Snacks found around the players in the Safari at the last refresh. */
    private static volatile List<BlockPos> snacks = List.of();

    private SnackBubble() {
    }

    /**
     * Finds the snacks around the players in the Safari and raises their
     * spawners' cap. Server thread, once a second (from
     * {@link SafariSpawnBoost}). Cost: per player in the Safari, the block
     * entities of about 11 by 11 loaded chunks, nearly all empty.
     */
    static void refresh(MinecraftServer server) {
        ServerLevel safari = SafariDimensions.level(server);
        if (safari == null) {
            snacks = List.of();
            return;
        }
        float cap = (float) (Cobblemon.INSTANCE.getConfig().getPokemonPerChunk()
                * SafariConfigManager.get().spawns.pokemonPerChunkMultiplier * SNACK_CAP_FACTOR);
        int reach = (int) Cobblemon.INSTANCE.getConfig().getMaximumSpawningZoneDistanceFromPlayer() + SEARCH_MARGIN;
        int chunkReach = SectionPos.blockToSectionCoord(reach) + 1;
        Set<BlockPos> found = new HashSet<>();
        Set<Long> scanned = new HashSet<>();
        for (ServerPlayer player : safari.players()) {
            int centreX = SectionPos.blockToSectionCoord(player.getBlockX());
            int centreZ = SectionPos.blockToSectionCoord(player.getBlockZ());
            for (int chunkX = centreX - chunkReach; chunkX <= centreX + chunkReach; chunkX++) {
                for (int chunkZ = centreZ - chunkReach; chunkZ <= centreZ + chunkReach; chunkZ++) {
                    // Two players side by side share their chunks: each is read once.
                    if (!scanned.add(((long) chunkX << 32) ^ (chunkZ & 0xFFFFFFFFL))) {
                        continue;
                    }
                    LevelChunk chunk = safari.getChunkSource().getChunkNow(chunkX, chunkZ);
                    if (chunk == null) {
                        continue;
                    }
                    for (BlockEntity entity : chunk.getBlockEntities().values()) {
                        if (entity instanceof PokeSnackBlockEntity snack) {
                            found.add(snack.getBlockPos().immutable());
                            raiseCap(snack, cap);
                        }
                    }
                }
            }
        }
        snacks = List.copyOf(found);
    }

    /**
     * Raises a snack's spawner cap. The spawner is built on first use;
     * asking for it builds it now, which Cobblemon would do at the snack's
     * next random tick anyway.
     */
    private static void raiseCap(PokeSnackBlockEntity snack, float cap) {
        PokeSnackBlockEntity.Spawner spawner = snack.getSpawner();
        if (spawner.getMaxPokemonPerChunk() < cap) {
            spawner.setMaxPokemonPerChunk(cap);
        }
    }

    /**
     * Whether a natural spawn may happen at a spot: not within
     * {@link #BUBBLE_RADIUS} of a snack. Called by Cobblemon for every
     * candidate spot of a player's spawner, possibly off the server thread:
     * reads the published list only, usually empty.
     */
    static boolean allows(BlockPos pos) {
        for (BlockPos snack : snacks) {
            if (snack.distSqr(pos) <= BUBBLE_RADIUS_SQUARED) {
                return false;
            }
        }
        return true;
    }

    /** Forgets the snacks; for a server start, as single player reuses this class. */
    static void reset() {
        snacks = List.of();
    }
}
