package dev.darcosse.common.cobblemonsafari.teleport;

import com.cobblemon.mod.common.battles.BattleRegistry;
import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import dev.darcosse.common.cobblemonsafari.config.SafariConfigManager;
import dev.darcosse.common.cobblemonsafari.dimension.SafariDimensions;
import dev.darcosse.common.cobblemonsafari.structure.Compass;
import dev.darcosse.common.cobblemonsafari.structure.RestHouseStaff;
import dev.darcosse.common.cobblemonsafari.structure.RestHouses;
import dev.darcosse.common.cobblemonsafari.util.Ticks;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Travel to a rest house: the wait between two trips, shared by the Escape
 * Rope's "nearest rest house" and the Safari Guide, and the guide's own
 * trips to the house of a compass direction.
 *
 * <p>One wait for both on purpose ({@code escapeRope.restHouseCooldownSeconds}):
 * with two, a player could rope to a house and take its guide straight on,
 * crossing the park in two clicks.
 *
 * <p>The guide offers the built houses only. A planned one is built the
 * first time its chunks are loaded around a player, over several ticks:
 * someone sent there would stand on the spot while the walls go up.
 *
 * <p>Server side. The wait is kept in memory only: a restart forgets it,
 * which at worst lets a player travel a minute early.
 */
public final class RestHouseTravel {

    /**
     * Blocks around a house's square within which a player counts as being
     * at that house, so its guide does not offer the house the player
     * stands in. Covers the steps and the plaza.
     */
    private static final int HOUSE_MARGIN = 2;

    /** Server tick from which each player may travel to a rest house again; absent means now. */
    private static final Map<UUID, Integer> READY_AT = new HashMap<>();

    private RestHouseTravel() {
    }

    /** Forgets every wait; call when a server starts, as single player reuses this class. */
    public static void reset() {
        READY_AT.clear();
    }

    /** Ticks before the player may travel to a rest house again; 0 when they may now. */
    public static int remainingTicks(ServerPlayer player) {
        int now = player.getServer().getTickCount();
        return Math.max(0, READY_AT.getOrDefault(player.getUUID(), now) - now);
    }

    /** Starts the player's wait after a trip, from the config; nothing when it is 0. */
    public static void markTravelled(ServerPlayer player) {
        int wait = Ticks.fromSeconds(SafariConfigManager.get().escapeRope.restHouseCooldownSeconds);
        if (wait > 0) {
            READY_AT.put(player.getUUID(), player.getServer().getTickCount() + wait);
        } else {
            READY_AT.remove(player.getUUID());
        }
    }

    /**
     * Whether the guide can take the player to the house of this direction:
     * it is built, and it is not the house the player stands in. What the
     * guide's dialogue uses to show the option.
     */
    public static boolean canTravelTo(ServerPlayer player, Compass direction) {
        if (!SafariDimensions.isSafari(player.level())) {
            return false;
        }
        RestHouses.HouseSite target = RestHouses.builtSiteIn(direction);
        return target != null && !target.equals(RestHouses.siteAt(player.getBlockX(), player.getBlockZ(), HOUSE_MARGIN));
    }

    /**
     * Takes the player to the house of this direction, in front of its door,
     * if they stand by a guide, are not in a battle, and their wait is over.
     * Checks again what the dialogue showed: it may have stayed open a while.
     *
     * @return whether the player was moved
     */
    public static boolean travelTo(ServerPlayer player, Compass direction) {
        if (!SafariDimensions.isSafari(player.level()) || !RestHouseStaff.isNearGuide(player)) {
            return false;
        }
        if (BattleRegistry.getBattleByParticipatingPlayer(player) != null) {
            player.displayClientMessage(Component.translatable(key("message.guide_in_battle")), true);
            return false;
        }
        int remaining = remainingTicks(player);
        if (remaining > 0) {
            player.displayClientMessage(Component.translatable(key("message.guide_wait"), Ticks.format(remaining)),
                    true);
            return false;
        }
        RestHouses.HouseSite target = RestHouses.builtSiteIn(direction);
        if (target == null || !canTravelTo(player, direction)
                || !SafariTeleporter.sendToArrival(player, (ServerLevel) player.level(), RestHouses.arrivalOf(target))) {
            player.displayClientMessage(Component.translatable(key("message.guide_unreachable")), true);
            return false;
        }
        markTravelled(player);
        player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.CHORUS_FRUIT_TELEPORT, SoundSource.PLAYERS, 1.0F, 1.0F);
        return true;
    }

    /** Builds a translation key in the mod namespace. */
    private static String key(String path) {
        return CobblemonSafari.MOD_ID + "." + path;
    }
}
