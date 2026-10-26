package dev.darcosse.common.cobblemonsafari.client.dialogue;

import com.cobblemon.mod.common.client.gui.dialogue.DialogueScreen;
import com.cobblemon.mod.common.client.gui.dialogue.widgets.DialogueOptionWidget;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Consumer;

/**
 * Scrolls the answers of a Cobblemon NPC dialogue when they do not fit under
 * the dialogue box: only as many as fit are shown, with a scroll bar beside
 * them; the mouse wheel over the answers, dragging the thumb or clicking the
 * track show the others.
 *
 * <p>Why: Cobblemon stacks vertical options one under the other below the
 * box, with no limit and no scrolling. A dialogue with many answers (the
 * Safari Guide offers up to seven destinations) runs off the bottom of the
 * screen at large GUI scales, and the last answers cannot be clicked. This
 * applies to every Cobblemon dialogue, the mod's or a datapack's: nothing
 * changes while the answers fit.
 *
 * <p>The list scrolls one whole answer at a time: Cobblemon draws its
 * answers itself, so a half-shown one could not be clipped.
 *
 * <p>Applied by ScreenDialogueOptionsMixin each time a dialogue screen lays
 * its widgets out (opening, resizing, next page of the dialogue). Only the
 * vertical layout is scrolled; a horizontal row is left as Cobblemon made it.
 *
 * <p>Client only; state of the client thread.
 */
public final class DialogueOptionPager {

    /** Space kept free under the last answer, in GUI pixels, so it does not touch the screen edge. */
    private static final int BOTTOM_MARGIN = 6;

    /**
     * The scroll bar: its width and its gap to the answers, in GUI pixels.
     * Narrower is hard to grab; wider crowds the answers.
     */
    private static final int BAR_WIDTH = 6;
    private static final int BAR_GAP = 4;

    /** Shortest thumb, in GUI pixels, so it stays grabbable with many answers. */
    private static final int MIN_THUMB_HEIGHT = 10;

    /**
     * Colours of the bar, ARGB: close to the greys of Cobblemon's dialogue
     * buttons, lighter while the thumb is hovered or dragged.
     */
    private static final int TRACK_COLOR = 0xA0202020;
    private static final int TRACK_BORDER = 0xFF0E0E0E;
    private static final int THUMB_COLOR = 0xFF9A9A9A;
    private static final int THUMB_HOVER_COLOR = 0xFFD0D0D0;
    private static final int THUMB_SHADE = 0xFF5E5E5E;

    /**
     * Where answers out of the window are parked, far above the screen.
     *
     * <p>Why parked and not hidden: Cobblemon's answer handles a click from
     * its last hover state alone (DialogueOptionWidget.mouseClicked reads
     * isHovered, not the mouse), and Minecraft refreshes that state only
     * while the widget is visible. A hidden answer kept the hover it had
     * when it scrolled out, so after scrolling with the wheel over the list
     * a click went to the answer that had left, not to the one shown under
     * the mouse (the Guide took players to another house than the one they
     * picked). A parked answer stays visible, far off screen, so its hover
     * goes false on the next frame; it draws nothing anyone can see.
     */
    private static final int PARKED_Y = -10_000;

    /** Scroll bars per open screen; weak, so a closed screen is forgotten with it. */
    private static final Map<Screen, ScrollBar> BARS = new WeakHashMap<>();

    private DialogueOptionPager() {
    }

