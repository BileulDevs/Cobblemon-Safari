package dev.darcosse.common.cobblemonsafari.client.banner;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import dev.darcosse.common.cobblemonsafari.decor.BannerDesigns;
import dev.darcosse.common.cobblemonsafari.decor.SafariBannerBlock;
import dev.darcosse.common.cobblemonsafari.decor.SafariBannerBlockEntity;
import dev.darcosse.common.cobblemonsafari.decor.SafariWallBannerBlock;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RotationSegment;
import net.minecraft.world.phys.AABB;

/**
 * Draws a Safari Banner: a walnut pole (standing banners only), a crossbar
 * and the cloth showing its picture, swaying like a vanilla banner.
 *
 * <p>Same size and sway as vanilla's banner, so both kinds look alike side
 * by side, but drawn as plain textured boxes from the block atlas instead
 * of vanilla's banner model: the picture is any atlas sprite under
 * {@code block/banner/} ({@link BannerDesigns}), animated ones included,
 * which the vanilla banner textures cannot be.
 *
 * <p>Worked in "banner space": origin at the middle of the block's floor,
 * the cloth facing +z, in blocks.
 *
 * CLIENT ONLY.
 */
public class SafariBannerRenderer implements BlockEntityRenderer<SafariBannerBlockEntity> {

    /** Wood of the pole and bar, a texture of the mod's decor set. */
    private static final ResourceLocation WOOD = CobblemonSafari.id("block/decor/walnut");

    /** Vanilla's banner measures, converted to blocks (model pixels × 2/3 ÷ 16). */
    private static final float HALF_WIDTH = 10.0F / 24.0F;
    private static final float CLOTH_HEIGHT = 40.0F / 24.0F;
    private static final float CLOTH_THICKNESS = 1.0F / 24.0F;
    private static final float POLE_HALF = 1.0F / 24.0F;
    private static final float POLE_TOP = 42.0F / 24.0F;
    private static final float BAR_HEIGHT = 2.0F / 24.0F;

    /**
     * Where a wall banner's bar sits: its top this far above the block's
     * floor, its back this far from the block's middle toward the wall.
     * Vanilla's wall banner values; the cloth hangs into the block below.
     */
    private static final float WALL_BAR_TOP = 0.854F;
    private static final float WALL_BAR_BACK = 0.479F;

    /**
     * Sway: vanilla's banner formula. One period every SWAY_PERIOD ticks,
     * leaning SWAY_LEAN half-turns forward on average, swinging SWAY_SWING
     * half-turns either way. Larger values make a windier banner.
     */
    private static final long SWAY_PERIOD = 100L;
    private static final float SWAY_LEAN = 0.0125F;
    private static final float SWAY_SWING = 0.01F;

    /** Per-axis spread of the sway phase, so banners side by side do not move in step (vanilla's). */
    private static final int PHASE_X = 7;
    private static final int PHASE_Y = 9;
    private static final int PHASE_Z = 13;

    /** Shading of the faces by orientation, as vanilla's block faces: sides darker than the top. */
    private static final float SHADE_FRONT = 1.0F;
    private static final float SHADE_SIDE = 0.7F;
    private static final float SHADE_TOP = 0.9F;

    /** Created by the loader's renderer registry. */
    public SafariBannerRenderer(BlockEntityRendererProvider.Context context) {
    }

