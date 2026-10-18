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
import net.minecraft.util.RandomSource;
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

    private SafariTeleporter() {
    }

    /**
     * Sends a player into the Safari: in front of the entrance house's door,
     * facing it, or when that spot is not safe (the house not built yet, a
     * player-made obstacle), to a dry spot near the arrival point as before.
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

    /** Moves a player to the given column inside a level, on its surface. */
    public static void sendToSurface(ServerPlayer player, ServerLevel level, int x, int z) {
        BlockPos surface = surface(level, x, z);
        teleport(player, level, surface.getX() + 0.5, surface.getY(), surface.getZ() + 0.5,
                player.getYRot(), player.getXRot());
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
