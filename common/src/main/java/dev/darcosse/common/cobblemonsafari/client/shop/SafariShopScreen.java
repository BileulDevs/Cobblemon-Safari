package dev.darcosse.common.cobblemonsafari.client.shop;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import dev.darcosse.common.cobblemonsafari.client.FeaturedClientState;
import dev.darcosse.common.cobblemonsafari.network.SafariClientNetwork;
import dev.darcosse.common.cobblemonsafari.network.SafariFeaturedPayload;
import dev.darcosse.common.cobblemonsafari.network.SafariShopBuyPayload;
import dev.darcosse.common.cobblemonsafari.network.SafariShopPayload;
import dev.darcosse.common.cobblemonsafari.points.SafariPointsIcon;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

/**
 * The Safari shop, in the style of a Game Corner prize counter: tabs (Pokémon,
 * items, balls...), then cards with the Pokémon's animated 3D model (or the
 * item, enlarged), its name and its price; select a card, then press Buy.
 *
 * <p>The screen only displays what the server sent ({@link SafariShopPayload})
 * and asks for purchases ({@link SafariShopBuyPayload}); the server decides.
 * After each request the server sends a refresh, which also re-enables the
 * Buy button, so a double click cannot buy twice. CLIENT ONLY.
 */
public class SafariShopScreen extends Screen {

    /**
     * Panel size, in GUI pixels. Fits the smallest common GUI (320×240 at the
     * largest GUI scale). The background texture has exactly this size
     * (tools/gui_assets.py): change both together or it stretches.
     */
    private static final int PANEL_WIDTH = 300;
    private static final int PANEL_HEIGHT = 232;

    /** Landscape drawn behind the panel, 1:1. */
    private static final ResourceLocation BACKGROUND = CobblemonSafari.id("textures/gui/shop_background.png");

    private static final int COLUMNS = 4;
    private static final int ROWS = 2;
    private static final int PER_PAGE = COLUMNS * ROWS;

    private static final int HEADER_HEIGHT = 22;
    private static final int FOOTER_HEIGHT = 24;
    /** Space between the panel edge and the content box, on the sides. */
    private static final int OUTER_MARGIN = 8;
    /** Space between the content box border and the cards: the box's padding. */
    private static final int CONTENT_PADDING = 6;
    /** Tabs sit on top of the content box, like folder tabs. */
    private static final int TAB_HEIGHT = 16;
    /** Inactive tabs are this much shorter than the open one, so the open one stands out. */
    private static final int TAB_INACTIVE_DROP = 2;
    private static final int TAB_PADDING = 4;
    private static final int TAB_GAP = 2;
    /** Gap above the tabs, under the header. */
    private static final int TAB_TOP_GAP = 2;
    /** Gap between the content box and the footer. */
    private static final int CONTENT_BOTTOM_GAP = 2;

    private static final int CARD_GAP = 4;
    /**
     * Card size, derived from the panel so the grid exactly fills the content
     * box with its padding: changing the panel size or a margin resizes the
     * cards instead of overflowing.
     */
    private static final int CONTENT_LEFT = OUTER_MARGIN;
    private static final int CONTENT_WIDTH = PANEL_WIDTH - 2 * OUTER_MARGIN;
    private static final int CONTENT_TOP = HEADER_HEIGHT + TAB_TOP_GAP + TAB_HEIGHT;
    private static final int CONTENT_HEIGHT = PANEL_HEIGHT - FOOTER_HEIGHT - CONTENT_BOTTOM_GAP - CONTENT_TOP;
    private static final int CARD_WIDTH = (CONTENT_WIDTH - 2 * CONTENT_PADDING - (COLUMNS - 1) * CARD_GAP) / COLUMNS;
    private static final int CARD_HEIGHT = (CONTENT_HEIGHT - 2 * CONTENT_PADDING - (ROWS - 1) * CARD_GAP) / ROWS;
    /** Space under the picture for the name and the price, two lines of text. */
    private static final int CARD_TEXT_HEIGHT = 24;
    /** Height of the model or item area at the top of a card. */
    private static final int PICTURE_HEIGHT = CARD_HEIGHT - CARD_TEXT_HEIGHT;

    private static final int BUTTON_HEIGHT = 18;
    private static final int PAGE_BUTTON_WIDTH = 20;
    private static final int BUY_BUTTON_WIDTH = 110;
    private static final int MARGIN = 6;

