package dev.darcosse.common.cobblemonsafari.teleport;

import dev.darcosse.common.cobblemonsafari.park.ParkGeometry;
import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import dev.darcosse.common.cobblemonsafari.config.ExitDestination;
import dev.darcosse.common.cobblemonsafari.config.SafariConfigManager;
import dev.darcosse.common.cobblemonsafari.dimension.SafariDimensions;
import dev.darcosse.common.cobblemonsafari.park.ParkBoundary;
import dev.darcosse.common.cobblemonsafari.structure.RestHouses;
import dev.darcosse.common.cobblemonsafari.util.LoadedChunks;
import dev.darcosse.common.cobblemonsafari.util.Surface;
import dev.darcosse.common.cobblemonsafari.session.ReturnPoint;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
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

    /**
     * How far around an underground house's arrival a free spot is searched
     * when the arrival itself is blocked, in blocks. The plaza in front of
     * the steps is wider; further would reach the passage's ramp.
     */
    private static final int CAVERN_ARRIVAL_REACH = 4;

    /** Heights tried at each spot of that search, relative to the arrival's feet, in order. */
    private static final int[] CAVERN_ARRIVAL_STEPS = {0, 1, -1};

    /**
     * How far, in blocks, {@link #findShoreNear} looks for a shore around a
     * swimmer. Larger finds the bank of a wide lake, but makes the swim in
     * the cinematic longer and loads more chunks once.
     */
    private static final int SHORE_SEARCH_RADIUS = 32;

    /**
     * Blocks above and below the water's surface where a shore's feet may
     * be: a bank a little higher than the water, or a beach just at its
     * level. Higher reaches the top of steep banks.
     */
    private static final int SHORE_HEIGHT_REACH = 3;

    /**
     * How far, in blocks, {@link #findShoreToward} swims toward the
     * destination looking for a shore. About the width of the lagoon around
     * the island; further is usually beyond the loaded chunks anyway.
     */
    private static final int SHORE_MARCH_DISTANCE = 192;

    /** How far down {@link #groundNear} looks for the ground under someone in the air, in blocks. */
    private static final int GROUND_DROP = 4;

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
        // Built in the background after the start; a player entering before
        // it stands has it built now, rather than watching it rise around them.
        RestHouses.ensureEntrance(safari);
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
        if (SafariConfigManager.get().hunt.exitDestination == ExitDestination.RETURN_POINT && returnPoint != null) {
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
     * Moves a player onto feet found beforehand (by {@link #findStandingSpotNear}),
     * keeping their gaze. For a move decided earlier than it happens, as the
     * Escape Rope's climb before its flight cinematic.
     */
    public static void sendToFeet(ServerPlayer player, ServerLevel level, BlockPos feet) {
        teleport(player, level, feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5, player.getYRot(), player.getXRot());
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
     * Moves a player to a rest house arrival, facing the door, or to the
     * best place to stand near it when the spot is blocked.
     *
     * @return false when no place to stand was found; the player has not moved
     */
    public static boolean sendToArrival(ServerPlayer player, ServerLevel level, RestHouses.Arrival front) {
        BlockPos feet = arrivalFeet(level, front);
        if (feet == null) {
            return false;
        }
        // Level gaze, as on entry: the player sees the house.
        teleport(player, level, feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5, front.yaw(), 0.0F);
        return true;
    }

    /**
     * Where {@link #sendToArrival} would put the player's feet, or null when
     * there is nowhere to stand. Also where the travel cinematic's bird lands
     * and takes off, so the double and the player stand on the same block.
     * Loads the chunks it reads, like {@link #findStandingSpotNear}.
     */
    @Nullable
    public static BlockPos arrivalFeet(ServerLevel level, RestHouses.Arrival front) {
        int x = front.column().getX();
        int z = front.column().getZ();
        if (front.exact()) {
            return cavernFeet(level, front.column());
        }
        BlockPos feet = surface(level, x, z);
        return isSafe(level, feet) ? feet : findStandingSpotNear(level, x, z);
    }

    /**
     * Where to stand in front of an underground house: its plaza at the
     * given feet, or, when something stands there (a Pokémon's block, a
     * player's), the nearest free spot within {@link #CAVERN_ARRIVAL_REACH}
     * blocks at about the same height. Never the surface search, which
     * would find the ground far above the cave. Null when the whole plaza
     * around is blocked.
     */
    @Nullable
    private static BlockPos cavernFeet(ServerLevel level, BlockPos feet) {
        if (canStandAt(level, feet)) {
            return feet;
        }
        for (int radius = 1; radius <= CAVERN_ARRIVAL_REACH; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
                        continue;
                    }
                    // Same height first, then a step up or down (the ramp in front of the plaza).
                    for (int dy : CAVERN_ARRIVAL_STEPS) {
                        BlockPos candidate = feet.offset(dx, dy, dz);
                        if (canStandAt(level, candidate)) {
                            return candidate;
                        }
                    }
                }
            }
        }
        return null;
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
                    if (ParkBoundary.isOutside(cx, cz) || RestHouses.isNearSurfaceHouse(cx, cz, ARRIVAL_HOUSE_MARGIN)) {
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
     * Ground under someone who is not standing on it (jumping, falling,
     * climbing a ladder): the first feet a player could stand on going down
     * from the given ones, at most {@link #GROUND_DROP} blocks, else the
     * nearest such feet around at about the same height. Null when there is
     * none: the player is high in the air, or in the water. Reads loaded
     * chunks only (the player's own).
     */
    @Nullable
    public static BlockPos groundNear(ServerLevel level, BlockPos feet) {
        for (int drop = 0; drop <= GROUND_DROP; drop++) {
            BlockPos below = feet.below(drop);
            if (!LoadedChunks.isReady(level, below)) {
                return null;
            }
            if (canStandAt(level, below)) {
                return below;
            }
        }
        return cavernFeet(level, feet);
    }

    /**
     * A shore reached by swimming from a spot toward a heading, for water
     * too wide for {@link #findShoreNear} (a lagoon, the open sea): columns
     * are read one block apart along the heading, up to
     * {@link #SHORE_MARCH_DISTANCE} blocks, and the first with dry feet at
     * about the water's level is the shore. Only fully loaded chunks are
     * read: the march stops at the first that is not, rather than make the
     * server thread generate terrain. Null when no shore is met.
     *
     * @param waterY  height of the water's surface block
     * @param heading horizontal unit vector, toward the destination
     */
    @Nullable
    public static BlockPos findShoreToward(ServerLevel level, double x, int waterY, double z, Vec3 heading) {
        for (int step = 1; step <= SHORE_MARCH_DISTANCE; step++) {
            int cx = Mth.floor(x + heading.x * step);
            int cz = Mth.floor(z + heading.z * step);
            if (!LoadedChunks.isReady(level, cx, cz)) {
                return null;
            }
            if (ParkBoundary.isOutside(cx, cz)) {
                continue;
            }
            for (int dy = 1 - SHORE_HEIGHT_REACH; dy <= SHORE_HEIGHT_REACH; dy++) {
                BlockPos feet = new BlockPos(cx, waterY + dy, cz);
                if (canStandAt(level, feet)) {
                    if (RestHouses.isNearSurfaceHouse(cx, cz, ARRIVAL_HOUSE_MARGIN)) {
                        // A house's plaza: the shore goes on beside it.
                        break;
                    }
                    return feet;
                }
            }
        }
        return null;
    }

    /**
     * The nearest shore of the water a player swims in: dry feet, at about
     * the water's level ({@link #SHORE_HEIGHT_REACH} blocks up or down),
     * searched ring by ring up to {@link #SHORE_SEARCH_RADIUS} blocks from
     * the column given. Not the surface of the columns: in a cave the
     * surface lies far above, and the shore of an underground lake is
     * beside it. Outside the park and on surface rest houses is skipped;
     * a spot under cover counts (caves are covered). Null when there is no
     * shore that close. Loads the chunks it reads: a rare action, not for
     * the tick loop.
     *
     * @param waterY height of the water's surface block (the last water block going up)
     */
    @Nullable
    public static BlockPos findShoreNear(ServerLevel level, int x, int waterY, int z) {
        for (int radius = 1; radius <= SHORE_SEARCH_RADIUS; radius++) {
            BlockPos best = null;
            int bestDistance = Integer.MAX_VALUE;
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
                        continue;
                    }
                    int cx = x + dx;
                    int cz = z + dz;
                    if (ParkBoundary.isOutside(cx, cz) || RestHouses.isNearSurfaceHouse(cx, cz, ARRIVAL_HOUSE_MARGIN)) {
                        continue;
                    }
                    // Lowest first: the bank just above the water, not the top of a cliff over it.
                    for (int dy = 1 - SHORE_HEIGHT_REACH; dy <= SHORE_HEIGHT_REACH; dy++) {
                        BlockPos feet = new BlockPos(cx, waterY + dy, cz);
                        if (canStandAt(level, feet)) {
                            int distance = dx * dx + dz * dz;
                            if (distance < bestDistance) {
                                best = feet;
                                bestDistance = distance;
                            }
                            break;
                        }
                    }
                }
            }
            if (best != null) {
                return best;
            }
        }
        return null;
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
            if (ParkBoundary.isOutside(x, z) || RestHouses.isNearSurfaceHouse(x, z, ARRIVAL_HOUSE_MARGIN)) {
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
