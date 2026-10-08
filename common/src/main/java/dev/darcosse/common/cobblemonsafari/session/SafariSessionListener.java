package dev.darcosse.common.cobblemonsafari.session;

import net.minecraft.server.level.ServerPlayer;

/**
 * Hook for features built on top of sessions: statistics, records, rewards,
 * ticket consumption, economy refunds...
 *
 * <p>Register through {@link SafariSessionManager#addListener}. Listeners run on
 * the server thread, inside the session update; they must be cheap and must not
 * start or end sessions themselves.
 */
public interface SafariSessionListener {

    /** A session has just started; the player is already inside. */
    default void onStart(ServerPlayer player, SafariSession session) {
    }

    /** The remaining time was changed by something other than the clock ticking down. */
    default void onTimeChanged(ServerPlayer player, SafariSession session, int previousTicks) {
    }

    /** The player caught a Pokémon inside the Safari; the record is already on the session. */
    default void onCatch(ServerPlayer player, SafariSession session, CatchRecord record) {
    }

    /** A session has just ended; the player may already have been teleported out. */
    default void onEnd(ServerPlayer player, SafariSession session, SessionEndReason reason) {
    }
}
