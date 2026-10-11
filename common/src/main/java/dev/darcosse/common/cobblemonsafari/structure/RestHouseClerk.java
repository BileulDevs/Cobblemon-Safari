package dev.darcosse.common.cobblemonsafari.structure;

import com.cobblemon.mod.common.api.npc.NPCClass;
import com.cobblemon.mod.common.api.npc.NPCClasses;
import com.cobblemon.mod.common.entity.npc.NPCEntity;
import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import dev.darcosse.common.cobblemonsafari.config.SafariConfig;
import dev.darcosse.common.cobblemonsafari.config.SafariConfigManager;
import dev.darcosse.common.cobblemonsafari.dimension.SafariDimensions;
import dev.darcosse.common.cobblemonsafari.world.SafariWorldState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * The shopkeeper of a rest house: a Cobblemon NPC standing behind the
 * counter whose dialogue opens the Safari shop ({@code /safari shop}, allowed
 * in a rest house) and lists the missions of the day.
 *
 * <p>Kept by {@link #tick}: whenever a built house is loaded and has no
 * clerk, one is put behind its counter. Spawning lazily, with players
 * around, rather than while the house is built at server start: the house
 * chunks are then loaded without their entities, and an entity added to them
 * is not reliably saved. It also brings back a clerk an operator removed.
 * Server side.
 */
public final class RestHouseClerk {

    /** Tag on every clerk. Part of the save format: renaming it orphans existing clerks. */
    public static final String CLERK_TAG = CobblemonSafari.MOD_ID + ".rest_house_clerk";

    /** NPC class of the clerk, shipped in the mod's datapack. */
    private static final ResourceLocation CLERK_CLASS = CobblemonSafari.id("safari_clerk");

    /**
     * Ticks between two checks. A clerk missing for up to this long after a
     * player arrives is not noticeable; checking every tick would query
     * entities 20 times a second for nothing.
     */
    private static final int CHECK_INTERVAL_TICKS = 100;

    /** Highest stack of roof and ceiling blocks the floor search goes through, from the top. */
    private static final int MAX_ROOF_DEPTH = 6;
    /** Air the clerk needs above the floor: a player-sized NPC. */
    private static final int MIN_HEADROOM = 2;

    private static int ticks;

    private RestHouseClerk() {
    }

    /**
     * Gives every loaded rest house its clerk, once every
     * {@link #CHECK_INTERVAL_TICKS}. Cost: per house, a chunk status check;
     * for loaded houses, one small entity query. Called each server tick.
     */
    public static void tick(MinecraftServer server) {
        if (++ticks < CHECK_INTERVAL_TICKS) {
            return;
        }
        ticks = 0;
        SafariConfig.RestHouses config = SafariConfigManager.get().restHouses;
        ServerLevel safari = SafariDimensions.level(server);
        if (!config.enabled || safari == null) {
            return;
        }
        SafariWorldState state = SafariWorldState.get(server);
        for (SafariConfig.Position position : config.positions) {
            if (state.isHouseBuilt(position.x, position.z)) {
                maintain(safari, position);
            }
        }
    }

    /** Spawns the clerk of one house if it is loaded and has none; removes extra ones. */
    private static void maintain(ServerLevel level, SafariConfig.Position position) {
        Direction facing = RestHouses.doorFacing(position);
        BlockPos column = RestHouseBuilder.clerkColumn(position.x, position.z, facing);
        // Entity ticking: the chunk AND its entities are loaded, so a clerk
        // already there is seen and a new one is saved with the chunk.
        if (!level.isPositionEntityTicking(column)) {
            return;
        }
        int half = RestHouses.HALF_SIZE;
        AABB house = new AABB(position.x - half, level.getMinBuildHeight(), position.z - half,
                position.x + half + 1, level.getMaxBuildHeight(), position.z + half + 1);
        List<NPCEntity> clerks = level.getEntitiesOfClass(NPCEntity.class, house,
                entity -> entity.getTags().contains(CLERK_TAG));
        for (int i = 1; i < clerks.size(); i++) {
            clerks.get(i).discard();
        }
        if (!clerks.isEmpty()) {
            return;
        }
        BlockPos feet = floorUnderRoof(level, column);
        if (feet != null) {
            spawn(level, feet, facing);
        }
    }

    /**
     * The floor of the house at a column: going down from the top, through
     * the roof and ceiling, then through the air of the room, to the first
     * block below. Null when the column has no such room (a house of an older
     * layout, whose wall stands there), so nobody is put on a roof.
     */
    @Nullable
    private static BlockPos floorUnderRoof(ServerLevel level, BlockPos column) {
        int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, column.getX(), column.getZ()) - 1;
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
     * Spawns a clerk standing on the given block, facing the door.
     *
     * @param facing direction the house door opens toward
     */
    private static void spawn(ServerLevel level, BlockPos feet, Direction facing) {
        NPCClass npcClass = NPCClasses.getByIdentifier(CLERK_CLASS);
        if (npcClass == null) {
            CobblemonSafari.LOGGER.error("NPC class {} is missing; rest house at {} has no clerk", CLERK_CLASS,
                    feet.toShortString());
            return;
        }
        NPCEntity clerk = new NPCEntity(level);
        float yaw = facing.toYRot();
        clerk.moveTo(feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D, yaw, 0.0F);
        clerk.setYHeadRot(yaw);
        clerk.setNpc(npcClass);
        clerk.initialize(1);
        clerk.addTag(CLERK_TAG);
        level.addFreshEntity(clerk);
    }

    /**
     * Whether damage to a clerk must be cancelled: all of it except
     * {@code /kill} and the void, like the gate warden. The NPC class is
     * already invulnerable; this also covers creative players.
     */
    public static boolean shouldCancelDamage(Entity entity, DamageSource source) {
        return entity.getTags().contains(CLERK_TAG)
                && !source.is(DamageTypes.GENERIC_KILL)
                && !source.is(DamageTypes.FELL_OUT_OF_WORLD);
    }
}
