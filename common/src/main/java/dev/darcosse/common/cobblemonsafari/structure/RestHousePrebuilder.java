package dev.darcosse.common.cobblemonsafari.structure;

import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import dev.darcosse.common.cobblemonsafari.config.SafariConfigManager;
import dev.darcosse.common.cobblemonsafari.dimension.SafariDimensions;
import dev.darcosse.common.cobblemonsafari.util.Ticks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;

/**
 * Builds the rest houses of a new map in the background, one at a time, so
 * that every house can be reached by the Safari Guide without waiting
 * ({@code restHouseTravel.prebuildHouses}).
 *
 * <p>The entrance house comes first, right after the start and whatever
 * {@code prebuildHouses} says: the server start no longer builds it (it
 * held up the creation of a new map for several seconds), and players
 * arrive in front of it. The other houses follow after
 * {@link #START_DELAY_SECONDS}, when {@code prebuildHouses} is on.
 *
 * <p>How: it keeps the area of the next unbuilt house loaded with a chunk
 * ticket. New chunks are generated off the server thread; once they are
 * loaded, {@link RestHouses#tick} sees the area and builds the house as it
 * would for a player standing there. The ticket goes when the build is over,
 * then the next house gets one.
 *
 * <p>It steps aside while the server is busy: when the average tick takes
 * longer than {@link #PAUSE_TICK_MILLIS}, or for {@link #ENTRY_QUIET_SECONDS}
 * after a player comes into the Safari (the moment the terrain around them
 * is generated), the house being loaded loses its ticket and no other one
 * starts. Its chunks already generated stay on disk; it starts over when
 * the server is calm again. The entrance house is never held back: players
 * arrive in front of it.
 *
 * <p>Server side. Nothing is saved: after a restart it simply carries on
 * with the houses still unbuilt.
 */
public final class RestHousePrebuilder {

    /**
     * Seconds after the server starts before the houses other than the
     * entrance are loaded, so the background work does not add to the
     * start's own (the entrance house, spawn chunks, players logging in).
     */
    private static final int START_DELAY_SECONDS = 30;

    /**
     * Ticks between two looks. The chunks of a house take seconds to
     * generate; looking more often would see nothing new.
     */
    private static final int CHECK_INTERVAL_TICKS = 20;

    /**
     * Longest wait for one house, in seconds, before it is skipped until the
     * next restart. Generating some 50 chunks takes a few seconds on an idle
     * server, more under load; a house that takes longer has a real problem
     * (a missing template) and must not hold up the others.
     */
    private static final int MAX_SECONDS_PER_HOUSE = 180;

    /**
     * Extra life of a ticket after {@link #MAX_SECONDS_PER_HOUSE}, in
     * seconds: its own timeout, a safety net should it be forgotten.
     */
    private static final int TICKET_SPARE_SECONDS = 30;

    /**
     * Average tick time, in milliseconds, above which the background houses
     * pause. A tick has 50 ms; at 40 the server is close to falling behind,
     * and generating a house's chunks would push it over. Lower pauses more
     * often (and the houses take longer to all stand).
     */
    private static final long PAUSE_TICK_MILLIS = 40;

    /**
     * Average tick time, in milliseconds, under which a pause may end. Below
     * {@link #PAUSE_TICK_MILLIS}, so that the load the houses themselves add
     * does not flip the pause on and off every second.
     */
    private static final long RESUME_TICK_MILLIS = 30;

    /**
     * Seconds the background houses wait after a player comes into the
     * Safari: the terrain around the newcomer is being generated, and
     * sharing the generation threads with a house slows it down where the
     * player is looking.
     */
    private static final int ENTRY_QUIET_SECONDS = 20;

    /** Shortest pause, in seconds, so a load that hovers around the threshold does not drop and retake a ticket each second. */
    private static final int MIN_PAUSE_SECONDS = 10;

    /** Nanoseconds in a millisecond, to read the server's tick time. */
    private static final long NANOS_PER_MILLI = 1_000_000L;

    /** Ticket keeping the house being prebuilt loaded. */
    private static final TicketType<ChunkPos> TICKET = TicketType.create(CobblemonSafari.MOD_ID + "_prebuild",
            Comparator.comparingLong(ChunkPos::toLong),
            Ticks.fromSeconds(MAX_SECONDS_PER_HOUSE + TICKET_SPARE_SECONDS));

    /** Anchor of the house being prebuilt, or -1. */
    private static int anchor = -1;
    /** Centre chunk of its ticket, while {@link #anchor} is set. */
    @Nullable
    private static ChunkPos ticket;
    /** Server tick its ticket was added. */
    private static int startedAt;
    /** Anchors given up on during this run. */
    private static final Set<Integer> SKIPPED = new HashSet<>();
    private static int ticks;
    /** Whether the background houses are paused (the entrance never is). */
    private static boolean paused;
    /** Server tick the current pause began. */
    private static int pausedAt;
    /** Server tick a player last came into the Safari, or a value far in the past. */
    private static int lastEntryAt = Integer.MIN_VALUE / 2;
    /** Players in the Safari at the last look, to notice someone coming in. */
    private static int lastPlayerCount;

    private RestHousePrebuilder() {
    }

    /** Forgets everything; for a server start, as single player reuses this class. */
    public static void reset() {
        anchor = -1;
        ticket = null;
        startedAt = 0;
        SKIPPED.clear();
        ticks = 0;
        paused = false;
        pausedAt = 0;
        lastEntryAt = Integer.MIN_VALUE / 2;
        lastPlayerCount = 0;
    }

