package dev.darcosse.common.cobblemonsafari.client.board;

import dev.darcosse.common.cobblemonsafari.points.SafariPointsIcon;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import dev.darcosse.common.cobblemonsafari.block.SafariBoardBlock;
import dev.darcosse.common.cobblemonsafari.block.SafariBoardBlockEntity;
import dev.darcosse.common.cobblemonsafari.block.ScreenLayout;
import dev.darcosse.common.cobblemonsafari.bounty.Bounty;
import dev.darcosse.common.cobblemonsafari.client.BountyClientState;
import dev.darcosse.common.cobblemonsafari.client.FeaturedClientState;
import dev.darcosse.common.cobblemonsafari.client.SafariClientState;
import dev.darcosse.common.cobblemonsafari.config.BoardPalette;
import dev.darcosse.common.cobblemonsafari.config.SafariClientConfig;
import dev.darcosse.common.cobblemonsafari.config.SafariClientConfigManager;
import dev.darcosse.common.cobblemonsafari.network.SafariFeaturedPayload;
import dev.darcosse.common.cobblemonsafari.spawn.Rarity;
import dev.darcosse.common.cobblemonsafari.util.Ticks;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/**
 * Draws the Safari Board screen: a station-style display of the special
 * Pokémon of the day with their animated models
 * and a progress bar for the time that matters to the viewer (their own
 * hunt if they have a session, otherwise the time until the next rotation).
 *
 * <p>Only the bottom-left block of a screen draws; it draws the whole
 * rectangle. Everything is laid out on a virtual canvas whose shorter side is
 * {@link #CANVAS_SHORT_SIDE} pixels, so a bigger wall shows the same layout,
 * bigger.
 *
 * <p>Cost per visible screen per frame: a few dozen quads and strings, plus
 * one Cobblemon model per special on the current page. Boards that are not
 * anchors return after one cached lookup.
 *
 * CLIENT ONLY.
 */
public class SafariBoardRenderer implements BlockEntityRenderer<SafariBoardBlockEntity> {

    // ---- Geometry -------------------------------------------------------

    /**
     * Canvas pixels along the shorter side of the screen. All sizes below are
     * in these pixels. Raising it makes everything smaller relative to the
     * screen (more room, smaller text); lowering it the opposite.
     */
    private static final float CANVAS_SHORT_SIDE = 120.0F;

    /** Depth of the panel's front face from the block centre, in blocks: see {@link SafariBoardBlock#PANEL_THICKNESS_PIXELS}. */
    private static final float PANEL_FRONT_Z = -0.5F + SafariBoardBlock.PANEL_THICKNESS_PIXELS / 16.0F;

    /**
     * Gap between the panel and the picture, in blocks. Too small would
     * z-fight with the block face, too large floats visibly. 0.008 (an
     * eighth of a texture pixel) is a comfortable margin; the border flicker
     * once blamed on this gap came from two layers of the picture itself,
     * see the depth layers below.
     */
    private static final float SURFACE_GAP = 0.008F;

    /** Draw distance in blocks; vanilla's default (64) cuts big station walls too early. */
    private static final int VIEW_DISTANCE = 96;

    /**
     * How far the picture spreads over a frame on each framed side, in blocks.
     * The frame has 6 px of planks between screen and beam (beam from 6 to
     * 10 px in {@code SafariBoardFrameBlock}); spreading 4 px leaves a 2 px
     * band of wood around the picture. 6 would hide the planks entirely, more
     * would draw over the beam.
     */
    private static final float FRAME_OVERLAP = 4.0F / 16.0F;

    /** Blocks added around a screen's bounds for models popping out of it. */
    private static final double BOUNDS_MARGIN = 1.0D;

    // ---- Layout (canvas pixels) ------------------------------------------

    private static final float BEZEL = 1.5F;
    private static final float PADDING = 2.5F;
    private static final float GAP = 2.0F;
    private static final float HEADER_HEIGHT = 13.0F;
    private static final float HEADER_MARGIN = 3.0F;
    private static final float HEADER_SPACING = 4.0F;
    private static final float LIVE_DOT_SIZE = 4.0F;
    private static final float FOOTER_HEIGHT = 20.0F;
    private static final float BAR_HEIGHT = 6.0F;
    private static final float LINE_HEIGHT = 9.0F;
    private static final float RARITY_STRIP = 2.0F;

