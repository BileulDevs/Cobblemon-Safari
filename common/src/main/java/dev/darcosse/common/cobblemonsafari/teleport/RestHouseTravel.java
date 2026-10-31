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
import dev.darcosse.common.cobblemonsafari.util.SafariCaves;
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
 * <p>A trip into or out of a cave (an underground house, or the rope used in
 * a cave) is filmed as a burrow instead ({@link TravelBurrow}): the double
 * rides a digger into the ground in front of the house it leaves (or where
 * the rope was used) and out of it in front of the destination, since no
 * bird could fly there.
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

    /** Anchor of a trip that goes to a spot rather than a house (the rope's climb to the open air). */
    private static final int NO_ANCHOR = -1;

    /** Where a trip stands. Painted cinematics go straight to {@link #WAITING}. */
    private enum Phase {
        /**
         * Escape Rope used in the water: the double rides a swimmer under
         * the water to the nearest shore (TravelSwim); the player is then
         * brought there under the black, and the trip goes on as a climb.
         */
        SWIMMING,
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
        /** The destination house, by its anchor; {@link #NO_ANCHOR} for a climb to the open air. */
        final int anchor;
        /**
         * Where the player is brought, for a trip that goes to a spot rather
         * than to a house (the rope's climb out of a cave); null for a house.
         */
        @Nullable
        final BlockPos destination;
        /** Server tick it began. */
        final int startTick;
        /** What the source adds once the player is moved (the rope's cooldown). */
        final Consumer<ServerPlayer> arrived;
        /** Whether this trip is filmed (flight or burrow); false for the painted scenes. */
        final boolean flying;
        /**
         * Whether it is filmed as a burrow (TravelBurrow) rather than a
         * flight: the trip goes into or out of a cave, where no bird flies.
         */
        final boolean burrow;
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
        /** The player's rotation there, facing the house: where the digger comes out facing. */
        float landingYaw;
        /** Server tick the swim's fade to black was sent, or -1 before; see {@link Phase#SWIMMING}. */
        int blackTick = -1;

        /**
         * @param flight      the take-off already staged, or null
         * @param takeOffFeet where the take-off is to be staged after the climb out of a cave, or null
         * @param burrow      whether the trip is filmed as a burrow, even when its departure could not be
         */
        Trip(int anchor, int startTick, ChunkPos ticket, Consumer<ServerPlayer> arrived,
             @Nullable TravelFlight.Flight flight, @Nullable BlockPos takeOffFeet, Vec3 heading, boolean burrow,
             @Nullable BlockPos destination) {
            this.anchor = anchor;
            this.destination = destination;
            this.startTick = startTick;
            this.ticket = ticket;
            this.arrived = arrived;
            this.flight = flight;
            this.takeOffFeet = takeOffFeet;
            this.burrow = burrow;
            this.flying = flight != null || takeOffFeet != null || burrow;
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
        return guideTrip(player, RestHouses.siteIn(direction));
    }

    /**
     * Starts the guide's trip to a house, if the player stands by a guide,
     * is not in a battle nor already on the way, and their wait is over.
     *
     * @param target the house, or null when there is none (refused, with a message)
     * @return whether a trip started
     */
    private static boolean guideTrip(ServerPlayer player, @Nullable RestHouses.HouseSite target) {
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
        if (target == null || isCurrentHouse(player, target)) {
            player.displayClientMessage(Component.translatable(key("message.guide_unreachable")), true);
            return false;
        }
        start(player, target, Source.GUIDE, ignored -> {
        });
        return true;
    }

    /**
     * Whether the guide can take the player to the underground house of a
     * cavern ({@code ParkGeometry.CaveHouse.id}): the guide is on, the house
     * exists, and it is not the one the player stands in.
     */
    public static boolean canTravelToCave(ServerPlayer player, String caveId) {
        if (!config().guideEnabled || !SafariDimensions.isSafari(player.level())) {
            return false;
        }
        RestHouses.HouseSite target = RestHouses.caveSite(caveId);
        return target != null && !isCurrentHouse(player, target);
    }

    /**
     * Starts the guide's trip to the underground house of a cavern; same
     * checks as {@link #travelTo}.
     *
     * @return whether a trip started
     */
    public static boolean travelToCave(ServerPlayer player, String caveId) {
        return guideTrip(player, RestHouses.caveSite(caveId));
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
        // Underground houses only from a cave: from the surface above a
        // cavern, the nearest house would otherwise be the one under the feet.
        RestHouses.HouseSite target = RestHouses.nearestSite(player.getBlockX(), player.getBlockZ(),
                SafariCaves.isInCave(player));
        if (target == null) {
            player.displayClientMessage(Component.translatable(key("message.rope_no_rest_house")), true);
            return false;
        }
        start(player, target, Source.ROPE, arrived);
        return true;
    }

    /**
     * Starts the rope's climb out of a cave as a burrow: the digger goes down
     * where the player stands and comes out at the open-air spot above
     * (or the nearest one), instead of the instant move of a climb from
     * under a tree. Only in a cave ({@link SafariCaves}), with the
     * cinematics on and a digger available, and not during another trip.
     * It is not a rest house trip: it starts neither the guide's and rope's
     * shared wait nor the trips statistic.
     *
     * @param arrived what the rope does once the player is out (its cooldown)
     * @return whether the climb started; false leaves the rope to its instant climb
     */
    public static boolean climbOutOfCave(ServerPlayer player, Consumer<ServerPlayer> arrived) {
        if (config().cinematicStyle != TravelCinematicStyle.FLIGHT || isTravelling(player)
                || !SafariCaves.isInCave(player) || !TravelBurrow.available(player)) {
            return false;
        }
        ServerLevel level = (ServerLevel) player.level();
        BlockPos surface = SafariTeleporter.findStandingSpotNear(level, player.getBlockX(), player.getBlockZ());
        if (surface == null) {
            return false;
        }
        int now = player.getServer().getTickCount();
        ChunkPos centre = new ChunkPos(surface);
        level.getChunkSource().addRegionTicket(TICKET, centre, TICKET_RADIUS, centre);
        // The double digs on the way the player looks: the climb has no direction of its own.
        Vec3 heading = Vec3.directionFromRotation(0.0F, player.getYRot());
        TravelFlight.Flight flight = player.onGround()
                ? TravelBurrow.dig(level, player, player.blockPosition(), heading, now) : null;
        Trip trip = new Trip(NO_ANCHOR, now, centre, arrived, flight, null, heading, true, surface);
        TRIPS.put(player.getUUID(), trip);
        int clientTimeout = Ticks.fromSeconds(MAX_WAIT_SECONDS + TICKET_SPARE_SECONDS)
                + TravelBurrow.DIG_TICKS * 2 + SETTLE_TICKS;
        String label = key("travel.surface");
        Platform.get().sendToPlayer(player, flight != null
                ? SafariTravelPayload.startFlight(label, "", clientTimeout, flight.bird().getId(), flight.camera(),
                flight.look())
                : SafariTravelPayload.startFlight(label, "", clientTimeout, SafariTravelPayload.NO_ACTOR,
                Vec3.ZERO, Vec3.ZERO));
        hide(player);
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
        ChunkPos centre = target.ticketCentre();
        level.getChunkSource().addRegionTicket(TICKET, centre, TICKET_RADIUS, centre);
        Vec3 heading = headingTo(player, target);
        TravelFlight.Flight flight = null;
        BlockPos climbTo = null;
        boolean burrow = burrows(player, source, target);
        Swim swim = source == Source.ROPE ? planSwim(level, player, heading, now) : null;
        if (swim != null) {
            // The take-off (or the dig) is staged at the shore once the swim is over.
            flight = swim.flight();
            climbTo = swim.shore();
        } else if (burrow) {
            BlockPos feet = burrowFeet(level, player, source);
            // No spot to dig from (the rope used while swimming or falling): the trip
            // opens on black and the digger still comes out at the destination.
            flight = feet == null ? null : TravelBurrow.dig(level, player, feet, heading, now);
        } else {
            TakeOff plan = planTakeOff(level, player, source, heading);
            if (plan != null && plan.climb()) {
                climbTo = plan.feet();
            } else if (plan != null) {
                flight = TravelFlight.takeOff(level, player, plan.feet(), heading, now);
            }
        }
        Trip trip = new Trip(target.anchor(), now, centre, arrived, flight, climbTo, heading, burrow, null);
        if (swim != null) {
            trip.enter(Phase.SWIMMING, now);
        }
        TRIPS.put(player.getUUID(), trip);
        Label label = labelOf(level, target);
        // The client closes the cinematic by itself a little after the server would give up.
        int clientTimeout = Ticks.fromSeconds(MAX_WAIT_SECONDS + TICKET_SPARE_SECONDS)
                + (trip.flying ? SURFACE_TICKS + TravelFlight.FLIGHT_TICKS * 2 + SETTLE_TICKS : 0)
                + (swim != null ? swim.flight().duration() : 0);
        SafariTravelPayload payload;
        if (flight != null) {
            payload = SafariTravelPayload.startFlight(label.biomeKey(), label.directionKey(), clientTimeout,
                    flight.bird().getId(), flight.camera(), flight.look());
        } else if (climbTo != null || burrow) {
            // Opens on black: the climb out of the cave happens under it, or the burrow could not be filmed here.
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
        if (climbTo != null && swim == null) {
            // After the payload, so the client is already black when it moves.
            SafariTeleporter.sendToFeet(player, level, climbTo);
        }
    }

    /**
     * The swim to the shore, staged, and the shore it reaches.
     *
     * @param flight the swimmer and the double, already in the water
     * @param shore  the feet the player is brought to once the swim is over; null in open water,
     *               where the player stays put, hidden, until moved to the destination
     */
    private record Swim(TravelFlight.Flight flight, @Nullable BlockPos shore) {
    }

    /**
     * The rope's swim to the shore when the player is in the water, at the
     * surface or deep down, or null when the trip starts as usual: not in
     * the water, cinematics off, no swimmer, or no camera spot. The shore is
     * the nearest bank within 32 blocks, else the first land met swimming
     * toward the destination (loaded chunks only); with neither, the swimmer
     * heads off toward the destination all the same.
     * Cost: the shore search (rings of a few dozen columns, loading their
     * chunks once, then a line of columns) and two entities spawned.
     */
    @Nullable
    private static Swim planSwim(ServerLevel level, ServerPlayer player, Vec3 heading, int now) {
        if (!player.isInWater() || config().cinematicStyle != TravelCinematicStyle.FLIGHT
                || !TravelSwim.available(player)) {
            return null;
        }
        Double surface = TravelSwim.waterSurface(player);
        if (surface == null) {
            return null;
        }
        // The surface block is the last water block: one under the surface's height.
        int waterY = (int) Math.floor(surface) - 1;
        BlockPos shore = SafariTeleporter.findShoreNear(level, player.getBlockX(), waterY, player.getBlockZ());
        if (shore == null) {
            // Open water (the lagoon, the sea): swim toward the destination until land.
            shore = SafariTeleporter.findShoreToward(level, player.getX(), waterY, player.getZ(), heading);
        }
        TravelFlight.Flight flight;
        if (shore != null) {
            flight = TravelSwim.dive(level, player, surface, shore, now);
        } else {
            // No land in reach (the open sea, or land beyond the loaded chunks): the
            // swimmer still dives and heads off toward the destination, the screen
            // fades as it leaves the frame and the trip goes on in black. Without it
            // the trip fell back to the rope's painted scene.
            flight = TravelSwim.diveToward(level, player, surface, heading, now);
        }
        return flight == null ? null : new Swim(flight, shore);
    }

    /**
     * Whether a trip is filmed as a burrow: the config films trips, a digger
     * can be staged, and the trip goes into a cave (an underground house) or
     * out of one (the guide of an underground house, the rope used in a cave).
     */
    private static boolean burrows(ServerPlayer player, Source source, RestHouses.HouseSite target) {
        if (config().cinematicStyle != TravelCinematicStyle.FLIGHT || !TravelBurrow.available(player)) {
            return false;
        }
        if (target.underground()) {
            return true;
        }
        if (source == Source.GUIDE) {
            RestHouses.HouseSite here = houseHere(player);
            return here != null && here.underground();
        }
        return SafariCaves.isInCave(player);
    }

    /**
     * Where the digger goes down: in front of the house's steps for the
     * guide, where the player stands for the rope (the ground under them
     * when they are jumping or falling); null when there is none near
     * (high in the air).
     */
    @Nullable
    private static BlockPos burrowFeet(ServerLevel level, ServerPlayer player, Source source) {
        if (source == Source.GUIDE) {
            RestHouses.HouseSite here = houseHere(player);
            return here == null ? null : SafariTeleporter.arrivalFeet(level, RestHouses.arrivalOf(here));
        }
        // Jumping or falling: the ground under them, or the nearest spot around.
        return player.onGround() ? player.blockPosition()
                : SafariTeleporter.groundNear(level, player.blockPosition());
    }

    /** The house the player stands at (its square, steps and plaza), or null. */
    @Nullable
    private static RestHouses.HouseSite houseHere(ServerPlayer player) {
        return RestHouses.siteAt(player.getBlockX(), player.getBlockY(), player.getBlockZ(), HOUSE_MARGIN);
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
            RestHouses.HouseSite here = houseHere(player);
            feet = here == null ? null : SafariTeleporter.arrivalFeet(level, RestHouses.arrivalOf(here));
        } else if (player.onGround() && level.canSeeSky(BlockPos.containing(player.getEyePosition()))) {
            feet = player.blockPosition();
        } else {
            // Under cover, swimming or in the air: the nearest good open-air spot.
            feet = SafariTeleporter.findStandingSpotNear(level, player.getBlockX(), player.getBlockZ());
            climb = feet != null && !feet.equals(player.blockPosition());
        }
        if (feet != null && TravelFlight.canTakeOff(level, feet, heading)) {
            return new TakeOff(feet, climb);
        }
        // The spot cannot be filmed (trees close all around block every direction
        // of the bird, or leave no camera spot): the best open-air spot nearby,
        // reached under the black, rather than the painted scene.
        BlockPos around = feet != null ? feet : player.blockPosition();
        BlockPos other = SafariTeleporter.findStandingSpotNear(level, around.getX(), around.getZ());
        if (other != null && !other.equals(feet) && TravelFlight.canTakeOff(level, other, heading)) {
            return new TakeOff(other, !other.equals(player.blockPosition()));
        }
        return null;
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
            case SWIMMING -> {
                TravelFlight.Flight flight = trip.flight;
                boolean done = flight == null || TravelFlight.tick(flight, now);
                // Black as soon as the swimmer is too far to be seen, or a little
                // before it arrives: watching empty water until the shore wastes time.
                if (flight != null && trip.blackTick < 0
                        && (now - trip.phaseTick >= flight.duration() - BLACK_LEAD_TICKS
                        || TravelSwim.outOfSight(flight))) {
                    Platform.get().sendToPlayer(player, SafariTravelPayload.black());
                    trip.blackTick = now;
                }
                // Once black, the rest of the swim is not shown: it ends when the fade does.
                if (trip.blackTick >= 0 && now - trip.blackTick >= BLACK_LEAD_TICKS) {
                    done = true;
                }
                if (done) {
                    dropFlight(trip);
                    if (trip.takeOffFeet != null) {
                        // Under the black: the player stands on the shore the swimmer reached.
                        SafariTeleporter.sendToFeet(player, safari, trip.takeOffFeet);
                    }
                    trip.enter(Phase.SURFACING, now);
                }
                return true;
            }
            case SURFACING -> {
                if (now - trip.phaseTick < SURFACE_TICKS) {
                    return true;
                }
                TravelFlight.Flight flight;
                if (trip.takeOffFeet == null) {
                    flight = null;
                } else if (trip.burrow) {
                    // From a cave's shore (or toward a cave): the trip goes on underground.
                    flight = TravelBurrow.dig(safari, player, trip.takeOffFeet, trip.heading, now);
                } else {
                    flight = TravelFlight.takeOff(safari, player, trip.takeOffFeet, trip.heading, now);
                }
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
                if (flight != null && now - trip.phaseTick == flight.duration() - BLACK_LEAD_TICKS) {
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
                TravelFlight.Flight landing;
                if (trip.landingFeet == null) {
                    landing = null;
                } else if (trip.burrow) {
                    // Out of the ground facing the house, as the player will stand.
                    landing = TravelBurrow.emerge(safari, player, trip.landingFeet,
                            Vec3.directionFromRotation(0.0F, trip.landingYaw), now);
                } else {
                    landing = TravelFlight.land(safari, player, trip.landingFeet, trip.heading, now);
                }
                if (landing == null) {
                    // No open sky (or no ground to dig out of, or no camera spot) at the destination:
                    // straight from black to the player's eyes.
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
            // A climb to the open air speaks of its spot, not of a rest house.
            end(safari, trip, player, trip.destination != null ? "message.rope_no_spot" : "message.guide_unreachable");
            return false;
        }
        if (elapsed < Ticks.fromSeconds(config().cinematicSeconds)) {
            return true;
        }
        if (trip.destination != null) {
            return reachSpot(safari, player, trip, now);
        }
        RestHouses.HouseSite site = RestHouses.siteOf(trip.anchor);
        if (site == null) {
            return true;
        }
        // A wet spot moved the house to its next candidate, maybe beyond
        // the ticket: follow it, or the new spot never loads and the trip
        // waits until it gives up.
        ChunkPos siteChunk = site.ticketCentre();
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
            trip.landingYaw = arrival.yaw();
            trip.enter(Phase.SETTLING, now);
            return true;
        }
        safari.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.CHORUS_FRUIT_TELEPORT, SoundSource.PLAYERS, 1.0F, 1.0F);
        end(safari, trip, player, null);
        return false;
    }

    /**
     * Brings the player of a trip to a spot (the rope's climb) once its
     * chunk is loaded, then lets the digger come out there. Unlike a house,
     * the spot was chosen at the start and does not move.
     *
     * @return false when the trip is over (ended here)
     */
    private static boolean reachSpot(ServerLevel safari, ServerPlayer player, Trip trip, int now) {
        BlockPos feet = trip.destination;
        // Not hasChunkAt: see waitForDestination.
        if (!LoadedChunks.isReady(safari, feet)) {
            return true;
        }
        SafariTeleporter.sendToFeet(player, safari, feet);
        trip.arrived.accept(player);
        trip.landingFeet = feet;
        trip.landingYaw = player.getYRot();
        trip.enter(Phase.SETTLING, now);
        return true;
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
        if (site.underground()) {
            // Named by its cavern: the surface biome above it says nothing of where it is.
            return new Label(key("rest_house.cave." + site.caveId()), "");
        }
        Optional<ResourceKey<Biome>> biome = biomeAt(level, site.x(), site.z());
        String biomeKey = biome.map(key -> Util.makeDescriptionId("biome", key.location()))
                .orElse(key("travel.unknown_biome"));
        boolean shared = false;
        for (RestHouses.HouseSite other : RestHouses.sites()) {
            if (other.anchor() != site.anchor() && !other.underground()
                    && biomeAt(level, other.x(), other.z()).equals(biome)) {
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
        RestHouses.HouseSite here = houseHere(player);
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
