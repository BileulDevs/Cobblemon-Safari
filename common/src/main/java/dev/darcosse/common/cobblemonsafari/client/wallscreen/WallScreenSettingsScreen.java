package dev.darcosse.common.cobblemonsafari.client.wallscreen;

import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import dev.darcosse.common.cobblemonsafari.block.SafariWallScreenBlock;
import dev.darcosse.common.cobblemonsafari.block.WallScreenContent;
import dev.darcosse.common.cobblemonsafari.network.SafariClientNetwork;
import dev.darcosse.common.cobblemonsafari.network.SafariWallScreenOpenPayload;
import dev.darcosse.common.cobblemonsafari.network.SafariWallScreenSetPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.util.EnumMap;
import java.util.Map;

/**
 * Settings of a Safari Wall Screen group: what it shows and how far it is
 * tilted, toward the floor or the ceiling.
 *
 * <p>Every change is sent to the server at once, so the screen behind this
 * one updates live while the player adjusts it; the game is not paused for
 * the same reason. The server re-checks everything, and this screen only
 * opens when the server said the player may change the group.
 *
 * CLIENT ONLY.
 */
public class WallScreenSettingsScreen extends Screen {

    /** Panel size in GUI pixels. Taller fits more content choices; the buttons are laid out from the top. */
    private static final int PANEL_WIDTH = 220;
    private static final int PANEL_HEIGHT = 188;
    private static final int MARGIN = 10;
    private static final int TITLE_HEIGHT = 20;
    private static final int BUTTON_HEIGHT = 20;
    /** Vertical space between two rows of buttons. */
    private static final int ROW_GAP = 4;
    /** Space between the section label and the buttons under it. */
    private static final int LABEL_GAP = 12;
    /** Space between the tilt slider and the hint under it. */
    private static final int HINT_GAP = 3;
    private static final int DONE_BUTTON_WIDTH = 100;

    /** Panel colours (ARGB), in the spirit of the shop screen: dark panel, lighter title band. */
    private static final int PANEL_BORDER = 0xFF5A4630;
    private static final int PANEL_COLOR = 0xEE1E2026;
    private static final int TITLE_BAR_COLOR = 0xFF2E3238;
    private static final int TEXT_COLOR = 0xFFFFFFFF;
    private static final int MUTED_COLOR = 0xFFA8A8A8;

    /** The screen the settings were opened on; the server finds the group from it. */
    private final BlockPos pos;
    private WallScreenContent content;
    private int tilt;

    private final Map<WallScreenContent, Button> contentButtons = new EnumMap<>(WallScreenContent.class);
    private int left;
    private int top;
    private int tiltRowY;

    /** Screen starting from the settings the server sent. */
    private WallScreenSettingsScreen(SafariWallScreenOpenPayload payload) {
        super(tr("wall_screen.title"));
        this.pos = payload.pos();
        this.content = payload.content();
        // Clamped: a server of another build could send a range this client does not know.
        this.tilt = Math.max(-SafariWallScreenBlock.MAX_TILT_STEPS,
                Math.min(SafariWallScreenBlock.MAX_TILT_STEPS, payload.tilt()));
    }

    /** Payload handler, installed through {@code WallScreenClientBridge}: opens the screen. */
    public static void handle(SafariWallScreenOpenPayload payload) {
        Minecraft.getInstance().setScreen(new WallScreenSettingsScreen(payload));
    }

    /** Places the buttons; called again by vanilla when the window is resized. */
    @Override
    protected void init() {
        contentButtons.clear();
        left = (width - PANEL_WIDTH) / 2;
        top = (height - PANEL_HEIGHT) / 2;
        int buttonWidth = PANEL_WIDTH - 2 * MARGIN;
        int y = top + TITLE_HEIGHT + LABEL_GAP;
        for (WallScreenContent choice : WallScreenContent.values()) {
            Button button = addRenderableWidget(Button.builder(tr(choice.translationKey()), b -> choose(choice))
                    .bounds(left + MARGIN, y, buttonWidth, BUTTON_HEIGHT).build());
            contentButtons.put(choice, button);
            y += BUTTON_HEIGHT + ROW_GAP;
        }
        tiltRowY = y + ROW_GAP;
        addRenderableWidget(new TiltSlider(left + MARGIN, tiltRowY, buttonWidth, BUTTON_HEIGHT));
        addRenderableWidget(Button.builder(Component.translatable("gui.done"),
                        b -> Minecraft.getInstance().setScreen(null))
                .bounds(left + (PANEL_WIDTH - DONE_BUTTON_WIDTH) / 2, top + PANEL_HEIGHT - MARGIN - BUTTON_HEIGHT,
                        DONE_BUTTON_WIDTH, BUTTON_HEIGHT).build());
        refreshButtons();
    }

