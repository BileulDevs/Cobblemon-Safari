package dev.darcosse.common.cobblemonsafari.client.board;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import dev.darcosse.common.cobblemonsafari.block.SafariWallScreenBlock;
import dev.darcosse.common.cobblemonsafari.block.SafariWallScreenBlockEntity;
import dev.darcosse.common.cobblemonsafari.block.ScreenLayout;
import dev.darcosse.common.cobblemonsafari.block.WallScreenLayout;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4f;

/**
 * Draws a Safari Wall Screen group: the wall plate and arm, the tilted panel
 * and, on it, the page the group shows (the Safari Board's specials, the
 * viewer's record or today's missions).
 *
 * <p>The picture itself is painted by a {@link SafariBoardRenderer} kept
 * inside, through {@link SafariBoardRenderer#paintScreen}: the wall screen
 * only adds the hardware and the tilt, so both screens share one look and
 * one layout code.
 *
 * <p>The panel and arm are drawn here rather than by a block model because
 * the panel turns by 5° steps around a hinge, which baked models cannot do
 * (their element rotations are limited to 22.5° steps).
 *
 * <p>Cost per visible group per frame: the board picture (see
 * {@link SafariBoardRenderer}) plus about 60 quads of hardware. Screens that
 * are not anchors return after one cached lookup.
 *
 * CLIENT ONLY.
 */
public class SafariWallScreenRenderer implements BlockEntityRenderer<SafariWallScreenBlockEntity> {

    /** Texture of the panel's back and edges, the arm and the plate: the board's own casing. */
    private static final ResourceLocation CASING_TEXTURE = CobblemonSafari.id("textures/block/safari_board_frame.png");

    /** Texture of the panel's front, under the picture: the board's screen face. */
    private static final ResourceLocation SCREEN_TEXTURE = CobblemonSafari.id("textures/block/safari_board_screen.png");

    /** Pixels per block, for converting the block's pixel constants. */
    private static final float PIXELS_PER_BLOCK = 16.0F;

    /**
     * Gap between the panel's front and the picture, in pixels. Same value as
     * the board's (0.008 block): smaller z-fights with the panel, larger
     * floats visibly.
     */
    private static final float SURFACE_GAP_PIXELS = 0.008F * PIXELS_PER_BLOCK;

    /** Half the side of the square plate screwed to the wall, and its thickness, in pixels. */
    private static final float PLATE_HALF_SIZE = 3.0F;
    private static final float PLATE_THICKNESS = 1.0F;

    /** Half the side of the arm's square section, in pixels. Thinner reads as wire, thicker as a beam. */
    private static final float ARM_HALF_SIZE = 1.0F;

    /**
     * Half the side of the hinge plate on the panel's back, and its thickness,
     * in pixels. It hides where the arm meets the panel, which would
     * otherwise show the arm's end cutting into the back at an angle.
     */
    private static final float HINGE_HALF_SIZE = 2.0F;
    private static final float HINGE_THICKNESS = 0.5F;

    /**
     * Brightness of each face, like vanilla's block shading (top 100%,
     * bottom 50%, north/south 80%, east/west 60%). The render type used has
     * no lighting of its own, so without this every face of a box looks the
     * same and the panel reads as a flat cut-out.
     */
    private static final float SHADE_TOP = 1.0F;
    private static final float SHADE_BOTTOM = 0.5F;
    private static final float SHADE_FRONT_BACK = 0.8F;
    private static final float SHADE_SIDES = 0.6F;

    /** Draw distance in blocks, same as the board's. */
    private static final int VIEW_DISTANCE = 96;

    /** Blocks added around a group's bounds for models popping out of the picture. */
    private static final double BOUNDS_MARGIN = 1.0D;

    private static final int ALPHA_OPAQUE = 0xFF000000;
    private static final int COLOUR_MAX = 0xFF;

    /** Bit flags choosing which faces of a box are drawn. */
    private static final int FACE_FRONT = 1;
    private static final int FACE_BACK = 1 << 1;
    private static final int FACE_LEFT = 1 << 2;
    private static final int FACE_RIGHT = 1 << 3;
    private static final int FACE_TOP = 1 << 4;
    private static final int FACE_BOTTOM = 1 << 5;
    private static final int ALL_FACES = FACE_FRONT | FACE_BACK | FACE_LEFT | FACE_RIGHT | FACE_TOP | FACE_BOTTOM;

    /** Paints the picture; never registered itself, only used for {@link SafariBoardRenderer#paintScreen}. */
    private final SafariBoardRenderer picture;

    /** Created by the loader's renderer registry. */
    public SafariWallScreenRenderer(BlockEntityRendererProvider.Context context) {
        this.picture = new SafariBoardRenderer(context);
    }

