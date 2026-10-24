package dev.darcosse.common.cobblemonsafari.client.shop;

import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import dev.darcosse.common.cobblemonsafari.client.SafariLandscapes;
import dev.darcosse.common.cobblemonsafari.item.SafariEscapeRopeItem;
import dev.darcosse.common.cobblemonsafari.network.SafariClientNetwork;
import dev.darcosse.common.cobblemonsafari.network.SafariShopBuyPayload;
import dev.darcosse.common.cobblemonsafari.network.SafariShopPayload;
import dev.darcosse.common.cobblemonsafari.points.SafariPointsIcon;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The Safari shop, in the style of a Game Corner prize counter: tabs (Pokémon,
 * items, balls...), then cards with the Pokémon's animated 3D model (or the
 * item, enlarged), its name and its price; select a card, then press Buy.
 *
 * <p>The screen only displays what the server sent ({@link SafariShopPayload})
 * and asks for purchases ({@link SafariShopBuyPayload}); the server decides.
 * After each request the server sends a refresh, which also re-enables the
 * Buy button, so a double click cannot buy twice.
 *
 * <p>A trade whose cost accepts several kinds (any evolution stone, any
 * apricorn) first asks which kind to give when the player carries more than
 * one ({@link Choice}); without it the server would take whichever its tag
 * lists first. CLIENT ONLY.
 */
public class SafariShopScreen extends Screen {

    /**
     * Panel size, in GUI pixels. Fits the smallest common GUI (320×240 at the
     * largest GUI scale). The background texture has exactly this size
     * (tools/gui_assets.py): change both together or it stretches.
     */
    private static final int PANEL_WIDTH = 300;
    private static final int PANEL_HEIGHT = 232;

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
    /**
     * Width of the scroll arrows at the ends of the tab row, shown only when
     * even the names alone do not fit. Narrower is hard to click.
     */
    private static final int TAB_ARROW_WIDTH = 12;
    /**
     * Tighter tab metrics, used when the normal ones do not fit: less
     * padding, a 10 px icon instead of 12, 1 px gaps, and the row may use the
     * whole box width instead of keeping the box padding. Measured on the
     * five default tabs, this keeps icons and names in English, French and
     * German, where the normal metrics overflow by about 35 px. Smaller
     * icons stop reading as items.
     */
    private static final int TAB_PADDING_TIGHT = 2;
    private static final float TAB_ICON_SCALE_TIGHT = 0.625F;
    private static final int TAB_GAP_TIGHT = 1;
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
    /**
     * Stack count of an item card: how far it sticks out right of the icon,
     * and its depth. Item models are drawn up to about z 150 + 16 × scale;
     * the count must be in front of them or the icon hides it.
     */
    private static final float COUNT_OVERHANG = 4.0F;
    private static final float ITEM_COUNT_Z = 250.0F;
    private static final int COUNT_COLOR = 0xFFFFFFFF;
    /** Tab icons are drawn at this scale (16 px items, 12 px on the tab). */
    private static final float TAB_ICON_SCALE = 0.75F;
    /**
     * Cost icons on trade cards: 16 px items drawn at 10 px to sit on the
     * price line, the gap before their count, and the space between two
     * costs. Larger icons push a two-item cost out of the card.
     */
    private static final float COST_ICON_SCALE = 0.625F;
    private static final int COST_ICON_SIZE = 10;
    private static final int COST_ICON_GAP = 1;
    private static final int COST_SPACING = 4;
    /**
     * How long each kind of a tag cost (each apricorn colour...) shows on
     * the card, in ms. Shorter flickers, longer hides that several count.
     */
    private static final long COST_ICON_CYCLE_MS = 1_000L;
    /**
     * Card names wider than the card scroll back and forth, like the lines
     * of the wall screens: speed in GUI pixels per second, and the pause at
     * each end.
     */
    private static final float NAME_SCROLL_SPEED = 20.0F;
    private static final long NAME_SCROLL_PAUSE_MS = 1_200L;
    /** Space kept between a card name and the card's edges. */
    private static final int NAME_MARGIN = 3;
    /** The level badge on Pokémon cards is drawn at this text scale. */
    private static final float BADGE_SCALE = 0.75F;