    /** Smallest cell still readable; below it, specials are split over pages. */
    private static final float MIN_CELL_WIDTH = 38.0F;
    private static final float MIN_CELL_HEIGHT = 40.0F;
    /** Preferred cell width over height; the grid picker favours shapes close to it. */
    private static final float CELL_ASPECT = 0.85F;
    /** Cell width at which cell text is drawn at full size; narrower cells shrink their text. */
    private static final float CELL_TEXT_REFERENCE_WIDTH = 64.0F;
    private static final float MIN_CELL_TEXT_SCALE = 0.5F;
    /**
     * Size of the rarity and habitat lines relative to the name. Smaller
     * fits longer biome names; below ~0.6 the font turns to mush on small screens.
     */
    private static final float SUBTEXT_SCALE = 0.72F;
    /**
     * Share of the cell height kept for the model when deciding whether the
     * habitat line fits. Lower favours text, higher favours the model.
     */
    private static final float MIN_MODEL_SHARE = 0.45F;
    /**
     * Share of the model area the model's height fills. Cobblemon's profile
     * sizes are only roughly uniform, so this leaves room: at 0.85 many
     * models overflowed their cell.
     */
    private static final float MODEL_FILL = 0.62F;
    /**
     * Largest model height as a share of the cell width. A model turning on
     * itself is as wide as it is long, so wide cells must not let a long
     * model (a snake, a whale) grow past the sides.
     */
    private static final float MODEL_WIDTH_FILL = 0.55F;
    /** How far models stand out of the screen, relative to their height. 0 keeps them flat on the glass. */
    private static final float MODEL_POP_OUT = 0.35F;

    // ---- Depth layers (canvas pixels toward the viewer) --------------------

    // Every pair of fills that can overlap must sit on different layers: two
    // overlapping quads at the same depth z-fight, flickering as the camera
    // turns. The rarity strip and the glowing frame of special cells shared
    // Z_DETAIL, which made the top border of those cells flicker. Layers are
    // at least 0.1 canvas pixel apart (about 0.0017 block on a 3x2 screen),
    // far above depth buffer precision at reading distance.

    private static final float Z_BACKGROUND = 0.0F;
    private static final float Z_PANEL = 0.25F;
    private static final float Z_DETAIL = 0.5F;
    /** The pulsing frame of special cells, over the rarity strip it crosses. */
    private static final float Z_GLOW = 0.6F;
    /** Detail drawn over another detail (a ticked box, a bar's filled part); below the scanline. */
    private static final float Z_DETAIL_TOP = 0.7F;
    /**
     * Below the text on purpose: translucent quads still write depth, and
     * depending on batch order the text could be hidden behind a band drawn
     * in front of it.
     */
    private static final float Z_SCANLINE = 0.8F;
    private static final float Z_TEXT = 0.9F;
    private static final float Z_OVERLAY = 1.2F;

    // ---- Animation ----------------------------------------------------------

    /** One full turn of the models. Shorter feels hectic on a board meant to be read. */
    private static final long SPIN_PERIOD_MS = 12_000L;
    /** Angle between neighbouring models, so they do not turn in lockstep. */
    private static final float SPIN_PHASE_DEGREES = 47.0F;
    /** Time each page stays up when the specials do not fit on one. */
    private static final long PAGE_DURATION_MS = 7_000L;
    /**
     * How long the specials and the missions each stay on screen before the
     * board switches to the other, in ms. Two pages of specials fit in one
     * turn; a longer value favours the specials.
     */
    private static final long VIEW_DURATION_MS = 14_000L;
    /** Mission rows: tallest row, and the row height at which text is drawn at full size. */
    private static final float MAX_BOUNTY_ROW_HEIGHT = 26.0F;
    private static final float BOUNTY_ROW_REFERENCE = 24.0F;
    private static final float BOUNTY_BOX_SIZE = 8.0F;
    private static final float BOUNTY_BAR_HEIGHT = 2.0F;
    private static final float ROW_PADDING = 2.0F;
    /**
     * Scroll speed of cell lines too long for their cell, in canvas pixels per
     * second, and the pause at each end before turning back. Faster is harder
     * to read; a shorter pause hides the start and end of the text.
     */
    private static final float MARQUEE_SPEED = 12.0F;
    private static final long MARQUEE_PAUSE_MS = 1_500L;
    /** Rounding slack when deciding whether a glyph lies inside a scrolling line. */
    private static final float CLIP_TOLERANCE = 0.01F;
    private static final long SCANLINE_PERIOD_MS = 3_500L;
    private static final float SCANLINE_HEIGHT = 6.0F;
    private static final long LIVE_BLINK_PERIOD_MS = 1_000L;
    private static final long SHIMMER_PERIOD_MS = 2_500L;
    private static final float SHIMMER_WIDTH = 8.0F;
    private static final long GLOW_PERIOD_MS = 1_600L;
    /** White flash when the list changes, like a screen switching channel. */
    private static final long FLASH_MS = 300L;
    /** Split-flap reveal: delay between letters, delay between cells, extra time before a letter settles. */
    private static final long FLAP_CHAR_DELAY_MS = 40L;
    private static final long FLAP_CELL_STAGGER_MS = 150L;
    private static final long FLAP_SETTLE_MS = 250L;
    /** How often a flapping letter changes; too fast reads as noise. */
    private static final long FLAP_FRAME_MS = 60L;
    private static final String FLAP_GLYPHS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final long CONNECTING_DOT_MS = 400L;

    // ---- Colours ------------------------------------------------------------

