package dev.darcosse.common.cobblemonsafari.teleport;

import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.CobblemonSounds;
import com.cobblemon.mod.common.api.moves.Move;
import com.cobblemon.mod.common.api.moves.MoveTemplate;
import com.cobblemon.mod.common.api.pokemon.PokemonProperties;
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies;
import com.cobblemon.mod.common.api.riding.RidingStyle;
import com.cobblemon.mod.common.entity.npc.NPCEntity;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import dev.darcosse.common.cobblemonsafari.structure.RestHouses;
import dev.darcosse.common.cobblemonsafari.util.LoadedChunks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Set;

/**
 * The burrow cinematic, for trips into or out of a cave, where no bird can
 * fly: the player's double rides a digger that burrows into the ground in
 * front of the house it leaves, as the move Dig does, and comes out of the
 * ground in front of the house it reaches, throwing up earth both times.
 *
 * <p>The digger is a copy of the first Pokémon of the player's party whose
 * species digs naturally ({@link #DIGGERS}) and that could learn Dig
 * ({@link #diggerOf}), or a Dugtrio when the party has none. When it has a
 * seat in Cobblemon's riding data the double rides it there; most have
 * none (Dugtrio, Sandslash, Excadrill...): the double is then not riding it
 * but kept on its back by this class each tick, in the same seated pose.
 * Any digger smaller than a Dugtrio grown twice is grown to that height
 * ({@link #REFERENCE_SCALE}), so a rider fits; taller ones keep their size,
 * and so do the exceptions of {@link #KEEP_SIZE}.
 *
 * <p>Same staging as the flight ({@link TravelFlight}): actors without AI
 * or gravity, moved by the server along a fixed path, filmed by the client's
 * camera from a spot beside them, tagged and swept the same way; only the
 * path, the camera and the effects differ. A shot is a
 * {@link TravelFlight.Flight} carrying a {@link Dig}. Server side.
 */
final class TravelBurrow {

    /** Name of the move a party Pokémon must be able to learn to dig the player through. */
    private static final String DIG_MOVE = "dig";

    /**
     * The species that may dig in the cinematic, every form included
     * (Alolan Dugtrio, Alolan Sandslash...): those that look at home going
     * underground. Learning Dig is not enough: Charizard learns it, and
     * flapping into the ground looks wrong. A species missing from the
     * running Cobblemon is simply never matched.
     */
    private static final Set<ResourceLocation> DIGGERS = species(
            "dugtrio",
            "hippopotas", "hippowdon",
            "orthworm",
            "dunsparce", "dudunsparce",
            "rhyhorn", "rhydon", "rhyperior",
            "krokorok", "krookodile",
            "sandslash",
            "drilbur", "excadrill",
            "onix", "steelix",
            "silicobra", "sandaconda",
            "trapinch");

    /** The fallback digger's species, checked by {@link #available}; a datapack may remove it. */
    private static final ResourceLocation DIGGER_SPECIES = ResourceLocation.fromNamespaceAndPath("cobblemon", "dugtrio");

    /** The fallback digger, as Cobblemon properties; same rules as the flight's Staraptor. */
    private static final String DIGGER_PROPERTIES = "dugtrio level=50 shiny=false uncatchable";

    /**
     * The size every digger is brought to when it is smaller: the height of
     * a Dugtrio grown this many times ({@code scale_modifier}, Cobblemon's
     * own size property, which grows the hitbox too). At its natural size a
     * Dugtrio is under a metre and the double looked like walking on it.
     * Measured on the fallback Dugtrio itself, so a datapack resizing it
     * resizes every digger with it. A digger taller by nature keeps its size.
     */
    private static final float REFERENCE_SCALE = 2.0F;

    /**
     * Diggers never grown, whatever their height: Orthworm is a flat worm
     * (half a block high, over a block wide), and grown to the reference
     * height it would be some four and a half blocks wide. It has a seat,
     * so its rider is placed by Cobblemon at its natural size.
     */
    private static final Set<ResourceLocation> KEEP_SIZE = species("orthworm");

    /**
     * How far the seated double sinks into the top of a digger without
     * seats, in blocks: the seated pose draws the hips above the entity's
     * feet, so its feet go a little under the digger's top for it to sit on
     * it rather than hover. Raise it if the double hovers, lower it if it
     * sinks into the digger.
     */
    private static final double SEAT_SINK = 0.6D;