    /**
     * Colours, ARGB. Safari greens framed with wood and a gold accent, like
     * the gate and the wall screens. Boxes are translucent so the landscape shows
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
     * The "which kind do you give" box: its width (inside the panel, with
     * room for the frame to show), padding, and the cell of each item icon.
     * Narrower cells make the icons touch; wider ones fit fewer per line.
     */
    private static final int CHOICE_WIDTH = PANEL_WIDTH - 40;
    private static final int CHOICE_PADDING = 8;
    private static final int CHOICE_CELL = 20;
    private static final int CHOICE_LINE_GAP = 4;
    private static final int CHOICE_BUTTON_WIDTH = 80;
    /**
     * Depth of the box, above the cards' item models (drawn up to about z 250)
     * and their count labels, so nothing of the cards shows through it.
     */
    private static final float CHOICE_Z = 400.0F;
    private static final int CHOICE_BACKGROUND = 0xF0122A1C;
    private static final int CHOICE_SELECTED = 0x60FFD86B;
    /** GLFW's Escape key: closes the box rather than the whole shop. */
    private static final int KEY_ESCAPE = 256;

    /**
     * One cost of the trade being confirmed, whose kind the player picks.
     *
     * @param costIndex position of the cost in the entry
     * @param cost      the cost
     * @param kinds     the accepted kinds the player carries, with how many of each
     * @param picked    index in kinds of the one given first
     */
    private record Row(int costIndex, SafariShopPayload.Cost cost, List<ItemStack> kinds, int[] picked) {
    }

    /** The trade being confirmed: its entry and the costs with a choice. */
    private record Choice(int entryIndex, List<Row> rows) {
    }

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
    /**
     * First tab shown when the tab row scrolls (position among the tabs that
     * hold entries, not an index in data.tabs()). Kept so the row does not
     * jump back when another tab opens.
     */
    private int tabScroll;

    private int left;
    private int top;
    private Button previousButton;
    private Button nextButton;
    private Button buyButton;
    /** The box's buttons: clickable children, drawn by hand above the box (see {@link #render}). */
    private Button confirmButton;
    private Button cancelButton;
    /** The trade waiting for the player to pick what to give, or null. */
    private Choice choice;

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
        // New random Pokémon: the same index may now be another species, so
        // the selection is dropped rather than silently moved to it.
        boolean redrawn = payload.rotation() != data.rotation();
        data = payload;
        awaitingServer = false;
        // The entry may have changed under the box: the player confirms afresh.
        choice = null;
        if (tab >= data.tabs().size() || entriesOf(tab).isEmpty()) {
            tab = firstTabWithEntries();
        }
        visible = entriesOf(tab);
        if (redrawn || !visible.contains(selected)) {
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
        // Children only, not renderables: drawn in render(), above the box.
        confirmButton = addWidget(Button.builder(tr("shop.choose.confirm"), button -> confirmChoice())
                .bounds(0, 0, CHOICE_BUTTON_WIDTH, BUTTON_HEIGHT).build());
        cancelButton = addWidget(Button.builder(tr("shop.choose.cancel"), button -> closeChoice())
                .bounds(0, 0, CHOICE_BUTTON_WIDTH, BUTTON_HEIGHT).build());
        refreshButtons();
    }

    /** Enables the buttons that make sense now and labels the Buy button with the price. */
    private void refreshButtons() {
        if (buyButton == null) {
            return;
        }
        boolean choosing = choice != null;
        // Hidden, not only greyed: a hidden button ignores clicks, so the box is modal.
        previousButton.visible = !choosing;
        nextButton.visible = !choosing;
        buyButton.visible = !choosing;
        confirmButton.visible = choosing;
        cancelButton.visible = choosing;
        if (choosing) {
            layoutChoiceButtons();
        }
        previousButton.active = page > 0;
        nextButton.active = page + 1 < pageCount();
        SafariShopPayload.Entry entry = selectedEntry();
        if (entry == null) {
            buyButton.setMessage(tr("shop.select"));
            buyButton.active = false;
        } else {
            buyButton.setMessage(buyLabel(entry));
            buyButton.active = !awaitingServer && entry.kind() != SafariShopPayload.KIND_UNAVAILABLE
                    && entry.remaining() != 0 && data.points() >= entry.price() && hasCostItems(entry)
                    && !alreadyCarried(entry);
        }
    }

    /** "Buy (200)", "Trade", "Take" for a free entry, or the wait left when it is not ready yet. */
    private static Component buyLabel(SafariShopPayload.Entry entry) {
        if (entry.waitSeconds() > 0) {
            return tr("shop.wait_badge", waitText(entry.waitSeconds()));
        }
        if (!entry.cost().isEmpty()) {
            return tr("shop.trade");
        }
        return isFree(entry) ? tr("shop.take") : tr("shop.buy", SafariPointsIcon.amount(entry.price()));
    }

    /** Whether an entry costs nothing at all: no points, no items. */
    private static boolean isFree(SafariShopPayload.Entry entry) {
        return entry.price() == 0 && entry.cost().isEmpty();
    }