    /** Alpha range of the pulsing outline of the rarest cells; the colour itself is the rarity's. */
    private static final int GLOW_MIN_ALPHA = 0x30;
    private static final int GLOW_ALPHA_RANGE = 0x90;
    private static final int ALPHA_SHIFT = 24;
    private static final int RGB_MASK = 0xFFFFFF;

    private final Font font;
    /**
     * Colours of the frame being drawn, read from the client config once per
     * screen. Render thread only, like everything in a block entity renderer.
     */
    private BoardPalette pal = SafariClientConfigManager.palette();

    /** Created by the loader's renderer registry. */
    public SafariBoardRenderer(BlockEntityRendererProvider.Context context) {
        this.font = context.getFont();
    }

    /**
     * Rendered even when the anchor block itself is off-screen: on a big wall
     * the bottom-left corner can be out of view while the rest is in plain
     * sight. Vanilla then renders it without a frustum check; NeoForge still
     * culls with {@link #screenBounds}.
     */
    @Override
    public boolean shouldRenderOffScreen(SafariBoardBlockEntity blockEntity) {
        return true;
    }

    /** See {@link #VIEW_DISTANCE}. */
    @Override
    public int getViewDistance() {
        return VIEW_DISTANCE;
    }

    /**
     * World box covering the whole screen drawn by this block, models
     * included; just the block for boards that do not draw. Used by the
     * NeoForge subclass for frustum culling.
     */
    protected AABB screenBounds(SafariBoardBlockEntity blockEntity) {
        BlockPos pos = blockEntity.getBlockPos();
        ScreenLayout layout = blockEntity.layout();
        BlockState state = blockEntity.getBlockState();
        if (!layout.anchor() || !state.hasProperty(HorizontalDirectionalBlock.FACING)) {
            return new AABB(pos);
        }
        Direction right = ScreenLayout.rightOf(state.getValue(HorizontalDirectionalBlock.FACING));
        BlockPos far = pos.relative(right, layout.width() - 1).above(layout.height() - 1);
        return AABB.encapsulatingFullBlocks(pos, far).inflate(BOUNDS_MARGIN);
    }

    /** Draws the screen if this block is the anchor of one. */
    @Override
    public void render(SafariBoardBlockEntity blockEntity, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffers, int packedLight, int packedOverlay) {
        Level level = blockEntity.getLevel();
        BlockState state = blockEntity.getBlockState();
        if (level == null || !state.hasProperty(HorizontalDirectionalBlock.FACING)) {
            return;
        }
        ScreenLayout layout = blockEntity.layout();
        if (!layout.anchor()) {
            return;
        }
        Direction facing = state.getValue(HorizontalDirectionalBlock.FACING);
        pal = SafariClientConfigManager.palette();
        // Framed sides: the picture covers the frame's planks up to its beam.
        float growLeft = layout.framedLeft() ? FRAME_OVERLAP : 0.0F;
        float growRight = layout.framedRight() ? FRAME_OVERLAP : 0.0F;
        float growTop = layout.framedTop() ? FRAME_OVERLAP : 0.0F;
        float growBottom = layout.framedBottom() ? FRAME_OVERLAP : 0.0F;
        float widthBlocks = layout.width() + growLeft + growRight;
        float heightBlocks = layout.height() + growTop + growBottom;
        float pixelsPerBlock = CANVAS_SHORT_SIDE / Math.min(widthBlocks, heightBlocks);
        float unit = 1.0F / pixelsPerBlock;

        poseStack.pushPose();
        // Block centre, then turn so +X is the reader's right, +Y up and +Z out
        // of the screen (toYRot: south 0, west 90, north 180, east 270).
        poseStack.translate(0.5F, 0.5F, 0.5F);
        poseStack.mulPose(Axis.YP.rotationDegrees(-facing.toYRot()));
        // Top-left corner of the whole screen, just in front of the panel.
        poseStack.translate(-0.5F - growLeft, -0.5F + layout.height() + growTop, PANEL_FRONT_Z + SURFACE_GAP);
        poseStack.scale(unit, -unit, unit);

        BoardPainter painter = new BoardPainter(poseStack, buffers, font);
        drawScreen(painter, widthBlocks * pixelsPerBlock, heightBlocks * pixelsPerBlock,
                System.currentTimeMillis(), level.getGameTime());
        poseStack.popPose();
    }