    /** Items are drawn at this multiple of their 16 px icon on cards. */
    private static final float ITEM_SCALE = 2.5F;
    private static final int ITEM_ICON_SIZE = 16;
    /** Tab icons are drawn at this scale (16 px items, 12 px on the tab). */
    private static final float TAB_ICON_SCALE = 0.75F;
    /** The level badge on Pokémon cards is drawn at this text scale. */
    private static final float BADGE_SCALE = 0.75F;

    /**
     * Colours, ARGB. Safari greens framed with wood and a gold accent, like
     * the gate and the board. Boxes are translucent so the landscape shows
     * through, but dark enough to keep white text readable over the sky.
     */
    private static final int PANEL_BORDER = 0xFF7A5A2E;
    private static final int BAR_COLOR = 0xC0102A1C;
    private static final int BAR_LINE = 0x90FFD86B;
    private static final int WOOD = 0xFF6B4A2A;
    private static final int WOOD_LIGHT = 0xFFB08A4E;
    /** Lighter than the cards: the landscape shows through the box, the cards stand on it. */
    private static final int CONTENT_COLOR = 0x80102419;
    private static final int CARD_TOP = 0xB0347A56;
    private static final int CARD_BOTTOM = 0xB01C4A33;
    private static final int CARD_TEXT_BAND = 0xD8122A1C;
    private static final int CARD_HOVER_TINT = 0x30FFFFFF;
    private static final int EMPTY_SLOT = 0x40000000;
    private static final int GOLD = 0xFFFFD86B;
    private static final int TAB_COLOR = 0xC0163A26;
    private static final int TAB_HOVER = 0xD02E6B4B;
    private static final int TEXT_COLOR = 0xFFFFFFFF;
    private static final int PRICE_COLOR = 0xFFFFD86B;
    private static final int PRICE_TOO_HIGH = 0xFFFF6B6B;
    private static final int MUTED_COLOR = 0xFFB5C9BA;

    /**
     * Where the scene's Pokémon stand, in panel pixels: x, y of the model
     * area's top-left corner, its size, and the facing the model sways
     * around (degrees). Matches the landscape of tools/gui_assets.py: the
     * first is the trainer's companion, at his side, turned toward the
     * plain; the others are wild, smaller with distance, turned toward him.
     * If the models face the wrong way in game, flip the sign of the facings.
     */
    private static final int[][] SCENE_SPOTS = {
            {200, 166, 30, -55},
            {136, 148, 20, 55},
            {98, 144, 15, 55},
    };
    /** OpenGL's depth buffer bit, the value vanilla passes to RenderSystem.clear for the same purpose. */
    private static final int GL_DEPTH_BUFFER_BIT = 0x100;
    /** Animation state keys of the scene models, apart from the cards' (which use entry indices). */
    private static final String SCENE_KEY_PREFIX = "scene";

    private SafariShopPayload data;
    /** Index in {@code data.tabs()} of the open tab. */
    private int tab;
    /** Indices in the whole entry list of the entries of the open tab, in order. */
    private List<Integer> visible = List.of();
    private int page;
    /** Index in the whole list of the selected entry, or -1. */
    private int selected = -1;
    /** True between a purchase request and the server's refresh. */
    private boolean awaitingServer;

    private int left;
    private int top;
    private Button previousButton;
    private Button nextButton;
    private Button buyButton;

    /** Screen showing the given shop contents. */
    private SafariShopScreen(SafariShopPayload data) {
        super(tr("shop.title"));
        this.data = data;
        this.tab = firstTabWithEntries();
        this.visible = entriesOf(tab);
    }

    /**
     * Payload handler, installed through {@code ShopClientBridge}: opens the
     * screen, or refreshes it if it is already open. A refresh with the
     * screen closed is ignored.
     */
    public static void handle(SafariShopPayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        if (payload.refresh()) {
            if (minecraft.screen instanceof SafariShopScreen screen) {
                screen.update(payload);
            }
        } else {
            minecraft.setScreen(new SafariShopScreen(payload));
        }
    }

    /** Applies a refresh, keeping the tab, page and selection when they still exist. */
    private void update(SafariShopPayload payload) {
        data = payload;
        awaitingServer = false;
        if (tab >= data.tabs().size() || entriesOf(tab).isEmpty()) {
            tab = firstTabWithEntries();
        }
        visible = entriesOf(tab);
        if (!visible.contains(selected)) {
            selected = -1;
        }
        page = Math.min(page, Math.max(0, pageCount() - 1));
        refreshButtons();
    }

