package dev.darcosse.common.cobblemonsafari.reset;

import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import dev.darcosse.common.cobblemonsafari.config.SafariConfig;
import dev.darcosse.common.cobblemonsafari.config.SafariConfigManager;
import dev.darcosse.common.cobblemonsafari.dimension.SafariDimensions;
import dev.darcosse.common.cobblemonsafari.session.SafariSessionManager;
import dev.darcosse.common.cobblemonsafari.structure.RestHousePrebuilder;
import dev.darcosse.common.cobblemonsafari.structure.RestHouses;
import dev.darcosse.common.cobblemonsafari.util.Ticks;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * Resets the Safari map while the server runs ({@code reset.mode} LIVE, or
 * {@code /safari reset force}).
 *
 * <p>Three phases, all on the server thread, once a second:
 * <ol>
 *   <li>waiting: once a minute, whether a reset is due. A due reset starts
 *       at once if nobody is in the Safari; otherwise it waits for that, up
 *       to {@code reset.maxWaitMinutes};</li>
 *   <li>countdown: the players inside are warned, then sent out with their
 *       unused time given back; no one may enter from then on;</li>
 *   <li>draining: the old level, empty, keeps ticking a few seconds so its
 *       chunks unload and save bit by bit, the game's own way, rather than
 *       all at once when it closes; then {@link SafariLevelSwap} closes it
 *       and builds the new one in the same tick.</li>
 * </ol>
 * The only work on a single tick is the swap itself: closing a level with
 * almost no chunk left and creating an empty one. The old map's files are
 * deleted on a background thread.
 */
public final class LiveReset {

    /** Where the reset stands. */
    private enum Phase { IDLE, COUNTDOWN, DRAINING }

    /** Server ticks between two steps: the phases count in seconds. */
    private static final int TICKS_PER_STEP = 20;

    /**
     * Seconds between two checks of whether a reset is due: the day changes
     * once a day, and the check builds a date in the configured time zone.
     */
    private static final int DUE_CHECK_SECONDS = 60;

    /** Seconds left at which the players inside are warned, besides the start of the countdown. */
    private static final int[] WARNINGS = {600, 300, 120, 60, 30, 10, 5, 4, 3, 2, 1};

    /**
     * Seconds the empty old level is left to unload its chunks before it is
     * closed anyway. Chunks kept by something else (a /forceload, another
     * mod's ticket) never unload; they are then saved by the close, a short
     * stall proportional to their number.
     */
    private static final int MAX_DRAIN_SECONDS = 30;

    /** Loaded chunks left at which the drain stops waiting: none, the cheapest close. */
    private static final int DRAINED_CHUNKS = 0;

    private static Phase phase = Phase.IDLE;
    /** Server ticks since the last step. */
    private static int ticks;
    /** Seconds since the last due check. */
    private static int sinceDueCheck;
    /** Whether the last due check found a reset due. */
    private static boolean due;
    /** Seconds a due reset has waited for the Safari to empty. */
    private static int waited;
    /** Seconds left in the countdown, or spent draining. */
    private static int seconds;

    private LiveReset() {
    }

    /** Forgets any reset in progress; for a server start, as single player reuses this class. */
    public static void reset() {
        phase = Phase.IDLE;
        ticks = 0;
        // A start already resets a due map before the levels load: nothing to check right away.
        sinceDueCheck = 0;
        due = false;
        waited = 0;
        seconds = 0;
    }

    /**
     * Whether the Safari is closing for a reset: no entry, and nothing may
     * build or load chunks in the old level.
     */
    public static boolean isClosing() {
        return phase != Phase.IDLE;
    }

    /**
     * Starts a reset now, whatever the mode and the day: at once when the
     * Safari is empty or there is no countdown, else after the countdown.
     *
     * @return the number of players inside who were warned, or -1 when a reset is already under way
     */
    public static int force(MinecraftServer server) {
        if (phase != Phase.IDLE) {
            return -1;
        }
        List<ServerPlayer> inside = playersInside(server);
        int countdown = SafariConfigManager.get().reset.countdownSeconds;
        if (inside.isEmpty() || countdown == 0) {
            startDrain(server);
        } else {
            startCountdown(inside, countdown);
        }
        return inside.size();
    }

    /**
     * One step a second; a counter increment on the other ticks. Waiting
     * costs a date built once a minute; a countdown, a player list per
     * second; a drain, a chunk count per second.
     */
    public static void tick(MinecraftServer server) {
        if (++ticks < TICKS_PER_STEP) {
            return;
        }
        ticks = 0;
        switch (phase) {
            case IDLE -> waitForDue(server);
            case COUNTDOWN -> countDown(server);
            case DRAINING -> drain(server);
        }
    }

