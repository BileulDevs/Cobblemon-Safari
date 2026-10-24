package dev.darcosse.common.cobblemonsafari.teleport;

import dev.darcosse.common.cobblemonsafari.park.ParkGeometry;
import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import dev.darcosse.common.cobblemonsafari.config.ExitDestination;
import dev.darcosse.common.cobblemonsafari.config.SafariConfigManager;
import dev.darcosse.common.cobblemonsafari.dimension.SafariDimensions;
import dev.darcosse.common.cobblemonsafari.park.ParkBoundary;
import dev.darcosse.common.cobblemonsafari.structure.RestHouses;
import dev.darcosse.common.cobblemonsafari.util.Surface;
import dev.darcosse.common.cobblemonsafari.session.ReturnPoint;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * Moves players into and out of the Safari.
 *
 * <p>Every call here may load or generate chunks synchronously on the server
 * thread. That is acceptable because it only happens on entry and exit, never
 * per tick; do not call these from the tick loop without a rate limit.
 */
public final class SafariTeleporter {

    /**
     * Random arrival candidates tried before giving up. Each one can generate a
     * chunk, so raising this lengthens the worst-case entry stall; lowering it
     * increases the chance of refusing entry when the arrival point is flooded.
     */
    private static final int MAX_ARRIVAL_ATTEMPTS = 32;

    /**
     * Every this many failed attempts the search radius grows by one arrival
     * spread, so a flooded arrival point eventually finds dry land.
     */
    private static final int ATTEMPTS_PER_RING = 4;

    /**
     * Blocks kept clear around a rest house when picking an arrival, so
     * nobody lands on its roof or wedged against its walls.
     */
    private static final int ARRIVAL_HOUSE_MARGIN = 2;

    /**
     * How far, in blocks, {@link #findStandingSpotNear} looks around the
     * column asked for, ring by ring. Covers the width of a big tree's crown
     * or a lake shore. Each ring loads at most a few chunks once; the worst
     * case (nothing found) reads about 625 columns, for a rare action.
     * Smaller fails more often in dense jungle; larger may put the player
     * far from where they stood.
     */
    private static final int STANDING_SEARCH_RADIUS = 12;

    private SafariTeleporter() {
    }

    /**
     * Sends a player into the Safari: in front of the entrance house's door,
     * facing it, or when that spot is not safe (the house not built yet, a
     * player-made obstacle), to a dry spot near the arrival point.
     *
     * @return false when the Safari level is missing or no safe spot was found;
     *         the player has not moved in that case
     */
    public static boolean sendIntoSafari(ServerPlayer player) {
        ServerLevel safari = SafariDimensions.level(player.getServer());
        if (safari == null) {
            CobblemonSafari.LOGGER.error("Safari dimension {} does not exist; check 'dimension' in the config",
                    SafariDimensions.key().location());
            return false;
        }
        RestHouses.Arrival front = RestHouses.entranceArrival();
        if (front != null) {
            BlockPos feet = surface(safari, front.column().getX(), front.column().getZ());
            if (isSafe(safari, feet)) {
                // Level gaze: the player sees the house, not the sky or their feet.
                teleport(player, safari, feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5, front.yaw(), 0.0F);
                return true;
            }
        }
        BlockPos arrival = findArrival(safari);
        if (arrival == null) {
            return false;
        }
        teleport(player, safari, arrival.getX() + 0.5, arrival.getY(), arrival.getZ() + 0.5,
                player.getYRot(), player.getXRot());
        return true;
    }

    /**
     * Sends a player out of the Safari according to the configured destination,
     * falling back to the overworld spawn when the return point is unusable.
     */
    public static void sendOut(ServerPlayer player, @Nullable ReturnPoint returnPoint) {
        MinecraftServer server = player.getServer();
        if (SafariConfigManager.get().exitDestination == ExitDestination.RETURN_POINT && returnPoint != null) {
            ServerLevel target = server.getLevel(returnPoint.dimensionKey());
            // A return point inside the Safari itself would bounce the player straight back in.
            if (target != null && !SafariDimensions.isSafari(target)) {
                teleport(player, target, returnPoint.x(), returnPoint.y(), returnPoint.z(),
                        returnPoint.yaw(), returnPoint.pitch());
                return;
            }
        }
        sendToWorldSpawn(player);
    }

