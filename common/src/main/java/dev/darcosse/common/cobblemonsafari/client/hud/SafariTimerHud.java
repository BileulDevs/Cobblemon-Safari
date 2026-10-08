package dev.darcosse.common.cobblemonsafari.client.hud;

import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import dev.darcosse.common.cobblemonsafari.client.SafariClientState;
import dev.darcosse.common.cobblemonsafari.config.SafariClientConfig;
import dev.darcosse.common.cobblemonsafari.config.SafariClientConfigManager;
import dev.darcosse.common.cobblemonsafari.loan.LoanedBalls;
import dev.darcosse.common.cobblemonsafari.util.Ticks;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws the remaining Safari time. CLIENT ONLY: never reference this class from
 * code that can run on a dedicated server.
 *
 * <p>Runs every frame while the HUD is drawn, so it only reads cached state:
 * a few small text components and one short list per frame, no config file access.
 * Cost per frame: one pass over the 41 inventory slots to count loaned balls.
 */
public final class SafariTimerHud {

    /** Id of the HUD layer on NeoForge; also a stable name for other mods to order against. */
    public static final ResourceLocation LAYER_ID = CobblemonSafari.id("timer");

    /** Inner padding of the background panel, in GUI pixels. */
    private static final int PADDING = 4;
    /** Semi-transparent black, readable over snow and sand alike. */
    private static final int PANEL_COLOR = 0x90000000;
    private static final int LABEL_COLOR = 0xFFB8E07A;
    private static final int CATCHES_COLOR = 0xFFE8D27A;
    /** Faint vertical line between statistics; fully opaque lines look like glitches on bright sand. */
    private static final int DIVIDER_COLOR = 0x60FFFFFF;
    /**
     * Horizontal space between two segments. Below ~6 the dividers touch the
     * text; above ~14 the panel stretches across a 320 px wide GUI.
     */
    private static final int SEGMENT_GAP = 8;
    /** Label, time, balls, catches: sizes the list once instead of growing it every frame. */
    private static final int MAX_SEGMENTS = 4;

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
        List<Segment> segments = new ArrayList<>(MAX_SEGMENTS);
        if (config.showLabel) {
            segments.add(new Segment(Component.translatable(CobblemonSafari.MOD_ID + ".hud.label"), LABEL_COLOR));
        }
        segments.add(new Segment(remaining > 0
                ? Component.literal(Ticks.format(remaining))
                : Component.translatable(CobblemonSafari.MOD_ID + ".hud.time_up"),
                TimerStyle.color(remaining, gameTime, config)));
        if (config.showBalls && SafariClientState.ballsLoaned() > 0) {
            // Counted from the client's own inventory rather than sent by the
            // server: the inventory is already synced, and this reacts on the
            // very frame a ball is thrown or picked back up.
            int balls = minecraft.player != null ? LoanedBalls.count(minecraft.player) : 0;
            segments.add(new Segment(Component.translatable(CobblemonSafari.MOD_ID + ".hud.balls", balls),
                    TimerStyle.ballColor(balls)));
        }
        if (config.showCatches) {
            segments.add(new Segment(Component.translatable(CobblemonSafari.MOD_ID + ".hud.catches",
                    SafariClientState.catches()), CATCHES_COLOR));
        }

        int width = PADDING * 2 + SEGMENT_GAP * (segments.size() - 1);
        for (Segment segment : segments) {
            width += font.width(segment.text());
        }
        int height = font.lineHeight + PADDING * 2;

        int x = Math.round(graphics.guiWidth() * config.hudAnchor.xFraction - width * config.hudAnchor.xFraction)
                + config.hudOffsetX;
        int y = Math.round(graphics.guiHeight() * config.hudAnchor.yFraction - height * config.hudAnchor.yFraction)
                + config.hudOffsetY;
        // Keep the panel on screen whatever the offset and GUI scale.
        x = Math.max(0, Math.min(x, graphics.guiWidth() - width));
        y = Math.max(0, Math.min(y, graphics.guiHeight() - height));

        graphics.fill(x, y, x + width, y + height, PANEL_COLOR);
        int textY = y + PADDING + 1;
        int cursor = x + PADDING;
        for (int i = 0; i < segments.size(); i++) {
            Segment segment = segments.get(i);
            if (i > 0) {
                // Thin divider centred in the gap; skipped after the label,
                // which reads as a title for the time rather than a separate stat.
                if (!(i == 1 && config.showLabel)) {
                    int dividerX = cursor - SEGMENT_GAP / 2 - 1;
                    graphics.fill(dividerX, y + PADDING, dividerX + 1, y + height - PADDING, DIVIDER_COLOR);
                }
            }
            graphics.drawString(font, segment.text(), cursor, textY, segment.color(), true);
            cursor += font.width(segment.text()) + SEGMENT_GAP;
        }
    }

    /** One piece of text of the panel with its colour. */
    private record Segment(Component text, int color) {
    }
}