    /** Places the buttons; called again by vanilla when the window is resized. */
    @Override
    protected void init() {
        left = (width - PANEL_WIDTH) / 2;
        top = (height - PANEL_HEIGHT) / 2;
        int buttonY = top + PANEL_HEIGHT - FOOTER_HEIGHT + (FOOTER_HEIGHT - BUTTON_HEIGHT) / 2;
        previousButton = addRenderableWidget(Button.builder(Component.literal("<"), button -> turnPage(-1))
                .bounds(left + MARGIN, buttonY, PAGE_BUTTON_WIDTH, BUTTON_HEIGHT).build());
        nextButton = addRenderableWidget(Button.builder(Component.literal(">"), button -> turnPage(1))
                .bounds(left + MARGIN + PAGE_BUTTON_WIDTH + 44, buttonY, PAGE_BUTTON_WIDTH, BUTTON_HEIGHT).build());
        buyButton = addRenderableWidget(Button.builder(tr("shop.select"), button -> buySelected())
                .bounds(left + PANEL_WIDTH - MARGIN - BUY_BUTTON_WIDTH, buttonY, BUY_BUTTON_WIDTH, BUTTON_HEIGHT)
                .build());
        refreshButtons();
    }

    /** Enables the buttons that make sense now and labels the Buy button with the price. */
    private void refreshButtons() {
        if (buyButton == null) {
            return;
        }
        previousButton.active = page > 0;
        nextButton.active = page + 1 < pageCount();
        SafariShopPayload.Entry entry = selectedEntry();
        if (entry == null) {
            buyButton.setMessage(tr("shop.select"));
            buyButton.active = false;
        } else {
            buyButton.setMessage(tr("shop.buy", SafariPointsIcon.amount(entry.price())));
            buyButton.active = !awaitingServer && entry.kind() != SafariShopPayload.KIND_UNAVAILABLE
                    && data.points() >= entry.price();
        }
    }

    private void turnPage(int delta) {
        page = Math.max(0, Math.min(pageCount() - 1, page + delta));
        refreshButtons();
    }

    /** Opens a tab on its first page; the selection does not follow to another tab. */
    private void openTab(int index) {
        if (index == tab) {
            return;
        }
        tab = index;
        visible = entriesOf(tab);
        page = 0;
        selected = -1;
        refreshButtons();
    }

    /** Sends the purchase request; the server's refresh re-enables the button. */
    private void buySelected() {
        SafariShopPayload.Entry entry = selectedEntry();
        if (entry == null || awaitingServer) {
            return;
        }
        awaitingServer = true;
        refreshButtons();
        SafariClientNetwork.sendToServer(new SafariShopBuyPayload(selected, entry.price()));
    }