    /** Lays out and draws every part of the screen. */
    private void drawScreen(BoardPainter p, float width, float height, long now, long gameTime) {
        p.fill(0.0F, 0.0F, width, height, Z_BACKGROUND, pal.bezel());
        float left = BEZEL;
        float top = BEZEL;
        float right = width - BEZEL;
        float bottom = height - BEZEL;
        p.fill(left, top, right, bottom, Z_BACKGROUND + Z_PANEL / 2.0F, pal.background());

        float x0 = left + PADDING;
        float x1 = right - PADDING;
        float headerTop = top + PADDING;
        float bodyTop = headerTop + HEADER_HEIGHT + GAP;
        float footerTop = bottom - PADDING - FOOTER_HEIGHT;
        float bodyBottom = footerTop - GAP;

        List<SafariFeaturedPayload.Entry> entries = FeaturedClientState.entries();

        // Specials and missions take turns; without missions the specials stay.
        boolean missions = BountyClientState.hasBounties() && (now / VIEW_DURATION_MS) % 2 == 1;
        Page page;
        if (missions) {
            drawBounties(p, x0, bodyTop, x1, bodyBottom, now);
            page = Page.SINGLE;
        } else {
            page = drawBody(p, x0, bodyTop, x1, bodyBottom, entries, now);
        }
        drawHeader(p, x0, headerTop, x1, page, now, missions);
        drawFooter(p, x0, footerTop, x1, now, gameTime);

        drawScanline(p, left, top, right, bottom, now);
        long sinceChange = now - FeaturedClientState.changedAtMillis();
        if (FeaturedClientState.received() && sinceChange >= 0 && sinceChange < FLASH_MS) {
            int peak = pal.flash() >>> ALPHA_SHIFT;
            int alpha = (int) (peak * (1.0F - sinceChange / (float) FLASH_MS));
            p.fill(left, top, right, bottom, Z_OVERLAY, (alpha << ALPHA_SHIFT) | (pal.flash() & RGB_MASK));
        }
    }

    // ---- Header ------------------------------------------------------------

    /**
     * Title band with the page counter and a blinking LIVE light. On narrow
     * screens the title shortens first, then the LIVE label goes and only its
     * light stays.
     */
    private void drawHeader(BoardPainter p, float x0, float y, float x1, Page page, long now, boolean missions) {
        p.fill(x0, y, x1, y + HEADER_HEIGHT, Z_PANEL, pal.header());
        float textY = y + (HEADER_HEIGHT - LINE_HEIGHT) / 2.0F + 1.0F;
        float left = x0 + HEADER_MARGIN;
        float right = x1 - HEADER_MARGIN;

        String live = tr("screen.live").toUpperCase(Locale.ROOT);
        String counter = page.count() > 1 ? (page.index() + 1) + "/" + page.count() : "";
        float counterBlock = counter.isEmpty() ? 0.0F : p.width(counter) + HEADER_SPACING;
        float dotBlock = LIVE_DOT_SIZE + HEADER_SPACING;
        String titleKey = missions ? "screen.bounties_title" : "screen.title";
        String title = tr(titleKey).toUpperCase(Locale.ROOT);
        boolean liveLabel = true;
        float rightBlock = p.width(live) + dotBlock + counterBlock;
        if (p.width(title) > right - left - rightBlock) {
            title = tr(titleKey + "_short").toUpperCase(Locale.ROOT);
        }
        if (p.width(title) > right - left - rightBlock) {
            liveLabel = false;
        }

        // Laid out from the right edge: label, light, page counter, then the title in what is left.
        float cursor = right;
        if (liveLabel) {
            cursor -= p.width(live);
            p.text(live, cursor, textY, Z_TEXT, 1.0F, pal.muted());
            cursor -= HEADER_SPACING / 2.0F;
        }
        boolean lit = (now / (LIVE_BLINK_PERIOD_MS / 2)) % 2 == 0;
        float dotY = y + (HEADER_HEIGHT - LIVE_DOT_SIZE) / 2.0F;
        p.fill(cursor - LIVE_DOT_SIZE, dotY, cursor, dotY + LIVE_DOT_SIZE, Z_DETAIL,
                lit ? pal.liveOn() : pal.liveOff());
        cursor -= LIVE_DOT_SIZE + HEADER_SPACING;
        if (!counter.isEmpty()) {
            cursor -= p.width(counter);
            p.text(counter, cursor, textY, Z_TEXT, 1.0F, pal.muted());
            cursor -= HEADER_SPACING;
        }
        p.text(p.fit(title, cursor - left), left, textY, Z_TEXT, 1.0F, pal.title());
    }

    // ---- Body ---------------------------------------------------------------

    /** Which page of specials is on screen; count 1 when everything fits. */
    private record Page(int index, int count) {
        static final Page SINGLE = new Page(0, 1);
    }

    /** Rows and columns of the grid, and the resulting cell size. */
    private record Grid(int columns, int rows, float cellWidth, float cellHeight) {
    }