    /**
     * "5 h 12" or "38 min", rounded up to the minute, as the server words the
     * same wait. Shown as sent when the screen opened: it does not count down
     * while the screen stays open, and refreshes with the next purchase or
     * reopening.
     */
    private static Component waitText(int seconds) {
        int minutes = Math.max(1, (seconds + 59) / 60);
        if (minutes < 60) {
            return tr("shop.wait_minutes", minutes);
        }
        return tr("shop.wait_hours", minutes / 60, String.format("%02d", minutes % 60));
    }

    /** Moves {@code delta} pages forward or back within the open tab, clamped to its pages. */
    private void turnPage(int delta) {
        page = Math.max(0, Math.min(pageCount() - 1, page + delta));
        refreshButtons();
    }

    /**
     * Opens the previous or next tab holding entries, which also scrolls the
     * tab row to it: the arrows browse the tabs rather than only slide the
     * row, so a click always shows something new. Stops at both ends.
     */
    private void scrollTabs(int direction) {
        List<Integer> shown = new ArrayList<>();
        for (int i = 0; i < data.tabs().size(); i++) {
            if (!entriesOf(i).isEmpty()) {
                shown.add(i);
            }
        }
        if (shown.isEmpty()) {
            return;
        }
        int position = Math.max(0, shown.indexOf(tab));
        int next = Math.max(0, Math.min(shown.size() - 1, position + direction));
        // No scroll bookkeeping here: tabLayout slides the window to keep the open tab in view.
        openTab(shown.get(next));
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

    /**
     * Buys the selected entry, or first opens the box asking which kind to
     * give when one of its costs accepts several kinds the player carries.
     */
    private void buySelected() {
        SafariShopPayload.Entry entry = selectedEntry();
        if (entry == null || awaitingServer) {
            return;
        }
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < entry.cost().size(); i++) {
            SafariShopPayload.Cost cost = entry.cost().get(i);
            List<ItemStack> kinds = carriedKinds(cost);
            if (kinds.size() > 1) {
                // The first kind is what the server takes first anyway: picked by default.
                rows.add(new Row(i, cost, kinds, new int[]{0}));
            }
        }
        if (rows.isEmpty()) {
            send(entry, Collections.nCopies(entry.cost().size(), ""));
            return;
        }
        choice = new Choice(selected, rows);
        refreshButtons();
    }

    /** Sends the purchase with the kinds picked in the box. */
    private void confirmChoice() {
        SafariShopPayload.Entry entry = selectedEntry();
        Choice pending = choice;
        choice = null;
        if (entry == null || pending == null || pending.entryIndex() != selected) {
            refreshButtons();
            return;
        }
        List<String> preferred = new ArrayList<>(Collections.nCopies(entry.cost().size(), ""));
        for (Row row : pending.rows()) {
            ItemStack kind = row.kinds().get(row.picked()[0]);
            preferred.set(row.costIndex(), BuiltInRegistries.ITEM.getKey(kind.getItem()).toString());
        }
        send(entry, preferred);
    }

    /** Closes the box without buying. */
    private void closeChoice() {
        choice = null;
        refreshButtons();
    }

    /** Sends the purchase request; the server's refresh re-enables the button. */
    private void send(SafariShopPayload.Entry entry, List<String> preferred) {
        awaitingServer = true;
        refreshButtons();
        SafariClientNetwork.sendToServer(new SafariShopBuyPayload(selected, entry.price(), data.rotation(),
                List.copyOf(preferred)));
    }

    /**
     * The kinds of a cost the local player carries, in the cost's order, as
     * stacks holding how many they carry (for the icon's count).
     */
    private List<ItemStack> carriedKinds(SafariShopPayload.Cost cost) {
        List<ItemStack> kinds = new ArrayList<>();
        if (minecraft == null || minecraft.player == null) {
            return kinds;
        }
        for (ItemStack item : cost.items()) {
            int count = minecraft.player.getInventory().countItem(item.getItem());
            if (count > 0) {
                kinds.add(item.copyWithCount(count));
            }
        }
        return kinds;
    }

    /** Selects the card or opens the tab under the mouse; otherwise lets the buttons handle the click. */
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (choice != null) {
            // Modal: only the box's icons and buttons answer.
            if (pickAt(mouseX, mouseY)) {
                return true;
            }
            return super.mouseClicked(mouseX, mouseY, button);
        }
        int tabIndex = tabAt(mouseX, mouseY);
        if (tabIndex == ARROW_BACK || tabIndex == ARROW_FORWARD) {
            scrollTabs(tabIndex == ARROW_BACK ? -1 : 1);
            return true;
        }
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

    /** Escape closes the box first, then the shop. */
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (choice != null && keyCode == KEY_ESCAPE) {
            closeChoice();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    /** Draws the shop, then the box above everything when it is open. */
    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        if (choice != null) {
            drawChoice(graphics, mouseX, mouseY, partialTick);
        }
    }

