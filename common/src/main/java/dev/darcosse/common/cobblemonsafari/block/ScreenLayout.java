package dev.darcosse.common.cobblemonsafari.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * How Safari Board blocks group into one big screen, seen from one block.
 *
 * <p>Boards facing the same way and touching side by side merge into a single
 * display, like the multi-block screens of video mods. The screen is the
 * largest rectangle whose bottom-left corner (as seen by someone reading it)
 * is the anchor block: only the anchor draws, so a screen is drawn exactly
 * once whatever its size.
 *
 * <p>Only rectangles merge. Boards sticking out of an irregular shape stay
 * blank rather than guessing which screen they belong to.
 *
 * @param anchor whether this block is the bottom-left corner and draws the screen
 * @param width  screen width in blocks, 1 when not an anchor
 * @param height screen height in blocks, 1 when not an anchor
 */
public record ScreenLayout(boolean anchor, int width, int height) {

    /**
     * Largest screen, in blocks. Bounds the scan and the render bounding box.
     * Raising it allows bigger walls at the cost of a longer scan (width ×
     * height block reads per refresh, see {@code SafariBoardBlockEntity}).
     */
    public static final int MAX_WIDTH = 16;
    public static final int MAX_HEIGHT = 9;

    /** Layout of a block that belongs to another block's screen. */
    public static final ScreenLayout COVERED = new ScreenLayout(false, 1, 1);

    /**
     * Works out the screen the given board belongs to.
     *
     * @param level any level view; client side in practice
     * @param pos   position of the board
     * @param state its current state; anything that is not a board yields {@link #COVERED}
     */
    public static ScreenLayout compute(BlockGetter level, BlockPos pos, BlockState state) {
        if (!(state.getBlock() instanceof SafariBoardBlock)) {
            return COVERED;
        }
        Direction facing = state.getValue(HorizontalDirectionalBlock.FACING);
        Direction right = rightOf(facing);
        Direction left = right.getOpposite();
        if (isPart(level, pos.relative(left), facing) || isPart(level, pos.below(), facing)) {
            return COVERED;
        }
        int width = 1;
        while (width < MAX_WIDTH && isPart(level, pos.relative(right, width), facing)) {
            width++;
        }
        int height = 1;
        while (height < MAX_HEIGHT && rowComplete(level, pos.above(height), right, width, facing)) {
            height++;
        }
        return new ScreenLayout(true, width, height);
    }

    /**
     * The reader's right-hand side for a board facing the given way. The reader
     * looks against the facing, so their right is the facing turned a quarter
     * counter-clockwise (facing south, the reader looks north, right is east).
     */
    public static Direction rightOf(Direction facing) {
        return facing.getCounterClockWise();
    }

    /** Whether a whole row of the candidate rectangle is made of matching boards. */
    private static boolean rowComplete(BlockGetter level, BlockPos rowStart, Direction right, int width,
                                       Direction facing) {
        for (int i = 0; i < width; i++) {
            if (!isPart(level, rowStart.relative(right, i), facing)) {
                return false;
            }
        }
        return true;
    }

    /** Whether the block at a position is a board facing the same way, i.e. part of the same wall. */
    private static boolean isPart(BlockGetter level, BlockPos pos, Direction facing) {
        BlockState state = level.getBlockState(pos);
        return state.getBlock() instanceof SafariBoardBlock
                && state.getValue(HorizontalDirectionalBlock.FACING) == facing;
    }
}