    /**
     * Draws the specials, or a status message when there are none.
     *
     * @return the page shown, for the header's page counter
     */
    private Page drawBody(BoardPainter p, float x0, float y0, float x1, float y1,
                          List<SafariFeaturedPayload.Entry> entries, long now) {
        float centerY = (y0 + y1) / 2.0F - LINE_HEIGHT / 2.0F;
        if (!FeaturedClientState.received()) {
            int dots = (int) ((now / CONNECTING_DOT_MS) % 4);
            String message = tr("screen.connecting") + ".".repeat(dots);
            drawCentered(p, message, x0, x1, centerY, pal.muted());
            return Page.SINGLE;
        }
        if (!FeaturedClientState.enabled()) {
            drawCentered(p, tr("screen.featured_off"), x0, x1, centerY, pal.muted());
            return Page.SINGLE;
        }
        if (entries.isEmpty()) {
            drawCentered(p, tr("board.empty"), x0, x1, centerY, pal.muted());
            return Page.SINGLE;
        }

        float bodyWidth = x1 - x0;
        float bodyHeight = y1 - y0;
        int perPage = entries.size();
        Grid grid = bestGrid(perPage, bodyWidth, bodyHeight);
        while (perPage > 1 && (grid.cellWidth() < MIN_CELL_WIDTH || grid.cellHeight() < MIN_CELL_HEIGHT)) {
            perPage--;
            grid = bestGrid(perPage, bodyWidth, bodyHeight);
        }
        int pageCount = (entries.size() + perPage - 1) / perPage;
        int pageIndex = (int) ((now / PAGE_DURATION_MS) % pageCount);
        // A page flip replays the split-flap reveal, like a real board refreshing.
        long revealStart = pageCount > 1
                ? Math.max(FeaturedClientState.changedAtMillis(), now - now % PAGE_DURATION_MS)
                : FeaturedClientState.changedAtMillis();

        int first = pageIndex * perPage;
        int last = Math.min(entries.size(), first + perPage);
        for (int i = first; i < last; i++) {
            int slot = i - first;
            int column = slot % grid.columns();
            int row = slot / grid.columns();
            float cx = x0 + column * (grid.cellWidth() + GAP);
            float cy = y0 + row * (grid.cellHeight() + GAP);
            drawCell(p, cx, cy, grid.cellWidth(), grid.cellHeight(), entries.get(i), slot, revealStart, now);
        }
        return new Page(pageIndex, pageCount);
    }

    /**
     * Today's missions, one row each: a box ticked when done, what to catch,
     * the reward and the local player's progress with a thin bar. Below them,
     * when there is room, the bonus for finishing them all.
     */
    private void drawBounties(BoardPainter p, float x0, float y0, float x1, float y1, long now) {
        List<Bounty> bounties = BountyClientState.bounties();
        int count = bounties.size();
        float rowHeight = Math.min(MAX_BOUNTY_ROW_HEIGHT, (y1 - y0 - (count - 1) * GAP) / count);
        float scale = Math.max(MIN_CELL_TEXT_SCALE, Math.min(1.0F, rowHeight / BOUNTY_ROW_REFERENCE));
        float subScale = scale * SUBTEXT_SCALE;
        boolean allDone = true;
        for (int i = 0; i < count; i++) {
            Bounty bounty = bounties.get(i);
            int progress = BountyClientState.progress(i);
            boolean done = progress >= bounty.count();
            allDone &= done;
            float top = y0 + i * (rowHeight + GAP);
            float bottom = top + rowHeight;
            p.fill(x0, top, x1, bottom, Z_PANEL, pal.cell());

            float box = Math.min(rowHeight - 2.0F * ROW_PADDING, BOUNTY_BOX_SIZE * scale);
            float boxLeft = x0 + ROW_PADDING;
            float boxTop = top + (rowHeight - box) / 2.0F;
            p.fill(boxLeft, boxTop, boxLeft + box, boxTop + box, Z_DETAIL, pal.muted());
            p.fill(boxLeft + 1.0F, boxTop + 1.0F, boxLeft + box - 1.0F, boxTop + box - 1.0F, Z_DETAIL_TOP,
                    done ? pal.barSessionOk() : pal.cell());

            float textLeft = boxLeft + box + ROW_PADDING * 2.0F;
            float textRight = x1 - ROW_PADDING;
            drawMarquee(p, bounty.describe().getString(), textLeft, textRight, top + ROW_PADDING, scale,
                    done ? pal.barSessionOk() : pal.text(), now);

            float subTop = top + ROW_PADDING + LINE_HEIGHT * scale;
            Component reward = Component.translatable(CobblemonSafari.MOD_ID + ".board.bounty_reward",
                    SafariPointsIcon.amount(bounty.reward()));
            p.text(reward, textLeft, subTop, Z_TEXT, subScale, pal.star());
            String counter = progress + "/" + bounty.count();
            p.text(counter, textRight - p.width(counter) * subScale, subTop, Z_TEXT, subScale, pal.muted());

            float barTop = bottom - ROW_PADDING - BOUNTY_BAR_HEIGHT;
            p.fill(textLeft, barTop, textRight, barTop + BOUNTY_BAR_HEIGHT, Z_DETAIL, pal.barBackground());
            float fraction = Math.min(1.0F, progress / (float) bounty.count());
            p.fill(textLeft, barTop, textLeft + (textRight - textLeft) * fraction, barTop + BOUNTY_BAR_HEIGHT,
                    Z_DETAIL_TOP, done ? pal.barSessionOk() : pal.barRotation());
        }
        float footerY = y0 + count * (rowHeight + GAP);
        int bonus = BountyClientState.allDoneBonus();
        if (bonus > 0 && footerY + LINE_HEIGHT <= y1) {
            Component line = allDone
                    ? Component.translatable(CobblemonSafari.MOD_ID + ".board.bounty_all_done")
                    : Component.translatable(CobblemonSafari.MOD_ID + ".board.bounty_bonus", SafariPointsIcon.amount(bonus));
            // Not fitted with an ellipsis like other lines: the icon glyph
            // cannot be cut, and the line is short in every language.
            p.text(line, x0 + (x1 - x0 - p.width(line)) / 2.0F, footerY, Z_TEXT, 1.0F,
                    allDone ? pal.barSessionOk() : pal.muted());
        }
    }

