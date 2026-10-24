package dev.darcosse.common.cobblemonsafari.teleport;

import com.cobblemon.mod.common.api.npc.NPCClass;
import com.cobblemon.mod.common.api.npc.NPCClasses;
import com.cobblemon.mod.common.api.pokemon.PokemonProperties;
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies;
import com.cobblemon.mod.common.entity.npc.NPCEntity;
import com.cobblemon.mod.common.entity.pokemon.PokemonBehaviourFlag;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import dev.darcosse.common.cobblemonsafari.structure.RestHouses;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The actors of the flight cinematic: a Staraptor and, on its back, a double
 * of the travelling player wearing their skin. They take off in front of the
 * house the player leaves and land in front of the one they reach, moved by
 * the server along a fixed path, so every player around sees the same
 * flight.
 *
 * <p>Why a double rather than the player riding: a client moves the vehicle
 * it controls by itself, so the server could not script the player's own
 * mount without fighting their client. The player stays put, invisible,
 * while the client films the double with the game camera (client side
 * TravelCamera).
 *
 * <p>Both actors have no AI and no gravity: nothing but this class moves
 * them. They are tagged {@link #ACTOR_TAG}; any tagged entity not in a
 * flight under way is an orphan (left by a crash or a stop mid-trip) and
 * {@link #sweep} removes it. Server side.
 */
final class TravelFlight {

    /** Tag on both actors. Part of the save format: renaming it leaves old orphans unswept. */
    static final String ACTOR_TAG = CobblemonSafari.MOD_ID + ".travel_actor";

    /** NPC class of the double: no dialogue, the player's skin, a seated pose. */
    private static final ResourceLocation TRAVELLER_CLASS = CobblemonSafari.id("safari_traveller");

    /** The bird's species, checked by {@link #available}; a datapack may remove it. */
    private static final ResourceLocation BIRD_SPECIES = ResourceLocation.fromNamespaceAndPath("cobblemon", "staraptor");

    /**
     * The bird, as Cobblemon properties. "uncatchable" makes balls bounce
     * off; shiny is fixed so the same bird flies for everyone, every time.
     * The level only shows on its name plate, which a wild Pokémon hides.
     */
    private static final String BIRD_PROPERTIES = "staraptor level=50 shiny=false uncatchable";

    /** Length of a take-off or a landing, in ticks. Shorter looks like a jump; longer drags. */
    static final int FLIGHT_TICKS = 80;

    /**
     * Share of a flight spent rising straight up from the ground (or, when
     * landing, settling down onto it), before the bird heads away.
     */
    private static final double HOVER_SHARE = 0.2D;
    /** Height of that straight rise, in blocks. */
    private static final double HOVER_RISE = 2.0D;
    /**
     * Distance and height covered after the rise, in blocks. Larger flies
     * further out of the camera's frame, but needs more open sky to pass
     * the path check.
     */
    private static final double CLIMB_DISTANCE = 40.0D;
    private static final double CLIMB_HEIGHT = 26.0D;

    /** Points of the path checked against the terrain. More is safer and costs a heightmap read each. */
    private static final int PATH_SAMPLES = 12;

    /**
     * Where the camera may stand, from the take-off or landing spot: to one
     * side or the other, a little before or behind along the flight, above
     * the ground. Tried in this order, nearest first, and ranked by
     * {@link #cameraScore}. Closer frames the bird bigger but loses it
     * sooner; more candidates cost a few more block reads and ray casts.
     */
    private static final double[] CAMERA_SIDES = {7.0D, 5.0D, 10.0D};
    private static final double CAMERA_ALONG_TAKEOFF = -3.0D;
    private static final double CAMERA_ALONG_LANDING = 5.0D;
    private static final double[] CAMERA_HEIGHTS = {2.5D, 4.0D, 6.5D, 9.0D};

    /** Point of the path, from 0 to 1, that the camera should also see: the bird should not vanish behind a roof. */
    private static final double CAMERA_SEES_PATH_AT = 0.5D;

    /** Height above the feet the camera aims at before the bird shows up: a rider's chest. */
    private static final double LOOK_HEIGHT = 1.5D;

    /** Ticks between two sweeps for orphan actors; a sweep walks every entity of the Safari. */
    private static final int SWEEP_INTERVAL_TICKS = 100;

    /** Wing flap played at take-off and landing: volume, then pitch (higher sounds like a smaller bird). */
    private static final float FLAP_VOLUME = 0.8F;
    private static final float FLAP_PITCH = 1.3F;

    /** The eight compass directions, as horizontal unit vectors, tried when the wished one is blocked. */
    private static final List<Vec3> COMPASS = compass();

    private static int sweepTicks;

    /**
     * One flight under way.
     *
     * @param bird      the Staraptor
     * @param rider     the player's double on its back
     * @param spot      the feet position it takes off from or lands on
     * @param direction where it flies toward, horizontal unit vector
     * @param landing   true when it comes down onto the spot
     * @param startTick server tick the flight began
     * @param camera    where the client's camera stands
     * @param look      what the camera aims at until the bird reaches the client
     */
    record Flight(PokemonEntity bird, NPCEntity rider, Vec3 spot, Vec3 direction, boolean landing, int startTick,
                  Vec3 camera, Vec3 look) {
    }

    private TravelFlight() {
    }

    /**
     * Whether a flight can be staged at all: the bird species and the
     * double's NPC class are loaded. Cheap: two registry lookups.
     */
    static boolean available() {
        return PokemonSpecies.getByIdentifier(BIRD_SPECIES) != null
                && NPCClasses.getByIdentifier(TRAVELLER_CLASS) != null;
    }

    /**
     * Stages the take-off from a spot, flying toward the wished direction or
     * the nearest one that is open, or returns null when no direction lets
     * the bird out (a spot under a roof or a tree) or the actors could not
     * be spawned.
     */
    @Nullable
    static Flight takeOff(ServerLevel level, ServerPlayer player, BlockPos feet, Vec3 wished, int now) {
        return stage(level, player, feet, wished, false, now);
    }

    /**
     * Stages the landing on a spot, coming in along the wished direction or
     * the nearest open one, or returns null when the bird cannot come down
     * there.
     */
    @Nullable
    static Flight land(ServerLevel level, ServerPlayer player, BlockPos feet, Vec3 wished, int now) {
        return stage(level, player, feet, wished, true, now);
    }

    /** Picks the direction and the camera, then spawns the actors at the path's first point. */
    @Nullable
    private static Flight stage(ServerLevel level, ServerPlayer player, BlockPos feet, Vec3 wished, boolean landing,
                                int now) {
        Vec3 spot = Vec3.atBottomCenterOf(feet);
        Vec3 direction = openDirection(level, spot, wished, landing);
        if (direction == null) {
            return null;
        }
        Vec3 camera = cameraSpot(level, spot, direction, landing);
        if (camera == null) {
            return null;
        }
        Vec3 first = position(spot, direction, landing, 0.0D);
        float yaw = yawOf(direction);
        PokemonEntity bird = spawnBird(level, first, yaw);
        if (bird == null) {
            return null;
        }
        NPCEntity rider = spawnRider(level, player, first, yaw, bird);
        if (rider == null) {
            bird.discard();
            return null;
        }
        level.playSound(null, spot.x, spot.y, spot.z, SoundEvents.ENDER_DRAGON_FLAP, SoundSource.NEUTRAL,
                FLAP_VOLUME, FLAP_PITCH);
        Vec3 look = landing ? first : spot.add(0.0D, LOOK_HEIGHT, 0.0D);
        return new Flight(bird, rider, spot, direction, landing, now, camera, look);
    }

    /**
     * Moves the actors to where they are at this tick.
     *
     * @return true once the flight is over (the actors stay where they are until {@link #discard})
     */
    static boolean tick(Flight flight, int now) {
        double progress = Math.min(1.0D, (now - flight.startTick()) / (double) FLIGHT_TICKS);
        Vec3 next = position(flight.spot(), flight.direction(), flight.landing(), progress);
        PokemonEntity bird = flight.bird();
        // The motion is given too, though nothing applies it: the client's
        // Pokémon animations read it to tell flying from hovering.
        bird.setDeltaMovement(next.subtract(bird.position()));
        bird.setPos(next);
        float yaw = yawOf(flight.direction());
        bird.setYRot(yaw);
        bird.setYBodyRot(yaw);
        bird.setYHeadRot(yaw);
        flight.rider().setYRot(yaw);
        flight.rider().setYHeadRot(yaw);
        return progress >= 1.0D;
    }

    /** Removes both actors. */
    static void discard(Flight flight) {
        flight.rider().stopRiding();
        flight.rider().discard();
        flight.bird().discard();
    }

    /**
     * Removes tagged actors that belong to no flight under way, once every
     * {@link #SWEEP_INTERVAL_TICKS}. Cost: one pass over the entities loaded
     * in the Safari every 5 seconds, a tag check each.
     *
     * @param active the UUIDs of the actors of flights under way
     */
    static void sweep(@Nullable ServerLevel safari, Set<UUID> active) {
        if (safari == null || ++sweepTicks < SWEEP_INTERVAL_TICKS) {
            return;
        }
        sweepTicks = 0;
        List<Entity> orphans = new ArrayList<>();
        for (Entity entity : safari.getAllEntities()) {
            if (entity.getTags().contains(ACTOR_TAG) && !active.contains(entity.getUUID())) {
                orphans.add(entity);
            }
        }
        // Removed after the walk: discarding while iterating the level's entities is not safe.
        for (Entity orphan : orphans) {
            orphan.discard();
        }
    }

    /** Forgets the sweep's count; call when a server starts. */
    static void reset() {
        sweepTicks = 0;
    }

    /**
     * Where the bird is at a point of its flight, from 0 to 1. Taking off,
     * it rises straight up, then climbs away, steeply first and then
     * flattening while it speeds up. Landing plays the same path backwards,
     * coming from the far side: fast from afar, slowing down to settle.
     */
    private static Vec3 position(Vec3 spot, Vec3 direction, boolean landing, double progress) {
        double s = landing ? 1.0D - progress : progress;
        double along;
        double up;
        if (s < HOVER_SHARE) {
            double u = s / HOVER_SHARE;
            along = 0.0D;
            // Smoothstep: no jolt when it leaves the ground or meets it.
            up = HOVER_RISE * u * u * (3.0D - 2.0D * u);
        } else {
            double u = (s - HOVER_SHARE) / (1.0D - HOVER_SHARE);
            along = CLIMB_DISTANCE * u * u;
            up = HOVER_RISE + CLIMB_HEIGHT * (1.0D - (1.0D - u) * (1.0D - u));
        }
        // Landing comes from behind the spot, flying toward the wished direction.
        double signed = landing ? -along : along;
        return spot.add(direction.x * signed, up, direction.z * signed);
    }

    /**
     * The wished direction when its path is open, else the open compass
     * direction closest to it, else null. Only loaded chunks are read: an
     * unloaded column lies far out, where the bird is high up anyway.
     */
    @Nullable
    private static Vec3 openDirection(ServerLevel level, Vec3 spot, Vec3 wished, boolean landing) {
        List<Vec3> candidates = new ArrayList<>();
        candidates.add(wished);
        List<Vec3> others = new ArrayList<>(COMPASS);
        others.sort(Comparator.comparingDouble(dir -> -dir.dot(wished)));
        candidates.addAll(others);
        for (Vec3 direction : candidates) {
            if (isPathOpen(level, spot, direction, landing)) {
                return direction;
            }
        }
        return null;
    }

    /** Whether the bird stays above the terrain all along the path. */
    private static boolean isPathOpen(ServerLevel level, Vec3 spot, Vec3 direction, boolean landing) {
        for (int i = 0; i <= PATH_SAMPLES; i++) {
            Vec3 point = position(spot, direction, landing, i / (double) PATH_SAMPLES);
            BlockPos column = BlockPos.containing(point);
            if (!level.hasChunkAt(column)) {
                continue;
            }
            int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING, column.getX(), column.getZ());
            // On the spot itself the bird stands on the ground: it only needs open sky.
            boolean onSpot = point.y < spot.y + HOVER_RISE && point.distanceToSqr(spot.x, point.y, spot.z) < 1.0D;
            if (point.y < ground + (onSpot ? 0 : 1)) {
                return false;
            }
        }
        return true;
    }

    /**
     * The best camera spot of the candidates ({@link #cameraScore}), the
     * first one on a tie, or null when none is free air outside the houses.
     * Cost: at most 24 candidates, each a few block and heightmap reads and
     * up to two short ray casts; once per take-off or landing.
     */
    @Nullable
    private static Vec3 cameraSpot(ServerLevel level, Vec3 spot, Vec3 direction, boolean landing) {
        Vec3 side = new Vec3(-direction.z, 0.0D, direction.x);
        double along = landing ? CAMERA_ALONG_LANDING : CAMERA_ALONG_TAKEOFF;
        Vec3 rider = spot.add(0.0D, LOOK_HEIGHT, 0.0D);
        Vec3 midway = position(spot, direction, landing, landing ? 1.0D - CAMERA_SEES_PATH_AT : CAMERA_SEES_PATH_AT);
        Vec3 best = null;
        int bestScore = 0;
        for (double height : CAMERA_HEIGHTS) {
            for (double distance : CAMERA_SIDES) {
                for (int sign : new int[]{1, -1}) {
                    Vec3 camera = spot.add(side.scale(distance * sign)).add(direction.scale(along)).add(0, height, 0);
                    int score = cameraScore(level, camera, rider, midway);
                    if (score == SCORE_BEST) {
                        return camera;
                    }
                    if (score > bestScore) {
                        best = camera;
                        bestScore = score;
                    }
                }
            }
        }
        return best;
    }

    /** Ranks of a camera spot, best last. 0 means it cannot be used at all. */
    private static final int SCORE_UNUSABLE = 0;
    private static final int SCORE_COVERED = 1;
    private static final int SCORE_OPEN = 2;
    private static final int SCORE_SEES_RIDER = 3;
    private static final int SCORE_BEST = 4;

    /**
     * How good a camera spot is. Unusable: not free air, or under cover
     * inside a rest house's square (its hall, under the roof). Then, better
     * and better: free air under cover elsewhere (a canopy, as before this
     * ranking existed); under the open sky; also seeing the rider's spot;
     * also seeing the middle of the path.
     *
     * <p>Why a ranking and not hard rules: as hard rules (open sky, outside
     * the house's square with a margin, both sight lines), almost no spot
     * passed, since the steps lie at the edge of the house's square, and
     * every trip fell back to the panorama. The one spot that must never be
     * used is the hall, where the camera only saw the ceiling; it is the
     * only one refused.
     */
    private static int cameraScore(ServerLevel level, Vec3 camera, Vec3 rider, Vec3 midway) {
        BlockPos block = BlockPos.containing(camera);
        if (!level.hasChunkAt(block) || !level.getBlockState(block).isAir()
                || !level.getBlockState(block.above()).isAir()) {
            return SCORE_UNUSABLE;
        }
        boolean openSky = block.getY() >= level.getHeight(Heightmap.Types.MOTION_BLOCKING, block.getX(), block.getZ());
        if (!openSky) {
            // Under cover in a house's square is under its roof: the hall.
            return RestHouses.siteAt(block.getX(), block.getZ(), 0) != null ? SCORE_UNUSABLE : SCORE_COVERED;
        }
        if (!sees(level, camera, rider)) {
            return SCORE_OPEN;
        }
        return sees(level, camera, midway) ? SCORE_BEST : SCORE_SEES_RIDER;
    }

    /** Whether nothing solid stands between two points. Unloaded chunks on the way count as clear. */
    private static boolean sees(ServerLevel level, Vec3 from, Vec3 to) {
        if (!level.hasChunkAt(BlockPos.containing(to))) {
            return true;
        }
        BlockHitResult hit = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE,
                CollisionContext.empty()));
        return hit.getType() == HitResult.Type.MISS;
    }

    /** Spawns the bird, frozen in the air until moved, or null when the level refused it. */
    @Nullable
    private static PokemonEntity spawnBird(ServerLevel level, Vec3 at, float yaw) {
        PokemonEntity bird;
        try {
            bird = PokemonProperties.Companion.parse(BIRD_PROPERTIES).createEntity(level);
        } catch (RuntimeException e) {
            CobblemonSafari.LOGGER.warn("Could not create the travel cinematic's bird: {}", e.toString());
            return null;
        }
        bird.moveTo(at.x, at.y, at.z, yaw, 0.0F);
        prepareActor(bird);
        bird.setBehaviourFlag(PokemonBehaviourFlag.FLYING, true);
        // Wild, but not to be battled: a player could otherwise start a fight mid-air.
        bird.getEntityData().set(PokemonEntity.getUNBATTLEABLE(), true);
        return level.addFreshEntity(bird) ? bird : null;
    }

    /** Spawns the double, named and dressed like the player, seated on the bird; or null. */
    @Nullable
    private static NPCEntity spawnRider(ServerLevel level, ServerPlayer player, Vec3 at, float yaw,
                                        PokemonEntity bird) {
        NPCClass npcClass = NPCClasses.getByIdentifier(TRAVELLER_CLASS);
        if (npcClass == null) {
            return null;
        }
        NPCEntity rider = new NPCEntity(level);
        rider.moveTo(at.x, at.y, at.z, yaw, 0.0F);
        rider.setNpc(npcClass);
        rider.initialize(1);
        // After setNpc, which names the NPC from its class.
        rider.setCustomName(player.getName());
        prepareActor(rider);
        if (!level.addFreshEntity(rider)) {
            return null;
        }
        // Forced: Pokémon only take passengers on their free seats, and a
        // wild one may not offer them to an NPC; the seat is free here.
        rider.startRiding(bird, true);
        TravelSkins.dress(level.getServer(), player, rider);
        return rider;
    }

    /** What both actors share: nothing moves them but this class, nothing hurts them. */
    private static void prepareActor(net.minecraft.world.entity.Mob actor) {
        actor.setNoAi(true);
        actor.setNoGravity(true);
        actor.setInvulnerable(true);
        actor.addTag(ACTOR_TAG);
    }

    /** The yaw facing a horizontal direction, in Minecraft's convention (0 = south). */
    private static float yawOf(Vec3 direction) {
        return (float) (Mth.atan2(-direction.x, direction.z) * Mth.RAD_TO_DEG);
    }

    /** The eight compass directions, normalised. */
    private static List<Vec3> compass() {
        List<Vec3> directions = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            double angle = i * Math.PI / 4.0D;
            directions.add(new Vec3(Math.cos(angle), 0.0D, Math.sin(angle)));
        }
        return List.copyOf(directions);
    }
}
