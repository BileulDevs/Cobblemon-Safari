package dev.darcosse.common.cobblemonsafari.client.board;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import dev.darcosse.common.cobblemonsafari.client.SafariLandscapes;
import dev.darcosse.common.cobblemonsafari.block.WallScreenContent;
import dev.darcosse.common.cobblemonsafari.bounty.Bounty;
import dev.darcosse.common.cobblemonsafari.client.BountyClientState;
import dev.darcosse.common.cobblemonsafari.client.StatsClientState;
import dev.darcosse.common.cobblemonsafari.client.FeaturedClientState;
import dev.darcosse.common.cobblemonsafari.client.SafariClientState;
import dev.darcosse.common.cobblemonsafari.config.BoardPalette;
import dev.darcosse.common.cobblemonsafari.config.SafariClientConfig;
import dev.darcosse.common.cobblemonsafari.config.WallScreenFooter;
import dev.darcosse.common.cobblemonsafari.config.SafariClientConfigManager;
import dev.darcosse.common.cobblemonsafari.network.SafariFeaturedPayload;
import dev.darcosse.common.cobblemonsafari.network.SafariStatsPayload;
import dev.darcosse.common.cobblemonsafari.points.SafariPointsIcon;
import dev.darcosse.common.cobblemonsafari.spawn.Rarity;
import dev.darcosse.common.cobblemonsafari.util.Ticks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/**
 * Paints the picture of a wall screen: a station-style display of the
 * special Pokémon of the day with their animated models (FEATURED page), the
 * hunter's numbers (STATS) or the missions (BOUNTIES), with a header, a
 * footer and a progress bar for the time that matters to the viewer: their
 * own hunt if they have a session, or the time until the next rotation,
 * as each kind of screen is set in the client config (board.*Footer).
 *
 * <p>Not a block entity renderer itself: {@code SafariWallScreenRenderer}
 * places and tilts the pose, then calls {@link #paintScreen}. Everything is
 * laid out on a virtual canvas whose shorter side is
 * {@link #CANVAS_SHORT_SIDE} pixels, so a bigger screen shows the same
 * layout, bigger.
 *
 * <p>Cost per painted screen per frame: a few dozen quads and strings, plus
 * one Cobblemon model per special on the current page.
 *
 * CLIENT ONLY, render thread.
 */
public class SafariScreenPainter {

    // ---- Geometry -------------------------------------------------------

    /**
     * Canvas pixels along the shorter side of the screen. All sizes below are
     * in these pixels. Raising it makes everything smaller relative to the
     * screen (more room, smaller text); lowering it the opposite.
     */
    private static final float CANVAS_SHORT_SIDE = 120.0F;

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

    /**
     * Stats page: smallest readable tile, preferred tile shape (wider than
     * tall: a label over a number), and the largest size of the number
     * relative to the font. Below the minimum, tiles are split over pages.
     */
    private static final float MIN_TILE_WIDTH = 40.0F;
    private static final float MIN_TILE_HEIGHT = 22.0F;
    private static final float TILE_ASPECT = 2.0F;
    private static final float TILE_VALUE_MAX_SCALE = 2.0F;
    /** Space between a tile's label and its number, and around them, in canvas pixels. */
    private static final float TILE_INNER_GAP = 2.0F;

    /**
     * Bounties page: a row holds the mission on one line and its reward and
     * progress bar on a second one. Below the minimum height the two lines
     * overlap, so rows are split over pages; above the maximum, rows stop
     * growing and leave the rest of the body empty instead of turning into
     * slabs with tiny text.
     */
    private static final float MIN_ROW_HEIGHT = 22.0F;
    private static final float MAX_ROW_HEIGHT = 30.0F;
    private static final float ROW_BAR_HEIGHT = 3.0F;
    /** Width of the coloured strip on the left of each mission row. */
    private static final float ROW_STRIP = 2.0F;
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
     * sizes are only roughly uniform, so this leaves room: around 0.85 many
     * models overflow their cell.
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
    // turns (the rarity strip and the glowing frame of special cells, for
    // instance, cross on the top border of those cells). Layers are at least
    // 0.1 canvas pixel apart (about 0.0017 block on a 3x2 screen),
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

