package dev.darcosse.common.cobblemonsafari.structure;

import dev.darcosse.common.cobblemonsafari.park.ParkGeometry;
import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import dev.darcosse.common.cobblemonsafari.dimension.SafariDimensions;
import dev.darcosse.common.cobblemonsafari.world.SafariWorldState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The rest houses of the Safari: where they stand, whether they are built,
 * and building the missing ones. Each anchor of
 * {@link ParkGeometry#REST_HOUSES} gets a house, built at the best spot
 * nearby, which can differ from map to map. Always built: they are the only
 * place to heal and the shop's counter inside the park.
 *
 * <p>Like the rest houses of the original Safari Zones, they are the only
 * place to heal inside the park. Each is the Safari Pokémon Center
 * template, placed by code at server start rather than generated as a
 * world structure, so that it stands at a spot chosen for it, levelled on a
 * foundation fitted to whatever terrain the seed produced (see
 * {@link RestHouseBuilder}).
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
     */
    public static final int FOOTPRINT_REACH = HALF_SIZE + 2;

    /**
     * How far from its anchor a house may move to find a good
     * spot, in blocks. The entrance stays close: it must remain next to the
     * arrival point. Larger radii find better spots on rough maps, at the
     * cost of a slower first start of each map.
     */
    private static final int ENTRANCE_SEARCH_RADIUS = 32;
    private static final int SEARCH_RADIUS = 64;

    /**
     * Centres of the houses built on the current map, entrance first. Read
     * by the per-spawn checks, so kept in memory; refreshed by
     * {@link #ensureBuilt}. Null until the first server start, when the
     * anchors stand in.
     */
    private static volatile List<HouseSite> sites;

    /**
     * Where one built house stands.
     *
     * @param x        centre column
     * @param z        centre column
     * @param entrance whether it is the entrance house (the one nearest the arrival point)
     */
    public record HouseSite(int x, int z, boolean entrance) {
    }

    private RestHouses() {
    }

    /**
     * Builds every rest house that does not exist yet, each at the best
     * spot near its anchor ({@link RestHouseSites}), then refreshes
     * {@link #sites()}.
     *
     * <p>Cost: for each missing house, a site search (column heights read
     * from the noise, about a second), then loading or generating the chunks
     * under it and a few thousand block changes, once per map. Called at
     * server start and after /safari reload, never per tick.
     */
    public static void ensureBuilt(MinecraftServer server) {
        ServerLevel safari = SafariDimensions.level(server);
        if (safari == null) {
            sites = List.of();
            return;
        }
        SafariWorldState state = SafariWorldState.get(server);
        List<ParkGeometry.Column> positions = ParkGeometry.REST_HOUSES;
        List<int[]> taken = new ArrayList<>();
        for (ParkGeometry.Column position : positions) {
            int[] built = state.houseSite(position.x(), position.z());
            if (built != null) {
                taken.add(built);
            }
        }
        for (int i = 0; i < positions.size(); i++) {
            ParkGeometry.Column position = positions.get(i);
            if (state.isHouseBuilt(position.x(), position.z())) {
                continue;
            }
            // The first house is the entrance: it is searched for closer to its anchor.
            boolean entrance = i == 0;
            int radius = entrance ? ENTRANCE_SEARCH_RADIUS : SEARCH_RADIUS;
            int[] site = pickDrySite(safari, position, radius, taken);
            if (!RestHouseBuilder.build(safari, site[0], site[1], doorFacing(site[0], site[1]))) {
                // Template missing: not marked built, so the next start tries again.
                continue;
            }
            state.markHouseBuilt(position.x(), position.z(), site[0], site[1]);
            taken.add(site);
            CobblemonSafari.LOGGER.info("Built Safari rest house at {}, {} (anchor {}, {})",
                    site[0], site[1], position.x(), position.z());
        }
        refreshSites(state);
    }

    /**
     * The first candidate of the site search with no water on the real
     * terrain under the house and around it. The search reads the noise,
     * which can miss a pond between its samples or misjudge water near sea
     * level; the real blocks cannot. When every candidate is wet (or there
     * is none), the driest one, or the anchor as a last resort, logged.
     *
     * <p>Cost: each candidate checked generates the few chunks under it, at
     * most {@link RestHouseSites#MAX_CANDIDATES} per house, once per map.
     */
    private static int[] pickDrySite(ServerLevel level, ParkGeometry.Column anchor, int radius, List<int[]> taken) {
        int[] driest = null;
        int driestWet = Integer.MAX_VALUE;
        for (int[] candidate : RestHouseSites.candidates(level, anchor.x(), anchor.z(), radius, taken)) {
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
        CobblemonSafari.LOGGER.warn("No spot found for the rest house near {}, {}; building it on that position",
                anchor.x(), anchor.z());
        return new int[]{anchor.x(), anchor.z()};
    }

    /** Rebuilds the in-memory list of built houses from the world state. */
    private static void refreshSites(SafariWorldState state) {
        List<HouseSite> list = new ArrayList<>();
        for (int i = 0; i < ParkGeometry.REST_HOUSES.size(); i++) {
            ParkGeometry.Column position = ParkGeometry.REST_HOUSES.get(i);
            int[] site = state.houseSite(position.x(), position.z());
            if (site != null) {
                list.add(new HouseSite(site[0], site[1], i == 0));
            }
        }
        sites = List.copyOf(list);
    }

    /**
     * The houses of the current map, where they were actually built. Before
     * the first build of this server run, the anchors.
     */
    public static List<HouseSite> sites() {
        List<HouseSite> current = sites;
        if (current != null) {
            return current;
        }
        List<HouseSite> anchors = new ArrayList<>();
        for (int i = 0; i < ParkGeometry.REST_HOUSES.size(); i++) {
            ParkGeometry.Column anchor = ParkGeometry.REST_HOUSES.get(i);
            anchors.add(new HouseSite(anchor.x(), anchor.z(), i == 0));
        }
        return anchors;
    }

    /**
     * Whether a column lies on a rest house (plaza included) or within {@code margin} blocks of it.
     * Cheap (a few comparisons per house), safe to call per spawn attempt.
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
     * Where players arrive in the Safari: on the grass in front of the
     * entrance house's steps, on the axis of its door.
     *
     * @param column the column to stand on (y 0: the caller finds the ground)
     * @param yaw    the rotation that faces the doorway
     */
    public record Arrival(BlockPos column, float yaw) {
    }

    /**
     * The arrival in front of the entrance house, or null when no entrance
     * house is known (the Safari level is missing). Before the first build of
     * this server run it uses the entrance anchor, where the house will
     * stand only if the anchor was a good spot; callers check the ground.
     */
    @Nullable
    public static Arrival entranceArrival() {
        for (HouseSite site : sites()) {
            if (site.entrance()) {
                Direction facing = doorFacing(site.x(), site.z());
                // Looking back at the house: the opposite of where its door opens.
                return new Arrival(RestHouseBuilder.arrivalColumn(site.x(), site.z(), facing),
                        facing.getOpposite().toYRot());
            }
        }
        return null;
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
