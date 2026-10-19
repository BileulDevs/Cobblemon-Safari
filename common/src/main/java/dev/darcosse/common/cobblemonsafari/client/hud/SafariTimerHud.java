package dev.darcosse.common.cobblemonsafari.client.hud;

import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import dev.darcosse.common.cobblemonsafari.client.SafariClientState;
import dev.darcosse.common.cobblemonsafari.config.SafariClientConfig;
import dev.darcosse.common.cobblemonsafari.config.SafariClientConfigManager;
import dev.darcosse.common.cobblemonsafari.util.Ticks;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * Draws the remaining Safari time: a stopwatch made of a Safari Ball, its
 * red hand turning faster and faster as time runs out, with the time under
 * it. By default in the bottom left corner, the two lines together as high
 * as the hotbar and level with it. The stopwatch itself stays still. CLIENT ONLY: never reference
 * this class from code that can run on a dedicated server.
 *
 * <p>Runs every frame while the HUD is drawn, so it only reads cached state:
 * one formatted string and one texture blit per frame, no config file access.
 */
public final class SafariTimerHud {

    /** Id of the HUD layer on NeoForge; also a stable name for other mods to order against. */
    public static final ResourceLocation LAYER_ID = CobblemonSafari.id("timer");

    /**
     * The timer's icon (16 x 16), a Safari Ball turned stopwatch, drawn by
     * tools/timer_icon.py; resource packs can replace it. It used to be
     * Cobblemon's own Safari Ball texture.
     */
    private static final ResourceLocation ICON_TEXTURE = CobblemonSafari.id("textures/gui/safari_timer.png");
    private static final int TEXTURE_SIZE = 16;
    /**
     * Size of the texture on screen, in GUI pixels: one per texel, like an
     * item in the hotbar, which keeps it crisp. A non-integer scale (e.g. 18)
     * would give it uneven pixels.
     */
    private static final int ICON_SIZE = 16;
    /**
     * Drawn part of the texture, in texels from its top left corner: the
     * stopwatch fills columns 2 to 13 and rows 0 to 13 (crown to base), the
     * rest is transparent. The widget is laid out and centred on what is
     * drawn, not on the texture, so the time sits right under the
     * stopwatch and the two lines fill the hotbar's height. Must follow
     * tools/timer_icon.py.
     */
    private static final int ICON_DRAWN_LEFT = 2;
    private static final int ICON_DRAWN_RIGHT = 14;
    private static final int ICON_DRAWN_HEIGHT = 14;

    /** Gap between the bottom of the stopwatch and the top of the digits, in GUI pixels. */
    private static final int TEXT_GAP = 1;

    /**
     * Height of the font's capitals and digits, in GUI pixels, out of its
     * 9-pixel line: the widget is measured on what is drawn of the time,
     * like the stopwatch. With the 14 drawn rows of the stopwatch and the
     * gap, the widget is 22 px high, the height of the hotbar.
     */
    private static final int DIGIT_HEIGHT = 7;

    /**
     * Cells of the icon lit by the hand, for each of its eight positions
     * from twelve o'clock clockwise, in texture coordinates. The pin is at
     * (8, 8); straight positions are two cells long, diagonal ones one, as
     * the round dial allows. Must follow the dial of tools/timer_icon.py.
     * Eight positions, not a rotated sprite: at 16 x 16 a turned hand
     * would blur, while lit cells stay as crisp as the rest of the icon.
     */
    private static final int[][][] HAND_CELLS = {
            {{8, 7}, {8, 6}},
            {{9, 7}},
            {{9, 8}, {10, 8}},
            {{9, 9}},
            {{8, 9}, {8, 10}},
            {{7, 9}},
            {{7, 8}, {6, 8}},
            {{7, 7}},
    };

    /** Colour of the hand, ARGB: the red of a stopwatch's seconds hand. */
    private static final int HAND_COLOUR = 0xFFD2322D;

    /**
     * Speed of the hand, in turns per second: the slowest with plenty of
     * time, the fastest as time runs out. In between, the speed grows with
     * the square of how far into the last {@link #HAND_RAMP_SECONDS} the
     * hunt is, so it stays calm for a while and races at the end: about one
     * turn a second at 5 minutes, close to the fastest under a minute.
     * Faster than a few turns a second, the eight positions blur.
     */
    private static final float HAND_SLOWEST_TURNS_PER_SECOND = 0.125F;
    private static final float HAND_FASTEST_TURNS_PER_SECOND = 2.0F;
    /** Time left, in seconds, from which the hand speeds up: the first chat warning by default. */
    private static final float HAND_RAMP_SECONDS = 900.0F;

    /**
     * Longest step of the hand between two frames, in ticks. A frame after
     * a pause, a loading screen or a dimension change must not spin it by
     * whatever time passed meanwhile.
     */
    private static final float MAX_HAND_STEP_TICKS = 20.0F;

    /**
     * Turns made by the hand so far, and the game time (ticks plus partial
     * tick) of the frame that last moved it. Accumulated rather than
     * computed from the clock, since the speed changes as time runs out:
     * an angle computed from the clock would jump each time the speed did.
     */
    private static float handTurns;
    private static float lastHandTicks = Float.NaN;

