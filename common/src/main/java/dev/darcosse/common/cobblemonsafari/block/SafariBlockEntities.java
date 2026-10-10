package dev.darcosse.common.cobblemonsafari.block;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
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
    private static BlockEntityType<SafariGateBlockEntity> safariGate;

    private SafariBlockEntities() {
    }

    /**
     * Builds a block entity type, on the loader side.
     *
     * <p>Exists because vanilla's {@code BlockEntityType.BlockEntitySupplier}
     * is package-private: common code compiles against the plain game jar and
     * cannot even name it in a method reference. Fabric (transitive access
     * widener) and NeoForge (access transformer) both make it public, so each
     * loader builds the type with {@code BlockEntityType.Builder.of(...)}.
     */
    @FunctionalInterface
    public interface TypeFactory {

        /**
         * Builds the type that creates block entities with {@code constructor}.
         * Returned as a wildcard because a lambda cannot implement a generic
         * method; {@link #register} casts it back to the type it asked for.
         */
        BlockEntityType<?> create(BiFunction<BlockPos, BlockState, ? extends BlockEntity> constructor, Block block);
    }

    /**
     * Creates every block entity type and hands it to the loader's registrar.
     * Call exactly once, after {@link SafariBlocks#register}.
     */
    @SuppressWarnings("unchecked")
    public static void register(TypeFactory factory, BiConsumer<ResourceLocation, BlockEntityType<?>> registrar) {
        // Safe: each type was built from the constructor of exactly that block entity class.
        safariBoard = (BlockEntityType<SafariBoardBlockEntity>) factory.create(SafariBoardBlockEntity::new,
                SafariBlocks.safariBoard());
        registrar.accept(SafariBlocks.SAFARI_BOARD_ID, safariBoard);
        safariGate = (BlockEntityType<SafariGateBlockEntity>) factory.create(SafariGateBlockEntity::new,
                SafariBlocks.safariGate());
        registrar.accept(SafariBlocks.SAFARI_GATE_ID, safariGate);
    }

    /**
     * Type of the Safari Gate's block entity.
     *
     * @throws IllegalStateException before registration, which means a loader entry point is wired wrong
     */
    public static BlockEntityType<SafariGateBlockEntity> safariGate() {
        if (safariGate == null) {
            throw new IllegalStateException("SafariBlockEntities.register was never called");
        }
        return safariGate;
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
