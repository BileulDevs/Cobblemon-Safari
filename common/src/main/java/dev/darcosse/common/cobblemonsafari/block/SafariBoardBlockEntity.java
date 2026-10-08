package dev.darcosse.common.cobblemonsafari.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Block entity of a Safari Board.
 *
 * <p>Stores nothing and syncs nothing: everything the screen shows comes from
 * the server through the featured and timer payloads, so a board placed
 * anywhere is always up to date. It exists because block entity renderers
 * are the only way to draw dynamic content on a block, and it caches the
 * screen layout so the multi-block scan does not run every frame.
 */
public class SafariBoardBlockEntity extends BlockEntity {

    /**
     * How often the cached layout is recomputed, in game ticks. Lower reacts
     * faster to boards being added or removed, at the cost of more block reads
     * (up to {@link ScreenLayout#MAX_WIDTH} × {@link ScreenLayout#MAX_HEIGHT}
     * per anchor per refresh).
     */
    private static final long LAYOUT_REFRESH_TICKS = 10L;

    private ScreenLayout layout = ScreenLayout.COVERED;
    private long layoutComputedAt = Long.MIN_VALUE;

    /** Created by the block or by chunk loading. */
    public SafariBoardBlockEntity(BlockPos pos, BlockState state) {
        super(SafariBlockEntities.safariBoard(), pos, state);
    }

    /**
     * The screen this board belongs to, recomputed at most every
     * {@link #LAYOUT_REFRESH_TICKS}. Client side only in practice: the server
     * never asks.
     */
    public ScreenLayout layout() {
        Level level = getLevel();
        if (level == null) {
            return ScreenLayout.COVERED;
        }
        long now = level.getGameTime();
        // The absolute difference also catches a clock that jumped back (/time set).
        if (Math.abs(now - layoutComputedAt) >= LAYOUT_REFRESH_TICKS) {
            layout = ScreenLayout.compute(level, getBlockPos(), getBlockState());
            layoutComputedAt = now;
        }
        return layout;
    }
}