    /**
     * Length of a dig or an emergence, in ticks. The trip's timing is built
     * on it (RestHouseTravel); the phases below must fit in it.
     */
    static final int DIG_TICKS = 60;

    /**
     * Going down, the digger first scratches the ground on the spot for
     * this many ticks: small bobs, earth thrown, digging sounds close
     * together. The wind-up that makes the dive read as a decision.
     */
    private static final int WINDUP_TICKS = 14;

    /** Height of each bob while scratching, in blocks, and how many bobs. */
    private static final double SCRATCH_BOB = 0.12D;
    private static final int SCRATCH_BOBS = 3;

    /**
     * Then it jumps, for this many ticks, {@link #JUMP_FORWARD} ahead. Long
     * enough for the camera, which turns to follow the digger, to tilt up
     * with it and back down.
     */
    private static final int JUMP_TICKS = 16;
    private static final double JUMP_FORWARD = 0.5D;

    /**
     * Height of the jump as a share of the digger's height: a jump of a
     * fixed block or so looked like a hop once the digger was grown to two
     * Dugtrios. 1.2 clears its own height and a little more.
     */
    private static final double JUMP_SHARE = 1.2D;

    /** Lowest jump wanted, in blocks, whatever the digger's size. */
    private static final double MIN_JUMP = 1.5D;

    /**
     * Lowest jump kept under a low ceiling, in blocks: below it the dig
     * would look like a stumble, but a cave may leave no more room.
     */
    private static final double MIN_FITTING_JUMP = 0.4D;

    /** Highest ceiling looked for above the spot, in blocks: beyond it the jump is never cut. */
    private static final int HEADROOM_SCAN = 16;

    /**
     * Then dives into the ground, accelerating, in this many ticks: the
     * impact. Longer and it slows back into the old sinking look.
     */
    private static final int PLUNGE_TICKS = 7;

    /** Blocks from the spot, along the trip, where the dive ends underground. */
    private static final double FORWARD = 1.8D;

    /**
     * Coming out, the ground rumbles over the exit for this many ticks
     * before anything shows: the tension before the burst.
     */
    private static final int RUMBLE_TICKS = 16;

    /** Then the digger bursts up through the ground, decelerating, in this many ticks. */
    private static final int BURST_TICKS = 6;

    /**
     * Then a jump to the spot, this long, as high as the dig's jump:
     * it lands where the player will stand. The rest of the shot it stands there.
     */
    private static final int LAND_ARC_TICKS = 16;

    /** Blocks behind the spot where the digger breaks out of the ground. */
    private static final double EXIT_BACK = 0.6D;

    /**
     * Height of the double above the digger's base when seated    /**
     * Height of the double above the digger's base when seated, in blocks:
     * about a player's height plus the seat. The dig goes this far down
     * beyond the digger's own height, so the double vanishes too.
     */
    private static final double RIDER_HEIGHT = 1.8D;

    /** Extra depth, in blocks, so nothing of the actors shows at the end of the dig. */
    private static final double SINK_MARGIN = 0.5D;

    /**
     * Earth thrown up per tick while the actors cross the ground, and its
     * spread around the hole in blocks. More particles cost each watching
     * client a little more; they live about a second.
     */
    private static final int PARTICLES_PER_TICK = 10;
    private static final double PARTICLE_SPREAD = 0.6D;

    /** Earth thrown per tick while scratching, and its spread: small spurts. */
    private static final int SCRATCH_PARTICLES = 4;
    private static final double SCRATCH_SPREAD = 0.4D;

    /**
     * The impact, once, when the digger hits the ground (and when it breaks
     * out of it): a sheaf of earth, and a puff of dust. One packet each to
     * the players around.
     */
    private static final int IMPACT_PARTICLES = 50;
    private static final double IMPACT_SPREAD = 0.8D;
    private static final double IMPACT_SPEED = 0.3D;
    private static final int IMPACT_DUST = 14;

    /** Volume of the impact sounds (Cobblemon's ground impact, the ground's own break sound). */
    private static final float IMPACT_VOLUME = 1.2F;
    /** Pitch of the ground's break sound at the impact: lower reads heavier. */
    private static final float IMPACT_PITCH = 0.6F;

    /**
     * After the dive, a rumble runs ahead along the trip under the ground:
     * a puff of dust every {@link #TRAIL_INTERVAL} ticks, {@link #TRAIL_STEP}
     * blocks further each time, with a low digging sound.
     */
    private static final int TRAIL_INTERVAL = 3;
    private static final double TRAIL_STEP = 0.5D;
    private static final int TRAIL_PARTICLES = 6;

