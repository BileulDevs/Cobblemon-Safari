package dev.darcosse.common.cobblemonsafari.park;

import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import dev.darcosse.common.cobblemonsafari.config.SafariConfig;
import dev.darcosse.common.cobblemonsafari.config.SafariConfigManager;
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

    /** Whether a position lies outside the park square. Always false when the border is disabled. */
    public static boolean isOutside(double x, double z) {
        SafariConfig.Park park = SafariConfigManager.get().park;
        if (park.borderHalfSize <= 0) {
            return false;
        }
        return Math.abs(x - park.centerX) > park.borderHalfSize
                || Math.abs(z - park.centerZ) > park.borderHalfSize;
    }

    /** Clamps a player back inside the square, near where they crossed. */
    public static void pullBack(ServerPlayer player) {
        SafariConfig.Park park = SafariConfigManager.get().park;
        int limit = Math.max(0, park.borderHalfSize - PULLBACK_DISTANCE);
        int x = (int) Math.floor(clamp(player.getX(), park.centerX - limit, park.centerX + limit));
        int z = (int) Math.floor(clamp(player.getZ(), park.centerZ - limit, park.centerZ + limit));
        SafariTeleporter.sendToSurface(player, (ServerLevel) player.level(), x, z);
        player.displayClientMessage(
                Component.translatable(CobblemonSafari.MOD_ID + ".message.boundary"), true);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