    /** Selects the card or opens the tab under the mouse; otherwise lets the buttons handle the click. */
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int tabIndex = tabAt(mouseX, mouseY);
        if (tabIndex >= 0) {
            openTab(tabIndex);
            return true;
        }
        int index = cardAt(mouseX, mouseY);
        if (index >= 0) {
            selected = index;
            refreshButtons();
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /** The world stays visible and running behind the counter. */
    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** Frees the animation states of the models. */
    @Override
    public void removed() {
        ShopPokemonModels.clear();
        super.removed();
    }

    /**
     * Draws the panel, tabs and cards under the buttons. Vanilla's
     * {@code render} calls this first, then draws the widgets on top.
     */
    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(graphics, mouseX, mouseY, partialTick);
        graphics.fill(left - 1, top - 1, left + PANEL_WIDTH + 1, top + PANEL_HEIGHT + 1, PANEL_BORDER);
        graphics.blit(BACKGROUND, left, top, 0.0F, 0.0F, PANEL_WIDTH, PANEL_HEIGHT, PANEL_WIDTH, PANEL_HEIGHT);
        drawScenePokemon(graphics, partialTick);
        // Header and footer bars, each with a thin gold rule toward the content.
        graphics.fill(left, top, left + PANEL_WIDTH, top + HEADER_HEIGHT, BAR_COLOR);
        graphics.hLine(left, left + PANEL_WIDTH - 1, top + HEADER_HEIGHT - 1, BAR_LINE);
        int footerTop = top + PANEL_HEIGHT - FOOTER_HEIGHT;
        graphics.fill(left, footerTop, left + PANEL_WIDTH, top + PANEL_HEIGHT, BAR_COLOR);
        graphics.hLine(left, left + PANEL_WIDTH - 1, footerTop, BAR_LINE);

        graphics.drawString(font, title, left + MARGIN, top + (HEADER_HEIGHT - font.lineHeight) / 2, TEXT_COLOR);
        Component points = tr("shop.points", SafariPointsIcon.amount(data.points()));
        graphics.drawString(font, points, left + PANEL_WIDTH - MARGIN - font.width(points),
                top + (HEADER_HEIGHT - font.lineHeight) / 2, PRICE_COLOR);

        drawContentBox(graphics);
        drawTabs(graphics, mouseX, mouseY);

        List<SafariShopPayload.Entry> entries = data.entries();
        if (visible.isEmpty()) {
            Component empty = tr(entries.isEmpty() ? "shop.empty" : "shop.tab_empty");
            graphics.drawString(font, empty, left + (PANEL_WIDTH - font.width(empty)) / 2,
                    top + CONTENT_TOP + CONTENT_HEIGHT / 2 - font.lineHeight / 2, TEXT_COLOR);
        }
        for (int slot = 0; slot < PER_PAGE; slot++) {
            int position = page * PER_PAGE + slot;
            int x = cardX(slot);
            int y = cardY(slot);
            if (position >= visible.size()) {
                // Empty slots keep the grid's shape, so a short tab does not look broken.
                if (!visible.isEmpty()) {
                    graphics.fill(x + 1, y + 1, x + CARD_WIDTH - 1, y + CARD_HEIGHT - 1, EMPTY_SLOT);
                }
                continue;
            }
            int index = visible.get(position);
            boolean hovered = mouseX >= x && mouseX < x + CARD_WIDTH && mouseY >= y && mouseY < y + CARD_HEIGHT;
            drawCard(graphics, entries.get(index), index, x, y, hovered, partialTick);
        }

        Component pageLabel = Component.literal((page + 1) + " / " + Math.max(1, pageCount()));
        int labelCenter = left + MARGIN + PAGE_BUTTON_WIDTH + 22;
        graphics.drawString(font, pageLabel, labelCenter - font.width(pageLabel) / 2,
                top + PANEL_HEIGHT - FOOTER_HEIGHT / 2 - font.lineHeight / 2, MUTED_COLOR);
    }