    /**
     * Scrolls the answers of a dialogue screen when they overflow; does
     * nothing for any other screen. Cost: a walk over the answers, only
     * when the screen lays its widgets out; the bar itself draws two
     * rectangles a frame.
     *
     * @param addWidget adds a widget to the screen's lists, as the protected Screen.addRenderableWidget would
     */
    public static void layout(Screen screen, Consumer<AbstractWidget> addWidget) {
        if (!(screen instanceof DialogueScreen dialogue)) {
            return;
        }
        List<DialogueOptionWidget> options = dialogue.getDialogueOptionWidgets();
        ScrollBar existing = BARS.get(screen);
        if (existing != null && existing.options == options) {
            // Same answers laid out again (Screen.init after rebuildWidgets): keep the scroll.
            existing.apply();
            return;
        }
        BARS.remove(screen);
        if (options.size() < 2) {
            return;
        }
        int top = options.get(0).getY();
        int step = options.get(1).getY() - top;
        if (step <= 0) {
            // A horizontal row: Cobblemon puts every answer on the same line.
            return;
        }
        int optionHeight = options.get(0).getHeight();
        int room = screen.height - BOTTOM_MARGIN - top;
        int shown = Math.max(1, (room - optionHeight) / step + 1);
        if (options.size() <= shown) {
            return;
        }
        DialogueOptionWidget first = options.get(0);
        ScrollBar bar = new ScrollBar(dialogue, options, first.getX(), first.getWidth(), top, step, optionHeight, shown);
        addWidget.accept(bar);
        // First among the screen's children: the wheel goes to the first child under the
        // mouse, and over the answers that would be an answer, which ignores it.
        @SuppressWarnings("unchecked")
        List<GuiEventListener> children = (List<GuiEventListener>) screen.children();
        children.remove(bar);
        children.add(0, bar);
        BARS.put(screen, bar);
        bar.apply();
    }

    /**
     * The scroll bar beside the answers, and the scrolling itself. Its
     * mouse area covers the answers too, for the wheel only: clicks there
     * are left to the answers.
     */
    private static final class ScrollBar extends AbstractWidget {

        /** The dialogue, asked whether its answers are on screen yet. */
        private final DialogueScreen dialogue;
        final List<DialogueOptionWidget> options;
        private final int optionsLeft;
        private final int optionsWidth;
        private final int top;
        private final int step;
        /** Answers visible at once. */
        private final int shown;
        /** Index of the first visible answer. */
        private int first;
        /** While dragging: how far below the thumb's top it was grabbed, in GUI pixels; -1 otherwise. */
        private double grab = -1.0D;

        ScrollBar(DialogueScreen dialogue, List<DialogueOptionWidget> options, int optionsLeft, int optionsWidth,
                  int top, int step, int optionHeight, int shown) {
            super(optionsLeft + optionsWidth + BAR_GAP, top, BAR_WIDTH, (shown - 1) * step + optionHeight,
                    Component.empty());
            this.dialogue = dialogue;
            this.options = options;
            this.optionsLeft = optionsLeft;
            this.optionsWidth = optionsWidth;
            this.top = top;
            this.step = step;
            this.shown = shown;
        }

        /**
         * Whether the answers are on screen. Cobblemon shows them only once
         * the line has finished typing out (DialogueScreen.renderInput); the
         * bar shows and answers with them, not before.
         */
        private boolean answersShown() {
            return visible && dialogue.renderInput();
        }

        /** Highest first index: the last answer at the bottom of the window. */
        private int maxFirst() {
            return options.size() - shown;
        }

        /** Scrolls to a first index, clamped. */
        private void scrollTo(int index) {
            first = Mth.clamp(index, 0, maxFirst());
            apply();
        }

        /** Puts the answers of the window in their slots and parks the others (see {@link #PARKED_Y}). */
        void apply() {
            for (int i = 0; i < options.size(); i++) {
                DialogueOptionWidget option = options.get(i);
                int slot = i - first;
                boolean inWindow = slot >= 0 && slot < shown;
                option.setY(inWindow ? top + slot * step : PARKED_Y);
            }
        }

        private int thumbHeight() {
            return Math.max(MIN_THUMB_HEIGHT, height * shown / options.size());
        }

        private int thumbTop() {
            int travel = height - thumbHeight();
            return getY() + (maxFirst() == 0 ? 0 : travel * first / maxFirst());
        }

