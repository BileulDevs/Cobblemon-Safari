package dev.darcosse.common.cobblemonsafari.teleport;

import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies;
import com.cobblemon.mod.common.api.riding.RidingProperties;
import com.cobblemon.mod.common.api.riding.RidingStyle;
import com.cobblemon.mod.common.entity.npc.NPCEntity;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import dev.darcosse.common.cobblemonsafari.util.LoadedChunks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * The dive cinematic, for the Escape Rope used in the water: the player's
 * double rides a swimmer that dives, as the move Dive does, swims under the
 * water to the nearest shore and comes up there; the trip then goes on from
 * that shore with the flight or, in a cave, the burrow.
 *
 * <p>The swimmer is a copy of the first Pokémon of the player's party that
 * could learn Dive, swims with a rider (a liquid riding style and a seat in
 * Cobblemon's riding data) and is not fainted ({@link #swimmerOf}), or a
 * Wailmer, which has both. Same staging as the flight
 * ({@link TravelFlight}): actors without AI or gravity, moved by the server
 * along a fixed path, filmed by the client's camera, tagged and swept the
 * same way. A shot is a {@link TravelFlight.Flight} carrying a {@link Dive}.
 * Server side.
 */
final class TravelSwim {

    /** Name of the move a party Pokémon must be able to learn to carry the player under water. */
    private static final String DIVE_MOVE = "dive";

    /** The fallback swimmer's species, checked by {@link #available}; a datapack may remove it. */
    private static final ResourceLocation SWIMMER_SPECIES = ResourceLocation.fromNamespaceAndPath("cobblemon", "wailmer");

    /** The fallback swimmer, as Cobblemon properties; same rules as the flight's Staraptor. */
    private static final String SWIMMER_PROPERTIES = "wailmer level=50 shiny=false uncatchable";

    /**
     * Speed of the swim, in blocks per tick, and its shortest and longest
     * length in ticks. Faster crosses a wide lake before the camera follows;
     * the bounds keep a swim to a bank next door from being a blink, and one
     * across a lake from dragging on.
     */
    private static final double SPEED = 0.175D;
    private static final int MIN_TICKS = 100;
    private static final int MAX_TICKS = 220;

    /**
     * Blocks swum toward the destination when no shore was found (the open
     * sea, land beyond the loaded chunks): the swimmer heads off and the
     * screen fades as it leaves the frame, the trip then goes on in black.
     */
    private static final double OPEN_WATER_DISTANCE = 24.0D;

    /**
     * How deep the swimmer goes, in blocks below the surface, in the middle
     * of the swim. Deeper hides it better; shallow water then shows it going
     * through the bottom, which the water's murk mostly hides.
     */
    private static final double DIVE_DEPTH = 3.0D;

    /**
     * Blocks short of the shore where the swimmer comes up: its body ends at
     * the bank rather than inside it.
     */
    private static final double SHORE_GAP = 1.5D;

    /** Share of the swimmer's height kept under the surface while it floats: it swims, it does not stand on the water. */
    private static final double FLOAT_SHARE = 0.5D;

    /**
     * Where the camera may stand around the dive: angles from the swim's
     * direction (side first, so it sees the swimmer leave), distances and
     * heights above the water, in blocks.
     */
    private static final double[] CAMERA_ANGLES = {100.0D, -100.0D, 140.0D, -140.0D, 60.0D, -60.0D, 180.0D};
    private static final double[] CAMERA_DISTANCES = {5.0D, 7.0D, 3.5D};
    private static final double[] CAMERA_HEIGHTS = {2.5D, 3.5D, 1.5D};

    /**
     * Distance from the camera, in blocks, beyond which the swimmer is no
     * longer seen and the screen fades to black: from above the water (where
     * it is under the surface, behind its murk, a dark shape soon lost), and
     * from under it (the water's fog). Larger shows more of the swim, and
     * more empty water once it is gone.
     */
    private static final double SIGHT_IN_AIR = 16.0D;
    private static final double SIGHT_IN_WATER = 10.0D;

    /** Bubbles trailed per tick under water, splashes thrown at the dive and at the surfacing. */
    private static final int BUBBLES_PER_TICK = 4;
    private static final int SPLASHES = 30;

    /**
     * How far below its floating height the player may be and still count
     * as at the surface, in blocks: a swimmer bobbing at the surface is a
     * little under it.
     */
    private static final double SURFACE_TOLERANCE = 1.0D;

    /** Share of the swim during which it goes down, then comes up: the dives' splash windows. */
    private static final double DIVE_SHARE = 0.2D;

    /**
     * What a dive needs on top of a flight.
     *
     * @param end     where the swimmer comes up, near the shore
     * @param surface height of the water's surface (top of its last water block)
     * @param float_  how far below the surface the swimmer's base floats
     * @param fromSurface whether it starts at the surface: it then dives with a splash; from the
     *                    depths it only swims up
     */
    record Dive(Vec3 end, double surface, double float_, boolean fromSurface) {
    }

    private TravelSwim() {
    }

    /**
     * Whether a dive can be staged at all for this player: the double's NPC
     * class is loaded, and the player has a swimmer or Wailmer exists.
     */
    static boolean available(ServerPlayer player) {
        return TravelFlight.riderClassLoaded()
                && (swimmerOf(player) != null || PokemonSpecies.getByIdentifier(SWIMMER_SPECIES) != null);
    }

    /**
     * The first Pokémon of the player's party, not fainted, that swims with
     * a rider (a liquid riding style and at least one seat) and could learn
     * Dive, or null.
     */
    @Nullable
    private static Pokemon swimmerOf(ServerPlayer player) {
        for (Pokemon pokemon : Cobblemon.INSTANCE.getStorage().getParty(player)) {
            if (pokemon == null || pokemon.isFainted()) {
                continue;
            }
            RidingProperties riding = pokemon.getRiding();
            if (riding.getBehaviours() != null && riding.getBehaviours().containsKey(RidingStyle.LIQUID)
                    && !riding.getSeats().isEmpty() && TravelBurrow.canLearn(pokemon, DIVE_MOVE)) {
                return pokemon;
            }
        }
        return null;
    }

    /**
     * Height of the surface of the water a player is in: the top of the
     * last water block above their feet. Null when they are not in water.
     */
    @Nullable
    static Double waterSurface(ServerPlayer player) {
        ServerLevel level = (ServerLevel) player.level();
        BlockPos.MutableBlockPos cursor = player.blockPosition().mutable();
        if (!level.getFluidState(cursor).is(FluidTags.WATER)) {
            cursor.move(0, -1, 0);
            if (!level.getFluidState(cursor).is(FluidTags.WATER)) {
                return null;
            }
        }
        while (level.getFluidState(cursor.above()).is(FluidTags.WATER) && cursor.getY() < level.getMaxBuildHeight()) {
            cursor.move(0, 1, 0);
        }
        return cursor.getY() + 1.0D;
    }

    /**
     * Stages the dive from where the player swims to the shore, or returns
     * null: no camera spot, or the actors could not be spawned.
     *
     * @param surface height of the water's surface ({@link #waterSurface})
     * @param shore   the feet the player will stand on at the shore
     */
    @Nullable
    static TravelFlight.Flight dive(ServerLevel level, ServerPlayer player, double surface, BlockPos shore, int now) {
        return swim(level, player, surface, Vec3.atBottomCenterOf(shore), SHORE_GAP, now);
    }

    /**
     * Stages a dive with no shore to reach: off toward the destination, for
     * {@link #OPEN_WATER_DISTANCE} blocks; null as {@link #dive}.
     *
     * @param heading horizontal unit vector toward the destination
     */
    @Nullable
    static TravelFlight.Flight diveToward(ServerLevel level, ServerPlayer player, double surface, Vec3 heading,
                                          int now) {
        Vec3 target = player.position().add(heading.scale(OPEN_WATER_DISTANCE));
        return swim(level, player, surface, target, 0.0D, now);
    }

    /**
     * Stages a swim from the player to a point (its column matters, not its
     * height), stopping {@code gap} blocks short of it.
     */
    @Nullable
    private static TravelFlight.Flight swim(ServerLevel level, ServerPlayer player, double surface, Vec3 target,
                                            double gap, int now) {
        Vec3 flat = new Vec3(target.x - player.getX(), 0.0D, target.z - player.getZ());
        double length = flat.length();
        Vec3 direction = length < 1.0E-3D ? new Vec3(0.0D, 0.0D, 1.0D) : flat.scale(1.0D / length);
        double swim = Math.max(0.0D, length - gap);
        int duration = Mth.clamp((int) Math.round(swim / SPEED), MIN_TICKS, MAX_TICKS);
        Pokemon mount = swimmerOf(player);
        float yaw = TravelFlight.yawOf(direction);
        PokemonEntity swimmer = TravelFlight.spawnMount(level, mount, SWIMMER_PROPERTIES, player.position(), yaw,
                false, 0.0D);
        if (swimmer == null) {
            return null;
        }
        double floatDepth = swimmer.getBbHeight() * FLOAT_SHARE;
        // Where the player is, however deep: from the sea floor the swimmer
        // starts there and rises toward the shore, it does not pop up at the surface.
        // At the surface, it floats half under it.
        double startY = Math.min(player.getY(), surface - floatDepth);
        Vec3 start = new Vec3(player.getX(), startY, player.getZ());
        Vec3 end = new Vec3(player.getX(), surface - floatDepth, player.getZ()).add(direction.scale(swim));
        Vec3 camera = cameraSpot(level, start, end, direction, swimmer.getBbHeight());
        if (camera == null) {
            swimmer.discard();
            return null;
        }
        swimmer.moveTo(start.x, start.y, start.z, yaw, 0.0F);
        // A swimmer with a seat carries the double there (swimmerOf checks for one; Wailmer has one).
        NPCEntity rider = TravelFlight.spawnRider(level, player, start, yaw, swimmer, true);
        if (rider == null) {
            swimmer.discard();
            return null;
        }
        TravelFlight.keepSeated(swimmer, rider, RidingStyle.LIQUID);
        boolean fromSurface = startY >= surface - floatDepth - SURFACE_TOLERANCE;
        if (fromSurface) {
            level.playSound(null, start.x, surface, start.z, SoundEvents.PLAYER_SPLASH_HIGH_SPEED,
                    SoundSource.NEUTRAL, 0.8F, 1.0F);
        }
        Vec3 look = start.add(0.0D, swimmer.getBbHeight(), 0.0D);
        return new TravelFlight.Flight(swimmer, rider, start, direction, false, now, camera, look, duration,
                null, new Dive(end, surface, floatDepth, fromSurface));
    }

    /**
     * Moves the actors to where they are at this tick, with bubbles under
     * water and splashes where it goes down and comes up.
     *
     * @return true once the swim is over (the actors stay where they are until discarded)
     */
    static boolean tick(TravelFlight.Flight flight, int now) {
        Dive dive = flight.dive();
        int elapsed = now - flight.startTick();
        double progress = Math.min(1.0D, elapsed / (double) flight.duration());
        // From the depths no dip: the swim already rises toward the shore, and
        // dipping under a start on the sea floor would go through it.
        Vec3 next = position(flight.spot(), dive.end(), progress, dive.fromSurface() ? DIVE_DEPTH : 0.0D);
        PokemonEntity swimmer = flight.bird();
        swimmer.setDeltaMovement(next.subtract(swimmer.position()));
        swimmer.setPos(next);
        float yaw = TravelFlight.yawOf(flight.direction());
        swimmer.setYRot(yaw);
        swimmer.setYBodyRot(yaw);
        swimmer.setYHeadRot(yaw);
        TravelFlight.keepSeated(swimmer, flight.rider(), RidingStyle.LIQUID);
        flight.rider().setYRot(yaw);
        flight.rider().setYHeadRot(yaw);
        effects((ServerLevel) swimmer.level(), dive, next, progress, elapsed);
        return progress >= 1.0D;
    }

    /**
     * Whether the swimmer is too far from the camera to be seen any more:
     * beyond {@link #SIGHT_IN_AIR} blocks from a camera above the water,
     * {@link #SIGHT_IN_WATER} from one under it, whose view the water's fog
     * cuts short. The rest of the swim is then not worth watching. Cost: a
     * fluid read and a distance.
     */
    static boolean outOfSight(TravelFlight.Flight flight) {
        PokemonEntity swimmer = flight.bird();
        Vec3 camera = flight.camera();
        boolean underWater = swimmer.level().getFluidState(BlockPos.containing(camera)).is(FluidTags.WATER);
        double sight = underWater ? SIGHT_IN_WATER : SIGHT_IN_AIR;
        return swimmer.position().distanceToSqr(camera) > sight * sight;
    }

    /**
     * Where the swimmer is at a point of the swim, from 0 to 1: straight
     * toward the shore (rising from the depths when it started there), and
     * from the surface going down under it and coming back up along a
     * smooth arch, deepest halfway.
     *
     * @param dip how deep the arch goes below the straight line, in blocks
     */
    private static Vec3 position(Vec3 start, Vec3 end, double progress, double dip) {
        Vec3 along = start.lerp(end, Mth.smoothstep(progress));
        return along.subtract(0.0D, dip * Mth.sin((float) (progress * Math.PI)), 0.0D);
    }

    /**
     * Splashes and a splash sound at the dive and at the surfacing, bubbles
     * in between. Cost: one particle packet a tick to the players around.
     */
    private static void effects(ServerLevel level, Dive dive, Vec3 at, double progress, int elapsed) {
        boolean diving = progress < DIVE_SHARE && dive.fromSurface();
        boolean surfacing = progress > 1.0D - DIVE_SHARE;
        if (diving || surfacing) {
            if (elapsed % 5 == 0) {
                level.sendParticles(ParticleTypes.SPLASH, at.x, dive.surface(), at.z, SPLASHES, 0.8D, 0.1D, 0.8D, 0.2D);
            }
            if ((elapsed == 0 && diving) || progress >= 1.0D) {
                level.playSound(null, at.x, dive.surface(), at.z, SoundEvents.PLAYER_SPLASH, SoundSource.NEUTRAL,
                        0.8F, 0.9F);
            }
        } else {
            level.sendParticles(ParticleTypes.BUBBLE, at.x, at.y + 0.5D, at.z, BUBBLES_PER_TICK, 0.4D, 0.4D, 0.4D,
                    0.05D);
        }
    }

    /**
     * Where the camera stands to film the dive: the first candidate around
     * the start, a little above the swimmer, in two blocks without
     * collision (air, or water when the start is deep: the camera then films
     * under water), that sees the start and the middle of the swim; failing
     * that, the first such spot that sees the start. Null when none does.
     *
     * @param height the swimmer's height
     */
    @Nullable
    private static Vec3 cameraSpot(ServerLevel level, Vec3 start, Vec3 end, Vec3 direction, double height) {
        Vec3 target = start.add(0.0D, height * 0.5D, 0.0D);
        Vec3 midway = start.lerp(end, 0.5D).add(0.0D, height * 0.5D, 0.0D);
        Vec3 fallback = null;
        for (double above : CAMERA_HEIGHTS) {
            for (double distance : CAMERA_DISTANCES) {
                for (double angle : CAMERA_ANGLES) {
                    Vec3 offset = direction.yRot((float) Math.toRadians(angle)).scale(distance);
                    Vec3 camera = start.add(offset).add(0.0D, above, 0.0D);
                    if (!free(level, BlockPos.containing(camera)) || !TravelFlight.sees(level, camera, target)) {
                        continue;
                    }
                    if (TravelFlight.sees(level, camera, midway)) {
                        return camera;
                    }
                    if (fallback == null) {
                        fallback = camera;
                    }
                }
            }
        }
        return fallback;
    }

    /**
     * Whether the camera can stand in a block and the one above: loaded,
     * without collision, and no lava (water is fine, the dive is filmed in it).
     */
    private static boolean free(ServerLevel level, BlockPos block) {
        for (BlockPos pos : new BlockPos[]{block, block.above()}) {
            if (!LoadedChunks.isReady(level, pos) || !level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()
                    || level.getFluidState(pos).is(FluidTags.LAVA)) {
                return false;
            }
        }
        return true;
    }
}