    /** Ticks between two digging sounds while crossing the ground. */
    private static final int SOUND_INTERVAL = 4;

    /** Volume of the digging sound, relative to the ground's own break sound. */
    private static final float SOUND_VOLUME = 0.8F;

    /** Pitch of the rumble under the ground, relative to the ground's break sound. */
    private static final float RUMBLE_PITCH = 0.5F;

    /**
     * Where the camera may stand around the hole: angles from the dig's
     * direction (degrees, front quarters first, so it sees the digger's face),
     * distances and heights above the ground, in blocks. The lowest height
     * comes first: a camera a little below the riders' heads makes the dive
     * look bigger. Close: a burrow is a small thing, and caves leave little room.
     */
    private static final double[] CAMERA_ANGLES = {40.0D, -40.0D, 25.0D, -25.0D, 60.0D, -60.0D, 0.0D, 90.0D,
            -90.0D, 120.0D, -120.0D, 180.0D};
    private static final double[] CAMERA_DISTANCES = {5.0D, 4.0D, 6.5D};
    private static final double[] CAMERA_HEIGHTS = {1.2D, 1.8D, 2.8D};

    /**
     * Air the camera wants around it on the first pass, in blocks each way:
     * 1 is a 3 x 3 column two blocks high. Without it the camera may hug a
     * wall and film along it.
     */
    private static final int CAMERA_CLEARANCE = 1;

    /**
     * How far from a rest house's square the camera stays on the first
     * pass, in blocks: its walls, pillars and front decorations are what
     * the camera used to film past.
     */
    private static final int HOUSE_MARGIN = 2;

    /**
     * Height of the rider's head above the digger's top, for the camera's
     * sight lines: about a seated player.
     */
    private static final double RIDER_HEAD = 1.4D;

    /**
     * Heights above the hole tried last for the camera    /**
     * Heights above the hole tried last for the camera, in blocks, when no
     * spot around sees it: lowest first, in a tunnel the ceiling is close.
     */
    private static final double[] OVERHEAD_HEIGHTS = {3.0D, 4.0D, 2.5D};

    /** Height above the ground the camera aims at: the digger and its rider. */
    private static final double LOOK_HEIGHT = 0.9D;

    /**
     * What a burrow needs on top of a flight.
     *
     * @param ground     the block the digger goes through, for its particles and sound
     * @param depth      how far below the spot the actors go, in blocks
     * @param seated     whether the double rides in a seat (else it sits on the digger, moved by this class)
     * @param riderLift  height of that double's feet above the digger's base
     * @param jump       height of its jumps, in blocks: grown with the digger, cut under a low ceiling
     */
    record Dig(BlockState ground, double depth, boolean seated, double riderLift, double jump) {
    }

    private TravelBurrow() {
    }

    /**
     * Whether a burrow can be staged at all for this player: the double's
     * NPC class is loaded, and the player has a digger or the fallback
     * species exists. Cheap: registry lookups and a walk over a
     * party of six with their learnsets.
     */
    static boolean available(ServerPlayer player) {
        return TravelFlight.riderClassLoaded()
                && (diggerOf(player) != null || PokemonSpecies.getByIdentifier(DIGGER_SPECIES) != null);
    }

    /**
     * The first Pokémon of the player's party, not fainted, whose species is
     * one of {@link #DIGGERS} and that could learn Dig, or null.
     */
    @Nullable
    private static Pokemon diggerOf(ServerPlayer player) {
        for (Pokemon pokemon : Cobblemon.INSTANCE.getStorage().getParty(player)) {
            if (pokemon != null && !pokemon.isFainted()
                    && DIGGERS.contains(pokemon.getSpecies().getResourceIdentifier())
                    && canLearn(pokemon, DIG_MOVE)) {
                return pokemon;
            }
        }
        return null;
    }

