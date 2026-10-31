package dev.darcosse.common.cobblemonsafari.teleport;

import com.cobblemon.mod.common.Cobblemon;
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
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
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
     * Length of a dig or an emergence, in ticks. Shorter looks like a fall
     * through the floor; longer leaves the camera staring at dirt.
     */
    static final int DIG_TICKS = 60;

    /**
     * Share of a dig spent hopping in place before going down (and, coming
     * out, settling after the last jump). The hop shows the digger leaving
     * the ground on purpose rather than sinking.
     */
    private static final double HOP_SHARE = 0.25D;

    /** Height of that hop, in blocks. */
    private static final double HOP_HEIGHT = 0.5D;

    /** Blocks travelled forward while going down (or, coming out, while rising): it digs ahead, not straight down. */
    private static final double FORWARD = 1.5D;

    /**
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

    /** Ticks between two digging sounds while crossing the ground. */
    private static final int SOUND_INTERVAL = 4;

    /** Volume of the digging sound, relative to the ground's own break sound. */
    private static final float SOUND_VOLUME = 0.8F;

    /**
     * Where the camera may stand around the hole: angles from the dig's
     * direction (degrees, front quarters first, so it sees the digger's face),
     * distances and heights above the ground, in blocks. Close: a burrow is a
     * small thing, and caves leave little room.
     */
    private static final double[] CAMERA_ANGLES = {40.0D, -40.0D, 75.0D, -75.0D, 0.0D, 120.0D, -120.0D, 180.0D};
    private static final double[] CAMERA_DISTANCES = {4.5D, 3.5D, 6.0D};
    private static final double[] CAMERA_HEIGHTS = {1.8D, 2.8D, 1.2D};

    /**
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
     */
    record Dig(BlockState ground, double depth, boolean seated, double riderLift) {
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
     * Stages the digger coming out of the ground at these feet, facing the
     * given direction once out, or returns null when it cannot (see {@link #dig}).
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
        Vec3 camera = cameraSpot(level, spot, direction);
        if (camera == null) {
            return null;
        }
        Pokemon mount = diggerOf(player);
        // The fallback Dugtrio has no seat either.
        boolean seated = mount != null && !mount.getRiding().getSeats().isEmpty();
        float yaw = TravelFlight.yawOf(direction);
        // Spawned on the spot, then put on the path once its height is known.
        double height = referenceHeight(level);
        if (height <= 0.0D) {
            return null;
        }
        if (mount != null && KEEP_SIZE.contains(mount.getSpecies().getResourceIdentifier())) {
            height = 0.0D;
        }
        PokemonEntity digger = TravelFlight.spawnMount(level, mount, DIGGER_PROPERTIES, spot, yaw, false, height);
        if (digger == null) {
            return null;
        }
        double lift = seated ? 0.0D : Math.max(0.0D, digger.getBbHeight() - SEAT_SINK);
        double depth = digger.getBbHeight() + RIDER_HEIGHT + SINK_MARGIN;
        Vec3 first = position(spot, direction, landing, depth, 0.0D);
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
                new Dig(ground, depth, seated, lift), null);
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
        Vec3 next = position(flight.spot(), flight.direction(), flight.landing(), dig.depth(), progress);
        PokemonEntity digger = flight.bird();
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
        if (crossingGround(flight, next)) {
            throwEarth(flight, dig, next, elapsed);
        }
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
     * Earth flying up around the hole, and the ground's own digging sound
     * now and then. Cost: one particle packet a tick to the players around,
     * a sound every {@link #SOUND_INTERVAL} ticks.
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
     * Where the digger is at a point of the shot, from 0 to 1. Going down: a
     * hop in place, then down into the ground while moving forward,
     * smoothly. Coming out plays the same path backwards, from behind the
     * spot: up out of the ground while moving forward, then the hop, landing
     * on the spot.
     */
    private static Vec3 position(Vec3 spot, Vec3 direction, boolean landing, double depth, double progress) {
        double s = landing ? 1.0D - progress : progress;
        double along;
        double up;
        if (s < HOP_SHARE) {
            double u = s / HOP_SHARE;
            along = 0.0D;
            up = HOP_HEIGHT * Mth.sin((float) (u * Math.PI));
        } else {
            double u = (s - HOP_SHARE) / (1.0D - HOP_SHARE);
            along = FORWARD * u;
            up = -depth * Mth.smoothstep(u);
        }
        double signed = landing ? -along : along;
        return spot.add(direction.x * signed, up, direction.z * signed);
    }

    /**
     * Where the camera stands to film the hole: the first candidate that is
     * free air (two blocks), not under a house's roof, and sees the hole;
     * failing all, a spot straight above the hole. Null when none does: the
     * trip then goes on in black. Unlike the
     * flight's, it needs no open sky: the burrow is filmed in caves.
     * Cost: at most 72 candidates, two block reads and a short ray cast each.
     */
    @Nullable
    private static Vec3 cameraSpot(ServerLevel level, Vec3 spot, Vec3 direction) {
        Vec3 target = spot.add(0.0D, LOOK_HEIGHT, 0.0D);
        for (double height : CAMERA_HEIGHTS) {
            for (double distance : CAMERA_DISTANCES) {
                for (double angle : CAMERA_ANGLES) {
                    Vec3 offset = direction.yRot((float) Math.toRadians(angle)).scale(distance);
                    Vec3 camera = spot.add(offset).add(0.0D, height, 0.0D);
                    if (usable(level, camera) && TravelFlight.sees(level, camera, target)) {
                        return camera;
                    }
                }
            }
        }
        // Nothing around sees the hole (a narrow tunnel, a bend): straight above
        // it, looking down, rather than no shot at all.
        for (double height : OVERHEAD_HEIGHTS) {
            Vec3 camera = spot.add(0.0D, height, 0.0D);
            if (usable(level, camera) && TravelFlight.sees(level, camera, target)) {
                return camera;
            }
        }
        return null;
    }

    /**
     * Whether the camera can stand there: loaded, two blocks of air, and not
     * under the roof of a house (its hall, from which it would see a wall).
     */
    private static boolean usable(ServerLevel level, Vec3 camera) {
        BlockPos block = BlockPos.containing(camera);
        if (!LoadedChunks.isReady(level, block) || !level.getBlockState(block).isAir()
                || !level.getBlockState(block.above()).isAir()) {
            return false;
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
