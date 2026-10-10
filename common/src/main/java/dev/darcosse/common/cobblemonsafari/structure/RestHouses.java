package dev.darcosse.common.cobblemonsafari.structure;

import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import dev.darcosse.common.cobblemonsafari.config.SafariConfig;
import dev.darcosse.common.cobblemonsafari.config.SafariConfigManager;
import dev.darcosse.common.cobblemonsafari.dimension.SafariDimensions;
import dev.darcosse.common.cobblemonsafari.world.SafariWorldState;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.util.List;

/**
 * The rest houses of the Safari: where they stand, whether they are built,
 * and building the missing ones.
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
     * configured column. Changing it resizes the whole layout of
     * {@link RestHouseBuilder}, which is written relative to it.
     */
    public static final int HALF_SIZE = 5;

    /**
     * Distance from the centre to the farthest block a house touches: the
     * front plaza and the cleared ring around it. Everything that must keep
     * away from a house (arrivals, specials) measures from this, not from the
     * walls, or a special could pop up on the plaza in front of the board.
     */
    public static final int FOOTPRINT_REACH = HALF_SIZE + 5;

    private RestHouses() {
    }

    /**
     * Builds every configured rest house that does not exist yet.
     *
     * <p>Cost: for each missing house, loading or generating the 1 to 4 chunks
     * under it and a few hundred block changes, once per world. Called at
     * server start and after /safari reload, never per tick.
     */
    public static void ensureBuilt(MinecraftServer server) {
        SafariConfig.RestHouses config = SafariConfigManager.get().restHouses;
        ServerLevel safari = SafariDimensions.level(server);
        if (!config.enabled || safari == null) {
            return;
        }
        SafariWorldState state = SafariWorldState.get(server);
        List<SafariConfig.Position> positions = config.positions;
        for (int i = 0; i < positions.size(); i++) {
            SafariConfig.Position position = positions.get(i);
            if (state.isHouseBuilt(position.x, position.z)) {
                continue;
            }
            // The first house is the entrance: it also gets the Safari Board.
            boolean entrance = i == 0;
            RestHouseBuilder.build(safari, position.x, position.z, doorFacing(position), entrance);
            state.markHouseBuilt(position.x, position.z);
            CobblemonSafari.LOGGER.info("Built Safari rest house at {}, {}", position.x, position.z);
        }
    }

    /**
     * Whether a column lies on a rest house (plaza included) or within {@code margin} blocks of it.
     * Cheap (a few comparisons per house), safe to call per spawn attempt.
     */
    public static boolean isNear(int x, int z, int margin) {
        SafariConfig.RestHouses config = SafariConfigManager.get().restHouses;
        if (!config.enabled) {
            return false;
        }
        int reach = FOOTPRINT_REACH + margin;
        for (SafariConfig.Position position : config.positions) {
            if (Math.abs(x - position.x) <= reach && Math.abs(z - position.z) <= reach) {
                return true;
            }
        }
        return false;
    }

    /**
     * The door faces the centre of the park, so every house opens toward the
     * heart of the island and the entrance opens toward the arrival point.
     */
    private static Direction doorFacing(SafariConfig.Position position) {
        SafariConfig.Park park = SafariConfigManager.get().park;
        int dx = park.centerX - position.x;
        int dz = park.centerZ - position.z;
        if (Math.abs(dx) > Math.abs(dz)) {
            return dx > 0 ? Direction.EAST : Direction.WEST;
        }
        return dz >= 0 ? Direction.SOUTH : Direction.NORTH;
    }
}