    /** One full turn of the models. Shorter feels hectic on a screen meant to be read. */
    private static final long SPIN_PERIOD_MS = 12_000L;
    /** Angle between neighbouring models, so they do not turn in lockstep. */
    private static final float SPIN_PHASE_DEGREES = 47.0F;
    /**
     * Sprite mode: share of the model area the sprite fills (the sprites have
     * a transparent margin of their own), space kept on each side of the
     * cell, and a slow bob, staggered between cells. More than ~1 px of bob
     * reads as jitter on a small screen.
     */
    private static final float SPRITE_FILL = 1.0F;
    private static final float SPRITE_SIDE_MARGIN = 2.0F;
    private static final float SPRITE_BOB_PIXELS = 0.75F;
    private static final long SPRITE_BOB_PERIOD_MS = 2_400L;
    private static final long SPRITE_BOB_STAGGER_MS = 300L;
    /** Sprites are drawn with their own colours: no tint. */
    private static final int SPRITE_TINT = 0xFFFFFFFF;

    /** Time each page stays up when the specials do not fit on one. */
    private static final long PAGE_DURATION_MS = 7_000L;
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
    private static final int ALPHA_MAX = 0xFF;

    /**
     * Vertex colour of the landscape: multiplies the picture, here to about
     * 70%. Brighter makes the scenery livelier and white text harder to read.
     */
    private static final int LANDSCAPE_TINT = 0xFFB4B4B4;
    private static final int RGB_MASK = 0xFFFFFF;

    private final Font font;
    /**
     * Colours of the frame being drawn, read from the client config once per
     * screen. Render thread only, like everything that draws in the world.
     */
    private BoardPalette pal = SafariClientConfigManager.palette();
    /** Whether this frame draws the landscape, and the panels' alpha over it (from the client config). */
    private boolean landscape;
    /** Whether this frame draws sprites instead of models (from the client config). */
    private boolean spriteMode;
    private int panelAlpha = ALPHA_MAX;

    /** Painter drawing its text with the given font (the block entity renderer context's). */
    public SafariScreenPainter(Font font) {
        this.font = font;
    }

    /**
     * Draws the whole picture of a screen of the given size, in blocks. The
     * pose must already sit at the screen's top-left corner, on its surface,
     * with +X to the reader's right, +Y up and +Z toward the reader; the pose
     * is left as it was found. {@code SafariWallScreenRenderer} tilts the
     * pose before calling this.
     *
     * @param content which page to show
     */
    void paintScreen(PoseStack poseStack, MultiBufferSource buffers, float widthBlocks, float heightBlocks,
                     long gameTime, WallScreenContent content) {
        pal = SafariClientConfigManager.palette();
        float pixelsPerBlock = CANVAS_SHORT_SIDE / Math.min(widthBlocks, heightBlocks);
        float unit = 1.0F / pixelsPerBlock;
        poseStack.pushPose();
        poseStack.scale(unit, -unit, unit);
        BoardPainter painter = new BoardPainter(poseStack, buffers, font);
        drawScreen(painter, widthBlocks * pixelsPerBlock, heightBlocks * pixelsPerBlock,
                System.currentTimeMillis(), gameTime, content);
        poseStack.popPose();
    }