    /** The chosen content's button is disabled, which is how vanilla screens show the current choice. */
    private void refreshButtons() {
        for (Map.Entry<WallScreenContent, Button> entry : contentButtons.entrySet()) {
            entry.getValue().active = entry.getKey() != content;
        }
    }

    /** Switches the group to another content. */
    private void choose(WallScreenContent choice) {
        content = choice;
        apply();
    }

    /** Sets the tilt from the slider; sent only when the step actually changes. */
    private void setTilt(int steps) {
        if (steps != tilt) {
            tilt = steps;
            apply();
        }
    }

    /** The slider's text: the signed angle, with a plus sign so both directions read alike. */
    private static Component tiltLabel(int steps) {
        int degrees = (int) SafariWallScreenBlock.tiltDegrees(steps);
        return Component.translatable(CobblemonSafari.MOD_ID + ".message.wall_screen_tilt",
                degrees > 0 ? "+" + degrees : Integer.toString(degrees));
    }

    /** Sends the current settings and updates the buttons. */
    private void apply() {
        SafariClientNetwork.sendToServer(new SafariWallScreenSetPayload(pos, content.ordinal(), tilt));
        refreshButtons();
    }

    /** The screen behind stays live, so the player sees each change on it. */
    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /**
     * Draws the panel, title, section label and tilt value under the
     * buttons. Vanilla's {@code render} calls this first, then the widgets.
     */
    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(graphics, mouseX, mouseY, partialTick);
        graphics.fill(left - 1, top - 1, left + PANEL_WIDTH + 1, top + PANEL_HEIGHT + 1, PANEL_BORDER);
        graphics.fill(left, top, left + PANEL_WIDTH, top + PANEL_HEIGHT, PANEL_COLOR);
        graphics.fill(left, top, left + PANEL_WIDTH, top + TITLE_HEIGHT, TITLE_BAR_COLOR);
        graphics.drawString(font, title, left + (PANEL_WIDTH - font.width(title)) / 2,
                top + (TITLE_HEIGHT - font.lineHeight) / 2, TEXT_COLOR);

        Component section = tr("wall_screen.content");
        graphics.drawString(font, section, left + MARGIN, top + TITLE_HEIGHT + (LABEL_GAP - font.lineHeight) / 2 + 1,
                MUTED_COLOR);

        Component hint = tr("wall_screen.tilt_hint");
        graphics.drawString(font, hint, left + (PANEL_WIDTH - font.width(hint)) / 2,
                tiltRowY + BUTTON_HEIGHT + HINT_GAP, MUTED_COLOR);
    }

    /**
     * The tilt slider, like an HTML range input: one notch per step from
     * {@code -MAX_TILT_STEPS} (left, facing the ceiling) to
     * {@code +MAX_TILT_STEPS} (right, facing the floor). The handle snaps to
     * the notches while dragging, and every new notch is sent at once so the
     * screen behind moves live.
     */
    private final class TiltSlider extends AbstractSliderButton {

        /** Steps from one end of the slider to the other. */
        private static final int SPAN = 2 * SafariWallScreenBlock.MAX_TILT_STEPS;

        /** Slider at the current tilt. */
        private TiltSlider(int x, int y, int width, int height) {
            super(x, y, width, height, tiltLabel(tilt), toValue(tilt));
        }

        /** Slider position (0 to 1) of a signed step. */
        private static double toValue(int steps) {
            return (steps + SafariWallScreenBlock.MAX_TILT_STEPS) / (double) SPAN;
        }

        /** Shows the angle of the notch the handle sits on. */
        @Override
        protected void updateMessage() {
            setMessage(tiltLabel(tilt));
        }

        /** Snaps the handle to the nearest notch and applies it. */
        @Override
        protected void applyValue() {
            int steps = (int) Math.round(value * SPAN) - SafariWallScreenBlock.MAX_TILT_STEPS;
            value = toValue(steps);
            setTilt(steps);
        }
    }

    /** A translated text of the mod. */
    private static Component tr(String path) {
        return Component.translatable(CobblemonSafari.MOD_ID + "." + path);
    }
}
