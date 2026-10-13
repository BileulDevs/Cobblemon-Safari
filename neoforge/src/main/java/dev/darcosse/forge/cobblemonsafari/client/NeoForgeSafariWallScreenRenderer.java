package dev.darcosse.forge.cobblemonsafari.client;

import dev.darcosse.common.cobblemonsafari.block.SafariWallScreenBlockEntity;
import dev.darcosse.common.cobblemonsafari.client.board.SafariWallScreenRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.client.extensions.IBlockEntityRendererExtension;

/**
 * The wall screen renderer with NeoForge's culling box.
 *
 * <p>Same reason as {@link NeoForgeSafariBoardRenderer}: NeoForge culls block
 * entity renderers against the block alone by default, so a group of screens
 * would vanish as soon as its leftmost block leaves the view. Implements the
 * extension interface explicitly for the same bridge-method reason.
 *
 * CLIENT ONLY.
 */
public class NeoForgeSafariWallScreenRenderer extends SafariWallScreenRenderer
        implements IBlockEntityRendererExtension<SafariWallScreenBlockEntity> {

    /** Created by NeoForge's renderer registry. */
    public NeoForgeSafariWallScreenRenderer(BlockEntityRendererProvider.Context context) {
        super(context);
    }

    /** Whole group plus a margin for the models standing out of the picture. */
    @Override
    public AABB getRenderBoundingBox(SafariWallScreenBlockEntity blockEntity) {
        return screenBounds(blockEntity);
    }
}
