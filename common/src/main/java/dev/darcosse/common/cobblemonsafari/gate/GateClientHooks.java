package dev.darcosse.common.cobblemonsafari.gate;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.util.function.BiConsumer;

/**
 * Lets the Safari Gate block, which loads on both sides, reach client-only
 * code without naming it: the client installs its handler at start-up, a
 * dedicated server never does. Same pattern as {@code ShopClientBridge}.
 */
public final class GateClientHooks {

    /** Called for a pane block the client is animating; starts the gate's tune near it. */
    private static volatile BiConsumer<Level, BlockPos> paneAnimated = (level, pos) -> {
    };

    private GateClientHooks() {
    }

    /** Installs the client handler. Client start-up only. */
    public static void setPaneAnimated(BiConsumer<Level, BlockPos> handler) {
        paneAnimated = handler;
    }

    /** A pane block is being animated on the client (from {@code animateTick}). */
    public static void onPaneAnimated(Level level, BlockPos pos) {
        paneAnimated.accept(level, pos);
    }
}
