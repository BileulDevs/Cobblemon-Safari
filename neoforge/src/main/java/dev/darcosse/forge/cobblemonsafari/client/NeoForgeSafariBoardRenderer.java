package dev.darcosse.forge.cobblemonsafari.client;

import dev.darcosse.common.cobblemonsafari.block.SafariBoardBlockEntity;
import dev.darcosse.common.cobblemonsafari.client.board.SafariBoardRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.client.extensions.IBlockEntityRendererExtension;

/**
 * The board renderer with NeoForge's culling box.
 *
 * <p>NeoForge culls block entity renderers against a box that defaults to the
 * block itself: a wall screen would vanish as soon as its bottom-left block
 * leaves the view. The box returned here covers the whole screen.
 *
 * <p>Implements {@link IBlockEntityRendererExtension} explicitly instead of
 * relying on it being injected into {@code BlockEntityRenderer}: either way
 * javac then emits the bridge method the game calls at runtime, which a plain
 * method compiled without the interface in sight would not get.
 *
 * CLIENT ONLY.
 */
public class NeoForgeSafariBoardRenderer extends SafariBoardRenderer
        implements IBlockEntityRendererExtension<SafariBoardBlockEntity> {

    /** Created by NeoForge's renderer registry. */
    public NeoForgeSafariBoardRenderer(BlockEntityRendererProvider.Context context) {
        super(context);
    }

    /** Whole screen plus a margin for the models standing out of it. */
    @Override
    public AABB getRenderBoundingBox(SafariBoardBlockEntity blockEntity) {
        return screenBounds(blockEntity);
    }
}
