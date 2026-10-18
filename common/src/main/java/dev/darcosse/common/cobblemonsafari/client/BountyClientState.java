package dev.darcosse.common.cobblemonsafari.client;

import dev.darcosse.common.cobblemonsafari.bounty.Bounty;
import dev.darcosse.common.cobblemonsafari.network.SafariBountiesPayload;

import java.util.List;

/**
 * The client's copy of today's missions and of the local player's progress,
 * for the wall screens. Holds no client-only types, so payload handlers
 * registered on both dists can call it.
 */
public final class BountyClientState {

    private static boolean enabled;
    private static List<Bounty> bounties = List.of();
    private static List<Integer> progress = List.of();
    private static int allDoneBonus;

    private BountyClientState() {
    }

    /** Applies a snapshot from the server. */
    public static void onBounties(SafariBountiesPayload payload) {
        enabled = payload.enabled();
        bounties = payload.bounties();
        progress = payload.progress();
        allDoneBonus = payload.allDoneBonus();
    }

    /** Forgets everything; called on disconnect. */
    public static void clear() {
        enabled = false;
        bounties = List.of();
        progress = List.of();
        allDoneBonus = 0;
    }

    /** Whether the board should show a missions page. */
    public static boolean hasBounties() {
        return enabled && !bounties.isEmpty();
    }

    /** Today's missions. */
    public static List<Bounty> bounties() {
        return bounties;
    }

    /** Local player's catches toward mission {@code index}, capped at its count. */
    public static int progress(int index) {
        return index < progress.size() ? Math.min(progress.get(index), bounties.get(index).count()) : 0;
    }

    /** Points paid once every mission is done. */
    public static int allDoneBonus() {
        return allDoneBonus;
    }
}