    /** Lays out and draws every part of the screen. */
    private void drawScreen(BoardPainter p, float width, float height, long now, long gameTime,
                            WallScreenContent content) {
        p.fill(0.0F, 0.0F, width, height, Z_BACKGROUND, pal.bezel());
        float left = BEZEL;
        float top = BEZEL;
        float right = width - BEZEL;
        float bottom = height - BEZEL;
        SafariClientConfig.Board board = SafariClientConfigManager.get().board;
        landscape = board.backgroundImage;
        spriteMode = board.useSprites;
        panelAlpha = (int) Math.round(board.panelOpacity * ALPHA_MAX);
        if (landscape) {
            drawLandscape(p, left, top, right, bottom, sceneOf(content));
        } else {
            p.fill(left, top, right, bottom, Z_BACKGROUND + Z_PANEL / 2.0F, pal.background());
        }

        float x0 = left + PADDING;
        float x1 = right - PADDING;
        float headerTop = top + PADDING;
        float bodyTop = headerTop + HEADER_HEIGHT + GAP;
        float footerTop = bottom - PADDING - FOOTER_HEIGHT;
        float bodyBottom = footerTop - GAP;

        Page page = switch (content) {
            case STATS -> drawStats(p, x0, bodyTop, x1, bodyBottom, now, gameTime);
            case BOUNTIES -> drawBounties(p, x0, bodyTop, x1, bodyBottom, now);
            default -> drawBody(p, x0, bodyTop, x1, bodyBottom, FeaturedClientState.entries(), now);
        };
        drawHeader(p, x0, headerTop, x1, page, now, titleKey(content));
        drawFooter(p, x0, footerTop, x1, now, gameTime, footerOf(board, content));

        drawScanline(p, left, top, right, bottom, now);
        long sinceChange = now - FeaturedClientState.changedAtMillis();
        // The flash marks new specials: on the other pages it would flash for nothing they show.
        if (content == WallScreenContent.FEATURED && FeaturedClientState.received()
                && sinceChange >= 0 && sinceChange < FLASH_MS) {
            int peak = pal.flash() >>> ALPHA_SHIFT;
            int alpha = (int) (peak * (1.0F - sinceChange / (float) FLASH_MS));
            p.fill(left, top, right, bottom, Z_OVERLAY, (alpha << ALPHA_SHIFT) | (pal.flash() & RGB_MASK));
        }
    }

    // ---- Header ------------------------------------------------------------

    /** Background scene of a page, so each page is recognisable at a glance. */
    private static SafariLandscapes.Scene sceneOf(WallScreenContent content) {
        return switch (content) {
            case STATS -> SafariLandscapes.RECORD;
            case BOUNTIES -> SafariLandscapes.MISSIONS;
            default -> SafariLandscapes.BOARD;
        };
    }

    /** Header title of a page; each has a "_short" form for narrow screens. */
    private static String titleKey(WallScreenContent content) {
        return switch (content) {
            case STATS -> "screen.stats_title";
            case BOUNTIES -> "screen.bounties_title";
            default -> "screen.title";
        };
    }

