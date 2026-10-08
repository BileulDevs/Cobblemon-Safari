package dev.darcosse.common.cobblemonsafari.client.board;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import org.joml.Matrix4f;

/**
 * Drawing primitives for one frame of one Safari Board screen, in canvas
 * coordinates: x to the right, y DOWN, z toward the viewer, one unit per
 * canvas pixel. Mirrors the conventions of GUI drawing so the layout code
 * reads like a screen, not like world geometry.
 *
 * <p>Everything is full-bright: the screen glows the same by day and by night.
 *
 * CLIENT ONLY.
 */
final class BoardPainter {

    /** Light value for everything on the screen: it emits its own light. */
    static final int LIGHT = LightTexture.FULL_BRIGHT;

    private final PoseStack poseStack;
    private final MultiBufferSource buffers;
    private final Font font;

    /** Painter drawing into the given pose, which must already be in canvas space. */
    BoardPainter(PoseStack poseStack, MultiBufferSource buffers, Font font) {
        this.poseStack = poseStack;
        this.buffers = buffers;
        this.font = font;
    }

    /** The pose stack, for callers drawing models in canvas space. */
    PoseStack poseStack() {
        return poseStack;
    }

    /** The buffer source, for callers drawing models. */
    MultiBufferSource buffers() {
        return buffers;
    }

    /** Width of a string in canvas pixels at scale 1. */
    int width(String text) {
        return font.width(text);
    }

    /**
     * Longest prefix of {@code text} that fits in {@code maxWidth}, with an
     * ellipsis when something was cut.
     */
    String fit(String text, float maxWidth) {
        if (font.width(text) <= maxWidth) {
            return text;
        }
        String ellipsis = "…";
        int room = Math.max(0, (int) maxWidth - font.width(ellipsis));
        return font.plainSubstrByWidth(text, room) + ellipsis;
    }

    /**
     * Fills a rectangle. Colours are ARGB; alpha below 255 blends, which the
     * scanline and glow effects rely on.
     *
     * <p>Vertices go top-left, bottom-left, bottom-right, top-right: with y
     * pointing down in canvas space, that is counter-clockwise as the viewer
     * sees it, which is the side the render type does not cull.
     */
    void fill(float x0, float y0, float x1, float y1, float z, int argb) {
        if (x1 <= x0 || y1 <= y0) {
            return;
        }
        Matrix4f matrix = poseStack.last().pose();
        VertexConsumer buffer = buffers.getBuffer(RenderType.textBackground());
        buffer.addVertex(matrix, x0, y0, z).setColor(argb).setLight(LIGHT);
        buffer.addVertex(matrix, x0, y1, z).setColor(argb).setLight(LIGHT);
        buffer.addVertex(matrix, x1, y1, z).setColor(argb).setLight(LIGHT);
        buffer.addVertex(matrix, x1, y0, z).setColor(argb).setLight(LIGHT);
    }

    /**
     * Draws text with its top-left corner at (x, y).
     *
     * @param scale text size relative to the font's 9-pixel line
     */
    void text(String text, float x, float y, float z, float scale, int argb) {
        poseStack.pushPose();
        poseStack.translate(x, y, z);
        poseStack.scale(scale, scale, 1.0F);
        // POLYGON_OFFSET: same choice as signs, keeps glyphs from flickering
        // against the panel they sit on.
        font.drawInBatch(text, 0.0F, 0.0F, argb, false, poseStack.last().pose(), buffers,
                Font.DisplayMode.POLYGON_OFFSET, 0, LIGHT);
        poseStack.popPose();
    }
}
