package dev.darcosse.common.cobblemonsafari.block;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

import java.util.function.BiConsumer;
import java.util.function.BiFunction;

/**
 * Block entity types added by the mod.
 *
 * <p>Same lifecycle rule as {@link SafariBlocks}: created inside
 * {@link #register}, after the blocks they attach to exist.
 */
public final class SafariBlockEntities {

    private static BlockEntityType<SafariBoardBlockEntity> safariBoard;

    private SafariBlockEntities() {
    }

    /**
     * Builds a block entity type for the board, on the loader side.
     *
     * <p>Exists because vanilla's {@code BlockEntityType.BlockEntitySupplier}
     * is package-private: common code compiles against the plain game jar and
     * cannot even name it in a method reference. Fabric (transitive access
     * widener) and NeoForge (access transformer) both make it public, so each
     * loader builds the type with {@code BlockEntityType.Builder.of(...)}.
     */
    @FunctionalInterface
    public interface BoardTypeFactory {

        /** Builds the type that creates board block entities with {@code constructor}. */
        BlockEntityType<SafariBoardBlockEntity> create(
                BiFunction<BlockPos, BlockState, SafariBoardBlockEntity> constructor, Block block);
    }

    /**
     * Creates every block entity type and hands it to the loader's registrar.
     * Call exactly once, after {@link SafariBlocks#register}.
     */
    public static void register(BoardTypeFactory factory, BiConsumer<ResourceLocation, BlockEntityType<?>> registrar) {
        safariBoard = factory.create(SafariBoardBlockEntity::new, SafariBlocks.safariBoard());
        registrar.accept(SafariBlocks.SAFARI_BOARD_ID, safariBoard);
    }

    /**
     * Type of the Safari Board's block entity.
     *
     * @throws IllegalStateException before registration, which means a loader entry point is wired wrong
     */
    public static BlockEntityType<SafariBoardBlockEntity> safariBoard() {
        if (safariBoard == null) {
            throw new IllegalStateException("SafariBlockEntities.register was never called");
        }
        return safariBoard;
    }
}
