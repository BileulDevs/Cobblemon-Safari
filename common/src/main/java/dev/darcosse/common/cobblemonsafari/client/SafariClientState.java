package dev.darcosse.common.cobblemonsafari.client;

import dev.darcosse.common.cobblemonsafari.network.SafariRulesPayload;
import dev.darcosse.common.cobblemonsafari.network.SafariTimerPayload;
import dev.darcosse.common.cobblemonsafari.structure.RestHouses;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Set;

/**
 * What the client knows about its own Safari session and the server rules.
 *
 * <p>Deliberately free of client-only imports: the NeoForge payload handlers
 * that write here are registered on both sides, and this class must load on a
 * dedicated server without crashing.
 *
 * <p>Not thread-safe; only touch it from the client main thread (payload
 * handlers already hop to it before calling in).
 */
public final class SafariClientState {

    private static boolean active;
    private static int remainingAtSnapshot;
    private static boolean running;
    private static int catches;
    private static int ballsLoaned;
    private static int grantedTicks;
    /**
     * Client game time the last snapshot is anchored to. Left unset on receipt
     * because this class cannot read the client clock; the first reader anchors it.
     */
    private static long anchorGameTime;
    private static boolean anchorPending;

    @Nullable
    private static ResourceLocation safariDimension;
    @Nullable
    private static Set<ResourceLocation> allowedBalls;
    private static boolean protectBlocks;
    private static boolean exemptCreativeAndSpectator;
    private static Set<ResourceLocation> placeableBlocks = Set.of();
    private static List<SafariRulesPayload.HouseColumn> restHouses = List.of();

    private SafariClientState() {
    }

    /** Applies a timer snapshot from the server. */
    public static void onTimer(SafariTimerPayload payload) {
        active = payload.active();
        remainingAtSnapshot = payload.remainingTicks();
        running = payload.running();
        catches = payload.catches();
        ballsLoaned = payload.ballsLoaned();
        grantedTicks = payload.grantedTicks();
        anchorPending = true;
    }

    /** Applies the server rules. */
    public static void onRules(SafariRulesPayload payload) {
        safariDimension = payload.dimension();
        allowedBalls = Set.copyOf(payload.allowedBalls());
        protectBlocks = payload.protectBlocks();
        exemptCreativeAndSpectator = payload.exemptCreativeAndSpectator();
        placeableBlocks = Set.copyOf(payload.placeableBlocks());
        restHouses = payload.restHouses();
    }

    /** Forgets everything; called on disconnect so nothing leaks into the next server. */
    public static void clear() {
        active = false;
        remainingAtSnapshot = 0;
        running = false;
        catches = 0;
        ballsLoaned = 0;
        grantedTicks = 0;
        anchorPending = false;
        safariDimension = null;
        allowedBalls = null;
        protectBlocks = false;
        exemptCreativeAndSpectator = false;
        placeableBlocks = Set.of();
        restHouses = List.of();
    }

    /**
     * Whether a column lies inside the walls of a rest house (its plaza is
     * outside). A few comparisons per house: cheap enough for the per-tick
     * music check. Does not look at the dimension; callers do.
     */
    public static boolean isInRestHouse(int x, int z) {
        for (SafariRulesPayload.HouseColumn house : restHouses) {
            if (Math.abs(x - house.x()) <= RestHouses.HALF_SIZE && Math.abs(z - house.z()) <= RestHouses.HALF_SIZE) {
                return true;
            }
        }
        return false;
    }

    /** Pokémon caught this session, as last reported by the server. */
    public static int catches() {
        return catches;
    }

    /** Safari Balls lent at the entrance; 0 when the server does not lend any. */
    public static int ballsLoaned() {
        return ballsLoaned;
    }

    /** Total time granted over the session, additions included; 0 without a session. */
    public static int grantedTicks() {
        return grantedTicks;
    }

    /** Whether the session clock is counting down, i.e. the player is inside the park. */
    public static boolean isRunning() {
        return running;
    }

    /** Whether the server reported an active session. */
    public static boolean isActive() {
        return active;
    }

    /**
     * Remaining ticks as of the given client game time, counted down locally
     * since the last snapshot. Uses game time rather than wall time so a paused
     * single-player game freezes the display along with the server clock.
     */
    public static int remainingTicks(long clientGameTime) {
        if (anchorPending) {
            anchorGameTime = clientGameTime;
            anchorPending = false;
        }
        if (!running) {
            return remainingAtSnapshot;
        }
        long elapsed = Math.max(0, clientGameTime - anchorGameTime);
        return (int) Math.max(0, remainingAtSnapshot - elapsed);
    }

    /** The Safari dimension announced by the server, or null before the rules arrive. */
    @Nullable
    public static ResourceLocation safariDimension() {
        return safariDimension;
    }

    /** Whether the server protects the terrain; false before the rules arrive (meaning: do not pre-check). */
    public static boolean protectBlocks() {
        return protectBlocks;
    }

    /** Block ids the server lets players place and break despite the protection; empty before the rules arrive. */
    public static Set<ResourceLocation> placeableBlocks() {
        return placeableBlocks;
    }

    /** Whether creative/spectator players are exempt from terrain protection. */
    public static boolean exemptCreativeAndSpectator() {
        return exemptCreativeAndSpectator;
    }

    /** Allowed balls announced by the server, or null before the rules arrive (meaning: do not pre-check). */
    @Nullable
    public static Set<ResourceLocation> allowedBalls() {
        return allowedBalls;
    }
}