    /** LIVE mode: starts a due reset when the Safari is empty, or the countdown once the wait is over. */
    private static void waitForDue(MinecraftServer server) {
        SafariConfig.Reset config = SafariConfigManager.get().reset;
        if (config.mode != ResetMode.LIVE) {
            due = false;
            return;
        }
        if (++sinceDueCheck >= DUE_CHECK_SECONDS) {
            sinceDueCheck = 0;
            boolean wasDue = due;
            due = SafariMapReset.isDue();
            if (due && !wasDue) {
                waited = 0;
            }
        }
        if (!due) {
            return;
        }
        List<ServerPlayer> inside = playersInside(server);
        if (inside.isEmpty()) {
            CobblemonSafari.LOGGER.info("Safari map reset due and the Safari is empty: resetting it now");
            startDrain(server);
        } else if (++waited >= config.maxWaitMinutes * 60) {
            CobblemonSafari.LOGGER.info("Safari map reset due for {} minutes with {} player(s) inside: forcing it",
                    config.maxWaitMinutes, inside.size());
            if (config.countdownSeconds == 0) {
                startDrain(server);
            } else {
                startCountdown(inside, config.countdownSeconds);
            }
        }
    }

    /** Warns the players inside that the Safari closes in the given time. */
    private static void startCountdown(List<ServerPlayer> inside, int countdown) {
        phase = Phase.COUNTDOWN;
        seconds = countdown;
        warn(inside, countdown);
    }

    /** Counts down; at zero, or as soon as the Safari is empty, moves on. */
    private static void countDown(MinecraftServer server) {
        seconds--;
        List<ServerPlayer> inside = playersInside(server);
        if (seconds <= 0 || inside.isEmpty()) {
            startDrain(server);
            return;
        }
        for (int mark : WARNINGS) {
            if (seconds == mark) {
                warn(inside, seconds);
                break;
            }
        }
    }

    /** One warning line to each player inside. */
    private static void warn(List<ServerPlayer> inside, int secondsLeft) {
        Component line = Component.translatable(CobblemonSafari.MOD_ID + ".message.reset_countdown",
                Ticks.format(Ticks.fromSeconds(secondsLeft))).withStyle(ChatFormatting.GOLD);
        for (ServerPlayer player : inside) {
            player.sendSystemMessage(line);
        }
    }

    /**
     * Sends everyone out and lets the old level unload: the mod's own
     * tickets and building work on it are dropped, so only what the players
     * kept loaded remains, and that unloads within seconds.
     */
    private static void startDrain(MinecraftServer server) {
        phase = Phase.DRAINING;
        seconds = 0;
        due = false;
        evictAll(server);
        ServerLevel safari = SafariDimensions.level(server);
        if (safari != null) {
            RestHousePrebuilder.release(safari);
        }
        // Pending builds and site searches belong to the old terrain.
        RestHouses.reset();
    }

    /** Swaps the levels once the old one has no chunk left, or after {@link #MAX_DRAIN_SECONDS}. */
    private static void drain(MinecraftServer server) {
        // Anyone who got in anyway (an operator's teleport) is sent out before the level closes under them.
        evictAll(server);
        ServerLevel safari = SafariDimensions.level(server);
        boolean drained = safari == null
                || safari.getChunkSource().getLoadedChunksCount() <= DRAINED_CHUNKS;
        if (!drained && ++seconds < MAX_DRAIN_SECONDS) {
            return;
        }
        phase = Phase.IDLE;
        waited = 0;
        if (SafariLevelSwap.swap(server)) {
            Component line = Component.translatable(CobblemonSafari.MOD_ID + ".message.reset_done")
                    .withStyle(ChatFormatting.GREEN);
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                player.sendSystemMessage(line);
            }
        }
    }

    /** Sends every player in the Safari out, with their unused time given back. */
    private static void evictAll(MinecraftServer server) {
        for (ServerPlayer player : playersInside(server)) {
            SafariSessionManager.evictForReset(player);
        }
    }

    /** A copy of the players in the Safari: sending them out changes the level's list. */
    private static List<ServerPlayer> playersInside(MinecraftServer server) {
        ServerLevel safari = SafariDimensions.level(server);
        return safari == null ? List.of() : new ArrayList<>(safari.players());
    }
}