    /**
     * Whether a Pokémon knows a move or could learn it: by level, TM, tutor,
     * egg, evolution or form change, from its form's learnset. A move it
     * knows from elsewhere (a datapack, a command) counts too.
     */
    static boolean canLearn(Pokemon pokemon, String move) {
        for (Move known : pokemon.getMoveSet().getMoves()) {
            if (move.equals(known.getName())) {
                return true;
            }
        }
        for (MoveTemplate legal : pokemon.getForm().getMoves().getAllLegalMoves()) {
            if (move.equals(legal.getName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Stages the digger going down into the ground at these feet, heading
     * toward the wished direction, or returns null: nothing solid to dig
     * into there (water, air), no camera spot, or the actors could not be
     * spawned.
     */
    @Nullable
    static TravelFlight.Flight dig(ServerLevel level, ServerPlayer player, BlockPos feet, Vec3 wished, int now) {
        return stage(level, player, feet, wished, false, now);
    }

    /**
     * Stages the digger coming out of the ground at these feet, or returns
     * null when it cannot (see {@link #dig}). It comes out facing the
     * camera, toward which it jumps onto the spot; {@code facing} only
     * places the camera (front quarters first) and stands in when the
     * camera is straight above.
     */
    @Nullable
    static TravelFlight.Flight emerge(ServerLevel level, ServerPlayer player, BlockPos feet, Vec3 facing, int now) {
        return stage(level, player, feet, facing, true, now);
    }

    /** Picks the camera, then spawns the actors at the path's first point. */
    @Nullable
    private static TravelFlight.Flight stage(ServerLevel level, ServerPlayer player, BlockPos feet, Vec3 direction,
                                             boolean landing, int now) {
        BlockState ground = level.getBlockState(feet.below());
        if (ground.isAir() || !ground.getFluidState().isEmpty()) {
            return null;
        }
        Vec3 spot = Vec3.atBottomCenterOf(feet);
        // Spawned on the spot, then put on the path once its height is known.
        double height = referenceHeight(level);
        if (height <= 0.0D) {
            return null;
        }
        double jump = jumpHeight(level, feet, height);
        Vec3 camera = cameraSpot(level, spot, direction, height, jump);
        if (camera == null) {
            return null;
        }
        if (landing) {
            // Coming out, the actors face the camera, whatever side it found room
            // on: kept away from the house, it often stands behind the arrival,
            // and they came out with their backs to it.
            direction = towardCamera(spot, camera, direction);
        }
        Pokemon mount = diggerOf(player);
        // The fallback Dugtrio has no seat either.
        boolean seated = mount != null && !mount.getRiding().getSeats().isEmpty();
        float yaw = TravelFlight.yawOf(direction);
        if (mount != null && KEEP_SIZE.contains(mount.getSpecies().getResourceIdentifier())) {
            height = 0.0D;
        }
        PokemonEntity digger = TravelFlight.spawnMount(level, mount, DIGGER_PROPERTIES, spot, yaw, false, height);
        if (digger == null) {
            return null;
        }
        double lift = seated ? 0.0D : Math.max(0.0D, digger.getBbHeight() - SEAT_SINK);
        double depth = digger.getBbHeight() + RIDER_HEIGHT + SINK_MARGIN;
        Vec3 first = position(spot, direction, landing, depth, jump, 0);
        digger.moveTo(first.x, first.y, first.z, yaw, 0.0F);
        NPCEntity rider = TravelFlight.spawnRider(level, player, first.add(0.0D, lift, 0.0D), yaw, digger, seated);
        if (rider == null) {
            digger.discard();
            return null;
        }
        if (seated) {
            keepSeated(digger, rider);
        }
        Vec3 look = spot.add(0.0D, LOOK_HEIGHT, 0.0D);
        return new TravelFlight.Flight(digger, rider, spot, direction, landing, now, camera, look, DIG_TICKS,
                new Dig(ground, depth, seated, lift, jump), null);
    }

    /**
     * Moves the actors to where they are at this tick, and throws up earth
     * while they cross the ground.
     *
     * @return true once the shot is over (the actors stay where they are until discarded)
     */
    static boolean tick(TravelFlight.Flight flight, int now) {
        Dig dig = flight.burrow();
        int elapsed = now - flight.startTick();
        double progress = Math.min(1.0D, elapsed / (double) flight.duration());
        Vec3 next = position(flight.spot(), flight.direction(), flight.landing(), dig.depth(), dig.jump(), elapsed);
        PokemonEntity digger = flight.bird();
        Vec3 previous = digger.position();
        // The motion is given too, though nothing applies it: the client's
        // animations read it to play the walk rather than the idle.
        digger.setDeltaMovement(next.subtract(digger.position()));
        digger.setPos(next);
        float yaw = TravelFlight.yawOf(flight.direction());
        digger.setYRot(yaw);
        digger.setYBodyRot(yaw);
        digger.setYHeadRot(yaw);
        NPCEntity rider = flight.rider();
        if (dig.seated()) {
            keepSeated(digger, rider);
        } else {
            rider.setPos(next.add(0.0D, dig.riderLift(), 0.0D));
        }
        rider.setYRot(yaw);
        rider.setYHeadRot(yaw);
        effects(flight, dig, previous, next, elapsed);
        return progress >= 1.0D;
    }

    /**
     * Keeps the double in the digger's seat. Cobblemon's riding controller
     * would throw it off: with nobody steering, a mount that is not on the
     * ground is taken for flying, and one without an air riding style (every
     * digger with a seat: the Rhyhorn line, Orthworm, Dudunsparce ride on
     * land only) is then left with no style and ejects its passengers, ten
     * ticks after the mount (RidingController.tick, Cobblemon 1.8.0). A
     * digger moved through the air and the ground is never on the ground.
     * See {@link TravelFlight#keepSeated}.
     */
    private static void keepSeated(PokemonEntity digger, NPCEntity rider) {
        TravelFlight.keepSeated(digger, rider, RidingStyle.LAND);
    }

    /**
     * Whether some of the actors are going through the ground: the digger's
     * base below the surface while the top of the double is still above it.
     */
    private static boolean crossingGround(TravelFlight.Flight flight, Vec3 at) {
        double surface = flight.spot().y;
        return at.y < surface && at.y + flight.burrow().depth() > surface;
    }

    /**
     * The effects of this tick, by phase: earth spurts while scratching, the
     * impact when the digger hits (or breaks out of) the ground, earth
     * while it crosses it, the rumble ahead after the dive, and the rumble
     * over the exit before it breaks out.
     */
    private static void effects(TravelFlight.Flight flight, Dig dig, Vec3 previous, Vec3 next, int elapsed) {
        ServerLevel level = (ServerLevel) flight.bird().level();
        double surface = flight.spot().y;
        Vec3 spot = flight.spot();
        Vec3 direction = flight.direction();
        if (!flight.landing()) {
            if (elapsed < WINDUP_TICKS) {
                spurt(level, dig, spot, SCRATCH_PARTICLES, SCRATCH_SPREAD, elapsed, 2);
                return;
            }
            if (previous.y >= surface && next.y < surface) {
                impact(level, dig, next.x, surface, next.z);
            }
            int afterDive = elapsed - (WINDUP_TICKS + JUMP_TICKS + PLUNGE_TICKS);
            if (afterDive > 0 && afterDive % TRAIL_INTERVAL == 0) {
                double along = FORWARD + TRAIL_STEP * (afterDive / TRAIL_INTERVAL);
                Vec3 at = spot.add(direction.x * along, 0.0D, direction.z * along);
                rumble(level, dig, at, TRAIL_PARTICLES, afterDive % (TRAIL_INTERVAL * 2) == 0, 1.0F);
            }
        } else {
            Vec3 exit = spot.add(direction.x * -EXIT_BACK, 0.0D, direction.z * -EXIT_BACK);
            if (elapsed < RUMBLE_TICKS) {
                // Growing: a few grains first, then a shaking patch of ground.
                int count = 2 + elapsed * TRAIL_PARTICLES / RUMBLE_TICKS;
                float loudness = 0.4F + 0.6F * elapsed / RUMBLE_TICKS;
                rumble(level, dig, exit, count, elapsed % SOUND_INTERVAL == 0, loudness);
                return;
            }
            if (previous.y < surface && next.y >= surface) {
                impact(level, dig, next.x, surface, next.z);
            }
            if (elapsed == RUMBLE_TICKS + BURST_TICKS + LAND_ARC_TICKS) {
                land(level, dig, spot);
            }
        }
        if (crossingGround(flight, next)) {
            throwEarth(flight, dig, next, elapsed);
        }
    }

    /** A small spurt of earth at the spot, with a digging sound every {@code soundEvery} ticks. */
    private static void spurt(ServerLevel level, Dig dig, Vec3 at, int count, double spread, int elapsed,
                              int soundEvery) {
        level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, dig.ground()), at.x, at.y + 0.1D, at.z,
                count, spread, 0.1D, spread, 0.1D);
        if (elapsed % soundEvery == 0) {
            SoundType sound = dig.ground().getSoundType();
            level.playSound(null, at.x, at.y, at.z, sound.getHitSound(), SoundSource.NEUTRAL,
                    sound.getVolume() * SOUND_VOLUME, sound.getPitch());
        }
    }

    /**
     * The impact: a sheaf of earth and a puff of dust, Cobblemon's ground
     * impact and the ground's own break sound, low. Once per dig or burst.
     */
    private static void impact(ServerLevel level, Dig dig, double x, double surface, double z) {
        level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, dig.ground()), x, surface + 0.2D, z,
                IMPACT_PARTICLES, IMPACT_SPREAD, 0.4D, IMPACT_SPREAD, IMPACT_SPEED);
        level.sendParticles(ParticleTypes.POOF, x, surface + 0.3D, z, IMPACT_DUST, IMPACT_SPREAD, 0.2D,
                IMPACT_SPREAD, 0.05D);
        level.playSound(null, x, surface, z, CobblemonSounds.IMPACT_GROUND, SoundSource.NEUTRAL, IMPACT_VOLUME, 1.0F);
        SoundType sound = dig.ground().getSoundType();
        level.playSound(null, x, surface, z, sound.getBreakSound(), SoundSource.NEUTRAL,
                sound.getVolume() * IMPACT_VOLUME, sound.getPitch() * IMPACT_PITCH);
    }

