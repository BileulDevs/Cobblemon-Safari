package dev.darcosse.common.cobblemonsafari.park;

import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import dev.darcosse.common.cobblemonsafari.teleport.SafariTeleporter;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Backstop for the world border: brings back players who ended up outside it.
 *
 * <p>The world border ({@link SafariBorder}) stops players walking out, but it
 * does not pull in someone who is already outside: a /tp, a respawn, or a
 * border that shrank while they stood near it. This check, same square as the
 * border, covers those cases.
 */
public final class ParkBoundary {

    /**
     * How far inside the border a player is put back. Too small and the border
     * warning overlay stays on; too large and the correction feels like a
     * random teleport.
     */
    private static final int PULLBACK_DISTANCE = 8;

    private ParkBoundary() {
    }

    /** Whether a position lies outside the park square. */
    public static boolean isOutside(double x, double z) {
        return Math.abs(x - ParkGeometry.CENTER_X) > ParkGeometry.BORDER_HALF_SIZE
                || Math.abs(z - ParkGeometry.CENTER_Z) > ParkGeometry.BORDER_HALF_SIZE;
    }

    /** Clamps a player back inside the square, near where they crossed. */
    public static void pullBack(ServerPlayer player) {
        int limit = Math.max(0, ParkGeometry.BORDER_HALF_SIZE - PULLBACK_DISTANCE);
        int x = (int) Math.floor(clamp(player.getX(), ParkGeometry.CENTER_X - limit, ParkGeometry.CENTER_X + limit));
        int z = (int) Math.floor(clamp(player.getZ(), ParkGeometry.CENTER_Z - limit, ParkGeometry.CENTER_Z + limit));
        SafariTeleporter.sendToSurface(player, (ServerLevel) player.level(), x, z);
        player.displayClientMessage(
                Component.translatable(CobblemonSafari.MOD_ID + ".message.boundary"), true);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
