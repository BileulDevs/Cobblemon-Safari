package dev.darcosse.common.cobblemonsafari.client;

import dev.darcosse.common.cobblemonsafari.network.SafariFeaturedPayload;

import java.util.List;

/**
 * What the client knows about the special Pokémon of the day, for the
 * wall screens.
 *
 * <p>Same constraints as {@link SafariClientState}: no client-only imports
 * (NeoForge registers payload handlers on both sides), main thread only.
 */
public final class FeaturedClientState {

    private static boolean received;
    private static boolean enabled;
    private static List<SafariFeaturedPayload.Entry> entries = List.of();
    /** Local wall-clock time at which the current rotation ends. */
    private static long rotationEndsAtMillis;
    private static long rotationLengthMillis;
    /** Local wall-clock time of the last change of list; drives the split-flap reveal. */
    private static long changedAtMillis;

    private FeaturedClientState() {
    }

    /**
     * Applies a snapshot from the server. The remaining time is anchored to
     * the local clock on receipt: network latency makes the countdown up to a
     * ping late, which a countdown in hours can afford.
     */
    public static void onFeatured(SafariFeaturedPayload payload) {
        long now = System.currentTimeMillis();
        if (!received || !payload.entries().equals(entries)) {
            changedAtMillis = now;
        }
        received = true;
        enabled = payload.enabled();
        entries = payload.entries();
        rotationEndsAtMillis = now + payload.millisUntilRotation();
        rotationLengthMillis = payload.rotationLengthMillis();
    }

    /** Forgets everything; called on disconnect so nothing leaks into the next server. */
    public static void clear() {
        received = false;
        enabled = false;
        entries = List.of();
        rotationEndsAtMillis = 0L;
        rotationLengthMillis = 0L;
        changedAtMillis = 0L;
    }

    /** Whether a snapshot arrived since joining; screens show a "connecting" state until then. */
    public static boolean received() {
        return received;
    }

    /** Whether the server runs the featured rotation at all. */
    public static boolean enabled() {
        return enabled;
    }

    /** Today's specials, most common first. Immutable. */
    public static List<SafariFeaturedPayload.Entry> entries() {
        return entries;
    }

    /** Milliseconds left in the rotation at the given local time, never negative. */
    public static long millisUntilRotation(long nowMillis) {
        return Math.max(0L, rotationEndsAtMillis - nowMillis);
    }

    /** Length of a whole rotation; 0 before the first snapshot. */
    public static long rotationLengthMillis() {
        return rotationLengthMillis;
    }

    /** Local time at which the list last changed. */
    public static long changedAtMillis() {
        return changedAtMillis;
    }
}
