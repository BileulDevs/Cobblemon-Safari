package dev.darcosse.common.cobblemonsafari.network;

import dev.darcosse.common.cobblemonsafari.config.SafariConfigManager;
import dev.darcosse.common.cobblemonsafari.dimension.SafariDimensions;
import dev.darcosse.common.cobblemonsafari.platform.Platform;
import dev.darcosse.common.cobblemonsafari.points.SafariPoints;
import dev.darcosse.common.cobblemonsafari.stats.PlayerSafariStats;
import dev.darcosse.common.cobblemonsafari.stats.SafariStatsStore;
import dev.darcosse.common.cobblemonsafari.util.Ticks;
import dev.darcosse.common.cobblemonsafari.session.SafariSession;
import dev.darcosse.common.cobblemonsafari.structure.RestHouses;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * Server-side helpers that build and send Safari payloads.
 *
 * <p>Registration of the payload types is loader-specific and lives in each
 * loader module; this class only decides WHAT to send.
 */
public final class SafariNetwork {

    /**
     * Network protocol version announced to NeoForge's channel negotiation.
     * Bumped whenever a payload is added or changes format, so that a client
     * of another build is refused at login with a clear message instead of
     * failing on the first packet.
     * 2: featured payload added, timer payload v3.
     * 3: rules payload v3 (placeable blocks).
     * 4: shop and shop purchase payloads added.
     * 5: bounties payload added.
     * 6: rules payload v4 (rest house columns).
     * 7: shop payload v2 (tabs).
     * 8: stats, wall screen open and wall screen settings payloads added.
     * 9: wall screen open and settings payloads v2 (signed tilt).
     * 10: banner open and banner set payloads added.
     */
    public static final String PROTOCOL_VERSION = "10";

    private SafariNetwork() {
    }

    /** Sends the current state of a player's session: clock, catches and loan. */
    public static void sendTimer(ServerPlayer player, SafariSession session, boolean running) {
        Platform.get().sendToPlayer(player, new SafariTimerPayload(true, session.remainingTicks(), running,
                session.catches().size(), session.ballsLoaned(), session.grantedTicks()));
    }

    /** Tells a player they no longer have a session, hiding their HUD. */
    public static void sendNoSession(ServerPlayer player) {
        Platform.get().sendToPlayer(player, SafariTimerPayload.inactive());
    }

    /** Sends the current rules to one player. */
    public static void sendRules(ServerPlayer player) {
        Platform.get().sendToPlayer(player, buildRules());
    }

    /** Sends the current rules to everyone, after a config reload. */
    public static void broadcastRules(MinecraftServer server) {
        SafariRulesPayload rules = buildRules();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            Platform.get().sendToPlayer(player, rules);
        }
    }

    /** Sends the special Pokémon of the day to one player. */
    public static void sendFeatured(ServerPlayer player, SafariFeaturedPayload payload) {
        Platform.get().sendToPlayer(player, payload);
    }

    /** Sends the special Pokémon of the day to everyone, when the list changed. */
    public static void broadcastFeatured(MinecraftServer server, SafariFeaturedPayload payload) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            Platform.get().sendToPlayer(player, payload);
        }
    }

    /** Sends the shop contents to one player, opening or refreshing their shop screen. */
    public static void sendShop(ServerPlayer player, SafariShopPayload payload) {
        Platform.get().sendToPlayer(player, payload);
    }

    /**
     * Sends a player their own Safari record and points, for the wall
     * screens' STATS page. A player who never played gets zeros, so the page
     * shows an empty record rather than waiting forever.
     */
    public static void sendStats(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        PlayerSafariStats stats = SafariStatsStore.get(server).find(player.getUUID());
        int points = SafariPoints.balance(player);
        if (stats == null) {
            Platform.get().sendToPlayer(player, new SafariStatsPayload(0, 0, 0, 0, 0L, 0, points));
            return;
        }
        Platform.get().sendToPlayer(player, new SafariStatsPayload(stats.sessions(), stats.totalCatches(),
                stats.shinyCatches(), stats.bestSessionCatches(), stats.ticksHunted() / Ticks.PER_SECOND,
                stats.speciesCaught().size(), points));
    }

    /** Opens the settings screen of a wall screen group on one player's client. */
    public static void sendWallScreenOpen(ServerPlayer player, SafariWallScreenOpenPayload payload) {
        Platform.get().sendToPlayer(player, payload);
    }

    /** Opens the design picker of a banner on one player's client. */
    public static void sendBannerOpen(ServerPlayer player, SafariBannerOpenPayload payload) {
        Platform.get().sendToPlayer(player, payload);
    }

    /** Snapshot of the rules the client needs, from the current config. */
    private static SafariRulesPayload buildRules() {
        return new SafariRulesPayload(SafariDimensions.key().location(),
                List.copyOf(SafariConfigManager.allowedBalls()),
                SafariConfigManager.get().protection.protectBlocks,
                SafariConfigManager.get().exemptCreativeAndSpectator,
                List.copyOf(SafariConfigManager.placeableBlocks()),
                restHouseColumns());
    }

    /**
     * Centre columns of the rest houses where they stand or will stand,
     * built or not yet (the site search may move them from their configured
     * positions); none when the Safari level is missing.
     */
    private static List<SafariRulesPayload.HouseColumn> restHouseColumns() {
        List<SafariRulesPayload.HouseColumn> columns = new ArrayList<>();
        for (RestHouses.HouseSite site : RestHouses.sites()) {
            columns.add(new SafariRulesPayload.HouseColumn(site.x(), site.z()));
        }
        return List.copyOf(columns);
    }
}
