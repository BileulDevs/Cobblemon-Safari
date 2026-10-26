package dev.darcosse.common.cobblemonsafari.config;

/**
 * Swapping a mission at the rest house clerk ({@code bounties.swap}): a
 * player who finds a mission too hard pays to replace it with another, for
 * themselves only. The new mission starts from zero; a mission already done
 * cannot be swapped.
 */
public class BountySwap {
    /** Whether the clerk offers it. */
    public boolean enabled = true;
    /** Safari points it costs: about a third of a mission's reward, so swapping stays worth it. */
    public int cost = 50;
    /** Swaps each player may make per rotation, 0 to 8. */
    public int perRotation = 1;
}
