package dev.darcosse.common.cobblemonsafari.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * How Safari Wall Screens group into one tilted screen, seen from one block.
 *
 * <p>Wall screens only merge sideways, one block high, at most
 * {@link #MAX_WIDTH} wide. A row of touching screens facing the same way is
 * cut into groups of {@link #MAX_WIDTH} starting from its left end (as seen
 * by the reader), so a row of 5 is a group of 3 then a group of 2. The
 * leftmost block of a group is its anchor: it draws the whole group and its
 * tilt is the group's tilt.
 *
 * @param anchorPos position of the group's leftmost block
 * @param width     number of blocks in the group, 1 to {@link #MAX_WIDTH}
 * @param index     position of the queried block in the group, 0 for the anchor
 */
public record WallScreenLayout(BlockPos anchorPos, int width, int index) {

    /**
     * Widest group, in blocks. Raising it allows wider screens; the mount
     * stays at the group's centre, so a much wider screen would look like it
     * hangs from a single bolt.
     */
    public static final int MAX_WIDTH = 3;

    /**
     * How far left the scan looks for the start of a row. Grouping counts from
     * the row's start, so in a row longer than this the groups past this
     * distance no longer line up between neighbours and some screens go blank.
     * A multiple of {@link #MAX_WIDTH} keeps the first groups exact. Higher
     * costs one more block read per screen per layout refresh.
     */
    private static final int MAX_ROW_SCAN = 48;

    /**
     * The reader's right-hand side for a screen facing the given way. The
     * reader looks against the facing, so their right is the facing turned a
     * quarter counter-clockwise (facing south, the reader looks north, right
     * is east).
     */
    public static Direction rightOf(Direction facing) {
        return facing.getCounterClockWise();
    }

    /** Whether this block is the anchor, i.e. the one that draws the group. */
    public boolean anchor() {
        return index == 0;
    }

    /** Layout of a lone screen, or of something that is not a wall screen at all. */
    public static WallScreenLayout single(BlockPos pos) {
        return new WallScreenLayout(pos.immutable(), 1, 0);
    }

    /**
     * Works out the group the given wall screen belongs to.
     *
     * @param level any level view; client side for drawing, server side for tilting
     * @param pos   position of the screen
     * @param state its current state; anything that is not a wall screen yields {@link #single}
     */
    public static WallScreenLayout compute(BlockGetter level, BlockPos pos, BlockState state) {
        if (!(state.getBlock() instanceof SafariWallScreenBlock)) {
            return single(pos);
        }
        Direction facing = state.getValue(HorizontalDirectionalBlock.FACING);
        Direction right = rightOf(facing);
        Direction left = right.getOpposite();
        int fromRowStart = 0;
        while (fromRowStart < MAX_ROW_SCAN && isPart(level, pos.relative(left, fromRowStart + 1), facing)) {
            fromRowStart++;
        }
        int index = fromRowStart % MAX_WIDTH;
        BlockPos anchor = pos.relative(left, index);
        int width = 1;
        while (width < MAX_WIDTH && isPart(level, anchor.relative(right, width), facing)) {
            width++;
        }
        return new WallScreenLayout(anchor.immutable(), width, index);
    }

    /** Whether the block at a position is a wall screen facing the same way, i.e. part of the same row. */
    public static boolean isPart(BlockGetter level, BlockPos pos, Direction facing) {
        BlockState state = level.getBlockState(pos);
        return state.getBlock() instanceof SafariWallScreenBlock
                && state.getValue(HorizontalDirectionalBlock.FACING) == facing;
    }
}