    /**
     * Keeps the whole widget this far inside the screen whatever the offset.
     * 1, not more: the default position puts the widget, as high as the
     * hotbar, against the bottom of the screen like it.
     */
    private static final int SCREEN_MARGIN = 1;

    private SafariTimerHud() {
    }

    /** Renders the timer if the player has a session and stands in the Safari. */
    public static void render(GuiGraphics graphics, DeltaTracker deltaTracker) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || !SafariClientState.isActive()) {
            return;
        }
        long gameTime = minecraft.level.getGameTime();
        // Read BEFORE the visibility checks: the first read anchors the latest
        // server snapshot, and it must happen even while the HUD is hidden (F1),
        // otherwise the countdown would restart from the snapshot on unhide.
        int remaining = SafariClientState.remainingTicks(gameTime);

        SafariClientConfig config = SafariClientConfigManager.get();
        if (!config.hudEnabled || minecraft.options.hideGui) {
            return;
        }
        ResourceLocation safari = SafariClientState.safariDimension();
        if (safari == null || !minecraft.level.dimension().location().equals(safari)) {
            return;
        }

        Font font = minecraft.font;
        Component time = remaining > 0
                ? Component.literal(Ticks.format(remaining))
                : Component.translatable(CobblemonSafari.MOD_ID + ".hud.time_up");
        // Layout relative to the pivot, the middle of the widget: the
        // stopwatch on top, the time under it, both centred horizontally.
        int height = ICON_DRAWN_HEIGHT + TEXT_GAP + DIGIT_HEIGHT;
        int top = -height / 2;
        int bottom = top + height;
        int iconX = -(ICON_DRAWN_LEFT + ICON_DRAWN_RIGHT) / 2;
        int iconY = top;
        int textWidth = font.width(time);
        int textX = -textWidth / 2;
        int textY = top + ICON_DRAWN_HEIGHT + TEXT_GAP;
        int left = Math.min(iconX + ICON_DRAWN_LEFT, textX);
        int right = Math.max(iconX + ICON_DRAWN_RIGHT, textX + textWidth);

        // The anchor and offset place the pivot; the whole widget stays on screen.
        int pivotX = Math.round(graphics.guiWidth() * config.hudAnchor.xFraction) + config.hudOffsetX;
        int pivotY = Math.round(graphics.guiHeight() * config.hudAnchor.yFraction) + config.hudOffsetY;
        pivotX = clamp(pivotX, SCREEN_MARGIN - left, graphics.guiWidth() - SCREEN_MARGIN - right);
        pivotY = clamp(pivotY, SCREEN_MARGIN - top, graphics.guiHeight() - SCREEN_MARGIN - bottom);

        graphics.pose().pushPose();
        graphics.pose().translate(pivotX, pivotY, 0.0F);

        graphics.blit(ICON_TEXTURE, iconX, iconY, 0.0F, 0.0F, ICON_SIZE, ICON_SIZE, TEXTURE_SIZE, TEXTURE_SIZE);
        // Driven by the game clock plus the partial tick, so the hand moves
        // at the same pace at any frame rate; it stops with the game, like the timer.
        float ticks = gameTime + deltaTracker.getGameTimeDeltaPartialTick(false);
        advanceHand(ticks, remaining);
        int position = Math.floorMod((int) Math.floor(handTurns * HAND_CELLS.length), HAND_CELLS.length);
        for (int[] cell : HAND_CELLS[position]) {
            int x = iconX + cell[0];
            int y = iconY + cell[1];
            graphics.fill(x, y, x + 1, y + 1, HAND_COLOUR);
        }

        graphics.drawString(font, time, textX, textY, TimerStyle.color(remaining, gameTime, config), true);

        graphics.pose().popPose();
    }

    /**
     * Moves the hand by the time since the last frame, at the speed the time
     * left calls for. It stops once time is up.
     */
    private static void advanceHand(float ticks, int remainingTicks) {
        float step = Float.isNaN(lastHandTicks) ? 0.0F : ticks - lastHandTicks;
        lastHandTicks = ticks;
        // Negative after a new world (its clock starts again); capped after a pause.
        step = Math.max(0.0F, Math.min(step, MAX_HAND_STEP_TICKS));
        handTurns = (handTurns + step / Ticks.PER_SECOND * turnsPerSecond(remainingTicks)) % 1.0F;
    }

    /** Speed of the hand for the time left; see {@link #HAND_SLOWEST_TURNS_PER_SECOND}. */
    private static float turnsPerSecond(int remainingTicks) {
        if (remainingTicks <= 0) {
            return 0.0F;
        }
        float seconds = (float) remainingTicks / Ticks.PER_SECOND;
        float urgency = 1.0F - Math.min(1.0F, seconds / HAND_RAMP_SECONDS);
        return HAND_SLOWEST_TURNS_PER_SECOND
                + (HAND_FASTEST_TURNS_PER_SECOND - HAND_SLOWEST_TURNS_PER_SECOND) * urgency * urgency;
    }

    /** A value kept within bounds; the lower bound wins on a screen too small for both. */
    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }
}