    /** Sends a player to the overworld spawn, on the surface. */
    public static void sendToWorldSpawn(ServerPlayer player) {
        ServerLevel overworld = player.getServer().overworld();
        BlockPos spawn = overworld.getSharedSpawnPos();
        BlockPos surface = surface(overworld, spawn.getX(), spawn.getZ());
        teleport(player, overworld, surface.getX() + 0.5, surface.getY(), surface.getZ() + 0.5,
                player.getYRot(), player.getXRot());
    }

    /**
     * Moves a player to the best place to stand at the surface near a column
     * of the Safari: see {@link #findStandingSpotNear}.
     *
     * @return false when no such place was found; the player has not moved
     */
    public static boolean sendToSurface(ServerPlayer player, ServerLevel level, int x, int z) {
        BlockPos spot = findStandingSpotNear(level, x, z);
        if (spot == null) {
            return false;
        }
        teleport(player, level, spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5,
                player.getYRot(), player.getXRot());
        return true;
    }

    /**
     * Moves a player onto the top of the given column, whatever stands there.
     * Only for a move that must happen even when {@link #sendToSurface} finds
     * nothing better, like bringing someone back inside the park.
     */
    public static void sendToColumnTop(ServerPlayer player, ServerLevel level, int x, int z) {
        BlockPos surface = surface(level, x, z);
        teleport(player, level, surface.getX() + 0.5, surface.getY(), surface.getZ() + 0.5,
                player.getYRot(), player.getXRot());
    }

    /**
     * Moves a player in front of the door of the rest house nearest to them,
     * facing it, or to the best place to stand near that spot when it is
     * blocked. Same placement as the arrival at the entrance house.
     *
     * @return false when no rest house is known or no place to stand was
     *         found; the player has not moved
     */
    public static boolean sendToRestHouse(ServerPlayer player, ServerLevel level) {
        RestHouses.Arrival front = RestHouses.nearestArrival(player.getBlockX(), player.getBlockZ());
        return front != null && sendToArrival(player, level, front);
    }

    /**
     * Moves a player to a rest house arrival, facing the door, or to the
     * best place to stand near it when the spot is blocked.
     *
     * @return false when no place to stand was found; the player has not moved
     */
    public static boolean sendToArrival(ServerPlayer player, ServerLevel level, RestHouses.Arrival front) {
        int x = front.column().getX();
        int z = front.column().getZ();
        BlockPos feet = surface(level, x, z);
        if (!isSafe(level, feet)) {
            feet = findStandingSpotNear(level, x, z);
            if (feet == null) {
                return false;
            }
        }
        // Level gaze, as on entry: the player sees the house.
        teleport(player, level, feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5, front.yaw(), 0.0F);
        return true;
    }

