package dev.darcosse.common.cobblemonsafari.gate;

import com.cobblemon.mod.common.api.npc.NPCClass;
import com.cobblemon.mod.common.api.npc.NPCClasses;
import com.cobblemon.mod.common.api.pokemon.PokemonProperties;
import com.cobblemon.mod.common.entity.npc.NPCEntity;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import dev.darcosse.common.cobblemonsafari.config.SafariConfig;
import dev.darcosse.common.cobblemonsafari.config.SafariConfigManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The people and Pokémon living at a Safari Gate: a Safari Warden standing
 * beside it and a few of his Pokémon roaming the plaza.
 *
 * <p>They are ordinary entities, recognised by scoreboard tags rather than
 * stored ids, so a gate needs no saved state and adopts whatever residents
 * are around it. The warden is a Cobblemon NPC defined by the datapack
 * ({@code data/cobblemon_safari/npcs/safari_warden.json}); the Pokémon are wild,
 * uncatchable, protected from battles by {@link SafariGate}, and, like the
 * warden, immune to all damage but {@code /kill} ({@link #shouldCancelDamage}).
 * They are not persistent: Cobblemon's despawner removes them once nobody
 * is around, and the gate brings them back when a player comes near, which
 * also cleans up any that wandered off for good.
 */
public final class GateResidents {

    /** Tag on the warden. Part of the save format: renaming it orphans existing wardens. */
    public static final String WARDEN_TAG = CobblemonSafari.MOD_ID + ".gate_warden";

    /** Tag on every roaming Pokémon. Part of the save format, like {@link #WARDEN_TAG}. */
    public static final String COMPANION_TAG = CobblemonSafari.MOD_ID + ".gate_companion";

    /** Prefix of the per-slot tag, so that each configured Pokémon exists once. */
    private static final String SLOT_TAG_PREFIX = COMPANION_TAG + ".";

    /** NPC class of the warden, shipped in the mod's datapack. */
    private static final ResourceLocation WARDEN_CLASS = CobblemonSafari.id("safari_warden");

    /**
     * Residents are only looked after while a player is this close, in
     * blocks. Spawning for nobody would be undone by Cobblemon's despawner.
     */
    private static final double PLAYER_RANGE = 48.0D;

    /**
     * Where the warden stands relative to the middle of the gate: this many
     * blocks along the gate's axis and across it. Tried in all four
     * quadrants; the structure keeps those spots clear whatever its rotation.
     */
    private static final int WARDEN_ALONG = 3;
    private static final int WARDEN_ACROSS = 2;

    /** Box half-size searched for the warden; beyond it a new one is spawned. */
    private static final double WARDEN_SEARCH = 12.0D;

    /** Residents farther than roamRadius × this are teleported back instead of walked back. */
    private static final double TELEPORT_FACTOR = 2.0D;

    /** Pokémon are searched within roamRadius × this; any farther are lost and replaced. */
    private static final double SEARCH_FACTOR = 3.0D;

    /**
     * Walking speed used to bring a stray back, as a multiplier of its
     * movement speed. Cobblemon's own wandering uses 0.35 for NPCs; a bit
     * faster reads as "heading back" rather than strolling.
     */
    private static final float RETURN_SPEED = 0.5F;

    /** Speed multiplier for mobs moved by plain navigation, which expects 1 for a normal walk. */
    private static final double NAVIGATION_SPEED = 1.0D;

    /** How close, in blocks, a stray must get to the gate for its walk back to count as done. */
    private static final int RETURN_CLOSE_ENOUGH = 2;

    /**
     * No Pokémon spawns closer than this to the gate's plane, in blocks, so
     * none blocks the doorway or stands inside the pane.
     */
    private static final int DOORWAY_CLEARANCE = 2;

    /**
     * Lowest top, as a fraction of a block, of a collision box that counts as
     * ground. Half a block accepts slabs and paths and rejects carpets, snow
     * layers and pressure plates, on which a mob would sink into the block.
     */
    private static final double MIN_GROUND_TOP = 0.5D;

    /** Random spots tried before giving up on spawning a Pokémon until the next check. */
    private static final int SPAWN_ATTEMPTS = 8;

    /** Companion entries already reported as broken, to log each one once. */
    private static final Set<String> REPORTED = new HashSet<>();

    private GateResidents() {
    }

    /** Whether an entity is one of the warden's roaming Pokémon. */
    public static boolean isCompanion(Entity entity) {
        return entity.getTags().contains(COMPANION_TAG);
    }

    /** Whether an entity lives at a gate: the warden or one of his Pokémon. */
    public static boolean isResident(Entity entity) {
        return entity.getTags().contains(COMPANION_TAG) || entity.getTags().contains(WARDEN_TAG);
    }

    /**
     * Whether damage to a gate resident must be cancelled: all of it, from
     * any source including creative players, except {@code /kill} (so
     * operators can still remove one) and the void (an entity that cannot
     * die there would fall forever). Server side.
     */
    public static boolean shouldCancelDamage(Entity entity, DamageSource source) {
        return isResident(entity)
                && !source.is(DamageTypes.GENERIC_KILL)
                && !source.is(DamageTypes.FELL_OUT_OF_WORLD);
    }

    /**
     * Makes sure the warden and every configured Pokémon are at the gate.
     * Two entity box queries, plus a spawn now and then; called every few
     * seconds per gate, only while a player is near.
     *
     * @param middle bottom block in the middle of the gate
     * @param axis   horizontal axis the gate spans
     */
    public static void maintain(ServerLevel level, BlockPos middle, Direction.Axis axis) {
        Vec3 centre = Vec3.atBottomCenterOf(middle);
        if (!level.hasNearbyAlivePlayer(centre.x, centre.y, centre.z, PLAYER_RANGE)) {
            return;
        }
        SafariConfig.Gate config = SafariConfigManager.get().gate;
        Direction along = Direction.fromAxisAndDirection(axis, Direction.AxisDirection.POSITIVE);
        Direction across = along.getClockWise();
        if (config.spawnWarden) {
            maintainWarden(level, middle, axis, along, across, config.roamRadius);
        }
        maintainCompanions(level, middle, axis, config);
    }

    /**
     * Keeps exactly one warden, wandering within the roaming area. He walks
     * around on his own (Cobblemon's wander behaviour, see the NPC class);
     * this only brings him back when he strays too far.
     */
    private static void maintainWarden(ServerLevel level, BlockPos middle, Direction.Axis axis, Direction along,
                                       Direction across, int roamRadius) {
        List<NPCEntity> wardens = level.getEntitiesOfClass(NPCEntity.class,
                new AABB(middle).inflate(WARDEN_SEARCH), entity -> entity.getTags().contains(WARDEN_TAG));
        if (wardens.isEmpty()) {
            BlockPos spot = wardenSpot(level, middle, along, across);
            if (spot != null) {
                spawnWarden(level, spot);
            } else {
                reportOnce(middle.toShortString(), "No free spot for the Safari Warden beside the gate at {}");
            }
            return;
        }
        // Two gates close together, or a copy pasted by an operator: keep one.
        for (int i = 1; i < wardens.size(); i++) {
            wardens.get(i).discard();
        }
        keepWithinReach(level, wardens.get(0), middle, axis, roamRadius);
    }

    /**
     * Walks a resident back toward the gate when it is beyond the roaming
     * radius, or teleports it when it is much farther (stuck behind a
     * wall, pushed down a slope).
     */
    private static void keepWithinReach(ServerLevel level, Mob resident, BlockPos middle, Direction.Axis axis,
                                        int roamRadius) {
        double distance = horizontalDistance(resident.position(), Vec3.atBottomCenterOf(middle));
        if (distance > roamRadius * TELEPORT_FACTOR) {
            BlockPos back = roamSpot(level, middle, axis, roamRadius, level.random);
            if (back != null) {
                resident.teleportTo(back.getX() + 0.5D, back.getY(), back.getZ() + 0.5D);
            }
        } else if (distance > roamRadius) {
            walkBack(resident, middle);
        }
    }

    /**
     * Sends a resident walking to the gate. Brain-driven mobs (Cobblemon's
     * NPCs and Pokémon) get a walk target, which their own movement task
     * follows; a plain navigation order would be overwritten by their next
     * wander. Mobs without that memory fall back to navigation.
     */
    private static void walkBack(Mob resident, BlockPos middle) {
        Brain<?> brain = resident.getBrain();
        if (brain.checkMemory(MemoryModuleType.WALK_TARGET, MemoryStatus.REGISTERED)) {
            brain.setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(middle, RETURN_SPEED, RETURN_CLOSE_ENOUGH));
        } else {
            resident.getNavigation().moveTo(middle.getX() + 0.5D, middle.getY(), middle.getZ() + 0.5D, NAVIGATION_SPEED);
        }
    }

    /** First free standing spot among the four quadrants beside the gate, or null. */
    @Nullable
    private static BlockPos wardenSpot(ServerLevel level, BlockPos middle, Direction along, Direction across) {
        int[][] signs = {{1, 1}, {-1, 1}, {1, -1}, {-1, -1}};
        for (int[] sign : signs) {
            BlockPos column = middle.relative(along, WARDEN_ALONG * sign[0]).relative(across, WARDEN_ACROSS * sign[1]);
            BlockPos spot = surface(level, column);
            if (canStand(level, spot)) {
                return spot;
            }
        }
        return null;
    }

    /** Spawns the warden, if the datapack still defines him. */
    private static void spawnWarden(ServerLevel level, BlockPos spot) {
        NPCClass npcClass = NPCClasses.getByIdentifier(WARDEN_CLASS);
        if (npcClass == null) {
            reportOnce(WARDEN_CLASS.toString(), "NPC class {} is missing, Safari Gates will have no warden");
            return;
        }
        NPCEntity warden = new NPCEntity(level);
        warden.moveTo(spot.getX() + 0.5D, spot.getY(), spot.getZ() + 0.5D, level.random.nextFloat() * 360.0F, 0.0F);
        warden.setNpc(npcClass);
        warden.initialize(1);
        warden.addTag(WARDEN_TAG);
        level.addFreshEntity(warden);
    }

    /** Keeps one Pokémon per configured entry within the roaming area. */
    private static void maintainCompanions(ServerLevel level, BlockPos middle, Direction.Axis axis,
                                           SafariConfig.Gate config) {
        double roam = config.roamRadius;
        List<PokemonEntity> found = level.getEntitiesOfClass(PokemonEntity.class,
                new AABB(middle).inflate(roam * SEARCH_FACTOR), GateResidents::isCompanion);
        List<String> entries = config.companions;
        boolean[] present = new boolean[entries.size()];
        List<PokemonEntity> kept = new ArrayList<>();
        for (PokemonEntity pokemon : found) {
            int slot = slotOf(pokemon);
            // Slots beyond the list (an entry was removed) and duplicates go.
            if (slot < 0 || slot >= present.length || present[slot]) {
                pokemon.discard();
                continue;
            }
            present[slot] = true;
            kept.add(pokemon);
        }
        for (PokemonEntity pokemon : kept) {
            keepWithinReach(level, pokemon, middle, axis, config.roamRadius);
        }
        for (int slot = 0; slot < present.length; slot++) {
            if (!present[slot]) {
                spawnCompanion(level, middle, axis, config.roamRadius, slot, entries.get(slot));
            }
        }
    }

    /** Spawns the Pokémon of one slot at a random free spot of the plaza. */
    private static void spawnCompanion(ServerLevel level, BlockPos middle, Direction.Axis axis, int roamRadius,
                                       int slot, String entry) {
        BlockPos spot = roamSpot(level, middle, axis, roamRadius, level.random);
        if (spot == null) {
            return;
        }
        PokemonEntity pokemon;
        try {
            // "uncatchable" is Cobblemon's own flag: balls bounce off, like a Pokémon owned by an NPC.
            pokemon = PokemonProperties.Companion.parse(entry + " uncatchable").createEntity(level);
        } catch (RuntimeException e) {
            reportOnce(entry, "Gate companion '{}' could not be created; check gate.companions in the config");
            return;
        }
        pokemon.moveTo(spot.getX() + 0.5D, spot.getY(), spot.getZ() + 0.5D, level.random.nextFloat() * 360.0F, 0.0F);
        pokemon.addTag(COMPANION_TAG);
        pokemon.addTag(SLOT_TAG_PREFIX + slot);
        // Backstop for the damage hook (shouldCancelDamage): vanilla's flag
        // already stops most hits before any loader event fires.
        pokemon.setInvulnerable(true);
        level.addFreshEntity(pokemon);
    }

    /** Slot recorded on a companion, or -1 when it has none. */
    private static int slotOf(Mob pokemon) {
        for (String tag : pokemon.getTags()) {
            if (tag.startsWith(SLOT_TAG_PREFIX)) {
                try {
                    return Integer.parseInt(tag.substring(SLOT_TAG_PREFIX.length()));
                } catch (NumberFormatException e) {
                    return -1;
                }
            }
        }
        return -1;
    }

    /** A random free standing spot within the roaming area, away from the doorway, or null. */
    @Nullable
    private static BlockPos roamSpot(ServerLevel level, BlockPos middle, Direction.Axis axis, int radius,
                                     RandomSource random) {
        for (int attempt = 0; attempt < SPAWN_ATTEMPTS; attempt++) {
            int dx = random.nextInt(radius * 2 + 1) - radius;
            int dz = random.nextInt(radius * 2 + 1) - radius;
            int acrossOffset = axis == Direction.Axis.X ? dz : dx;
            if (dx * dx + dz * dz > radius * radius || Math.abs(acrossOffset) < DOORWAY_CLEARANCE) {
                continue;
            }
            BlockPos spot = surface(level, middle.offset(dx, 0, dz));
            if (canStand(level, spot)) {
                return spot;
            }
        }
        return null;
    }

    /** First free block above the ground of a column, leaves ignored so nobody spawns on a tree. */
    private static BlockPos surface(ServerLevel level, BlockPos column) {
        return new BlockPos(column.getX(),
                level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column.getX(), column.getZ()),
                column.getZ());
    }

    /**
     * Whether a mob can stand at a position: ground below, no liquid, two free
     * blocks.
     *
     * <p>Ground is "has a collision box reaching at least half a block up",
     * not {@code isFaceSturdy}: a sturdy face must reach the very top of the
     * block, which dirt paths (15/16 high), farmland, soul sand and mud do
     * not. The gate's plaza is made of dirt paths, so a sturdy-face test found
     * no spot at all for the warden.
     */
    private static boolean canStand(ServerLevel level, BlockPos pos) {
        BlockPos below = pos.below();
        VoxelShape ground = level.getBlockState(below).getCollisionShape(level, below);
        return !ground.isEmpty()
                && ground.max(Direction.Axis.Y) >= MIN_GROUND_TOP
                && level.getFluidState(pos).isEmpty()
                && level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()
                && level.getBlockState(pos.above()).getCollisionShape(level, pos.above()).isEmpty();
    }

    /** Distance ignoring height, so a Pokémon on a slope is not counted as stray. */
    private static double horizontalDistance(Vec3 a, Vec3 b) {
        double dx = a.x - b.x;
        double dz = a.z - b.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** Logs a configuration problem once per offending value, not every check. */
    private static void reportOnce(String value, String message) {
        if (REPORTED.add(value)) {
            CobblemonSafari.LOGGER.warn(message, value);
        }
    }
}