    /** Pole, bar and swaying cloth. Cost per frame: about 40 vertices per banner. */
    @Override
    public void render(SafariBannerBlockEntity banner, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffers, int light, int overlay) {
        BlockState state = banner.getBlockState();
        boolean standing = state.getBlock() instanceof SafariBannerBlock;
        if (!standing && !(state.getBlock() instanceof SafariWallBannerBlock)) {
            return;
        }
        VertexConsumer buffer = buffers.getBuffer(RenderType.entityCutoutNoCull(TextureAtlas.LOCATION_BLOCKS));
        TextureAtlasSprite wood = sprite(WOOD);
        TextureAtlasSprite cloth = sprite(banner.design());
        if (isMissing(cloth)) {
            // A design from a resource pack this client does not have.
            cloth = sprite(BannerDesigns.DEFAULT);
        }

        poseStack.pushPose();
        poseStack.translate(0.5F, 0.0F, 0.5F);
        float barTop;
        float barBack;
        if (standing) {
            poseStack.mulPose(Axis.YP.rotationDegrees(
                    -RotationSegment.convertToDegrees(state.getValue(SafariBannerBlock.ROTATION))));
            barTop = POLE_TOP + BAR_HEIGHT;
            barBack = -POLE_HALF;
            box(buffer, poseStack.last(), wood, light, -POLE_HALF, 0.0F, -POLE_HALF, POLE_HALF, POLE_TOP, POLE_HALF);
        } else {
            poseStack.mulPose(Axis.YP.rotationDegrees(-state.getValue(SafariWallBannerBlock.FACING).toYRot()));
            barTop = WALL_BAR_TOP;
            barBack = -WALL_BAR_BACK;
        }
        float barFront = barBack + 2.0F * POLE_HALF;
        box(buffer, poseStack.last(), wood, light, -HALF_WIDTH, barTop - BAR_HEIGHT, barBack,
                HALF_WIDTH, barTop, barFront);

        // The cloth hangs from the front of the bar and swings around that edge.
        poseStack.translate(0.0F, barTop, barFront);
        poseStack.mulPose(Axis.XP.rotation(sway(banner, partialTick)));
        cloth(buffer, poseStack.last(), cloth, light);
        poseStack.popPose();
    }

    /** Current sway angle in radians: vanilla's banner formula, phased by position. */
    private static float sway(SafariBannerBlockEntity banner, float partialTick) {
        long time = banner.getLevel() == null ? 0L : banner.getLevel().getGameTime();
        BlockPos pos = banner.getBlockPos();
        long phase = (long) pos.getX() * PHASE_X + (long) pos.getY() * PHASE_Y + (long) pos.getZ() * PHASE_Z + time;
        float cycle = (Math.floorMod(phase, SWAY_PERIOD) + partialTick) / SWAY_PERIOD;
        return (-SWAY_LEAN + SWAY_SWING * Mth.cos(Mth.TWO_PI * cycle)) * Mth.PI;
    }

    /**
     * The cloth, hanging down from the origin: the picture on the front,
     * the same picture on the back as seen through the cloth (mirrored), and
     * its edge pixels on the thin sides.
     *
     * <p>Seen from the front (from +z, looking toward -z) the viewer's right
     * is +x, as east is on the right of someone facing north: the picture's
     * left edge (u0) is at -x, on both faces.
     */
    private static void cloth(VertexConsumer buffer, PoseStack.Pose pose, TextureAtlasSprite sprite, int light) {
        float x0 = -HALF_WIDTH;
        float x1 = HALF_WIDTH;
        float y0 = -CLOTH_HEIGHT;
        float y1 = 0.0F;
        float z0 = 0.0F;
        float z1 = CLOTH_THICKNESS;
        float u0 = sprite.getU0();
        float u1 = sprite.getU1();
        float v0 = sprite.getV0();
        float v1 = sprite.getV1();
        // A sliver of the picture's edge, so the sides match its border.
        float edge = (u1 - u0) / sprite.contents().width();
        // Front (+z): picture's left edge at -x.
        quad(buffer, pose, light, SHADE_FRONT, 0, 0, 1,
                x0, y1, z1, u0, v0, x0, y0, z1, u0, v1, x1, y0, z1, u1, v1, x1, y1, z1, u1, v0);
        // Back (-z): the same pixels at the same places, so it reads mirrored from behind.
        quad(buffer, pose, light, SHADE_FRONT, 0, 0, -1,
                x1, y1, z0, u1, v0, x1, y0, z0, u1, v1, x0, y0, z0, u0, v1, x0, y1, z0, u0, v0);
        // Edges: -x carries the picture's left column, +x its right column.
        quad(buffer, pose, light, SHADE_SIDE, -1, 0, 0,
                x0, y1, z0, u0, v0, x0, y0, z0, u0, v1, x0, y0, z1, u0 + edge, v1, x0, y1, z1, u0 + edge, v0);
        quad(buffer, pose, light, SHADE_SIDE, 1, 0, 0,
                x1, y1, z1, u1 - edge, v0, x1, y0, z1, u1 - edge, v1, x1, y0, z0, u1, v1, x1, y1, z0, u1, v0);
        // Bottom hem.
        float vEdge = (v1 - v0) / sprite.contents().height();
        quad(buffer, pose, light, SHADE_SIDE, 0, -1, 0,
                x0, y0, z1, u0, v1 - vEdge, x0, y0, z0, u0, v1, x1, y0, z0, u1, v1, x1, y0, z1, u1, v1 - vEdge);
    }

