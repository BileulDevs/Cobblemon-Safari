package dev.darcosse.forge.cobblemonsafari.client;

import dev.darcosse.common.cobblemonsafari.client.banner.SafariBannerRenderer;
import dev.darcosse.common.cobblemonsafari.decor.SafariBannerBlockEntity;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.client.extensions.IBlockEntityRendererExtension;

/**
 * The banner renderer with NeoForge's culling box.
 *
 * <p>Same reason as {@link NeoForgeSafariWallScreenRenderer}: NeoForge culls block
 * entity renderers against the block alone by default, and a banner reaches
 * into the block above (standing) or below (on a wall), which would vanish
 * as soon as the banner's own block leaves the view. Implements the
 * extension interface explicitly for the same bridge-method reason.
 *
 * CLIENT ONLY.
 */
public class NeoForgeSafariBannerRenderer extends SafariBannerRenderer
        implements IBlockEntityRendererExtension<SafariBannerBlockEntity> {

    /** Created by NeoForge's renderer registry. */
    public NeoForgeSafariBannerRenderer(BlockEntityRendererProvider.Context context) {
        super(context);
    }

    /** The banner's block and the one its cloth or pole reaches into. */
    @Override
    public AABB getRenderBoundingBox(SafariBannerBlockEntity blockEntity) {
        return bannerBounds(blockEntity);
    }
}