    /**
     * The grid shape that gives the largest cells for {@code count} specials,
     * judging a cell by its width and by its height scaled to the preferred
     * aspect, whichever is the limiting one.
     */
    private static Grid bestGrid(int count, float width, float height) {
        Grid best = null;
        float bestScore = -1.0F;
        for (int columns = 1; columns <= count; columns++) {
            int rows = (count + columns - 1) / columns;
            float cellWidth = (width - (columns - 1) * GAP) / columns;
            float cellHeight = (height - (rows - 1) * GAP) / rows;
            float score = Math.min(cellWidth, cellHeight * CELL_ASPECT);
            if (score > bestScore) {
                bestScore = score;
                best = new Grid(columns, rows, cellWidth, cellHeight);
            }
        }
        return best;
    }

    /** One special: rarity strip, turning model, name, rarity and, when there is room, habitat. */
    private void drawCell(BoardPainter p, float x, float y, float w, float h, SafariFeaturedPayload.Entry entry,
                          int slot, long revealStart, long now) {
        int rarityColor = rarityColor(entry);
        p.fill(x, y, x + w, y + h, Z_PANEL, pal.cell());
        p.fill(x, y, x + w, y + RARITY_STRIP, Z_DETAIL, rarityColor);
        if (entry.special() || entry.rarity() == Rarity.ULTRA_RARE) {
            drawGlow(p, x, y, w, h, rarityColor, now);
        }

        float textScale = Math.max(MIN_CELL_TEXT_SCALE, Math.min(1.0F, w / CELL_TEXT_REFERENCE_WIDTH));
        float subScale = textScale * SUBTEXT_SCALE;
        float line = LINE_HEIGHT * textScale;
        float subLine = LINE_HEIGHT * subScale;
        boolean showHabitat = h - RARITY_STRIP - line - 2.0F * subLine - 4.0F >= h * MIN_MODEL_SHARE;
        float textHeight = line + (showHabitat ? 2.0F : 1.0F) * subLine;
        float textTop = y + h - 2.0F - textHeight;
        float modelTop = y + RARITY_STRIP + 2.0F;
        float modelBottom = textTop - 1.0F;
        float maxTextWidth = (w - 4.0F) / textScale;

        // Model centred in its area (both ways), standing out of the glass.
        // Feet on the bottom of the area would leave the empty room above it,
        // and the model would look sunk into the text.
        float modelHeight = Math.max(0.0F, Math.min((modelBottom - modelTop) * MODEL_FILL, w * MODEL_WIDTH_FILL));
        float feetY = modelBottom - ((modelBottom - modelTop) - modelHeight) / 2.0F;
        boolean drawn = false;
        if (modelHeight > 0.0F) {
            PoseStack pose = p.poseStack();
            pose.pushPose();
            pose.translate(x + w / 2.0F, feetY, Z_TEXT + modelHeight * MODEL_POP_OUT);
            float yaw = (now % SPIN_PERIOD_MS) / (float) SPIN_PERIOD_MS * 360.0F + slot * SPIN_PHASE_DEGREES;
            drawn = BoardPokemonModels.render(entry.species(), new HashSet<>(entry.aspects()), pose,
                    p.buffers(), BoardPainter.LIGHT, modelHeight, yaw);
            pose.popPose();
        }
        if (!drawn) {
            // Unknown species or broken model: a big question mark keeps the cell from looking empty.
            float size = Math.max(1.0F, (modelBottom - modelTop) / LINE_HEIGHT * 0.6F);
            float qx = x + (w - p.width("?") * size) / 2.0F;
            float qy = (modelTop + modelBottom) / 2.0F - LINE_HEIGHT * size / 2.0F;
            p.text("?", qx, qy, Z_TEXT, size, pal.muted());
        }

        // Name, revealed letter by letter like a split-flap display.
        String prefix = entry.special() ? "★ " : "";
        String name = p.fit(BoardPokemonModels.displayName(entry.species()), maxTextWidth - p.width(prefix));
        long elapsed = now - revealStart - slot * FLAP_CELL_STAGGER_MS;
        String shown = flap(name, elapsed, slot);
        // Centred on the final name, not the flapping one: scrambled letters
        // have other widths and the line would wobble during the reveal.
        float nameWidth = (p.width(prefix) + p.width(name)) * textScale;
        float nameX = x + (w - nameWidth) / 2.0F;
        if (!prefix.isEmpty()) {
            p.text(prefix, nameX, textTop, Z_TEXT, textScale, pal.star());
            nameX += p.width(prefix) * textScale;
        }
        p.text(shown, nameX, textTop, Z_TEXT, textScale, pal.text());

        // Rarity and habitat in smaller type; a line still too long scrolls.
        float lineLeft = x + 2.0F;
        float lineRight = x + w - 2.0F;
        String rarity = entry.rarity().displayName().getString();
        drawMarquee(p, rarity, lineLeft, lineRight, textTop + line, subScale, rarityColor, now);
        if (showHabitat) {
            drawMarquee(p, habitatName(entry.habitat()), lineLeft, lineRight, textTop + line + subLine, subScale,
                    pal.muted(), now);
        }
    }

