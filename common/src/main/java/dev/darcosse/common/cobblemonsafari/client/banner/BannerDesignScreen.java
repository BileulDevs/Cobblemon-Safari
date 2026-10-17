package dev.darcosse.common.cobblemonsafari.client.banner;

import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import dev.darcosse.common.cobblemonsafari.decor.BannerDesigns;
import dev.darcosse.common.cobblemonsafari.network.SafariBannerOpenPayload;
import dev.darcosse.common.cobblemonsafari.network.SafariBannerSetPayload;
import dev.darcosse.common.cobblemonsafari.network.SafariClientNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Picks the picture of a Safari Banner among every texture under
 * {@code textures/block/banner/} of the loaded resource packs, shown as a
 * grid of thumbnails (animated ones move, being atlas sprites). When there
 * are more than fit, a scrollbar appears on the right of the grid: drag its
 * thumb, click its track, or use the wheel.
 *
 * <p>Every click is sent to the server at once, so the banner behind the
 * screen changes live; the game is not paused for the same reason. The
 * server re-checks everything, and this screen only opens when the server
 * said the player may change the banner.
 *
 * CLIENT ONLY.
 */
public class BannerDesignScreen extends Screen {

    /** Where the designs are, relative to a namespace's assets; the atlas id drops "textures/" and ".png". */
    private static final String TEXTURE_FOLDER = "textures/" + BannerDesigns.FOLDER.substring(0,
            BannerDesigns.FOLDER.length() - 1);
    private static final String TEXTURES_PREFIX = "textures/";
    private static final String PNG = ".png";

    /** Panel size in GUI pixels; the grid fills it under the title, the Done button at the bottom. */
    private static final int PANEL_WIDTH = 232;
    private static final int PANEL_HEIGHT = 200;
    private static final int MARGIN = 10;
    private static final int TITLE_HEIGHT = 20;
    private static final int BUTTON_HEIGHT = 20;
    private static final int DONE_BUTTON_WIDTH = 100;

    /** Thumbnail size, at the cloth's 1:2 ratio, and the gap between thumbnails. */
    private static final int THUMB_WIDTH = 24;
    private static final int THUMB_HEIGHT = 48;
    private static final int THUMB_GAP = 6;
    /** Frame drawn around a thumbnail, in GUI pixels. */
    private static final int FRAME = 1;

    /** Panel colours (ARGB), as the wall screen settings. */
    private static final int PANEL_BORDER = 0xFF5A4630;
    private static final int PANEL_COLOR = 0xEE1E2026;
    private static final int TITLE_BAR_COLOR = 0xFF2E3238;
    private static final int TEXT_COLOR = 0xFFFFFFFF;
    private static final int THUMB_BACK = 0xFF2A2D33;
    private static final int SELECTED_FRAME = 0xFFF2C14E;
    private static final int HOVER_FRAME = 0xFFB0B0B0;

    /**
     * Scrollbar, in GUI pixels: width, gap to the grid, and the shortest the
     * thumb gets (with many rows a proportional thumb would be too small to
     * grab). Only reserved when the designs do not fit.
     */
    private static final int SCROLLBAR_WIDTH = 6;
    private static final int SCROLLBAR_GAP = 4;
    private static final int SCROLLBAR_MIN_THUMB = 10;
    /** Scrollbar colours (ARGB), in the panel's walnut and gold. */
    private static final int SCROLLBAR_TRACK = 0xFF2A2D33;
    private static final int SCROLLBAR_THUMB = 0xFF8A7458;
    private static final int SCROLLBAR_THUMB_HOVER = 0xFFC9A66B;
    /** Value of {@link #thumbGrab} while the thumb is not held. */
    private static final double NOT_GRABBED = -1;

    /** The banner the picker was opened on. */
    private final BlockPos pos;
    private ResourceLocation selected;
    private final List<ResourceLocation> designs = new ArrayList<>();

