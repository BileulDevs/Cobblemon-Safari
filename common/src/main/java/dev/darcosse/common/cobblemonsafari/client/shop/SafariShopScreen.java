package dev.darcosse.common.cobblemonsafari.client.shop;

import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import dev.darcosse.common.cobblemonsafari.network.SafariClientNetwork;
import dev.darcosse.common.cobblemonsafari.network.SafariShopBuyPayload;
import dev.darcosse.common.cobblemonsafari.network.SafariShopPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;

/**
 * The warden's shop, in the style of a Game Corner prize counter: cards with
 * the Pokémon's turning 3D model (or the item, enlarged), its name and its
 * price; select a card, then press Buy.
 *
 * <p>The screen only displays what the server sent ({@link SafariShopPayload})
 * and asks for purchases ({@link SafariShopBuyPayload}); the server decides.
 * After each request the server sends a refresh, which also re-enables the
 * Buy button, so a double click cannot buy twice. CLIENT ONLY.
 */
public class SafariShopScreen extends Screen {

    /** Panel size, in GUI pixels. Fits the smallest common GUI (320×240 at the largest GUI scale). */
    private static final int PANEL_WIDTH = 300;
    private static final int PANEL_HEIGHT = 214;

    private static final int COLUMNS = 4;
    private static final int ROWS = 2;
    private static final int PER_PAGE = COLUMNS * ROWS;

    private static final int CARD_WIDTH = 70;
    private static final int CARD_HEIGHT = 82;
    private static final int CARD_GAP = 4;
    /** Height of the model or item area at the top of a card; the rest holds name and price. */
    private static final int PICTURE_HEIGHT = 56;

    private static final int HEADER_HEIGHT = 22;
    private static final int FOOTER_HEIGHT = 24;
    private static final int BUTTON_HEIGHT = 18;
    private static final int PAGE_BUTTON_WIDTH = 20;
    private static final int BUY_BUTTON_WIDTH = 110;
    private static final int MARGIN = 6;

    /** Items are drawn at this multiple of their 16 px icon. */
    private static final float ITEM_SCALE = 2.5F;
    private static final int ITEM_ICON_SIZE = 16;

    /** Colours, ARGB. Safari greens and a gold accent, like the gate and the board. */
    private static final int PANEL_COLOR = 0xF0163A26;
    private static final int PANEL_BORDER = 0xFF7A5A2E;
    private static final int CARD_COLOR = 0xFF22533A;
    private static final int CARD_HOVER = 0xFF2E6B4B;
    private static final int CARD_SELECTED_BORDER = 0xFFFFD86B;
    private static final int TEXT_COLOR = 0xFFFFFFFF;
    private static final int PRICE_COLOR = 0xFFFFD86B;
    private static final int PRICE_TOO_HIGH = 0xFFFF6B6B;
    private static final int MUTED_COLOR = 0xFFB5C9BA;

    private SafariShopPayload data;
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

    /** Applies a refresh, keeping the page and selection when they still exist. */
    private void update(SafariShopPayload payload) {
        data = payload;
        awaitingServer = false;
        if (selected >= data.entries().size()) {
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
            buyButton.setMessage(tr("shop.buy", entry.price()));
            buyButton.active = !awaitingServer && entry.kind() != SafariShopPayload.KIND_UNAVAILABLE
                    && data.points() >= entry.price();
        }
    }

    private void turnPage(int delta) {
        page = Math.max(0, Math.min(pageCount() - 1, page + delta));
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

    /** Selects the card under the mouse, if any; otherwise lets the buttons handle the click. */
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
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
     * Draws the panel and the cards under the buttons. Vanilla's
     * {@code render} calls this first, then draws the widgets on top.
     */
    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(graphics, mouseX, mouseY, partialTick);
        graphics.fill(left - 1, top - 1, left + PANEL_WIDTH + 1, top + PANEL_HEIGHT + 1, PANEL_BORDER);
        graphics.fill(left, top, left + PANEL_WIDTH, top + PANEL_HEIGHT, PANEL_COLOR);
        graphics.drawString(font, title, left + MARGIN, top + (HEADER_HEIGHT - font.lineHeight) / 2, TEXT_COLOR);
        Component points = tr("shop.points", data.points());
        graphics.drawString(font, points, left + PANEL_WIDTH - MARGIN - font.width(points),
                top + (HEADER_HEIGHT - font.lineHeight) / 2, PRICE_COLOR);

        List<SafariShopPayload.Entry> entries = data.entries();
        if (entries.isEmpty()) {
            Component empty = tr("shop.empty");
            graphics.drawString(font, empty, left + (PANEL_WIDTH - font.width(empty)) / 2,
                    top + PANEL_HEIGHT / 2, MUTED_COLOR);
        }
        for (int slot = 0; slot < PER_PAGE; slot++) {
            int index = page * PER_PAGE + slot;
            if (index >= entries.size()) {
                break;
            }
            int x = cardX(slot);
            int y = cardY(slot);
            boolean hovered = mouseX >= x && mouseX < x + CARD_WIDTH && mouseY >= y && mouseY < y + CARD_HEIGHT;
            drawCard(graphics, entries.get(index), index, x, y, hovered, partialTick);
        }

        Component pageLabel = Component.literal((page + 1) + " / " + Math.max(1, pageCount()));
        int labelCenter = left + MARGIN + PAGE_BUTTON_WIDTH + 22;
        graphics.drawString(font, pageLabel, labelCenter - font.width(pageLabel) / 2,
                top + PANEL_HEIGHT - FOOTER_HEIGHT / 2 - font.lineHeight / 2, MUTED_COLOR);
    }

