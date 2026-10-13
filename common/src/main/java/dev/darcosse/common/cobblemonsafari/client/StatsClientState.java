package dev.darcosse.common.cobblemonsafari.client;

import dev.darcosse.common.cobblemonsafari.network.SafariStatsPayload;

/**
 * The client's copy of the local player's Safari record, for the STATS page
 * of the wall screens. Holds no client-only types, so payload handlers
 * registered on both dists can call it.
 */
public final class StatsClientState {

    /** Null until the server's first snapshot arrives. */
    private static SafariStatsPayload stats;

    private StatsClientState() {
    }

    /** Applies a snapshot from the server. */
    public static void onStats(SafariStatsPayload payload) {
        stats = payload;
    }

    /** Forgets everything; called on disconnect. */
    public static void clear() {
        stats = null;
    }

    /** The local player's record, or null while none was received (old server, or still logging in). */
    public static SafariStatsPayload stats() {
        return stats;
    }
}
