package dev.darcosse.common.cobblemonsafari.item;

import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import net.minecraft.resources.ResourceLocation;
import dev.darcosse.common.cobblemonsafari.block.SafariBlocks;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;

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
     * Stack size of tickets. 16 like other paper goods with a use; 64 would
     * make bulk gifting trivial for servers that sell tickets.
     */
    private static final int TICKET_STACK_SIZE = 16;

    private static Item safariTicket;
    private static Item safariBoard;

    private SafariItems() {
    }

    /**
     * Creates every item and hands it to the loader's registrar. Call exactly
     * once, after {@code SafariBlocks.register}: block items wrap existing blocks.
     */
    public static void register(BiConsumer<ResourceLocation, Item> registrar) {
        safariTicket = new SafariTicketItem(new Item.Properties().stacksTo(TICKET_STACK_SIZE).rarity(Rarity.UNCOMMON));
        registrar.accept(SAFARI_TICKET_ID, safariTicket);
        safariBoard = new BlockItem(SafariBlocks.safariBoard(), new Item.Properties());
        registrar.accept(SafariBlocks.SAFARI_BOARD_ID, safariBoard);
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
