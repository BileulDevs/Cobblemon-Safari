package dev.darcosse.common.cobblemonsafari.client.travel;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

/**
 * The Escape Rope's travel scene, in pixel art: a trainer walks out of a
 * cave onto a cliff ledge, a bird swoops in, the trainer jumps on its back
 * and they fly off; then they fly on above the clouds for as long as the
 * trip lasts.
 *
 * <p>The frames are drawn by {@code tools/travel_cave_escape.py} into one
 * atlas; this class only moves them. Positions are in scene texels: the
 * scene is scaled to cover the screen, so sprites move in whole texels, as
 * pixel art does.
 *
 * <p>Client only.
 */
final class CaveEscapeAnimation {

    private static final ResourceLocation ATLAS = CobblemonSafari.id("textures/gui/travel/cave_escape.png");

    // Atlas layout, mirroring the constants of the same names in
    // tools/travel_cave_escape.py: changing one side alone draws the wrong
    // part of the atlas.
    private static final int ATLAS_W = 512;
    private static final int ATLAS_H = 256;
    private static final int SCENE_W = 192;
    private static final int SCENE_H = 108;
    private static final int CAVE_BG_U = 0;
    private static final int SKY_BG_U = 192;
    private static final int CLOUDS_V = 108;
    private static final int CLOUDS_W = 192;
    private static final int CLOUDS_H = 40;
    private static final int TRAINER_V = 160;
    private static final int TRAINER_W = 16;
    private static final int TRAINER_H = 20;
    private static final int BIRD_V = 184;
    private static final int BIRD_W = 32;
    private static final int BIRD_H = 24;
    private static final int RIDER_V = 208;
    private static final int RIDER_W = 32;
    private static final int RIDER_H = 32;
    /** Trainer frames: four walk steps, then the jump. */
    private static final int WALK_FRAMES = 4;
    private static final int JUMP_FRAME = 4;
    /** Bird and rider frames: wings up, level, down. */
    private static final int WING_FRAMES = 3;
    private static final int LEVEL_WING_FRAME = 1;
    private static final int LEDGE_Y = 78;
    private static final int CAVE_MOUTH_X = 44;

    // The script, in milliseconds since the scene began. Stretching it makes
    // the escape slower; the flight loop then simply starts later.
    private static final long WALK_END = 1_700L;
    private static final long BIRD_IN_START = 1_000L;
    private static final long BIRD_IN_END = 2_000L;
    private static final long JUMP_END = 2_400L;
    private static final long FLY_OFF_END = 3_600L;
    private static final long CROSSFADE_END = 3_900L;

    /** Time per walk step and per wing beat, in milliseconds. */
    private static final long STEP_MS = 150L;
    private static final long BEAT_FAST_MS = 90L;
    private static final long BEAT_HOVER_MS = 180L;

    /** Where the trainer walks from and to (left edge of the sprite), in texels. */
    private static final int WALK_FROM_X = 4;
    private static final int WALK_TO_X = 84;
    /** Darkest the trainer gets deep in the cave, from 0 (black) to 1 (full light). */
    private static final float CAVE_DARKEST = 0.2F;
    /** Texels in from the mouth over which the trainer comes out of the dark. */
    private static final int CAVE_LIGHT_RAMP = 22;

    /** The bird's entry, from off screen to its hover beside the ledge (top-left of its frame). */
    private static final int BIRD_FROM_X = 200;
    private static final int BIRD_FROM_Y = 6;
    private static final int BIRD_HOVER_X = 98;
    private static final int BIRD_HOVER_Y = LEDGE_Y - 22;
    /** Where the seated trainer sits inside a rider frame; mirrors tx, ty of rider_frame(). */
    private static final int SEAT_X = 9;
    private static final int SEAT_Y = 1;
    /** Height of the jump's arc, in texels. */
    private static final int JUMP_HEIGHT = 10;
    /** Where the rider leaves the frame, up and to the right. */
    private static final int FLY_OFF_X = 236;
    private static final int FLY_OFF_Y = -44;

    /** The rider during the flight loop: centre of its drift, and how far it drifts. */
    private static final int LOOP_X = 80;
    private static final int LOOP_Y = 40;
    private static final double LOOP_DRIFT_X = 3.0D;
    private static final double LOOP_DRIFT_Y = 2.0D;
    private static final double LOOP_PERIOD_X_MS = 900.0D;
    private static final double LOOP_PERIOD_Y_MS = 450.0D;
    /** Speed of the two cloud layers, in texels per second: the nearer layer moves faster. */
    private static final double FAR_CLOUDS_SPEED = 25.0D;
    private static final double NEAR_CLOUDS_SPEED = 55.0D;
    /** Rows of the scene where the two layers run. */
    private static final int FAR_CLOUDS_Y = SCENE_H - CLOUDS_H - 8;
    private static final int NEAR_CLOUDS_Y = SCENE_H - 26;

    private CaveEscapeAnimation() {
    }

    /**
     * Draws the scene at a moment, scaled to cover the screen and centred.
     *
     * @param elapsed milliseconds since the scene began
     */
    static void render(GuiGraphics graphics, int width, int height, long elapsed) {
        float scale = Math.max(width / (float) SCENE_W, height / (float) SCENE_H);
        graphics.pose().pushPose();
        graphics.pose().translate((width - SCENE_W * scale) / 2.0F, (height - SCENE_H * scale) / 2.0F, 0.0F);
        graphics.pose().scale(scale, scale, 1.0F);
        if (elapsed < CROSSFADE_END) {
            drawEscape(graphics, elapsed);
        }
        if (elapsed >= FLY_OFF_END) {
            float alpha = Mth.clamp((elapsed - FLY_OFF_END) / (float) (CROSSFADE_END - FLY_OFF_END), 0.0F, 1.0F);
            drawFlight(graphics, elapsed, alpha);
        }
        graphics.pose().popPose();
    }