    private int left;
    private int top;
    private int columns;
    private int visibleRows;
    /** First visible row of the grid, for scrolling. */
    private int scrollRow;
    /** Whether the designs overflow, so the scrollbar is shown and its lane reserved. */
    private boolean scrollable;
    /** Left edge of the grid area, and its width (the panel minus margins and the scrollbar lane). */
    private int gridLeft;
    private int gridAreaWidth;
    /**
     * While the thumb is dragged, how far below its top it was grabbed, so it
     * does not jump under the cursor; {@link #NOT_GRABBED} otherwise.
     */
    private double thumbGrab = NOT_GRABBED;

    /** Picker starting from the design the server sent. */
    private BannerDesignScreen(SafariBannerOpenPayload payload) {
        super(Component.translatable(CobblemonSafari.MOD_ID + ".banner.title"));
        this.pos = payload.pos();
        ResourceLocation current = BannerDesigns.parse(payload.design());
        this.selected = current != null ? current : BannerDesigns.DEFAULT;
    }

    /** Payload handler, installed through {@code BannerClientBridge}: opens the picker. */
    public static void handle(SafariBannerOpenPayload payload) {
        Minecraft.getInstance().setScreen(new BannerDesignScreen(payload));
    }

    /**
     * Lists the designs and lays out the grid; called again by vanilla when
     * the window is resized. The mod's own designs come first, then each
     * resource pack namespace in alphabetical order.
     */
    @Override
    protected void init() {
        designs.clear();
        Minecraft.getInstance().getResourceManager()
                .listResources(TEXTURE_FOLDER, file -> file.getPath().endsWith(PNG))
                .keySet().forEach(file -> designs.add(ResourceLocation.fromNamespaceAndPath(file.getNamespace(),
                        file.getPath().substring(TEXTURES_PREFIX.length(), file.getPath().length() - PNG.length()))));
        designs.sort(Comparator.comparing((ResourceLocation id) -> !id.getNamespace().equals(CobblemonSafari.MOD_ID))
                .thenComparing(ResourceLocation::getNamespace).thenComparing(ResourceLocation::getPath));

        left = (width - PANEL_WIDTH) / 2;
        top = (height - PANEL_HEIGHT) / 2;
        int gridHeight = PANEL_HEIGHT - TITLE_HEIGHT - 3 * MARGIN - BUTTON_HEIGHT;
        visibleRows = Math.max(1, (gridHeight + THUMB_GAP) / (THUMB_HEIGHT + THUMB_GAP));
        gridLeft = left + MARGIN;
        gridAreaWidth = PANEL_WIDTH - 2 * MARGIN;
        columns = columnsIn(gridAreaWidth);
        // Only when the designs overflow does the scrollbar take its lane; that
        // can cost a column, which is why the overflow is checked again after.
        scrollable = maxScroll() > 0;
        if (scrollable) {
            gridAreaWidth -= SCROLLBAR_WIDTH + SCROLLBAR_GAP;
            columns = columnsIn(gridAreaWidth);
        }
        scrollRow = Mth.clamp(scrollRow, 0, maxScroll());
        thumbGrab = NOT_GRABBED;

        addRenderableWidget(Button.builder(Component.translatable("gui.done"),
                        b -> Minecraft.getInstance().setScreen(null))
                .bounds(left + (PANEL_WIDTH - DONE_BUTTON_WIDTH) / 2, top + PANEL_HEIGHT - MARGIN - BUTTON_HEIGHT,
                        DONE_BUTTON_WIDTH, BUTTON_HEIGHT).build());
    }

    /** How many thumbnails fit side by side in a width. */
    private static int columnsIn(int width) {
        return Math.max(1, (width + THUMB_GAP) / (THUMB_WIDTH + THUMB_GAP));
    }