    /**
     * Title band with the page counter and a blinking LIVE light. On narrow
     * screens the title shortens first, then the LIVE label goes and only its
     * light stays.
     */
    private void drawHeader(BoardPainter p, float x0, float y, float x1, Page page, long now, String titleKey) {
        p.fill(x0, y, x1, y + HEADER_HEIGHT, Z_PANEL, panel(pal.header()));
        float textY = y + (HEADER_HEIGHT - LINE_HEIGHT) / 2.0F + 1.0F;
        float left = x0 + HEADER_MARGIN;
        float right = x1 - HEADER_MARGIN;

        String live = tr("screen.live").toUpperCase(Locale.ROOT);
        String counter = page.count() > 1 ? (page.index() + 1) + "/" + page.count() : "";
        float counterBlock = counter.isEmpty() ? 0.0F : p.width(counter) + HEADER_SPACING;
        float dotBlock = LIVE_DOT_SIZE + HEADER_SPACING;
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

    // ---- Stats page -----------------------------------------------------------

    /** One stat tile: a small label over a big value. */
    private record StatTile(String label, Component value) {
    }

    /**
     * The viewer's own Safari record as a grid of tiles, split over pages
     * when the screen is too small for all of them.
     *
     * @return the page shown, for the header's page counter
     */
    private Page drawStats(BoardPainter p, float x0, float y0, float x1, float y1, long now, long gameTime) {
        SafariStatsPayload stats = StatsClientState.stats();
        if (stats == null) {
            int dots = (int) ((now / CONNECTING_DOT_MS) % 4);
            drawCentered(p, tr("screen.connecting") + ".".repeat(dots), x0, x1,
                    (y0 + y1) / 2.0F - LINE_HEIGHT / 2.0F, pal.muted());
            return Page.SINGLE;
        }
        List<StatTile> tiles = List.of(
                new StatTile(tr("screen.stats.points"), SafariPointsIcon.amount(stats.points())),
                new StatTile(tr("screen.stats.catches"), Component.literal(String.valueOf(stats.catches()))),
                new StatTile(tr("screen.stats.species"), Component.literal(String.valueOf(stats.species()))),
                new StatTile(tr("screen.stats.shiny"), Component.literal(String.valueOf(stats.shinies()))),
                new StatTile(tr("screen.stats.best"), Component.literal(String.valueOf(stats.bestSession()))),
                new StatTile(tr("screen.stats.time"), Component.literal(Ticks.format(liveTimeHunted(stats, gameTime)))));

        float width = x1 - x0;
        float height = y1 - y0;
        int perPage = tiles.size();
        Grid grid = bestGrid(perPage, width, height, TILE_ASPECT);
        while (perPage > 1 && (grid.cellWidth() < MIN_TILE_WIDTH || grid.cellHeight() < MIN_TILE_HEIGHT)) {
            perPage--;
            grid = bestGrid(perPage, width, height, TILE_ASPECT);
        }
        int pageCount = (tiles.size() + perPage - 1) / perPage;
        int pageIndex = (int) ((now / PAGE_DURATION_MS) % pageCount);
        int first = pageIndex * perPage;
        int last = Math.min(tiles.size(), first + perPage);
        for (int i = first; i < last; i++) {
            int slot = i - first;
            float cx = x0 + (slot % grid.columns()) * (grid.cellWidth() + GAP);
            float cy = y0 + (slot / grid.columns()) * (grid.cellHeight() + GAP);
            drawStatTile(p, cx, cy, grid.cellWidth(), grid.cellHeight(), tiles.get(i));
        }
        return new Page(pageIndex, pageCount);
    }

    /**
     * Time hunted, ticking while the viewer hunts: the finished games from
     * the server's record, plus the time already used in the current game
     * (granted minus remaining, the same sum the server adds when the game
     * ends). Saturates instead of overflowing on years of play.
     */
    private static int liveTimeHunted(SafariStatsPayload stats, long gameTime) {
        long ticks = (long) Ticks.fromSeconds(stats.secondsHunted());
        if (SafariClientState.isActive()) {
            ticks += Math.max(0, SafariClientState.grantedTicks() - SafariClientState.remainingTicks(gameTime));
        }
        return (int) Math.min(Integer.MAX_VALUE, ticks);
    }

    /** A tile: label at the top in small muted type, the value as large as the tile allows below it. */
    private void drawStatTile(BoardPainter p, float x, float y, float w, float h, StatTile tile) {
        p.fill(x, y, x + w, y + h, Z_PANEL, panel(pal.cell()));
        float labelScale = Math.max(MIN_CELL_TEXT_SCALE, Math.min(1.0F, w / CELL_TEXT_REFERENCE_WIDTH)) * SUBTEXT_SCALE;
        float labelRoom = (w - 2.0F * TILE_INNER_GAP) / labelScale;
        String label = p.fit(tile.label(), labelRoom);
        float labelWidth = p.width(label) * labelScale;
        p.text(label, x + (w - labelWidth) / 2.0F, y + TILE_INNER_GAP, Z_TEXT, labelScale, pal.muted());

        float valueTop = y + TILE_INNER_GAP * 2.0F + LINE_HEIGHT * labelScale;
        float valueRoom = h - (valueTop - y) - TILE_INNER_GAP;
        float valueWidth = Math.max(1.0F, p.width(tile.value()));
        float valueScale = Math.min(TILE_VALUE_MAX_SCALE,
                Math.min((w - 2.0F * TILE_INNER_GAP) / valueWidth, valueRoom / LINE_HEIGHT));
        if (valueScale <= 0.0F) {
            return;
        }
        float valueX = x + (w - valueWidth * valueScale) / 2.0F;
        float valueY = valueTop + (valueRoom - LINE_HEIGHT * valueScale) / 2.0F;
        p.text(tile.value(), valueX, valueY, Z_TEXT, valueScale, pal.title());
    }

    // ---- Bounties page --------------------------------------------------------

    /**
     * Today's missions with the viewer's progress, one row each, split over
     * pages when they do not fit.
     *
     * @return the page shown, for the header's page counter
     */
    private Page drawBounties(BoardPainter p, float x0, float y0, float x1, float y1, long now) {
        float centerY = (y0 + y1) / 2.0F - LINE_HEIGHT / 2.0F;
        if (!BountyClientState.hasBounties()) {
            drawCentered(p, tr("bounty.none"), x0, x1, centerY, pal.muted());
            return Page.SINGLE;
        }
        List<Bounty> bounties = BountyClientState.bounties();
        float height = y1 - y0;
        int perPage = bounties.size();
        while (perPage > 1 && rowHeight(perPage, height) < MIN_ROW_HEIGHT) {
            perPage--;
        }
        float rowHeight = Math.min(MAX_ROW_HEIGHT, rowHeight(perPage, height));
        int pageCount = (bounties.size() + perPage - 1) / perPage;
        int pageIndex = (int) ((now / PAGE_DURATION_MS) % pageCount);
        int first = pageIndex * perPage;
        int last = Math.min(bounties.size(), first + perPage);
        for (int i = first; i < last; i++) {
            float rowTop = y0 + (i - first) * (rowHeight + GAP);
            drawBountyRow(p, x0, rowTop, x1, rowHeight, bounties.get(i), i, now);
        }
        return new Page(pageIndex, pageCount);
    }

    /** Height of each of {@code rows} rows sharing {@code height}, gaps included. */
    private static float rowHeight(int rows, float height) {
        return (height - (rows - 1) * GAP) / rows;
    }

    /**
     * One mission: what to catch and the count on the first line, the reward
     * and a progress bar on the second. A done mission turns green.
     */
    private void drawBountyRow(BoardPainter p, float x0, float y, float x1, float h, Bounty bounty, int index,
                               long now) {
        int progress = BountyClientState.progress(index);
        boolean done = progress >= bounty.count();
        int accent = done ? pal.barSessionOk() : pal.barRotation();
        p.fill(x0, y, x1, y + h, Z_PANEL, panel(pal.cell()));
        p.fill(x0, y, x0 + ROW_STRIP, y + h, Z_DETAIL, accent);

        // Text shrinks with the row so both lines always fit: the first line
        // gets the font's height, the second the subtext's, plus the margins.
        float lines = LINE_HEIGHT * (1.0F + SUBTEXT_SCALE) + 3.0F * TILE_INNER_GAP;
        float scale = Math.max(MIN_CELL_TEXT_SCALE, Math.min(1.0F, h / lines));
        float subScale = scale * SUBTEXT_SCALE;
        float left = x0 + ROW_STRIP + TILE_INNER_GAP;
        float right = x1 - TILE_INNER_GAP;

        String count = Math.min(progress, bounty.count()) + "/" + bounty.count();
        float countWidth = p.width(count) * scale;
        float lineY = y + TILE_INNER_GAP;
        p.text(count, right - countWidth, lineY, Z_TEXT, scale, done ? accent : pal.text());
        String mark = done ? "✔ " : "• ";
        float markWidth = p.width(mark) * scale;
        p.text(mark, left, lineY, Z_TEXT, scale, accent);
        // Built every frame: describe() looks up the type or species name,
        // which may change language at any time; a few rows per screen.
        drawMarquee(p, bounty.describe().getString(), left + markWidth, right - countWidth - HEADER_SPACING,
                lineY, scale, done ? pal.muted() : pal.text(), now, false);

        float subY = lineY + LINE_HEIGHT * scale + TILE_INNER_GAP;
        Component reward = Component.literal("+").append(SafariPointsIcon.amount(bounty.reward()));
        float rewardWidth = p.width(reward) * subScale;
        p.text(reward, left, subY, Z_TEXT, subScale, pal.star());
        float barLeft = left + rewardWidth + HEADER_SPACING;
        float barTop = subY + (LINE_HEIGHT * subScale - ROW_BAR_HEIGHT) / 2.0F;
        if (barLeft < right) {
            p.fill(barLeft, barTop, right, barTop + ROW_BAR_HEIGHT, Z_DETAIL, panel(pal.barBackground()));
            float fraction = Math.min(1.0F, progress / (float) Math.max(1, bounty.count()));
            p.fill(barLeft, barTop, barLeft + (right - barLeft) * fraction, barTop + ROW_BAR_HEIGHT,
                    Z_DETAIL_TOP, accent);
        }
    }

    /**
     * The grid shape that gives the largest cells for {@code count} specials,
     * judging a cell by its width and by its height scaled to the preferred
     * aspect, whichever is the limiting one.
     */
    private static Grid bestGrid(int count, float width, float height) {
        return bestGrid(count, width, height, CELL_ASPECT);
    }

    /** Same as {@link #bestGrid(int, float, float)} for cells of another preferred shape. */
    private static Grid bestGrid(int count, float width, float height, float aspect) {
        Grid best = null;
        float bestScore = -1.0F;
        for (int columns = 1; columns <= count; columns++) {
            int rows = (count + columns - 1) / columns;
            float cellWidth = (width - (columns - 1) * GAP) / columns;
            float cellHeight = (height - (rows - 1) * GAP) / rows;
            float score = Math.min(cellWidth, cellHeight * aspect);
            if (score > bestScore) {
                bestScore = score;
                best = new Grid(columns, rows, cellWidth, cellHeight);
            }
        }
        return best;
    }

    /**
     * The Pokémon's sprite from a resource pack, centred in the model area
     * and bobbing slightly so the screen stays alive.
     *
     * @return false when no loaded pack has a sprite for it (the caller then draws the model)
     */
    private boolean drawSprite(BoardPainter p, float x, float w, float top, float bottom,
                               SafariFeaturedPayload.Entry entry, int slot, long now) {
        ResourceLocation sprite = PokemonSprites.find(entry.species(), new HashSet<>(entry.aspects()));
        if (sprite == null) {
            return false;
        }
        float size = Math.min(bottom - top, w - 2.0F * SPRITE_SIDE_MARGIN) * SPRITE_FILL;
        if (size <= 0.0F) {
            return false;
        }
        double phase = 2.0D * Math.PI * ((now + slot * SPRITE_BOB_STAGGER_MS) % SPRITE_BOB_PERIOD_MS)
                / SPRITE_BOB_PERIOD_MS;
        float bob = (float) Math.sin(phase) * SPRITE_BOB_PIXELS;
        float left = x + (w - size) / 2.0F;
        float spriteTop = (top + bottom) / 2.0F - size / 2.0F + bob;
        p.image(sprite, left, spriteTop, left + size, spriteTop + size, Z_DETAIL, 0.0F, 0.0F, 1.0F, 1.0F,
                SPRITE_TINT);
        return true;
    }

    /** One special: rarity strip, turning model, name, rarity and, when there is room, habitat. */
    private void drawCell(BoardPainter p, float x, float y, float w, float h, SafariFeaturedPayload.Entry entry,
                          int slot, long revealStart, long now) {
        int rarityColor = rarityColor(entry);
        p.fill(x, y, x + w, y + h, Z_PANEL, panel(pal.cell()));
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
        if (spriteMode && modelBottom > modelTop) {
            drawn = drawSprite(p, x, w, modelTop, modelBottom, entry, slot, now);
        }
        if (!drawn && modelHeight > 0.0F) {
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
        drawMarquee(p, text, x0, x1, y, scale, color, now, true);
    }

    /**
     * Same as {@link #drawMarquee(BoardPainter, String, float, float, float, float, int, long)}, with
     * the choice of where a line that fits sits: centred in a cell, or
     * against the left edge in a list row where lines must align.
     */
    private void drawMarquee(BoardPainter p, String text, float x0, float x1, float y, float scale, int color,
                             long now, boolean centred) {
        float room = x1 - x0;
        float width = p.width(text) * scale;
        if (width <= room) {
            p.text(text, centred ? x0 + (room - width) / 2.0F : x0, y, Z_TEXT, scale, color);
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

    /** What the footer of a kind of screen counts down, from the viewer's client config. */
    private static WallScreenFooter footerOf(SafariClientConfig.Board board, WallScreenContent content) {
        return switch (content) {
            case STATS -> board.statsFooter;
            case BOUNTIES -> board.bountiesFooter;
            default -> board.featuredFooter;
        };
    }

    /**
     * Label, time and progress bar. With {@link WallScreenFooter#SESSION}, a
     * viewer with a session sees their own hunt draining; anyone else, and
     * everyone with {@link WallScreenFooter#ROTATION}, sees the countdown to
     * the next rotation.
     */
    private void drawFooter(BoardPainter p, float x0, float y, float x1, long now, long gameTime,
                            WallScreenFooter mode) {
        String labelKey;
        String time;
        float fraction;
        int color;
        if (mode == WallScreenFooter.SESSION && SafariClientState.isActive()) {
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
        p.fill(x0, barTop, x1, barBottom, Z_PANEL, panel(pal.barBackground()));
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

    /**
     * A page's safari landscape, in the light of the time of day, behind the
     * whole screen, cropped to the screen's shape (centred on the horizon, so
     * a long flat screen shows the horizon line) and dimmed so text stays
     * readable. Drawn before every fill: the translucent panels blend over it.
     */
    private void drawLandscape(BoardPainter p, float left, float top, float right, float bottom,
                               SafariLandscapes.Scene scene) {
        float width = right - left;
        float height = bottom - top;
        float screenAspect = width / height;
        float textureAspect = scene.width() / (float) scene.height();
        float u0 = 0.0F;
        float u1 = 1.0F;
        float v0 = 0.0F;
        float v1 = 1.0F;
        if (screenAspect > textureAspect) {
            float span = textureAspect / screenAspect;
            v0 = Math.max(0.0F, Math.min(1.0F - span, scene.horizon() - span / 2.0F));
            v1 = v0 + span;
        } else {
            float span = screenAspect / textureAspect;
            u0 = (1.0F - span) / 2.0F;
            u1 = u0 + span;
        }
        p.image(scene.forTime(Minecraft.getInstance().level), left, top, right, bottom,
                Z_BACKGROUND + Z_PANEL / 2.0F, u0, v0, u1, v1, LANDSCAPE_TINT);
    }

    /**
     * A panel colour made translucent over the landscape, unless the colour
     * already sets its own alpha in the client config. Unchanged without the
     * landscape.
     */
    private int panel(int colour) {
        if (!landscape || (colour >>> ALPHA_SHIFT) != ALPHA_MAX) {
            return colour;
        }
        return (panelAlpha << ALPHA_SHIFT) | (colour & RGB_MASK);
    }

    /** Text centred between two x positions at scale 1, cut with an ellipsis when too long. */
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