    /**
     * Rendered even when the anchor block itself is off-screen: on a group of
     * 3 the leftmost block can be out of view while the rest is in plain
     * sight. Vanilla then skips the frustum check; NeoForge still culls with
     * {@link #screenBounds}.
     */
    @Override
    public boolean shouldRenderOffScreen(SafariWallScreenBlockEntity blockEntity) {
        return true;
    }

    /** See {@link #VIEW_DISTANCE}. */
    @Override
    public int getViewDistance() {
        return VIEW_DISTANCE;
    }

    /**
     * World box covering the whole group drawn by this block, models
     * included; just the block for screens that do not draw. Used by the
     * NeoForge subclass for frustum culling.
     */
    protected AABB screenBounds(SafariWallScreenBlockEntity blockEntity) {
        BlockPos pos = blockEntity.getBlockPos();
        WallScreenLayout layout = blockEntity.layout();
        BlockState state = blockEntity.getBlockState();
        if (!layout.anchor() || !state.hasProperty(HorizontalDirectionalBlock.FACING)) {
            return new AABB(pos);
        }
        Direction right = ScreenLayout.rightOf(state.getValue(HorizontalDirectionalBlock.FACING));
        BlockPos far = pos.relative(right, layout.width() - 1);
        return AABB.encapsulatingFullBlocks(pos, far).inflate(BOUNDS_MARGIN);
    }

    /** Draws the group if this block is its anchor. */
    @Override
    public void render(SafariWallScreenBlockEntity blockEntity, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffers, int packedLight, int packedOverlay) {
        Level level = blockEntity.getLevel();
        BlockState state = blockEntity.getBlockState();
        if (level == null || !(state.getBlock() instanceof SafariWallScreenBlock)) {
            return;
        }
        WallScreenLayout layout = blockEntity.layout();
        if (!layout.anchor()) {
            return;
        }
        Direction facing = state.getValue(HorizontalDirectionalBlock.FACING);
        // The anchor's settings are the group's: placement and the settings screen
        // keep the others equal, but a freshly regrouped row may disagree until
        // its settings are next changed.
        float tilt = SafariWallScreenBlock.tiltDegrees(state.getValue(SafariWallScreenBlock.TILT));
        int width = layout.width();
        float widthPixels = width * PIXELS_PER_BLOCK;
        float centre = widthPixels / 2.0F;

        poseStack.pushPose();
        // Block centre, then turn so +X is the reader's right, +Y up and +Z out
        // of the screen, like the board; then back to the bottom-left corner
        // against the wall, and pixel units.
        poseStack.translate(0.5F, 0.5F, 0.5F);
        poseStack.mulPose(Axis.YP.rotationDegrees(-facing.toYRot()));
        poseStack.translate(-0.5F, -0.5F, -0.5F);
        float pixel = 1.0F / PIXELS_PER_BLOCK;
        poseStack.scale(pixel, pixel, pixel);

        // Each render type gets its own buffer, fully drawn before the next is
        // requested: asking the buffer source for another type may end the
        // previous batch, after which writing to it would fail.
        VertexConsumer casing = buffers.getBuffer(RenderType.text(CASING_TEXTURE));
        Matrix4f fixed = poseStack.last().pose();
        float pivotY = SafariWallScreenBlock.PIVOT_HEIGHT_PIXELS;
        float pivotDepth = SafariWallScreenBlock.PIVOT_DEPTH_PIXELS;
        // Plate and arm stay still; their wall-side faces are never seen.
        box(casing, fixed, centre - PLATE_HALF_SIZE, pivotY - PLATE_HALF_SIZE, 0.0F,
                centre + PLATE_HALF_SIZE, pivotY + PLATE_HALF_SIZE, PLATE_THICKNESS,
                ALL_FACES & ~FACE_BACK, packedLight);
        box(casing, fixed, centre - ARM_HALF_SIZE, pivotY - ARM_HALF_SIZE, PLATE_THICKNESS,
                centre + ARM_HALF_SIZE, pivotY + ARM_HALF_SIZE, pivotDepth,
                ALL_FACES & ~FACE_BACK & ~FACE_FRONT, packedLight);

        // Hinge: positive X rotation turns +Y toward +Z, so the top of the
        // panel swings away from the wall. The hit boxes use the same rotation.
        poseStack.translate(0.0F, pivotY, pivotDepth);
        poseStack.mulPose(Axis.XP.rotationDegrees(tilt));
        Matrix4f tilted = poseStack.last().pose();
        float halfHeight = SafariWallScreenBlock.PANEL_HEIGHT_PIXELS / 2.0F;
        float thickness = SafariWallScreenBlock.PANEL_THICKNESS_PIXELS;
        box(casing, tilted, centre - HINGE_HALF_SIZE, -HINGE_HALF_SIZE, -HINGE_THICKNESS,
                centre + HINGE_HALF_SIZE, HINGE_HALF_SIZE, 0.0F, ALL_FACES & ~FACE_FRONT, packedLight);
        // One box per block so texture coordinates stay within one tile; inner
        // sides between blocks are hidden and skipped.
        for (int i = 0; i < width; i++) {
            int faces = FACE_BACK | FACE_TOP | FACE_BOTTOM;
            if (i == 0) {
                faces |= FACE_LEFT;
            }
            if (i == width - 1) {
                faces |= FACE_RIGHT;
            }
            float x0 = i * PIXELS_PER_BLOCK;
            box(casing, tilted, x0, -halfHeight, 0.0F, x0 + PIXELS_PER_BLOCK, halfHeight, thickness,
                    faces, packedLight);
        }

        // The front, under the picture: only seen through a translucent bezel
        // colour or before the first frame of the picture is ready.
        VertexConsumer screen = buffers.getBuffer(RenderType.text(SCREEN_TEXTURE));
        for (int i = 0; i < width; i++) {
            float x0 = i * PIXELS_PER_BLOCK;
            box(screen, tilted, x0, -halfHeight, 0.0F, x0 + PIXELS_PER_BLOCK, halfHeight, thickness,
                    FACE_FRONT, packedLight);
        }

        // Top-left corner of the picture, just in front of the panel, back in block units.
        poseStack.translate(0.0F, halfHeight, thickness + SURFACE_GAP_PIXELS);
        poseStack.scale(PIXELS_PER_BLOCK, PIXELS_PER_BLOCK, PIXELS_PER_BLOCK);
        picture.paintScreen(poseStack, buffers, width, SafariWallScreenBlock.PANEL_HEIGHT_PIXELS / PIXELS_PER_BLOCK,
                level.getGameTime(), state.getValue(SafariWallScreenBlock.CONTENT));
        poseStack.popPose();
    }

