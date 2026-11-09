package dev.darcosse.common.cobblemonsafari.client.egg;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.darcosse.common.cobblemonsafari.egg.SafariNestEntity;
import dev.darcosse.common.cobblemonsafari.item.SafariItems;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/**
 * Draws a straw nest: the nest item's model (straw ring and egg, see
 * {@code models/item/safari_nest.json}) at the entity's feet, turned by its
 * yaw. Going through an item model keeps the nest a plain JSON model that a
 * resource pack can replace. CLIENT ONLY.
 */
public class SafariNestRenderer extends EntityRenderer<SafariNestEntity> {

    /**
     * Lift applied before drawing: the item renderer centres a model's block
     * on the origin, so half a block up puts the model's floor on the ground.
     */
    private static final float MODEL_FLOOR_OFFSET = 0.5F;

    private final ItemRenderer itemRenderer;

    /** The nest's model, built at the first frame: the item only exists once registries are filled. */
    private ItemStack nestStack;

    /** Built by the loader's renderer registry with the game's rendering context. */
    public SafariNestRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.itemRenderer = context.getItemRenderer();
        // No round shadow: the straw ring already sits flat on the ground.
        this.shadowRadius = 0.0F;
    }

    /** Draws the nest model, lit by the light at the nest. */
    @Override
    public void render(SafariNestEntity entity, float entityYaw, float partialTicks, PoseStack poseStack,
                       MultiBufferSource buffers, int packedLight) {
        if (nestStack == null) {
            nestStack = new ItemStack(SafariItems.safariNest());
        }
        poseStack.pushPose();
        poseStack.translate(0.0F, MODEL_FLOOR_OFFSET, 0.0F);
        // Negated: entity yaw turns clockwise seen from above, the pose stack's Y rotation the other way.
        poseStack.mulPose(Axis.YP.rotationDegrees(-entity.getYRot()));
        // NONE: the model's own coordinates, without the display transforms of an item in a hand or a frame.
        itemRenderer.renderStatic(nestStack, ItemDisplayContext.NONE, packedLight, OverlayTexture.NO_OVERLAY,
                poseStack, buffers, entity.level(), entity.getId());
        poseStack.popPose();
        super.render(entity, entityYaw, partialTicks, poseStack, buffers, packedLight);
    }

    /** The block atlas, where item models' textures live; unused by the item renderer itself. */
    @Override
    @SuppressWarnings("deprecation")
    public ResourceLocation getTextureLocation(SafariNestEntity entity) {
        return TextureAtlas.LOCATION_BLOCKS;
    }
}
