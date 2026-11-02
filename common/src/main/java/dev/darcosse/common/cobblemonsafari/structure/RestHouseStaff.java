package dev.darcosse.common.cobblemonsafari.structure;

import com.cobblemon.mod.common.api.npc.NPCClass;
import com.cobblemon.mod.common.api.npc.NPCClasses;
import com.cobblemon.mod.common.entity.npc.NPCEntity;
import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import dev.darcosse.common.cobblemonsafari.config.SafariConfigManager;
import dev.darcosse.common.cobblemonsafari.dimension.SafariDimensions;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * The staff of a rest house, two Cobblemon NPCs:
 * <ul>
 *   <li>the clerk, behind the counter, whose dialogue opens the Safari shop
 *   ({@code q.player.safari_shop()}, allowed in a rest house) and lists the
 *   missions of the day;</li>
 *   <li>the guide, in the south hall, whose dialogue takes the player to
 *   another rest house of the map ({@code RestHouseTravel}); only while
 *   {@code restHouses.travel.guide} is on, and removed when it is off.</li>
 * </ul>
 *
 * <p>Kept by {@link #tick}: whenever a built house is loaded and misses one
 * of them, they are put at their spot. Spawning lazily, with players
 * around, rather than while the house is built at server start: the house
 * chunks are then loaded without their entities, and an entity added to them
 * is not reliably saved. It also brings back one an operator removed.
 * Server side.
 */
public final class RestHouseStaff {

    /** Tag on every clerk. Part of the save format: renaming it orphans existing clerks. */
    public static final String CLERK_TAG = CobblemonSafari.MOD_ID + ".rest_house_clerk";

    /** Tag on every guide. Part of the save format, like {@link #CLERK_TAG}. */
    public static final String GUIDE_TAG = CobblemonSafari.MOD_ID + ".rest_house_guide";

    /**
     * Ticks between two checks. Someone missing for up to this long after a
     * player arrives is not noticeable; checking every tick would query
     * entities 20 times a second for nothing.
     */
    private static final int CHECK_INTERVAL_TICKS = 100;

    /** Highest stack of roof and ceiling blocks the floor search goes through, from the top. */
    private static final int MAX_ROOF_DEPTH = 6;
    /** Air a staff member needs above the floor: a player-sized NPC. */
    private static final int MIN_HEADROOM = 2;

    /**
     * Distance from their spot, in blocks, beyond which a staff member is
     * put back on it. Staff do not walk, so only a wrong spot (a roof, from
     * before this check) gets that far; smaller would fight the small moves
     * of their head-turning.
     */
    private static final double MISPLACED_DISTANCE = 1.5D;

    /**
     * Height of the template's roof above its ground layer (its top layer):
     * where the floor search starts in a house whose height is known.
     */
    private static final int HOUSE_ROOF_ABOVE_GROUND = RestHouses.TEMPLATE_HEIGHT - 1;

    /**
     * How close, in blocks, a player must be to a guide for the guide's
     * travel to work (see {@link #isNearGuide}). Survival players reach an
     * entity from 3 blocks; the margin covers the time the dialogue stays
     * open.
     */
    private static final double GUIDE_TALK_RANGE = 6.0D;

    /**
     * One staff member: how to recognise them, their NPC class, where they
     * stand and which way they face, from the house centre and the
     * direction its door opens toward, and whether the config wants them.
     */
    private record Role(String tag, ResourceLocation npcClass,
                        BiFunction<BlockPos, Direction, BlockPos> column,
                        Function<Direction, Direction> facing,
                        BooleanSupplier enabled) {
    }

    /** Both roles, clerk first: a house loaded for a moment gets its shop before its guide. */
    private static final List<Role> ROLES = List.of(
            new Role(CLERK_TAG, CobblemonSafari.id("safari_clerk"),
                    (centre, door) -> RestHouseBuilder.clerkColumn(centre.getX(), centre.getZ(), door),
                    // Behind the counter, looking at the door the players come in through.
                    door -> door,
                    () -> true),
            new Role(GUIDE_TAG, CobblemonSafari.id("safari_guide"),
                    (centre, door) -> RestHouseBuilder.guideColumn(centre.getX(), centre.getZ(), door),
                    RestHouseBuilder::guideFacing,
                    () -> SafariConfigManager.get().restHouses.travel.guide)
    );

    private static int ticks;

    private RestHouseStaff() {
    }

    /**
     * Gives every loaded rest house its staff, once every
     * {@link #CHECK_INTERVAL_TICKS}. Cost: per house, a chunk status check
     * per role; for loaded houses, one small entity query per role. Called
     * each server tick.
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
        for (RestHouses.HouseSite site : RestHouses.sites()) {
            // A planned house is bare ground: the floor search would find a cave under it.
            if (!site.built()) {
                continue;
            }
            for (Role role : ROLES) {
                maintain(safari, site, role);
            }
        }
    }

    /**
     * Spawns one role of one house if it is loaded and has none, removes
     * extra ones, and puts back one standing away from their spot.
     */
    private static void maintain(ServerLevel level, RestHouses.HouseSite site, Role role) {
        int centerX = site.x();
        int centerZ = site.z();
        Direction door = site.facing();
        BlockPos column = role.column().apply(new BlockPos(centerX, 0, centerZ), door);
        // Entity ticking: the chunk AND its entities are loaded, so someone
        // already there is seen and a new one is saved with the chunk.
        if (!level.isPositionEntityTicking(column)) {
            return;
        }
        int half = RestHouses.HALF_SIZE;
        // The house's own height only when known: staff of a house in a cave
        // below (or above) must not be counted as this one's.
        int minY = site.minY() == RestHouses.UNKNOWN_Y ? level.getMinBuildHeight() : site.minY();
        int maxY = Math.min(site.maxY(), level.getMaxBuildHeight());
        AABB house = new AABB(centerX - half, minY, centerZ - half,
                centerX + half + 1, maxY, centerZ + half + 1);
        List<NPCEntity> found = level.getEntitiesOfClass(NPCEntity.class, house,
                entity -> entity.getTags().contains(role.tag()));
        // Switched off in the config: the role goes, and is not brought back.
        int keep = role.enabled().getAsBoolean() ? 1 : 0;
        for (int i = keep; i < found.size(); i++) {
            found.get(i).discard();
        }
        if (keep == 0) {
            return;
        }
        BlockPos feet = floorUnderRoof(level, column, roofOf(level, site, column));
        if (feet == null) {
            return;
        }
        if (found.isEmpty()) {
            spawn(level, feet, role.facing().apply(door), role);
            return;
        }
        // A clerk put on the roof by an older version is in the house's box,
        // so found and kept: without this, they stayed there for good.
        NPCEntity staff = found.get(0);
        if (staff.distanceToSqr(Vec3.atBottomCenterOf(feet)) > MISPLACED_DISTANCE * MISPLACED_DISTANCE) {
            place(staff, feet, role.facing().apply(door));
        }
    }

    /**
     * The top of the house's roof at a staff column, where the floor search
     * starts. From the house's own height when it is known (every house
     * built since 1.4.0, and every underground one): the highest block
     * within the template. The sky's heightmap is only a fallback for older
     * houses, without leaves: a jungle tree or a big oak left standing
     * beside the house spreads its leaves over the roof, and the air
     * between them and the roof was taken for the room, which put the clerk
     * on the roof. Underground, the sky's heightmap gives the cave's roof,
     * far above.
     */
    private static int roofOf(ServerLevel level, RestHouses.HouseSite site, BlockPos column) {
        if (site.groundY() != RestHouses.UNKNOWN_Y) {
            return roofTop(level, column, site.groundY());
        }
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column.getX(), column.getZ()) - 1;
    }

    /**
     * The floor of the house at a column: going down from the top, through
     * the roof and ceiling, then through the air of the room, to the first
     * block below. Null when the column has no such room (a wall stands
     * there), so nobody is put on a roof.
     */
    @Nullable
    private static BlockPos floorUnderRoof(ServerLevel level, BlockPos column, int top) {
        int y = top;
        while (y > top - MAX_ROOF_DEPTH && !level.getBlockState(column.atY(y)).isAir()) {
            y--;
        }
        if (y == top || !level.getBlockState(column.atY(y)).isAir()) {
            return null;
        }
        int headroom = 0;
        while (y > level.getMinBuildHeight() && level.getBlockState(column.atY(y)).isAir()) {
            headroom++;
            y--;
        }
        return headroom >= MIN_HEADROOM ? column.atY(y + 1) : null;
    }

    /**
     * The top of a house's roof at a column: the highest block within the
     * template's height above its ground layer. Whatever stands higher (a
     * cave's roof, a tree's leaves) is never reached.
     */
    private static int roofTop(ServerLevel level, BlockPos column, int groundY) {
        int y = groundY + HOUSE_ROOF_ABOVE_GROUND;
        while (y > groundY && level.getBlockState(column.atY(y)).isAir()) {
            y--;
        }
        return y;
    }

    /**
     * Spawns a staff member standing on the given block.
     *
     * @param facing direction they look toward
     */
    private static void spawn(ServerLevel level, BlockPos feet, Direction facing, Role role) {
        NPCClass npcClass = NPCClasses.getByIdentifier(role.npcClass());
        if (npcClass == null) {
            CobblemonSafari.LOGGER.error("NPC class {} is missing; rest house at {} goes without it", role.npcClass(),
                    feet.toShortString());
            return;
        }
        NPCEntity npc = new NPCEntity(level);
        place(npc, feet, facing);
        npc.setNpc(npcClass);
        npc.initialize(1);
        npc.addTag(role.tag());
        level.addFreshEntity(npc);
    }

    /** Puts a staff member on a block, looking the given way. */
    private static void place(NPCEntity npc, BlockPos feet, Direction facing) {
        float yaw = facing.toYRot();
        npc.moveTo(feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D, yaw, 0.0F);
        npc.setYHeadRot(yaw);
    }

    /**
     * Whether a rest house guide stands within talking range of the player:
     * what the guide's travel requires, so the travel functions cannot be
     * used from another dialogue as a free teleport. One small entity box
     * query.
     */
    public static boolean isNearGuide(ServerPlayer player) {
        return !player.level().getEntitiesOfClass(NPCEntity.class,
                player.getBoundingBox().inflate(GUIDE_TALK_RANGE),
                entity -> entity.getTags().contains(GUIDE_TAG)).isEmpty();
    }

    /**
     * Whether damage to a staff member must be cancelled: all of it except
     * {@code /kill} and the void, like the gate warden. The NPC classes are
     * already invulnerable; this also covers creative players.
     */
    public static boolean shouldCancelDamage(Entity entity, DamageSource source) {
        return (entity.getTags().contains(CLERK_TAG) || entity.getTags().contains(GUIDE_TAG))
                && !source.is(DamageTypes.GENERIC_KILL)
                && !source.is(DamageTypes.FELL_OUT_OF_WORLD);
    }
}
