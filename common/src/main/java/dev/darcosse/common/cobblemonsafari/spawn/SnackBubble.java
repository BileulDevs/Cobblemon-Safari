package dev.darcosse.common.cobblemonsafari.spawn;

import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.block.entity.PokeSnackBlockEntity;
import dev.darcosse.common.cobblemonsafari.config.SafariConfig;
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
 *   <li>a bubble: no natural spawn within {@code spawns.snackClearRadius}
 *       blocks of a snack ({@link SafariSpawnFilter#isAllowedPosition}), so
 *       the area empties as its Pokémon wander off or despawn, and fills
 *       again with the snack's own;</li>
 *   <li>a higher cap for the snack's spawner,
 *       {@code spawns.snackDensityMultiplier} times the Safari's density, so
 *       it bites before the bubble has emptied.</li>
 * </ul>
 *
 * <p>The snacks are found once a second around the players in the Safari,
 * from the block entities of the loaded chunks, and published for the spawn
 * threads as an immutable list.
 */
public final class SnackBubble {

    /** No snack known: what the spawn threads read before the first refresh and after a reset. */
    private static final Bubbles NONE = new Bubbles(List.of(), 0L);

    /**
     * Snacks found around the players in the Safari at the last refresh,
     * with the squared clearing radius of that refresh: published together,
     * so a spawn thread never pairs a new list with an old radius after a
     * config reload.
     */
    private static volatile Bubbles bubbles = NONE;

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
            bubbles = NONE;
            return;
        }
        SafariConfig.Spawns config = SafariConfigManager.get().spawns;
        int radius = config.snackClearRadius;
        float cap = (float) (Cobblemon.INSTANCE.getConfig().getPokemonPerChunk()
                * config.pokemonPerChunkMultiplier * config.snackDensityMultiplier);
        // As far as Cobblemon puts spawn zones (maximumSpawningZoneDistanceFromPlayer,
        // 64 by default) plus the clearing, so a zone's candidate spots near a snack are known.
        int reach = (int) Cobblemon.INSTANCE.getConfig().getMaximumSpawningZoneDistanceFromPlayer() + radius;
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
        bubbles = new Bubbles(List.copyOf(found), (long) radius * radius);
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
     * {@code spawns.snackClearRadius} of a snack. Called by Cobblemon for
     * every candidate spot of a player's spawner, possibly off the server
     * thread: reads the published snapshot only, usually empty.
     */
    static boolean allows(BlockPos pos) {
        Bubbles current = bubbles;
        // A radius of 0 turns the clearing off: the snacks are still found, for their cap.
        if (current.radiusSquared() == 0L) {
            return true;
        }
        for (BlockPos snack : current.snacks()) {
            if (snack.distSqr(pos) <= current.radiusSquared()) {
                return false;
            }
        }
        return true;
    }

    /** Forgets the snacks; for a server start, as single player reuses this class. */
    static void reset() {
        bubbles = NONE;
    }

    /**
     * The snacks of one refresh and the clearing radius they were found with.
     *
     * @param snacks        positions of the snacks around the players in the Safari
     * @param radiusSquared squared {@code spawns.snackClearRadius} at that refresh; 0 means no clearing
     */
    private record Bubbles(List<BlockPos> snacks, long radiusSquared) {
    }
}
