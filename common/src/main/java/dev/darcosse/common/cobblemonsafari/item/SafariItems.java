package dev.darcosse.common.cobblemonsafari.item;

import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import net.minecraft.resources.ResourceLocation;
import dev.darcosse.common.cobblemonsafari.block.SafariBlocks;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * Items added by the mod.
 *
 * <p>Instances are created INSIDE {@link #register}, not in static
 * initialisers: an Item claims its registry holder in its constructor, and
 * NeoForge only allows that while the item registry is open, i.e. during its
 * {@code RegisterEvent}. Fabric calls this from its initializer, which is also
 * before the registry freezes.
 */
public final class SafariItems {

    /** Id of the Safari Ticket; also its model and translation path. */
    public static final ResourceLocation SAFARI_TICKET_ID = CobblemonSafari.id("safari_ticket");

    /**
     * Fixed-length tickets: id suffix and duration in seconds. Changing an id
     * deletes the items players already own; changing a duration only affects
     * tickets used afterwards.
     */
    private static final String[] TIMED_TICKET_IDS = {"safari_ticket_15m", "safari_ticket_30m", "safari_ticket_1h"};
    private static final int[] TIMED_TICKET_SECONDS = {15 * 60, 30 * 60, 60 * 60};

    /**
     * Stack size of tickets. 16 like other paper goods with a use; 64 would
     * make bulk gifting trivial for servers that sell tickets.
     */
    private static final int TICKET_STACK_SIZE = 16;

    private static Item safariTicket;
    private static final List<Item> TIMED_TICKETS = new ArrayList<>();
    private static Item safariBoard;
    private static Item safariBoardFrame;
    private static Item safariGate;
    private static Item escapeRope;

    /** Id of the Escape Rope; also its model and translation path. */
    public static final ResourceLocation ESCAPE_ROPE_ID = CobblemonSafari.id("safari_escape_rope");

    private SafariItems() {
    }

    /**
     * Creates every item and hands it to the loader's registrar. Call exactly
     * once, after {@code SafariBlocks.register}: block items wrap existing blocks.
     */
    public static void register(BiConsumer<ResourceLocation, Item> registrar) {
        safariTicket = new SafariTicketItem(new Item.Properties().stacksTo(TICKET_STACK_SIZE).rarity(Rarity.UNCOMMON), 0);
        registrar.accept(SAFARI_TICKET_ID, safariTicket);
        for (int i = 0; i < TIMED_TICKET_IDS.length; i++) {
            Item ticket = new SafariTicketItem(new Item.Properties().stacksTo(TICKET_STACK_SIZE)
                    .rarity(Rarity.UNCOMMON), TIMED_TICKET_SECONDS[i]);
            registrar.accept(CobblemonSafari.id(TIMED_TICKET_IDS[i]), ticket);
            TIMED_TICKETS.add(ticket);
        }
        safariBoard = new BlockItem(SafariBlocks.safariBoard(), new Item.Properties());
        registrar.accept(SafariBlocks.SAFARI_BOARD_ID, safariBoard);
        safariBoardFrame = new BlockItem(SafariBlocks.safariBoardFrame(), new Item.Properties());
        registrar.accept(SafariBlocks.SAFARI_BOARD_FRAME_ID, safariBoardFrame);
        // Epic like other blocks only operators can get (command blocks, structure blocks).
        safariGate = new BlockItem(SafariBlocks.safariGate(), new Item.Properties().rarity(Rarity.EPIC));
        registrar.accept(SafariBlocks.SAFARI_GATE_ID, safariGate);
        // One per player and never used up, so no stacking.
        escapeRope = new SafariEscapeRopeItem(new Item.Properties().stacksTo(1));
        registrar.accept(ESCAPE_ROPE_ID, escapeRope);
    }

    /**
     * The Escape Rope.
     *
     * @throws IllegalStateException before registration
     */
    public static Item escapeRope() {
        if (escapeRope == null) {
            throw new IllegalStateException("SafariItems.register was never called");
        }
        return escapeRope;
    }

    /**
     * The Safari Gate pane, as an item, for operators building their own gates.
     *
     * @throws IllegalStateException before registration
     */
    public static Item safariGate() {
        if (safariGate == null) {
            throw new IllegalStateException("SafariItems.register was never called");
        }
        return safariGate;
    }

    /**
     * The Safari Board Frame, as an item.
     *
     * @throws IllegalStateException before registration
     */
    public static Item safariBoardFrame() {
        if (safariBoardFrame == null) {
            throw new IllegalStateException("SafariItems.register was never called");
        }
        return safariBoardFrame;
    }

    /**
     * The Safari Board, as an item.
     *
     * @throws IllegalStateException before registration
     */
    public static Item safariBoard() {
        if (safariBoard == null) {
            throw new IllegalStateException("SafariItems.register was never called");
        }
        return safariBoard;
    }

    /**
     * The 15 min, 30 min and 1 h tickets, in that order. Empty before registration.
     */
    public static List<Item> timedTickets() {
        return Collections.unmodifiableList(TIMED_TICKETS);
    }

    /**
     * The Safari Ticket.
     *
     * @throws IllegalStateException before registration, which means a loader entry point is wired wrong
     */
    public static Item safariTicket() {
        if (safariTicket == null) {
            throw new IllegalStateException("SafariItems.register was never called");
        }
        return safariTicket;
    }
}