    /** The landing after the burst: a ring of dust and a thud, softer than the impact. */
    private static void land(ServerLevel level, Dig dig, Vec3 spot) {
        level.sendParticles(ParticleTypes.POOF, spot.x, spot.y + 0.1D, spot.z, IMPACT_DUST / 2, PARTICLE_SPREAD,
                0.05D, PARTICLE_SPREAD, 0.02D);
        SoundType sound = dig.ground().getSoundType();
        level.playSound(null, spot.x, spot.y, spot.z, sound.getFallSound(), SoundSource.NEUTRAL,
                sound.getVolume() * IMPACT_VOLUME, sound.getPitch() * IMPACT_PITCH);
    }

    /** Earth quivering on the ground over the digger, with a low digging sound when asked. */
    private static void rumble(ServerLevel level, Dig dig, Vec3 at, int count, boolean sounding, float loudness) {
        level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, dig.ground()), at.x, at.y + 0.05D, at.z,
                count, PARTICLE_SPREAD * 0.6D, 0.05D, PARTICLE_SPREAD * 0.6D, 0.05D);
        if (sounding) {
            SoundType sound = dig.ground().getSoundType();
            level.playSound(null, at.x, at.y, at.z, sound.getHitSound(), SoundSource.NEUTRAL,
                    sound.getVolume() * SOUND_VOLUME * loudness, sound.getPitch() * RUMBLE_PITCH);
        }
    }

    /**
     * Earth flying up around the hole, and the ground's own digging sound
     * now and then, while the actors cross the ground. Cost: one particle
     * packet a tick to the players around, a sound every
     * {@link #SOUND_INTERVAL} ticks.
     */
    private static void throwEarth(TravelFlight.Flight flight, Dig dig, Vec3 at, int elapsed) {
        ServerLevel level = (ServerLevel) flight.bird().level();
        double surface = flight.spot().y;
        level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, dig.ground()), at.x, surface + 0.1D, at.z,
                PARTICLES_PER_TICK, PARTICLE_SPREAD, 0.2D, PARTICLE_SPREAD, 0.15D);
        if (elapsed % SOUND_INTERVAL == 0) {
            SoundType sound = dig.ground().getSoundType();
            level.playSound(null, at.x, surface, at.z, sound.getBreakSound(), SoundSource.NEUTRAL,
                    sound.getVolume() * SOUND_VOLUME, sound.getPitch() * 0.8F);
        }
    }

    /**
     * Where the digger is at a tick of the shot.
     *
     * <p>Going down: scratching on the spot ({@link #WINDUP_TICKS}), a jump
     * ({@link #JUMP_TICKS}), then a dive that accelerates into the ground
     * ({@link #PLUNGE_TICKS}), and it stays out of sight under the ground.
     *
     * <p>Coming out: out of sight under the exit while the ground rumbles
     * ({@link #RUMBLE_TICKS}), a burst up through the ground that slows as
     * it rises ({@link #BURST_TICKS}), a jump onto the spot
     * ({@link #LAND_ARC_TICKS}), then it stands there.
     */
    private static Vec3 position(Vec3 spot, Vec3 direction, boolean landing, double depth, double jump, int tick) {
        double along;
        double up;
        if (!landing) {
            if (tick < WINDUP_TICKS) {
                along = 0.0D;
                double u = tick / (double) WINDUP_TICKS;
                up = SCRATCH_BOB * Math.abs(Mth.sin((float) (u * Math.PI * SCRATCH_BOBS)));
            } else if (tick < WINDUP_TICKS + JUMP_TICKS) {
                double u = (tick - WINDUP_TICKS) / (double) JUMP_TICKS;
                along = JUMP_FORWARD * u;
                // Three quarters of an arc: past the top, already coming down when the dive takes over.
                up = jump * Mth.sin((float) (u * Math.PI * 0.75D));
            } else {
                double u = Math.min(1.0D, (tick - WINDUP_TICKS - JUMP_TICKS) / (double) PLUNGE_TICKS);
                // Where the jump left off, so the dive starts without a jump in height.
                double start = jump * Mth.sin((float) (Math.PI * 0.75D));
                // Squared: slow off the top of the jump, fastest into the ground.
                double eased = u * u;
                along = JUMP_FORWARD + (FORWARD - JUMP_FORWARD) * eased;
                up = start - (start + depth) * eased;
            }
        } else {
            if (tick < RUMBLE_TICKS) {
                along = -EXIT_BACK;
                up = -depth;
            } else if (tick < RUMBLE_TICKS + BURST_TICKS) {
                double u = (tick - RUMBLE_TICKS) / (double) BURST_TICKS;
                // 1 - (1 - u)^2: fastest out of the ground, slowing at the top.
                double eased = 1.0D - (1.0D - u) * (1.0D - u);
                along = -EXIT_BACK;
                up = -depth + depth * eased;
            } else if (tick < RUMBLE_TICKS + BURST_TICKS + LAND_ARC_TICKS) {
                double u = (tick - RUMBLE_TICKS - BURST_TICKS) / (double) LAND_ARC_TICKS;
                along = -EXIT_BACK * (1.0D - u);
                up = jump * Mth.sin((float) (u * Math.PI));
            } else {
                along = 0.0D;
                up = 0.0D;
            }
        }
        return spot.add(direction.x * along, up, direction.z * along);
    }

    /**
     * Where the camera stands to film the hole: the first candidate that is
     * free air, not under a house's roof, and sees the hole and both
     * actors' heads, tried twice: first wanting air all around and keeping
     * away from the rest houses ({@link #CAMERA_CLEARANCE},
     * {@link #HOUSE_MARGIN}), then without. Failing all, a spot straight
     * above the hole. Null when none does: the trip then goes on in black.
     * Unlike the flight's, it needs no open sky: the burrow is filmed in caves.
     * Cost: at most 216 candidates, a few block reads and three short ray
     * casts each.
     *
     * @param height the digger's height, for the actors' heads
     * @param jump   the height of its jump, for the top of it
     */
    @Nullable
    private static Vec3 cameraSpot(ServerLevel level, Vec3 spot, Vec3 direction, double height, double jump) {
        Vec3 target = spot.add(0.0D, LOOK_HEIGHT, 0.0D);
        // The rider's head at the top of the jump too: the camera follows the digger up.
        Vec3[] targets = {spot.add(0.0D, 0.3D, 0.0D), spot.add(0.0D, height, 0.0D),
                spot.add(0.0D, height + RIDER_HEAD, 0.0D), spot.add(0.0D, jump + height + RIDER_HEAD, 0.0D)};
        for (boolean strict : new boolean[]{true, false}) {
            for (double camHeight : CAMERA_HEIGHTS) {
                for (double distance : CAMERA_DISTANCES) {
                    for (double angle : CAMERA_ANGLES) {
                        Vec3 offset = direction.yRot((float) Math.toRadians(angle)).scale(distance);
                        Vec3 camera = spot.add(offset).add(0.0D, camHeight, 0.0D);
                        if (usable(level, camera, strict) && seesAll(level, camera, targets)) {
                            return camera;
                        }
                    }
                }
            }
        }
        // Nothing around sees the hole (a narrow tunnel, a bend): straight above
        // it, looking down, rather than no shot at all.
        for (double camHeight : OVERHEAD_HEIGHTS) {
            Vec3 camera = spot.add(0.0D, camHeight, 0.0D);
            if (usable(level, camera, false) && TravelFlight.sees(level, camera, target)) {
                return camera;
            }
        }
        return null;
    }

    /**
     * The horizontal direction from the spot to the camera, for actors that
     * must face it; {@code fallback} when the camera stands straight above.
     */
    private static Vec3 towardCamera(Vec3 spot, Vec3 camera, Vec3 fallback) {
        Vec3 flat = new Vec3(camera.x - spot.x, 0.0D, camera.z - spot.z);
        return flat.lengthSqr() < 1.0E-4D ? fallback : flat.normalize();
    }

    /**
     * How high the digger jumps from these feet: {@link #JUMP_SHARE} of its
     * height, at least {@link #MIN_JUMP}, but low enough that the digger
     * and its rider keep under the ceiling of a cave; never below
     * {@link #MIN_FITTING_JUMP}. Cost: up to {@link #HEADROOM_SCAN} block reads.
     */
    private static double jumpHeight(ServerLevel level, BlockPos feet, double height) {
        double wanted = Math.max(MIN_JUMP, height * JUMP_SHARE);
        BlockPos.MutableBlockPos cursor = feet.mutable();
        int free = 0;
        while (free < HEADROOM_SCAN && level.getBlockState(cursor).getCollisionShape(level, cursor).isEmpty()) {
            free++;
            cursor.move(0, 1, 0);
        }
        if (free >= HEADROOM_SCAN) {
            return wanted;
        }
        double room = free - height - RIDER_HEIGHT;
        return Math.max(MIN_FITTING_JUMP, Math.min(wanted, room));
    }

    /**
     * Whether nothing stands between the camera and any of the targets,
     * thin blocks included: the outline is cast, not the collision, so a
     * banner, a sign or a flower in front of the lens counts, as it did not
     * for the flight's test.
     */
    private static boolean seesAll(ServerLevel level, Vec3 camera, Vec3[] targets) {
        for (Vec3 target : targets) {
            if (!LoadedChunks.isReady(level, BlockPos.containing(target))) {
                continue;
            }
            BlockHitResult hit = level.clip(new ClipContext(camera, target, ClipContext.Block.OUTLINE,
                    ClipContext.Fluid.NONE, CollisionContext.empty()));
            if (hit.getType() != HitResult.Type.MISS) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether the camera can stand there: loaded, two blocks of air, and not
     * under the roof of a house (its hall, from which it would see a wall).
     * Strict, it also wants air all around ({@link #CAMERA_CLEARANCE}) and
     * keeps {@link #HOUSE_MARGIN} blocks from any rest house's square.
     */
    private static boolean usable(ServerLevel level, Vec3 camera, boolean strict) {
        BlockPos block = BlockPos.containing(camera);
        if (!LoadedChunks.isReady(level, block) || !level.getBlockState(block).isAir()
                || !level.getBlockState(block.above()).isAir()) {
            return false;
        }
        if (strict) {
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
        }
        boolean openSky = block.getY() >= level.getHeight(Heightmap.Types.MOTION_BLOCKING, block.getX(), block.getZ());
        if (openSky) {
            return true;
        }
        RestHouses.HouseSite house = RestHouses.siteAt(block.getX(), block.getY(), block.getZ(), 0);
        // Underground the chamber's rock is above every spot: only the hall itself is refused.
        return house == null || house.underground() && !underHouseRoof(level, block, house);
    }

    /**
     * Whether a block lies under the house's own roof    /**
     * Whether a block lies under the house's own roof: something solid
     * within the template's height above it. In a chamber, the cave's rock
     * is further up; the chamber's hidden lights have no collision and do
     * not count.
     */
    private static boolean underHouseRoof(ServerLevel level, BlockPos block, RestHouses.HouseSite house) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int y = block.getY() + 1; y <= house.groundY() + RestHouses.TEMPLATE_HEIGHT; y++) {
            cursor.set(block.getX(), y, block.getZ());
            if (!level.getBlockState(cursor).getCollisionShape(level, cursor).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * The height every digger is grown to: a Dugtrio at
     * {@link #REFERENCE_SCALE}, measured on a Dugtrio built for it (never
     * added to the level), or 0 when it cannot be built (species removed).
     * Measured each time: a datapack reload may change Dugtrio, and a trip
     * is rare. Cost: one Pokémon and its entity built.
     */
    private static double referenceHeight(ServerLevel level) {
        try {
            PokemonProperties properties = PokemonProperties.Companion.parse(DIGGER_PROPERTIES);
            properties.setScaleModifier(REFERENCE_SCALE);
            return properties.createEntity(level).getBbHeight();
        } catch (RuntimeException e) {
            return 0.0D;
        }
    }

    /** Cobblemon species ids from their names. */
    private static Set<ResourceLocation> species(String... names) {
        Set<ResourceLocation> ids = new HashSet<>();
        for (String name : names) {
            ids.add(ResourceLocation.fromNamespaceAndPath("cobblemon", name));
        }
        return Set.copyOf(ids);
    }
}
