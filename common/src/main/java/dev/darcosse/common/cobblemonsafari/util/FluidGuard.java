package dev.darcosse.common.cobblemonsafari.util;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

/**
 * Keeps water and lava from hanging in the air when the builds remove
 * blocks without updating their neighbours.
 *
 * <p>Every build of the mod (rest houses, chambers, the trees cut around
 * gates and houses) sets blocks with UPDATE_KNOWN_SHAPE and no neighbour
 * update, on purpose: a neighbour update would make plants that lost their
 * support drop as items and leaves decay in chains. The price: a fluid next
 * to a removed block is not told, and stays frozen beside the gap, floating,
 * until a block next to it changes; then it pours. Two answers, by case:
 * <ul>
 *   <li>shaping the ground or a house's volume: the block touching a fluid is
 *       simply not opened ({@link #touchesFluid}); the fluid keeps its wall,
 *       nothing pours onto the plaza or into the house;</li>
 *   <li>removing a tree, whose logs and leaves may stand in a pond: the block
 *       is emptied the way vanilla would leave it ({@link #remove}), a
 *       waterlogged leaf turning back into the water it held, and the fluids
 *       around are told to update, so they settle as if a player had cut
 *       the tree.</li>
 * </ul>
 */
public final class FluidGuard {

    private FluidGuard() {
    }

    /**
     * Whether a block touches a fluid on one of its six sides. Six block
     * reads; the caller decides which of its own blocks count (see
     * {@link #touchesFluid(LevelReader, BlockPos, Inside)}).
     */
    public static boolean touchesFluid(LevelReader level, BlockPos pos) {
        return touchesFluid(level, pos, side -> false);
    }

    /**
     * Whether a block touches, on one of its six sides, a fluid that is not
     * part of the volume being emptied: water inside that volume is about to
     * go and does not count.
     *
     * @param inside which neighbours belong to the volume being emptied
     */
    public static boolean touchesFluid(LevelReader level, BlockPos pos, Inside inside) {
        BlockPos.MutableBlockPos side = new BlockPos.MutableBlockPos();
        for (Direction direction : Direction.values()) {
            side.setWithOffset(pos, direction);
            // The fluid read first: cheap and almost always empty.
            if (!level.getFluidState(side).isEmpty() && !inside.contains(side)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Removes a block as vanilla would leave its spot: the fluid it held
     * (a waterlogged leaf leaves its water), else air. Then every fluid
     * touching the spot, and the one left in it, is scheduled to update, so
     * it flows into the gap or settles instead of hanging there. Cost: six
     * block reads, and a scheduled tick per fluid found (none away from water).
     *
     * @param flags the caller's block flags (no neighbour update)
     */
    public static void remove(ServerLevel level, BlockPos pos, int flags) {
        FluidState held = level.getBlockState(pos).getFluidState();
        BlockState left = held.isEmpty() ? Blocks.AIR.defaultBlockState() : held.createLegacyBlock();
        level.setBlock(pos, left, flags);
        if (!held.isEmpty()) {
            level.scheduleTick(pos, held.getType(), held.getType().getTickDelay(level));
        }
        BlockPos.MutableBlockPos side = new BlockPos.MutableBlockPos();
        for (Direction direction : Direction.values()) {
            side.setWithOffset(pos, direction);
            FluidState fluid = level.getFluidState(side);
            if (!fluid.isEmpty()) {
                level.scheduleTick(side.immutable(), fluid.getType(), fluid.getType().getTickDelay(level));
            }
        }
    }

    /** Which positions belong to a volume being emptied. */
    @FunctionalInterface
    public interface Inside {
        /** Whether the position is part of the volume. */
        boolean contains(BlockPos pos);
    }
}