    /** Where the box's parts stand on screen; shared by drawing and clicks. */
    private record ChoiceLayout(int x, int y, int height, int columns, List<Integer> rowTops, int hintY,
                                int buttonsY) {
    }

    /** Lays the box out, centred on the panel, as tall as its rows need. */
    private ChoiceLayout choiceLayout() {
        int columns = Math.max(1, (CHOICE_WIDTH - 2 * CHOICE_PADDING) / CHOICE_CELL);
        int y = CHOICE_PADDING + font.lineHeight + CHOICE_LINE_GAP;
        List<Integer> rowTops = new ArrayList<>();
        boolean hint = false;
        for (Row row : choice.rows()) {
            rowTops.add(y);
            int lines = (row.kinds().size() + columns - 1) / columns;
            y += font.lineHeight + 2 + lines * CHOICE_CELL + CHOICE_LINE_GAP;
            hint |= row.cost().count() > 1;
        }
        int hintY = y;
        if (hint) {
            y += font.wordWrapHeight(tr("shop.choose.first"), CHOICE_WIDTH - 2 * CHOICE_PADDING) + CHOICE_LINE_GAP;
        }
        int buttonsY = y;
        int height = y + BUTTON_HEIGHT + CHOICE_PADDING;
        int x = left + (PANEL_WIDTH - CHOICE_WIDTH) / 2;
        int top = this.top + (PANEL_HEIGHT - height) / 2;
        List<Integer> absolute = new ArrayList<>(rowTops.size());
        for (int rowTop : rowTops) {
            absolute.add(top + rowTop);
        }
        return new ChoiceLayout(x, top, height, columns, absolute, hint ? top + hintY : -1, top + buttonsY);
    }

    /** Places the box's two buttons at its bottom, side by side. */
    private void layoutChoiceButtons() {
        ChoiceLayout layout = choiceLayout();
        int gap = CHOICE_PADDING;
        int start = layout.x() + (CHOICE_WIDTH - 2 * CHOICE_BUTTON_WIDTH - gap) / 2;
        confirmButton.setPosition(start, layout.buttonsY());
        cancelButton.setPosition(start + CHOICE_BUTTON_WIDTH + gap, layout.buttonsY());
    }

    /** The top-left corner of a kind's icon cell. */
    private static int[] cellOf(ChoiceLayout layout, int rowTop, int kind, int lineHeight) {
        int x = layout.x() + CHOICE_PADDING + (kind % layout.columns()) * CHOICE_CELL;
        int y = rowTop + lineHeight + 2 + (kind / layout.columns()) * CHOICE_CELL;
        return new int[]{x, y};
    }

    /**
     * The box: a title, then for each cost its name and the kinds carried,
     * the picked one framed in gold, a hint when a kind may not cover the
     * whole count, and the two buttons. Hovering an icon names the item.
     */
    private void drawChoice(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        ChoiceLayout layout = choiceLayout();
        int x0 = layout.x();
        int y0 = layout.y();
        int x1 = x0 + CHOICE_WIDTH;
        int y1 = y0 + layout.height();
        graphics.pose().pushPose();
        graphics.pose().translate(0.0F, 0.0F, CHOICE_Z);
        // The shop dimmed behind the box, so it reads as a question.
        graphics.fill(left, top, left + PANEL_WIDTH, top + PANEL_HEIGHT, 0x80000000);
        graphics.fill(x0, y0, x1, y1, CHOICE_BACKGROUND);
        frame(graphics, x0, y0, x1, y1, GOLD);
        Component heading = tr("shop.choose.title");
        graphics.drawString(font, heading, x0 + (CHOICE_WIDTH - font.width(heading)) / 2, y0 + CHOICE_PADDING,
                TEXT_COLOR);
        ItemStack hovered = ItemStack.EMPTY;
        for (int r = 0; r < choice.rows().size(); r++) {
            Row row = choice.rows().get(r);
            int rowTop = layout.rowTops().get(r);
            Component label = Component.literal(row.cost().count() + " × ").append(row.cost().name());
            graphics.drawString(font, label, x0 + CHOICE_PADDING, rowTop, PRICE_COLOR);
            for (int k = 0; k < row.kinds().size(); k++) {
                int[] cell = cellOf(layout, rowTop, k, font.lineHeight);
                if (k == row.picked()[0]) {
                    graphics.fill(cell[0], cell[1], cell[0] + CHOICE_CELL - 1, cell[1] + CHOICE_CELL - 1,
                            CHOICE_SELECTED);
                    frame(graphics, cell[0], cell[1], cell[0] + CHOICE_CELL - 1, cell[1] + CHOICE_CELL - 1, GOLD);
                }
                ItemStack kind = row.kinds().get(k);
                int iconX = cell[0] + (CHOICE_CELL - 1 - ITEM_ICON_SIZE) / 2;
                int iconY = cell[1] + (CHOICE_CELL - 1 - ITEM_ICON_SIZE) / 2;
                graphics.renderItem(kind, iconX, iconY);
                graphics.renderItemDecorations(font, kind, iconX, iconY);
                if (mouseX >= cell[0] && mouseX < cell[0] + CHOICE_CELL && mouseY >= cell[1]
                        && mouseY < cell[1] + CHOICE_CELL) {
                    hovered = kind;
                }
            }
        }
        if (layout.hintY() >= 0) {
            graphics.drawWordWrap(font, tr("shop.choose.first"), x0 + CHOICE_PADDING, layout.hintY(),
                    CHOICE_WIDTH - 2 * CHOICE_PADDING, MUTED_COLOR);
        }
        confirmButton.render(graphics, mouseX, mouseY, partialTick);
        cancelButton.render(graphics, mouseX, mouseY, partialTick);
        if (!hovered.isEmpty()) {
            graphics.renderTooltip(font, hovered, mouseX, mouseY);
        }
        graphics.pose().popPose();
    }