    /**
     * The best place to stand at the surface near a column of the Safari, or
     * null: the nearest one under the open sky, else the nearest one under
     * a cover (foliage, an overhang), searched ring by ring up to
     * {@link #STANDING_SEARCH_RADIUS}.
     *
     * <p>Why not the column itself: its surface ignores leaves (so the
     * player does not land on a canopy), which puts the "ground" of a column
     * under a tree on top of the trunk, inside the crown, where players got
     * stuck. The column may also be a lake or a rest house roof. Columns
     * outside the park or on a rest house are skipped.
     *
     * <p>Loads the chunks it reads: not for the tick loop.
     */
    @Nullable
    public static BlockPos findStandingSpotNear(ServerLevel level, int x, int z) {
        BlockPos covered = null;
        for (int radius = 0; radius <= STANDING_SEARCH_RADIUS; radius++) {
            BlockPos best = null;
            int bestDistance = Integer.MAX_VALUE;
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    // The ring only: the inner columns were tried on smaller radii.
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
                        continue;
                    }
                    int cx = x + dx;
                    int cz = z + dz;
                    if (ParkBoundary.isOutside(cx, cz) || RestHouses.isNear(cx, cz, ARRIVAL_HOUSE_MARGIN)) {
                        continue;
                    }
                    BlockPos feet = surface(level, cx, cz);
                    if (!canStandAt(level, feet)) {
                        continue;
                    }
                    if (!level.canSeeSky(feet)) {
                        if (covered == null) {
                            covered = feet;
                        }
                        continue;
                    }
                    int distance = dx * dx + dz * dz;
                    if (distance < bestDistance) {
                        best = feet;
                        bestDistance = distance;
                    }
                }
            }
            // A square ring holds columns farther than the next ring's
            // nearest ones (its corners); close enough for a teleport, and
            // stopping here keeps the common case to a few columns.
            if (best != null) {
                return best;
            }
        }
        return covered;
    }

    /**
     * Whether a player can stand at these feet and walk away: ground with a
     * collision box that is neither liquid, leaves, a trunk top nor harmful,
     * and two blocks free of collision, liquid and harm above it. Plants
     * without collision (grass, flowers) count as free, unlike {@link #isSafe}.
     */
    private static boolean canStandAt(ServerLevel level, BlockPos feet) {
        if (feet.getY() <= level.getMinBuildHeight() || feet.getY() + 1 >= level.getMaxBuildHeight()) {
            return false;
        }
        BlockPos groundPos = feet.below();
        BlockState ground = level.getBlockState(groundPos);
        if (!ground.getFluidState().isEmpty() || ground.is(BlockTags.LEAVES) || ground.is(BlockTags.LOGS)
                || ground.is(Blocks.MAGMA_BLOCK) || ground.is(Blocks.CACTUS) || ground.is(BlockTags.CAMPFIRES)
                || ground.getCollisionShape(level, groundPos).isEmpty()) {
            return false;
        }
        return isFree(level, feet) && isFree(level, feet.above());
    }

    /** A block a player can be in: no collision, no liquid, nothing that hurts or traps. */
    private static boolean isFree(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.getCollisionShape(level, pos).isEmpty()
                && state.getFluidState().isEmpty()
                && !state.is(BlockTags.FIRE)
                && !state.is(Blocks.SWEET_BERRY_BUSH)
                && !state.is(Blocks.POWDER_SNOW)
                && !state.is(Blocks.COBWEB)
                && !state.is(Blocks.WITHER_ROSE);
    }

    /** Moves a player across dimensions, dismounting first and clearing fall distance. */
    private static void teleport(ServerPlayer player, ServerLevel level, double x, double y, double z,
                                 float yaw, float pitch) {
        // Riding a Pokémon across dimensions is not supported by the vanilla
        // teleport; dismounting first avoids leaving the mount behind in a bad state.
        player.stopRiding();
        player.teleportTo(level, x, y, z, yaw, pitch);
        player.resetFallDistance();
        // The teleport can be refused silently (another mod cancelling the
        // dimension change, a call from the wrong thread). Said in the log,
        // since the player is then left where they were with no explanation.
        if (player.level() != level) {
            CobblemonSafari.LOGGER.warn("Teleport of {} to {} did not happen (thread {}); still in {}",
                    player.getGameProfile().getName(), level.dimension().location(),
                    Thread.currentThread().getName(), player.level().dimension().location());
        }
    }

    /** Searches a dry, open spot around the arrival point, widening the search on failure. */
    @Nullable
    private static BlockPos findArrival(ServerLevel level) {
        RandomSource random = level.getRandom();
        for (int attempt = 0; attempt < MAX_ARRIVAL_ATTEMPTS; attempt++) {
            int spread = Math.max(1, ParkGeometry.ARRIVAL_SPREAD * (1 + attempt / ATTEMPTS_PER_RING));
            int x = ParkGeometry.ARRIVAL_X + random.nextInt(spread * 2 + 1) - spread;
            int z = ParkGeometry.ARRIVAL_Z + random.nextInt(spread * 2 + 1) - spread;
            // Outside the park the boundary would immediately pull the player back;
            // on a rest house the "ground" found would be its roof.
            if (ParkBoundary.isOutside(x, z) || RestHouses.isNear(x, z, ARRIVAL_HOUSE_MARGIN)) {
                continue;
            }
            BlockPos candidate = surface(level, x, z);
            // A chunk touched by a ruin or tower: the "ground" may be a roof
            // or the inside of a wall. Coarse (the whole chunk) but cheap,
            // the chunk being loaded already.
            if (isSafe(level, candidate) && !level.structureManager().hasAnyStructureAt(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /** Surface of a column, loading its chunk; see {@link Surface#forced}. */
    private static BlockPos surface(ServerLevel level, int x, int z) {
        return Surface.forced(level, x, z);
    }

    /** Solid, dry ground with two blocks of air above, inside the build height. */
    private static boolean isSafe(ServerLevel level, BlockPos feet) {
        // An empty column reports the world bottom; standing there means falling into the void.
        if (feet.getY() <= level.getMinBuildHeight()) {
            return false;
        }
        BlockState ground = level.getBlockState(feet.below());
        return ground.getFluidState().isEmpty()
                && !ground.isAir()
                && level.getBlockState(feet).isAir()
                && level.getBlockState(feet.above()).isAir();
    }
}
