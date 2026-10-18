package dev.darcosse.common.cobblemonsafari.block;

import dev.darcosse.common.cobblemonsafari.decor.SafariBannerBlockEntity;
import dev.darcosse.common.cobblemonsafari.decor.SafariDecor;
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

    private static BlockEntityType<SafariGateBlockEntity> safariGate;
    private static BlockEntityType<SafariWallScreenBlockEntity> safariWallScreen;
    private static BlockEntityType<SafariBannerBlockEntity> safariBanner;

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
         * Builds the type that creates block entities with {@code constructor},
         * valid on the given blocks (several for a block with a wall twin,
         * like the banners). Returned as a wildcard because a lambda cannot
         * implement a generic method; {@link #register} casts it back to the
         * type it asked for.
         */
        BlockEntityType<?> create(BiFunction<BlockPos, BlockState, ? extends BlockEntity> constructor, Block... blocks);
    }

    /**
     * Creates every block entity type and hands it to the loader's registrar.
     * Call exactly once, after {@link SafariBlocks#register}.
     */
    @SuppressWarnings("unchecked")
    public static void register(TypeFactory factory, BiConsumer<ResourceLocation, BlockEntityType<?>> registrar) {
        // Safe: each type was built from the constructor of exactly that block entity class.
        safariGate = (BlockEntityType<SafariGateBlockEntity>) factory.create(SafariGateBlockEntity::new,
                SafariBlocks.safariGate());
        registrar.accept(SafariBlocks.SAFARI_GATE_ID, safariGate);
        safariWallScreen = (BlockEntityType<SafariWallScreenBlockEntity>) factory.create(
                SafariWallScreenBlockEntity::new, SafariBlocks.safariWallScreen());
        registrar.accept(SafariBlocks.SAFARI_WALL_SCREEN_ID, safariWallScreen);
        // One type for both banners: the picture belongs to the banner, whichever way it hangs.
        safariBanner = (BlockEntityType<SafariBannerBlockEntity>) factory.create(SafariBannerBlockEntity::new,
                SafariDecor.safariBanner(), SafariDecor.safariWallBanner());
        registrar.accept(SafariDecor.SAFARI_BANNER_ID, safariBanner);
    }

    /**
     * Type of the Safari Banners' block entity, standing and on a wall.
     *
     * @throws IllegalStateException before registration, which means a loader entry point is wired wrong
     */
    public static BlockEntityType<SafariBannerBlockEntity> safariBanner() {
        if (safariBanner == null) {
            throw new IllegalStateException("SafariBlockEntities.register was never called");
        }
        return safariBanner;
    }

    /**
     * Type of the Safari Wall Screen's block entity.
     *
     * @throws IllegalStateException before registration, which means a loader entry point is wired wrong
     */
    public static BlockEntityType<SafariWallScreenBlockEntity> safariWallScreen() {
        if (safariWallScreen == null) {
            throw new IllegalStateException("SafariBlockEntities.register was never called");
        }
        return safariWallScreen;
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

}