    /** Picks the kind under the mouse, if any. */
    private boolean pickAt(double mouseX, double mouseY) {
        ChoiceLayout layout = choiceLayout();
        for (int r = 0; r < choice.rows().size(); r++) {
            Row row = choice.rows().get(r);
            for (int k = 0; k < row.kinds().size(); k++) {
                int[] cell = cellOf(layout, layout.rowTops().get(r), k, font.lineHeight);
                if (mouseX >= cell[0] && mouseX < cell[0] + CHOICE_CELL && mouseY >= cell[1]
                        && mouseY < cell[1] + CHOICE_CELL) {
                    row.picked()[0] = k;
                    return true;
                }
            }
        }
        return false;
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
        // The landscape of the time of day, 1:1: the panel has the texture's exact size.
        SafariLandscapes.Scene scene = SafariLandscapes.SHOP;
        graphics.blit(scene.forTime(minecraft == null ? null : minecraft.level), left, top, 0.0F, 0.0F,
                PANEL_WIDTH, PANEL_HEIGHT, scene.width(), scene.height());
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
     * How a row of tabs is drawn: padding inside a tab, icon scale (0 for
     * no icon), gap after the icon and between tabs, and where the row
     * starts and how wide it may be.
     */
    private record TabMetrics(int padding, float iconScale, int gap, int start, int room) {

        /** Whether tabs show their icon. */
        boolean icons() {
            return iconScale > 0.0F;
        }
    }

    /**
     * Where one tab of the row sits, or a scroll arrow (index
     * {@link #ARROW_BACK} / {@link #ARROW_FORWARD}), with the metrics it is
     * drawn with.
     */
    private record TabSlot(int index, int x, int width, TabMetrics metrics) {
    }

    /** Pseudo tab indices of the scroll arrows in a {@link TabSlot}. */
    private static final int ARROW_BACK = -2;
    private static final int ARROW_FORWARD = -3;

    /**
     * Lays the tab row out, for drawing and for clicks alike, so the two
     * can never disagree. Names always stay readable, icons stay as long as
     * possible; four steps, each only when the previous one does not fit:
     * normal tabs with icon and name; tight tabs with icon and name (see
     * {@link #TAB_PADDING_TIGHT}); tight tabs with their name alone; then the
     * row scrolls, names alone, with arrows at both ends, keeping the open
     * tab in view. No tab is ever left out of the row.
     */
    private List<TabSlot> tabLayout() {
        List<Integer> shown = new ArrayList<>();
        for (int i = 0; i < data.tabs().size(); i++) {
            if (!entriesOf(i).isEmpty()) {
                shown.add(i);
            }
        }
        TabMetrics normal = new TabMetrics(TAB_PADDING, TAB_ICON_SCALE, TAB_GAP,
                left + CONTENT_LEFT + CONTENT_PADDING, CONTENT_WIDTH - 2 * CONTENT_PADDING);
        // One pixel in from each side of the box, clear of its wooden border.
        int tightStart = left + CONTENT_LEFT + 1;
        int tightRoom = CONTENT_WIDTH - 2;
        TabMetrics tight = new TabMetrics(TAB_PADDING_TIGHT, TAB_ICON_SCALE_TIGHT, TAB_GAP_TIGHT, tightStart, tightRoom);
        TabMetrics namesOnly = new TabMetrics(TAB_PADDING_TIGHT, 0.0F, TAB_GAP_TIGHT, tightStart, tightRoom);
        for (TabMetrics metrics : new TabMetrics[]{normal, tight, namesOnly}) {
            if (rowWidth(shown, metrics) <= metrics.room()) {
                return place(shown, metrics.start(), metrics);
            }
        }
        // Scrolling: the window of tabs between the two arrows.
        int windowRoom = tightRoom - 2 * (TAB_ARROW_WIDTH + TAB_GAP_TIGHT);
        int openPosition = Math.max(0, shown.indexOf(tab));
        tabScroll = Math.max(0, Math.min(tabScroll, shown.size() - 1));
        if (openPosition < tabScroll) {
            tabScroll = openPosition;
        }
        // Moves the window right until the open tab fits in it.
        while (tabScroll < openPosition
                && rowWidth(shown.subList(tabScroll, openPosition + 1), namesOnly) > windowRoom) {
            tabScroll++;
        }
        int end = tabScroll;
        while (end < shown.size() && rowWidth(shown.subList(tabScroll, end + 1), namesOnly) <= windowRoom) {
            end++;
        }
        List<TabSlot> slots = new ArrayList<>();
        slots.add(new TabSlot(ARROW_BACK, tightStart, TAB_ARROW_WIDTH, namesOnly));
        slots.addAll(place(shown.subList(tabScroll, Math.max(end, tabScroll + 1)),
                tightStart + TAB_ARROW_WIDTH + TAB_GAP_TIGHT, namesOnly));
        slots.add(new TabSlot(ARROW_FORWARD, tightStart + tightRoom - TAB_ARROW_WIDTH, TAB_ARROW_WIDTH, namesOnly));
        return slots;
    }

    /** Places tabs left to right from {@code x}. */
    private List<TabSlot> place(List<Integer> indices, int x, TabMetrics metrics) {
        List<TabSlot> slots = new ArrayList<>();
        for (int i : indices) {
            int w = tabWidth(data.tabs().get(i), metrics);
            slots.add(new TabSlot(i, x, w, metrics));
            x += w + metrics.gap();
        }
        return slots;
    }

    /** Total width of a row of tabs, gaps included. */
    private int rowWidth(List<Integer> indices, TabMetrics metrics) {
        int total = 0;
        for (int i : indices) {
            total += tabWidth(data.tabs().get(i), metrics) + metrics.gap();
        }
        return Math.max(0, total - metrics.gap());
    }

    /**
     * The tab row, as folder tabs on the content box: each tab with an icon
     * then its name (the icon gives way only when the row is very crowded,
     * see {@link #tabLayout}). The open tab is taller, gold-edged, and melts
     * into the box (same colour, no line between them); the others sit lower,
     * darker.
     */
    private void drawTabs(GuiGraphics graphics, int mouseX, int mouseY) {
        int bottom = top + CONTENT_TOP;
        int openLeft = -1;
        int openRight = -1;
        for (TabSlot slot : tabLayout()) {
            int x = slot.x();
            int w = slot.width();
            boolean open = slot.index() == tab;
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
            int middle = y + (bottom - y) / 2;
            if (slot.index() < 0) {
                String glyph = slot.index() == ARROW_BACK ? "<" : ">";
                graphics.drawString(font, glyph, x + (w - font.width(glyph)) / 2, middle - font.lineHeight / 2 + 1,
                        hovered ? TEXT_COLOR : MUTED_COLOR);
                continue;
            }
            SafariShopPayload.Tab shown = data.tabs().get(slot.index());
            TabMetrics metrics = slot.metrics();
            int textX = x + metrics.padding();
            if (metrics.icons() && !shown.icon().isEmpty()) {
                float scale = metrics.iconScale();
                graphics.pose().pushPose();
                graphics.pose().translate(x + metrics.padding(), middle - ITEM_ICON_SIZE * scale / 2.0F, 0.0F);
                graphics.pose().scale(scale, scale, 1.0F);
                graphics.renderFakeItem(shown.icon(), 0, 0);
                graphics.pose().popPose();
                textX += (int) (ITEM_ICON_SIZE * scale) + metrics.gap();
            }
            graphics.drawString(font, tabName(shown), textX, middle - font.lineHeight / 2 + 1,
                    open ? GOLD : hovered ? TEXT_COLOR : MUTED_COLOR);
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
            // Level as a small badge in the corner, so the name line holds the
            // whole species name rather than "Name (Lv. 12)" cut short.
            graphics.pose().pushPose();
            graphics.pose().translate(x + 3, y + 3, 200.0F);
            graphics.pose().scale(BADGE_SCALE, BADGE_SCALE, 1.0F);
            graphics.drawString(font, tr("shop.level_badge", entry.level()), 0, 0, MUTED_COLOR);
            graphics.pose().popPose();
        }

        drawName(graphics, name(entry), x, pictureBottom + 2, entry.shiny() ? GOLD : TEXT_COLOR);
        int priceY = pictureBottom + 2 + font.lineHeight + 2;
        if (isFree(entry)) {
            Component free = tr("shop.free");
            graphics.drawString(font, free, x + (CARD_WIDTH - font.width(free)) / 2, priceY, PRICE_COLOR);
        } else if (entry.cost().isEmpty()) {
            Component price = tr("shop.price_short", SafariPointsIcon.amount(entry.price()));
            graphics.drawString(font, price, x + (CARD_WIDTH - font.width(price)) / 2, priceY,
                    data.points() >= entry.price() ? PRICE_COLOR : PRICE_TOO_HIGH);
        } else {
            drawCost(graphics, entry, x, priceY);
        }
        if (alreadyCarried(entry)) {
            drawBadge(graphics, tr("shop.carried"), MUTED_COLOR, x1, y);
        } else if (entry.waitSeconds() > 0) {
            drawBadge(graphics, tr("shop.wait_badge", waitText(entry.waitSeconds())), PRICE_TOO_HIGH, x1, y);
        } else if (entry.remaining() != SafariShopPayload.UNLIMITED) {
            drawRemaining(graphics, entry.remaining(), x1, y);
        }
    }

    /**
     * A card's name, centred when it fits; otherwise clipped to the card and
     * scrolled back and forth with a pause at each end, so the whole name
     * can be read.
     */
    private void drawName(GuiGraphics graphics, Component name, int x, int y, int colour) {
        int available = CARD_WIDTH - 2 * NAME_MARGIN;
        int width = font.width(name);
        if (width <= available) {
            graphics.drawString(font, name, x + (CARD_WIDTH - width) / 2, y, colour);
            return;
        }
        int overflow = width - available;
        long travel = (long) (overflow / NAME_SCROLL_SPEED * 1000.0F);
        long cycle = 2L * (travel + NAME_SCROLL_PAUSE_MS);
        long t = System.currentTimeMillis() % cycle;
        float offset;
        if (t < NAME_SCROLL_PAUSE_MS) {
            offset = 0.0F;
        } else if (t < NAME_SCROLL_PAUSE_MS + travel) {
            offset = overflow * (t - NAME_SCROLL_PAUSE_MS) / (float) travel;
        } else if (t < 2L * NAME_SCROLL_PAUSE_MS + travel) {
            offset = overflow;
        } else {
            offset = overflow * (1.0F - (t - 2L * NAME_SCROLL_PAUSE_MS - travel) / (float) travel);
        }
        int left = x + NAME_MARGIN;
        graphics.enableScissor(left, y - 1, left + available, y + font.lineHeight);
        graphics.pose().pushPose();
        graphics.pose().translate(left - offset, y, 0.0F);
        graphics.drawString(font, name, 0, 0, colour);
        graphics.pose().popPose();
        graphics.disableScissor();
    }

    /**
     * The items a trade asks for, as small icons each followed by its count,
     * centred on the price line: red counts for what the player lacks.
     * Points, when the trade also costs some, come last.
     */
    private void drawCost(GuiGraphics graphics, SafariShopPayload.Entry entry, int x, int y) {
        List<Component> counts = new ArrayList<>();
        int width = 0;
        for (SafariShopPayload.Cost cost : entry.cost()) {
            Component count = Component.literal("×" + cost.count());
            counts.add(count);
            width += COST_ICON_SIZE + COST_ICON_GAP + font.width(count) + COST_SPACING;
        }
        Component points = entry.price() > 0 ? tr("shop.price_short", SafariPointsIcon.amount(entry.price())) : null;
        width += points == null ? -COST_SPACING : font.width(points);
        int cursor = x + (CARD_WIDTH - width) / 2;
        for (int i = 0; i < entry.cost().size(); i++) {
            SafariShopPayload.Cost cost = entry.cost().get(i);
            graphics.pose().pushPose();
            // Icon centred on the text line, which is a little shorter than the icon.
            graphics.pose().translate(cursor, y + (font.lineHeight - COST_ICON_SIZE) / 2.0F - 1.0F, 0.0F);
            graphics.pose().scale(COST_ICON_SCALE, COST_ICON_SCALE, 1.0F);
            graphics.renderFakeItem(shownItem(cost), 0, 0);
            graphics.pose().popPose();
            cursor += COST_ICON_SIZE + COST_ICON_GAP;
            boolean enough = carried(cost) >= cost.count();
            graphics.drawString(font, counts.get(i), cursor, y, enough ? PRICE_COLOR : PRICE_TOO_HIGH);
            cursor += font.width(counts.get(i)) + COST_SPACING;
        }
        if (points != null) {
            graphics.drawString(font, points, cursor, y, data.points() >= entry.price() ? PRICE_COLOR : PRICE_TOO_HIGH);
        }
    }

    /** "2 left" (or "Sold out") in the top right corner of a limited card, small like the level badge. */
    private void drawRemaining(GuiGraphics graphics, int remaining, int right, int y) {
        Component text = remaining > 0 ? tr("shop.remaining", remaining) : tr("shop.sold_out");
        drawBadge(graphics, text, remaining > 0 ? MUTED_COLOR : PRICE_TOO_HIGH, right, y);
    }

    /** A small text in the top right corner of a card, like the level badge. */
    private void drawBadge(GuiGraphics graphics, Component text, int colour, int right, int y) {
        graphics.pose().pushPose();
        graphics.pose().translate(right - 3 - font.width(text) * BADGE_SCALE, y + 3, 200.0F);
        graphics.pose().scale(BADGE_SCALE, BADGE_SCALE, 1.0F);
        graphics.drawString(font, text, 0, 0, colour);
        graphics.pose().popPose();
    }

    /**
     * The icon of a cost: its item, or for a tag each accepted item in turn,
     * so the card shows what kinds count.
     */
    private static ItemStack shownItem(SafariShopPayload.Cost cost) {
        int index = (int) ((System.currentTimeMillis() / COST_ICON_CYCLE_MS) % cost.items().size());
        return cost.items().get(index);
    }

    /**
     * Whether the entry gives the Escape Rope and the player already carries
     * one: the server refuses a second, so the card says so and the button
     * stays grey. Read from the client's own inventory, so it is right as
     * soon as the rope is picked up, without a refresh.
     */
    private boolean alreadyCarried(SafariShopPayload.Entry entry) {
        return entry.kind() == SafariShopPayload.KIND_ITEM && entry.item().getItem() instanceof SafariEscapeRopeItem
                && minecraft != null && minecraft.player != null
                && minecraft.player.getInventory().countItem(entry.item().getItem()) > 0;
    }

    /**
     * How many of a cost's items the local player carries, all kinds
     * together. A client-side guess for the display; the server counts again.
     */
    private int carried(SafariShopPayload.Cost cost) {
        if (minecraft == null || minecraft.player == null) {
            return 0;
        }
        int total = 0;
        for (ItemStack item : cost.items()) {
            total += minecraft.player.getInventory().countItem(item.getItem());
        }
        return total;
    }

    /** Whether the local player carries every item a trade asks for. */
    private boolean hasCostItems(SafariShopPayload.Entry entry) {
        for (SafariShopPayload.Cost cost : entry.cost()) {
            if (carried(cost) < cost.count()) {
                return false;
            }
        }
        return true;
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
        graphics.pose().popPose();
        if (stack.getCount() > 1) {
            // Not renderItemDecorations: under the icon's scale the count
            // would grow with it and cover half the item. Drawn at text size
            // instead, tucked into the icon's bottom-right corner.
            String count = "×" + stack.getCount();
            float right = x + (CARD_WIDTH + size) / 2.0F + COUNT_OVERHANG;
            float bottom = y + (PICTURE_HEIGHT + size) / 2.0F;
            graphics.pose().pushPose();
            graphics.pose().translate(right - font.width(count), bottom - font.lineHeight, ITEM_COUNT_Z);
            graphics.drawString(font, count, 0, 0, COUNT_COLOR, true);
            graphics.pose().popPose();
        }
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

    /** Width of a tab with the given metrics: its name, with its icon in front when shown. */
    private int tabWidth(SafariShopPayload.Tab shown, TabMetrics metrics) {
        int iconWidth = metrics.icons() && !shown.icon().isEmpty()
                ? (int) (ITEM_ICON_SIZE * metrics.iconScale()) + metrics.gap() : 0;
        return metrics.padding() * 2 + iconWidth + font.width(tabName(shown));
    }

    /**
     * Index of the tab under the point, {@link #ARROW_BACK} or
     * {@link #ARROW_FORWARD} for a scroll arrow, or -1. Uses the same layout
     * as {@link #drawTabs}.
     */
    private int tabAt(double mouseX, double mouseY) {
        int bottom = top + CONTENT_TOP;
        if (mouseY < bottom - TAB_HEIGHT || mouseY >= bottom) {
            return -1;
        }
        for (TabSlot slot : tabLayout()) {
            if (mouseX >= slot.x() && mouseX < slot.x() + slot.width()) {
                return slot.index();
            }
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

    /** Pages the open tab needs, at {@link #PER_PAGE} cards each. */
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
