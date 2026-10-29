package dev.darcosse.common.cobblemonsafari.teleport;

import dev.darcosse.common.cobblemonsafari.stats.SafariStatistics;
import com.cobblemon.mod.common.battles.BattleRegistry;
import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import dev.darcosse.common.cobblemonsafari.config.SafariConfig;
import dev.darcosse.common.cobblemonsafari.config.SafariConfigManager;
import dev.darcosse.common.cobblemonsafari.config.TravelCinematicStyle;
import dev.darcosse.common.cobblemonsafari.dimension.SafariDimensions;
import dev.darcosse.common.cobblemonsafari.network.SafariTravelPayload;
import dev.darcosse.common.cobblemonsafari.platform.Platform;
import dev.darcosse.common.cobblemonsafari.structure.Compass;
import dev.darcosse.common.cobblemonsafari.structure.RestHouseStaff;
import dev.darcosse.common.cobblemonsafari.structure.RestHouses;
import dev.darcosse.common.cobblemonsafari.util.LoadedChunks;
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
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
 * <p>The guide's trips are filmed by default
 * ({@code restHouseTravel.cinematicStyle}: FLIGHT): a double of the player
 * takes off on their own flying Pokémon (a Staraptor when they have none)
 * in front of the house ({@link TravelFlight}),
 * the screen goes black while the destination gets ready, then the double
 * lands in front of the destination, where the player already stands,
 * invisible, and takes their place. The Escape Rope's trips are filmed the
 * same way, taking off where the rope was used (after a climb to the open
 * air, under the black, when used in a cave); its pixel-art scene stays for
 * when the flight cannot be staged.
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

    /**
     * Ticks of black after the rope brought the player out of a cave, before
     * the take-off is filmed: time for the client to receive the surroundings
     * of the new spot, a dozen blocks away at most.
     */
    private static final int SURFACE_TICKS = 20;

    /**
     * Ticks between the player's move to the destination and the filming of
     * the landing, under the black screen: time for the destination's chunks
     * to reach their client. Shorter shows the landing over missing terrain.
     */
    private static final int SETTLE_TICKS = 30;

    /**
     * Ticks before the end of the take-off at which the client is told to
     * fade to black. Matches the client's fade (TravelCinematicScreen
     * SHOT_FADE_MS), so the bird leaves the frame as the black closes.
     */
    private static final int BLACK_LEAD_TICKS = 12;

    /** Ticket keeping a destination loaded; one per trip, keyed by its chunk. */
    private static final TicketType<ChunkPos> TICKET = TicketType.create(CobblemonSafari.MOD_ID + "_travel",
            Comparator.comparingLong(ChunkPos::toLong), Ticks.fromSeconds(MAX_WAIT_SECONDS + TICKET_SPARE_SECONDS));

    /**
     * Blocks around a house's square within which a player counts as being
     * at that house, so its guide does not offer the house the player
     * stands in. Covers the steps and the plaza.
     */
    private static final int HOUSE_MARGIN = 2;

    /** Where a trip stands. Painted cinematics go straight to {@link #WAITING}. */
    private enum Phase {
        /**
         * Escape Rope used under cover: the screen is black while the player,
         * brought to the open air, waits for the take-off to be filmed there.
         */
        SURFACING,
        /** The double takes off where the player stands: the house's steps, or where the rope was used. */
        DEPARTING,
        /** Black screen (or painted scene) while the destination loads and builds. */
        WAITING,
        /** The player stands at the destination; its chunks reach their client before the landing is filmed. */
        SETTLING,
        /** The double lands in front of the destination, where the player stands invisible. */
        LANDING
    }

    /**
     * A trip under way. Mutable: it moves through its {@link Phase}s, and its
     * ticket follows the house when a build finds its spot wet and takes the
     * next candidate.
     */
    private static final class Trip {
        /** The destination house, by its anchor. */
        final int anchor;
        /** Server tick it began. */
        final int startTick;
        /** What the source adds once the player is moved (the rope's cooldown). */
        final Consumer<ServerPlayer> arrived;
        /** Whether this trip plays the flight; false for the painted scenes. */
        final boolean flying;
        /** Horizontal unit vector from the house left to the destination: where the double flies. */
        final Vec3 heading;
        /** Centre chunk of its ticket, to remove it. */
        ChunkPos ticket;
        Phase phase;
        /** Server tick the current phase began. */
        int phaseTick;
        /** The actors of the take-off or landing under way, or null. */
        @Nullable
        TravelFlight.Flight flight;
        /** Where the double takes off once the player has climbed out of a cave ({@link Phase#SURFACING}). */
        @Nullable
        BlockPos takeOffFeet;
        /** Where the player was put down at the destination; set from {@link Phase#SETTLING} on. */
        @Nullable
        BlockPos landingFeet;

        /**
         * @param flight      the take-off already staged, or null
         * @param takeOffFeet where the take-off is to be staged after the climb out of a cave, or null
         */
        Trip(int anchor, int startTick, ChunkPos ticket, Consumer<ServerPlayer> arrived,
             @Nullable TravelFlight.Flight flight, @Nullable BlockPos takeOffFeet, Vec3 heading) {
            this.anchor = anchor;
            this.startTick = startTick;
            this.ticket = ticket;
            this.arrived = arrived;
            this.flight = flight;
            this.takeOffFeet = takeOffFeet;
            this.flying = flight != null || takeOffFeet != null;
            this.heading = heading;
            this.phase = flight != null ? Phase.DEPARTING : takeOffFeet != null ? Phase.SURFACING : Phase.WAITING;
            this.phaseTick = startTick;
        }

        /** Moves to the next phase from this tick. */
        void enter(Phase next, int now) {
            phase = next;
            phaseTick = now;
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
        // The actors of a previous world's trips, if any, are swept as orphans.
        TRIPS.clear();
        BIOMES.clear();
        TravelSkins.reset();
        TravelFlight.reset();
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
        start(player, target, Source.GUIDE, ignored -> {
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
        start(player, target, Source.ROPE, arrived);
        return true;
    }

    // ---- Trips ------------------------------------------------------------

    /**
     * Starts a trip: loads the destination, stages the take-off when the trip
     * is filmed, and opens the cinematic. Cost: one ticket added (the chunks
     * load over the next ticks, generated off the server thread when new);
     * for a flight, a few heightmap and block reads and two entities spawned,
     * plus, for the rope, the search for an open-air spot nearby.
     */
    private static void start(ServerPlayer player, RestHouses.HouseSite target, Source source,
                              Consumer<ServerPlayer> arrived) {
        ServerLevel level = (ServerLevel) player.level();
        int now = player.getServer().getTickCount();
        ChunkPos centre = new ChunkPos(new BlockPos(target.x(), 0, target.z()));
        level.getChunkSource().addRegionTicket(TICKET, centre, TICKET_RADIUS, centre);
        Vec3 heading = headingTo(player, target);
        TakeOff plan = planTakeOff(level, player, source, heading);
        TravelFlight.Flight flight = null;
        BlockPos climbTo = null;
        if (plan != null && plan.climb()) {
            climbTo = plan.feet();
        } else if (plan != null) {
            flight = TravelFlight.takeOff(level, player, plan.feet(), heading, now);
        }
        Trip trip = new Trip(target.anchor(), now, centre, arrived, flight, climbTo, heading);
        TRIPS.put(player.getUUID(), trip);
        Label label = labelOf(level, target);
        // The client closes the cinematic by itself a little after the server would give up.
        int clientTimeout = Ticks.fromSeconds(MAX_WAIT_SECONDS + TICKET_SPARE_SECONDS)
                + (trip.flying ? SURFACE_TICKS + TravelFlight.FLIGHT_TICKS * 2 + SETTLE_TICKS : 0);
        SafariTravelPayload payload;
        if (flight != null) {
            payload = SafariTravelPayload.startFlight(label.biomeKey(), label.directionKey(), clientTimeout,
                    flight.bird().getId(), flight.camera(), flight.look());
        } else if (climbTo != null) {
            // Opens on black: the climb out of the cave happens under it.
            payload = SafariTravelPayload.startFlight(label.biomeKey(), label.directionKey(), clientTimeout,
                    SafariTravelPayload.NO_ACTOR, Vec3.ZERO, Vec3.ZERO);
        } else {
            SafariTravelPayload.Scene scene = source == Source.ROPE ? SafariTravelPayload.Scene.CAVE
                    : SafariTravelPayload.Scene.LANDSCAPE;
            payload = SafariTravelPayload.start(scene, label.biomeKey(), label.directionKey(), clientTimeout);
        }
        Platform.get().sendToPlayer(player, payload);
        if (trip.flying) {
            hide(player);
        }
        if (climbTo != null) {
            // After the payload, so the client is already black when it moves.
            SafariTeleporter.sendToFeet(player, level, climbTo);
        }
    }

    /**
     * Where a filmed trip takes off.
     *
     * @param feet  the take-off spot
     * @param climb true when the player must first be brought there (the rope used in a cave)
     */
    private record TakeOff(BlockPos feet, boolean climb) {
    }

    /**
     * Where the trip's take-off is filmed, or null when the trip is shown as
     * a painted scene instead: the config asks for it, there is no mount at
     * all (no flyer in the party and no Staraptor), the double's NPC class is
     * missing, or the mount has no open sky there.
     *
     * <p>The guide's trips take off from the house's steps. The rope's take
     * off where it was used, in the open air; under cover (a cave, a canopy)
     * from the best open-air spot within
     * {@code SafariTeleporter.STANDING_SEARCH_RADIUS} blocks around, the one
     * the rope's own climb would pick, the player being brought there under
     * the black screen. With no such spot the rope's trip keeps its pixel-art
     * scene and the player leaves from where they stand.
     */
    @Nullable
    private static TakeOff planTakeOff(ServerLevel level, ServerPlayer player, Source source, Vec3 heading) {
        if (config().cinematicStyle != TravelCinematicStyle.FLIGHT || !TravelFlight.available(player)) {
            return null;
        }
        BlockPos feet;
        boolean climb = false;
        if (source == Source.GUIDE) {
            RestHouses.HouseSite here = RestHouses.siteAt(player.getBlockX(), player.getBlockZ(), HOUSE_MARGIN);
            feet = here == null ? null : SafariTeleporter.arrivalFeet(level, RestHouses.arrivalOf(here));
        } else if (player.onGround() && level.canSeeSky(BlockPos.containing(player.getEyePosition()))) {
            feet = player.blockPosition();
        } else {
            // Under cover, swimming or in the air: the nearest good open-air spot.
            feet = SafariTeleporter.findStandingSpotNear(level, player.getBlockX(), player.getBlockZ());
            climb = feet != null && !feet.equals(player.blockPosition());
        }
        if (feet == null || !TravelFlight.canTakeOff(level, feet, heading)) {
            return null;
        }
        return new TakeOff(feet, climb);
    }

    /** Horizontal unit vector from the player toward the destination; south when they stand on it. */
    private static Vec3 headingTo(ServerPlayer player, RestHouses.HouseSite target) {
        Vec3 heading = new Vec3(target.x() + 0.5D - player.getX(), 0.0D, target.z() + 0.5D - player.getZ());
        return heading.lengthSqr() < 1.0E-6D ? new Vec3(0.0D, 0.0D, 1.0D) : heading.normalize();
    }

    /**
     * Plays the trips: films the flights, moves the players whose
     * destination is ready, gives up the trips that cannot end, and sweeps
     * orphan actors. Called each server tick. Cost: nothing without a trip
     * but the sweep (one pass over the Safari's entities every 5 s); per
     * trip, a player lookup and a battle lookup, plus during a flight two
     * entity moves, and once the cinematic has run long enough a house
     * lookup and a loaded-chunk check.
     */
    public static void tick(MinecraftServer server) {
        ServerLevel safari = SafariDimensions.level(server);
        TravelFlight.sweep(safari, activeActors());
        if (TRIPS.isEmpty()) {
            return;
        }
        int now = server.getTickCount();
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
            if (trip.flying) {
                // Again each tick: a potion effect ending would show the player.
                player.setInvisible(true);
            }
            if (!advance(safari, player, trip, now)) {
                it.remove();
            }
        }
    }

    /**
     * Plays one tick of a trip's current phase.
     *
     * @return false when the trip is over (ended here)
     */
    private static boolean advance(ServerLevel safari, ServerPlayer player, Trip trip, int now) {
        switch (trip.phase) {
            case SURFACING -> {
                if (now - trip.phaseTick < SURFACE_TICKS) {
                    return true;
                }
                TravelFlight.Flight flight = trip.takeOffFeet == null ? null
                        : TravelFlight.takeOff(safari, player, trip.takeOffFeet, trip.heading, now);
                if (flight == null) {
                    // The spot changed since the plan (a block placed, a mob in the way): no
                    // take-off, the black simply lasts until the landing.
                    trip.enter(Phase.WAITING, now);
                    return true;
                }
                trip.flight = flight;
                trip.enter(Phase.DEPARTING, now);
                Platform.get().sendToPlayer(player, SafariTravelPayload.shot(flight.bird().getId(),
                        flight.camera(), flight.look()));
                return true;
            }
            case DEPARTING -> {
                TravelFlight.Flight flight = trip.flight;
                boolean done = flight == null || TravelFlight.tick(flight, now);
                // Black a little before the bird is gone, so the fade ends as it does.
                if (now - trip.phaseTick == TravelFlight.FLIGHT_TICKS - BLACK_LEAD_TICKS) {
                    Platform.get().sendToPlayer(player, SafariTravelPayload.black());
                }
                if (done) {
                    dropFlight(trip);
                    trip.enter(Phase.WAITING, now);
                }
                return true;
            }
            case WAITING -> {
                return waitForDestination(safari, player, trip, now);
            }
            case SETTLING -> {
                if (now - trip.phaseTick < SETTLE_TICKS) {
                    return true;
                }
                TravelFlight.Flight landing = trip.landingFeet == null ? null
                        : TravelFlight.land(safari, player, trip.landingFeet, trip.heading, now);
                if (landing == null) {
                    // No open sky at the destination: straight from black to the player's eyes.
                    end(safari, trip, player, null);
                    return false;
                }
                trip.flight = landing;
                trip.enter(Phase.LANDING, now);
                Platform.get().sendToPlayer(player, SafariTravelPayload.shot(landing.bird().getId(),
                        landing.camera(), landing.look()));
                return true;
            }
            case LANDING -> {
                if (trip.flight != null && !TravelFlight.tick(trip.flight, now)) {
                    return true;
                }
                end(safari, trip, player, null);
                return false;
            }
        }
        return true;
    }

    /**
     * Waits until the destination stands and the cinematic has run long
     * enough, then moves the player there.
     *
     * @return false when the trip is over (ended here)
     */
    private static boolean waitForDestination(ServerLevel safari, ServerPlayer player, Trip trip, int now) {
        int elapsed = now - trip.startTick;
        if (elapsed > Ticks.fromSeconds(MAX_WAIT_SECONDS)
                + (trip.flying ? TravelFlight.FLIGHT_TICKS : 0)) {
            CobblemonSafari.LOGGER.warn("Trip of {} to rest house {} given up after {} s: the house never got ready",
                    player.getGameProfile().getName(), trip.anchor, MAX_WAIT_SECONDS);
            end(safari, trip, player, "message.guide_unreachable");
            return false;
        }
        if (elapsed < Ticks.fromSeconds(config().cinematicSeconds)) {
            return true;
        }
        RestHouses.HouseSite site = RestHouses.siteOf(trip.anchor);
        if (site == null) {
            return true;
        }
        // A wet spot moved the house to its next candidate, maybe beyond
        // the ticket: follow it, or the new spot never loads and the trip
        // waits until it gives up.
        ChunkPos siteChunk = new ChunkPos(new BlockPos(site.x(), 0, site.z()));
        if (!siteChunk.equals(trip.ticket)) {
            safari.getChunkSource().removeRegionTicket(TICKET, trip.ticket, TICKET_RADIUS, trip.ticket);
            safari.getChunkSource().addRegionTicket(TICKET, siteChunk, TICKET_RADIUS, siteChunk);
            trip.ticket = siteChunk;
        }
        if (!site.built()) {
            return true;
        }
        RestHouses.Arrival arrival = RestHouses.arrivalOf(site);
        // Not hasChunkAt: it is true as soon as this trip's ticket exists, while the
        // chunk may still be generating, and the arrival reads would then wait for it.
        if (!LoadedChunks.isReady(safari, arrival.column())) {
            return true;
        }
        BlockPos feet = SafariTeleporter.arrivalFeet(safari, arrival);
        if (feet == null || !SafariTeleporter.sendToArrival(player, safari, arrival)) {
            end(safari, trip, player, "message.guide_unreachable");
            return false;
        }
        markTravelled(player);
        SafariStatistics.increment(player, SafariStatistics.REST_HOUSE_TRIPS);
        trip.arrived.accept(player);
        if (trip.flying) {
            // Invisible where the double will land: the swap at the end shows nothing.
            trip.landingFeet = feet;
            trip.enter(Phase.SETTLING, now);
            return true;
        }
        safari.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.CHORUS_FRUIT_TELEPORT, SoundSource.PLAYERS, 1.0F, 1.0F);
        end(safari, trip, player, null);
        return false;
    }

    /**
     * Ends a trip: removes its ticket and its actors, shows the player
     * again, closes their cinematic and tells them why when it failed. The
     * player now there keeps the chunks loaded on their own.
     *
     * @param reason translation key (without the mod prefix) of the failure, or null
     */
    private static void end(@Nullable ServerLevel safari, Trip trip, @Nullable ServerPlayer player,
                            @Nullable String reason) {
        if (safari != null) {
            safari.getChunkSource().removeRegionTicket(TICKET, trip.ticket, TICKET_RADIUS, trip.ticket);
        }
        dropFlight(trip);
        if (player == null) {
            return;
        }
        if (trip.flying) {
            show(player);
        }
        Platform.get().sendToPlayer(player, SafariTravelPayload.end());
        if (reason != null) {
            player.displayClientMessage(Component.translatable(key(reason)), true);
        }
    }

    /** Removes the actors of the trip's take-off or landing, if any. */
    private static void dropFlight(Trip trip) {
        if (trip.flight != null) {
            TravelFlight.discard(trip.flight);
            trip.flight = null;
        }
    }

    /**
     * Hides the player while their double flies. Their armour and held
     * items still show to others, as for any invisible player: hiding those
     * would take a client change on every viewer.
     */
    private static void hide(ServerPlayer player) {
        player.setInvisible(true);
    }

    /** Shows the player again, unless an invisibility effect of their own keeps them hidden. */
    private static void show(ServerPlayer player) {
        player.setInvisible(player.hasEffect(MobEffects.INVISIBILITY));
    }

    /** The UUIDs of the actors of every flight under way, which the sweep must spare. */
    private static Set<UUID> activeActors() {
        if (TRIPS.isEmpty()) {
            return Set.of();
        }
        Set<UUID> actors = new HashSet<>();
        for (Trip trip : TRIPS.values()) {
            if (trip.flight != null) {
                actors.add(trip.flight.bird().getUUID());
                actors.add(trip.flight.rider().getUUID());
            }
        }
        return actors;
    }

    /**
     * Whether damage must be cancelled: to a player on a trip (they cannot
     * see nor move behind the cinematic) and to the flight's actors. All of
     * it but {@code /kill} and the void, like the rest house staff.
     */
    public static boolean shouldCancelDamage(Entity entity, DamageSource source) {
        if (source.is(DamageTypes.GENERIC_KILL) || source.is(DamageTypes.FELL_OUT_OF_WORLD)) {
            return false;
        }
        return entity.getTags().contains(TravelFlight.ACTOR_TAG)
                || entity instanceof ServerPlayer player && isTravelling(player);
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
