package dev.darcosse.common.cobblemonsafari.block;

import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import dev.darcosse.common.cobblemonsafari.dimension.SafariDimensions;
import dev.darcosse.common.cobblemonsafari.network.SafariNetwork;
import dev.darcosse.common.cobblemonsafari.network.SafariWallScreenOpenPayload;
import dev.darcosse.common.cobblemonsafari.network.SafariWallScreenSetPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Server side of the wall screen settings: who may change a screen, opening
 * the settings screen on a client, and applying what the client sends back.
 *
 * <p>Rules: operators may change any screen. Other players may change the
 * screens outside the Safari, except those of a Safari Gate's plaza (any
 * screen within {@link #GATE_LOCK_HORIZONTAL} blocks of a gate pane), which
 * belong to the structure.
 *
 * <p>Every request is re-checked here, whatever the client screen allowed:
 * the screen only reflects what the server said when it opened.
 */
public final class WallScreenSettings {

    /** Permission level of operators, as used by the Safari commands. */
    private static final int OP_LEVEL = 2;

    /**
     * Farthest a player may stand from the screen they change, in blocks.
     * Above vanilla's reach so a player stepping back to judge the tilt
     * stays in control; it also bounds which blocks a forged packet can make
     * the server read, keeping them in loaded chunks.
     */
    private static final double MAX_DISTANCE = 8.0D;

    /**
     * How far around a screen a Safari Gate pane locks it, horizontally and
     * vertically, in blocks. The gate structure is 27 × 12 × 27 with the gate
     * near its middle, so 14 covers its whole plaza. Larger also locks
     * screens players build just outside a gate; the scan reads
     * (2h+1)² × (2v+1) blocks, about 21,000 here, only when a player opens
     * or changes settings.
     */
    private static final int GATE_LOCK_HORIZONTAL = 14;
    private static final int GATE_LOCK_VERTICAL = 12;

    private WallScreenSettings() {
    }

    /**
     * Whether a player may change the screen at a position.
     *
     * @param level the level the screen is in
     */
    public static boolean canEdit(ServerPlayer player, Level level, BlockPos pos) {
        if (player.hasPermissions(OP_LEVEL)) {
            return true;
        }
        if (SafariDimensions.isSafari(level)) {
            return false;
        }
        return !isNearGate(level, pos);
    }

    /**
     * Opens the settings screen of the group a clicked screen belongs to, or
     * tells the player in the action bar that they may not change it.
     */
    public static void open(ServerPlayer player, BlockPos pos, BlockState state) {
        Level level = player.level();
        if (!canEdit(player, level, pos)) {
            player.displayClientMessage(Component.translatable(key("message.wall_screen_locked")), true);
            return;
        }
        WallScreenLayout layout = WallScreenLayout.compute(level, pos, state);
        BlockState anchor = level.getBlockState(layout.anchorPos());
        SafariNetwork.sendWallScreenOpen(player, new SafariWallScreenOpenPayload(pos.immutable(),
                anchor.getValue(SafariWallScreenBlock.CONTENT), SafariWallScreenBlock.signedTilt(anchor)));
    }

    /**
     * Applies a settings change from a client to the whole group, after
     * checking distance, block, values and rights. Silently ignores requests
     * that no honest client sends (bad values, wrong block); the player is
     * told only when they lost the right to change the screen meanwhile.
     */
    public static void handleSet(ServerPlayer player, SafariWallScreenSetPayload request) {
        BlockPos pos = request.pos();
        // Distance first: it keeps every read below inside loaded chunks.
        double centreX = pos.getX() + 0.5D;
        double centreY = pos.getY() + 0.5D;
        double centreZ = pos.getZ() + 0.5D;
        if (player.distanceToSqr(centreX, centreY, centreZ) > MAX_DISTANCE * MAX_DISTANCE) {
            return;
        }
        Level level = player.level();
        BlockState state = level.getBlockState(pos);
        WallScreenContent content = WallScreenContent.byOrdinal(request.contentOrdinal());
        if (!(state.getBlock() instanceof SafariWallScreenBlock) || content == null
                || Math.abs(request.tilt()) > SafariWallScreenBlock.MAX_TILT_STEPS) {
            return;
        }
        if (!canEdit(player, level, pos)) {
            player.displayClientMessage(Component.translatable(key("message.wall_screen_locked")), true);
            return;
        }
        WallScreenLayout layout = WallScreenLayout.compute(level, pos, state);
        Direction right = WallScreenLayout.rightOf(state.getValue(HorizontalDirectionalBlock.FACING));
        for (int i = 0; i < layout.width(); i++) {
            BlockPos part = layout.anchorPos().relative(right, i);
            BlockState partState = level.getBlockState(part);
            if (!(partState.getBlock() instanceof SafariWallScreenBlock)) {
                continue;
            }
            BlockState updated = SafariWallScreenBlock.withSignedTilt(partState, request.tilt())
                    .setValue(SafariWallScreenBlock.CONTENT, content);
            if (updated != partState) {
                // UPDATE_KNOWN_SHAPE: neither setting changes a neighbour's shape.
                level.setBlock(part, updated, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
            }
        }
    }

    /** Whether a Safari Gate pane lies within the lock range of a position. */
    private static boolean isNearGate(Level level, BlockPos pos) {
        for (BlockPos near : BlockPos.betweenClosed(
                pos.offset(-GATE_LOCK_HORIZONTAL, -GATE_LOCK_VERTICAL, -GATE_LOCK_HORIZONTAL),
                pos.offset(GATE_LOCK_HORIZONTAL, GATE_LOCK_VERTICAL, GATE_LOCK_HORIZONTAL))) {
            if (level.getBlockState(near).getBlock() instanceof SafariGateBlock) {
                return true;
            }
        }
        return false;
    }

    /** Builds a translation key in the mod namespace. */
    private static String key(String path) {
        return CobblemonSafari.MOD_ID + "." + path;
    }
}
