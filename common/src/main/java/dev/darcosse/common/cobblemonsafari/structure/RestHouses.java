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
import net.minecraft.world.level.ChunkPos;
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
 * noise without generating a chunk (the searches run in parallel). None is
 * built then: the entrance house is the first one {@link RestHousePrebuilder}
 * loads in the background, right after the start, and it is built in steps
 * like the others; the others are built the first time a player has loaded
 * the chunks around them, or by the prebuilder. Building the entrance during
 * the start generated its chunks on the server thread, several seconds
 * before a new map could be played; building them all, some 150 chunks,
 * about 25 seconds. A player entering before the entrance stands has it
 * built on the spot ({@link #ensureEntrance}): they arrive in front of it.
 *
 * <p>Two more houses stand underground, one per cavern
 * ({@link ParkGeometry#CAVE_REST_HOUSES}), each in a chamber dug beside its
 * cavern's passage ({@link CaveChamber}). They have fixed positions (no
 * site search) and are built like the others once their chamber is loaded.
 * Their anchors follow the surface ones: index
 * {@code REST_HOUSES.size() + i}. Because a house can now lie under the
 * ground, every "is this position at a house" query takes the height too;
 * a surface house built before its height was saved spans every height.
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
     * Height of the Pokémon Center template, ground layer included (layers 0
     * to 10). Must follow the template if its height changes.
     */
    public static final int TEMPLATE_HEIGHT = 11;

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

    /** Height of a house whose ground layer is not known (a surface house built before 1.4.0, or planned). */
    public static final int UNKNOWN_Y = Integer.MIN_VALUE;

    /**
     * Height a surface house spans above its ground layer: the template (11)
     * and the air cleared above it (4), plus a block. A position higher than
     * this above a house's square is not in the house (flying over it, or in
     * a cave under it when measured from below).
     */
    private static final int SURFACE_HOUSE_HEIGHT = 16;

    /** Blocks below a house's ground layer still counted as at the house: its foundation and steps. */
    private static final int BELOW_GROUND = 2;

    /** The chambers of the underground houses, in the order of ParkGeometry.CAVE_REST_HOUSES. */
    private static final List<CaveChamber> CHAMBERS = chambers();

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
     * @param anchor   index of its anchor: in ParkGeometry.REST_HOUSES, then the underground ones after
     *                 them; what stays the same when a build finds the spot wet and moves the house to
     *                 its next candidate
     * @param groundY  height of its ground layer, or {@link #UNKNOWN_Y}
     * @param chamber  its chamber when it lies underground, else null
     */
    public record HouseSite(int x, int z, boolean entrance, boolean built, int anchor, int groundY,
                            @Nullable CaveChamber chamber) {

        /** Whether this house stands underground, in a cavern's chamber. */
        public boolean underground() {
            return chamber != null;
        }

        /** The id of its cavern ("giant_cavern"), or null at the surface. */
        @Nullable
        public String caveId() {
            return chamber == null ? null : chamber.house.id();
        }

        /** Where its door opens: toward the centre of the park, or toward its passage underground. */
        public Direction facing() {
            return chamber != null ? chamber.facing : doorFacing(x, z);
        }

        /** Lowest height counted as at this house; {@link #UNKNOWN_Y} when its height is not known. */
        public int minY() {
            if (chamber != null) {
                return chamber.bottomY() - BELOW_GROUND;
            }
            return groundY == UNKNOWN_Y ? UNKNOWN_Y : groundY - BELOW_GROUND;
        }

        /** Highest height counted as at this house; {@link Integer#MAX_VALUE} when its height is not known. */
        public int maxY() {
            if (chamber != null) {
                return chamber.topY();
            }
            return groundY == UNKNOWN_Y ? Integer.MAX_VALUE : groundY + SURFACE_HOUSE_HEIGHT;
        }

        /** Whether a height lies within this house's span; any height when the span is not known. */
        public boolean spans(int y) {
            return y >= minY() && y <= maxY();
        }

        /**
         * Centre chunk of a ticket loading everything its build and its
         * arrival touch, within {@link #AREA_TICKET_RADIUS}: the house's own
         * chunk, or the middle of its chamber, which reaches far in front
         * of the door to cut the passage.
         */
        public ChunkPos ticketCentre() {
            return chamber != null ? new ChunkPos(new BlockPos(chamber.centreX(), 0, chamber.centreZ()))
                    : new ChunkPos(new BlockPos(x, 0, z));
        }
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
     * Chooses a site for every house that has none. No house is built here:
     * the entrance is loaded and built in the background right after the
     * start ({@link RestHousePrebuilder}), the others by {@link #tick} as
     * players or the prebuilder come near.
     *
     * <p>Cost, on a new map: the site searches (noise reads, about a second,
     * all houses at once on background threads). Called at server start
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
        refreshSites(state);
    }

    /**
     * Makes sure the entrance house stands, for a player about to arrive in
     * front of it: nothing when it is built (the usual case, the prebuilder
     * builds it within seconds of the start), else it is built now, its
     * chunks generated on the spot. A background build of it already under
     * way is finished here rather than started over, so its half-changed
     * terrain is not built on twice.
     *
     * <p>Cost: nothing once built; before that, the stall the server start
     * used to take (about 16 chunks and the build), paid by the first player
     * entering within the first seconds of a new map.
     */
    public static void ensureEntrance(ServerLevel level) {
        SafariWorldState state = SafariWorldState.get(level.getServer());
        ParkGeometry.Column anchor = ParkGeometry.REST_HOUSES.get(0);
        if (state.isHouseBuilt(anchor.x(), anchor.z())) {
            return;
        }
        PendingBuild running = paused.remove(key(anchor));
        if (running == null) {
            for (PendingBuild build : builds) {
                if (build.anchor().equals(anchor)) {
                    running = build;
                    break;
                }
            }
            if (running != null) {
                builds.remove(running);
            }
        }
        if (running != null) {
            RestHouseBuilder.Job job = running.job();
            RestHouseBuilder.loadArea(level, job.centerX, job.centerZ, job.facing);
            if (job.runUntilPlaced(level)) {
                markBuilt(level.getServer(), state, anchor, job);
                // The cut trees are removed over the next ticks, as for any build.
                builds.addFirst(running);
                return;
            }
            // Its spot turned out wet: the start's way picks the driest of the sites.
        }
        buildEntrance(level, state);
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
        // A surface house over an underground one would share its square:
        // both would answer the column queries made with an unknown height.
        for (ParkGeometry.CaveHouse cave : ParkGeometry.CAVE_REST_HOUSES) {
            taken.add(new int[]{cave.x(), cave.z()});
        }
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
     * chunks are generated on the spot: a player arrives in front of it.
     * Only for {@link #ensureEntrance}.
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
        markBuilt(level.getServer(), state, anchor, job);
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
            if (job.isAreaLoaded(safari)) {
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
        for (CaveChamber chamber : CHAMBERS) {
            ParkGeometry.CaveHouse cave = chamber.house;
            ParkGeometry.Column anchor = new ParkGeometry.Column(cave.x(), cave.z());
            if (state.isHouseBuilt(cave.x(), cave.z()) || paused.containsKey(key(anchor))
                    || !RestHouseBuilder.areaLoaded(safari, chamber)) {
                continue;
            }
            RestHouseBuilder.Job job = RestHouseBuilder.Job.startCave(safari, chamber);
            if (job != null) {
                builds.add(new PendingBuild(anchor, job));
            }
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
            case PLACED -> markBuilt(server, state, build.anchor(), job);
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

    /** Records a house as standing, logs it, refreshes {@link #sites()} and tells the clients. */
    private static void markBuilt(MinecraftServer server, SafariWorldState state, ParkGeometry.Column anchor,
                                  RestHouseBuilder.Job job) {
        state.markHouseBuilt(anchor.x(), anchor.z(), job.centerX, job.centerZ, job.groundY());
        CobblemonSafari.LOGGER.info("Built Safari rest house at {}, {} (anchor {}, {})",
                job.centerX, job.centerZ, anchor.x(), anchor.z());
        refreshSites(state);
        // Here, for every way a house gets built: its height is known now, and it may
        // stand at another of its sites than planned, so the clients' music (and the
        // shop's notion of "inside") must follow. The background builds used to skip it.
        SafariNetwork.broadcastRules(server);
    }

    // ---- Queries ----------------------------------------------------------

    /** Rebuilds the in-memory list of houses, built or planned, from the world state. */
    private static void refreshSites(SafariWorldState state) {
        List<HouseSite> list = new ArrayList<>();
        for (int i = 0; i < ParkGeometry.REST_HOUSES.size(); i++) {
            ParkGeometry.Column position = ParkGeometry.REST_HOUSES.get(i);
            int[] site = state.houseSite(position.x(), position.z());
            if (site != null) {
                list.add(new HouseSite(site[0], site[1], i == 0, true, i,
                        state.houseGroundY(position.x(), position.z()), null));
                continue;
            }
            List<int[]> plan = state.housePlan(position.x(), position.z());
            if (!plan.isEmpty()) {
                list.add(new HouseSite(plan.get(0)[0], plan.get(0)[1], i == 0, false, i, UNKNOWN_Y, null));
            }
        }
        for (int i = 0; i < CHAMBERS.size(); i++) {
            ParkGeometry.CaveHouse cave = CHAMBERS.get(i).house;
            list.add(new HouseSite(cave.x(), cave.z(), false, state.isHouseBuilt(cave.x(), cave.z()),
                    ParkGeometry.REST_HOUSES.size() + i, cave.groundY(), CHAMBERS.get(i)));
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
            anchors.add(new HouseSite(anchor.x(), anchor.z(), i == 0, false, i, UNKNOWN_Y, null));
        }
        for (int i = 0; i < CHAMBERS.size(); i++) {
            ParkGeometry.CaveHouse cave = CHAMBERS.get(i).house;
            anchors.add(new HouseSite(cave.x(), cave.z(), false, false, ParkGeometry.REST_HOUSES.size() + i,
                    cave.groundY(), CHAMBERS.get(i)));
        }
        return anchors;
    }

    /**
     * Whether a column lies on a surface rest house (plaza included) or
     * within {@code margin} blocks of it, built or planned: the spot of a
     * house still to build is kept clear too. Underground houses are left
     * out: the callers place things at the surface (arrivals, specials),
     * where a cave house far below is no obstacle. Cheap (a few comparisons
     * per house), safe to call per spawn attempt.
     */
    public static boolean isNearSurfaceHouse(int x, int z, int margin) {
        int reach = FOOTPRINT_REACH + margin;
        for (HouseSite site : sites()) {
            if (!site.underground() && Math.abs(x - site.x()) <= reach && Math.abs(z - site.z()) <= reach) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a position lies on a rest house (plaza included) or within
     * {@code margin} blocks of it, at the surface or underground, height
     * included. Cheap: a few comparisons per house.
     */
    public static boolean isNear(int x, int y, int z, int margin) {
        int reach = FOOTPRINT_REACH + margin;
        for (HouseSite site : sites()) {
            if (Math.abs(x - site.x()) <= reach && Math.abs(z - site.z()) <= reach && site.spans(y)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Where players arrive at a house: on the ground in front of its steps,
     * on the axis of its door.
     *
     * @param column the column to stand on: at y 0 when the caller finds the ground, at the feet'
     *               height when {@code exact}
     * @param yaw    the rotation that faces the doorway
     * @param exact  true for an underground house, whose ground cannot be found from the sky:
     *               {@code column} then holds the feet's height
     */
    public record Arrival(BlockPos column, float yaw, boolean exact) {
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
            // Underground houses are named by their cavern, not by a direction.
            if (!site.underground() && Compass.of(site.x(), site.z()) == direction) {
                return site;
            }
        }
        return null;
    }

    /** The underground house of a cavern ({@link ParkGeometry.CaveHouse#id()}), or null. */
    @Nullable
    public static HouseSite caveSite(String caveId) {
        for (HouseSite site : sites()) {
            if (caveId.equals(site.caveId())) {
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
     *
     * @param underground whether underground houses may be chosen: only for a player in a cave,
     *                    or one standing above a cavern would be sent down into it
     */
    @Nullable
    public static HouseSite nearestSite(int x, int z, boolean underground) {
        HouseSite nearest = null;
        long nearestDistance = Long.MAX_VALUE;
        for (HouseSite site : sites()) {
            if (site.underground() && !underground) {
                continue;
            }
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
     * centre, plus {@code margin}) holds a position, height included, or
     * null.
     */
    @Nullable
    public static HouseSite siteAt(int x, int y, int z, int margin) {
        int reach = HALF_SIZE + margin;
        for (HouseSite site : sites()) {
            if (Math.abs(x - site.x()) <= reach && Math.abs(z - site.z()) <= reach && site.spans(y)) {
                return site;
            }
        }
        return null;
    }

    /** The arrival in front of one house's steps, facing its door. */
    public static Arrival arrivalOf(HouseSite site) {
        Direction facing = site.facing();
        BlockPos column = RestHouseBuilder.arrivalColumn(site.x(), site.z(), facing);
        // Looking back at the house: the opposite of where its door opens.
        float yaw = facing.getOpposite().toYRot();
        if (site.underground()) {
            // On the plaza, one block above the house's ground layer.
            return new Arrival(column.atY(site.groundY() + 1), yaw, true);
        }
        return new Arrival(column, yaw, false);
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

    /** The chambers of the generated underground houses. */
    private static List<CaveChamber> chambers() {
        List<CaveChamber> list = new ArrayList<>();
        for (ParkGeometry.CaveHouse cave : ParkGeometry.CAVE_REST_HOUSES) {
            list.add(RestHouseBuilder.chamberOf(cave));
        }
        return List.copyOf(list);
    }
}
