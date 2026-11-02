package dev.darcosse.common.cobblemonsafari.teleport;

import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.api.npc.NPCClass;
import com.cobblemon.mod.common.api.npc.NPCClasses;
import com.cobblemon.mod.common.api.pokemon.PokemonProperties;
import com.cobblemon.mod.common.api.pokemon.PokemonPropertyExtractor;
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies;
import com.cobblemon.mod.common.api.riding.RidingProperties;
import com.cobblemon.mod.common.api.riding.RidingStyle;
import com.cobblemon.mod.common.api.riding.behaviour.RidingBehaviourSettings;
import com.cobblemon.mod.common.api.riding.behaviour.RidingController;
import com.cobblemon.mod.common.entity.npc.NPCEntity;
import com.cobblemon.mod.common.entity.pokemon.PokemonBehaviourFlag;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.cobblemon.mod.common.pokemon.properties.UncatchableProperty;
import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import dev.darcosse.common.cobblemonsafari.structure.RestHouses;
import dev.darcosse.common.cobblemonsafari.util.LoadedChunks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The actors of the flight cinematic: a flying mount and, on its back, a
 * double of the travelling player wearing their skin. The mount is a copy of
 * the first Pokémon of the player's party that can be ridden in the air
 * ({@link #mountOf}), or a Staraptor when the party has none. They take off
 * in front of the house the player leaves and land in front of the one they
 * reach, moved by the server along a fixed path, so every player around sees
 * the same flight.
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

    /** The fallback mount's species, checked by {@link #available}; a datapack may remove it. */
    private static final ResourceLocation BIRD_SPECIES = ResourceLocation.fromNamespaceAndPath("cobblemon", "staraptor");

    /**
     * The fallback mount, as Cobblemon properties. "uncatchable" makes balls
     * bounce off; shiny is fixed so the same bird flies for everyone, every time.
     * The level only shows on its name plate, which a wild Pokémon hides.
     */
    private static final String BIRD_PROPERTIES = "staraptor level=50 shiny=false uncatchable";

    /** Length of a take-off or a landing, in ticks. Shorter looks like a jump; longer drags. */
    static final int FLIGHT_TICKS = 80;

    /**
     * Taking off, the bird first gathers itself on the spot for this many
     * ticks (a flap, a small bob): the wind-up that makes the leap read as
     * a push.
     */
    private static final int GATHER_TICKS = 10;
    /** Height of that bob, in blocks. Never below the ground: the path check would refuse it. */
    private static final double GATHER_BOB = 0.1D;

    /**
     * Then it leaps straight up {@link #LEAP_HEIGHT} blocks in this many
     * ticks, fast off the ground and slowing at the top: the impact of the
     * take-off, with a gust on the ground.
     */
    private static final int LEAP_TICKS = 6;
    private static final double LEAP_HEIGHT = 4.5D;

    /**
     * Then it hangs at the top for this many ticks, flapping, the time for
     * the camera to frame it up there, before it dashes away.
     */
    private static final int HANG_TICKS = 8;
    /** Bob while hanging, in blocks. */
    private static final double HANG_BOB = 0.15D;

    /**
     * Landing: ticks of the approach from afar to above the spot, where it
     * brakes; it slows down as it comes in, the dash played backwards.
     */
    private static final int APPROACH_TICKS = 50;

    /** Then it brakes above the spot for this many ticks, flapping hard, its gust raising dust. */
    private static final int FLARE_TICKS = 12;

    /** Then it drops onto the spot in this many ticks, faster as it goes down. */
    private static final int DROP_TICKS = 10;

    /** Then a small bounce on touching the ground: its length in ticks, and its height in blocks. */
    private static final int BOUNCE_TICKS = 4;
    private static final double BOUNCE_HEIGHT = 0.3D;
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
    static final double LOOK_HEIGHT = 1.5D;

    /** Ticks between two sweeps for orphan actors; a sweep walks every entity of the Safari. */
    private static final int SWEEP_INTERVAL_TICKS = 100;

    /** Wing flap played at take-off and landing: volume, then pitch (higher sounds like a smaller bird). */
    private static final float FLAP_VOLUME = 0.8F;
    private static final float FLAP_PITCH = 1.3F;

    /** Pitch of the big flap of the leap: lower than the others, heavier. */
    private static final float LEAP_FLAP_PITCH = 0.9F;

    /** Ticks between two flaps while hanging or braking. */
    private static final int FLAP_INTERVAL = 4;

    /**
     * The gust of the leap (and of the touchdown, smaller): wind particles,
     * a ring of dust and earth of the ground, and the wind charge's burst.
     * One packet each to the players around.
     */
    private static final int GUST_RING = 10;
    private static final int GUST_DUST = 12;
    private static final int GUST_EARTH = 30;
    private static final double GUST_SPREAD = 1.2D;
    private static final float GUST_VOLUME = 0.7F;

    /**
     * A trail of small gusts behind the bird, every {@link #TRAIL_INTERVAL}
     * ticks, for {@link #TRAIL_TICKS} ticks after the dash starts (or
     * before the brake when landing).
     */
    private static final int TRAIL_INTERVAL = 2;
    private static final int TRAIL_TICKS = 20;

    /** Volume of the rush of air when the bird dashes off or comes in. */
    private static final float RUSH_VOLUME = 0.9F;

    /** The eight compass directions, as horizontal unit vectors, tried when the wished one is blocked. */
    private static final List<Vec3> COMPASS = compass();

    private static int sweepTicks;

    /**
     * One shot under way: a flight, or a burrow when {@code burrow} is set
     * (TravelBurrow: the mount digs into the ground or comes out of it), or a
     * dive when {@code dive} is set (TravelSwim: the mount swims under water
     * to a shore).
     *
     * @param bird      the mount: a copy of the player's flyer (or digger), or the fallback species
     * @param rider     the player's double on its back
     * @param spot      the feet position it takes off from or lands on
     * @param direction where it flies (or digs) toward, horizontal unit vector
     * @param landing   true when it comes down onto the spot (or out of the ground there)
     * @param startTick server tick the shot began
     * @param camera    where the client's camera stands
     * @param look      what the camera aims at until the bird reaches the client
     * @param duration  length of the shot, in ticks
     * @param burrow    what a burrow needs on top, or null
     * @param dive      what a dive needs on top, or null
     */
    record Flight(PokemonEntity bird, NPCEntity rider, Vec3 spot, Vec3 direction, boolean landing, int startTick,
                  Vec3 camera, Vec3 look, int duration, @Nullable TravelBurrow.Dig burrow,
                  @Nullable TravelSwim.Dive dive) {
    }

    private TravelFlight() {
    }

    /**
     * Whether a flight can be staged at all for this player: the double's
     * NPC class is loaded, and the player has a flyer or the fallback
     * species exists. Cheap: two registry lookups and a walk over a party of six.
     */
    static boolean available(ServerPlayer player) {
        return NPCClasses.getByIdentifier(TRAVELLER_CLASS) != null
                && (mountOf(player) != null || PokemonSpecies.getByIdentifier(BIRD_SPECIES) != null);
    }

    /** Whether the double's NPC class is loaded. */
    static boolean riderClassLoaded() {
        return NPCClasses.getByIdentifier(TRAVELLER_CLASS) != null;
    }

    /**
     * The first Pokémon of the player's party that can carry a rider in the
     * air, not fainted, or null. "Can carry" is Cobblemon's own riding data:
     * an AIR riding behaviour and at least one seat. A species that only
     * flies as a wild Pokémon, without seats, is skipped: the double would
     * have nowhere to sit and would float above it.
     */
    @Nullable
    private static Pokemon mountOf(ServerPlayer player) {
        for (Pokemon pokemon : Cobblemon.INSTANCE.getStorage().getParty(player)) {
            if (pokemon == null || pokemon.isFainted()) {
                continue;
            }
            RidingProperties riding = pokemon.getRiding();
            Map<RidingStyle, RidingBehaviourSettings> behaviours = riding.getBehaviours();
            if (behaviours != null && behaviours.containsKey(RidingStyle.AIR) && !riding.getSeats().isEmpty()) {
                return pokemon;
            }
        }
        return null;
    }

    /**
     * Whether a take-off could be staged from these feet: the mount has an
     * open direction and the camera a spot. Spawns nothing; for a take-off
     * decided before the player is brought there (the Escape Rope's climb out
     * of a cave). Cost: up to nine paths of 13 heightmap reads and 24 camera
     * candidates.
     */
    static boolean canTakeOff(ServerLevel level, BlockPos feet, Vec3 wished) {
        Vec3 spot = Vec3.atBottomCenterOf(feet);
        Vec3 direction = openDirection(level, spot, wished, false);
        return direction != null && cameraSpot(level, spot, direction, false) != null;
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
        PokemonEntity bird = spawnBird(level, player, first, yaw);
        if (bird == null) {
            return null;
        }
        NPCEntity rider = spawnRider(level, player, first, yaw, bird);
        if (rider == null) {
            bird.discard();
            return null;
        }
        Vec3 look = landing ? first : spot.add(0.0D, LOOK_HEIGHT, 0.0D);
        return new Flight(bird, rider, spot, direction, landing, now, camera, look, FLIGHT_TICKS, null, null);
    }

    /**
     * Moves the actors to where they are at this tick.
     *
     * @return true once the flight is over (the actors stay where they are until {@link #discard})
     */
    static boolean tick(Flight flight, int now) {
        if (flight.burrow() != null) {
            return TravelBurrow.tick(flight, now);
        }
        if (flight.dive() != null) {
            return TravelSwim.tick(flight, now);
        }
        int elapsed = now - flight.startTick();
        double progress = Math.min(1.0D, elapsed / (double) FLIGHT_TICKS);
        Vec3 next = position(flight.spot(), flight.direction(), flight.landing(), progress);
        PokemonEntity bird = flight.bird();
        // The motion is given too, though nothing applies it: the client's
        // Pokémon animations read it to tell flying from hovering.
        bird.setDeltaMovement(next.subtract(bird.position()));
        bird.setPos(next);
        effects(flight, next, elapsed);
        float yaw = yawAt(flight, elapsed);
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
     * Where the bird is at a point of its flight, from 0 to 1 (a tick of
     * {@link #FLIGHT_TICKS}, so the path check can sample it).
     *
     * <p>Taking off: it gathers itself on the spot ({@link #GATHER_TICKS}),
     * leaps straight up ({@link #LEAP_TICKS}), hangs there flapping
     * ({@link #HANG_TICKS}), then dashes away, climbing steeply first and
     * flattening while it speeds up.
     *
     * <p>Landing: it comes in from the far side, fast then slowing (the dash
     * backwards, {@link #APPROACH_TICKS}), to above the spot, brakes there
     * ({@link #FLARE_TICKS}), drops onto it faster and faster
     * ({@link #DROP_TICKS}), bounces once ({@link #BOUNCE_TICKS}) and stands.
     */
    private static Vec3 position(Vec3 spot, Vec3 direction, boolean landing, double progress) {
        double tick = progress * FLIGHT_TICKS;
        double along;
        double up;
        if (!landing) {
            if (tick < GATHER_TICKS) {
                along = 0.0D;
                up = GATHER_BOB * Mth.sin((float) (tick / GATHER_TICKS * Math.PI));
            } else if (tick < GATHER_TICKS + LEAP_TICKS) {
                double u = (tick - GATHER_TICKS) / LEAP_TICKS;
                along = 0.0D;
                // 1 - (1 - u)^3: off the ground at once, slowing to the top.
                up = LEAP_HEIGHT * (1.0D - Math.pow(1.0D - u, 3.0D));
            } else if (tick < GATHER_TICKS + LEAP_TICKS + HANG_TICKS) {
                double u = (tick - GATHER_TICKS - LEAP_TICKS) / HANG_TICKS;
                along = 0.0D;
                up = LEAP_HEIGHT + HANG_BOB * Mth.sin((float) (u * Math.PI * 2.0D));
            } else {
                double u = (tick - GATHER_TICKS - LEAP_TICKS - HANG_TICKS)
                        / (FLIGHT_TICKS - GATHER_TICKS - LEAP_TICKS - HANG_TICKS);
                double[] dash = dash(Math.min(1.0D, u));
                along = dash[0];
                up = dash[1];
            }
        } else {
            if (tick < APPROACH_TICKS) {
                // The dash backwards: far and high first, slowing as it comes to the spot.
                double[] dash = dash(1.0D - tick / APPROACH_TICKS);
                along = -dash[0];
                up = dash[1];
            } else if (tick < APPROACH_TICKS + FLARE_TICKS) {
                double u = (tick - APPROACH_TICKS) / FLARE_TICKS;
                along = 0.0D;
                up = LEAP_HEIGHT + HANG_BOB * Mth.sin((float) (u * Math.PI * 2.0D));
            } else if (tick < APPROACH_TICKS + FLARE_TICKS + DROP_TICKS) {
                double u = (tick - APPROACH_TICKS - FLARE_TICKS) / DROP_TICKS;
                along = 0.0D;
                // Squared: slow off the brake, fastest onto the ground.
                up = LEAP_HEIGHT * (1.0D - u * u);
            } else if (tick < APPROACH_TICKS + FLARE_TICKS + DROP_TICKS + BOUNCE_TICKS) {
                double u = (tick - APPROACH_TICKS - FLARE_TICKS - DROP_TICKS) / BOUNCE_TICKS;
                along = 0.0D;
                up = BOUNCE_HEIGHT * Mth.sin((float) (u * Math.PI));
            } else {
                along = 0.0D;
                up = 0.0D;
            }
        }
        return spot.add(direction.x * along, up, direction.z * along);
    }

    /**
     * The dash away from above the spot, from 0 to 1: blocks along the
     * flight, then blocks up from the ground. Steep first and flattening
     * while it speeds up; starts at the top of the leap.
     */
    private static double[] dash(double u) {
        double along = CLIMB_DISTANCE * u * u;
        double up = LEAP_HEIGHT + CLIMB_HEIGHT * (1.0D - (1.0D - u) * (1.0D - u));
        return new double[]{along, up};
    }

    /**
     * The bird's heading at a tick: along the flight, except when landing,
     * where from the brake on it turns to face the camera, so the bird and
     * its rider touch down facing it.
     */
    private static float yawAt(Flight flight, int elapsed) {
        float along = yawOf(flight.direction());
        if (!flight.landing() || elapsed < APPROACH_TICKS) {
            return along;
        }
        Vec3 flat = new Vec3(flight.camera().x - flight.spot().x, 0.0D, flight.camera().z - flight.spot().z);
        if (flat.lengthSqr() < 1.0E-4D) {
            return along;
        }
        float toCamera = yawOf(flat.normalize());
        float u = Math.min(1.0F, (elapsed - APPROACH_TICKS) / (float) FLARE_TICKS);
        return along + Mth.wrapDegrees(toCamera - along) * u;
    }

    /**
     * The effects of this tick, by phase: flaps, the gust of the leap, the
     * rush and the trail of the dash; when landing, the rush and trail of
     * the approach, the flaps and the dust of the brake, and the touchdown.
     * Cost: at most two particle packets and two sounds a tick, while a
     * flight plays.
     */
    private static void effects(Flight flight, Vec3 at, int elapsed) {
        ServerLevel level = (ServerLevel) flight.bird().level();
        Vec3 spot = flight.spot();
        if (!flight.landing()) {
            int leap = GATHER_TICKS;
            int dashStart = GATHER_TICKS + LEAP_TICKS + HANG_TICKS;
            if (elapsed == GATHER_TICKS / 2) {
                flap(level, at, FLAP_PITCH);
            } else if (elapsed == leap) {
                gust(level, spot, 1.0F);
                flap(level, at, LEAP_FLAP_PITCH);
            } else if (elapsed > leap + LEAP_TICKS && elapsed < dashStart
                    && (elapsed - leap - LEAP_TICKS) % FLAP_INTERVAL == 0) {
                flap(level, at, FLAP_PITCH);
            } else if (elapsed == dashStart) {
                rush(level, at);
            }
            if (elapsed >= dashStart && elapsed < dashStart + TRAIL_TICKS && elapsed % TRAIL_INTERVAL == 0) {
                trail(level, at);
            }
        } else {
            int flare = APPROACH_TICKS;
            int touchdown = APPROACH_TICKS + FLARE_TICKS + DROP_TICKS;
            if (elapsed == flare - TRAIL_TICKS) {
                rush(level, at);
            }
            if (elapsed >= flare - TRAIL_TICKS && elapsed < flare && elapsed % TRAIL_INTERVAL == 0) {
                trail(level, at);
            }
            if (elapsed >= flare && elapsed < touchdown && (elapsed - flare) % FLAP_INTERVAL == 0) {
                flap(level, at, FLAP_PITCH);
                // The downdraught of the brake raises a little dust under it.
                level.sendParticles(ParticleTypes.POOF, spot.x, spot.y + 0.1D, spot.z, GUST_DUST / 3, GUST_SPREAD * 0.6D,
                        0.05D, GUST_SPREAD * 0.6D, 0.02D);
            }
            if (elapsed == touchdown) {
                gust(level, spot, 0.5F);
            }
        }
    }

    /** One wing flap at the bird. */
    private static void flap(ServerLevel level, Vec3 at, float pitch) {
        level.playSound(null, at.x, at.y, at.z, SoundEvents.ENDER_DRAGON_FLAP, SoundSource.NEUTRAL, FLAP_VOLUME, pitch);
    }

    /**
     * A gust on the ground at the spot: a ring of wind, dust and earth of
     * the ground, and the wind charge's burst. {@code strength} 1 for the
     * leap, less for the touchdown.
     */
    private static void gust(ServerLevel level, Vec3 spot, float strength) {
        double y = spot.y + 0.2D;
        level.sendParticles(ParticleTypes.GUST, spot.x, y + 0.3D, spot.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
        level.sendParticles(ParticleTypes.SMALL_GUST, spot.x, y, spot.z, Math.round(GUST_RING * strength),
                GUST_SPREAD, 0.1D, GUST_SPREAD, 0.0D);
        level.sendParticles(ParticleTypes.POOF, spot.x, y, spot.z, Math.round(GUST_DUST * strength), GUST_SPREAD,
                0.1D, GUST_SPREAD, 0.05D);
        BlockState ground = level.getBlockState(BlockPos.containing(spot).below());
        if (!ground.isAir()) {
            level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, ground), spot.x, y, spot.z,
                    Math.round(GUST_EARTH * strength), GUST_SPREAD, 0.2D, GUST_SPREAD, 0.2D);
            SoundType sound = ground.getSoundType();
            level.playSound(null, spot.x, spot.y, spot.z, sound.getFallSound(), SoundSource.NEUTRAL,
                    sound.getVolume() * strength, sound.getPitch() * 0.7F);
        }
        level.playSound(null, spot.x, spot.y, spot.z, SoundEvents.WIND_CHARGE_BURST.value(), SoundSource.NEUTRAL,
                GUST_VOLUME * strength, 1.0F);
    }

    /** The rush of air when the bird dashes off or comes in. */
    private static void rush(ServerLevel level, Vec3 at) {
        level.playSound(null, at.x, at.y, at.z, SoundEvents.TRIDENT_RIPTIDE_1.value(), SoundSource.NEUTRAL,
                RUSH_VOLUME, 1.0F);
    }

    /** A small gust left behind the bird. */
    private static void trail(ServerLevel level, Vec3 at) {
        level.sendParticles(ParticleTypes.SMALL_GUST, at.x, at.y + 0.5D, at.z, 1, 0.2D, 0.2D, 0.2D, 0.0D);
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
            // Not hasChunkAt: the destination's chunks get a ticket at take-off and
            // may still be generating; getHeight would wait for them on the server thread.
            if (!LoadedChunks.isReady(level, column)) {
                continue;
            }
            int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING, column.getX(), column.getZ());
            // On the spot itself the bird stands on the ground: it only needs open sky.
            boolean onSpot = point.y < spot.y + LEAP_HEIGHT && point.distanceToSqr(spot.x, point.y, spot.z) < 1.0D;
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
    private static final int SCORE_SEES_PATH = 4;
    private static final int SCORE_BEST = 5;

    /**
     * Air the best camera spots have around them, in blocks each way (1 is
     * a 3 x 3 column two blocks high), and how far they keep from a rest
     * house's square: a spot against a wall or a pillar films along it.
     * A bonus, not a rule: see {@link #cameraScore}.
     */
    private static final int CAMERA_CLEARANCE = 1;
    private static final int HOUSE_MARGIN = 2;

    /**
     * How good a camera spot is. Unusable: not free air, or under cover
     * inside a rest house's square (its hall, under the roof). Then, better
     * and better: free air under cover elsewhere (a canopy, as before this
     * ranking existed); under the open sky; also seeing the rider's spot;
     * also seeing the middle of the path; best, also with air all around
     * ({@link #CAMERA_CLEARANCE}), {@link #HOUSE_MARGIN} blocks from any
     * rest house, and nothing in front of the lens, thin blocks included
     * (a banner, a sign or a flower: {@link #seesClearly}).
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
        if (!LoadedChunks.isReady(level, block) || !level.getBlockState(block).isAir()
                || !level.getBlockState(block.above()).isAir()) {
            return SCORE_UNUSABLE;
        }
        boolean openSky = block.getY() >= level.getHeight(Heightmap.Types.MOTION_BLOCKING, block.getX(), block.getZ());
        if (!openSky) {
            // Under cover in a house's square is under its roof: the hall.
            return RestHouses.siteAt(block.getX(), block.getY(), block.getZ(), 0) != null ? SCORE_UNUSABLE : SCORE_COVERED;
        }
        if (!sees(level, camera, rider)) {
            return SCORE_OPEN;
        }
        if (!sees(level, camera, midway)) {
            return SCORE_SEES_RIDER;
        }
        return roomy(level, block) && seesClearly(level, camera, rider) ? SCORE_BEST : SCORE_SEES_PATH;
    }

    /** Whether a camera block has air all around and keeps away from the rest houses. */
    private static boolean roomy(ServerLevel level, BlockPos block) {
        if (RestHouses.siteAt(block.getX(), block.getY(), block.getZ(), HOUSE_MARGIN) != null) {
            return false;
        }
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -CAMERA_CLEARANCE; dx <= CAMERA_CLEARANCE; dx++) {
            for (int dz = -CAMERA_CLEARANCE; dz <= CAMERA_CLEARANCE; dz++) {
                for (int dy = 0; dy <= 1; dy++) {
                    cursor.set(block.getX() + dx, block.getY() + dy, block.getZ() + dz);
                    if (!LoadedChunks.isReady(level, cursor) || !level.getBlockState(cursor).isAir()) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /**
     * Whether nothing at all stands between two points, thin blocks
     * included: the outline is cast, not the collision ({@link #sees}).
     */
    private static boolean seesClearly(ServerLevel level, Vec3 from, Vec3 to) {
        BlockHitResult hit = level.clip(new ClipContext(from, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE,
                CollisionContext.empty()));
        return hit.getType() == HitResult.Type.MISS;
    }

    /**
     * Whether nothing solid stands between two points. An end point in a
     * chunk not fully loaded counts as clear, so the ray never makes the
     * server thread wait for a chunk still generating.
     */
    static boolean sees(ServerLevel level, Vec3 from, Vec3 to) {
        if (!LoadedChunks.isReady(level, BlockPos.containing(from))
                || !LoadedChunks.isReady(level, BlockPos.containing(to))) {
            return true;
        }
        BlockHitResult hit = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE,
                CollisionContext.empty()));
        return hit.getType() == HitResult.Type.MISS;
    }

    /**
     * Spawns the mount, frozen in the air until moved, or null when it could
     * not be built or the level refused it.
     *
     * <p>The player's flyer is copied, never sent out: its species, form,
     * aspects (shiny, regional form...), gender and level, so it looks the
     * same, but as a new wild entity. Using the party Pokémon itself would
     * tie the actor to the party: recalling it, its health, its position in
     * storage. The copy is uncatchable like the fallback bird.
     */
    @Nullable
    private static PokemonEntity spawnBird(ServerLevel level, ServerPlayer player, Vec3 at, float yaw) {
        return spawnMount(level, mountOf(player), BIRD_PROPERTIES, at, yaw, true, 0.0D);
    }

    /**
     * Spawns a mount, frozen where it is put until moved, or null when it
     * could not be built or the level refused it: a copy of the party
     * Pokémon given (see {@link #spawnBird}), else the fallback species.
     *
     * @param fallback  Cobblemon properties of the fallback mount
     * @param flying    whether it holds the flying pose
     * @param minHeight height the mount is grown to, in blocks, when it is smaller (Cobblemon's
     *                  {@code scale_modifier}, which grows its hitbox too); 0 keeps its size
     */
    @Nullable
    static PokemonEntity spawnMount(ServerLevel level, @Nullable Pokemon mount, String fallback, Vec3 at, float yaw,
                                    boolean flying, double minHeight) {
        PokemonEntity bird;
        try {
            PokemonProperties properties;
            if (mount != null) {
                properties = mount.createPokemonProperties(PokemonPropertyExtractor.SPECIES,
                        PokemonPropertyExtractor.FORM, PokemonPropertyExtractor.SHINY,
                        PokemonPropertyExtractor.ASPECTS, PokemonPropertyExtractor.GENDER,
                        PokemonPropertyExtractor.LEVEL);
                properties.getCustomProperties().add(UncatchableProperty.INSTANCE.uncatchable());
            } else {
                properties = PokemonProperties.Companion.parse(fallback);
            }
            bird = properties.createEntity(level);
            double height = bird.getBbHeight();
            if (height > 0.0D && height < minHeight) {
                // Built again rather than rescaled: the size must be in the Pokémon
                // before its entity exists, or clients would get it a moment late.
                // The first entity was never added to the level: nothing to remove.
                Float current = properties.getScaleModifier();
                float base = current == null ? 1.0F : current;
                properties.setScaleModifier((float) (base * minHeight / height));
                bird = properties.createEntity(level);
            }
        } catch (RuntimeException e) {
            CobblemonSafari.LOGGER.warn("Could not create the travel cinematic's mount: {}", e.toString());
            return null;
        }
        bird.moveTo(at.x, at.y, at.z, yaw, 0.0F);
        prepareActor(bird);
        if (flying) {
            bird.setBehaviourFlag(PokemonBehaviourFlag.FLYING, true);
        }
        // Wild, but not to be battled: a player could otherwise start a fight mid-trip.
        bird.getEntityData().set(PokemonEntity.getUNBATTLEABLE(), true);
        return level.addFreshEntity(bird) ? bird : null;
    }

    /** Spawns the double, named and dressed like the player, seated on the bird; or null. */
    @Nullable
    private static NPCEntity spawnRider(ServerLevel level, ServerPlayer player, Vec3 at, float yaw,
                                        PokemonEntity bird) {
        return spawnRider(level, player, at, yaw, bird, true);
    }

    /**
     * Spawns the double, named and dressed like the player, in its seated
     * pose, or null: riding the mount in its seat, or, when the mount has no
     * seat, put at the given spot without riding (the caller then keeps it
     * on the mount's back, see TravelBurrow). The pose is the same either
     * way: the traveller's poser sits in every pose type.
     */
    @Nullable
    static NPCEntity spawnRider(ServerLevel level, ServerPlayer player, Vec3 at, float yaw, PokemonEntity bird,
                                boolean seated) {
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
        if (seated) {
            // Forced: Pokémon only take passengers on their free seats, and a
            // wild one may not offer them to an NPC; the seat is free here.
            rider.startRiding(bird, true);
        }
        TravelSkins.dress(level.getServer(), player, rider);
        return rider;
    }

    /**
     * Keeps a double in the seat of a mount moved by the server, whatever
     * Cobblemon's riding controller decides. With nobody steering, the
     * controller picks the mount's riding style from where it is (on the
     * ground: land; in water: liquid; neither: air) and ejects its
     * passengers when the mount has no such style (RidingController.tick,
     * Cobblemon 1.8.0): a digger, never on the ground, or a swimmer leaving
     * the water, would throw the double off. The controller only looks for
     * a new style ten ticks after its last change, so setting the wished
     * style again each tick ({@code changeBehaviour}, public; its setter is
     * private) keeps it from ever looking. The double is put back should it
     * have been thrown off all the same. Cost per tick: a new riding state
     * for an actor nobody steers, and a vehicle check.
     *
     * @param style the style to hold, or any style of the mount when it has not this one
     */
    static void keepSeated(PokemonEntity mount, NPCEntity rider, RidingStyle style) {
        RidingController controller = mount.getRidingController();
        if (controller != null) {
            Map<RidingStyle, RidingBehaviourSettings> behaviours = controller.getBehaviours();
            RidingBehaviourSettings held = behaviours.get(style);
            if (held == null && !behaviours.isEmpty()) {
                held = behaviours.values().iterator().next();
            }
            if (held != null) {
                controller.changeBehaviour(held.getKey());
            }
        }
        if (rider.getVehicle() != mount) {
            rider.startRiding(mount, true);
        }
    }

    /** What both actors share: nothing moves them but this class, nothing hurts them. */
    private static void prepareActor(net.minecraft.world.entity.Mob actor) {
        actor.setNoAi(true);
        actor.setNoGravity(true);
        actor.setInvulnerable(true);
        actor.addTag(ACTOR_TAG);
    }

    /** The yaw facing a horizontal direction, in Minecraft's convention (0 = south). */
    static float yawOf(Vec3 direction) {
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
