package dev.darcosse.common.cobblemonsafari.park;

import dev.darcosse.common.cobblemonsafari.dimension.SafariDimensions;
import net.minecraft.network.protocol.game.ClientboundInitializeBorderPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.border.WorldBorder;

/**
 * Gives the Safari its own world border, which vanilla 1.21.1 does not support
 * out of the box. Two vanilla behaviours get in the way:
 * <ol>
 *   <li>Server side, {@code MinecraftServer.createLevels} makes every other
 *   dimension's border follow the overworld's
 *   ({@code DelegateBorderChangeListener}). Any /worldborder in the overworld
 *   overwrites the Safari border. So the border is re-applied whenever it drifts.</li>
 *   <li>Client side, {@code PlayerList.sendLevelInfo} always sends the
 *   OVERWORLD border, whatever dimension the player arrives in. So the Safari
 *   border is re-sent to players inside the Safari. Leaving needs nothing:
 *   vanilla then sends the overworld border, which is the right one.</li>
 * </ol>
 *
 * <p>Neither the border's size nor its centre is saved for non-overworld
 * dimensions, so it is applied again at every server start.
 */
public final class SafariBorder {

    /**
     * Distance at which the vanilla red warning overlay appears. Players are
     * invulnerable inside, so the warning only signals the end of the park.
     */
    private static final int WARNING_BLOCKS = 16;

    /**
     * Tolerance when comparing the live border with the configured one. The
     * border stores doubles and may be mid-interpolation after an overworld
     * change; anything beyond this is treated as drift and corrected.
     */
    private static final double DRIFT_EPSILON = 0.01;

    private SafariBorder() {
    }

    /** Applies the park's border to the Safari level. Call at server start and after a config reload. */
    public static void apply(MinecraftServer server) {
        ServerLevel safari = SafariDimensions.level(server);
        if (safari == null) {
            return;
        }
        WorldBorder border = safari.getWorldBorder();
        // Exact block coordinates, not block centres: ParkBoundary tests the same
        // square, and a half-block offset would make it pull back players the
        // border still lets stand on its eastern and southern edges.
        border.setCenter(ParkGeometry.CENTER_X, ParkGeometry.CENTER_Z);
        border.setSize(ParkGeometry.BORDER_HALF_SIZE * 2.0);
        border.setWarningBlocks(WARNING_BLOCKS);
    }

    /**
     * Re-applies the border if the overworld overwrote it, then sends it to
     * everyone inside the Safari.
     *
     * <p>Cost: one comparison, plus one ~50-byte packet per player inside.
     * Called once per second from the consistency sweep; the resend is what
     * repairs the client after a respawn, a /tp, or an overworld border change
     * broadcast to all players.
     */
    public static void enforce(MinecraftServer server) {
        ServerLevel safari = SafariDimensions.level(server);
        if (safari == null) {
            return;
        }
        if (hasDrifted(safari.getWorldBorder())) {
            apply(server);
        }
        for (ServerPlayer player : safari.players()) {
            sendTo(player);
        }
    }

    /** Sends the Safari border to one player; call right after they arrive. */
    public static void sendTo(ServerPlayer player) {
        ServerLevel safari = SafariDimensions.level(player.getServer());
        if (safari != null) {
            player.connection.send(new ClientboundInitializeBorderPacket(safari.getWorldBorder()));
        }
    }

    /** Whether the live border moved away from the park's centre or size. */
    private static boolean hasDrifted(WorldBorder border) {
        return Math.abs(border.getCenterX() - ParkGeometry.CENTER_X) > DRIFT_EPSILON
                || Math.abs(border.getCenterZ() - ParkGeometry.CENTER_Z) > DRIFT_EPSILON
                || Math.abs(border.getSize() - ParkGeometry.BORDER_HALF_SIZE * 2.0) > DRIFT_EPSILON;
    }
}