    /** The screen behind stays live, so the player sees the banner change. */
    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** Draws the panel, the title, the thumbnails and the name of the hovered one. */
    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        ResourceLocation hovered = designAt(mouseX, mouseY);
        for (int i = 0; i < designs.size(); i++) {
            int[] cell = cellOf(i);
            if (cell == null) {
                continue;
            }
            ResourceLocation design = designs.get(i);
            int x = cell[0];
            int y = cell[1];
            int frame = design.equals(selected) ? SELECTED_FRAME : design.equals(hovered) ? HOVER_FRAME : 0;
            if (frame != 0) {
                graphics.fill(x - FRAME, y - FRAME, x + THUMB_WIDTH + FRAME, y + THUMB_HEIGHT + FRAME, frame);
            }
            graphics.fill(x, y, x + THUMB_WIDTH, y + THUMB_HEIGHT, THUMB_BACK);
            TextureAtlasSprite sprite = SafariBannerRenderer.sprite(design);
            graphics.blit(x, y, 0, THUMB_WIDTH, THUMB_HEIGHT, sprite);
        }
        if (scrollable) {
            renderScrollbar(graphics, mouseX, mouseY);
        }
        if (hovered != null) {
            graphics.renderTooltip(font, nameOf(hovered), mouseX, mouseY);
        }
    }

    /** Track and thumb; the thumb lights up while hovered or held. */
    private void renderScrollbar(GuiGraphics graphics, int mouseX, int mouseY) {
        int x = trackLeft();
        graphics.fill(x, trackTop(), x + SCROLLBAR_WIDTH, trackTop() + trackHeight(), SCROLLBAR_TRACK);
        int thumbTop = thumbTop();
        boolean lit = thumbGrab != NOT_GRABBED || (mouseX >= x && mouseX < x + SCROLLBAR_WIDTH
                && mouseY >= thumbTop && mouseY < thumbTop + thumbHeight());
        graphics.fill(x, thumbTop, x + SCROLLBAR_WIDTH, thumbTop + thumbHeight(),
                lit ? SCROLLBAR_THUMB_HOVER : SCROLLBAR_THUMB);
    }

    /** Panel and title, under the widgets. */
    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(graphics, mouseX, mouseY, partialTick);
        graphics.fill(left - 1, top - 1, left + PANEL_WIDTH + 1, top + PANEL_HEIGHT + 1, PANEL_BORDER);
        graphics.fill(left, top, left + PANEL_WIDTH, top + PANEL_HEIGHT, PANEL_COLOR);
        graphics.fill(left, top, left + PANEL_WIDTH, top + TITLE_HEIGHT, TITLE_BAR_COLOR);
        graphics.drawString(font, title, left + (PANEL_WIDTH - font.width(title)) / 2,
                top + (TITLE_HEIGHT - font.lineHeight) / 2, TEXT_COLOR);
    }

    /**
     * A click on a thumbnail shows that picture on the banner at once. On the
     * scrollbar, the thumb is grabbed; a click on the track moves the thumb
     * under the cursor and grabs it there, so the player can keep dragging.
     */
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (scrollable && onTrack(mouseX, mouseY)) {
            int thumbTop = thumbTop();
            if (mouseY >= thumbTop && mouseY < thumbTop + thumbHeight()) {
                thumbGrab = mouseY - thumbTop;
            } else {
                thumbGrab = thumbHeight() / 2.0;
                dragThumbTo(mouseY);
            }
            return true;
        }
        ResourceLocation design = designAt(mouseX, mouseY);
        if (design != null) {
            selected = design;
            SafariClientNetwork.sendToServer(new SafariBannerSetPayload(pos, design.toString()));
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /** Moves the held thumb with the cursor. */
    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (thumbGrab != NOT_GRABBED) {
            dragThumbTo(mouseY);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    /** Lets go of the thumb. */
    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (thumbGrab != NOT_GRABBED) {
            thumbGrab = NOT_GRABBED;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    /**
     * Scrolls to the row whose thumb position is nearest the cursor. The grid
     * moves a whole row at a time, so thumbnails are never cut in half; the
     * thumb follows in steps.
     */
    private void dragThumbTo(double mouseY) {
        int travel = trackHeight() - thumbHeight();
        if (travel <= 0) {
            return;
        }
        double fraction = (mouseY - thumbGrab - trackTop()) / travel;
        scrollRow = Mth.clamp((int) Math.round(fraction * maxScroll()), 0, maxScroll());
    }

    /** Whether a point is on the scrollbar's track (thumb included). */
    private boolean onTrack(double mouseX, double mouseY) {
        return mouseX >= trackLeft() && mouseX < trackLeft() + SCROLLBAR_WIDTH
                && mouseY >= trackTop() && mouseY < trackTop() + trackHeight();
    }

    /** Left edge of the scrollbar, against the panel's right margin. */
    private int trackLeft() {
        return left + PANEL_WIDTH - MARGIN - SCROLLBAR_WIDTH;
    }

    /** Top of the scrollbar, level with the first row of thumbnails. */
    private int trackTop() {
        return top + TITLE_HEIGHT + MARGIN;
    }

    /** Height of the scrollbar: the visible rows of thumbnails. */
    private int trackHeight() {
        return visibleRows * (THUMB_HEIGHT + THUMB_GAP) - THUMB_GAP;
    }

    /** Thumb height, the share of the rows that is visible, never below the grabbable minimum. */
    private int thumbHeight() {
        int rows = (designs.size() + columns - 1) / columns;
        return Math.min(trackHeight(), Math.max(SCROLLBAR_MIN_THUMB, trackHeight() * visibleRows / Math.max(1, rows)));
    }

    /** Top of the thumb for the current scroll. */
    private int thumbTop() {
        int max = maxScroll();
        return trackTop() + (max == 0 ? 0 : (trackHeight() - thumbHeight()) * scrollRow / max);
    }

    /** The wheel scrolls the grid by one row. */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        scrollRow = Mth.clamp(scrollRow - (int) Math.signum(scrollY), 0, maxScroll());
        return true;
    }

    /** Rows hidden below the panel, the most the grid can scroll. */
    private int maxScroll() {
        int rows = (designs.size() + columns - 1) / columns;
        return Math.max(0, rows - visibleRows);
    }

    /** Top-left corner of the thumbnail of a design, or null when it is scrolled out of view. */
    @Nullable
    private int[] cellOf(int index) {
        int row = index / columns - scrollRow;
        if (row < 0 || row >= visibleRows) {
            return null;
        }
        int column = index % columns;
        int gridWidth = columns * THUMB_WIDTH + (columns - 1) * THUMB_GAP;
        int x = gridLeft + (gridAreaWidth - gridWidth) / 2 + column * (THUMB_WIDTH + THUMB_GAP);
        int y = top + TITLE_HEIGHT + MARGIN + row * (THUMB_HEIGHT + THUMB_GAP);
        return new int[]{x, y};
    }

    /** The design under the mouse, or null. */
    @Nullable
    private ResourceLocation designAt(double mouseX, double mouseY) {
        for (int i = 0; i < designs.size(); i++) {
            int[] cell = cellOf(i);
            if (cell != null && mouseX >= cell[0] && mouseX < cell[0] + THUMB_WIDTH
                    && mouseY >= cell[1] && mouseY < cell[1] + THUMB_HEIGHT) {
                return designs.get(i);
            }
        }
        return null;
    }

    /**
     * Display name of a design: the translation
     * {@code cobblemon_safari.banner_design.<namespace>.<name>} when a
     * language has one, else the file name with its underscores as spaces,
     * so a resource pack's designs read fine without translations.
     */
    private static Component nameOf(ResourceLocation design) {
        String name = design.getPath().substring(BannerDesigns.FOLDER.length());
        String fallback = name.replace('_', ' ');
        if (!fallback.isEmpty()) {
            fallback = fallback.substring(0, 1).toUpperCase(Locale.ROOT) + fallback.substring(1);
        }
        return Component.translatableWithFallback(
                CobblemonSafari.MOD_ID + ".banner_design." + design.getNamespace() + "." + name.replace('/', '.'),
                fallback);
    }
}