    /** The cliff part: walk out, the bird's entry, the jump, the take-off. */
    private static void drawEscape(GuiGraphics graphics, long t) {
        blit(graphics, 0, 0, CAVE_BG_U, 0, SCENE_W, SCENE_H);
        // Trainer on foot, until the jump lands on the bird.
        if (t < JUMP_END) {
            int x;
            int y = LEDGE_Y - TRAINER_H;
            int frame;
            if (t < WALK_END) {
                x = lerp(WALK_FROM_X, WALK_TO_X, t / (float) WALK_END);
                frame = (int) (t / STEP_MS % WALK_FRAMES);
            } else if (t < BIRD_IN_END) {
                // Waits at the edge, standing.
                x = WALK_TO_X;
                frame = 1;
            } else {
                float u = (t - BIRD_IN_END) / (float) (JUMP_END - BIRD_IN_END);
                x = lerp(WALK_TO_X, BIRD_HOVER_X + SEAT_X, u);
                int landY = BIRD_HOVER_Y - (RIDER_H - BIRD_H) + SEAT_Y;
                y = lerp(y, landY, u) - (int) Math.round(JUMP_HEIGHT * Math.sin(Math.PI * u));
                frame = JUMP_FRAME;
            }
            // Dim inside the cave, brightening as the trainer steps into daylight.
            float light = Mth.clamp((x + TRAINER_W - (CAVE_MOUTH_X - CAVE_LIGHT_RAMP)) / (float) CAVE_LIGHT_RAMP,
                    CAVE_DARKEST, 1.0F);
            graphics.setColor(light, light, light, 1.0F);
            blit(graphics, x, y, frame * TRAINER_W, TRAINER_V, TRAINER_W, TRAINER_H);
            graphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);
        }
        // The bird alone, coming in and hovering.
        if (t >= BIRD_IN_START && t < JUMP_END) {
            int x;
            int y;
            int frame;
            if (t < BIRD_IN_END) {
                float u = (t - BIRD_IN_START) / (float) (BIRD_IN_END - BIRD_IN_START);
                // Eased out: fast from afar, braking into the hover.
                float eased = 1.0F - (1.0F - u) * (1.0F - u);
                x = lerp(BIRD_FROM_X, BIRD_HOVER_X, eased);
                y = lerp(BIRD_FROM_Y, BIRD_HOVER_Y, eased);
                frame = beat(t, BEAT_FAST_MS);
            } else {
                x = BIRD_HOVER_X;
                y = BIRD_HOVER_Y + (beat(t, BEAT_HOVER_MS) == 0 ? -1 : 0);
                frame = beat(t, BEAT_HOVER_MS);
            }
            blit(graphics, x, y, frame * BIRD_W, BIRD_V, BIRD_W, BIRD_H);
        }
        // Together, flying away.
        if (t >= JUMP_END && t < FLY_OFF_END) {
            float u = (t - JUMP_END) / (float) (FLY_OFF_END - JUMP_END);
            // Eased in: a heavy take-off speeding up.
            float eased = u * u;
            int startY = BIRD_HOVER_Y - (RIDER_H - BIRD_H);
            int x = lerp(BIRD_HOVER_X, FLY_OFF_X, eased);
            int y = lerp(startY, FLY_OFF_Y, eased);
            blit(graphics, x, y, beat(t, BEAT_FAST_MS) * RIDER_W, RIDER_V, RIDER_W, RIDER_H);
        }
    }

    /** The flight above the clouds, faded in over the cliff by alpha. */
    private static void drawFlight(GuiGraphics graphics, long t, float alpha) {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        graphics.setColor(1.0F, 1.0F, 1.0F, alpha);
        blit(graphics, 0, 0, SKY_BG_U, 0, SCENE_W, SCENE_H);
        clouds(graphics, t, FAR_CLOUDS_SPEED, FAR_CLOUDS_Y);
        int x = LOOP_X + (int) Math.round(LOOP_DRIFT_X * Math.sin(t / LOOP_PERIOD_X_MS));
        int y = LOOP_Y + (int) Math.round(LOOP_DRIFT_Y * Math.sin(t / LOOP_PERIOD_Y_MS));
        blit(graphics, x, y, beat(t, BEAT_HOVER_MS) * RIDER_W, RIDER_V, RIDER_W, RIDER_H);
        // The near layer passes in front of the bird.
        clouds(graphics, t, NEAR_CLOUDS_SPEED, NEAR_CLOUDS_Y);
        graphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.disableBlend();
    }

    /** One layer of clouds scrolling left, the strip repeated so it never ends. */
    private static void clouds(GuiGraphics graphics, long t, double speed, int y) {
        int offset = (int) (t * speed / 1000.0D % CLOUDS_W);
        for (int x = -offset; x < SCENE_W; x += CLOUDS_W) {
            blit(graphics, x, y, 0, CLOUDS_V, CLOUDS_W, CLOUDS_H);
        }
    }

    /** The wing frame at a moment, cycling up, level, down, level. */
    private static int beat(long t, long period) {
        int step = (int) (t / period % (WING_FRAMES + 1));
        return step == WING_FRAMES ? LEVEL_WING_FRAME : step;
    }

    /** A rectangle of the atlas at a scene position. */
    private static void blit(GuiGraphics graphics, int x, int y, int u, int v, int w, int h) {
        graphics.blit(ATLAS, x, y, u, v, w, h, ATLAS_W, ATLAS_H);
    }

    /** Rounds a linear blend between two texel positions. */
    private static int lerp(int from, int to, float progress) {
        return Math.round(from + (to - from) * progress);
    }
}