    /**
     * A box in wood, every face showing the whole sprite, squeezed to the
     * face: on a pole two texels wide this reads as streaked grain.
     */
    private static void box(VertexConsumer buffer, PoseStack.Pose pose, TextureAtlasSprite sprite, int light,
                            float x0, float y0, float z0, float x1, float y1, float z1) {
        float u0 = sprite.getU0();
        float v0 = sprite.getV0();
        float u1 = sprite.getU1();
        float v1 = sprite.getV1();
        quad(buffer, pose, light, SHADE_FRONT, 0, 0, 1,
                x0, y1, z1, u0, v0, x0, y0, z1, u0, v1, x1, y0, z1, u1, v1, x1, y1, z1, u1, v0);
        quad(buffer, pose, light, SHADE_FRONT, 0, 0, -1,
                x1, y1, z0, u0, v0, x1, y0, z0, u0, v1, x0, y0, z0, u1, v1, x0, y1, z0, u1, v0);
        quad(buffer, pose, light, SHADE_SIDE, -1, 0, 0,
                x0, y1, z0, u0, v0, x0, y0, z0, u0, v1, x0, y0, z1, u1, v1, x0, y1, z1, u1, v0);
        quad(buffer, pose, light, SHADE_SIDE, 1, 0, 0,
                x1, y1, z1, u0, v0, x1, y0, z1, u0, v1, x1, y0, z0, u1, v1, x1, y1, z0, u1, v0);
        quad(buffer, pose, light, SHADE_TOP, 0, 1, 0,
                x0, y1, z0, u0, v0, x0, y1, z1, u0, v1, x1, y1, z1, u1, v1, x1, y1, z0, u1, v0);
        quad(buffer, pose, light, SHADE_SIDE, 0, -1, 0,
                x0, y0, z1, u0, v0, x0, y0, z0, u0, v1, x1, y0, z0, u1, v1, x1, y0, z1, u1, v0);
    }

    /** One quad, four corners each with its texture coordinates, counter-clockwise seen from the front. */
    private static void quad(VertexConsumer buffer, PoseStack.Pose pose, int light, float shade,
                             float nx, float ny, float nz,
                             float ax, float ay, float az, float au, float av,
                             float bx, float by, float bz, float bu, float bv,
                             float cx, float cy, float cz, float cu, float cv,
                             float dx, float dy, float dz, float du, float dv) {
        int grey = Math.round(shade * 255.0F);
        int colour = 0xFF000000 | grey << 16 | grey << 8 | grey;
        vertex(buffer, pose, light, colour, nx, ny, nz, ax, ay, az, au, av);
        vertex(buffer, pose, light, colour, nx, ny, nz, bx, by, bz, bu, bv);
        vertex(buffer, pose, light, colour, nx, ny, nz, cx, cy, cz, cu, cv);
        vertex(buffer, pose, light, colour, nx, ny, nz, dx, dy, dz, du, dv);
    }

    /** One vertex in the entity format: position, colour, texture, overlay, light, normal. */
    private static void vertex(VertexConsumer buffer, PoseStack.Pose pose, int light, int colour,
                               float nx, float ny, float nz, float x, float y, float z, float u, float v) {
        buffer.addVertex(pose, x, y, z).setColor(colour).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(light).setNormal(pose, nx, ny, nz);
    }

    /** A sprite of the block atlas; the missing-texture sprite when there is none by that name. */
    static TextureAtlasSprite sprite(ResourceLocation id) {
        return Minecraft.getInstance().getTextureAtlas(TextureAtlas.LOCATION_BLOCKS).apply(id);
    }

    /** Whether a sprite is the atlas's stand-in for a texture it does not have. */
    static boolean isMissing(TextureAtlasSprite sprite) {
        return sprite.contents().name().equals(MissingTextureAtlasSprite.getLocation());
    }

    /**
     * World box covering the whole banner, cloth included: the block and the
     * one above for a standing banner, the block and the one below for a
     * wall banner. Used by the NeoForge subclass for frustum culling, which
     * otherwise tests the block alone and hides half-visible banners.
     */
    public static AABB bannerBounds(SafariBannerBlockEntity banner) {
        BlockPos pos = banner.getBlockPos();
        boolean standing = banner.getBlockState().getBlock() instanceof SafariBannerBlock;
        return AABB.encapsulatingFullBlocks(pos, standing ? pos.above() : pos.below());
    }
}
