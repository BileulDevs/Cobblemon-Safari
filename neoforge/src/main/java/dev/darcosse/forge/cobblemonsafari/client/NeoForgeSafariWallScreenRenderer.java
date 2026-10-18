package dev.darcosse.forge.cobblemonsafari.client;

import dev.darcosse.common.cobblemonsafari.block.SafariWallScreenBlockEntity;
import dev.darcosse.common.cobblemonsafari.client.board.SafariWallScreenRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.client.extensions.IBlockEntityRendererExtension;

/**
 * The wall screen renderer with NeoForge's culling box.
 *
 * <p>NeoForge culls block entity renderers against a box that defaults to
 * the block itself, so a group of screens would vanish as soon as its
 * leftmost block leaves the view. The box returned here covers the whole
 * group.
 *
 * <p>Implements {@link IBlockEntityRendererExtension} explicitly instead of
 * relying on it being injected into {@code BlockEntityRenderer}: either way
 * javac then emits the bridge method the game calls at runtime, which a plain
 * method compiled without the interface in sight would not get.
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