    /**
     * One line of text kept inside {@code [x0, x1]}: centred when it fits,
     * otherwise scrolling back and forth with a pause at each end, so the
     * whole text can be read without shrinking it further.
     *
     * <p>Glyphs are clipped one by one at the edges because world rendering
     * has no scissor test: a glyph is drawn only if it lies entirely inside.
     */
    private void drawMarquee(BoardPainter p, String text, float x0, float x1, float y, float scale, int color,
                             long now) {
        float room = x1 - x0;
        float width = p.width(text) * scale;
        if (width <= room) {
            p.text(text, x0 + (room - width) / 2.0F, y, Z_TEXT, scale, color);
            return;
        }
        float overflow = width - room;
        long travel = (long) (overflow / MARQUEE_SPEED * 1000.0F);
        long cycle = 2L * (travel + MARQUEE_PAUSE_MS);
        long t = Math.floorMod(now, cycle);
        float offset;
        if (t < MARQUEE_PAUSE_MS) {
            offset = 0.0F;
        } else if (t < MARQUEE_PAUSE_MS + travel) {
            offset = overflow * (t - MARQUEE_PAUSE_MS) / (float) travel;
        } else if (t < 2L * MARQUEE_PAUSE_MS + travel) {
            offset = overflow;
        } else {
            offset = overflow * (1.0F - (t - 2L * MARQUEE_PAUSE_MS - travel) / (float) travel);
        }
        // The visible glyphs form one contiguous run: collect it, then draw it once.
        StringBuilder visible = new StringBuilder();
        float runStart = -1.0F;
        float cursor = x0 - offset;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            float glyphWidth = p.width(new String(Character.toChars(codePoint))) * scale;
            if (cursor >= x0 - CLIP_TOLERANCE && cursor + glyphWidth <= x1 + CLIP_TOLERANCE) {
                if (runStart < 0.0F) {
                    runStart = cursor;
                }
                visible.appendCodePoint(codePoint);
            }
            cursor += glyphWidth;
            i += Character.charCount(codePoint);
        }
        if (runStart >= 0.0F) {
            p.text(visible.toString(), runStart, y, Z_TEXT, scale, color);
        }
    }

    /** Pulsing outline around the rarest specials. */
    private void drawGlow(BoardPainter p, float x, float y, float w, float h, int color, long now) {
        double phase = (now % GLOW_PERIOD_MS) / (double) GLOW_PERIOD_MS * Math.PI * 2.0;
        int alpha = GLOW_MIN_ALPHA + (int) (GLOW_ALPHA_RANGE * (0.5 + 0.5 * Math.sin(phase)));
        int argb = (alpha << ALPHA_SHIFT) | (color & RGB_MASK);
        float t = 1.0F;
        p.fill(x, y, x + w, y + t, Z_GLOW, argb);
        p.fill(x, y + h - t, x + w, y + h, Z_GLOW, argb);
        p.fill(x, y + t, x + t, y + h - t, Z_GLOW, argb);
        p.fill(x + w - t, y + t, x + w, y + h - t, Z_GLOW, argb);
    }

    // ---- Footer -------------------------------------------------------------

    /**
     * Label, time and progress bar. A viewer with a session sees their own
     * hunt draining; anyone else sees the countdown to the next rotation.
     */
    private void drawFooter(BoardPainter p, float x0, float y, float x1, long now, long gameTime) {
        String labelKey;
        String time;
        float fraction;
        int color;
        if (SafariClientState.isActive()) {
            int remaining = SafariClientState.remainingTicks(gameTime);
            // max(): a raise of the remaining time reaches the client in the same
            // snapshot as the new total, but guard against a ratio above 1 anyway.
            int granted = Math.max(1, Math.max(SafariClientState.grantedTicks(), remaining));
            fraction = remaining / (float) granted;
            labelKey = SafariClientState.isRunning() ? "screen.session_left" : "screen.session_paused";
            time = Ticks.format(remaining);
            color = sessionColor(remaining, SafariClientConfigManager.get());
        } else if (FeaturedClientState.received() && FeaturedClientState.enabled()) {
            long left = FeaturedClientState.millisUntilRotation(now);
            long length = FeaturedClientState.rotationLengthMillis();
            fraction = length > 0 ? left / (float) length : 0.0F;
            labelKey = "screen.next_rotation";
            // Rounded up, like the session timer: "0:00" only once it is really over.
            time = Ticks.format(Ticks.fromSeconds((left + 999L) / 1000L));
            color = pal.barRotation();
        } else {
            return;
        }

        float timeWidth = p.width(time);
        p.text(time, x1 - timeWidth, y + 1.0F, Z_TEXT, 1.0F, color);
        // Each label has a short form for narrow screens; it is cut only if even that does not fit.
        float labelRoom = x1 - x0 - timeWidth - HEADER_SPACING;
        String label = tr(labelKey);
        if (p.width(label) > labelRoom) {
            label = tr(labelKey + "_short");
        }
        p.text(p.fit(label, labelRoom), x0, y + 1.0F, Z_TEXT, 1.0F, pal.muted());

        float barTop = y + FOOTER_HEIGHT - BAR_HEIGHT;
        float barBottom = barTop + BAR_HEIGHT;
        p.fill(x0, barTop, x1, barBottom, Z_PANEL, pal.barBackground());
        float clamped = Math.max(0.0F, Math.min(1.0F, fraction));
        float fillRight = x0 + (x1 - x0) * clamped;
        p.fill(x0, barTop, fillRight, barBottom, Z_DETAIL, color);
        // A light sweeping along the filled part, so the bar reads as live even
        // when an hours-long countdown barely moves.
        float span = fillRight - x0;
        if (span > SHIMMER_WIDTH) {
            float progress = (now % SHIMMER_PERIOD_MS) / (float) SHIMMER_PERIOD_MS;
            float shimmerX = x0 + (span + SHIMMER_WIDTH) * progress - SHIMMER_WIDTH;
            p.fill(Math.max(x0, shimmerX), barTop, Math.min(fillRight, shimmerX + SHIMMER_WIDTH), barBottom,
                    Z_TEXT, pal.shimmer());
        }
    }

    /** Bar colour for the hunt, with the same thresholds as the HUD timer. */
    private int sessionColor(int remainingTicks, SafariClientConfig config) {
        int seconds = Ticks.toSecondsCeil(remainingTicks);
        if (seconds <= config.criticalTimeSeconds) {
            return pal.barSessionCritical();
        }
        if (seconds <= config.lowTimeSeconds) {
            return pal.barSessionLow();
        }
        return pal.barSessionOk();
    }

    // ---- Effects and helpers ---------------------------------------------

    /** A faint bright band rolling down the screen, the cue that this is a live feed. */
    private void drawScanline(BoardPainter p, float left, float top, float right, float bottom, long now) {
        float progress = (now % SCANLINE_PERIOD_MS) / (float) SCANLINE_PERIOD_MS;
        float y = top + (bottom - top + SCANLINE_HEIGHT) * progress - SCANLINE_HEIGHT;
        p.fill(left, Math.max(top, y), right, Math.min(bottom, y + SCANLINE_HEIGHT), Z_SCANLINE, pal.scanline());
    }

    /**
     * The text as it looks {@code elapsed} ms into its split-flap reveal:
     * settled letters from the left, random letters after them.
     */
    private static String flap(String target, long elapsed, int seed) {
        if (elapsed >= target.length() * FLAP_CHAR_DELAY_MS + FLAP_SETTLE_MS) {
            return target;
        }
        long frame = Math.max(0L, elapsed) / FLAP_FRAME_MS;
        StringBuilder builder = new StringBuilder(target.length());
        for (int i = 0; i < target.length(); i++) {
            char c = target.charAt(i);
            if (Character.isWhitespace(c) || elapsed >= i * FLAP_CHAR_DELAY_MS + FLAP_SETTLE_MS) {
                builder.append(c);
            } else {
                // Cheap deterministic scramble: stable within a frame, so letters
                // flip at the flap rate rather than every rendered frame.
                long hash = (frame * 31L + i) * 0x9E3779B97F4A7C15L + seed;
                builder.append(FLAP_GLYPHS.charAt((int) Math.floorMod(hash >>> 17, (long) FLAP_GLYPHS.length())));
            }
        }
        return builder.toString();
    }

    /** Text centred between two x positions at scale 1. */
    private void drawCentered(BoardPainter p, String text, float x0, float x1, float y, int color) {
        String fitted = p.fit(text, x1 - x0);
        p.text(fitted, x0 + (x1 - x0 - p.width(fitted)) / 2.0F, y, Z_TEXT, 1.0F, color);
    }

    /** Rarity colour of an entry, from the client config. */
    private int rarityColor(SafariFeaturedPayload.Entry entry) {
        return pal.rarity(entry.rarity());
    }

    /** Biome name in the player's language. */
    private static String habitatName(ResourceLocation habitat) {
        if (habitat == null) {
            return tr("board.habitat_unknown");
        }
        return Component.translatable("biome." + habitat.getNamespace() + "." + habitat.getPath()).getString();
    }

    /** Resolves a translation key of the mod in the player's language. */
    private static String tr(String path, Object... args) {
        return Component.translatable(CobblemonSafari.MOD_ID + "." + path, args).getString();
    }
}
