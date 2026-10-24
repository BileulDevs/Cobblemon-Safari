package dev.darcosse.common.cobblemonsafari.teleport;

import com.cobblemon.mod.common.battles.BattleRegistry;
import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import dev.darcosse.common.cobblemonsafari.config.SafariConfig;
import dev.darcosse.common.cobblemonsafari.config.SafariConfigManager;
import dev.darcosse.common.cobblemonsafari.dimension.SafariDimensions;
import dev.darcosse.common.cobblemonsafari.network.SafariTravelPayload;
import dev.darcosse.common.cobblemonsafari.platform.Platform;
import dev.darcosse.common.cobblemonsafari.structure.Compass;
import dev.darcosse.common.cobblemonsafari.structure.RestHouseStaff;
import dev.darcosse.common.cobblemonsafari.structure.RestHouses;
import dev.darcosse.common.cobblemonsafari.util.Ticks;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Trips to a rest house, by the Safari Guide of a house or by the Escape
 * Rope's "nearest rest house": every house of the map can be reached, built
 * or not yet.
 *
 * <p>A trip is not instant. It loads the destination's area with a chunk
 * ticket, which also makes a planned house build itself (RestHouses builds a
 * house once its area is loaded), and the player watches a short loading
 * cinematic meanwhile ({@link SafariTravelPayload}, client side
 * TravelCinematicScreen). The player is moved once the house stands, its
 * arrival is loaded and the cinematic has run for
 * {@code restHouseTravel.cinematicSeconds}; the cinematic then fades out on
 * the new place. A trip follows the house's anchor, not a position: a build
 * that finds its spot wet moves the house to its next candidate.
 *
 * <p>One wait between two trips, whichever way they go
 * ({@code restHouseTravel.cooldownSeconds}): with two, a player could rope to
 * a house and take its guide straight on.
 *
 * <p>Server side. Waits and trips are kept in memory only: a restart forgets
 * them (a player on their way is simply not moved, and their client closes
 * the cinematic on its own, see {@link SafariTravelPayload#start}).
 */
public final class RestHouseTravel {

    /** Who sends the player: decides which switch of the config applies. */
    public enum Source {
        /** The Safari Guide of a rest house ({@code restHouseTravel.guideEnabled}). */
        GUIDE,
        /** The Escape Rope ({@code restHouseTravel.ropeEnabled}). */
        ROPE
    }

    /**
     * Longest wait for the destination, in seconds, before the trip is given
     * up. Covers generating some 30 chunks and the few ticks of a build, with
     * other builds queued first; a trip that waits longer has a real problem
     * (a missing template, an unloadable area).
     */
    private static final int MAX_WAIT_SECONDS = 45;

    /** Radius of the destination's chunk ticket; see {@link RestHouses#AREA_TICKET_RADIUS}. */
    private static final int TICKET_RADIUS = RestHouses.AREA_TICKET_RADIUS;

    /**
     * Extra life of a ticket after {@link #MAX_WAIT_SECONDS}, in seconds:
     * the ticket's own timeout, a safety net should a trip be forgotten
     * without its ticket being removed (it is removed by hand otherwise).
     */
    private static final int TICKET_SPARE_SECONDS = 15;

    /** Ticket keeping a destination loaded; one per trip, keyed by its chunk. */
    private static final TicketType<ChunkPos> TICKET = TicketType.create(CobblemonSafari.MOD_ID + "_travel",
            Comparator.comparingLong(ChunkPos::toLong), Ticks.fromSeconds(MAX_WAIT_SECONDS + TICKET_SPARE_SECONDS));

    /**
     * Blocks around a house's square within which a player counts as being
     * at that house, so its guide does not offer the house the player
     * stands in. Covers the steps and the plaza.
     */
    private static final int HOUSE_MARGIN = 2;

    /**
     * A trip under way.
     *
     * @param anchor    the destination house, by its anchor
     * @param startTick server tick it began
     * @param ticket    centre chunk of its ticket, to remove it; moved with the house when a
     *                  build finds its spot wet and takes the next candidate
     * @param arrived   what the source adds once the player is moved (the rope's cooldown)
     */
    private record Trip(int anchor, int startTick, ChunkPos ticket, Consumer<ServerPlayer> arrived) {

        /** The same trip with its ticket at another chunk. */
        Trip withTicket(ChunkPos moved) {
            return new Trip(anchor, startTick, moved, arrived);
        }
    }

    /**
     * The words naming a house: its biome and, when another house shares
     * that biome, its compass direction too. Translation keys, so each
     * player reads them in their own language.
     *
     * @param biomeKey     translation key of the biome
     * @param directionKey translation key of the direction, or "" when the biome is enough
     */
    public record Label(String biomeKey, String directionKey) {

        /** The label as shown in chat and dialogues. */
        public Component component() {
            Component biome = Component.translatable(biomeKey);
            return directionKey.isEmpty() ? biome.copy()
                    : Component.translatable(key("travel.house_with_direction"), biome,
                    Component.translatable(directionKey));
        }
    }

    /** Server tick from which each player may travel again; absent means now. */
    private static final Map<UUID, Integer> READY_AT = new HashMap<>();

    /** Trips under way, by player. A handful at most. */
    private static final Map<UUID, Trip> TRIPS = new LinkedHashMap<>();

    /**
     * Biome of each house spot, by packed column. Reading it from the noise
     * costs a height computation; the spots change once per map at most.
     */
    private static final Map<Long, Optional<ResourceKey<Biome>>> BIOMES = new HashMap<>();

    private RestHouseTravel() {
    }

    /** Forgets waits, trips and biomes; call when a server starts, as single player reuses this class. */
    public static void reset() {
        READY_AT.clear();
        TRIPS.clear();
        BIOMES.clear();
    }

    // ---- Waits ------------------------------------------------------------

    /** Ticks before the player may travel again; 0 when they may now. */
    public static int remainingTicks(ServerPlayer player) {
        int now = player.getServer().getTickCount();
        return Math.max(0, READY_AT.getOrDefault(player.getUUID(), now) - now);
    }

    /** Starts the player's wait after a trip, from the config; nothing when it is 0. */
    private static void markTravelled(ServerPlayer player) {
        int wait = Ticks.fromSeconds(config().cooldownSeconds);
        if (wait > 0) {
            READY_AT.put(player.getUUID(), player.getServer().getTickCount() + wait);
        } else {
            READY_AT.remove(player.getUUID());
        }
    }

    /** Whether a trip of this player is under way. */
    public static boolean isTravelling(ServerPlayer player) {
        return TRIPS.containsKey(player.getUUID());
    }

    // ---- The guide --------------------------------------------------------

    /**
     * Whether the guide can take the player to the house of this direction:
     * the guide is on, there is a house there, and it is not the one the
     * player stands in. What the guide's dialogue uses to show the option.
     */
    public static boolean canTravelTo(ServerPlayer player, Compass direction) {
        if (!config().guideEnabled || !SafariDimensions.isSafari(player.level())) {
            return false;
        }
        RestHouses.HouseSite target = RestHouses.siteIn(direction);
        return target != null && !isCurrentHouse(player, target);
    }

    /**
     * Starts the guide's trip to the house of this direction, if the player
     * stands by a guide, is not in a battle nor already on the way, and their
     * wait is over. Checks again what the dialogue showed: it may have
     * stayed open a while.
     *
     * @return whether a trip started
     */
    public static boolean travelTo(ServerPlayer player, Compass direction) {
        if (!config().guideEnabled || !SafariDimensions.isSafari(player.level())
                || !RestHouseStaff.isNearGuide(player) || isTravelling(player)) {
            return false;
        }
        if (BattleRegistry.getBattleByParticipatingPlayer(player) != null) {
            player.displayClientMessage(Component.translatable(key("message.guide_in_battle")), true);
            return false;
        }
        int remaining = remainingTicks(player);
        if (remaining > 0) {
            player.displayClientMessage(Component.translatable(key("message.guide_wait"), Ticks.format(remaining)),
                    true);
            return false;
        }
        RestHouses.HouseSite target = RestHouses.siteIn(direction);
        if (target == null || isCurrentHouse(player, target)) {
            player.displayClientMessage(Component.translatable(key("message.guide_unreachable")), true);
            return false;
        }
        start(player, target, ignored -> {
        });
        return true;
    }

    // ---- The rope ---------------------------------------------------------

    /** Whether the rope's "nearest rest house" option is on. */
    public static boolean ropeEnabled() {
        return config().ropeEnabled;
    }

    /**
     * Starts the rope's trip to the nearest rest house. The rope checked the
     * Safari and battles itself; this checks the switch, the trip already
     * under way and the wait.
     *
     * @param arrived what the rope does once the player is moved (its cooldown)
     * @return whether a trip started
     */
    public static boolean ropeToNearest(ServerPlayer player, Consumer<ServerPlayer> arrived) {
        if (!ropeEnabled() || isTravelling(player)) {
            return false;
        }
        int remaining = remainingTicks(player);
        if (remaining > 0) {
            player.displayClientMessage(Component.translatable(key("message.rope_rest_house_wait"),
                    Ticks.format(remaining)), true);
            return false;
        }
        RestHouses.HouseSite target = RestHouses.nearestSite(player.getBlockX(), player.getBlockZ());
        if (target == null) {
            player.displayClientMessage(Component.translatable(key("message.rope_no_rest_house")), true);
            return false;
        }
        start(player, target, arrived);
        return true;
    }

    // ---- Trips ------------------------------------------------------------

    /**
     * Starts a trip: loads the destination, opens the cinematic. Cost: one
     * ticket added; the chunks load over the next ticks, generated off the
     * server thread when new.
     */
    private static void start(ServerPlayer player, RestHouses.HouseSite target, Consumer<ServerPlayer> arrived) {
        ServerLevel level = (ServerLevel) player.level();
        ChunkPos centre = new ChunkPos(new BlockPos(target.x(), 0, target.z()));
        level.getChunkSource().addRegionTicket(TICKET, centre, TICKET_RADIUS, centre);
        TRIPS.put(player.getUUID(), new Trip(target.anchor(), player.getServer().getTickCount(), centre, arrived));
        Label label = labelOf(level, target);
        // The client closes the cinematic by itself a little after the server would give up.
        int clientTimeout = Ticks.fromSeconds(MAX_WAIT_SECONDS + TICKET_SPARE_SECONDS);
        Platform.get().sendToPlayer(player, SafariTravelPayload.start(label.biomeKey(), label.directionKey(),
                clientTimeout));
    }

    /**
     * Moves the players whose destination is ready, and gives up the trips
     * that cannot end. Called each server tick. Cost: nothing without a trip;
     * per trip, a player lookup, a battle lookup and, once the cinematic has
     * run long enough, a house lookup and a loaded-chunk check.
     */
    public static void tick(MinecraftServer server) {
        if (TRIPS.isEmpty()) {
            return;
        }
        ServerLevel safari = SafariDimensions.level(server);
        int now = server.getTickCount();
        int minimum = Ticks.fromSeconds(config().cinematicSeconds);
        int giveUp = Ticks.fromSeconds(MAX_WAIT_SECONDS);
        Iterator<Map.Entry<UUID, Trip>> it = TRIPS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Trip> entry = it.next();
            Trip trip = entry.getValue();
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            // Logged out, or sent out of the Safari (session over): nothing to move.
            if (player == null || safari == null || player.level() != safari) {
                end(safari, trip, player, null);
                it.remove();
                continue;
            }
            if (BattleRegistry.getBattleByParticipatingPlayer(player) != null) {
                end(safari, trip, player, "message.guide_in_battle");
                it.remove();
                continue;
            }
            int elapsed = now - trip.startTick();
            if (elapsed > giveUp) {
                CobblemonSafari.LOGGER.warn("Trip of {} to rest house {} given up after {} s: the house never got ready",
                        player.getGameProfile().getName(), trip.anchor(), MAX_WAIT_SECONDS);
                end(safari, trip, player, "message.guide_unreachable");
                it.remove();
                continue;
            }
            if (elapsed < minimum) {
                continue;
            }
            RestHouses.HouseSite site = RestHouses.siteOf(trip.anchor());
            if (site == null) {
                continue;
            }
            // A wet spot moved the house to its next candidate, maybe beyond
            // the ticket: follow it, or the new spot never loads and the trip
            // waits until it gives up.
            ChunkPos siteChunk = new ChunkPos(new BlockPos(site.x(), 0, site.z()));
            if (!siteChunk.equals(trip.ticket())) {
                safari.getChunkSource().removeRegionTicket(TICKET, trip.ticket(), TICKET_RADIUS, trip.ticket());
                safari.getChunkSource().addRegionTicket(TICKET, siteChunk, TICKET_RADIUS, siteChunk);
                trip = trip.withTicket(siteChunk);
                entry.setValue(trip);
            }
            if (!site.built()) {
                continue;
            }
            RestHouses.Arrival arrival = RestHouses.arrivalOf(site);
            if (!safari.hasChunkAt(arrival.column())) {
                continue;
            }
            it.remove();
            if (!SafariTeleporter.sendToArrival(player, safari, arrival)) {
                end(safari, trip, player, "message.guide_unreachable");
                continue;
            }
            markTravelled(player);
            safari.playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.CHORUS_FRUIT_TELEPORT, SoundSource.PLAYERS, 1.0F, 1.0F);
            trip.arrived().accept(player);
            end(safari, trip, player, null);
        }
    }

    /**
     * Ends a trip: removes its ticket, closes the player's cinematic and
     * tells them why when it failed. The player now there keeps the chunks
     * loaded on their own.
     *
     * @param reason translation key (without the mod prefix) of the failure, or null
     */
    private static void end(@Nullable ServerLevel safari, Trip trip, @Nullable ServerPlayer player,
                            @Nullable String reason) {
        if (safari != null) {
            safari.getChunkSource().removeRegionTicket(TICKET, trip.ticket(), TICKET_RADIUS, trip.ticket());
        }
        if (player == null) {
            return;
        }
        Platform.get().sendToPlayer(player, SafariTravelPayload.end());
        if (reason != null) {
            player.displayClientMessage(Component.translatable(key(reason)), true);
        }
    }

    // ---- Names ------------------------------------------------------------

    /**
     * How a house is named: by its biome, plus its direction when another
     * house shares the biome. Read from the generator's noise for a house not
     * built yet, so no chunk is loaded for a name.
     */
    public static Label labelOf(ServerLevel level, RestHouses.HouseSite site) {
        Optional<ResourceKey<Biome>> biome = biomeAt(level, site.x(), site.z());
        String biomeKey = biome.map(key -> Util.makeDescriptionId("biome", key.location()))
                .orElse(key("travel.unknown_biome"));
        boolean shared = false;
        for (RestHouses.HouseSite other : RestHouses.sites()) {
            if (other.anchor() != site.anchor() && biomeAt(level, other.x(), other.z()).equals(biome)) {
                shared = true;
                break;
            }
        }
        String directionKey = shared ? key("compass." + Compass.of(site.x(), site.z()).id()) : "";
        return new Label(biomeKey, directionKey);
    }

    /** The biome at the surface of a column, from the noise when its chunk is not loaded; cached. */
    private static Optional<ResourceKey<Biome>> biomeAt(ServerLevel level, int x, int z) {
        return BIOMES.computeIfAbsent(ChunkPos.asLong(x, z), ignored -> {
            int y = level.getChunkSource().getGenerator().getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG,
                    level, level.getChunkSource().randomState());
            return level.getBiome(new BlockPos(x, y, z)).unwrapKey();
        });
    }

    /** Whether the player stands at this house: its square, steps and plaza. */
    private static boolean isCurrentHouse(ServerPlayer player, RestHouses.HouseSite house) {
        RestHouses.HouseSite here = RestHouses.siteAt(player.getBlockX(), player.getBlockZ(), HOUSE_MARGIN);
        return here != null && here.anchor() == house.anchor();
    }

    /** The travel section of the current config. */
    private static SafariConfig.RestHouseTravel config() {
        return SafariConfigManager.get().restHouseTravel;
    }

    /** Builds a translation key in the mod namespace. */
    private static String key(String path) {
        return CobblemonSafari.MOD_ID + "." + path;
    }
}