        private boolean overBar(double mouseX, double mouseY) {
            return mouseX >= getX() && mouseX < getX() + width && mouseY >= getY() && mouseY < getY() + height;
        }

        private boolean overOptions(double mouseX, double mouseY) {
            return mouseX >= optionsLeft && mouseX < optionsLeft + optionsWidth && mouseY >= getY()
                    && mouseY < getY() + height;
        }

        /** The bar and the answers' window, so the wheel reaches this widget over either. */
        @Override
        public boolean isMouseOver(double mouseX, double mouseY) {
            return answersShown() && (overBar(mouseX, mouseY) || overOptions(mouseX, mouseY));
        }

        /** One answer per notch of the wheel. */
        @Override
        public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
            if (scrollY == 0.0D || !answersShown()) {
                return false;
            }
            scrollTo(first - (int) Math.signum(scrollY));
            return true;
        }

        /**
         * Grabs the thumb, or jumps by a window when the track is clicked
         * above or below it. A click on the answers is given to the answer
         * of the window under the mouse, and to no other.
         *
         * <p>Why the bar hands it over: the screen offers a click to each of
         * its children in turn, and an answer takes it whenever its last
         * hover state says so. In the frame where the list scrolled, an
         * answer that just left the window may still say so (see
         * {@link #PARKED_Y}); the bar comes first among the children, so it
         * decides.
         */
        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (!active || !answersShown()) {
                return false;
            }
            if (overOptions(mouseX, mouseY)) {
                for (int i = first; i < first + shown; i++) {
                    DialogueOptionWidget option = options.get(i);
                    if (option.isMouseOver(mouseX, mouseY)) {
                        option.mouseClicked(mouseX, mouseY, button);
                        break;
                    }
                }
                // Taken even when no answer is under the mouse (the gap between two):
                // otherwise a parked answer could still claim it.
                return true;
            }
            if (button != 0 || !overBar(mouseX, mouseY)) {
                return false;
            }
            int thumbTop = thumbTop();
            if (mouseY < thumbTop) {
                scrollTo(first - shown);
            } else if (mouseY >= thumbTop + thumbHeight()) {
                scrollTo(first + shown);
            } else {
                grab = mouseY - thumbTop;
            }
            return true;
        }

        /** Moves the thumb with the mouse, snapping the list to whole answers. */
        @Override
        public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
            if (grab < 0.0D || button != 0) {
                return false;
            }
            int travel = height - thumbHeight();
            if (travel > 0) {
                double share = (mouseY - grab - getY()) / travel;
                scrollTo((int) Math.round(share * maxFirst()));
            }
            return true;
        }

        /** Lets go of the thumb. */
        @Override
        public boolean mouseReleased(double mouseX, double mouseY, int button) {
            boolean wasDragging = grab >= 0.0D;
            grab = -1.0D;
            return wasDragging;
        }

        /** The track, then the thumb with a darker lower edge, lit while hovered or dragged. */
        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            if (!answersShown()) {
                return;
            }
            int x0 = getX();
            int y0 = getY();
            graphics.fill(x0, y0, x0 + width, y0 + height, TRACK_BORDER);
            graphics.fill(x0 + 1, y0 + 1, x0 + width - 1, y0 + height - 1, TRACK_COLOR);
            int thumbTop = thumbTop();
            int thumbBottom = thumbTop + thumbHeight();
            boolean lit = grab >= 0.0D || overBar(mouseX, mouseY) && mouseY >= thumbTop && mouseY < thumbBottom;
            graphics.fill(x0 + 1, thumbTop + 1, x0 + width - 1, thumbBottom - 1, lit ? THUMB_HOVER_COLOR : THUMB_COLOR);
            graphics.fill(x0 + 1, thumbBottom - 2, x0 + width - 1, thumbBottom - 1, THUMB_SHADE);
        }

        /** Says which answers are shown, for the narrator. */
        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            output.add(NarratedElementType.POSITION, Component.translatable("narrator.position.list",
                    first + 1, options.size()));
        }
    }
}