    /** One card: frame, picture, name, price. */
    private void drawCard(GuiGraphics graphics, SafariShopPayload.Entry entry, int index, int x, int y,
                          boolean hovered, float partialTick) {
        if (index == selected) {
            graphics.fill(x - 1, y - 1, x + CARD_WIDTH + 1, y + CARD_HEIGHT + 1, CARD_SELECTED_BORDER);
        }
        graphics.fill(x, y, x + CARD_WIDTH, y + CARD_HEIGHT, hovered ? CARD_HOVER : CARD_COLOR);

        // Clipped: some models are wider or taller than their profile box.
        graphics.enableScissor(x + 1, y + 1, x + CARD_WIDTH - 1, y + PICTURE_HEIGHT);
        switch (entry.kind()) {
            case SafariShopPayload.KIND_POKEMON -> {
                // Text and fills are batched; flushed first so the model is not drawn under them.
                graphics.flush();
                ShopPokemonModels.draw(graphics, Integer.toString(index), entry.species(), entry.aspects(),
                        x + (CARD_WIDTH - PICTURE_HEIGHT) / 2, y + 2, PICTURE_HEIGHT - 2, partialTick);
            }
            case SafariShopPayload.KIND_ITEM -> drawBigItem(graphics, entry.item(), x, y);
            default -> drawBigItem(graphics, new ItemStack(Items.BARRIER), x, y);
        }
        graphics.disableScissor();

        Component name = name(entry);
        String shown = font.plainSubstrByWidth(name.getString(), CARD_WIDTH - 4);
        graphics.drawString(font, shown, x + (CARD_WIDTH - font.width(shown)) / 2, y + PICTURE_HEIGHT + 3,
                entry.shiny() ? PRICE_COLOR : TEXT_COLOR);
        Component price = tr("shop.price_short", entry.price());
        graphics.drawString(font, price, x + (CARD_WIDTH - font.width(price)) / 2,
                y + PICTURE_HEIGHT + 3 + font.lineHeight + 2,
                data.points() >= entry.price() ? PRICE_COLOR : PRICE_TOO_HIGH);
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

    /** What a card is called: species and level, item name and count, or "unavailable". */
    private static Component name(SafariShopPayload.Entry entry) {
        return switch (entry.kind()) {
            case SafariShopPayload.KIND_POKEMON -> tr("shop.pokemon_name",
                    ShopPokemonModels.displayName(entry.species()), entry.level());
            case SafariShopPayload.KIND_ITEM -> entry.item().getHoverName();
            default -> tr("shop.unavailable");
        };
    }

    /** Index of the entry whose card is under the point, or -1. */
    private int cardAt(double mouseX, double mouseY) {
        for (int slot = 0; slot < PER_PAGE; slot++) {
            int index = page * PER_PAGE + slot;
            if (index >= data.entries().size()) {
                break;
            }
            int x = cardX(slot);
            int y = cardY(slot);
            if (mouseX >= x && mouseX < x + CARD_WIDTH && mouseY >= y && mouseY < y + CARD_HEIGHT) {
                return index;
            }
        }
        return -1;
    }

    /** Left edge of the card in a page slot; the grid is centred in the panel. */
    private int cardX(int slot) {
        int gridWidth = COLUMNS * CARD_WIDTH + (COLUMNS - 1) * CARD_GAP;
        return left + (PANEL_WIDTH - gridWidth) / 2 + (slot % COLUMNS) * (CARD_WIDTH + CARD_GAP);
    }

    /** Top edge of the card in a page slot. */
    private int cardY(int slot) {
        return top + HEADER_HEIGHT + (slot / COLUMNS) * (CARD_HEIGHT + CARD_GAP);
    }

    private int pageCount() {
        return (data.entries().size() + PER_PAGE - 1) / PER_PAGE;
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