    /**
     * Pokémon living in the landscape: a companion beside the trainer drawn
     * in the background, and wild ones on the plain he points at. Real
     * Cobblemon models, today's specials first (the shop's own Pokémon when
     * the specials are not known yet), so the scene changes with the day.
     * They are part of the background: everything else is drawn over them.
     */
    private void drawScenePokemon(GuiGraphics graphics, float partialTick) {
        List<ResourceLocation> species = new ArrayList<>();
        List<List<String>> aspects = new ArrayList<>();
        for (SafariFeaturedPayload.Entry special : FeaturedClientState.entries()) {
            species.add(special.species());
            aspects.add(special.aspects());
        }
        for (SafariShopPayload.Entry entry : data.entries()) {
            if (entry.kind() == SafariShopPayload.KIND_POKEMON) {
                species.add(entry.species());
                aspects.add(entry.aspects());
            }
        }
        if (species.isEmpty()) {
            return;
        }
        graphics.flush();
        graphics.enableScissor(left, top, left + PANEL_WIDTH, top + PANEL_HEIGHT);
        for (int i = 0; i < SCENE_SPOTS.length && i < species.size(); i++) {
            int[] spot = SCENE_SPOTS[i];
            ShopPokemonModels.draw(graphics, SCENE_KEY_PREFIX + i, species.get(i), aspects.get(i),
                    left + spot[0], top + spot[1], spot[2], partialTick, spot[3]);
        }
        graphics.disableScissor();
        // Models write depth; cleared so the boxes and cards drawn next cover
        // them instead of being hidden behind their front half.
        RenderSystem.clear(GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
    }

    /** The box holding the cards: translucent, framed with wood, the tabs sitting on its top edge. */
    private void drawContentBox(GuiGraphics graphics) {
        int x0 = left + CONTENT_LEFT;
        int y0 = top + CONTENT_TOP;
        int x1 = x0 + CONTENT_WIDTH;
        int y1 = y0 + CONTENT_HEIGHT;
        graphics.fill(x0, y0, x1, y1, CONTENT_COLOR);
        // Sides and bottom only: the top edge is drawn with the tabs, which
        // leave a gap in it under the open tab.
        graphics.hLine(x0 + 1, x1 - 2, y1 - 1, WOOD);
        graphics.vLine(x0, y0, y1 - 1, WOOD);
        graphics.vLine(x1 - 1, y0, y1 - 1, WOOD);
    }

    /**
     * The tab row, as folder tabs on the content box: each tab with an icon
     * then its name. The open tab is taller, gold-edged, and melts into the
     * box (same colour, no line between them); the others sit lower, darker.
     */
    private void drawTabs(GuiGraphics graphics, int mouseX, int mouseY) {
        int x = left + CONTENT_LEFT + CONTENT_PADDING;
        int bottom = top + CONTENT_TOP;
        int openLeft = -1;
        int openRight = -1;
        for (int i = 0; i < data.tabs().size(); i++) {
            if (entriesOf(i).isEmpty()) {
                continue;
            }
            SafariShopPayload.Tab shown = data.tabs().get(i);
            int w = tabWidth(shown);
            if (x + w > left + CONTENT_LEFT + CONTENT_WIDTH - CONTENT_PADDING) {
                // More tabs than fit: the rest stay hidden rather than spill out of the box.
                break;
            }
            boolean open = i == tab;
            int y = bottom - TAB_HEIGHT + (open ? 0 : TAB_INACTIVE_DROP);
            boolean hovered = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < bottom;
            if (open) {
                // Same colour as the box, and the box's top edge stops under it:
                // tab and box read as one shape.
                openLeft = x;
                openRight = x + w;
                graphics.fill(x, y, x + w, bottom, CONTENT_COLOR);
                graphics.hLine(x + 1, x + w - 2, y, GOLD);
                graphics.vLine(x, y, bottom, GOLD);
                graphics.vLine(x + w - 1, y, bottom, GOLD);
            } else {
                graphics.fill(x, y, x + w, bottom, hovered ? TAB_HOVER : TAB_COLOR);
                graphics.hLine(x + 1, x + w - 2, y, WOOD);
                graphics.vLine(x, y, bottom, WOOD);
                graphics.vLine(x + w - 1, y, bottom, WOOD);
            }
            int textX = x + TAB_PADDING;
            int middle = y + (bottom - y) / 2;
            if (!shown.icon().isEmpty()) {
                graphics.pose().pushPose();
                graphics.pose().translate(x + TAB_PADDING, middle - ITEM_ICON_SIZE * TAB_ICON_SCALE / 2.0F, 0.0F);
                graphics.pose().scale(TAB_ICON_SCALE, TAB_ICON_SCALE, 1.0F);
                graphics.renderFakeItem(shown.icon(), 0, 0);
                graphics.pose().popPose();
                textX += (int) (ITEM_ICON_SIZE * TAB_ICON_SCALE) + TAB_GAP;
            }
            graphics.drawString(font, tabName(shown), textX, middle - font.lineHeight / 2 + 1,
                    open ? GOLD : hovered ? TEXT_COLOR : MUTED_COLOR);
            x += w + TAB_GAP;
        }
        int boxLeft = left + CONTENT_LEFT;
        int boxRight = boxLeft + CONTENT_WIDTH;
        if (openLeft < 0) {
            graphics.hLine(boxLeft + 1, boxRight - 2, bottom, WOOD);
        } else {
            // The open tab's gold sides run down into the box edge on both ends.
            graphics.hLine(boxLeft + 1, openLeft, bottom, WOOD);
            graphics.hLine(openRight - 1, boxRight - 2, bottom, WOOD);
            graphics.hLine(openLeft, openLeft, bottom, GOLD);
            graphics.hLine(openRight - 1, openRight - 1, bottom, GOLD);
        }
    }

    /**
     * One card: a wood-framed tile with rounded corners, a lit picture area
     * (model or item, level badge for Pokémon), then a dark band with the
     * name and the price.
     */
    private void drawCard(GuiGraphics graphics, SafariShopPayload.Entry entry, int index, int x, int y,
                          boolean hovered, float partialTick) {
        int x1 = x + CARD_WIDTH;
        int y1 = y + CARD_HEIGHT;
        int pictureBottom = y + PICTURE_HEIGHT;
        graphics.fillGradient(x + 1, y + 1, x1 - 1, pictureBottom, CARD_TOP, CARD_BOTTOM);
        graphics.fill(x + 1, pictureBottom, x1 - 1, y1 - 1, CARD_TEXT_BAND);
        if (hovered) {
            graphics.fill(x + 1, y + 1, x1 - 1, y1 - 1, CARD_HOVER_TINT);
        }
        boolean isSelected = index == selected;
        frame(graphics, x, y, x1, y1, isSelected ? GOLD : hovered ? WOOD_LIGHT : WOOD);
        if (isSelected) {
            // Second, inner gold line: the selection reads at a glance.
            frame(graphics, x + 1, y + 1, x1 - 1, y1 - 1, GOLD);
        }

        // Clipped: some models are wider or taller than their profile box.
        graphics.enableScissor(x + 2, y + 2, x1 - 2, pictureBottom);
        switch (entry.kind()) {
            case SafariShopPayload.KIND_POKEMON -> {
                // Text and fills are batched; flushed first so the model is not drawn under them.
                graphics.flush();
                int size = PICTURE_HEIGHT - 4;
                ShopPokemonModels.draw(graphics, Integer.toString(index), entry.species(), entry.aspects(),
                        x + (CARD_WIDTH - size) / 2, y + 3, size, partialTick);
            }
            case SafariShopPayload.KIND_ITEM -> drawBigItem(graphics, entry.item(), x, y);
            default -> drawBigItem(graphics, new ItemStack(Items.BARRIER), x, y);
        }
        graphics.disableScissor();

        if (entry.kind() == SafariShopPayload.KIND_POKEMON) {
            // Level as a small badge in the corner: the name line then holds the
            // whole species name, which "Name (Lv. 12)" used to cut.
            graphics.pose().pushPose();
            graphics.pose().translate(x + 3, y + 3, 200.0F);
            graphics.pose().scale(BADGE_SCALE, BADGE_SCALE, 1.0F);
            graphics.drawString(font, tr("shop.level_badge", entry.level()), 0, 0, MUTED_COLOR);
            graphics.pose().popPose();
        }

        Component name = name(entry);
        String shown = font.plainSubstrByWidth(name.getString(), CARD_WIDTH - 4);
        graphics.drawString(font, shown, x + (CARD_WIDTH - font.width(shown)) / 2, pictureBottom + 2,
                entry.shiny() ? GOLD : TEXT_COLOR);
        Component price = tr("shop.price_short", SafariPointsIcon.amount(entry.price()));
        graphics.drawString(font, price, x + (CARD_WIDTH - font.width(price)) / 2,
                pictureBottom + 2 + font.lineHeight + 2,
                data.points() >= entry.price() ? PRICE_COLOR : PRICE_TOO_HIGH);
    }

    /**
     * A one-pixel frame whose corner pixels are left out: at GUI scale that
     * reads as rounded corners, softer than a hard rectangle.
     */
    private static void frame(GuiGraphics graphics, int x0, int y0, int x1, int y1, int color) {
        graphics.hLine(x0 + 1, x1 - 2, y0, color);
        graphics.hLine(x0 + 1, x1 - 2, y1 - 1, color);
        graphics.vLine(x0, y0, y1 - 1, color);
        graphics.vLine(x1 - 1, y0, y1 - 1, color);
    }

    /** An item icon enlarged and centred in the picture area, with its stack count. */
    private void drawBigItem(GuiGraphics graphics, ItemStack stack, int x, int y) {
        float size = ITEM_ICON_SIZE * ITEM_SCALE;
        graphics.pose().pushPose();
        graphics.pose().translate(x + (CARD_WIDTH - size) / 2.0F, y + (PICTURE_HEIGHT - size) / 2.0F, 0.0F);
        graphics.pose().scale(ITEM_SCALE, ITEM_SCALE, 1.0F);
        graphics.renderItem(stack, 0, 0);
        graphics.renderItemDecorations(font, stack, 0, 0);
        graphics.pose().popPose();
    }

    /** What a card is called: species, item name, or "unavailable". The level is a badge, see {@link #drawCard}. */
    private static Component name(SafariShopPayload.Entry entry) {
        return switch (entry.kind()) {
            case SafariShopPayload.KIND_POKEMON -> Component.literal(ShopPokemonModels.displayName(entry.species()));
            case SafariShopPayload.KIND_ITEM -> entry.item().getHoverName();
            default -> tr("shop.unavailable");
        };
    }

    /**
     * A tab's label: the name set in the config, else the translation of a
     * built-in id, else the id itself capitalised (a custom tab without a
     * name would otherwise show a raw translation key).
     */
    private static Component tabName(SafariShopPayload.Tab shown) {
        if (!shown.name().isEmpty()) {
            return Component.literal(shown.name());
        }
        String key = CobblemonSafari.MOD_ID + ".shop.tab." + shown.id();
        if (Language.getInstance().has(key)) {
            return Component.translatable(key);
        }
        return Component.literal(shown.id().isEmpty() ? "?"
                : Character.toUpperCase(shown.id().charAt(0)) + shown.id().substring(1));
    }

    private int tabWidth(SafariShopPayload.Tab shown) {
        int iconWidth = shown.icon().isEmpty() ? 0 : (int) (ITEM_ICON_SIZE * TAB_ICON_SCALE) + TAB_GAP;
        return TAB_PADDING * 2 + iconWidth + font.width(tabName(shown));
    }

    /** Index of the tab under the point, or -1. Mirrors the layout of {@link #drawTabs}. */
    private int tabAt(double mouseX, double mouseY) {
        int bottom = top + CONTENT_TOP;
        if (mouseY < bottom - TAB_HEIGHT || mouseY >= bottom) {
            return -1;
        }
        int x = left + CONTENT_LEFT + CONTENT_PADDING;
        for (int i = 0; i < data.tabs().size(); i++) {
            if (entriesOf(i).isEmpty()) {
                continue;
            }
            int w = tabWidth(data.tabs().get(i));
            if (x + w > left + CONTENT_LEFT + CONTENT_WIDTH - CONTENT_PADDING) {
                break;
            }
            if (mouseX >= x && mouseX < x + w) {
                return i;
            }
            x += w + TAB_GAP;
        }
        return -1;
    }

    /** Index of the entry whose card is under the point, or -1. */
    private int cardAt(double mouseX, double mouseY) {
        for (int slot = 0; slot < PER_PAGE; slot++) {
            int position = page * PER_PAGE + slot;
            if (position >= visible.size()) {
                break;
            }
            int x = cardX(slot);
            int y = cardY(slot);
            if (mouseX >= x && mouseX < x + CARD_WIDTH && mouseY >= y && mouseY < y + CARD_HEIGHT) {
                return visible.get(position);
            }
        }
        return -1;
    }

    /** Left edge of the card in a page slot, inside the content box's padding. */
    private int cardX(int slot) {
        return left + CONTENT_LEFT + CONTENT_PADDING + (slot % COLUMNS) * (CARD_WIDTH + CARD_GAP);
    }

    /** Top edge of the card in a page slot. */
    private int cardY(int slot) {
        return top + CONTENT_TOP + CONTENT_PADDING + (slot / COLUMNS) * (CARD_HEIGHT + CARD_GAP);
    }

    private int pageCount() {
        return (visible.size() + PER_PAGE - 1) / PER_PAGE;
    }

    /**
     * Indices of the entries shown in a tab. Without any tab (a server
     * config with none cannot be sent, but a stale client might see one)
     * every entry shows.
     */
    private List<Integer> entriesOf(int tabIndex) {
        List<Integer> out = new ArrayList<>();
        List<SafariShopPayload.Entry> entries = data.entries();
        for (int i = 0; i < entries.size(); i++) {
            if (data.tabs().isEmpty() || entries.get(i).tab() == tabIndex) {
                out.add(i);
            }
        }
        return out;
    }

    /** The first tab holding something, so the screen never opens on an empty tab; 0 when all are empty. */
    private int firstTabWithEntries() {
        for (int i = 0; i < data.tabs().size(); i++) {
            if (!entriesOf(i).isEmpty()) {
                return i;
            }
        }
        return 0;
    }

    /** The selected entry, or null when none or no longer listed. */
    private SafariShopPayload.Entry selectedEntry() {
        return selected >= 0 && selected < data.entries().size() ? data.entries().get(selected) : null;
    }

    /** Translatable component in the mod namespace. */
    private static MutableComponent tr(String path, Object... args) {
        return Component.translatable(CobblemonSafari.MOD_ID + "." + path, args);
    }
}
