package dev.darcosse.common.cobblemonsafari.network;

import dev.darcosse.common.cobblemonsafari.config.SafariConfigManager;
import dev.darcosse.common.cobblemonsafari.platform.Platform;
import dev.darcosse.common.cobblemonsafari.session.SafariSession;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

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
     */
    public static final String PROTOCOL_VERSION = "2";

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

    /** Snapshot of the rules the client needs, from the current config. */
    private static SafariRulesPayload buildRules() {
        return new SafariRulesPayload(SafariConfigManager.dimension().location(),
                List.copyOf(SafariConfigManager.allowedBalls()),
                SafariConfigManager.get().protection.protectBlocks,
                SafariConfigManager.get().exemptCreativeAndSpectator);
    }
}
