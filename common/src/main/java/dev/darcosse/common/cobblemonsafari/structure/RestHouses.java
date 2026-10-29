package dev.darcosse.common.cobblemonsafari.structure;

import dev.darcosse.common.cobblemonsafari.park.ParkGeometry;
import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import dev.darcosse.common.cobblemonsafari.dimension.SafariDimensions;
import dev.darcosse.common.cobblemonsafari.network.SafariNetwork;
import dev.darcosse.common.cobblemonsafari.world.SafariWorldState;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * The rest houses of the Safari: where they stand, whether they are built,
 * and building the missing ones. Each anchor of
 * {@link ParkGeometry#REST_HOUSES} gets a house, built at the best spot
 * nearby, which can differ from map to map. Always built: like the rest
 * houses of the original Safari Zones, they are the only place to heal
 * inside the park, and they hold the shop's counter.
 *
 * <p>Each is the Safari Pokémon Center template, placed by code rather than generated as a world structure, so
 * that it stands at a spot chosen for it, fitted to whatever terrain the
 * seed produced (see {@link RestHouseBuilder}).
 *
 * <p>When: at server start, every house gets its site, chosen from the
 * noise without generating a chunk (the searches run in parallel), and only
 * the entrance house is built, since players arrive in front of it. The
 * others are built the first time a player has loaded the chunks around
 * them, one at a time, in small steps over a few ticks. Building them all at
 * start would mean generating some 150 chunks on the server thread, about 25
 * seconds before a new map could be played.
 */
public final class RestHouses {

    /**
     * Distance from a house's centre column to its farthest block, front
     * steps included: the Pokémon Center template is 20 × 19, centred on its
     * cell (10, 9). Used as a square half-side ("inside a house") by the
     * client's music, the clerk search and the shop. Must follow the
     * template if its size changes.
     */
    public static final int HALF_SIZE = 10;

    /**
     * Distance from the centre to the farthest block a house touches: the
     * template and the cleared ring around it. Everything that must keep
     * away from a house (arrivals, specials) measures from this, not from the
     * walls, or a special could pop up on the steps in front of the door.
     * The reshaped terrain beyond is open ground, where anything may stand.
     */
    public static final int FOOTPRINT_REACH = HALF_SIZE + 2;

    /**
     * Radius, in chunks around a house's centre chunk, of a ticket that
     * loads everything its build touches (RestHouseTravel, RestHousePrebuilder).
     * A house and its cleared ring span at most 2 chunks from its centre on
     * each side, plus the build skirt; 3 makes sure every chunk of the build
     * and the arrival is fully loaded. Each step up loads about 2 × (2r + 1)
     * chunks more.
     */
    public static final int AREA_TICKET_RADIUS = 3;

    /**
     * How far from its anchor a house may move to find a good spot, in
     * blocks. The entrance stays close: it must remain next to the arrival
     * point. Larger radii find better spots on rough maps, at the
     * cost of a slower first start of each map.
     */
    private static final int ENTRANCE_SEARCH_RADIUS = 32;
    private static final int SEARCH_RADIUS = 64;

    /**
     * Ticks between two looks for a house whose surroundings players have
     * loaded. Lower builds sooner after a player comes near; each look
     * costs a chunk lookup or so per house still to build.
     */
    private static final int LOOK_INTERVAL_TICKS = 20;

    /**
     * Centres of the houses of the current map, built or planned, entrance
     * first. Read by the per-spawn checks, so kept in memory; refreshed by
     * {@link #prepare} and whenever a house is built or moved. Null until
     * the first server start, when the anchors stand in.
     */
    private static volatile List<HouseSite> sites;

    /** Builds under way, the first one stepped each tick; at most one per house. */
    private static final Deque<PendingBuild> builds = new ArrayDeque<>();

    /**
     * Builds started whose area was unloaded since (the player who brought
     * them left), by anchor key: resumed when the area is loaded again,
     * meanwhile the other houses may start.
     */
    private static final Map<String, PendingBuild> paused = new HashMap<>();

    private static int ticksSinceLook;

    /**
     * Where one house stands or will stand.
     *
     * @param x        centre column
     * @param z        centre column
     * @param entrance whether it is the entrance house (the one nearest the arrival point)
     * @param built    whether it stands already; a planned house is only a spot kept clear
     * @param anchor   index of its anchor in ParkGeometry.REST_HOUSES: what stays the same when a
     *                 build finds the spot wet and moves the house to its next candidate
     */
    public record HouseSite(int x, int z, boolean entrance, boolean built, int anchor) {
    }

    /** A build under way, and the anchor whose house it is. */
    private record PendingBuild(ParkGeometry.Column anchor, RestHouseBuilder.Job job) {
    }

    private RestHouses() {
    }

    // ---- Start ----------------------------------------------------------

    /**
     * Forgets the builds under way. Called at server start, before
     * {@link #prepare}: in single player these fields survive from one world
     * to the next.
     */
    public static void reset() {
        builds.clear();
        paused.clear();
        ticksSinceLook = 0;
        sites = null;
    }

    /**
     * Chooses a site for every house that has none, then builds the entrance
     * house if it is missing. The other houses are built later, by
     * {@link #tick}, as players come near.
     *
     * <p>Cost, on a new map: the site searches (noise reads, about a second,
     * all houses at once on background threads), then the entrance house:
     * its chunks generated (about 16) and its build. Called at server start
     * only, never per tick; {@code /safari reload} uses {@link #refresh}.
     */
    public static void prepare(MinecraftServer server) {
        ServerLevel safari = SafariDimensions.level(server);
        if (safari == null) {
            sites = List.of();
            return;
        }
        SafariWorldState state = SafariWorldState.get(server);
        planMissingSites(safari, state);
        buildEntrance(safari, state);
        refreshSites(state);
    }

    /**
     * What {@link #prepare} does, for a server already running
     * ({@code /safari reload}): plans the sites still missing, but never
     * builds on the spot. A missing entrance house is left to {@link #tick},
     * which builds it in steps once its area is fully loaded, like any other
     * house.
     *
     * <p>Why not {@link #prepare}: building the entrance there generates its
     * chunks and those of every wet candidate on the server thread, several
     * seconds with players online (the same stall as issue #5, from a
     * command). At server start nobody is waiting; here everybody is.
     *
     * <p>Cost: nothing when every house is planned (the normal case, since the
     * start plans them all); otherwise the noise searches of the start.
     */
    public static void refresh(MinecraftServer server) {
        ServerLevel safari = SafariDimensions.level(server);
        if (safari == null) {
            sites = List.of();
            return;
        }
        SafariWorldState state = SafariWorldState.get(server);
        planMissingSites(safari, state);
        refreshSites(state);
        ParkGeometry.Column entrance = ParkGeometry.REST_HOUSES.get(0);
        if (!state.isHouseBuilt(entrance.x(), entrance.z())) {
            // Only after a failed build at start (template missing): players arriving
            // meanwhile land at a random dry spot around the arrival point, as before.
            CobblemonSafari.LOGGER.info("The entrance rest house is not built yet; it will be built in the "
                    + "background once its area is loaded");
        }
    }

    /**
     * Gives a list of candidate sites to every unbuilt house that has none.
     * The searches run in parallel without knowing each other's result; the
     * lists are then checked in order, and a candidate too close to a house
     * already placed or planned is dropped. A house left with no candidate is
     * searched again, alone, knowing the others; its anchor is the last resort.
     */
    private static void planMissingSites(ServerLevel level, SafariWorldState state) {
        List<ParkGeometry.Column> anchors = ParkGeometry.REST_HOUSES;
        List<int[]> taken = new ArrayList<>();
        Map<Integer, CompletableFuture<List<int[]>>> searches = new HashMap<>();
        for (int i = 0; i < anchors.size(); i++) {
            ParkGeometry.Column anchor = anchors.get(i);
            int[] built = state.houseSite(anchor.x(), anchor.z());
            List<int[]> plan = state.housePlan(anchor.x(), anchor.z());
            if (built != null) {
                taken.add(built);
            } else if (!plan.isEmpty()) {
                taken.add(plan.get(0));
            } else {
                int radius = i == 0 ? ENTRANCE_SEARCH_RADIUS : SEARCH_RADIUS;
                List<int[]> known = List.copyOf(taken);
                searches.put(i, CompletableFuture.supplyAsync(
                        () -> RestHouseSites.candidates(level, anchor.x(), anchor.z(), radius, known),
                        Util.backgroundExecutor()));
            }
        }
        for (int i = 0; i < anchors.size(); i++) {
            CompletableFuture<List<int[]>> search = searches.get(i);
            if (search == null) {
                continue;
            }
            ParkGeometry.Column anchor = anchors.get(i);
            int radius = i == 0 ? ENTRANCE_SEARCH_RADIUS : SEARCH_RADIUS;
            List<int[]> candidates = new ArrayList<>();
            try {
                candidates.addAll(search.join());
            } catch (RuntimeException e) {
                CobblemonSafari.LOGGER.error("Site search for the rest house near {}, {} failed; searching again",
                        anchor.x(), anchor.z(), e);
            }
            candidates.removeIf(site -> RestHouseSites.tooClose(site[0], site[1], taken));
            if (candidates.isEmpty()) {
                candidates.addAll(RestHouseSites.candidates(level, anchor.x(), anchor.z(), radius, taken));
            }
            if (candidates.isEmpty()) {
                CobblemonSafari.LOGGER.warn("No spot found for the rest house near {}, {}; it will stand on that position",
                        anchor.x(), anchor.z());
                candidates.add(new int[]{anchor.x(), anchor.z()});
            }
            state.setHousePlan(anchor.x(), anchor.z(), candidates);
            taken.add(candidates.get(0));
        }
    }

    /**
     * Builds the entrance house now, if missing, at the first of its sites
     * with no water on the real terrain (the driest if all are wet). Its
     * chunks are generated on the spot: players arrive in front of it.
     */
    private static void buildEntrance(ServerLevel level, SafariWorldState state) {
        ParkGeometry.Column anchor = ParkGeometry.REST_HOUSES.get(0);
        if (state.isHouseBuilt(anchor.x(), anchor.z())) {
            return;
        }
        int[] site = pickDrySite(level, anchor, state.housePlan(anchor.x(), anchor.z()));
        Direction facing = doorFacing(site[0], site[1]);
        RestHouseBuilder.loadArea(level, site[0], site[1], facing);
        RestHouseBuilder.Job job = RestHouseBuilder.Job.start(level, site[0], site[1], facing, true);
        if (job == null || !job.runUntilPlaced(level)) {
            // Template missing: not marked built, so the next start tries again.
            return;
        }
        markBuilt(state, anchor, job);
        // The cut trees are removed over the next ticks, with the chunks loaded by then.
        builds.add(new PendingBuild(anchor, job));
    }

    /**
     * The first site with no water on the real terrain under the house and
     * around it. The search reads the noise, which can miss a pond between
     * its samples; the real blocks cannot. When every site is wet, the
     * driest one, logged.
     *
     * <p>Cost: each site checked generates the few chunks under it, at most
     * {@link RestHouseSites#MAX_CANDIDATES}, once per map.
     */
    private static int[] pickDrySite(ServerLevel level, ParkGeometry.Column anchor, List<int[]> candidates) {
        int[] driest = null;
        int driestWet = Integer.MAX_VALUE;
        for (int[] candidate : candidates) {
            int wet = RestHouseBuilder.wetColumns(level, candidate[0], candidate[1],
                    doorFacing(candidate[0], candidate[1]));
            if (wet == 0) {
                return candidate;
            }
            CobblemonSafari.LOGGER.info("Rest house spot {}, {} has water on {} columns; trying the next one",
                    candidate[0], candidate[1], wet);
            if (wet < driestWet) {
                driestWet = wet;
                driest = candidate;
            }
        }
        if (driest != null) {
            CobblemonSafari.LOGGER.warn("Every spot found for the rest house near {}, {} has water; using the driest",
                    anchor.x(), anchor.z());
            return driest;
        }
        return new int[]{anchor.x(), anchor.z()};
    }

    // ---- Ticking ----------------------------------------------------------

    /**
     * Advances the build under way, or looks for a planned house whose
     * surroundings are loaded and starts building it. Called each server
     * tick.
     *
     * <p>Cost: with no build under way, once every {@link #LOOK_INTERVAL_TICKS},
     * a loaded-chunk lookup per house still to build (about 16 each). During a
     * build, one step of it per tick (see {@link RestHouseBuilder.Job#step}):
     * a few ticks of up to a few thousand block changes, once per house and map.
     */
    public static void tick(MinecraftServer server) {
        ServerLevel safari = SafariDimensions.level(server);
        if (safari == null) {
            return;
        }
        if (!builds.isEmpty()) {
            stepBuild(server, safari);
            return;
        }
        if (++ticksSinceLook < LOOK_INTERVAL_TICKS) {
            return;
        }
        ticksSinceLook = 0;
        // Paused builds first: they already changed the terrain.
        for (Map.Entry<String, PendingBuild> entry : paused.entrySet()) {
            RestHouseBuilder.Job job = entry.getValue().job();
            if (RestHouseBuilder.areaLoaded(safari, job.centerX, job.centerZ, job.facing)) {
                builds.add(paused.remove(entry.getKey()));
                return;
            }
        }
        SafariWorldState state = SafariWorldState.get(server);
        List<ParkGeometry.Column> anchors = ParkGeometry.REST_HOUSES;
        for (ParkGeometry.Column anchor : anchors) {
            if (state.isHouseBuilt(anchor.x(), anchor.z()) || paused.containsKey(key(anchor))) {
                continue;
            }
            List<int[]> plan = state.housePlan(anchor.x(), anchor.z());
            if (plan.isEmpty()) {
                continue;
            }
            int[] site = plan.get(0);
            Direction facing = doorFacing(site[0], site[1]);
            if (!RestHouseBuilder.areaLoaded(safari, site[0], site[1], facing)) {
                continue;
            }
            // The last site left is built on even if wet: a house is never given up.
            RestHouseBuilder.Job job = RestHouseBuilder.Job.start(safari, site[0], site[1], facing, plan.size() == 1);
            if (job != null) {
                builds.add(new PendingBuild(anchor, job));
            }
            // One build at a time: two at once would double the cost of the ticks they share.
            return;
        }
    }

    /**
     * One step of the first build. A build waiting for its chunks is
     * paused, so a house whose player left does not hold up the others.
     */
    private static void stepBuild(MinecraftServer server, ServerLevel level) {
        PendingBuild build = builds.peekFirst();
        RestHouseBuilder.Job job = build.job();
        SafariWorldState state = SafariWorldState.get(server);
        switch (job.step(level)) {
            case RUNNING -> {
            }
            case WAITING -> paused.put(key(build.anchor()), builds.pollFirst());
            case PLACED -> markBuilt(state, build.anchor(), job);
            case DONE -> builds.pollFirst();
            case WET -> {
                builds.pollFirst();
                List<int[]> plan = state.housePlan(build.anchor().x(), build.anchor().z());
                if (!plan.isEmpty()) {
                    plan.remove(0);
                    state.setHousePlan(build.anchor().x(), build.anchor().z(), plan);
                    // The house moved: clients play its music somewhere else now.
                    refreshSites(state);
                    SafariNetwork.broadcastRules(server);
                }
            }
        }
    }

    /**
     * Whether a build is under way, placing blocks or removing the cut
     * trees: a ticket keeping its house loaded should stay until it ends.
     */
    public static boolean isBuilding() {
        return !builds.isEmpty();
    }

    /** Key of an anchor in {@link #paused}. */
    private static String key(ParkGeometry.Column anchor) {
        return anchor.x() + "," + anchor.z();
    }

    /** Records a house as standing, logs it and refreshes {@link #sites()}. */
    private static void markBuilt(SafariWorldState state, ParkGeometry.Column anchor, RestHouseBuilder.Job job) {
        state.markHouseBuilt(anchor.x(), anchor.z(), job.centerX, job.centerZ);
        CobblemonSafari.LOGGER.info("Built Safari rest house at {}, {} (anchor {}, {})",
                job.centerX, job.centerZ, anchor.x(), anchor.z());
        refreshSites(state);
    }

    // ---- Queries ----------------------------------------------------------

    /** Rebuilds the in-memory list of houses, built or planned, from the world state. */
    private static void refreshSites(SafariWorldState state) {
        List<HouseSite> list = new ArrayList<>();
        for (int i = 0; i < ParkGeometry.REST_HOUSES.size(); i++) {
            ParkGeometry.Column position = ParkGeometry.REST_HOUSES.get(i);
            int[] site = state.houseSite(position.x(), position.z());
            if (site != null) {
                list.add(new HouseSite(site[0], site[1], i == 0, true, i));
                continue;
            }
            List<int[]> plan = state.housePlan(position.x(), position.z());
            if (!plan.isEmpty()) {
                list.add(new HouseSite(plan.get(0)[0], plan.get(0)[1], i == 0, false, i));
            }
        }
        sites = List.copyOf(list);
    }

    /**
     * The houses of the current map, built or planned, where they stand or
     * will stand. Before the first start of this server run, the anchors.
     */
    public static List<HouseSite> sites() {
        List<HouseSite> current = sites;
        if (current != null) {
            return current;
        }
        List<HouseSite> anchors = new ArrayList<>();
        for (int i = 0; i < ParkGeometry.REST_HOUSES.size(); i++) {
            ParkGeometry.Column anchor = ParkGeometry.REST_HOUSES.get(i);
            anchors.add(new HouseSite(anchor.x(), anchor.z(), i == 0, false, i));
        }
        return anchors;
    }

    /**
     * Whether a column lies on a rest house (plaza included) or within
     * {@code margin} blocks of it, built or planned: the spot of a house
     * still to build is kept clear too. Cheap (a few comparisons per house),
     * safe to call per spawn attempt.
     */
    public static boolean isNear(int x, int z, int margin) {
        int reach = FOOTPRINT_REACH + margin;
        for (HouseSite site : sites()) {
            if (Math.abs(x - site.x()) <= reach && Math.abs(z - site.z()) <= reach) {
                return true;
            }
        }
        return false;
    }

    /**
     * Where players arrive in the Safari: on the ground in front of the
     * entrance house's steps, on the axis of its door.
     *
     * @param column the column to stand on (y 0: the caller finds the ground)
     * @param yaw    the rotation that faces the doorway
     */
    public record Arrival(BlockPos column, float yaw) {
    }

    /**
     * The arrival in front of the entrance house, or null when no entrance
     * house is known (the Safari level is missing). Before the first start
     * of this server run it uses the entrance anchor, where the house will
     * stand only if the anchor was a good spot; callers check the ground.
     */
    @Nullable
    public static Arrival entranceArrival() {
        for (HouseSite site : sites()) {
            if (site.entrance()) {
                return arrivalOf(site);
            }
        }
        return null;
    }

    /**
     * The rest house, built or planned, lying in a compass sector of the
     * park, or null when there is none.
     */
    @Nullable
    public static HouseSite siteIn(Compass direction) {
        for (HouseSite site : sites()) {
            if (Compass.of(site.x(), site.z()) == direction) {
                return site;
            }
        }
        return null;
    }

    /** The house of an anchor as it stands now (built, or planned where it will be), or null. */
    @Nullable
    public static HouseSite siteOf(int anchor) {
        for (HouseSite site : sites()) {
            if (site.anchor() == anchor) {
                return site;
            }
        }
        return null;
    }

    /**
     * The rest house nearest to a column, built or planned, or null when
     * none is known. A planned one is built when its area is loaded, which
     * a trip there does (RestHouseTravel).
     */
    @Nullable
    public static HouseSite nearestSite(int x, int z) {
        HouseSite nearest = null;
        long nearestDistance = Long.MAX_VALUE;
        for (HouseSite site : sites()) {
            long dx = site.x() - x;
            long dz = site.z() - z;
            long distance = dx * dx + dz * dz;
            if (distance < nearestDistance) {
                nearest = site;
                nearestDistance = distance;
            }
        }
        return nearest;
    }

    /**
     * The house whose square (walls and plaza, {@link #HALF_SIZE} around its
     * centre, plus {@code margin}) holds a column, or null.
     */
    @Nullable
    public static HouseSite siteAt(int x, int z, int margin) {
        int reach = HALF_SIZE + margin;
        for (HouseSite site : sites()) {
            if (Math.abs(x - site.x()) <= reach && Math.abs(z - site.z()) <= reach) {
                return site;
            }
        }
        return null;
    }

    /** The arrival in front of one house's steps, facing its door. */
    public static Arrival arrivalOf(HouseSite site) {
        Direction facing = doorFacing(site.x(), site.z());
        // Looking back at the house: the opposite of where its door opens.
        return new Arrival(RestHouseBuilder.arrivalColumn(site.x(), site.z(), facing),
                facing.getOpposite().toYRot());
    }

    /**
     * The door faces the centre of the park, so every house opens toward the
     * heart of the island and the entrance opens toward the arrival point.
     */
    static Direction doorFacing(int x, int z) {
        int dx = ParkGeometry.CENTER_X - x;
        int dz = ParkGeometry.CENTER_Z - z;
        if (Math.abs(dx) > Math.abs(dz)) {
            return dx > 0 ? Direction.EAST : Direction.WEST;
        }
        return dz >= 0 ? Direction.SOUTH : Direction.NORTH;
    }
}
