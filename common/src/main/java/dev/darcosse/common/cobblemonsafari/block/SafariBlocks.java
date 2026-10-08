package dev.darcosse.common.cobblemonsafari.block;

import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;

import java.util.function.BiConsumer;

/**
 * Blocks added by the mod.
 *
 * <p>Same rule as {@code SafariItems}: instances are created inside
 * {@link #register}, during the loader's block registration, never in static
 * initialisers. Must run before {@code SafariItems.register}, which wraps the
 * blocks in block items.
 */
public final class SafariBlocks {

    /** Id of the Safari Board; also its model, loot table and translation path. */
    public static final ResourceLocation SAFARI_BOARD_ID = CobblemonSafari.id("safari_board");

    /** Mining time like a wooden sign: easy to move, not broken by a stray punch. */
    private static final float BOARD_STRENGTH = 1.0F;

    private static Block safariBoard;

    private SafariBlocks() {
    }

    /** Creates every block and hands it to the loader's registrar. Call exactly once. */
    public static void register(BiConsumer<ResourceLocation, Block> registrar) {
        safariBoard = new SafariBoardBlock(BlockBehaviour.Properties.of()
                .mapColor(MapColor.WOOD)
                .sound(SoundType.WOOD)
                .strength(BOARD_STRENGTH)
                .noOcclusion());
        registrar.accept(SAFARI_BOARD_ID, safariBoard);
    }

    /**
     * The Safari Board.
     *
     * @throws IllegalStateException before registration, which means a loader entry point is wired wrong
     */
    public static Block safariBoard() {
        if (safariBoard == null) {
            throw new IllegalStateException("SafariBlocks.register was never called");
        }
        return safariBoard;
    }
}
