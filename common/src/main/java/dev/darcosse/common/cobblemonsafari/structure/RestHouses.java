package dev.darcosse.common.cobblemonsafari.structure;

import dev.darcosse.common.cobblemonsafari.park.ParkGeometry;
import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import dev.darcosse.common.cobblemonsafari.dimension.SafariDimensions;
import dev.darcosse.common.cobblemonsafari.world.SafariWorldState;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

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
 * place to heal inside the park. Built from code rather than from a structure
 * file so that the Healing Machine can be flagged infinite (see
 * {@link RestHouseBuilder}) and the building can adapt its foundation to
 * whatever terrain the seed produced.
 */
public final class RestHouses {

    /**
     * Half the side of the building: houses are 11x11, centred on their
     * site. Changing it resizes the whole layout of
     * {@link RestHouseBuilder}, which is written relative to it.
     */
    public static final int HALF_SIZE = 5;

    /**
     * Distance from the centre to the farthest block a house touches: the
     * front plaza and the cleared ring around it. Everything that must keep
     * away from a house (arrivals, specials) measures from this, not from the
     * walls, or a special could pop up on the plaza in front of the door.
     */
    public static final int FOOTPRINT_REACH = HALF_SIZE + 5;

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
            int[] site = RestHouseSites.find(safari, position.x(), position.z(), radius, taken);
            RestHouseBuilder.build(safari, site[0], site[1], doorFacing(site[0], site[1]));
            state.markHouseBuilt(position.x(), position.z(), site[0], site[1]);
            taken.add(site);
            CobblemonSafari.LOGGER.info("Built Safari rest house at {}, {} (anchor {}, {})",
                    site[0], site[1], position.x(), position.z());
        }
        refreshSites(state);
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