    /**
     * Moves the background building along. Called each server tick. Cost: a
     * counter increment per tick; once a second, a scan of the ten houses,
     * a read of the server's average tick time and, while a house is
     * loading, at most one ticket change.
     */
    public static void tick(MinecraftServer server) {
        if (++ticks < CHECK_INTERVAL_TICKS) {
            return;
        }
        ticks = 0;
        ServerLevel safari = SafariDimensions.level(server);
        if (safari == null) {
            return;
        }
        boolean busy = updatePause(server, safari);
        if (anchor >= 0) {
            follow(server, safari, busy);
            return;
        }
        // The entrance first, at once and even with prebuilding off: see the class comment.
        for (RestHouses.HouseSite site : RestHouses.sites()) {
            if (site.entrance() && !site.built() && !SKIPPED.contains(site.anchor())) {
                start(server, safari, site);
                return;
            }
        }
        if (!SafariConfigManager.get().restHouseTravel.prebuildHouses
                || server.getTickCount() < Ticks.fromSeconds(START_DELAY_SECONDS) || busy) {
            return;
        }
        for (RestHouses.HouseSite site : RestHouses.sites()) {
            if (!site.built() && !SKIPPED.contains(site.anchor())) {
                start(server, safari, site);
                return;
            }
        }
    }

    /**
     * Updates the pause and tells whether the background houses must wait:
     * a player came in recently, or the server is slow (with the hysteresis
     * of {@link #RESUME_TICK_MILLIS} and {@link #MIN_PAUSE_SECONDS}).
     */
    private static boolean updatePause(MinecraftServer server, ServerLevel safari) {
        int now = server.getTickCount();
        int players = safari.players().size();
        // More players than a second ago: someone came in (a few leaving at once may hide one, which only costs a pause).
        if (players > lastPlayerCount) {
            lastEntryAt = now;
        }
        lastPlayerCount = players;
        long tickMillis = server.getAverageTickTimeNanos() / NANOS_PER_MILLI;
        boolean entering = now - lastEntryAt < Ticks.fromSeconds(ENTRY_QUIET_SECONDS);
        if (!paused && (entering || tickMillis > PAUSE_TICK_MILLIS)) {
            paused = true;
            pausedAt = now;
            CobblemonSafari.LOGGER.debug("Background rest house building paused ({} ms per tick, player entering: {})",
                    tickMillis, entering);
        } else if (paused && !entering && tickMillis < RESUME_TICK_MILLIS
                && now - pausedAt >= Ticks.fromSeconds(MIN_PAUSE_SECONDS)) {
            paused = false;
            CobblemonSafari.LOGGER.debug("Background rest house building resumed ({} ms per tick)", tickMillis);
        }
        return paused;
    }

    /** Starts loading a house's area. */
    private static void start(MinecraftServer server, ServerLevel safari, RestHouses.HouseSite site) {
        anchor = site.anchor();
        startedAt = server.getTickCount();
        hold(safari, chunkOf(site));
    }

    /**
     * Watches the house being prebuilt: done once it stands and no build is
     * running (its cut trees are removed after it is marked built), skipped
     * when it takes too long, and its ticket moved when the build found the
     * spot wet and took the next candidate. Prebuilding switched off in the
     * config stops it, unless it is the entrance.
     */
    private static void follow(MinecraftServer server, ServerLevel safari, boolean busy) {
        RestHouses.HouseSite site = RestHouses.siteOf(anchor);
        if (site == null || (!site.entrance() && !SafariConfigManager.get().restHouseTravel.prebuildHouses)) {
            release(safari);
            return;
        }
        // Not counted as a failure: the house starts over, with a fresh time limit, once the server is calm.
        if (busy && !site.entrance()) {
            release(safari);
            return;
        }
        if (site.built() && !RestHouses.isBuilding()) {
            release(safari);
            return;
        }
        if (server.getTickCount() - startedAt > Ticks.fromSeconds(MAX_SECONDS_PER_HOUSE)) {
            CobblemonSafari.LOGGER.warn("Rest house {} was not built within {} s in the background; skipped until "
                    + "the next restart (a player going there still builds it)", anchor, MAX_SECONDS_PER_HOUSE);
            SKIPPED.add(anchor);
            release(safari);
            return;
        }
        ChunkPos centre = chunkOf(site);
        if (!centre.equals(ticket)) {
            hold(safari, centre);
        }
    }

    /** Puts the ticket on a chunk, removing the previous one. */
    private static void hold(ServerLevel safari, ChunkPos centre) {
        if (ticket != null) {
            safari.getChunkSource().removeRegionTicket(TICKET, ticket, RestHouses.AREA_TICKET_RADIUS, ticket);
        }
        ticket = centre;
        safari.getChunkSource().addRegionTicket(TICKET, centre, RestHouses.AREA_TICKET_RADIUS, centre);
    }

    /** Removes the ticket, if any, and forgets the house. */
    private static void release(ServerLevel safari) {
        if (ticket != null) {
            safari.getChunkSource().removeRegionTicket(TICKET, ticket, RestHouses.AREA_TICKET_RADIUS, ticket);
        }
        ticket = null;
        anchor = -1;
    }

    /** Centre chunk of a house's ticket: the house, or the middle of its chamber underground. */
    private static ChunkPos chunkOf(RestHouses.HouseSite site) {
        return site.ticketCentre();
    }
}