    /**
     * Draws the chosen faces of a textured box, in pixels. Each face is wound
     * counter-clockwise as seen from outside, the side the render type does
     * not cull (same convention as {@link BoardPainter}), and its texture
     * spans one pixel of texture per pixel of box, from the tile's corner.
     * Boxes are at most 16 pixels on every side, so coordinates stay in 0..1.
     */
    private static void box(VertexConsumer buffer, Matrix4f matrix, float x0, float y0, float z0,
                            float x1, float y1, float z1, int faces, int light) {
        if ((faces & FACE_FRONT) != 0) {
            quad(buffer, matrix, light, SHADE_FRONT_BACK,
                    x0, y1, z1, x0, y0, z1, x1, y0, z1, x1, y1, z1);
        }
        if ((faces & FACE_BACK) != 0) {
            quad(buffer, matrix, light, SHADE_FRONT_BACK,
                    x1, y1, z0, x1, y0, z0, x0, y0, z0, x0, y1, z0);
        }
        if ((faces & FACE_RIGHT) != 0) {
            quad(buffer, matrix, light, SHADE_SIDES,
                    x1, y1, z1, x1, y0, z1, x1, y0, z0, x1, y1, z0);
        }
        if ((faces & FACE_LEFT) != 0) {
            quad(buffer, matrix, light, SHADE_SIDES,
                    x0, y1, z0, x0, y0, z0, x0, y0, z1, x0, y1, z1);
        }
        if ((faces & FACE_TOP) != 0) {
            quad(buffer, matrix, light, SHADE_TOP,
                    x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0);
        }
        if ((faces & FACE_BOTTOM) != 0) {
            quad(buffer, matrix, light, SHADE_BOTTOM,
                    x0, y0, z1, x0, y0, z0, x1, y0, z0, x1, y0, z1);
        }
    }

    /**
     * One textured quad, corners given as top-left, bottom-left,
     * bottom-right, top-right of the face as seen from outside. The texture
     * width follows the bottom edge and its height the left edge.
     */
    private static void quad(VertexConsumer buffer, Matrix4f matrix, int light, float shade,
                             float ax, float ay, float az, float bx, float by, float bz,
                             float cx, float cy, float cz, float dx, float dy, float dz) {
        float u = length(cx - bx, cy - by, cz - bz) / PIXELS_PER_BLOCK;
        float v = length(bx - ax, by - ay, bz - az) / PIXELS_PER_BLOCK;
        int grey = Math.round(shade * COLOUR_MAX);
        int colour = ALPHA_OPAQUE | grey << 16 | grey << 8 | grey;
        buffer.addVertex(matrix, ax, ay, az).setColor(colour).setUv(0.0F, 0.0F).setLight(light);
        buffer.addVertex(matrix, bx, by, bz).setColor(colour).setUv(0.0F, v).setLight(light);
        buffer.addVertex(matrix, cx, cy, cz).setColor(colour).setUv(u, v).setLight(light);
        buffer.addVertex(matrix, dx, dy, dz).setColor(colour).setUv(u, 0.0F).setLight(light);
    }

    /** Length of an edge, in pixels. */
    private static float length(float x, float y, float z) {
        return (float) Math.sqrt(x * x + y * y + z * z);
    }
}
