package dev.darcosse.common.cobblemonsafari.structure;

import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import dev.darcosse.common.cobblemonsafari.config.SafariConfigManager;
import dev.darcosse.common.cobblemonsafari.dimension.SafariDimensions;
import dev.darcosse.common.cobblemonsafari.util.Ticks;
import net.minecraft.core.BlockPos;
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
 * <p>How: it keeps the area of the next unbuilt house loaded with a chunk
 * ticket. New chunks are generated off the server thread; once they are
 * loaded, {@link RestHouses#tick} sees the area and builds the house as it
 * would for a player standing there. The ticket goes when the build is over,
 * then the next house gets one.
 *
 * <p>Server side. Nothing is saved: after a restart it simply carries on
 * with the houses still unbuilt.
 */
public final class RestHousePrebuilder {

    /**
     * Seconds after the server starts before the first house is loaded, so
     * the background work does not add to the start's own (entrance house,
     * spawn chunks, players logging in).
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

    private RestHousePrebuilder() {
    }

    /** Forgets everything; for a server start, as single player reuses this class. */
    public static void reset() {
        anchor = -1;
        ticket = null;
        startedAt = 0;
        SKIPPED.clear();
        ticks = 0;
    }

    /**
     * Moves the background building along. Called each server tick. Cost: a
     * counter increment per tick; once a second, a scan of the eight houses
     * and, while a house is loading, at most one ticket change.
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
        if (!SafariConfigManager.get().restHouseTravel.prebuildHouses) {
            release(safari);
            return;
        }
        if (server.getTickCount() < Ticks.fromSeconds(START_DELAY_SECONDS)) {
            return;
        }
        if (anchor >= 0) {
            follow(server, safari);
            return;
        }
        for (RestHouses.HouseSite site : RestHouses.sites()) {
            if (!site.built() && !SKIPPED.contains(site.anchor())) {
                anchor = site.anchor();
                startedAt = server.getTickCount();
                hold(safari, chunkOf(site));
                return;
            }
        }
    }

    /**
     * Watches the house being prebuilt: done once it stands and no build is
     * running (its cut trees are removed after it is marked built), skipped
     * when it takes too long, and its ticket moved when the build found the
     * spot wet and took the next candidate.
     */
    private static void follow(MinecraftServer server, ServerLevel safari) {
        RestHouses.HouseSite site = RestHouses.siteOf(anchor);
        if (site == null) {
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

    /** Centre chunk of a house. */
    private static ChunkPos chunkOf(RestHouses.HouseSite site) {
        return new ChunkPos(new BlockPos(site.x(), 0, site.z()));
    }
}
