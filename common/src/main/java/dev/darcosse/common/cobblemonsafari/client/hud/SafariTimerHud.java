package dev.darcosse.common.cobblemonsafari.client.hud;

import com.mojang.math.Axis;
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
 * Draws the remaining Safari time: a Safari Ball that rocks gently, faster
 * when time runs low, with the time under it. CLIENT ONLY: never reference
 * this class from code that can run on a dedicated server.
 *
 * <p>Runs every frame while the HUD is drawn, so it only reads cached state:
 * one formatted string and one texture blit per frame, no config file access.
 */
public final class SafariTimerHud {

    /** Id of the HUD layer on NeoForge; also a stable name for other mods to order against. */
    public static final ResourceLocation LAYER_ID = CobblemonSafari.id("timer");

    /**
     * Cobblemon's own Safari Ball item texture (16 x 16), read from its
     * resources rather than copied, so a resource pack restyling the ball
     * restyles the timer too.
     */
    private static final ResourceLocation BALL_TEXTURE =
            ResourceLocation.fromNamespaceAndPath("cobblemon", "textures/item/poke_balls/safari_ball.png");
    private static final int TEXTURE_SIZE = 16;
    /**
     * Size of the texture on screen, in GUI pixels: one per texel, like an
     * item in the hotbar, which keeps it crisp. The ball itself is the
     * middle 12 x 12; a non-integer scale (e.g. 18) would give it uneven
     * pixels.
     */
    private static final int BALL_SIZE = 16;
    /** Transparent margin of the texture around the ball, in GUI pixels: the time tucks under the ball, not the margin. */
    private static final int BALL_MARGIN = 2;

    /** Scale of the time text: the normal font size, matching the smaller ball. */
    private static final float TEXT_SCALE = 1.0F;
    /** Gap between the bottom of the ball and the top of the time, in GUI pixels. */
    private static final int TEXT_GAP = 1;

    /**
     * Rocking of the ball: one swing every this many ticks, up to this angle
     * each way. Below the critical time it swings this many times faster,
     * like a ball about to break free.
     */
    private static final float SWING_PERIOD_TICKS = 80.0F;
    private static final float SWING_DEGREES = 30.0F;
    private static final float CRITICAL_SWING_SPEED = 5.0F;

    /** Keeps the whole widget this far inside the screen whatever the offset. */
    private static final int SCREEN_MARGIN = 2;

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
        int textWidth = Math.round(font.width(time) * TEXT_SCALE);
        int halfWidth = Math.max(BALL_SIZE, textWidth) / 2;
        int height = BALL_SIZE - BALL_MARGIN + TEXT_GAP + Math.round(font.lineHeight * TEXT_SCALE);

        // The pivot is the centre of the ball; the anchor and offset place it.
        int pivotX = Math.round(graphics.guiWidth() * config.hudAnchor.xFraction) + config.hudOffsetX;
        int pivotY = Math.round(graphics.guiHeight() * config.hudAnchor.yFraction) + config.hudOffsetY;
        pivotX = clamp(pivotX, halfWidth + SCREEN_MARGIN, graphics.guiWidth() - halfWidth - SCREEN_MARGIN);
        pivotY = clamp(pivotY, BALL_SIZE / 2 + SCREEN_MARGIN,
                graphics.guiHeight() - (height - BALL_SIZE / 2) - SCREEN_MARGIN);

        graphics.pose().pushPose();
        graphics.pose().translate(pivotX, pivotY, 0.0F);

        // Driven by the game clock plus the partial tick, so the swing is
        // smooth at any frame rate; it pauses with the game, like the timer.
        float ticks = gameTime + deltaTracker.getGameTimeDeltaPartialTick(false);
        boolean critical = remaining > 0 && Ticks.toSecondsCeil(remaining) <= config.criticalTimeSeconds;
        float speed = critical ? CRITICAL_SWING_SPEED : 1.0F;
        float swing = (float) Math.sin(2.0D * Math.PI * ticks * speed / SWING_PERIOD_TICKS);
        graphics.pose().pushPose();
        graphics.pose().mulPose(Axis.ZP.rotationDegrees(swing * SWING_DEGREES));
        graphics.blit(BALL_TEXTURE, -BALL_SIZE / 2, -BALL_SIZE / 2, 0.0F, 0.0F, BALL_SIZE, BALL_SIZE,
                TEXTURE_SIZE, TEXTURE_SIZE);
        graphics.pose().popPose();

        graphics.pose().pushPose();
        graphics.pose().translate(0.0F, BALL_SIZE / 2.0F - BALL_MARGIN + TEXT_GAP, 0.0F);
        graphics.pose().scale(TEXT_SCALE, TEXT_SCALE, 1.0F);
        graphics.drawString(font, time, -font.width(time) / 2, 0, TimerStyle.color(remaining, gameTime, config), true);
        graphics.pose().popPose();

        graphics.pose().popPose();
    }

    /** A value kept within bounds; the lower bound wins on a screen too small for both. */
    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }
}
