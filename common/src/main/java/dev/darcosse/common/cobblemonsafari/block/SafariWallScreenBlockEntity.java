package dev.darcosse.common.cobblemonsafari.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Block entity of a Safari Wall Screen.
 *
 * <p>Stores and syncs nothing, like the board's: the picture comes from the
 * featured and timer payloads, the tilt from the block state. It exists so a
 * renderer can draw the tilted panel, and it caches the group layout so the
 * row scan does not run every frame.
 */
public class SafariWallScreenBlockEntity extends BlockEntity {

    /**
     * How often the cached layout is recomputed, in game ticks. Same value as
     * the board: lower reacts faster to screens being added or removed, at the
     * cost of more block reads (up to the row scan length plus
     * {@link WallScreenLayout#MAX_WIDTH} per screen per refresh).
     */
    private static final long LAYOUT_REFRESH_TICKS = 10L;

    private WallScreenLayout layout;
    private long layoutComputedAt = Long.MIN_VALUE;

    /** Created by the block or by chunk loading. */
    public SafariWallScreenBlockEntity(BlockPos pos, BlockState state) {
        super(SafariBlockEntities.safariWallScreen(), pos, state);
        this.layout = WallScreenLayout.single(pos);
    }

    /**
     * The group this screen belongs to, recomputed at most every
     * {@link #LAYOUT_REFRESH_TICKS}. Client side only in practice: the server
     * computes its own when a player tilts a group, never through this cache.
     */
    public WallScreenLayout layout() {
        Level level = getLevel();
        if (level == null) {
            return layout;
        }
        long now = level.getGameTime();
        // The absolute difference also catches a clock that jumped back (/time set).
        if (Math.abs(now - layoutComputedAt) >= LAYOUT_REFRESH_TICKS) {
            layout = WallScreenLayout.compute(level, getBlockPos(), getBlockState());
            layoutComputedAt = now;
        }
        return layout;
    }
}
